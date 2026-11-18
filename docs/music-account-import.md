# Connected playlist import on Android

Profile → Import playlists now includes Deezer, Tidal and Amazon Music account cards.
Connect a service, sign in on its own page, and tap **Load playlists**. The native picker
supports search, select all / deselect all, multiple selection, batch import, progress and
cancellation. Failed reads stay selected for retry. Existing service playlist IDs are reused
on re-import; these imports refresh the mirrored playlist rather than creating another copy.

## Service adapters

- **Deezer:** reads the signed-in ARL cookie through Android's CookieManager; exchanges it
  for an in-memory JWT, then queries the user's owned playlists and their tracks through
  Pipe GraphQL. Both connections use cursor pagination. JWT authentication is retried once
  with a fresh exchange. No Deezer developer app key is required by this adapter.
- **Tidal:** reads access-token fields from the signed-in web player's storage only on
  `https://listen.tidal.com`. Validates the session, enumerates collection folders with
  cursor pagination, then reads each selected playlist's item pages with the user's token.
  It does not fall back to a public web token when private access fails. Video-containing
  playlists currently fail explicitly rather than silently dropping video entries.
- **Amazon Music:** reads the user's signed-in web library and selected playlist pages.
  The region selector supports the Amazon Music regional hosts listed in the adapter.
  The reader traverses open shadow roots, accumulates virtualized rows while scrolling,
  and requires a declared song count plus contiguous row positions before returning data.
  If Amazon's current page or localization doesn't expose those fields, import fails
  explicitly; partial rows never replace an existing playlist. Library discovery is bounded
  to the items exposed by the signed-in page, so service-side UI changes may need adapter fixes.

These are session-based integrations, not approved official partner API integrations.
Private playlist retrieval depends on the service accepting the user's session. The source
implementation is present, but private access has **not been verified with live accounts on
a phone**. In particular, Amazon's current DOM and Tidal's storage format require device QA.

## Data and credentials

Service login cookies remain in Android's existing WebView cookie store. Tokens are held in
memory and are not logged or stored in Auralis preferences. Preferences store only a service
connection flag. Disconnect removes that service's cookies and web storage without clearing
Spotify or YouTube sessions. Imports carry metadata only and reuse Auralis's existing official
audio matching pipeline; the adapters do not fetch service audio or interact with DRM.

Each selected playlist must finish reading before it is passed to the library repository.
Deezer/Tidal count mismatches, repeated pagination cursors, malformed pages, unsupported
items and Amazon gaps are errors. Successfully read playlists can still be saved if another
selected playlist fails. Count equality verifies completeness, not a snapshot of a playlist
that someone edits concurrently during import.

## Validation

Run the offline JavaScript adapter tests with:

```powershell
node scripts/test-music-account-import.mjs
```

The checks cover URL boundaries, regional identities, row parsing, order and duplicate-song
preservation, incomplete/unknown totals, foreign origins, recommendation-page rejection,
and Tidal access-token extraction. XML/resource checks and `git diff --check` also pass.
After build authorization, `:app:assembleRelease` passed, including Kotlin compilation and
release lint. The signed APK was verified and copied to the workspace-root
`Auralis-Release.apk`; its hash matches the build output. No APK installation or live-account
tests were performed. The JavaScript fixture tests do not validate the native HTTP adapters
or Compose UI at runtime.

Device verification still needs to exercise each service's login, a private playlist, more
than one page of tracks/playlists, multi-selection, re-import, expired sessions, cancellation,
and signing out without disturbing Spotify/YouTube. Compare imported song counts and order
against the original playlists. Install only when authorized.
