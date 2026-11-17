// Listening history as the apps already merge it: events keyed "<startMs>_<trackId>" in one map per
// UTC day; the same key from two devices keeps the larger listened time. Pure functions.

export const MAX_DAY_EVENTS = 3000;
export const MAX_PUSH_EVENTS = 1000;
export const IMPORT_DAILY_ROWS = 20_000;

const isStr = (v, max) => typeof v === 'string' && v.length <= max;
const whole = (v) => (Number.isSafeInteger(v) ? v : null);

/** One event as stored (the Firestore day-document shape, plus `d`, the recording device). */
export function listenEvent(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const ts = whole(raw.ts); const ms = whole(raw.ms);
  if (!isStr(raw.t, 256) || !raw.t || ts === null || ts <= 0 || ms === null || ms <= 0 || ms > 24 * 3_600_000) return null;
  const text = (v, max) => (isStr(v, max) && v !== '' ? v : null);
  return { t: raw.t, ts, ms, ti: text(raw.ti, 300), ar: text(raw.ar, 300), al: text(raw.al, 300), th: text(raw.th, 600),
    du: whole(raw.du) ?? 0, d: text(raw.d, 80),
    ...(text(raw.as, 8192) ? { as: text(raw.as, 8192) } : {}) };
}

export const eventKey = (e) => `${e.ts}_${e.t}`;
export const dayOf = (ts) => new Date(ts).toISOString().slice(0, 10);

/**
 * Merges [incoming] events into the day maps already stored (`days`: day → { key → event }).
 * Returns the changed days with their full maps, and per day only the events that changed.
 */
export function mergeListens(days, incoming, clearedBefore = 0) {
  const changed = new Map();
  for (const raw of incoming) {
    const e = listenEvent(raw);
    if (!e || e.ts < clearedBefore) continue;
    const day = dayOf(e.ts);
    const map = changed.get(day)?.map ?? { ...(days[day] ?? {}) };
    const key = eventKey(e);
    const held = map[key];
    if (held && held.ms >= e.ms) continue;
    if (!held && Object.keys(map).length >= MAX_DAY_EVENTS) continue;
    map[key] = { ...e, ...(held && !e.d ? { d: held.d } : {}),
      ...(held?.as && !e.as ? { as: held.as } : {}) };
    const entry = changed.get(day) ?? { map, delta: {} };
    entry.map = map; entry.delta[key] = map[key];
    changed.set(day, entry);
  }
  return changed;
}

/** A day map with everything before [before] removed. */
export function clearBefore(map, before) {
  return Object.fromEntries(Object.entries(map).filter(([, e]) => e.ts >= before));
}
