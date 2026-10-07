# zemer-cipher (vendored)

Source: https://github.com/ZemerTeam/zemer-cipher, commit `eccd43e` (2026-09-27), GPL-3.0 (see LICENSE).
Copyright its authors (alltechdev and the Zemer team); credits for BgUtils (MIT), yt-dlp and NewPipe
are in the upstream README.

Auralis uses it to download age-restricted songs for users who signed in to YouTube: it deciphers
the web clients' signature and `n` parameter with YouTube's own player script, using remote player
configs from the upstream repo's `player_configs.json` (refreshed at runtime), and mints PO tokens.

`src/main` is copied unchanged; tests were not vendored. Only `build.gradle.kts` differs (Auralis's
version catalog, no publishing).
