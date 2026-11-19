# Auralis — Cloudflare library sync migration handoff

> **Production live — 19 November 2026:** owner approved and deployed automatic library migration for all authenticated accounts (`LIBRARY_AUTO_IMPORT=true`, production `LIBRARY_AUTO_UIDS=*`). New users migrate on their next updated-client sync without manual UID registration; old apps retain their bridged Firestore path. Real production signup/automatic admission, authenticated endpoints, native Mac restore/realtime edits and the legacy mirror passed. Adopted the earlier manually fenced account without restoring its prior deletions. See [production rollout report](docs/library-sync/production-automatic-rollout-2026-11-19.md). Production config is committed on; no rollback or Firestore-rules deployment was performed. Windows/actual shipped older binaries remain separate verification gaps.

> **Latest live staging pilot — 19 November 2026:** dedicated Firestore-only backend identity configured on staging, automatic import restricted to one disposable account, and actual signed-in Android/Mac import, bidirectional edits, stale legacy snapshot, native Firestore-mode bridge and restart catch-up passed. Controlled budget failures and CAS rollback/recovery preserve data. Fixed the Mac pilot launcher, automatic rollback fence assumption and interrupted-reimport backup retention. See [live pilot report](docs/library-sync/staging-automatic-pilot-2026-11-19.md). Production Worker/rules/public activation remain untouched by this run; Windows and actual shipped older binaries are still separate acceptance gates.

> **Current implementation — 19 November 2026:** shared work is on `main`. Automatic authenticated library bootstrap, a backend old-APK reconciliation bridge, global legacy-I/O budgets, persisted crash recovery, fenced-account adoption and coordinated rollback are implemented locally. Updated enabled clients wait for verified import instead of directly falling back to Firestore. See [automatic migration and admin rollout handoff](docs/library-sync/automatic-migration.md) and [verification](docs/library-sync/automatic-verification-2026-11-19.md). Backend credentials, live staging/device acceptance and production activation are still admin gates; do not publish the new APKs before the backend is ready. Older branch/rollout statements below are historical. Approved Mac UI exclusions remain intact.

> Approved main integration (17 Nov): latest main `ffd5b50f` integrated selectively after checkpoint `63eb1afc`. See `docs/main-integration-2026-11-17.md`. Playback, cover, artist-stats, Android lyrics/imports and Discord/tooling included; existing Mac input/window code and fullscreen transition preserved. Added optional artist metadata to local Cloudflare history protocol/bridge; server extension is not deployed. Native playback shutdown race fixed and tested. Public sync flags remain off. Full desktop GUI suite is not green: native search-drag test fails and headless artwork tests can deadlock; report records exact scope. No push/deploy/account migration.

> **Latest signed-in pilot completion (17 Nov, evening):** the real phone/Mac checks are now complete for settings/history: 10-minute restricted deep Doze, 14.1s wake catch-up, full reboot with retained sign-in and 15.81s catch-up, real clear failures preserving both listens, successful retry/remote clear, and native Firebase test-account deletion. The dedicated hylol account is **deleted** (`USER_NOT_FOUND` on refresh); create a new disposable account for future tests. Fixed cloud-before-local clear ordering and surfaced retry errors on both apps. Fresh staging account tests 50/50, shared clear tests 4/4, Android clear tests 2/2. See `docs/library-sync/signed-in-pilot-2026-11-17.md` for evidence and limits. Both pilots stopped, normal battery settings restored, personal installation/profile untouched. Older “pending phone/clear/Doze” notes below are historical. Windows and a longer unforced battery soak remain outside this run; production/rules/public activation/library migration unchanged. Legacy library quota warning still observed; this pilot does not resolve Firestore quota.

> Current signed-in pilot: `docs/library-sync/signed-in-pilot-2026-11-17.md`. Added isolated Android `syncPilot` variant (`com.auralis.music.syncpilot`) and `scripts/run-sync-pilot-macos.sh` with explicit staging/profile guards. Owner signed the existing test account into both native apps. Real auth, settings, bidirectional playback history, background recording/catch-up and both clear confirmations were exercised. Personal app/profile data were not copied or cleared; production activation and library migration remain off.

> Phone follow-up: see `docs/library-sync/android-device-verification-2026-11-17.md`. Five physical-device tests passed. Fixed `LibraryCloudClient.erase()` skipping settings/listens when library sync is disabled, identically in both clients; new staging regression passes (11/11 client E2E). Regular release restored on the phone with signing/data retained. Full signed-in UI, clear-error UX, and battery/Doze coverage remain pending.

## Latest continuation — 17 November 2026

Codex resumed Claude's interrupted staging verification. See `docs/library-sync/verification-2026-11-17.md` for exact results and remaining gates. Server tests 107/107, emulator rules/migration 48/48, staging client 10/10, settings bridge fixtures 10/10, desktop profile 50/50, local desktop core 250 tests (one existing skip), Android sync 51/51; both release builds succeeded with rollout off. Root release APK retains its signing certificate.

Added and verified an additional catch-up fix for a topic registered after the shared socket is already open, identically in both clients. Its regression failed before the fix and passes on staging/local. Also corrected clear-stats wording about old APKs retaining local copies. No production deployment, rules, secrets or real-account migration changed in this continuation. Android lifecycle/clear UI and Windows runtime remain unverified.

The original migration handoff below records the earlier starting scope, not the current implementation status. Settings/history have since been implemented locally and deployed to staging as described in the verification report. Preserve all accumulated work and unrelated notch changes.

---

Updated: 14 November 2026. Intended recipient: Claude working in this repository.

## Objective and authorization

Replace Firestore account library sync with Cloudflare SQLite-backed Durable Objects, keeping Firebase Authentication and existing accounts. Support Android and universal desktop (Windows and macOS). Keep rollout consistent when older APKs/desktop versions are still installed.

The user requested this handoff after reviewing the plan below. No Cloudflare library implementation or production migration has been performed. Implement locally and validate first. This handoff is not authorization to deploy, change live security rules, enable paid services, or migrate real accounts. Follow existing production deployment restrictions in the repository.

## Workspace safety

- Checkout: `/Users/sharthakjaiswal/Desktop/Auralis`; observed branch `macos`, HEAD `9a652c3f`. Recheck current state.
- Read `AGENTS.md`, then inspect `git status --short` and relevant diffs before editing.
- There is substantial unrelated, uncommitted macOS notch work alongside the sync fixes below. Preserve all of it; do not reset or overwrite changes.
- `windows` and `windows/core` ship to BOTH Windows and macOS. Preserve shared desktop behavior and platform guards, bundled runtime, signing and SSL protections.
- The previous historical handoff was preserved at `docs/handoff-2026-10-28.md`. Its Windows-machine paths and deployment state are historical.

## Verified findings and existing fixes

1. Playlist/like sync exists on both Android and desktop and stores the library at Firestore `users/{uid}`.
2. The supplied `/Users/sharthakjaiswal/Downloads/firebase-desktop.json` exactly matched `windows/local-resources/firebase-desktop.json`. Both target Android's project, `auralis-70cf8`. Wrong/missing Firebase configuration was not the cause.
3. A live read using the saved Mac session refreshed authentication successfully, confirmed the expected UID, then returned HTTP 429 / `RESOURCE_EXHAUSTED` / `Quota exceeded.` This established quota exhaustion at diagnosis, NOT the precise quota metric or the feature consuming it. Recheck current conditions if necessary without exposing credentials.
4. Existing Cloudflare WebSockets handle Auralis Connect playback/device/queue synchronization on BOTH Android and desktop. They do not handle account playlists or likes.
5. Desktop previously checked remote history periodically, but fetched library changes only on startup/manual refresh/local edits. Failed startup restores were not retried automatically.
6. Existing working-tree fixes add `DesktopAccount.syncCloudLibrary`, serialized library sync, retry of initial pulls, visible `syncMessage`, and library checks every minute. A rejected upload can still apply an already-fetched cloud merge locally without advancing the acknowledged baseline. Flow collection avoids cancelling a sync when its own remote updates emit local changes.
7. The user correctly raised scaling concerns about those added Firestore reads. Replace polling in the Cloudflare path with notifications; do not simply poll another database every minute.

Earlier verification: 19 focused account/history tests passed and `:windows:createDistributable` succeeded. This predates subsequent unrelated workspace changes: rerun checks after implementation. Live cross-device recovery was NOT verified because Firebase rejected reads. No Android changes, live migrations, billing changes or cloud library implementation occurred in that investigation.

## Code map

- Android account library: `android/app/src/main/java/com/auralis/music/domain/auth/GoogleAccountSyncManager.kt` — `restoreLibraryFromCloud`, `backupLibraryToCloud`, `syncLibrary`, `startContinuousCloudSync`.
- Android parsing/application: `android/app/src/main/java/com/auralis/music/data/sync/AccountRestore.kt` and adjacent sync classes.
- Desktop account: `windows/src/main/kotlin/com/auralis/music/windows/profile/DesktopAccount.kt` — `pullCloudBackup`, `pushLibrary`, `syncCloudLibrary`.
- Desktop scheduling: `windows/src/main/kotlin/com/auralis/music/windows/AuralisDesktopApp.kt`.
- Desktop sync status: `windows/src/main/kotlin/com/auralis/music/windows/profile/AccountPage.kt`, `DesktopSignInDialog.kt`.
- Merge/baseline implementation: `shared/src/commonMain/kotlin/com/auralis/music/data/sync/AccountLibraryMerge.kt` AND `windows/core/src/commonMain/kotlin/com/auralis/music/data/sync/AccountLibraryMerge.kt`. Inspect both copies.
- Desktop Firebase REST: `windows/src/main/kotlin/com/auralis/music/windows/together/FirestoreRest.kt`.
- Existing Cloudflare service: `connect-server/src/index.js`, `core.js`, `auth.js`, `wrangler.toml`, `PROTOCOL.md`, `test/`.
- Existing clients: Android `data/connect/AuralisConnect.kt`; desktop `windows/.../connect/DesktopConnect.kt`; shared and desktop-core `data/connect/ConnectRealtimeBackend.kt`.
- `connect-server/src/auth.js` already verifies Firebase ID tokens with Google's public keys without reading Firestore. Reuse project/UID verification and reject anonymous credentials for account library access.
- Preserve the production Connect URL in root `gradle.properties` and its existing behavior/protocol.
- Rules and tests: `firestore.rules`, `firestore-tests/`.
- Background: `docs/connect-websocket-plan.md`, `docs/connect-staging-results.md`. These include historical phases; verify current behavior from code.
- Desktop regressions: `windows/src/test/kotlin/com/auralis/music/windows/profile/AccountCloudRetryTest.kt`, `AccountServicePlaylistRestoreTest.kt`, `AccountHomeStartupTest.kt`, `HistorySyncTest.kt`.

## Architecture and initial scope

Migrate playlists/tracks, likes, saved albums/artists, pins, and deletion records. Preserve playlist IDs, creation dates, metadata, track ordering and legitimate duplicates. Keep listening history/stats and unrelated Firestore features out of the first migration; inventory/document their remaining quota usage. This does not eliminate all Firebase use.

Retain Firebase Authentication, UIDs, local libraries, and account separation. Add a SEPARATE SQLite-backed `LibraryAccount` Durable Object namespace, one object per verified UID. Reuse Connect authentication patterns, not its playback storage or delayed-persistence optimizations. Acknowledged library edits must survive disconnects/restarts.

Persist library data, monotonically increasing revision, deletion records, accepted-operation deduplication, bounded change log, and migration checkpoints/source version. Commit edits, operation identity and revision atomically before acknowledging or broadcasting. Never hold storage transactions across external network requests.

Design indexed storage against real library sizes; do not rewrite all track rows for every like. Define payload limits, snapshot paging/chunking, change-log compaction and operation-ID retention. SQL row accounting is not equivalent to Firestore document accounting.

## Client protocol

- Introduce a transport boundary; retain reusable local library/merge behavior. Do not combine migration with UI redesign.
- Persist a local outbox. Edits include like/unlike, playlist create/delete/rename, track add/remove/reorder, saved album/artist changes and pins.
- Each operation has stable operation ID, device identity, protocol version and base revision. Duplicate submissions apply once; acknowledgments reference durable revisions.
- Specify concurrent-edit semantics explicitly. Use existing Kotlin merge tests as compatibility fixtures; blindly porting snapshots or using last-writer-wins is insufficient.
- Fetch initial snapshot, then changes since the last acknowledged revision. When the log is too old, fetch a snapshot and rebase pending edits.
- Use hibernating WebSockets while appropriate to platform lifecycle; reconnect with backoff/jitter and refreshed authentication. No recurring unchanged-library downloads or storage heartbeats.
- Safely coordinate local application, cursor/baseline advancement and outbox ACKs. Downloaded server changes must not echo as new user edits.
- Backend failures keep edits locally pending, show status and retry. They never mean an empty account or permission to switch backend.
- Scope cursors/outbox/baselines/credentials to UID; protect sign-out/account switching from cross-account uploads.
- Preserve imported YouTube playlist IDs and existing two-way sync semantics. Cookies stay device-local. Restoring a playlist must not automatically activate external write sync on every device. Test protected/service-linked playlist handling and duplicate external edits.

## Consistent rollout: one writable authority per account

| Account state | Writable authority | Old clients |
| --- | --- | --- |
| Legacy | Firestore | Existing sync continues |
| Migrating | Library cloud writes briefly fenced; updated clients queue edits | Firestore library writes rejected |
| Cloudflare | Library Durable Object | Must update for cloud library sync |

Tradeoff: unchanged old apps cannot keep syncing after cutover without a bridge. The recommended first implementation has NO bidirectional bridge and NO blind dual writes. Indefinite old-client compatibility would retain Firestore quota usage and substantially enlarge the consistency problem.

Ship migration-capable Android AND desktop releases first, initially leaving existing accounts Legacy. Clients discover account authority before library sync. An unreachable discovery service is not evidence of Legacy mode; retain local edits and retry. Specify authenticated discovery, caching, migration epochs and stale-state handling. Server enforcement remains authoritative.

Start new accounts directly on Cloudflare only after pilot validation. Do not accidentally seed them with another account's local library.

### Per-account cutover

1. Record a resumable migration job and mark the account Migrating.
2. Enforce a Firestore write fence on migrated library fields using SECURITY RULES, not only a new-client flag.
3. Read the FINAL Firestore library AFTER the fence is active, recording server update/version and schema.
4. Import idempotently into the Durable Object, preserving IDs, metadata and deletion records.
5. Validate canonical content checksum and collection/track counts; durably commit snapshot and checkpoint.
6. Mark Cloudflare authoritative ONLY after that verified import exists. Coordinate discovery and DO migration epochs and fail closed on inconsistent intermediate states.
7. Updated clients fetch the snapshot, reconcile trusted baselines/pending local edits, then submit their outboxes.

Each step must survive crashes and retries. If quota blocks the final Firestore read, remain pending and back off. Never import a failed read as an empty library. Before authority changes, failure recovery may restore Legacy access only when Cloudflare has accepted no authoritative client writes. After Cloudflare accepts edits, there is no automatic fallback to stale Firestore.

### Write-fence requirements

- Migration metadata is server-controlled. Clients cannot remove/change the fence.
- Inspect root-document shape/key-count rules and old whole-document replacements before choosing metadata placement.
- Fence migrated fields without breaking unrelated root fields, stats/history, Connect, or other features.
- Root-document deletion can bypass a field-only fence: handle it and account deletion explicitly.
- Test merge writes, full replacements, creates and deletes from old versions.
- If rules perform migration metadata lookups, account for their billed reads during rollout.
- Use least-privilege backend credentials for final reads/control writes; no service-account secrets in clients, logs or Git.

### Dormant and offline devices

Device version/last-seen tracking helps select cohorts but cannot stop a months-old APK returning. Enforce fences server-side. Unchanged old clients may display only generic sync/permission errors; do not promise they can show a new update-required UI. Updated clients should explain the requirement.

After updating, reconcile offline edits against their previous trusted baseline when present. Missing local items alone are not deletions, especially after reinstall/partial restore. Define the supported offline window, deletion retention, and recovery beyond log/tombstone retention.

## Required tests and release gates

- Old Android and desktop clients editing before, during and after cutover.
- Concurrent playlist edits, like/unlike conflicts, track reorder/remove and duplicate tracks.
- Duplicate/reordered operations, lost ACK, restart after commit before broadcast.
- Offline edits after migration, stale cursors/snapshots, compacted logs and deletion retention.
- Crash/retry at EVERY migration boundary, including fence-before-import and import-before-authority-flip.
- Firestore quota failures, Cloudflare downtime, expired tokens and cross-account authorization attempts.
- Fresh-install restore of full imported playlists and likes.
- Existing YouTube two-way sync without duplicate or destructive external edits.
- Account sign-out, switch and deletion in all migration states.
- Idle connections produce no periodic library database work; measured load fits the budget.

Use staging/local accounts and production-shaped fixtures. Test security rules in the emulator and persistence/restarts in the Workers runtime. Run relevant existing regressions. Do not experiment destructively on real accounts.

Roll out: internal test accounts → small pilot → progressively larger cohorts, gated by correctness and measured usage rather than elapsed time. Keep real cutover disabled until explicitly authorized and verified tooling is ready.

Observe migration state/failures, restore latency, conflicts/dedup hits, pending-outbox age, reconnects, SQL rows read/written, transferred bytes and quota headroom. Avoid unnecessarily logging private libraries, emails, tokens or cookies.

## Recovery and rollback

Preserve frozen Firestore source snapshots for a defined recovery window. Build independent export/restore tooling for Cloudflare libraries and verify provider recovery availability/plan eligibility.

After authoritative Cloudflare edits exist, rollback requires fencing writes, exporting latest Cloudflare state, verified reverse import, and coordinated authority/security-rule changes. Switching a flag back to the frozen Firestore document loses edits. Temporary outages normally use the local outbox and reconnect, not a backend switch.

## Suggested delivery sequence

1. Inventory field ownership, merge semantics, authentication/lifecycle; specify protocol/state machine and fixtures.
2. Implement/test LibraryAccount persistence, auth/routing, edits, snapshot/delta delivery and notifications.
3. Add common transport boundaries and persistent outboxes; wire Android/desktop with rollout disabled.
4. Implement/test fencing, migration orchestration, verification and recovery tooling.
5. Run mixed-version/offline/failure/load checks and build both platforms.
6. Deliver exact staged deployment/cutover commands, rollback procedure and evidence for production approval.

Completion means tested local implementation plus reviewable rules/server/migration artifacts, with outstanding live checks clearly identified and no unapproved production changes. Estimate discussed: roughly 2–4 weeks for rollout-safe initial scope, not a promise. Stats migration or indefinite old-client bridging expands it.

Follow `AGENTS.md` for builds: Android changes require `:app:assembleRelease` and refresh of root `Auralis-Release.apk`, preserving app ID/signing/data. Desktop needs relevant tests and `:windows:createDistributable`; Windows installed updates use `scripts/update-windows-app.ps1`. Preserve macOS bundled VLC/browser, signing and native protections. A Mac build does not prove Windows runtime behavior.

## Revised phases (supersedes "Suggested delivery sequence" ordering)

Added after a review on 14 November 2026. Each phase has an exit gate; do not start a phase whose prerequisite decisions (below) are open.

| # | Phase | Exit gate |
| --- | --- | --- |
| 0 | Quota attribution: identify the exhausted Firestore metric, the Firebase plan, and the per-feature consumers (library pulls/pushes, desktop history checks, the new 60 s library check in `AuralisDesktopApp.kt`, listening/{day} writes, Connect docs, old-client retries). | Top consumers known; written answer to "does moving only the library relieve the 429?" If not, re-scope before Phase 2. |
| 1 | Specification: field ownership, protocol, authority state machine, discovery activation, Legacy polling policy, first-sync operation conversion, retention limits, fixtures. | Decisions D1–D5 recorded and approved. |
| 2 | `LibraryAccount` DO + discovery endpoint, local/Workers runtime only. | Persistence, restart, lost-ACK, dedup and idle-no-storage-work tests pass. |
| 3 | Client transport boundary + per-UID outbox, wired with rollout off (D2). | Existing account/history regressions plus outbox/echo/account-switch tests pass. |
| 4 | Firestore fence rules (D4). | Emulator tests reproduce real legacy write shapes (see below). |
| 5 | Migration orchestrator, verification, export/restore tooling. | Crash/retry at every boundary; failed read never imports as empty. |
| 6 | Mixed-version, offline, failure and load checks; Android and desktop builds. | Release gates above pass with evidence. |
| 7 | Staged deployment/cutover package for explicit approval. | User approval; nothing live before this. |

## Decisions required before implementation

Proposals only until approved.

- **D1 Authority ownership.** One system of record for each account's state (Legacy / Migrating / Cloudflare + epoch) and for the migration job. Proposal: the `LibraryAccount` DO owns state and job checkpoints; the Firestore fence and discovery responses are derived from it in a fixed order (fence → final read → import → verify → DO flips → discovery reports Cloudflare). Inconsistent combinations fail closed.
- **D2 Discovery activation / "rollout off".** Until a production discovery endpoint is approved, deployed and switched on, clients must not consult discovery and must keep existing Firestore sync working. "Unreachable discovery is not Legacy" applies only once discovery is activated for that build/account. Define the activation mechanism (build flag vs signed remote setting), how a client learns discovery is active, and caching of the result per UID.
- **D3 Legacy polling policy.** The working-tree 60 s desktop library check increases Firestore reads during a long Legacy period. Decide before shipping it: back it out, lengthen it substantially, or replace with retry-on-failure + event triggers (startup, focus, local edit). Inform with Phase 0 numbers.
- **D4 Fence placement.** Recommended: a separate server-only authority document read by rules. This is a design choice (clearer ownership, no root key-count pressure, immune to root delete/recreate), not a technical necessity: root-document metadata can also work if rules forbid clients changing or removing it and deny root deletion while fenced. Leaving current `users/{uid}` rules unchanged is unsafe either way (unknown keys allowed, owner delete unconditional). Account for billed rule `get()` reads.
- **D5 First-sync operation conversion.** Today's merge is state-based with a baseline; only playlists have tombstones (`librarySync.deleted`, time-limited). Specify how a device's first Cloudflare sync turns local state vs its trusted baseline into operations (adds, removals, reorders, likes/unlikes, saved albums/artists, pins); behavior with no/stale baseline (no inferred deletions); and how the existing merge tests map to operation-level fixtures.

## Legacy write shapes the fence tests must reproduce

- Desktop (`FirestoreRest.write`): `POST {endpoint}:commit` with one `update` write plus `updateMask` of top-level field paths (merge); `merge = false` sends no mask (full replacement); optional `updateTransforms` server timestamp; optional `currentDocument.exists = false` for create. Library push in `DesktopAccount` is an unconditional (non-transactional) write to `users/{uid}`. `FirestoreRest.delete` sends HTTP `DELETE` on the document.
- Android (`GoogleAccountSyncManager`): transaction `tx.set(doc, …, SetOptions.merge())`.
- Old-client coverage: exact request replay against the rules emulator, plus integration tests from historical client source built against a test configuration where practical. Unmodified production binaries cannot easily be pointed at staging.

## Schedule

The 2–4 week figure was a rough guess. It is not a schedule until Phase 0 and D1–D5 are resolved.

## Implementation status (14 November 2026)

Local implementation exists with rollout disabled. Nothing is deployed, no production rules were
changed, no real account was migrated, no billing was enabled. Start with these documents:

- `docs/library-sync/phase0-quota.md` — Firestore call inventory and the console evidence still
  needed. The quota consumer is NOT identified; the code suggests it is not the library.
- `docs/library-sync/spec.md` — D1–D5 resolved, protocol, limits, storage, cutover.
- `docs/library-sync/runbook.md` — approvals, exact deployment/cutover/rollback commands, budget.
- `docs/library-sync/test-results.md` — what was run, results, and what is not verified live.
- `docs/library-sync/approval.md` — remaining risks and the exact production changes awaiting
  approval. `staging.md` (staging checks waiting on a Cloudflare login), `rule-reads.md` (fence
  cost for all users).
- `docs/library-sync/account-sync-proposal.md` — proposal to move settings and listening-history
  updates to the same Cloudflare sync service (prompt updates, reconnect catch-up), with what stays
  on Firestore. `patches/interim-polling-reduction.patch` is the separate, unreleased 15-minute
  polling stopgap; it is NOT applied to the working tree.
- `docs/library-sync/pilot.md` — internal-account-only pilot: two allowlists, verified
  export/restore, monitoring, stop conditions. Broad rollout stays disabled; nothing migrates
  automatically.

Code map for the new path:

- Server: `connect-server/src/library.js` (`LibraryAccount`, routes), `library-core.js` (shapes,
  limits, checksum, Firestore document conversion). Wire reference in `connect-server/PROTOCOL.md`.
- Migration and recovery: `connect-server/tools/library-migrate.mjs`; cost measurement:
  `tools/library-measure.mjs`.
- Rules: `firestore.rules` (`libraryAuthority`, `changesLibrary`, `libraryFenced`).
- Clients: `data/sync/LibraryCloud.kt` and `LibraryCloudClient.kt`, kept IDENTICAL in
  `shared/src/commonMain` (Android) and `windows/core/src/commonMain` (desktop); a desktop test
  fails if they differ. Wiring: `DesktopAccount.pushLibrary`/`pullCloudBackup`,
  `LibraryCheckSchedule`, and Android `GoogleAccountSyncManager.syncLibrary`.
- Rollout switch: `library.sync.url` in root `gradle.properties`, blank = off.

## Current official documentation to verify

Provider APIs/limits change. Fetch current documentation before implementation:

- https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/
- https://developers.cloudflare.com/durable-objects/best-practices/websockets/
- https://developers.cloudflare.com/durable-objects/platform/pricing/
- https://developers.cloudflare.com/workers/platform/limits/
- https://firebase.google.com/docs/firestore/security/rules-conditions
- https://firebase.google.com/docs/functions/firestore-events (duplicate/out-of-order trigger delivery matters if a bridge is proposed later).

Free tiers have finite account-wide compute/request/storage limits. Reduced traffic comes from change-driven sync and bounded storage work, not an unlimited-free promise.
