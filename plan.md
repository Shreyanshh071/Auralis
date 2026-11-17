# Auralis playback reliability implementation plan

Date: 2026-11-16. Primary target: Windows desktop playback.

**Status: plan, not completed implementation.** Experimental reliability edits already exist in the working tree. Audit and integrate them before implementing this plan. No guarantee of immunity from upstream challenges is possible; judge success by observed behavior and tests.

The previous root plan is preserved verbatim in [docs/windows-crash-investigation-plan.md](docs/windows-crash-investigation-plan.md). The supporting source audit and pinned upstream references are in [docs/playback-resilience-analysis.md](docs/playback-resilience-analysis.md).

## 1. Objective and boundaries

Build dependable playback that minimizes unnecessary requests, shares compatible work, preserves consistent sessions, and recovers according to the actual failure. When upstream access is unavailable, preserve queue/position and stop repeated requests.

Required outcomes:

- Restoring an idle queue causes zero stream or companion-video extraction requests.
- Concurrent callers share compatible resolution work; abandoned work is canceled.
- Every automatic retry has a reason, remaining request allowance and deadline.
- Rate limits, verification challenges, account/content restrictions, expired links, CDN refusals and decoder errors remain distinct.
- A failed replacement never destroys working playback.
- Tokens, sessions, resource caches and client health have correct scopes and expiry.
- Full-song playback, later byte ranges, seeking, pause/resume, downloads and Connect remain usable.
- Diagnostics measure actual HTTP traffic without exposing signed URLs or credentials.

Implement Windows first. Portable policy can later be reused on Android. Do not silently enable account-authenticated playback merely because the library is signed in. Public proxy support is supplementary, not a prerequisite for the main path.

## 2. Evidence, assumptions and existing work

### Confirmed evidence

- YouTube returned `SignInConfirmNotBotException` with `LOGIN_REQUIRED` and a bot-confirmation message. This proves a challenge, not its trigger, scope or expiry time.
- Each extraction can make several HTTP requests. Job counts cannot establish an HTTP request limit.
- Auralis already deduplicates audio lookups and has a bounded-range local relay.
- The resolver, relay, alternate-upload matcher and preload paths can each initiate additional work.
- Ordinary URL refresh currently resets the shared NewPipe session.
- The working tree includes a disk stream-link cache keyed by video ID, altered hedge behavior, a fixed in-memory backoff and sidebar video gating.
- `restoreQueue()` still contains delayed restored-song resolution. Inspect actual behavior instead of treating all earlier proposed work as shipped.
- The custom cookie jar uses permissive host suffix checks; audit matching before expanding session support.

### Upstream reference

Inspected Metrolist `667b8bc73b608004113e45fbc04b481eb80db720` and its pinned InnerTubeX v0.11.7, commit `e4fb0003ce092beb71f3de464c661bf9f3f54c7f`. These are source observations, not benchmarks on this network or promises about an installed Metrolist release.

Useful mechanisms: capability-aware client selection, visitor/video-bound token handling, deduplicated token mints, cache generations, scoped client health, endpoint cooldowns and typed transport recovery.

### Assumptions that must not become claims

- HTTP 403 does not by itself prove an IP ban or expired link.
- Generic LOGIN_REQUIRED does not by itself prove an age gate.
- A successful initial byte range does not establish sustained playback.
- A network-interface event does not prove the public IP changed.
- A profile listed upstream is not necessarily usable; many are broken or probe-only.
- Numeric limits below are provisional app policies, not known YouTube allowance thresholds.

## 3. Proposed architecture

```text
Playback/download intent and permitted prefetch
                    |
          PlaybackRequestCoordinator
          priority / ownership / cancellation
          deduplication / generations
                    |
           ResourceResolver interface
       local -> valid cache -> provider adapter
                    |
       SessionManager + optional TokenProvider
                    |
       RequestGate / HTTP budget / cooldown
                    |
             PlaybackResource
        local / direct / HLS / SABR
                    |
           Transport adapter -> VLC/relay
                    |
       typed failure -> bounded recovery
```

The recovery controller re-enters the same coordinator with remaining credits. It must not create an independent unlimited resolver loop. Downloads, alternate matching and optional videos obey applicable shared gates, with foreground audio priority.

### Data contracts

Names are provisional; extend existing abstractions where separate classes would add needless indirection.

| Contract | Required information |
| --- | --- |
| PlaybackIntent | Track/request ID, play intent, local/Connect owner, revision and resume position. |
| ResolutionKey | Video, quality, content/transport requirements, session/network generation and relevant excluded profiles. |
| PlaybackResource | Source/video identity, transport, URI, expiry, client/profile, itag/format/codec, length, generation, required headers and token references. |
| ResolutionResult | Resource, typed failure, canceled or deferred-until; avoid reducing everything to null. |
| RequestBudget | Monotonic deadline, actual HTTP credits, recovery transitions and category counters shared by nested work. |
| PlaybackFailure | Category, endpoint/operation, HTTP code, Retry-After, resource/profile identity and playback progress. |
| SessionSnapshot | Opaque identity, visitor context, authentication mode, generation and locale; secrets stored separately. |
| CooldownState | Scope, cause, retry deadline, backoff step and ownership of the single permitted trial. |

Keep required signed URL parameters intact for playback, but redact them in diagnostics. Do not persist raw cookies, authorization headers or tokens in the stream-link cache.

## 4. Phase 0 — baseline and instrumentation

Files: `NewPipeDownloader.kt`, `DesktopStreamResolver.kt`, `DesktopStreamProbe.kt`, `LocalStreamRelay.kt`, `DesktopPlaybackMatch.kt`, `ProbeStream.kt` and relevant call sites.

- [ ] Inventory extraction, refresh, probe, alternate recording, prefetch, download and video entry points.
- [ ] Record which experimental edits are compiled/installed and which remain incomplete. Review disk-cache and hedge changes before relying on them.
- [ ] Add one correlation ID for a playback intent and all related recovery work.
- [ ] Count actual HTTP calls by endpoint category, provider, reason, status and elapsed time. Distinguish extraction jobs from requests.
- [ ] Account for library retries and redirects at network-interceptor level where possible. Document any engine visibility limitations.
- [ ] Add dated, bounded diagnostics with cache hits, cancellation, failure category, retry reason and credits consumed.
- [ ] Redact cookie values, visitor data, authorization, tokens and signed URL query strings.
- [ ] Make live probes opt-in and bounded; optional video/alternative-search probing must be explicitly selected.
- [ ] Build controllable HTTP fixtures and injected clocks for later phases.

Exit: a deterministic scenario accounts for every emitted request. Save baseline latency and request totals. Do not repeatedly probe the challenged network to establish the baseline.

## 5. Phase 1 — intent-based work and concurrency

Files: controller, resolver, `HedgedStreamLookup.kt`, `SidebarMusicVideo.kt`, `NowPlayingSidebar.kt`, `Main.kt`, download and Connect integration.

- [ ] Remove delayed network resolution from `restoreQueue()`. Restore queue and position idle; local cache inspection is permitted.
- [ ] Drive optional video from observable playback intent and active track identity rather than UI composition or polling the clock alone.
- [ ] Resolve video only when enabled, visible, owned locally and needed after audio establishes playback. Reuse audio extraction metadata when the provider exposes video too.
- [ ] Limit next-song prefetch to one item after useful foreground progress; suspend during failures/cooldowns and cancel when no longer next.
- [ ] Give downloads a bounded lower-priority lane while observing shared provider/network limits.
- [ ] Start with one active extraction and no automatic duplicate hedges. Tune concurrency only from measured latency and request totals.
- [ ] Bound the waiting queue; remove superseded selections and promote existing preloads when they become foreground.
- [ ] Share compatible resolutions and track waiter ownership. Cancel orphaned work without canceling another caller's needed result.
- [ ] Ensure blocking NewPipe HTTP calls have deadlines and cancellation; coroutine cancellation alone is insufficient.
- [ ] Do not hold a gate permit while awaiting nested token/browser work that needs the same permit. Test recursive refresh and token setup for deadlocks.

Exit: ten concurrent identical callers use one extraction; idle restore and UI expand/collapse cause no extraction; rapid skips leave no obsolete work past its cancellation deadline; preload cannot starve foreground recovery.

## 6. Phase 2 — typed failures and coordinated recovery

Files: `YouTubeBackoff.kt`, downloader, resolver, probe, relay and controller. Introduce shared policy contracts only where needed.

| Evidence | Category | Response |
| --- | --- | --- |
| HTTP 429 | Rate limited | Stop applicable work, honor Retry-After, permit one later trial. |
| Explicit bot-confirmation response | Verification challenge | Stop blind replay; allow only bounded supported verification repair. |
| Generic LOGIN_REQUIRED | Authentication/verification required | Preserve reason; do not assume IP block or age gate. |
| Confirmed age/account restriction | Restricted content/account | Explain restriction; stop repeating the same anonymous path. |
| Known resource expiry | URL expired | Budgeted refresh without penalizing a working client. |
| Media 403 without expiry evidence | Media forbidden | Evaluate profile/token/session evidence; bounded recovery, no blanket CDN blacklist. |
| Timeout/transient 5xx | Transient transport | Delayed bounded retry; repeated failures trigger cooldown. |
| Offline/DNS/socket failure | Network unavailable | Preserve state and wait for usable connectivity. |
| Decoder/output failure | Player failure | Repair player/output; do not automatically re-extract. |
| Removed/private/unavailable media | Content unavailable | Specific error; alternate recording only when appropriate and budgeted. |

- [ ] Parse Retry-After seconds and HTTP dates, including malformed, overflow and clock-change cases.
- [ ] Use monotonic deadlines in-process and validated wall deadlines for restart persistence.
- [ ] Implement closed/waiting/single-trial states. Multiple callers cannot all retry when a cooldown expires.
- [ ] Persist versioned scope, cause, backoff step and bounded deadline without secrets. Corrupt state cannot permanently lock playback.
- [ ] Make search, downloads and optional video obey applicable cooldowns without stopping unrelated providers unnecessarily.
- [ ] Pass a shared recovery allowance through controller, resolver, relay and alternate matching; coalesce concurrent range refreshes.
- [ ] Stop queue auto-skipping on common network/verification failure and preserve position and user intent.
- [ ] Reset exhausted budgets only after meaningful playback progress or an explicit new intent, not each nested function call.

Initial tuning policy, to validate rather than assume safe:

- One active extraction; no speculative duplicate hedge.
- Thirty-second total foreground resolution/recovery deadline; background work gets a shorter configured deadline.
- At most two automatic recovery transitions before meaningful progress, counted across all nested paths.
- Transient backoff starts at five seconds and doubles to sixty seconds, with bounded jitter; server Retry-After may require a longer wait.
- Verification challenges have a separate conservative cooldown and a bounded repair policy.
- Establish the control-plane HTTP credit cap from the measured successful cold bootstrap/token path. It must include legitimate attestation calls; do not choose an arbitrary number that prevents verification.
- Sustained media chunks have a separate retry budget so control-plane caps cannot interrupt a healthy song. Media-triggered refresh transitions still consume the shared recovery allowance.

Exit: no storm across concurrent callers or restart; Retry-After honored; one trial after expiry; decoder errors cause no extractor call; ordinary CDN failures do not block all songs incorrectly.

## 7. Phase 3 — session and cache lifecycle

Files: resolver, downloader, `YouTubeSession.kt`, `YouTubeSessionSource.kt`, `ContentProxy.kt`, relay and settings.

- [ ] Remove unconditional global `resetSession()` from ordinary URL refresh. Rotate session only for a classified reason and invalidate dependent generations together.
- [ ] Keep anonymous playback visitor context distinct from signed-in library synchronization credentials.
- [ ] Replace permissive cookie host checks with standard domain/path/secure/expiry matching. Test suffix confusion, subdomains, expired cookies and insecure transport.
- [ ] Scope cache/pending results by quality, profile/transport, session generation and route/proxy generation.
- [ ] Detect known route changes without relying on a third-party public-IP polling service. Unknown scope after restart requires conservative reuse/validation.
- [ ] Refresh cannot join older normal work; older results cannot overwrite the refreshed cache. Check generations at publication.
- [ ] Version and bound disk entries, apply expiry safety margins, cap unknown-expiry lifetimes and write atomically off UI/audio threads.
- [ ] Handle truncated/corrupt cache files and migration without touching user library/account data.
- [ ] Do not repeatedly reload a rejected persisted URL. Invalidate it under the same shared refresh ownership.
- [ ] Keep Settings cache clearing coherent across provider/audio/video caches while preserving connections.

Exit: tests cover quality/session/network isolation, expiry/restart, corruption and late-result races. One song's URL refresh cannot silently reset another song's session.

## 8. Phase 4 — extractor and token-runtime integration spike

Deliver a pinned provider adapter and architecture decision record behind a development setting before changing the default.

- [ ] Evaluate InnerTubeX v0.11.7 compatibility with Kotlin/JVM, serialization, coroutines, HTTP engine and Windows packaging/architectures.
- [ ] Review GPLv3 obligations before incorporating upstream code/dependencies; compare extending NewPipe with adopting the maintained adapter.
- [ ] Declare real capabilities: visitor context, JavaScript cipher solving, token provider kinds and transports. Exclude profiles with unsatisfied requirements.
- [ ] Prototype anonymous playback with consistent visitor context first. Account-authenticated playback remains an explicit separate decision.
- [ ] Verify signature and n-parameter processing as well as token minting.
- [ ] Match token binding to player/media requirements: visitor-bound versus video-bound. Never attach one token indiscriminately everywhere.
- [ ] Deduplicate token mints, enforce expiry and session invalidation, serialize browser state, and cool down failed generation.
- [ ] Select a supported Windows browser runtime or pinned local helper from the spike results. Verify cold startup, offline failure, cancellation and clean shutdown.
- [ ] If using a helper, package/update it with checksum verification and architecture checks; bind its service to loopback, constrain callers where feasible and shut down only the process owned by Auralis.
- [ ] Propagate the original deadline through token work. Runtime failure cannot create endless helper restarts or secretly change authentication mode.
- [ ] Keep the legacy provider only as a bounded alternative when failure classification allows it; both providers share cooldowns and budgets.

Go/no-go: adapter builds/packages, cancels cleanly and completes controlled full-song/seek playback with correctly scoped resources. Unsupported token requirements must disable the profile; token generation success alone is insufficient.

## 9. Phase 5 — client capability selection and health

- [ ] Maintain a small catalog of supported tested profiles with authentication, token, content, transport and format requirements.
- [ ] Order candidates using capabilities, content hints and scoped observed health. Do not simply append more client names.
- [ ] Persist bounded health records by network/session/account mode with schema, expiry and classified failures.
- [ ] Reward meaningful actual playback, not just a successful player response.
- [ ] Do not penalize profiles for URL expiry, unavailable content or failures from obsolete generations.
- [ ] Keep broken/probe-only profiles outside normal selection; manual probes remain explicit and budgeted.
- [ ] Manual overrides still obey network cooldowns and request limits.

Exit: unhealthy profiles are demoted only in the relevant scope, alternatives remain usable, and selection stops at the shared deadline/allowance without exhausting every profile.

## 10. Phase 6 — sustained playback and transport recovery

Files: relay, probe, `VlcAudioPlayer.kt`, controller and resource/transport adapters.

- [ ] Carry source identity, format/itag, codec and byte-range metadata to the relay. Equal file length alone cannot establish interchangeable media bytes.
- [ ] Replace URLs in-place only with compatible media identity; otherwise rebuild at the captured timestamp under a current-intent generation guard.
- [ ] Coalesce failed-block refreshes and cancel obsolete media prefetches. Keep compatible useful cached blocks.
- [ ] Prepare replacement while retaining current playback; commit only when the track/resource/ownership still matches. Ignore late replacements after skip or remote handoff.
- [ ] Schedule proactive renewal only for profiles with demonstrated short lifetime/expiry constraints; deduplicate and budget it.
- [ ] Validate direct audio first. Add HLS through a proper manifest/header/expiry adapter with seeking and live-specific tests.
- [ ] SABR requires protocol parsing, media delivery, attestation and cancellation support. A SABR bootstrap result is not a URL that VLC can simply play.
- [ ] Keep crossfade/native resource ownership compatible with the separate crash plan.

Exit: whole songs, later ranges, seeking, expiry mid-song, failed renewal and rapid changes pass without mixed-format bytes, position loss or native lifecycle regressions.

## 11. Phase 7 — optional Piped/Invidious availability aids

- [ ] Keep a small configurable provider pool with documented operators and a clear notice that requested video IDs and connection metadata reach them.
- [ ] Validate actual audio delivery, not just HTTP 200 metadata. Validate schemes and provider redirect handling.
- [ ] Apply short total deadlines, small attempt allowances, cancellation and health cooldowns; never scan a long dead-host list per song.
- [ ] Preserve video/recording identity and keep proxy health separate from direct YouTube cooldown.
- [ ] Never send Google account cookies to public fallback operators.
- [ ] Test adapters with local fixtures first. If no real endpoint works, mark fallback unavailable/disabled rather than claim recovered playback.

Exit: a configured healthy provider completes sustained playback within the shared recovery budget; an unavailable pool fails promptly without exhausting the queue.

## 12. User-visible behavior

- Preserve queue, selected track and position while deferred; do not appear to play while stalled at zero.
- Show accurate messages for rate limiting, verification, offline state, restricted content and source failure.
- Any shown timer is the next app retry time, not a promise that an upstream restriction will have ended.
- Retry buttons re-enter the same gate; rapid clicks cannot bypass Retry-After or multiply pending work.
- Downloads/local media remain usable during upstream cooldowns.
- Connect handoff remains authoritative; remote queue updates cannot accidentally start local audio/video.
- Development diagnostics show counts, timings, profile/provider and classified failures without secrets; protocol detail stays out of ordinary player UI.

## 13. Test matrix

Use injected clocks, controllable HTTP fixtures and meaningful concurrency tests. Avoid tests that only repeat implementation lines.

| Scenario | Expected result |
| --- | --- |
| Restore idle queue, sidebar enabled, UI expand/collapse | Zero stream/video extraction before playback intent. |
| Ten identical concurrent callers | One resolution; independent waiter cancellation works. |
| Rapid skips and preload promotion | Only current intent commits; obsolete work ends by deadline. |
| HTTP 429, seconds/date Retry-After | Applicable requests stop; persistence works; one later trial. |
| Bot challenge, generic login requirement, age gate | Distinct categories and bounded responses. |
| Clock jump or corrupted cooldown file | No busy retries or indefinite accidental lockout. |
| Session/network/quality change during work | Old results cannot enter the new cache scope. |
| Cache restart, expiry and corruption | Safe migration, correct reuse and bounded invalidation. |
| Concurrent failed relay blocks | One refresh and a shared recovery allowance. |
| Same-length incompatible format replacement | No byte splice; timestamped rebuild if appropriate. |
| First range OK, later range refused | Classified bounded recovery/failure. |
| Token mismatch/expiry/helper exit | Correct invalidation, no leakage, no restart storm. |
| Failed proactive replacement | Current working stream retained. |
| Offline downloaded/local track | No stream-resolution request. |
| Connect remote playback | No unintended local playback/video work. |
| All public fallbacks fail | Deadline respected and queue retained. |
| Pause/resume/crossfade/output switching | Existing media behavior remains correct. |

Relevant existing checks: core stream-probe and hedge tests; playback match/session; downloads; Connect; repeat/crossfade; song notifications. Update hedge expectations to the chosen default instead of preserving a contradictory optimization.

Before completion, confirm task names and run:

```powershell
.\gradlew.bat :windows-core:desktopTest :windows:test
.\gradlew.bat :windows:verifyPlaybackControls
.\scripts\update-windows-app.ps1
```

Diagnose failed checks before weakening them. Record exact independently established flaky failures and their scope; do not silently skip tests and describe the build as verified.

Live checks follow deterministic tests once requests are accepted. Use a small fixed set: ordinary song, long song, explicit non-age-gated song, unavailable/restricted item as an expected failure, and live only if supported. Validate full songs, later ranges and bidirectional seeks. Measure startup latency, actual request totals, cache hits and recovery outcome. Stop live testing on rate-limit/challenge evidence and continue with fixtures.

## 14. Rollout and rollback

1. Deliver request accounting, idle-work removal, shared cancellation and typed cooldown handling as the first meaningful increment.
2. Add session/cache correctness and migration next; preserve user/account/library data.
3. Introduce the pinned extractor/token runtime behind a development setting and compare a small recorded test matrix.
4. Enable only profiles/transports that pass their own sustained-playback gates. HLS, SABR and public fallback each have separate completion criteria.
5. For every Windows app-code delivery, use `scripts/update-windows-app.ps1` to build/update the installed app and runtime. Preserve app identity, production Connect and saved connections. Batch changes to avoid repeated cold-launch traffic.
6. Keep the previous distributable/checkpoint and compatible configuration. Rollback replaces app/runtime/provider behavior, not user library or account state. Preserve valid cooldowns where schemas allow it.
7. If Android changes, run `:app:assembleRelease` and refresh root `Auralis-Release.apk` from `android/app/build/outputs/apk/release/app-release.apk`, preserving signing/app ID and installed data.

Editing this plan and its analysis alone requires no app rebuild.

## 15. Reviewable implementation increments

| Increment | Deliverable | Gate |
| --- | --- | --- |
| A | HTTP accounting and safe diagnostics | Fixture request counts verified. |
| B | Intent gating and shared cancellation | Idle/dedup/skip tests pass. |
| C | Typed failures, shared budgets, durable cooldown | No storms across restart/concurrency. |
| D | Session generations and cache migration | Network/quality/stale-result tests pass. |
| E | Extractor/runtime spike and decision record | Packages and completes controlled playback. |
| F | Token lifecycle and profile health | Binding, scoping and bounded fallback verified. |
| G | Relay and transport renewal | Whole songs, seeks and replacement tests pass. |
| H | Optional public providers | Healthy and unavailable cases validated. |
| I | Installed-build regression and rollout | Test evidence and measured outcomes recorded. |

## 16. Risks and decisions to close

- Runtime/packaging size and supported Windows architecture for browser/token helper: decide in Phase 4 from a buildable spike.
- Dependency/license compatibility: resolve before incorporating upstream code.
- Session persistence versus conservative cache reuse after restart: define supported anonymous state and credentials storage explicitly.
- Network identity cannot always be known: use route/proxy generations and conservative invalidation rather than claiming exact IP tracking.
- Background downloads versus foreground priority: enforce fairness without starving either; verify under cancellation and sleep/resume.
- Upstream APIs change: pin dependencies, keep adapters small, maintain fixtures and a bounded manual compatibility matrix.
- Public fallback availability cannot be guaranteed: remain optional with honest operational status.
- Success cannot mean avoiding every service-side restriction: it means correct handling with measured reliability and bounded traffic.

## 17. Definition of done

- [ ] Existing experimental changes reviewed and integrated or explicitly superseded.
- [ ] Idle extraction eliminated and actual HTTP traffic observable/bounded.
- [ ] Shared work cancels correctly; stale results cannot commit.
- [ ] Typed failures and cooldowns behave correctly across concurrency/restart.
- [ ] Cache, session, quality and network generations are coherent.
- [ ] Each enabled profile has working token/runtime prerequisites and clean teardown.
- [ ] Sustained playback, seeking and recovery verified for every enabled transport.
- [ ] Downloads, crossfade and Connect regressions checked.
- [ ] Optional proxy availability represented honestly and credentials protected.
- [ ] Relevant checks pass; unresolved failures and upstream limitations documented.
- [ ] Installed Windows app/runtime updated using the required script.
- [ ] Measured results recorded without claiming permanent prevention of YouTube challenges.
