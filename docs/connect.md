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
- Controls on any client (play/pause, seek, next/previous, picking a song or a queue item, queue
  edits, shuffle, repeat) are sent to the output. Play/Pause never moves the audio.
- "Connect to a device" (device button in the mini player / player bar, and in the full player)
  lists the account's devices: this device marked "This phone" / "This computer", the output
  ticked, offline devices seen in the last 14 days shown as unavailable. Picking one hands off.
- Opening an app never plays anything and never takes the output.

## Data (Firestore, account-scoped)

```
users/{uid}/connectDevices/{deviceId}   one per client install and account
users/{uid}/connect/session             the authoritative session
users/{uid}/connect/queue               its queue window (written with the session, atomically)
```

`deviceId` is a random UUID per (install, account), never derived from hardware or the name.
Device documents hold the platform-reported name (Android: Settings > About > Device name, else
maker + model; Windows: the computer name via JNA), an optional nickname, platform, type, whether
it can play, an online flag and a server-time heartbeat (`seenAt`, every 60 s). Duplicate names
are shown as "Name", "Name (2)"…; blank names fall back to "Android phone" / "Windows computer".
Registrations not seen for 30 days are deleted by the account's other clients.

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
2. The target prepares (loads the queue, warms the stream; songs that only exist on another
   device fail here) and marks `ready`. If there is no active output it commits itself.
3. The current output sees `ready`, **stops its audio first**, then in one write publishes its
   exact position and hands `ownerId`/`epoch` to the target (`committed`).
4. The target sees the commit and starts at the extrapolated position, playing or paused as the
   session was. Every client now shows the new output; "Connecting to *device*…" until then.

Failure handling: a target that can't play the song → `failed`, the old output never stopped. No
answer from the target in 15 s → `failed`. The old output silent for 5 s after `ready` (crashed,
asleep, offline) → the target commits anyway; if the old one comes back it sees the new epoch and
stays silent. If the song never starts on the new output, it hands the output back. Volume is
never copied between devices.

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

Android uses realtime listeners (one read per change). Windows has no realtime Firestore listener
over REST, so it polls the ~1 KB session document: every 0.4 s only during a handoff, 2 s while it
is the output (for remote controls), 3 s while showing another device's playback, 6 s otherwise,
four times slower when minimised; the queue document is fetched only when its revision changes.
A PC that is the output for 8 hours uses ~14 k reads; idle and visible ~600 reads/hour. The free
tier allows 50 k reads/day, so heavy all-day use on several PCs can reach it — raise the idle
intervals in `DesktopConnect.pollInterval` if that happens. Remote controls on a PC output take up
to ~2 s to apply; on Android they are near-instant.

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
