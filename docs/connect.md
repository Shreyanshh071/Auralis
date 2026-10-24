# Auralis Connect

One listening session per account, played on one device at a time and controlled from every
device signed in to that account (Android phones/tablets and Windows PCs). Modelled on the
familiar "connect to a device" experience; it is Auralis's own protocol on Firebase, not a
reproduction of any other service's internals.

## What it does

- Every client signed in to an account (email or Google; never an anonymous Listen Together
  guest) registers itself as a device of that account.
- The account has one session: song, play/pause, a timestamped position, queue window (up to 100
  songs around the current one), shuffle, repeat, playback context (Android playlists), and the
  **output** — the single device allowed to make sound.
- Other clients show the session silently: the mini player and full player display the song, the
  play state and a moving seek bar, with "Playing on *device*" / "Paused on *device*". Nothing is
  played locally.
- On Android, a live remote output keeps the mini player protected from dismissal.
  Once the output is inactive ("Last played on"), swiping down dismisses that
  historical session on this phone. Heartbeats do not restore the dismissed card;
  reconnecting with a live session shows it again. The account's saved session is
  not deleted. The gesture reads current output state, so no screen restart is needed.
  During active remote playback, dragging down still animates the card; a dismissal
  attempt springs back and explains that playback must move to this phone or the
  PC app must close first. The connection is checked at release, not captured at
  the start of the gesture.
- The Android full-player device pill appears only for an active remote output
  (including paused playback) or an ongoing connection attempt. Idle prompts,
  failed attempts without an active output, and historical outputs stay hidden.
- Controls on any client (play/pause, seek, next/previous, picking a song or a queue item, queue
  edits, shuffle, repeat) are sent to the output. Play/Pause never moves the audio.
- "Connect to a device" (device button in the mini player / player bar, and in the full player)
  lists the account's devices: this device marked "This phone" / "This computer", the output
  ticked, offline devices seen in the last 14 days shown as unavailable. Picking one hands off.
- Opening an app never plays anything and never takes the output.
- A phone with no local song creates its player presentation when a remote session arrives;
  returning to the foreground also fetches authoritative state immediately.

## Data (Firestore, account-scoped)

```
users/{uid}/connectDevices/{deviceId}   one per client install and account
users/{uid}/connect/session             the authoritative session
users/{uid}/connect/queue               its queue window (written with the session, atomically)
```

`deviceId` is a random UUID per (install, account), never derived from hardware or the name.
Device documents hold the platform-reported name (Android: Settings > About > Device name, else
maker + model; Windows: the computer name via JNA), an optional nickname, platform, type, whether
it can play, an online flag and a server-time heartbeat (`seenAt`, every 10 s, stale after 30 s). Duplicate names
are shown as "Name", "Name (2)"…; blank names fall back to "Android phone" / "Windows computer".
Registrations not seen for 30 days are deleted by the account's other clients.
Android marks an idle phone offline when its activity stops, and restores availability when it
returns or starts playback. Background audio remains available. Windows refreshes device records
every second while its picker is open. A sudden process kill or lost network still requires the
30-second heartbeat expiry; graceful backgrounding sends an explicit offline update.

### Session fields that make it safe

| Field | Role |
| --- | --- |
| `version` | +1 on every write; the rules refuse anything else, so a write based on old state always loses |
| `ownerId` / `epoch` | the one output and its fencing token; `epoch` +1 on every change of owner |
| `leaseUntil` | the output renews it every 30 s (90 s lease); when it lapses the output is "last played on" |
| `positionMs` + `positionAt` | position and the (estimated) server time it was true; clients extrapolate |
| `transfer` | a handoff in progress: `requested` → `ready` → `committed` (or `failed`) |
| `commands` | controls waiting for the output (max 20), applied once each and removed by the output |

Writes are transactions on Android (they fail rather than queue up offline, so a stale write is
never replayed later) and conditional writes on Windows (REST commit with the document's
`updateTime` as a precondition, retried on conflict). Clients only act on server-confirmed reads.

## Handoff

1. Any client writes `transfer = {to, epoch: current+1, status: requested}`.
2. The target checks it can play the song (songs that only exist on another device fail here)
   and marks `ready`, warming the stream in the background so the warm-up overlaps the rest of
   the handoff. If there is no active output it commits itself.
3. The current output sees `ready`, **stops its audio first**, then in one write publishes its
   exact position and hands `ownerId`/`epoch` to the target (`committed`).
4. The target sees the commit and starts at the frozen source position, playing or paused as the
   session was. Every client now shows the new output; "Connecting to *device*…" until then.

Failure handling: a target that can't play the song → `failed`, the old output never stopped. No
answer from the target in 15 s → `failed`. The old output silent for 5 s after `ready` (crashed,
asleep, offline) → the target commits anyway; if the old one comes back it sees the new epoch and
stays silent. If the song never starts on the new output, it hands the output back. Volume is
never copied between devices.

Preparation acknowledgements are retried after transient write failures. Windows retries a rules
rejection caused by concurrent session writes only after a fresh read confirms the document's
update time changed; actual permission failures are still surfaced.
Returning playback to the device that opens the picker still needs the current output's commit.
The returning device now starts stream preparation alongside its request, before the request's
acknowledgement. Windows reuses a healthy watch's session document for conditional writes even
between checkpoints, and keeps Firestore HTTP connections pooled to avoid repeated TLS setup.

## Online / offline rules

- Only one **online** client of an account makes sound. A client that sees `ownerId` isn't itself
  (or the epoch moved on) stops its audio immediately; audio that starts locally while another
  device is the active output is stopped.
- An output that loses its connection keeps playing locally (offline playback is a feature). Its
  lease lapses after ≤ 90 s and the others show "Last played on …". Any client may then press Play
  to take the session over. When the old output reconnects it reads the session first, sees it is
  no longer the output and stays silent — it never reclaims playback. If nobody took over, it is
  still the output and simply renews its lease.
- Signing out / switching accounts: the client gives up the output (paused), deletes its device
  registration while still authorised, drops its listeners/polling, and stops intercepting
  controls. FirebaseAuth state changes (Android) / the account state (Windows) drive this, so an
  expired or revoked sign-in also removes control (reads then fail the rules).
- Listen Together takes precedence: a room guest's player is driven by the room and Connect stands
  aside (it neither silences nor claims that audio, and the room's song is shown).

## Security rules

`firestore.rules` → `isAccount(uid)`: `request.auth.uid == uid` and the sign-in provider is not
`anonymous`. Ownership is derived from the authenticated path only; no client-supplied account id
is trusted. Another account cannot list, read, write or delete these documents even when it knows
a device id (they live under the owner's `users/{uid}`). Being on the same Wi-Fi changes nothing.

Within one account the devices trust each other (they share the same credentials); the rules
still enforce the version sequence, fenced owner changes (next epoch only, and only via a pending
transfer to that device, an expired lease, or the owner itself), a lease at most 2 minutes ahead,
document shapes and sizes, and server timestamps. No `get()`/`exists()` calls, so rules cost no
extra reads.

Tests: `firestore-tests/connect.rules.test.mjs` (Firestore emulator).

## Cost (Spark / free tier)

Android uses realtime listeners (one read per change). Windows now watches the session and queue
through Firestore's authenticated gRPC Listen stream using the signed-in user's Firebase ID token.
Each committed change reaches the engine without waiting for a polling interval. The stream reconnects
with backoff and refreshed credentials; sign-out cancels it. A healthy stream uses a reconciliation
read every 30 seconds. Listener failure falls back to polling: every 250 ms during a handoff or while the device
picker is open, 500 ms while another device is online, 1 s during playback on either device, 2 s while this PC is paused, 1.5 s while
another output is paused, and 6 s otherwise. Opening the picker interrupts the current wait.
Active playback polling stays responsive while minimised; paused/idle mirrors poll twice as
slowly. The queue is fetched only when its revision changes. Active playback uses up to about
3,600 session reads/hour per PC, plus device-list reads; several PCs running all day can exceed
the 50 k reads/day free tier. With another device online, polling can reach 7,200 reads/hour
per PC, plus writes and device-list reads. Network latency adds to polling and handoff round trips.
Those high polling counts apply to the fallback path; a healthy stream uses about 120 reconciliation
reads/hour plus reads for document changes, device lists, and reconnects.

The output saves a playback checkpoint at least every five seconds while advancing, as well as
on controls and graceful shutdown. Abrupt termination can lose the interval since the last
confirmed checkpoint. Reopening the previous output restores the account song and bookmark
silently before it publishes local state. Handoff commits mark playback as buffering so mirrors
hold the paused position until the new output confirms playback.

## Deploy order

1. Deploy the rules first (existing collections are unchanged; new ones were denied before):
   `firebase deploy --only firestore:rules`
   No indexes are needed (single documents and one small collection).
2. Ship the Android build and the Windows build (any order). Older clients ignore Connect and keep
   working; they just don't appear as devices. Until the rules are deployed, new clients log
   permission errors for Connect and otherwise behave as before.
3. No data migration: the documents are created on first use. Account deletion on Android also
   deletes them.

## Known limitations

- The queue travels as a window of 100 songs around the current one.
- Windows shuffles "upcoming songs once" and has no un-shuffle; a remote "shuffle off" is ignored
  there, and Windows reports shuffle only after it shuffled.
- Playback context (album/playlist name) is carried for Android playlists only.
- Lyrics on a silent Android client are not loaded for the remote song. On Windows the lyrics
  panel follows the mirrored song and the remote clock (not yet checked on real hardware).
- Positions rely on each client's estimate of server time from its own writes (accurate to about
  half a round trip); before a client's first write, its local clock is used.
- There is no web client yet; the model supports one (`BROWSER` type, "Web browser" fallback).
- Nicknames are stored and shown but there is no rename screen yet.
