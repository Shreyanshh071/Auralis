# zemer-cipher (vendored, desktop port)

Source: https://github.com/ZemerTeam/zemer-cipher, commit `eccd43e` (2026-09-27), GPL-3.0 (see LICENSE).
Copyright its authors (alltechdev and the Zemer team); credits for BgUtils (MIT), yt-dlp and NewPipe
are in the upstream README. The Android app vendors the same commit in `zemer-cipher/`.

The Windows/macOS app uses it to play age-restricted songs for users who signed in to YouTube:
it deciphers the web clients' signature and `n` parameter with YouTube's own player script, using
remote player configs from the upstream repo's `player_configs.json`, and mints PO tokens.

Desktop adaptations (`windows/core/src/desktopMain/kotlin/com/zemer/cipher`):
- Android WebViews replaced by an unseen Chromium driven over the DevTools protocol
  (`HeadlessChromium`); `CipherWebView` and `PoTokenWebView` keep their names and roles but call
  awaited `Runtime.evaluate` instead of a JavaScript bridge. Pages are served from memory and every
  other request they make is refused (as upstream's `blockNetworkLoads`).
- Android `Context`, `Uri`, `SystemClock` and Timber replaced by a data directory, `java.net`
  encoding, `System.nanoTime` and a logging shim that drops debug lines (they print tokens).
- `PoTokenGenerator` suspends instead of blocking a thread; the PoToken API key comes from a
  build-generated resource (`potoken.properties`) instead of BuildConfig.
- The remote config refresh runs on first use instead of at startup; `PlayerDatesStore` is omitted.
