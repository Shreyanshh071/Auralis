import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

function youtubeSearchPlugin() {
  return {
    name: 'youtube-search-middleware',
    configureServer(server: any) {
      server.middlewares.use('/api/youtube-search', async (req: any, res: any) => {
        try {
          const url = new URL(req.url || '', `http://${req.headers.host}`);
          const q = url.searchParams.get('q') || '';
          if (!q.trim()) {
            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify({ songs: [], artists: [], playlists: [], results: [] }));
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

          // 1. First priority: Query YouTube Music (WEB_REMIX) for official release & Top Result Card
          try {
            const musicResponse = await fetch('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
                'Referer': 'https://music.youtube.com/',
                'Origin': 'https://music.youtube.com'
              },
              body: JSON.stringify({
                context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
                query: q
              })
            });

            if (musicResponse.ok) {
              const musicData = (await musicResponse.json()) as any;
              const sections = musicData.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];

              for (const sec of sections) {
                // Official Top Result Card (the single most authoritative song match)
                const card = sec.musicCardShelfRenderer;
                if (card) {
                  const title = card.title?.runs?.[0]?.text;
                  const subText = card.subtitle?.runs?.map((r: any) => r.text).join('') || '';
                  const videoId =
                    card.onTap?.watchEndpoint?.videoId ||
                    card.buttons?.[0]?.buttonRenderer?.command?.watchEndpoint?.videoId ||
                    card.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.[0]?.url?.match(/\/vi\/([^\/]+)/)?.[1];

                  let artist = 'YouTube Artist';
                  let duration = 200;
                  const subParts = subText.split('•').map((s: string) => s.trim());
                  if (subParts.length >= 2) {
                    artist = subParts[1];
                    const durStr = subParts.find((s: string) => /^\d+:\d+$/.test(s));
                    if (durStr) {
                      const [m, s] = durStr.split(':').map(Number);
                      duration = m * 60 + s;
                    }
                  }

                  if (videoId && title && !seenSongIds.has(videoId)) {
                    seenSongIds.add(videoId);
                    songs.push({
                      id: videoId,
                      title,
                      artist,
                      duration,
                      thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                      source: 'youtube'
                    });
                  }
                }

                // Official song shelf
                const shelf = sec.musicShelfRenderer;
                if (shelf) {
                  for (const item of shelf.contents || []) {
                    const flex = item.musicResponsiveListItemRenderer;
                    if (!flex) continue;

                    const trackTitle = flex.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text;
                    const col1Runs = flex.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
                    const artist = col1Runs[0]?.text || 'YouTube Artist';
                    const videoId =
                      flex.playlistItemData?.videoId ||
                      flex.doubleTapCommand?.watchEndpoint?.videoId ||
                      flex.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId;

                    let duration = 200;
                    const durRun = col1Runs.find((r: any) => /^\d+:\d+$/.test(r.text?.trim() || ''));
                    if (durRun) {
                      const [m, s] = durRun.text.trim().split(':').map(Number);
                      duration = m * 60 + s;
                    }

                    if (videoId && trackTitle && !seenSongIds.has(videoId) && songs.length < 25) {
                      seenSongIds.add(videoId);
                      songs.push({
                        id: videoId,
                        title: trackTitle,
                        artist,
                        duration,
                        thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                        source: 'youtube'
                      });
                    }
                  }
                }
              }
            }
          } catch {}

          // 2. Query standard YouTube for additional tracks, artists, and playlists
          const response = await fetch('https://www.youtube.com/youtubei/v1/search?prettyPrint=false', {
            method: 'POST',
            headers: { 
              'Content-Type': 'application/json',
              'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36'
            },
            body: JSON.stringify({
              context: {
                client: {
                  clientName: 'WEB',
                  clientVersion: '2.20240101.00.00',
                  hl: 'en',
                  gl: 'US',
                },
              },
              query: q,
            }),
          });

          if (response.ok) {
            const data = (await response.json()) as any;
            const contents = data.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents;
            const itemSection = contents?.find((c: any) => c.itemSectionRenderer)?.itemSectionRenderer?.contents || [];

            const webSongs: any[] = [];

            for (const item of itemSection) {
              const vr = item.videoRenderer;
              if (vr && vr.videoId && !seenSongIds.has(vr.videoId)) {
                const rawTitle = vr.title?.runs?.[0]?.text || 'Untitled Track';
                const owner = vr.ownerText?.runs?.[0]?.text || 'YouTube Artist';
                const cleanArtist = (s: string) =>
                  s.replace(/\s*-\s*Topic$/i, '').replace(/VEVO$/i, '').trim();
                let artist = cleanArtist(owner) || owner;
                let title = rawTitle;
                if (rawTitle.includes(' - ')) {
                  const parts = rawTitle.split(' - ');
                  const p0 = parts[0].trim();
                  const p1 = parts.slice(1).join(' - ').trim();

                  const isP1Descriptor =
                    /^(?:full\s*(?:video|audio|song|track)?|official\s*(?:video|music\s*video|audio|lyrics?|lyric\s*video)?|lyrical|audio\s*song|video\s*song)/i.test(p1) ||
                    p1.includes('|');

                  if (isP1Descriptor) {
                    title = p0.replace(/\s*[\(\[]\s*(?:official|video|audio|lyrics?|4k|hd|hq).*/gi, '').trim() || p0;
                    const segs = p1.split(/\s*[-–—:|~•]+\s*/).map((s: string) => s.trim()).filter((s: string) => s && !/^(?:full|official|video|audio|song|lyrical|4k|8k|hd|hq)/i.test(s));
                    const foundArtist = segs.find((s: string) => !/tseries|sonymusic|zeemusic|saregama|yrf|tipsofficial|spinninrecords|monstercat|vevo|channel|records|music/i.test(s.toLowerCase().replace(/\s+/g, '')));
                    artist = foundArtist ? cleanArtist(foundArtist) : (cleanArtist(owner) || owner);
                  } else {
                    artist = cleanArtist(p0) || artist;
                    title = p1 || rawTitle;
                  }
                }
                const lengthText = vr.lengthText?.simpleText || '3:30';
                const parts = lengthText.split(':').map(Number);
                const duration = parts.length === 2
                  ? parts[0] * 60 + parts[1]
                  : (parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : 200);

                const isTopic = / - Topic$/i.test(owner);
                const isVevo = /VEVO$/i.test(owner);

                webSongs.push({
                  id: vr.videoId,
                  title,
                  artist,
                  duration,
                  thumbnail: `https://i.ytimg.com/vi/${vr.videoId}/hqdefault.jpg`,
                  source: 'youtube',
                  isOfficial: isTopic || isVevo || owner.toLowerCase().includes(artist.toLowerCase()),
                  rawTitle
                });
                continue;
              }

              // Channels → artists.
              const cr = item.channelRenderer;
              if (cr && cr.channelId && artists.length < 12) {
                const name = cr.title?.simpleText || cr.title?.runs?.[0]?.text;
                if (name) {
                  artists.push({
                    id: cr.channelId,
                    name,
                    thumbnail: pickThumb(cr.thumbnail?.thumbnails),
                    subscribers: cr.subscriberCountText?.simpleText || cr.videoCountText?.simpleText || undefined,
                    query: `${name} top songs`,
                  });
                }
                continue;
              }

              // Playlists
              const pr = item.playlistRenderer;
              if (pr && pr.playlistId && playlists.length < 12) {
                const title = pr.title?.simpleText || pr.title?.runs?.[0]?.text;
                if (title) {
                  const rawCount = pr.videoCount ?? pr.videoCountText?.runs?.[0]?.text;
                  const trackCount = rawCount != null ? Number(String(rawCount).replace(/[^\d]/g, '')) : undefined;
                  playlists.push({
                    id: pr.playlistId,
                    title,
                    thumbnail: pickThumb(pr.thumbnail?.thumbnails) || pickThumb(pr.thumbnails?.[0]?.thumbnails),
                    author: pr.shortBylineText?.runs?.[0]?.text || pr.longBylineText?.runs?.[0]?.text || undefined,
                    trackCount: Number.isFinite(trackCount) && (trackCount as number) > 0 ? trackCount : undefined,
                  });
                }
                continue;
              }
            }

            // Sort webSongs so official/topic releases come before covers/slowed edits
            webSongs.sort((a, b) => {
              let sA = a.isOfficial ? 30 : 0;
              let sB = b.isOfficial ? 30 : 0;
              if (/\b(slowed|reverb|sped up|nightcore)\b/i.test(a.rawTitle)) sA -= 40;
              if (/\b(slowed|reverb|sped up|nightcore)\b/i.test(b.rawTitle)) sB -= 40;
              if (/\b(cover|karaoke|live at|live in)\b/i.test(a.rawTitle)) sA -= 30;
              if (/\b(cover|karaoke|live at|live in)\b/i.test(b.rawTitle)) sB -= 30;
              return sB - sA;
            });

            for (const s of webSongs) {
              if (songs.length < 25 && !seenSongIds.has(s.id)) {
                seenSongIds.add(s.id);
                songs.push({
                  id: s.id,
                  title: s.title,
                  artist: s.artist,
                  duration: s.duration,
                  thumbnail: s.thumbnail,
                  source: s.source
                });
              }
            }
            // Modern WEB search increasingly returns "viewModel" formats instead of
            // the classic renderers above: the artist as `officialCardViewModel` and
            // playlists/albums as `lockupViewModel`. Extract those too, so dev returns
            // the same typed shape a production Piped/Invidious instance would.
            if (artists.length === 0) {
              const oc = itemSection.find((x: any) => x.officialCardViewModel)?.officialCardViewModel;
              const name = oc?.header?.pageHeaderViewModel?.title?.dynamicTextViewModel?.text?.content;
              if (oc && typeof name === 'string' && name.trim()) {
                // The artist's channel id is the first UC… browseId inside the card.
                let channelId: string | null = null;
                (function findUC(o: any) {
                  if (!o || typeof o !== 'object' || channelId) return;
                  const b = o.browseEndpoint?.browseId || o.browseId;
                  if (typeof b === 'string' && /^UC[\w-]{20,}$/.test(b)) { channelId = b; return; }
                  for (const k in o) { if (channelId) break; findUC(o[k]); }
                })(oc);
                artists.push({
                  id: channelId || `yt:${name.trim()}`,
                  name: name.trim(),
                  thumbnail: undefined,
                  subscribers: undefined,
                  query: `${name.trim()} top songs`,
                });
              }
            }

            if (playlists.length < 12) {
              const lockups: any[] = [];
              (function collect(node: any) {
                if (!node || typeof node !== 'object') return;
                if (node.lockupViewModel) lockups.push(node.lockupViewModel);
                for (const k in node) collect(node[k]);
              })(data.contents?.twoColumnSearchResultsRenderer?.primaryContents);

              const seenPl = new Set(playlists.map((p) => p.id));
              for (const lk of lockups) {
                if (playlists.length >= 12) break;
                const ct = lk.contentType || '';
                if (!/PLAYLIST|ALBUM/.test(ct)) continue; // skip VIDEO lockups
                const id = lk.contentId;
                const title = lk.metadata?.lockupMetadataViewModel?.title?.content;
                if (typeof id !== 'string' || !id || typeof title !== 'string' || !title || seenPl.has(id)) continue;
                seenPl.add(id);
                const img =
                  lk.contentImage?.collectionThumbnailViewModel?.primaryThumbnail?.thumbnailViewModel?.image?.sources?.[0]?.url ||
                  lk.contentImage?.thumbnailViewModel?.image?.sources?.[0]?.url;
                playlists.push({
                  id,
                  title,
                  thumbnail: typeof img === 'string' ? img : undefined,
                  author: undefined,
                  trackCount: undefined,
                });
              }
            }
          }

          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify({ songs, artists, playlists, results: songs }));
        } catch (err: any) {
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
})


