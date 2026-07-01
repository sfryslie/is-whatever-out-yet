import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ── IGDB (game release dates via Twitch app credentials) ─────────────────────

internal const val IGDB_PC_PLATFORM = 6
// PS4=48, Xbox One=49, Switch=130, PS5=167, Xbox Series X|S=169
internal val IGDB_CONSOLE_PLATFORMS = setOf(48, 49, 130, 167, 169)

internal data class IgdbReleaseDate(
    val platformId: Int?,
    val category:   Int,     // 0=exact, 1=month+year, 2=year, 3=Q1…6=Q4, 7=TBD
    val unixDate:   Long?,   // Unix seconds; only set for category 0
    val year:       Int?,
    val month:      Int?,    // 1–12
    val human:      String?, // human-readable label from IGDB, e.g. "Nov 19, 2026"
)

// Maps an IGDB release date to the LocalDate used as our flip trigger / countdown end.
// For vague categories we pick a conservative end-of-period so the card doesn't flip early.
internal fun IgdbReleaseDate.toLocalDate(): LocalDate? = when (category) {
    0    -> unixDate?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate() }
    1    -> if (year != null && month != null) LocalDate.of(year, month, 1) else null
    2    -> year?.let { LocalDate.of(it, 12, 31) }
    3    -> year?.let { LocalDate.of(it,  3, 31) }  // Q1
    4    -> year?.let { LocalDate.of(it,  6, 30) }  // Q2
    5    -> year?.let { LocalDate.of(it,  9, 30) }  // Q3
    6    -> year?.let { LocalDate.of(it, 12, 31) }  // Q4
    else -> null                                     // 7 = TBD
}

internal fun IgdbReleaseDate.toVagueLabel(): String? = when (category) {
    0    -> null
    1    -> if (year != null && month != null)
                "${java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)} $year?"
            else null
    2    -> year?.let { "$it?" }
    3    -> year?.let { "Q1 $it?" }
    4    -> year?.let { "Q2 $it?" }
    5    -> year?.let { "Q3 $it?" }
    6    -> year?.let { "Q4 $it?" }
    else -> null
}

internal fun parseIgdbReleaseDates(obj: JsonObject): List<IgdbReleaseDate> =
    obj["release_dates"]?.jsonArray?.map { rd ->
        val o = rd.jsonObject
        IgdbReleaseDate(
            platformId = o["platform"]?.jsonPrimitive?.intOrNull,
            // IGDB omits category=0 (exact date) from responses — infer it from the timestamp.
            category   = o["category"]?.jsonPrimitive?.intOrNull
                         ?: if (o["date"] != null) 0 else 7,
            unixDate   = o["date"]?.jsonPrimitive?.longOrNull,
            year       = o["y"]?.jsonPrimitive?.intOrNull,
            month      = o["m"]?.jsonPrimitive?.intOrNull,
            human      = o["human"]?.jsonPrimitive?.contentOrNull,
        )
    } ?: emptyList()

/**
 * Build an [ItemResult] from a raw IGDB game JSON object ([game]). Primary release date is the
 * earliest confirmed date among console platforms (falling back to all non-PC, then all dates).
 * PC (platform 6) is tracked separately:
 * - Pre-release: if PC has a confirmed date that differs from primary, it appears as detail text.
 *   TBD PC entries are silent so the item's [Item.defaultDetail] (e.g. "Not for PC though, rip.")
 *   can serve as the human-authored fallback.
 * - Post-primary-release: if PC has a future confirmed date, [ItemResult.countdownTo] is set so
 *   the frontend can compute "N days to go" against the user's clock.
 *
 * If IGDB has no resolvable date for this game (empty/TBD-only `release_dates`), falling back to
 * the item's static defaults would silently erase a date a *previous* run already confirmed — so
 * [prev] (the previous run's result for this item, if any) is preferred over the static defaults
 * in that case. `null` (the default) keeps the old defaults-only behavior for callers that don't
 * track run history.
 */
internal fun buildIgdbResult(item: Item, game: JsonObject, today: LocalDate, prev: ItemResult? = null): ItemResult {
    val status   = game["status"]?.jsonPrimitive?.intOrNull  // 0 = Released
    val allDates = parseIgdbReleaseDates(game)

    val pcDate = allDates.firstOrNull { it.platformId == IGDB_PC_PLATFORM }

    // Prefer console dates for primary; fall back to non-PC, then everything.
    val primary = (allDates.filter { it.platformId in IGDB_CONSOLE_PLATFORMS }
        .ifEmpty { allDates.filter { it.platformId != IGDB_PC_PLATFORM } }
        .ifEmpty { allDates })
        .filter { it.category != 7 }
        .minByOrNull { it.toLocalDate() ?: LocalDate.MAX }

    val primaryDate = primary?.toLocalDate()
    val pcLocalDate = pcDate?.toLocalDate()

    val gameReleased = status == 0 || (primaryDate != null && !primaryDate.isAfter(today))
    val pcReleased   = pcLocalDate != null && !pcLocalDate.isAfter(today)

    // "PC: <date>" note — only when IGDB has a confirmed PC date that differs from primary.
    // Omitted for TBD (no resolvable date) so defaultDetail can provide context instead.
    val pcNote = if (pcLocalDate != null && !pcReleased && pcLocalDate != primaryDate)
        pcDate?.human?.let { "PC: $it" } ?: pcDate?.toVagueLabel()?.let { "PC: $it" }
    else null

    return when {
        // All platforms out (or no separate PC tracking needed)
        gameReleased && (pcDate == null || pcReleased) -> ItemResult(
            item.id, item.label, item.category,
            answer = "Yes.",
            detail = item.defaultDetail,
            since  = primaryDate?.toString(),
        )

        // Primary platform is out; PC has a confirmed future date — countdownTo so the
        // frontend computes "N days to go" against the user's local clock.
        gameReleased && pcLocalDate != null -> ItemResult(
            item.id, item.label, item.category,
            answer      = "Yes.",
            countdownTo = pcLocalDate.toString(),
            detail      = "PC release",
        )

        // Primary platform is out; no confirmed PC date
        gameReleased -> ItemResult(
            item.id, item.label, item.category,
            answer = "Yes.",
            detail = item.defaultDetail,
        )

        // Not yet released — date-driven card
        primaryDate != null -> ItemResult(
            item.id, item.label, item.category,
            releaseDate = primaryDate.toString(),
            vagueLabel  = primary?.toVagueLabel(),
            detail      = pcNote ?: item.defaultDetail,
        )

        // No date info — fall back to the previous run's result (a transient/partial IGDB
        // response shouldn't undo a date already confirmed), or the item defaults on first run.
        else -> prev?.copy(id = item.id, label = item.label, category = item.category)
            ?: ItemResult(
                item.id, item.label, item.category,
                answer = item.defaultAnswer,
                detail = item.defaultDetail,
            )
    }
}

// Exchange Twitch app credentials for a short-lived bearer token used on all IGDB requests.
// The token lasts ~60 days; for a 30-minute cron it's fine to fetch a fresh one each run.
suspend fun fetchIgdbToken(client: HttpClient, clientId: String, secret: String): String? = try {
    val body = client.post("https://id.twitch.tv/oauth2/token") {
        parameter("client_id", clientId)
        parameter("client_secret", secret)
        parameter("grant_type", "client_credentials")
    }.bodyAsText()
    Json.parseToJsonElement(body).jsonObject["access_token"]?.jsonPrimitive?.contentOrNull
} catch (e: Exception) {
    null
}

suspend fun fetchIgdbGame(client: HttpClient, clientId: String, token: String, slug: String): JsonObject? = try {
    val body = client.post("https://api.igdb.com/v4/games") {
        header("Client-ID", clientId)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Text.Plain)
        setBody(
            "fields name, status, release_dates.category, release_dates.date, " +
            "release_dates.human, release_dates.m, release_dates.platform, release_dates.y; " +
            "where slug = \"$slug\"; limit 1;"
        )
    }.bodyAsText()
    Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
} catch (e: Exception) {
    null
}
