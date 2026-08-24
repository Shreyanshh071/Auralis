import type { Track } from '../types/music';
import { isTrackLike, type QueueStorage } from './queueStorage.ts';

/**
 * Storage helpers for the listening history ("Recently Played").
 *
 * These live outside PlayerContext, alongside the queue/position helpers in
 * queueStorage.ts, for the same reason: the restore path is fed whatever is in
 * localStorage — values written by an older build or truncated by a crash — so
 * the parsing rules are the part most worth testing directly
 * (`scripts/test-media-session-history.mjs`).
 */

export const HISTORY_STORAGE_KEY = 'auralis_history';

/**
 * How many recently played tracks are retained. Earlier builds kept 50; the cap
 * was raised to 100 when per-entry timestamps were added. The list is bounded so
 * the localStorage write stays small and can never grow without limit.
 */
export const HISTORY_LIMIT = 100;

export interface HistoryEntry {
  track: Track;
  /** Epoch ms the track was pushed onto the history. 0 means "unknown". */
  playedAt: number;
}

/**
 * Reading `localStorage` can throw outright (sandboxed iframes, storage disabled),
 * not just return null, so even the lookup is guarded. Mirrors queueStorage.
 */
function defaultStorage(): QueueStorage | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    return localStorage;
  } catch {
    return null;
  }
}

/** Keep only the first entry seen per track id, preserving order. */
function dedupeByTrackId(entries: HistoryEntry[]): HistoryEntry[] {
  const seen = new Set<string>();
  const out: HistoryEntry[] = [];
  for (const entry of entries) {
    if (seen.has(entry.track.id)) continue;
    seen.add(entry.track.id);
    out.push(entry);
  }
  return out;
}

/**
 * Turn a raw stored string into a usable history.
 *
 * Two on-disk shapes are accepted. The current shape is an array of
 * `{ track, playedAt }`. Builds from before listening-history timestamps wrote a
 * plain `Track[]`; those are migrated in place with an unknown (`0`) timestamp
 * rather than being discarded, so upgrading the app does not silently wipe a
 * user's Recently Played list. Anything malformed yields an empty history, and
 * unplayable entries (no id / title) are dropped individually.
 */
export function parseStoredHistory(raw: string | null): HistoryEntry[] {
  if (!raw) return [];

  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];

    const entries: HistoryEntry[] = [];
    for (const item of parsed) {
      // Current shape: { track, playedAt }.
      if (item && typeof item === 'object' && 'track' in item) {
        const candidate = (item as { track: unknown }).track;
        if (isTrackLike(candidate)) {
          const playedAt = (item as { playedAt?: unknown }).playedAt;
          entries.push({
            track: candidate,
            playedAt:
              typeof playedAt === 'number' && Number.isFinite(playedAt) && playedAt >= 0
                ? playedAt
                : 0,
          });
        }
        continue;
      }
      // Legacy shape: a bare Track.
      if (isTrackLike(item)) {
        entries.push({ track: item, playedAt: 0 });
      }
    }

    return dedupeByTrackId(entries).slice(0, HISTORY_LIMIT);
  } catch {
    return [];
  }
}

/** Read the history written by a previous session. */
export function loadHistory(storage: QueueStorage | null = defaultStorage()): HistoryEntry[] {
  if (!storage) return [];
  try {
    return parseStoredHistory(storage.getItem(HISTORY_STORAGE_KEY));
  } catch {
    return [];
  }
}

/**
 * Write the history. The cap is enforced here too, so a caller cannot persist an
 * unbounded array. Failures are swallowed: history is a convenience, and losing a
 * write to a full or blocked storage is not worth a visible error.
 */
export function saveHistory(
  entries: HistoryEntry[],
  storage: QueueStorage | null = defaultStorage(),
): void {
  if (!storage) return;
  try {
    storage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(entries.slice(0, HISTORY_LIMIT)));
  } catch {
    // Quota exceeded or storage blocked; the next write will try again.
  }
}

/**
 * Prepend a freshly played track. Any existing entry for the same track is
 * removed first, so the most recent play floats to the top exactly once (no
 * duplicate rows), and the result is capped at HISTORY_LIMIT.
 */
export function addToHistory(
  entries: HistoryEntry[],
  track: Track,
  playedAt: number,
): HistoryEntry[] {
  if (!isTrackLike(track)) return entries;
  const stamped = Number.isFinite(playedAt) && playedAt >= 0 ? playedAt : 0;
  const withoutDupe = entries.filter((e) => e.track.id !== track.id);
  return [{ track, playedAt: stamped }, ...withoutDupe].slice(0, HISTORY_LIMIT);
}

/** Drop a single track from the history by id. */
export function removeFromHistory(entries: HistoryEntry[], trackId: string): HistoryEntry[] {
  return entries.filter((e) => e.track.id !== trackId);
}

/** Project the stored entries down to the plain Track[] the UI consumes. */
export function historyTracks(entries: HistoryEntry[]): Track[] {
  return entries.map((e) => e.track);
}
