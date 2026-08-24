import { Capacitor, CapacitorHttp } from '@capacitor/core';
import type { Artist, PlaylistResult, SearchResults, Track } from '../types/music';
import { searchCache, normalizeSearchKey, getInFlightSearch, setInFlightSearch } from './searchCache.ts';

/**
 * OPTIONAL DEMO CONTENT — not search results, not recommendations.
 *
 * This is a fixed, hand-written list of well-known tracks. It exists only so the
 * app can be demonstrated without a working search provider.
 *
 * Rules:
 *   - MUST NOT be returned from `searchYouTube()`.
 *   - MUST NOT be used as recommendations, quick picks, or "trending".
 *   - MUST only ever be shown behind an explicit, user-visible "demo content" label.
 *
 * If a search or recommendation path fails, it must surface a real error or a real
 * empty result — never this list.
 */
export const DEMO_TRACKS: Track[] = [
  {
    id: 'sBzrzS1Ag_g',
    title: 'The Less I Know The Better',
    artist: 'Tame Impala',
    album: 'Currents',
    duration: 216,
    thumbnail: 'https://i.ytimg.com/vi/sBzrzS1Ag_g/hqdefault.jpg',
    color: '#9333ea',
  },
  {
    id: 'fJ9rUzIMcZQ',
    title: 'Bohemian Rhapsody',
    artist: 'Queen',
    album: 'A Night at the Opera',
    duration: 354,
    thumbnail: 'https://i.ytimg.com/vi/fJ9rUzIMcZQ/hqdefault.jpg',
    color: '#e11d48',
  },
  {
    id: '4NRXx6U8ABQ',
    title: 'Blinding Lights',
    artist: 'The Weeknd',
    album: 'After Hours',
    duration: 200,
    thumbnail: 'https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg',
    color: '#ef4444',
  },
  {
    id: '34Na4j8AVgA',
    title: 'Starboy',
    artist: 'The Weeknd ft. Daft Punk',
    album: 'Starboy',
    duration: 230,
    thumbnail: 'https://i.ytimg.com/vi/34Na4j8AVgA/hqdefault.jpg',
    color: '#3b82f6',
  },
  {
    id: '09839DpTctU',
    title: 'Hotel California',
    artist: 'Eagles',
    album: 'Hotel California',
    duration: 390,
    thumbnail: 'https://i.ytimg.com/vi/09839DpTctU/hqdefault.jpg',
    color: '#f59e0b',
  },
  {
    id: 'GCdwKhTtNNw',
    title: 'Sweater Weather',
    artist: 'The Neighbourhood',
    album: 'I Love You.',
    duration: 240,
    thumbnail: 'https://i.ytimg.com/vi/GCdwKhTtNNw/hqdefault.jpg',
    color: '#6366f1',
  },
  {
    id: 'H5v3kku4y6Q',
    title: 'As It Was',
    artist: 'Harry Styles',
    album: "Harry's House",
    duration: 167,
    thumbnail: 'https://i.ytimg.com/vi/H5v3kku4y6Q/hqdefault.jpg',
    color: '#ec4899',
  },
  {
    id: 'JGwWNGJdvx8',
    title: 'Shape of You',
    artist: 'Ed Sheeran',
    album: '÷ (Divide)',
    duration: 233,
    thumbnail: 'https://i.ytimg.com/vi/JGwWNGJdvx8/hqdefault.jpg',
    color: '#06b6d4',
  },
  {
    id: 'kTJczUoc26U',
    title: 'Stay',
    artist: 'The Kid LAROI, Justin Bieber',
    album: 'Stay',
    duration: 141,
    thumbnail: 'https://i.ytimg.com/vi/kTJczUoc26U/hqdefault.jpg',
    color: '#8b5cf6',
  },
  {
    id: 'XXYlFuWEuKI',
    title: 'Save Your Tears',
    artist: 'The Weeknd',
    album: 'After Hours',
    duration: 215,
    thumbnail: 'https://i.ytimg.com/vi/XXYlFuWEuKI/hqdefault.jpg',
    color: '#ef4444',
  },
  {
    id: 'mRD0-GxqHVo',
    title: 'Heat Waves',
    artist: 'Glass Animals',
    album: 'Dreamland',
    duration: 238,
    thumbnail: 'https://i.ytimg.com/vi/mRD0-GxqHVo/hqdefault.jpg',
    color: '#10b981',
  },
  {
    id: 'TUVcZfQe-Kw',
    title: 'Levitating',
    artist: 'Dua Lipa',
    album: 'Future Nostalgia',
    duration: 203,
    thumbnail: 'https://i.ytimg.com/vi/TUVcZfQe-Kw/hqdefault.jpg',
    color: '#ec4899',
  },
  {
    id: 'DyDfgMOUjCI',
    title: 'bad guy',
    artist: 'Billie Eilish',
    album: 'WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?',
    duration: 194,
    thumbnail: 'https://i.ytimg.com/vi/DyDfgMOUjCI/hqdefault.jpg',
    color: '#84cc16',
  },
  {
    id: 'djV11Xbc914',
    title: 'Take On Me',
    artist: 'a-ha',
    album: 'Hunting High and Low',
    duration: 227,
    thumbnail: 'https://i.ytimg.com/vi/djV11Xbc914/hqdefault.jpg',
    color: '#f97316',
  },
  {
    id: 'hTWKbfoikeg',
    title: 'Smells Like Teen Spirit',
    artist: 'Nirvana',
    album: 'Nevermind',
    duration: 301,
    thumbnail: 'https://i.ytimg.com/vi/hTWKbfoikeg/hqdefault.jpg',
    color: '#06b6d4',
  },
  {
    id: '1w7OgIMMRc4',
    title: "Sweet Child O' Mine",
    artist: "Guns N' Roses",
    album: "Appetite for Destruction",
    duration: 356,
    thumbnail: 'https://i.ytimg.com/vi/1w7OgIMMRc4/hqdefault.jpg',
    color: '#eab308',
  },
];

export const MOODS = [
  { id: 'focus', name: 'Focus & Study', subtitle: 'Lofi & deep concentration', query: 'lofi study focus beats', color: '#6366f1' },
  { id: 'workout', name: 'Workout & Gym', subtitle: 'High energy trap & EDM', query: 'high energy workout hype', color: '#ef4444' },
  { id: 'relax', name: 'Relax & Chill', subtitle: 'Ambient soundscapes & acoustic', query: 'relax chill acoustic ambient', color: '#10b981' },
  { id: 'night', name: 'Late Night Drive', subtitle: 'Synthwave & midnight moody', query: 'synthwave late night drive', color: '#8b5cf6' },
  { id: 'party', name: 'Party & Upbeat', subtitle: 'Dance hits & club bangers', query: 'dance party club hits', color: '#f59e0b' },
];

// Static browse categories. These are query presets for the Explore view, not
// content — each one runs a real search when selected.
export const GENRES = [
  { id: 'pop', name: 'Pop Hits', query: 'top pop hits music', gradient: 'from-pink-500/20 to-rose-600/20' },
  { id: 'hiphop', name: 'Hip-Hop / Rap', query: 'hip hop top rap hits', gradient: 'from-amber-500/20 to-orange-600/20' },
  { id: 'indie', name: 'Indie & Alt', query: 'indie alternative rock music', gradient: 'from-emerald-500/20 to-teal-600/20' },
  { id: 'rock', name: 'Classic Rock', query: 'classic rock greatest hits', gradient: 'from-red-500/20 to-purple-600/20' },
  { id: 'electronic', name: 'Electronic / EDM', query: 'electronic dance edm music', gradient: 'from-blue-500/20 to-cyan-600/20' },
  { id: 'rnb', name: 'R&B / Soul', query: 'r&b soul smooth hits', gradient: 'from-purple-500/20 to-indigo-600/20' },
];



/**
 * Thrown when every search provider was unreachable or errored.
 *
 * This is deliberately distinct from "the providers worked and found nothing".
 * Callers must be able to tell a broken backend apart from an empty result set,
 * so the UI can show a retry affordance instead of "no results".
 */
export class SearchUnavailableError extends Error {
  readonly attempted: string[];

  constructor(attempted: string[]) {
    super(
      'No search provider could be reached. The local search endpoint only exists ' +
        'under `vite dev`, and the public fallback instances did not respond.'
    );
    this.name = 'SearchUnavailableError';
    this.attempted = attempted;
  }
}

/** Clean YouTube artist name (e.g. 'Ed Sheeran - Topic' → 'Ed Sheeran'). */
export function cleanArtistName(raw: string): string {
  if (!raw || typeof raw !== 'string') return 'Unknown artist';
  let name = raw.trim();
  name = name.replace(/\s*-\s*Topic$/i, '');
  name = name.replace(/VEVO$/i, '');
  return name.trim() || raw.trim();
}

/** Clean YouTube video noise from track title while preserving genuine musical modifiers. */
export function cleanTrackTitle(raw: string): string {
  if (!raw || typeof raw !== 'string') return 'Untitled Track';
  let cleaned = raw;
  const videoNoisePatterns = [
    /\s*[\(\[]\s*official\s*(music)?\s*video\s*[\)\]]/gi,
    /\s*[\(\[]\s*official\s*audio\s*[\)\]]/gi,
    /\s*[\(\[]\s*lyric\s*video\s*[\)\]]/gi,
    /\s*[\(\[]\s*lyrics\s*[\)\]]/gi,
    /\s*[\(\[]\s*visualizer\s*[\)\]]/gi,
    /\s*[\(\[]\s*audio\s*[\)\]]/gi,
    /\s*[\(\[]\s*(?:4k|hd|hq|1080p|720p|uhd|60fps)(?:\s+(?:4k|hd|hq|1080p|720p|uhd|60fps|audio))*\s*[\)\]]/gi,
    /\s*[\(\[]\s*official\s*[\)\]]/gi,
    /\s*\|\s*official\s*(music)?\s*video/gi,
  ];

  for (const pattern of videoNoisePatterns) {
    cleaned = cleaned.replace(pattern, '');
  }

  return cleaned.replace(/\s+/g, ' ').trim() || raw;
}

/** Normalise one raw Piped/Invidious item into a Track, or null if unusable. */
function parseProviderItem(item: any): Track | null {
  const videoId = item.url ? String(item.url).replace('/watch?v=', '') : item.videoId || item.id;
  if (!videoId || typeof videoId !== 'string' || videoId.length < 5) return null;

  const rawTitle = item.title || 'Untitled Track';
  const uploader = item.uploaderName || item.author || 'Unknown artist';

  let artist = cleanArtistName(uploader);
  let title = rawTitle;
  if (rawTitle.includes(' - ')) {
    const parts = rawTitle.split(' - ');
    const p0 = parts[0].trim();
    const p1 = parts.slice(1).join(' - ').trim();

    const isP1Descriptor =
      /^(?:full\s*(?:video|audio|song|track)?|official\s*(?:video|music\s*video|audio|lyrics?|lyric\s*video)?|lyrical|audio\s*song|video\s*song)/i.test(p1) ||
      p1.includes('|');

    if (isP1Descriptor) {
      title = cleanTrackTitle(p0);
      const segs = p1.split(/\s*[-–—:|~•]+\s*/).map((s: string) => s.trim()).filter((s: string) => s && !/^(?:full|official|video|audio|song|lyrical|4k|8k|hd|hq)/i.test(s));
      const foundArtist = segs.find((s: string) => !/tseries|sonymusic|zeemusic|saregama|yrf|tipsofficial|spinninrecords|monstercat|vevo|channel|records|music/i.test(s.toLowerCase().replace(/\s+/g, '')));
      artist = foundArtist ? cleanArtistName(foundArtist) : cleanArtistName(uploader);
    } else {
      artist = cleanArtistName(p0);
      title = cleanTrackTitle(p1);
    }
  } else {
    title = cleanTrackTitle(title);
  }

  const rawDuration = item.duration ?? (item.lengthSeconds ? Number(item.lengthSeconds) : undefined);
  const duration = typeof rawDuration === 'number' && rawDuration > 0 ? rawDuration : 0;

  return {
    id: videoId,
    title,
    artist,
    duration,
    thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    source: 'youtube',
  };
}

// Per-bucket caps. Songs dominate the Explore view; artists/playlists are shown
// as compact rows, so a dozen of each is plenty.
const MAX_SONGS = 25;
const MAX_ARTISTS = 12;
const MAX_PLAYLISTS = 12;

/** One normalised entity from a provider search item (exactly one field set), or null. */
export interface ParsedSearchItem {
  song?: Track;
  artist?: Artist;
  playlist?: PlaylistResult;
}

/** Human-friendly count: 1_234 → "1.2K", 3_400_000 → "3.4M". */
export function formatCount(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '';
  if (n >= 1e9) return `${(n / 1e9).toFixed(1).replace(/\.0$/, '')}B`;
  if (n >= 1e6) return `${(n / 1e6).toFixed(1).replace(/\.0$/, '')}M`;
  if (n >= 1e3) return `${(n / 1e3).toFixed(1).replace(/\.0$/, '')}K`;
  return String(n);
}

/** Accept a full https URL, upgrade a protocol-relative `//host/…`, else drop it. */
function normalizeThumbUrl(url: unknown): string | undefined {
  if (typeof url !== 'string' || !url) return undefined;
  if (url.startsWith('//')) return `https:${url}`;
  if (url.startsWith('http://') || url.startsWith('https://')) return url;
  return undefined;
}

/** Pull a channel id (UC…) out of a Piped `/channel/UC…` url. */
function channelIdFromUrl(url: unknown): string | null {
  if (typeof url !== 'string') return null;
  const m = url.match(/\/channel\/([\w-]+)/);
  return m ? m[1] : null;
}

/** Pull a playlist id out of a Piped `/playlist?list=…` url. */
function playlistIdFromUrl(url: unknown): string | null {
  if (typeof url !== 'string') return null;
  const m = url.match(/[?&]list=([\w-]+)/);
  return m ? m[1] : null;
}

/**
 * Normalise one Piped search item. Piped tags items with a `type`:
 *   - `stream`   → a song (delegated to `parseProviderItem`)
 *   - `channel`  → an artist
 *   - `playlist` → a playlist / album
 * Anything else (e.g. `radio`) is skipped. Exported for fixture tests.
 */
export function parsePipedSearchItem(item: any): ParsedSearchItem | null {
  if (!item || typeof item !== 'object') return null;
  const type = item.type;

  if (type === 'channel') {
    const name = String(item.name || '').trim();
    if (!name) return null;
    const subs =
      typeof item.subscribers === 'number' && item.subscribers >= 0
        ? `${formatCount(item.subscribers)} subscribers`
        : undefined;
    return {
      artist: {
        id: channelIdFromUrl(item.url) || `piped:${name}`,
        name,
        thumbnail: normalizeThumbUrl(item.thumbnail),
        subscribers: subs,
        query: `${name} top songs`,
      },
    };
  }

  if (type === 'playlist') {
    const id = playlistIdFromUrl(item.url);
    const title = String(item.name || '').trim();
    if (!id || !title) return null;
    return {
      playlist: {
        id,
        title,
        thumbnail: normalizeThumbUrl(item.thumbnail),
        author: item.uploaderName ? String(item.uploaderName) : undefined,
        trackCount: typeof item.videos === 'number' && item.videos > 0 ? item.videos : undefined,
      },
    };
  }

  // Streams (songs). Piped uses `stream`; some payloads omit the field entirely.
  if (type === 'stream' || type === undefined) {
    const song = parseProviderItem(item);
    return song ? { song } : null;
  }

  return null;
}

/**
 * Normalise one Invidious search item. Invidious tags items with a `type`:
 *   - `video`    → a song
 *   - `channel`  → an artist
 *   - `playlist` → a playlist / album
 * Exported for fixture tests.
 */
export function parseInvidiousSearchItem(item: any): ParsedSearchItem | null {
  if (!item || typeof item !== 'object') return null;
  const type = item.type;

  if (type === 'channel') {
    const name = String(item.author || '').trim();
    if (!name) return null;
    const thumbs = Array.isArray(item.authorThumbnails) ? item.authorThumbnails : [];
    const bestThumb = thumbs.length ? thumbs[thumbs.length - 1]?.url : undefined;
    const subs =
      typeof item.subCount === 'number' && item.subCount >= 0
        ? `${formatCount(item.subCount)} subscribers`
        : undefined;
    return {
      artist: {
        id: item.authorId ? String(item.authorId) : `inv:${name}`,
        name,
        thumbnail: normalizeThumbUrl(bestThumb),
        subscribers: subs,
        query: `${name} top songs`,
      },
    };
  }

  if (type === 'playlist') {
    const id = item.playlistId ? String(item.playlistId) : null;
    const title = String(item.title || '').trim();
    if (!id || !title) return null;
    return {
      playlist: {
        id,
        title,
        thumbnail: normalizeThumbUrl(item.playlistThumbnail),
        author: item.author ? String(item.author) : undefined,
        trackCount: typeof item.videoCount === 'number' && item.videoCount > 0 ? item.videoCount : undefined,
      },
    };
  }

  if (type === 'video' || type === undefined) {
    const song = parseProviderItem(item);
    return song ? { song } : null;
  }

  return null;
}

/**
 * Bucket a raw provider payload into typed songs / artists / playlists, de-duping
 * by id within each bucket and honouring the per-bucket caps. Exported for tests.
 */
export function bucketItems(items: any[], provider: 'piped' | 'invidious'): SearchResults {
  const out: SearchResults = { songs: [], artists: [], playlists: [] };
  if (!Array.isArray(items)) return out;

  const seenSong = new Set<string>();
  const seenArtist = new Set<string>();
  const seenPlaylist = new Set<string>();
  const parse = provider === 'piped' ? parsePipedSearchItem : parseInvidiousSearchItem;

  for (const item of items) {
    const parsed = parse(item);
    if (!parsed) continue;

    if (parsed.song && out.songs.length < MAX_SONGS && !seenSong.has(parsed.song.id)) {
      seenSong.add(parsed.song.id);
      out.songs.push(parsed.song);
    } else if (parsed.artist && out.artists.length < MAX_ARTISTS && !seenArtist.has(parsed.artist.id)) {
      seenArtist.add(parsed.artist.id);
      out.artists.push(parsed.artist);
    } else if (parsed.playlist && out.playlists.length < MAX_PLAYLISTS && !seenPlaylist.has(parsed.playlist.id)) {
      seenPlaylist.add(parsed.playlist.id);
      out.playlists.push(parsed.playlist);
    }
  }

  return out;
}

/** True when a valid, playable Track (real video id + title). */
function isPlayableTrack(t: any): t is Track {
  return !!t && typeof t.id === 'string' && t.id.length >= 5 && typeof t.title === 'string' && !!t.title;
}

/**
 * Normalise the local dev middleware's response. It emits `{ songs, artists,
 * playlists }`; the legacy shape `{ results }` (songs only) is still accepted.
 * Every entry is validated so a malformed payload can never inject junk.
 */
function normalizeLocalResponse(data: any): SearchResults {
  const out: SearchResults = { songs: [], artists: [], playlists: [] };
  if (!data || typeof data !== 'object') return out;

  const seenSong = new Set<string>();
  const songs = Array.isArray(data.songs) ? data.songs : Array.isArray(data.results) ? data.results : [];
  for (const s of songs) {
    if (isPlayableTrack(s) && out.songs.length < MAX_SONGS && !seenSong.has(s.id)) {
      seenSong.add(s.id);
      out.songs.push({
        ...s,
        title: cleanTrackTitle(s.title),
        artist: cleanArtistName(s.artist),
        source: s.source || 'youtube',
      });
    }
  }

  if (Array.isArray(data.artists)) {
    for (const a of data.artists) {
      if (a && typeof a.id === 'string' && typeof a.name === 'string' && a.name && out.artists.length < MAX_ARTISTS) {
        out.artists.push({
          id: a.id,
          name: a.name,
          thumbnail: normalizeThumbUrl(a.thumbnail),
          subscribers: typeof a.subscribers === 'string' ? a.subscribers : undefined,
          query: typeof a.query === 'string' && a.query ? a.query : `${a.name} top songs`,
        });
      }
    }
  }

  if (Array.isArray(data.playlists)) {
    for (const p of data.playlists) {
      if (p && typeof p.id === 'string' && typeof p.title === 'string' && p.title && out.playlists.length < MAX_PLAYLISTS) {
        out.playlists.push({
          id: p.id,
          title: p.title,
          thumbnail: normalizeThumbUrl(p.thumbnail),
          author: typeof p.author === 'string' ? p.author : undefined,
          trackCount: typeof p.trackCount === 'number' && p.trackCount > 0 ? p.trackCount : undefined,
        });
      }
    }
  }

  return out;
}

function hasAnyResult(r: SearchResults): boolean {
  return r.songs.length > 0 || r.artists.length > 0 || r.playlists.length > 0;
}

/**
 * Pool of public Piped and Invidious search instances used as fallbacks
 * when the local dev middleware is unavailable (e.g. in production / Capacitor Android)
 * or when an instance is down or rate-limited.
 */
export interface ProviderEndpoint {
  url: (q: string) => string;
  provider: 'piped' | 'invidious';
}

export const PUBLIC_SEARCH_PROVIDERS: ProviderEndpoint[] = [
  { url: (q) => `https://pipedapi.adminforge.de/search?q=${q}&filter=music_songs`, provider: 'piped' },
  { url: (q) => `https://pipedapi.r4fo.com/search?q=${q}&filter=music_songs`, provider: 'piped' },
  { url: (q) => `https://api-piped.mha.fi/search?q=${q}&filter=music_songs`, provider: 'piped' },
  { url: (q) => `https://pipedapi.ducks.party/search?q=${q}&filter=music_songs`, provider: 'piped' },
  { url: (q) => `https://invidious.asir.dev/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
  { url: (q) => `https://invidious.drgns.space/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
  { url: (q) => `https://invidious.nerdvpn.de/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
  { url: (q) => `https://yt.artemislena.eu/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
  { url: (q) => `https://invidious.jing.rocks/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
  { url: (q) => `https://inv.nadeko.net/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
  { url: (q) => `https://pipedapi.kavin.rocks/search?q=${q}&filter=music_songs`, provider: 'piped' },
  { url: (q) => `https://pipedapi.leptons.xyz/search?q=${q}&filter=music_songs`, provider: 'piped' },
  { url: (q) => `https://api.piped.privacydev.net/search?q=${q}&filter=music_songs`, provider: 'piped' },
  { url: (q) => `https://iv.ggtyler.dev/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
  { url: (q) => `https://invidious.private.coffee/api/v1/search?q=${q}&type=video`, provider: 'invidious' },
];

export interface UniversalRequestOptions {
  method?: 'GET' | 'POST';
  headers?: Record<string, string>;
  data?: any;
  params?: Record<string, string>;
  signal?: AbortSignal;
  timeoutMs?: number;
}

export interface UniversalResponse<T = any> {
  ok: boolean;
  status: number;
  data: T;
}

/**
 * Universal network transport adapter.
 * On Native Android (Capacitor), uses CapacitorHttp to run requests through
 * native OkHttp sockets, bypassing WebView CORS boundaries and connection overhead.
 * On Web/Node, uses standard fetch with AbortSignal timeouts.
 */
export async function universalFetch<T = any>(
  url: string,
  options: UniversalRequestOptions = {}
): Promise<UniversalResponse<T>> {
  const { method = 'GET', headers = {}, data, params, signal, timeoutMs = 3000 } = options;

  if (Capacitor.isNativePlatform()) {
    try {
      const capRes = await CapacitorHttp.request({
        url,
        method,
        headers: {
          Accept: 'application/json',
          ...headers,
        },
        data,
        params,
        connectTimeout: timeoutMs,
        readTimeout: timeoutMs,
      });

      const ok = capRes.status >= 200 && capRes.status < 300;
      let parsedData = capRes.data;
      if (typeof parsedData === 'string') {
        try {
          parsedData = JSON.parse(parsedData);
        } catch {
          // Keep as string if not JSON
        }
      }

      return {
        ok,
        status: capRes.status,
        data: parsedData as T,
      };
    } catch {
      if (signal?.aborted) {
        throw new DOMException('The operation was aborted', 'AbortError');
      }
      return {
        ok: false,
        status: 0,
        data: null as any,
      };
    }
  }

  // Web / Dev / Node fallback:
  let fullUrl = url;
  if (params && Object.keys(params).length > 0) {
    const u = new URL(url, typeof window !== 'undefined' && window.location?.origin ? window.location.origin : 'http://localhost');
    for (const [k, v] of Object.entries(params)) {
      u.searchParams.set(k, v);
    }
    fullUrl = u.toString();
  }

  const timeoutSignal = typeof AbortSignal !== 'undefined' && AbortSignal.timeout ? AbortSignal.timeout(timeoutMs) : undefined;
  const mergedSignal = signal && timeoutSignal
    ? (typeof AbortSignal !== 'undefined' && AbortSignal.any ? AbortSignal.any([signal, timeoutSignal]) : signal)
    : signal || timeoutSignal;

  const res = await fetch(fullUrl, {
    method,
    headers: {
      Accept: 'application/json',
      ...(data ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: data ? (typeof data === 'string' ? data : JSON.stringify(data)) : undefined,
    signal: mergedSignal,
  });

  let parsed: any = null;
  if (res.ok) {
    try {
      parsed = await res.json();
    } catch {
      parsed = null;
    }
  }

  return {
    ok: res.ok,
    status: res.status,
    data: parsed as T,
  };
}

interface ProviderStats {
  successCount: number;
  failureCount: number;
  totalLatencyMs: number;
  lastUsed: number;
  quarantinedUntil: number;
}

export const providerStatsMap = new Map<string, ProviderStats>();

export function recordProviderResult(url: string, success: boolean, latencyMs: number): void {
  const baseHost = url.split('?')[0];
  const stats = providerStatsMap.get(baseHost) || {
    successCount: 0,
    failureCount: 0,
    totalLatencyMs: 0,
    lastUsed: 0,
    quarantinedUntil: 0,
  };

  stats.lastUsed = Date.now();
  if (success) {
    stats.successCount++;
    stats.totalLatencyMs += latencyMs;
    stats.quarantinedUntil = 0;
  } else {
    stats.failureCount++;
    // Quarantine failing provider for 45 seconds
    stats.quarantinedUntil = Date.now() + 45_000;
  }
  providerStatsMap.set(baseHost, stats);
}

export function getRankedProviders(providers: ProviderEndpoint[] = PUBLIC_SEARCH_PROVIDERS): ProviderEndpoint[] {
  const now = Date.now();
  return [...providers].sort((a, b) => {
    const hostA = a.url('').split('?')[0];
    const hostB = b.url('').split('?')[0];
    const sA = providerStatsMap.get(hostA);
    const sB = providerStatsMap.get(hostB);

    const isQuarantinedA = sA && sA.quarantinedUntil > now;
    const isQuarantinedB = sB && sB.quarantinedUntil > now;

    if (isQuarantinedA && !isQuarantinedB) return 1;
    if (!isQuarantinedA && isQuarantinedB) return -1;

    const avgA = sA && sA.successCount > 0 ? sA.totalLatencyMs / sA.successCount : 1000;
    const avgB = sB && sB.successCount > 0 ? sB.totalLatencyMs / sB.successCount : 1000;

    return avgA - avgB;
  });
}

export interface ProviderResponse {
  url: string;
  results: SearchResults;
  responded: boolean;
}

/** Query a single provider instance with an individual timeout and dynamic health tracking. */
export async function queryProvider(
  endpoint: ProviderEndpoint,
  cleanQ: string,
  timeoutMs = 1500
): Promise<ProviderResponse> {
  const url = endpoint.url(encodeURIComponent(cleanQ));
  const startTime = Date.now();
  try {
    const res = await universalFetch<any>(url, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      timeoutMs,
    });
    if (!res.ok || !res.data) {
      recordProviderResult(url, false, Date.now() - startTime);
      return { url, results: { songs: [], artists: [], playlists: [] }, responded: false };
    }
    const data = res.data;
    const items = data.items || data || [];
    if (!Array.isArray(items)) {
      recordProviderResult(url, false, Date.now() - startTime);
      return { url, results: { songs: [], artists: [], playlists: [] }, responded: false };
    }
    const bucketed = bucketItems(items, endpoint.provider);
    recordProviderResult(url, true, Date.now() - startTime);
    return { url, results: bucketed, responded: true };
  } catch {
    recordProviderResult(url, false, Date.now() - startTime);
    return { url, results: { songs: [], artists: [], playlists: [] }, responded: false };
  }
}

/**
 * Race a concurrent batch of search providers.
 * Returns the first provider that responds with non-empty results immediately.
 */
export async function raceProviderBatch(
  providers: ProviderEndpoint[],
  cleanQ: string,
  timeoutMs = 1500
): Promise<{ winner: SearchResults | null; attempted: string[]; anyResponded: boolean }> {
  const attempted: string[] = [];
  let anyResponded = false;

  const promises = providers.map(async (p) => {
    const res = await queryProvider(p, cleanQ, timeoutMs);
    attempted.push(res.url);
    if (res.responded) {
      anyResponded = true;
    }
    if (hasAnyResult(res.results)) {
      return res.results;
    }
    return null;
  });

  const winner = await new Promise<SearchResults | null>((resolve) => {
    let pending = promises.length;
    let resolved = false;

    if (pending === 0) {
      resolve(null);
      return;
    }

    for (const p of promises) {
      p.then((res) => {
        if (resolved) return;
        if (res && hasAnyResult(res)) {
          resolved = true;
          resolve(res);
        } else {
          pending--;
          if (pending === 0) {
            resolve(null);
          }
        }
      }).catch(() => {
        if (resolved) return;
        pending--;
        if (pending === 0) {
          resolve(null);
        }
      });
    }
  });

  return { winner, attempted, anyResponded };
}

/**
 * Typed discovery search — songs, artists, and playlists/albums.
 *
 * Resolution rules — there is no hardcoded fallback:
 *   - Empty query                         → empty buckets
 *   - A provider responded with results   → those (typed, bucketed)
 *   - A provider responded with nothing   → empty buckets (genuine "no results")
 *   - Every provider failed / timed out   → throws `SearchUnavailableError`
 *
 * Resilience strategy:
 *   1. Primary: local dev middleware (/api/youtube-search) when running under `vite dev` (timeout 2200ms).
 *   2. Fallback pool: concurrent batch racing across health-ranked Piped / Invidious instances with fast 1500ms
 *      per-instance timeouts, resolving as soon as the fastest working instance responds.
 */
export async function searchAll(query: string): Promise<SearchResults> {
  const cleanQ = query.trim();
  const empty: SearchResults = { songs: [], artists: [], playlists: [] };
  if (!cleanQ) return empty;

  const cached = searchCache.get(cleanQ);
  if (cached) return cached;

  const inFlight = getInFlightSearch(cleanQ);
  if (inFlight) return inFlight;

  const searchPromise = (async () => {
    const attempted: string[] = [];
    let anyProviderResponded = false;

    // 1. Primary (Native Android): Direct YouTube Music InnerTube via CapacitorHttp (bypasses CORS)
    if (Capacitor.isNativePlatform()) {
      try {
        const [generalRes, songsRes] = await Promise.allSettled([
          universalFetch<any>('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
              'Referer': 'https://music.youtube.com/',
              'Origin': 'https://music.youtube.com',
            },
            data: {
              context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
              query: cleanQ,
            },
            timeoutMs: 2500,
          }),
          universalFetch<any>('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
              'Referer': 'https://music.youtube.com/',
              'Origin': 'https://music.youtube.com',
            },
            data: {
              context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
              query: cleanQ,
              params: 'Eg-KAQwIARAAGAAgACgAMABqChAMEAUSAhACEAU%3D',
            },
            timeoutMs: 2500,
          }),
        ]);

        const songs: Track[] = [];
        const artists: Artist[] = [];
        const playlists: PlaylistResult[] = [];
        const seenSongIds = new Set<string>();
        const seenArtistIds = new Set<string>();
        const seenPlaylistIds = new Set<string>();

        const parseFlexItem = (flex: any) => {
          if (!flex) return;
          const col0Runs = flex.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
          const col1Runs = flex.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
          const title = col0Runs[0]?.text || '';
          const subText = col1Runs.map((r: any) => r.text).join('');
          const subParts = subText.split('•').map((s: string) => s.trim());
          const itemType = subParts[0]?.toLowerCase() || '';

          const nav = flex.navigationEndpoint;
          const browseId = nav?.browseEndpoint?.browseId || col0Runs[0]?.navigationEndpoint?.browseEndpoint?.browseId;
          const videoId =
            flex.playlistItemData?.videoId ||
            flex.doubleTapCommand?.watchEndpoint?.videoId ||
            flex.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId ||
            col0Runs[0]?.navigationEndpoint?.watchEndpoint?.videoId ||
            nav?.watchEndpoint?.videoId;

          const thumbs = flex.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || [];
          const thumbUrl = (thumbs.length > 0 ? thumbs[thumbs.length - 1]?.url : '') || (videoId ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` : '');

          if (itemType.includes('artist') || (browseId && browseId.startsWith('UC') && !videoId)) {
            if (title && !seenArtistIds.has(title.toLowerCase()) && artists.length < 12) {
              seenArtistIds.add(title.toLowerCase());
              artists.push({
                id: browseId || `yt:${title}`,
                name: title,
                thumbnail: thumbUrl || undefined,
                subscribers: subParts.find((s: string) => /subscribers|audience/i.test(s)),
                query: `${title} top songs`,
              });
            }
            return;
          }

          if (
            itemType.includes('album') ||
            itemType.includes('ep') ||
            itemType.includes('single') ||
            itemType.includes('playlist') ||
            (browseId && (browseId.startsWith('MPRE') || browseId.startsWith('VL') || browseId.startsWith('PL')))
          ) {
            if (title && !seenPlaylistIds.has(title.toLowerCase()) && playlists.length < 12) {
              seenPlaylistIds.add(title.toLowerCase());
              const author = subParts.length > 1 && !/^\d{4}$/.test(subParts[1]) ? subParts[1] : undefined;
              playlists.push({
                id: browseId || `pl:${title}`,
                title,
                thumbnail: thumbUrl || undefined,
                author,
                trackCount: undefined,
              });
            }
            return;
          }

          if (videoId && title && !seenSongIds.has(videoId) && songs.length < 30) {
            seenSongIds.add(videoId);
            let artist = 'YouTube Artist';
            if (subParts.length >= 2) {
              artist = subParts[1];
            } else if (col1Runs.length > 0) {
              const artistRun = col1Runs.find((r: any) => r.navigationEndpoint?.browseEndpoint?.browseId?.startsWith('UC'));
              if (artistRun) artist = artistRun.text;
            }
            let duration = 200;
            const durStr = subParts.find((s: string) => /^\d+:\d+$/.test(s));
            if (durStr) {
              const [m, s] = durStr.split(':').map(Number);
              duration = m * 60 + s;
            }
            songs.push({
              id: videoId,
              title: cleanTrackTitle(title),
              artist: cleanArtistName(artist),
              duration,
              thumbnail: thumbUrl || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
              source: 'youtube',
            });
          }
        };

        if (generalRes.status === 'fulfilled' && generalRes.value.ok && generalRes.value.data) {
          const generalData = generalRes.value.data;
          const sections = generalData.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];
          for (const sec of sections) {
            if (sec.musicCardShelfRenderer) {
              const card = sec.musicCardShelfRenderer;
              const title = card.title?.runs?.[0]?.text;
              const subText = card.subtitle?.runs?.map((r: any) => r.text).join('') || '';
              const subParts = subText.split('•').map((s: string) => s.trim());
              const cardType = subParts[0]?.toLowerCase() || '';
              const thumbs = card.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || [];
              const thumbUrl = thumbs.length > 0 ? thumbs[thumbs.length - 1]?.url : '';

              if (cardType.includes('artist')) {
                if (title && !seenArtistIds.has(title.toLowerCase())) {
                  seenArtistIds.add(title.toLowerCase());
                  artists.unshift({
                    id: card.onTap?.browseEndpoint?.browseId || `yt:${title}`,
                    name: title,
                    thumbnail: thumbUrl || undefined,
                    subscribers: subParts.find((s: string) => /subscribers|audience/i.test(s)),
                    query: `${title} top songs`,
                  });
                }
              } else if (cardType.includes('album') || cardType.includes('playlist')) {
                if (title && !seenPlaylistIds.has(title.toLowerCase())) {
                  seenPlaylistIds.add(title.toLowerCase());
                  playlists.unshift({
                    id: card.onTap?.browseEndpoint?.browseId || `pl:${title}`,
                    title,
                    thumbnail: thumbUrl || undefined,
                    author: subParts[1],
                    trackCount: undefined,
                  });
                }
              } else {
                const videoId =
                  card.onTap?.watchEndpoint?.videoId ||
                  card.buttons?.[0]?.buttonRenderer?.command?.watchEndpoint?.videoId ||
                  card.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.[0]?.url?.match(/\/vi\/([^\/]+)/)?.[1];
                if (videoId && title && !seenSongIds.has(videoId)) {
                  seenSongIds.add(videoId);
                  let artist = subParts[1] || 'YouTube Artist';
                  let duration = 200;
                  const durStr = subParts.find((s: string) => /^\d+:\d+$/.test(s));
                  if (durStr) {
                    const [m, s] = durStr.split(':').map(Number);
                    duration = m * 60 + s;
                  }
                  songs.unshift({
                    id: videoId,
                    title: cleanTrackTitle(title),
                    artist: cleanArtistName(artist),
                    duration,
                    thumbnail: thumbUrl || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                    source: 'youtube',
                  });
                }
              }
            }
            if (sec.musicShelfRenderer) {
              for (const item of sec.musicShelfRenderer.contents || []) {
                parseFlexItem(item.musicResponsiveListItemRenderer);
              }
            }
            if (sec.itemSectionRenderer) {
              for (const item of sec.itemSectionRenderer.contents || []) {
                parseFlexItem(item.musicResponsiveListItemRenderer);
              }
            }
          }
        }

        if (songsRes.status === 'fulfilled' && songsRes.value.ok && songsRes.value.data) {
          const songsData = songsRes.value.data;
          const sections = songsData.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];
          for (const sec of sections) {
            const shelf = sec.musicShelfRenderer;
            if (shelf) {
              for (const item of shelf.contents || []) {
                parseFlexItem(item.musicResponsiveListItemRenderer);
              }
            }
          }
        }

        const nativeResults: SearchResults = { songs, artists, playlists };
        if (hasAnyResult(nativeResults)) {
          searchCache.set(cleanQ, nativeResults);
          return nativeResults;
        }
      } catch {}
    }

    // 2. Primary (Dev / Web): local dev-server middleware (dev only).
    const localEndpoint = `/api/youtube-search?q=${encodeURIComponent(cleanQ)}`;
    attempted.push(localEndpoint);
    try {
      const res = await fetch(localEndpoint, { signal: AbortSignal.timeout(2200) });
      if (res.ok) {
        const local = normalizeLocalResponse(await res.json());
        if (hasAnyResult(local)) {
          searchCache.set(cleanQ, local);
          return local;
        }
      }
    } catch {
      // Unreachable (expected outside `vite dev`) — try the public providers.
    }

    // 3. Fallback: race health-ranked public Piped / Invidious instances in concurrent batches of 3.
    const rankedProviders = getRankedProviders();
    const BATCH_SIZE = 3;
    for (let i = 0; i < rankedProviders.length; i += BATCH_SIZE) {
      const batch = rankedProviders.slice(i, i + BATCH_SIZE);
      const { winner, attempted: batchAttempted, anyResponded } = await raceProviderBatch(batch, cleanQ, 1500);
      for (const url of batchAttempted) {
        if (!attempted.includes(url)) attempted.push(url);
      }
      if (anyResponded) {
        anyProviderResponded = true;
      }
      if (winner && hasAnyResult(winner)) {
        searchCache.set(cleanQ, winner);
        return winner;
      }
    }

    if (anyProviderResponded) {
      // At least one provider answered and none had matches: a real empty result.
      return empty;
    }

    throw new SearchUnavailableError(attempted);
  })();

  setInFlightSearch(cleanQ, searchPromise);
  return searchPromise;
}

/**
 * Song-only search. Thin wrapper over {@link searchAll} that returns just the
 * `songs` bucket, preserving the historic `Promise<Track[]>` contract for callers
 * that only play tracks (Header typeahead, Home quick picks). It inherits
 * `searchAll`'s resolution rules verbatim: `[]` for an empty query or a genuine
 * empty result, and `SearchUnavailableError` when every provider is unreachable.
 */
export async function searchYouTube(query: string): Promise<Track[]> {
  return (await searchAll(query)).songs;
}



