# Playback resilience: Metrolist and Auralis

Inspected 2026-11-16. This is a source audit, not a live playback benchmark.

## Revisions and scope

- Metrolist default branch: `667b8bc73b608004113e45fbc04b481eb80db720`.
- Its declared InnerTubeX dependency: `v0.11.7`, checked out at `e4fb0003ce092beb71f3de464c661bf9f3f54c7f`.
- Auralis: current working tree, including unfinished stream reliability changes from this chat.
- Default-branch behavior may differ from a particular installed Metrolist release.

## What the evidence does and does not establish

Auralis logged `SignInConfirmNotBotException` and YouTube's `LOGIN_REQUIRED` response with the text "Sign in to confirm that you're not a bot". This proves requests were challenged. It does not establish a permanent ban, a particular expiry time, a triggering request count, or that the IP alone explains the challenge. Session state, client profile, attestation, authentication and network conditions can all affect the response.

An extraction is not one HTTP request. NewPipe and InnerTubeX can each perform several requests internally. Therefore limits on extraction jobs alone cannot establish a strict request limit. Measure actual HTTP calls and constrain the total work across extraction, probing, relay recovery, prefetch and alternate recording matching.

Metrolist has code to handle failed verification and rate limiting. Its presence is evidence of recovery engineering, not proof that Metrolist avoids all challenges. Its client catalog includes developers' benchmark observations; those are not independently verified results on this user's network.

## Findings

### 1. Client selection is capability-aware

InnerTubeX's `ContentAwareFallbackStrategy` selects profiles based on authentication, available token providers, JavaScript runtime, content restrictions, transport and recorded client health. `PlayerClientDirector` tries the selected clients under a shared request/time budget; it can prefetch token work, so the whole pipeline is not strictly sequential.

The catalog marks many apparently useful profiles as broken, deprecated or probe-only. In particular, plain `VISIONOS` is probe-only in this revision. The legacy `VISIONOS_0_1` path can enter automatic selection when visitor data is available. This is not evidence that changing Auralis's VISIONOS version alone would solve playback.

Auralis currently filters NewPipe audio results by VISIONOS/IOS. Filtering returned results is not equivalent to planning only compatible client requests before extraction.

### 2. Session and token consistency matter

Android uses a WebView to execute BotGuard challenges. Its token provider deduplicates token mints, serializes minting, caches video results for at most ten minutes and the minter's expiry, and cools down failures for thirty seconds. Cached results must match visitor data and cookie state.

The client catalog distinguishes tokens bound to visitor data from tokens bound to a video. Player requests and media delivery can have different token requirements. A single token string attached indiscriminately to requests would be incorrect.

Desktop has a page-bound JavaScript runtime and a local token-provider sidecar path. The sidecar has a pinned version and checksum verification; configured provider URLs must use loopback HTTP. This demonstrates a desktop design exists, not that its runtime can be copied into Auralis without integration work.

Auralis's `refreshBlocking` currently invalidates a stream and clears the shared NewPipe visitor session on every refresh. That broad reset can interfere with other concurrent songs/lookups. Session changes should have a generation and a specific reason; stale work must not publish into a newer session.

### 3. Verification failure is not the same as rate limiting

InnerTubeX classifies a generic `LOGIN_REQUIRED` as a token-related client failure while checking for confirmed age gates separately. Metrolist also distinguishes media CDN refusal, expired URLs, attestation problems, transport/network failures and decoder problems.

Its recovery policy starts with two request credits, three attempts and a thirty-second deadline. Those are recovery-layer limits, not a guarantee of only two HTTP calls in the entire pipeline. HTTP 429 gets no immediate playback recovery. Expired URLs refresh without declaring the client unhealthy; token failures request token repair; decoder failures rebuild the renderer.

Auralis currently uses a five-minute in-memory YouTube pause and several independent retry loops. Treating a bot challenge as a reason to stop a retry storm is useful, but calling every challenge an IP ban is too coarse. Preserve the actual reason and endpoint in diagnostics.

### 4. Cooldowns are coordinated and scoped

`ExtractionCooldown` checks requests to YouTube endpoints, honors HTTP 429 `Retry-After` (seconds or HTTP date), and uses an exponential delay starting at five seconds up to sixty seconds for repeated transient failures. It can terminate a client fallback loop through a cancellation signal. The current request's response is closed when the cooldown aborts it.

`ClientHealthTracker` persists client outcomes, scoped by account, network and playback capabilities. Media-forbidden and repeated failures impose penalties/cooldowns. Successful actual playback can restore a client's health; merely obtaining a player response is insufficient.

Auralis's process-local pause disappears on restart. A robust design should persist a bounded retry deadline, with the reason and network scope, and permit only one trial request when it expires. A network change should invalidate network-bound state without pretending every connectivity event proves that an IP changed.

### 5. Cache and concurrency correctness reduce both failures and traffic

Metrolist keys resolved resources by video, content hints, quality, session fingerprint, network scope and excluded profiles. Concurrent identical requests share work. A refresh cannot join an older ordinary lookup; generation checks prevent older work from replacing refreshed results. Unneeded work is canceled when no callers remain.

Auralis already shares audio lookups by target ID. Its new disk cache is still keyed by target ID alone, and optional video extraction is separate. Improve the existing mechanism rather than describing deduplication as entirely missing. Disk reuse needs quality/profile and network/session validity checks, expiry safety margins, bounded size and invalidation after a rejected read. Do not persist account cookies in the stream-link file.

### 6. Initial success does not establish sustained playback

The InnerTubeX catalog records cases where an initial range returned HTTP 206 but the next range failed. Metrolist supports direct, HLS and SABR transports and renews some tokenless streams proactively. The renewal observer starts after twenty seconds of progress, makes at most three attempts with five-second delays, and keeps the current stream when preparation fails. Other recovery rules use a forty-five-second threshold.

Metrolist also remembers rejected web tokens for thirty minutes and can prefer a supported tokenless fallback while those tokens remain unhealthy. Blindly regenerating the same rejected token is not its only strategy.

Auralis's local relay already reads bounded ranges and refreshes rejected URLs. Its retry policy and resolver policy need one shared budget. Equal content length alone is not a sufficient identity check for replacing media bytes: retain format/itag, codec, source identity and range compatibility, or rebuild playback at the current timestamp.

### 7. Piped/Invidious are optional availability aids

The inspected Metrolist YouTube playback path uses InnerTubeX and direct/HLS/SABR recovery; no Piped/Invidious dependency was found in that path. Such servers can themselves be unavailable or challenged. A public fallback should be bounded, health-checked and explicit about sending video IDs to another operator. It should not delay every song by trying a long list of dead hosts.

## Recommended Auralis architecture and order

1. **Measure and bound traffic.** Add one coordinator for actual player/bootstrap/token calls and recovery work. Foreground audio gets priority. Deduplicate audio and companion-video metadata where possible. Cancel abandoned work. Start conservatively with one active extraction and a bounded queue; tune only from observed latency and call counts.
2. **Classify failures and stop repeated failures.** Separate 429, bot challenge, age/account gate, expired URL, CDN 403, timeout and decoder error. Respect `Retry-After`, persist network-scoped cooldowns, and permit one trial after a pause. A media 403 must not automatically become a global YouTube block or a blanket blacklist of a shared CDN hostname.
3. **Fix session/cache lifecycle.** Remove unconditional global session resets on URL refresh. Track generations. Scope cached links and client health properly. Preserve the current playing stream if preparing a replacement fails. Remove restored-song network preloading and optional YouTube video requests until playback is requested.
4. **Add compatible verification and client planning.** Evaluate a maintained extractor with capability-aware tokens and stable visitor context. Prototype Windows integration independently: browser runtime, token lifecycle, cancellation, shutdown, updates and logging redaction all need to work. Metrolist and InnerTubeX carry GPLv3 license text; review license obligations before incorporating their code or dependency.
5. **Add transport-aware recovery.** Direct audio first where validated; introduce HLS/SABR only with a functioning media adapter and sustained-playback tests. A SABR result is not just a URL to hand to VLC. Its protocol, token renewal and delivery need support.
6. **Treat public proxies as optional.** Use a small bounded pool, short failure budgets, health cooldowns and explicit operational ownership. Do not promise a fallback is available until complete media playback succeeds.

## Acceptance criteria

- Restoring an idle queue causes zero stream-resolution or companion-video extraction calls.
- Ten concurrent requests for the same resource share one extraction.
- Skipping rapidly cannot leave obsolete lookup jobs running indefinitely or publish a stale result.
- A 429 response stops new applicable requests and honors both Retry-After formats, including after restart.
- A bot challenge stops blind retries while retaining a distinct error category from confirmed rate limiting.
- Switching networks invalidates applicable cached resources; an old response cannot overwrite a new one.
- Quality/profile changes cannot reuse an incompatible disk entry.
- Token refresh is deduplicated, expiry-aware and tied to the matching session/video binding.
- An expired URL does not penalize a working client; a failed refresh preserves a working current stream.
- Validate full songs, seeks in both directions and later byte ranges on a small fixed manual test set. Start with deterministic fake-server tests; avoid repeatedly probing a challenged network.
- Logs contain request counts, timings, source/profile and classified failures; never cookies, token values or full signed URLs.

## Source links

Metrolist sources pinned to inspected revision:

- [Extraction cooldown](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/commonMain/kotlin/com/metrolist/shared/youtube/playback/ExtractionCooldown.kt)
- [Resolver and its cache/flight generations](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/commonMain/kotlin/com/metrolist/shared/youtube/playback/DefaultStreamResolver.kt)
- [Client health](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/commonMain/kotlin/com/metrolist/shared/youtube/strategy/ClientHealthTracker.kt)
- [Android token provider](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/androidMain/kotlin/com/metrolist/shared/youtube/playback/AndroidTokenProvider.kt)
- [Desktop token provider](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/desktopMain/kotlin/com/metrolist/shared/youtube/playback/DesktopTokenProvider.kt)
- [Recovery policy](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/commonMain/kotlin/com/metrolist/shared/playback/PlaybackRecoveryPolicy.kt)
- [Tokenless renewal](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/commonMain/kotlin/com/metrolist/shared/playback/TokenlessStreamRenewal.kt)
- [Resolver regression tests](https://github.com/metrolistgroup/metrolist/blob/667b8bc73b608004113e45fbc04b481eb80db720/shared/src/commonTest/kotlin/com/metrolist/shared/youtube/playback/DefaultStreamResolverTest.kt)

InnerTubeX v0.11.7 sources:

- [Client catalog](https://github.com/MetrolistGroup/innertubex/blob/e4fb0003ce092beb71f3de464c661bf9f3f54c7f/src/commonMain/kotlin/com/metrolist/innertubex/extraction/strategy/PlaybackClientCatalog.kt)
- [Capability-aware selection](https://github.com/MetrolistGroup/innertubex/blob/e4fb0003ce092beb71f3de464c661bf9f3f54c7f/src/commonMain/kotlin/com/metrolist/innertubex/extraction/strategy/ContentAwareFallbackStrategy.kt)
- [Player requests and token binding](https://github.com/MetrolistGroup/innertubex/blob/e4fb0003ce092beb71f3de464c661bf9f3f54c7f/src/commonMain/kotlin/com/metrolist/innertubex/extraction/PlayerClientDirector.kt)
- [Playability classification](https://github.com/MetrolistGroup/innertubex/blob/e4fb0003ce092beb71f3de464c661bf9f3f54c7f/src/commonMain/kotlin/com/metrolist/innertubex/extraction/PlayabilityFailure.kt)
