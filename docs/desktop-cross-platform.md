# Desktop Cross-Platform Architecture (Windows & macOS)

**Updated:** October 2026  
**Status:** In production parity (Windows & macOS)

---

## 1. Overview

The desktop version of Auralis is built using **Compose Multiplatform for Desktop (Kotlin 2.0 / JVM 21)**. Although the module is named `:windows` for historical reasons, it now builds and packages identical desktop binaries for both **Windows** (x86_64) and **macOS** (Apple Silicon `arm64` and Intel `x86_64`).

Any feature, screen, or bugfix committed to `:windows` or `:windows-core` is automatically synchronized across both operating systems.

---

## 2. Platform Adaptations

### A. Window Chrome & Native Decorations
- **Windows:** Custom borderless frame (`undecorated = true`) with custom DWM non-client hit testing (`WM_NCHITTEST`), native caption hover state, and Segoe Fluent-style caption buttons (`CaptionButton`).
- **macOS:** Native decorated window (`undecorated = false`), configured with:
  ```kotlin
  rootPane.putClientProperty("apple.awt.fullWindowContent", true)
  rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
  rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
  rootPane.putClientProperty("apple.awt.fullscreenable", true)
  ```
  This creates a modern floating title bar with native macOS traffic lights (red, yellow, green) at the top-left, floating directly over Compose's dark canvas. The brand row provides a `78.dp` offset on macOS to clear the traffic lights, and Windows caption buttons are hidden.

### B. Hardware Rendering
- **Windows:** Hardware-accelerated Skia rendering over Direct3D via Skiko (`skiko-awt-runtime-windows-x64`).
- **macOS:** Hardware-accelerated Skia rendering over Apple Metal (`-Dskiko.renderApi=METAL`, `skiko-awt-runtime-macos-arm64`).

### C. Audio Engine (libVLC)
- **Windows:** Discovers bundled VLC in resources or standard 64-bit VLC at `C:\Program Files\VideoLAN\VLC`.
- **macOS:** Automatically detects `/Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib` and plugins.

### D. Session & Account Protection
- **Windows:** Protects session tokens using Windows DPAPI (`Crypt32Util`).
- **macOS:** Safely stores session state with fallback standard persistence.

### E. Netty / gRPC Security on Apple Silicon
- Netty shaded OpenSSL (`tcnative`) bundles extracted to temporary folders violate macOS Apple Mobile File Integrity (AMFI) due to invalid ad-hoc code signatures.
- Netty is configured via JVM options to use standard JDK SSL and Java NIO transport (`-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true`, `-Dio.netty.handler.ssl.noOpenSsl=true`, `-Dio.grpc.netty.shaded.io.netty.transport.noNative=true`, `-Dio.netty.transport.noNative=true`), completely resolving SIGKILL crashes.

---

## 3. Zero-Cost macOS Distribution

No Apple Developer account ($99/year) or paid certificates are required for local builds, ad-hoc distribution, or GitHub Releases:
- The macOS application bundle is packaged into standard `.app` and `.dmg` formats using Gradle (`:windows:createDistributable` and `:windows:packageDmg`).
- Ad-hoc codesigning is performed for free using macOS command line tools:
  ```bash
  codesign -s - --force --deep windows/build/compose/binaries/main/app/Auralis.app
  ```
- For users downloading the DMG from GitHub Releases, standard macOS Gatekeeper quarantine removal is supported:
  ```bash
  xattr -cr /Applications/Auralis.app
  ```

---

## 4. Developer Workflow & Commands

### Building on macOS
```bash
# Compile and create standalone .app
./gradlew :windows:createDistributable

# Package DMG installer
./gradlew :windows:packageDmg
```

### Building on Windows
```bash
# Compile and create executable directory
./gradlew :windows:createDistributable

# Package MSI / EXE installer
./gradlew :windows:packageMsi
./gradlew :windows:packageExe
```
