# Signed-in internal staging pilot — 17 November 2026

## Isolation and build setup

The existing dedicated Firebase account was reused; no account was created or migrated. The owner entered its email/password directly in the phone and Mac login screens. Credentials were not requested in chat. Diagnostics privately verified the same UID on both devices, the previously documented test-UID suffix, and Firebase project `auralis-70cf8`; no full UID, password or token is recorded here.

Android has a separate `syncPilot` build type:

- Application ID: `com.auralis.music.syncpilot`, label **Auralis Sync Pilot**.
- Separate Android sandbox/session, installed alongside `com.auralis.music`; no personal app data copied or cleared.
- Account sync points to the exact staging origin; Connect also points to staging.
- Library service URL is empty. No library fence or migration is involved.
- Normal updater subscriptions/scheduling are disabled in this internal clone.
- Uses existing local Firebase project options for Email/Password only; no new Firebase app/project or backend configuration was registered. Google sign-in for the clone is outside this setup.
- Normal debug/release URL validation still rejects staging. The production release has `SYNC_PILOT=false` and empty account/library sync URLs.

Mac uses the regular signed distributable via `scripts/run-sync-pilot-macos.sh`, with an isolated profile at `windows/build/sync-pilot/mac-profile`. No personal profile, refresh token, YouTube cookie or library was copied into it. The window is named **Auralis Sync Pilot**. Startup rejects pilot mode unless it has the exact staging account endpoint, an empty library URL and an explicitly isolated `sync-pilot` profile directory.

Build commands:

```bash
./gradlew :app:assembleSyncPilot :windows:createDistributable
bash scripts/run-sync-pilot-macos.sh
```

The Android artifact is `android/app/build/outputs/apk/syncPilot/app-syncPilot.apk`. Install it separately; do not distribute it as `Auralis-Release.apk` or set staging URLs in root `gradle.properties`.

## Verified with the real signed-in apps

- Both native apps signed into the expected dedicated test account.
- The owner's real Firebase token was accepted on staging's settings/history routes. Both topics are enabled there.
- A reversible remote settings edit reached Android's actual DataStore in **1.98 s** and Mac's in **1.76 s**. Original values were restored afterward.
- A setting changed through the native Mac UI (lyrics alignment Left → Centre) reached staging with the app's device identity. It was restored to Left through the UI and confirmed on the server.
- Settings are per platform: Android settings use the `android` map, desktop settings use `windows`. This is not a claim that a phone-specific setting should change a desktop preference.
- Actual Mac/libVLC playback recorded **32,618 ms**. The normal app batching path uploaded it to staging and persisted its sent marker; initial history import completed.
- Backgrounding the actual phone app reduced the account's shared socket count from **2 to 1**. A settings edit made while backgrounded did not change the phone's DataStore.
- After the Mac playback was uploaded, the backgrounded phone still had zero matching playback events. Returning to the foreground restored **2 sockets** and imported the playback event plus the pending setting within **1.6 s**. The exact **32,618 ms** duration reached the phone's real Room database. The original phone setting was restored.
- The native Mac clear-stats dialog visibly offers **Only on this Mac**, **On all my devices**, and **Cancel**, with explicit account-wide wording and the caveat about old versions retaining local copies. Cancel was selected; no history was deleted.
- Playback was paused on Mac after the sample; its original unmuted state was restored. No account deletion or library migration was performed.

- Real phone playback continued while the app was backgrounded for **107.6 seconds**. Its final Room event measured **178,454 ms**. After pausing and returning to the foreground, the normal Android sync path uploaded the finished event and the native Mac app received the exact duration within **4.2 s**. No synthetic event was inserted into either app database.
- The native Android Stats screen showed **3:31** total time (the 32,618 ms Mac listen plus the 178,454 ms phone listen), confirming real playback data in both directions.
- The actual Android clear confirmation displayed **Also clear on all my devices** unchecked by default, with local-only explanatory text. Cancel was selected; neither local nor account data was cleared.

- Force-stop/reopen of the actual pilot app retained the test sign-in and fetched a setting changed while it was stopped in **15.31 s**. Its two Room events survived; the cloud history revision did not advance, so the existing listens were not re-uploaded as changes.
- After final updater isolation changes, rebuilt both APK variants and updated only the pilot installation. The normal phone app's last update remains 17:55:37 and its original first-install time remains unchanged. The root public release APK was refreshed locally, not installed over the personal app.

## Extended on-device checks (same disposable account)

- Forced **deep Doze for 600 seconds**, with this pilot's standby bucket temporarily restricted. Every 30-second sample reported `IDLE`; one brief owner wake was disclosed during the run, and did not change sampled idle state. This is a bounded forced-Doze test, not an overnight OEM battery guarantee. Normal battery simulation and original standby bucket (`10`) were restored in a `finally` block.
- A setting created remotely during Doze remained pending, then reached the real phone DataStore **14.1 seconds** after reopening. Its original value was restored.
- Real clear-stats failure testing found that the earlier Android flow cleared local data before an asynchronous cloud request completed, and swallowed cloud failures. Desktop also swallowed the account-clear error. Both now await the cloud acknowledgement before clearing locally and keep an actionable error in the confirmation dialog. The Android cloud clear has a 30-second timeout; local-only clear does not fall back to deleting Firestore history when the new service is configured but unavailable.
- Verified the failure on both actual apps by temporarily disabling only staging's listening topic, with an automatic restore watchdog. Both displayed persistent errors and retained both real playback events (3:31). Staging's `settings,listens` topics were restored immediately afterward; production was not changed.
- Retrying Android's same account-wide clear succeeded: its native Stats page showed **0:00**, its Room events became empty, and the Mac received the clear and showed empty stats. Retrying Mac's previously failed confirmation also succeeded and closed the dialog. This erased only the specifically approved dedicated test history.
- Added focused clear-order tests (four shared desktop cases, two Android cases) covering acknowledgement order, preserving local data on cloud failure, local-only scope, and cancellation. Both build variants and the signed Mac distributable rebuilt; root release APK refreshed with its existing certificate.

- A full phone reboot retained the dedicated sign-in and caught up a setting created while force-stopped in **15.81 seconds** after launch. Original setting restored; cleared history stayed empty.
- With the owner's specific confirmation, the owner entered the test password directly into Android's actual delete dialog and submitted it. The app returned to sign-in and visibly confirmed **“Your account and backed-up data were deleted.”** Firebase's saved Mac refresh token was subsequently refused with **`USER_NOT_FOUND`**. Staging's admin status recorded the terminal erased marker (`state=migrating`, `job.dir=erased`, an intentional tombstone preventing resurrection), and its read-only export contained no settings or library records. History was already cleared on both devices before deletion. No account was migrated.
- The now-invalid Mac test session was explicitly signed out and the pilot quit. The dedicated account has been deleted and **cannot be reused** for future pilots; use a new disposable account.
- Fresh staging regression run after the clear fix: **50/50 desktop account tests**, **4/4 shared clear-order tests**, and **2/2 Android release clear-order tests** passed. Release APK refreshed with original certificate; Mac signature verifies.

## Remaining checks

This is an internal pilot, not public-rollout approval. Windows runtime is assigned to the owner’s friend. A longer unforced OEM battery soak remains beyond the bounded test above. The requested signed-in Mac/Android pilot, bounded Doze, reboot, clear-failure/retry, and native account deletion have now been exercised. Destructive clear/deletion should only involve explicitly approved disposable test data/account.

Production Worker topics, secrets/allowlists, Firestore rules and public activation were not changed. Bridge requests may read/write this dedicated test account's Firestore data as designed; that does not migrate its library or touch other accounts.

## Firestore limitation observed

The Mac account panel still displayed **“Firebase quota exceeded”** for its legacy **library** path before the deleted session was signed out. Settings/history staging success does not prove Firestore quota is fixed. The library endpoint is intentionally off in these pilot builds, and the old-app bridge still uses Firestore. No public-rollout or quota-resolution claim follows from this pilot.
