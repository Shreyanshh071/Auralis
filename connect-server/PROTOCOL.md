# Connect protocol, version 1

One WebSocket per device. JSON text frames, 64 KB at most. Any server implementing this and
`src/core.js`'s rules works with the apps unchanged.

## Connecting

`GET /v1/connect?device=<id>` with `Authorization: Bearer <Firebase ID token>`. The token decides the
account. Refused with 401 (token), 400 (device id) or 429 (16 sockets already open).

The server sends `state` at once. Keep the socket alive with WebSocket protocol pings.

## Client to server

`r` is a request id, echoed in the reply.

| Frame | Meaning | Reply |
| --- | --- | --- |
| `{t:"hello", r, d}` | This device, sent on connecting and when it changes | `ok` |
| `{t:"update", r, base, s, q?}` | Replace the session, built on version `base`, with an optional queue | `ok`, `conflict` or `err` |
| `{t:"fetch", r}` | Current state | `state` |
| `{t:"intent", r, i}` | Record a playback intent | `ok` or `err` |
| `{t:"get-intent", r}` | Read it | `intent` |
| `{t:"remove", r, id}` | Forget a device | `ok` |

## Server to client

| Frame | Meaning |
| --- | --- |
| `{t:"state", r?, s, q, dv, now}` | Everything, on connecting |
| `{t:"session", s, q?, now}` | Another device wrote |
| `{t:"devices", dv, now}` | The device list changed |
| `{t:"ok", r, v?, now}` | Done, `v` being the new version |
| `{t:"conflict", r, s, q, now}` | `base` was stale; redo the step on this state |
| `{t:"intent", r, i, now}` | The recorded intent |
| `{t:"err", r?, code, retryMs?}` | `invalid`, `bad-version`, `not-allowed`, `not-this-device`, `refused`, `slow`, `bad-frame`, `unknown` |

`s`, `q`, `d` and `i` are the Firestore documents' fields from `ConnectCodec`, except `updatedAt` and
`seenAt`, which only the server sets.

## Close codes

| Code | Meaning | Client |
| --- | --- | --- |
| 4401 | Token expired | Refresh, reconnect now |
| 4409 | Same device connected elsewhere | Wait a minute |

## Production security

The Worker verifies Firebase ID tokens before selecting the Durable Object by verified UID. Device
IDs identify sockets within that account. They are client-selected and do not prove possession of
a particular physical device. Replacing a socket invalidates the old socket's command authority.
The WebSocket protocol has no account deletion command. Any future deletion operation needs its
own authorization and fresh authentication.

Expired sockets cannot issue commands and are excluded from outgoing sync and active presence.
The Worker closes them on the next relevant account event. A completely idle socket may remain
open and answer transport keepalives without delivering account data. This avoids per-socket
timers that would interfere with Durable Object hibernation.

Firebase signature verification does not detect disabled accounts or tokens revoked before
expiry. An already-issued token can retain Connect access until it expires. For the initial
playback-only rollout, early revocation checks are not required if this window is accepted.
Immediate revocation would require a separate trusted Firebase Auth check and a way to invalidate
already-open sockets. See [Firebase session management](https://firebase.google.com/docs/auth/admin/manage-sessions).

The pre-authentication upgrade limit is 300 attempts per minute per Cloudflare-provided IP and
location. Shared NATs share the limit, and distributed sources or locations can exceed it in
aggregate. Requests reach the Worker before this check, so it cannot protect the Worker daily
quota on its own. The account Durable Object independently allows a burst of 64 upgrades and
refills at one per second. That in-memory bucket resets when the object leaves memory. Throttled
upgrades return HTTP 429. See [Cloudflare rate limiting](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/).
| 4413 | Frame too large | Wait a minute |
| 4429 | Too many messages | Wait a minute |
| Other | Network or server | Backoff with full jitter, 1–60 s |

## Local development

`npm install`, then `npm test`. Everything runs in `workerd` on this machine and needs no Cloudflare
account. `.dev.vars` turns on a test-only login, which works only on localhost.
