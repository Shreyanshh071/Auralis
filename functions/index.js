"use strict";
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { deleteAccountData, authorize } = require("./deletion-workflow");
const { deletionStore } = require("./firestore-deletion-store");
initializeApp();
const options = { region: "us-central1", timeoutSeconds: 540, memory: "512MiB", maxInstances: 2 };

exports.deleteAuralisAccount = onCall(options, async request => {
  let uid;
  try { uid = authorize(request.auth, Math.floor(Date.now() / 1000)); }
  catch (e) { throw new HttpsError(e.code, e.message); }
  try { return await deleteAccountData(uid, deletionStore(getFirestore(), getAuth())); }
  catch (e) {
    // Do not log library, names, credentials or tokens.
    console.error("Account cleanup requires retry", { code: e.code || "cleanup-failed" });
    throw new HttpsError("unavailable", "Deletion is pending. Retry to confirm completion.");
  }
});

// Continues accepted deletion even after process death, network loss or Auth removal.
exports.retryAuralisAccountDeletion = onSchedule({ ...options, schedule: "every 5 minutes" }, async () => {
  const db = getFirestore();
  const pending = await db.collection("account_deletions").where("state", "==", "pending").limit(20).get();
  for (const job of pending.docs) {
    try { await deleteAccountData(job.id, deletionStore(db, getAuth())); }
    catch (e) { console.error("Pending account cleanup will retry", { code: e.code || "cleanup-failed" }); }
  }
});

// Deleted Auth accounts can no longer mint tokens. Keep the tombstone for 24 hours
// (well beyond the lifetime of existing ID tokens), then remove the UID-only job record.
exports.purgeCompletedAuralisDeletions = onSchedule({ ...options, schedule: "every 24 hours" }, async () => {
  const db = getFirestore();
  const old = await db.collection("account_deletions").where("state", "==", "complete")
    .where("completedAt", "<", Date.now() - 86400000).limit(200).get();
  for (const job of old.docs) {
    try { await getAuth().getUser(job.id); }
    catch (e) {
      if (e.code === "auth/user-not-found") await job.ref.delete();
      else throw e;
    }
  }
});
