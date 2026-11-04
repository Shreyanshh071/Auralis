import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { connect, deviceDoc, sessionDoc, startServer, stopServer } from './harness.mjs';

const PORT = 8791;
let server;
let accounts = 0;
const account = () => `acct${Date.now()}x${accounts++}`;

before(async () => { server = await startServer(PORT); }, { timeout: 120_000 });
after(async () => { await stopServer(server); });

const join = async (uid, id) => {
  const c = await connect(PORT, uid, id);
  await c.next((f) => f.t === 'state');
  await c.call({ t: 'hello', d: deviceDoc(id) });
  return c;
};

test('a new device gets the current state at once, and devices see each other', async () => {
  const uid = account();
  const phone = await join(uid, 'phone');
  assert.equal((await phone.call({ t: 'update', base: 0, s: sessionDoc() })).t, 'ok');
  const pc = await connect(PORT, uid, 'pc');
  const state = await pc.next((f) => f.t === 'state');
  assert.equal(state.s.version, 1);
  assert.deepEqual(state.dv.map((d) => d.id), ['phone']);
  await pc.call({ t: 'hello', d: deviceDoc('pc') });
  const seen = await phone.next((f) => f.t === 'devices');
  assert.deepEqual(seen.dv.map((d) => d.id).sort(), ['pc', 'phone']);
  phone.close(); pc.close();
});

test('a write reaches every other device with the server time and not the writer', async () => {
  const uid = account();
  const [phone, pc] = [await join(uid, 'phone'), await join(uid, 'pc')];
  await phone.call({ t: 'update', base: 0, s: sessionDoc() });
  const push = await pc.next((f) => f.t === 'session');
  assert.equal(push.s.version, 1);
  assert.ok(push.s.updatedAt > 0);
  assert.equal(phone.frames.filter((f) => f.t === 'session').length, 0);
  phone.close(); pc.close();
});

test('simultaneous writes: one wins and the other gets the newer state to redo it', async () => {
  const uid = account();
  const [phone, pc] = [await join(uid, 'phone'), await join(uid, 'pc')];
  await phone.call({ t: 'update', base: 0, s: sessionDoc() });
  const [a, b] = await Promise.all([
    phone.call({ t: 'update', base: 1, s: sessionDoc({ version: 2, positionMs: 1 }) }),
    pc.call({ t: 'update', base: 1, s: sessionDoc({ version: 2, positionMs: 2, updatedBy: 'pc' }) }),
  ]);
  assert.deepEqual([a.t, b.t].sort(), ['conflict', 'ok']);
  assert.equal((a.t === 'conflict' ? a : b).s.version, 2);
  phone.close(); pc.close();
});

test('duplicate and delayed frames cannot apply twice', async () => {
  const uid = account();
  const phone = await join(uid, 'phone');
  const frame = { t: 'update', base: 0, s: sessionDoc() };
  assert.equal((await phone.call(frame)).t, 'ok');
  assert.equal((await phone.call(frame)).t, 'conflict', 'A replay carries an old base');
  assert.equal((await phone.call({ t: 'update', base: 1, s: sessionDoc({ version: 2 }) })).t, 'ok');
  assert.equal((await phone.call(frame)).t, 'conflict');
  assert.equal((await phone.call({ t: 'fetch' })).s.version, 2);
  phone.close();
});

test('accounts are separate: same device id, nothing shared', async () => {
  const [one, two] = [account(), account()];
  const a = await join(one, 'phone');
  const b = await join(two, 'phone');
  await a.call({ t: 'update', base: 0, s: sessionDoc() });
  assert.equal((await b.call({ t: 'fetch' })).s, null);
  await new Promise((r) => setTimeout(r, 200));
  assert.equal(b.frames.filter((f) => f.t === 'session').length, 0);
  a.close(); b.close();
});

test('nobody connects without a valid token, and bad requests never reach an account', async () => {
  await assert.rejects(connect(PORT, 'x', 'phone', { token: null }), (e) => e.status === 401);
  await assert.rejects(connect(PORT, 'x', 'phone', { token: 'forged' }), (e) => e.status === 401);
  await assert.rejects(connect(PORT, 'x', 'bad/device'), (e) => e.status === 400);
});

test('a client-sent identity is ignored: the token decides the account', async () => {
  const uid = account();
  const mine = await join(uid, 'phone');
  await mine.call({ t: 'update', base: 0, s: sessionDoc() });
  await assert.rejects(connect(PORT, uid, 'x', { token: null, headers: { 'x-uid': uid, 'x-device': 'x', 'x-expires': '99999999999999' } }), (e) => e.status === 401);
  const other = account();
  const spoof = await connect(PORT, other, 'phone', { headers: { 'x-uid': uid } });
  assert.equal((await spoof.next((f) => f.t === 'state')).s, null, 'The token chose the account, not the header');
  spoof.close();
  mine.close();
});

test('the staging login works with the secret and keeps its own accounts', async () => {
  await assert.rejects(connect(PORT, 'x', 'phone', { token: 'staging:wrong:probe' }), (e) => e.status === 401);
  const c = await connect(PORT, 'x', 'phone', { token: `staging:local-secret:${account().toLowerCase()}` });
  assert.equal((await c.next((f) => f.t === 'state')).s, null);
  c.close();
});

test('a device writes and registers only as itself', async () => {
  const uid = account();
  const phone = await join(uid, 'phone');
  assert.equal((await phone.call({ t: 'update', base: 0, s: sessionDoc({ updatedBy: 'pc' }) })).code, 'not-this-device');
  assert.equal((await phone.call({ t: 'hello', d: deviceDoc('pc') })).code, 'invalid');
  phone.close();
});

test('a connection outlives its token only until it next speaks', async () => {
  const uid = account();
  const c = await connect(PORT, uid, 'phone', { token: `dev:${uid}:300` });
  await c.next((f) => f.t === 'state');
  await new Promise((r) => setTimeout(r, 500));
  c.raw(JSON.stringify({ t: 'fetch', r: 1 }));
  assert.equal(await c.closedWith(), 4401);
});

test('the same device reconnecting replaces its old socket', async () => {
  const uid = account();
  const first = await join(uid, 'phone');
  const second = await join(uid, 'phone');
  assert.equal(await first.closedWith(), 4409);
  assert.equal((await second.call({ t: 'fetch' })).t, 'state');
  second.close();
});

test('expired idle sockets receive no pushes and refreshed credentials restore presence', async () => {
  const uid = account();
  const pc = await join(uid, 'pc');
  const phone = await connect(PORT, uid, 'phone', { token: `dev:${uid}:1000` });
  await phone.next((f) => f.t === 'state');
  assert.equal((await phone.call({ t: 'hello', d: deviceDoc('phone') })).t, 'ok');
  await new Promise((r) => setTimeout(r, 1100));
  const state = await pc.call({ t: 'fetch' });
  assert.equal(state.dv.find((d) => d.id === 'phone').online, false);
  assert.equal((await pc.call({ t: 'update', base: 0, s: sessionDoc({ ownerId: 'pc', updatedBy: 'pc' }) })).t, 'ok');
  assert.equal(await phone.closedWith(), 4401);
  assert.equal(phone.frames.some((f) => f.t === 'session'), false);
  await assert.rejects(connect(PORT, uid, 'phone', { token: `dev:${uid}:-1` }), (e) => e.status === 401);
  const refreshed = await join(uid, 'phone');
  assert.equal((await pc.call({ t: 'fetch' })).dv.find((d) => d.id === 'phone').online, true);
  refreshed.close();
  pc.close();
});

test('account upgrade bursts are bounded without blocking other accounts', async () => {
  const uid = account();
  const burst = await Promise.allSettled(Array.from({ length: 72 }, () => connect(PORT, uid, 'phone')));
  const rejected = burst.filter((result) => result.status === 'rejected');
  assert.ok(rejected.length > 0);
  assert.ok(rejected.every((result) => result.reason.status === 429));
  burst.forEach((result) => { if (result.status === 'fulfilled') result.value.close(); });
  const other = await join(account(), 'phone');
  other.close();
  await new Promise((r) => setTimeout(r, 1100));
  const reconnected = await join(uid, 'phone');
  reconnected.close();
});

test('a flood is refused and eventually closed, while another account is untouched', async () => {
  const [bad, good] = [account(), account()];
  const flood = await join(bad, 'phone');
  const other = await join(good, 'phone');
  const started = Date.now();
  for (let i = 0; i < 400; i++) flood.raw(JSON.stringify({ t: 'fetch', r: 100000 + i }));
  assert.equal(await flood.closedWith(8000), 4429);
  assert.ok(flood.frames.some((f) => f.code === 'slow'));
  assert.equal((await other.call({ t: 'fetch' })).t, 'state');
  assert.ok(Date.now() - started < 8000);
  other.close();
});

test('oversized and malformed frames do no harm', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  c.raw('not json');
  assert.equal((await c.next((f) => f.t === 'err')).code, 'bad-frame');
  assert.equal((await c.call({ t: 'nonsense' })).code, 'unknown');
  c.raw(JSON.stringify({ t: 'fetch', pad: 'x'.repeat(70_000) }));
  assert.equal(await c.closedWith(), 4413);
});

test('too many sockets on an account are refused', async () => {
  const uid = account();
  const open = [];
  for (let i = 0; i < 16; i++) open.push(await join(uid, `d${i}`));
  await assert.rejects(connect(PORT, uid, 'd99'), (e) => e.status === 429);
  open.forEach((c) => c.close());
});

test('sockets that vanished or are closing never lock an account out', async () => {
  const uid = account();
  for (let round = 0; round < 3; round += 1) {
    const open = [];
    for (let i = 0; i < 16; i += 1) open.push(await join(uid, `d${i}`));
    open.forEach((c) => c.close()); // gone without a closing handshake, like a process that died
  }
  const last = [];
  for (let i = 0; i < 16; i += 1) last.push(await join(uid, `d${i}`));
  assert.equal((await last[15].call({ t: 'fetch' })).t, 'state');
  last.forEach((c) => c.close());
});

test('reconnecting the same devices at the limit always works', async () => {
  const uid = account();
  const open = [];
  for (let i = 0; i < 16; i += 1) open.push(await join(uid, `d${i}`));
  const again = [];
  for (let i = 0; i < 16; i += 1) again.push(await join(uid, `d${i}`));
  assert.equal(again.length, 16);
  [...open, ...again].forEach((c) => c.close());
});

test('a text ping is answered without reaching the account', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  const pong = new Promise((resolve) => { c.ws.on('message', (d) => { if (d.toString() === 'pong') resolve(true); }); });
  c.raw('ping');
  assert.equal(await Promise.race([pong, new Promise((r) => setTimeout(() => r(false), 2000))]), true);
  assert.equal((await c.call({ t: 'fetch' })).t, 'state');
  c.close();
});

test('staging diagnostics say whether the object stayed in memory', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  const first = await c.call({ t: 'stats' });
  const second = await c.call({ t: 'stats' });
  assert.equal(first.instance, second.instance);
  assert.equal(second.handled, first.handled + 1);
  c.close();
});

test('a clean close finishes quickly on both sides', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  const started = Date.now();
  c.ws.close(1000);
  assert.equal(await c.closedWith(3000), 1000);
  assert.ok(Date.now() - started < 2000, 'The server must answer a close');
});

test('the intent is stored, read back, and never goes backwards', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  assert.equal((await c.call({ t: 'get-intent' })).i, null);
  const i = { version: 5, epoch: 1, at: Date.now(), by: 'phone' };
  assert.equal((await c.call({ t: 'intent', i })).t, 'ok');
  assert.equal((await c.call({ t: 'intent', i: { ...i, version: 4 } })).code, 'refused');
  assert.deepEqual((await c.call({ t: 'get-intent' })).i, i);
  c.close();
});

test('state survives every socket closing, and a position report is not lost', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  await c.call({ t: 'update', base: 0, s: sessionDoc() });
  await c.call({ t: 'update', base: 1, s: sessionDoc({ version: 2, positionMs: 777 }) });
  c.close();
  await new Promise((r) => setTimeout(r, 300));
  const back = await connect(PORT, uid, 'phone');
  const state = await back.next((f) => f.t === 'state');
  assert.equal(state.s.positionMs, 777);
  assert.ok(state.dv.some((d) => d.id === 'phone'));
  back.close();
});

test('a routine report survives hibernation on the socket, without storage', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  await c.call({ t: 'update', base: 0, s: sessionDoc() });
  await c.call({ t: 'update', base: 1, s: sessionDoc({ version: 2, positionMs: 1234 }) });
  await new Promise((r) => setTimeout(r, 12_000));
  const state = await c.call({ t: 'fetch' });
  assert.equal(state.s.version, 2);
  assert.equal(state.s.positionMs, 1234);
  c.close();
}, { timeout: 30_000 });

test('a device cannot wipe account Connect state', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  await c.call({ t: 'update', base: 0, s: sessionDoc() });
  assert.equal((await c.call({ t: 'wipe' })).code, 'unknown');
  const state = await c.call({ t: 'fetch' });
  assert.equal(state.s.version, 1);
  assert.ok(state.dv.some((d) => d.id === 'phone'));
  c.close();
});

test('the intent survives every socket closing', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  const i = { version: 5, epoch: 1, at: Date.now(), by: 'phone' };
  await c.call({ t: 'intent', i });
  c.close();
  await new Promise((r) => setTimeout(r, 300));
  const back = await join(uid, 'phone');
  assert.deepEqual((await back.call({ t: 'get-intent' })).i, i);
  back.close();
});

test('state held by a leaving device stays with those still here', async () => {
  const uid = account();
  const [phone, pc] = [await join(uid, 'phone'), await join(uid, 'pc')];
  await phone.call({ t: 'update', base: 0, s: sessionDoc() });
  await phone.call({ t: 'update', base: 1, s: sessionDoc({ version: 2, positionMs: 4321 }) });
  phone.close();
  await new Promise((r) => setTimeout(r, 300));
  assert.equal((await pc.call({ t: 'fetch' })).s.positionMs, 4321);
  pc.close();
});

test('a server crash keeps the output, and versions only move forward', async () => {
  const uid = account();
  const c = await join(uid, 'phone');
  await c.call({ t: 'update', base: 0, s: sessionDoc({ playing: true }) });
  await c.call({ t: 'update', base: 1, s: sessionDoc({ version: 2, ownerId: 'pc', ownerName: 'PC', epoch: 2, updatedBy: 'phone' }) });
  await c.call({ t: 'update', base: 2, s: sessionDoc({ version: 3, ownerId: 'pc', ownerName: 'PC', epoch: 2, updatedBy: 'phone', positionMs: 50 }) });
  await stopServer(server);
  assert.notEqual(await c.closedWith(5000), null, 'Clients learn the server went away');
  server = await startServer(PORT, server.persistDir);
  const back = await connect(PORT, uid, 'phone');
  const state = await back.next((f) => f.t === 'state');
  assert.equal(state.s.ownerId, 'pc');
  assert.equal(state.s.epoch, 2);
  assert.ok(state.s.version > 3, `version ${state.s.version} must pass everything handed out`);
  assert.equal((await back.call({ t: 'hello', d: deviceDoc('phone') })).t, 'ok');
  back.close();
}, { timeout: 120_000 });

test('pre-auth upgrade bursts are limited before reaching an account', async () => {
  const statuses = [];
  for (let attempt = 0; attempt < 305; attempt += 1) {
    await assert.rejects(connect(PORT, 'unused', 'phone', { token: null }), (error) => {
      statuses.push(error.status);
      return error.status === 401 || error.status === 429;
    });
    if (statuses.at(-1) === 429) break;
  }
  assert.equal(statuses.at(-1), 429);
});
