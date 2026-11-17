# Account library on Cloudflare — test results

> Real-phone follow-up: [Android device verification, 17 November](android-device-verification-2026-11-17.md). Five tests passed on Android 16. They exposed and verified a fix for erasure when only settings/history are enabled. Final staging client tests are now 11/11. The regular signed release was restored with data preserved; full signed-in UI and battery/Doze checks remain separate.

> Latest continuation: see [17 November verification](verification-2026-11-17.md) for current staging, local regression and build results. The report below is historical evidence from 14 November; its production status and Android compilation limitations have since changed.

Last run on 14 November 2026, after the independent review's fixes, on macOS (Apple Silicon), on the uncommitted working tree of branch
`macos` at `9a652c3f`. Everything ran on this machine: the real Worker and Durable Objects in
`wrangler dev` (workerd), the Firestore emulator with project `demo-auralis`. Nothing reached
Cloudflare, Firebase or any real account.

## Results

| Suite | Command | Result |
| --- | --- | --- |
| Library server, pilot allowlist and restore, plus existing Connect server tests | `cd connect-server && npm test` | 85 / 85 pass |
| Security rules (Connect and the library fence) and the migration tool end to end | `cd firestore-tests && npm test` | 48 / 48 pass (30 rules, 18 migration) |
| Shared sync engine against an in-memory server | `./gradlew :windows-core:desktopTest --tests 'com.auralis.music.data.sync.*'` | 28 / 28 pass |
| Real client against the local server | same, with `AURALIS_CONNECT_PORT=8795` | 5 / 5 pass |
| Whole desktop core module | `./gradlew :windows-core:desktopTest` | 235 tests, 0 failures, 1 skipped (existing) |
| Desktop account, sync and history tests, including two desktop instances | `./gradlew :windows:test --tests 'com.auralis.music.windows.profile.*'` | 46 / 46 pass, including `AccountLibraryCloudTest` 8/8 |
| Staging smoke test on the deployed staging Worker (Connect + library, synthetic identities) | `node connect-server/tools/staging-smoke.mjs` | 12 / 12 steps pass |
| Real client against deployed staging | `:windows-core:desktopTest --tests '…LibraryCloudEndToEndTest'` with the staging variables | 5 / 5 pass |
| Two desktop instances plus a third account, real `DesktopAccount` code, against deployed staging | `:windows:test --tests '…AccountLibraryCloudTest'` with the staging variables | 8 / 8 pass |
| Existing Connect on deployed staging | `node connect-server/tools/measure.mjs check` (×3) | pass; round trip 298–343 ms |
| Android release build | `./gradlew :app:assembleRelease` | builds; root `Auralis-Release.apk` refreshed (same app id `com.auralis.music`, same signing certificate, `LIBRARY_SYNC_URL = ""`) |
| Desktop distributable | `./gradlew :windows:createDistributable` | builds; `codesign --verify --deep` passes; no `librarySyncUrl` in the launcher config (rollout off); Connect URL unchanged |
| Worker bundle and bindings | `npx wrangler deploy --dry-run` | valid; bindings `CONNECT`, `LIBRARY`, `CONNECT_UPGRADES`, `LIBRARY_REQUESTS` |

To rerun the tests that need a library server, start one first with `node connect-server/test/serve.mjs 8795`.

## Staging (14 November 2026)

Deployed only the staging Worker (`auralis-connect-staging`, version
`12bdca0c-6a1d-4248-b53d-0a2f90e3e2cc`) and set its `STAGING_SECRET`, `LIBRARY_ADMIN_SECRET` and
`LIBRARY_PILOT_UIDS=staging-*`. All checks used synthetic `staging-…` identities and synthetic
libraries; no Firebase account, cookie or session was used. Production `auralis-connect` was not
deployed: it still answers `/healthz` and returns 404 for `/v1/library/authority`.

On staging the idle check confirmed that pings to a hibernating library socket do no database
work at Cloudflare's edge, not only locally.

## Independent review

A separate reviewer traced migration, restore/rollback, offline reconciliation, account deletion
and security. Every confirmed finding was fixed, and a test reproduces each one. The cursor test
was checked to fail against the old code.

| Finding | Severity | Fix |
| --- | --- | --- |
| A push that changed nothing let the client move its cursor past another device's revision, so it missed those edits and later got stuck on stale playlist edits | high | The ack now carries `from` and `changed`; the cursor moves only when this batch moved it from exactly the device's cursor |
| Account deletion stuck for good if a step after erase failed | high | Erase works in any state and repeats successfully |
| Erase skipped when discovery was unreachable and the cached answer was legacy | medium | Erase asks the service directly and fails if unreachable |
| A library archived by a rollback survived account deletion | medium | Erase wipes in any state |
| A reset part-way through paging skipped unread data | medium | The client restarts from a full fetch on a reset at any page |
| Rollback could leave an account frozen when an old deletion record crossed its 180-day limit | medium | Export expires old records first, and the tool compares both sides at one cutoff time |
| One permanently refused entry stopped the baseline from ever being saved, so others' deletions came back | medium | The baseline is saved without the refused entries, which stay on the device |
| Restore accepted an export naming no account | low | The file's `uid` is required and must match |
| The desktop token check could read the uid after fetching the token | low | `FirestoreRest.accountIdTokenFor(uid)` checks inside one lock; account switches are synchronized |
| An outbox resent after the server forgot its operation ids could apply twice | low | An outbox older than 20 days is dropped; the merge derives any missing edit again |
| A missing Firestore document imported an empty library without confirmation | low | Requires `--allow-empty` |
| A device still signed in after an erase shows "being moved" until its token expires | low | Not changed; documented |

## Real-device checks

- **macOS (this Mac):** the new desktop build (`createDistributable`) launched on a copy of the app
  data with the sign-in removed. It started normally (startup components ready in 3.5 s; settings
  sync OFF because signed out), and created no library-service files, which confirms the rollout is
  off. The copied data included the device's YouTube session: the playlist sync loaded it, checked
  one linked playlist and left it pending review ("remote playlist changed since the preview"),
  writing nothing to YouTube. The copy was deleted afterwards.
- **Android:** no device was connected (`adb devices` empty), so nothing ran on a phone.
- **Not run on any real device:** signed-in sync on the new builds, the Cloudflare path, Windows,
  Android foreground socket handling and account deletion. Signed-in checks would read and write
  a real account's Firestore library and need the owner's go-ahead and an internal account.

## Failures and gaps found, not caused by this change

- `DesktopEscapeNavigationTest` (1 test) and `MacWindowChromeTest` (1 of 7) fail. Both also fail
  on a clean checkout of `9a652c3f` in a temporary worktree, with the same assertions, so they
  predate this work. Neither touches sync code.
- The full `:windows:test` run hangs in `HomeArtworkTest.shortcutHasMenuAndRemovalAndItsCoverBackgroundMoves`
  (a UI rendering test). The run was stopped and the account/sync subset above was run instead.
  The rest of `:windows:test` (about 190 UI and player test files) was not run to completion.
- Android unit tests do not compile: `FavoritePreservationTest` and `ListeningHistoryDeduplicationTest`
  have `FakeTrackDao` classes that no longer implement `TrackDao`. Those files and the DAOs are
  unchanged from `HEAD`. So `AccountLibraryMergeTest` (Android) was not run; the same merge code
  is exercised through the desktop engine tests.
- The existing Connect test harness only killed `npx` on macOS, so the server kept running and
  "restart" tests never restarted it. Fixed in `connect-server/test/harness.mjs` (process group).

## What the tests cover

Server (`connect-server/test/library.test.mjs`): an untouched account is legacy and refuses reads
and writes; missing, bad and expired tokens; admin routes return 404 without the secret; the
cutover (fence state, checksum-verified import, idempotent repeat, refused commit on mismatch, no
abort after the flip); abort; empty account; edits reaching other devices with the change but not
the writer; a lost ACK and reordered duplicates; concurrent playlist edits (`stale` then merge);
like/unlike and pin ordering; stale epoch, old protocol and oversized or invalid batches; account
isolation, including a uid supplied in headers or the body; paged restore of a large library in
order; reset after a re-import; rollback freeze, export and cancel; zero storage work for idle
sockets, pings and an up-to-date pull; expiry of removal records past the offline window;
account-deletion erase; socket expiry; persistence and dedup memory across a real restart.

Rules (`firestore-tests/library.rules.test.mjs`): every old write shape works before the fence;
another account's fence has no effect; while fenced, Android's transaction merge and the desktop's
`POST :commit` with `updateMask` are refused (one field or all); whole-document replacements
cannot change or drop the library; deleting the document does not lift the fence and recreating it
with a library is refused, through both the SDK and REST `DELETE`; settings (both platforms),
starter picks, listening history (SDK merge and the desktop's per-event mask) and Connect devices
keep working; the frozen library stays readable by its owner only; no client can read, create,
change or remove a fence; removing the fence restores old write shapes; existing size limits.

Pilot safeguards (`connect-server/test/library-pilot.test.mjs` and the migration tests): the
Worker refuses `begin` for uids not in `LIBRARY_PILOT_UIDS` (no partial matches; unset allows
none; `*` refused outside local tests); the tool refuses uids missing from its allowlist file and
changes nothing; unusable source entries stop the migration before import; export files verify
against their checksum; restore reproduces an export exactly, refuses damaged or foreign exports,
keeps a backup and moves devices to a new epoch; refusals and retries are counted; the report flags
a list that shrank below half, refused changes, and an account stuck migrating.

Migration (`firestore-tests/library.migration.test.mjs`): full cutover with a production-shaped
document (IDs, creation dates, metadata, order, repeated songs, imported YouTube playlist,
deletion records, pins, settings left alone); a crash after each of begin, fence, read and import,
then a resume (including a write that lands before the fence and must be imported); quota failure
on the final read stays fenced and never imports empty; fence write failure; a stored library
that does not match is not flipped; missing document; unusable entries reported; abort restores
Firestore exactly; rollback after edits keeps those edits, survives a crash between write and
verify, leaves settings untouched, and allows migrating again; `status` reports a leftover fence.

Clients (`LibraryCloudSyncTest`, `LibraryCloudEndToEndTest`, `AccountLibraryCloudTest`): the
first sync with a trusted baseline sends only offline edits; no baseline deletes nothing; a
reinstall with a stale baseline does not wipe the library; fresh-install restore; no echo of
downloaded changes; rename without resending tracks; concurrent edits merged and resent within one
sync; a lost ACK across an app restart; offline edits retried; reset after compaction keeps local
edits; pushed changes applied without a server read; service-linked playlists only gain songs;
authority refusals change nothing locally; a new epoch drops the old mirror and outbox; per-account
isolation of mirrors and outboxes; unreadable mirror files; discovery policy (rollout off never
asks; unreachable is never legacy; stale epochs ignored); desktop routing with rollout off (no
files written), unreachable discovery (no Firestore read or write), a legacy account with
discovery on, a migrated account (no Firestore library read or write, notification instead of
polling), an account mid-move, and a fence refusal sending the device to the new library; the
15-minute check with backoff; the Android copy of the shared files is byte-identical.

## Measured storage cost (local, billed SQLite rows)

`node connect-server/tools/library-measure.mjs 8795`

| Library | Import rows written | Like r/w | Unlike r/w | Add song r/w | Reconnect when current r/w | Catch-up pull read | Fresh restore read |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5 playlists × 20, 50 likes (36 KiB) | 63 | 0/3 | 2/3 | 3/4 | 0/0 | 58 | 65 |
| 15 × 60, 300 likes (288 KiB) | 333 | 0/3 | 2/3 | 3/4 | 0/0 | 318 | 345 |
| 40 × 100, 1,500 likes (1.3 MiB) | 1,583 | 0/3 | 2/3 | 3/4 | 0/0 | 1,543 | 3,139 |
| 20 × 150, 500 likes (843 KiB) | 543 | 0/3 | 2/3 | 3/4 | 0/0 | 523 | 565 |

Firestore impact, measured in tests rather than production: a migrated account's updated desktop
makes zero reads and writes of `users/{uid}` for library sync. A legacy desktop's background
library check drops from 60 to 4 reads an hour. The fence adds one billed `exists()` read for each
write that changes a library field.

## Not verified

- Anything in production: deployment, real Firebase tokens on the library routes, hibernation on
  Cloudflare's edge, request and duration usage, rule-lookup billing.
- Which feature exhausted the Firestore quota (needs console data; see `phase0-quota.md`).
- Old released binaries against a fenced account (their write shapes were replayed instead).
- Windows runtime behaviour; Android behaviour on a device (foreground socket, account deletion).
- Cloudflare point-in-time recovery availability on the account's plan.
