// Cloudflare Worker: Web Push backend for "Is whatever out yet?"
//
// Dependency-free — implements VAPID (RFC 8292) and aes128gcm payload encryption (RFC 8291/8188)
// with the Workers Web Crypto API. The static site subscribes browsers here; the GitHub Action
// posts changes to /send and this Worker fans them out (encrypted) to the matching subscribers.
//
// Endpoints:
//   GET  /key         → { key }  the VAPID public key, for the frontend's applicationServerKey
//   POST /subscribe   → { subscription, topics }  store/replace a browser push subscription
//   POST /unsubscribe → { endpoint }  remove it
//   POST /send        → { topics, title, message, url, tag }  (Bearer SEND_TOKEN)  fan out a push
//
// Secrets (wrangler secret put …): VAPID_PUBLIC, VAPID_PRIVATE, VAPID_SUBJECT, SEND_TOKEN
// Optional var: ALLOWED_ORIGIN (CORS; defaults to "*")
// KV binding: SUBS

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

      if (request.method === 'POST' && url.pathname === '/send') {
        if ((request.headers.get('Authorization') || '') !== `Bearer ${env.SEND_TOKEN}`) {
          return json({ error: 'unauthorized' }, 401, cors);
        }
        const body = await request.json();
        const wanted = new Set(body.topics || []);
        if (wanted.size === 0) return json({ sent: 0 }, 200, cors);
        const payload = JSON.stringify({
          title: body.title || 'Is whatever out yet?',
          body: body.message || '',
          url: body.url || 'https://iswhateveroutyet.com',
          tag: body.tag || '',
        });
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
async function fanOut(env, wanted, payload) {
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
      const raw = await env.SUBS.get(k.name);
      if (!raw) continue;
      const result = await sendOne(env, JSON.parse(raw), payload);
      if (result === 'gone') await env.SUBS.delete(k.name);
      else if (result === true) sent++;
    }
  } while (cursor);
  return sent;
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
