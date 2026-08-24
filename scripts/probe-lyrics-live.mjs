// Live diagnostic probe: runs the REAL fetchLyrics() against a batch of
// realistic YouTube-derived track metadata and reports provider + syncType.
// Not part of `npm test` (it does network I/O). Run with:
//   node scripts/probe-lyrics-live.mjs
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const mod = await import(pathToFileURL(path.join(repoRoot, 'src', 'services', 'lyrics.ts')).href);
const { fetchLyrics } = mod;

// (title, artist, duration) as the YouTube search middleware would produce them.
const CASES = [
  ['Blinding Lights', 'The Weeknd', 262],
  ['Loving Machine', 'TV Girl', 227],
  ['Espresso', 'Sabrina Carpenter', 175],
  ['Cruel Summer', 'Taylor Swift', 178],
  ['Die With A Smile', 'Lady Gaga, Bruno Mars', 251],
  ['Not Like Us', 'Kendrick Lamar', 274],
  ['Anti-Hero', 'Taylor Swift', 200],
  ['As It Was', 'Harry Styles', 167],
  ['Kesariya', 'Arijit Singh', 268],
  ['Apna Bana Le', 'Arijit Singh', 262],
  ['Tum Hi Ho', 'Arijit Singh', 262],
  ['Levitating', 'Dua Lipa', 203],
  // Messy YouTube shapes
  ['The Weeknd - Blinding Lights (Official Video)', 'TheWeeknd', 261],
  ['Sabrina Carpenter - Espresso (Official Video)', 'Sabrina Carpenter', 176],
  ['Ed Sheeran - Shape of You (Official Music Video)', 'Ed Sheeran', 263],
  ['Shape of You', 'Ed Sheeran - Topic', 234],
  ['Believer', 'Imagine Dragons', 224],
  ['STAY', 'The Kid LAROI, Justin Bieber', 141],
  ['Snowman', 'Sia', 165],
  ['golden hour', 'JVKE', 209],
  ['Until I Found You', 'Stephen Sanchez', 178],
  ['Heat Waves', 'Glass Animals', 238],
  // Long music-video durations (intro/outro padding) vs album track length
  ['Bohemian Rhapsody', 'Queen', 367],
  ['Numb', 'Linkin Park', 187],
];

let ok = 0;
const rows = [];
for (const [title, artist, duration] of CASES) {
  const t0 = Date.now();
  let res = null;
  try {
    res = await fetchLyrics(title, artist, duration, undefined);
  } catch (e) {
    rows.push([title, 'THROW', e.message, Date.now() - t0]);
    continue;
  }
  const ms = Date.now() - t0;
  if (!res) {
    rows.push([`${title} — ${artist}`, 'NONE', '-', ms]);
  } else {
    ok++;
    rows.push([
      `${title} — ${artist}`,
      `${res.provider}/${res.syncType}`,
      `${res.lines.length} lines | matched: ${res.trackName} — ${res.artistName}`,
      ms,
    ]);
  }
}

console.log('\n===== RESULTS =====');
for (const [name, verdict, detail, ms] of rows) {
  console.log(`${verdict.padEnd(18)} ${String(ms + 'ms').padEnd(8)} ${name.slice(0, 46).padEnd(48)} ${detail}`);
}
const synced = rows.filter((r) => /line-sync|richsync/.test(r[1])).length;
console.log(`\nfound ${ok}/${CASES.length}   synced ${synced}/${CASES.length}   none ${CASES.length - ok}`);
console.log(`total wall time: ${rows.reduce((a, r) => a + r[3], 0)}ms`);
