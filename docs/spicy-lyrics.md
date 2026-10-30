# Spicy Lyrics integration

Android and Windows can query Spicy Lyrics while playing YouTube Music audio. The
adapter searches Spotify metadata for the same recording and calls the official
Spicy Lyrics v1 endpoint with its Spotify ID. Playback IDs are never changed.

## Setup

Official locally configured builds include Auralis's **publishable** native-client
key, so users do not need to enter a key on either platform. The no-Origin-header
option must be enabled on this application key. Server secret keys are never
bundled. Android does not expose a key field in settings. Windows retains an
optional personal override; leaving it blank selects the included application key.
Application and per-IP API limits still apply.

Enable Spicy Lyrics in the provider switches. Reopen lyrics or use the Android
   provider picker to request it when another provider is already cached.

On Windows, selecting the Spicy animation also runs a separate source-enrichment
check while cached lyrics remain visible. A cached word-sync result does not
cancel it, and the normal provider race skips the duplicate Spicy request.
Matched Spicy word timing replaces a same-tier fallback; line-only replies never
downgrade existing word timing. Failed checks retry after five minutes on later
loads; valid Spicy responses refresh after a day, within the 30-day storage limit.
Source syllable boundaries bypass spelling-based timing redistribution.
Android now checks for the same source upgrade after displaying cached or fallback
lyrics when the Spicy animation is selected, including remote playback. The check
has its own five-minute failure retry limit and survives the word-cache early
return; changing songs or manually selecting a provider cancels it. Source
`IsPartOfWord` boundaries and nested pieces survive Android's parser, spelling
repair, playback offsets and Room cache. The renderer unwraps those measured
pieces for animation without adding spaces inside the written word. Translation
completion cannot restore a superseded lyric source.
Backing-vocal wrappers are removed consistently from displayed text and its timed
tokens (including nested syllables). Previously a displayed `Oh` could not match
a source `(Oh)` token and silently fell back to static text. The correction keeps
every original timestamp/rest and the existing 70% italic backing style, enabling
the same whole-part and held-letter swell/glow in the sidebar and fullscreen.

For builds from source, Android reads `SPICY_LYRICS_PUBLISHABLE_KEY` from the
environment or ignored local build properties. Windows packages
`windows/local-resources/spicy-lyrics.properties`, with a `publishableKey` entry.
These local application credentials are excluded from Git. Android source builds
must configure the key at build time. Unconfigured Windows builds can use a
personal publishable key in Settings → Playback → Lyrics source order.

On Android, select Settings → Appearance → Lyrics animation → Spicy Lyrics to use
the native Spicy renderer. The provider and animation remain separate choices:
other providers can supply word timing for this style. Standard blur and existing
phone singer positioning remain in effect. On the phone, credits scroll after the
final lyric in both classic and experimental views, with no tinted backing box.
Their visibility follows their scroll position, independently of the playback
controls, so scrolling back does not make the credits disappear.

Android database migration 9 → 10 adds credits, Spotify recording ID, and the
original API fetch time without deleting library data. Cache round-trips retain
Spicy syllable boundaries; response age is never renewed by alignment or copying.
Expired Spicy rows are rejected on read and purged at startup and periodically
while the repository is alive. Holding a line to select and share it works for
Spicy API responses on Android, as it does for every other provider.

Without a valid included or personal publishable key the provider is omitted from
automatic requests and the picker. Personal overrides stay in local content
settings. Normal provider switches still apply.

Spotify metadata lookup uses Auralis's existing Spotify session/token resolver.
For a known Spotify recording ID, Windows first reads the track's public embed
metadata and verifies its ID, title, artist and measured duration. This does not
extract a session token. Existing Spotify-to-YouTube matches can supply that ID.
Songs without a known ID still need Spotify metadata search. HTTP 429 responses
respect the full Retry-After value; cached/fallback lyrics remain available.
Its anonymous fallback may be unavailable or rejected by Spotify; a signed-in
Spotify session or working configured Spotify developer credentials may be needed.
A Spicy Lyrics key does not grant Spotify API access. If metadata search is
unavailable, the adapter returns no candidate and other lyrics providers continue.

## Matching and timing

The PC animation is a separate setting: Appearance → Lyrics animation style →
Spicy Lyrics. It uses a native renderer with soft highlights, word swelling,
held-note letter emphasis and spring scrolling. Choosing the API provider alone
does not select an animation. Line-only data stays line-synced. Its active line
gently enlarges, with a vertical highlight and inner glyph glow over the source line
interval, in both the Windows sidebar and full player. When a source omits a line
end, the next line start bounds the highlight; an unknown final end stays a
whole-line highlight. The renderer does not create word timestamps that the
source did not supply.
Active line brushes explicitly use full drawing opacity, independently of the
dim colour used to measure upcoming text. Otherwise Compose inherits that
paragraph alpha and makes both the active highlight and its glow nearly invisible.
Rendering checks exercise inactive-to-active transitions with the real dim colour,
mixed Hindi/English text, multiple line durations, and sidebar/full-player widths.

The original spring-driven word swell and held-letter animation are restored.
Windows retains the original timed syllables inside joined TTML words, including
through cache reload and alignment. Spicy animates those pieces independently:
short syllables swell as a unit; held syllables use letter emphasis, while an
unsplit held word remains a full letter group. Wrapping and italic backing-vocal
styling no longer disable letter motion. Old Windows lyric caches refetch once
because they did not retain the syllable intervals.
Regular emphasis selection applies to every supplied syllable held for at least
1000 ms, including one-letter syllables. Unicode graphemes replace the former
ASCII-only gate; combining marks, emoji sequences and Indic conjuncts stay whole.
Spicy API `IsPartOfWord` joins are preserved explicitly with all original timed
pieces, including three or more pieces and spellings absent from dictionaries.
No word-name list determines whether a fragment swells or uses letter emphasis.
Spicy's Windows interlude rows compare adjacent lead groups using supplied
line/word ends, matching upstream `ApplySyllableLyrics` and `ApplyLineLyrics`.
A lead gap of at least three seconds starts dots immediately at the lead end;
the minimal-mode rule is five seconds. Background rows do not change that gap,
and earlier overlapping leads do not delay it. Unknown ends are not estimated.
There are no automatic outro dots. Other lyric styles retain their own break rules.
The dot group uses the upstream 300 ms entry, 500 ms pre-hide and 400 ms
anticipating exit curve, with individual dot intervals finishing 550 ms early.
Visible dot diameters are reduced by 8% while retaining the existing animation
room. Intro and interlude click targets do not clip their contents to a pill;
raised dots and the anticipating exit therefore retain their complete circles.
Line-by-line animation and inner glyph glow remain. Backing vocals retain their
smaller, bold italic presentation in both views. The extra Regular, Simple and
Refreshed mode selector has been removed. The standard lyrics blur setting retains
Auralis's progressive viewport blur across styles.
Ended phrases dim at their
supplied end times. Overlapping backing vocals stay with their lead scroll group.
On PC, singer IDs are ranked by lead-line count for the whole song: the main
singer stays left, the second right, and further singers and ensemble lines are
centered. Missing singer metadata retains the normal alignment; artist names
alone never assign a singer to a lyric line.
Line-only glow stays inside glyphs. Following the October 27 swell-glow video,
word/syllable and letter swelling also carries a tight glyph-shaped bloom.
Each fragment's transparent ink mask is blurred once on a software raster
surface, then the cached halo moves with the same join compensation and springs
as its sharp text. No paragraph rectangle or per-frame GPU blur is used.
Upcoming visible rows prepare their glyph masks before activation; dim text is
painted separately from the stable mask layout, so changing active-line opacity
does not rebuild the raster and halo during a short sung syllable. Slow UI frames
use their full elapsed time for seek detection before spring integration is bounded;
ordinary playback through a delayed frame no longer resets the motion as a seek.
Real seeks still reset, and source syllable/highlight timestamps are unchanged.
Android now uses the same elapsed-frame seek check as Windows. Its previous
100 ms integration cap was also used for seek detection, so normal playback
through a 350+ ms delayed frame could snap a word or letter spring to its goal.
Phone regression checks cover delayed frames at 0.5–2x playback, actual seeks,
pauses, spring continuity and source-duration highlight progress.
Mask preparation is limited to the active phrase and the next three seconds of
lyrics; browsing distant verses draws ordinary text instead of preparing every
word halo. Fullscreen and sidebar following have one cancellable scroll owner.
Glyph bloom generation runs on a shared, two-worker background dispatcher instead
of blocking the UI at phrase boundaries. Timed fill and swell remain available
while upcoming glow masks prepare. Joined-syllable runs and their translation
buffer are cached instead of regrouping and sorting glyphs every drawn frame.
Once the full-player opening transition completes, covered Home content stops
drawing; its navigation and scroll state stay mounted. Direct3D no longer receives
the extra idle present intended for OpenGL screenshots. Frame and lyric-layer
clears remain enabled. Offscreen pixel tests cannot verify physical-panel trails
that do not appear in screen recordings.
Lyric clicks dispatch native seeks off the UI thread and supersede queued older
clicks. The player holds the requested clock until a matching decoder timestamp
arrives, rejecting pre-seek reports. The fullscreen highlight samples each display
frame and hands off when audio has advanced beyond the target, including delayed
first reports and paused seeks. Blurred line surfaces clear before redrawing so
reused layers cannot retain the previous lyric's ink.
After manual scrolling becomes idle, following returns to the current position
through a single damped viewport animation. It measures the active row as it
approaches, never prepositions at the top or starts a second correction; fresh
wheel input cancels its owner. The native presentation target clears before each
frame as well as individual lyric layers, preventing stale swap-buffer ink.
Automatic phrase transitions use an exactly integrated critically damped spring,
including sub-8 ms frames on 144/240 Hz displays. Rapid consecutive targets retain
velocity; manual input and lyric seeks reset it. The newest started lead owns the
scroll anchor, so a lingering older harmony cannot pull the viewport backwards.
Each vocal remains active through the latest genuine word or nested syllable end,
even when paragraph metadata ends earlier. These ends are prepared once per lyric
delivery. Spicy's blur follows phrase distance rather than retargeting on each
scroll pixel, and unchanged paragraph layouts reuse their glyph/glow masks.
The sidebar reserves anchor-sized top and bottom padding so its first and final
phrases can reach the same position as every other phrase. Its full-height panel
uses the same local-or-remote playback clock as the main player.

Windows' lyrics mini-player is a non-modal square popup inset at the bottom-right
of the app, with the same player background, a corner resize target and controls
that fade on pointer exit or inactivity. Cover cues combine two restrained expand
corners with pointer-led water rings. Opening the main player enters true
fullscreen and hides both custom and Windows caption chrome; closing restores
the previous placement. F11 toggles fullscreen directly too.
Lyrics following also resumes while paused. New wheel input cancels that return immediately and starts a
fresh idle wait. Offscreen fading-edge layers are explicitly cleared before each
frame so translucent text cannot accumulate in a reused compositing surface.
Halo pixels are copied directly into owned premultiplied bitmaps, including
transparent pixels; the Compose 1.7 SrcOver conversion over uncleared storage
is deliberately avoided to prevent recycled pixels appearing as bright blobs.
The October 27 desktop comparison checked the user's Spotify Spicy settings:
Simple Lyrics Mode and Join Split Words were both off. Native split syllables
now scale around their own centre (CSS inline-block default), instead of a shared
word boundary, and adjacent raster slices no longer overlap under independent
transforms. Spring frequencies and damping retain the upstream regular values.
Animated pieces within one written word are packed by their changing glyph
advances around that word's centre, so independent swelling cannot introduce a
space at a source syllable boundary. Actual whitespace ends a joined run.
Whole-word illumination now uses the full glow envelope instead of attenuating
it twice; line-only illumination brightens the complete line at its peak.
The native renderer follows regular Spicy's glow envelope, with 35% word bloom
and a restrained 65% emphasized-letter bloom so adjacent halos do not obscure
the letter shapes. It retains the user's smaller italic backing vocals and
does not claim pixel-identical browser text-shadow output.
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
area, with wrapping contributor links drawn directly over the lyrics background.
The footer says "Lyrics from" the actual source. Apple Music and Spotify responses
from the Spicy API also link "via Spicy Lyrics". Community responses retain their
linked uploader and maker. Settings → About separately acknowledges "Animation
inspired by Spicy Lyrics", with a link to the upstream project.
When the Spicy animation style is selected, the lyrics footer also links a UI and
animation acknowledgement to Spikerko and contributors. Supplied `SongWriters`
names appear as "Written by" alongside the source and sync contributor credits.
The sidebar uses one compact row (for example, "Better Lyrics · Spicy UI:
Spikerko+"). Long community credit lists scroll horizontally within that row;
the fullscreen footer keeps the complete wording and wraps when needed.
Contributor links use the response URL; missing URLs leave visible text rather
than guessed links. Unknown sources are labelled unknown. The Windows lyrics-copy/share
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
