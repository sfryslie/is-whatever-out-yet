import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ── AniList GraphQL API ───────────────────────────────────────────────────────

internal data class AniListMedia(
    val status: String,
    val startDate: LocalDate?,
    val nextAiringEpisode: Pair<Long, Int>?,  // airingAt unix timestamp to episode number
)

internal val ANILIST_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy")
// AniList stores airingAt as UTC; startDate fields are already in JST (the broadcast timezone).
// We convert airingAt to JST so the displayed date matches the Japanese broadcast calendar.
internal val JST = ZoneOffset.ofHours(9)

/**
 * Parse a raw AniList GraphQL response [body] into per-media results for [mediaIds]. A media entry
 * that's missing or explicitly `null` (AniList returns `null` for an ID it can't resolve — e.g. a
 * merged/deleted entry, or a transient hiccup on just that one ID) is skipped rather than treated
 * as a parse failure: one bad ID shouldn't take the whole batch down with it.
 */
internal fun parseAniListBatchResponse(body: JsonObject, mediaIds: List<Int>): Map<Int, AniListMedia> {
    // `as? JsonObject` (rather than the throwing `.jsonObject` extension) is the load-bearing bit
    // here: AniList legitimately returns a JSON `null` for plenty of fields that are merely absent
    // rather than the key being omitted — "data" itself on a GraphQL error, "startDate" for a show
    // with no confirmed date yet, "nextAiringEpisode" for anything not currently airing. A safe
    // cast treats all of those as "not present" instead of crashing the whole batch on one field.
    val data = body["data"] as? JsonObject ?: return emptyMap()
    return mediaIds.mapNotNull { id ->
        val media = data["m$id"] as? JsonObject ?: return@mapNotNull null
        val status = media["status"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val sd = media["startDate"] as? JsonObject
        val startDate = run {
            val y = sd?.get("year")?.jsonPrimitive?.intOrNull
            val m = sd?.get("month")?.jsonPrimitive?.intOrNull
            val d = sd?.get("day")?.jsonPrimitive?.intOrNull
            if (y != null && m != null && d != null) LocalDate.of(y, m, d) else null
        }
        val nextAiring = (media["nextAiringEpisode"] as? JsonObject)?.let {
            val at = it["airingAt"]?.jsonPrimitive?.longOrNull ?: return@let null
            val ep = it["episode"]?.jsonPrimitive?.intOrNull ?: return@let null
            at to ep
        }
        id to AniListMedia(status, startDate, nextAiring)
    }.toMap()
}

/** Fetch status + next-episode info for all [mediaIds] in a single GraphQL request. */
internal suspend fun fetchAniListBatch(client: HttpClient, mediaIds: List<Int>): Map<Int, AniListMedia> {
    if (mediaIds.isEmpty()) return emptyMap()
    val fields = mediaIds.joinToString(" ") { id ->
        "m$id: Media(id: $id, type: ANIME) { status startDate { year month day } nextAiringEpisode { airingAt episode } }"
    }
    return try {
        val body = client.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":"{ $fields }"}""")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
        parseAniListBatchResponse(body, mediaIds)
    } catch (e: Exception) {
        println("  AniList batch fetch failed: ${e.message}")
        emptyMap()
    }
}
