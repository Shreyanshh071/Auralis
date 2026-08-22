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

export interface LyricWord {
  word: string;
  time: number;
  duration?: number;
}

export interface LyricLine {
  time: number; // In seconds
  text: string;
  words?: LyricWord[];
  isInstrumental?: boolean;
}

export interface LyricsData {
  syncType: 'richsync' | 'line-sync' | 'plain';
  lines: LyricLine[];
  plainLyrics?: string;
  provider: 'lrclib' | 'local' | 'youtube';
  trackName?: string;
  artistName?: string;
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

export interface PlayerSettings {
  volume: number;
  isMuted: boolean;
  // Playback speed is NOT kept here. It lives as first-class player state in
  // PlayerContext (`playbackRate` / `setPlaybackRate`), wired directly to the
  // YouTube IFrame `setPlaybackRate` API and re-applied on every track load,
  // with a visible control in the now-playing view — so it is real behaviour,
  // not an inert stored value.
  //
  // `audioQuality`, `ambientVisuals` and `karaokeSweep` were also removed for the
  // same reason: each was declared, defaulted and persisted but read by nothing
  // and exposed by no control. `audioQuality` in particular is unimplementable on
  // the current pipeline — the YouTube IFrame quality API is deprecated and the
  // audio is cross-origin — so any control would have been fake. Reintroduce any
  // of these only together with real wiring and a real control.
  lyricsFontSize: 'small' | 'medium' | 'large';
  lyricsMode: 'spicy' | 'cinema' | 'classic';
  lyricsAlignment: 'left' | 'center';
  lyricsDepthBlur: boolean;
}

