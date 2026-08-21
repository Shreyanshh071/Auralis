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
            res.end(JSON.stringify({ results: [] }));
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
            res.end(JSON.stringify({ results: [] }));
            return;
          }

          const data = await response.json();
          const contents = data.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents;
          const itemSection = contents?.find((c: any) => c.itemSectionRenderer)?.itemSectionRenderer?.contents || [];
          
          const results: any[] = [];
          for (const item of itemSection) {
            const vr = item.videoRenderer;
            if (vr && vr.videoId) {
              const rawTitle = vr.title?.runs?.[0]?.text || 'Untitled Track';
              const owner = vr.ownerText?.runs?.[0]?.text || 'YouTube Artist';
              let artist = owner;
              let title = rawTitle;
              if (rawTitle.includes(' - ')) {
                const parts = rawTitle.split(' - ');
                artist = parts[0].trim();
                title = parts.slice(1).join(' - ').trim();
              }
              const lengthText = vr.lengthText?.simpleText || '3:30';
              const parts = lengthText.split(':').map(Number);
              const duration = parts.length === 2 
                ? parts[0] * 60 + parts[1] 
                : (parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : 200);
              
              const thumb = `https://i.ytimg.com/vi/${vr.videoId}/hqdefault.jpg`;
              
              results.push({
                id: vr.videoId,
                title,
                artist,
                duration,
                thumbnail: thumb,
                source: 'youtube',
              });
              if (results.length >= 25) break;
            }
          }

          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify({ results }));
        } catch (err: any) {
          res.statusCode = 200;
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify({ results: [] }));
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


