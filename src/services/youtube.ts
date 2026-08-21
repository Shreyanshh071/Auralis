import type { Track } from '../types/music';

/**
 * Curated high-fidelity catalog with authentic, reliable album artwork
 */
export const CURATED_TRACKS: Track[] = [
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

export const GENRES = [
  { id: 'pop', name: 'Pop Hits', query: 'top pop hits music', gradient: 'from-pink-500/20 to-rose-600/20' },
  { id: 'hiphop', name: 'Hip-Hop / Rap', query: 'hip hop top rap hits', gradient: 'from-amber-500/20 to-orange-600/20' },
  { id: 'indie', name: 'Indie & Alt', query: 'indie alternative rock music', gradient: 'from-emerald-500/20 to-teal-600/20' },
  { id: 'rock', name: 'Classic Rock', query: 'classic rock greatest hits', gradient: 'from-red-500/20 to-purple-600/20' },
  { id: 'electronic', name: 'Electronic / EDM', query: 'electronic dance edm music', gradient: 'from-blue-500/20 to-cyan-600/20' },
  { id: 'rnb', name: 'R&B / Soul', query: 'r&b soul smooth hits', gradient: 'from-purple-500/20 to-indigo-600/20' },
];

// Memory cache for instant instant search responses
const searchCache = new Map<string, Track[]>();

/**
 * Search YouTube using fast internal API with fallback to iTunes & Public Proxies
 */
export async function searchYouTube(query: string): Promise<Track[]> {
  const cleanQ = query.trim();
  if (!cleanQ) return CURATED_TRACKS.slice(0, 8);

  const cacheKey = cleanQ.toLowerCase();
  if (searchCache.has(cacheKey)) {
    return searchCache.get(cacheKey)!;
  }

  // 1. Primary: Fast Internal Vite Server API (no CORS, sub-200ms)
  try {
    const res = await fetch(`/api/youtube-search?q=${encodeURIComponent(cleanQ)}`, {
      signal: AbortSignal.timeout(3500),
    });
    if (res.ok) {
      const data = await res.json();
      if (data && Array.isArray(data.results) && data.results.length > 0) {
        searchCache.set(cacheKey, data.results);
        return data.results;
      }
    }
  } catch {}

  // 2. Fallback: Fast public Invidious instances with strict 1500ms timeout
  const instances = [
    `https://pipedapi.leptons.xyz/search?q=${encodeURIComponent(cleanQ)}&filter=music_songs`,
    `https://invidious.jing.rocks/api/v1/search?q=${encodeURIComponent(cleanQ)}&type=video`,
  ];

  for (const endpoint of instances) {
    try {
      const res = await fetch(endpoint, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(1500),
      });

      if (res.ok) {
        const data = await res.json();
        const items = data.items || data || [];

        if (Array.isArray(items) && items.length > 0) {
          const results: Track[] = [];
          for (const item of items) {
            const videoId = item.url ? item.url.replace('/watch?v=', '') : item.videoId || item.id;
            if (!videoId || typeof videoId !== 'string' || videoId.length < 5) continue;

            const rawTitle = item.title || 'Untitled Track';
            const uploader = item.uploaderName || item.author || 'YouTube Artist';

            let artist = uploader;
            let title = rawTitle;

            if (rawTitle.includes(' - ')) {
              const parts = rawTitle.split(' - ');
              artist = parts[0].trim();
              title = parts.slice(1).join(' - ').trim();
            }

            const duration = item.duration || (item.lengthSeconds ? Number(item.lengthSeconds) : 200);
            const thumbnail = `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;

            results.push({
              id: videoId,
              title,
              artist,
              duration: typeof duration === 'number' ? duration : 200,
              thumbnail,
              source: 'youtube',
            });

            if (results.length >= 15) break;
          }

          if (results.length > 0) {
            searchCache.set(cacheKey, results);
            return results;
          }
        }
      }
    } catch {}
  }

  // 3. Fallback to matching curated catalog
  const q = cleanQ.toLowerCase();
  const matched = CURATED_TRACKS.filter(
    (t) => t.title.toLowerCase().includes(q) || t.artist.toLowerCase().includes(q)
  );

  const fallback = matched.length > 0 ? matched : CURATED_TRACKS.slice(0, 8);
  searchCache.set(cacheKey, fallback);
  return fallback;
}

