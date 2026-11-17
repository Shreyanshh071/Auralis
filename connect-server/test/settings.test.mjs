// The settings topic on the real Worker and Durable Object in workerd. Nothing leaves this machine.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { connect, startServer, stopServer } from './harness.mjs';

const PORT = 8798;
const BASE = `http://127.0.0.1:${PORT}`;
let server; let off;
let n = 0;
const account = () => `set${Date.now()}x${n++}`;
const sockets = [];
before(async () => { server = await startServer(PORT); }, { timeout: 120_000 });
after(async () => { sockets.forEach((c) => c.close()); await stopServer(server); if (off) await stopServer(off); });

const p = (value, revision, device) => ({ value, revision, device });
const rpc = async (uid, frame, device = 'pc', base = BASE) => (await fetch(`${base}/v1/library/rpc?device=${device}`, { method: 'POST',
  headers: { authorization: `Bearer dev:${uid}` }, body: JSON.stringify(frame) })).json();
const exchange = (uid, platform, edits = {}, seed = {}, device = 'pc', extra = {}) => rpc(uid, { t: 'settings', v: 2, platform, edits, seed, ...extra }, device);
const socket = async (uid, device) => { const c = await connect(PORT, uid, device, { path: '/v1/library' }); sockets.push(c); c.ready = await c.call({ t: 'hello', v: 2 }); return c; };

test('the first device seeds the account; the next one gets those values instead of uploading its own', async () => {
  const uid = account();
  const first = await exchange(uid, 'android', {}, { 'a.theme': 'dark', 'a.lyrics_animation': 'Spicy Lyrics' }, 'phone');
  assert.deepEqual(first.values['a.theme'], p('dark', 0, 'initial'));
  const second = await exchange(uid, 'android', {}, { 'a.theme': 'light' }, 'tablet');
  assert.equal(second.values['a.theme'].value, 'dark', 'a non-empty account ignores the seed');
  assert.equal(second.rev, first.rev, 'a pull changes nothing');
  const pc = await exchange(uid, 'windows', {}, { 'a.theme': 'light' });
  assert.equal(pc.values['a.theme'].value, 'light', 'each platform has its own map');
});

test('an edit is pushed to the account\'s other devices with the value, and not to the writer', async () => {
  const uid = account();
  await exchange(uid, 'android', {}, { 'a.theme': 'dark' }, 'phone');
  const [phone, tablet] = [await socket(uid, 'phone'), await socket(uid, 'tablet')];
  assert.equal(tablet.ready.topics.settings.bridge, true);
  const ack = await phone.call({ t: 'settings', v: 2, platform: 'android', edits: { 'a.theme': p('light', 1, 'phone') } });
  assert.equal(ack.values['a.theme'].value, 'light');
  const pushed = await tablet.next((f) => f.t === 'changed' && f.topic === 'settings');
  assert.deepEqual([pushed.platform, pushed.by, pushed.values['a.theme'].value, pushed.rev], ['android', 'phone', 'light', ack.rev]);
  assert.equal(phone.frames.filter((f) => f.t === 'changed').length, 0);
});

test('concurrent edits of one key settle the same everywhere, whatever the order', async () => {
  const uid = account();
  await exchange(uid, 'android', {}, { 'a.theme': 'dark' }, 'phone');
  await exchange(uid, 'android', { 'a.theme': p('light', 4, 'phone') }, {}, 'phone');
  const late = await exchange(uid, 'android', { 'a.theme': p('sepia', 3, 'tablet') }, {}, 'tablet');
  assert.equal(late.values['a.theme'].value, 'light', 'the lower revision does not overwrite');
  const resent = await exchange(uid, 'android', { 'a.theme': p('light', 4, 'phone') }, {}, 'phone');
  assert.equal(resent.rev, late.rev, 'resending an applied edit changes nothing');
});

test('a reconnecting device learns from hello whether it missed anything', async () => {
  const uid = account();
  const before = await exchange(uid, 'android', {}, { 'a.theme': 'dark' }, 'phone');
  const quiet = await socket(uid, 'tablet');
  assert.equal(quiet.ready.topics.settings.rev, before.rev, 'nothing missed: same revision, no pull needed');
  quiet.close();
  await exchange(uid, 'android', { 'a.theme': p('light', 1, 'phone') }, {}, 'phone');
  const back = await socket(uid, 'tablet');
  assert.ok(back.ready.topics.settings.rev > before.rev, 'a newer revision tells it to pull');
});

test('an old-app change marks the account, and invalid frames are refused', async () => {
  const uid = account();
  await exchange(uid, 'android', {}, { 'a.theme': 'dark' }, 'phone', { legacy: true });
  assert.equal((await rpc(uid, { t: 'settings', v: 2, platform: 'ios' })).code, 'invalid');
  assert.equal((await rpc(uid, { t: 'settings', v: 1, platform: 'android' })).code, 'version');
  const many = Object.fromEntries(Array.from({ length: 401 }, (_, i) => [`a.k${i}`, p('1', 1, 'd')]));
  assert.equal((await exchange(uid, 'android', many)).code, 'invalid');
  assert.equal((await rpc(uid, { t: 'hello', v: 3 })).code, 'version');
  assert.equal((await rpc(uid, { t: 'hello', v: 1 })).topics, undefined, 'version 1 clients see the old hello');
});

test('settings work whatever the library\'s state, and account deletion removes them', async () => {
  const uid = account();
  await exchange(uid, 'windows', {}, { 'a.theme': 'dark' });
  assert.equal((await rpc(uid, { t: 'erase', v: 1 })).t, 'erased');
  assert.equal((await exchange(uid, 'windows', {}, { 'a.theme': 'light' })).code, 'authority', 'an erased account accepts nothing');
  const exported = await (await fetch(`${BASE}/v1/library/admin/${uid}/export`, { method: 'POST', headers: { 'x-library-admin': 'local-admin' }, body: '{}' })).json();
  assert.deepEqual(exported.settings, {});
});

test('retiring the bridge is explicit, needs the exact acknowledgement, and can be undone', async () => {
  const uid = account();
  const adminCall = async (action, body = {}) => { const r = await fetch(`${BASE}/v1/library/admin/${uid}/${action}`, { method: 'POST',
    headers: { 'x-library-admin': 'local-admin' }, body: JSON.stringify(body) }); return { status: r.status, body: await r.json() }; };
  await exchange(uid, 'android', {}, { 'a.theme': 'dark' }, 'phone');
  assert.equal((await adminCall('status')).body.bridge.active, true, 'on by default');
  const refused = await adminCall('retire-bridge', { acknowledge: 'yes' });
  assert.equal(refused.status, 400);
  assert.match(refused.body.acknowledge, /old devices/);
  assert.equal((await exchange(uid, 'android')).bridge, true, 'a wrong acknowledgement changes nothing');
  const retired = await adminCall('retire-bridge', { acknowledge: refused.body.acknowledge });
  assert.equal(retired.body.bridge.active, false);
  assert.equal((await exchange(uid, 'android')).bridge, false, 'updated apps are told to stop the bridge');
  assert.equal((await rpc(uid, { t: 'hello', v: 2 })).topics.settings.bridge, false);
  assert.equal((await adminCall('restore-bridge')).body.bridge.active, true);
  assert.equal((await exchange(uid, 'android')).bridge, true);
});

test('a Worker without ACCOUNT_SYNC_TOPICS serves no settings (production default)', async () => {
  off = await startServer(8799, undefined, { ACCOUNT_SYNC_TOPICS: null });
  const uid = account();
  const answer = await rpc(uid, { t: 'settings', v: 2, platform: 'android', seed: { 'a.theme': 'dark' } }, 'pc', 'http://127.0.0.1:8799');
  assert.equal(answer.code, 'topic');
  const hello = await rpc(uid, { t: 'hello', v: 2 }, 'pc', 'http://127.0.0.1:8799');
  assert.deepEqual(hello.topics, {});
});
