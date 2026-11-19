// Backend credentials only. Never receives/stores an app ID token, and never includes provider
// responses, keys, library contents or account IDs in errors/logs.
const MAX_RESPONSE = 8 * 1024 * 1024;
export class LegacyUnavailable extends Error {
  constructor(code, retryMs = 60_000) { super(code); this.code = code; this.retryMs = retryMs; }
}
const fields = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, encode(v)]));
function encode(v) {
  if (v == null) return { nullValue: null };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (typeof v === 'string') return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(encode) } };
  return { mapValue: { fields: fields(v) } };
}
function decode(v) {
  if ('nullValue' in v) return null;
  if ('booleanValue' in v) return v.booleanValue;
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('stringValue' in v) return v.stringValue;
  if ('timestampValue' in v) return v.timestampValue;
  if ('referenceValue' in v) return v.referenceValue;
  if ('bytesValue' in v) return v.bytesValue;
  if ('geoPointValue' in v) return v.geoPointValue;
  if ('arrayValue' in v) return (v.arrayValue.values ?? []).map(decode);
  if ('mapValue' in v) return decodeFields(v.mapValue.fields ?? {});
  throw new LegacyUnavailable('legacy-shape');
}
const decodeFields = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, decode(v)]));
const b64 = (bytes) => btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const part = (o) => b64(new TextEncoder().encode(JSON.stringify(o)));

async function boundedJson(response) {
  const reader = response.body?.getReader();
  if (!reader) throw new LegacyUnavailable('legacy-response');
  let size = 0; const chunks = [];
  for (;;) {
    const { done, value } = await reader.read(); if (done) break;
    size += value.length;
    if (size > MAX_RESPONSE) { await reader.cancel(); throw new LegacyUnavailable('legacy-too-large'); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size); let at = 0;
  for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
  try { return JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new LegacyUnavailable('legacy-response'); }
}

export class LibraryFirestore {
  constructor(env, fetcher = (url, init) => fetch(url, init), claim = async () => {}) { this.env = env; this.fetcher = fetcher; this.claim = claim; }

  async bearer() {
    if (this.env.DEV_AUTH === 'local-tests-only' && this.env.LIBRARY_FIRESTORE_EMULATOR) return 'owner';
    if (this.cached && this.cached.until > Date.now()) return this.cached.token;
    let key;
    try { key = JSON.parse(this.env.LIBRARY_FIRESTORE_SERVICE_ACCOUNT ?? ''); }
    catch { throw new LegacyUnavailable('legacy-credentials'); }
    if (key.project_id !== this.env.FIREBASE_PROJECT_ID || typeof key.client_email !== 'string' || typeof key.private_key !== 'string')
      throw new LegacyUnavailable('legacy-credentials');
    const now = Math.floor(Date.now() / 1000);
    const unsigned = `${part({ alg: 'RS256', typ: 'JWT' })}.${part({ iss: key.client_email,
      scope: 'https://www.googleapis.com/auth/datastore', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3000 })}`;
    try {
      const raw = Uint8Array.from(atob(key.private_key.replace(/-----[^-]+-----|\s/g, '')), (c) => c.charCodeAt(0));
      const cryptoKey = await crypto.subtle.importKey('pkcs8', raw, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
      const signature = b64(new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', cryptoKey, new TextEncoder().encode(unsigned))));
      const response = await this.fetcher('https://oauth2.googleapis.com/token', { method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, signal: AbortSignal.timeout(10_000),
        body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}` }) });
      if (!response.ok) throw new Error();
      const answer = await boundedJson(response);
      if (typeof answer.access_token !== 'string' || !(answer.expires_in > 120)) throw new Error();
      this.cached = { token: answer.access_token, until: Date.now() + (answer.expires_in - 120) * 1000 };
      return this.cached.token;
    } catch { throw new LegacyUnavailable('legacy-credentials'); }
  }

  async request(method, suffix, body, kind = 'read') {
    const token = await this.bearer();
    await this.claim(kind, kind === 'write' ? body?.writes?.length ?? 1 : 1);
    const test = this.env.DEV_AUTH === 'local-tests-only' && this.env.LIBRARY_FIRESTORE_EMULATOR;
    const origin = test ? this.env.LIBRARY_FIRESTORE_EMULATOR : 'https://firestore.googleapis.com';
    if (test && !/^http:\/\/127\.0\.0\.1:\d+$/.test(origin)) throw new LegacyUnavailable('legacy-config');
    try {
      return await this.fetcher(`${origin}/v1/projects/${encodeURIComponent(this.env.FIREBASE_PROJECT_ID)}/databases/(default)${suffix}`, {
        method, headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10_000),
      });
    } catch { throw new LegacyUnavailable('legacy-network'); }
  }

  async get(path) {
    const r = await this.request('GET', `/documents/${path.split('/').map(encodeURIComponent).join('/')}`);
    if (r.status === 404) {
      await r.body?.cancel();
      if (!path.startsWith('users/')) return { exists: false, updateTime: null, data: null };
      // A nonexistent project/database is not a new account. Verify the parent before accepting
      // a missing source. Metadata authorization failure also leaves the account pending.
      const database = await this.request('GET', '');
      if (!database.ok) throw new LegacyUnavailable(`legacy-parent-${database.status}`);
      await database.body?.cancel();
      return { exists: false, updateTime: null, data: null };
    }
    if (!r.ok) { await r.body?.cancel(); throw new LegacyUnavailable(`legacy-${r.status}`, r.status === 429 ? 15 * 60_000 : 60_000); }
    const doc = await boundedJson(r);
    if (typeof doc.updateTime !== 'string' || !doc.name?.endsWith(`/documents/${path}`)) throw new LegacyUnavailable('legacy-shape');
    return { exists: true, updateTime: doc.updateTime, data: decodeFields(doc.fields ?? {}) };
  }

  async compareAndSet(uid, source, document, pending) {
    const write = { update: { name: `projects/${this.env.FIREBASE_PROJECT_ID}/databases/(default)/documents/users/${uid}`, fields: fields(document) },
      updateMask: { fieldPaths: Object.keys(document) },
      currentDocument: source.exists ? { updateTime: source.updateTime } : { exists: false } };
    const marker = { update: { name: `projects/${this.env.FIREBASE_PROJECT_ID}/databases/(default)/documents/libraryBridgeJournal/${uid}`,
      fields: fields({ id: pending.id, checksum: pending.checksum }) } };
    const r = await this.request('POST', '/documents:commit', { writes: [write, marker] }, 'write');
    if ([409, 412].includes(r.status)) { await r.body?.cancel(); return null; }
    if (!r.ok) { await r.body?.cancel(); throw new LegacyUnavailable(`legacy-${r.status}`, r.status === 429 ? 15 * 60_000 : 60_000); }
    const result = await boundedJson(r);
    const updateTime = result.writeResults?.[0]?.updateTime;
    if (!updateTime) throw new LegacyUnavailable('legacy-write-response');
    return updateTime;
  }

  async removeFence(uid, updateTime) {
    const r = await this.request('DELETE', `/documents/libraryAuthority/${encodeURIComponent(uid)}?currentDocument.updateTime=${encodeURIComponent(updateTime)}`, null, 'write');
    if (r.ok || r.status === 404) { await r.body?.cancel(); return; }
    await r.body?.cancel(); throw new LegacyUnavailable(`legacy-fence-${r.status}`);
  }

  async deleteMarker(uid) {
    const r = await this.request('DELETE', `/documents/libraryBridgeJournal/${encodeURIComponent(uid)}`, null, 'write');
    if (r.ok || r.status === 404) { await r.body?.cancel(); return; }
    await r.body?.cancel(); throw new LegacyUnavailable(`legacy-cleanup-${r.status}`, 15 * 60_000);
  }
}
