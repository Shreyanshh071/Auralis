# Auralis 1.1.1 — rebuilt selective-port release

This release replaces the deleted earlier 1.1.1 APK. It was reconstructed from v1.1.0 (`e5f7e00a02c40d22dd50429776a2c806cffb6f6b`) with selected 1.1.1 functionality and subsequent verified fixes. It is not the APK associated with the historical `v1.1.1` tag.

## Download

- File: `Auralis-v1.1.1-universal.apk`
- Package: `com.auralis.music`
- Version: **1.1.1**, version code **3**
- Android: **7.0/API 24+**
- Size: **24,628,921 bytes** (approximately 24.6 MB / 23.5 MiB)
- SHA-256: `319b24743a681aa6c7992e6bcb4a9dd71a95393f694960e85af7cf23500be5dc`
- Non-debuggable release build with code/resource shrinking; signed with the existing project certificate to preserve update compatibility. The current release Gradle signing configuration is named `debug`.

## Included

- Playback foreground-service idle callback fix and download-only offline queue protection.
- Lyrics provider/parser updates, original-script preservation, defective timing rejection, full wording validation and presentation/re-sync improvements.
- Handle-only playlist reorder and order locking.
- Stable imported playlist identity, playlist statistics and Speed Dial.
- Selected search ranking and Shorts filtering/continuation improvements.
- Import UI and YouTube account/playlist picker.
- Playlist downloads, folder/progress display and offline removal management.
- Account isolation and coordinated account/cloud deletion.
- Listen Together host/listener cleanup, vote removal, retained listener requests, persisted cleanup retries and alone/idle closure handling.
- Selected UI improvements.

## Artwork behavior

Spotify's existing release artwork and album metadata remain authoritative during YouTube playback rematching. Matching a playback video does not blindly replace that artwork with the video's thumbnail.

Explicit artwork selection is persisted separately from playback identity and protected at the Track write boundary. This is not an automatic catalog/canonical-release resolver. If Spotify supplies a compilation release, preserving that image is not evidence of rendering corruption. Third-party playlist conversion can select a different Spotify recording/release before Auralis imports it.

The removed release-selection system, old restoration artifact and bulk artwork-repair migration are not included. The original 580-track backup was not restored or migrated. Room includes the current artwork-selection schema and data-preserving compatibility paths; this release is not a Room v9 build.

## Validation scope

57 focused JVM tests passed:

| Test class | Passed |
| --- | ---: |
| LyricsValidatorTest | 6 |
| ManagedPlaylistDownloadStorageTest | 7 |
| PlaybackForegroundReleaseTest | 7 |
| PlaylistDragReorderDetectorTest | 15 |
| RoomCleanupPersistenceTest | 4 |
| RoomCleanupTest | 6 |
| SparkAccountDeletionTest | 2 |
| SpotifyArtworkAuthorityTest | 10 |

Debug and release builds succeeded. The final version-only rebuild was installed as 1.1.1/code 3 and opened on the connected phone without a captured startup fatal exception. This was not a new full regression run. Room instrumentation tests are separate from the JVM count above.

Earlier scoped checks covered normal Firebase account deletion and Listen Together host/listener exit, offline retry, stale-session protection, and timer warning/closure/cancellation. User checks confirmed selected reorder, lyrics and download fixes. No guaranteed startup time, rendering frame rate or synchronization latency is claimed.

## Limitations

- Lyrics/stream/catalog availability and correctness depend on providers and region.
- Public playlist audio copies in `Download/Auralis` may survive app uninstall or Clear storage. App-private files are distinct from public copies.
- Spark room cleanup has no independent server scheduler. Persisted retries need the app to reconnect; permanent offline/uninstalled hosts are not guaranteed immediate cleanup.
- Account deletion failure, re-authentication and concurrent multi-device writes need further live verification.
- Existing historical artwork is not automatically repaired, and no universal cover-correctness claim is made.
- Source builds need the configured local native SDK dependencies as well as Firebase/provider setup.

## Source history

The release is published under `v1.1.1-rebuilt`, pointing to the selective-port source. The old `v1.1.1` tag is retained unchanged as historical evidence and should not be used to reproduce this APK. The default-branch README documents this release; the release source remains on `selective-port`.
