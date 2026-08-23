import type { Artist, PlaylistResult, SearchResults, Track } from '../types/music';

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

// Memory cache for search responses. Only non-empty result sets are cached, so a
// zero-result or failed query is always retried rather than remembered.
const resultsCache = new Map<string, SearchResults>();

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
    artist = cleanArtistName(parts[0].trim());
    title = parts.slice(1).join(' - ').trim();
  }

  title = cleanTrackTitle(title);

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
  { url: (q) => `https://pipedapi.kavin.rocks/search?q=${q}&filter=all`, provider: 'piped' },
  { url: (q) => `https://pipedapi.leptons.xyz/search?q=${q}&filter=all`, provider: 'piped' },
  { url: (q) => `https://api.piped.privacydev.net/search?q=${q}&filter=all`, provider: 'piped' },
  { url: (q) => `https://pipedapi.tokhmi.xyz/search?q=${q}&filter=all`, provider: 'piped' },
  { url: (q) => `https://invidious.jing.rocks/api/v1/search?q=${q}&type=all`, provider: 'invidious' },
  { url: (q) => `https://inv.nadeko.net/api/v1/search?q=${q}&type=all`, provider: 'invidious' },
  { url: (q) => `https://invidious.nerdvpn.de/api/v1/search?q=${q}&type=all`, provider: 'invidious' },
  { url: (q) => `https://iv.ggtyler.dev/api/v1/search?q=${q}&type=all`, provider: 'invidious' },
  { url: (q) => `https://invidious.private.coffee/api/v1/search?q=${q}&type=all`, provider: 'invidious' },
];

export interface ProviderResponse {
  url: string;
  results: SearchResults;
  responded: boolean;
}

/** Query a single provider instance with an individual timeout and honest error handling. */
export async function queryProvider(
  endpoint: ProviderEndpoint,
  cleanQ: string,
  timeoutMs = 4500
): Promise<ProviderResponse> {
  const url = endpoint.url(encodeURIComponent(cleanQ));
  try {
    const res = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!res.ok) {
      return { url, results: { songs: [], artists: [], playlists: [] }, responded: false };
    }
    const data = await res.json();
    const items = data.items || data || [];
    if (!Array.isArray(items)) {
      return { url, results: { songs: [], artists: [], playlists: [] }, responded: false };
    }
    const bucketed = bucketItems(items, endpoint.provider);
    return { url, results: bucketed, responded: true };
  } catch {
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
  timeoutMs = 4500
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
 *   1. Primary: local dev middleware (/api/youtube-search) when running under `vite dev` (timeout 2500ms).
 *   2. Fallback pool: concurrent batch racing across trusted Piped / Invidious instances with per-instance
 *      timeouts, resolving as soon as the fastest working instance responds.
 */
export async function searchAll(query: string): Promise<SearchResults> {
  const cleanQ = query.trim();
  const empty: SearchResults = { songs: [], artists: [], playlists: [] };
  if (!cleanQ) return empty;

  const cacheKey = cleanQ.toLowerCase();
  const cached = resultsCache.get(cacheKey);
  if (cached) return cached;

  const attempted: string[] = [];
  let anyProviderResponded = false;

  // 1. Primary: local dev-server middleware (dev only — see note above). It returns
  //    typed buckets; an empty response is not authoritative (the middleware answers
  //    200 + empty on its own internal errors), so we fall through when it is empty.
  const localEndpoint = `/api/youtube-search?q=${encodeURIComponent(cleanQ)}`;
  attempted.push(localEndpoint);
  try {
    const res = await fetch(localEndpoint, { signal: AbortSignal.timeout(2500) });
    if (res.ok) {
      const local = normalizeLocalResponse(await res.json());
      if (hasAnyResult(local)) {
        resultsCache.set(cacheKey, local);
        return local;
      }
    }
  } catch {
    // Unreachable (expected outside `vite dev`) — try the public providers.
  }

  // 2. Fallback: race public Piped / Invidious instances in concurrent batches of 3.
  const BATCH_SIZE = 3;
  for (let i = 0; i < PUBLIC_SEARCH_PROVIDERS.length; i += BATCH_SIZE) {
    const batch = PUBLIC_SEARCH_PROVIDERS.slice(i, i + BATCH_SIZE);
    const { winner, attempted: batchAttempted, anyResponded } = await raceProviderBatch(batch, cleanQ, 4500);
    for (const url of batchAttempted) {
      if (!attempted.includes(url)) attempted.push(url);
    }
    if (anyResponded) {
      anyProviderResponded = true;
    }
    if (winner && hasAnyResult(winner)) {
      resultsCache.set(cacheKey, winner);
      return winner;
    }
  }

  if (anyProviderResponded) {
    // At least one provider answered and none had matches: a real empty result.
    return empty;
  }

  throw new SearchUnavailableError(attempted);
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



