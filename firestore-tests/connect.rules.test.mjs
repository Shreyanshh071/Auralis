// Auralis Connect security rules, run against the Firestore emulator:
//   cd firestore-tests && npm install && npm test
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, serverTimestamp } from 'firebase/firestore';

let env;
const ALICE = 'alice-uid';
const BOB = 'bob-uid';

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-auralis',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const account = (uid) => env.authenticatedContext(uid, { firebase: { sign_in_provider: 'password' } }).firestore();
const anonymous = (uid) => env.authenticatedContext(uid, { firebase: { sign_in_provider: 'anonymous' } }).firestore();

const device = (id, extra = {}) => ({
  id, name: 'Pixel 8', nickname: null, platform: 'ANDROID', type: 'PHONE', appVersion: '1.1.1',
  canPlay: true, online: true, createdAt: 1, seenAt: serverTimestamp(), ...extra,
});

const session = (extra = {}) => ({
  version: 1, ownerId: 'phone', ownerName: 'Pixel 8', ownerType: 'PHONE', epoch: 1,
  leaseUntil: Date.now() + 60_000, track: { id: 's1', title: 'Song', artist: 'A', duration: 180 },
  playing: true, buffering: false, positionMs: 1000, positionAt: Date.now(), durationMs: 180000,
  queueRev: 1, queueIndex: 0, shuffle: false, repeat: 'OFF', context: null, transfer: null, commands: {},
  updatedBy: 'phone', updatedAt: serverTimestamp(), ...extra,
});

const sessionRef = (db, uid) => doc(db, `users/${uid}/connect/session`);

async function seed(path, data) {
  await env.withSecurityRulesDisabled(async (ctx) => { await setDoc(doc(ctx.firestore(), path), data); });
}

test('a client registers itself and lists its own account devices', async () => {
  const db = account(ALICE);
  await assertSucceeds(setDoc(doc(db, `users/${ALICE}/connectDevices/phone`), device('phone')));
  await assertSucceeds(getDocs(collection(db, `users/${ALICE}/connectDevices`)));
});

test('another account cannot discover, read or register devices', async () => {
  await seed(`users/${ALICE}/connectDevices/phone`, { ...device('phone'), seenAt: 1 });
  const bob = account(BOB);
  await assertFails(getDocs(collection(bob, `users/${ALICE}/connectDevices`)));
  await assertFails(getDoc(doc(bob, `users/${ALICE}/connectDevices/phone`)));
  await assertFails(setDoc(doc(bob, `users/${ALICE}/connectDevices/bob-laptop`), device('bob-laptop')));
  await assertFails(deleteDoc(doc(bob, `users/${ALICE}/connectDevices/phone`)));
});

test('anonymous (Listen Together guest) and signed-out clients get nothing', async () => {
  await seed(`users/${ALICE}/connect/session`, { ...session(), updatedAt: 1 });
  await assertFails(getDoc(sessionRef(anonymous(ALICE), ALICE)));
  await assertFails(getDoc(sessionRef(env.unauthenticatedContext().firestore(), ALICE)));
  await assertFails(setDoc(doc(anonymous(ALICE), `users/${ALICE}/connectDevices/x`), device('x')));
});

test('another account cannot read, control or take over the session, even with known ids', async () => {
  await seed(`users/${ALICE}/connect/session`, { ...session(), updatedAt: 1 });
  const bob = account(BOB);
  await assertFails(getDoc(sessionRef(bob, ALICE)));
  await assertFails(getDoc(doc(bob, `users/${ALICE}/connect/queue`)));
  await assertFails(updateDoc(sessionRef(bob, ALICE), { version: 2, commands: { c1: { type: 'PAUSE', from: 'phone', at: 1 } }, updatedAt: serverTimestamp() }));
  await assertFails(setDoc(sessionRef(bob, ALICE), session({ version: 2, ownerId: 'bob-laptop', epoch: 2, updatedBy: 'bob-laptop' })));
});

test('every write must be exactly one version newer', async () => {
  const db = account(ALICE);
  await assertSucceeds(setDoc(sessionRef(db, ALICE), session()));
  await assertSucceeds(setDoc(sessionRef(db, ALICE), session({ version: 2, playing: false })));
  await assertFails(setDoc(sessionRef(db, ALICE), session({ version: 2, playing: true })), 'stale write');
  await assertFails(setDoc(sessionRef(db, ALICE), session({ version: 4 })), 'skipped version');
  await assertFails(setDoc(doc(db, `users/${ALICE}/connect/other`), session()), 'unknown doc');
});

test('ownership moves only through a handoff, an expired lease, or the owner itself', async () => {
  const db = account(ALICE);
  await seed(`users/${ALICE}/connect/session`, { ...session({ version: 5, epoch: 3 }), updatedAt: 1 });
  // A controller cannot just make itself the output while the phone's lease is valid.
  await assertFails(setDoc(sessionRef(db, ALICE), session({ version: 6, epoch: 4, ownerId: 'laptop', updatedBy: 'laptop' })));
  // Nor skip an epoch, even as the owner.
  await assertFails(setDoc(sessionRef(db, ALICE), session({ version: 6, epoch: 5, ownerId: 'laptop', updatedBy: 'phone' })));
  // The target of a pending handoff can.
  const t = { id: 't1', from: 'phone', to: 'laptop', toName: 'Laptop', toType: 'COMPUTER', epoch: 4, status: 'ready', at: 1, by: 'desktop', reason: '' };
  await assertSucceeds(setDoc(sessionRef(db, ALICE), session({ version: 6, epoch: 3, transfer: t, updatedBy: 'laptop' })));
  await assertSucceeds(setDoc(sessionRef(db, ALICE), session({ version: 7, epoch: 4, ownerId: 'laptop', transfer: { ...t, status: 'committed' }, updatedBy: 'laptop' })));
  // The owner hands over itself.
  await assertSucceeds(setDoc(sessionRef(db, ALICE), session({ version: 8, epoch: 5, ownerId: 'phone', updatedBy: 'laptop' })));
});

test('an expired lease lets another device take over', async () => {
  const db = account(ALICE);
  await seed(`users/${ALICE}/connect/session`, { ...session({ version: 2, epoch: 1, leaseUntil: Date.now() - 1000 }), updatedAt: 1 });
  await assertSucceeds(setDoc(sessionRef(db, ALICE), session({ version: 3, epoch: 2, ownerId: 'laptop', updatedBy: 'laptop' })));
});

test('leases cannot be set far into the future, and documents stay small', async () => {
  const db = account(ALICE);
  await assertFails(setDoc(sessionRef(db, ALICE), session({ leaseUntil: Date.now() + 3_600_000 })));
  const commands = Object.fromEntries([...Array(21).keys()].map((i) => [`c${i}`, { type: 'PLAY', from: 'x', at: i }]));
  await assertFails(setDoc(sessionRef(db, ALICE), session({ commands })));
  const tracks = [...Array(101).keys()].map((i) => ({ id: `s${i}` }));
  await assertFails(setDoc(doc(db, `users/${ALICE}/connect/queue`), { rev: 1, offset: 0, tracks }));
  await assertSucceeds(setDoc(doc(db, `users/${ALICE}/connect/queue`), { rev: 1, offset: 0, tracks: tracks.slice(0, 100) }));
});

const intentRef = (db, uid) => doc(db, `users/${uid}/connect/intent`);
const intent = (extra = {}) => ({ version: 5, epoch: 2, at: Date.now(), by: 'phone', ...extra });

test('a session keeps exactly the released format', async () => {
  const db = account(ALICE);
  await assertSucceeds(setDoc(sessionRef(db, ALICE), session()));
  await env.clearFirestore();
  await assertFails(setDoc(sessionRef(db, ALICE), session({ intent: { seq: 1, at: 1, by: 'phone' } })));
});

test('the account records and reads its last playback action', async () => {
  const db = account(ALICE);
  await assertSucceeds(setDoc(intentRef(db, ALICE), intent()));
  await assertSucceeds(getDoc(intentRef(db, ALICE)));
  await assertSucceeds(setDoc(intentRef(db, ALICE), intent({ version: 6 })));
  await assertSucceeds(setDoc(intentRef(db, ALICE), intent({ version: 6, by: 'pc' })));
});

test('the playback action never goes backwards, so a replayed write cannot undo a newer one', async () => {
  const db = account(ALICE);
  await assertSucceeds(setDoc(intentRef(db, ALICE), intent({ version: 6 })));
  await assertFails(setDoc(intentRef(db, ALICE), intent({ version: 5 })));
});

test('another account, anonymous guests and signed-out clients get nothing from it', async () => {
  await seed(`users/${ALICE}/connect/intent`, intent());
  await assertFails(getDoc(intentRef(account(BOB), ALICE)));
  await assertFails(setDoc(intentRef(account(BOB), ALICE), intent({ version: 9 })));
  await assertFails(deleteDoc(intentRef(account(BOB), ALICE)));
  await assertFails(getDoc(intentRef(anonymous(ALICE), ALICE)));
  await assertFails(setDoc(intentRef(anonymous(ALICE), ALICE), intent({ version: 9 })));
  await assertFails(getDoc(intentRef(env.unauthenticatedContext().firestore(), ALICE)));
  await assertFails(setDoc(intentRef(env.unauthenticatedContext().firestore(), ALICE), intent({ version: 9 })));
});

test('the playback action has a fixed shape and no room for anything else', async () => {
  const db = account(ALICE);
  await assertFails(setDoc(intentRef(db, ALICE), { version: 1, epoch: 1, at: 1 }));
  await assertFails(setDoc(intentRef(db, ALICE), intent({ version: 'one' })));
  await assertFails(setDoc(intentRef(db, ALICE), intent({ epoch: 1.5 })));
  await assertFails(setDoc(intentRef(db, ALICE), intent({ at: 'later' })));
  await assertFails(setDoc(intentRef(db, ALICE), intent({ at: Date.now() + 3_600_000 })));
  await assertFails(setDoc(intentRef(db, ALICE), intent({ by: 'x'.repeat(65) })));
  await assertFails(setDoc(intentRef(db, ALICE), intent({ note: 'free text' })));
});

test('only the known connect documents exist', async () => {
  const db = account(ALICE);
  await assertFails(setDoc(doc(db, `users/${ALICE}/connect/other`), intent()));
  await assertFails(getDoc(doc(db, `users/${ALICE}/connect/other`)));
});

test('deleting the account can remove the playback action', async () => {
  await seed(`users/${ALICE}/connect/intent`, intent());
  await assertSucceeds(deleteDoc(intentRef(account(ALICE), ALICE)));
});

test('signing out removes the registration', async () => {
  const db = account(ALICE);
  await assertSucceeds(setDoc(doc(db, `users/${ALICE}/connectDevices/phone`), device('phone')));
  await assertSucceeds(deleteDoc(doc(db, `users/${ALICE}/connectDevices/phone`)));
});
