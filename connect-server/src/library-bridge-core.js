// Three-way legacy snapshot reconciliation. A bridge echo is a no-op; neither side's stale
// snapshot replaces the other side wholesale. Ordering uses occurrence IDs, so repeated songs
// survive. Concurrent deletion of a baseline entry wins over edits to that entry.
import { canonical, library } from './library-core.js';

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const indexed = (items) => new Map(items.map((x) => [x.id, x]));
const occurrences = (tracks) => {
  const counts = new Map();
  return tracks.map((t) => { const n = (counts.get(t.id) ?? 0) + 1; counts.set(t.id, n); return { key: `${t.id}\n${n}`, t }; });
};

export function mergeTrackLists(base, cloud, legacy) {
  if (same(cloud, legacy) || same(legacy, base)) return cloud;
  if (same(cloud, base)) return legacy;
  const b = occurrences(base); const c = occurrences(cloud); const l = occurrences(legacy);
  const known = new Set(b.map((x) => x.key));
  const cs = new Set(c.map((x) => x.key)); const ls = new Set(l.map((x) => x.key));
  const kept = new Set(b.filter((x) => cs.has(x.key) && ls.has(x.key)).map((x) => x.key));
  const out = c.filter((x) => !known.has(x.key) || kept.has(x.key));
  // An occurrence already present on both sides is an echo. Extra repeated occurrences on
  // either side remain distinct; retries never duplicate an addition already reconciled.
  let previous = null;
  for (const x of l) {
    if (known.has(x.key)) { if (kept.has(x.key)) previous = x.key; continue; }
    if (out.some((y) => y.key === x.key)) { previous = x.key; continue; }
    const key = `legacy:${x.key}`;
    const at = previous == null ? 0 : out.findIndex((y) => y.key === previous) + 1;
    out.splice(at, 0, { key, t: x.t }); previous = key;
  }
  return out.map((x) => x.t);
}

function mergeSet(base, cloud, legacy, merge = (b, c, l) => same(c, b) ? l : c) {
  const b = indexed(base); const c = indexed(cloud); const l = indexed(legacy); const out = [];
  for (const id of new Set([...c.keys(), ...l.keys()])) {
    const before = b.get(id); const here = c.get(id); const there = l.get(id);
    if (before && (!here || !there)) continue;
    out.push(here && there ? merge(before, here, there) : here ?? there);
  }
  return out;
}

export function reconcileLibrary(base, cloud, legacy, now) {
  const deleted = new Map();
  for (const x of [...base.deleted, ...cloud.deleted, ...legacy.deleted]) deleted.set(x.id, Math.max(deleted.get(x.id) ?? 0, x.at));
  const before = indexed(base.playlists);
  const c = indexed(cloud.playlists); const l = indexed(legacy.playlists);
  const cloudDeleted = new Set(cloud.deleted.map((x) => x.id));
  for (const d of base.deleted) if (c.has(d.id) && !cloudDeleted.has(d.id)) deleted.delete(d.id);
  for (const id of before.keys()) if (!c.has(id) || !l.has(id)) deleted.set(id, Math.max(deleted.get(id) ?? 0, now));
  const playlists = mergeSet(base.playlists, cloud.playlists, legacy.playlists, (b, a, z) => ({
    ...(b && same({ ...a, tracks: [] }, { ...b, tracks: [] }) ? z : a),
    tracks: mergeTrackLists(b?.tracks ?? [], a.tracks, z.tracks),
  })).filter((p) => !(deleted.has(p.id) && p.createdAt <= deleted.get(p.id)));
  const live = new Set(playlists.map((p) => p.id));
  const pinsChanged = !same(cloud.pins, base.pins) || cloud.pinsAt !== base.pinsAt;
  const chosenPins = pinsChanged ? cloud : legacy;
  const next = { playlists,
    favorites: mergeSet(base.favorites, cloud.favorites, legacy.favorites),
    albums: mergeSet(base.albums, cloud.albums, legacy.albums),
    artists: mergeSet(base.artists, cloud.artists, legacy.artists),
    pins: chosenPins.pins, pinsAt: chosenPins.pinsAt,
    deleted: [...deleted].filter(([id]) => !live.has(id)).map(([id, at]) => ({ id, at })),
  };
  const checked = library(next);
  if (checked.error) throw new Error(`legacy-shape:${checked.error}`);
  return checked.library;
}

export const sameLibrary = (a, b) => canonical(a) === canonical(b);
