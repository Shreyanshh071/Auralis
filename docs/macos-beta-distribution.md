# Self-contained Apple Silicon beta

The DMG contains the Java runtime, libVLC 3.0.24 with its audio/video/network plugins, the native Auralis notch, and a pinned private Chromium fallback for YouTube sign-in. Recipients need macOS 14+ and Apple Silicon, but do not install Java, VLC, Chrome/Firefox, Boring Notch, Python, Swift or developer tools separately.

Chromium is used only when an existing supported sign-in browser is unavailable. Its profile belongs to Auralis and is separate from installed browsers. The archive comes from Google's official Chromium snapshot host and is pinned by revision and SHA-256 in `windows/macos-browser/chromium.json`; no user data is copied from the build machine. The browser ships intact with framework symlinks, helper signatures and JIT/sandbox metadata preserved. No sandbox-disabling flags are used.

VLC is copied at build time from the build host's Apple Silicon VLC 3.0.24 app (`AURALIS_VLC_DIR`, default `/Applications/VLC.app`). `scripts/bundle-macos-vlc.py` rewrites non-system dylib dependencies to bundle-relative loader paths, signs every library, rejects missing/external dependencies and includes licenses, source links and a payload hash manifest. The VLC application UI/updater and OS notification plugin are omitted; all playback codecs/plugins remain. The recipient's installed VLC is never needed. Mac plugin caching is disabled to preserve the app signature.

Build with `./gradlew :windows:packageDmg`. Build dependencies are copied before final outer-app signing. The browser download is cached on the producer's machine; no dependency downloads or additional installs are required on the recipient's machine.

`createDistributable` runs the finished app's `--verify-bundled-runtime` check before packaging. It bypasses normal startup/account/profile initialization and uses temporary, disposable data. It verifies:

- Muted PCM playback, pause, seek and resume.
- Opus/WebM, AAC/M4A and MP4 decoding from a loopback HTTP server supporting byte ranges.
- Every loaded VLC core/plugin path is inside the current app, without installed-VLC discovery.
- The private bundled browser starts headlessly with a fresh profile and responds on localhost with its sandbox enabled.
- Bundled JVM AWT, font manager, color management, JPEG and splashscreen libraries load with zero `/opt/homebrew` or `/usr/local` library paths.
- Every Mach-O binary in `Auralis.app` (runtime, VLC, Skiko, Notch, Chromium) links only system or bundle-relative libraries.
- The notch's own native check runs from its packaged location and all app signatures remain valid afterward.

The same runtime check can be run from a mounted DMG or a relocated app in a path containing spaces:

```sh
"/Applications/Auralis.app/Contents/MacOS/Auralis" --verify-bundled-runtime
```

The check does not sign in to Google or validate a real account's challenges; normal sign-in is performed by the recipient. Normal builds retain the production Connect endpoint. System media controls and the native notch require no external app. The existing Boring Notch visibility conflict policy still applies if the recipient already runs it.

This is an ad-hoc signed beta, not a notarized Developer ID release. macOS may require first-launch approval. Clean-VM and additional real-device checks are still useful for OS/hardware behavior, especially the custom lock-screen integration's private APIs; dependency smoke tests are not a claim of universal hardware compatibility.
