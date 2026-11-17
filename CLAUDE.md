# Claude & AI Assistant Guide

See [AGENTS.md](AGENTS.md) for full project architecture and cross-platform desktop guidelines.

## Quick Summary for AI Coding
- **Monorepo:** Android (`:app`) + Desktop (`:windows` and `:windows-core`).
- **Desktop Parity:** `:windows` targets both **Windows** and **macOS** using Compose Multiplatform.
- **Rules:**
  1. Any new feature added to `:windows` automatically ships to both Windows and macOS.
  2. Guard all Win32 / JNA / DPAPI APIs with `isWindows`.
  3. On macOS, use native traffic lights (`apple.awt.fullWindowContent = true`, `undecorated = false`, `78.dp` title bar clearance). Do not draw Windows caption buttons on macOS.
  4. On macOS ARM64, disable Netty OpenSSL/native transport via JVM flags to prevent unsigned dylib SIGKILL crashes.
  5. Build with `./gradlew :windows:createDistributable` or `./gradlew :windows:packageDmg` (macOS) / `./gradlew :windows:packageMsi` (Windows).
