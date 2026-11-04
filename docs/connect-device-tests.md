# WebSocket Connect: real-device tests

The test builds sit beside the installed apps. They connect to the staging Worker
(`auralis-connect-staging.auralis018.workers.dev`), which holds no real user data apart from
the Connect session of whoever signs in to a test build.

## The builds

| | Installed app | Connect test build |
| --- | --- | --- |
| Android package | `com.auralis.music` | `com.auralis.music.connecttest`, named "Auralis Connect Test" |
| Windows | Installed in `%LOCALAPPDATA%\Auralis` | Run from the worktree's `windows\build\compose\binaries\main\app\Auralis\Auralis.exe`; window title "Auralis Connect Test" |
| Windows data | `%APPDATA%\Auralis` | `%APPDATA%\Auralis Connect Test`, with its own sign-in and single-instance lock |
| Connect | Firebase only | Firebase by default. The switch at the bottom of the device picker moves it to WebSocket |

The switch can't appear in any other build. Android's `CONNECT_REALTIME_URL` is empty in every
build type but `connectTest`, and Windows needs `-Pconnect.realtime.url` at build time.

Avoid the test builds' "check for updates": it would download the regular release.

## Before testing

1. **Android:** Firebase has only `com.auralis.music` registered, so the test build can't sign in until
   its package is added. In the Firebase console, open project `auralis-70cf8`, then Project settings, Add app, Android.
   Use package `com.auralis.music.connecttest` and SHA-1
   `85:80:EB:7F:A1:B4:22:AE:08:62:01:07:9A:09:DD:EB:EA:AA:E5:80`. Download the new `google-services.json`
   into `android/app/` of the main checkout. It still contains the existing app, so nothing changes for
   it. Then the test APK can be built with `assembleConnectTest`.
2. Install the test APK. It's a separate package, so it doesn't replace or touch the installed app's
   data.
3. Sign in to both test builds with the same account, then switch both to WebSocket Connect.

## Logs

- Android: `adb logcat -s AuralisConnect`. It says "over WebSocket" or "over Firestore" on joining.
- Windows: `%APPDATA%\Auralis Connect Test\auralis.log`.

## Tests

Record the result of each, and the time, so it can be matched against the server.

**Remote playback**

1. PC plays; the phone shows "Playing on" the PC within a few seconds.
2. From the phone: pause, play, next, previous, seek, and picking a song. Each happens on the PC once.
3. Hand off phone to PC and back from each device's picker. The audio moves without overlapping.
4. "This phone" and "This computer" on the player bring the audio back in one action.

**Reconnection**

5. PC Wi-Fi off for 30 s, then on. Connect recovers by itself and the phone sees the PC again.
6. Phone in airplane mode for 30 s, then off, then the same with Wi-Fi switched to mobile data.
7. PC asleep for 2 minutes. After waking, it reconnects and shows the right output.
8. Leave everything idle for 30 minutes on Wi-Fi. Both stay connected, or reconnect without help.

**Background (Android)**

9. PC plays, phone screen off for 10 minutes. On unlocking, the phone shows the right song and position.
10. Phone plays with its screen off. The PC pauses and skips it.
11. Swipe the test app away while it plays, then reopen it. Nothing restarts and nothing is handed over.
12. Note battery drain over an hour idle in the background, against the installed app.

**Offline actions**

13. Phone offline, tap a song; PC presses Next after that; reconnect. The PC's newer action stays.
14. Same, but the phone taps after the PC acts. The phone takes the output.

**Playback never depends on the server**

15. Phone offline: play, pause, skip, seek, and play a downloaded song. All immediate.
16. PC offline: the same with a streamed song.

**Switching transport**

17. While playing, switch WebSocket Connect off and on, on each device. The music never stops and
    nothing moves to the other device.
18. With the switch off, the test builds and the installed apps sync over Firebase as before.
19. With one device on WebSocket and one on Firebase, they don't see each other. This is expected. Neither
    takes the other's audio.

## Already verified without devices

All 117 engine tests, including switching transport without stopping or handing over the music. 47
server tests. Staging measurements are in `connect-staging-results.md`. None of these used the real apps.
