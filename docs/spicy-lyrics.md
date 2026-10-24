# Spicy Lyrics integration

Android and Windows can query Spicy Lyrics while playing YouTube Music audio. The
adapter searches Spotify metadata for the same recording and calls the official
Spicy Lyrics v1 endpoint with its Spotify ID. Playback IDs are never changed.

## Setup

1. Create an application at <https://developers.spicylyrics.org/dashboard/applications>.
2. Enable client access and create a **publishable** `sl_pk_` key. Enable the
   no-Origin-header option for native clients. Do not use a server secret.
3. Android: Settings → Content → Lyrics → Spicy Lyrics publishable key.
   Windows: Settings → Playback → Lyrics source order → Spicy Lyrics publishable key.
4. Enable Spicy Lyrics in the provider switches. Reopen lyrics or use the Android
   provider picker to request it when another provider is already cached.

Without a valid-looking publishable key the provider is omitted from automatic
requests and the picker. Keys are saved in local content settings, not compiled
into the application. Normal provider switches still apply.

Spotify metadata lookup uses Auralis's existing Spotify session/token resolver.
Its anonymous fallback may be unavailable or rejected by Spotify; a signed-in
Spotify session or working configured Spotify developer credentials may be needed.
A Spicy Lyrics key does not grant Spotify API access. If metadata search is
unavailable, the adapter returns no candidate and other lyrics providers continue.

## Matching and timing

The PC animation is a separate setting: Appearance → Lyrics animation style →
Spicy Lyrics. It uses a native renderer with soft highlights, word swelling,
held-note letter emphasis and spring scrolling. Choosing the API provider alone
does not select an animation. Line-only data stays line-synced; the renderer
does not create word timestamps that the source did not supply.

The Windows regular Spicy style uses per-source-syllable motion, cubic spline
goals with damped springs, a 35% unsung / 85% lead highlight, and small index-based line blur
rather than the other styles' deep viewport blur. Ended phrases dim at their
supplied end times. Overlapping backing vocals stay with their lead scroll group.
These presentation values target the regular Spicetify mode; browser/font
rasterization, Simple Lyrics variants and different source timings can still
produce visible differences. This is not a verified pixel-identical web port.
At the user's preference, backing vocals retain Auralis's smaller 70%-size,
bold italic presentation with a bright active highlight. Their ink can overflow
the row so lift and glow are not clipped.

- Title tokens must agree closely, at least one full artist name must agree,
  and recording duration must be known on both sides and differ by at most 2.5 s.
- Original version labels are retained before general search-title cleanup.
  Live, acoustic, remix, slowed, clean/explicit and other marked versions must
  agree. Supplied ISRCs must agree when both sides have one.
- Known Spotify track IDs use metadata lookup directly, with the same recording
  checks. YouTube IDs are never sent to the lyrics endpoint.
- Successful mappings use a bounded, 256-entry in-memory cache with a 24-hour TTL.
  Metadata, duration, album and case-sensitive playback ID are part of the key.
  Lyrics and credits use the existing persistent lyrics caches on each platform.
  Spicy Lyrics responses expire 30 days after the original API fetch. Alignment,
  translation and copying an entry never renew that timestamp. Expired entries
  are rejected from memory and storage and removed during cache cleanup; legacy
  Spicy entries without a retrieval timestamp are discarded.
- Cross-service matches are not labelled exact YouTube video matches. No guessed
  word times, stretched timing, or automatic video-intro offset is introduced.
  Metadata alone cannot prove identical intros or edits; conservative rejection
  and the existing master-alignment gates limit mismatches.

The parser supports Syllable, Line and Static API responses. It preserves real
syllable starts/ends, word continuation, vocal rests, separate background vocals,
speaker alignment, and supplied translations. API EndTime is the last lyric
timestamp; the Spotify recording length supplies duration metadata instead.

## Attribution and failure behavior

The API's source determines the on-screen linked provider credit. Community syncs
also retain the uploader and optional maker links, including after cache reloads.
Community payloads missing uploader attribution are rejected.

On PC, credits stay in a reserved bottom-left footer outside the scrolling lyric
area, with wrapping contributor links and a translucent contrast background.
Contributor links use the response URL; missing URLs leave visible text rather
than guessed links. Unknown sources are labelled unknown. The lyrics-copy/share
action is disabled for Spicy API responses. The Windows updater excludes the
refetchable lyrics directory from new user-data backups. Existing old backups
are not automatically rewritten; their stored lyric responses require a separate
retention review. Public per-user key onboarding requires the official app-template
process, and API access alone does not clear song-text rights.

Requests use separate Spotify and Spicy Lyrics bearer tokens, disable redirects,
and cancel OkHttp calls when the provider coroutine is cancelled. API errors or
malformed responses return no candidate. HTTP 429, 401 and 403 trigger a short
cooldown keyed to the current publishable key; 429 honors numeric Retry-After.

## Verification

Deterministic Kotlin tests on both platforms cover recording rejection, ID
validation, all three payload formats, timing/rest preservation, credits,
metadata mapping/cache reuse, token separation, missing/secret keys, malformed
payloads, HTTP failures, cooldowns and persistent credit round trips.

Run `:windows-core:desktopTest --tests '*SpicyLyrics*' --tests '*LyricsResponseCache*'` and
`:app:testDebugUnitTest --tests '*SpicyLyrics*' --tests '*LyricsResponseCache*'`. These tests intercept HTTP and
need no credentials. An authenticated live test requires a real native-client
publishable key and Spotify metadata access; no live success is implied by mocks.

References: <https://developers.spicylyrics.org/llms-full.txt> and
<https://developer.spotify.com/documentation/web-api/reference/search>.
