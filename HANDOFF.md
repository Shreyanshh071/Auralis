# Auralis handoff — 28 October 2026

## Workspace and state

- Checkout: `C:\Users\Acer\Desktop\Auralis`, branch `main`, base commit `60a4499` (`Improve playlist sync, lyrics, and desktop player`).
- The working tree contains substantial **uncommitted and untracked** work from this session. Preserve it; inspect `git status --short` and `git diff` before making changes. Nothing was committed or pushed during this session.
- GitHub CLI is authenticated as `IshaanThakur09` and has push access to `Shreyanshh071/Auralis`. Global Git author is `IshaanThakur09 <ishaanthakur49@gmail.com>`.
- User prefers English, wants direct action rather than repeated confirmations, and explicitly wants only `Auralis-Debug.apk` and `Auralis-Release.apk` maintained in the workspace.

## Delivered changes

- **Android:** adjusted sidebar/player lyrics and layout, restored home shelf order, redesigned playlist sync dialog, restored blurred cover background for song option sheets, and improved player artwork corners/animation. Queue history now distinguishes actually played entries from skipped items and preserves the queue when jumping.
- **Liquid glass:** matched the shared Android renderer to BitChord's reference recipe (8 dp blur, 24 dp lens, reduced-resolution capture and theme tint), including header, dock, mini players, menus and sync dialog. The Windows search dropdown and requested menus use the matching popup treatment. Existing default/frosted styles remain separate.
- **Windows player:** fullscreen lyrics now uses the sidebar's animated covers, with still-art fallback; the hidden sidebar stops video playback. Sidebar intro dots sit near the top while active lyrics follow the center. Shuffle is a persistent toggle within the running session, preserves shuffle for manual selections, and restores source order on toggle-off.
- **Account history:** Android and Windows project synced measured listening events into their History lists with original timestamps and deduplication. Windows tracks imported rows by account and removes them on sign-out/account switch; repeated pulls don't undo local history removals. History removals/clears are local, not synced deletions. Sync is not instantaneous: Android uploads approximately every two minutes, Windows polls approximately once per minute.
- **Android Connect:** removed the separate persistent idle-availability notification. The phone is available while the app is visible or playback is active; an idle background phone may need Auralis reopened before a handoff. The normal media notification remains.
- **Windows file pickers:** replaced old AWT/Swing chooser paths with a shared modern Windows `IFileOpenDialog` / `IFileSaveDialog` implementation for images, download folders, fonts, metadata rules and diagnostics. Cancellation does not open a fallback dialog. Open/folder and save COM interfaces initialized successfully, but a full interactive selection and save workflow has not been verified.

## Builds and installed artifacts

- Windows runnable app: `C:\Users\Acer\Desktop\Auralis-App\Auralis.exe`; keep its whole app/runtime folder. Latest desktop picker build was copied there and launched.
- Android release was installed over USB on the phone (ADB serial `5168470c`) with its existing app data preserved. `Auralis-Debug.apk` and `Auralis-Release.apk` in the workspace root were refreshed after the Connect change.
- Correct original Android debug signing keystore: `C:\Users\Acer\.android\auralis\debug.keystore`. Debug and release use this key; keystore files are ignored by Git. Do not replace it or delete app data to solve a signing mismatch.
- Use the installed JDK 21 and override the stale Gradle Java-home setting, e.g.:

  ```powershell
  .\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" :app:assembleDebug :app:assembleRelease --console=plain
  .\scripts\package-android.ps1 -SkipBuild
  ```

  Packaging copies and verifies both APKs, then removes other APK files **inside the workspace**. Build both variants before running it.
- Windows distribution: `:windows:createDistributable`. If VLC is not in its default location, set `AURALIS_VLC_DIR` to the existing bundled VLC folder at `C:\Users\Acer\AppData\Local\Auralis\app\resources\vlc` or `C:\Users\Acer\Desktop\Auralis-App\app\resources\vlc`. Deploy the complete distribution into the Desktop app folder, preserving its VLC resources and user data. Do not substitute the installer for the runnable app.
- For phone install: `adb -s 5168470c install -r Auralis-Release.apk` using the Android SDK platform-tools. Check device connection first; the serial may change.

## Verification and follow-up

- Latest modern-dialog Windows build succeeded; native open/folder and save COM object initialization passed. Check actual chooser appearance, selection, cancellation, image conversion and save overwrite behavior interactively when requested.
- Fullscreen animated cover build and three cover-matching tests passed; animation in fullscreen was not separately visually verified with a live source.
- History sync: eight focused merge/account restore tests passed; laptop imported 64 account history entries and phone account restore/upload logged success. A controlled two-device new-listen handoff was not verified. Consider whether deletion sync or history of very short listens is desired before broadening the feature; this implementation imports measured listening events rather than a separate complete history stream.
- Connect: seven presence tests passed; the idle notification/service was absent on the phone while foregrounded and backgrounded after install. Actual cross-device transfer with active playback was not exercised in that final check.
- Sidebar lyric centering, manual scroll return, and selected highlight/duet checks passed. One separate `SidebarWordLyricsTest` style-switching seek snapshot test failed pixel comparison; it did not exercise the changed sidebar layout.
- Artwork movement received optimizations, but prior phone profiling still showed dropped frames in the Lyrics transition. Do not claim that stutter is fully resolved.
- No changes or builds should be made merely to continue this handoff; follow the user's next request.
