# Verification after the settings/history staging continuation

> Signed-in pilot follow-up: see [signed-in-pilot-2026-11-17.md](signed-in-pilot-2026-11-17.md). The existing test account is now signed in on an isolated Android clone and Mac profile. Real Firebase authentication, settings in the native apps, playback-history transfer both ways, actual background/foreground catch-up, and clear-dialog scope/defaults were verified. No production activation or library migration occurred.

> Real-phone follow-up: [Android device verification, 17 November](android-device-verification-2026-11-17.md). Five tests passed on Android 16. They exposed and verified a fix for erasure when only settings/history are enabled. Final staging client tests are now 11/11. The regular signed release was restored with data preserved; full signed-in UI and battery/Doze checks remain separate.

Date: 17 November 2026. Codex resumed after Claude's usage limit stopped the previous session.

## Scope and deployment state

The settings/history server was already deployed to `auralis-connect-staging.auralis018.workers.dev` with both topics enabled when this continuation began. This run made **no deployments**, changed no Worker secrets, deployed no Firestore rules, and migrated no real account. All remote tests used synthetic staging identities and made-up libraries; Firestore bridge tests used test doubles or the emulator.

Production and its pilot allowlist were not modified. Public Android and desktop builds retain blank library/account sync URLs. Existing Firestore behavior remains the public path. This verification is not approval for production rollout.

## Additional client fix verified here

Claude's opening/reconnect catch-up fix was present in both copies of `LibraryCloudClient.kt`. A related gap remained: a settings/history listener registered after the shared socket was already open missed that opening callback, so it could stay stale without another remote edit or reconnect.

Added a deterministic test that opens a socket for the first topic, waits for its catch-up, then adds another topic with no intervening remote push. It **failed against the previous code** with the expected ten-second timeout on staging. The client now requests catch-up when adding a previously unwatched topic to an existing connection. Replacing an existing listener does not repeatedly trigger catch-up. Both shared copies were changed identically.

A second regression verifies an edit committed after a device's initial pull but before its first socket opens is fetched automatically. Both tests pass on staging and locally as part of the full core suite.

## Clear-stats scope

The existing explicit scope choices are retained: local clear is the default; account-wide clear is an additional deliberate choice. Corrected Android and desktop wording so it no longer promises that unchanged old APKs erase their local copies. The wording now says account history and updated devices are cleared, and older versions may retain local copies.

This was a wording correction, not a redesign of clear/error behavior. Account-wide clear's real-phone UI, lifecycle and failure reporting still need device validation; Android's clear request currently runs asynchronously and logs service failures. Do not interpret compilation/unit tests as verification of successful deletion on every device.

## Results

| Check | Result | Environment |
| --- | --- | --- |
| Server suite (`npm test`) | 107 passed, 0 failed | Local Workers runtime; existing Connect, library, settings, listens |
| Firestore rules and migration suite | 48 passed, 0 failed | Firestore emulator `demo-auralis` and local Worker |
| Staging smoke script | 12 steps passed | Deployed staging; synthetic Connect/library flow, including restore, rollback and erase |
| Existing Connect measurement | Passed; 355 ms round trip | Deployed staging, synthetic account |
| `LibraryCloudEndToEndTest` after the additional client fix | 10 passed, no failures | Deployed staging; real client code, including settings/listens and the two new catch-up regressions |
| `AccountSettingsCloudTest` | 10 passed | In-memory bridge fixtures, including retire/restore reconciliation |
| Desktop profile/account suite | 50 passed | Stage-aware tests pointed at deployed staging; other tests retain their isolated fixtures |
| Full desktop core suite | 250 tests, 0 failures, 1 existing skip | Local server on port 8795; real HTTP/socket tests enabled |
| Android sync-related release unit tests | 51 passed, 0 failed | Local JVM, no phone |
| Android release APK | Build successful; original signing certificate preserved | New artifact copied to root `Auralis-Release.apk`, exact hash match with release output |
| macOS distributable | Build successful; `codesign --verify --deep --strict` passed | `windows/build/compose/binaries/main/app/Auralis.app` |
| Rollout controls | Off in both artifacts | Android `ACCOUNT_SYNC_URL` / `LIBRARY_SYNC_URL` empty; desktop launcher has no enabled account/library URL |

The desktop profile run includes `AccountLibraryCloudTest` (8 tests) and `AccountListensCloudTest` (4 tests), using staging when configured. The latter includes reconciliation of listens recorded on both sides during bridge retirement. These are multiple instances on the same synthetic UID, not sharing between unrelated user accounts.

The Android subset was: `AccountLibraryMergeTest`, `AccountPreferencesStartupTest`, `AccountRestoreOnePassTest`, `CloudSyncConflictTest`, `FavoritePreservationTest`, `ListeningHistoryDeduplicationTest`, `ListeningProgressTest`, `HomeStartupStatsTest`, and `ListenChunkMergerTest`. The unrelated full Android suite that calls external lyrics websites was not run.

## Reproduction

Keep secrets out of command lines, output, Git and this document. Existing staging credentials are in ignored `connect-server/.measure.env`; the staging Kotlin tests require `AURALIS_LIBRARY_URL`, `AURALIS_STAGING_SECRET`, and `LIBRARY_ADMIN_SECRET` populated privately from it. Always verify the destination is staging before setting them.

Staging test tasks:

```bash
./gradlew :windows-core:desktopTest --tests '*LibraryCloudEndToEndTest' --tests '*AccountSettingsCloudTest' :windows:test --tests 'com.auralis.music.windows.profile.*' --console=plain
```

Local regression/build tasks (with a synthetic local Worker running and `AURALIS_CONNECT_PORT=8795`):

```bash
./gradlew :windows-core:desktopTest :windows:test --tests 'com.auralis.music.windows.profile.*' :app:testReleaseUnitTest --tests '*AccountLibraryMergeTest' --tests '*AccountPreferencesStartupTest' --tests '*AccountRestoreOnePassTest' --tests '*CloudSyncConflictTest' --tests '*FavoritePreservationTest' --tests '*ListeningHistoryDeduplicationTest' --tests '*ListeningProgressTest' --tests '*HomeStartupStatsTest' --tests '*ListenChunkMergerTest' :app:assembleRelease :windows:createDistributable --console=plain
```

Gradle does not automatically consider these environment variables test inputs. When changing a task's server target, force that test task to rerun rather than trusting `UP-TO-DATE`. In this continuation the staging profile suite actually executed; the later local build reused its profile results. The full core task executed with the local server, and Android's selected tests executed.

## Remaining gates

- No Android phone was connected (`adb devices` empty). Foreground/background sockets, battery restrictions, eventual background uploads, clear-stats scope/failure UX, and deletion need real-device checks. Five-minute background delivery is not guaranteed by these tests.
- Windows runtime remains untested; macOS builds do not establish Windows behavior.
- No signed-in native app UI or visual clear-dialog QA was performed in this continuation. Synthetic clients exercise code/network behavior, not those UI paths.
- These tests do not establish the project's production quota consumer, total fleet cost, or real Firebase authentication for the newly added settings/listens topics. Earlier production library/Connect authentication verification is separately documented in `production-step1.md`.
- Security-rule deployment, fence selection, internal pilot builds/cutover and public rollout remain separate decisions. No test account is automatically migrated by this work.
- The interim polling-reduction patch stays separate and unapplied. Unchanged old APKs still consume Firestore resources.
- This continuation did not perform a new full independent security/concurrency audit of all accumulated implementation changes. Existing test coverage is evidence for the listed scenarios, not a guarantee about every failure mode.

## Follow-up (Claude, after Codex's continuation)

The desktop profile/account suite had not actually re-executed locally after the catch-up change:
Gradle reused cached results. Run with `--rerun-tasks`, one test failed:
`AccountLibraryCloudTest` "a migrated account syncs with the library server…".

This was a race in the test, not in the app. The test waited for any notification-triggered
library sync. With the socket now catching up when it opens, that opening sync satisfied the wait
before the phone's like arrived. The test now waits for the like itself, and checks that a sync
triggered after the like applied it. No production code changed.

| Check (all with `--rerun-tasks`) | Result |
| --- | --- |
| Desktop profile/account suite, local Worker | 50/50, three consecutive runs |
| Desktop profile/account suite, deployed staging | 50/50 |
| `LibraryCloudEndToEndTest`, deployed staging | 11/11 |
