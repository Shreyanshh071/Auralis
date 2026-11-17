// The library write fence (docs/library-sync/spec.md, D4), run against the Firestore emulator:
//   cd firestore-tests && npm install && npm test
//
// Old app versions keep running after an account's library moves. These tests send the exact
// write shapes those versions send and check that the fence refuses every one that would change
// the library, without breaking anything else they do.
import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, deleteDoc, runTransaction, serverTimestamp } from 'firebase/firestore';

const PROJECT = 'demo-auralis';
const HOST = '127.0.0.1:8085';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const account = (uid) => env.authenticatedContext(uid, { firebase: { sign_in_provider: 'password' } }).firestore();
const asServer = (work) => env.withSecurityRulesDisabled((ctx) => work(ctx.firestore()));
const seed = (path, data) => asServer((db) => setDoc(doc(db, path), data));
const fence = (uid, state = 'migrating') => seed(`libraryAuthority/${uid}`, { state, epoch: 1, at: 1 });
const stored = async (path) => { let data; await asServer(async (db) => { data = (await getDoc(doc(db, path))).data(); }); return data; };

const song = (id) => ({ id, title: `Song ${id}`, artist: 'A', album: null, thumbnail: 't', duration: 180 });
/** What `cloudDocument` (Android) and `pushLibrary` (desktop) write. */
const libraryDoc = (extra = {}) => ({
  playlists: [{ id: 'p1', title: 'Road', description: null, coverUrl: null, createdAt: 5, isCustom: true, tracks: [song('a'), song('b')] }],
  favorites: [song('f1')],
  savedArtists: [{ id: 'ar', name: 'Artist', thumbnail: null, subscribers: null }],
  savedAlbums: [{ id: 'al', title: 'Album', artist: null, thumbnail: null, trackCount: null, savedAt: 3 }],
  librarySync: { pins: [], pinsAt: 0, deleted: [] },
  updatedAt: 100, playlistsUpdatedAt: 100, favoritesUpdatedAt: 100, schemaVersion: 1,
  ...extra,
});

// ---- The desktop client's REST calls, as FirestoreRest sends them ---------------------------------

/** The emulator accepts an unsigned token, which is how a signed-in desktop is represented here. */
function token(uid) {
  const part = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${part({ alg: 'none', typ: 'JWT' })}.${part({ sub: uid, user_id: uid, aud: PROJECT, iss: `https://securetoken.google.com/${PROJECT}`,
    iat: 1, exp: 4_000_000_000, auth_time: 1, firebase: { sign_in_provider: 'password', identities: {} } })}.`;
}

function value(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (typeof v === 'string') return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(value) } };
  return { mapValue: { fields: fields(v) } };
}
const fields = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, value(v)]));
const ROOT = `projects/${PROJECT}/databases/(default)/documents`;

/** `FirestoreRest.write`: POST :commit with one update, and an updateMask of the top-level fields when merging. */
async function desktopWrite(uid, path, data, { merge = true, create = false, mask, updateTime } = {}) {
  const write = { update: { name: `${ROOT}/${path}`, fields: fields(data) } };
  if (merge) write.updateMask = { fieldPaths: mask ?? Object.keys(data) };
  if (create) write.currentDocument = { exists: false };
  if (updateTime) write.currentDocument = { updateTime };
  const response = await fetch(`http://${HOST}/v1/${ROOT}:commit`, { method: 'POST',
    headers: { authorization: `Bearer ${token(uid)}`, 'content-type': 'application/json' }, body: JSON.stringify({ writes: [write] }) });
  return response.status;
}

/** `FirestoreRest.delete`: HTTP DELETE on the document. */
async function desktopDelete(uid, path) {
  return (await fetch(`http://${HOST}/v1/${ROOT}/${path}`, { method: 'DELETE', headers: { authorization: `Bearer ${token(uid)}` } })).status;
}

async function desktopGet(uid, path) {
  const response = await fetch(`http://${HOST}/v1/${ROOT}/${path}`, { headers: { authorization: `Bearer ${token(uid)}` } });
  return { status: response.status, body: response.ok ? await response.json() : null };
}

/** Android's `syncLibrary`: a transaction that reads the document and merges the library into it. */
const androidLibrarySync = (db, uid, data) => runTransaction(db, async (tx) => {
  const ref = doc(db, `users/${uid}`);
  await tx.get(ref);
  tx.set(ref, data, { merge: true });
});

// ---- Before the fence: nothing changes for anyone --------------------------------------------------

test('a legacy account: every old write shape still works', async () => {
  const db = account(ALICE);
  await assertSucceeds(androidLibrarySync(db, ALICE, libraryDoc()));
  await assertSucceeds(androidLibrarySync(db, ALICE, libraryDoc({ favorites: [song('f1'), song('f2')], updatedAt: 200 })));
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc({ favorites: [song('f9')], updatedAt: 300 })), 200);
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc({ updatedAt: 400 }), { merge: false }), 200, 'a whole-document replacement');
  assert.equal((await stored(`users/${ALICE}`)).favorites[0].id, 'f1');
  assert.equal(await desktopDelete(ALICE, `users/${ALICE}`), 200);
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc(), { create: true }), 200, 'creating it again');
});

test("another account's fence does not touch this one", async () => {
  await fence(BOB);
  await assertSucceeds(androidLibrarySync(account(ALICE), ALICE, libraryDoc()));
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc({ favorites: [] })), 200);
});

// ---- The fence -------------------------------------------------------------------------------------

for (const state of ['migrating', 'cloudflare']) {
  test(`fenced (${state}): Android's library sync is refused and the document is unchanged`, async () => {
    await seed(`users/${ALICE}`, libraryDoc());
    await fence(ALICE, state);
    const db = account(ALICE);
    await assertFails(androidLibrarySync(db, ALICE, libraryDoc({ favorites: [song('f1'), song('late')], updatedAt: 999 })));
    await assertFails(setDoc(doc(db, `users/${ALICE}`), libraryDoc({ playlists: [] }), { merge: true }));
    await assertFails(setDoc(doc(db, `users/${ALICE}`), { librarySync: { pins: ['p1'], pinsAt: 9, deleted: [] } }, { merge: true }));
    assert.deepEqual(await stored(`users/${ALICE}`), libraryDoc());
  });

  test(`fenced (${state}): the desktop's POST :commit with an updateMask is refused`, async () => {
    await seed(`users/${ALICE}`, libraryDoc());
    await fence(ALICE, state);
    assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc({ favorites: [song('late')], updatedAt: 999 })), 403);
    assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, { favorites: [] }), 403, 'one library field alone');
    assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, { savedAlbums: [], savedArtists: [] }), 403);
    assert.deepEqual(await stored(`users/${ALICE}`), libraryDoc());
  });
}

test('fenced: a whole-document replacement cannot change or drop the library', async () => {
  await seed(`users/${ALICE}`, libraryDoc({ preferences: { windows: { theme: 'dark' } } }));
  await fence(ALICE);
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc({ favorites: [] }), { merge: false }), 403, 'changed library');
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, { preferences: { windows: {} }, updatedAt: 1 }, { merge: false }), 403, 'library fields dropped');
  await assertFails(setDoc(doc(account(ALICE), `users/${ALICE}`), { updatedAt: 5 }), 'the SDK replacement drops them too');
  assert.equal((await stored(`users/${ALICE}`)).favorites.length, 1);
  // Replacing the document with the very same library and other fields changed is not a library write.
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc({ updatedAt: 777 }), { merge: false }), 200);
});

test('fenced: deleting the document does not lift the fence', async () => {
  await seed(`users/${ALICE}`, libraryDoc());
  await fence(ALICE);
  const db = account(ALICE);
  // Account deletion on old Android deletes the document; that stays possible.
  await assertSucceeds(deleteDoc(doc(db, `users/${ALICE}`)));
  await assertFails(setDoc(doc(db, `users/${ALICE}`), libraryDoc()), 'creating it again with a library');
  await assertFails(androidLibrarySync(db, ALICE, libraryDoc()));
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc(), { create: true }), 403);
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, libraryDoc()), 403);
  assert.equal(await stored(`users/${ALICE}`), undefined);
  // The desktop's DELETE is allowed as well, and still nothing can be recreated with a library.
  await assertSucceeds(setDoc(doc(db, `users/${ALICE}`), { preferences: { android: { theme: { value: 'dark', revision: 1, device: 'd' } } } }, { merge: true }));
  assert.equal(await desktopDelete(ALICE, `users/${ALICE}`), 200);
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, { favorites: [song('x')] }, { create: true }), 403);
});

test('fenced: settings, starter picks, listening history and Connect keep working', async () => {
  await seed(`users/${ALICE}`, libraryDoc());
  await fence(ALICE);
  const db = account(ALICE);
  // Android settings sync: a transaction with mergeFields("preferences.android").
  await assertSucceeds(runTransaction(db, async (tx) => {
    const ref = doc(db, `users/${ALICE}`);
    await tx.get(ref);
    tx.set(ref, { preferences: { android: { theme: { value: 'dark', revision: 1, device: 'phone' } } } }, { mergeFields: ['preferences.android'] });
  }));
  // Desktop settings sync: a conditional update masked to preferences.windows.
  const current = await desktopGet(ALICE, `users/${ALICE}`);
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, { preferences: { windows: { accent: { value: 'red', revision: 1, device: 'pc' } } } },
    { mask: ['preferences.windows'], updateTime: current.body.updateTime }), 200);
  // Onboarding picks.
  await assertSucceeds(setDoc(doc(db, `users/${ALICE}`), { starterTaste: { artists: '[]', tracks: '[]' } }, { merge: true }));
  // Listening history: Android's batch merge and the desktop's per-event mask.
  await assertSucceeds(setDoc(doc(db, `users/${ALICE}/listening/2026-11-14`), { events: { '1_a': { t: 'a', ts: 1, ms: 5 } }, updatedAt: 1 }, { merge: true }));
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}/listening/2026-11-14`, { events: { '2_b': { t: 'b', ts: 2, ms: 9 } }, updatedAt: 2 },
    { mask: ['events.`2_b`', 'updatedAt'] }), 200);
  await assertSucceeds(setDoc(doc(db, `users/${ALICE}/connectDevices/phone`), { id: 'phone', name: 'Pixel', nickname: null, platform: 'ANDROID',
    type: 'PHONE', appVersion: '1', canPlay: true, online: true, createdAt: 1, seenAt: serverTimestamp() }));
  const after = await stored(`users/${ALICE}`);
  assert.deepEqual(after.favorites, libraryDoc().favorites, 'the frozen library is untouched');
  assert.equal(after.preferences.android.theme.value, 'dark');
  assert.equal(after.preferences.windows.accent.value, 'red');
});

test('fenced: the frozen library can still be read by its owner, and only by its owner', async () => {
  await seed(`users/${ALICE}`, libraryDoc());
  await fence(ALICE, 'cloudflare');
  await assertSucceeds(getDoc(doc(account(ALICE), `users/${ALICE}`)));
  assert.equal((await desktopGet(ALICE, `users/${ALICE}`)).status, 200);
  await assertFails(getDoc(doc(account(BOB), `users/${ALICE}`)));
  assert.equal((await desktopGet(BOB, `users/${ALICE}`)).status, 403);
});

test('no client can see, create, change or remove a fence', async () => {
  await fence(ALICE);
  for (const db of [account(ALICE), account(BOB), env.unauthenticatedContext().firestore()]) {
    await assertFails(getDoc(doc(db, `libraryAuthority/${ALICE}`)));
    await assertFails(setDoc(doc(db, `libraryAuthority/${ALICE}`), { state: 'legacy' }));
    await assertFails(setDoc(doc(db, `libraryAuthority/${ALICE}`), { state: 'legacy' }, { merge: true }));
    await assertFails(deleteDoc(doc(db, `libraryAuthority/${ALICE}`)));
    await assertFails(setDoc(doc(db, `libraryAuthority/${BOB}`), { state: 'cloudflare' }));
  }
  assert.equal(await desktopDelete(ALICE, `libraryAuthority/${ALICE}`), 403);
  assert.equal(await desktopWrite(ALICE, `libraryAuthority/${ALICE}`, { state: 'legacy' }, { merge: false }), 403);
  assert.equal((await stored(`libraryAuthority/${ALICE}`)).state, 'migrating');
  // And the library is still fenced afterwards.
  await assertFails(androidLibrarySync(account(ALICE), ALICE, libraryDoc()));
});

test('a signed-out client or another account cannot write a library, fenced or not', async () => {
  await assertFails(setDoc(doc(account(BOB), `users/${ALICE}`), libraryDoc()));
  await assertFails(setDoc(doc(env.unauthenticatedContext().firestore(), `users/${ALICE}`), libraryDoc()));
  assert.equal(await desktopWrite(BOB, `users/${ALICE}`, libraryDoc()), 403);
});

test('removing the fence (an aborted migration or a rollback) restores every old write shape', async () => {
  await seed(`users/${ALICE}`, libraryDoc());
  await fence(ALICE);
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, { favorites: [] }), 403);
  await asServer((db) => deleteDoc(doc(db, `libraryAuthority/${ALICE}`)));
  assert.equal(await desktopWrite(ALICE, `users/${ALICE}`, { favorites: [song('back')] }), 200);
  await assertSucceeds(androidLibrarySync(account(ALICE), ALICE, libraryDoc({ favorites: [song('back'), song('again')] })));
  assert.equal((await stored(`users/${ALICE}`)).favorites.length, 2);
});

test('the existing size and shape limits still apply', async () => {
  const db = account(ALICE);
  await assertFails(setDoc(doc(db, `users/${ALICE}`), { favorites: 'not a list' }));
  await assertFails(setDoc(doc(db, `users/${ALICE}`), { playlists: Array.from({ length: 501 }, (_, i) => ({ id: `p${i}` })) }));
});
