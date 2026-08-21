import type { Playlist, Track } from '../types/music';
import { cleanTitle } from './lyrics';

/**
 * Extract playlist ID from various YouTube & YouTube Music URL formats
 */
export function extractPlaylistId(input: string): string | null {
  const trimmed = input.trim();

  // If already clean playlist ID (starts with PL, OLAK, RD, etc.)
  if (/^[A-Za-z0-9_-]{12,}$/.test(trimmed) && (trimmed.startsWith('PL') || trimmed.startsWith('OL') || trimmed.startsWith('RD') || trimmed.startsWith('FL') || trimmed.startsWith('LL'))) {
    return trimmed;
  }

  // URL extraction
  try {
    const url = new URL(trimmed.startsWith('http') ? trimmed : `https://${trimmed}`);
    const listParam = url.searchParams.get('list');
    if (listParam) return listParam;

    // Check pathname for /playlist/ID or /channel/ID
    const match = url.pathname.match(/playlist\/([A-Za-z0-9_-]+)/);
    if (match) return match[1];
  } catch {}

  // Regex fallback for list= parameter in text
  const match = trimmed.match(/[?&]list=([A-Za-z0-9_-]+)/);
  if (match) return match[1];

  return null;
}

/**
 * Fetch playlist metadata and all tracks using multiple Piped & Invidious instances
 */
export async function importYouTubePlaylist(playlistInput: string): Promise<Playlist | null> {
  const playlistId = extractPlaylistId(playlistInput);
  if (!playlistId) return null;

  const instances = [
    `https://pipedapi.kavin.rocks/playlists/${playlistId}`,
    `https://api.piped.privacydev.net/playlists/${playlistId}`,
    `https://invidious.nerdvpn.de/api/v1/playlists/${playlistId}`,
    `https://inv.nadeko.net/api/v1/playlists/${playlistId}`,
    `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://pipedapi.kavin.rocks/playlists/${playlistId}`)}`,
  ];

  for (const endpoint of instances) {
    try {
      const res = await fetch(endpoint, {
        headers: { 'Accept': 'application/json' },
      });

      if (res.ok) {
        const data = await res.json();
        const rawTracks = data.relatedStreams || data.videos || [];

        if (rawTracks.length > 0) {
          const tracks: Track[] = rawTracks.map((item: any) => {
            const rawTitle = item.title || 'Untitled Track';
            const uploader = item.uploaderName || item.author || 'YouTube Artist';
            const clean = cleanTitle(rawTitle);

            let artist = uploader;
            let title = clean || rawTitle;

            if (rawTitle.includes(' - ')) {
              const parts = rawTitle.split(' - ');
              artist = parts[0].trim();
              title = cleanTitle(parts.slice(1).join(' - ')) || parts[1].trim();
            }

            const videoId = item.url ? item.url.replace('/watch?v=', '') : item.videoId || item.id;
            const duration = item.duration || (item.lengthSeconds ? Number(item.lengthSeconds) : 210);
            const thumbnail = item.thumbnail || (item.videoThumbnails && item.videoThumbnails[0]?.url) || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;

            return {
              id: videoId,
              title,
              artist,
              album: data.name || data.title || 'YouTube Playlist',
              duration: typeof duration === 'number' ? duration : 210,
              thumbnail,
              source: 'youtube',
            };
          });

          return {
            id: `yt-pl-${playlistId}-${Date.now()}`,
            title: data.name || data.title || `YouTube Playlist`,
            description: data.description || `Imported YouTube playlist (${tracks.length} songs)`,
            cover: tracks[0]?.thumbnail,
            tracks,
            createdAt: Date.now(),
            isCustom: true,
          };
        }
      }
    } catch {
      // Continue to next instance
    }
  }

  // All instances failed — return null honestly
  return null;
}
