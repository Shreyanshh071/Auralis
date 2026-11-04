import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RateLimit, UNSAVED_VERSIONS, decideUpdate, intentAccepted, mustSave, recoverSession, validDevice, validDeviceId, validIntent, validQueue, validSession } from '../src/core.js';

const NOW = 1_000_000;
export const session = (extra = {}) => ({
  version: 1, ownerId: 'phone', ownerName: 'Pixel', ownerType: 'PHONE', epoch: 1, leaseUntil: NOW + 60_000, track: { id: 's' }, playing: true,
  buffering: false, positionMs: 0, positionAt: NOW, durationMs: 1, queueRev: 1, queueIndex: 0, shuffle: false, repeat: 'OFF', context: null,
  transfer: null, commands: {}, updatedBy: 'phone', ...extra,
});

test('a first write must be version 1', () => {
  assert.equal(decideUpdate(null, 0, session(), 'phone', NOW).result, 'ok');
  assert.equal(decideUpdate(null, 0, session({ version: 5 }), 'phone', NOW).code, 'bad-version');
});

test('a write based on an older version is a conflict, so the client redoes it', () => {
  const current = session({ version: 7 });
  assert.equal(decideUpdate(current, 6, session({ version: 7 }), 'phone', NOW).result, 'conflict');
});

test('every write is exactly one version newer', () => {
  const current = session({ version: 7 });
  assert.equal(decideUpdate(current, 7, session({ version: 9 }), 'phone', NOW).code, 'bad-version');
  assert.equal(decideUpdate(current, 7, session({ version: 8 }), 'phone', NOW).result, 'ok');
});

test('the server stamps its own time', () => {
  assert.equal(decideUpdate(null, 0, session({ updatedAt: 5 }), 'phone', NOW).session.updatedAt, NOW);
});

test('a device writes only as itself', () => {
  assert.equal(decideUpdate(null, 0, session({ updatedBy: 'someone-else' }), 'phone', NOW).code, 'not-this-device');
});

test('ownership moves only through a handoff, an expired lease, or the owner itself', () => {
  const live = session({ version: 2 });
  const takeover = session({ version: 3, ownerId: 'pc', epoch: 2, updatedBy: 'pc' });
  assert.equal(decideUpdate(live, 2, takeover, 'pc', NOW).code, 'not-allowed');
  assert.equal(decideUpdate({ ...live, leaseUntil: NOW - 1 }, 2, takeover, 'pc', NOW).result, 'ok');
  const handoff = { id: 't', to: 'pc', epoch: 2, status: 'ready' };
  assert.equal(decideUpdate({ ...live, transfer: handoff }, 2, takeover, 'pc', NOW).result, 'ok');
  assert.equal(decideUpdate({ ...live, transfer: { ...handoff, status: 'failed' } }, 2, takeover, 'pc', NOW).code, 'not-allowed');
  assert.equal(decideUpdate(live, 2, session({ version: 3, ownerId: 'pc', epoch: 2, updatedBy: 'phone' }), 'phone', NOW).result, 'ok');
  assert.equal(decideUpdate(live, 2, session({ version: 3, ownerId: 'pc', epoch: 5, updatedBy: 'phone' }), 'phone', NOW).code, 'not-allowed');
});

test('sessions keep firestore.rules limits', () => {
  assert.equal(validSession(session(), NOW), true);
  assert.equal(validSession(session({ extra: 1 }), NOW), false);
  assert.equal(validSession(session({ leaseUntil: NOW + 120_001 }), NOW), false);
  assert.equal(validSession(session({ repeat: 'NOPE' }), NOW), false);
  assert.equal(validSession(session({ ownerId: 'x'.repeat(65) }), NOW), false);
  assert.equal(validSession(session({ commands: Object.fromEntries(Array.from({ length: 21 }, (_, i) => [i, {}])) }), NOW), false);
  assert.equal(validSession(session({ transfer: { status: 'odd' } }), NOW), false);
  assert.equal(validSession('text', NOW), false);
});

test('queues keep their limits', () => {
  assert.equal(validQueue({ rev: 1, offset: 0, tracks: [] }), true);
  assert.equal(validQueue({ rev: 1, offset: 0, tracks: new Array(101).fill({}) }), false);
  assert.equal(validQueue({ rev: 1, offset: 0, tracks: [], extra: true }), false);
});

test('the intent keeps its shape and never goes backwards', () => {
  const i = { version: 5, epoch: 2, at: NOW, by: 'phone' };
  assert.equal(validIntent(i, NOW), true);
  assert.equal(validIntent({ ...i, extra: 1 }, NOW), false);
  assert.equal(validIntent({ ...i, at: NOW + 120_001 }, NOW), false);
  assert.equal(intentAccepted(i, { ...i, version: 4 }, NOW), false);
  assert.equal(intentAccepted(i, { ...i, version: 5, by: 'pc' }, NOW), true);
});

test('a device can only describe itself', () => {
  const d = { id: 'a', name: 'n', nickname: null, platform: 'ANDROID', type: 'PHONE', appVersion: '1', canPlay: true, online: true, createdAt: 1, seenAt: 1 };
  assert.equal(validDevice(d, 'a'), true);
  assert.equal(validDevice(d, 'b'), false);
  assert.equal(validDevice({ ...d, type: 'TOASTER' }, 'a'), false);
  assert.equal(validDeviceId('a/../b'), false);
  assert.equal(validDeviceId('x'.repeat(65)), false);
});

test('only ownership must reach storage at once', () => {
  const before = session();
  assert.equal(mustSave(before, { ...before, version: 2, positionMs: 5, playing: false, track: { id: 'other' } }), false);
  assert.equal(mustSave(before, { ...before, version: 2, ownerId: 'pc', epoch: 2 }), true);
  assert.equal(mustSave(before, { ...before, version: 2, epoch: 2 }), true);
  assert.equal(mustSave(null, before), true);
});

test('a cold start moves past every version that may have been handed out', () => {
  assert.equal(recoverSession(session({ version: 10 })).version, 10 + UNSAVED_VERSIONS);
  assert.equal(recoverSession(null), null);
});

test('a burst passes, a flood is refused, and it recovers', () => {
  const limit = new RateLimit(40, 10);
  limit.at = 0;
  let passed = 0;
  for (let i = 0; i < 100; i++) if (limit.allow(0)) passed++;
  assert.equal(passed, 40);
  assert.equal(limit.refused, 60);
  assert.equal(limit.allow(1_000), true);
  assert.equal(limit.refused, 60, 'One token is not calm');
  assert.equal(limit.allow(4_000), true);
  assert.equal(limit.refused, 0);
});
