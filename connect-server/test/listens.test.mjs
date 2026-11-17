// The listening-history topic on the real Worker and Durable Object in workerd.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { connect, startServer, stopServer } from './harness.mjs';

const PORT = 8800;
const BASE = `http://127.0.0.1:${PORT}`;
let server; let n = 0;
const sockets = [];
const account = () => `lis${Date.now()}x${n++}`;
before(async () => { server = await startServer(PORT, undefined, { LISTENS_IMPORT_DAILY_ROWS: '40' }); }, { timeout: 120_000 });
after(async () => { sockets.forEach((c) => c.close()); await stopServer(server); });

// In the past, since a clear marker can never be later than now.
const T = Date.now() - 2 * 86_400_000;
const ev = (t, ts, ms, d = 'pc') => ({ t, ts, ms, ti: `Song ${t}`, ar: 'A', al: null, th: '', du: 200, d });
const rpc = async (uid, frame, device = 'pc') => (await fetch(`${BASE}/v1/library/rpc?device=${device}`, { method: 'POST',
  headers: { authorization: `Bearer dev:${uid}` }, body: JSON.stringify({ v: 2, ...frame }) })).json();
const socket = async (uid, device) => { const c = await connect(PORT, uid, device, { path: '/v1/library' }); sockets.push(c); c.ready = await c.call({ t: 'hello', v: 2 }); return c; };
const all = async (uid) => { const out = {}; let page; for (;;) { const d = await rpc(uid, { t: 'listens.pull', full: true, page }); for (const x of d.days) out[x.day] = x.events; if (!d.more) return { days: out, rev: d.rev }; page = d.more; } };

test('a batch of finished listens is stored per day and pushed to the other devices with only the new events', async () => {
  const uid = account();
  const phone = await socket(uid, 'phone');
  assert.equal(phone.ready.topics.listens.imported, false);
  const ack = await rpc(uid, { t: 'listens', events: [ev('a', T, 180_000), ev('b', T + 200_000, 120_000), ev('c', T + 86_400_000, 30_000)] });
  assert.deepEqual([ack.changed, ack.rev], [true, 1], 'one revision for the batch');
  const pushed = await phone.next((f) => f.t === 'changed' && f.topic === 'listens');
  assert.deepEqual([pushed.from, pushed.rev, pushed.days.length], [0, 1, 2]);
  const again = await rpc(uid, { t: 'listens', events: [ev('a', T, 180_000)] });
  assert.equal(again.changed, false, 'resending a batch changes nothing (a lost acknowledgment is harmless)');
  const { days } = await all(uid);
  assert.equal(Object.keys(days).length, 2);
});

test('a reconnecting device pulls only the days changed since its cursor', async () => {
  const uid = account();
  const first = await rpc(uid, { t: 'listens', events: [ev('a', T, 1_000)] });
  await rpc(uid, { t: 'listens', events: [ev('b', T + 86_400_000, 1_000)] }, 'phone');
  const delta = await rpc(uid, { t: 'listens.pull', cursor: first.rev });
  assert.deepEqual(delta.days.map((d) => d.day), [new Date(T + 86_400_000).toISOString().slice(0, 10)]);
  assert.equal((await rpc(uid, { t: 'listens.pull', cursor: delta.rev })).days.length, 0, 'current: nothing to read');
});

test('clearing stats removes everything before the marker for all devices and refuses it later', async () => {
  const uid = account();
  await rpc(uid, { t: 'listens', events: [ev('old', T, 1_000), ev('older', T - 86_400_000 * 5, 1_000), ev('new', T + 7_200_000, 1_000)] });
  const phone = await socket(uid, 'phone');
  const cursor = phone.ready.topics.listens.rev;
  const cleared = await rpc(uid, { t: 'listens.clear', before: T + 3_600_000 });
  assert.equal(cleared.clearedBefore, T + 3_600_000);
  assert.equal((await phone.next((f) => f.t === 'changed' && f.topic === 'listens')).clearedBefore, T + 3_600_000);
  const { days } = await all(uid);
  assert.deepEqual(Object.values(days).flatMap((d) => Object.values(d)).map((e) => e.t), ['new']);
  assert.equal((await rpc(uid, { t: 'listens.pull', cursor })).t, 'reset', 'a device from before the clear starts over');
  const resent = await rpc(uid, { t: 'listens', events: [ev('old', T, 1_000)] }, 'old-app-bridge');
  assert.equal(resent.changed, false, 'a cleared listen cannot come back');
});

test('imports are capped per day and say when to try again; importDone is recorded', async () => {
  const uid = account();
  const history = Array.from({ length: 30 }, (_, i) => ev(`h${i}`, T - i * 86_400_000, 1_000));
  const ok = await rpc(uid, { t: 'listens', import: true, events: history });
  assert.equal(ok.changed, true);
  const over = await rpc(uid, { t: 'listens', import: true, events: history.map((e) => ({ ...e, ts: e.ts - 40 * 86_400_000 })) });
  assert.equal(over.code, 'later');
  assert.ok(over.retryAfterMs > 0);
  const done = await rpc(uid, { t: 'listens', events: [], importDone: true });
  assert.equal(done.imported, true);
  assert.equal((await rpc(uid, { t: 'hello', v: 2 })).topics.listens.imported, true);
});

test('invalid batches are refused and account deletion removes the history', async () => {
  const uid = account();
  assert.equal((await rpc(uid, { t: 'listens', events: Array.from({ length: 1001 }, (_, i) => ev(`x${i}`, T + i, 1)) })).code, 'invalid');
  await rpc(uid, { t: 'listens', events: [ev('a', T, 1_000)] });
  assert.equal((await rpc(uid, { t: 'erase', v: 1 })).t, 'erased');
  assert.equal((await rpc(uid, { t: 'listens.pull', full: true })).code, 'authority');
});
