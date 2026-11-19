# Signed-in automatic library pilot — 19 November 2026

This run followed main through `1236dd07` (including the friend's Canvas/lyrics/Discord changes) and continued the automatic-migration implementation from `749cd56d`. **The Worker deployments in this run were staging only. No production Worker or Firestore security rules were deployed. No broad migration was enabled.**

## Environment and credentials

The owner granted the temporary Google login Firebase Admin plus the IAM setup roles. Created `auralis-library-staging` in the existing Firebase project and a custom `auralisLibraryBridge` role containing exactly:

- `datastore.databases.getMetadata`
- `datastore.entities.get`
- `datastore.entities.create`
- `datastore.entities.update`
- `datastore.entities.delete`

The backend has no Firebase Auth administration or project-IAM administration grant. Its key is stored as the staging Worker's `LIBRARY_FIRESTORE_SERVICE_ACCOUNT` secret, with an owner-only backup outside the repository. Human OAuth credentials were not deployed to a Worker. The backend identity remains independent of the temporary human access.

`LIBRARY_AUTO_UIDS` was restricted to the approved disposable account (tag `f63f61bac500`), briefly adding one newly created empty-account fixture (tag `4673bcbac137`). No wildcard admission. The second account was erased and deleted after verification; admission returned to the first account only.

The local staging synthetic credential had become stale: synthetic requests returned 401 while real Firebase sign-in worked. Refreshed **staging's** `STAGING_SECRET` and its matching owner-only `.measure.env`; the production secret was not used or changed. Staging smoke then passed both before and after the initial automatic backend deployment.

Final staging version: `290ada2e-9a72-4915-bf34-f23b7cbed390`. Current checked-in `LIBRARY_AUTO_IMPORT` defaults remain false. Staging was deployed with an explicit true override; production was not enabled. Test budget overrides were temporary and restored to 5,000 backend read/write reservations per day.

## Account/data safety

- Privately checked that both installed pilot sessions matched the previously approved disposable identity, rather than assuming an account based on the profile label.
- Backed up the Mac pilot profile and Android pilot sandbox outside Git with restricted permissions. The regular phone package and normal Mac profile were not replaced or cleared.
- The disposable account had no Firestore root document at the start. Seeded and read-back verified a controlled fixture: Gilli with 111 ordered tracks (including a duplicate), Hi with two tracks, one like, one album, one artist and a pin. No YouTube session or YouTube playlist was changed.
- The live rules differ from tracked `firestore.rules`: the deployed fence applies to one earlier pilot UID. Journal paths remain default-denied. Backed up the live rules and retained them unchanged.
- Only the newly created empty-account fixture was deleted in this run. The existing disposable account remains available with its pilot library.

## Actual phone and Mac results

| Check | Observed result |
| --- | --- |
| Real Firebase sign-in | Existing saved disposable login refreshed; verified same UID on Android and Mac privately |
| Automatic native import | Both actual apps reached Cloudflare epoch 1 and held the fixture; initial source checksum matched the verified seed |
| Concurrent first sync/re-login | One imported authority/epoch; no duplicate playlists; repeated app starts retained edits |
| Mac → phone deletion | Owner deleted Hi in the native Mac pilot because macOS denied our UI automation Accessibility permission; deletion arrived on phone |
| Legacy stale snapshot | Authenticated old-desktop-shaped `POST :commit` added track 112 while its stale snapshot still contained Hi; Gilli reached 112, Hi stayed deleted |
| Phone → Mac new playlist | Actual phone UI created `Phone pilot playlist`; it reached the running Mac repository in 4.36 s |
| Phone → Mac track edit | Actual phone UI added the player track to Gilli; Mac repository reached 113 tracks in 4.51 s |
| Likes/unlikes | Phone UI like reached Mac; native Firestore-mode unlike later reached Mac through the scheduled bridge |
| Native legacy Firestore path | Rebuilt the isolated phone variant with library rollout disabled, retaining its data/session. Its native Firestore SDK transaction uploaded an unlike; the normal bridge delivered it to the Mac in 81.73 s, without a forced sync |
| Process restart/catch-up | Restored Cloudflare-enabled phone variant; accepted a remote edit while it was stopped, then launched it. It caught up in 5.37 s, retaining epoch 1 |
| New account | Created a fresh Firebase email/password fixture, confirmed missing source, and initialized an empty Cloudflare library at epoch 1 |

The native legacy-mode APK is the current source using the existing Firestore path, **not an actual shipped older APK**. Old desktop request shapes and SDK rules cases were exercised, but do not label this as exhaustive shipped-version compatibility. Windows runtime was not tested on this Mac; the friend's Windows test remains a separate acceptance gate.

## Failure and rollback checks on real staging

Temporarily set the backend **write** budget to zero. A fresh fixture's Cloudflare edit was acknowledged and persisted, while its legacy mirror reported `legacy-budget`. This did not consume the real project's Firestore quota to simulate a failure.

After restoring the write budget, froze updated writers for rollback. Verified their writes were refused while an authenticated old-client write remained allowed. The backend conditionally reconciled and mirrored both the accepted Cloudflare edit and the legacy edit, then switched authority to Legacy with reimport paused. Both were present in Firestore. Verified exports were saved privately.

Temporarily set the backend **read** budget to zero and explicitly resumed that fixture. Bootstrap stayed pending (202 / `legacy-budget`); its two-entry archive and original source backup survived. Existing data was not replaced with an empty account. The fresh fixture's erase also removed its mirror marker; deleted its Firestore root and Firebase identity, verified `USER_NOT_FOUND` on token refresh, and removed its local generated credentials. Restored normal staging budgets and one-account admission.

Actual provider 429 exhaustion, a provider outage and lost acknowledgements were not deliberately induced against production Firestore. Those cases were covered by local transport/coordinator/workerd tests. Budget stops provide the controlled live failure check.

## Fixes found during this pilot

1. **Mac launcher precedence:** packaged jpackage JVM flags overrode `JAVA_TOOL_OPTIONS`, so the pilot correctly refused startup when it saw production account endpoints. The script now clones the code bundle into a disposable build directory, changes only that clone's four endpoint options, ad-hoc signs/verifies it, then launches with the isolated profile. The normal signed app and its configuration remain unchanged. The temporary clone is removed after exit.
2. **Rollback assumption:** automatic rollback previously depended on a fence applying to every UID, which the live pilot-specific rules do not provide. It now freezes updated writers and uses the persisted reconciliation baseline, conditional Firestore commits and crash-recovery journal before its authority flip. Old writers stay live; races retry. The old manual-migration rollback retains its fence path. Added tests force a real CAS conflict, cover quota interruption/retry, and verify both accepted and legacy edits survive without operator Firestore writes.
3. **Interrupted reimport backup:** a failed reimport could replace its pending metadata and discard the earlier source backup. Preserve that state while recording the retry. The local regression and live read-budget test both verify archive/source retention.

## Verification and release gates

- Latest pulled Canvas/Discord checks: **14/14**, Mac distributable build succeeded.
- Worker suite after the fixes: **166/166**.
- Firestore emulator rules/operator suite: **50/50**.
- Additional automatic suite after the backup-retention fix: **10/10**.
- Deployed staging Connect/library smoke: **12/12** after the initial automatic backend deployment; final normal-budget configuration also passed 12/12.
- Mac launcher: actual signed native launch, endpoint guard and native sync succeeded. Original bundle strict signature verification passed.
- Android library-on and library-off internal variants built and updated with `adb install -r`; restored library-on at the end. Internal build outputs were not copied into new APK deliverables.

Production rollout still needs explicit acceptance and activation. In particular, provision a dedicated production backend identity, deploy the tested backend before releasing these Cloudflare-only library clients, verify a production internal account first, then approve broader admission. Measure usage and finish Windows/actual older-binary checks before claiming broad readiness. No paid plan or billing change was made.

The final normal-budget mirror was reverified after removing the temporary wake-up favorite: Gilli retained 113 tracks and its duplicate, Hi remained deleted, epoch stayed 1 and the original source backup checksum was unchanged. Both pilot apps were stopped after checks; their data/session were retained.
