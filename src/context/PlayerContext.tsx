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
} from '../lib/queueStorage';
import type { StoredQueue } from '../lib/queueStorage';
import { moveItem, removeAt, reorderQueue as reorderQueueTracks } from '../lib/queueOps';

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
  activeModalTab: 'lyrics' | 'queue' | 'visualizer' | 'info';
  setIsNowPlayingOpen: (open: boolean) => void;
  setActiveModalTab: (tab: 'lyrics' | 'queue' | 'visualizer' | 'info') => void;

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

  // ---- No pre-seeded track or queue: only what the last session left ----
  const [currentTrack, setCurrentTrack] = useState<Track | null>(restoredQueue.currentTrack);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [currentTime, setCurrentTime] = useState<number>(0);
  // Seeded from the restored track so the scrubber shows its real length instead
  // of 0:00 / 0:00. The player overwrites this with its own duration on play.
  const [duration, setDuration] = useState<number>(restoredQueue.currentTrack?.duration ?? 0);
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

  // History persisted to localStorage
  const [history, setHistory] = useState<Track[]>(() => {
    try {
      const saved = localStorage.getItem('auralis_history');
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });

  // Lyrics
  const [lyrics, setLyrics] = useState<LyricsData | null>(null);
  const [isLoadingLyrics, setIsLoadingLyrics] = useState<boolean>(false);
  const [activeLyricIndex, setActiveLyricIndex] = useState<number>(-1);
  const [lyricsOffset, setLyricsOffset] = useState<number>(0);

  // Dynamic Theme
  const [dominantColor, setDominantColor] = useState<string>('#dbe7b5');
  const [isNowPlayingOpen, setIsNowPlayingOpen] = useState<boolean>(false);
  const [activeModalTab, setActiveModalTab] = useState<'lyrics' | 'queue' | 'visualizer' | 'info'>('lyrics');

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

  const playerRef = useRef<any>(null);
  const timeUpdateInterval = useRef<any>(null);
  const isPlayerReadyRef = useRef<boolean>(false);
  const pendingTrackRef = useRef<Track | null>(null);
  // Latest chosen playback speed, read inside the once-mounted player callbacks
  // (which close over stale state) so the rate can be re-applied on every load.
  const playbackRateRef = useRef<number>(playbackRate);
  playbackRateRef.current = playbackRate;
  // Video id currently loaded into the YouTube player, or null if it has never
  // been given one. A restored session has a currentTrack but no loaded video,
  // and play/seek must not pretend to work in that state.
  const loadedVideoIdRef = useRef<string | null>(null);
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
    localStorage.setItem('auralis_history', JSON.stringify(history.slice(0, 50)));
  }, [history]);

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
    setToasts((prev) => [...prev, { id, text, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 3000);
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
              pendingTrackRef.current = null;
              event.target.loadVideoById({ videoId: tr.id, startSeconds: 0 });
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
              handleTrackEnded();
            }
          },
          onError: async (e: any) => {
            console.warn('YouTube Player Error:', e);
            setIsLoadingAudio(false);
            if (currentTrack) {
              try {
                const alt = await searchYouTube(`${currentTrack.title} ${currentTrack.artist} audio`);
                if (alt.length > 1 && alt[1].id !== currentTrack.id) {
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
          } catch {}
        }
      }, 100);
    } else {
      if (timeUpdateInterval.current) clearInterval(timeUpdateInterval.current);
    }
    return () => {
      if (timeUpdateInterval.current) clearInterval(timeUpdateInterval.current);
    };
  }, [isPlaying]);

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
    if (repeatMode === 'one') {
      seekTo(0);
      resume();
    } else if (repeatMode === 'all' || queueIndex < queue.length - 1) {
      nextTrack();
    } else {
      setIsPlaying(false);
    }
  };

  // ---- Playback Controls ----

  /**
   * Hand a track to the YouTube player and start it.
   *
   * Split out of `playTrack` so the cold-start path in `togglePlay` can reuse it
   * without re-recording history. If the iframe API has not finished loading the
   * track is parked in `pendingTrackRef` and the onReady handler picks it up.
   */
  const loadAndPlay = (track: Track) => {
    if (playerRef.current && typeof playerRef.current.loadVideoById === 'function') {
      try {
        playerRef.current.loadVideoById({ videoId: track.id, startSeconds: 0 });
        playerRef.current.playVideo();
        loadedVideoIdRef.current = track.id;
      } catch (err) {
        console.error('Error loading video:', err);
      }
    } else {
      pendingTrackRef.current = track;
    }
  };

  const playTrack = (track: Track, newQueue?: Track[]) => {
    // Add current track to history
    if (currentTrack) {
      setHistory((prev) => [currentTrack, ...prev.filter((t) => t.id !== currentTrack.id)].slice(0, 50));
    }

    setCurrentTrack(track);
    setCurrentTime(0);
    setLyricsOffset(0);
    globalPlaybackClock.reset();
    setIsPlaying(true);
    setIsLoadingAudio(true);

    // Record play count
    setPlayCounts((prev) => recordPlay(prev, track));

    if (newQueue) {
      setQueue(newQueue);
      const idx = newQueue.findIndex((t) => t.id === track.id);
      setQueueIndex(idx !== -1 ? idx : 0);
    } else {
      const idx = queue.findIndex((t) => t.id === track.id);
      if (idx !== -1) {
        setQueueIndex(idx);
      } else {
        setQueue((prev) => [...prev, track]);
        setQueueIndex(queue.length);
      }
    }

    loadAndPlay(track);
  };

  /**
   * Start the restored track from the beginning.
   *
   * After a reload the track came back from storage but the player has never
   * been given a video, so `playVideo()` would do nothing while the UI flipped
   * to "playing". Loading it first is what makes the restored state real.
   */
  const startFromCold = (track: Track) => {
    setCurrentTime(0);
    setIsPlaying(true);
    setIsLoadingAudio(true);
    globalPlaybackClock.reset();
    setPlayCounts((prev) => recordPlay(prev, track));
    loadAndPlay(track);
  };

  const togglePlay = () => {
    if (!currentTrack) return;

    if (!isPlaying && loadedVideoIdRef.current !== currentTrack.id) {
      startFromCold(currentTrack);
      return;
    }

    if (!playerRef.current) {
      playTrack(currentTrack);
      return;
    }
    try {
      if (isPlaying) {
        if (typeof playerRef.current.pauseVideo === 'function') playerRef.current.pauseVideo();
        setIsPlaying(false);
      } else {
        if (typeof playerRef.current.playVideo === 'function') playerRef.current.playVideo();
        setIsPlaying(true);
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
  };

  const resume = () => {
    if (!currentTrack) return;
    // Same cold-start rule as togglePlay: with nothing loaded, reporting
    // playback would be a lie, so load the track instead.
    if (loadedVideoIdRef.current !== currentTrack.id) {
      startFromCold(currentTrack);
      return;
    }
    if (playerRef.current && typeof playerRef.current.playVideo === 'function') {
      try { playerRef.current.playVideo(); } catch {}
    }
    setIsPlaying(true);
  };

  const seekTo = (seconds: number) => {
    // Nothing is loaded straight after a reload, so a seek would move the
    // progress bar while the audio stayed where it was. Refuse instead.
    if (!currentTrack || loadedVideoIdRef.current !== currentTrack.id) return;
    if (playerRef.current && typeof playerRef.current.seekTo === 'function') {
      try {
        playerRef.current.seekTo(seconds, true);
        setCurrentTime(seconds);
        globalPlaybackClock.seekTo(seconds);
      } catch {}
    }
  };

  const nextTrack = () => {
    if (queue.length === 0) return;
    let nextIdx = queueIndex + 1;
    if (isShuffle) {
      nextIdx = Math.floor(Math.random() * queue.length);
    } else if (nextIdx >= queue.length) {
      if (repeatMode === 'all') { nextIdx = 0; } else { return; }
    }
    const nxt = queue[nextIdx];
    if (nxt) { setQueueIndex(nextIdx); playTrack(nxt); }
  };

  const prevTrack = () => {
    if (currentTime > 4) { seekTo(0); return; }
    let prevIdx = queueIndex - 1;
    if (prevIdx < 0) {
      if (repeatMode === 'all') { prevIdx = queue.length - 1; } else { seekTo(0); return; }
    }
    const prv = queue[prevIdx];
    if (prv) { setQueueIndex(prevIdx); playTrack(prv); }
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
    setFavorites((prev) => {
      const exists = prev.some((t) => t.id === track.id);
      if (exists) {
        showToast('Removed from Liked Songs', 'info');
        return prev.filter((t) => t.id !== track.id);
      } else {
        showToast('Added to Liked Songs', 'success');
        return [track, ...prev];
      }
    });
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
