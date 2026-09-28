## Auralis 1.1.1

This update focuses on lyrics, search, playlist imports, and Speed Dial.

- Karaoke highlighting sweeps through each timed word, including shaped scripts, and completes the word when its final fragment ends.
- Search keeps same-name albums by different artists, ranks songs and albums together using relevance and available play counts, and does not repeat the two featured matches in the list below.
- YouTube playlist imports filter identifiable Shorts when metadata is available and preserve existing local playlists when an imported playlist has the same name.
- Your most-listened playlist can appear in Speed Dial after at least three starts from that playlist and 20 minutes of actual playback.

Download **Auralis-v1.1.1-universal.apk** for Android 7.0 or newer. It packages `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` and can update the project's 1.1.0 APK without clearing local data.

Listen Together rooms close automatically while the host app is running: after five minutes without guests or 30 minutes without playback, plus a one-minute warning. The host also deletes a room on a normal exit. Firestore TTL cleanup for rooms abandoned by an unexpected process termination is not configured.
