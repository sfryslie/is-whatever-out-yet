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
.github/workflows/check-models.yml  # Cron job (every 30 min) that runs the checker and commits data.json
```

## Adding or editing items

Everything lives in `ITEMS` in `Main.kt`. Each item is:

```kotlin
Item(id, label, category, check, defaultAnswer, defaultDetail)
```

- `defaultAnswer` defaults to `"No."`, `defaultDetail` defaults to `null`
- Always add a matching entry in `data.json` so the site shows something before the next run

### Check types

| Check | Behavior |
|-------|----------|
| `Check.Hardcoded` | Uses `defaultAnswer`/`defaultDetail`, never changes |
| `Check.ScheduledDate(date)` | Shows countdown; flips to "Yes." on/after `date` |
| `Check.Anthropic(pattern)` | Searches Anthropic `/v1/models` for an ID containing `pattern` |
| `Check.OpenAI(pattern)` | Same for OpenAI `/v1/models`; skipped if `OPENAI_API_KEY` unset |
| `Check.Gemini(pattern)` | Same for Google `/v1beta/models`; skipped if `GOOGLE_API_KEY` unset |
| `Check.HomestarRunner` | Checks homestarrunner.com sitemap for sbemail211 |

## Card coloring

Driven by `cardClass()` in `index.html`:

- Starts with `"yes"` → green (`yes` class)
- Starts with `"no"` or equals `"never."` → dark/muted (`no` class)
- Contains `"soon"` or `"next year"` → amber (`soon` class)
- Anything else → blue (`other` class) — used for dates, "Chill.", etc.

## Secrets (GitHub Actions)

- `ANTHROPIC_API_KEY` — required
- `OPENAI_API_KEY` — optional, live OpenAI check skipped if absent
- `GOOGLE_API_KEY` — optional, live Gemini check skipped if absent

## Workflow notes

- The cron pushes `data.json` directly to `main` (branch protection bypassed via a PAT if configured, otherwise requires the Actions bot exemption)
- `exitProcess(0)` at the end of `main()` is intentional — kills Ktor's CIO background threads cleanly
- Model-list endpoints are free metadata calls, no token cost

## Deployment

- Hosted on GitHub Pages from `main` branch root
- Custom domain: `iswhateveroutyet.com` (Cloudflare DNS, gray cloud, A records → GitHub IPs)
- `isclaudesonnet5outyet.com` redirects to `iswhateveroutyet.com` via Cloudflare redirect rule
