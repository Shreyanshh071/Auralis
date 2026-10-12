# Auralis queued fixes status — 2026-10-13

The queued fixes are installed and verified:
- Artist overview/catalogue loading: commit 9716714, installed earlier.
- Sidebar lyrics tint, brighter app background and responsive settings sliders: installed earlier.
- Fullscreen lyrics text size/line spacing follow live settings, including single-line gaps.
- Queue history excludes unplayed positions and distinguishes duplicates; opens at Continue Playing, scroll up for history.
- Stats menus use backdrop glass; shortcut rows have full rounded hover highlights.
- Squiggly spacing adjusted to 48dp between the earlier sparse and dense versions; exact phone density unavailable.
- Wrapped opens the phone's animated fourteen-page story, with current-year measured stats, portraits, navigation and explicit playlist save.

The NVIDIA startup hint remains unresolved under the user's Auralis-only restriction. The user explicitly rejects global overlay/hint changes. GPU backends inject NVIDIA capture; previous native software artwork/glass diagnostics were too slow. No global NVIDIA or game settings were changed; software rendering is not enabled.

Final update backup: C:\Users\shrey\Downloads\Auralis-checkpoints\before-queued-fixes-20261013-011043. All 73 checked data files preserved; installed main/core jars match build; app reopened responding. Version1.0.0 and existing shortcut retained.

Evidence and details: docs/windows-port-status.md. Focused automated checks and headless visual renders passed. Native UI automation is unavailable (helper initialization os error3); no custom PowerShell UI automation used.

Preserve unrelated dirty context-menu/secondaryClick/OptionsSheetHost work. Only queued-fix hunks were staged for shared files. Local commits only; do not push. No installer/build remains running. User has authorized continuing/installing and previously finished Valorant.
