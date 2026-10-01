> DEFERRED PAID BACKEND — NOT ACTIVE. firebase.json no longer includes functions, and Android no longer depends on Firebase Functions. The active Spark implementation and its limits are documented in the account-deletion-spark-20261001/REPORT.md handoff. The paid version with its matching rules was preserved in account-deletion-20261001/prepared-source.zip. The old deletion.emulator.js tests expect those old paired rules; use test/spark-deletion.emulator.js for current Spark rules.

# Account deletion backend

Prepared code only. Do not install the Android change until this backend, indexes and rules are deployed and a disposable-account test succeeds.

## Flow
1. Callable derives the UID from Firebase Auth, rejects anonymous callers and requires authentication within five minutes. Caller data cannot choose another UID/path.
2. Server transaction saves a pending deletion tombstone and a ten-minute worker lease. Firestore rules prevent library/stat/room writes by that UID across all devices, including stale tokens. Other members cannot add data to a closed room or a deleting host's room.
3. Close and recursively delete hosted rooms (including all child collections). Delete the account's memberships and authored recommendations, remove its votes from other recommendations and clean legacy embedded rosters. Other users' unrelated library/rooms/requests remain.
4. Recursively delete users/{uid}, verify known account references are absent, then delete Auth. Mark complete only afterward.
5. A five-minute worker retries incomplete jobs. Expired leases recover process death; repeats also work after Auth deletion.
6. Completed UID-only tombstones remain for 24 hours to reject still-valid old tokens. A daily worker removes old completed jobs only after checking Auth is absent. Pending jobs never expire automatically.

Normal listener departure is a separate task: keep requests for the ongoing session. Account deletion removes the author's identifiable requests; this does not change ordinary leave behavior.

## Android integration
A persisted per-UID barrier blocks new library/stat uploads and drains existing uploads before calling the server. Wrong initial credentials do not pause backups. An unconfirmed request leaves backup paused for that UID; retry is explicit. There is no unsafe client-only fallback. Local Room/downloads/queue are not deleted or migrated.

## Verification
- npm test: workflow authorization/order/partial failure tests.
- firebase emulators:exec --only firestore,auth --project demo-auralis-account-cleanup "node --test test/deletion.emulator.js"
- Android AccountWriteBarrierTest, CloudSyncConflictTest and Listen Together tests.
Emulator tests refuse to run unless both emulator environment variables exist.

## Deployment gate (not performed)
Cloud Functions / scheduled workers need a billing-enabled Firebase project. Deployment changes cloud behavior; do not run it before review/approval. Rules, functions and collection-group indexes must all be deployed and indexes ready before shipping the APK. The callable region and Android default are us-central1.
After approval, deploy the account-cleanup codebase plus Firestore indexes/rules, then verify deletion using a disposable account with another account's data present. Never use the real library as test data.

Scope covers the current Auth + Firestore model. No per-user Storage/Realtime Database writes were found; re-audit if those are added. External Auth console deletion is not routed through this app endpoint. A permanently unavailable backend keeps accepted deletion pending; monitor pending jobs before calling the system production-verified.
