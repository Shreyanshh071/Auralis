import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RETIRE_ACKNOWLEDGEMENT, bridgeActive, mergeSettings, newer, preference, topicEnabled, validSettingKey } from '../src/settings-core.js';

const p = (value, revision, device) => ({ value, revision, device });

test('merge follows the apps\' rule: higher revision wins, then the device id', () => {
  const remote = { 'a.theme': p('dark', 3, 'phone') };
  assert.deepEqual(mergeSettings(remote, { 'a.theme': p('light', 2, 'pc') }, {}).changed, [], 'an older edit loses');
  assert.equal(mergeSettings(remote, { 'a.theme': p('light', 4, 'pc') }, {}).result['a.theme'].value, 'light');
  assert.equal(mergeSettings(remote, { 'a.theme': p('light', 3, 'zz') }, {}).result['a.theme'].value, 'light', 'tie: larger device id');
  assert.equal(mergeSettings(remote, { 'a.theme': p('light', 3, 'aa') }, {}).result['a.theme'].value, 'dark');
  assert.equal(newer(p('x', 3, 'b'), p('y', 3, 'a')), true);
});

test('an empty account starts from the device\'s values at revision 0; a non-empty one ignores the seed', () => {
  const first = mergeSettings({}, {}, { 'a.theme': 'dark', 'bad key': 'x' });
  assert.deepEqual(first.result, { 'a.theme': p('dark', 0, 'initial') });
  assert.deepEqual(mergeSettings({ 'a.x': p('1', 1, 'd') }, {}, { 'a.theme': 'dark' }).changed, []);
  // An edit beats the seed of the same exchange.
  assert.equal(mergeSettings({}, { 'a.theme': p('light', 1, 'pc') }, { 'a.theme': 'dark' }).result['a.theme'].value, 'light');
});

test('merging the same edits again or in another order gives the same result', () => {
  const a = { 'a.theme': p('dark', 5, 'phone') }; const b = { 'a.theme': p('light', 5, 'pc'), 'p.speed': p('1.5', 2, 'pc') };
  const ab = mergeSettings(mergeSettings({}, a, {}).result, b, {}).result;
  const ba = mergeSettings(mergeSettings({}, b, {}).result, a, {}).result;
  assert.deepEqual(ab, ba);
  assert.deepEqual(mergeSettings(ab, b, {}).changed, [], 'applying again changes nothing');
});

test('malformed edits are ignored', () => {
  assert.equal(preference(p('x', 0, 'd'), 1), null, 'edits need revision ≥ 1');
  assert.equal(preference(p('x', 1_000_000_001, 'd')), null);
  assert.equal(preference(p('x', 1, '')), null);
  assert.equal(preference(p('x'.repeat(1025), 1, 'd')), null);
  assert.equal(validSettingKey('a.lyrics_animation'), true);
  assert.equal(validSettingKey('A.x'), false);
  assert.deepEqual(mergeSettings({}, { 'a.ok': p('1', 1, 'd'), 'nope': p('1', 1, 'd'), 'a.bad': { value: 3 } }, {}).changed, ['a.ok']);
});

test('the bridge never retires by itself, however long an account has been quiet', () => {
  const day = 86_400_000;
  assert.equal(bridgeActive({ settingsStartedAt: 1, legacySeenAt: 0 }), true, 'a year with no old-app activity: still on');
  assert.equal(bridgeActive({ settingsStartedAt: 1, legacySeenAt: 40 * day }), true);
  assert.equal(bridgeActive({ bridgeRetiredAt: 5 }), false, 'only an explicit retirement turns it off');
  assert.match(RETIRE_ACKNOWLEDGEMENT, /old devices/);
  assert.equal(topicEnabled({}, 'settings'), false, 'unset serves nothing (production)');
  assert.equal(topicEnabled({ ACCOUNT_SYNC_TOPICS: 'settings, listens' }, 'listens'), true);
});
