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

          if (!response.ok) {
            res.statusCode = response.status;
            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify({ songs: [], artists: [], playlists: [], results: [] }));
            return;
          }

          const data = (await response.json()) as any;
          const contents = data.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents;
          const itemSection = contents?.find((c: any) => c.itemSectionRenderer)?.itemSectionRenderer?.contents || [];

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

          for (const item of itemSection) {
            const vr = item.videoRenderer;
            if (vr && vr.videoId && songs.length < 25) {
              const rawTitle = vr.title?.runs?.[0]?.text || 'Untitled Track';
              const owner = vr.ownerText?.runs?.[0]?.text || 'YouTube Artist';
              const cleanArtist = (s: string) =>
                s.replace(/\s*-\s*Topic$/i, '').replace(/VEVO$/i, '').trim();
              let artist = cleanArtist(owner) || owner;
              let title = rawTitle;
              // Only split on a spaced " - " ("Artist - Title"). A bare "|"/":"
              // is usually jukebox/tag noise ("Song || tag || tag" or
              // "Song | Latest Punjabi Songs 2024"), and the channel owner is a
              // far more reliable artist than a guessed pipe segment. This
              // mirrors the production parser in src/services/youtube.ts; the
              // lyrics layer (extractTrackAndArtistPairs) recovers the rest.
              if (rawTitle.includes(' - ')) {
                const parts = rawTitle.split(' - ');
                artist = cleanArtist(parts[0].trim()) || artist;
                title = parts.slice(1).join(' - ').trim() || rawTitle;
              }
              const lengthText = vr.lengthText?.simpleText || '3:30';
              const parts = lengthText.split(':').map(Number);
              const duration = parts.length === 2
                ? parts[0] * 60 + parts[1]
                : (parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : 200);

              songs.push({
                id: vr.videoId,
                title,
                artist,
                duration,
                thumbnail: `https://i.ytimg.com/vi/${vr.videoId}/hqdefault.jpg`,
                source: 'youtube',
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

            // Playlists (YouTube models albums as playlists too).
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
                // The only images in this card are song thumbnails, not an artist
                // photo — omit rather than mislabel one (the UI shows an initial).
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


