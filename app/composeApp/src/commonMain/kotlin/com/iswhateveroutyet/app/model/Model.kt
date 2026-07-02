package com.iswhateveroutyet.app.model

import kotlinx.serialization.Serializable

/**
 * Mirror of the checker's ItemResult (checker/src/main/kotlin/Model.kt) — the shape of every
 * entry in data/<category>.json. Either [answer] is set (hardcoded / API check) OR [releaseDate]
 * is set (date-driven; the client computes Yes/No and the countdown from the local clock).
 */
@Serializable
data class ItemResult(
    val id: String,
    val label: String,
    val category: String,
    val answer: String? = null,
    val detail: String? = null,
    val releaseDate: String? = null,
    val vagueLabel: String? = null,
    val countdownTo: String? = null,
    val countdownLabel: String? = null,
    val countdownSub: String? = null,
    val since: String? = null,
    val tone: String? = null,
    val aliases: List<String>? = null,
)

@Serializable
data class CategoryRef(val name: String, val file: String)

/** data/index.json — global `updated` stamp + ordered category list. */
@Serializable
data class DataIndex(val updated: String, val categories: List<CategoryRef>)

/** data/<category-slug>.json. */
@Serializable
data class CategoryFile(val category: String, val items: List<ItemResult>)

/** Everything the UI needs, flattened back out of the per-category files. */
data class SiteData(
    val updated: String,
    val categories: List<String>,
    val items: List<ItemResult>,
)
