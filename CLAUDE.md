# CLAUDE.md

## Project overview

Static GitHub Pages site that tracks whether various things are "out yet." A Kotlin checker runs every 30 minutes via GitHub Actions and writes `data.json`. The frontend (`index.html`) fetches that file and renders cards.

## Repo structure

```
index.html                        # Frontend — vanilla JS, no build step
data.json                         # Written by the checker; read by the frontend
checker/
  src/main/kotlin/Main.kt         # All checker logic — items catalogue + check strategies
  build.gradle.kts                # Kotlin JVM + Ktor Client CIO + kotlinx-serialization
  settings.gradle.kts
  gradlew / gradlew.bat           # Gradle wrapper — build without a system Gradle install
.github/workflows/check-models.yml  # Cron job (every 30 min) that runs the checker and commits data.json
```

## Building locally

The repo ships the Gradle wrapper, so no system Gradle install is needed (only a JDK 21 on PATH):

```
cd checker
./gradlew compileKotlin   # type-check after editing Main.kt
./gradlew run             # run the checker (needs ANTHROPIC_API_KEY; other keys optional)
```

On Windows use `gradlew.bat`. The wrapper self-downloads Gradle 8.10.2 on first run.

## Adding or editing items

**`Main.kt` is the source of truth. `data.json` is a generated artifact** — the GitHub Action overwrites it completely on every run. Editing only `data.json` will be reverted within 30 minutes.

When adding or changing an item, always update **both**:
1. `ITEMS` in `checker/src/main/kotlin/Main.kt` — permanent definition
2. `data.json` — so the site reflects the change immediately, before the next action run

Each item in `ITEMS` is:

```kotlin
Item(id, label, category, check, defaultAnswer, defaultDetail)
```

- `defaultAnswer` defaults to `"No."`, `defaultDetail` defaults to `null`

### Check types

| Check | Behavior |
|-------|----------|
| `Check.Hardcoded` | Uses `defaultAnswer`/`defaultDetail`, never changes |
| `Check.ScheduledDate(date)` | Emits `releaseDate` (ISO) only — the frontend computes Yes/No, the formatted date, and "N days to go" from the user's local clock |
| `Check.VagueDate(date, vagueLabel)` | Same as ScheduledDate plus a `vagueLabel` (e.g. `"January 2027?"`). Frontend shows the label and "~N months out" instead of the exact date. `date` is still the flip trigger |
| `Check.Anthropic(pattern)` | Searches Anthropic `/v1/models` for an ID containing `pattern` |
| `Check.OpenAI(pattern)` | Same for OpenAI `/v1/models`; skipped if `OPENAI_API_KEY` unset |
| `Check.Gemini(pattern)` | Same for Google `/v1beta/models`; skipped if `GOOGLE_API_KEY` unset |
| `Check.Grok(pattern)` | Same for xAI `/v1/models` (OpenAI-compatible); skipped if `XAI_API_KEY` unset |
| `Check.HomestarRunner` | Checks homestarrunner.com sitemap for sbemail211 |
| `Check.WikipediaLead(article, phrase, latestDate?)` | Fetches the Wikipedia REST summary for `article`; phrase present in the lead extract → defaults (condition still holds), phrase missing → `"Yes."` with the full new extract + a Wikipedia link as the detail. Optional `latestDate` adds a display-only `countdownTo` while the condition still holds (the Wikipedia signal can flip earlier). Fails closed on network errors |
| `Check.WikipediaHtml(article, phrase, flippedDetail)` | Same idea but fetches the full rendered HTML so the check can see infobox fields the summary endpoint strips (e.g. `"Incarcerated at"` for prisoners). Case-sensitive match — infobox values have predictable capitalization. Phrase missing → `"Maybe?"` (intentionally hedged — infobox edits can be template churn / transfer notation, not just release) with `flippedDetail` + Wikipedia link |

## Data shape

Each `ItemResult` carries either `answer` (hardcoded / API-driven) OR `releaseDate` + optional `vagueLabel` (date-driven). The frontend's `resolveItem()` in [index.html](index.html) turns a `releaseDate` item into the rendered answer + countdown against `new Date()`, so countdowns are always accurate to the user's local clock instead of whenever the last cron run happened to land.

`countdownTo` is a third option that *layers* a display-only countdown on top of a server-authoritative `answer` — used when something other than the date drives the flip (e.g. the Wikipedia check on Donald Trump), but a "this could end by date X at the latest" countdown is still useful.

## Card layout & coloring

A card has up to four parts: label → `answer` (big headline) → optional countdown block (date + sub line) → optional `detail` (small note).

Card class comes from `cardClass()` in `index.html` based on `answer`:

- Starts with `"yes"` → green (`yes` class)
- Starts with `"no"` or equals `"never."` → dark/muted (`no` class) — this is the case for every pre-release countdown card
- Contains `"soon"` or `"next year"` → amber (`soon` class)
- Anything else → blue (`other` class) — `"Probably."`, `"Chill."`, etc.

The countdown label is always rendered in the blue accent (`--other`) regardless of card class, so countdown cards still read as "No, but here's when".

## Secrets (GitHub Actions)

- `ANTHROPIC_API_KEY` — required
- `OPENAI_API_KEY` — optional, live OpenAI check skipped if absent
- `GOOGLE_API_KEY` — optional, live Gemini check skipped if absent
- `XAI_API_KEY` — optional, live Grok check skipped if absent

## Workflow notes

- The cron pushes `data.json` directly to `main` (branch protection bypassed via a PAT if configured, otherwise requires the Actions bot exemption)
- `exitProcess(0)` at the end of `main()` is intentional — kills Ktor's CIO background threads cleanly
- Model-list endpoints are free metadata calls, no token cost

## Deployment

- Hosted on GitHub Pages from `main` branch root
- Custom domain: `iswhateveroutyet.com` (Cloudflare DNS, gray cloud, A records → GitHub IPs)
- `isclaudesonnet5outyet.com` redirects to `iswhateveroutyet.com` via Cloudflare redirect rule
