#!/usr/bin/env node
// Moves one account's library between Firestore and the library service, one resumable step at a
// time (docs/library-sync/spec.md, "Per-account cutover"). Every command can be run again after a
// crash: the account's own Durable Object remembers how far the migration got.
//
//   node tools/library-migrate.mjs status   <uid> ...
//   node tools/library-migrate.mjs report   --allowlist <file>        (stop conditions; exit 3 when one is hit)
//   node tools/library-migrate.mjs migrate  --allowlist <file> <uid> ...
//   node tools/library-migrate.mjs abort    <uid>                      (before the flip only)
//   node tools/library-migrate.mjs rollback <uid>                      (after the flip: writes the latest library back)
//   node tools/library-migrate.mjs export   <uid>                      (saves and verifies an export)
//   node tools/library-migrate.mjs restore  <uid> <export file>        (puts an export back, keeping a backup first)
//   node tools/library-migrate.mjs bridge-status  <uid>                (settings/history bridge for old app versions)
//   node tools/library-migrate.mjs retire-bridge  <uid> --old-devices-will-stop-syncing
//   node tools/library-migrate.mjs restore-bridge <uid>
//
// The allowlist file names the only accounts this tool will move, one uid per line (# comments
// allowed). The Worker enforces its own list too (LIBRARY_PILOT_UIDS); both must name an account.
//
// Environment:
//   LIBRARY_URL                     origin of the Worker, e.g. https://auralis-connect.<account>.workers.dev
//   LIBRARY_ADMIN_SECRET            the secret set with `wrangler secret put LIBRARY_ADMIN_SECRET`
//   FIREBASE_PROJECT_ID             e.g. auralis-70cf8
//   GOOGLE_APPLICATION_CREDENTIALS  path to a service-account key that may read users/* and write
//                                   libraryAuthority/* (never commit it), or
//   FIRESTORE_EMULATOR_HOST         host:port of a local emulator (tests)
//   LIBRARY_SNAPSHOT_DIR            where frozen sources and exports are saved (default ./library-snapshots)
//
// This tool changes real accounts. Do not point it at production without explicit approval.
import { createSign } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { checksum, counts, fromUserDocument, toUserDocument } from '../src/library-core.js';

export const LIBRARY_FIELDS = ['playlists', 'favorites', 'savedAlbums', 'savedArtists', 'librarySync'];
const ROLLBACK_FIELDS = [...LIBRARY_FIELDS, 'updatedAt', 'playlistsUpdatedAt', 'favoritesUpdatedAt', 'schemaVersion'];

/** A step failed in a way that is safe to retry later; the account stays where it was. */
export class Retryable extends Error {}

// ---- Firestore, with backend credentials ----------------------------------------------------------

function decode(v) {
  if ('nullValue' in v) return null;
  if ('booleanValue' in v) return v.booleanValue;
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('stringValue' in v) return v.stringValue;
  if ('timestampValue' in v) return v.timestampValue;
  if ('arrayValue' in v) return (v.arrayValue.values ?? []).map(decode);
  if ('mapValue' in v) return decodeFields(v.mapValue.fields ?? {});
  return null;
}
const decodeFields = (fields) => Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, decode(v)]));

function encode(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (typeof v === 'string') return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(encode) } };
  return { mapValue: { fields: encodeFields(v) } };
}
const encodeFields = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, encode(v)]));

async function serviceAccountToken(keyPath, fetcher) {
  const key = JSON.parse(readFileSync(keyPath, 'utf8'));
  const now = Math.floor(Date.now() / 1000);
  const part = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const unsigned = `${part({ alg: 'RS256', typ: 'JWT' })}.${part({ iss: key.client_email, scope: 'https://www.googleapis.com/auth/datastore',
    aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3000 })}`;
  const signature = createSign('RSA-SHA256').update(unsigned).sign(key.private_key).toString('base64url');
  const response = await fetcher('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}` }) });
  if (!response.ok) throw new Retryable(`could not get backend credentials (HTTP ${response.status})`);
  const body = await response.json();
  return { token: body.access_token, until: Date.now() + (body.expires_in - 120) * 1000 };
}

export function firestoreClient({ projectId, emulatorHost, keyPath, fetcher = fetch }) {
  if (!projectId) throw new Error('FIREBASE_PROJECT_ID is required');
  const origin = emulatorHost ? `http://${emulatorHost}` : 'https://firestore.googleapis.com';
  const root = `projects/${projectId}/databases/(default)/documents`;
  let cached = null;
  const headers = async () => {
    // The emulator treats "owner" as backend access, which is what a service account is in production.
    if (emulatorHost) return { authorization: 'Bearer owner', 'content-type': 'application/json' };
    if (!keyPath) throw new Error('GOOGLE_APPLICATION_CREDENTIALS is required outside the emulator');
    if (!cached || Date.now() > cached.until) cached = await serviceAccountToken(keyPath, fetcher);
    return { authorization: `Bearer ${cached.token}`, 'content-type': 'application/json' };
  };
  const call = async (method, path, body) => {
    let response;
    try { response = await fetcher(`${origin}/v1/${path}`, { method, headers: await headers(), body: body ? JSON.stringify(body) : undefined }); }
    catch (e) { throw new Retryable(`Firestore unreachable: ${e.message}`); }
    return response;
  };
  return {
    /** The document, or `{ exists: false }` only when Firestore itself says it is not there. */
    async get(path) {
      const response = await call('GET', `${root}/${path}`);
      if (response.status === 404) return { exists: false, updateTime: null, data: null };
      // Quota, an outage or a permission problem is not an empty document.
      if (!response.ok) throw new Retryable(`Firestore read of ${path.split('/')[0]}/… failed: HTTP ${response.status}`);
      const doc = await response.json();
      return { exists: true, updateTime: doc.updateTime, data: decodeFields(doc.fields ?? {}) };
    },
    /** Creates or replaces the whole document. */
    async set(path, data) {
      const response = await call('PATCH', `${root}/${path}`, { fields: encodeFields(data) });
      if (!response.ok) throw new Retryable(`Firestore write failed: HTTP ${response.status}`);
    },
    /** Writes only [fieldPaths] of [data], leaving every other field as it is. */
    async merge(path, data, fieldPaths) {
      const mask = fieldPaths.map((f) => `updateMask.fieldPaths=${encodeURIComponent(f)}`).join('&');
      const response = await call('PATCH', `${root}/${path}?${mask}`, { fields: encodeFields(data) });
      if (!response.ok) throw new Retryable(`Firestore write failed: HTTP ${response.status}`);
    },
    async delete(path) {
      const response = await call('DELETE', `${root}/${path}`);
      if (!response.ok && response.status !== 404) throw new Retryable(`Firestore delete failed: HTTP ${response.status}`);
    },
  };
}

// ---- The library service's admin routes -----------------------------------------------------------

export function libraryClient({ url, secret, fetcher = fetch }) {
  if (!url || !secret) throw new Error('LIBRARY_URL and LIBRARY_ADMIN_SECRET are required');
  return async (uid, action, body = {}) => {
    let response;
    try {
      response = await fetcher(`${url.replace(/\/$/, '')}/v1/library/admin/${encodeURIComponent(uid)}/${action}`,
        { method: 'POST', headers: { 'x-library-admin': secret, 'content-type': 'application/json' }, body: JSON.stringify(body) });
    } catch (e) { throw new Retryable(`library service unreachable: ${e.message}`); }
    if (response.status === 404) throw new Error('library admin routes answered 404: wrong secret, or LIBRARY_ADMIN_SECRET is not set on the Worker');
    if (response.status >= 500 || response.status === 429) throw new Retryable(`library service answered HTTP ${response.status}`);
    return { status: response.status, body: await response.json() };
  };
}

// ---- The steps --------------------------------------------------------------------------------------

const fencePath = (uid) => `libraryAuthority/${uid}`;
const userPath = (uid) => `users/${uid}`;

function save(dir, name, value) {
  if (!dir) return null;
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const file = join(dir, name);
  // A user's whole library: readable by the operator only, and never to be committed.
  writeFileSync(file, JSON.stringify(value), { mode: 0o600 });
  return file;
}

/**
 * The cutover for one account. [stopAfter] ends the run after that step, which is how the tests
 * stand in for a crash: begin, fence, read, import. Returns what happened, never the library.
 */
export async function migrate(uid, { firestore, library, snapshotDir = null, settleMs = 2000, stopAfter = null, log = () => {}, now = Date.now,
  allowlist = null, acceptDropped = false, allowEmpty = false }) {
  const stop = (step) => stopAfter === step;
  if (!allowlist || !allowlist.has(uid)) throw new Error(`${uid}: not in the allowlist file; nothing was changed`);
  // 1. The account's object becomes "migrating": updated clients stop uploading and keep edits locally.
  let state = (await library(uid, 'status')).body;
  if (state.state === 'cloudflare') return { uid, result: 'already-migrated', epoch: state.epoch };
  if (state.state === 'migrating' && state.job?.dir !== 'in') throw new Error(`${uid}: a rollback is in progress; finish it first`);
  const begun = await library(uid, 'begin');
  if (begun.status !== 200) throw new Error(`${uid}: could not begin (${begun.body.error})`);
  state = begun.body;
  log(`${uid}: migrating (epoch ${state.epoch})`);
  if (stop('begin')) return { uid, result: 'stopped', at: 'begin' };

  // 2. The fence: security rules now refuse library writes from every client, old or new.
  await firestore.set(fencePath(uid), { state: 'migrating', epoch: state.epoch, at: now() });
  if (stop('fence')) return { uid, result: 'stopped', at: 'fence' };

  // 3. The final library, read only after the fence is in place. Two reads that agree show no
  //    write was still landing. A read that fails leaves the account fenced and is retried later;
  //    it is never taken as an empty library.
  let source = await firestore.get(userPath(uid));
  for (let attempt = 0; ; attempt += 1) {
    if (settleMs > 0) await new Promise((r) => setTimeout(r, settleMs));
    const again = await firestore.get(userPath(uid));
    if (again.exists === source.exists && again.updateTime === source.updateTime) break;
    if (attempt >= 4) throw new Retryable(`${uid}: the Firestore library is still changing behind the fence`);
    source = again;
  }
  // No document at all imports an empty library: a person must say that is expected.
  if (!source.exists && !allowEmpty) {
    throw new Error(`${uid}: there is no Firestore library document; the account stays fenced. Run abort, or migrate again with --allow-empty if it is really empty`);
  }
  const converted = fromUserDocument(source.data, now());
  const sum = await checksum(converted.library);
  const expected = counts(converted.library);
  const frozen = save(snapshotDir, `${uid}.source.${source.updateTime ?? 'missing'}.json`.replace(/[:]/g, '-'),
    { uid, readAt: new Date(now()).toISOString(), exists: source.exists, updateTime: source.updateTime, document: source.data });
  log(`${uid}: read final library (${source.exists ? `updated ${source.updateTime}` : 'no document'}): ${JSON.stringify(expected)}` +
    (converted.dropped.tracks || converted.dropped.items ? `; unusable entries left behind: ${JSON.stringify(converted.dropped)}` : ''));
  if (stop('read')) return { uid, result: 'stopped', at: 'read' };
  // Entries the library service can't store (no id) would silently disappear. Stop for a person to look.
  if ((converted.dropped.tracks || converted.dropped.items) && !acceptDropped) {
    throw new Error(`${uid}: the source has unusable entries ${JSON.stringify(converted.dropped)}; the account stays fenced. ` +
      'Review the frozen source, then run abort, or migrate again with --accept-dropped');
  }

  // 4. Import. Sending the same library again is a no-op; a different one replaces the import.
  const imported = await library(uid, 'import', { library: converted.library, checksum: sum,
    source: { exists: source.exists, updateTime: source.updateTime, schemaVersion: source.data?.schemaVersion ?? null, dropped: converted.dropped } });
  if (imported.status !== 200) throw new Error(`${uid}: import refused (${imported.body.error})`);
  if (stop('import')) return { uid, result: 'stopped', at: 'import' };

  // 5. Verify what the object actually stored, read back from its own rows.
  const verified = imported.body;
  if (verified.checksum !== sum || JSON.stringify(verified.counts) !== JSON.stringify(expected)) {
    throw new Error(`${uid}: stored library does not match the source (${JSON.stringify(verified.counts)} vs ${JSON.stringify(expected)}); nothing was flipped`);
  }

  // 6. The flip. Refused by the object unless its stored checksum is this one.
  const committed = await library(uid, 'commit', { checksum: sum });
  if (committed.status !== 200) throw new Error(`${uid}: commit refused (${committed.body.error}); the account stays fenced`);
  // Informational only: the object is the authority, the fence just has to exist.
  await firestore.set(fencePath(uid), { state: 'cloudflare', epoch: committed.body.epoch, at: now(), checksum: sum });
  log(`${uid}: now on the library service (epoch ${committed.body.epoch})`);
  // A first export straight away, checked, so a known-good copy exists before any device writes.
  const exported = await saveExport(uid, { library, snapshotDir, now });
  if (exported.checksum !== sum) throw new Error(`${uid}: the first export does not match the import; run rollback`);
  return { uid, result: 'migrated', epoch: committed.body.epoch, checksum: sum, counts: expected, dropped: converted.dropped, frozen, export: exported.file };
}

/** Exports an account's library, saves it, and checks the saved file against its checksum. */
export async function saveExport(uid, { library, snapshotDir = null, now = Date.now }) {
  const exported = (await library(uid, 'export')).body;
  if (exported.error) throw new Error(`${uid}: export refused (${exported.error})`);
  if ((await checksum(exported.library)) !== exported.checksum) throw new Error(`${uid}: the export does not match its own checksum`);
  const file = save(snapshotDir, `${uid}.export.rev${exported.rev}.e${exported.epoch}.json`,
    { uid, exportedAt: new Date(now()).toISOString(), rev: exported.rev, epoch: exported.epoch, checksum: exported.checksum, library: exported.library });
  if (file) {
    const back = JSON.parse(readFileSync(file, 'utf8'));
    if ((await checksum(back.library)) !== exported.checksum) throw new Error(`${uid}: the saved export file does not verify`);
  }
  return { checksum: exported.checksum, counts: exported.counts, rev: exported.rev, file, library: exported.library };
}

/** Reads and checks an export file written by [saveExport]. */
export async function readExport(file) {
  const saved = JSON.parse(readFileSync(file, 'utf8'));
  if (!saved?.library || typeof saved.checksum !== 'string') throw new Error('not an export file');
  if ((await checksum(saved.library)) !== saved.checksum) throw new Error('the export file does not match its checksum; it is damaged');
  return saved;
}

/**
 * Puts an export back into an account on the library service. The current library is exported
 * first, so a restore can itself be undone. Devices start over from the restored library; edits
 * made after the export are lost, which is what restoring means.
 */
export async function restore(uid, file, { library, snapshotDir = null, log = () => {}, now = Date.now }) {
  const saved = await readExport(file);
  if (saved.uid !== uid) throw new Error(`${uid}: that export belongs to another account, or names none`);
  const state = (await library(uid, 'status')).body;
  if (state.state !== 'cloudflare' && !(state.state === 'migrating' && state.job?.dir === 'out')) throw new Error(`${uid}: not on the library service`);
  const backup = await saveExport(uid, { library, snapshotDir, now });
  const frozen = await library(uid, 'rollback-begin');
  if (frozen.status !== 200) throw new Error(`${uid}: could not freeze (${frozen.body.error})`);
  const restored = await library(uid, 'restore', { library: saved.library, checksum: saved.checksum });
  if (restored.status !== 200 || restored.body.checksum !== saved.checksum) {
    throw new Error(`${uid}: restore failed (${restored.body.error ?? 'checksum mismatch'}); the account stays frozen. Backup: ${backup.file}`);
  }
  const resumed = await library(uid, 'rollback-abort');
  if (resumed.status !== 200) throw new Error(`${uid}: restored but could not unfreeze (${resumed.body.error})`);
  log(`${uid}: restored the export from rev ${saved.rev}; backup of what it replaced: ${backup.file ?? '(not saved)'}`);
  return { uid, result: 'restored', checksum: saved.checksum, epoch: resumed.body.epoch, backup: backup.file };
}

/**
 * The pilot's stop conditions, checked for every account in the allowlist. Any hit means: migrate
 * no more accounts until a person has looked.
 */
export async function report(allowlist, { firestore, library, now = Date.now, stuckMs = 3_600_000 }) {
  const accounts = [];
  const stops = [];
  for (const uid of allowlist) {
    const s = await status(uid, { firestore, library });
    const raw = (await library(uid, 'status')).body;
    const reasons = [];
    if (s.problem) reasons.push(s.problem);
    if (s.state === 'migrating' && raw.job?.dir === 'in' && now() - (raw.job.startedAt ?? now()) > stuckMs) reasons.push('stuck migrating for over an hour');
    if (s.state === 'migrating' && raw.job?.dir === 'out') reasons.push('frozen for a rollback or restore');
    if ((raw.seen?.bad ?? 0) > 0 || (raw.seen?.limit ?? 0) > 0) reasons.push(`the service refused client changes (bad ${raw.seen.bad}, over limit ${raw.seen.limit})`);
    if (raw.job?.source?.dropped && (raw.job.source.dropped.tracks || raw.job.source.dropped.items)) reasons.push('the import left unusable entries behind');
    const imported = raw.job?.counts;
    if (s.state === 'cloudflare' && imported) {
      // Most of a list vanishing is how a sync bug looks: the same rule the clients use.
      for (const [name, key] of [['playlists', 'p'], ['favorites', 'f'], ['albums', 'a'], ['artists', 'r']]) {
        const before = imported[name] ?? 0; const live = raw.live?.[key] ?? 0;
        if (before >= 6 && live * 2 < before) reasons.push(`${name} fell from ${before} at import to ${live}`);
      }
    }
    accounts.push({ ...s, seen: raw.seen, imported: imported ?? null, stop: reasons });
    if (reasons.length) stops.push({ uid, reasons });
  }
  return { accounts, stops, ok: stops.length === 0 };
}

export function readAllowlist(file) {
  const uids = readFileSync(file, 'utf8').split('\n').map((l) => l.replace(/#.*/, '').trim()).filter(Boolean);
  if (uids.length === 0) throw new Error('the allowlist file names no accounts');
  if (uids.length > 25) throw new Error('more than 25 accounts: the pilot allowlist is for internal accounts only');
  return new Set(uids);
}

/** Before the flip only: the account goes back to Firestore exactly as it was. */
export async function abort(uid, { firestore, library, log = () => {} }) {
  const aborted = await library(uid, 'abort');
  if (aborted.status !== 200) throw new Error(`${uid}: cannot abort (${aborted.body.error}); after the flip use rollback`);
  // The object is legacy first, the fence goes second: in between, writes are refused, never lost.
  await firestore.delete(fencePath(uid));
  log(`${uid}: migration abandoned; Firestore is the library again`);
  return { uid, result: 'aborted', epoch: aborted.body.epoch };
}

/**
 * After the flip: freezes the library service, writes its latest library back into the Firestore
 * document old clients use, checks it, then lifts the fence. Edits made on the library service
 * are kept; nothing is restored from the stale frozen copy.
 */
export async function rollback(uid, { firestore, library, snapshotDir = null, stopAfter = null, log = () => {}, now = Date.now }) {
  const frozen = await library(uid, 'rollback-begin');
  if (frozen.status !== 200) {
    if (frozen.body.state === 'legacy') { await firestore.delete(fencePath(uid)); return { uid, result: 'already-legacy' }; }
    throw new Error(`${uid}: cannot roll back (${frozen.body.error})`);
  }
  if (stopAfter === 'freeze') return { uid, result: 'stopped', at: 'freeze' };
  const exported = await saveExport(uid, { library, snapshotDir, now });
  const file = exported.file;
  // Backend credentials bypass the fence, which stays up until the document is verified.
  await firestore.merge(userPath(uid), toUserDocument(exported.library, now()), ROLLBACK_FIELDS);
  if (stopAfter === 'write') return { uid, result: 'stopped', at: 'write' };
  const written = await firestore.get(userPath(uid));
  // Both sides through the same conversion at the same moment, so a deletion record crossing its
  // 180-day limit in between can't make a correct write look wrong.
  const at = now();
  const expected = await checksum(fromUserDocument(toUserDocument(exported.library, at), at).library);
  const back = await checksum(fromUserDocument(written.data, at).library);
  if (back !== expected) throw new Error(`${uid}: the Firestore document does not match the export; the account stays frozen`);
  const done = await library(uid, 'rollback-commit', { checksum: exported.checksum });
  if (done.status !== 200) throw new Error(`${uid}: rollback commit refused (${done.body.error})`);
  await firestore.delete(fencePath(uid));
  log(`${uid}: rolled back; Firestore is the library again (${JSON.stringify(exported.counts)})`);
  return { uid, result: 'rolled-back', epoch: done.body.epoch, checksum: exported.checksum, counts: exported.counts, export: file };
}

/** Where an account is, and whether the fence agrees with it. */
export async function status(uid, { firestore, library }) {
  const state = (await library(uid, 'status')).body;
  const fence = await firestore.get(fencePath(uid));
  const fenced = fence.exists;
  const problem = state.state === 'cloudflare' && !fenced ? 'on the library service WITHOUT a fence: old clients can still write Firestore'
    : state.state === 'legacy' && fenced ? 'legacy but still fenced: nobody can write the library; run abort or rollback again to lift it'
    : null;
  return { uid, state: state.state, epoch: state.epoch, rev: state.rev, step: state.job?.step ?? null, live: state.live, clientPushes: state.pushes, fenced, problem };
}

// ---- Command line -----------------------------------------------------------------------------------

async function main(argv) {
  const args = [...argv];
  const flag = (name) => { const i = args.indexOf(name); if (i < 0) return null; const v = args[i + 1]; args.splice(i, 2); return v; };
  const bare = (name) => { const i = args.indexOf(name); if (i < 0) return false; args.splice(i, 1); return true; };
  const allowlistFile = flag('--allowlist');
  const acceptDropped = bare('--accept-dropped');
  const allowEmpty = bare('--allow-empty');
  const acceptRetire = bare('--old-devices-will-stop-syncing');
  const [command, ...rest] = args;
  const usage = () => { console.error('usage: library-migrate.mjs <status|report|migrate|abort|rollback|export|restore> ... (see the top of this file)'); return 2; };
  if (!command) return usage();
  const env = process.env;
  const deps = {
    firestore: firestoreClient({ projectId: env.FIREBASE_PROJECT_ID, emulatorHost: env.FIRESTORE_EMULATOR_HOST, keyPath: env.GOOGLE_APPLICATION_CREDENTIALS }),
    library: libraryClient({ url: env.LIBRARY_URL, secret: env.LIBRARY_ADMIN_SECRET }),
    snapshotDir: env.LIBRARY_SNAPSHOT_DIR ?? './library-snapshots',
    log: (line) => console.log(line),
  };
  if (command === 'report') {
    if (!allowlistFile) { console.error('report needs --allowlist <file>'); return 2; }
    const result = await report(readAllowlist(allowlistFile), deps);
    for (const a of result.accounts) console.log(JSON.stringify(a));
    console.log(result.ok ? 'OK: no stop condition hit' : `STOP: ${result.stops.length} account(s) need a person to look`);
    return result.ok ? 0 : 3;
  }
  if (command === 'bridge-status' || command === 'retire-bridge' || command === 'restore-bridge') {
    const [uid] = rest;
    if (!uid) return usage();
    if (command === 'bridge-status') { console.log(JSON.stringify({ uid, bridge: (await deps.library(uid, 'status')).body.bridge })); return 0; }
    if (command === 'restore-bridge') { console.log(JSON.stringify({ uid, bridge: (await deps.library(uid, 'restore-bridge')).body.bridge })); return 0; }
    const current = (await deps.library(uid, 'status')).body.bridge;
    console.error([
      'WARNING: retiring the bridge for this account means:',
      '  - Devices of this account still running an old Auralis version stop exchanging settings and',
      '    listening history with updated devices. They keep syncing only with other old devices.',
      '  - There is no way to detect every old device: one that never changes a setting is invisible.',
      `  - Last old-app activity seen: ${current?.oldAppLastSeen ? new Date(current.oldAppLastSeen).toISOString() : 'never'}.`,
      '  - Undo with: restore-bridge <uid>. Changes old devices made in the meantime are picked up again then.',
    ].join('\n'));
    if (!acceptRetire) { console.error('Not retired. Repeat with --old-devices-will-stop-syncing to confirm.'); return 2; }
    const { RETIRE_ACKNOWLEDGEMENT } = await import('../src/settings-core.js');
    const done = await deps.library(uid, 'retire-bridge', { acknowledge: RETIRE_ACKNOWLEDGEMENT });
    console.log(JSON.stringify({ uid, status: done.status, bridge: done.body.bridge }));
    return done.status === 200 ? 0 : 1;
  }
  if (command === 'restore') {
    const [uid, file] = rest;
    if (!uid || !file) return usage();
    try { console.log(JSON.stringify(await restore(uid, file, deps))); return 0; }
    catch (e) { console.error(`${uid}: FAILED: ${e.message}`); return 1; }
  }
  if (rest.length === 0) return usage();
  let allowlist = null;
  if (command === 'migrate') {
    if (!allowlistFile) { console.error('migrate needs --allowlist <file>: only accounts named there are moved'); return 2; }
    allowlist = readAllowlist(allowlistFile);
    // Stop conditions first: no new account moves while an earlier one needs attention.
    const before = await report(allowlist, deps);
    if (!before.ok) { for (const s of before.stops) console.error(`STOP ${s.uid}: ${s.reasons.join('; ')}`); return 3; }
  }
  let failed = 0;
  for (const uid of rest) {
    try {
      if (command === 'status') console.log(JSON.stringify(await status(uid, deps)));
      else if (command === 'migrate') { const r = await migrate(uid, { ...deps, allowlist, acceptDropped, allowEmpty }); console.log(JSON.stringify({ ...r, frozen: undefined, export: undefined })); }
      else if (command === 'abort') console.log(JSON.stringify(await abort(uid, deps)));
      else if (command === 'rollback') { const r = await rollback(uid, deps); console.log(JSON.stringify({ ...r, export: undefined })); }
      else if (command === 'export') {
        const r = await saveExport(uid, deps);
        console.log(JSON.stringify({ uid, rev: r.rev, checksum: r.checksum, counts: r.counts, file: r.file, verified: true }));
      } else { console.error(`unknown command ${command}`); return 2; }
    } catch (e) {
      failed += 1;
      // The uid and the reason, never the library.
      console.error(`${uid}: ${e instanceof Retryable ? 'not done, safe to run again' : 'FAILED'}: ${e.message}`);
    }
  }
  return failed ? 1 : 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) process.exitCode = await main(process.argv.slice(2));
