// Verifies a Firebase ID token the way Google's documentation lists, with no Firebase SDK, so the
// server never reads Firestore. It only runs on connecting, not for each message.

const KEYS_URL = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
const CLOCK_SKEW_S = 60;

let cachedKeys = null;
let cachedUntil = 0;

function decodePart(part) {
  const text = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='));
  return Uint8Array.from(text, (c) => c.charCodeAt(0));
}

export async function googleKeys(now = Date.now(), fetcher = fetch) {
  if (cachedKeys && now < cachedUntil) return cachedKeys;
  const response = await fetcher(KEYS_URL);
  if (!response.ok) {
    if (cachedKeys) return cachedKeys; // Google's hiccup must not sign everyone out.
    throw new Error(`keys ${response.status}`);
  }
  const maxAge = /max-age=(\d+)/.exec(response.headers.get('cache-control') ?? '')?.[1];
  cachedKeys = (await response.json()).keys;
  cachedUntil = now + Number(maxAge ?? 3600) * 1000;
  return cachedKeys;
}

/**
 * A test identity for the staging Worker, which has no real users. It needs a secret that only
 * staging has, and its accounts can never be a real uid.
 */
export function stagingIdentity(token, secret, now = Date.now()) {
  if (!secret || typeof token !== 'string') return null;
  const match = /^staging:([^:]+):([a-z0-9-]{1,40})$/.exec(token);
  if (!match) return null;
  const given = new TextEncoder().encode(match[1]);
  const wanted = new TextEncoder().encode(secret);
  if (given.length !== wanted.length) return null;
  let difference = 0;
  for (let i = 0; i < given.length; i += 1) difference |= given[i] ^ wanted[i];
  return difference === 0 ? { uid: `staging-${match[2]}`, expiresAtMs: now + 86_400_000 } : null;
}

/** The account a token proves, or null. Anonymous Listen Together guests have none here. */
export async function verifyToken(token, projectId, keys, nowMs = Date.now()) {
  const parts = typeof token === 'string' ? token.split('.') : [];
  if (parts.length !== 3) return null;
  let header, claims;
  try {
    header = JSON.parse(new TextDecoder().decode(decodePart(parts[0])));
    claims = JSON.parse(new TextDecoder().decode(decodePart(parts[1])));
  } catch {
    return null;
  }
  if (header.alg !== 'RS256') return null;
  const jwk = keys.find((k) => k.kid === header.kid);
  if (!jwk) return null;
  const now = nowMs / 1000;
  if (!(claims.exp > now) || !(claims.iat <= now + CLOCK_SKEW_S) || !(claims.auth_time <= now + CLOCK_SKEW_S)) return null;
  if (claims.aud !== projectId || claims.iss !== `https://securetoken.google.com/${projectId}`) return null;
  if (typeof claims.sub !== 'string' || !claims.sub || claims.sub.length > 128) return null;
  if (claims.firebase?.sign_in_provider === 'anonymous') return null;
  const key = await crypto.subtle.importKey('jwk', { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: 'RS256', ext: true },
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const signed = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, decodePart(parts[2]), signed);
  return valid ? { uid: claims.sub, expiresAtMs: claims.exp * 1000 } : null;
}
