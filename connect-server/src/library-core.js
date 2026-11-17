// The rules of one account's library, as pure functions: shapes, limits, the canonical form used
// for checksums, and the conversion from the Firestore `users/{uid}` document. Nothing here knows
// about Cloudflare, so the migration tool and the tests use the same code as the server.

export const PROTOCOL_VERSION = 1;
export const MAX_OPS = 500;
export const MAX_BODY_BYTES = 4 * 1024 * 1024;
export const MAX_PLAYLISTS = 1000;
export const MAX_PLAYLIST_TRACKS = 10_000;
export const MAX_ITEMS = 20_000;
export const MAX_RECORD_BYTES = 4 * 1024;
export const MAX_PINS = 200;
export const MAX_ID = 256;
export const CHUNK_TRACKS = 200;
export const PAGE_BYTES = 512 * 1024;
export const OP_MEMORY_MS = 30 * 86_400_000;
export const OP_MEMORY_COUNT = 5000;
export const REMOVAL_MEMORY_MS = 180 * 86_400_000;
export const REMOVAL_MEMORY_COUNT = 5000;

/** Liked songs, saved albums, saved artists. */
export const KINDS = { fav: 'f', alb: 'a', art: 'r' };

const isStr = (v) => typeof v === 'string';
const isMap = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const text = (v) => (isStr(v) && v !== '' && v !== 'null' ? v : null);
const whole = (v) => (Number.isFinite(v) ? Math.trunc(v) : 0);
export const validId = (v) => isStr(v) && v.length > 0 && v.length <= MAX_ID;

export function track(t) {
  if (!isMap(t) || !validId(t.id)) return null;
  return { id: t.id, title: text(t.title) ?? 'Unknown Track', artist: text(t.artist) ?? 'Unknown Artist', album: text(t.album),
    thumbnail: isStr(t.thumbnail) && t.thumbnail !== 'null' ? t.thumbnail : '', duration: whole(t.duration) };
}

export function album(a) {
  if (!isMap(a) || !validId(a.id) || !text(a.title)) return null;
  return { id: a.id, title: a.title, artist: text(a.artist), thumbnail: text(a.thumbnail),
    trackCount: whole(a.trackCount) > 0 ? whole(a.trackCount) : null, savedAt: whole(a.savedAt) };
}

export function artist(a) {
  if (!isMap(a) || !validId(a.id) || !text(a.name)) return null;
  return { id: a.id, name: a.name, thumbnail: text(a.thumbnail), subscribers: text(a.subscribers) };
}

export const ITEM = { f: track, a: album, r: artist };

/** A playlist's own fields, without its tracks. */
export function playlistMeta(p) {
  if (!isMap(p) || !validId(p.id)) return null;
  return { id: p.id, title: text(p.title) ?? 'Restored Playlist', description: text(p.description), coverUrl: text(p.coverUrl),
    createdAt: whole(p.createdAt), isCustom: p.isCustom !== false };
}

export const fits = (record) => JSON.stringify(record).length <= MAX_RECORD_BYTES;

/** Tracks as stored: every valid one, in order, duplicates included. Null when any is unusable. */
export function tracks(list) {
  if (!Array.isArray(list) || list.length > MAX_PLAYLIST_TRACKS) return null;
  const out = [];
  for (const t of list) {
    const clean = track(t);
    if (!clean || !fits(clean)) return null;
    out.push(clean);
  }
  return out;
}

export function chunks(list) {
  const out = [];
  for (let i = 0; i < list.length; i += CHUNK_TRACKS) out.push(JSON.stringify(list.slice(i, i + CHUNK_TRACKS)));
  return out;
}

export function pins(list) {
  if (!Array.isArray(list) || list.length > MAX_PINS) return null;
  return list.every(validId) ? list : null;
}

/**
 * One operation, checked and cleaned, or null. `ifRev` is the revision of the playlist the device
 * last saw (0 when it has never seen it).
 */
export function operation(op) {
  if (!isMap(op) || !validId(op.id) || !isStr(op.t)) return null;
  const [family, verb] = op.t.split('.');
  if (KINDS[family]) {
    const k = KINDS[family];
    if (verb === 'del') return validId(op.key) ? { id: op.id, t: op.t, k, key: op.key } : null;
    if (verb !== 'put') return null;
    const v = ITEM[k](op.v);
    return v && fits(v) ? { id: op.id, t: op.t, k, key: v.id, v } : null;
  }
  if (op.t === 'pl.put') {
    const meta = playlistMeta(op.v);
    if (!meta || !fits(meta) || !Number.isSafeInteger(op.ifRev) || op.ifRev < 0) return null;
    const list = op.tracks === undefined || op.tracks === null ? null : tracks(op.tracks);
    if (op.tracks !== undefined && op.tracks !== null && !list) return null;
    return { id: op.id, t: op.t, key: meta.id, ifRev: op.ifRev, v: meta, tracks: list };
  }
  if (op.t === 'pl.del') {
    if (!validId(op.key) || !Number.isSafeInteger(op.ifRev) || op.ifRev < 0) return null;
    return { id: op.id, t: op.t, key: op.key, ifRev: op.ifRev, at: whole(op.at) };
  }
  if (op.t === 'pins.set') {
    const list = pins(op.pins);
    return list && Number.isSafeInteger(op.at) && op.at > 0 ? { id: op.id, t: op.t, pins: list, at: op.at } : null;
  }
  return null;
}

/**
 * A whole library in snapshot form, cleaned, or a reason it can't be stored. This is what the
 * migration imports and what export returns.
 */
export function library(input) {
  if (!isMap(input)) return { error: 'not a library' };
  const seen = new Set();
  const playlists = [];
  for (const p of input.playlists ?? []) {
    const meta = playlistMeta(p);
    if (!meta || !fits(meta)) return { error: 'bad playlist' };
    if (seen.has(meta.id)) return { error: 'duplicate playlist id' };
    seen.add(meta.id);
    const list = tracks(p.tracks ?? []);
    if (!list) return { error: `bad tracks in playlist ${playlists.length}` };
    playlists.push({ ...meta, tracks: list });
  }
  if (playlists.length > MAX_PLAYLISTS) return { error: 'too many playlists' };
  const items = {};
  for (const [name, k] of [['favorites', 'f'], ['albums', 'a'], ['artists', 'r']]) {
    const ids = new Set();
    const out = [];
    for (const raw of input[name] ?? []) {
      const v = ITEM[k](raw);
      if (!v || !fits(v)) return { error: `bad ${name} entry` };
      if (ids.has(v.id)) continue; // A set: the first one keeps its place.
      ids.add(v.id);
      out.push(v);
    }
    if (out.length > MAX_ITEMS) return { error: `too many ${name}` };
    items[name] = out;
  }
  const pinned = pins(input.pins ?? []);
  if (!pinned) return { error: 'bad pins' };
  const deleted = [];
  for (const d of input.deleted ?? []) {
    if (!isMap(d) || !validId(d.id)) return { error: 'bad deletion record' };
    if (!seen.has(d.id)) deleted.push({ id: d.id, at: whole(d.at) });
  }
  return { library: { playlists, ...items, pins: pinned, pinsAt: whole(input.pinsAt), deleted } };
}

/** The same library always gives the same text: playlists and deletion records by id, lists in order. */
export function canonical(lib) {
  const byId = (a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
  return JSON.stringify({
    playlists: [...lib.playlists].sort(byId).map((p) => [p.id, p.title, p.description, p.coverUrl, p.createdAt, p.isCustom,
      p.tracks.map((t) => [t.id, t.title, t.artist, t.album, t.thumbnail, t.duration])]),
    favorites: lib.favorites.map((t) => [t.id, t.title, t.artist, t.album, t.thumbnail, t.duration]),
    albums: lib.albums.map((a) => [a.id, a.title, a.artist, a.thumbnail, a.trackCount, a.savedAt]),
    artists: lib.artists.map((a) => [a.id, a.name, a.thumbnail, a.subscribers]),
    pins: lib.pins, pinsAt: lib.pinsAt,
    deleted: [...lib.deleted].sort(byId).map((d) => [d.id, d.at]),
  });
}

export async function checksum(lib) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical(lib)));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export const counts = (lib) => ({ playlists: lib.playlists.length, tracks: lib.playlists.reduce((n, p) => n + p.tracks.length, 0),
  favorites: lib.favorites.length, albums: lib.albums.length, artists: lib.artists.length, pins: lib.pins.length,
  deleted: lib.deleted.length });

/**
 * The plain `users/{uid}` document (already decoded from Firestore's typed values) as a library.
 * Tracks without an id can't be played or matched and are counted, not kept. A playlist saved
 * before playlists had ids gets a stable one, so importing twice gives the same result. Deletion
 * records past their lifetime are left behind.
 */
export function fromUserDocument(doc, now = Date.now()) {
  const dropped = { tracks: 0, items: 0 };
  const list = (v) => (Array.isArray(v) ? v.filter(isMap) : []);
  const keep = (raw, clean, key) => {
    const out = [];
    for (const r of list(raw)) { const v = clean(r); if (v) out.push(v); else dropped[key] += 1; }
    return out;
  };
  const playlists = [];
  const used = new Set();
  for (const p of list(doc?.playlists)) {
    const body = keep(p.tracks, track, 'tracks');
    let id = text(p.id);
    if (!id) {
      const base = `legacy:${hash(`${text(p.title) ?? ''}\n${body.map((t) => t.id).join('\n')}`)}`;
      id = base;
      for (let n = 2; used.has(id); n += 1) id = `${base}-${n}`;
    }
    if (used.has(id)) { dropped.items += 1; continue; }
    used.add(id);
    playlists.push({ ...playlistMeta({ ...p, id }), tracks: body });
  }
  const sync = isMap(doc?.librarySync) ? doc.librarySync : {};
  return {
    library: {
      playlists,
      favorites: unique(keep(doc?.favorites, track, 'items')),
      albums: unique(keep(doc?.savedAlbums, album, 'items')),
      artists: unique(keep(doc?.savedArtists, artist, 'items')),
      pins: Array.isArray(sync.pins) ? sync.pins.filter(validId).slice(0, MAX_PINS) : [],
      pinsAt: whole(sync.pinsAt),
      // Clients already ignore a deletion record older than this (DELETION_MEMORY_MS).
      deleted: list(sync.deleted).filter((d) => validId(d.id) && !used.has(d.id) && now - whole(d.at) < REMOVAL_MEMORY_MS)
        .map((d) => ({ id: d.id, at: whole(d.at) })),
    },
    dropped,
  };
}

/** The library as the fields old clients read and write in `users/{uid}` (for a rollback). */
export function toUserDocument(lib, now) {
  return {
    playlists: lib.playlists.map((p) => ({ id: p.id, title: p.title, description: p.description, coverUrl: p.coverUrl,
      createdAt: p.createdAt, isCustom: p.isCustom, tracks: p.tracks })),
    favorites: lib.favorites,
    savedAlbums: lib.albums,
    savedArtists: lib.artists,
    librarySync: { pins: lib.pins, pinsAt: lib.pinsAt, deleted: lib.deleted },
    updatedAt: now, playlistsUpdatedAt: now, favoritesUpdatedAt: now, schemaVersion: 1,
  };
}

function unique(items) {
  const seen = new Set();
  return items.filter((v) => !seen.has(v.id) && seen.add(v.id));
}

function hash(value) {
  let h = 0x811c9dc5;
  for (let i = 0; i < value.length; i += 1) { h ^= value.charCodeAt(i); h = Math.imul(h, 0x01000193); }
  return (h >>> 0).toString(16).padStart(8, '0');
}

/** Compares two secrets without stopping at the first difference. */
export function sameSecret(given, wanted) {
  if (!isStr(given) || !isStr(wanted) || !wanted) return false;
  const a = new TextEncoder().encode(given);
  const b = new TextEncoder().encode(wanted);
  let difference = a.length ^ b.length;
  for (let i = 0; i < a.length && i < b.length; i += 1) difference |= a[i] ^ b[i];
  return difference === 0;
}

/**
 * Whether an operator may start moving [uid]. LIBRARY_PILOT_UIDS lists uids separated by commas
 * or whitespace; unset or empty allows none. "*" is honoured only by the local test server, and
 * "staging-*" only by a Worker that has STAGING_SECRET.
 */
export function pilotAllowed(uid, env) {
  const list = String(env.LIBRARY_PILOT_UIDS ?? '').split(/[\s,]+/).filter(Boolean);
  if (list.includes('*')) return env.DEV_AUTH === 'local-tests-only';
  // "staging-*" covers the staging Worker's synthetic test identities, which can never be real
  // uids. It only works where STAGING_SECRET exists, which production never has.
  if (list.includes('staging-*') && env.STAGING_SECRET && /^staging-[a-z0-9-]{1,40}$/.test(String(uid))) return true;
  return validId(uid) && list.includes(uid);
}
