// High-resolution album artwork resolver using iTunes & Apple Music API
// Provides authentic, pure 600x600 square artwork with ZERO black bars or letterboxing

const artworkCache = new Map<string, string>();

/**
 * Clean search query to find the purest album cover match
 */
function cleanQuery(title: string, artist: string): string {
  const cleanTitle = title
    .replace(/\[.*?\]|\(.*?\)/g, '')
    .replace(/official\s*video|official\s*audio|lyrics|hd|4k|remastered/gi, '')
    .replace(/ft\.?|feat\.?/gi, '')
    .trim();
  
  const cleanArtist = artist.replace(/vevo|official/gi, '').trim();
  return `${cleanArtist} ${cleanTitle}`.trim();
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
      { signal: AbortSignal.timeout(2000) }
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

