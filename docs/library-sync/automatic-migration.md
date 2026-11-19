# Automatic library import and old-app compatibility

Implemented on main, 19 November 2026. **Source implementation and local verification are not a production rollout.** Deploy and validate the backend before distributing these updated clients. New clients wait when bootstrap is unavailable/disabled; they no longer fall back to Firestore for the library.

## Data path

- Updated Android/macOS/Windows clients call authenticated `GET /v1/library/bootstrap`. The Worker derives the UID from the verified Firebase token, never from a submitted account ID. The account's Durable Object serializes bootstrap, client mutations, WebSocket messages and bridge alarms.
- `LIBRARY_AUTO_IMPORT=true` AND membership in `LIBRARY_AUTO_UIDS` admit an import. Unset/empty list admits nobody. A dedicated test UID supports an internal pilot; `*` explicitly enables self-service onboarding for all authenticated accounts after approval. There is no ongoing manual migration list after broad enablement.
- The backend uses `LIBRARY_FIRESTORE_SERVICE_ACCOUNT` (a Worker secret) to read the legacy document. An app token or Firebase client configuration file is not a backend credential. The project's identity must match `FIREBASE_PROJECT_ID`.
- Permission, network, quota, malformed data and verification failures leave bootstrap pending, with durable exponential backoff. A source 404 is accepted as a new account only after the parent database is verified. No failure is interpreted as an empty library. Android retries pending discovery while visible with backoff; desktop uses its existing retry schedule. Local edits remain saved until readiness.
- The initial normalized source, checksum, bridge baseline and authority transition are saved atomically with the verified Cloudflare snapshot. Concurrent first logins reuse that account state. Re-login never imports over already accepted Cloudflare edits. SQL journal values are chunked to avoid a large library exceeding a single SQLite value's limit.
- Updated clients then use the existing revisioned operations/outbox protocol and notifications. Android's optional onboarding-picks restore reads the imported data through the service, so an unrelated root Firestore read cannot prevent library restoration.

## Old APKs remain on Firestore

Automatic accounts are **not permanently fenced**. Existing security rules and legacy document shapes remain in use. The backend reconciles Firestore and Cloudflare against its persisted last shared baseline:

- Keep playlist IDs, track order and repeated occurrences; merge independent additions. Concurrent ordering conflicts prefer Cloudflare's order, inserting additional legacy tracks relative to surviving legacy neighbours.
- Concurrent deletion of a baseline playlist/song/set entry wins over an edit to that baseline entry. Likes, saved albums/artists and pins are reconciled too. Playlist tombstones use the existing retention policy.
- A Firestore mirror is a masked `POST :commit` with the exact read version as a precondition, preserving profile/other fields. A racing old write causes re-read/reconciliation, not an overwrite.
- That same atomic commit writes a server-only `libraryBridgeJournal/{uid}` marker. A persisted pending mirror plus this marker recovers a lost acknowledgement even if an old client replaced the root afterward. Bridge echoes produce no writes. Operation deduplication survives a bridge-induced full pull/reset.
- A previously existing root document disappearing stops the bridge for investigation; it is not silently recreated. Already accepted Cloudflare data remains available.

New-to-new changes use notifications, normally within seconds. Old-to-new compatibility checks run about every **five minutes while an updated device is connected**; a new edit also schedules reconciliation. Idle accounts with no connected device or dirty/pending mirror do not keep polling. This delay concerns the legacy bridge, not normal updated-device sync. There is no automatic library-bridge retirement window; the existing `retire-bridge` command concerns settings/history only.

Legacy clients submit snapshots without complete causal information. The baseline and deletion policy cannot infer the intent of every arbitrarily stale full replacement. Test with the actual oldest APKs you still support before broad rollout; request replay and emulator tests alone are not that evidence. The existing Firestore document/size limits also still constrain what an old APK can receive. A library that outgrows those limits remains safe on Cloudflare but its legacy mirror can fail and needs operator attention.

## Quota budget

A separate `LibraryLegacyBudget` Durable Object reserves backend Firestore operations across all accounts in an environment. Defaults: 5,000 reads and 5,000 writes per Pacific calendar day, including conservative reservations for failed requests. A mirrored commit reserves two writes (library plus marker); conditional fence deletion reserves a write. Quota/budget refusal backs off instead of spinning.

These caps do **not** control old APKs' own traffic, other Firestore consumers, or all Cloudflare charges. They are headroom limits, not a promise that a large mixed-version fleet stays free. When exhausted, first imports and legacy compatibility wait; already migrated updated clients can continue syncing with Cloudflare. Measure Firebase and Cloudflare usage during the pilot before changing caps or expanding admission. No paid service, Cloud Function, event trigger or production configuration was enabled by this work.

## Existing manually fenced accounts

They remain fenced until an operator explicitly adopts the compatibility bridge:

```sh
node tools/library-migrate.mjs adopt-bridge <uid> --allow-old-library-writes
```

Requires automatic import enabled and backend credentials on the Worker. The backend checks the frozen Firestore source against the original manual-import checksum, mirrors subsequent Cloudflare edits, and only then conditionally removes the original fence. A different/stale source or replaced fence is refused, not guessed. Interrupted adoption resumes from its persisted journal. Inspect a refusal and verify backups before repairing the source; do not blindly delete fences.

Useful operator commands (existing environment credentials required):

```sh
node tools/library-migrate.mjs status <uid>
node tools/library-migrate.mjs export <uid>
node tools/library-migrate.mjs export-source <uid>
node tools/library-migrate.mjs sync-legacy <uid>
node tools/library-migrate.mjs rollback <uid>
```

`status`/`report` recognize an unfenced automatic account as valid and expose bridge errors/backoff. The admin `export-source` action returns the immutable initial source backup; `export` remains the current verified library. Exports contain private library data: keep them outside Git with restricted access.

For automatic rollback, the tool freezes updated writers and saves a verified backup. The backend reconciles the latest legacy snapshot with its persisted baseline, conditionally mirrors with conflict retries and the crash-recovery marker, then atomically marks authority Legacy. Old writers remain live; their writes after the handoff stay in Firestore. This path needs no new Firestore security-rule fence. Existing manually migrated accounts retain the original fenced rollback path. The automatic job stays paused, so the next login cannot undo rollback. Updated Cloudflare-only clients wait during a rollback; restoring old-client service requires coordinating the client release, or explicitly resume automatic import after verifying the rollback:

```sh
node tools/library-migrate.mjs resume-automatic <uid> --resume-verified-rollback
```

Account erase removes the DO's initial-source backup and bridge journal alongside the library. The Firestore mirror marker is deleted by a durable backend cleanup alarm; quota/credential failure retries without blocking Firebase account deletion or retaining the library backup. Existing manual fences remain part of the administrator's legacy cleanup policy. No real account was erased during this implementation.

## Admin handoff: server first, clients second

The production owner must perform the remaining environment work. This change does not grant deploy approval.

1. Review the diff, backups, budget and merge policy. Use a dedicated least-privilege Firestore service account for the correct project; do not use a project-owner key. It needs database metadata get and document get/create/update, plus delete for adopting the old fence. Backend access bypasses client rules, so audit its IAM role. Never put credentials into an APK, Git or chat.
2. Set secrets on **staging only**, reading the private service-account JSON directly from a local file. Enter only the dedicated test account UID when prompted for the admission list:

   ```sh
   cd connect-server
   npx wrangler secret put LIBRARY_FIRESTORE_SERVICE_ACCOUNT --env staging < /absolute/private/path/service-account.json
   npx wrangler secret put LIBRARY_AUTO_UIDS --env staging
   npx wrangler deploy --env staging --var LIBRARY_AUTO_IMPORT:true
   ```

   Preserve the existing staging `ACCOUNT_SYNC_TOPICS` secret; do not shadow it with a plain `--var` of the same name. The checked-in defaults stay off. The new namespace migration is `v4` (`LibraryLegacyBudget`); retain all earlier migration tags. Do not replace the production admin secret or staging test-login secret. The Firebase project must be the same project that issued the test account token. Synthetic staging tokens are not a substitute for a Firebase-authenticated import from a real test account.
3. Build isolated Android internal variant with `./gradlew :app:assembleSyncPilot -Psync.pilot.library=true`; keep its output internal to Gradle (the two root APK deliverables remain release/debug). Build Mac normally, then launch `scripts/run-sync-pilot-macos.sh --library` for an isolated profile against staging. The guard permits only the exact staging library endpoint; normal builds keep production endpoints. Do not sign a personal account into this import pilot.
4. Test a disposable existing account on phone and Mac, and the friend's Windows build. Include Pinkman's 111→112 track edit while deleting another playlist, ordering/duplicates, likes/unlikes, saved items, offline edits, restart/reconnect and quota failure. Run at least one actual supported old APK against the same test account and verify both directions. Existing locally tested request shapes are useful but do not replace these device checks.
5. Export and inspect the initial source and current library, test rollback/recovery, and inspect both providers' usage. Confirm backend OAuth/IAM, actual missing-account metadata response and device lifecycle behavior. Do not advertise live readiness from unit-test results.
6. After approval, deploy production backend with admission limited to internal accounts, verify, then distribute matching clients internally. Broaden only after acceptance; `LIBRARY_AUTO_UIDS=*` removes per-user onboarding work but is a deliberate rollout action. Enable server configuration persistently in the approved deployment process so a later plain deployment does not unintentionally reset the import flag. Existing automatic accounts keep their bridge even when new-import admission is turned off.

Remaining direct Firestore paths include legacy history mirroring, profile/onboarding writes and account-deletion/legacy room cleanup. This change removes the **library fallback** in enabled builds; it does not claim every Firestore call or Firebase Auth has been removed.

Primary references: [Durable Object alarms](https://developers.cloudflare.com/durable-objects/api/alarms/), [atomic Firestore commits](https://firebase.google.com/docs/firestore/reference/rest/v1/projects.databases.documents/commit), [Firestore server IAM](https://docs.cloud.google.com/firestore/native/docs/security/iam).
