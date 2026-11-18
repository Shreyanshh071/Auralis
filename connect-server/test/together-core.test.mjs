import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_MEMBERS, changesStored, create, deleteRec, emptyRoom, join, leave, recommend, setRecStatus, split, updateMember,
  updateRoom, upvote, validRoomCode, view, ROOM_VOLATILE } from '../src/together-core.js';

const room = () => ({ hostName: 'Host', currentTrack: { id: 't1' }, queue: [], isPlaying: true, playbackPosition: 0, updatedAt: 1, settings: {} });
const member = (name = 'M') => ({ name, lastSeen: 1, joinedAt: 1 });
const made = () => create(emptyRoom(), 'host', 'ABC234', room(), member('Host'), 100).state;

test('room codes use the app alphabet', () => {
  assert.ok(validRoomCode('ABC234'));
  for (const bad of ['abc234', 'ABC23', 'ABC2345', 'ABCI01', '', null]) assert.ok(!validRoomCode(bad));
});

test('creating sets the host and server fields, whatever the client claims', () => {
  const r = create(emptyRoom(), 'host', 'ABC234', { ...room(), hostId: 'someone', code: 'ZZZZZZ', serverUpdatedAt: 5 }, { ...member(), isHost: false, id: 'x' }, 100);
  assert.equal(r.state.room.hostId, 'host');
  assert.equal(r.state.room.code, 'ABC234');
  assert.equal(r.state.room.serverUpdatedAt, 100);
  assert.equal(r.state.room.status, 'active');
  assert.deepEqual({ id: r.state.members.host.id, isHost: r.state.members.host.isHost, serverSeen: r.state.members.host.serverSeen }, { id: 'host', isHost: true, serverSeen: 100 });
  assert.equal(create(r.state, 'other', 'ABC234', room(), member(), 1).error, 'exists');
});

test('joining needs an active room and stamps presence', () => {
  assert.equal(join(emptyRoom(), 'g', member(), 1).error, 'not-found');
  const s = join(made(), 'g', { ...member('G'), isHost: true }, 200).state;
  assert.equal(s.members.g.isHost, false);
  assert.equal(s.members.g.serverSeen, 200);
  const closed = updateRoom(made(), 'host', { status: 'closed' }, 1).state;
  assert.equal(join(closed, 'g', member(), 1).error, 'closed');
});

test('a room holds at most MAX_MEMBERS, but members already in can rejoin', () => {
  let s = made();
  for (let i = 1; i < MAX_MEMBERS; i++) s = join(s, `g${i}`, member(), 1).state;
  assert.equal(join(s, 'late', member(), 1).error, 'full');
  assert.ok(join(s, 'g1', member(), 1).state);
});

test('only the host updates the room, and it cannot change who hosts it', () => {
  const s = join(made(), 'g', member(), 1).state;
  assert.equal(updateRoom(s, 'g', { isPlaying: false }, 1).error, 'denied');
  const r = updateRoom(s, 'host', { isPlaying: false, hostId: 'g', updatedAt: 9 }, 300);
  assert.equal(r.state.room.hostId, 'host');
  assert.equal(r.state.room.isPlaying, false);
  assert.equal(r.state.room.serverUpdatedAt, 300);
  assert.equal(r.stored, false, 'playback-only changes stay off storage');
  assert.equal(updateRoom(s, 'host', { currentTrack: { id: 't2' } }, 1).stored, true);
  assert.equal(updateRoom(s, 'host', { currentTrack: { id: 't1' }, playbackPosition: 5 }, 1).stored, false, 'an unchanged track is not a change');
});

test('members change only their own record, and network fields merge', () => {
  let s = join(made(), 'g', member(), 1).state;
  assert.equal(updateMember(s, 'stranger', { name: 'x' }, 1).error, 'not-member');
  s = updateMember(s, 'g', { network: { slow: true } }, 1).state;
  s = updateMember(s, 'g', { network: { readyFor: 't1' }, isHost: true }, 1).state;
  assert.deepEqual(s.members.g.network, { slow: true, readyFor: 't1' });
  assert.equal(s.members.g.isHost, false);
  const beat = updateMember(s, 'g', { lastSeen: 50 }, 400);
  assert.equal(beat.state.members.g.serverSeen, 400);
  assert.equal(beat.stored, false, 'heartbeats stay off storage');
});

test('a guest leaving removes them; the host leaving closes the room', () => {
  const s = join(made(), 'g', member(), 1).state;
  assert.equal(leave(s, 'g').state.members.g, undefined);
  const closed = leave(s, 'host');
  assert.equal(closed.closed, true);
  assert.equal(closed.state.room, null);
});

test('song requests follow the Firestore rules', () => {
  let s = join(made(), 'g', member(), 1).state;
  assert.equal(recommend(s, 'stranger', 'r1', { track: { id: 'x' } }, 1).error, 'not-member');
  const r = recommend(s, 'g', 'r1', { track: { id: 'x' }, recommendedByUid: 'host', status: 'accepted', upvotes: ['a', 'b'] }, 5);
  s = r.state;
  assert.deepEqual([s.recs.r1.recommendedByUid, s.recs.r1.status, s.recs.r1.upvotes], ['g', 'pending', ['g']]);
  s = upvote(s, 'host', 'r1').state;
  assert.deepEqual(s.recs.r1.upvotes, ['g', 'host']);
  s = upvote(s, 'host', 'r1').state;
  assert.deepEqual(s.recs.r1.upvotes, ['g']);
  assert.equal(setRecStatus(s, 'g', 'r1', 'accepted').error, 'denied');
  assert.equal(setRecStatus(s, 'host', 'r1', 'whatever').error, 'invalid');
  s = setRecStatus(s, 'host', 'r1', 'accepted').state;
  assert.equal(s.recs.r1.status, 'accepted');
  const s2 = join(s, 'h', member(), 1).state;
  assert.equal(deleteRec(s2, 'h', 'r1').error, 'denied');
  assert.equal(deleteRec(s2, 'g', 'r1').state.recs.r1, undefined);
  assert.equal(deleteRec(s2, 'host', 'r1').state.recs.r1, undefined);
});

test('outsiders see the room but not who is in it', () => {
  const s = join(made(), 'g', member(), 1).state;
  assert.equal(view(s, 'stranger').members, null);
  assert.equal(view(s, 'g').members.length, 2);
});

test('split and changesStored separate playback fields from the rest', () => {
  const { stored, vol } = split({ isPlaying: true, queue: [] }, ROOM_VOLATILE);
  assert.deepEqual([stored, vol], [{ queue: [] }, { isPlaying: true }]);
  assert.equal(changesStored({ queue: [] }, { queue: [] }, ROOM_VOLATILE), false);
});
