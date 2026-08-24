import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

function youtubeSearchPlugin() {
  // Server-side in-memory LRU query cache (max 500 entries, 15-min TTL)
  const searchCache = new Map<string, { data: any; timestamp: number }>();
  const suggestCache = new Map<string, { data: string[]; timestamp: number }>();

  const pruneCache = (cache: Map<string, any>, max = 500) => {
    if (cache.size > max) {
      const oldestKey = cache.keys().next().value;
      if (oldestKey) cache.delete(oldestKey);
    }
  };

  return {
    name: 'youtube-search-middleware',
    configureServer(server: any) {
      // 1. Fast Autocomplete Suggestion Proxy
      server.middlewares.use('/api/youtube-suggest', async (req: any, res: any) => {
        let q = '';
        try {
          const url = new URL(req.url || '', `http://${req.headers.host}`);
          q = url.searchParams.get('q') || '';
          if (!q.trim()) {
            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify([]));
            return;
          }

          const suggestKey = q.trim().toLowerCase();
          const cached = suggestCache.get(suggestKey);
          if (cached && Date.now() - cached.timestamp < 10 * 60 * 1000) {
            res.setHeader('Content-Type', 'application/json');
            res.setHeader('X-Cache', 'HIT');
            res.end(JSON.stringify(cached.data));
            return;
          }

          const NON_MUSIC_TERMS = /\b(game|games|gameplay|gaming|minecraft|roblox|gta|vlog|vlogs|challenge|reaction|reactions|funny moments|walkthrough|episode|episodes|fears to fathom|subnautica|god of war|granny|horror|horror game|shorts|stream|streamer|live stream|unboxing|prank|meme)\b/i;

          let rawList: string[] = [];

          // 1. Primary: YouTube Music get_search_suggestions endpoint
          try {
            const ytmRes = await fetch('https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
                'Referer': 'https://music.youtube.com/',
                'Origin': 'https://music.youtube.com',
              },
              body: JSON.stringify({
                context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
                input: q.trim(),
              }),
              signal: AbortSignal.timeout(1600),
            });

            if (ytmRes.ok) {
              const data = (await ytmRes.json()) as any;
              const contents = data.contents?.[0]?.searchSuggestionsSectionRenderer?.contents || [];
              for (const item of contents) {
                const s = item.searchSuggestionRenderer?.suggestion?.runs?.map((r: any) => r.text).join('');
                if (s && typeof s === 'string') rawList.push(s.trim());
              }
            }
          } catch {}

          // 2. Fallback: Google Suggest with strict music filtering
          if (rawList.length === 0) {
            try {
              const suggestUrl = `https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${encodeURIComponent(q.trim())}`;
              const suggestRes = await fetch(suggestUrl, { signal: AbortSignal.timeout(1500) });
              if (suggestRes.ok) {
                const data = (await suggestRes.json()) as any;
                if (Array.isArray(data) && Array.isArray(data[1])) {
                  rawList = data[1];
                }
              }
            } catch {}
          }

          // Strict Music Filter & Deduplication
          const qClean = q.trim().toLowerCase();
          const filtered = rawList.filter((s) => {
            if (!s || typeof s !== 'string') return false;
            if (NON_MUSIC_TERMS.test(s)) return false;
            return true;
          });

          const results: string[] = [q.trim()];
          for (const s of filtered) {
            if (s.toLowerCase() !== qClean && !results.includes(s)) {
              results.push(s);
            }
          }

          // If gaming/vlog terms pruned the suggestions, supplement with music-specific completions
          if (results.length < 3) {
            const musicFallbacks = [`${q.trim()} songs`, `${q.trim()} music`, `${q.trim()} playlist`];
            for (const fb of musicFallbacks) {
              if (!results.includes(fb) && results.length < 6) {
                results.push(fb);
              }
            }
          }

          const finalList = results.slice(0, 6);
          pruneCache(suggestCache, 500);
          suggestCache.set(suggestKey, { data: finalList, timestamp: Date.now() });

          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify(finalList));
          return;
        } catch {
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify([q.trim()]));
        }
      });

      // 2. Parallelized Search Proxy with In-Memory Caching
      server.middlewares.use('/api/youtube-search', async (req: any, res: any) => {
        try {
          const url = new URL(req.url || '', `http://${req.headers.host}`);
          const q = url.searchParams.get('q') || '';
          if (!q.trim()) {
            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify({ songs: [], artists: [], playlists: [], results: [] }));
            return;
          }

          const cacheKey = q.trim().toLowerCase();
          const cached = searchCache.get(cacheKey);
          if (cached && Date.now() - cached.timestamp < 15 * 60 * 1000) {
            res.setHeader('Content-Type', 'application/json');
            res.setHeader('X-Cache', 'HIT');
            res.end(JSON.stringify(cached.data));
            return;
          }

          // Thumbnail urls from InnerTube are often protocol-relative (`//host/…`).
          const pickThumb = (thumbs: any): string | undefined => {
            if (!Array.isArray(thumbs) || !thumbs.length) return undefined;
            let u = thumbs[thumbs.length - 1]?.url;
            if (typeof u !== 'string' || !u) return undefined;
            if (u.startsWith('//')) u = `https:${u}`;
            return u;
          };

          const songs: any[] = [];
          const artists: any[] = [];
          const playlists: any[] = [];
          const seenSongIds = new Set<string>();
          const seenArtistIds = new Set<string>();
          const seenPlaylistIds = new Set<string>();

          // Execute ONLY YouTube Music (WEB_REMIX): General YTM + Official YTM Songs in PARALLEL
          const [generalSettled, songsFilterSettled] = await Promise.allSettled([
            fetch('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
                'Referer': 'https://music.youtube.com/',
                'Origin': 'https://music.youtube.com',
              },
              body: JSON.stringify({
                context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
                query: q,
              }),
              signal: AbortSignal.timeout(2200),
            }),
            fetch('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
                'Referer': 'https://music.youtube.com/',
                'Origin': 'https://music.youtube.com',
              },
              body: JSON.stringify({
                context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
                query: q,
                params: 'Eg-KAQwIARAAGAAgACgAMABqChAMEAUSAhACEAU%3D', // YTM Songs filter
              }),
              signal: AbortSignal.timeout(2200),
            }),
          ]);

          function parseFlexItem(flex: any) {
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
            const thumbUrl = pickThumb(thumbs) || (videoId ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` : '');

            // 1. Artists
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

            // 2. Albums & Playlists
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

            // 3. Songs
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
                title,
                artist,
                duration,
                thumbnail: thumbUrl || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                source: 'youtube',
              });
            }
          }

          // Parse General YTM Results (Top result card, Artists, Albums, Playlists)
          if (generalSettled.status === 'fulfilled' && generalSettled.value.ok) {
            try {
              const generalData = (await generalSettled.value.json()) as any;
              const sections = generalData.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];

              for (const sec of sections) {
                // Top Card
                if (sec.musicCardShelfRenderer) {
                  const card = sec.musicCardShelfRenderer;
                  const title = card.title?.runs?.[0]?.text;
                  const subText = card.subtitle?.runs?.map((r: any) => r.text).join('') || '';
                  const subParts = subText.split('•').map((s: string) => s.trim());
                  const cardType = subParts[0]?.toLowerCase() || '';

                  const thumbs = card.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || [];
                  const thumbUrl = pickThumb(thumbs) || '';

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
                        title,
                        artist,
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
            } catch {}
          }

          // Parse YTM Official Songs Filter (pure song list)
          if (songsFilterSettled.status === 'fulfilled' && songsFilterSettled.value.ok) {
            try {
              const songsData = (await songsFilterSettled.value.json()) as any;
              const sections = songsData.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];
              for (const sec of sections) {
                const shelf = sec.musicShelfRenderer;
                if (shelf) {
                  for (const item of shelf.contents || []) {
                    parseFlexItem(item.musicResponsiveListItemRenderer);
                  }
                }
              }
            } catch {}
          }

          const responsePayload = { songs, artists, playlists, results: songs };
          pruneCache(searchCache, 500);
          searchCache.set(cacheKey, { data: responsePayload, timestamp: Date.now() });

          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify(responsePayload));
        } catch {
          res.statusCode = 200;
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify({ songs: [], artists: [], playlists: [], results: [] }));
        }
      });
    },
  };
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    youtubeSearchPlugin(),
  ],
});


