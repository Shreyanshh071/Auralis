// Behaviour tests for the pure queue / list ordering helpers.
//
// Run with:  node scripts/test-queue-ops.mjs
//
// Imports src/lib/queueOps.ts directly (Node type-stripping, 22.18+). These are
// the exact functions PlayerContext uses to reorder and remove queue entries and
// to reorder playlist tracks, so a passing run proves the ordering rules hold.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'lib', 'queueOps.ts');

const mod = await import(pathToFileURL(modulePath).href);
const { moveItem, mapIndexAfterMove, reorderQueue, removeAt } = mod;

const track = (id) => ({ id, title: `Track ${id}`, artist: 'Someone', duration: 180 });
const ids = (list) => list.map((t) => t.id);

// ---------------------------------------------------------------------------
// moveItem
// ---------------------------------------------------------------------------

test('moveItem drops the element at the destination index, both directions', () => {
  assert.deepEqual(moveItem(['A', 'B', 'C', 'D'], 1, 3), ['A', 'C', 'D', 'B']);
  assert.deepEqual(moveItem(['A', 'B', 'C', 'D'], 3, 1), ['A', 'D', 'B', 'C']);
  assert.deepEqual(moveItem(['A', 'B', 'C'], 0, 2), ['B', 'C', 'A']);
  assert.deepEqual(moveItem(['A', 'B', 'C'], 2, 0), ['C', 'A', 'B']);
});

test('moveItem never mutates its input and copes with edges', () => {
  const original = ['A', 'B', 'C'];
  const moved = moveItem(original, 0, 2);
  assert.deepEqual(original, ['A', 'B', 'C']); // untouched
  assert.notEqual(moved, original);

  // Out-of-range indices are clamped rather than throwing.
  assert.deepEqual(moveItem(['A', 'B', 'C'], -5, 99), ['B', 'C', 'A']);
  // A no-op move still returns a fresh copy.
  const same = moveItem(original, 1, 1);
  assert.deepEqual(same, original);
  assert.notEqual(same, original);
  // Zero / one element lists are returned as copies.
  assert.deepEqual(moveItem([], 0, 1), []);
  assert.deepEqual(moveItem(['A'], 0, 0), ['A']);
});

// ---------------------------------------------------------------------------
// mapIndexAfterMove  (positional, duplicate-safe)
// ---------------------------------------------------------------------------

test('mapIndexAfterMove tracks every slot through a move', () => {
  // list [A,B,C,D], move 1 -> 3  ==> [A,C,D,B]
  assert.equal(mapIndexAfterMove(0, 1, 3), 0); // A
  assert.equal(mapIndexAfterMove(1, 1, 3), 3); // B (the moved one)
  assert.equal(mapIndexAfterMove(2, 1, 3), 1); // C
  assert.equal(mapIndexAfterMove(3, 1, 3), 2); // D

  // list [A,B,C,D], move 3 -> 1  ==> [A,D,B,C]
  assert.equal(mapIndexAfterMove(0, 3, 1), 0); // A
  assert.equal(mapIndexAfterMove(1, 3, 1), 2); // B
  assert.equal(mapIndexAfterMove(2, 3, 1), 3); // C
  assert.equal(mapIndexAfterMove(3, 3, 1), 1); // D (the moved one)
});

// ---------------------------------------------------------------------------
// reorderQueue  (keeps the playing track under the index)
// ---------------------------------------------------------------------------

test('reorderQueue keeps the index on the same playing track', () => {
  const q = [track('a'), track('b'), track('c'), track('d')];

  // Playing 'c' (index 2). Move an item from before it to after it.
  let r = reorderQueue(q, 2, 0, 3); // move a -> 3  => [b,c,d,a]
  assert.deepEqual(ids(r.tracks), ['b', 'c', 'd', 'a']);
  assert.equal(r.tracks[r.index].id, 'c');

  // Playing 'b' (index 1). Move the playing track itself.
  r = reorderQueue(q, 1, 1, 3); // move b -> 3 => [a,c,d,b]
  assert.deepEqual(ids(r.tracks), ['a', 'c', 'd', 'b']);
  assert.equal(r.tracks[r.index].id, 'b');

  // Playing 'a' (index 0). Move a later item to the front.
  r = reorderQueue(q, 0, 3, 0); // move d -> 0 => [d,a,b,c]
  assert.deepEqual(ids(r.tracks), ['d', 'a', 'b', 'c']);
  assert.equal(r.tracks[r.index].id, 'a');
});

test('reorderQueue is a safe no-op on tiny queues', () => {
  assert.deepEqual(reorderQueue([], 0, 0, 1), { tracks: [], index: 0 });
  const one = [track('a')];
  const r = reorderQueue(one, 0, 0, 0);
  assert.deepEqual(ids(r.tracks), ['a']);
  assert.equal(r.index, 0);
});

test('reorderQueue keeps the right slot even with duplicate track ids', () => {
  // Same track queued twice; index must follow POSITION, not id.
  const q = [track('a'), track('dup'), track('b'), track('dup')];
  // Playing the second 'dup' (index 3). Move 'a' to the end.
  const r = reorderQueue(q, 3, 0, 3); // [dup,b,dup,a]
  assert.deepEqual(ids(r.tracks), ['dup', 'b', 'dup', 'a']);
  // The originally-playing element was the LAST dup; after moving a to the end
  // it sits at index 2.
  assert.equal(r.index, 2);
});

// ---------------------------------------------------------------------------
// removeAt  (the bug the old inline filter had)
// ---------------------------------------------------------------------------

test('removeAt decrements the index when an earlier track is removed', () => {
  const q = [track('a'), track('b'), track('c'), track('d')];
  // Playing 'c' (index 2); remove 'a' (index 0).
  const r = removeAt(q, 2, 0);
  assert.deepEqual(ids(r.tracks), ['b', 'c', 'd']);
  assert.equal(r.tracks[r.index].id, 'c'); // still pointing at c
});

test('removeAt leaves the index alone when a later track is removed', () => {
  const q = [track('a'), track('b'), track('c'), track('d')];
  const r = removeAt(q, 1, 3); // playing 'b', remove 'd'
  assert.deepEqual(ids(r.tracks), ['a', 'b', 'c']);
  assert.equal(r.tracks[r.index].id, 'b');
});

test('removeAt keeps a valid, playable index when the current track is removed', () => {
  const q = [track('a'), track('b'), track('c'), track('d')];
  const r = removeAt(q, 2, 2); // playing 'c', remove 'c'
  assert.deepEqual(ids(r.tracks), ['a', 'b', 'd']);
  // The slot now holds 'd' (what was next); index stays in range and playable.
  assert.equal(r.index, 2);
  assert.equal(r.tracks[r.index].id, 'd');

  // Removing the last track while it is current clamps back into range.
  const r2 = removeAt([track('a'), track('b')], 1, 1);
  assert.deepEqual(ids(r2.tracks), ['a']);
  assert.equal(r2.index, 0);
});

test('removeAt on an out-of-range or emptying queue is safe', () => {
  const q = [track('a'), track('b')];
  assert.deepEqual(removeAt(q, 0, 5).tracks.map((t) => t.id), ['a', 'b']); // no-op
  assert.deepEqual(removeAt(q, 0, -1).tracks.map((t) => t.id), ['a', 'b']); // no-op
  const emptied = removeAt([track('a')], 0, 0);
  assert.deepEqual(emptied.tracks, []);
  assert.equal(emptied.index, 0);
});
