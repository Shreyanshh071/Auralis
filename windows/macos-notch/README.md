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

Parent-to-child: one UTF-8 JSON object followed by a newline. All fields are required.

```json
{"version":1,"type":"state","visible":true,"trackId":"example","title":"Auralis","artist":"Artist","artwork":"","playing":true,"buffering":false,"positionMs":30000,"durationMs":120000,"canPrevious":true,"canNext":true}
```

Child-to-parent: newline-delimited JSON with `version:1`, `type:"command"`, `action`, `trackId`, and optional `positionMs`. Supported actions are `playPause`, `previous`, `next`, `seek`, `openPlayer`, and `openSettings`. Commands affect only the current Auralis track. Close stdin to stop the helper. There is no network listener or system-wide Now Playing reader.

`--render-preview /absolute/path.png` exports the expanded native SwiftUI layout for visual checks. `--preview` opens a centered standalone test panel, away from the physical notch.
