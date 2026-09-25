# Player and audio settings

The existing scrollable AlertDialog contains Player, Queue, and Misc sections. Streaming quality, spatial audio, gapless playback, and remove silence remain in Player.

## Runtime ownership

- `SettingsDataStore` persists each switch with an individual atomic preference edit. Defaults preserve Auralis behavior: persistent queue, auto load more, and stop on task clear are on; mute pause, Bluetooth resume, and screen wake are off.
- `AuralisAudioPlayer` observes preferences for its existing application lifetime. Queue restoration reads the saved preference before restoring. Save/clear operations share a mutex, recheck the preference, and cannot restore an older snapshot after disabling persistence. Disabling persistence removes the saved queue, leaving the live queue intact; enabling it saves the current queue.
- Automatic radio loading uses the existing InnerTube radio, history, and artist-search sources and `AudioQueueManager` deduplication. Its job now belongs to the existing playback owner, so clearing the Activity does not cancel future loading. Turning it off cancels pending work. Playback changes cancel obsolete requests. Explicit playlist queues remain finite; existing manual Next wraparound and repeat behavior remain intact. No second service or queue is created.
- `AuralisMediaService.onTaskRemoved` checks the preference before its existing cleanup. With stop-on-clear disabled, playing/buffering audio retains the existing session and notification. Android force-stop remains distinct from removing the recent task.
- Device media volume/mute is observed by the playback owner, with a check in its existing progress ticker for devices that delay settings notifications. Pausing calls the shared dual-engine pause path. Raising volume does not unexpectedly restart playback. Pause relies on the running service's state observers instead of starting another foreground-service request.
- Bluetooth resume uses `AudioDeviceCallback` for new A2DP/LE output devices. The initial device inventory is suppressed. It resumes the existing current song through the shared resume path, excluding muted output, forced speaker routing, active buffering/playback, and Listen Together guests. It does not start a separate receiver/service to launch a force-stopped app.
- The existing expanded-player state controls `View.keepScreenOn` while playing. A `DisposableEffect` restores the previous flag when the player collapses, pauses, or leaves composition.

## Reference implementation inspected before implementation

Reference: an open-source GPL-3.0 music player (inspected 2026-09-24).

- Playback service: preference-gated save/restore, near-end queue pagination, device-volume callbacks, audio-device callbacks, and callback cleanup.
- Persisted queue model: saved queue items, index, position, and queue metadata.
- Main activity: finishing-Activity check for stop-on-task-clear. Auralis uses its existing service task-removal hook instead.
- Expanded player: expanded + playing + preference wake condition and effect cleanup.
- Player settings screen: preference-backed controls.

These were architecture references; Auralis retains its DataStore queue format, dual engines, queue manager, notification service, and Compose player state.

## Verification

Verified on 2026-09-24: debug APK built and installed on device `ZA222LJBW2`; 36 targeted JVM tests and 3 device tests passed. Logs are in `scratch/player-settings-unit-tests.log`, `scratch/player-settings-device-tests.log` (runtime checks), and `scratch/player-settings-dialog-test.log` (modal check).

Targeted JVM checks cover real DataStore close/reopen in both switch positions, concurrent independent edits, legacy settings updates, disabled radio loading, queue serialization, queue navigation, gapless queue transitions, and playback-position preservation.

Device instrumentation covers scrolling and persisting all six switches inside the existing modal, disabling/re-enabling queue persistence without clearing the live queue, and pausing real local PCM playback when the system media stream is muted.

Hardware acceptance checks: reconnect a Bluetooth headset with the option both off/on; remove the recent task during native and fallback-engine playback with stop-on-clear both off/on; verify screen timeout while expanded/playing, collapsed, and paused. Instrumentation does not emulate a physical Bluetooth reconnection.
