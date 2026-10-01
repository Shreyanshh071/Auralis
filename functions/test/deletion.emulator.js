
"use strict";
// Explicit emulator requirement: these tests can never target production.
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw new Error("Firestore and Auth emulators required");
}
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const { doc, setDoc, updateDoc } = require("firebase/firestore");
const { deleteAccountData } = require("../deletion-workflow");
const { deletionStore } = require("../firestore-deletion-store");
const projectId = "demo-auralis-account-cleanup";
const app = initializeApp({ projectId });
const db = getFirestore(app);
const auth = getAuth(app);
let env;
test.before(async () => {
  env = await initializeTestEnvironment({ projectId, firestore: { rules: fs.readFileSync("../firestore.rules", "utf8") } });
});
test.after(async () => { await env.cleanup(); await db.terminate(); });
async function seed(uid) {
  await auth.createUser({ uid, email: `${uid}@example.test` });
  await db.doc(`users/${uid}`).set({ favorites: ["keep-local-only"] });
  await db.doc(`users/${uid}/listening/2026-10-01`).set({ events: { test: {} } });
  await db.doc(`users/${uid}/future/nested/details/extra`).set({ private: true });
  await db.doc(`rooms/${uid}-host`).set({ hostId: uid, status: "active" });
  await db.doc(`rooms/${uid}-host/members/other`).set({ id: "other", name: "Another user" });
  await db.doc(`rooms/${uid}-host/recommendations/other`).set({ recommendedByUid: "other", upvotes: [] });
  await db.doc(`rooms/${uid}-guest`).set({ hostId: "other", status: "active", membersList: [{ id: uid, name: "Delete me" }, { id: "other", name: "Keep me" }] });
  await db.doc(`rooms/${uid}-guest/members/${uid}`).set({ id: uid, name: "Delete me" });
  await db.doc(`rooms/${uid}-guest/members/other`).set({ id: "other", name: "Keep me" });
  await db.doc(`rooms/${uid}-guest/recommendations/own`).set({ recommendedByUid: uid, upvotes: [uid], track: { id: "song" } });
  await db.doc(`rooms/${uid}-guest/recommendations/other`).set({ recommendedByUid: "other", upvotes: [uid, "other"], track: { id: "keep-song" } });
}
test("real cleanup deletes Auth, all nested library, hosted rooms and account references; preserves others", async () => {
  const uid = "complete-user";
  await seed(uid);
  await db.doc("users/other").set({ favorites: ["untouched"] });
  assert.deepEqual(await deleteAccountData(uid, deletionStore(db, auth)), { complete: true });
  await assert.rejects(auth.getUser(uid), e => e.code === "auth/user-not-found");
  for (const path of [`users/${uid}`, `users/${uid}/listening/2026-10-01`, `users/${uid}/future/nested/details/extra`,
    `rooms/${uid}-host`, `rooms/${uid}-host/members/other`, `rooms/${uid}-host/recommendations/other`,
    `rooms/${uid}-guest/members/${uid}`, `rooms/${uid}-guest/recommendations/own`]) {
    assert.equal((await db.doc(path).get()).exists, false, path);
  }
  assert.deepEqual((await db.doc(`rooms/${uid}-guest/recommendations/other`).get()).data(),
    { recommendedByUid: "other", upvotes: ["other"], track: { id: "keep-song" } });
  assert.deepEqual((await db.doc("users/other").get()).data(), { favorites: ["untouched"] });
  assert.deepEqual((await db.doc(`rooms/${uid}-guest`).get()).get("membersList"), [{ id: "other", name: "Keep me" }]);
  assert.equal((await db.doc(`rooms/${uid}-guest/members/other`).get()).exists, true);
  assert.deepEqual(await deleteAccountData(uid, deletionStore(db, auth)), { complete: true });
});
test("partial failure retains Auth, blocks recreation and resumes idempotently", async () => {
  const uid = "retry-user"; await seed(uid);
  const store = deletionStore(db, auth);
  await assert.rejects(deleteAccountData(uid, { ...store, deleteLibrary: async () => { throw Error("network"); } }));
  assert.equal((await auth.getUser(uid)).uid, uid);
  assert.equal((await db.doc(`account_deletions/${uid}`).get()).get("state"), "pending");
  const client = env.authenticatedContext(uid).firestore();
  await assertFails(setDoc(doc(client, `users/${uid}`), { favorites: [] }));
  await assertFails(setDoc(doc(client, `users/${uid}/listening/2026-10-02`), { events: {}, updatedAt: 1 }));
  await assertFails(setDoc(doc(client, `account_deletions/${uid}`), { state: "complete" }));
  assert.deepEqual(await deleteAccountData(uid, store), { complete: true });
});
test("success response failure after Auth removal can be recovered by server retry", async () => {
  const uid = "response-lost"; await seed(uid);
  const store = deletionStore(db, auth);
  await assert.rejects(deleteAccountData(uid, { ...store, complete: async () => { throw Error("interrupted"); } }));
  await assert.rejects(auth.getUser(uid));
  assert.deepEqual(await deleteAccountData(uid, store), { complete: true });
});
test("other accounts keep writing; closed or deleting-host rooms reject new members", async () => {
  const uid = "blocked-host";
  await db.doc(`account_deletions/${uid}`).set({ state: "pending" });
  await db.doc("rooms/blocked").set({ hostId: uid, status: "active" });
  await db.doc("rooms/closed").set({ hostId: "other", status: "closed" });
  const client = env.authenticatedContext("other").firestore();
  await assertSucceeds(setDoc(doc(client, "users/other"), { favorites: [] }));
  const member = { id: "other", name: "Keep", isHost: false, lastSeen: 1 };
  for (const room of ["blocked", "closed", "missing"]) await assertFails(setDoc(doc(client, `rooms/${room}/members/other`), member));
});
test("deleting members cannot recommend, vote or heartbeat with a still-valid token", async () => {
  const uid = "stale-token";
  await db.doc(`account_deletions/${uid}`).set({ state: "pending" });
  await db.doc("rooms/live").set({ hostId: "other", status: "active" });
  await db.doc(`rooms/live/members/${uid}`).set({ id: uid, name: "x", isHost: false, lastSeen: 1 });
  await db.doc("rooms/live/recommendations/rec").set({ recommendedByUid: "other", upvotes: ["other"], status: "pending" });
  const client = env.authenticatedContext(uid).firestore();
  await assertFails(updateDoc(doc(client, `rooms/live/members/${uid}`), { lastSeen: 2 }));
  await assertFails(updateDoc(doc(client, "rooms/live/recommendations/rec"), { upvotes: ["other", uid] }));
  await assertFails(setDoc(doc(client, "rooms/live/recommendations/new"), {
    id: "new", track: {}, recommendedByUid: uid, recommendedByName: "x", note: "", upvotes: [uid], createdAt: 1, status: "pending" }));
});
test("a crashed worker lease becomes retryable without another client request", async () => {
  const uid = "crashed-worker"; await seed(uid);
  assert.equal(await deletionStore(db, auth, () => 100).claim(uid), true);
  assert.equal(await deletionStore(db, auth, () => 101).claim(uid), false);
  assert.deepEqual(await deleteAccountData(uid, deletionStore(db, auth, () => 600101)), { complete: true });
});
