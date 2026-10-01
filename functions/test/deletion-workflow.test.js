"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { authorize, deleteAccountData } = require("../deletion-workflow");
const auth = { uid: "owner", token: { auth_time: 1000, firebase: { sign_in_provider: "password" } } };
test("requires signed-in non-anonymous recently reauthenticated owner", () => {
  assert.equal(authorize(auth, 1001), "owner");
  for (const a of [null, { ...auth, token: { auth_time: 1000, firebase: { sign_in_provider: "anonymous" } } },
    { ...auth, token: { auth_time: 600 } }, { ...auth, token: { auth_time: 2000 } }, { uid: "x", token: {} }]) {
    assert.throws(() => authorize(a, 1001));
  }
});
function fake(failAt) {
  const calls = []; let complete = false;
  const store = { calls, claim: async () => !complete, isComplete: async () => complete };
  for (const name of ["closeHostedRooms", "removeRoomReferences", "deleteLibrary", "verifyNoData", "deleteAuth", "retry"]) {
    store[name] = async uid => { assert.equal(uid, "owner"); calls.push(name); if (name === failAt) throw Error(name); };
  }
  store.complete = async () => { calls.push("complete"); complete = true; };
  return store;
}
test("Auth deletion follows verified cloud cleanup, and success follows Auth", async () => {
  const store = fake();
  assert.deepEqual(await deleteAccountData("owner", store), { complete: true });
  assert.deepEqual(store.calls, ["closeHostedRooms", "removeRoomReferences", "deleteLibrary", "verifyNoData", "deleteAuth", "complete"]);
  store.calls.length = 0;
  assert.deepEqual(await deleteAccountData("owner", store), { complete: true });
  assert.deepEqual(store.calls, []);
});
for (const stage of ["closeHostedRooms", "removeRoomReferences", "deleteLibrary", "verifyNoData", "deleteAuth", "complete"]) {
  test(`failure at ${stage} remains retryable and never reports success`, async () => {
    const store = fake(stage);
    if (stage === "complete") store.complete = async () => { throw Error("complete"); };
    await assert.rejects(deleteAccountData("owner", store));
    assert.equal(store.calls.at(-1), "retry");
    if (["closeHostedRooms", "removeRoomReferences", "deleteLibrary", "verifyNoData"].includes(stage)) {
      assert.ok(!store.calls.includes("deleteAuth"));
    }
    const healthy = fake();
    assert.deepEqual(await deleteAccountData("owner", healthy), { complete: true });
  });
}
test("a concurrent worker cannot claim success while cleanup is pending", async () => {
  assert.deepEqual(await deleteAccountData("owner", { claim: async () => false, isComplete: async () => false }), { complete: false });
});
