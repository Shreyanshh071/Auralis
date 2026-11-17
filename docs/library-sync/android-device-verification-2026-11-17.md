# Android device verification — 17 November 2026

> Signed-in pilot follow-up: see [signed-in-pilot-2026-11-17.md](signed-in-pilot-2026-11-17.md). The existing test account is now signed in on an isolated Android clone and Mac profile. Real Firebase authentication, settings in the native apps, playback-history transfer both ways, actual background/foreground catch-up, and clear-dialog scope/defaults were verified. No production activation or library migration occurred.

## Device and data protection

Connected physical Android device: model CPH2613, Android 16. Installed app was `com.auralis.music`, version 1.1.3.3 (versionCode 8). Verified that its signing certificate matches the workspace release before installation. Used `adb install -r`; never uninstalled or cleared the main app's data. Its original first-install time remains 25 September 2026.

Temporarily installed the debug build and its instrumentation APK. Used the existing `MotionArtworkTestRunner`, which substitutes a plain Application to prevent normal playback/account startup. All remote mutations used synthetic `staging-phone-*` identities on the staging Worker and fixture storage. No Firebase test user was created, no personal account was migrated, and no personal library/YouTube edits were made by the tests.

Staging credentials were supplied in a temporary owner-only file in private app storage, not APK assets, instrumentation arguments, logs or Git. Removed that file and the instrumentation APK after testing. Erased the final run's synthetic account fixture data and restored the regular signed release with rollout off. Confirmed the installed app is no longer debuggable. Existing permissions and installation identity were retained.

## Test results on the phone

Final instrumentation result: **5 tests, 0 failures**, 12.409 seconds.

| Test | What ran |
| --- | --- |
| `phoneReceivesSettingFromMacWithoutPolling` | Real Android client sends a synthetic setting; a peer process on this Mac observes it on staging and writes a return edit. The phone receives and applies the return through its notification listener. |
| `activityBackgroundClosesSocketAndForegroundCatchesMissedSettings` | Registers the actual GoogleAccountSyncManager activity callbacks with an isolated Activity. Moving it to background closes the shared client socket; an edit made meanwhile is not delivered. Returning increments the real foreground counter; reattaching the listener fetches the missed edit. |
| `finishedListensPersistAcrossClientRestartAndClearOnlySyntheticAccount` | Real Android file store/batcher, HTTP transport and staging server: finished event upload, persisted sent marker preventing resend, another client receiving history, reconstructed client, account-wide clear, and synthetic cloud-data erasure. |
| `reportsWhetherTheInstalledPhoneProfileIsReadyForAccountSync` | Read-only readiness probe of the installed profile; no user-identifying values printed. |
| `savedPhonePreferencesCanRestoreToAFreshPhoneWithoutModifyingTheOriginal` | Reads actual Android preference storage, restores a fixture profile through an in-memory cloud backend, and asserts the source preferences are unchanged. |

## Bug found and fixed

The first run passed settings exchange and lifecycle checks but failed the synthetic account-erasure assertion. `LibraryCloudClient.erase()` returned immediately when the library feature was disabled, even though settings/listening history were using the same service. This would leave those cloud topics behind when deleting an account before its library migration.

Changed the guard from library `enabled` to `serviceConfigured` in both identical client copies. Added a desktop end-to-end regression for erasing settings and listens with `libraryEnabled = false`, including retrying erase. The phone test now verifies history access is refused after erase. A metadata `hello` is not a suitable erasure assertion because it can still report the erased authority state; the test verifies a history pull instead.

Affected production-code files:

- `shared/src/commonMain/kotlin/com/auralis/music/data/sync/LibraryCloudClient.kt`
- `windows/core/src/commonMain/kotlin/com/auralis/music/data/sync/LibraryCloudClient.kt`

Test additions:

- `android/app/src/androidTest/java/com/auralis/music/AccountSyncDeviceTest.kt`
- `windows/core/src/desktopTest/kotlin/com/auralis/music/data/sync/LibraryCloudEndToEndTest.kt`

The device tests explicitly skip unless a private staging fixture is supplied. They reject non-staging endpoints/UID prefixes. Do not bundle the fixture or secrets into builds.

## Regressions and final artifacts after the fix

- Staging `LibraryCloudEndToEndTest`: **11/11**, including account-only erasure.
- Settings bridge fixture tests: **10/10**.
- Desktop profile/account tests: **50/50**.
- Android sync release unit tests: **51/51**.
- Android release and macOS distributable built successfully.
- Root `Auralis-Release.apk` refreshed from the new release output, with the original signing certificate.
- `codesign --verify --deep --strict` passed for the macOS app.
- Public account/library sync URLs remain blank. No production deployment, Firestore security rules, migration or pilot allowlist changed.

## Boundaries: what this does not establish

These are real-device runtime/network tests, but not a complete signed-in pilot of every native app path:

- Lifecycle testing uses an isolated Activity plus actual app callbacks, with listener reattachment driven by the test. It does not verify the full signed-in MainActivity/settings/history follower wiring end to end.
- No full Firebase sign-in/account-deletion UI flow was run. Cloud data erasure was tested on synthetic staging identities, not by deleting a real Firebase user.
- No real playback session, five-minute background scheduling guarantee, screen-off/Doze soak, battery restrictions, or force-stop/reboot behavior was tested. These require a dedicated signed-in pilot and longer device runs.
- Clear-stats backend behavior was exercised on synthetic data, but the native clear-scope dialog was not visually exercised in this session. Android's asynchronous all-devices clear error reporting remains a follow-up.
- The phone-to-Mac peer was a small protocol client on this Mac, not the interactive desktop UI. The actual desktop account engine has separate staging tests.
- Windows runtime and production quota attribution remain outside this session.

## Reproduction outline

1. Build `:app:assembleDebug :app:assembleDebugAndroidTest -PmotionArtworkTests` with public rollout properties blank.
2. Verify the installed app's signing certificate before any update. Install debug and test APKs with `-r` (and `-t` for the test APK), without clearing data.
3. Supply an owner-only private `files/sync-device-test.json` containing `base` (the exact staging origin), a unique synthetic `uid` beginning `staging-phone-`, and the staging `secret`. Never print these credentials.
4. Run a Mac peer on that synthetic settings identity: observe `a.pure_black_mini_player=true` from device `phone` at revision 1; publish false at revision 2 with device `mac-device-test`.
5. Run only `AccountSyncDeviceTest,AccountPreferencesStorageTest` using `com.auralis.music.test/com.auralis.music.MotionArtworkTestRunner`; keep the phone unlocked and approve USB installs if prompted.
6. Check `OK (5 tests)`, remove the private fixture and test APK, erase synthetic remote fixtures, and restore the regular release with `adb install -r`.

Three missing Android test AAR downloads initially failed in Gradle's TLS connection. They were fetched from Google's official Maven endpoint with curl into a temporary local repository; a temporary init script selected that repository for those three modules. No repository/build configuration was committed for this environment-specific download issue.
