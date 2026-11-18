import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { connect, startServer, stopServer } from './harness.mjs';

const PORT = 8797;
let server;
let rooms = 0;
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const roomCode = () => { const n = Date.now() + rooms++ * 7919; return [...Array(6)].map((_, i) => ALPHABET[Math.floor(n / 32 ** i) % 32]).join(''); };
const enter = async (uid, code) => {
  const c = await connect(PORT, uid, 'd', { path: `/v1/together?room=${code}&x=` });
  await c.next((f) => f.t === 'state');
  return c;
};
const member = (name) => ({ name, lastSeen: Date.now(), joinedAt: Date.now() });
const roomDoc = () => ({ hostName: 'Host', currentTrack: { id: 't1', title: 'One' }, queue: [], queueIndex: -1, isPlaying: true, playbackPosition: 0, updatedAt: Date.now(), settings: {} });

before(async () => { server = await startServer(PORT); }, { timeout: 120_000 });
after(async () => { await stopServer(server); });

test('a bad room code or missing token is refused before reaching a room', async () => {
  await assert.rejects(connect(PORT, 'u', 'd', { path: '/v1/together?room=bad&x=' }), (e) => e.status === 400);
  await assert.rejects(connect(PORT, 'u', 'd', { token: null, path: `/v1/together?room=${roomCode()}&x=` }), (e) => e.status === 401);
});

test('host creates, guest joins, and both see playback and members live', async () => {
  const code = roomCode();
  const host = await enter('host', code);
  const made = await host.call({ t: 'create', room: roomDoc(), member: member('Host') });
  assert.equal(made.t, 'ok');
  assert.equal(made.state.room.hostId, 'host');

  const guest = await enter('guest', code);
  const joined = await guest.call({ t: 'join', member: member('Guest') });
  assert.equal(joined.t, 'ok');
  assert.equal(joined.state.members.length, 2);
  const roster = await host.next((f) => f.t === 'members' && f.members.length === 2);
  assert.ok(roster.members.find((m) => m.id === 'guest').serverSeen > 0);

  await host.call({ t: 'room', set: { isPlaying: false, playbackPosition: 42_000, updatedAt: Date.now() } });
  const pushed = await guest.next((f) => f.t === 'room' && f.room?.isPlaying === false);
  assert.equal(pushed.room.playbackPosition, 42_000);
  assert.ok(pushed.room.serverUpdatedAt > 0);

  assert.equal((await guest.call({ t: 'room', set: { isPlaying: true } })).code, 'denied');
  host.close(); guest.close();
});

test('heartbeats answer with server time; guest commands reach the host', async () => {
  const code = roomCode();
  const host = await enter('h2', code);
  await host.call({ t: 'create', room: roomDoc(), member: member('Host') });
  const guest = await enter('g2', code);
  await guest.call({ t: 'join', member: member('Guest') });
  const before = Date.now();
  const beat = await guest.call({ t: 'member', set: { lastSeen: before } });
  assert.ok(beat.now >= before - 5_000);
  await guest.call({ t: 'member', set: { command: { type: 'pause', seq: 1 } } });
  const seen = await host.next((f) => f.t === 'members' && f.members.some((m) => m.command?.seq === 1));
  assert.equal(seen.members.find((m) => m.id === 'g2').command.type, 'pause');
  host.close(); guest.close();
});

test('song requests: guests suggest, host decides, outsiders see nothing', async () => {
  const code = roomCode();
  const host = await enter('h3', code);
  await host.call({ t: 'create', room: roomDoc(), member: member('Host') });
  const guest = await enter('g3', code);
  await guest.call({ t: 'join', member: member('Guest') });
  const made = await guest.call({ t: 'rec', rec: { track: { id: 'x', title: 'X' }, recommendedByName: 'Guest' } });
  assert.ok(made.id);
  const recs = await host.next((f) => f.t === 'recs' && f.recs.length === 1);
  assert.equal(recs.recs[0].recommendedByUid, 'g3');
  assert.equal((await guest.call({ t: 'rec-status', id: made.id, status: 'accepted' })).code, 'denied');
  await host.call({ t: 'rec-status', id: made.id, status: 'accepted' });
  await guest.next((f) => f.t === 'recs' && f.recs[0]?.status === 'accepted');
  const outsider = await enter('nosy', code);
  const fetched = await outsider.call({ t: 'fetch' });
  assert.equal(fetched.members, null);
  assert.equal(fetched.recs, null);
  host.close(); guest.close(); outsider.close();
});

test('state survives everyone disconnecting and the newest playback is kept', async () => {
  const code = roomCode();
  const host = await enter('h4', code);
  await host.call({ t: 'create', room: roomDoc(), member: member('Host') });
  await host.call({ t: 'room', set: { playbackPosition: 99_000, updatedAt: Date.now() } });
  host.close();
  await new Promise((r) => setTimeout(r, 500));
  const back = await connect(PORT, 'h4', 'd', { path: `/v1/together?room=${code}&x=` });
  const state = await back.next((f) => f.t === 'state');
  assert.equal(state.room.playbackPosition, 99_000);
  back.close();
});

test('the host leaving closes the room for everyone and forgets it', async () => {
  const code = roomCode();
  const host = await enter('h5', code);
  await host.call({ t: 'create', room: roomDoc(), member: member('Host') });
  const guest = await enter('g5', code);
  await guest.call({ t: 'join', member: member('Guest') });
  await host.call({ t: 'leave' });
  const ended = await guest.next((f) => f.t === 'room' && f.room === null);
  assert.equal(ended.room, null);
  assert.equal(await guest.closedWith(), 4000);
  const late = await enter('g6', code);
  assert.equal((await late.call({ t: 'join', member: member('Late') })).code, 'not-found');
  late.close();
});

test('a second socket for the same person replaces the first', async () => {
  const code = roomCode();
  const first = await enter('h7', code);
  await first.call({ t: 'create', room: roomDoc(), member: member('Host') });
  const second = await enter('h7', code);
  assert.equal(await first.closedWith(), 4409);
  assert.equal((await second.call({ t: 'room', set: { isPlaying: false } })).t, 'ok');
  second.close();
});
