// Service worker for "Is whatever out yet?"
//
// Two caching strategies:
//   - the app shell (HTML/manifest/icons) is cache-first so the site opens instantly and works
//     offline once installed;
//   - the data/ JSON files (index + per-category) are network-first so an online user always sees
//     the freshest status, falling back to the last cached copy when offline.
//
// Bump CACHE_VERSION whenever the shell changes to evict the old cache.
const CACHE_VERSION = 'iwoy-v9';

// Push Worker base URL — must match PUSH_API in index.html.
const PUSH_API = 'https://iswhateveroutyet-push.iswhateveroutyet-push.workers.dev';
const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // Network-first for the live data so it never goes stale while online.
  if (/\/data\/[^/]+\.json$/.test(url.pathname)) {
    event.respondWith(
      fetch(request)
        .then((resp) => {
          const copy = resp.clone();
          caches.open(CACHE_VERSION).then((cache) => cache.put(request, copy));
          return resp;
        })
        .catch(() => caches.match(request))
    );
    return;
  }

  // Cache-first for everything else (the shell), refreshing the cache in the background.
  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((resp) => {
          if (resp && resp.status === 200 && resp.type === 'basic') {
            const copy = resp.clone();
            caches.open(CACHE_VERSION).then((cache) => cache.put(request, copy));
          }
          return resp;
        })
        .catch(() => cached);
      return cached || network;
    })
  );
});

// ── Web Push ──────────────────────────────────────────────────────────────────
// The push Worker delivers a JSON payload: { title, body, url, tag }.
self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (e) { /* keep defaults */ }
  const title = data.title || 'Is whatever out yet?';
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || '',
      icon: 'icons/icon-192.png',
      badge: 'icons/icon-192.png',
      tag: data.tag || undefined,
      data: { url: data.url || 'https://iswhateveroutyet.com' },
    })
  );
});

// The browser rotated (or revoked and reissued) this device's push subscription. Chrome on Android
// does this on its own schedule, and the old endpoint then 410s and gets pruned Worker-side — so
// without this handler a device goes quietly dark while its 🔔 bells still read as "on".
// Re-subscribe with the same VAPID key and ask the Worker to carry the old endpoint's topics over.
// `newSubscription` is often absent (Chrome), hence the manual re-subscribe fallback.
self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil(
    (async () => {
      const oldEndpoint = event.oldSubscription?.endpoint;
      try {
        let sub = event.newSubscription;
        if (!sub) {
          const { key } = await (await fetch(PUSH_API + '/key')).json();
          sub = await self.registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: urlB64ToU8(key),
          });
        }
        await fetch(PUSH_API + '/resubscribe', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ oldEndpoint, subscription: sub.toJSON() }),
        });
      } catch (e) {
        // Best effort — the page's reconcile pass re-registers next time the site is opened.
      }
    })(),
  );
});

function urlB64ToU8(base64) {
  const pad = '='.repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + pad).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(b64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || 'https://iswhateveroutyet.com';
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const client of list) {
        if (client.url === url && 'focus' in client) return client.focus();
      }
      return clients.openWindow(url);
    })
  );
});
