"use strict";

// No caller-supplied UID/path: the callable authorizes the authenticated account first.
async function deleteAccountData(uid, store) {
  if (!await store.claim(uid)) return { complete: await store.isComplete(uid) };
  try {
    await store.closeHostedRooms(uid);
    await store.removeRoomReferences(uid);
    await store.deleteLibrary(uid);
    await store.verifyNoData(uid);
    await store.deleteAuth(uid);
    await store.complete(uid);
    return { complete: true };
  } catch (error) {
    await store.retry(uid);
    throw error;
  }
}

function authorize(auth, nowSeconds) {
  if (!auth || !auth.uid || auth.token?.firebase?.sign_in_provider === "anonymous") {
    const e = new Error("Sign in to delete your account"); e.code = "unauthenticated"; throw e;
  }
  const at = auth.token?.auth_time;
  if (!Number.isFinite(at) || nowSeconds - at > 300 || at > nowSeconds + 30) {
    const e = new Error("Confirm your identity again before deleting"); e.code = "failed-precondition"; throw e;
  }
  return auth.uid;
}
module.exports = { deleteAccountData, authorize };
