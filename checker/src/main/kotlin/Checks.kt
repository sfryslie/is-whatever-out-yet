import io.ktor.client.*
import java.time.Instant
import java.time.LocalDate

// ── Check execution ───────────────────────────────────────────────────────────

/**
 * Everything a single [runCheck] call needs: API keys, the pre-fetched model lists / AniList batch
 * / IGDB token (shared across items so each provider is hit once per run), the previous run's
 * results for fail-closed fallbacks, and today's date.
 */
internal class CheckContext(
    val client: HttpClient,
    val anthropicKey: String?,
    val openAiKey: String?,
    val googleKey: String?,
    val xaiKey: String?,
    val anthropicIds: List<String>,
    val openAiIds: List<String>,
    val geminiIds: List<String>,
    val xaiIds: List<String>,
    val aniListData: Map<Int, AniListMedia>,
    val igdbClientId: String?,
    val igdbToken: String?,
    val prevById: Map<String, ItemResult>,
    val today: LocalDate,
) {
    companion object {
        /**
         * Read provider credentials from the environment and do the once-per-run fetches (model
         * catalogues, the batched AniList query, the IGDB token). Every key is optional — a missing
         * one just skips that provider's live checks.
         */
        suspend fun fromEnv(
            client: HttpClient,
            items: List<Item>,
            prevById: Map<String, ItemResult>,
            today: LocalDate,
        ): CheckContext {
            val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
            val openAiKey    = System.getenv("OPENAI_API_KEY")
            val googleKey    = System.getenv("GOOGLE_API_KEY")
            val xaiKey       = System.getenv("XAI_API_KEY")
            val igdbClientId = System.getenv("IGDB_CLIENT_ID")
            val igdbSecret   = System.getenv("IGDB_CLIENT_SECRET")

            val anthropicIds: List<String> = if (anthropicKey != null) {
                println("Fetching Anthropic models…")
                fetchAnthropicModelIds(client, anthropicKey).also {
                    println("  Found ${it.size} model(s): ${it.joinToString()}")
                }
            } else {
                println("ANTHROPIC_API_KEY not set — skipping Anthropic checks.")
                emptyList()
            }

            val openAiIds: List<String> = if (openAiKey != null) {
                println("Fetching OpenAI models…")
                fetchOpenAIModelIds(client, openAiKey).also {
                    println("  Found ${it.size} model(s)")
                }
            } else {
                println("OPENAI_API_KEY not set — skipping OpenAI checks.")
                emptyList()
            }

            val geminiIds: List<String> = if (googleKey != null) {
                println("Fetching Gemini models…")
                fetchGeminiModelIds(client, googleKey).also {
                    println("  Found ${it.size} model(s)")
                }
            } else {
                println("GOOGLE_API_KEY not set — skipping Gemini checks.")
                emptyList()
            }

            val xaiIds: List<String> = if (xaiKey != null) {
                println("Fetching xAI models…")
                fetchXaiModelIds(client, xaiKey).also {
                    println("  Found ${it.size} model(s)")
                }
            } else {
                println("XAI_API_KEY not set — skipping Grok checks.")
                emptyList()
            }

            val aniListIds = items.mapNotNull { (it.check as? Check.AniList)?.mediaId }
            val aniListData: Map<Int, AniListMedia> = if (aniListIds.isNotEmpty()) {
                println("Fetching AniList data for ${aniListIds.size} show(s) (single request)…")
                fetchAniListBatch(client, aniListIds).also {
                    println("  Got ${it.size}/${aniListIds.size} result(s)")
                }
            } else emptyMap()

            val igdbToken: String? = if (igdbClientId != null && igdbSecret != null) {
                println("Fetching IGDB access token…")
                fetchIgdbToken(client, igdbClientId, igdbSecret).also {
                    if (it == null) println("  Failed — IGDB checks will fall back to item defaults.")
                    else            println("  Got IGDB token.")
                }
            } else {
                println("IGDB_CLIENT_ID / IGDB_CLIENT_SECRET not set — IGDB checks will use item defaults.")
                null
            }

            return CheckContext(
                client, anthropicKey, openAiKey, googleKey, xaiKey,
                anthropicIds, openAiIds, geminiIds, xaiIds,
                aniListData, igdbClientId, igdbToken, prevById, today,
            )
        }
    }
}

/** Execute [item]'s check strategy against [ctx] and produce its result for this run. */
internal suspend fun runCheck(item: Item, ctx: CheckContext): ItemResult {
    val result = when (val check = item.check) {
        is Check.Hardcoded -> ItemResult(
            item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail,
            since = item.since?.toString(), tone = item.tone,
        )

        is Check.RollingNewYear -> ItemResult(
            item.id, item.label, item.category,
            answer = item.defaultAnswer,
            detail = item.defaultDetail,
            countdownTo = LocalDate.of(LocalDate.now().year + 1, 1, 1).toString(),
        )

        is Check.ScheduledDate -> ItemResult(
            item.id, item.label, item.category,
            detail = item.defaultDetail,
            releaseDate = check.date.toString(),
        )

        is Check.VagueDate -> ItemResult(
            item.id, item.label, item.category,
            detail = item.defaultDetail,
            releaseDate = check.date.toString(),
            vagueLabel = check.vagueLabel,
        )

        is Check.CountdownTo -> ItemResult(
            item.id, item.label, item.category,
            answer = item.defaultAnswer,
            detail = item.defaultDetail,
            countdownTo = check.date.toString(),
        )

        is Check.Anthropic -> {
            // Listing match excludes preview/experimental variants. If a candidate is found,
            // probe with a 1-token messages call — Anthropic lists models in the catalog
            // before they're actually callable, so we have to verify accessibility.
            val candidate = matchModelId(ctx.anthropicIds, check.pattern)
            val callable = candidate != null && ctx.anthropicKey != null &&
                probeAnthropicModel(ctx.client, ctx.anthropicKey, candidate)
            if (candidate != null && !callable) {
                println("  ${item.label}: listed as '$candidate' but probe failed")
            }
            ItemResult(
                item.id, item.label, item.category,
                answer = if (callable) "Yes." else item.defaultAnswer,
                detail = if (callable) candidate else item.defaultDetail,
            )
        }

        is Check.OpenAI -> {
            if (ctx.openAiKey == null) {
                ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
            } else {
                val matched = matchModelId(ctx.openAiIds, check.pattern)
                ItemResult(
                    item.id, item.label, item.category,
                    answer = if (matched != null) "Yes." else item.defaultAnswer,
                    detail = matched ?: item.defaultDetail,
                )
            }
        }

        is Check.Gemini -> {
            if (ctx.googleKey == null) {
                ItemResult(item.id, item.label, item.category, item.defaultAnswer, "Add GOOGLE_API_KEY secret to enable live check.")
            } else {
                val matched = matchModelId(ctx.geminiIds, check.pattern)
                ItemResult(
                    item.id, item.label, item.category,
                    answer = if (matched != null) "Yes." else item.defaultAnswer,
                    detail = matched ?: item.defaultDetail,
                )
            }
        }

        is Check.Grok -> {
            if (ctx.xaiKey == null) {
                ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
            } else {
                val matched = matchModelId(ctx.xaiIds, check.pattern)
                ItemResult(
                    item.id, item.label, item.category,
                    answer = if (matched != null) "Yes." else item.defaultAnswer,
                    detail = matched ?: item.defaultDetail,
                )
            }
        }

        is Check.HomestarRunner -> {
            println("Checking Homestar Runner sitemap…")
            val (answer, detail) = checkHomestarRunnerSitemap(ctx.client)
            ItemResult(item.id, item.label, item.category, answer, detail)
        }

        is Check.GasPrices -> {
            println("Checking AAA national gas average…")
            val price = fetchNationalGasAverage(ctx.client, check.url)
            val label = price?.let { "$" + "%.2f".format(it) + "/gal" }
            // The price drives the answer: over $6/gal it's "out," over $5/gal it's getting
            // there. Below that (or on a parse/network miss) the item defaults hold.
            val (answer, detail) = when {
                price == null -> item.defaultAnswer to item.defaultDetail
                price > 6.0   -> "Yes." to "Over six bucks a gallon. Yeah, it's a problem."
                price > 5.0   -> "Maybe?" to "Five-plus a gallon and climbing."
                else          -> item.defaultAnswer to item.defaultDetail
            }
            ItemResult(
                item.id, item.label, item.category,
                answer = answer,
                detail = detail,
                countdownLabel = label,
                countdownSub = if (label != null) "U.S. average · AAA" else null,
            )
        }

        is Check.IGDB -> {
            // Fail closed: missing credentials or an unreachable/empty IGDB response shouldn't
            // overwrite a date a previous run already confirmed — prefer that over the item's
            // static defaults, and only fall back to the defaults on a true first run.
            val staleOrDefault = {
                ctx.prevById[item.id]?.copy(id = item.id, label = item.label, category = item.category)
                    ?: ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
            }
            if (ctx.igdbClientId == null || ctx.igdbToken == null) {
                staleOrDefault()
            } else {
                println("Checking IGDB for ${item.label} (slug: ${check.slug})…")
                val game = fetchIgdbGame(ctx.client, ctx.igdbClientId, ctx.igdbToken, check.slug)
                if (game == null) {
                    println("  IGDB: no result for '${check.slug}' — using previous run's data")
                    staleOrDefault()
                } else {
                    buildIgdbResult(item, game, ctx.today, ctx.prevById[item.id])
                }
            }
        }

        is Check.WikipediaLead -> {
            println("Checking Wikipedia lead for ${check.article}…")
            val extract = fetchWikipediaExtract(ctx.client, check.article)
            if (extract == null || extract.contains(check.phrase, ignoreCase = true)) {
                ItemResult(
                    item.id, item.label, item.category,
                    answer = item.defaultAnswer,
                    detail = item.defaultDetail,
                    countdownTo = check.latestDate?.toString(),
                    since = item.since?.toString(),
                )
            } else {
                // Early flip — Wikipedia signal beat the deadline. Drop the countdown.
                val articleUrl = "https://en.wikipedia.org/wiki/${check.article}"
                ItemResult(
                    item.id, item.label, item.category,
                    answer = "Yes.",
                    detail = "${escapeHtmlText(extract)} <a href=\"$articleUrl\" target=\"_blank\" rel=\"noopener\">(Wikipedia)</a>",
                    tone = check.flippedTone,
                )
            }
        }

        is Check.WikipediaHtml -> {
            println("Checking Wikipedia HTML for ${check.article}…")
            val html = fetchWikipediaHtml(ctx.client, check.article)
            if (html == null || check.phrases.any { html.contains(it) }) {
                ItemResult(
                    item.id, item.label, item.category,
                    answer = item.defaultAnswer,
                    detail = item.defaultDetail,
                    countdownTo = check.latestDate?.toString(),
                )
            } else {
                // Early flip — Wikipedia signal beat the deadline. Drop the countdown.
                val articleUrl = "https://en.wikipedia.org/wiki/${check.article}"
                ItemResult(
                    item.id, item.label, item.category,
                    answer = "Maybe?",
                    detail = "${check.flippedDetail} <a href=\"$articleUrl\" target=\"_blank\" rel=\"noopener\">(Wikipedia)</a>",
                )
            }
        }

        is Check.AniList -> {
            val media = ctx.aniListData[check.mediaId]
            if (media == null) {
                // Fail closed: network error, or this mediaId missing/null in the batch result.
                // Falling back to the item's static default would silently undo a previously
                // confirmed "Yes." or exact date — prefer the previous run's result instead,
                // and only use the vague placeholder on a true first run.
                ctx.prevById[item.id]?.copy(id = item.id, label = item.label, category = item.category)
                    ?: ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail,
                        releaseDate = check.vagueDate?.toString(), vagueLabel = check.vagueLabel)
            } else when (media.status) {
                "NOT_YET_RELEASED" -> {
                    // Prefer the fully-specified startDate (already in JST, the broadcast calendar).
                    // If startDate is partial/missing, try converting nextAiringEpisode to JST.
                    val exactDate = media.startDate ?: media.nextAiringEpisode?.let { (airingAt, _) ->
                        Instant.ofEpochSecond(airingAt).atZone(JST).toLocalDate()
                    }
                    if (exactDate != null) {
                        ItemResult(item.id, item.label, item.category, detail = item.defaultDetail,
                            releaseDate = exactDate.toString())
                    } else {
                        // No confirmed date yet — hold at the vague fallback
                        ItemResult(item.id, item.label, item.category, detail = item.defaultDetail,
                            releaseDate = check.vagueDate?.toString(), vagueLabel = check.vagueLabel)
                    }
                }
                "RELEASING", "HIATUS" -> {
                    val (countdownLabel, countdownSub) = media.nextAiringEpisode?.let { (airingAt, episode) ->
                        val date = Instant.ofEpochSecond(airingAt).atZone(JST).toLocalDate()
                        "Ep. $episode airs" to ANILIST_DATE_FMT.format(date)
                    } ?: (null to null)
                    ItemResult(item.id, item.label, item.category, answer = "Yes.",
                        detail = item.defaultDetail, countdownLabel = countdownLabel, countdownSub = countdownSub)
                }
                else -> // FINISHED, CANCELLED
                    ItemResult(item.id, item.label, item.category, answer = "Yes.", detail = item.defaultDetail)
            }
        }
    }
    return result.copy(aliases = item.aliases)
}
