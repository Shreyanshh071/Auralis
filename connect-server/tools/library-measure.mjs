#!/usr/bin/env node
// Measures what the library service's storage does for typical work, against a local `wrangler dev`
// (node test/serve.mjs 8795). Prints billed rows read/written per action, which is what the
// Durable Objects free plan limits (100,000 rows written and 5,000,000 rows read a day).
//   node tools/library-measure.mjs [port]
import { checksum, fromUserDocument } from '../src/library-core.js';

const port = Number(process.argv[2] ?? 8795);
const BASE = `http://127.0.0.1:${port}`;
const song = (id) => ({ id, title: `A fairly ordinary song title ${id}`, artist: 'Some Artist', album: 'Some Album',
  thumbnail: `https://lh3.googleusercontent.com/${'x'.repeat(60)}=w544-h544-l90-rj`, duration: 215 });

let n = 0;
const admin = async (uid, action, body = {}) => (await fetch(`${BASE}/v1/library/admin/${uid}/${action}`, { method: 'POST',
  headers: { 'x-library-admin': 'local-admin' }, body: JSON.stringify(body) })).json();
const rpc = async (uid, frame, device = 'pc') => (await fetch(`${BASE}/v1/library/rpc?device=${device}`, { method: 'POST',
  headers: { authorization: `Bearer dev:${uid}` }, body: JSON.stringify(frame) })).json();
const rows = async (uid) => (await rpc(uid, { t: 'stats' })).rows;
const cost = async (uid, work) => { const a = await rows(uid); await work(); const b = await rows(uid); return { read: b.read - a.read, written: b.written - a.written }; };

async function account(playlists, tracksEach, favorites) {
  const uid = `measure${Date.now()}x${n++}`;
  const doc = { playlists: Array.from({ length: playlists }, (_, p) => ({ id: `p${p}`, title: `Playlist ${p}`, createdAt: 1, isCustom: true,
    tracks: Array.from({ length: tracksEach }, (_, i) => song(`t${p}-${i}`)) })), favorites: Array.from({ length: favorites }, (_, i) => song(`f${i}`)) };
  const { library } = fromUserDocument(doc);
  const sum = await checksum(library);
  await admin(uid, 'begin');
  const before = await admin(uid, 'status');
  const imported = await cost(uid, () => admin(uid, 'import', { library, checksum: sum, source: { exists: true } }));
  const { epoch } = await admin(uid, 'commit', { checksum: sum });
  return { uid, epoch, imported, bytes: JSON.stringify(library).length, before };
}

const table = [];
for (const [label, playlists, tracksEach, favorites] of [['small', 5, 20, 50], ['typical', 15, 60, 300], ['large', 40, 100, 1500], ['near the Firestore 1 MiB limit', 20, 150, 500]]) {
  const a = await account(playlists, tracksEach, favorites);
  const cursor = (await rpc(a.uid, { t: 'hello', v: 1 })).rev;
  const p0 = (await rpc(a.uid, { t: 'pull', full: true })).playlists.find((p) => p.id === 'p0');
  const like = await cost(a.uid, () => rpc(a.uid, { t: 'push', v: 1, epoch: a.epoch, ops: [{ id: `like${n++}`, t: 'fav.put', v: song('new-like') }] }));
  const unlike = await cost(a.uid, () => rpc(a.uid, { t: 'push', v: 1, epoch: a.epoch, ops: [{ id: `unlike${n++}`, t: 'fav.del', key: 'f0' }] }));
  const append = await cost(a.uid, () => rpc(a.uid, { t: 'push', v: 1, epoch: a.epoch, ops: [{ id: `add${n++}`, t: 'pl.put', ifRev: p0.rev,
    v: { id: 'p0', title: 'Playlist 0', createdAt: 1, isCustom: true }, tracks: [...p0.tracks, song('appended')] }] }));
  const current = (await rpc(a.uid, { t: 'hello', v: 1 })).rev;
  const idle = await cost(a.uid, async () => { await rpc(a.uid, { t: 'hello', v: 1 }, 'phone'); await rpc(a.uid, { t: 'pull', cursor: current }, 'phone'); });
  const catchUp = await cost(a.uid, () => rpc(a.uid, { t: 'pull', cursor }, 'phone'));
  const restore = await cost(a.uid, async () => { let page; for (;;) { const d = await rpc(a.uid, { t: 'pull', full: true, page }, 'new'); if (!d.more) break; page = d.more; } });
  table.push({ library: `${label} (${playlists}x${tracksEach} + ${favorites} likes, ${Math.round(a.bytes / 1024)} KiB)`,
    'import written': a.imported.written, 'like r/w': `${like.read}/${like.written}`, 'unlike r/w': `${unlike.read}/${unlike.written}`,
    'append song r/w': `${append.read}/${append.written}`, 'reconnect, current r/w': `${idle.read}/${idle.written}`,
    'catch-up pull read': catchUp.read, 'fresh restore read': restore.read });
}
console.table(table);
