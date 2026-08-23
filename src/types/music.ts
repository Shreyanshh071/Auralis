export interface Track {
  id: string; // YouTube Video ID
  title: string;
  artist: string;
  album?: string;
  duration: number; // in seconds
  thumbnail: string;
  source?: 'youtube' | 'local' | 'curated';
  channelTitle?: string;
  views?: string;
  color?: string; // Dominant theme color (e.g. #8b5cf6)
}

/** A channel / artist returned by a typed search. */
export interface Artist {
  id: string; // channel id (UC…) or a stable slug when the provider omits one
  name: string;
  thumbnail?: string;
  /** Human-readable subscriber count as the provider phrased it, when present. */
  subscribers?: string;
  /** Search query used to open this artist ("<name> top songs"). */
  query: string;
}

/** A playlist (or album, which YouTube models as a playlist) returned by search. */
export interface PlaylistResult {
  id: string; // playlist id (PL…, OLAK…, RD…)
  title: string;
  thumbnail?: string;
  /** Uploader / channel that owns the playlist, when the provider reports it. */
  author?: string;
  /** Number of items, when the provider reports it. */
  trackCount?: number;
}

/**
 * Typed result of a discovery search. Each bucket holds only entities the
 * provider actually distinguished — nothing is fabricated to fill a section.
 * `songs` are playable streams; `artists` are channels; `playlists` includes
 * albums (YouTube exposes albums as playlists).
 */
export interface SearchResults {
  songs: Track[];
  artists: Artist[];
  playlists: PlaylistResult[];
}

export interface LyricWord {
  word: string;
  time: number;
  duration?: number;
}

export interface LyricLine {
  time: number; // In seconds
  text: string;
  translatedText?: string;
  words?: LyricWord[];
  isInstrumental?: boolean;
}

export interface LyricsData {
  syncType: 'richsync' | 'line-sync' | 'plain';
  lines: LyricLine[];
  plainLyrics?: string;
  translatedPlainLyrics?: string;
  translatedLanguage?: string;
  provider: 'amll' | 'lrclib' | 'local' | 'youtube';
  trackName?: string;
  artistName?: string;
}

export interface SavedArtist {
  id: string; // channel id (UC...) or stable slug
  name: string;
  thumbnail?: string;
  subscribers?: string;
  query?: string;
  savedAt: number;
}

export interface SavedAlbum {
  id: string; // playlist id (PL..., OLAK...)
  title: string;
  artist?: string;
  thumbnail?: string;
  trackCount?: number;
  savedAt: number;
}

export interface Playlist {
  id: string;
  title: string;
  description?: string;
  cover?: string;
  tracks: Track[];
  createdAt: number;
  isCustom?: boolean;
}


export type RepeatMode = 'off' | 'all' | 'one';

export type ThemeMode = 'dark' | 'light' | 'system';

export interface PlayerSettings {
  volume: number;
  isMuted: boolean;
  theme: ThemeMode;
  lyricsFontSize: 'small' | 'medium' | 'large';
  lyricsMode: 'spicy' | 'cinema' | 'classic';
  lyricsAlignment: 'left' | 'center';
  lyricsDepthBlur: boolean;
}

