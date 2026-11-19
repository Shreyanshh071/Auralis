// One account's library: playlists, liked songs, saved albums and artists, pins. Each account is
// one SQLite Durable Object. Unlike Connect's playback state, nothing here is kept only in memory
// or on a socket: an edit is in storage before it is acknowledged or shown to another device.
//
// The object also owns which system is the account's library (legacy Firestore, migrating, or
// here), so there is one place to ask. See docs/library-sync/spec.md.
import { DurableObject } from 'cloudflare:workers';
import { AutomaticLibrary } from './library-auto.js';
import { LibraryFirestore, LegacyUnavailable } from './library-firestore.js';
import { sameLibrary } from './library-bridge-core.js';
import { RateLimit, validDeviceId } from './core.js';
import { IMPORT_DAILY_ROWS, MAX_PUSH_EVENTS, clearBefore, dayOf, mergeListens } from './listens-core.js';
import { MAX_SETTINGS_KEYS, RETIRE_ACKNOWLEDGEMENT, SETTINGS_PLATFORMS, bridgeActive, mergeSettings, preference, topicEnabled } from './settings-core.js';
import { CHUNK_TRACKS, ITEM, MAX_BODY_BYTES, MAX_ITEMS, MAX_OPS, MAX_PLAYLISTS, OP_MEMORY_COUNT, OP_MEMORY_MS, PAGE_BYTES,
  PROTOCOL_VERSION, REMOVAL_MEMORY_COUNT, REMOVAL_MEMORY_MS, checksum, chunks, counts, library, operation, pilotAllowed, sameSecret, validId,
} from './library-core.js';

const CLOSE_EXPIRED = 4401;
const CLOSE_TOO_FAST = 4429;
const CLOSE_TOO_LARGE = 4413;
const CLOSE_REPLACED = 4409;
const MAX_SOCKETS = 16;
const REFUSALS_BEFORE_CLOSE = 200;
const MAX_PUSHED_DELTA_BYTES = 256 * 1024;
const STATES = ['legacy', 'migrating', 'cloudflare'];

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

/** The Worker's part: who is asking, and which account that reaches. */
export async function libraryRoute(request, env, url, authenticate) {
  const path = url.pathname;
  const admin = /^\/v1\/library\/admin\/([^/]{1,128})\/([a-z-]{1,24})$/.exec(path);
  if (admin) {
    // Absent until an operator sets the secret, so a default deployment has no admin surface.
    if (!env.LIBRARY_ADMIN_SECRET || !sameSecret(request.headers.get('x-library-admin'), env.LIBRARY_ADMIN_SECRET)) {
      return new Response('not found', { status: 404 });
    }
    if (request.method !== 'POST') return new Response('post only', { status: 405 });
    const body = await readBody(request);
    if (body === null) return new Response('too large', { status: 413 });
    let uid;
    try { uid = decodeURIComponent(admin[1]); } catch { return json({ error: 'bad account' }, 400); }
    if (!validId(uid) || /[\\/]/.test(uid) || ['.', '..'].includes(uid)) return json({ error: 'bad account' }, 400);
    // The legacy manual migration tool retains its separate internal-pilot allowlist.
    if (admin[2] === 'begin' && !pilotAllowed(uid, env)) {
      return json({ error: 'not on the pilot allowlist' }, 403);
    }
    return env.LIBRARY.get(env.LIBRARY.idFromName(uid)).fetch(`https://account/admin/${admin[2]}`,
      { method: 'POST', body, headers: { 'x-tag': await tag(uid), 'x-uid': uid } });
  }
  const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';
  if (env.LIBRARY_REQUESTS && !(await env.LIBRARY_REQUESTS.limit({ key: ip })).success) {
    return new Response('too many requests', { status: 429, headers: { 'Retry-After': '60' } });
  }
  const token = /^Bearer (.+)$/.exec(request.headers.get('authorization') ?? '')?.[1];
  const who = await authenticate(token, env, url);
  if (!who) return new Response('unauthorized', { status: 401 });
  if (!validId(who.uid) || /[\\/]/.test(who.uid) || ['.', '..'].includes(who.uid)) return new Response('bad account', { status: 400 });
  const account = env.LIBRARY.get(env.LIBRARY.idFromName(who.uid));
  // Only these headers, set here, reach the account: nothing a client sent is believed.
  const headers = { 'x-uid': who.uid, 'x-expires': String(who.expiresAtMs), 'x-tag': await tag(who.uid) };
  if (path === '/v1/library/authority' || path === '/v1/library/bootstrap') {
    if (request.method !== 'GET') return new Response('get only', { status: 405 });
    return account.fetch(`https://account/${path.endsWith('/bootstrap') ? 'bootstrap' : 'authority'}`, { headers });
  }
  const device = url.searchParams.get('device');
  if (!validDeviceId(device)) return new Response('bad device', { status: 400 });
  headers['x-device'] = device;
  if (path === '/v1/library/rpc') {
    if (request.method !== 'POST') return new Response('post only', { status: 405 });
    const body = await readBody(request);
    if (body === null) return new Response('too large', { status: 413 });
    return account.fetch('https://account/rpc', { method: 'POST', headers, body });
  }
  if (path === '/v1/library') {
    if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') return new Response('websocket only', { status: 426 });
    return account.fetch('https://account/socket', { headers: { ...headers, upgrade: 'websocket' } });
  }
  return new Response('not found', { status: 404 });
}

/** A short, stable stand-in for a uid in logs: enough to follow one account, not to name it. */
async function tag(uid) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`auralis-library:${uid}`));
  return [...new Uint8Array(digest)].slice(0, 6).map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** One line per event for `wrangler tail` or Workers Logs. Never library content, tokens or uids. */
function event(name, fields) {
  try { console.log(JSON.stringify({ ev: `library.${name}`, ...fields })); } catch { /* logging must not break a request */ }
}

async function readBody(request) {
  const declared = Number(request.headers.get('content-length') ?? 0);
  if (declared > MAX_BODY_BYTES) return null;
  const body = await request.text();
  return body.length > MAX_BODY_BYTES ? null : body;
}

export class LibraryAccount extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    // WITHOUT ROWID: the primary key is the table, so a write is one billed row, not two.
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS playlists (id TEXT PRIMARY KEY, rev INTEGER NOT NULL, deleted_at INTEGER NOT NULL DEFAULT 0,
        body TEXT NOT NULL) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS playlist_chunks (playlist_id TEXT NOT NULL, seq INTEGER NOT NULL, body TEXT NOT NULL,
        PRIMARY KEY (playlist_id, seq)) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS items (kind TEXT NOT NULL, id TEXT NOT NULL, pos INTEGER NOT NULL, rev INTEGER NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0, body TEXT NOT NULL, PRIMARY KEY (kind, id)) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS ops (id TEXT PRIMARY KEY, rev INTEGER NOT NULL, at INTEGER NOT NULL) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS settings (platform TEXT NOT NULL, k TEXT NOT NULL, value TEXT NOT NULL, revision INTEGER NOT NULL,
        device TEXT NOT NULL, rev INTEGER NOT NULL, PRIMARY KEY (platform, k)) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS listen_days (day TEXT PRIMARY KEY, rev INTEGER NOT NULL, body TEXT NOT NULL) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS library_auto (k TEXT PRIMARY KEY, body TEXT NOT NULL) WITHOUT ROWID;
    `);
    // Keepalive that never wakes this object, so an idle connection does no storage work.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    this.m = null;
    this.limits = new Map();
    this.rows = { read: 0, written: 0 };
    this.work = Promise.resolve();
    this.autoCache = undefined;
    const firestore = new LibraryFirestore(env, (url, init) => fetch(url, init), async (kind, count) => {
      if (!env.LIBRARY_LEGACY_BUDGET) throw new LegacyUnavailable('legacy-budget-config');
      const budget = env.LIBRARY_LEGACY_BUDGET.get(env.LIBRARY_LEGACY_BUDGET.idFromName('legacy-io'));
      const response = await budget.fetch('https://budget/claim', { method: 'POST', body: JSON.stringify({ kind, count }) });
      if (!response.ok) throw new LegacyUnavailable('legacy-budget', 15 * 60_000);
    });
    this.automatic = new AutomaticLibrary({ firestore, load: () => this.autoState(), save: (s) => this.saveAuto(s),
      snapshot: () => this.snapshot(), commit: (lib, state, options) => this.commitAutomatic(lib, state, options) });
  }

  serial(work) {
    const next = this.work.then(async () => {
      try { return await work(); }
      catch (error) { this.m = null; this.autoCache = undefined; throw error; }
    });
    this.work = next.catch(() => {});
    return next;
  }

  autoState() {
    if (this.autoCache !== undefined) return this.autoCache;
    const parts = {};
    for (const row of this.q('SELECT k, body FROM library_auto ORDER BY k')) {
      const key = row.k.split(':')[0]; parts[key] = (parts[key] ?? '') + row.body;
    }
    if (!parts.meta) return (this.autoCache = null);
    const state = JSON.parse(parts.meta);
    if (parts.baseline) state.baseline = JSON.parse(parts.baseline);
    if (parts.source) state.imported.library = JSON.parse(parts.source);
    if (parts.pending) state.pending.library = JSON.parse(parts.pending);
    return (this.autoCache = state);
  }

  saveAuto(state, inTransaction = false) {
    const { baseline, imported, pending, ...meta } = state;
    const parts = { meta: JSON.stringify({ ...meta, imported: imported ? { ...imported, library: undefined } : undefined,
      pending: pending ? { ...pending, library: undefined } : null }) };
    if (baseline) parts.baseline = JSON.stringify(baseline);
    if (imported?.library) parts.source = JSON.stringify(imported.library);
    if (pending?.library) parts.pending = JSON.stringify(pending.library);
    const write = () => {
      const held = new Map(this.q('SELECT k, body FROM library_auto').map((r) => [r.k, r.body]));
      for (const [key, text] of Object.entries(parts)) for (let at = 0; at < text.length; at += 64 * 1024) {
        const id = `${key}:${String(at / (64 * 1024)).padStart(6, '0')}`; const body = text.slice(at, at + 64 * 1024);
        if (held.get(id) !== body) this.q('INSERT INTO library_auto VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET body=excluded.body', id, body);
        held.delete(id);
      }
      for (const id of held.keys()) this.q('DELETE FROM library_auto WHERE k = ?', id);
    };
    if (inTransaction) write(); else this.ctx.storage.transactionSync(write);
    this.autoCache = structuredClone(state);
  }

  commitAutomatic(lib, state, { importing }) {
    const before = this.meta().rev;
    try {
      this.ctx.storage.transactionSync(() => {
        const m = this.meta();
        // Retain operation deduplication records across a legacy catch-up/reset.
        for (const table of ['playlists', 'playlist_chunks', 'items']) this.q(`DELETE FROM ${table}`);
        Object.assign(m, { pos: 0, removed: 0, oldestRemoval: 0 });
        this.store(lib);
        if (!sameLibrary(lib, this.snapshot())) throw new LegacyUnavailable('legacy-verification');
        m.floor = m.rev;
        if (importing) { m.state = 'cloudflare'; m.epoch++; m.job = { dir: 'automatic', at: Date.now(), checksum: state.imported.checksum,
          counts: counts(lib), source: { exists: state.imported.exists, updateTime: state.imported.updateTime } }; }
        this.saveAuto(state, true); this.save();
      });
    } catch (error) { this.m = null; this.autoCache = undefined; throw error; }
    this.broadcast({ t: 'changed', from: before, rev: this.meta().rev, by: 'legacy-bridge' });
  }

  async bootstrap(uid) {
    const m = this.meta(); const state = this.autoState(); const now = Date.now();
    if (m.state === 'cloudflare') {
      if (state?.phase === 'ready') { await this.scheduleAutomatic(); }
      return json({ state: m.state, epoch: m.epoch, v: PROTOCOL_VERSION });
    }
    if (m.state === 'migrating' || state?.phase === 'paused')
      return json({ state: 'migrating', epoch: m.epoch, v: PROTOCOL_VERSION }, 202);
    if (this.env.LIBRARY_AUTO_IMPORT !== 'true')
      return json({ state: 'migrating', epoch: m.epoch, v: PROTOCOL_VERSION, reason: 'rollout-off' }, 202);
    const allowed = (this.env.LIBRARY_AUTO_UIDS ?? '').split(',').map((s) => s.trim());
    if (!allowed.includes('*') && !allowed.includes(uid))
      return json({ state: 'migrating', epoch: m.epoch, v: PROTOCOL_VERSION, reason: 'rollout-off' }, 202);
    if (state?.nextAt > now) return json({ state: 'migrating', epoch: m.epoch, v: PROTOCOL_VERSION,
      reason: state.error, retryAfterMs: state.nextAt - now }, 202);
    try {
      await this.automatic.initialize(uid);
      return json({ state: m.state, epoch: m.epoch, v: PROTOCOL_VERSION });
    } catch (error) {
      const failures = (state?.failures ?? 0) + 1;
      const retry = Math.max(error.retryMs ?? 60_000, Math.min(15 * 60_000, 15_000 * 2 ** Math.min(failures, 6)));
      this.saveAuto({ uid, phase: 'pending', failures, error: error.code ?? 'legacy-unavailable', nextAt: now + retry });
      return json({ state: 'migrating', epoch: m.epoch, v: PROTOCOL_VERSION, reason: error.code ?? 'legacy-unavailable', retryAfterMs: retry }, 202);
    }
  }

  async scheduleAutomatic() {
    const cleanup = this.meta().legacyCleanup;
    if (cleanup) {
      await this.ctx.storage.setAlarm(Math.max(Date.now() + 1000, cleanup.nextAt)); return;
    }
    const state = this.autoState();
    if (state?.phase !== 'ready' || this.meta().state !== 'cloudflare') return;
    if (!state.dirty && !state.pending && this.sockets().length === 0) return;
    const at = Math.max(Date.now() + 1000, state.nextAt ?? 0);
    const current = await this.ctx.storage.getAlarm();
    if (current == null || at < current) await this.ctx.storage.setAlarm(at);
  }

  automaticDirty(inTransaction = false) {
    const state = this.autoState();
    if (state?.phase !== 'ready') return;
    this.saveAuto({ ...state, dirty: true, nextAt: state.failures ? state.nextAt : Date.now() }, inTransaction);
  }

  async alarm() {
    return this.serial(async () => {
      const m = this.meta();
      if (m.legacyCleanup) {
        try { await this.automatic.firestore.deleteMarker(m.legacyCleanup.uid); m.legacyCleanup = null; }
        catch { m.legacyCleanup.nextAt = Date.now() + 15 * 60_000; }
        this.save(); await this.scheduleAutomatic(); return;
      }
      const state = this.autoState();
      if (state?.phase !== 'ready' || this.meta().state !== 'cloudflare') return;
      if (!state.dirty && !state.pending && this.sockets().length === 0) return;
      try { await this.automatic.synchronize(); } catch (error) { event('legacy-wait', { tag: this.tag ?? '?', code: error.code ?? 'legacy-unavailable' }); }
      await this.scheduleAutomatic();
    });
  }

  // ---- storage ---------------------------------------------------------------------------------

  q(statement, ...args) {
    const cursor = this.sql.exec(statement, ...args);
    const rows = cursor.toArray();
    this.rows.read += cursor.rowsRead;
    this.rows.written += cursor.rowsWritten;
    return rows;
  }

  /** The few account-wide values, read once per wake and kept in three rows. */
  meta() {
    if (this.m) return this.m;
    const m = { rev: 0, floor: 0, state: 'legacy', epoch: 0, pins: [], pinsAt: 0, pinsRev: 0, pos: 0, job: null,
      live: { p: 0, f: 0, a: 0, r: 0 }, removed: 0, pushes: 0, opn: 0, oldestRemoval: 0,
      seen: { dup: 0, stale: 0, bad: 0, limit: 0 }, settingsRev: 0, settingsStartedAt: 0, legacySeenAt: 0,
      listensRev: 0, listensFloor: 0, clearedBefore: 0, listensImported: false, importUsed: { day: '', rows: 0 }, bridgeRetiredAt: 0, retiredAtListensRev: 0, backfillFrom: -1 };
    this.stored = {};
    for (const row of this.q('SELECT k, v FROM meta')) {
      this.stored[row.k] = row.v;
      if (row.k === 'core') Object.assign(m, JSON.parse(row.v));
      else m[row.k] = JSON.parse(row.v);
    }
    this.m = m;
    return m;
  }

  /** Writes only the rows whose content changed, so one like costs one counter row. */
  save() {
    const { pins, job, ...core } = this.m;
    for (const [k, value] of [['core', core], ['pins', pins], ['job', job]]) {
      const v = JSON.stringify(value);
      if (this.stored[k] === v) continue;
      this.q('INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v', k, v);
      this.stored[k] = v;
    }
  }

  playlistTracks(id) {
    return this.q('SELECT body FROM playlist_chunks WHERE playlist_id = ? ORDER BY seq', id).flatMap((row) => JSON.parse(row.body));
  }

  /** Rewrites only the chunks whose content changed: appending a song writes one row. */
  writeTracks(id, list) {
    const next = chunks(list);
    const before = this.q('SELECT seq, body FROM playlist_chunks WHERE playlist_id = ? ORDER BY seq', id);
    next.forEach((body, seq) => {
      if (before[seq]?.body === body) return;
      this.q('INSERT INTO playlist_chunks (playlist_id, seq, body) VALUES (?, ?, ?) ON CONFLICT(playlist_id, seq) DO UPDATE SET body = excluded.body',
        id, seq, body);
    });
    if (before.length > next.length) this.q('DELETE FROM playlist_chunks WHERE playlist_id = ? AND seq >= ?', id, next.length);
  }

  wipe() {
    const m = this.meta();
    for (const table of ['playlists', 'playlist_chunks', 'items', 'ops']) this.q(`DELETE FROM ${table}`);
    Object.assign(m, { pins: [], pinsAt: 0, pinsRev: 0, pos: 0, live: { p: 0, f: 0, a: 0, r: 0 }, removed: 0, pushes: 0, opn: 0, oldestRemoval: 0 });
    // Whatever a device mirrored before is gone without removal records: it must start over.
    m.rev += 1;
    m.floor = m.rev;
    this.save();
  }

  /** The whole library in snapshot form, read back from storage. */
  snapshot() {
    const m = this.meta();
    const playlists = [];
    const deleted = [];
    for (const row of this.q('SELECT id, deleted_at, body FROM playlists ORDER BY id')) {
      if (row.deleted_at) deleted.push({ id: row.id, at: row.deleted_at });
      else playlists.push({ id: row.id, ...JSON.parse(row.body), tracks: this.playlistTracks(row.id) });
    }
    const of = (kind) => this.q('SELECT body FROM items WHERE kind = ? AND deleted = 0 ORDER BY pos, id', kind).map((r) => JSON.parse(r.body));
    return { playlists, favorites: of('f'), albums: of('a'), artists: of('r'), pins: m.pins, pinsAt: m.pinsAt, deleted };
  }

  // ---- operations ------------------------------------------------------------------------------

  /** Applies one checked operation at revision [rev]. Returns its result and what changed. */
  apply(op, rev, now) {
    const m = this.meta();
    if (op.k) {
      const row = this.q('SELECT pos, deleted, body FROM items WHERE kind = ? AND id = ?', op.k, op.key)[0];
      if (op.t.endsWith('.del')) {
        if (!row || row.deleted) return { s: 'ok' };
        this.q("UPDATE items SET deleted = ?, rev = ?, body = '' WHERE kind = ? AND id = ?", now, rev, op.k, op.key);
        m.live[op.k] -= 1; this.removal(now);
        return { s: 'ok', item: { k: op.k, id: op.key, rev, del: now } };
      }
      const body = JSON.stringify(op.v);
      if (row && !row.deleted) {
        if (row.body === body) return { s: 'ok' };
        this.q('UPDATE items SET body = ?, rev = ? WHERE kind = ? AND id = ?', body, rev, op.k, op.key);
        return { s: 'ok', item: { k: op.k, id: op.key, rev, pos: row.pos, v: op.v } };
      }
      if (m.live[op.k] >= MAX_ITEMS) return { s: 'limit' };
      m.pos += 1; m.live[op.k] += 1;
      if (row) m.removed -= 1;
      this.q('INSERT INTO items (kind, id, pos, rev, deleted, body) VALUES (?, ?, ?, ?, 0, ?) ON CONFLICT(kind, id) DO UPDATE SET pos = excluded.pos, rev = excluded.rev, deleted = 0, body = excluded.body',
        op.k, op.key, m.pos, rev, body);
      return { s: 'ok', item: { k: op.k, id: op.key, rev, pos: m.pos, v: op.v } };
    }
    if (op.t === 'pins.set') {
      if (op.at <= m.pinsAt) return { s: 'ok' };
      m.pins = op.pins; m.pinsAt = op.at; m.pinsRev = rev;
      return { s: 'ok', pins: true };
    }
    const row = this.q('SELECT rev, deleted_at, body FROM playlists WHERE id = ?', op.key)[0];
    // Another device changed this playlist since this one last saw it: it must merge and resend.
    if (row && row.rev > op.ifRev) return { s: 'stale', rev: row.rev };
    if (op.t === 'pl.del') {
      if (!row || row.deleted_at) return { s: 'ok' };
      // The device's own time, unless it is impossible: a wrong clock must not expire the record early.
      const at = op.at > now - REMOVAL_MEMORY_MS && op.at <= now + 60_000 ? op.at : now;
      this.q("UPDATE playlists SET rev = ?, deleted_at = ?, body = '{}' WHERE id = ?", rev, at, op.key);
      this.q('DELETE FROM playlist_chunks WHERE playlist_id = ?', op.key);
      m.live.p -= 1; this.removal(at);
      return { s: 'ok', playlist: { id: op.key, rev, deletedAt: at } };
    }
    const live = row && !row.deleted_at;
    if (!live && m.live.p >= MAX_PLAYLISTS) return { s: 'limit' };
    const { id, ...fields } = op.v;
    const before = live ? JSON.parse(row.body) : null;
    const keptTracks = op.tracks === null && live;
    const count = keptTracks ? before.n : (op.tracks ?? []).length;
    const body = JSON.stringify({ ...fields, n: count });
    if (!keptTracks) this.writeTracks(id, op.tracks ?? []);
    this.q('INSERT INTO playlists (id, rev, deleted_at, body) VALUES (?, ?, 0, ?) ON CONFLICT(id) DO UPDATE SET rev = excluded.rev, deleted_at = 0, body = excluded.body',
      id, rev, body);
    if (!live) { m.live.p += 1; if (row) m.removed -= 1; }
    // Without `tracks` the device keeps the ones it has: only the playlist's own fields changed.
    return { s: 'ok', playlist: { id, rev, ...fields, ...(keptTracks ? {} : { tracks: op.tracks ?? [] }) } };
  }

  /** Old operation ids and removal records go when they are written past, never on a timer. */
  prune(now) {
    const m = this.meta();
    if (m.opn > OP_MEMORY_COUNT * 1.2) {
      const revs = this.q('SELECT rev FROM ops').map((r) => r.rev).sort((a, b) => b - a);
      const cut = revs[OP_MEMORY_COUNT - 1] ?? 0;
      this.q('DELETE FROM ops WHERE rev < ? OR at < ?', cut, now - OP_MEMORY_MS);
      m.opn = this.q('SELECT COUNT(*) AS n FROM ops')[0].n;
    }
    if (m.removed > REMOVAL_MEMORY_COUNT) {
      const gone = [...this.q('SELECT rev FROM items WHERE deleted > 0'), ...this.q('SELECT rev FROM playlists WHERE deleted_at > 0')]
        .map((r) => r.rev).sort((a, b) => b - a);
      const cut = gone[Math.floor(REMOVAL_MEMORY_COUNT / 2)] ?? 0;
      this.q('DELETE FROM items WHERE deleted > 0 AND rev <= ?', cut);
      this.q('DELETE FROM playlists WHERE deleted_at > 0 AND rev <= ?', cut);
      m.removed = gone.filter((r) => r > cut).length;
      // A device that last synced before the cut can no longer be told what was removed.
      m.floor = Math.max(m.floor, cut);
    }
  }

  /** Counts a removal record and remembers the oldest, so expiry needs no scan until one is due. */
  removal(at) {
    const m = this.meta();
    m.removed += 1;
    if (!m.oldestRemoval || at < m.oldestRemoval) m.oldestRemoval = at;
  }

  /**
   * Removal records older than the supported offline window, checked when a device asks. Nothing
   * is read until the oldest record is actually that old.
   */
  expire(now) {
    const m = this.meta();
    const old = now - REMOVAL_MEMORY_MS;
    if (!m.removed || !m.oldestRemoval || m.oldestRemoval >= old) return;
    const cut = Math.max(0, ...this.q('SELECT rev FROM items WHERE deleted > 0 AND deleted < ?', old).map((r) => r.rev),
      ...this.q('SELECT rev FROM playlists WHERE deleted_at > 0 AND deleted_at < ?', old).map((r) => r.rev));
    this.q('DELETE FROM items WHERE deleted > 0 AND deleted < ?', old);
    this.q('DELETE FROM playlists WHERE deleted_at > 0 AND deleted_at < ?', old);
    const left = [...this.q('SELECT deleted AS at FROM items WHERE deleted > 0'), ...this.q('SELECT deleted_at AS at FROM playlists WHERE deleted_at > 0')];
    m.removed = left.length;
    m.oldestRemoval = left.length ? Math.min(...left.map((r) => r.at)) : 0;
    // A device that last synced before the cut can no longer be told what was removed.
    m.floor = Math.max(m.floor, cut);
    this.save();
  }

  refuse(r) {
    const m = this.meta();
    event('refused', { tag: this.tag, state: m.state, epoch: m.epoch });
    return { t: 'err', r, code: 'authority', state: m.state, epoch: m.epoch };
  }

  push(frame, device, now) {
    const m = this.meta();
    const r = frame.r;
    if (frame.v !== PROTOCOL_VERSION) return { reply: { t: 'err', r, code: 'version', v: PROTOCOL_VERSION } };
    if (m.state !== 'cloudflare') return { reply: this.refuse(r) };
    if (frame.epoch !== m.epoch) return { reply: { t: 'err', r, code: 'epoch', state: m.state, epoch: m.epoch } };
    if (!Array.isArray(frame.ops) || frame.ops.length === 0 || frame.ops.length > MAX_OPS) return { reply: { t: 'err', r, code: 'invalid' } };
    const ops = frame.ops.map(operation);
    const results = [];
    const delta = { playlists: [], items: [] };
    const from = m.rev;
    const rev = from + 1;
    let changed = false;
    // One transaction: the edits, their ids and the revision are all stored, or none are.
    this.ctx.storage.transactionSync(() => {
      const keep = JSON.stringify({ live: m.live, removed: m.removed, pos: m.pos, pins: m.pins, pinsAt: m.pinsAt, pinsRev: m.pinsRev,
        opn: m.opn, floor: m.floor, pushes: m.pushes, oldestRemoval: m.oldestRemoval });
      try {
        ops.forEach((op, i) => {
          const id = frame.ops[i]?.id;
          if (!op) { results.push({ id: validId(id) ? id : null, s: 'bad' }); return; }
          const seen = this.q('SELECT rev FROM ops WHERE id = ?', op.id)[0];
          if (seen) { results.push({ id: op.id, s: 'dup', rev: seen.rev }); return; }
          const done = this.apply(op, rev, now);
          results.push({ id: op.id, s: done.s, rev: done.s === 'stale' ? done.rev : rev });
          if (done.s !== 'ok') return;
          this.q('INSERT INTO ops (id, rev, at) VALUES (?, ?, ?)', op.id, rev, now);
          m.opn += 1;
          if (done.item) delta.items.push(done.item);
          if (done.playlist) delta.playlists.push(done.playlist);
          if (done.pins) delta.pins = { pins: m.pins, at: m.pinsAt, rev };
          changed = changed || Boolean(done.item || done.playlist || done.pins);
        });
        // An accepted id is remembered even when it changed nothing, so its revision stays `from`.
        if (changed) {
          m.rev = rev;
          m.pushes += 1;
          this.prune(now);
          this.automaticDirty(true);
        }
        this.save();
      } catch (e) {
        Object.assign(m, JSON.parse(keep), { rev: from });
        this.m = null; // Reload from storage: the transaction undid the rows.
        throw e;
      }
    });
    if (!changed) for (const result of results) if (result.s === 'ok') result.rev = from;
    // Counted for the pilot report: retries, conflicts and refusals, never content.
    for (const result of results) if (result.s in m.seen) m.seen[result.s] += 1;
    this.save();
    // `from` and `changed` let a device tell whether this batch alone moved the revision.
    const reply = { t: 'ack', r, rev: m.rev, from, changed, results };
    const unusual = results.filter((x) => x.s !== 'ok' && x.s !== 'dup').length;
    if (unusual) event('push', { tag: this.tag, ops: results.length, stale: results.filter((x) => x.s === 'stale').length,
      refused: results.filter((x) => x.s === 'bad' || x.s === 'limit').length, rev: m.rev });
    if (!changed) return { reply };
    const pushed = { t: 'changed', from, rev: m.rev, by: device, delta };
    return { reply, pushed: JSON.stringify(pushed).length <= MAX_PUSHED_DELTA_BYTES ? pushed : { t: 'changed', from, rev: m.rev, by: device } };
  }

  /**
   * What changed after [cursor], or everything when [full]. Pages are cut by size; the revision
   * of the first page is the cursor to keep, because later pages can only be newer than it.
   */
  pull(frame, now) {
    const m = this.meta();
    const r = frame.r;
    if (m.state !== 'cloudflare') return this.refuse(r);
    this.expire(now);
    const full = frame.full === true;
    const cursor = Number.isSafeInteger(frame.cursor) && frame.cursor >= 0 ? frame.cursor : 0;
    if (!full && (cursor < m.floor || cursor > m.rev)) return { t: 'reset', r, rev: m.rev, epoch: m.epoch };
    const page = frame.page && typeof frame.page === 'object' ? frame.page : { phase: 0, key: '', kind: '', rev: m.rev };
    const upTo = Number.isSafeInteger(page.rev) ? page.rev : m.rev;
    const out = { t: 'delta', r, full, rev: upTo, epoch: m.epoch, playlists: [], items: [] };
    if (!full && cursor === m.rev) return out; // Nothing to read: the device is current.
    const since = full ? -1 : cursor;
    let size = 0;
    let more = null;
    if (page.phase === 0) {
      const rows = this.q('SELECT id, rev, deleted_at, body FROM playlists WHERE id > ? AND rev > ? ORDER BY id', String(page.key ?? ''), since);
      for (const row of rows) {
        if (size > PAGE_BYTES) { more = { phase: 0, key: out.playlists.at(-1).id, kind: '', rev: upTo }; break; }
        const entry = row.deleted_at ? { id: row.id, rev: row.rev, deletedAt: row.deleted_at }
          : { id: row.id, rev: row.rev, ...withoutCount(JSON.parse(row.body)), tracks: this.playlistTracks(row.id) };
        size += JSON.stringify(entry).length;
        out.playlists.push(entry);
      }
    }
    if (!more) {
      const kind = page.phase === 1 ? String(page.kind ?? '') : '';
      const key = page.phase === 1 ? String(page.key ?? '') : '';
      const rows = this.q('SELECT kind, id, pos, rev, deleted, body FROM items WHERE (kind > ? OR (kind = ? AND id > ?)) AND rev > ? ORDER BY kind, id',
        kind, kind, key, since);
      for (const row of rows) {
        if (full && row.deleted) continue; // A fresh copy has nothing to remove.
        if (size > PAGE_BYTES) { const last = out.items.at(-1); more = { phase: 1, kind: last?.k ?? kind, key: last?.id ?? key, rev: upTo }; break; }
        const entry = row.deleted ? { k: row.kind, id: row.id, rev: row.rev, del: row.deleted }
          : { k: row.kind, id: row.id, rev: row.rev, pos: row.pos, v: JSON.parse(row.body) };
        size += JSON.stringify(entry).length;
        out.items.push(entry);
      }
    }
    if (more) out.more = more;
    else if (full || m.pinsRev > cursor) out.pins = { pins: m.pins, at: m.pinsAt, rev: m.pinsRev };
    return out;
  }

  /**
   * Account deletion: the owner removes whatever library is held here. The account then refuses
   * reads and writes for good, so a device that is still signed in for a while can't upload it again.
   */
  erase(frame, now) {
    const m = this.meta();
    const r = frame.r;
    if (frame.v !== PROTOCOL_VERSION) return { t: 'err', r, code: 'version', v: PROTOCOL_VERSION };
    // In any state: a library archived by a rollback, or one mid-migration, goes too. Erasing an
    // erased account succeeds again, so a deletion that failed later can simply be retried.
    if (m.state === 'migrating' && m.job?.dir === 'erased') return { t: 'erased', r, epoch: m.epoch };
    const automatic = this.autoState();
    const legacyUid = automatic?.imported ? automatic.uid : null;
    this.ctx.storage.transactionSync(() => {
      this.wipe();
      this.q('DELETE FROM library_auto');
      this.autoCache = null;
      this.q('DELETE FROM settings');
      this.q('DELETE FROM listen_days');
      Object.assign(m, { settingsRev: m.settingsRev + 1, settingsStartedAt: 0, legacySeenAt: 0, bridgeRetiredAt: 0, backfillFrom: -1, listensRev: m.listensRev + 1,
        listensFloor: m.listensRev + 1, listensImported: false });
      this.setState('migrating', { dir: 'erased', at: now });
      if (legacyUid) { m.legacyCleanup = { uid: legacyUid, nextAt: now }; this.save(); }
    });
    return { t: 'erased', r, epoch: m.epoch };
  }

  /** The other topics this Worker serves for the account, with their revisions. */
  topics(now) {
    const m = this.meta();
    const topics = {};
    if (topicEnabled(this.env, 'settings')) topics.settings = { rev: m.settingsRev, bridge: bridgeActive(m) };
    if (topicEnabled(this.env, 'listens')) {
      topics.listens = { rev: m.listensRev, floor: m.listensFloor, clearedBefore: m.clearedBefore, imported: m.listensImported,
        bridge: bridgeActive(m) };
      // Set after a bridge restore until an updated app has copied the gap to Firestore.
      if (bridgeActive(m) && m.backfillFrom >= 0) topics.listens.backfillFrom = m.backfillFrom;
    }
    return topics;
  }

  /**
   * Settings: merge this device's edits (and, for an empty account, its current values) into the
   * platform's map, and answer with the whole map. Other devices are sent what changed. An exchange
   * without edits is a pull. `legacy` marks that the device found changes made by an old app version.
   */
  settings(frame, device, now) {
    const m = this.meta();
    const r = frame.r;
    if (!topicEnabled(this.env, 'settings')) return { reply: { t: 'err', r, code: 'topic' } };
    if (frame.v !== 2) return { reply: { t: 'err', r, code: 'version', v: 2 } };
    if (m.state === 'migrating' && m.job?.dir === 'erased') return { reply: this.refuse(r) };
    const platform = frame.platform;
    if (!SETTINGS_PLATFORMS.includes(platform)) return { reply: { t: 'err', r, code: 'invalid' } };
    const edits = frame.edits ?? {};
    if (typeof edits !== 'object' || Array.isArray(edits) || Object.keys(edits).length > MAX_SETTINGS_KEYS) {
      return { reply: { t: 'err', r, code: 'invalid' } };
    }
    const remote = {};
    for (const row of this.q('SELECT k, value, revision, device FROM settings WHERE platform = ?', platform)) {
      const pref = preference({ value: row.value, revision: row.revision, device: row.device }, 0);
      if (pref) remote[row.k] = pref;
    }
    const { result, changed } = mergeSettings(remote, edits, frame.seed);
    if (Object.keys(result).length > MAX_SETTINGS_KEYS) return { reply: { t: 'err', r, code: 'limit' } };
    let delta = null;
    this.ctx.storage.transactionSync(() => {
      if (changed.length) {
        m.settingsRev += 1;
        delta = {};
        for (const key of changed) {
          const p = result[key];
          this.q('INSERT INTO settings (platform, k, value, revision, device, rev) VALUES (?, ?, ?, ?, ?, ?) ' +
            'ON CONFLICT(platform, k) DO UPDATE SET value = excluded.value, revision = excluded.revision, device = excluded.device, rev = excluded.rev',
          platform, key, p.value, p.revision, p.device, m.settingsRev);
          delta[key] = p;
        }
      }
      if (!m.settingsStartedAt) m.settingsStartedAt = now;
      if (frame.legacy === true) m.legacySeenAt = now;
      this.save();
    });
    const reply = { t: 'settings', r, platform, rev: m.settingsRev, values: result, bridge: bridgeActive(m) };
    if (!delta) return { reply };
    return { reply, pushed: { t: 'changed', topic: 'settings', platform, rev: m.settingsRev, by: device, values: delta } };
  }

  /** Whether the listens topic may be used for this account right now, or the refusal. */
  listensRefusal(frame) {
    const m = this.meta();
    if (!topicEnabled(this.env, 'listens')) return { t: 'err', r: frame.r, code: 'topic' };
    if (frame.v !== 2) return { t: 'err', r: frame.r, code: 'version', v: 2 };
    if (m.state === 'migrating' && m.job?.dir === 'erased') return this.refuse(frame.r);
    return null;
  }

  /**
   * Finished listens from one device, merged into their days: one row written per changed day, so a
   * batch of listens costs a few rows, not one per listen. `import` marks history copied from
   * Firestore, which counts against a daily cap; `importDone` records that the copy is complete.
   */
  listens(frame, device, now) {
    const refusal = this.listensRefusal(frame);
    if (refusal) return { reply: refusal };
    const m = this.meta();
    const r = frame.r;
    const events = frame.events ?? [];
    if (!Array.isArray(events) || events.length > MAX_PUSH_EVENTS) return { reply: { t: 'err', r, code: 'invalid' } };
    const touched = [...new Set(events.map((e) => (Number.isSafeInteger(e?.ts) && e.ts > 0 ? dayOf(e.ts) : null)).filter(Boolean))];
    if (frame.import === true) {
      const today = dayOf(now);
      const used = m.importUsed.day === today ? m.importUsed.rows : 0;
      // History copies are spread out so they never use up the day's free row budget.
      if (used + touched.length > Number(this.env.LISTENS_IMPORT_DAILY_ROWS ?? IMPORT_DAILY_ROWS)) {
        return { reply: { t: 'err', r, code: 'later', retryAfterMs: Date.parse(`${today}T24:00:00Z`) - now } };
      }
      m.importUsed = { day: today, rows: used + touched.length };
    }
    const days = {};
    for (const day of touched) {
      const row = this.q('SELECT body FROM listen_days WHERE day = ?', day)[0];
      if (row) days[day] = JSON.parse(row.body);
    }
    const changed = mergeListens(days, events, m.clearedBefore);
    const from = m.listensRev;
    this.ctx.storage.transactionSync(() => {
      if (changed.size) {
        m.listensRev += 1;
        for (const [day, { map }] of changed) {
          this.q('INSERT INTO listen_days (day, rev, body) VALUES (?, ?, ?) ON CONFLICT(day) DO UPDATE SET rev = excluded.rev, body = excluded.body',
            day, m.listensRev, JSON.stringify(map));
        }
      }
      if (frame.importDone === true) m.listensImported = true;
      if (frame.backfillDone === true && Number.isSafeInteger(frame.backfillFrom) && frame.backfillFrom <= m.backfillFrom) m.backfillFrom = -1;
      if (frame.legacy === true) m.legacySeenAt = now;
      if (!m.settingsStartedAt) m.settingsStartedAt = now;
      this.save();
    });
    const reply = { t: 'listens', r, rev: m.listensRev, from, changed: changed.size > 0, imported: m.listensImported, clearedBefore: m.clearedBefore };
    if (!changed.size) return { reply };
    const delta = [...changed].map(([day, { delta: d }]) => ({ day, events: d }));
    const pushed = { t: 'changed', topic: 'listens', from, rev: m.listensRev, by: device, days: delta };
    return { reply, pushed: JSON.stringify(pushed).length <= 256 * 1024 ? pushed : { t: 'changed', topic: 'listens', from, rev: m.listensRev, by: device } };
  }

  /** Days changed after [cursor], whole, paged by size; `reset` when the cursor predates a clear or erase. */
  listensPull(frame, now) {
    const refusal = this.listensRefusal(frame);
    if (refusal) return refusal;
    const m = this.meta();
    const cursor = Number.isSafeInteger(frame.cursor) && frame.cursor >= 0 ? frame.cursor : 0;
    const full = frame.full === true;
    if (!full && (cursor < m.listensFloor || cursor > m.listensRev)) return { t: 'reset', r: frame.r, rev: m.listensRev, clearedBefore: m.clearedBefore };
    const out = { t: 'listens', r: frame.r, rev: m.listensRev, clearedBefore: m.clearedBefore, days: [] };
    if (!full && cursor === m.listensRev) return out;
    const after = typeof frame.page === 'string' ? frame.page : '';
    let size = 0;
    for (const row of this.q('SELECT day, rev, body FROM listen_days WHERE day > ? AND rev > ? ORDER BY day', after, full ? -1 : cursor)) {
      if (size > PAGE_BYTES) { out.more = out.days.at(-1).day; break; }
      size += row.body.length;
      out.days.push({ day: row.day, rev: row.rev, events: JSON.parse(row.body) });
    }
    return out;
  }

  /** "Clear listening stats": everything before [before] goes for every device of the account. */
  listensClear(frame, device, now) {
    const refusal = this.listensRefusal(frame);
    if (refusal) return { reply: refusal };
    const m = this.meta();
    const before = Math.min(Number.isSafeInteger(frame.before) ? frame.before : 0, now);
    if (before <= m.clearedBefore) return { reply: { t: 'listens', r: frame.r, rev: m.listensRev, clearedBefore: m.clearedBefore } };
    this.ctx.storage.transactionSync(() => {
      m.listensRev += 1;
      const cutDay = dayOf(before);
      this.q('DELETE FROM listen_days WHERE day < ?', cutDay);
      const row = this.q('SELECT body FROM listen_days WHERE day = ?', cutDay)[0];
      if (row) this.q('UPDATE listen_days SET body = ?, rev = ? WHERE day = ?', JSON.stringify(clearBefore(JSON.parse(row.body), before)), m.listensRev, cutDay);
      m.clearedBefore = before;
      // A device that synced before the clear must start over rather than keep what was cleared.
      m.listensFloor = m.listensRev;
      this.save();
    });
    const reply = { t: 'listens', r: frame.r, rev: m.listensRev, clearedBefore: m.clearedBefore };
    return { reply, pushed: { t: 'changed', topic: 'listens', rev: m.listensRev, by: device, clearedBefore: m.clearedBefore } };
  }

  /** One frame in, one frame out: the socket and the HTTP call share this. */
  handle(frame, device, now) {
    const m = this.meta();
    const r = frame?.r;
    switch (frame?.t) {
      case 'hello': {
        if (frame.v !== PROTOCOL_VERSION && frame.v !== 2) return { reply: { t: 'err', r, code: 'version', v: 2 } };
        const ready = { t: 'ready', r, state: m.state, epoch: m.epoch, rev: m.rev, floor: m.floor, now };
        // Version 2 also names each other topic's revision, so a reconnecting device pulls only what it missed.
        if (frame.v === 2) ready.topics = this.topics(now);
        return { reply: ready };
      }
      case 'settings': return this.settings(frame, device, now);
      case 'listens': return this.listens(frame, device, now);
      case 'listens.pull': return { reply: this.listensPull(frame, now) };
      case 'listens.clear': return this.listensClear(frame, device, now);
      case 'pull': return { reply: this.pull(frame, now) };
      case 'push': return this.push(frame, device, now);
      case 'erase': return { reply: this.erase(frame, now) };
      case 'stats':
        if (!this.env.STAGING_SECRET) return { reply: { t: 'err', r, code: 'unknown' } };
        return { reply: { t: 'stats', r, rows: this.rows, sockets: this.ctx.getWebSockets().length, rev: m.rev } };
      default: return { reply: { t: 'err', r, code: 'unknown' } };
    }
  }

  // ---- transport -------------------------------------------------------------------------------

  async fetch(request) {
    return this.serial(() => this.fetchSerialized(request));
  }

  async fetchSerialized(request) {
    const url = new URL(request.url);
    this.tag = request.headers.get('x-tag') ?? this.tag ?? '?';
    this.accountUid = request.headers.get('x-uid');
    if (url.pathname.startsWith('/admin/')) {
      const action = url.pathname.slice(7);
      const response = await this.admin(action, await request.text());
      const m = this.meta();
      event('admin', { tag: this.tag, action, status: response.status, state: m.state, epoch: m.epoch, rev: m.rev });
      return response;
    }
    const now = Date.now();
    const expiresAtMs = Number(request.headers.get('x-expires'));
    if (!(expiresAtMs > now)) return new Response('expired', { status: 401 });
    const m = this.meta();
    if (url.pathname === '/bootstrap') return this.bootstrap(request.headers.get('x-uid'));
    if (url.pathname === '/authority') return json({ state: m.state, epoch: m.epoch, v: PROTOCOL_VERSION });
    const device = request.headers.get('x-device');
    if (url.pathname === '/rpc') {
      let frame;
      try { frame = JSON.parse(await request.text()); } catch { return json({ t: 'err', code: 'bad-frame' }, 400); }
      if (!frame || typeof frame !== 'object' || Array.isArray(frame)) return json({ t: 'err', code: 'bad-frame' }, 400);
      if (frame.t === 'profile.picks') {
        const state = this.autoState();
        return json({ t: 'profile.picks', available: state?.phase === 'ready', picks: state?.picks ?? null });
      }
      const done = this.handle(frame, device, now);
      if (done.pushed) this.broadcast(done.pushed, device);
      if (frame.t === 'push' && done.reply.changed) this.automaticDirty();
      await this.scheduleAutomatic();
      return json(done.reply);
    }
    const open = this.sockets();
    for (const socket of open) {
      if (socket.deserializeAttachment()?.device === device) socket.close(CLOSE_REPLACED, 'replaced');
    }
    if (open.filter((s) => s.deserializeAttachment()?.device !== device).length >= MAX_SOCKETS) {
      return new Response('too many devices', { status: 429 });
    }
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    pair[1].serializeAttachment({ device, expiresAtMs });
    await this.scheduleAutomatic();
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  sockets() {
    const now = Date.now();
    return this.ctx.getWebSockets().filter((socket) => {
      if (socket.readyState !== WebSocket.READY_STATE_OPEN) return false;
      const attachment = socket.deserializeAttachment();
      if (!attachment) return false;
      if (now < attachment.expiresAtMs) return true;
      socket.close(CLOSE_EXPIRED, 'expired');
      return false;
    });
  }

  /** Tells every other device. The output gate holds this until the edit is on disk. */
  broadcast(frame, exceptDevice) {
    const body = JSON.stringify(frame);
    for (const socket of this.sockets()) {
      if (socket.deserializeAttachment()?.device === exceptDevice) continue;
      try { socket.send(body); } catch { /* closing */ }
    }
  }

  async webSocketMessage(socket, message) {
    return this.serial(() => this.messageSerialized(socket, message));
  }

  async messageSerialized(socket, message) {
    const attachment = socket.deserializeAttachment();
    if (!attachment || socket.readyState !== WebSocket.READY_STATE_OPEN) return;
    const size = typeof message === 'string' ? message.length : message.byteLength;
    if (size > MAX_BODY_BYTES) return socket.close(CLOSE_TOO_LARGE, 'too large');
    const now = Date.now();
    if (now >= attachment.expiresAtMs) return socket.close(CLOSE_EXPIRED, 'expired');
    let limit = this.limits.get(socket);
    if (!limit) { limit = new RateLimit(); limit.at = now; this.limits.set(socket, limit); }
    const send = (frame) => { try { socket.send(JSON.stringify(frame)); } catch { /* closed */ } };
    if (!limit.allow(now)) {
      if (limit.refused >= REFUSALS_BEFORE_CLOSE) return socket.close(CLOSE_TOO_FAST, 'too fast');
      return send({ t: 'err', code: 'slow', retryMs: 1000 });
    }
    let frame;
    try { frame = JSON.parse(message); } catch { return send({ t: 'err', code: 'bad-frame' }); }
    if (!frame || typeof frame !== 'object' || Array.isArray(frame)) return send({ t: 'err', code: 'bad-frame' });
    const done = this.handle(frame, attachment.device, now);
    if (frame.t === 'push' && done.reply.changed) this.automaticDirty();
    await this.scheduleAutomatic();
    send(done.reply);
    if (done.pushed) this.broadcast(done.pushed, attachment.device);
  }

  webSocketClose(socket) {
    try { socket.close(1000, 'closed'); } catch { /* already closed */ }
    this.limits.delete(socket);
  }

  webSocketError(socket) { this.limits.delete(socket); }

  // ---- migration and recovery ------------------------------------------------------------------

  setState(state, job) {
    const m = this.meta();
    m.state = state; m.epoch += 1; m.job = job;
    this.save();
    this.broadcast({ t: 'authority', state, epoch: m.epoch });
  }

  async verify() {
    const lib = this.snapshot();
    return { checksum: await checksum(lib), counts: counts(lib) };
  }

  async admin(action, text) {
    const m = this.meta();
    let body = {};
    try { if (text) body = JSON.parse(text); } catch { return json({ error: 'bad json' }, 400); }
    const now = Date.now();
    const status = () => ({ state: m.state, epoch: m.epoch, rev: m.rev, floor: m.floor, job: m.job, live: m.live, pushes: m.pushes,
      seen: m.seen, sockets: this.ctx.getWebSockets().length,
      legacyCleanupPending: !!m.legacyCleanup,
      automatic: (() => { const s = this.autoState(); return s ? { phase: s.phase, dirty: !!s.dirty, error: s.error ?? null,
        nextAt: s.nextAt, lastSuccess: s.lastSuccess ?? null, adoptingFence: !!s.adoptionFence } : null; })(),
      bridge: { active: bridgeActive(m), retiredAt: m.bridgeRetiredAt || null, accountSyncSince: m.settingsStartedAt || null,
        oldAppLastSeen: m.legacySeenAt || null, backfillPending: m.backfillFrom >= 0 } });
    const incoming = m.state === 'migrating' && m.job?.dir === 'in';
    switch (action) {
      case 'status': return json(status());
      case 'export-source': {
        const s = this.autoState();
        if (!s?.imported) return json({ error: 'no automatic source backup' }, 404);
        return json({ ...status(), source: s.imported });
      }
      case 'sync-legacy':
      case 'adopt-bridge': {
        if (m.state !== 'cloudflare') return json({ error: 'not cloudflare', ...status() }, 409);
        if (action === 'adopt-bridge') {
          if (this.env.LIBRARY_AUTO_IMPORT !== 'true' || body.acknowledge !== 'allow old apps to write through the library bridge')
            return json({ error: 'automatic import must be enabled and the compatibility change acknowledged' }, 400);
        } else if (this.autoState()?.phase !== 'ready') return json({ error: 'no automatic bridge', ...status() }, 409);
        try {
          if (action === 'adopt-bridge') await this.automatic.adoptFenced(this.accountUid, m.job?.checksum);
          else await this.automatic.synchronize({ force: true });
          await this.scheduleAutomatic(); return json(status());
        } catch (error) {
          await this.scheduleAutomatic();
          return json({ error: error.code ?? 'legacy-unavailable', ...status() }, 503);
        }
      }
      case 'resume-automatic': {
        const s = this.autoState();
        if (m.state !== 'legacy' || s?.phase !== 'paused' || body.acknowledge !== 'resume automatic import from the verified rollback source')
          return json({ error: 'not a paused rollback, or missing acknowledgement' }, 409);
        this.saveAuto({ ...s, phase: 'pending', nextAt: 0, failures: 0, pending: null });
        return json(status());
      }
      case 'verify': this.expire(now); return json({ ...status(), ...(await this.verify()) });
      case 'export': {
        // Records past the offline window are dropped first, as a device or a read-back would.
        this.expire(now);
        const lib = this.snapshot();
        const settings = {};
        for (const row of this.q('SELECT platform, k, value, revision, device FROM settings')) {
          (settings[row.platform] ??= {})[row.k] = { value: row.value, revision: row.revision, device: row.device };
        }
        return json({ ...status(), library: lib, checksum: await checksum(lib), counts: counts(lib), settings });
      }
      case 'begin':
        if (m.state === 'cloudflare') return json({ error: 'already cloudflare', ...status() }, 409);
        if (m.state === 'migrating' && !incoming) return json({ error: 'rollback in progress', ...status() }, 409);
        if (m.state === 'legacy') this.setState('migrating', { dir: 'in', step: 'fence', startedAt: now });
        return json(status());
      case 'import': {
        if (!incoming) return json({ error: 'not migrating', ...status() }, 409);
        if (typeof body.checksum !== 'string' || !body.source || typeof body.source !== 'object') return json({ error: 'bad import' }, 400);
        const checked = library(body.library);
        if (checked.error) return json({ error: checked.error }, 422);
        // The checksum must describe exactly what was sent, or the tool and server disagree on form.
        if ((await checksum(checked.library)) !== body.checksum) return json({ error: 'checksum does not match library' }, 422);
        if (!(m.job.step === 'imported' && m.job.checksum === body.checksum)) {
          this.ctx.storage.transactionSync(() => {
            this.wipe();
            this.store(checked.library);
            m.job = { ...m.job, step: 'imported', checksum: body.checksum, source: body.source, importedAt: now, counts: counts(checked.library) };
            this.save();
          });
        }
        return json({ ...status(), ...(await this.verify()) });
      }
      case 'commit': {
        if (m.state === 'cloudflare' && m.job?.checksum === body.checksum) return json(status());
        if (!incoming || m.job.step !== 'imported') return json({ error: 'nothing imported', ...status() }, 409);
        const found = await this.verify();
        if (body.checksum !== m.job.checksum || found.checksum !== m.job.checksum) {
          return json({ error: 'checksum mismatch', ...status(), ...found }, 409);
        }
        this.setState('cloudflare', { ...m.job, step: 'done', committedAt: now });
        return json(status());
      }
      case 'abort':
        if (m.state === 'legacy') return json(status());
        if (!incoming) return json({ error: 'cannot abort', ...status() }, 409);
        this.ctx.storage.transactionSync(() => { this.wipe(); this.setState('legacy', { aborted: true, at: now }); });
        return json(status());
      case 'rollback-begin':
        if (m.state === 'migrating' && m.job?.dir === 'out') return json(status());
        if (m.state !== 'cloudflare') return json({ error: 'not cloudflare', ...status() }, 409);
        this.ctx.storage.transactionSync(() => {
          if (this.autoState()?.phase === 'ready') this.saveAuto({ ...this.autoState(), phase: 'paused' }, true);
          this.setState('migrating', { dir: 'out', step: 'frozen', startedAt: now, was: m.job });
        });
        return json(status());
      case 'restore': {
        // Replaces the library with an export while the account is frozen (after rollback-begin).
        // Devices start over from it: the epoch changes when the account is unfrozen.
        if (!(m.state === 'migrating' && m.job?.dir === 'out')) return json({ error: 'freeze the account first (rollback-begin)', ...status() }, 409);
        if (typeof body.checksum !== 'string') return json({ error: 'bad restore' }, 400);
        const checked = library(body.library);
        if (checked.error) return json({ error: checked.error }, 422);
        if ((await checksum(checked.library)) !== body.checksum) return json({ error: 'checksum does not match library' }, 422);
        this.ctx.storage.transactionSync(() => {
          this.wipe();
          this.store(checked.library);
          m.job = { ...m.job, restored: { checksum: body.checksum, at: now } };
          this.save();
        });
        return json({ ...status(), ...(await this.verify()) });
      }
      case 'retire-bridge':
        // An explicit, per-account decision with the consequence spelled out; never on a timer.
        if (body.acknowledge !== RETIRE_ACKNOWLEDGEMENT) {
          return json({ error: 'not retired: send acknowledge with the exact consequence', acknowledge: RETIRE_ACKNOWLEDGEMENT, ...status() }, 400);
        }
        if (!m.bridgeRetiredAt) { m.bridgeRetiredAt = now; m.retiredAtListensRev = m.listensRev; this.save(); }
        return json(status());
      case 'restore-bridge':
        // Listens updated apps recorded while retired never reached Firestore: the first updated app
        // to see the bridge back copies everything changed since the retirement to Firestore. Settings
        // need nothing extra: the next mirror sends the whole map and merges both ways.
        if (m.bridgeRetiredAt) {
          m.backfillFrom = m.backfillFrom >= 0 ? Math.min(m.backfillFrom, m.retiredAtListensRev) : m.retiredAtListensRev;
          m.bridgeRetiredAt = 0; this.save();
        }
        return json(status());
      case 'rollback-abort':
        if (!(m.state === 'migrating' && m.job?.dir === 'out')) return json({ error: 'no rollback', ...status() }, 409);
        this.ctx.storage.transactionSync(() => {
          this.setState('cloudflare', m.job.was ?? { step: 'done' });
          if (this.autoState()?.phase === 'paused') this.saveAuto({ ...this.autoState(), phase: 'ready', dirty: true, nextAt: 0 }, true);
        });
        await this.scheduleAutomatic();
        return json(status());
      case 'rollback-commit':
        if (m.state === 'legacy') return json(status());
        if (!(m.state === 'migrating' && m.job?.dir === 'out')) return json({ error: 'no rollback', ...status() }, 409);
        // Rows stay as an archive until a later import replaces them.
        this.setState('legacy', { rolledBack: true, at: now, checksum: body.checksum ?? null });
        return json(status());
      default: return json({ error: 'unknown action' }, 404);
    }
  }

  /** Writes a checked library at one new revision. Only inside a transaction, after a wipe. */
  store(lib) {
    const m = this.meta();
    m.rev += 1;
    const rev = m.rev;
    for (const p of lib.playlists) {
      const { id, tracks, ...fields } = p;
      this.q('INSERT INTO playlists (id, rev, deleted_at, body) VALUES (?, ?, 0, ?)', id, rev, JSON.stringify({ ...fields, n: tracks.length }));
      chunks(tracks).forEach((body, seq) => this.q('INSERT INTO playlist_chunks (playlist_id, seq, body) VALUES (?, ?, ?)', id, seq, body));
    }
    for (const d of lib.deleted) this.q("INSERT INTO playlists (id, rev, deleted_at, body) VALUES (?, ?, ?, '{}')", d.id, rev, d.at > 0 ? d.at : 1);
    m.oldestRemoval = lib.deleted.length ? Math.min(...lib.deleted.map((d) => (d.at > 0 ? d.at : 1))) : 0;
    for (const [kind, list] of [['f', lib.favorites], ['a', lib.albums], ['r', lib.artists]]) {
      for (const v of list) {
        m.pos += 1;
        this.q('INSERT INTO items (kind, id, pos, rev, deleted, body) VALUES (?, ?, ?, ?, 0, ?)', kind, v.id, m.pos, rev, JSON.stringify(ITEM[kind](v)));
      }
    }
    Object.assign(m, { pins: lib.pins, pinsAt: lib.pinsAt, pinsRev: rev, removed: lib.deleted.length,
      live: { p: lib.playlists.length, f: lib.favorites.length, a: lib.albums.length, r: lib.artists.length } });
    this.save();
  }
}

function withoutCount(body) {
  const { n: _, ...fields } = body;
  return fields;
}

export const LIBRARY_STATES = STATES;
export const LIBRARY_CHUNK = CHUNK_TRACKS;
