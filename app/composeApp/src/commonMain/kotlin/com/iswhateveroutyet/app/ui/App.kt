package com.iswhateveroutyet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.iswhateveroutyet.app.data.WhateverRepository
import com.iswhateveroutyet.app.logic.HIDE_LEVELS
import com.iswhateveroutyet.app.logic.isHiddenByLevel
import com.iswhateveroutyet.app.logic.matchesSearch
import com.iswhateveroutyet.app.logic.resolveItem
import com.iswhateveroutyet.app.logic.upcomingSortKey
import com.iswhateveroutyet.app.model.ItemResult
import com.iswhateveroutyet.app.model.SiteData
import com.iswhateveroutyet.app.push.DisabledPushPlatform
import com.iswhateveroutyet.app.push.PushManager
import com.iswhateveroutyet.app.push.PushPlatform
import com.iswhateveroutyet.app.push.TOPIC_ALL
import com.iswhateveroutyet.app.push.topicCat
import com.iswhateveroutyet.app.push.topicItem
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Ready(val data: SiteData) : LoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(pushPlatform: PushPlatform = DisabledPushPlatform) {
    val settings = remember { Settings() }
    val client = remember { HttpClient() }
    val repo = remember { WhateverRepository(client) }
    val pushManager = remember { PushManager(pushPlatform, settings, client) }
    val scope = rememberCoroutineScope()

    // Theme: stored choice wins; otherwise follow the OS preference (same as the site).
    var themeChoice by remember { mutableStateOf(settings.getStringOrNull("theme")) }
    val systemDark = isSystemInDarkTheme()
    val isLight = themeChoice?.let { it == "light" } ?: !systemDark
    val palette = if (isLight) LightPalette else DarkPalette

    var hideLevel by remember {
        mutableStateOf(settings.getInt("hideOldLevel", 0).coerceIn(0, HIDE_LEVELS.lastIndex))
    }
    var hiddenCats by remember {
        mutableStateOf(
            settings.getStringOrNull("hiddenCats")?.let {
                try {
                    Json.decodeFromString<List<String>>(it).toSet()
                } catch (e: Exception) {
                    emptySet()
                }
            } ?: emptySet<String>()
        )
    }

    var search by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var lastChecked by remember { mutableStateOf<Instant?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load(initial: Boolean) {
        try {
            state = LoadState.Ready(repo.load())
        } catch (e: Exception) {
            if (initial || state !is LoadState.Ready) state = LoadState.Error
        }
        repo.lastChecked()?.let { lastChecked = it }
    }

    LaunchedEffect(Unit) { load(initial = true) }

    fun refresh() {
        scope.launch {
            refreshing = true
            load(initial = false)
            refreshing = false
        }
    }

    val pushTopics by pushManager.topics.collectAsState()

    fun toggleBell(topic: String) {
        scope.launch {
            if (!pushManager.toggle(topic)) {
                snackbar.showSnackbar("Could not update notifications — please try again.")
            }
        }
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = if (isLight) lightColorScheme() else darkColorScheme()) {
            val font = plexMono()
            Surface(color = palette.bg, contentColor = palette.text, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh) {
                        ContentGrid(
                            state = state,
                            lastChecked = lastChecked,
                            search = search,
                            onSearch = { search = it },
                            hideLevel = hideLevel,
                            hiddenCats = hiddenCats,
                            pushEnabled = pushManager.enabled,
                            pushTopics = pushTopics,
                            onBell = ::toggleBell,
                            font = font,
                        )
                    }

                    // Settings (gear/hamburger) — fixed top-right, like the site.
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .background(palette.surface, RoundedCornerShape(8.dp))
                            .border(1.dp, palette.border, RoundedCornerShape(8.dp)),
                    ) {
                        Icon(
                            Icons.Outlined.Menu,
                            contentDescription = "Settings",
                            tint = palette.text,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
            }

            if (showSettings) {
                val categories = (state as? LoadState.Ready)?.data?.categories.orEmpty()
                SettingsSheet(
                    onDismiss = { showSettings = false },
                    isLight = isLight,
                    onTheme = { light ->
                        val value = if (light) "light" else "dark"
                        settings.putString("theme", value)
                        themeChoice = value
                    },
                    hideLevel = hideLevel,
                    onHideLevel = { level ->
                        hideLevel = level
                        settings.putInt("hideOldLevel", level)
                    },
                    categories = categories,
                    hiddenCats = hiddenCats,
                    onToggleCat = { cat ->
                        hiddenCats = hiddenCats.toMutableSet()
                            .apply { if (!add(cat)) remove(cat) }
                        settings.putString("hiddenCats", Json.encodeToString(hiddenCats.toList()))
                    },
                    pushEnabled = pushManager.enabled,
                    notifyAllOn = TOPIC_ALL in pushTopics,
                    onNotifyAll = { toggleBell(TOPIC_ALL) },
                    onRefresh = ::refresh,
                    font = font,
                )
            }
        }
    }
}

@Composable
private fun ContentGrid(
    state: LoadState,
    lastChecked: Instant?,
    search: String,
    onSearch: (String) -> Unit,
    hideLevel: Int,
    hiddenCats: Set<String>,
    pushEnabled: Boolean,
    pushTopics: Set<String>,
    onBell: (String) -> Unit,
    font: FontFamily,
) {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 170.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") { SiteHeader(font) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
            SearchField(search, onSearch, font)
        }

        when (state) {
            LoadState.Loading -> items(8) { SkeletonCard() }

            LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                ErrorText(font)
            }

            is LoadState.Ready -> {
                val filter = search.trim().lowercase()
                val visible = state.data.items.filter { item ->
                    matchesSearch(item, filter) &&
                        !isHiddenByLevel(item, hideLevel, today) &&
                        item.category !in hiddenCats
                }

                when {
                    visible.isEmpty() -> item(
                        span = { GridItemSpan(maxLineSpan) }, key = "empty"
                    ) { NoResults(font) }

                    visible.size == 1 -> item(
                        span = { GridItemSpan(maxLineSpan) }, key = "hero"
                    ) {
                        val item = visible[0]
                        Hero(
                            resolved = resolveItem(item, today),
                            pushEnabled = pushEnabled,
                            bellOn = topicItem(item) in pushTopics,
                            onBell = { onBell(topicItem(item)) },
                            font = font,
                        )
                    }

                    else -> {
                        // Group by category, preserving first-seen order (mirrors index.json order).
                        val groups = LinkedHashMap<String, MutableList<ItemResult>>()
                        visible.forEach { groups.getOrPut(it.category) { mutableListOf() }.add(it) }
                        groups.forEach { (cat, list) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "cat-$cat") {
                                CategoryHeader(
                                    name = cat,
                                    pushEnabled = pushEnabled,
                                    bellOn = topicCat(cat) in pushTopics,
                                    onBell = { onBell(topicCat(cat)) },
                                    font = font,
                                )
                            }
                            // Stable sort floats imminent releases to the top of each category.
                            val sorted = list.sortedBy { upcomingSortKey(it, today) }
                            items(sorted, key = { "${it.category}/${it.id}" }) { raw ->
                                ItemCard(
                                    resolved = resolveItem(raw, today),
                                    pushEnabled = pushEnabled,
                                    bellOn = topicItem(raw) in pushTopics,
                                    onBell = { onBell(topicItem(raw)) },
                                    font = font,
                                )
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                    Footer(updated = state.data.updated, lastChecked = lastChecked, font = font)
                }
            }
        }
    }
}
