// The pilot allowlist and restore, against the real Worker with an explicit allowlist.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, stopServer } from './harness.mjs';
import { checksum, fromUserDocument } from '../src/library-core.js';

const PORT = 8796;
const BASE = `http://127.0.0.1:${PORT}`;
let server;
before(async () => { server = await startServer(PORT, undefined, { LIBRARY_PILOT_UIDS: 'pilot-a,pilot-b' }); }, { timeout: 120_000 });
after(async () => { await stopServer(server); });

const admin = async (uid, action, body = {}) => {
  const response = await fetch(`${BASE}/v1/library/admin/${uid}/${action}`, { method: 'POST', headers: { 'x-library-admin': 'local-admin' }, body: JSON.stringify(body) });
  return { status: response.status, body: await response.json() };
};
const rpc = async (uid, frame) => (await fetch(`${BASE}/v1/library/rpc?device=pc`, { method: 'POST', headers: { authorization: `Bearer dev:${uid}` },
  body: JSON.stringify(frame) })).json();
const song = (id) => ({ id, title: id, artist: 'A', album: null, thumbnail: 't', duration: 1 });

test('an account not on the allowlist cannot start moving, and stays legacy', async () => {
  const refused = await admin('someone-else', 'begin');
  assert.equal(refused.status, 403);
  assert.match(refused.body.error, /allowlist/);
  assert.equal((await admin('someone-else', 'status')).body.state, 'legacy');
  assert.equal((await admin('pilot-ab', 'begin')).status, 403, 'no partial matches');
  // A signed-in client of an unlisted account can't move itself either.
  assert.equal((await rpc('someone-else', { t: 'push', v: 1, epoch: 0, ops: [{ id: 'o', t: 'fav.put', v: song('a') }] })).code, 'authority');
});

test('a listed account moves; then export, restore and resume reproduce the library exactly', async () => {
  const { library } = fromUserDocument({ playlists: [{ id: 'p', title: 'P', createdAt: 1, tracks: [song('a'), song('b'), song('a')] }], favorites: [song('f1'), song('f2')] });
  const sum = await checksum(library);
  assert.equal((await admin('pilot-a', 'begin')).status, 200);
  await admin('pilot-a', 'import', { library, checksum: sum, source: { exists: true } });
  const { epoch } = (await admin('pilot-a', 'commit', { checksum: sum })).body;

  const exported = (await admin('pilot-a', 'export')).body;
  assert.equal(exported.checksum, sum, 'the export is the imported library');
  assert.equal(await checksum(exported.library), exported.checksum, 'an export verifies against its own checksum');

  // Edits after the export, then a restore back to it.
  const ack = await rpc('pilot-a', { t: 'push', v: 1, epoch, ops: [{ id: 'later', t: 'fav.put', v: song('later') }, { id: 'gone', t: 'fav.del', key: 'f1' }] });
  assert.deepEqual(ack.results.map((r) => r.s), ['ok', 'ok']);
  assert.equal((await admin('pilot-a', 'restore', { library: exported.library, checksum: exported.checksum })).status, 409, 'not while live');
  await admin('pilot-a', 'rollback-begin');
  assert.equal((await admin('pilot-a', 'restore', { library: exported.library, checksum: 'f'.repeat(64) })).status, 422);
  const restored = await admin('pilot-a', 'restore', { library: exported.library, checksum: exported.checksum });
  assert.equal(restored.status, 200);
  assert.equal(restored.body.checksum, exported.checksum);
  const resumed = (await admin('pilot-a', 'rollback-abort')).body;
  assert.equal(resumed.state, 'cloudflare');
  assert.ok(resumed.epoch > epoch, 'devices start over from the restored library');
  assert.equal((await admin('pilot-a', 'verify')).body.checksum, sum);
  const stale = await rpc('pilot-a', { t: 'pull', cursor: ack.rev });
  assert.equal(stale.t, 'reset');
  const counts = (await admin('pilot-a', 'status')).body.seen;
  assert.deepEqual(counts, { dup: 0, stale: 0, bad: 0, limit: 0 });
});

test('refusals and retries are counted for the pilot report', async () => {
  const { library } = fromUserDocument(null);
  const sum = await checksum(library);
  await admin('pilot-b', 'begin');
  await admin('pilot-b', 'import', { library, checksum: sum, source: { exists: false } });
  const { epoch } = (await admin('pilot-b', 'commit', { checksum: sum })).body;
  const frame = { t: 'push', v: 1, epoch, ops: [{ id: 'x1', t: 'fav.put', v: song('a') }, { id: 'x2', t: 'fav.put', v: { title: 'no id' } }] };
  await rpc('pilot-b', frame);
  await rpc('pilot-b', frame);
  const seen = (await admin('pilot-b', 'status')).body.seen;
  assert.deepEqual(seen, { dup: 1, stale: 0, bad: 2, limit: 0 });
});
