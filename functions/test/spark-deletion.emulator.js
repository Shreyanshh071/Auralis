
"use strict";
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) throw Error("Emulators required; production forbidden");
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const { doc, collection, collectionGroup, query, where, getDoc, getDocs, setDoc, updateDoc, deleteDoc, serverTimestamp, arrayRemove, orderBy, documentId, limit, startAfter } = require("firebase/firestore");
const projectId = "demo-auralis-spark-cleanup";
const admin = getFirestore(initializeApp({ projectId }));
let env;
function client(uid) { return env.authenticatedContext(uid, { auth_time: Math.floor(Date.now()/1000), firebase: { sign_in_provider: "password" } }).firestore(); }
test.before(async () => { env = await initializeTestEnvironment({ projectId, firestore: { rules: fs.readFileSync("../firestore.rules", "utf8") } }); });
test.after(async () => { await env.cleanup(); await admin.terminate(); });
async function block(db, uid) { await setDoc(doc(db, `account_deletion_blocks/${uid}`), { requestedAt: serverTimestamp() }); }
function ownQueries(db, uid) {
  return [query(collectionGroup(db, "recommendations"), where("recommendedByUid", "==", uid)),
    query(collectionGroup(db, "recommendations"), where("upvotes", "array-contains", uid)),
    query(collectionGroup(db, "members"), where("id", "==", uid))];
}
async function seed(uid) {
  await admin.doc(`users/${uid}`).set({ favorites: ["old"] });
  await admin.doc(`users/${uid}/listening/2026-10-01`).set({ events: {} });
  await admin.doc(`rooms/${uid}-host`).set({ hostId: uid, status: "active" });
  await admin.doc(`rooms/${uid}-host/recommendations/guest`).set({ recommendedByUid: "other", upvotes: ["other"] });
  await admin.doc(`rooms/${uid}-host/members/other`).set({ id: "other" });
  await admin.doc(`rooms/${uid}-guest`).set({ hostId: "other", status: "active" });
  await admin.doc(`rooms/${uid}-guest/members/${uid}`).set({ id: uid, name: "Guest", isHost: false, lastSeen: 1 });
  await admin.doc(`rooms/${uid}-guest/recommendations/own`).set({ recommendedByUid: uid, upvotes: [uid] });
  await admin.doc(`rooms/${uid}-guest/recommendations/other`).set({ recommendedByUid: "other", upvotes: [uid, "other"], track: { id: "keep" } });
}
async function erase(db, uid) {
  const hosted = await getDocs(query(collection(db, "rooms"), where("hostId", "==", uid)));
  for (const room of hosted.docs) await setDoc(doc(db, `users/${uid}/room_cleanup/${room.id}`), { roomCode: room.id });
  const journal = await getDocs(collection(db, `users/${uid}/room_cleanup`));
  for (const entry of journal.docs) {
    const roomRef = doc(db, `rooms/${entry.id}`);
    if ((await getDoc(roomRef)).exists()) {
      await updateDoc(roomRef, { status: "closed" });
      for (const rec of (await getDocs(collection(db, `rooms/${entry.id}/recommendations`))).docs) await deleteDoc(rec.ref);
      assert.equal((await getDocs(collection(db, `rooms/${entry.id}/recommendations`))).empty, true);
      await deleteDoc(roomRef);
    }
    for (const member of (await getDocs(collection(db, `rooms/${entry.id}/members`))).docs) await deleteDoc(member.ref);
    await deleteDoc(entry.ref);
  }
  const [recs,votes,members] = ownQueries(db, uid);
  for (const rec of (await getDocs(recs)).docs) await deleteDoc(rec.ref);
  for (const rec of (await getDocs(votes)).docs) await updateDoc(rec.ref, { upvotes: arrayRemove(uid) });
  for (const member of (await getDocs(members)).docs) await deleteDoc(member.ref);
  for (const stat of (await getDocs(collection(db, `users/${uid}/listening`))).docs) await deleteDoc(stat.ref);
  await deleteDoc(doc(db, `users/${uid}`));
  for (const q of ownQueries(db,uid)) assert.equal((await getDocs(q)).empty, true);
}
test("owner can clean historical shared references and hosted room children using client SDK only", async () => {
  const uid = "spark-owner"; await seed(uid);
  const db = client(uid); await block(db, uid); await erase(db, uid);
  for (const path of [`users/${uid}`, `users/${uid}/listening/2026-10-01`, `rooms/${uid}-host`,
    `rooms/${uid}-host/members/other`, `rooms/${uid}-host/recommendations/guest`,
    `rooms/${uid}-guest/members/${uid}`, `rooms/${uid}-guest/recommendations/own`]) {
    assert.equal((await admin.doc(path).get()).exists, false, path);
  }
  assert.deepEqual((await admin.doc(`rooms/${uid}-guest/recommendations/other`).get()).data(),
    { recommendedByUid: "other", upvotes: ["other"], track: { id: "keep" } });
  assert.equal((await admin.doc(`rooms/${uid}-guest`).get()).exists, true);
  assert.equal((await admin.doc(`account_deletion_blocks/${uid}`).get()).exists, true);
  await erase(db, uid); // Idempotent full retry.
});
test("cloud journal recovers member cleanup after parent-room deletion and process interruption", async () => {
  const uid = "spark-interrupted"; await seed(uid); const db = client(uid); await block(db,uid);
  await setDoc(doc(db, `users/${uid}/room_cleanup/${uid}-host`), { roomCode: `${uid}-host` });
  await updateDoc(doc(db, `rooms/${uid}-host`), { status: "closed" });
  await deleteDoc(doc(db, `rooms/${uid}-host/recommendations/guest`));
  await deleteDoc(doc(db, `rooms/${uid}-host`));
  assert.equal((await admin.doc(`rooms/${uid}-host/members/other`).get()).exists, true);
  await erase(client(uid), uid);
  assert.equal((await admin.doc(`rooms/${uid}-host/members/other`).get()).exists, false);
});
test("another device cannot recreate library or stats; other accounts still work", async () => {
  const uid = "spark-blocked"; const db=client(uid); await block(db,uid);
  await assertFails(setDoc(doc(client(uid),`users/${uid}`), { favorites: [] }));
  await assertFails(setDoc(doc(client(uid),`users/${uid}/listening/2026-10-01`), { events:{},updatedAt:1 }));
  await assertSucceeds(setDoc(doc(client("other-safe"),"users/other-safe"), {favorites:[]}));
  await assertFails(deleteDoc(doc(db,`account_deletion_blocks/${uid}`)));
});
test("deleting account cannot read every room/member/request or delete someone else's request", async () => {
  const uid="spark-private"; await seed(uid); const db=client(uid); await block(db,uid);
  await assertFails(getDocs(collection(db,"rooms")));
  await assertFails(getDocs(collectionGroup(db,"members")));
  await assertFails(getDocs(collectionGroup(db,"recommendations")));
  await assertFails(deleteDoc(doc(db,`rooms/${uid}-guest/recommendations/other`)));
  await assertFails(updateDoc(doc(db,`rooms/${uid}-guest/recommendations/other`), {upvotes:[]}));
  await assertFails(setDoc(doc(db,"account_deletion_blocks/other"), {requestedAt:serverTimestamp()}));
});
test("block creation requires recent non-anonymous owner and exact server timestamp", async () => {
  const stale=env.authenticatedContext("stale", {auth_time:1,firebase:{sign_in_provider:"password"}}).firestore();
  await assertFails(block(stale,"stale"));
  const anon=env.authenticatedContext("anonymous", {auth_time:Math.floor(Date.now()/1000),firebase:{sign_in_provider:"anonymous"}}).firestore();
  await assertFails(block(anon,"anonymous"));
  await assertFails(setDoc(doc(client("bad-marker"),"account_deletion_blocks/bad-marker"), {requestedAt:1}));
});
test("pagination stays associated with the query when processed records are deleted", async () => {
  const uid="spark-paging"; const db=client(uid); await block(db,uid);
  for (let n=0;n<5;n++) await admin.doc(`rooms/paging/members/${uid}-${n}`).set({id:uid});
  let cursor; let count=0;
  while(true) {
    let q=query(collectionGroup(db,"members"),where("id","==",uid),orderBy(documentId()),limit(2));
    if(cursor) q=query(q,startAfter(cursor));
    const page=await getDocs(q); if(page.empty) break;
    cursor=page.docs.at(-1); count+=page.size;
    // Normal member ID must equal UID; malformed legacy rows cannot be deleted by a client.
    // Paging itself can be checked using admin deletions, without granting broad client writes.
    for(const d of page.docs) await admin.doc(d.ref.path).delete();
  }
  assert.equal(count,5);
});
