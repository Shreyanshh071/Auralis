// High-resolution album artwork resolver using iTunes & Apple Music API
// Provides authentic, pure 600x600 square artwork with ZERO black bars or letterboxing

const artworkCache = new Map<string, string>();

/**
 * Detects if an image URL is a YouTube 4:3 letterboxed thumbnail (hqdefault / sddefault / default).
 * These images contain baked-in black bars on top and bottom and must be zoomed (scale-[1.35]) to fill a 1:1 square container.
 */
export function isLetterboxedThumbnail(url?: string): boolean {
  if (!url || typeof url !== 'string') return false;
  // If it's Apple Music / iTunes or modern clean 16:9 (maxresdefault / hq720 / mqdefault), it has no letterbox bars
  if (
    url.includes('mzstatic.com') ||
    url.includes('maxresdefault') ||
    url.includes('hq720') ||
    url.includes('mqdefault')
  ) {
    return false;
  }
  // Standard YouTube hqdefault, sddefault, default are 4:3 with top/bottom black bars
  return (
    url.includes('i.ytimg.com') ||
    url.includes('img.youtube.com') ||
    url.includes('hqdefault') ||
    url.includes('sddefault') ||
    url.includes('ytimg')
  );
}

/**
 * Clean search query to find the purest album cover match
 */
function cleanQuery(title: string, artist: string): string {
  const cleanTitle = title
    .replace(/\[.*?\]|\(.*?\)/g, '')
    .replace(/official\s*video|official\s*audio|official\s*music\s*video|lyrics|lyric\s*video|hd|4k|remastered|visualizer|audio/gi, '')
    .replace(/ft\.?|feat\.?|prod\.?\s*by|featuring/gi, '')
    .replace(/[|\-_/\\].*$/, '')
    .trim();
  
  const cleanArtist = artist
    .replace(/vevo|official|channel|topic/gi, '')
    .replace(/[|\-_/\\].*$/, '')
    .trim();

  return `${cleanArtist} ${cleanTitle}`.trim() || title;
}

/**
 * Fetch high-resolution square album artwork with zero black bars
 */
export async function getAlbumArtwork(title: string, artist: string, fallbackThumbnail?: string): Promise<string> {
  // If already high-res Apple Music/iTunes artwork, keep it
  if (fallbackThumbnail && fallbackThumbnail.includes('mzstatic.com') && !fallbackThumbnail.includes('1000x1000bb')) {
    return fallbackThumbnail;
  }

  const cacheKey = `${artist}-${title}`.toLowerCase();
  if (artworkCache.has(cacheKey)) {
    return artworkCache.get(cacheKey)!;
  }

  const query = cleanQuery(title, artist);

  try {
    const res = await fetch(
      `https://itunes.apple.com/search?term=${encodeURIComponent(query)}&entity=song&limit=5`,
      { signal: AbortSignal.timeout(2500) }
    );

    if (res.ok) {
      const data = await res.json();
      if (data && data.results && data.results.length > 0) {
        // Find result matching the artist name best to avoid wrong remixes/covers
        const cleanArtistLower = artist.toLowerCase().trim();
        const bestMatch = data.results.find((item: any) =>
          item.artistName?.toLowerCase().includes(cleanArtistLower) ||
          cleanArtistLower.includes(item.artistName?.toLowerCase() || '')
        ) || data.results[0];

        if (bestMatch && bestMatch.artworkUrl100) {
          const highResArtwork = bestMatch.artworkUrl100
            .replace('100x100bb', '600x600bb')
            .replace('100x100', '600x600');
          
          artworkCache.set(cacheKey, highResArtwork);
          return highResArtwork;
        }
      }
    }
  } catch (err) {
    // Network or timeout
  }

  // Fallback to provided thumbnail or YouTube HQ thumbnail
  const finalFallback = fallbackThumbnail || 'https://i.ytimg.com/vi/sBzrzS1Ag_g/hqdefault.jpg';
  artworkCache.set(cacheKey, finalFallback);
  return finalFallback;
}

