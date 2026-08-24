// Live end-to-end probe: pulls REAL YouTube search results using the same
// InnerTube call + parsing as the vite dev middleware, then runs the REAL
// fetchLyrics() on each. Reports the honest hit rate the app would show.
//   node scripts/probe-lyrics-e2e.mjs
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { fetchLyrics } = await import(pathToFileURL(path.join(repoRoot, 'src', 'services', 'lyrics.ts')).href);

async function ytSearch(q) {
  const response = await fetch('https://www.youtube.com/youtubei/v1/search?prettyPrint=false', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent':
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB', clientVersion: '2.20240101.00.00', hl: 'en', gl: 'US' } },
      query: q,
    }),
  });
  const data = await response.json();
  const contents =
    data.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents;
  const itemSection = contents?.find((c) => c.itemSectionRenderer)?.itemSectionRenderer?.contents || [];
  const songs = [];
  for (const item of itemSection) {
    const vr = item.videoRenderer;
    if (!vr || !vr.videoId || songs.length >= 4) continue;
    const rawTitle = vr.title?.runs?.[0]?.text || 'Untitled Track';
    const owner = vr.ownerText?.runs?.[0]?.text || 'YouTube Artist';
    const cleanArtist = (s) => s.replace(/\s*-\s*Topic$/i, '').replace(/VEVO$/i, '').trim();
    // Mirror the PRODUCTION parser (src/services/youtube.ts + the aligned dev
    // middleware): only split on a spaced " - "; keep the channel owner as the
    // artist otherwise. A bare "|"/":" is jukebox/tag noise, not "Artist|Title".
    let artist = cleanArtist(owner) || owner;
    let title = rawTitle;
    if (rawTitle.includes(' - ')) {
      const parts = rawTitle.split(' - ');
      artist = cleanArtist(parts[0].trim()) || artist;
      title = parts.slice(1).join(' - ').trim() || rawTitle;
    }
    const lengthText = vr.lengthText?.simpleText || '3:30';
    const parts = lengthText.split(':').map(Number);
    const duration =
      parts.length === 2 ? parts[0] * 60 + parts[1] : parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : 200;
    songs.push({ id: vr.videoId, title, artist, duration, rawTitle, owner });
  }
  return songs;
}

const QUERIES = process.argv.slice(2).length
  ? process.argv.slice(2)
  : [
      'arijit singh songs',
      'lofi hindi songs',
      'karan aujla',
      'anuv jain',
      'weeknd',
      'bollywood 2024 hits',
      'travis scott',
      'seedhe maut',
      'phonk',
      'taylor swift live',
    ];

const rows = [];
for (const q of QUERIES) {
  let songs = [];
  try {
    songs = await ytSearch(q);
  } catch (e) {
    console.log(`search FAILED "${q}": ${e.message}`);
    continue;
  }
  for (const s of songs) {
    const t0 = Date.now();
    let res = null;
    try {
      res = await fetchLyrics(s.title, s.artist, s.duration, s.id);
    } catch (e) {
      rows.push(['THROW', Date.now() - t0, s, e.message]);
      continue;
    }
    rows.push([
      res ? `${res.provider}/${res.syncType}` : 'NONE',
      Date.now() - t0,
      s,
      res ? `${res.lines.length}L  ${res.trackName} — ${res.artistName}` : '',
    ]);
  }
}

console.log('\n===== E2E RESULTS =====');
for (const [verdict, ms, s, detail] of rows) {
  console.log(
    `${verdict.padEnd(18)} ${String(ms + 'ms').padEnd(8)} ${`${s.title} / ${s.artist}`.slice(0, 52).padEnd(54)} ${String(s.duration).padStart(4)}s  ${detail}`
  );
}
const total = rows.length;
const none = rows.filter((r) => r[0] === 'NONE' || r[0] === 'THROW').length;
const plain = rows.filter((r) => r[0].endsWith('/plain')).length;
const synced = rows.filter((r) => /line-sync|richsync/.test(r[0])).length;
console.log(`\ntotal ${total}   synced ${synced}   plain ${plain}   none ${none}`);
console.log(`avg latency ${Math.round(rows.reduce((a, r) => a + r[1], 0) / Math.max(1, total))}ms`);
