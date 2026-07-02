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
import kotlinx.serialization.json.Json

/**
 * Local stand-in for push on platforms with no push service (desktop): polls the same data/
 * JSON the checker writes and calls [notify] when a *subscribed* item transitions to "out" or
 * to the death tone — the same transitions the checker pushes on. First fetch is baseline only,
 * so starting the app never notifies (mirrors the checker's first-seen rule). Topics are re-read
 * from [settings] every cycle, so bell toggles apply without restarting.
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
                val states = data.items.associate { stateKey(it) to outState(it, today) }
                val topics = currentTopics()
                val prev = baseline
                if (prev != null && topics.isNotEmpty()) {
                    for (item in data.items) {
                        val before = prev[stateKey(item)] ?: continue // first-seen never notifies
                        val now = states.getValue(stateKey(item))
                        val flipped = (now.out && !before.out) || (now.dead && !before.dead)
                        if (flipped && subscribesTo(item, topics)) {
                            notify(
                                "Is whatever out yet?",
                                "${item.label}: ${resolveItem(item, today).answer}",
                            )
                        }
                    }
                }
                baseline = states
            } catch (e: Exception) {
                // Transient fetch failure — keep the old baseline and retry next cycle.
            }
            delay(interval)
        }
    }

    private fun currentTopics(): Set<String> =
        settings.getStringOrNull(PushManager.KEY)?.let {
            try {
                Json.decodeFromString<List<String>>(it).toSet()
            } catch (e: Exception) {
                emptySet()
            }
        } ?: emptySet()
}

data class OutState(val out: Boolean, val dead: Boolean)

internal fun stateKey(item: ItemResult) = "${item.category}/${item.id}"

/**
 * Resolved against [today], so date-driven releases crossing midnight count as a flip even when
 * the JSON itself didn't change between polls.
 */
internal fun outState(item: ItemResult, today: LocalDate) = OutState(
    out = resolveItem(item, today).answer.lowercase().startsWith("yes"),
    dead = item.tone == "death",
)
