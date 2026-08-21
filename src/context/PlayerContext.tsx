import React, { createContext, useContext, useState, useEffect, useRef, useCallback, useMemo } from 'react';
import type { Track, LyricsData, RepeatMode, Playlist, PlayerSettings } from '../types/music';
import { searchYouTube } from '../services/youtube';
import { fetchLyrics } from '../services/lyrics';
import { getDominantColor } from '../services/colorExtractor';
import { getAlbumArtwork } from '../services/artwork';
import { useAuth } from './AuthContext';

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
  createPlaylist: (title: string, description?: string) => void;
  addToPlaylist: (playlistId: string, track: Track) => void;
  removeFromPlaylist: (playlistId: string, trackId: string) => void;
  importPlaylistToState: (playlist: Playlist) => void;
  deletePlaylist: (playlistId: string) => void;

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
  toggleRepeat: () => void;
  toggleShuffle: () => void;
  addToQueue: (track: Track) => void;
  removeFromQueue: (index: number) => void;
  clearQueue: () => void;

  // Sleep Timer
  sleepTimerRemaining: number | null;
  setSleepTimer: (minutes: number | null) => void;

  // Settings
  settings: PlayerSettings;
  updateSettings: (newSettings: Partial<PlayerSettings>) => void;

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

  // ---- No pre-seeded track or queue ----
  const [currentTrack, setCurrentTrack] = useState<Track | null>(null);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [currentTime, setCurrentTime] = useState<number>(0);
  const [duration, setDuration] = useState<number>(0);
  const [volume, setVolumeState] = useState<number>(() => {
    const saved = localStorage.getItem('auralis_volume');
    return saved ? Number(saved) : 90;
  });
  const [isMuted, setIsMuted] = useState<boolean>(false);
  const [repeatMode, setRepeatMode] = useState<RepeatMode>('off');
  const [isShuffle, setIsShuffle] = useState<boolean>(false);
  const [isLoadingAudio, setIsLoadingAudio] = useState<boolean>(false);

  // ---- Queue & History (both start empty) ----
  const [queue, setQueue] = useState<Track[]>([]);
  const [queueIndex, setQueueIndex] = useState<number>(0);

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

  // ---- Playlists: Only user-created, no defaults ----
  const [playlists, setPlaylists] = useState<Playlist[]>(() => {
    try {
      const saved = localStorage.getItem('auralis_playlists');
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });

  // ---- Play Count Analytics ----
  const [playCounts, setPlayCounts] = useState<PlayCountMap>(loadPlayCounts);

  // Sleep Timer
  const [sleepTimerRemaining, setSleepTimerRemaining] = useState<number | null>(null);

  // Toast notifications
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  // Settings
  const [settings, setSettings] = useState<PlayerSettings>(() => {
    const defaultSettings: PlayerSettings = {
      volume: 90,
      isMuted: false,
      playbackRate: 1,
      audioQuality: 'high',
      ambientVisuals: true,
      lyricsFontSize: 'medium',
      lyricsMode: 'spicy',
      lyricsAlignment: 'left',
      lyricsDepthBlur: true,
      karaokeSweep: true,
    };
    try {
      const saved = localStorage.getItem('auralis_settings');
      return saved ? { ...defaultSettings, ...JSON.parse(saved) } : defaultSettings;
    } catch {
      return defaultSettings;
    }
  });

  const playerRef = useRef<any>(null);
  const timeUpdateInterval = useRef<any>(null);
  const isPlayerReadyRef = useRef<boolean>(false);
  const pendingTrackRef = useRef<Track | null>(null);

  // ---- Sync from Firestore on user login ----
  useEffect(() => {
    if (!user) return;

    let isCancelled = false;
    fetchCloudData().then((cloudData) => {
      if (isCancelled || !cloudData) return;

      if (cloudData.favorites && Array.isArray(cloudData.favorites)) {
        setFavorites((localFavs) => {
          const map = new Map<string, Track>();
          cloudData.favorites?.forEach((t) => map.set(t.id, t));
          localFavs.forEach((t) => map.set(t.id, t));
          return Array.from(map.values());
        });
      }

      if (cloudData.playlists && Array.isArray(cloudData.playlists)) {
        setPlaylists((localPls) => {
          const map = new Map<string, Playlist>();
          cloudData.playlists?.forEach((p) => map.set(p.id, p));
          localPls.forEach((p) => map.set(p.id, p));
          return Array.from(map.values());
        });
      }
    });

    return () => {
      isCancelled = true;
    };
  }, [user]);

  // ---- Persist to localStorage & Firestore ----
  useEffect(() => {
    localStorage.setItem('auralis_favorites', JSON.stringify(favorites));
    if (user) {
      saveFavoritesToCloud(favorites);
    }
  }, [favorites, user]);

  useEffect(() => {
    localStorage.setItem('auralis_playlists', JSON.stringify(playlists));
    if (user) {
      savePlaylistsToCloud(playlists);
    }
  }, [playlists, user]);

  useEffect(() => {
    localStorage.setItem('auralis_volume', volume.toString());
  }, [volume]);

  useEffect(() => {
    localStorage.setItem('auralis_settings', JSON.stringify(settings));
  }, [settings]);

  useEffect(() => {
    localStorage.setItem('auralis_history', JSON.stringify(history.slice(0, 50)));
  }, [history]);

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
              setIsPlaying(true);
            }
          },
          onStateChange: (event: any) => {
            if (event.data === 1) {
              setIsPlaying(true);
              setIsLoadingAudio(false);
              const dur = event.target.getDuration();
              if (dur && dur > 0) setDuration(dur);
            } else if (event.data === 2) {
              setIsPlaying(false);
              setIsLoadingAudio(false);
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

  // Track progress interval
  useEffect(() => {
    if (isPlaying) {
      timeUpdateInterval.current = setInterval(() => {
        if (playerRef.current && typeof playerRef.current.getCurrentTime === 'function') {
          try {
            const time = playerRef.current.getCurrentTime();
            if (typeof time === 'number') setCurrentTime(time);
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

  // Lyrics sync — works for both line-sync and richsync
  useEffect(() => {
    if (!lyrics || lyrics.syncType === 'plain' || lyrics.lines.length === 0) {
      setActiveLyricIndex(-1);
      return;
    }
    const adjustedTime = currentTime + lyricsOffset;
    let activeIdx = -1;
    for (let i = 0; i < lyrics.lines.length; i++) {
      if (adjustedTime >= lyrics.lines[i].time) {
        activeIdx = i;
      } else {
        break;
      }
    }
    setActiveLyricIndex(activeIdx);
  }, [currentTime, lyrics, lyricsOffset]);

  // Load artwork, lyrics, dominant color on track change
  useEffect(() => {
    if (!currentTrack) return;

    getAlbumArtwork(currentTrack.title, currentTrack.artist, currentTrack.thumbnail).then((resolvedThumb) => {
      if (resolvedThumb && resolvedThumb !== currentTrack.thumbnail) {
        setCurrentTrack((prev) => (prev ? { ...prev, thumbnail: resolvedThumb } : null));
        setQueue((prev) =>
          prev.map((t) => (t.id === currentTrack.id ? { ...t, thumbnail: resolvedThumb } : t))
        );
      }
      getDominantColor(resolvedThumb).then((color) => setDominantColor(color));
    });

    setIsLoadingLyrics(true);
    fetchLyrics(currentTrack.title, currentTrack.artist, currentTrack.duration, currentTrack.id)
      .then((data) => setLyrics(data))
      .finally(() => setIsLoadingLyrics(false));
  }, [currentTrack?.id]);

  // Sleep timer countdown
  useEffect(() => {
    if (sleepTimerRemaining === null || sleepTimerRemaining <= 0) return;
    const timer = setInterval(() => {
      setSleepTimerRemaining((prev) => {
        if (prev === null || prev <= 1) {
          pause();
          return null;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [sleepTimerRemaining]);

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
  const playTrack = (track: Track, newQueue?: Track[]) => {
    // Add current track to history
    if (currentTrack) {
      setHistory((prev) => [currentTrack, ...prev.filter((t) => t.id !== currentTrack.id)].slice(0, 50));
    }

    setCurrentTrack(track);
    setCurrentTime(0);
    setLyricsOffset(0);
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

    if (playerRef.current && typeof playerRef.current.loadVideoById === 'function') {
      try {
        playerRef.current.loadVideoById({ videoId: track.id, startSeconds: 0 });
        playerRef.current.playVideo();
      } catch (err) {
        console.error('Error loading video:', err);
      }
    } else {
      pendingTrackRef.current = track;
    }
  };

  const togglePlay = () => {
    if (!playerRef.current) {
      if (currentTrack) playTrack(currentTrack);
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
    if (playerRef.current && typeof playerRef.current.playVideo === 'function') {
      try { playerRef.current.playVideo(); } catch {}
    }
    setIsPlaying(true);
  };

  const seekTo = (seconds: number) => {
    if (playerRef.current && typeof playerRef.current.seekTo === 'function') {
      try {
        playerRef.current.seekTo(seconds, true);
        setCurrentTime(seconds);
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

  const createPlaylist = (title: string, description?: string) => {
    const newPl: Playlist = {
      id: `pl-${Date.now()}`,
      title,
      description,
      tracks: [],
      createdAt: Date.now(),
    };
    setPlaylists((prev) => [...prev, newPl]);
    showToast(`Playlist "${title}" created`, 'success');
  };

  const addToPlaylist = (playlistId: string, track: Track) => {
    setPlaylists((prev) =>
      prev.map((pl) => {
        if (pl.id === playlistId) {
          if (pl.tracks.some((t) => t.id === track.id)) return pl;
          showToast(`Added to "${pl.title}"`, 'success');
          return { ...pl, tracks: [...pl.tracks, track] };
        }
        return pl;
      })
    );
  };

  const removeFromPlaylist = (playlistId: string, trackId: string) => {
    setPlaylists((prev) =>
      prev.map((pl) => {
        if (pl.id === playlistId) {
          return { ...pl, tracks: pl.tracks.filter((t) => t.id !== trackId) };
        }
        return pl;
      })
    );
  };

  const importPlaylistToState = (playlist: Playlist) => {
    setPlaylists((prev) => {
      const filtered = prev.filter((p) => p.id !== playlist.id);
      return [...filtered, playlist];
    });
    showToast(`Imported "${playlist.title}" (${playlist.tracks.length} songs)`, 'success');
  };

  const deletePlaylist = (playlistId: string) => {
    setPlaylists((prev) => {
      const pl = prev.find((p) => p.id === playlistId);
      if (pl) showToast(`Deleted "${pl.title}"`, 'info');
      return prev.filter((p) => p.id !== playlistId);
    });
  };

  const addToQueue = (track: Track) => {
    setQueue((prev) => [...prev, track]);
    showToast('Added to queue', 'success');
  };

  const removeFromQueue = (index: number) => {
    setQueue((prev) => prev.filter((_, idx) => idx !== index));
  };

  const clearQueue = () => {
    if (currentTrack) {
      setQueue([currentTrack]);
      setQueueIndex(0);
    }
    showToast('Queue cleared', 'info');
  };

  const setManualLyricsOffset = (offset: number) => setLyricsOffset(offset);

  const setSleepTimer = (minutes: number | null) => {
    if (minutes === null) {
      setSleepTimerRemaining(null);
      showToast('Sleep timer cancelled', 'info');
    } else {
      setSleepTimerRemaining(minutes * 60);
      showToast(`Sleep timer set for ${minutes} minutes`, 'success');
    }
  };

  const updateSettings = (newSettings: Partial<PlayerSettings>) => {
    setSettings((prev) => ({ ...prev, ...newSettings }));
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
        importPlaylistToState,
        deletePlaylist,
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
        toggleRepeat,
        toggleShuffle,
        addToQueue,
        removeFromQueue,
        clearQueue,
        sleepTimerRemaining,
        setSleepTimer,
        settings,
        updateSettings,
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
