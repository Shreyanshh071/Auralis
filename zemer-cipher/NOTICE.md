# zemer-cipher (vendored)

Source: https://github.com/ZemerTeam/zemer-cipher, commit `eccd43e` (2026-09-27), GPL-3.0 (see LICENSE).
Copyright its authors (alltechdev and the Zemer team); credits for BgUtils (MIT), yt-dlp and NewPipe
are in the upstream README.

Auralis uses it to download age-restricted songs for users who signed in to YouTube: it deciphers
the web clients' signature and `n` parameter with YouTube's own player script, using remote player
configs from the upstream repo's `player_configs.json` (refreshed at runtime), and mints PO tokens.

Local adaptations include build integration, WebView lifecycle and error handling, and reading
the PoToken API key from generated BuildConfig instead of a hardcoded upstream value.
Tests from upstream were not vendored. See `docs/client-configuration.md` for build configuration.
