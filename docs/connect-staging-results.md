# Staging results, 2026-11-02

Worker `auralis-connect-staging` on the free plan, tested from one Windows PC in India, served through
Cloudflare's Singapore edge. Quota exhaustion was not run.

## Confirmed

| Check | Result |
| --- | --- |
| Deployment | Worked, after the `workers.dev` certificate appeared |
| Missing token, wrong secret, forged token | All refused with 401 |
| Wrong method, unknown path | 426 and 404 |
| 100 connections | All opened |
| 2,000 messages | All answered, none refused, 22 s |
| Round trip while connected | Median 95 ms, 95th percentile 101 ms, worst 103 ms |
| Round trip after 15 s idle | 94–103 ms, so waking adds no visible delay |
| First state after connecting | 338 ms |
| Short `check` runs | Usually 70–110 ms, with spikes to 1.4 s |

## Problems found

**Fixed: account lockout.** A socket told to close stayed counted against the 16-socket limit until its
client answered, so a device reconnecting often could lock its account out. Reproduced in a test and
fixed in `6a6e0159`, on staging as well.

**Open: a brand-new device can briefly be refused.** One socket from an abruptly ended client held a
slot for at least 3 minutes after the fix, and was gone by 8 minutes. A reconnecting device replaces
its own socket and accounts rarely have 16 devices, so this is minor.

**Reduced, still open: connections drop.** The planned one-hour idle test first could not complete.

| Socket | Dropped after |
| --- | --- |
| Staging, pings every 45 s, first run | About 3 min |
| Staging, no pings | 303 s |
| Staging, pings every 20 s | 422 s |
| Staging, pings every 45 s | 451 s |
| Staging, pings every 45 s and a message each minute | 452 s |
| Control, non-Cloudflare echo server, pings every 30 s | 289 s |
| Staging, pings every 30 s, while `wrangler tail` ran | 32 s and 62 s |

All closed with 1006, meaning no closing handshake. The non-Cloudflare control dropped too, and active
traffic didn't help, which points away from idle timeouts and towards this PC's network, but one
control doesn't prove that. The fast drops during `wrangler tail` are unexplained, and the tail
captured no events, so it told us nothing. The cause is unknown.

## Follow-up on a phone hotspot

Same PC, through Cloudflare's Delhi edge instead of Singapore.

| Socket, 15 minutes | Result |
| --- | --- |
| Control, non-Cloudflare echo server | Stayed up, all 19 pings answered |
| Staging, pings every 45 s | Two sockets dropped at 47 and 48 s, no pings answered |
| Staging, no pings | Dropped at 99 s |

So the network was not the cause. Local `workerd` kept pinged sockets open with no problem, and staging
reproduced the drop right after a WebSocket ping.

`setWebSocketAutoResponse` was then added, in `f3e6fdeb`. Same test with and without it:

| Staging version | Protocol pings, 45 s | Text pings, 45 s | Text pings, 20 s | No keepalive |
| --- | --- | --- | --- | --- |
| With the line, 8 min | Stayed up | Stayed up | Stayed up | Stayed up |
| Without the line, 4 min | Dropped at 226 s, on a ping | Not run | Not run | Stayed up |
| With the line, 60 min | Dropped at 31.5 min | Dropped at 24 min | Not run | Stayed up all hour |

The line is a large improvement, from drops within about 1–7 minutes to about 24–32. It isn't a complete
fix. Those are few samples, and why sockets with keepalives still drop while the idle one didn't is
unknown. All closes were 1006, with no closing handshake. Codex was also using this connection during
the hour. An explanation from Cloudflare's side wasn't found: `wrangler tail` produced nothing useful.

## Effect on capacity

Each reconnect costs a Worker request and a Durable Object request. With a drop about every 25 minutes
that is about 3 connections a device-hour including token refresh, not the 2 assumed, so the 20:1
figure falls from about 925 average devices to about 750. Reconnecting recovers by itself and playback
isn't involved. Real-device drop rates are unknown.

## Cloudflare's usage figures for the day

From the dashboard, after all of the above.

| Metric | Value | Free daily limit | Share |
| --- | --- | --- | --- |
| Worker invocations | 249 | 100,000 | 0.2% |
| Durable Object requests | 2,550 | 100,000 | 2.6% |
| Duration | 148 GB-s | 13,000 | 1.1% |
| SQL rows read | 30 | 5,000,000 | Under 0.1% |
| SQL rows written | 3 | 100,000 | Under 0.1% |
| Stored | 16.38 kB | 5 GB | Under 0.1% |

Also: Worker errors 0, Durable Object errors 231, median CPU 1.55 ms, 3 calls to Google's key server.

What these show:

- **Requests match every message counted one for one.** About 2,130 messages, 239 connections and
  roughly as many close events come to about 2,600, close to the 2,550 shown. At 20:1 the figure would
  be nearer 350. The dashboard may show raw counts with the ratio applied only to billing, so this
  doesn't prove how the free limit is enforced, but it removes any comfort that 20:1 applies.
- **Duration is much higher than planned.** 148 GB-s is about 20 minutes of the object being awake,
  about 0.06 GB-s a request, against the 0.00125 assumed. If each isolated wake costs seconds rather
  than milliseconds, Duration could become the tightest free limit. This needs its own test.
- **Row writes were low** but today's run was almost all reads, so the storage policy is not yet proven
  under real session writes.
- **The 231 Durable Object errors are unexplained.** They are close to the number of connections, so
  they may be sockets ending abnormally, but that isn't confirmed.

## Controlled follow-up

Staging-only diagnostics reported whether the object had stayed in memory.

| Idle gap | Object |
| --- | --- |
| 1–8 s | Stayed in memory |
| 10–90 s | Gone from memory, every time |

The code has no timers, alarms, `waitUntil`, standard `accept()` or outbound sockets. All 20 messages a
minute apart reached a fresh instance and were answered, median 96 ms. An earlier run of the same test
stopped when one socket looked open but gave no reply for 8 s. That didn't recur and isn't explained.

Dashboard change across these tests:

| Metric | Before | After | Change |
| --- | --- | --- | --- |
| Durable Object requests | 2,550 | 2,640 | +90 |
| Worker invocations | 249 | 277 | +28 |
| Duration | 148 GB-s | 148 GB-s | None visible |
| Errors | 231 | 257 | +26 |
| Rows read | 30 | 65 | +35 |
| Rows written | 3 | 3 | None |

Findings:

- **Errors are connection endings.** The 23 chosen closes rose by 26 with other connections included,
  so clean closes count too. Totals match connections all day, and no exception was logged. The status
  breakdown wasn't seen, so a few real failures among them can't be excluded.
- **Closing a socket costs a request.** About 38 messages and 27 connections explain 65; the other 25 or
  so match the closes.
- **Isolated messages barely register in Duration.** About 65 events stayed within the dashboard's rounding
  of 1 GB-s, so at most about 0.015 GB-s each.
- **The original 148 GB-s isn't explained.** It didn't come from isolated wakes. Keepalive pings during
  the long socket tests are one candidate, and haven't been tested alone.

## Capacity, separated

Per connected device-hour: 3 connections, 50 messages, protocol pings as keepalive. Those are estimates.

| Measure | Per device-day | Daily limit | Devices that fit |
| --- | --- | --- | --- |
| Raw requests: 6 for connections and closes, 50 messages | 1,344 | 100,000 | **74** if enforced raw |
| Billable equivalents: 6 plus 2.5 | 204 | 100,000 | **490** if enforced 20:1 |
| Worker invocations | 72 | 100,000 | 1,388 |
| Duration, upper bound | 20 GB-s | 13,000 | At least 650 |
| Rows written, assumed 4 an hour and unproven | 96 | 100,000 | 1,041 |

The earlier 925 and 750 are replaced: closes are charged as requests, which they didn't include. Whether
closes are discounted under 20:1 isn't documented, so they are counted in full. Cutting reconnects
matters most: 2 connections an hour instead of 3 raises 490 to about 640.

## Keepalive comparison and the close handshake, 2026-11-02 to 03

On the home Wi-Fi, 30 minutes, each keepalive on its own account:

| Socket | Pings answered | Drops |
| --- | --- | --- |
| Protocol pings every 45 s | 37 of 38 | 5, all in the first 3 minutes |
| Text pings every 30 s | 53 of 55 | 8: 7 early, 1 at 16 minutes |
| No keepalive, two sockets on two accounts | n/a | About every 5 minutes, within a second or so of each other |
| Non-Cloudflare control, pings every 45 s | 26 of 26 | 49, in bursts |

- The home Wi-Fi is unreliable for long-lived connections. The control dropped 49 times there and stayed
  up for 15 minutes on the hotspot.
- Idle sockets on this network die after about 5 minutes. A keepalive under that keeps them up, and
  protocol and text pings did equally well.
- The day's Durable Object counters reset before they could be read, so the request and duration cost
  of each keepalive is still not measured.

**Fixed: clean closes hung.** A client's clean close got no reply from the server: 10 of 10 timed out
after 6 s. The documented automatic reply for this compatibility date didn't happen on staging. The
server now answers itself, in `38437100`. After deploying, 10 of 10 closed cleanly with code 1000,
median 326 ms. This may also explain the slot that stayed held and some `1006` closes. It hasn't been
checked against the error count.

## Not measured

- How the free limit counts messages. It needs Cloudflare's answer, or a capped run.
- Duration and request cost of protocol and text pings, each on its own.
- Row writes under real session traffic.
- CPU with a real Firebase token.
- The daily limit itself. It was deliberately not approached.
