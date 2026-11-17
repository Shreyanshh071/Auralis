# Review of pending Windows playback and player edits

Reviewed 2026-11-17: commits `7dce9516`, `bfbe9af2`, `9ff82837`, and the pending player expansion, shortcut, and frame-dump changes.

## Findings corrected during review

- **High — destructive frame output:** `dump-frames.ps1 -Out` recursively deleted an arbitrary supplied directory. It now refuses nonempty directories and directory links without deleting their contents.
- **High — bearer token logging:** header redaction removed the word `Bearer` but could retain the actual authorization token. Entire authorization and cookie header values are now redacted.
- **Medium — request-cap bypass:** redirects and retries were charged only after an application-level allowance check. Each actual control-plane attempt now atomically reserves a credit before it can send.
- **Medium — unaccounted matching requests:** asynchronous searches lost the thread-local intent tag; synchronous NewPipe search fallbacks lacked cancellation and the shared gate. Tags now travel on requests, fallback searches use cancellable gated calls, and playback matching observes the streaming cooldown.
- **Medium — long-song recovery:** the playback deadline was renewed only once, leaving later failures without a recovery allowance. Continued meaningful playback now renews it every 20 seconds.
- **Medium — failed crossfade:** a refused incoming song could leave the outgoing player alive, allowing its end event to advance a queue that should be held. Holding a failure now stops that outgoing player; a native-player regression test covers this path.
- **Medium — shared-session disruption:** two media 403s automatically reset the shared visitor session without establishing a session failure. Refresh no longer resets it. Audio results also check their session generation before publication; companion-video cache reads check session and network route.
- **Medium — persistence races:** a delayed cooldown writer could overwrite or delete a newer pause. Persistence now serializes and writes current state. Cache and cooldown initialization now publish their loaded flag after loading finishes.
- **Medium — unbounded memory links:** only the disk serialization capped the number of stream links. Memory and disk loading now enforce the entry cap, and oversized disk-cache files are rejected.
- **Low — cookie identity:** host-only and domain cookies with the same domain, path and name were retained twice. They now replace each other, and longer matching paths are sent first.
- **Low — stale test expectation:** a lyrics test prohibited all requests despite the existing public album-metadata fallback. It now prohibits authenticated Spotify searches and unvalidated lyrics fetches while allowing public metadata.

## Remaining limits

- The 16-request allowance is provisional. It is not a measured YouTube enforcement threshold.
- Stream links are signed bearer URLs persisted in the user's application-data folder. They are excluded from traffic logs, but anyone with access to that profile can read the cache until the URLs expire.
- The route fingerprint cannot detect every public-IP change behind the same local network. Playback can need a fresh link after such a change.
- Loopback relay URLs use random tokens. The relay remains accessible to local processes that possess a token; this is not an authentication boundary against a compromised local account.
- Extraction concurrency is bounded. Existing media reads, probes and interactive catalogue requests use separate paths; this is not a global cap on all HTTP traffic.
- A source review and local fixture tests do not establish that YouTube will accept live playback. No repeated live network probes are part of this review.
- Plan phases 4–7 (extractor/token integration, client health, alternate transports and public fallback services) remain separate future work.

## Verification and delivery

- Full core suite: 233 tests, zero failures, 15 skipped.
- Broad Windows run: 521 tests, two failures, six skipped. The new crossfade test exposed the additional cooldown/unknown-failure path; it passes after that path was corrected. The unrelated settings-hover animation test passes on isolated rerun. All three playback intent tests and the settings-hover tests passed together on the final source.
- All nine local playback-controls checks pass. Windows and Discord bot compilation pass.
- Frame-output guard exercised against a nonempty fixture: refused the directory and preserved its file. PowerShell parsing, diff whitespace checks and the repository's tracked Google-API-key scan pass.
- Remote `main` advanced with substantial overlapping work during review. Delivery uses `codex/playback-review`; integration with current remote `main` requires a separate merge review.

## Integration with main (2026-11-17)

- Merged the incoming Canvas, artist-credit, shortcut and Android changes. Three conflict files were resolved by preserving the reviewed failure policy and resize-aware player overlay. The incoming 256 KiB relay chunks and text-input shortcut routing are retained.
- Full merged core test suite passes. All 42 targeted Windows playback, Canvas, shortcut, resumed-player and search tests pass.
- The playback-controls process crashed inside native libVLC while the Windows tests ran alongside it. Its isolated rerun passes all nine checks. This intermittent native failure remains a risk; a successful rerun does not establish its cause or eliminate it.
