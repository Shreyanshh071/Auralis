// What must hold under both the live rules and the rules being deployed. Run against each:
//   firebase emulators:exec --config firebase.json --only firestore --project demo-auralis "node --test connect.compat.test.mjs"
//   CONNECT_RULES=<path to the old rules> CONNECT_INTENT=refused firebase emulators:exec ... (same command)
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, deleteDoc, serverTimestamp } from 'firebase/firestore';

const rules = process.env.CONNECT_RULES ? readFileSync(process.env.CONNECT_RULES, 'utf8')
  : readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8');
const intentAllowed = process.env.CONNECT_INTENT !== 'refused';
const ALICE = 'alice-uid';
let env;

before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-auralis', firestore: { rules } });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const account = () => env.authenticatedContext(ALICE, { firebase: { sign_in_provider: 'password' } }).firestore();
const sessionRef = (db) => doc(db, `users/${ALICE}/connect/session`);
const queueRef = (db) => doc(db, `users/${ALICE}/connect/queue`);
const intentRef = (db) => doc(db, `users/${ALICE}/connect/intent`);

// Every field a released client writes, with a handoff and a command in flight.
const released = (extra = {}) => ({
  version: 1, ownerId: 'phone', ownerName: 'Pixel 8', ownerType: 'PHONE', epoch: 1,
  leaseUntil: Date.now() + 60_000,
  track: { id: 's1', title: 'Song', artist: 'A', album: null, albumId: null, thumbnail: '', duration: 180, local: false,
    lyricsProvider: null, lyricsSpotifyId: null, lyricsTitle: null, lyricsArtist: null, lyricsDurationMs: null },
  playing: true, buffering: false, positionMs: 1000, positionAt: Date.now(), durationMs: 180000,
  queueRev: 1, queueIndex: 0, shuffle: false, repeat: 'OFF', context: { type: 'playlist', id: 'p', title: 'Mix' },
  transfer: { id: 't', from: 'phone', to: 'pc', toName: 'PC', toType: 'COMPUTER', epoch: 2, status: 'requested', at: 1, by: 'pc', reason: '' },
  commands: { c1: { type: 'NEXT', from: 'pc', at: 1, queueRev: 1 } },
  updatedBy: 'phone', updatedAt: serverTimestamp(), ...extra,
});

test('a released client creates and replaces the session and queue', async () => {
  const db = account();
  await assertSucceeds(setDoc(sessionRef(db), released()));
  await assertSucceeds(setDoc(sessionRef(db), released({ version: 2, playing: false })));
  await assertSucceeds(setDoc(queueRef(db), { rev: 1, offset: 0, tracks: [{ id: 's1' }] }));
  await assertSucceeds(getDoc(sessionRef(db)));
  await assertSucceeds(getDoc(queueRef(db)));
});

test('a released handoff still moves the output', async () => {
  const db = account();
  await assertSucceeds(setDoc(sessionRef(db), released()));
  await assertSucceeds(setDoc(sessionRef(db), released({ version: 2, ownerId: 'pc', ownerName: 'PC', epoch: 2, updatedBy: 'pc',
    transfer: { id: 't', from: 'phone', to: 'pc', toName: 'PC', toType: 'COMPUTER', epoch: 2, status: 'committed', at: 2, by: 'pc', reason: '' } })));
});

test('a stale or unfenced session write is still refused', async () => {
  const db = account();
  await assertSucceeds(setDoc(sessionRef(db), released({ transfer: null })));
  await assertFails(setDoc(sessionRef(db), released({ version: 5 })));
  await assertFails(setDoc(sessionRef(db), released({ version: 2, ownerId: 'pc', epoch: 2, updatedBy: 'pc', transfer: null })));
});

test('the intent document is available only under the new rules', async () => {
  const db = account();
  const intent = { version: 1, epoch: 1, at: Date.now(), by: 'phone' };
  const check = intentAllowed ? assertSucceeds : assertFails;
  await check(setDoc(intentRef(db), intent));
  await check(getDoc(intentRef(db)));
  // Refused or not, it can't reach the session: clients write it separately.
  await assertSucceeds(setDoc(sessionRef(db), released()));
});

test('an account can always delete its connect documents', async () => {
  const db = account();
  await assertSucceeds(setDoc(sessionRef(db), released()));
  await assertSucceeds(deleteDoc(sessionRef(db)));
  await assertSucceeds(deleteDoc(intentRef(db)));
});
