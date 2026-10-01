"use strict";
const { FieldValue, FieldPath } = require("firebase-admin/firestore");

const isRoomChild = (ref, group) => {
  const parts = ref.path.split("/");
  return parts.length === 4 && parts[0] === "rooms" && parts[2] === group;
};
async function each(query, action) {
  let cursor;
  while (true) {
    let page = query.orderBy(FieldPath.documentId()).limit(200);
    if (cursor) page = page.startAfter(cursor);
    const snapshot = await page.get();
    if (snapshot.empty) return;
    for (const doc of snapshot.docs) await action(doc);
    cursor = snapshot.docs.at(-1);
  }
}

function deletionStore(db, auth, now = Date.now) {
  const job = uid => db.collection("account_deletions").doc(uid);
  const refs = (group, field, op, uid) => db.collectionGroup(group).where(field, op, uid);
  const roomQueries = uid => [
    ["members", refs("members", "id", "==", uid)],
    ["recommendations", refs("recommendations", "recommendedByUid", "==", uid)],
    ["recommendations", refs("recommendations", "upvotes", "array-contains", uid)]
  ];
  return {
    async claim(uid) {
      return db.runTransaction(async tx => {
        const doc = await tx.get(job(uid));
        if (doc.get("state") === "complete" || (doc.get("leaseUntil") || 0) > now()) return false;
        tx.set(job(uid), { state: "pending", leaseUntil: now() + 600000,
          requestedAt: doc.get("requestedAt") || now() });
        return true;
      });
    },
    async isComplete(uid) { return (await job(uid).get()).get("state") === "complete"; },
    async closeHostedRooms(uid) {
      await each(db.collection("rooms").where("hostId", "==", uid), async doc => {
        // Freeze the room before recursively deleting children, so other participants cannot
        // add new membership/requests while cleanup is underway.
        await doc.ref.update({ status: "closed" });
        await db.recursiveDelete(doc.ref);
      });
    },
    async removeRoomReferences(uid) {
      for (const [group, query] of roomQueries(uid)) {
        await each(query, async doc => {
          if (!isRoomChild(doc.ref, group)) return;
          if (group === "recommendations" && doc.get("recommendedByUid") !== uid) {
            await doc.ref.update({ upvotes: FieldValue.arrayRemove(uid) });
          } else {
            await db.recursiveDelete(doc.ref);
          }
        });
      }
      // Legacy rooms can embed a roster. A transaction preserves other users' concurrent updates.
      await each(db.collection("rooms"), async doc => {
        await db.runTransaction(async tx => {
          const current = await tx.get(doc.ref);
          if (!current.exists) return;
          const roster = current.get("membersList");
          if (!Array.isArray(roster) || !roster.some(m => m?.id === uid)) return;
          const filtered = roster.filter(m => m?.id !== uid);
          tx.update(doc.ref, { membersList: filtered });
        });
      });
    },
    async deleteLibrary(uid) { await db.recursiveDelete(db.collection("users").doc(uid)); },
    async verifyNoData(uid) {
      const user = db.collection("users").doc(uid);
      if ((await user.get()).exists) throw new Error("Library remains");
      for (const collection of await user.listCollections()) {
        if (!(await collection.limit(1).get()).empty) throw new Error("Nested library data remains");
      }
      if (!(await db.collection("rooms").where("hostId", "==", uid).limit(1).get()).empty) {
        throw new Error("Hosted room remains");
      }
      for (const [group, query] of roomQueries(uid)) {
        await each(query, async doc => {
          if (isRoomChild(doc.ref, group)) throw new Error("Room reference remains");
        });
      }
      await each(db.collection("rooms"), async doc => {
        if ((doc.get("membersList") || []).some(m => m?.id === uid)) throw new Error("Embedded roster remains");
      });
    },
    async deleteAuth(uid) {
      try { await auth.deleteUser(uid); }
      catch (e) { if (e.code !== "auth/user-not-found") throw e; }
    },
    async complete(uid) { await job(uid).set({ state: "complete", completedAt: now() }); },
    async retry(uid) { await job(uid).update({ state: "pending", leaseUntil: 0 }); }
  };
}
module.exports = { deletionStore };
