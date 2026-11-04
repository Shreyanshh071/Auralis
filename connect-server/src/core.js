// The rules of one account's Connect state, as pure functions. This is firestore.rules for the
// session, queue, intent and device documents, so behaviour doesn't change with the transport, and
// nothing here knows about Cloudflare: a server on another host reuses this file.

export const MAX_FRAME_BYTES = 64 * 1024;
export const MAX_DEVICES = 32;
export const MAX_SOCKETS = 16;
export const LEASE_AHEAD_MS = 120_000;
export const MAX_ID = 64;
/** Sessions that differ only by a routine report are kept out of storage for at most this many versions. */
export const UNSAVED_VERSIONS = 64;

const SESSION_KEYS = new Set(['version', 'ownerId', 'ownerName', 'ownerType', 'epoch', 'leaseUntil', 'track', 'playing',
  'buffering', 'positionMs', 'positionAt', 'durationMs', 'queueRev', 'queueIndex', 'shuffle', 'repeat', 'context',
  'transfer', 'commands', 'updatedBy', 'updatedAt']);
const DEVICE_KEYS = new Set(['id', 'name', 'nickname', 'platform', 'type', 'appVersion', 'canPlay', 'online', 'createdAt', 'seenAt']);
const DEVICE_TYPES = new Set(['PHONE', 'TABLET', 'COMPUTER', 'SPEAKER', 'BROWSER']);
const INTENT_KEYS = ['version', 'epoch', 'at', 'by'];
const TRANSFER_STATUS = new Set(['requested', 'ready', 'committed', 'failed']);
const ACTIVE_TRANSFER = new Set(['requested', 'ready']);

const isInt = (v) => Number.isSafeInteger(v);
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isStr = (v, max) => typeof v === 'string' && v.length <= max;
const isMap = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

export function validSession(s, now) {
  if (!isMap(s) || Object.keys(s).some((k) => !SESSION_KEYS.has(k))) return false;
  return isInt(s.version) && isInt(s.epoch) && isInt(s.queueRev)
    && isStr(s.ownerId, MAX_ID) && isStr(s.ownerName, 80)
    && isNum(s.leaseUntil) && s.leaseUntil <= now + LEASE_AHEAD_MS
    && typeof s.playing === 'boolean' && typeof s.buffering === 'boolean'
    && isNum(s.positionMs) && isNum(s.positionAt) && isNum(s.durationMs)
    && typeof s.shuffle === 'boolean' && ['OFF', 'ALL', 'ONE'].includes(s.repeat)
    && (s.track === null || s.track === undefined || isMap(s.track))
    && (s.transfer === null || s.transfer === undefined || (isMap(s.transfer) && TRANSFER_STATUS.has(s.transfer.status)))
    && isMap(s.commands) && Object.keys(s.commands).length <= 20
    && isStr(s.updatedBy, MAX_ID);
}

export function validQueue(q) {
  return isMap(q) && isInt(q.rev) && isInt(q.offset) && Array.isArray(q.tracks) && q.tracks.length <= 100
    && Object.keys(q).every((k) => k === 'rev' || k === 'offset' || k === 'tracks');
}

export function validIntent(i, now) {
  return isMap(i) && Object.keys(i).length === INTENT_KEYS.length && INTENT_KEYS.every((k) => k in i)
    && isInt(i.version) && isInt(i.epoch) && isNum(i.at) && i.at <= now + LEASE_AHEAD_MS && isStr(i.by, MAX_ID);
}

export function validDevice(d, deviceId) {
  if (!isMap(d) || Object.keys(d).some((k) => !DEVICE_KEYS.has(k))) return false;
  return d.id === deviceId && isStr(d.name, 80) && (d.nickname === null || d.nickname === undefined || isStr(d.nickname, 80))
    && DEVICE_TYPES.has(d.type) && isStr(d.platform, 40) && isStr(d.appVersion, 40)
    && typeof d.canPlay === 'boolean' && typeof d.online === 'boolean' && isNum(d.createdAt);
}

export const validDeviceId = (id) => typeof id === 'string' && /^[A-Za-z0-9._-]{1,64}$/.test(id);

/** A new owner takes the next epoch, and only as firestore.rules allows. */
function ownerChangeAllowed(before, after, now) {
  if (after.epoch !== before.epoch + 1) return false;
  const t = before.transfer;
  return before.ownerId === '' || before.leaseUntil < now
    || (!!t && ACTIVE_TRANSFER.has(t.status) && t.to === after.ownerId && t.epoch === after.epoch)
    || after.updatedBy === before.ownerId;
}

/**
 * One compare-and-swap write: the transaction Firestore ran. `base` is the version the client built
 * on. A conflict hands back the newer state so it can redo its step, as a Firestore retry does.
 */
export function decideUpdate(current, base, next, deviceId, now) {
  if (!validSession(next, now)) return { result: 'refused', code: 'invalid' };
  if (next.updatedBy !== deviceId) return { result: 'refused', code: 'not-this-device' };
  const known = current?.version ?? 0;
  if (base !== known) return { result: 'conflict' };
  if (current === null || current === undefined) {
    if (next.version !== 1 || ![0, 1].includes(next.epoch)) return { result: 'refused', code: 'bad-version' };
  } else {
    if (next.version !== current.version + 1) return { result: 'refused', code: 'bad-version' };
    const unchanged = next.ownerId === current.ownerId && next.epoch === current.epoch;
    if (!unchanged && !ownerChangeAllowed(current, next, now)) return { result: 'refused', code: 'not-allowed' };
  }
  return { result: 'ok', session: { ...next, updatedAt: now } };
}

/**
 * What must reach storage at once: who owns the output, because that fences every other write. The
 * rest of the session rides on the writer's socket and is saved when the last device leaves, since
 * storage writes are the tightest free limit. A crash can lose a few reports; the output repeats
 * its real state when it reconnects, and nothing a client plays depends on them.
 */
export const mustSave = (before, after) => !before || before.ownerId !== after.ownerId || before.epoch !== after.epoch;

/** The intent document never goes backwards. */
export const intentAccepted = (current, next, now) => validIntent(next, now) && (!current || next.version >= current.version);

/**
 * After a cold start only what was saved is left, up to UNSAVED_VERSIONS behind. Clients refuse a
 * session older than one they saw, so the version moves past everything that may have been handed out.
 */
export function recoverSession(saved, savedVersionGap = UNSAVED_VERSIONS) {
  return saved ? { ...saved, version: saved.version + savedVersionGap } : null;
}

/** A token bucket per connection: bursts of seeks pass, a flood doesn't. */
export class RateLimit {
  constructor(burst = 40, perSecond = 10) { this.burst = burst; this.perSecond = perSecond; this.tokens = burst; this.at = 0; this.refused = 0; }
  allow(now) {
    this.tokens = Math.min(this.burst, this.tokens + (now - this.at) / 1000 * this.perSecond);
    this.at = now;
    if (this.tokens < 1) { this.refused++; return false; }
    this.tokens -= 1;
    // Only real calm clears the count, so a flood can't excuse itself with the odd token.
    if (this.tokens >= this.burst / 2) this.refused = 0;
    return true;
  }
}
