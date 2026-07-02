# Push Worker

A tiny, dependency-free Cloudflare Worker that delivers the site's push notifications — Web Push
for browsers and (optionally) native FCM for the KMP app in [`app/`](../app). It stores browser
push subscriptions and native device tokens in KV and, when the checker POSTs a change to `/send`,
fans the notification out to every matching subscriber in one pass. VAPID + aes128gcm and the FCM
service-account OAuth flow are implemented directly on the Workers Web Crypto API — no npm
dependencies, nothing to keep patched.

## Endpoints

| Method | Path                 | Who calls it      | Body                                   |
|--------|----------------------|-------------------|----------------------------------------|
| GET    | `/key`               | the website       | —                                      |
| POST   | `/subscribe`         | the website       | `{ subscription, topics }`             |
| POST   | `/unsubscribe`       | the website       | `{ endpoint }`                         |
| POST   | `/register-native`   | the KMP app       | `{ token, platform, topics }`          |
| POST   | `/unregister-native` | the KMP app       | `{ token }`                            |
| POST   | `/send`              | the GitHub Action | `{ topics, title, message, url, tag }` (Bearer `SEND_TOKEN`) |

## One-time setup

```bash
cd push-worker
npm i -g wrangler        # if you don't have it
wrangler login

# 1. KV namespace for subscriptions — paste the printed id into wrangler.toml
wrangler kv namespace create SUBS

# 2. VAPID keypair (the signing keys for Web Push)
npx web-push generate-vapid-keys
#   → copy the Public Key and Private Key

# 3. Secrets
wrangler secret put VAPID_PUBLIC      # the public key from step 2
wrangler secret put VAPID_PRIVATE     # the private key from step 2
wrangler secret put VAPID_SUBJECT     # e.g. mailto:you@example.com
wrangler secret put SEND_TOKEN        # any long random string (shared with the Action)

# 4. Deploy
wrangler deploy
```

`wrangler deploy` prints the Worker URL (e.g. `https://iswhateveroutyet-push.<you>.workers.dev`).

Then wire the two ends to it:

- **Frontend:** set `PUSH_API` in `index.html` to that URL (no trailing slash) and redeploy the site.
- **GitHub Action:** add repo secrets `PUSH_API_URL` (the Worker URL) and `PUSH_SEND_TOKEN` (the same
  value as `SEND_TOKEN`).

## Test it

```bash
# Should return your VAPID public key:
curl https://iswhateveroutyet-push.<you>.workers.dev/key

# Subscribe a browser via the site (tap a 🔔), then fake a change:
curl -X POST https://iswhateveroutyet-push.<you>.workers.dev/send \
  -H "Authorization: Bearer <SEND_TOKEN>" -H "Content-Type: application/json" \
  -d '{"topics":["iswhateveroutyet-all"],"title":"Test","message":"It works!","url":"https://iswhateveroutyet.com"}'
# → {"sent":1}
```

Dead subscriptions (uninstalled browsers) are pruned automatically when `/send` gets a 404/410 back.

## Native push (the KMP app) — optional

The Android/iOS app registers FCM device tokens here (`/register-native`) instead of Web Push
subscriptions, and `/send` delivers to them through FCM's HTTP v1 API — Android directly, iOS
relayed through APNs by Firebase. Nothing on the checker side changes; the same `/send` reaches
browsers and phones. Without these secrets the native path is skipped silently:

```bash
# From your Firebase project (Project settings → Service accounts → Generate new private key):
wrangler secret put FCM_PROJECT_ID     # the Firebase project id
wrangler secret put FCM_CLIENT_EMAIL   # "client_email" from the service-account JSON
wrangler secret put FCM_PRIVATE_KEY    # "private_key" from the JSON (paste as-is, \n and all)
```

Dead FCM tokens (app uninstalled) are pruned on 404 the same way dead browser subscriptions are.
See [`app/README.md`](../app/README.md) for the app-side Firebase setup.
