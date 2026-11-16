# Plan: Windows desktop app crashes at random (2 of 4 testers)

Status: **plan only, no app code changed.** Checked against the repository on 2026-11-16 by two reviewers. This version keeps the corrections from both and drops anything that's overkill for a 4-person test group.

## Principles

- **Fix bugs we can already see; investigate the rest.** The crossfade and canvas races below are real native use-after-free bugs whether or not they're what the friends hit. They're small and low-risk, so they go into the first build instead of waiting for logs to "prove" them.
- **2 of 4 is too few to blame hardware.** A setting difference (crossfade or canvas on) explains it as well as GPUs do. Check settings before drivers.
- **A crashing DLL is a lead, not proof.** Native memory corruption often surfaces in an unrelated DLL (e.g. a GPU driver). Need a matching stack or a reproduction before naming a cause.
- **No blanket "log and keep going", no automatic renderer switching.** Both hide the real bug or leave broken state.
- **Keep it proportionate.** Friends send files by hand; no crash-reporting service, registry dump setup or formal sign-off process.

## 1. Ask the two affected friends (today, costs nothing)

1. `%APPDATA%\Auralis\auralis.log` (it's appended forever, so the end is enough).
2. Any `hs_err_pid*.log` in `%TMP%`, `%LOCALAPPDATA%\Auralis\`, or the Desktop. With no `-XX:ErrorFile` set, HotSpot writes to the working directory and falls back to `%TMP%`.
3. Event Viewer → Windows Logs → Application → **Error** entries for `Auralis.exe` around the crash: the *Faulting module name* and the **date and time** (our log has no dates, see 3.3).
4. Their **crossfade setting** and whether **canvas / music video** is on.
5. When it happens: starting up, skipping songs, pausing/seeking, canvas playing, after sleep, after clicking the Auralis icon again while it was open (see 2.4).
6. Does the app disappear completely, or freeze ("Not responding") until they close it? Is it still in the tray or Task Manager afterwards?
7. App version, GPU, RAM, Windows version. Ask the 2 unaffected friends for 4–7 too, as a comparison.

**Free experiment:** ask one affected friend to turn crossfade off and the other to turn canvas off for a day or two. If either stops crashing, that confirms 2.1–2.3 before we touch any code.

## 2. Concrete bugs found in the code

`runCatching` can't protect against any of 2.1–2.2: calling a freed libVLC player is an access violation that kills the JVM instantly, with no Kotlin exception and no `Fatal uncaught exception` line in the log. That matches "the window just disappears".

### 2.1 Crossfade use-after-free / double release (`VlcAudioPlayer.kt:148-203`)
Only when crossfade is above 0.
- The `audio-crossfade` thread checks `fadeRevision == revision`, *then* calls `outgoing.audio().setVolume(...)`. In between, `stopFade()` on another thread (`play()` for the next song, `pause()`, `seekTo()`) can `stop()` + `release()` that same `outgoing`.
- When the fade finishes, the fade thread sets `fading = null` and releases `outgoing`. At the same time `stopFade()` may have already read `fading` and released it too: **double `release()`**.
- `fadeRevision++` on a `@Volatile` isn't atomic, so an update can get lost.
- **Repro:** crossfade on, skip songs rapidly, or pause/seek during the fade.
- **Fix:** one lock guarding all fade state. The fade thread holds it across check-and-use. A player is released exactly once, by whichever side removes it from `fading` (compare-and-set). Use `AtomicLong` for the revision.

### 2.2 Canvas sync thread vs. release (`CanvasVideoEngine.kt:73-90`)
Only when canvas / music video is synced to the song.
- `stop` sets `active = false` and immediately starts `canvas-stop`, which calls `release()`. The `music-video-sync` thread may be in the middle of `status().time()` / `setTime()` / `setPause()` on that player.
- **Fix:** keep a reference to the sync thread, and have `canvas-stop` `join()` it (at most ~200 ms) before calling `stop()`/`release()`.

### 2.3 Canvas native memory churn (`CanvasVideoEngine.kt:59-64`)
- Every displayed frame (up to ~30/s) makes a new native Skia image with `Image.makeRaster`, up to 1440 px on the short side (~10–15 MB each). These are freed only when the GC happens to run Skia's cleaner, and native memory barely pressures the Java heap. Private bytes can climb fast, which on low-RAM PCs could end in a crash inside a GPU/Skia DLL and **wrongly point at Direct3D**.
- **Check first:** watch Auralis's memory in Task Manager for 10 minutes with the canvas on vs. off.
- **Fix if confirmed:** close the previous frame's image once the next one replaces it, or reuse one pixel buffer per player.

### 2.4 Second launch can kill the running app (`SingleInstanceApp.kt:125-129`)
- Despite its own comment ("never terminates processes that are still alive"), `recoverZombieInstance` calls `destroyForcibly()` on the running instance if `IsHungAppWindow` says its window is hung. Windows reports that after only ~5 s without handling messages. So a short freeze plus a second click on the Auralis icon kills the app, which looks exactly like a crash.
- **Fix:** don't kill a live instance. Show "Auralis isn't responding" and let the user decide, or at least log a thread dump first. Also check the saved PID still belongs to Auralis (PIDs get reused).

### 2.5 Any background exception closes the app (`Main.kt:103-107`)
- The global handler calls `exitProcess(1)` for any uncaught exception on any thread. This one *does* leave a `Fatal uncaught exception` line in the log, so step 1 will show whether it's happening.
- **Don't** replace it with a global "log and continue". Instead, if the logs show a specific background failure, fix it at its source and give that subsystem (Discord, Connect, lyrics, media controls) its own failure handling.
- Also: the handler exits while VLC and JNA threads are still running, so an `hs_err` that happens *during shutdown* may be a side effect. Look for an earlier `E/` line.

## 3. First build: fixes + better logs (ship together)

1. Fix 2.1, 2.2 and 2.4. Add 2.3 if the Task Manager check shows memory climbing.
2. Add `-XX:ErrorFile` pointing to a known per-user folder (e.g. `%LOCALAPPDATA%\Auralis\crash\hs_err_pid%p.log`). Check that the packaged launcher actually resolves the path, including usernames with spaces. If it can't, leave the `%TMP%` default and tell testers where to look.
3. Logging (`Log.desktop.kt` / `Main.kt:75`):
   - Timestamps with the **date** (currently `HH:mm:ss.SSS` only, so a long log can't be matched to Event Viewer).
   - Real rotation: `logToFile` is always called with `append = true`, so the `.old` rotation in its comment never happens. Rotate by size and keep the previous file.
   - At startup, log the app version, Java version, actual render API, max heap, and the crossfade/canvas settings.
   - Write a "running" marker at startup and delete it on clean exit. If it's still there next launch, log "previous session ended unexpectedly". (That can also mean Task Manager or a power cut, not only a crash.)
4. Startup blind spot: `awaitRestartParent`, the data-dir setup and `InstallerDefaults.install` run before logging starts. Move `Log.logToFile` as early as possible, so a crash on startup leaves a trace.

## 4. Reproduce locally

Use the **installed release build**, not a dev run (different data folder, ProGuard on). Run each scenario for ~30 min:
- Crossfade at 5–12 s: skip every 2–3 s, and pause/seek during fades. *(Targets 2.1.)*
- Canvas on: lots of song changes, switching between player views, resizing. Watch memory. *(Targets 2.2, 2.3.)*
- Make the UI freeze briefly, then click the Auralis shortcut again. *(Targets 2.4.)*
- Sleep/resume, minimize/restore, unplug Bluetooth headphones mid-song, turn Wi-Fi off for 30 s.

Change one thing at a time (crossfade off → canvas off → renderer) so results are clear. Run the same scenarios on the old build and the fixed build.

## 5. Only if crashes continue after the first build

Use the new logs and `hs_err` files to decide where to look next, instead of auditing everything up front:
- **`hs_err` / Event Viewer names a GPU or Skia DLL** (and canvas memory is ruled out): try `-Dskiko.renderApi=OPENGL` or `SOFTWARE_FAST` on that tester's PC as a test. If it helps, add a manual "Use software rendering" setting. Not an automatic fallback.
- **Crash in `libvlc*`** outside crossfade/canvas: look at audio device switching (`WindowsAudioOutputs.kt`) and track-change lifecycle.
- **Crash in JNA / user32 / combase**: look at `NativeWindowFrame.kt` and `WindowsMediaControls.kt` teardown. Callbacks are already kept as strong references there, so check in-flight callbacks and cleanup order, not pinning.
- **Freezes** (not disappearing): add a small watchdog that logs all thread stacks if the UI thread doesn't respond for 5 s.
- **`OutOfMemoryError` in the log**: cap image caches, and set an explicit `-Xmx`.
- **Startup-only crashes**: test launching without the class-data archive (`-XX:SharedArchiveFile`), and look for corrupt files in `%APPDATA%\Auralis`. Back up a bad file before resetting it; never silently delete user data.

## 6. Verify and ship

1. `.\gradlew.bat :windows:test :windows-core:desktopTest`, plus `:windows:verifyPlaybackControls`.
2. The step 4 scenarios on the release build: no crashes, memory stays flat with canvas on.
3. Update the local install with `.\scripts\update-windows-app.ps1` (per AGENTS.md), then build the release installer (`:windows:packageReleaseExe`) for the friends. Connect stays enabled; keep the old installer in case we need to roll back.
4. Send it to the two affected friends. Done when both go a few days of normal use (longer than their usual time between crashes) with no crash and no "previous session ended unexpectedly" that isn't explained by a shutdown or Task Manager. If we never reproduced it locally, say "looks fixed", not "proven fixed", and keep the logging in.

## Order of work

1. Message the friends (step 1), including the crossfade/canvas experiment.
2. Build: fix 2.1, 2.2, 2.4; check 2.3; better logs (step 3).
3. Reproduce and verify on the release build (steps 4 and 6), then send the build to the friends.
4. Only if crashes continue: follow step 5 using the new logs.
