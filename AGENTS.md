# Auralis AI Assistant & Developer Guidelines

This repository is a monorepo for **Auralis**, containing the Android application and the Desktop application (supporting both **Windows** and **macOS**).

---

## 1. Project Architecture

| Module | Target | Framework / Technologies |
|---|---|---|
| `:app` (`android/app`) | Android 7.0+ | Jetpack Compose, AndroidX Media3 (ExoPlayer), Room DB, DataStore, Firebase |
| `:shared` (`shared`) | Multiplatform Core | Kotlin Multiplatform logic shared across platforms |
| `:zemer-cipher` | Multiplatform | YouTube cipher / PO token extraction |
| `:windows-core` (`windows/core`) | Desktop Core | Compose Multiplatform, VLCJ/libVLC audio engine, NewPipeExtractor |
| `:windows` (`windows`) | Desktop App | Compose Multiplatform (Windows & macOS), Skiko (Direct3D on Windows, Metal on macOS) |

> [!NOTE]
> Despite the directory name `:windows`, this module is the **universal Desktop target** for both **Windows and macOS**. Any feature added to `:windows` or `:windows-core` automatically compiles and ships to both operating systems.

---

## 2. Desktop Cross-Platform Rules for AI & Developers

When modifying or adding features to `:windows` or `:windows-core`:

### A. Automatic Feature Parity
- Keep all UI components, view models, search, sync, listening stats, queue management, and settings inside the common desktop source sets (`desktopMain` and `main`).
- Avoid creating OS-forked screens. Any UI or feature added for Windows must automatically work on macOS, and vice versa.

### B. Guarding Platform-Specific APIs
Windows-specific APIs (Win32 JNA, DWM, DPAPI, Registry, Windows Named Pipes) must **never** be executed unconditionally. Always guard with OS checks:

```kotlin
val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
```

1. **Session & Credential Storage (`DesktopAccount.kt`, `ProtectedYouTubeStore.kt`):**
   - Windows uses DPAPI (`Crypt32Util.cryptProtectData`).
   - macOS (and non-Windows) uses standard file persistence or safe fallbacks. Always catch `UnsatisfiedLinkError` or guard with `isWindows`.

2. **Window Chrome & Traffic Lights (`Main.kt`, `WindowTitleBar.kt`):**
   - On Windows: The window is borderless (`undecorated = true`), uses native DWM caption hit testing via `NativeWindowFrame`, and renders custom Segoe caption buttons (`CaptionButton`).
   - On macOS: The window is decorated (`undecorated = false` / `undecorated = !isMac`) with client properties set on `rootPane`:
     ```kotlin
     rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
     rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
     rootPane?.putClientProperty("apple.awt.windowTitleVisible", false)
     rootPane?.putClientProperty("apple.awt.fullscreenable", true)
     ```
   - On macOS, native traffic lights (close, minimize, zoom) occupy the top-left corner. The title bar header row must maintain `78.dp` start padding (`padding(start = if (isMac) 78.dp else 10.dp)`) to clear the traffic lights.
   - Caption buttons (minimize, maximize, close) and their trailing divider must **never** render on macOS.

3. **File & Image Pickers (`NativeImagePicker.kt`):**
   - Windows uses COM / `Ole32` / `IFileDialog`.
   - macOS uses standard AWT `java.awt.FileDialog(frame, title, mode)`.

4. **Audio Engine / VLC Discovery (`VlcAudioPlayer.kt`):**
   - On macOS: Discovers libVLC from `/Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib` and exports `VLC_PLUGIN_PATH`.
   - On Windows: Uses bundled VLC or 64-bit VLC in `C:\Program Files\VideoLAN\VLC`.

5. **Netty / gRPC SSL & Transport on macOS ARM64 (`Main.kt`, `build.gradle.kts`):**
   - Netty's shaded `tcnative` dylibs extracted to `/tmp` have invalid code signatures on Apple Silicon and cause `SIGKILL` (AMFI code-signing violation).
   - Ensure the following JVM flags remain active on macOS:
     ```
     -Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true
     -Dio.netty.handler.ssl.noOpenSsl=true
     -Dio.grpc.netty.shaded.io.netty.transport.noNative=true
     -Dio.netty.transport.noNative=true
     ```

6. **Hardware Rendering:**
   - Windows uses Direct3D (`-Dskiko.renderApi=DIRECT3D`).
   - macOS uses Apple Metal (`-Dskiko.renderApi=METAL`).

---

## 3. Build & Packaging Commands

### Desktop (Windows & macOS)
- **Compile and create distributable app:**
  ```bash
  ./gradlew :windows:createDistributable
  ```
  - Outputs `windows/build/compose/binaries/main/app/Auralis.app` on macOS.
  - Outputs `windows/build/compose/binaries/main/app/Auralis` on Windows.

- **Package macOS installer (DMG):**
  ```bash
  ./gradlew :windows:packageDmg
  ```
  - Outputs `windows/build/compose/binaries/main/dmg/Auralis-1.0.0.dmg`.
  - For local execution on macOS, ad-hoc signing (`codesign -s - --force --deep ...`) is applied automatically and requires zero Apple Developer fees.

- **Package Windows installer (MSI / EXE):**
  ```bash
  ./gradlew :windows:packageMsi
  ./gradlew :windows:packageExe
  ```

### Android
- **Assemble Android APK:**
  ```bash
  ./gradlew :app:assembleRelease
  ```
  *(Note: `settings.gradle.kts` automatically omits Android modules if no Android SDK is detected, allowing desktop-only development on macOS machines).*
