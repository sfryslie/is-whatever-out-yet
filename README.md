# Is whatever out yet?

A multi-item release tracker hosted on GitHub Pages at [iswhateveroutyet.com](https://iswhateveroutyet.com).

Tracks AI models, games, people, and other things that may or may not exist yet. Checks run every 30 minutes via GitHub Actions and write results to `data.json`, which the static frontend reads.

## How it works

- **Frontend** — `index.html`, vanilla JS, no build step. Fetches `data.json` and renders cards grouped by category.
- **Checker** — Kotlin + Ktor Client (`checker/`), runs as a GitHub Actions cron job. Writes updated `data.json` back to the repo.
- **Check types:**
  - `Hardcoded` — answer never changes, set in code
  - `ScheduledDate` — shows countdown, flips to "Yes." on release day
  - `Anthropic` — live check against `/v1/models`
  - `OpenAI` — live check against `/v1/models` (optional, skipped if no key)
  - `Gemini` — live check against `/v1beta/models` (optional, skipped if no key)
  - `HomestarRunner` — checks homestarrunner.com sitemap for sbemail211

## Required secrets

| Secret | Required | Purpose |
|--------|----------|---------|
| `ANTHROPIC_API_KEY` | Yes | Anthropic model list checks |
| `OPENAI_API_KEY` | No | OpenAI model list checks |
| `GOOGLE_API_KEY` | No | Gemini model list checks |
| `XAI_API_KEY` | No | xAI/Grok model list checks |
| `NTFY_TOPIC` | No | [ntfy](https://ntfy.sh) topic for push notifications when something flips to "out" or someone dies |

Add these under Settings → Secrets and variables → Actions.

## Adding an item

In [`checker/src/main/kotlin/Main.kt`](checker/src/main/kotlin/Main.kt), add an entry to `ITEMS`:

```kotlin
Item("my-item-id", "My Item Label", "Category", Check.Hardcoded, "No.", "Optional detail text."),
```

Then add a matching placeholder entry to `data.json` so the frontend shows something before the next Action run.

## Running locally

```bash
cd checker
ANTHROPIC_API_KEY=sk-ant-... gradle run
```

Output is written to `../data.json` by default, or to `DATA_JSON_PATH` if set.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free to use, study, modify, and share for any
noncommercial purpose (personal projects, hobby use, education, research, nonprofits, etc.).
Commercial use requires a separate license. This covers both the code and the site's content
(the item copy). Not an OSI "open source" license, by design.
