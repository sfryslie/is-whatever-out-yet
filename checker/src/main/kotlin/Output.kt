import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ── Data directory I/O ────────────────────────────────────────────────────────
//
// The site's data lives split per category so git history shows exactly which category a run
// touched:
//   data/index.json            — { updated, categories: [ { name, file }, … ] } in display order
//   data/<category-slug>.json  — { category, items: [ ItemResult… ] }
// The frontend fetches the index, then every category file, and reassembles the flat item list.

@Serializable
internal data class CategoryRef(val name: String, val file: String)

@Serializable
internal data class DataIndex(val updated: String, val categories: List<CategoryRef>)

@Serializable
internal data class CategoryData(val category: String, val items: List<ItemResult>)

/** The previous run's output, reassembled from the per-category files. */
internal data class PrevRun(val updated: String, val items: List<ItemResult>)

internal fun categoryFileName(category: String) = categorySlug(category) + ".json"

/**
 * Load the previous run's full output (items + the `updated` stamp) so the caller can detect state
 * changes — which drive `since`, push notifications, the `updated` timestamp, and the commit
 * message. Missing/unparseable → null, which means "everything is first-seen": no false
 * notifications.
 */
internal fun readPreviousData(dataDir: String): PrevRun? = try {
    val indexFile = File(dataDir, "index.json")
    if (!indexFile.exists()) null else {
        val json = Json { ignoreUnknownKeys = true }
        val index = json.decodeFromString<DataIndex>(indexFile.readText())
        val items = index.categories.flatMap { ref ->
            val f = File(dataDir, ref.file)
            if (f.exists()) json.decodeFromString<CategoryData>(f.readText()).items else emptyList()
        }
        PrevRun(index.updated, items)
    }
} catch (e: Exception) {
    println("Could not read previous data from $dataDir (${e.message}) — treating all items as first-seen.")
    null
}

/**
 * Diff this run against [prevData], then write the per-category files + index to [dataDir] and the
 * commit message to COMMIT_MSG_PATH (if set). `updated` only moves on a *meaningful* change (a
 * card's answer/tone, or an added/removed item) — not on pure display churn like the live gas price
 * ticking — so the public timestamp reflects the last real change, not the last poll. When nothing
 * changed at all every file is byte-identical to last run and the workflow's staged-diff guard
 * skips the commit entirely.
 */
internal fun writeOutput(dataDir: String, prevData: PrevRun?, results: List<ItemResult>, today: LocalDate) {
    // Resolve the previous run against its own clock so a date-driven release that slipped past
    // between runs still registers as a change (see diffRuns).
    val prevClock = prevData?.updated?.let {
        try { Instant.parse(it).atZone(ZoneOffset.UTC).toLocalDate() } catch (e: Exception) { today }
    } ?: today
    val changes = if (prevData == null) listOf(RunChange("Initialize tracked data", meaningful = true))
                  else diffRuns(prevData.items, prevClock, results, today)
    val meaningful = changes.any { it.meaningful }
    val updated = if (prevData != null && !meaningful) prevData.updated else Instant.now().toString()

    // Group into category files, preserving ITEMS' first-seen category order for the index.
    val byCategory = LinkedHashMap<String, MutableList<ItemResult>>()
    results.forEach { byCategory.getOrPut(it.category) { mutableListOf() } += it }
    val index = DataIndex(updated, byCategory.keys.map { CategoryRef(it, categoryFileName(it)) })

    val dir = File(dataDir)
    dir.mkdirs()
    val json = Json { prettyPrint = true }
    byCategory.forEach { (category, items) ->
        File(dir, categoryFileName(category)).writeText(json.encodeToString(CategoryData(category, items)))
    }
    File(dir, "index.json").writeText(json.encodeToString(index))

    // A renamed/removed category would otherwise leave its old file live on the site forever.
    val expected = index.categories.map { it.file }.toSet() + "index.json"
    dir.listFiles()?.filter { it.name.endsWith(".json") && it.name !in expected }?.forEach { it.delete() }

    // Hand the workflow a commit message naming the change(s). Only written when something changed;
    // otherwise the files are unchanged and there's nothing to commit.
    System.getenv("COMMIT_MSG_PATH")?.let { msgPath ->
        if (changes.isNotEmpty()) File(msgPath).writeText(buildCommitMessage(changes))
    }

    if (changes.isEmpty()) {
        println("\nNo changes since last run — $dataDir is identical, nothing to commit.")
    } else {
        println("\nChanges this run (updated ${if (meaningful) "→ $updated" else "unchanged: $updated"}):")
        changes.forEach { println("  - ${it.description}${if (it.meaningful) "" else " (minor)"}") }
    }

    println("\nWrote ${index.categories.size} category file(s) + index.json to $dataDir:")
    results.forEach { r ->
        val note = r.detail?.let { " ($it)" } ?: ""
        println("  [${r.category}] ${r.label}: ${r.answer}$note")
    }
}
