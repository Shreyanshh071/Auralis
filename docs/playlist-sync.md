# Playlist sync

On Windows and Android, open a custom playlist's **⋯ → Playlist sync**. An imported playlist shows a single **Sync with YouTube Music/Spotify** toggle. Its saved import ID selects the original service and remote playlist automatically. There is no service picker, URL field or choice of which copy to use for these playlists.

Turning the toggle on connects to that same remote playlist and merges songs from both copies, including additions made before enabling sync. It does not create another service playlist or delete either side's songs during initial linking. After linking, additions in Auralis upload automatically after local changes settle; additions on the source service are pulled during the next automatic check. The signed-in service account must own or have edit access to the remote playlist. Auralis login alone does not grant access to Spotify or YouTube Music.

A playlist saved only in Auralis has no import source to infer. Existing unlinked local playlists retain setup for linking a destination, with a preview before confirmation. Once linked, they use the same simple toggle.

New creation/import flow: the Import screen offers **Keep imported playlists in sync**. Its selection is captured when each import begins and applied only after that playlist's tracks have been saved. New playlists use one creation dialog with **Auralis only**, **YouTube Music**, and **Spotify** destinations; choosing a service creates a private remote playlist and enables two-way sync immediately, without a second setup toggle. This dialog is also used by the player’s plus menu and song/album playlist pickers. Failed uploads retain the local playlist and journaled remote ID so retrying does not create another remote playlist.

While the app runs, linked playlists are checked about every five seconds and after playlist content changes settle (about 150 milliseconds). Artwork refreshes do not reset that delay. A conflated worker retains edits arriving during an upload and processes the latest contents immediately afterward. Temporary failures retry after one second, then three seconds, up to the normal five-second check interval. Local edits survive offline periods in the normal library. The next successful check merges independent changes against the last successful baseline. Conflicting titles or reorders pause sync instead of overwriting a copy. Turning sync off and on reconnects using a non-deleting initial merge. Uncertain recording matches, unavailable songs and unsupported Spotify episodes/local files pause sync instead of deleting or substituting entries.

Turning sync off keeps both playlists. Deleting the Auralis copy disconnects it without deleting the remote copy. Links are stored on each device; enabling one on Windows does not silently enable uploads from another device. Closing/force-stopping the app stops its sync worker; it resumes when the app opens again.

## Reliability and permissions

The link is bound to the connected service account, with a durable journal written before uploading. A stale preview or changed account is rejected. The journal retains the intended upload and local snapshot. An interrupted upload retries only when the service still matches the verified baseline or intended result; unexpected remote changes pause sync for review. Older journals can merge additions from both copies, including an earlier successful addition followed by an interrupted verification, only when both still contain every baseline occurrence in order. A missing or reordered baseline entry still pauses recovery. Account restoration preserves existing service-linked playlists so an older cloud copy cannot erase queued edits. Artwork-only refreshes do not invalidate an upload. Write transports validate the preview against a fresh service snapshot immediately before editing; the engine avoids repeating that same preflight download. Final uploaded contents are verified before recording success. Pure pulls validate once and reuse that response for the local copy. Remote services do not provide a cross-service transaction: simultaneous edits during an upload can still require review, and an interrupted upload can leave a partially updated remote copy.

Spotify uses the Web API and requires playlist read/write authorization and applicable app access. An expired or insufficient session shows an explicit error; signing into Spotify does not guarantee its current token has those permissions. YouTube Music uses the selected signed-in session and its internal playlist endpoints, which may change. Unsupported or incomplete responses stop sync. No account credentials are stored in the sync journal.

Validation uses fake remote accounts and HTTP fixtures for pushes, pulls, conflicts, offline failures, duplicate occurrences, legacy shelf and append-action continuation pages, batch uploads and YouTube occurrence-handle edits. These checks do not establish that a particular live account has write access. No existing remote playlist is modified merely by installing this feature.

## Directory layout

- Windows engine/transports: `windows/core/src/commonMain/kotlin/com/auralis/music/data/playlistsync/`
- Windows dialog: `windows/core/src/commonMain/kotlin/com/auralis/music/ui/library/PlaylistSyncDialog.kt`
- Android engine/transports and session factory: `android/app/src/main/java/com/auralis/music/data/playlistsync/`
- Android dialog: `android/app/src/main/java/com/auralis/music/ui/library/PlaylistSyncDialog.kt`
- Windows integration: desktop app root and the selected YouTube library session.
- Android integration: app root and Library's existing playlist options sheet.

The three engine/transport sources have identical logic in the platform directories. They depend on platform Spotify sessions, so they are not moved into the Android shared module. Android keeps its existing Room schema, which already stores playlist entries by position. APK downloads remain the two root files `Auralis-Release.apk` and `Auralis-Debug.apk`.

Protocol references: [Spotify Web API](https://developer.spotify.com/documentation/web-api), [ytmusicapi playlist operations](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/mixins/playlists.py).
