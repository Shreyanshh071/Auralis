# Building Auralis Notch

Requires an Apple Silicon Mac, macOS SDK and Swift compiler from Xcode Command Line Tools. Full Xcode and third-party Swift packages are not required. Runtime: macOS 14 or later.

From this directory, run:

```sh
bash build.sh /tmp/AuralisNotch.app
/tmp/AuralisNotch.app/Contents/MacOS/AuralisNotch --self-test
/tmp/AuralisNotch.app/Contents/MacOS/AuralisNotch --preview
```

The source copy shipped inside the app contains its icon and this script and can be rebuilt with the same commands. In the repository, the build uses the existing Auralis icon from `windows/icons`.

The helper uses a distinct bundle ID (`com.auralis.music.notch`), writes no preferences, adds no login item or menu-bar item, and does not modify or replace Boring Notch. Auralis launches it as a child; no separate user installation is needed. It exits when stdin closes or its parent exits, and hides after three seconds without state updates. The parent retains its existing conservative Boring Notch conflict policy.

## Private protocol v1

Parent-to-child: one UTF-8 JSON object followed by a newline. The original fields are required. Optional `playbackRate` and `lyrics` fields extend protocol v1 compatibly.

```json
{"version":1,"type":"state","visible":true,"trackId":"example","title":"Auralis","artist":"Artist","artwork":"","playing":true,"buffering":false,"positionMs":30000,"durationMs":120000,"canPrevious":true,"canNext":true}
```

Child-to-parent: newline-delimited JSON with `version:1`, `type:"command"`, `action`, `trackId`, and optional `positionMs`. Supported actions are `playPause`, `previous`, `next`, `seek`, `openPlayer`, and `openSettings`. Commands affect only the current Auralis track. Close stdin to stop the helper. There is no network listener or system-wide Now Playing reader.

`--render-preview /absolute/path.png` exports the expanded native SwiftUI layout for visual checks. `--preview` opens a centered standalone test panel, away from the physical notch.

## Lyrics

The lyrics row uses Boring Notch beta’s under-artist placement. Auralis sends the current line plus two prefetched lines, using its selected/cached provider and smooth playback clock. `lyrics` contains `status` (`loading`, `ready`, `static`, `unavailable`), and `lines` with `timeMs`, optional `endMs`, verbatim `text`, `instrumental`, and timed `pieces`. A piece contains `text`, optional `timeMs`/`durationMs`, and `background`. Nested provider syllables are preferred; otherwise genuine word timing is retained. Unknown durations stay unknown and never receive a guessed sweep. Static lyrics are actual untimed text, with an unsynced indicator and no karaoke highlight.

The main player and notch call the same `PlayerLyricsContent` composable, including the chosen lyric style and bundled font. Kotlin keeps a persistent offscreen Compose scene while expanded and sends small `lyricsFrame` messages (base64 PNG, track ID and exact line text) over the private pipe. Swift displays those frames directly; there is no separate Swift karaoke animation. Frame messages never refresh the state heartbeat. The child reports `lyricsViewport` and `lyricsSize` so rendering stops when collapsed and fits the measured slot. Pausing or buffering freezes the audio position; track changes clear old frames. The expanded panel is 640 × 218 with lyrics (190 without); the closed notch keeps its original size.

For deterministic visual checks, `--render-preview /tmp/preview.png` accepts `--preview-position-ms 66500` and `--preview-lyrics-mode syllable|word|static`. The preview uses synthetic example text/timings only.

To preview the actual shared renderer, pass `--preview-frame /absolute/frame.png --preview-frame-text "the rendered line"`. Standalone previews without a frame show plain placeholder text; they do not emulate the animation.
