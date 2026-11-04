# Auralis Connect over WebSockets

Status: built and tested locally on branch `connect-websocket`. Nothing is deployed, the apps don't
use it yet, and no Cloudflare or Oracle account has been touched. Listen Together is out of scope and
stays on Firebase.

## Decision

Use **Cloudflare Workers + Durable Objects**, one object per account, on the free plan.

The engine already talks through `ConnectBackend`, and a Durable Object is the same shape as what
Firestore gave it: one place that applies each account's writes in order, with durable storage.
`ConnectRealtimeBackend` implements that interface, so reconciliation, handoffs, leases and intents
carry over unchanged. Oracle stays the escape hatch if free-tier message counting turns out worse
than documented: `connect-server/src/core.js` holds every rule and has no Cloudflare code in it, and
the protocol in `connect-server/PROTOCOL.md` is plain JSON.

## Cloudflare and Oracle compared

Checked 2026-11-02 against official documentation.

| | Cloudflare free | Oracle Always Free |
| --- | --- | --- |
| Compute | Per account actor; 128 MB; hibernates | 2 OCPU, 12 GB Arm (halved from 4/24 in June 2026, without announcement) |
| Daily caps | 100k requests, 100k row writes, 5M row reads, 13k GB-s; reset 05:30 IST | None per message; 10 TB outbound a month |
| Over the limit | Errors, no charge | Slower |
| Idle behaviour | Hibernation keeps sockets open and is free | May be reclaimed if CPU, network and memory all stay under 20% for 7 days, which a light server would |
| Availability | Managed, no servers | One VM in one region; "out of host capacity" is a documented failure |
| Failures to plan for | Hitting a daily cap | Reclaim, account action, VM or disk loss, patching, TLS, DDoS |
| Isolation | Each account in its own object | One process, built by us |
| Needs a domain | No (`workers.dev`) | Yes, or a tunnel, for WSS |
| Surprise charges | None on free. The paid plan has no spending cap | None unless upgraded; upgrading exposes overage |
| Cost at 10,000 average devices | About $12–23 a month (paid plan) | ₹0 if it stays up |

Oracle's real advantage is no message quota. It loses on Auralis's priorities because a single
self-run VM is a reliability and maintenance burden from day one, while Cloudflare's limit only
matters at a scale Auralis hasn't reached and fails harmlessly: local playback doesn't notice.

## Capacity

Devices means devices **connected at the same moment, averaged over the day**, not installs. Most
installs are connected for a small share of the day, and only while open or playing.

Assumed per connected device-hour: 2 connections (hourly token expiry plus one network change), 50
messages (one in ten devices has a second device reporting every 15 s; solo devices report every 5
minutes), 4 storage rows. These are estimates, not measurements of real listeners.

| Daily free limit | Per device-day | Devices that fit |
| --- | --- | --- |
| DO requests, counting messages 20:1 as documented | 108 | **925** |
| Row writes (100,000) | 96 | 1,040 |
| Worker requests (100,000) | 48 | 2,080 |
| Duration (13,000 GB-s) | 1.5 GB-s | 8,600 |
| DO requests, if messages count 1:1 on free | 1,248 | **80** |

The last row is the main unknown. The pricing page documents 20:1, but a recent community report
describes hitting the free limit early and went unanswered; I could not read it in full. It has to be
measured on a real account before committing.

| Scenario | Messages a day | Requests | Rows | Result |
| --- | --- | --- | --- | --- |
| 100 devices | 120k | 11k (11%) | 9.6k (10%) | Comfortably free |
| 1,000 devices | 1.2M | 108k (108%) | 96k (96%) | Just over: fails late in the day. Longer token refresh or a quieter multi-device report would bring it under |
| 10,000 devices | 12M | 1.08M | 960k | Not free. About $12–23 a month on the paid plan, or Oracle |

For comparison, Firestore's 20,000 writes a day is used up by about 2.3 released devices running all
day. Presence stops being writes at all: an open socket is the heartbeat, using WebSocket pings,
which Cloudflare documents as uncharged.

Measured on this PC, real Worker and Durable Object code in one local `workerd` process, with the load
generator on the same machine:

| Sockets | Writes a second | Result |
| --- | --- | --- |
| 2,000 | 1,287 (target 2,000) | All stayed open, none failed, median 19 ms, p99 406 ms |
| 5,000 | 63 (the plan's rate) | All stayed open, none failed, median 17 ms, p99 110 ms |
| 5,000 | 253 (target 4,000, about 57 times the plan) | 90% dropped with abnormal closure 1006, 1,049 writes failed |

The last row is deliberate overload on one laptop process, and its exact cause wasn't isolated.
Cloudflare spreads accounts across objects, so it doesn't predict behaviour there, and none of this
tests Cloudflare's network or real free-tier accounting.

## What carries over, and what changes

Unchanged: `ConnectEngine`, `ConnectModels`, the reconciliation rules, leases, epochs, handoffs,
commands, the offline control store, and the 102 existing engine tests. Local-first playback is
untouched, because `intercept` never involved the backend.

| Firestore gave us | Now provided by |
| --- | --- |
| Transaction | `update` with a base version; the server refuses a stale base and returns newer state |
| Security rules | `core.js`, ported rule by rule, plus a device can only write as itself |
| Snapshot listeners | Server pushes `session` and `devices` frames |
| Durable documents | SQLite in the object, with socket attachments for unsaved state |
| Offline detection | Socket state, which is immediate |
| Server timestamps | `now` on every reply, sampled by `ServerClock` |
| Heartbeat writes | Being connected |

The engine gained two small things: each backend sets its own control patience (1.5 s here, against
Firestore's 4 s), and an output restates its real state after any reconnect, so a server that lost
reports recovers by itself.

## What survives a server restart

A WebSocket is neither reliable delivery nor storage, so each is handled explicitly.

- **Written at once:** ownership changes and queues. These fence everything else.
- **Written when the last device leaves, or every 64 versions:** the session and intent. Until then
  they ride on the writer's socket attachment, which survives hibernation and costs no storage write.
- **Lost in a crash:** at most the last few reports. The output repeats them on reconnect.
- **Versions** jump past anything possibly handed out after a cold start, so clients never reject the
  recovered session as stale.

This was forced by the numbers: saving every song change would cap the free plan near 130 devices.

## Reliability

| Failure | Handling |
| --- | --- |
| Server down | Calls fail at once, not after a timeout. Reconnect with full-jitter backoff, 1–60 s |
| Silent hang | 3 s reply timeout kills the socket; 45 s WebSocket pings catch an idle one |
| Lost reply | The caller sees a failure; the push that follows corrects state |
| Duplicate or delayed frame | Refused: its base version is stale |
| Out of order | Clients ignore older versions, as they always have |
| Duplicate command | Commands keep their ids and `applied` set |
| Network change | `nudge()` reconnects at once |
| Token expiry | Close 4401, refresh, reconnect |
| Quota exhausted | Connect unavailable until 05:30 IST; playback unaffected |
| Flood | Per-socket token bucket, then close; each account is its own object, so others aren't touched |
| Overload | 16 sockets per account, 64 KB frames, 4 close codes with long pauses |

## Protecting playback

`intercept` doesn't suspend and doesn't touch the transport, so no backend failure can reach a local
control. With no socket every call fails immediately. The only wait left is a song deliberately sent
to another device, 1.5 s here instead of 4, and only if the socket looks healthy but goes silent.
Remote state can't silence a device while it is offline, because reconciliation only runs when
connected. Tests freeze the network mid-session and assert local controls act within 50 ms.

## Security

- The Worker verifies Firebase ID tokens itself against Google's published keys, on connecting only.
  No Firestore read is involved.
- `alg` is pinned to RS256, and `aud`, `iss`, `exp`, `iat`, `auth_time` and `sub` are checked.
  Anonymous Listen Together guests are refused.
- The account comes only from the verified token. Client-sent identity headers are never forwarded.
- Tokens go in the `Authorization` header, not the URL, so they stay out of logs.
- A socket closes at its token's expiry on its next message. Replay is prevented by version checks
  and WSS.
- The test-only login needs a variable absent from `wrangler.toml` and refuses non-local hosts.
- Open questions: a revoked account keeps a socket for up to an hour, and there's no per-IP limit on
  bad tokens yet. Those cost Worker requests only and never reach an account.

## Old and new versions

There is no bridge. `connectTransportFor` keeps an account on Firestore while any device seen in the
last 14 days has an older version, so nobody is cut off, and moves it once all have updated. Updated
apps need one device-list read at startup, and must keep registering on Firestore so older devices
stay visible. There is no live Firestore fallback: if the WebSocket server is down, Connect waits,
which is what stops the quota problem coming back. Firestore code, `connect/intent` and its rules
stay until no older device remains. Accounts still on Firestore benefit from the heartbeat fixes and
`connect/intent`, so those are still worth releasing.

Known gap: an older device reopened after an account has moved isn't seen until the others next start.

## LAN (not built)

Worth doing later, and optional. Discover with mDNS, with each device's account key handed out over
the authenticated socket; authenticate with HMAC over a nonce on a TLS-pinned local socket. Send the
same frames, so version checks dedupe across paths. The owner remains the source of truth, and LAN
only activates after the cloud handshake has shared keys, so nothing unauthorised can pair and no
attempt can delay playback.

## Rollout

1. Land the branch with the flag off, so nothing changes for anyone.
2. Create a Cloudflare account on the free plan, with no card, and deploy to a staging name.
3. Measure real free-tier counting for a day with test devices. This settles the 20:1 question.
4. Wire `ConnectRealtimeBackend` into Android and Windows behind the flag: Firebase tokens, network
   callbacks, and foreground and background lifetime.
5. Test on real Android and Windows devices.
6. Enable for your own account only, then a small share, watching Cloudflare's usage page.
7. Enable by default for updated accounts.
8. Retire Firestore Connect and its rules once no older devices remain.

Rollback at any step is turning the flag off: devices return to Firestore on next start, with state
rebuilt from the output.

## Open decisions

1. Create a Cloudflare account, and which email owns it.
2. How the flag is delivered. It needs a source that doesn't depend on the WebSocket server.
3. Whether to accept the paid plan past about 1,000 average devices, or move to Oracle.
4. Current real-world active device count, which I can't see.
5. Whether to release the Firestore fixes first. I recommend yes: rollout takes weeks, and the quota
   can run out daily until then.
