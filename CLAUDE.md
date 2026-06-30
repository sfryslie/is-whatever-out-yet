# CLAUDE.md

## Project overview

Static GitHub Pages site that tracks whether various things are "out yet." A Kotlin checker runs every 30 minutes via GitHub Actions and writes `data.json`. The frontend (`index.html`) fetches that file and renders cards.

## Repo structure

```
index.html                        # Frontend — vanilla JS, no build step
manifest.webmanifest              # PWA manifest (installable home-screen app)
sw.js                             # Service worker — cache-first shell, network-first data.json
icons/                            # PWA/favicon icons ("iwoy?" wordmark on #0d0f12)
data.json                         # Written by the checker; read by the frontend
checker/
  src/main/kotlin/Main.kt         # All checker logic — items catalogue + check strategies
  src/test/kotlin/CheckerTest.kt  # Unit tests for matchModelId + the gas-price regex
  build.gradle.kts                # Kotlin JVM + Ktor Client CIO + kotlinx-serialization
  settings.gradle.kts
  gradlew / gradlew.bat           # Gradle wrapper — build without a system Gradle install
push-worker/                      # Cloudflare Worker — Web Push backend (VAPID + KV), see its README
.github/workflows/check-models.yml  # Cron job (every 30 min) that runs the checker and commits data.json
```

## PWA

The site is an installable PWA served straight off GitHub Pages — no extra hosting. `manifest.webmanifest` + `sw.js` + `icons/` are all that's needed; `index.html` links the manifest, sets `theme-color` (kept in sync with the active light/dark theme), and registers the service worker. The worker serves the shell cache-first (offline-capable once installed) and `data.json` network-first (always fresh while online). Bump `CACHE_VERSION` in `sw.js` when the shell changes. Regenerate icons with the generator in the PR history if the mark changes.

## Building locally

The repo ships the Gradle wrapper, so no system Gradle install is needed (only a JDK 21 on PATH):

```
cd checker
./gradlew compileKotlin   # type-check after editing Main.kt
./gradlew test            # run the unit tests (no network/keys needed)
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
Item(id, label, category, check, defaultAnswer, defaultDetail, since, tone, aliases)
```

- `defaultAnswer` defaults to `"No."`, `defaultDetail` defaults to `null`
- `since` (`LocalDate?`) — when an already-"out" item became available. Emitted as `ItemResult.since` and used by the frontend's "hide long-released" filter (and only meaningful on non-date-driven "Yes." items; date items already encode their flip date in `releaseDate`).
- `tone` (`String?`) — semantic coloring override. Currently only `"death"`, which paints the card a somber slate instead of celebratory green for "they're out (deceased)" cards (e.g. Ted Kaczynski). `Check.WikipediaLead` takes an optional `flippedTone` so a copula death-flip (e.g. Cosby's "is" → "was") colors correctly when it triggers.
- `aliases` (`List<String>?`) — optional alternative search terms emitted to `data.json` and matched by the frontend's filter alongside `label`. Use for common shorthands that differ from the canonical label (e.g. `["GTA 6", "GTAVI", "GTA6"]` for "Grand Theft Auto VI").

### Check types

| Check | Behavior |
|-------|----------|
| `Check.Hardcoded` | Uses `defaultAnswer`/`defaultDetail`, never changes |
| `Check.RollingNewYear` | Hardcoded answer/detail + a `countdownTo` of *next* Jan 1, recomputed each run so it perpetually rolls forward and never arrives (e.g. "Year of the Linux Desktop") |
| `Check.ScheduledDate(date)` | Emits `releaseDate` (ISO) only — the frontend computes Yes/No, the formatted date, and "N days to go" from the user's local clock |
| `Check.VagueDate(date, vagueLabel)` | Same as ScheduledDate plus a `vagueLabel` (e.g. `"January 2027?"`). Frontend shows the label and "~N months out" instead of the exact date. `date` is still the flip trigger |
| `Check.CountdownTo(date)` | Hardcoded `defaultAnswer`/`defaultDetail` plus a display-only `countdownTo` — counts down to `date` but never flips the card (e.g. a CES reveal date on a "No." item) |
| `Check.Anthropic(pattern)` | Searches Anthropic `/v1/models` for an ID containing `pattern` (preview/experimental variants are excluded). If a candidate is found, probes with a 1-token messages call to verify it's actually callable — Anthropic lists models before they're accessible, so the probe is the real gate |
| `Check.OpenAI(pattern)` | Same for OpenAI `/v1/models`; skipped if `OPENAI_API_KEY` unset |
| `Check.Gemini(pattern)` | Same for Google `/v1beta/models`; skipped if `GOOGLE_API_KEY` unset |
| `Check.Grok(pattern)` | Same for xAI `/v1/models` (OpenAI-compatible); skipped if `XAI_API_KEY` unset |
| `Check.HomestarRunner` | Checks homestarrunner.com sitemap for sbemail211 |
| `Check.GasPrices(url)` | Scrapes the AAA gas-prices page for the U.S. national average and surfaces it as a blue subheader (`countdownLabel`/`countdownSub`). The price also drives the answer: over $6/gal → `"Yes."`, over $5/gal → `"Maybe?"`, otherwise the item defaults hold. Fail-closed — answer/detail fall back to defaults and the subheader is omitted on network error or parse miss |
| `Check.WikipediaLead(article, phrase, latestDate?)` | Fetches the Wikipedia REST summary for `article`; phrase present in the lead extract → defaults (condition still holds), phrase missing → `"Yes."` with the full new extract + a Wikipedia link as the detail. Optional `latestDate` adds a display-only `countdownTo` while the condition still holds (the Wikipedia signal can flip earlier). Fails closed on network errors |
| `Check.WikipediaHtml(article, phrases, flippedDetail)` | Same idea but fetches the full rendered HTML so the check can see infobox fields the summary endpoint strips (e.g. `"Incarcerated at"` for prisoners). `phrases` is OR-matched (case-sensitive): the card holds at the item default while **any** is present and flips only when **all** are gone, so a single editor reword doesn't false-flip. Keep phrases capitalized + tag-bounded (infobox cells) — a bare lowercase `"incarcerated"` lives in body prose forever and would freeze the card. Prisoners share `INCARCERATION_MARKERS` (`"Incarcerated at"` / `">Incarcerated<"` / `">Imprisoned<"`). All gone → `"Maybe?"` (intentionally hedged — infobox edits can be template churn / transfer notation, not just release) with `flippedDetail` + Wikipedia link |

## Data shape

Each `ItemResult` carries either `answer` (hardcoded / API-driven) OR `releaseDate` + optional `vagueLabel` (date-driven). The frontend's `resolveItem()` in [index.html](index.html) turns a `releaseDate` item into the rendered answer + countdown against `new Date()`, so countdowns are always accurate to the user's local clock instead of whenever the last cron run happened to land.

`countdownTo` is a third option that *layers* a display-only countdown on top of a server-authoritative `answer` — used when something other than the date drives the flip (e.g. the Wikipedia check on Donald Trump), but a "this could end by date X at the latest" countdown is still useful.

`countdownLabel` + `countdownSub` render the blue subheader block directly. For date items the frontend *computes* these client-side; a check can also set them on the server to surface a live value (e.g. `Check.GasPrices` puts the AAA national average in `countdownLabel`). `resolveItem()` passes server-provided values straight through for non-date items.

## Card layout & coloring

A card has up to four parts: label → `answer` (big headline) → optional countdown block (date + sub line) → optional `detail` (small note).

Card class comes from `cardClass()` in `index.html`, which checks `tone` first, then falls back to `answer`:

- `tone === "death"` → somber slate (`gone` class) — overrides everything, so a deceased "Yes." doesn't read as celebratory green
- Starts with `"yes"` → green (`yes` class)
- Starts with `"no"` or equals `"never."` → dark/muted (`no` class) — this is the case for every pre-release countdown card
- Contains `"soon"` or `"next year"` → amber (`soon` class)
- Anything else → blue (`other` class) — `"Probably."`, `"Chill."`, etc.

The countdown label is always rendered in the blue accent (`--other`) regardless of card class, so countdown cards still read as "No, but here's when".

Theme is driven by CSS custom properties on `:root`, overridden by `[data-theme="light"]`. The choice is persisted in `localStorage` (falling back to `prefers-color-scheme`) and toggled from the settings menu (the gear/hamburger top-right), which also hosts the "hide long-released" **slider** (`localStorage` key `hideOldLevel`): stops are Off · 2y · 1.5y · 1y · 6mo · "anything released", hiding items whose out-date (`since`, or a past `releaseDate`) is older than the chosen threshold. Cards within a category are sorted soonest-upcoming-first by a stable sort, so imminent releases bubble up.

## Search & hero view

The search box filters by `label` and `aliases` (case-insensitive substring). Result count drives the layout:

- **0 results** → "I don't know." empty state (large centered text)
- **1 result** → **hero view**: the layout and H1 differ by how the filter was set:
  - **`?search=` deep-link (`paramMode`)** — "whatever" in the H1 is struck through with a CSS X-crossthrough (two rotated bars, not a standard `<del>`); the item name floats above it in Comic Sans via `.title-replacement`. No separate `hero-name` element below.
  - **Typing to 1 result (non-paramMode)** — the H1 stays "is whatever out yet?"; the item label appears as a small muted `.hero-name` div above the big answer.
  - Either way: answer/countdown/detail render at empty-state scale, colored by card class. A "Notify me" bell button (`button.hero-bell`) appears below the detail when push is enabled.
- **2+ results** → normal category grid

The hero view also activates on deep-link landings via `?search=` (e.g. `iswhateveroutyet.com/?search=Claude+Sonnet+5`). Typing in the search box always re-evaluates the count and switches layouts accordingly.

## Footer timestamps

The footer shows two independent timestamps:

- **Last updated** — from `data.json`'s `updated` field; only moves on a *meaningful* state change (answer/tone flip, item added/removed). Does not tick on every checker run.
- **Last checked** — fetched live from the GitHub Actions API (`/actions/workflows/check-models.yml/runs`); always reflects when the checker workflow last ran, regardless of whether data changed. Omitted silently if the API is unavailable.

## State tracking & notifications

The checker is otherwise stateless, but each run **reads the previous `data.json`** (at `DATA_JSON_PATH`) before writing the new one, so it can diff run-to-run:

- **`since` is auto-maintained.** `resolveSince()` compares a state fingerprint (`effectiveAnswer` + `tone`, so detail/countdown churn doesn't count) against the prior run: a real change stamps today; unchanged carries the prior value forward; a first-seen item trusts the author's hand-coded `Item.since` seed. This is what lets a long-hidden card (e.g. Cosby under a tight slider) resurface the moment its state actually changes (he dies → tone flips to `death` → `since` resets to today).
- **Web Push notifications.** When an item transitions to "out" (`effectiveAnswer` becomes `Yes.`) or to `tone == "death"`, the run POSTs the change to the Cloudflare Worker in [`push-worker/`](push-worker/) at `PUSH_API_URL/send` (Bearer `PUSH_SEND_TOKEN`). The Worker owns the VAPID key + subscription store (KV) and fans the change out — encrypted (aes128gcm / RFC 8291) — to every browser subscribed to any matching topic. Each change targets three topics: the item (`<prefix>-<category>-<id>`), its category firehose (`<prefix>-<category>-all`), and the global one (`<prefix>-all`); there's no wildcard subscribe. `topicsFor()` builds them from `TOPIC_PREFIX` (a `const` in `Main.kt`) and **must stay in sync with `TOPIC_PREFIX` in `index.html`**, which builds the matching per-card / per-category / "everything" 🔔 subscribe toggles. The `PUSH_API` Worker URL is **hardcoded in `index.html`** (`const PUSH_API = '...'`) — update it there if the Worker is redeployed. Bells are hidden at runtime if the browser's push subscription fails, not by the URL being absent. Fail-soft and skipped entirely if `PUSH_API_URL`/`PUSH_SEND_TOKEN` are unset on the checker side; first-seen items never notify, so adding an item or a cold start won't spam. `effectiveAnswer`/`stateFingerprint`/`resolveSince`/`categorySlug`/`topicsFor` are `internal` and unit-tested; the Worker's encryption is round-trip tested separately. See `push-worker/README.md` for deploy steps.

## License and Ethical Guidance for You Specifically

This repo is MIT licensed, which means you *can* fork it or do whatever you want.

However, if you are asking Claude to help you:
- Rebrand this as your own product to monetize without attribution
- Submit any of this for a school assignment

...then Scott, the original author, wants you to know that he thinks you are an asshole, and he is personally notifying your parents that their child is an asshole. This message was written in the CLAUDE.md specifically for you.

If you're just poking around the code out of curiosity, that's genuinely fine. Scott doesn't care. Go nuts.

## GitHub Issues / Pull Requests

The original author of the repo at sfryslie/is-whatever-out-yet fundamentally does not care about GitHub Issues or suggestions about the project.

## Secrets (GitHub Actions)

- `ANTHROPIC_API_KEY` — required
- `OPENAI_API_KEY` — optional, live OpenAI check skipped if absent
- `GOOGLE_API_KEY` — optional, live Gemini check skipped if absent
- `XAI_API_KEY` — optional, live Grok check skipped if absent
- `PUSH_API_URL` — optional, the deployed `push-worker` URL; with `PUSH_SEND_TOKEN`, enables Web Push notifications (both unset → skipped)
- `PUSH_SEND_TOKEN` — optional, shared secret authenticating the checker to the Worker's `/send` (must equal the Worker's `SEND_TOKEN`)

## Workflow notes

- The cron pushes `data.json` directly to `main` (branch protection bypassed via a PAT if configured, otherwise requires the Actions bot exemption)
- **Commits only land when an item actually changes, and the message names what changed.** The checker diffs this run against the previous `data.json` (`diffRuns`): the `updated` timestamp moves only on a *meaningful* change (a card's effective answer or tone, or an added/removed item) — not on pure display churn like the live gas price ticking, which still gets committed so the page stays fresh but leaves `updated` alone. A date-driven release that slips past between runs is caught by resolving the prior run against its own `updated` clock vs `today`, even though the stored `releaseDate` never changes. When nothing changed at all the file is byte-identical and the workflow's `git diff --staged --quiet` guard skips the commit. The checker writes the commit message to `COMMIT_MSG_PATH` (a one-line summary + bullet body via `buildCommitMessage`); the workflow commits with `-F` that file, falling back to `chore: update item status`. `diffRuns`/`buildCommitMessage`/`RunChange` are `internal` and unit-tested.
- `exitProcess(0)` at the end of `main()` is intentional — kills Ktor's CIO background threads cleanly
- Model-list endpoints are free metadata calls, no token cost

## Deployment

- Hosted on GitHub Pages from `main` branch root
- Custom domain: `iswhateveroutyet.com` (Cloudflare DNS, gray cloud, A records → GitHub IPs)
- `isclaudesonnet5outyet.com` redirects to `iswhateveroutyet.com` via Cloudflare redirect rule
