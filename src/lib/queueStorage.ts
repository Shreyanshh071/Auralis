import type { Playlist, Track } from '../types/music';

/**
 * Storage helpers for the playback queue and stored playlists.
 *
 * These live outside PlayerContext so the parsing rules can be exercised
 * directly by `scripts/test-queue-storage.mjs` — the restore path is fed
 * whatever happens to be in localStorage, including values written by an older
 * build or truncated by a crash, so it is the part most worth testing.
 */

export const QUEUE_STORAGE_KEY = 'auralis_queue';
export const PLAYLISTS_STORAGE_KEY = 'auralis_playlists';

export interface StoredQueue {
  tracks: Track[];
  index: number;
  currentTrack: Track | null;
}

/** The subset of the Web Storage API used here, so a fake can be passed in tests. */
export interface QueueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

/** A fresh object each call, so a caller handing `tracks` to state cannot alias it. */
export function emptyStoredQueue(): StoredQueue {
  return { tracks: [], index: 0, currentTrack: null };
}

/**
 * Reading `localStorage` can throw outright (sandboxed iframes, storage disabled),
 * not just return null, so even the lookup is guarded.
 */
function defaultStorage(): QueueStorage | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    return localStorage;
  } catch {
    return null;
  }
}

/** A stored entry is only usable if it can actually be loaded into the player. */
export function isTrackLike(value: unknown): value is Track {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<Track>;
  return (
    typeof candidate.id === 'string' &&
    candidate.id.length > 0 &&
    typeof candidate.title === 'string'
  );
}

/**
 * Turn a raw stored string into a queue that is safe to render and play.
 *
 * Anything malformed yields an empty queue rather than being trusted. A
 * half-written or hand-edited value would otherwise populate the queue panel
 * with rows that cannot be played, and an out-of-range index would make
 * next/previous step from the wrong position.
 */
export function parseStoredQueue(raw: string | null): StoredQueue {
  if (!raw) return emptyStoredQueue();

  try {
    const parsed = JSON.parse(raw) as Partial<StoredQueue> | null;
    if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.tracks)) {
      return emptyStoredQueue();
    }

    const tracks = parsed.tracks.filter(isTrackLike);
    const currentTrack = isTrackLike(parsed.currentTrack) ? parsed.currentTrack : null;

    let index =
      typeof parsed.index === 'number' && Number.isFinite(parsed.index)
        ? Math.floor(parsed.index)
        : 0;

    // Prefer the position of the restored track: it is the authoritative anchor
    // and survives entries being dropped by the filter above.
    if (currentTrack) {
      const found = tracks.findIndex((t) => t.id === currentTrack.id);
      if (found !== -1) index = found;
    }
    if (index < 0 || index >= tracks.length) index = 0;

    return { tracks, index, currentTrack };
  } catch {
    return emptyStoredQueue();
  }
}

/** Read the queue written by the previous session. */
export function loadStoredQueue(storage: QueueStorage | null = defaultStorage()): StoredQueue {
  if (!storage) return emptyStoredQueue();
  try {
    return parseStoredQueue(storage.getItem(QUEUE_STORAGE_KEY));
  } catch {
    return emptyStoredQueue();
  }
}

/**
 * Write the queue. Errors are not swallowed here — the caller reports them,
 * since a full or blocked storage quota is a real failure worth logging.
 */
export function saveStoredQueue(
  queue: StoredQueue,
  storage: QueueStorage | null = defaultStorage(),
): void {
  if (!storage) return;
  storage.setItem(QUEUE_STORAGE_KEY, JSON.stringify(queue));
}

/**
 * Every playlist that reaches storage is one the user created or imported, so
 * it is theirs to edit and delete. The read-only Liked / Recently Played / My
 * Top 50 views are assembled on the fly in LibraryView and are never stored.
 *
 * Normalising on read matters because LibraryView gates its delete and
 * remove-track controls on `isCustom`: playlists created before `createPlaylist`
 * started setting the flag would otherwise stay permanently undeletable.
 */
export function asUserPlaylist(playlist: Playlist): Playlist {
  return { ...playlist, isCustom: true };
}

/** A stored playlist needs an id and a track array to be usable. */
function isPlaylistLike(value: unknown): value is Playlist {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<Playlist>;
  return (
    typeof candidate.id === 'string' &&
    candidate.id.length > 0 &&
    typeof candidate.title === 'string' &&
    Array.isArray(candidate.tracks)
  );
}

/** Read stored playlists, dropping unusable entries and marking the rest editable. */
export function parseStoredPlaylists(raw: string | null): Playlist[] {
  if (!raw) return [];

  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter(isPlaylistLike)
      .map((pl) => asUserPlaylist({ ...pl, tracks: pl.tracks.filter(isTrackLike) }));
  } catch {
    return [];
  }
}

export function loadStoredPlaylists(
  storage: QueueStorage | null = defaultStorage(),
): Playlist[] {
  if (!storage) return [];
  try {
    return parseStoredPlaylists(storage.getItem(PLAYLISTS_STORAGE_KEY));
  } catch {
    return [];
  }
}
