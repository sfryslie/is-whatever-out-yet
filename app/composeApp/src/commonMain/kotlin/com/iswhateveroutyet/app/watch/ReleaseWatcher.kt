package com.iswhateveroutyet.app.watch

import com.iswhateveroutyet.app.data.WhateverRepository
import com.iswhateveroutyet.app.logic.resolveItem
import com.iswhateveroutyet.app.model.ItemResult
import com.iswhateveroutyet.app.push.PushManager
import com.iswhateveroutyet.app.push.subscribesTo
import com.russhwolf.settings.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Local stand-in for push on platforms without a push service: poll the same data/ JSON the
// checker writes and notify on the transitions the checker pushes on — an item flipping to
// "out" or to the death tone. The pure pieces (computeStates/diffChanges) are shared between
// the desktop in-process watcher below and Android's WorkManager job, which persists its
// baseline across runs.

/** Serializable so Android's WatchWorker can persist the baseline between runs. */
@Serializable
data class OutState(val out: Boolean, val dead: Boolean)

const val NOTIFY_TITLE = "Is whatever out yet?"

internal fun stateKey(item: ItemResult) = "${item.category}/${item.id}"

/**
 * Resolved against [today], so date-driven releases crossing midnight count as a flip even when
 * the JSON itself didn't change between polls.
 */
internal fun outState(item: ItemResult, today: LocalDate) = OutState(
    out = resolveItem(item, today).answer.lowercase().startsWith("yes"),
    dead = item.tone == "death",
)

fun computeStates(items: List<ItemResult>, today: LocalDate): Map<String, OutState> =
    items.associate { stateKey(it) to outState(it, today) }

data class ReleaseChange(val item: ItemResult, val message: String)

/**
 * Items that flipped to "out"/death relative to [baseline] and are covered by a subscribed
 * topic. Items absent from the baseline never notify (mirrors the checker's first-seen rule).
 */
fun diffChanges(
    baseline: Map<String, OutState>,
    items: List<ItemResult>,
    states: Map<String, OutState>,
    topics: Set<String>,
    today: LocalDate,
): List<ReleaseChange> {
    if (topics.isEmpty()) return emptyList()
    return items.mapNotNull { item ->
        val before = baseline[stateKey(item)] ?: return@mapNotNull null
        val now = states[stateKey(item)] ?: return@mapNotNull null
        val flipped = (now.out && !before.out) || (now.dead && !before.dead)
        if (flipped && subscribesTo(item, topics)) {
            ReleaseChange(item, "${item.label}: ${resolveItem(item, today).answer}")
        } else {
            null
        }
    }
}

/** The locally persisted topic set — same "pushTopics" storage the bells write. */
fun watchTopics(settings: Settings): Set<String> =
    settings.getStringOrNull(PushManager.KEY)?.let {
        try {
            Json.decodeFromString<List<String>>(it).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    } ?: emptySet()

/**
 * Desktop's in-process watcher: first fetch is baseline only, so starting the app never
 * notifies. Topics are re-read from [settings] every cycle, so bell toggles apply without
 * restarting.
 */
class ReleaseWatcher(
    private val repo: WhateverRepository,
    private val settings: Settings,
    private val notify: (title: String, message: String) -> Unit,
    private val interval: Duration = 15.minutes,
) {
    suspend fun run() {
        var baseline: Map<String, OutState>? = null
        while (true) {
            try {
                val data = repo.load()
                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                val states = computeStates(data.items, today)
                baseline?.let { base ->
                    diffChanges(base, data.items, states, watchTopics(settings), today)
                        .forEach { notify(NOTIFY_TITLE, it.message) }
                }
                baseline = states
            } catch (e: Exception) {
                // Transient fetch failure — keep the old baseline and retry next cycle.
            }
            delay(interval)
        }
    }
}
