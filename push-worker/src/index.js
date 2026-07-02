// Cloudflare Worker: push backend for "Is whatever out yet?" — Web Push + native FCM.
//
// Dependency-free — implements VAPID (RFC 8292) and aes128gcm payload encryption (RFC 8291/8188)
// with the Workers Web Crypto API. The static site subscribes browsers here; the KMP app (app/)
// registers native FCM device tokens; the GitHub Action posts changes to /send and this Worker
// fans them out to both kinds of subscriber in one pass.
//
// Endpoints:
//   GET  /key               → { key }  the VAPID public key, for the frontend's applicationServerKey
//   POST /subscribe         → { subscription, topics }  store/replace a browser push subscription
//   POST /unsubscribe       → { endpoint }  remove it
//   POST /register-native   → { token, platform, topics }  store/replace a native FCM device token
//   POST /unregister-native → { token }  remove it
//   POST /send              → { topics, title, message, url, tag }  (Bearer SEND_TOKEN)  fan out a push
//
// Secrets (wrangler secret put …): VAPID_PUBLIC, VAPID_PRIVATE, VAPID_SUBJECT, SEND_TOKEN
// Optional FCM secrets (native push; skipped if absent — see README):
//   FCM_PROJECT_ID, FCM_CLIENT_EMAIL, FCM_PRIVATE_KEY (service-account key, PKCS8 PEM)
// Optional var: ALLOWED_ORIGIN (CORS; defaults to "*")
// KV binding: SUBS (web subs keyed by endpoint URL; native tokens keyed "fcm:<token>")

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const cors = corsHeaders(env);
    if (request.method === 'OPTIONS') return new Response(null, { headers: cors });

    try {
      if (request.method === 'GET' && url.pathname === '/key') {
        return json({ key: env.VAPID_PUBLIC }, 200, cors);
      }

      if (request.method === 'POST' && url.pathname === '/subscribe') {
        const { subscription, topics } = await request.json();
        if (!subscription?.endpoint || !subscription?.keys || !Array.isArray(topics)) {
          return json({ error: 'bad request' }, 400, cors);
        }
        // Key by endpoint; keep the topic list in metadata so /send can match without a get-per-key.
        await env.SUBS.put(subscription.endpoint, JSON.stringify(subscription), {
          metadata: { topics: topics.slice(0, 300) },
        });
        return json({ ok: true }, 200, cors);
      }

      if (request.method === 'POST' && url.pathname === '/unsubscribe') {
        const { endpoint } = await request.json();
        if (endpoint) await env.SUBS.delete(endpoint);
        return json({ ok: true }, 200, cors);
      }

      // Native (Android/iOS) FCM device tokens — same KV namespace, "fcm:" key prefix so /send
      // can tell them apart from Web Push endpoints (which are https:// URLs).
      if (request.method === 'POST' && url.pathname === '/register-native') {
        const { token, platform, topics } = await request.json();
        if (!token || !Array.isArray(topics)) {
          return json({ error: 'bad request' }, 400, cors);
        }
        await env.SUBS.put(`fcm:${token}`, JSON.stringify({ token, platform: platform || '' }), {
          metadata: { topics: topics.slice(0, 300) },
        });
        return json({ ok: true }, 200, cors);
      }

      if (request.method === 'POST' && url.pathname === '/unregister-native') {
        const { token } = await request.json();
        if (token) await env.SUBS.delete(`fcm:${token}`);
        return json({ ok: true }, 200, cors);
      }

      if (request.method === 'POST' && url.pathname === '/send') {
        if ((request.headers.get('Authorization') || '') !== `Bearer ${env.SEND_TOKEN}`) {
          return json({ error: 'unauthorized' }, 401, cors);
        }
        const body = await request.json();
        const wanted = new Set(body.topics || []);
        if (wanted.size === 0) return json({ sent: 0 }, 200, cors);
        const payload = {
          title: body.title || 'Is whatever out yet?',
          body: body.message || '',
          url: body.url || 'https://iswhateveroutyet.com',
          tag: body.tag || '',
        };
        const sent = await fanOut(env, wanted, payload);
        return json({ sent }, 200, cors);
      }

      return json({ error: 'not found' }, 404, cors);
    } catch (e) {
      return json({ error: String((e && e.message) || e) }, 500, cors);
    }
  },
};

function corsHeaders(env) {
  return {
    'Access-Control-Allow-Origin': env.ALLOWED_ORIGIN || '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Max-Age': '86400',
  };
}

function json(obj, status, cors) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json', ...cors },
  });
}

// Walk every stored subscription, push to those whose topics intersect `wanted`, prune dead ones.
// Web Push endpoints and native FCM tokens live side by side; the "fcm:" prefix picks the transport.
async function fanOut(env, wanted, payload) {
  const webPayload = JSON.stringify(payload);
  let cursor;
  let sent = 0;
  const seen = new Set();
  do {
    const list = await env.SUBS.list({ cursor, limit: 1000 });
    cursor = list.list_complete ? null : list.cursor;
    for (const k of list.keys) {
      if (seen.has(k.name)) continue;
      const topics = k.metadata?.topics || [];
      if (!topics.some((t) => wanted.has(t))) continue;
      seen.add(k.name);
      const result = k.name.startsWith('fcm:')
        ? await sendFcm(env, k.name.slice(4), payload)
        : await sendWeb(env, k.name, webPayload);
      if (result === 'gone') await env.SUBS.delete(k.name);
      else if (result === true) sent++;
    }
  } while (cursor);
  return sent;
}

async function sendWeb(env, key, webPayload) {
  const raw = await env.SUBS.get(key);
  if (!raw) return false;
  return sendOne(env, JSON.parse(raw), webPayload);
}

// ── Native push: FCM HTTP v1 ─────────────────────────────────────────────────
// One send path covers both platforms: Android delivers directly, iOS relays through APNs
// via Firebase. Skipped quietly (returns false, keeps the token) if FCM secrets are unset.

let cachedFcmToken = null; // { token, exp } — per-isolate OAuth token cache

async function sendFcm(env, deviceToken, payload) {
  if (!env.FCM_PROJECT_ID || !env.FCM_CLIENT_EMAIL || !env.FCM_PRIVATE_KEY) return false;
  try {
    const access = await fcmAccessToken(env);
    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: {
            token: deviceToken,
            notification: { title: payload.title, body: payload.body },
            data: { url: payload.url || '', tag: payload.tag || '' },
            android: payload.tag ? { notification: { tag: payload.tag } } : undefined,
            apns: payload.tag
              ? { payload: { aps: { 'thread-id': payload.tag } } }
              : undefined,
          },
        }),
      },
    );
    if (res.status === 404) return 'gone'; // UNREGISTERED — device uninstalled the app
    return res.ok;
  } catch (e) {
    return false;
  }
}

// Service-account OAuth: RS256 JWT → https://oauth2.googleapis.com/token, scoped to messaging.
async function fcmAccessToken(env) {
  if (cachedFcmToken && Date.now() < cachedFcmToken.exp) return cachedFcmToken.token;
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(utf8(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64url(utf8(JSON.stringify({
    iss: env.FCM_CLIENT_EMAIL,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  })));
  const signingInput = `${header}.${claims}`;
  const key = await importFcmPrivate(env);
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, utf8(signingInput));
  const jwt = `${signingInput}.${b64url(new Uint8Array(sig))}`;
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body:
      'grant_type=' + encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer') +
      '&assertion=' + jwt,
  });
  if (!res.ok) throw new Error('FCM OAuth token exchange failed: ' + res.status);
  const data = await res.json();
  cachedFcmToken = { token: data.access_token, exp: Date.now() + (data.expires_in - 60) * 1000 };
  return cachedFcmToken.token;
}

function importFcmPrivate(env) {
  // Accept the key as pasted from the service-account JSON: literal "\n" sequences or real newlines.
  const pem = env.FCM_PRIVATE_KEY.replace(/\\n/g, '\n');
  const raw = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, '')
    .replace(/-----END PRIVATE KEY-----/, '')
    .replace(/\s+/g, '');
  return crypto.subtle.importKey(
    'pkcs8',
    b64urlDecode(raw), // handles standard base64 too (no -/_ present, padding tolerated)
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

// ── Web Push: VAPID auth + aes128gcm encrypted payload ───────────────────────
async function sendOne(env, subscription, payloadStr) {
  try {
    const audience = new URL(subscription.endpoint).origin;
    const authorization = await vapidHeader(env, audience);
    const body = await encryptPayload(subscription, new TextEncoder().encode(payloadStr));
    const res = await fetch(subscription.endpoint, {
      method: 'POST',
      headers: {
        Authorization: authorization,
        'Content-Encoding': 'aes128gcm',
        'Content-Type': 'application/octet-stream',
        TTL: '86400',
      },
      body,
    });
    if (res.status === 404 || res.status === 410) return 'gone';
    return res.ok;
  } catch (e) {
    return false;
  }
}

// VAPID JWT (ES256), per RFC 8292. Header: `vapid t=<jwt>, k=<public key>`.
async function vapidHeader(env, audience) {
  const header = b64url(utf8(JSON.stringify({ typ: 'JWT', alg: 'ES256' })));
  const claims = b64url(utf8(JSON.stringify({
    aud: audience,
    exp: Math.floor(Date.now() / 1000) + 12 * 3600,
    sub: env.VAPID_SUBJECT || 'mailto:admin@iswhateveroutyet.com',
  })));
  const signingInput = `${header}.${claims}`;
  const key = await importVapidPrivate(env);
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, utf8(signingInput));
  return `vapid t=${signingInput}.${b64url(new Uint8Array(sig))}, k=${env.VAPID_PUBLIC}`;
}

async function importVapidPrivate(env) {
  const pub = b64urlDecode(env.VAPID_PUBLIC); // 65-byte uncompressed point: 0x04 | X(32) | Y(32)
  const jwk = {
    kty: 'EC',
    crv: 'P-256',
    x: b64url(pub.slice(1, 33)),
    y: b64url(pub.slice(33, 65)),
    d: env.VAPID_PRIVATE, // base64url 32-byte scalar
    ext: true,
    key_ops: ['sign'],
  };
  return crypto.subtle.importKey('jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
}

// Encrypt `plaintext` for `subscription` using aes128gcm (RFC 8291 + RFC 8188), single record.
async function encryptPayload(subscription, plaintext) {
  const uaPublic = b64urlDecode(subscription.keys.p256dh); // 65 bytes
  const authSecret = b64urlDecode(subscription.keys.auth); // 16 bytes

  // Ephemeral application-server ECDH keypair.
  const asKeys = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const asPublic = new Uint8Array(await crypto.subtle.exportKey('raw', asKeys.publicKey)); // 65 bytes

  const uaKey = await crypto.subtle.importKey('raw', uaPublic, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const ecdhSecret = new Uint8Array(
    await crypto.subtle.deriveBits({ name: 'ECDH', public: uaKey }, asKeys.privateKey, 256),
  );

  // IKM = HKDF(salt=auth, ikm=ecdhSecret, info="WebPush: info\0"|uaPub|asPub)
  const keyInfo = concat(utf8('WebPush: info\0'), uaPublic, asPublic);
  const ikm = await hkdf(authSecret, ecdhSecret, keyInfo, 32);

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const cek = await hkdf(salt, ikm, utf8('Content-Encoding: aes128gcm\0'), 16);
  const nonce = await hkdf(salt, ikm, utf8('Content-Encoding: nonce\0'), 12);

  const aesKey = await crypto.subtle.importKey('raw', cek, { name: 'AES-GCM' }, false, ['encrypt']);
  const padded = concat(plaintext, new Uint8Array([0x02])); // single/last record delimiter
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, tagLength: 128 }, aesKey, padded),
  );

  // aes128gcm header: salt(16) | rs(4, BE) | idlen(1) | keyid(asPublic)
  const header = new Uint8Array(16 + 4 + 1 + asPublic.length);
  header.set(salt, 0);
  new DataView(header.buffer).setUint32(16, 4096, false);
  header[20] = asPublic.length;
  header.set(asPublic, 21);
  return concat(header, ciphertext);
}

// HKDF extract+expand (RFC 5869) via Web Crypto.
async function hkdf(salt, ikm, info, length) {
  const key = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info }, key, length * 8);
  return new Uint8Array(bits);
}

// ── byte / base64url helpers ─────────────────────────────────────────────────
function utf8(s) {
  return new TextEncoder().encode(s);
}
function b64url(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function b64urlDecode(str) {
  const s = str.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((str.length + 3) % 4);
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
function concat(...arrays) {
  const total = arrays.reduce((n, a) => n + a.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const a of arrays) {
    out.set(a, offset);
    offset += a.length;
  }
  return out;
}
