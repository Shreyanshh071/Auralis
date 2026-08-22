import type { Track } from '../types/music';

// ---------------------------------------------------------------------------
// Pure queue / list ordering helpers.
//
// These are separated from PlayerContext so the ordering rules can be tested
// directly (see scripts/test-queue-ops.mjs) rather than only through React
// state. Every function returns NEW arrays and never mutates its input.
// ---------------------------------------------------------------------------

/** Clamp to an integer inside [0, len-1]; returns 0 for an empty list. */
function clampIndex(value: number, len: number): number {
  if (len <= 0) return 0;
  const n = Math.trunc(Number.isFinite(value) ? value : 0);
  return Math.max(0, Math.min(len - 1, n));
}

/**
 * Move a list element from one position to another, returning a new array.
 * The moved element ends up AT the destination index (drag-and-drop semantics).
 * Out-of-range indices are clamped; a move that changes nothing still returns a
 * fresh copy so callers can rely on referential change.
 */
export function moveItem<T>(list: T[], from: number, to: number): T[] {
  const copy = list.slice();
  if (copy.length < 2) return copy;
  const f = clampIndex(from, copy.length);
  const t = clampIndex(to, copy.length);
  if (f === t) return copy;
  const [moved] = copy.splice(f, 1);
  copy.splice(t, 0, moved);
  return copy;
}

/**
 * Where an index lands after moving the element at `from` to `to`.
 * Purely positional (no identity lookup), so it is correct even when a queue
 * holds the same track more than once. `from`/`to` must already be clamped to
 * the SAME range moveItem used.
 */
export function mapIndexAfterMove(idx: number, from: number, to: number): number {
  if (idx === from) return to;
  let x = idx > from ? idx - 1 : idx; // element pulled out of `from`
  if (x >= to) x += 1; // element pushed back in at `to`
  return x;
}

/**
 * Reorder a queue while keeping `index` pointing at the SAME slot that is
 * currently playing. Returns the new tracks and the follow-up index.
 */
export function reorderQueue(
  tracks: Track[],
  index: number,
  from: number,
  to: number
): { tracks: Track[]; index: number } {
  if (tracks.length < 2) {
    return { tracks: tracks.slice(), index: clampIndex(index, tracks.length) };
  }
  const f = clampIndex(from, tracks.length);
  const t = clampIndex(to, tracks.length);
  const next = moveItem(tracks, f, t);
  if (f === t) return { tracks: next, index: clampIndex(index, next.length) };
  const mappedIndex = mapIndexAfterMove(clampIndex(index, tracks.length), f, t);
  return { tracks: next, index: clampIndex(mappedIndex, next.length) };
}

/**
 * Remove the track at `removeIndex`, keeping `currentIndex` pointing at the same
 * playing track wherever possible.
 *
 * - Removing a track BEFORE the current one shifts the current track left, so
 *   the index is decremented (this is the bug the old inline filter had).
 * - Removing a track AFTER the current one leaves the index alone.
 * - Removing the current track itself keeps the index in place (the next track
 *   has shifted into that slot) and clamps it into range.
 */
export function removeAt(
  tracks: Track[],
  currentIndex: number,
  removeIndex: number
): { tracks: Track[]; index: number } {
  if (removeIndex < 0 || removeIndex >= tracks.length) {
    return { tracks: tracks.slice(), index: clampIndex(currentIndex, tracks.length) };
  }
  const next = tracks.filter((_, i) => i !== removeIndex);
  if (next.length === 0) return { tracks: next, index: 0 };
  const nextIndex = removeIndex < currentIndex ? currentIndex - 1 : currentIndex;
  return { tracks: next, index: clampIndex(nextIndex, next.length) };
}
