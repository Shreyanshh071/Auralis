# Listen Together: Firebase vs. dedicated WebSocket server — architecture audit

Status: audit and plan only. Nothing here is implemented.
Based on the code as of 2026-09-26 (uncommitted working tree, including the room-rules and
guest-control changes made the same day).

---

## 1. What exists today

### 1.1 Files and classes

| Area | File | What it does |
|---|---|---|
| Firebase access | `android/app/src/main/java/com/auralis/music/data/sync/ListenTogetherManager.kt` | The only class that talks to Firestore for Listen Together. Anonymous auth, room create/join/leave, host broadcast, heartbeats, clock-offset measurement, snapshot listeners (room, members, recommendations), guest commands, room settings, recommendation CRUD, task-removed cleanup. Also defines `RoomMember`, `GuestCommand`, `RoomSettings`, `RoomRecommendation`, `NativeRoomState`, `GuestCommandTracker`, `GuestSongDecision`. |
| Sync math | `data/sync/ListenTogetherRoomController.kt` | `ListenTogetherSyncMath` (position extrapolation, 2.5 s drift threshold, clock-offset samples, presence staleness, 50-item queue window). Also two **unused** classes, `RoomState` and `RoomParticipant`. |
| Session logic | `ui/viewmodel/ListenTogetherViewModel.kt` | Owns the whole session: create/join/leave, observation jobs, heartbeat loop, clock-offset samples, host broadcast binding (`bindHostPlayer`), guest sync (`syncGuestWithRoomState`), guest control forwarding (`bindGuestControls`), host handling of guest commands and songs, pills. |
| ViewModel scope | `ui/viewmodel/AppViewModelProvider.kt` | Creates the ViewModel with `ViewModelProvider(activity, …)`: **Activity-scoped**. |
| Wiring | `ui/AuralisApp.kt` | Sets `setGuestListenTogether`, wires `onSyncTrackChange/Resume/Pause/Seek`, `onGetLocal*`, `onHostPlayTrack/AddToQueue/GuestCommand`, calls `bindHostPlayer`/`bindGuestControls`, gates every guest UI action (`isGuestInRoom`, `guestCanControl`, `guestCanAddSongs`). |
| UI | `ui/screens/ListenTogetherSheet.kt` | Host/join tabs, room code, room rules switches, song requests, member list, guest permissions card. |
| UI | `ui/components/TrackOptionsMenu.kt` | "Add to room queue" entry for guests. |
| Player | `data/service/AuralisAudioPlayer.kt` | `isGuestListenTogether` gate on play/pause/seek/next/previous/playTrack; `syncPlayTrack/syncResume/syncPause/syncSeek` used by guests; `userSeekEvents` used by host broadcast; `guestControlForwarder`. |
| Service | `service/AuralisMediaService.kt` | Blocks media-session commands for guests; calls `ListenTogetherManager.performTaskRemovedCleanup()` from `onTaskRemoved` and `onDestroy`. |
| Security | `firestore.rules` | Room, member and recommendation rules. |
| Config | `firebase.json`, `.firebaserc` (project `auralis-70cf8`), `android/app/google-services.json` | Firebase project. No Cloud Functions in the repo. |
| Build | `android/app/build.gradle.kts` | `firebase-auth`, `firebase-firestore`, `firebase-database` (**unused anywhere**), `firebase-messaging` (app-update notifications only, unrelated). |
| Tests | `ListenTogetherSyncTest`, `ListenTogetherRoomRulesTest`, `ListenTogetherRecommendationTest`, `ListenTogetherIdentityTest` | Pure logic only; nothing exercises Firestore or the ViewModel. |

Not implemented at all: **host migration**, **chat**, **reconnect/resume** (beyond Firestore's own
offline cache), **room expiry/cleanup**, any **backend code** (no Cloud Functions).

### 1.2 Firestore data model

```
rooms/{CODE}                      (host-only writes)
  id, code, hostId, hostName, status: active|closed
  currentTrack {id,title,artist,album,thumbnail,duration}
  queue [≤50 tracks, window around current], queueIndex
  isPlaying, playbackPosition, playbackRate
  updatedAt (host wall clock), serverUpdatedAt (serverTimestamp)
  seekVersion, membersList [host only], memberCount
  settings {guestsCanAddSongs, guestsCanControlPlayback, requireApproval}
rooms/{CODE}/members/{uid}        (each user writes only their own)
  id, name, isHost, lastSeen, serverSeen (serverTimestamp), joinedAt, avatarColorHex
  command {type, positionMs, seq}  (guest playback requests)
rooms/{CODE}/recommendations/{id} (members create; host sets status)
  id, track, recommendedByUid/Name, note, upvotes[], createdAt, status
```

### 1.3 Data flows (as implemented)

**Host changes playback (play/pause/skip/seek, from app, notification, lock screen, song end)**
`AuralisAudioPlayer` state flows (`currentTrack`, `isPlaying`, `queueState`, `userSeekEvents`)
→ `ListenTogetherViewModel.bindHostPlayer` (collects; also re-broadcasts every 5 s while playing)
→ `broadcastHostPlayback` → `ListenTogetherManager.updateHostPlayback` → `rooms/{CODE}.update(...)`
→ Firestore → every member's `observeRoomState` snapshot listener
→ `ListenTogetherViewModel` `roomJob` → `syncGuestWithRoomState`
→ `onSyncTrackChange` / `onSyncResume` / `onSyncPause` / `onSyncSeek` (set in `AuralisApp`)
→ `AuralisAudioPlayer.syncPlayTrack/syncResume/syncPause/syncSeek` → ExoPlayer (or YouTube engine).

**Guest position maths**
Estimated host position = `playbackPosition + (now − broadcastTime) × rate`, where
`broadcastTime = serverUpdatedAt − clockOffset` (clock offset from reading back a
`serverTimestamp` after each heartbeat write; best of last 5 samples by uncertainty).
Track change → `syncPlayTrack(track, queue, index, estimatedPos)`. Drift check only while
playing, at most every 5 s, hard seek when |drift| > 2 500 ms.

**Guest control (new)**
Guest taps play/pause/next/previous/seek → UI gate (`guestCanControl`) → `PlayerViewModel`
→ `AuralisAudioPlayer` (guest branch) → `guestControlForwarder` → `ListenTogetherManager.sendGuestCommand`
→ `members/{uid}.command` → host's members listener → `GuestCommandTracker` → `onHostGuestCommand`
→ host `PlayerViewModel` → host player → normal host broadcast → all guests.

**Guest adds a song**
Track menu / player "Add to queue" → `ListenTogetherViewModel.recommendSong` → `recommendations/{id}` (pending)
→ host's recommendations listener → `handleGuestSongs` → add to host queue + status `accepted`,
decline, or wait for the host's approve/decline buttons.

**Presence**
Everyone writes `members/{uid}.lastSeen/serverSeen` every 20 s; stale after 75 s. Guests leave when
the host's record is missing or stale. Join is refused when the host's record is stale.

**Leaving / app killed**
`leaveRoom` deletes own member doc; host also sets `status=closed`. `onTaskRemoved`/`onDestroy`
fire `performTaskRemovedCleanup()` (not awaited).

### 1.4 Authentication and authorization today
- `ensureAuthenticated()` signs in **anonymously** if nobody is signed in; Google sign-in
  (`GoogleAccountSyncManager`) uses `signInWithCredential` (replaces, does not link, the anonymous user).
- All authorization is Firestore rules: host-only room writes, own-member-only writes,
  member-only recommendations. Room codes are 6 characters from a 32-letter alphabet
  (~1.07 × 10⁹ codes); knowing the code is the only join credential. Room list queries are denied.

---

## 2. Hidden problems found in the existing implementation

Found by reading the code; none of these were reproduced on a device during this audit.
Several matter regardless of which transport is chosen.

1. **The session lives in an Activity-scoped ViewModel.** Broadcasting, guest sync, heartbeats
   and listeners all run in `viewModelScope`, while playback runs in `AuralisMediaService`. If the
   Activity is destroyed while music keeps playing (system reclaims it, "keep playing on task clear"),
   the host silently stops broadcasting and guests stop syncing. There is no `onCleared()`, so
   nobody leaves the room either.
2. **Swiping the app away while playing leaves a zombie room.** `onTaskRemoved` returns early (before
   `performTaskRemovedCleanup`) when `stopMusicOnTaskClear` is off and music is playing. The host keeps
   playing, the room stops updating, and guests are evicted ~75 s later.
3. **Any listener error closes the room.** `observeRoomState` emits `null` on *any* error; the
   ViewModel treats `null` as "room closed", clearing state and showing "Host has disconnected".
4. **The host's own second device kills the room.** Member docs are keyed by uid. The same
   account joining from another phone overwrites the host's member doc with `isHost=false`;
   guests' `isHostGone()` then sees no host and leaves. Leaving on one device also deletes the
   other device's record.
5. **Members can claim `isHost=true`.** `hasValidMemberShape()` doesn't tie `isHost` to the room's
   `hostId`. A modified client can show a HOST badge or mask a dead host from presence checks.
   The members list is also readable by any authenticated user, member or not.
6. **Signing in with Google mid-room changes the uid.** `signInWithCredential` replaces the
   anonymous user; the host then fails the `hostId == auth.uid` rule and every broadcast fails
   (only logged).
7. **Guests start each song late.** `syncPlayTrack` starts at a position estimated *before* the
   stream resolves (often 1–5 s). Correction waits for the 5 s debounce and only fires above 2.5 s,
   so guests commonly run 1–2.5 s behind for a whole song.
8. **Guests may play a different recording.** The room carries the host's `Track.id`, which can be
   an `sp_…` Spotify id or a pre-resolution id. Each guest resolves audio on its own
   (`AudioStreamResolver`) and can land on another version (radio edit, remaster) — a permanent
   offset no drift logic can fix. The host's resolved video id is not broadcast.
9. **A guest's headphone unplug is overridden.** ExoPlayer pauses on `AUDIO_BECOMING_NOISY`, then the
   next room update (≤ 5 s) sees "host playing, guest not" and calls `syncResume`, so music resumes
   through the phone speaker.
10. **Offline-queued broadcasts give wrong positions.** Firestore's offline cache commits a host's queued
   writes after reconnecting; `serverUpdatedAt` is the commit time but `playbackPosition` is old,
   so guests jump.
11. **Presence is expensive and quadratic.** Every member writes a heartbeat every 20 s *and* reads
   its own doc from the server (for the clock offset); every heartbeat triggers a read on every
   member's members listener → O(N²) reads per room.
12. **Rooms are never deleted.** Closed rooms, members and recommendations stay forever: no TTL,
   no Cloud Function, no cleanup.
13. **Queue sharing is partial and unversioned.** Only a 50-track window is shared; guests' queues
   are replaced wholesale; no version number, so reorder races are undetectable.
14. **No host migration.** Host leaving closes the room; host crash means ~75 s of silence then eviction.
15. **Room rules are enforced by the host's app, not a server.** A modified client can still write
   song requests and commands; the host ignores them, but they can be spammed.
16. `performTaskRemovedCleanup()` writes are not awaited (Firestore persists them offline, so a
   room may only close on the next launch).
17. Dead code: `firebase-database` dependency; `RoomState`/`RoomParticipant`; recommendation
   upvotes have data and rules but no UI.
18. Guests' notification/lock-screen buttons stay blocked even when the host allows guest control.

---

## 3. Classification of every Firebase feature Listen Together uses

| Firebase feature | Used for | Verdict |
|---|---|---|
| Firebase Auth (anonymous + Google) | Identity; `auth.uid` in all rules | **MUST remain in Firebase.** The WebSocket server verifies Firebase ID tokens. |
| Firestore `rooms/{code}` doc | Room lifecycle, playback state, queue window, settings, host id | **CAN move to WebSocket** (server memory). **NEEDS additional backend support** only if rooms must survive server restarts (Redis snapshot), or the host re-seeds on resume (§4.8). |
| Firestore `members/{uid}` | Presence, heartbeats, clock offset, guest commands | **CAN move to WebSocket.** The socket connection *is* presence; ping/pong replaces heartbeats and the serverTimestamp read-back. |
| Firestore `recommendations/*` | Guest song requests, approval, upvotes | **CAN move to WebSocket** (room-scoped, ephemeral). **NEEDS a database** only for request history. |
| `FieldValue.serverTimestamp()` + read-back | Clock sync | **CAN be removed** (NTP-style ping/pong). |
| Firestore offline persistence (implicit) | Nothing intentional; causes #10 | **CAN be removed** for Listen Together. |
| Snapshot listeners (3 per member) | Realtime fan-out | **CAN move to WebSocket.** |
| `firestore.rules` room section | All Listen Together authorization | **SHOULD remain until old app versions are gone** (and should be tightened now, #5). Remove later, replaced by server-side validation. |
| `performTaskRemovedCleanup` writes | Close room / leave on kill | **CAN be removed** (socket close = presence; server grace period). |
| `users/{uid}` library sync | Not Listen Together | **SHOULD remain in Firebase.** |
| FCM | Update notifications | **SHOULD remain**; could later deliver room invites. |
| `firebase-database` dependency | Nothing | **CAN be removed** now. |
| Room history, friends, persistent chat, analytics | Not built | **NEEDS additional backend/database** if wanted. |

---

## 4. Proposed WebSocket architecture (replaces only the realtime layer)

```
Auralis Android
 ├─ Firebase Auth ── ID token ─────────────┐
 ├─ Firestore (users/{uid} library only)   │
 └─ ListenTogetherSession (app-scoped)     │
      └─ WebSocketListenTogetherBackend ── wss:// + token
                                            ▼
                          Auralis Listen Together Server (Ktor)
                            ├─ Firebase Admin SDK: verifyIdToken
                            ├─ RoomRegistry (in memory)
                            │    └─ one RoomActor per room (serialises all changes)
                            ├─ Clock (monotonic ms, exposed as serverTimeMs)
                            ├─ Presence (per-connection ping / grace timers)
                            └─ optional Redis: room snapshots, cross-instance routing
```

### 4.1 Who is authoritative

The server holds **the room timeline** (ordering, versions, timestamps, rules), and it can move
`isPlaying`/position on its own when the host is gone. Which song plays next still comes from the
host's device (queue, radio auto-extend and version matching live in `AuralisAudioPlayer`); the
server keeps the **full** queue so another member can take over.

- **Phase A (drop-in):** host-driven. The host's player reports state and the server validates,
  timestamps, versions and fans it out. Same semantics as today, so the lowest risk.
- **Phase B (server-authoritative timeline):** play/pause/seek from anyone the rules allow is
  applied by the server to the room timeline, and the host's player *follows* like a guest.
  Track changes still come from the host (or the server advancing the stored queue at song end
  while the host is away). This is what makes host migration and guest control race-free.

### 4.2 Room state (server-side)

```
Room {
  code, createdAt, hostMemberId, settings, status: ACTIVE|HOST_AWAY|CLOSED
  stateVersion: Long            // +1 on every playback change
  queueVersion: Long            // +1 on every queue change
  playback { track: SharedTrack, isPlaying, anchorPositionMs, anchorServerTimeMs, rate }
  queue: List<SharedTrack>, currentIndex
  members: Map<memberId, Member{uid, deviceId, name, role, connected, lastPongAt}>
  requests: Map<id, SongRequest>
  lastCommandIds: per-session LRU for de-duplication
}
SharedTrack { trackId, resolvedVideoId, title, artist, album, thumbnail, durationMs }
```

`resolvedVideoId` fixes problem #8: guests play the host's exact recording.
Member identity = `uid + deviceId` (fixes #4).

### 4.3 Clock synchronisation

Over the socket, NTP-style: the client sends `PING{t0}`; the server replies `PONG{t0, t1=recv, t2=send}`;
the client records `t3`.
`offset = ((t1 − t0) + (t2 − t3)) / 2`, `rtt = (t3 − t0) − (t2 − t1)`.
Keep the last 8 samples and use the one with the lowest `rtt`. Take a burst of 5 on connect and after
every reconnect or network change, then 1 every 15 s. Discard samples with `rtt` > 1.5 s.
All clocks use `SystemClock.elapsedRealtime()` on Android (immune to the user changing the time),
converted to server time with the offset.

### 4.4 Expected position

```
serverNow   = localElapsedNow + offset
expectedPos = anchorPositionMs + (serverNow − anchorServerTimeMs) × rate      (if isPlaying)
            = anchorPositionMs                                                (if paused)
clamped to [0, durationMs]
```
The anchor is set by the server when it applies a change. For host-reported state, the host
sends `positionMs` plus `capturedAt` on its own clock; the server converts it with that host's
offset, so the host's own network delay is removed.

### 4.5 Drift correction (tuned to Auralis's players)

Measure only when the player is `STATE_READY` and playing, never within 2 s of a seek or track
start, and compare `player.currentPosition` with `expectedPos`. Check every 1 s, not every 5 s.

| Drift | Action | Why |
|---|---|---|
| < 200 ms | nothing | Below what's audible between two phones. Bluetooth output alone differs by 150–300 ms. |
| 200 ms – 1.5 s | ExoPlayer: nudge speed to 0.97× / 1.03× until within 80 ms, then 1.0× (pitch kept) | Seamless; no audible jump. |
| > 1.5 s, or any drift on the YouTube-engine fallback above 1 s | hard seek to `expectedPos + seekLatency` | The YouTube engine has no rate control; `seekLatency` is an EMA of measured seek-to-ready time (start at 300 ms). |
| New track | start loading immediately, then seek to a freshly computed `expectedPos` at `STATE_READY` | Fixes the "starts 1–5 s late" problem (#7). |

A guest paused by the system (noisy audio / focus loss) enters **LOCAL_PAUSE**: it isn't auto-resumed
and shows "Paused on this phone — tap to rejoin" (fixes #9).

### 4.6 Ordering, duplicates, races

- Every client→server command carries `commandId` (UUID). The server keeps the last 64 per
  session and replies `ACK{commandId, duplicate=true}` to repeats.
- Every server→client state carries `stateVersion` / `queueVersion`; clients drop anything
  older than they've applied. A full `ROOM_SNAPSHOT` resets them.
- Each room is a single-threaded actor (Kotlin coroutine + channel), so concurrent host/guest
  actions are applied in the order received.
- Queue edits carry `baseQueueVersion`. On a mismatch the server rejects with `QUEUE_CONFLICT`
  plus the current queue.
- Rate limits per session: 10 playback commands / 5 s, 5 song requests / 30 s, 30 chat
  messages / min.

### 4.7 Presence, reconnection, background, process death

- Server pings every 15 s. A member is **disconnected** after 30 s without a pong, **removed** after a
  90 s grace. Guests stay listed as "reconnecting" during the grace period.
- `WELCOME` returns a `resumeToken` (random 128-bit, bound to uid + deviceId + room). The client
  keeps it in memory and DataStore.
- Reconnect after a network change (Wi-Fi ↔ mobile) by listening to `ConnectivityManager` network
  callbacks: reconnect immediately, then back off with jitter (0.5, 1, 2, 4, 8, 15 s cap).
  Then send `RESUME{resumeToken, lastStateVersion, lastQueueVersion}`. The server replies with a snapshot or
  "up to date".
- The connection lives in an **app-scoped `ListenTogetherSession`**, owned alongside
  `AuralisMediaService` (foreground service while playing, so the network stays up in Doze).
  It must not live in an Activity ViewModel (fixes #1, #2).
- Process death: on relaunch, if a fresh `resumeToken` exists, offer "Rejoin room".
- The same account on two devices is two members (`deviceId`). A second device can join as a
  guest without touching the host.

### 4.8 Host disconnect and migration

1. The host's socket drops, and the room becomes `HOST_AWAY`. The server keeps extrapolating the timeline, so
   guests keep playing the current song, and the server can advance to the next stored queue item at song end.
2. The host resumes within the grace period (90 s): it resumes host role and gets a snapshot.
3. Grace expires: promote a member in this order: host-designated co-host, then earliest-joined
   connected member. Send `HOST_CHANGED`. The new host's device receives the full queue and becomes
   the source for track changes. If nobody is connected, close the room.
4. Explicit `TRANSFER_HOST` lets a host hand over before leaving.

Server restarts: Phase A keeps rooms in memory only. Clients reconnect, and the host's `RESUME`
carries its full state so the server can rebuild the room (`REHYDRATE`). Guests then resume
normally. Redis snapshots are only needed for multi-instance or zero-glitch deploys.

### 4.9 Room expiry
- Closed immediately when the host leaves explicitly and no migration candidate exists.
- Closed after 5 min with zero connected members.
- Hard cap: 12 h without a playback change.
- Codes are reusable once closed (the server checks for collisions in its registry).

### 4.10 Security
- `HELLO` must carry a Firebase ID token. The server verifies it with the Firebase Admin SDK
  (signature, expiry, audience `auralis-70cf8`) and optionally checks revocation. The token is refreshed
  and re-sent with `REAUTH` before expiry (tokens last 1 h).
- Anonymous tokens are allowed (as today) or require Google (a product decision).
- The room code stays the join credential. Rate-limit joins per uid/IP to stop code guessing.
  Optional: the host approves joiners.
- Rules (`guestsCanAddSongs`, `guestsCanControlPlayback`, `requireApproval`) are enforced **on the
  server** (fixes #15). Role comes from server state, never from the client (fixes #5).
- Validate every payload (max 64 KB frame, max 500 queue items, string lengths). TLS only (`wss`).

---

## 5. Protocol v1

Envelope for every frame (JSON over WebSocket, `kotlinx.serialization`, polymorphic on `type`):

```json
{ "v": 1, "type": "PLAY", "id": "c1f0…", "body": { … } }
```
`v` is the protocol version. The server accepts a range and answers `ERROR{code:"UNSUPPORTED_VERSION", minVersion}`.
`id` is the client command id for de-duplication and `ACK`/`ERROR` correlation.

### 5.1 Client → server

| type | body | who |
|---|---|---|
| `HELLO` | `idToken, deviceId, appVersion, protocolVersions[]` | all |
| `REAUTH` | `idToken` | all |
| `PING` | `t0` | all |
| `CREATE_ROOM` | `displayName, settings, playback, queue, currentIndex` | anyone |
| `JOIN_ROOM` | `code, displayName` | anyone |
| `RESUME` | `resumeToken, lastStateVersion, lastQueueVersion, hostState?` | members |
| `LEAVE_ROOM` | `—` | members |
| `HOST_STATE` | `track, isPlaying, positionMs, capturedAt, rate, reason` | host (Phase A) |
| `PLAY` / `PAUSE` | `positionMs?` | host; guests if allowed |
| `SEEK` | `positionMs` | host; guests if allowed |
| `NEXT` / `PREVIOUS` | `—` | host; guests if allowed (forwarded to host in Phase A) |
| `SET_TRACK` | `track, queueIndex, positionMs` | host |
| `QUEUE_UPDATE` | `baseQueueVersion, queue, currentIndex` | host |
| `UPDATE_SETTINGS` | `settings` | host |
| `SUGGEST_SONG` | `track, note` | guests if allowed |
| `DECIDE_SUGGESTION` | `requestId, accept` | host |
| `TRANSFER_HOST` | `toMemberId` | host |
| `CHAT_SEND` (optional) | `text` | members |
| `PLAYER_STATUS` | `trackId, state: LOADING/READY/BUFFERING/LOCAL_PAUSE, positionMs` | members (diagnostics, "waiting for X") |

### 5.2 Server → client

| type | body |
|---|---|
| `WELCOME` | `sessionId, serverTimeMs, protocolVersion, pingIntervalMs, resumeGraceMs` |
| `PONG` | `t0, t1, t2` |
| `ROOM_JOINED` | `resumeToken, memberId, role` + full `ROOM_SNAPSHOT` |
| `ROOM_SNAPSHOT` | `code, settings, hostMemberId, status, stateVersion, playback, queueVersion, queue, currentIndex, members[], requests[]` |
| `PLAYBACK_STATE` | `stateVersion, track, isPlaying, anchorPositionMs, anchorServerTimeMs, rate, cause{memberId, action}` |
| `QUEUE_STATE` | `queueVersion, queue, currentIndex` |
| `MEMBER_JOINED` / `MEMBER_UPDATED` / `MEMBER_LEFT` | `member{id,name,role,connected}` |
| `HOST_CHANGED` | `hostMemberId, reason: TRANSFER/MIGRATION/RETURNED` |
| `SETTINGS_UPDATED` | `settings` |
| `SUGGESTION_ADDED` / `SUGGESTION_UPDATED` | `request` |
| `COMMAND_FOR_HOST` | `command, fromMemberId` (Phase A: guest NEXT/PREVIOUS the host must carry out) |
| `CHAT_MESSAGE` (optional) | `id, memberId, text, serverTimeMs` |
| `ACK` | `commandId, duplicate` |
| `ERROR` | `commandId?, code, message, retryable` |
| `ROOM_CLOSED` | `reason: HOST_LEFT/EXPIRED/EMPTY` |

Error codes: `UNAUTHENTICATED, TOKEN_EXPIRED, UNSUPPORTED_VERSION, ROOM_NOT_FOUND, ROOM_CLOSED,
NOT_ALLOWED, QUEUE_CONFLICT, RATE_LIMITED, INVALID_PAYLOAD, RESUME_EXPIRED`.

### 5.3 Android types (sketch)

```kotlin
@Serializable data class Envelope(val v: Int = 1, val type: String, val id: String? = null, val body: JsonElement)

@Serializable data class SharedTrack(
    val trackId: String, val resolvedVideoId: String?, val title: String, val artist: String,
    val album: String?, val thumbnail: String, val durationMs: Long
)
@Serializable data class PlaybackState(
    val stateVersion: Long, val track: SharedTrack?, val isPlaying: Boolean,
    val anchorPositionMs: Long, val anchorServerTimeMs: Long, val rate: Float = 1f
)
@Serializable sealed interface ClientMessage {
    @Serializable @SerialName("HELLO") data class Hello(val idToken: String, val deviceId: String, val appVersion: String, val protocolVersions: List<Int>) : ClientMessage
    @Serializable @SerialName("PING") data class Ping(val t0: Long) : ClientMessage
    @Serializable @SerialName("JOIN_ROOM") data class JoinRoom(val code: String, val displayName: String) : ClientMessage
    @Serializable @SerialName("RESUME") data class Resume(val resumeToken: String, val lastStateVersion: Long, val lastQueueVersion: Long) : ClientMessage
    @Serializable @SerialName("SEEK") data class Seek(val positionMs: Long) : ClientMessage
    // … one class per row of §5.1
}
@Serializable sealed interface ServerMessage { /* one class per row of §5.2 */ }
```

These classes should live in a **shared Kotlin module** (`:listen-together-protocol`) compiled
into both the app and the server, so the schema has one source of truth.

---

## 6. Server technology

| | Kotlin + Ktor | TypeScript + Node | Go |
|---|---|---|---|
| WebSocket performance | Very good (coroutines; 10k idle sockets on 1–2 GB) | Very good with `ws`/uWebSockets | Excellent, lowest memory |
| Development complexity for Auralis | Lowest: same language, coroutines, and `kotlinx.serialization` already used in the app | Medium: second language, protocol types duplicated or generated | Highest: new language |
| Shared protocol types with Android | **Yes, the same module** | No (needs codegen) | No (needs codegen) |
| Firebase auth | Official Admin SDK (Java) | Official Admin SDK (best documented) | Official Admin SDK |
| Deployment | Fat JAR / container; JVM uses more RAM (~150–250 MB base) | Tiny container, many PaaS options | Single static binary, tiny |
| Maintenance / debugging | Same tools as the app (IntelliJ debugger, same idioms) | Good; different stack from the app | Good; separate stack |
| Ecosystem for this job | Ktor websockets, Redis (Lettuce), metrics | Largest | Strong |
| Scaling path | Room-affinity routing + Redis | Same | Same |

**Recommendation: Kotlin + Ktor.** The deciding factors are specific to Auralis: one developer,
an all-Kotlin codebase, and a protocol whose correctness depends on the client and server agreeing
exactly. Sharing the `@Serializable` protocol classes removes a whole category of bugs. The JVM's
extra memory is irrelevant at this scale (one small VM serves 10k users). Node would be the choice
only if the backend were meant to be maintained by someone who doesn't write Kotlin.

---

## 7. Migration strategy (incremental, no big-bang rewrite)

### 7.1 Abstraction

```
domain/listentogether/
  ListenTogetherBackend            // interface
    val connection: StateFlow<ConnectionState>      // CONNECTED / RECONNECTING / OFFLINE
    val room: StateFlow<RoomView?>                  // settings, members, playback, queue, requests
    val events: Flow<RoomEvent>                     // joined/left/host changed/errors (pills)
    suspend fun create(...), join(code), leave()
    suspend fun reportHostState(...), play(), pause(), seek(ms), next(), previous()
    suspend fun updateQueue(...), updateSettings(...), suggest(track), decide(id, accept)
  ListenTogetherSession            // app-scoped; owns backend + drift controller + player binding
data/listentogether/firebase/FirebaseListenTogetherBackend   // wraps today's ListenTogetherManager
data/listentogether/ws/WebSocketListenTogetherBackend        // OkHttp WebSocket (OkHttp already in the app)
```
`ListenTogetherViewModel` becomes a thin adapter over `ListenTogetherSession`. `AuralisApp`
stops wiring `onSync*` lambdas; the session talks to `AuralisAudioPlayer` directly.

### 7.2 Phases

**Phase 0 — fixes that help either backend (on Firebase, now)**
1. Move session state out of the Activity ViewModel into app-scoped `ListenTogetherSession` (#1, #2).
2. Broadcast `resolvedVideoId` and use it on guests (#8).
3. Start-at-ready + 1 s drift checks + speed nudging (#7); LOCAL_PAUSE for system pauses (#9).
4. Treat listener errors as "reconnecting", not "closed" (#3).
5. Key members by `uid_deviceId`; handle the uid change on Google sign-in (#4, #6).
6. Tighten rules: `isHost` must equal `memberId == room.hostId`; members list readable by members only (#5).
7. Remove `firebase-database`, `RoomState`/`RoomParticipant`.

**Phase 1 — interface.** Extract `ListenTogetherBackend`; implement `FirebaseListenTogetherBackend`
by moving `ListenTogetherManager` behind it. No behaviour change; ship.

**Phase 2 — server + client behind a switch.** Build the Ktor server (Phase A semantics),
deploy to staging, and implement `WebSocketListenTogetherBackend`. Choose the backend with a developer
setting (and later a remote flag). Rooms created on WebSocket get codes with a distinguishing prefix
(e.g. `W`), so the app knows which backend to join. Old app versions keep using Firestore unchanged.

**Phase 3 — make WebSocket the default** for new rooms. Keep the Firebase backend for joining rooms
created by older app versions.

**Phase 4 — server-authoritative timeline, host migration, chat** (WebSocket only).

**Phase 5 — retire Firestore rooms** once old app versions are negligible: remove the room rules,
add a one-off cleanup of old `rooms/*` documents.

### 7.3 Testing both side by side
- A shared test suite runs the same scenarios against both backends (fake Firestore via the
  emulator; the Ktor server via `testApplication`).
- Server: a simulated-client harness with N clients, injected clock skew (±30 s), latency
  (20–800 ms), jitter, packet loss, duplicated and reordered frames, disconnects and Wi-Fi↔cellular handovers.
  Assert on convergence time and final drift.
- Device: two to three phones on real networks. Log `expectedPos − actualPos` every second on each guest, with
  the same logging for both backends, so the drift numbers are directly comparable.
- Soak: 50 simulated rooms for 24 h on staging (memory, reconnect storms, expiry).

---

## 8. Cost and scaling (Listen Together traffic only)

Derived from the current code's write/read pattern. Assumptions: 4 people per room, playing
continuously, prices ≈ $0.18 per 100k writes and ≈ $0.06 per 100k reads (Firestore list prices
as I know them; check current pricing for the project's region).

**Firestore today, per 4-person room-hour:**
writes = host broadcast 720 (every 5 s) + heartbeats 4×180 = **~1,440**;
reads = room updates 720×4 + heartbeat read-backs 720 + members-listener fan-out 4×4×180 = **~6,500**.
≈ **$0.0065 per room-hour ≈ $0.0016 per user-hour**. Room cost grows with N² (members listener);
a 10-person room is ≈ $0.002 per user-hour.
Free tier (20k writes / 50k reads per day) ≈ 8 room-hours a day.

| Concurrent listeners (sustained 24/7) | Firebase-only | Firebase Auth + WebSocket server | Firebase + WebSocket + persistent DB |
|---|---|---|---|
| Small (≤ 20) | ~$0 (free tier) to a few $/mo | $0–5 (free/hobby tier or a $4–5 VM) | + $0 (Redis free tier) |
| 100 | ~$115/mo | ~$5–10/mo (one small VM) | ~$10–25/mo |
| 1,000 | ~$1,150/mo | ~$10–25/mo (1–2 GB VM) | ~$30–60/mo |
| 10,000 | ~$11,500/mo | ~$40–120/mo (2 instances + room-affinity routing, ~1 TB/mo egress) | ~$100–250/mo |

Firebase Auth is free at these numbers (anonymous + Google, below Identity Platform's paid MAU tiers).
Real bills scale with actual listening hours, not peak concurrency. A real user base listening together
an hour a day costs a small fraction of the "24/7" column. The WebSocket columns exclude engineering and
on-call time, which is the real cost: someone must deploy, monitor and keep the server up.

Server load reference: per client ~1 ping every 15 s plus a state message per change, i.e. under
1k messages/s at 10k users. One Ktor instance handles this. Beyond one instance, route by room code
(consistent hashing at the load balancer) or fan out through Redis pub/sub.

---

## 9. Final verdict

1. **What Firebase does today:** anonymous/Google identity; room documents holding the host's
   playback snapshot, 50-song queue window and rules; member presence via 20 s heartbeats;
   clock offset via serverTimestamp read-back; guest commands on member records; song requests;
   all authorization via security rules. No backend code.
2. **Move to WebSocket:** room lifecycle, membership/presence, playback timeline, clock sync,
   queue, guest commands, song requests, host migration, reconnection, chat.
3. **Stay in Firebase:** Auth (the server verifies its ID tokens), `users/{uid}` library sync, FCM.
   Keep the Firestore room path and its rules until old app versions are gone.
4. **What could break:** old clients (need the dual backend); security (rules → server validation must
   cover the same guarantees on day one); server restarts dropping rooms (needs host re-seeding or Redis);
   background kills (unless the session moves out of the Activity); token expiry mid-room;
   the uid change on Google sign-in; code-namespace mix-ups between backends; and a new single point of
   failure (Firestore doesn't go down with your VM).
5. **New infrastructure:** a Ktor service with TLS (e.g. Fly.io / Hetzner / Cloud Run with min instances), a domain,
   Firebase Admin credentials (service account), logging/metrics/uptime alerts, CI deploys, and
   optional Redis.
6. **Worth it?** Technically yes: lower latency (Firestore listener round trips are typically a few
   hundred ms versus tens of ms for a socket), host migration and chat become straightforward, rules
   are enforced by a server, and cost stops scaling with every heartbeat. **But it is not what limits sync
   quality today.** Problems #1, #7, #8 and #9 are client-side and cause most of the audible
   out-of-sync, and they would follow the app onto a WebSocket backend unchanged. At the
   current user count Firestore costs little. Do Phase 0 first; migrate when you want host
   migration or chat, or when Listen Together usage makes the Firestore bill noticeable (roughly
   from ~100 concurrent listeners sustained).
7. **Recommended architecture:** §4. Firebase Auth → Ktor WebSocket server, a room actor per room,
   the NTP-style clock, versioned server-authoritative state, a full queue on the server, an app-scoped
   `ListenTogetherSession`, and the protocol in a shared Kotlin module.
8. **Phased plan:** §7.2 (Phase 0 fixes → interface → server behind a switch → default → server-
   authoritative + migration + chat → retire Firestore rooms).
9. **Files likely to change:**
   - `data/sync/ListenTogetherManager.kt` → `FirebaseListenTogetherBackend`
   - `data/sync/ListenTogetherRoomController.kt` (sync math → drift controller; delete unused classes)
   - `ui/viewmodel/ListenTogetherViewModel.kt` (becomes a thin adapter)
   - `ui/viewmodel/AppViewModelProvider.kt`
   - `ui/AuralisApp.kt` (remove `onSync*` wiring; gates read session state)
   - `ui/screens/ListenTogetherSheet.kt` (connection state, reconnecting, host migration, chat)
   - `ui/components/TrackOptionsMenu.kt`
   - `data/service/AuralisAudioPlayer.kt` (ready callback, playback-speed nudging, LOCAL_PAUSE, resolved-id play)
   - `service/AuralisMediaService.kt` (session lifetime, task-removed behaviour, guest media commands)
   - `data/network/AudioStreamResolver.kt` (expose the resolved id for broadcast)
   - `domain/auth/GoogleAccountSyncManager.kt` (uid change during a room)
   - `firestore.rules`, `android/app/build.gradle.kts`
   - new: `:listen-together-protocol` module, `domain/listentogether/*`, `data/listentogether/ws/*`, `server/` (Ktor)
   - tests: the four `ListenTogether*Test` files plus new backend-contract and simulator tests
10. **Hidden problems:** §2 (18 items; the most important are #1, #2, #4, #7, #8, #9).
