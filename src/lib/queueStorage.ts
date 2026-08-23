import type { Playlist, SavedAlbum, SavedArtist, Track } from '../types/music';

/**
 * Storage helpers for playback queue, stored playlists, saved artists, and saved albums.
 *
 * These live outside PlayerContext so the parsing rules can be exercised
 * directly by `scripts/test-queue-storage.mjs` — the restore path is fed
 * whatever happens to be in localStorage, including values written by an older
 * build or truncated by a crash, so it is the part most worth testing.
 */

export const QUEUE_STORAGE_KEY = 'auralis_queue';
export const PLAYLISTS_STORAGE_KEY = 'auralis_playlists';
export const ARTISTS_STORAGE_KEY = 'auralis_saved_artists';
export const ALBUMS_STORAGE_KEY = 'auralis_saved_albums';

export interface StoredQueue {
  tracks: Track[];
  index: number;
  currentTrack: Track | null;
}

/** The subset of the Web Storage API used here, so a fake can be passed in tests. */
export interface QueueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem?(key: string): void;
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

/** Check if a candidate object matches SavedArtist structure */
export function isSavedArtistLike(value: unknown): value is SavedArtist {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<SavedArtist>;
  return (
    typeof candidate.id === 'string' &&
    candidate.id.length > 0 &&
    typeof candidate.name === 'string' &&
    candidate.name.length > 0
  );
}

/** Parse stored artists safely, dropping malformed records */
export function parseStoredArtists(raw: string | null): SavedArtist[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isSavedArtistLike);
  } catch {
    return [];
  }
}

export function loadStoredArtists(
  storage: QueueStorage | null = defaultStorage(),
): SavedArtist[] {
  if (!storage) return [];
  try {
    return parseStoredArtists(storage.getItem(ARTISTS_STORAGE_KEY));
  } catch {
    return [];
  }
}

export function saveStoredArtists(
  artists: SavedArtist[],
  storage: QueueStorage | null = defaultStorage(),
): void {
  if (!storage) return;
  storage.setItem(ARTISTS_STORAGE_KEY, JSON.stringify(artists));
}

/** Check if a candidate object matches SavedAlbum structure */
export function isSavedAlbumLike(value: unknown): value is SavedAlbum {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<SavedAlbum>;
  return (
    typeof candidate.id === 'string' &&
    candidate.id.length > 0 &&
    typeof candidate.title === 'string' &&
    candidate.title.length > 0
  );
}

/** Parse stored albums safely, dropping malformed records */
export function parseStoredAlbums(raw: string | null): SavedAlbum[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isSavedAlbumLike);
  } catch {
    return [];
  }
}

export function loadStoredAlbums(
  storage: QueueStorage | null = defaultStorage(),
): SavedAlbum[] {
  if (!storage) return [];
  try {
    return parseStoredAlbums(storage.getItem(ALBUMS_STORAGE_KEY));
  } catch {
    return [];
  }
}

export function saveStoredAlbums(
  albums: SavedAlbum[],
  storage: QueueStorage | null = defaultStorage(),
): void {
  if (!storage) return;
  storage.setItem(ALBUMS_STORAGE_KEY, JSON.stringify(albums));
}

/* ---------------------------------------------------------------------------
 * Playback position
 *
 * `auralis_queue` already brings the queue and the last played track back, but
 * it always restored them at 0:00, so pausing a song and closing Auralis lost
 * your place in it.
 *
 * The position lives in its own small key rather than being folded into
 * StoredQueue, for two reasons: a position write happens every few seconds
 * while playing and must not re-serialise the whole queue, and the queue's
 * schema (plus the tests pinning it) stays untouched. It is still the same
 * storage layer — same injectable QueueStorage, same "parse defensively,
 * never trust what is on disk" rules — not a second competing system.
 *
 * The record is versioned: anything written by a schema this build does not
 * understand is ignored, which is cheaper and safer than migrating a value
 * that is only ever a few seconds of convenience.
 * ------------------------------------------------------------------------- */

export const PLAYBACK_POSITION_STORAGE_KEY = 'auralis_playback_position';
export const PLAYBACK_POSITION_SCHEMA_VERSION = 1;

export interface StoredPlaybackPosition {
  /** Schema version; records from another version are discarded on read. */
  v: number;
  /** Which track the position belongs to. A position without one is meaningless. */
  trackId: string;
  /** Seconds into the track. */
  position: number;
  /** Track length as last known, used to reject positions past the end. */
  duration: number;
  /** Whether playback was running when this was written. */
  wasPlaying: boolean;
  /** Epoch ms of the write, for debugging and any future staleness policy. */
  savedAt: number;
}

/**
 * Below this many seconds there is nothing worth restoring — resuming "2
 * seconds in" is indistinguishable from the start and just costs an extra seek.
 */
export const MIN_RESUME_SECONDS = 3;

/**
 * A position this close to the end is treated as finished. Restoring it would
 * drop the user at the outro and immediately advance to the next track.
 */
export const END_OF_TRACK_GUARD_SECONDS = 5;

/** Parse a stored position record, returning null for anything unusable. */
export function parseStoredPlaybackPosition(raw: string | null): StoredPlaybackPosition | null {
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as Partial<StoredPlaybackPosition> | null;
    if (!parsed || typeof parsed !== 'object') return null;
    if (parsed.v !== PLAYBACK_POSITION_SCHEMA_VERSION) return null;
    if (typeof parsed.trackId !== 'string' || parsed.trackId.length === 0) return null;
    if (typeof parsed.position !== 'number' || !Number.isFinite(parsed.position)) return null;
    if (parsed.position < 0) return null;

    const duration =
      typeof parsed.duration === 'number' && Number.isFinite(parsed.duration) && parsed.duration > 0
        ? parsed.duration
        : 0;

    return {
      v: PLAYBACK_POSITION_SCHEMA_VERSION,
      trackId: parsed.trackId,
      position: parsed.position,
      duration,
      wasPlaying: parsed.wasPlaying === true,
      savedAt:
        typeof parsed.savedAt === 'number' && Number.isFinite(parsed.savedAt) ? parsed.savedAt : 0,
    };
  } catch {
    return null;
  }
}

/** Read the position written by the previous session. */
export function loadStoredPlaybackPosition(
  storage: QueueStorage | null = defaultStorage(),
): StoredPlaybackPosition | null {
  if (!storage) return null;
  try {
    return parseStoredPlaybackPosition(storage.getItem(PLAYBACK_POSITION_STORAGE_KEY));
  } catch {
    return null;
  }
}

/**
 * Write the position. Callers pass a partial record; version and timestamp are
 * filled in here so there is exactly one place that decides the schema.
 * Failures are swallowed: losing a resume point is not worth a visible error,
 * and this runs on paths as sensitive as page unload.
 */
export function saveStoredPlaybackPosition(
  entry: {
    trackId: string;
    position: number;
    duration: number;
    wasPlaying: boolean;
    savedAt: number;
  },
  storage: QueueStorage | null = defaultStorage(),
): void {
  if (!storage) return;
  if (!entry.trackId || !Number.isFinite(entry.position)) return;

  const record: StoredPlaybackPosition = {
    v: PLAYBACK_POSITION_SCHEMA_VERSION,
    trackId: entry.trackId,
    position: Math.max(0, entry.position),
    duration: Number.isFinite(entry.duration) && entry.duration > 0 ? entry.duration : 0,
    wasPlaying: entry.wasPlaying === true,
    savedAt: entry.savedAt,
  };

  try {
    storage.setItem(PLAYBACK_POSITION_STORAGE_KEY, JSON.stringify(record));
  } catch {
    // Quota exceeded or storage blocked; the next write will try again.
  }
}

/** Forget the stored position. */
export function clearStoredPlaybackPosition(
  storage: QueueStorage | null = defaultStorage(),
): void {
  if (!storage) return;
  try {
    if (typeof storage.removeItem === 'function') {
      storage.removeItem(PLAYBACK_POSITION_STORAGE_KEY);
    } else {
      storage.setItem(PLAYBACK_POSITION_STORAGE_KEY, '');
    }
  } catch {
    // Nothing useful to do; a stale record is rejected on read anyway.
  }
}

/**
 * Decide where a restored track should actually start.
 *
 * Everything that would make a seek wrong or pointless collapses to 0, so the
 * caller can use the result unconditionally: no saved record, a record for a
 * different track (the user played something else last), a position inside the
 * opening seconds, a position past the end of a shorter-than-expected track, or
 * a position in the final seconds of it.
 *
 * `trackDuration` is the duration the app knows now; the stored duration is
 * used as a fallback because a restored track may not have one yet.
 */
export function resumePositionFor(
  saved: StoredPlaybackPosition | null,
  trackId: string | null | undefined,
  trackDuration: number = 0,
): number {
  if (!saved || !trackId || saved.trackId !== trackId) return 0;
  if (!Number.isFinite(saved.position) || saved.position < MIN_RESUME_SECONDS) return 0;

  const duration =
    Number.isFinite(trackDuration) && trackDuration > 0 ? trackDuration : saved.duration;
  if (duration > 0 && saved.position > duration - END_OF_TRACK_GUARD_SECONDS) return 0;

  return saved.position;
}

