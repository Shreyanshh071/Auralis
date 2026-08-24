import React, { createContext, useContext, useState, useEffect, useRef, useCallback, useMemo } from 'react';
import type { Track, LyricsData, RepeatMode, Playlist, PlayerSettings, SavedArtist, SavedAlbum, Artist, PlaylistResult, ThemeMode } from '../types/music';
import { searchYouTube } from '../services/youtube';
import { fetchLyrics } from '../services/lyrics';
import { getDominantColor } from '../services/colorExtractor';
import { getAlbumArtwork } from '../services/artwork';
import { useAuth } from './AuthContext';
import { globalPlaybackClock } from '../lib/playbackClock';
import { findActiveLyricIndex } from '../lib/lyricsEngine';
import {
  asUserPlaylist,
  loadStoredPlaylists,
  loadStoredQueue,
  saveStoredQueue,
  loadStoredArtists,
  saveStoredArtists,
  loadStoredAlbums,
  saveStoredAlbums,
  loadStoredPlaybackPosition,
  saveStoredPlaybackPosition,
  resumePositionFor,
} from '../lib/queueStorage';
import type { StoredQueue, StoredPlaybackPosition } from '../lib/queueStorage';
import { moveItem, removeAt, reorderQueue as reorderQueueTracks } from '../lib/queueOps';
import { applyMaterialPalette, FALLBACK_SEED } from '../lib/materialPalette';
import {
  loadHistory,
  saveHistory,
  addToHistory,
  removeFromHistory as removeHistoryEntry,
  historyTracks,
} from '../lib/historyStorage';
import type { HistoryEntry } from '../lib/historyStorage';

/**
 * How often the playback position is checkpointed while a track is playing.
 *
 * Deliberately coarse. The position only needs to be good enough to drop the
 * user back roughly where they were, and a write every few seconds costs
 * nothing — whereas writing on every time update (10x a second) would hammer
 * localStorage synchronously on the main thread for no benefit. Events that
 * genuinely matter (pause, backgrounding, teardown) bypass this and write
 * immediately.
 */
const POSITION_WRITE_INTERVAL_MS = 5000;

// ---------------------------------------------------------------------------
// Play Count Tracking
// ---------------------------------------------------------------------------
interface PlayCountEntry {
  count: number;
  lastPlayed: number; // epoch ms
  track: Track;
}

type PlayCountMap = Record<string, PlayCountEntry>;

function loadPlayCounts(): PlayCountMap {
  try {
    const raw = localStorage.getItem('auralis_playcounts');
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

function savePlayCounts(map: PlayCountMap) {
  localStorage.setItem('auralis_playcounts', JSON.stringify(map));
}

// ---------------------------------------------------------------------------
// Playback speed
//
// The only speeds offered are ones the YouTube IFrame player is guaranteed to
// support (its getAvailablePlaybackRates is a superset of these), so the control
// can never desync from real playback. Changing the rate on the cross-origin
// IFrame also shifts pitch — that is an accepted limitation, documented in
// docs/parity-audit.md; independent pitch shift needs a Web Audio graph we do
// not have.
// ---------------------------------------------------------------------------
export const PLAYBACK_RATES: number[] = [0.5, 0.75, 1, 1.25, 1.5, 2];

function loadPlaybackRate(): number {
  try {
    const saved = Number(localStorage.getItem('auralis_playback_rate'));
    if (PLAYBACK_RATES.includes(saved)) return saved;
  } catch {}
  return 1;
}

function recordPlay(map: PlayCountMap, track: Track): PlayCountMap {
  const existing = map[track.id];
  return {
    ...map,
    [track.id]: {
      count: (existing?.count || 0) + 1,
      lastPlayed: Date.now(),
      track: { ...track },
    },
  };
}

/** Compute top tracks by play count with recency weighting (last 30 days) */
function computeTopTracks(map: PlayCountMap, limit: number): Track[] {
  const now = Date.now();
  const thirtyDays = 30 * 24 * 60 * 60 * 1000;

  return Object.values(map)
    .filter((entry) => entry.track && entry.track.id)
    .map((entry) => {
      const age = now - entry.lastPlayed;
      const recencyBoost = age < thirtyDays ? 1 + (1 - age / thirtyDays) : 0.5;
      return { ...entry, score: entry.count * recencyBoost };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map((e) => e.track);
}

/** Compute top artists by aggregated play count */
function computeTopArtists(map: PlayCountMap, limit: number): { name: string; image: string; playCount: number }[] {
  const artistMap: Record<string, { name: string; image: string; totalCount: number; lastPlayed: number }> = {};

  Object.values(map).forEach((entry) => {
    if (!entry.track || !entry.track.artist) return;
    const primaryArtist = entry.track.artist.split(',')[0].split('feat')[0].trim();
    const key = primaryArtist.toLowerCase();

    if (!artistMap[key]) {
      artistMap[key] = { name: primaryArtist, image: entry.track.thumbnail, totalCount: 0, lastPlayed: 0 };
    }
    artistMap[key].totalCount += entry.count;
    if (entry.lastPlayed > artistMap[key].lastPlayed) {
      artistMap[key].lastPlayed = entry.lastPlayed;
      artistMap[key].image = entry.track.thumbnail;
    }
  });

  return Object.values(artistMap)
    .sort((a, b) => b.totalCount - a.totalCount)
    .slice(0, limit)
    .map((a) => ({ name: a.name, image: a.image, playCount: a.totalCount }));
}

// ---------------------------------------------------------------------------
// Silent keepalive audio ("background audio anchor")
//
// Builds a short, valid, zero-amplitude WAV as an object URL. Played on loop, it
// keeps the WebView page's audio session — and with it the OS MediaSession /
// lock-screen binding — alive while music is playing, so the controls do not
// detach when Android backgrounds the app. It emits no sound: the audible audio
// is always the YouTube IFrame. Returns null where the APIs are unavailable
// (SSR / very old WebView), in which case the anchor is simply skipped.
// ---------------------------------------------------------------------------
function createSilentLoopUrl(): string | null {
  try {
    if (typeof Blob === 'undefined' || typeof URL === 'undefined' || !URL.createObjectURL) {
      return null;
    }
    const sampleRate = 8000;
    const seconds = 0.5;
    const numSamples = sampleRate * seconds;
    const bytesPerSample = 2; // 16-bit PCM, mono
    const dataSize = numSamples * bytesPerSample;
    const buffer = new ArrayBuffer(44 + dataSize);
    const view = new DataView(buffer);
    const writeStr = (offset: number, str: string) => {
      for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
    };
    writeStr(0, 'RIFF');
    view.setUint32(4, 36 + dataSize, true);
    writeStr(8, 'WAVE');
    writeStr(12, 'fmt ');
    view.setUint32(16, 16, true); // PCM chunk size
    view.setUint16(20, 1, true); // PCM format
    view.setUint16(22, 1, true); // mono
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * bytesPerSample, true); // byte rate
    view.setUint16(32, bytesPerSample, true); // block align
    view.setUint16(34, 16, true); // bits per sample
    writeStr(36, 'data');
    view.setUint32(40, dataSize, true);
    // Sample data is left as zeros — i.e. pure silence.
    return URL.createObjectURL(new Blob([buffer], { type: 'audio/wav' }));
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Queue persistence
//
// The queue used to live only in memory, so closing the app or reloading the
// page discarded it silently. It is now written to localStorage alongside the
// other auralis_* keys and restored on start.
//
// Restoring is deliberately passive: the tracks and the last played track come
// back, but nothing is handed to the YouTube player and nothing plays until the
// user presses play. The current track is restored too, because without it the
// mini player stays hidden and a restored queue would be unreachable from the
// UI, which would make the persistence pointless.
// ---------------------------------------------------------------------------
// The parsing rules live in src/lib/queueStorage.ts so they can be tested
// directly against real and corrupt stored values.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Toast System
// ---------------------------------------------------------------------------
export interface ToastMessage {
  id: string;
  text: string;
  type: 'success' | 'info' | 'error';
}

// ---------------------------------------------------------------------------
// Context Type
// ---------------------------------------------------------------------------
interface PlayerContextType {
  // Playback state
  currentTrack: Track | null;
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  isMuted: boolean;
  playbackRate: number;
  repeatMode: RepeatMode;
  isShuffle: boolean;
  isLoadingAudio: boolean;

  // Queue & History
  queue: Track[];
  history: Track[];
  clearHistory: () => void;
  removeFromHistory: (trackId: string) => void;
  queueIndex: number;

  // Lyrics
  lyrics: LyricsData | null;
  isLoadingLyrics: boolean;
  activeLyricIndex: number;
  setManualLyricsOffset: (offset: number) => void;
  lyricsOffset: number;

  // Visuals & Themes
  dominantColor: string;
  isNowPlayingOpen: boolean;
  activeModalTab: 'player' | 'lyrics' | 'queue' | 'visualizer' | 'info';
  setIsNowPlayingOpen: (open: boolean) => void;
  setActiveModalTab: (tab: 'player' | 'lyrics' | 'queue' | 'visualizer' | 'info') => void;

  // Playlists & Favorites
  favorites: Track[];
  toggleFavorite: (track: Track) => void;
  isFavorite: (trackId: string) => boolean;
  playlists: Playlist[];
  /**
   * Creates a playlist and returns it, so a caller that needs to put a track in
   * a brand-new playlist can do both in one step. Going through `addToPlaylist`
   * straight after would not work: that call reads the playlists it can see in
   * the current render, which does not yet include the new one.
   */
  createPlaylist: (title: string, description?: string, initialTracks?: Track[]) => Playlist;
  addToPlaylist: (playlistId: string, track: Track) => void;
  removeFromPlaylist: (playlistId: string, trackId: string) => void;
  reorderPlaylist: (playlistId: string, from: number, to: number) => void;
  importPlaylistToState: (playlist: Playlist) => void;
  deletePlaylist: (playlistId: string) => void;

  // Saved Artists & Albums
  savedArtists: SavedArtist[];
  saveArtist: (artist: Artist | SavedArtist) => void;
  removeArtist: (artistId: string) => void;
  isArtistSaved: (artistId: string) => boolean;
  savedAlbums: SavedAlbum[];
  saveAlbum: (album: PlaylistResult | Playlist | SavedAlbum) => void;
  removeAlbum: (albumId: string) => void;
  isAlbumSaved: (albumId: string) => boolean;

  // Play Count Analytics
  getTopTracks: (limit: number) => Track[];
  getTopArtists: (limit: number) => { name: string; image: string; playCount: number }[];
  playCounts: PlayCountMap;

  // Controls
  playTrack: (track: Track, newQueue?: Track[]) => void;
  togglePlay: () => void;
  pause: () => void;
  resume: () => void;
  seekTo: (seconds: number) => void;
  nextTrack: () => void;
  prevTrack: () => void;
  setVolume: (vol: number) => void;
  toggleMute: () => void;
  setPlaybackRate: (rate: number) => void;
  toggleRepeat: () => void;
  toggleShuffle: () => void;
  addToQueue: (track: Track) => void;
  removeFromQueue: (index: number) => void;
  reorderQueue: (from: number, to: number) => void;
  clearQueue: () => void;

  // Sleep Timer
  sleepTimerRemaining: number | null;
  setSleepTimer: (minutes: number | null) => void;

  // Settings & Theme
  settings: PlayerSettings;
  updateSettings: (newSettings: Partial<PlayerSettings>) => void;
  theme: ThemeMode;
  effectiveTheme: 'dark' | 'light';
  setTheme: (theme: ThemeMode) => void;

  // Toast
  toasts: ToastMessage[];
  showToast: (text: string, type?: 'success' | 'info' | 'error') => void;
}

const PlayerContext = createContext<PlayerContextType | null>(null);

declare global {
  interface Window {
    YT: any;
    onYouTubeIframeAPIReady: () => void;
  }
}

export const PlayerProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, fetchCloudData, saveFavoritesToCloud, savePlaylistsToCloud } = useAuth();

  // ---- Restored queue (read once, before any state that depends on it) ----
  const [restoredQueue] = useState<StoredQueue>(loadStoredQueue);
  // ---- Where the last session left off in that track, if anywhere useful ----
  const [restoredPosition] = useState<StoredPlaybackPosition | null>(loadStoredPlaybackPosition);
  const [initialResumeSeconds] = useState<number>(() =>
    resumePositionFor(
      restoredPosition,
      restoredQueue.currentTrack?.id,
      restoredQueue.currentTrack?.duration ?? 0,
    ),
  );

  // ---- No pre-seeded track or queue: only what the last session left ----
  const [currentTrack, setCurrentTrack] = useState<Track | null>(restoredQueue.currentTrack);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  // Restoring stays passive — nothing plays until the user presses play — but
  // the scrubber and the lyrics both start at the point they were left, so
  // reopening Auralis shows 1:13 rather than 0:00.
  const [currentTime, setCurrentTime] = useState<number>(initialResumeSeconds);
  // Seeded from the restored track so the scrubber shows its real length instead
  // of 0:00 / 0:00. The player overwrites this with its own duration on play.
  const [duration, setDuration] = useState<number>(
    restoredQueue.currentTrack?.duration ?? restoredPosition?.duration ?? 0,
  );
  const [volume, setVolumeState] = useState<number>(() => {
    const saved = localStorage.getItem('auralis_volume');
    return saved ? Number(saved) : 90;
  });
  const [isMuted, setIsMuted] = useState<boolean>(false);
  const [playbackRate, setPlaybackRateState] = useState<number>(loadPlaybackRate);
  const [repeatMode, setRepeatMode] = useState<RepeatMode>('off');
  const [isShuffle, setIsShuffle] = useState<boolean>(false);
  const [isLoadingAudio, setIsLoadingAudio] = useState<boolean>(false);

  // ---- Queue (restored from the previous session, paused) ----
  const [queue, setQueue] = useState<Track[]>(restoredQueue.tracks);
  const [queueIndex, setQueueIndex] = useState<number>(restoredQueue.index);

  // Listening history — the last HISTORY_LIMIT (100) tracks played, each stamped
  // with when it was added. It is held as rich entries so a timestamp is
  // available, but the public `history` below is projected down to a plain
  // Track[] because every consumer (LibraryView's Recently Played, HomeView's
  // recommendations) works on tracks, not entries.
  const [historyEntries, setHistoryEntries] = useState<HistoryEntry[]>(() => loadHistory());
  const history = useMemo(() => historyTracks(historyEntries), [historyEntries]);

  // Lyrics
  const [lyrics, setLyrics] = useState<LyricsData | null>(null);
  const [isLoadingLyrics, setIsLoadingLyrics] = useState<boolean>(false);
  const [activeLyricIndex, setActiveLyricIndex] = useState<number>(-1);
  const [lyricsOffset, setLyricsOffset] = useState<number>(0);

  // Dynamic Theme. Seeded with the Material 3 fallback source colour, which is
  // Auralis' own olive — so the very first paint already has a real accent and
  // never flashes a stand-in colour before artwork is sampled.
  const [dominantColor, setDominantColor] = useState<string>(FALLBACK_SEED);
  const [isNowPlayingOpen, setIsNowPlayingOpen] = useState<boolean>(false);
  const [activeModalTab, setActiveModalTab] = useState<'player' | 'lyrics' | 'queue' | 'visualizer' | 'info'>('player');

  // ---- Favorites: Start empty if no saved data ----
  const [favorites, setFavorites] = useState<Track[]>(() => {
    try {
      const saved = localStorage.getItem('auralis_favorites');
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });

  // ---- Playlists: Only user-created and imported ones, no defaults ----
  const [playlists, setPlaylists] = useState<Playlist[]>(() => loadStoredPlaylists());

  // ---- Saved Artists & Albums ----
  const [savedArtists, setSavedArtists] = useState<SavedArtist[]>(() => loadStoredArtists());
  const [savedAlbums, setSavedAlbums] = useState<SavedAlbum[]>(() => loadStoredAlbums());

  useEffect(() => {
    saveStoredArtists(savedArtists);
  }, [savedArtists]);

  useEffect(() => {
    saveStoredAlbums(savedAlbums);
  }, [savedAlbums]);

  // ---- Play Count Analytics ----
  const [playCounts, setPlayCounts] = useState<PlayCountMap>(loadPlayCounts);

  // Sleep Timer
  //
  // The source of truth is an ABSOLUTE deadline (epoch ms), not a decrementing
  // counter. A per-second `setInterval` is throttled or frozen while a mobile
  // WebView is backgrounded, so counting ticks drifts badly and can stall; and a
  // plain counter is lost on reload. Storing the deadline fixes both: whenever the
  // tick does run (or the tab becomes visible), it compares against the real clock,
  // so it fires on time after being backgrounded and survives a reload.
  const [sleepDeadline, setSleepDeadline] = useState<number | null>(() => {
    try {
      const raw = localStorage.getItem('auralis_sleep_deadline');
      if (!raw) return null;
      const dl = Number(raw);
      // A deadline already in the past is meaningless after a reload — nothing is
      // playing yet — so drop it instead of firing a pause the user never set up.
      if (!Number.isFinite(dl) || dl <= Date.now()) return null;
      return dl;
    } catch {
      return null;
    }
  });
  const [sleepTimerRemaining, setSleepTimerRemaining] = useState<number | null>(
    () => (sleepDeadline ? Math.max(0, Math.ceil((sleepDeadline - Date.now()) / 1000)) : null)
  );

  // Toast notifications
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  // Settings
  const [settings, setSettings] = useState<PlayerSettings>(() => {
    let initialTheme: ThemeMode = 'system';
    try {
      const savedTheme = localStorage.getItem('auralis_theme');
      if (savedTheme) initialTheme = JSON.parse(savedTheme);
    } catch {}

    const defaultSettings: PlayerSettings = {
      volume: 90,
      isMuted: false,
      theme: initialTheme,
      lyricsFontSize: 'medium',
      lyricsMode: 'spicy',
      lyricsAlignment: 'left',
      lyricsDepthBlur: true,
    };
    try {
      const saved = localStorage.getItem('auralis_settings');
      return saved ? { ...defaultSettings, ...JSON.parse(saved) } : defaultSettings;
    } catch {
      return defaultSettings;
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem('auralis_settings', JSON.stringify(settings));
    } catch {}
  }, [settings]);

  // Effective Theme & OS scheme detection
  const [effectiveTheme, setEffectiveTheme] = useState<'dark' | 'light'>(() => {
    if (typeof window === 'undefined') return 'dark';
    const mode = settings.theme || 'system';
    if (mode === 'dark') return 'dark';
    if (mode === 'light') return 'light';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });

  useEffect(() => {
    const applyTheme = () => {
      const mode = settings.theme || 'system';
      let resolved: 'dark' | 'light' = 'dark';
      if (mode === 'dark') {
        resolved = 'dark';
      } else if (mode === 'light') {
        resolved = 'light';
      } else {
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        resolved = prefersDark ? 'dark' : 'light';
      }

      setEffectiveTheme(resolved);
      if (resolved === 'dark') {
        document.documentElement.classList.add('dark');
        document.documentElement.classList.remove('light');
        document.documentElement.setAttribute('data-theme', 'dark');
      } else {
        document.documentElement.classList.remove('dark');
        document.documentElement.classList.add('light');
        document.documentElement.setAttribute('data-theme', 'light');
      }
      try {
        localStorage.setItem('auralis_theme', JSON.stringify(mode));
      } catch {}
    };

    applyTheme();

    if (settings.theme === 'system' && typeof window !== 'undefined' && window.matchMedia) {
      const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
      const listener = () => applyTheme();
      mediaQuery.addEventListener('change', listener);
      return () => mediaQuery.removeEventListener('change', listener);
    }
  }, [settings.theme]);

  /**
   * Publish the Material 3 accent roles for the current artwork colour.
   *
   * This is the single place the whole interface gets its accent from: the roles
   * land on <html> as custom properties, so every component that uses
   * `var(--m3-primary)` and friends follows the album cover without knowing
   * anything about it. It replaces the old arrangement where the accent was a
   * hardcoded purple/olive in ~250 places and the artwork colour only reached
   * two decorative background blobs.
   *
   * Cheap enough to run on every artwork or theme change: the palette is pure
   * arithmetic on one colour, the extractor caches per image URL, and the writes
   * are 17 custom properties that the compositor picks up via CSS transitions
   * rather than any per-frame JavaScript.
   */
  useEffect(() => {
    if (typeof document === 'undefined') return;
    applyMaterialPalette(document.documentElement, dominantColor, effectiveTheme);
  }, [dominantColor, effectiveTheme]);

  const playerRef = useRef<any>(null);
  const timeUpdateInterval = useRef<any>(null);
  const isPlayerReadyRef = useRef<boolean>(false);
  const pendingTrackRef = useRef<Track | null>(null);
  // Offset the pending track should start at, for the case where the user hits
  // play on a restored track before the IFrame API has finished loading.
  const pendingStartRef = useRef<number>(0);
  // Latest chosen playback speed, read inside the once-mounted player callbacks
  // (which close over stale state) so the rate can be re-applied on every load.
  const playbackRateRef = useRef<number>(playbackRate);
  playbackRateRef.current = playbackRate;
  // Mirror refs for track / repeat / shuffle / queue state — kept in sync below so the
  // once-mounted onStateChange and onError handlers always read current values instead of
  // the stale snapshot captured when the player was first created.
  const currentTrackRef = useRef<Track | null>(currentTrack);
  currentTrackRef.current = currentTrack;
  const repeatModeRef = useRef<RepeatMode>('off');
  repeatModeRef.current = repeatMode;
  const isShuffleRef = useRef<boolean>(false);
  isShuffleRef.current = isShuffle;
  const queueRef = useRef<Track[]>([]);
  queueRef.current = queue;
  const queueIndexRef = useRef<number>(0);
  queueIndexRef.current = queueIndex;
  const handleTrackEndedRef = useRef<() => void>(() => {});
  // Video id currently loaded into the YouTube player, or null if it has never
  // been given one. A restored session has a currentTrack but no loaded video,
  // and play/seek must not pretend to work in that state.
  const loadedVideoIdRef = useRef<string | null>(null);
  // The restored offset, held until the track it belongs to is actually handed
  // to the player, then cleared. Consumed once so that later cold starts of a
  // *different* track can never inherit a stale position.
  const resumeSecondsRef = useRef<number>(initialResumeSeconds);
  // Assigned once `showToast` is defined below, so effects declared earlier can
  // notify the user without depending on declaration order.
  const showToastRef = useRef<
    ((text: string, type?: 'success' | 'info' | 'error') => void) | null
  >(null);

  // ---- Cloud hydration gate ----
  //
  // Holds the uid whose cloud data has been successfully read and merged into
  // local state. Cloud WRITES are blocked until this matches the signed-in uid.
  //
  // Without this gate, the persist effects below fire the moment `user` changes —
  // before the read completes — so a freshly signed-in device with an empty local
  // library would write `favorites: []` and wipe the existing cloud data.
  //
  // The gate also stays shut if the read fails, because in that case local state
  // has not been reconciled and writing it back could destroy cloud data.
  const [hydratedUid, setHydratedUid] = useState<string | null>(null);

  // ---- Sync from Firestore on user login ----
  useEffect(() => {
    if (!user) {
      setHydratedUid(null);
      return;
    }

    let isCancelled = false;
    // Close the gate for this uid until its data has been read.
    setHydratedUid(null);

    (async () => {
      try {
        const cloudData = await fetchCloudData();
        if (isCancelled) return;

        // A null result means the document does not exist yet — that is a valid,
        // fully-reconciled state, so the gate may open and seed the cloud.
        if (cloudData?.favorites && Array.isArray(cloudData.favorites)) {
          setFavorites((localFavs) => {
            const map = new Map<string, Track>();
            cloudData.favorites?.forEach((t) => map.set(t.id, t));
            // Local entries win on id collision, but nothing is ever dropped.
            localFavs.forEach((t) => map.set(t.id, t));
            return Array.from(map.values());
          });
        }

        if (cloudData?.playlists && Array.isArray(cloudData.playlists)) {
          setPlaylists((localPls) => {
            const map = new Map<string, Playlist>();
            cloudData.playlists?.forEach((p) => map.set(p.id, asUserPlaylist(p)));
            localPls.forEach((p) => map.set(p.id, p));
            return Array.from(map.values());
          });
        }

        if (!isCancelled) setHydratedUid(user.uid);
      } catch (err) {
        // Read failed. Leave the gate shut so local data cannot overwrite the
        // cloud, and tell the user instead of failing silently.
        if (isCancelled) return;
        console.error('Cloud hydration failed; cloud writes stay disabled.', err);
        showToastRef.current?.(
          'Could not load your cloud library. Cloud saving is paused to protect it.',
          'error'
        );
      }
    })();

    return () => {
      isCancelled = true;
    };
  }, [user]);

  // ---- Persist to localStorage (always) ----
  useEffect(() => {
    localStorage.setItem('auralis_favorites', JSON.stringify(favorites));
  }, [favorites]);

  useEffect(() => {
    localStorage.setItem('auralis_playlists', JSON.stringify(playlists));
  }, [playlists]);

  // ---- Persist to Firestore (only after successful hydration) ----
  useEffect(() => {
    if (!user || hydratedUid !== user.uid) return;
    saveFavoritesToCloud(favorites);
  }, [favorites, user, hydratedUid]);

  useEffect(() => {
    if (!user || hydratedUid !== user.uid) return;
    savePlaylistsToCloud(playlists);
  }, [playlists, user, hydratedUid]);

  useEffect(() => {
    localStorage.setItem('auralis_volume', volume.toString());
  }, [volume]);

  useEffect(() => {
    localStorage.setItem('auralis_playback_rate', String(playbackRate));
  }, [playbackRate]);

  // Persist the sleep deadline so a reload can resume the same countdown.
  useEffect(() => {
    if (sleepDeadline === null) localStorage.removeItem('auralis_sleep_deadline');
    else localStorage.setItem('auralis_sleep_deadline', String(sleepDeadline));
  }, [sleepDeadline]);

  useEffect(() => {
    localStorage.setItem('auralis_settings', JSON.stringify(settings));
  }, [settings]);

  useEffect(() => {
    saveHistory(historyEntries);
  }, [historyEntries]);

  // ---- Persist the queue so it survives a reload ----
  useEffect(() => {
    const payload: StoredQueue = { tracks: queue, index: queueIndex, currentTrack };
    try {
      saveStoredQueue(payload);
    } catch (err) {
      // A very large imported playlist can exceed the storage quota. Playback
      // continues, but the failure is logged rather than swallowed: otherwise
      // the queue would silently stop persisting with no way to tell.
      console.warn('Could not persist the playback queue.', err);
    }
  }, [queue, queueIndex, currentTrack]);

  useEffect(() => {
    savePlayCounts(playCounts);
  }, [playCounts]);

  // ---- Toast helper ----
  const showToast = useCallback((text: string, type: 'success' | 'info' | 'error' = 'success') => {
    const id = `toast-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    // If a toast with identical text already exists, replace it rather than stacking duplicate banners
    setToasts((prev) => [...prev.filter((t) => t.text !== text), { id, text, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 2800);
  }, []);

  // Keep the ref in sync so earlier effects can call it.
  showToastRef.current = showToast;

  // ---- Play Count Accessors ----
  const getTopTracks = useCallback(
    (limit: number) => computeTopTracks(playCounts, limit),
    [playCounts]
  );

  const getTopArtists = useCallback(
    (limit: number) => computeTopArtists(playCounts, limit),
    [playCounts]
  );

  // ---- Listening history mutations ----
  const clearHistory = useCallback(() => setHistoryEntries([]), []);
  const removeFromHistory = useCallback(
    (trackId: string) => setHistoryEntries((entries) => removeHistoryEntry(entries, trackId)),
    []
  );

  // ---- YouTube IFrame Player ----
  useEffect(() => {
    const initPlayer = () => {
      if (!window.YT || !window.YT.Player) return;

      if (playerRef.current) {
        try {
          playerRef.current.destroy();
        } catch {}
      }

      playerRef.current = new window.YT.Player('yt-hidden-player', {
        height: '240',
        width: '320',
        playerVars: {
          autoplay: 0,
          controls: 0,
          disablekb: 1,
          fs: 0,
          modestbranding: 1,
          rel: 0,
          playsinline: 1,
          enablejsapi: 1,
          iv_load_policy: 3,
        },
        events: {
          onReady: (event: any) => {
            isPlayerReadyRef.current = true;
            event.target.setVolume(volume);
            if (isMuted) event.target.mute();

            if (pendingTrackRef.current) {
              const tr = pendingTrackRef.current;
              const startSeconds = pendingStartRef.current;
              pendingTrackRef.current = null;
              pendingStartRef.current = 0;
              event.target.loadVideoById({ videoId: tr.id, startSeconds });
              event.target.playVideo();
              loadedVideoIdRef.current = tr.id;
              setIsPlaying(true);
            }
          },
          onStateChange: (event: any) => {
            if (event.data === 1) {
              setIsPlaying(true);
              setIsLoadingAudio(false);
              const dur = event.target.getDuration();
              if (dur && dur > 0) setDuration(dur);
              // YouTube resets the rate to 1 whenever a new video loads, so
              // re-apply the user's chosen speed each time playback begins.
              if (typeof event.target.setPlaybackRate === 'function') {
                try { event.target.setPlaybackRate(playbackRateRef.current); } catch {}
              }
              // Sync the interpolating clock with the authoritative player time.
              try {
                const t = event.target.getCurrentTime();
                globalPlaybackClock.updateAnchor(
                  typeof t === 'number' ? t : 0,
                  true,
                  playbackRateRef.current,
                  dur && dur > 0 ? dur : 0
                );
              } catch {}
            } else if (event.data === 2) {
              setIsPlaying(false);
              setIsLoadingAudio(false);
              globalPlaybackClock.setPlaying(false);
            } else if (event.data === 3) {
              setIsLoadingAudio(true);
            } else if (event.data === 0) {
              handleTrackEndedRef.current();
            }
          },
          onError: async (e: any) => {
            console.warn('YouTube Player Error:', e);
            setIsLoadingAudio(false);
            const cur = currentTrackRef.current;
            if (cur) {
              try {
                const alt = await searchYouTube(`${cur.title} ${cur.artist} audio`);
                if (alt.length > 1 && alt[1].id !== cur.id) {
                  playTrack(alt[1]);
                }
              } catch {}
            }
          },
        },
      });
    };

    if (!window.YT || !window.YT.Player) {
      const tag = document.createElement('script');
      tag.src = 'https://www.youtube.com/iframe_api';
      const firstScriptTag = document.getElementsByTagName('script')[0];
      firstScriptTag?.parentNode?.insertBefore(tag, firstScriptTag);
      window.onYouTubeIframeAPIReady = initPlayer;
    } else {
      initPlayer();
    }

    return () => {
      if (timeUpdateInterval.current) clearInterval(timeUpdateInterval.current);
    };
  }, []);

  /* -------------------------------------------------------------------------
   * Playback position persistence
   *
   * Closing Auralis mid-song used to lose your place: the queue came back but
   * always at 0:00. The rules here are deliberately boring — checkpoint every
   * few seconds while playing, write immediately on the events that mean "this
   * is the position that matters" (pause, tab hidden, page going away), and
   * never write per animation frame.
   *
   * Nothing on this path touches the PlaybackClock or the lyrics pipeline: it
   * only *reads* the values the player already publishes.
   * ----------------------------------------------------------------------- */

  // Latest playback values, read by the write helper. Refs rather than
  // dependencies so the unload listeners can be registered exactly once and
  // still see current values instead of a stale closure.
  const positionSnapshotRef = useRef({
    trackId: null as string | null,
    position: 0,
    duration: 0,
    isPlaying: false,
  });
  positionSnapshotRef.current = {
    trackId: currentTrack?.id ?? null,
    position: currentTime,
    duration,
    isPlaying,
  };

  const lastPositionWriteRef = useRef<number>(0);

  /**
   * Write the current position. `force` bypasses the throttle and is used for
   * the events that are the whole point of this feature.
   */
  const persistPlaybackPosition = useCallback((force: boolean = false) => {
    const snapshot = positionSnapshotRef.current;
    if (!snapshot.trackId) return;

    const now = Date.now();
    if (!force && now - lastPositionWriteRef.current < POSITION_WRITE_INTERVAL_MS) return;
    lastPositionWriteRef.current = now;

    saveStoredPlaybackPosition({
      trackId: snapshot.trackId,
      position: snapshot.position,
      duration: snapshot.duration,
      wasPlaying: snapshot.isPlaying,
      savedAt: now,
    });
  }, []);

  // Pausing is the strongest signal that a position should be remembered, so it
  // is written straight away rather than waiting for the next checkpoint.
  // Starting playback writes too, so `wasPlaying` reflects reality if the app is
  // killed without any further event, and a track change writes the new track's
  // position so a stale record cannot outlive the song it belonged to.
  useEffect(() => {
    if (!currentTrack) return;
    persistPlaybackPosition(true);
  }, [isPlaying, currentTrack, persistPlaybackPosition]);

  // Anchor the clock at the restored offset so a restored session opens with
  // the lyrics on the right line, not at the first one. Anchoring while paused
  // only moves the clock's stored position — `getCurrentInterpolatedTime()`
  // returns the anchor verbatim until playback starts — so this cannot make the
  // lyrics drift.
  useEffect(() => {
    if (initialResumeSeconds <= 0) return;
    globalPlaybackClock.updateAnchor(
      initialResumeSeconds,
      false,
      1,
      restoredQueue.currentTrack?.duration ?? restoredPosition?.duration ?? 0,
    );
    // Restore-time only; every value read here is a first-render constant.
  }, [initialResumeSeconds, restoredQueue, restoredPosition]);

  // Lifecycle writes. `visibilitychange` covers backgrounding on Android (the
  // Capacitor WebView reports hidden when the activity stops) as well as
  // switching tabs on the web; `pagehide` covers navigation and app teardown.
  // `beforeunload` is the belt-and-braces case for desktop browsers that skip
  // `pagehide` on some close paths. All three are idempotent, and all three are
  // DOM events — no extra Capacitor plugin is needed for this.
  useEffect(() => {
    const writeNow = () => persistPlaybackPosition(true);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') writeNow();
    };

    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('pagehide', writeNow);
    window.addEventListener('beforeunload', writeNow);
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('pagehide', writeNow);
      window.removeEventListener('beforeunload', writeNow);
    };
  }, [persistPlaybackPosition]);

  // Track progress interval — kept at 100ms for progress bars, scrubbers, and
  // time counters. The interpolating clock is also re-anchored here to prevent
  // drift, but lyrics animations read the clock directly via rAF and do NOT
  // depend on this React state update cycle.
  useEffect(() => {
    if (isPlaying) {
      timeUpdateInterval.current = setInterval(() => {
        if (playerRef.current && typeof playerRef.current.getCurrentTime === 'function') {
          try {
            const time = playerRef.current.getCurrentTime();
            if (typeof time === 'number') {
              setCurrentTime(time);
              // Re-anchor the interpolating clock to the authoritative player
              // time on every poll so it stays within a single tick of truth.
              globalPlaybackClock.updateAnchor(time, true, playbackRateRef.current);
            }
            const dur = playerRef.current.getDuration();
            if (typeof dur === 'number' && dur > 0) setDuration(dur);
            // Checkpoint the position from the same tick the time came from, but
            // no more often than POSITION_WRITE_INTERVAL_MS.
            persistPlaybackPosition();
          } catch {}
        }
      }, 100);
    } else {
      if (timeUpdateInterval.current) clearInterval(timeUpdateInterval.current);
    }
    return () => {
      if (timeUpdateInterval.current) clearInterval(timeUpdateInterval.current);
    };
  }, [isPlaying, persistPlaybackPosition]);

  // Lyrics sync — uses the interpolating clock for smoother line transitions.
  // The effect fires on every currentTime tick (100ms) but uses the clock's
  // interpolated value for precision, and only calls setActiveLyricIndex when
  // the active line actually changes to avoid unnecessary React re-renders
  // downstream in SyncedLyrics.
  const prevLyricIndexRef = useRef(-1);
  useEffect(() => {
    if (!lyrics || lyrics.syncType === 'plain' || lyrics.lines.length === 0) {
      if (prevLyricIndexRef.current !== -1) {
        prevLyricIndexRef.current = -1;
        setActiveLyricIndex(-1);
      }
      return;
    }
    const clockTime = globalPlaybackClock.getCurrentInterpolatedTime();
    const newIdx = findActiveLyricIndex(lyrics.lines, clockTime, lyricsOffset);
    if (newIdx !== prevLyricIndexRef.current) {
      prevLyricIndexRef.current = newIdx;
      setActiveLyricIndex(newIdx);
    }
  }, [currentTime, lyrics, lyricsOffset]);

  // Ref to drop stale in-flight lyrics requests on track change
  const lyricsRequestIdRef = useRef<number>(0);

  // Load artwork, lyrics, dominant color on track change
  useEffect(() => {
    if (!currentTrack) {
      setLyrics(null);
      setIsLoadingLyrics(false);
      // Nothing playing, so drop back to the fallback accent instead of keeping
      // the last cover's colour on an empty player.
      setDominantColor(FALLBACK_SEED);
      return;
    }

    const activeTrackId = currentTrack.id;
    const reqId = ++lyricsRequestIdRef.current;

    getAlbumArtwork(currentTrack.title, currentTrack.artist, currentTrack.thumbnail).then((resolvedThumb) => {
      if (resolvedThumb && resolvedThumb !== currentTrack.thumbnail) {
        setCurrentTrack((prev) => (prev && prev.id === activeTrackId ? { ...prev, thumbnail: resolvedThumb } : prev));
        setQueue((prev) =>
          prev.map((t) => (t.id === activeTrackId ? { ...t, thumbnail: resolvedThumb } : t))
        );
      }
      getDominantColor(resolvedThumb).then((color) => {
        if (lyricsRequestIdRef.current === reqId) {
          setDominantColor(color);
        }
      });
    });

    setIsLoadingLyrics(true);
    fetchLyrics(currentTrack.title, currentTrack.artist, currentTrack.duration, currentTrack.id)
      .then((data) => {
        if (lyricsRequestIdRef.current === reqId) {
          setLyrics(data);
        }
      })
      .catch(() => {
        if (lyricsRequestIdRef.current === reqId) {
          setLyrics(null);
        }
      })
      .finally(() => {
        if (lyricsRequestIdRef.current === reqId) {
          setIsLoadingLyrics(false);
        }
      });
  }, [currentTrack?.id]);

  /* -------------------------------------------------------------------------
   * OS media controls (MediaSession) + background audio keepalive
   *
   * navigator.mediaSession is what puts the track title / artist / artwork and
   * the transport buttons on the Android lock screen and notification shade (and
   * drives the media keys on desktop web). It is a top-document API, so it works
   * even though the audio itself comes from the cross-origin YouTube IFrame.
   *
   * The action handlers delegate to the very same controls the in-app UI uses,
   * through a ref, so the handlers — registered once — always drive the current
   * player rather than a stale closure captured at mount.
   * ----------------------------------------------------------------------- */

  // Latest player controls, populated after the control functions are defined
  // (further down), so the mount-time handler registration can call through to
  // them without re-binding on every render.
  const mediaControlsRef = useRef<{
    play: () => void;
    pause: () => void;
    next: () => void;
    prev: () => void;
    seekTo: (seconds: number) => void;
    stop: () => void;
  } | null>(null);

  // Silent keepalive element. Created lazily on first play so it is tied to a
  // user gesture (autoplay policy) and never allocated for a session that never
  // plays anything.
  const audioAnchorRef = useRef<HTMLAudioElement | null>(null);

  /**
   * Publish position / duration / rate to the OS scrubber. The OS interpolates
   * position from playbackRate between calls, so this only needs to run on the
   * transitions that change the shape of playback (new track, duration known,
   * rate change, play/pause) and on explicit seeks — not on every time tick.
   * Reads live values from refs so it can be a stable callback.
   */
  const publishMediaPositionState = useCallback((positionOverride?: number) => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    if (typeof ms.setPositionState !== 'function') return;
    const snap = positionSnapshotRef.current;
    const dur = snap.duration;
    const pos = positionOverride ?? snap.position;
    try {
      if (Number.isFinite(dur) && dur > 0) {
        ms.setPositionState({
          duration: dur,
          playbackRate: playbackRateRef.current || 1,
          position: Math.min(Math.max(0, pos), dur),
        });
      } else {
        // Clears any stale state when the duration is not yet known.
        ms.setPositionState();
      }
    } catch {}
  }, []);

  // Metadata — refreshed whenever the track or its resolved (hi-res) artwork
  // changes. The artwork effect above upgrades currentTrack.thumbnail once a
  // better cover resolves, which re-fires this and hands the OS the sharp image.
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    if (!currentTrack) {
      try { ms.metadata = null; } catch {}
      return;
    }
    try {
      const art = currentTrack.thumbnail;
      const type = art && art.endsWith('.png') ? 'image/png' : 'image/jpeg';
      const artwork = art
        ? ['96x96', '128x128', '192x192', '256x256', '384x384', '512x512'].map((sizes) => ({
            src: art,
            sizes,
            type,
          }))
        : [];
      ms.metadata = new MediaMetadata({
        title: currentTrack.title || 'Unknown title',
        artist: currentTrack.artist || 'Unknown artist',
        album: currentTrack.album || '',
        artwork,
      });
    } catch {}
  }, [
    currentTrack?.id,
    currentTrack?.title,
    currentTrack?.artist,
    currentTrack?.album,
    currentTrack?.thumbnail,
  ]);

  // Transport buttons — registered once; each delegates to the live controls.
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    const set = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
      try {
        ms.setActionHandler(action, handler);
      } catch {
        // Some actions are unsupported on some platforms; ignore those.
      }
    };

    set('play', () => mediaControlsRef.current?.play());
    set('pause', () => mediaControlsRef.current?.pause());
    set('previoustrack', () => mediaControlsRef.current?.prev());
    set('nexttrack', () => mediaControlsRef.current?.next());
    set('stop', () => mediaControlsRef.current?.stop());
    set('seekto', (details) => {
      if (typeof details.seekTime === 'number') mediaControlsRef.current?.seekTo(details.seekTime);
    });
    set('seekbackward', (details) => {
      const offset = details.seekOffset || 10;
      mediaControlsRef.current?.seekTo(Math.max(0, positionSnapshotRef.current.position - offset));
    });
    set('seekforward', (details) => {
      const offset = details.seekOffset || 10;
      const snap = positionSnapshotRef.current;
      const target = snap.position + offset;
      mediaControlsRef.current?.seekTo(snap.duration > 0 ? Math.min(target, snap.duration) : target);
    });

    return () => {
      const actions: MediaSessionAction[] = [
        'play',
        'pause',
        'previoustrack',
        'nexttrack',
        'stop',
        'seekto',
        'seekbackward',
        'seekforward',
      ];
      for (const action of actions) set(action, null);
    };
  }, []);

  // Playing / paused indicator for the OS UI.
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.playbackState = isPlaying ? 'playing' : currentTrack ? 'paused' : 'none';
    } catch {}
  }, [isPlaying, currentTrack]);

  // Position state for the OS scrubber. Intentionally NOT keyed on currentTime:
  // the OS interpolates between updates from playbackRate, so re-publishing every
  // 100ms tick would be pointless churn. Seeks call publishMediaPositionState
  // directly (see seekTo) so a scrub still lands exactly.
  useEffect(() => {
    publishMediaPositionState();
  }, [currentTrack?.id, duration, playbackRate, isPlaying, publishMediaPositionState]);

  // Background audio anchor — start the silent keepalive while playing, pause it
  // otherwise. See createSilentLoopUrl: it emits no sound; it only keeps the
  // page's audio session (and the MediaSession binding) alive in the background.
  useEffect(() => {
    if (typeof document === 'undefined' || typeof Audio === 'undefined') return;
    let anchor = audioAnchorRef.current;
    if (!anchor) {
      const url = createSilentLoopUrl();
      if (!url) return;
      anchor = new Audio(url);
      anchor.loop = true;
      anchor.volume = 0.0001; // inaudible, but > 0 so it is not treated as muted
      anchor.setAttribute('playsinline', 'true');
      audioAnchorRef.current = anchor;
    }
    if (isPlaying) {
      // Rejection is fine: the autoplay policy may block it until a gesture, and
      // the anchor is a nice-to-have, not required for playback itself.
      void anchor.play().catch(() => {});
    } else {
      try { anchor.pause(); } catch {}
    }
  }, [isPlaying]);

  // Sleep timer countdown — driven by the absolute deadline, so it stays correct
  // across background throttling and reloads. It also re-checks the moment the tab
  // becomes visible again, catching the common case where the WebView froze the
  // interval while backgrounded and the deadline passed in the meantime.
  useEffect(() => {
    if (sleepDeadline === null) return;

    const check = () => {
      const remaining = Math.ceil((sleepDeadline - Date.now()) / 1000);
      if (remaining <= 0) {
        pause();
        setSleepDeadline(null);
        setSleepTimerRemaining(null);
      } else {
        setSleepTimerRemaining(remaining);
      }
    };

    check(); // sync immediately on set / restore / dependency change
    const timer = setInterval(check, 1000);
    const onVisible = () => {
      if (document.visibilityState === 'visible') check();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [sleepDeadline]);

  // Tab visibility listener — immediately re-anchors playback position and state
  // when returning to the tab after being in the background or another tab.
  useEffect(() => {
    const handleTabVisibility = () => {
      if (document.visibilityState === 'visible' && playerRef.current) {
        try {
          if (typeof playerRef.current.getCurrentTime === 'function') {
            const time = playerRef.current.getCurrentTime();
            if (typeof time === 'number' && Number.isFinite(time)) {
              setCurrentTime(time);
              globalPlaybackClock.updateAnchor(time, isPlaying, playbackRateRef.current);
            }
          }
          if (typeof playerRef.current.getPlayerState === 'function') {
            const state = playerRef.current.getPlayerState();
            if (state === 1 && !isPlaying) {
              setIsPlaying(true);
              globalPlaybackClock.setPlaying(true);
            } else if (state === 2 && isPlaying) {
              setIsPlaying(false);
              globalPlaybackClock.setPlaying(false);
            }
          }
        } catch {}
      }
    };

    document.addEventListener('visibilitychange', handleTabVisibility);
    window.addEventListener('focus', handleTabVisibility);
    return () => {
      document.removeEventListener('visibilitychange', handleTabVisibility);
      window.removeEventListener('focus', handleTabVisibility);
    };
  }, [isPlaying]);

  const handleTrackEnded = () => {
    const mode = repeatModeRef.current;
    const cur = currentTrackRef.current;

    if (mode === 'one' && cur) {
      // Loop single track: seek to beginning and continue playing
      if (playerRef.current && typeof playerRef.current.seekTo === 'function') {
        try {
          playerRef.current.seekTo(0, true);
          if (typeof playerRef.current.playVideo === 'function') {
            playerRef.current.playVideo();
          }
        } catch {
          loadAndPlay(cur, 0);
        }
      } else {
        loadAndPlay(cur, 0);
      }
      setCurrentTime(0);
      globalPlaybackClock.reset();
      globalPlaybackClock.setPlaying(true);
      setIsPlaying(true);
      if (lyrics && lyrics.syncType !== 'plain' && lyrics.lines.length > 0) {
        prevLyricIndexRef.current = 0;
        setActiveLyricIndex(0);
      }
    } else {
      const q = queueRef.current;
      const qi = queueIndexRef.current;
      if (mode === 'all' || qi < q.length - 1) {
        nextTrack();
      } else {
        setIsPlaying(false);
        globalPlaybackClock.setPlaying(false);
      }
    }
  };
  handleTrackEndedRef.current = handleTrackEnded;

  // ---- Playback Controls ----

  /**
   * Hand a track to the YouTube player and start it.
   *
   * Split out of `playTrack` so the cold-start path in `togglePlay` can reuse it
   * without re-recording history. If the iframe API has not finished loading the
   * track is parked in `pendingTrackRef` and the onReady handler picks it up.
   *
   * `startSeconds` is how a restored session resumes mid-song: it is handed
   * straight to `loadVideoById`, so the player buffers from that point rather
   * than loading at 0:00 and seeking afterwards (which is audible).
   */
  const loadAndPlay = (track: Track, startSeconds: number = 0) => {
    const startAt = Number.isFinite(startSeconds) && startSeconds > 0 ? startSeconds : 0;
    if (playerRef.current && typeof playerRef.current.loadVideoById === 'function') {
      try {
        playerRef.current.loadVideoById({ videoId: track.id, startSeconds: startAt });
        playerRef.current.playVideo();
        loadedVideoIdRef.current = track.id;
      } catch (err) {
        console.error('Error loading video:', err);
      }
    } else {
      pendingTrackRef.current = track;
      pendingStartRef.current = startAt;
    }
  };

  const playTrack = (track: Track, newQueue?: Track[]) => {
    const prev = currentTrackRef.current;
    if (prev) {
      setHistoryEntries((entries) => addToHistory(entries, prev, Date.now()));
    }

    setCurrentTrack(track);
    currentTrackRef.current = track;
    setCurrentTime(0);
    setLyricsOffset(0);
    // An explicit play starts at the beginning, so any pending resume offset from
    // the restored session is spent here rather than surfacing later.
    resumeSecondsRef.current = 0;
    globalPlaybackClock.reset();
    setIsPlaying(true);
    setIsLoadingAudio(true);

    // Record play count
    setPlayCounts((counts) => recordPlay(counts, track));

    if (newQueue && newQueue.length > 0) {
      setQueue(newQueue);
      queueRef.current = newQueue;
      const idx = newQueue.findIndex((t) => t.id === track.id);
      const chosenIdx = idx !== -1 ? idx : 0;
      setQueueIndex(chosenIdx);
      queueIndexRef.current = chosenIdx;
    } else {
      const q = queueRef.current;
      const idx = q.findIndex((t) => t.id === track.id);
      if (idx !== -1) {
        setQueueIndex(idx);
        queueIndexRef.current = idx;
      } else {
        const nextQ = [...q, track];
        setQueue(nextQ);
        queueRef.current = nextQ;
        setQueueIndex(q.length);
        queueIndexRef.current = q.length;
      }
    }

    loadAndPlay(track);
  };

  /**
   * Start the restored track — from where the last session left it, if anywhere.
   *
   * After a reload the track came back from storage but the player has never
   * been given a video, so `playVideo()` would do nothing while the UI flipped
   * to "playing". Loading it first is what makes the restored state real.
   *
   * The saved position is consumed once and only for the track it belongs to:
   * pressing play on the restored song resumes it, while picking any other song
   * starts at 0:00 as usual. `resumeSecondsRef` is cleared either way, so a
   * later replay of the same track is a fresh listen rather than a rewind.
   */
  const startFromCold = (track: Track) => {
    const startAt =
      resumeSecondsRef.current > 0 && restoredPosition?.trackId === track.id
        ? resumeSecondsRef.current
        : 0;
    resumeSecondsRef.current = 0;

    setCurrentTime(startAt);
    setIsPlaying(true);
    setIsLoadingAudio(true);
    // reset() would zero the clock; seekTo moves the anchor without touching the
    // playing flag, which is what a resume needs.
    globalPlaybackClock.reset();
    if (startAt > 0) globalPlaybackClock.seekTo(startAt);
    setPlayCounts((prev) => recordPlay(prev, track));
    loadAndPlay(track, startAt);
  };

  const togglePlay = () => {
    const cur = currentTrackRef.current;
    if (!cur) return;

    if (!isPlaying && loadedVideoIdRef.current !== cur.id) {
      startFromCold(cur);
      return;
    }

    if (!playerRef.current) {
      playTrack(cur);
      return;
    }
    try {
      if (isPlaying) {
        if (typeof playerRef.current.pauseVideo === 'function') playerRef.current.pauseVideo();
        setIsPlaying(false);
        globalPlaybackClock.setPlaying(false);
      } else {
        try {
          if (typeof playerRef.current.getPlayerState === 'function' && playerRef.current.getPlayerState() === 0) {
            playerRef.current.seekTo(0, true);
          }
        } catch {}
        if (typeof playerRef.current.playVideo === 'function') playerRef.current.playVideo();
        setIsPlaying(true);
        globalPlaybackClock.setPlaying(true);
      }
    } catch (err) {
      console.error('togglePlay error:', err);
    }
  };

  const pause = () => {
    if (playerRef.current && typeof playerRef.current.pauseVideo === 'function') {
      try { playerRef.current.pauseVideo(); } catch {}
    }
    setIsPlaying(false);
    globalPlaybackClock.setPlaying(false);
  };

  const resume = () => {
    const cur = currentTrackRef.current;
    if (!cur) return;
    // Same cold-start rule as togglePlay: with nothing loaded, reporting
    // playback would be a lie, so load the track instead.
    if (loadedVideoIdRef.current !== cur.id) {
      startFromCold(cur);
      return;
    }
    if (playerRef.current) {
      try {
        if (typeof playerRef.current.getPlayerState === 'function' && playerRef.current.getPlayerState() === 0) {
          playerRef.current.seekTo(0, true);
        }
        if (typeof playerRef.current.playVideo === 'function') {
          playerRef.current.playVideo();
        }
      } catch {}
    }
    setIsPlaying(true);
    globalPlaybackClock.setPlaying(true);
  };

  const seekTo = (seconds: number) => {
    const cur = currentTrackRef.current;
    if (!cur || loadedVideoIdRef.current !== cur.id) return;
    if (playerRef.current && typeof playerRef.current.seekTo === 'function') {
      try {
        playerRef.current.seekTo(seconds, true);
        setCurrentTime(seconds);
        globalPlaybackClock.seekTo(seconds);
        publishMediaPositionState(seconds);
        if (lyrics && lyrics.syncType !== 'plain' && lyrics.lines.length > 0) {
          const newIdx = findActiveLyricIndex(lyrics.lines, seconds, lyricsOffset);
          prevLyricIndexRef.current = newIdx;
          setActiveLyricIndex(newIdx);
        }
      } catch {}
    }
  };

  const nextTrack = () => {
    const q = queueRef.current;
    const qi = queueIndexRef.current;
    if (q.length === 0) return;
    let nextIdx = qi + 1;
    if (isShuffleRef.current) {
      if (q.length > 1) {
        do {
          nextIdx = Math.floor(Math.random() * q.length);
        } while (nextIdx === qi && q.length > 1);
      } else {
        nextIdx = 0;
      }
    } else if (nextIdx >= q.length) {
      if (repeatModeRef.current === 'all') { nextIdx = 0; } else { return; }
    }
    const nxt = q[nextIdx];
    if (nxt) {
      setQueueIndex(nextIdx);
      queueIndexRef.current = nextIdx;
      playTrack(nxt);
    }
  };

  const prevTrack = () => {
    if (currentTime > 4) { seekTo(0); return; }
    const q = queueRef.current;
    const qi = queueIndexRef.current;
    if (q.length === 0) return;
    let prevIdx = qi - 1;
    if (prevIdx < 0) {
      if (repeatModeRef.current === 'all') { prevIdx = q.length - 1; } else { seekTo(0); return; }
    }
    const prv = q[prevIdx];
    if (prv) {
      setQueueIndex(prevIdx);
      queueIndexRef.current = prevIdx;
      playTrack(prv);
    }
  };

  const setVolume = (val: number) => {
    setVolumeState(val);
    if (playerRef.current && typeof playerRef.current.setVolume === 'function') {
      try { playerRef.current.setVolume(val); } catch {}
    }
    if (val > 0 && isMuted) {
      setIsMuted(false);
      if (playerRef.current && typeof playerRef.current.unMute === 'function') {
        try { playerRef.current.unMute(); } catch {}
      }
    }
  };

  const toggleMute = () => {
    if (isMuted) {
      setIsMuted(false);
      if (playerRef.current && typeof playerRef.current.unMute === 'function') {
        try { playerRef.current.unMute(); playerRef.current.setVolume(volume); } catch {}
      }
    } else {
      setIsMuted(true);
      if (playerRef.current && typeof playerRef.current.mute === 'function') {
        try { playerRef.current.mute(); } catch {}
      }
    }
  };

  const toggleRepeat = () => {
    setRepeatMode((prev) => (prev === 'off' ? 'all' : prev === 'all' ? 'one' : 'off'));
  };

  // Change playback speed. Clamped to the supported set so the control and the
  // real player rate can never disagree; applied immediately and also re-applied
  // on each new track load by the onStateChange handler.
  const setPlaybackRate = (rate: number) => {
    const next = PLAYBACK_RATES.includes(rate) ? rate : 1;
    setPlaybackRateState(next);
    globalPlaybackClock.setPlaybackRate(next);
    if (playerRef.current && typeof playerRef.current.setPlaybackRate === 'function') {
      try { playerRef.current.setPlaybackRate(next); } catch {}
    }
  };

  const toggleShuffle = () => {
    setIsShuffle((prev) => !prev);
  };

  const isFavorite = (trackId: string) => favorites.some((t) => t.id === trackId);

  const toggleFavorite = (track: Track) => {
    const exists = favorites.some((t) => t.id === track.id);
    if (exists) {
      setFavorites((prev) => prev.filter((t) => t.id !== track.id));
      showToast('Removed from Liked Songs', 'info');
    } else {
      setFavorites((prev) => [track, ...prev]);
      showToast('Added to Liked Songs', 'success');
    }
  };

  const createPlaylist = (title: string, description?: string, initialTracks?: Track[]): Playlist => {
    const newPl: Playlist = {
      // The random suffix avoids a collision if two playlists are created inside
      // the same millisecond, which the previous `pl-${Date.now()}` allowed.
      id: `pl-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      title,
      description,
      tracks: initialTracks ? [...initialTracks] : [],
      createdAt: Date.now(),
      // Marks the playlist as the user's own, which is what enables the delete
      // and remove-track controls in LibraryView. Omitting this was the reason a
      // playlist created in the app could never be deleted.
      isCustom: true,
    };
    setPlaylists((prev) => [...prev, newPl]);
    showToast(`Playlist "${title}" created`, 'success');
    return newPl;
  };

  // Reads the target from the current `playlists` value rather than from inside
  // the state updater, so the toast is not a side effect of rendering and cannot
  // fire twice under React's double-invoked updaters in development.
  const addToPlaylist = (playlistId: string, track: Track) => {
    const target = playlists.find((pl) => pl.id === playlistId);
    if (!target) {
      showToast('That playlist no longer exists', 'error');
      return;
    }
    if (target.tracks.some((t) => t.id === track.id)) {
      showToast(`Already in "${target.title}"`, 'info');
      return;
    }
    setPlaylists((prev) =>
      prev.map((pl) => (pl.id === playlistId ? { ...pl, tracks: [...pl.tracks, track] } : pl))
    );
    showToast(`Added to "${target.title}"`, 'success');
  };

  const removeFromPlaylist = (playlistId: string, trackId: string) => {
    const target = playlists.find((pl) => pl.id === playlistId);
    if (!target || !target.tracks.some((t) => t.id === trackId)) return;

    setPlaylists((prev) =>
      prev.map((pl) =>
        pl.id === playlistId ? { ...pl, tracks: pl.tracks.filter((t) => t.id !== trackId) } : pl
      )
    );
    showToast(`Removed from "${target.title}"`, 'info');
  };

  // Reorder tracks within a playlist. Uses the same positional move helper as the
  // queue so the two behave identically; a missing playlist is simply ignored.
  const reorderPlaylist = (playlistId: string, from: number, to: number) => {
    setPlaylists((prev) =>
      prev.map((pl) =>
        pl.id === playlistId ? { ...pl, tracks: moveItem(pl.tracks, from, to) } : pl
      )
    );
  };

  const importPlaylistToState = (playlist: Playlist) => {
    setPlaylists((prev) => {
      const filtered = prev.filter((p) => p.id !== playlist.id);
      return [...filtered, asUserPlaylist(playlist)];
    });
    showToast(`Imported "${playlist.title}" (${playlist.tracks.length} songs)`, 'success');
  };

  const deletePlaylist = (playlistId: string) => {
    const target = playlists.find((p) => p.id === playlistId);
    if (!target) return;

    setPlaylists((prev) => prev.filter((p) => p.id !== playlistId));
    showToast(`Deleted "${target.title}"`, 'info');
  };

  const isArtistSaved = (artistId: string) => savedArtists.some((a) => a.id === artistId);

  const saveArtist = (artist: Artist | SavedArtist) => {
    if (isArtistSaved(artist.id)) {
      showToast(`"${artist.name}" is already in your library`, 'info');
      return;
    }
    const newArtist: SavedArtist = {
      id: artist.id,
      name: artist.name,
      thumbnail: artist.thumbnail,
      subscribers: artist.subscribers,
      query: (artist as Artist).query || `${artist.name} top songs`,
      savedAt: Date.now(),
    };
    setSavedArtists((prev) => [newArtist, ...prev]);
    showToast(`Saved artist "${artist.name}" to Library`, 'success');
  };

  const removeArtist = (artistId: string) => {
    const target = savedArtists.find((a) => a.id === artistId);
    setSavedArtists((prev) => prev.filter((a) => a.id !== artistId));
    showToast(`Removed "${target?.name || 'artist'}" from Library`, 'info');
  };

  const isAlbumSaved = (albumId: string) => savedAlbums.some((a) => a.id === albumId);

  const saveAlbum = (album: PlaylistResult | Playlist | SavedAlbum) => {
    if (isAlbumSaved(album.id)) {
      showToast(`"${album.title}" is already in your library`, 'info');
      return;
    }
    const artistName =
      (album as SavedAlbum).artist ||
      (album as PlaylistResult).author ||
      (album as Playlist).description ||
      'Album';
    const albumThumb =
      ('thumbnail' in album && typeof album.thumbnail === 'string' ? album.thumbnail : '') ||
      ('cover' in album && typeof (album as Playlist).cover === 'string' ? (album as Playlist).cover : '');

    const newAlbum: SavedAlbum = {
      id: album.id,
      title: album.title,
      artist: artistName,
      thumbnail: albumThumb,
      trackCount: (album as PlaylistResult).trackCount || (album as Playlist).tracks?.length,
      savedAt: Date.now(),
    };
    setSavedAlbums((prev) => [newAlbum, ...prev]);
    showToast(`Saved album "${album.title}" to Library`, 'success');
  };

  const removeAlbum = (albumId: string) => {
    const target = savedAlbums.find((a) => a.id === albumId);
    setSavedAlbums((prev) => prev.filter((a) => a.id !== albumId));
    showToast(`Removed "${target?.title || 'album'}" from Library`, 'info');
  };

  const addToQueue = (track: Track) => {
    setQueue((prev) => [...prev, track]);
    showToast('Added to queue', 'success');
  };

  // Removing a queue entry has to move `queueIndex` with it, or the index would
  // keep pointing at whatever track slid into that position — the old inline
  // filter left the index untouched, so deleting anything above the current
  // track made next/prev jump to the wrong song. The rules live in queueOps.
  const removeFromQueue = (index: number) => {
    const result = removeAt(queue, queueIndex, index);
    setQueue(result.tracks);
    setQueueIndex(result.index);
  };

  // Reorder the up-next queue (e.g. drag or move-up/down), keeping the currently
  // playing track under the index so playback and next/prev stay correct.
  const reorderQueue = (from: number, to: number) => {
    const result = reorderQueueTracks(queue, queueIndex, from, to);
    setQueue(result.tracks);
    setQueueIndex(result.index);
  };

  const clearQueue = () => {
    // The current track stays so playback is not cut off. With nothing playing
    // the queue is emptied outright: the previous version left it untouched
    // while still reporting "Queue cleared".
    if (currentTrack) {
      setQueue([currentTrack]);
    } else {
      setQueue([]);
    }
    setQueueIndex(0);
    showToast('Queue cleared', 'info');
  };

  const setManualLyricsOffset = (offset: number) => setLyricsOffset(offset);

  const setSleepTimer = (minutes: number | null) => {
    if (minutes === null) {
      setSleepDeadline(null);
      setSleepTimerRemaining(null);
      showToast('Sleep timer cancelled', 'info');
    } else {
      setSleepDeadline(Date.now() + minutes * 60 * 1000);
      setSleepTimerRemaining(minutes * 60);
      showToast(`Sleep timer set for ${minutes} minutes`, 'success');
    }
  };

  const updateSettings = (newSettings: Partial<PlayerSettings>) => {
    setSettings((prev) => ({ ...prev, ...newSettings }));
  };

  const setTheme = (theme: ThemeMode) => {
    updateSettings({ theme });
    showToast(`Theme set to ${theme.charAt(0).toUpperCase() + theme.slice(1)}`, 'info');
  };

  // Keep the MediaSession handlers pointed at the current controls. Assigned on
  // every render (like the other mirror refs above) so the once-registered
  // lock-screen buttons always invoke the live functions, never a stale closure.
  mediaControlsRef.current = {
    play: resume,
    pause,
    next: nextTrack,
    prev: prevTrack,
    seekTo,
    stop: pause,
  };

  return (
    <PlayerContext.Provider
      value={{
        currentTrack,
        isPlaying,
        currentTime,
        duration,
        volume,
        isMuted,
        playbackRate,
        repeatMode,
        isShuffle,
        isLoadingAudio,
        queue,
        history,
        clearHistory,
        removeFromHistory,
        queueIndex,
        lyrics,
        isLoadingLyrics,
        activeLyricIndex,
        setManualLyricsOffset,
        lyricsOffset,
        dominantColor,
        isNowPlayingOpen,
        activeModalTab,
        setIsNowPlayingOpen,
        setActiveModalTab,
        favorites,
        toggleFavorite,
        isFavorite,
        playlists,
        createPlaylist,
        addToPlaylist,
        removeFromPlaylist,
        reorderPlaylist,
        importPlaylistToState,
        deletePlaylist,
        savedArtists,
        saveArtist,
        removeArtist,
        isArtistSaved,
        savedAlbums,
        saveAlbum,
        removeAlbum,
        isAlbumSaved,
        getTopTracks,
        getTopArtists,
        playCounts,
        playTrack,
        togglePlay,
        pause,
        resume,
        seekTo,
        nextTrack,
        prevTrack,
        setVolume,
        toggleMute,
        setPlaybackRate,
        toggleRepeat,
        toggleShuffle,
        addToQueue,
        removeFromQueue,
        reorderQueue,
        clearQueue,
        sleepTimerRemaining,
        setSleepTimer,
        settings,
        updateSettings,
        theme: settings.theme || 'system',
        effectiveTheme,
        setTheme,
        toasts,
        showToast,
      }}
    >
      {/* YouTube IFrame Container */}
      <div
        style={{
          position: 'fixed',
          top: '0',
          left: '0',
          width: '240px',
          height: '240px',
          opacity: 0.001,
          pointerEvents: 'none',
          zIndex: -9999,
          overflow: 'hidden',
        }}
      >
        <div id="yt-hidden-player" style={{ width: '100%', height: '100%' }}></div>
      </div>

      {children}
    </PlayerContext.Provider>
  );
};

export const usePlayer = () => {
  const context = useContext(PlayerContext);
  if (!context) {
    throw new Error('usePlayer must be used within a PlayerProvider');
  }
  return context;
};
