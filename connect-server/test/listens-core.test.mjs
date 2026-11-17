import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_DAY_EVENTS, clearBefore, dayOf, eventKey, listenEvent, mergeListens } from '../src/listens-core.js';

const DAY = Date.UTC(2026, 10, 17, 10);
const ev = (t, ts, ms, extra = {}) => ({ t, ts, ms, ti: `Song ${t}`, ar: 'A', al: null, th: '', du: 200, d: 'pc', ...extra });

test('events merge as a union by key, and the longer listen wins', () => {
  const first = mergeListens({}, [ev('a', DAY, 30_000), ev('b', DAY + 1, 10_000)]);
  const day = first.get(dayOf(DAY)).map;
  assert.deepEqual(Object.keys(day).sort(), [eventKey(ev('a', DAY, 1)), eventKey(ev('b', DAY + 1, 1))].sort());
  const longer = mergeListens({ [dayOf(DAY)]: day }, [ev('a', DAY, 90_000, { d: 'phone' })]);
  assert.equal(longer.get(dayOf(DAY)).map[eventKey(ev('a', DAY, 1))].ms, 90_000);
  const shorter = mergeListens({ [dayOf(DAY)]: longer.get(dayOf(DAY)).map }, [ev('a', DAY, 5_000)]);
  assert.equal(shorter.size, 0, 'a shorter copy changes nothing');
});

test('the same events in any order or twice give the same days', () => {
  const a = [ev('a', DAY, 30_000)]; const b = [ev('a', DAY, 60_000), ev('c', DAY + 5, 1_000)];
  const apply = (days, list) => { const out = { ...days }; for (const [d, { map }] of mergeListens(days, list)) out[d] = map; return out; };
  const ab = apply(apply({}, a), b); const ba = apply(apply({}, b), a);
  assert.deepEqual(ab, ba);
  assert.equal(mergeListens(ab, b).size, 0);
});

test('only the changed events are reported, per day', () => {
  const next = DAY + 86_400_000;
  const changed = mergeListens({}, [ev('a', DAY, 1_000), ev('b', next, 2_000)]);
  assert.deepEqual([...changed.keys()].sort(), [dayOf(DAY), dayOf(next)].sort());
  assert.equal(Object.keys(changed.get(dayOf(next)).delta).length, 1);
});

test('cleared, malformed and over-cap events are dropped', () => {
  assert.equal(mergeListens({}, [ev('a', DAY, 1_000)], DAY + 1).size, 0, 'before the clear marker');
  assert.equal(listenEvent({ t: 'a', ts: DAY, ms: 0 }), null);
  assert.equal(listenEvent({ t: '', ts: DAY, ms: 5 }), null);
  assert.equal(listenEvent({ t: 'a', ts: -1, ms: 5 }), null);
  const full = Object.fromEntries(Array.from({ length: MAX_DAY_EVENTS }, (_, i) => [`${DAY + i}_x`, ev('x', DAY + i, 1)]));
  assert.equal(mergeListens({ [dayOf(DAY)]: full }, [ev('new', DAY + 999_999, 5)]).size, 0);
  assert.deepEqual(Object.keys(clearBefore({ k1: ev('a', DAY, 1), k2: ev('b', DAY + 10, 1) }, DAY + 5)), ['k2']);
});

test('artist credits survive history normalization and a longer legacy copy', () => {
  const artists = JSON.stringify([{ n: 'Tyler, The Creator', c: 'UC-one' }, { n: 'Guest', c: 'UC-two' }]);
  const original = ev('collab', DAY, 1000, { as: artists });
  assert.equal(listenEvent(original).as, artists);
  const first = mergeListens({}, [original]).get(dayOf(DAY)).map;
  const longer = mergeListens({ [dayOf(DAY)]: first }, [ev('collab', DAY, 2000)]).get(dayOf(DAY)).map;
  assert.equal(longer[eventKey(original)].as, artists);
  assert.equal(longer[eventKey(original)].ms, 2000);
  assert.equal(listenEvent(ev('bad', DAY, 1000, { as: 'x'.repeat(8193) })).as, undefined);
});
