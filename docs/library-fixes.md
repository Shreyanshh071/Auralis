# Library, playlist and queue repairs

**Date:** 2026-08-22
**Scope:** repairs to features that already existed but did not work. No new
features, no UI redesign, no architectural change. The visual language of every
touched screen is unchanged; the only new element is a single icon button that
had no equivalent before because the operation behind it was unreachable.

These fixes cover the three defects recorded in `docs/parity-audit.md`: a
user-created playlist could never be filled or deleted, the queue was discarded
on every reload, and Liked was unreachable on a phone.

---

## 1. A created playlist could never be filled

`addToPlaylist` existed in `PlayerContext` and was exposed on the context, but
**nothing in the application called it**. Playlist creation worked, so the app
looked functional: you got a playlist, a toast, and a tile in the Library that
stayed permanently empty.

**Fix.** `src/components/modals/AddToPlaylistButton.tsx` is the caller. It is a
self-contained trigger plus picker, so a track row adds it as one element. It
appears on Home rows, Explore cards, Liked rows, the rows inside an open Library
playlist, and next to the title in Now Playing.

Details that matter:

- Playlists the track is already in are listed but disabled and marked "Added",
  instead of silently doing nothing on click.
- Every handler calls `stopPropagation`, because the parent row's click plays the
  track.
- The picker overlay sits at `z-[60]`; the Now Playing and Library overlays are
  `z-50`, so it is usable from inside them.
- Creating a playlist from the picker passes the track as an initial member
  rather than calling `createPlaylist` then `addToPlaylist`. Two calls could not
  work: `addToPlaylist` resolves the playlist from the list rendered *before* the
  click, which does not contain the new one yet. `createPlaylist` therefore takes
  an optional `initialTracks` and returns the new `Playlist`.

## 2. A created playlist could never be deleted

`LibraryView` gates its delete control on `playlist.isCustom`, and
`createPlaylist` never set that flag. Imported playlists set it
(`services/youtubeImporter.ts`), so imports were deletable and creations were
not — which is why this looked like a Library bug rather than a create bug.

**Fix.** `isCustom` is what it always meant: *a stored playlist, therefore the
user's to edit*. The three read-only collections (Liked, Recently Played, My Top
50) are assembled on the fly in `LibraryView` and are never stored, so they
never carry it.

- `createPlaylist` sets `isCustom: true`.
- Every playlist read back from `localStorage` **or** merged from Firestore is
  normalised through `asUserPlaylist`, so playlists created before this fix
  become deletable without a migration step.
- A remove-from-this-playlist control was added to the rows of an open playlist,
  giving `removeFromPlaylist` its first caller as well.

## 3. The open playlist showed stale contents

`LibraryView` stored a **copy** of the selected playlist, so adding or removing a
track changed nothing on screen until the panel was closed and reopened. Adding
the controls above without fixing this would have made them look broken.

**Fix.** The state is now a descriptor (`{ kind: 'stored', id }`, or one of the
three views) resolved against live state on every render. A deleted playlist
resolves to nothing, which closes the panel by itself.

## 4. The queue was never persisted

There was no `auralis_queue` key. Reloading the page or restarting the app threw
the queue away silently, next to seven other `auralis_*` keys that were saved.

**Fix.** `src/lib/queueStorage.ts` owns the storage rules and
`PlayerContext` writes `{ tracks, index, currentTrack }` on every change.

Restoring is deliberately **passive**: nothing is handed to the YouTube player
and nothing plays until the user presses play.

Three honesty hazards were dealt with rather than shipped:

- **The current track is restored too.** Without it the mini player stays hidden,
  and Now Playing only opens from the mini player, so a restored queue would have
  existed in storage and been unreachable in the UI — persistence in name only.
- **Cold start.** With a restored track, `playVideo()` would be called on a
  player that was never given a video: it does nothing, while the UI flips to
  "playing". `loadedVideoIdRef` tracks what the player actually holds, and
  `togglePlay`/`resume` load the track first when it does not match.
- **Seeking before the first play** would have moved the progress bar with no
  audio behind it. `seekTo` refuses while nothing is loaded.

Malformed stored values are discarded rather than trusted, so a truncated or
hand-edited entry cannot fill the queue panel with rows that will not play, and
an out-of-range index cannot make next/previous step from the wrong position.

## 5. Navigation defects

- `Sidebar` listed every playlist and then ignored `pl.id`, calling
  `setActiveView('library')` — every playlist link led to the same grid. It now
  takes an `openPlaylist` callback. `App` holds the request and clears it once
  the Library consumes it, so switching away and back does not reopen it.
- The Library FAB opened the **import** dialog, which already had two other entry
  points, while playlist creation had none outside the desktop sidebar. Creating a
  playlist was therefore impossible on a phone. The FAB now creates a playlist.
- `MobileNav` omitted Liked, and the sidebar that links to it is `hidden md:flex`,
  so saved songs were unreachable on a phone. A fourth entry was added and the
  nav padding tightened to fit it. `BookOpen` and `Music2` were imported and
  never used; both were dropped.

---

## Verification

**Typecheck: passes.** `npx tsc -b --force` exits 0 (TypeScript 6.0.3).

**Storage rules: 14 automated tests, all passing.**
`node scripts/test-queue-storage.mjs` runs against `src/lib/queueStorage.ts`
directly — the same module the app imports, not a copy. Node strips the type
annotations itself (Node 22.18+), which is sound here because
`tsconfig.app.json` sets `erasableSyntaxOnly: true`.

Covered: save/restore round trip; the exact storage keys; missing, empty,
truncated, non-JSON and wrong-shaped values; storage that is absent or throws on
read; entries that could never be played; index clamping, flooring and
re-anchoring to the restored track; a current track missing from the queue; and
`isCustom` normalisation for legacy stored playlists.

**The tests were proven to have teeth.** Three faults were injected into the real
module, one at a time, and each was caught by exactly the test that should have
caught it, with the file restored byte-identically afterwards (same SHA-1):

| Injected fault | Test that failed |
|---|---|
| index clamp removed | an out-of-range or non-numeric index is brought back in range |
| `isCustom` normalisation removed | every stored playlist reads back as one the user can edit and delete |
| malformed-entry filter removed | entries that could never be played are dropped |

**What automated tests here do not cover.** The React state updates
(`addToPlaylist`, `deletePlaylist`, the live `LibraryView` selection) and the
player's cold-start path need a browser; there is no test runner or DOM
environment in this repository, and one cannot be installed offline. They are
covered by the typechecker and by the manual sequence below, which has **not**
been run — it needs a browser on the Windows machine.

1. Create a playlist from the Library FAB on a narrow window. Expect it to appear
   in the grid and in the sidebar.
2. Play a song, open Now Playing, use the add-to-playlist button. Expect a toast
   naming the playlist, and the count in the sidebar to increase.
3. Open the same button again. Expect the playlist to be listed as "Added" and
   not clickable.
4. Reload. Expect the track still in the playlist, the queue still populated, the
   mini player showing the last track, **paused**, with the correct duration.
5. Press play. Expect audio to start from the beginning of that track.
6. Drag the scrubber *before* pressing play. Expect it to refuse to move.
7. Remove the track from inside the open playlist. Expect the row to disappear
   immediately, not on reopen.
8. Delete the playlist. Expect the panel to close and the tile to disappear.
9. Reload once more. Expect the deletion to have survived.
10. Click a playlist in the sidebar. Expect that playlist to open, not the grid.
11. Switch to Home and back to Library. Expect the grid, not the playlist that
    was opened before.
12. On a phone-width window, expect four nav items and Liked to be reachable.

**Not verified here, unchanged from the baseline:** `npm run build` and the
Android debug build cannot run in this environment. `assembleDebug` has still
never passed, because the Windows machine has no JDK 21 and no Android SDK. Run
`.\scripts\detect-android-toolchain.ps1` and then `.\scripts\verify-build.ps1`.

## Cloud sync

The `hydratedUid` gate is untouched and still guards both writes: nothing is
written to Firestore until `fetchCloudData` has succeeded for the signed-in uid,
so a fresh device cannot overwrite cloud playlists with an empty array. Playlists
now change far more often than before, which makes that gate more load-bearing,
not less. Queue state is local only and is not synced.
