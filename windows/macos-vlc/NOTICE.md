# Bundled VLC playback engine

VLC 3.0.24 by VideoLAN and its contributors. libVLC/libvlccore and playback plugins are copied from the official Apple Silicon VLC application. No VLC installation is required on the recipient's Mac. The VLC application UI, updater and native notification plugin are excluded.

The core and plugin license terms are retained in COPYING (GPL v2) and COPYING.LIB (LGPL v2.1); individual components may use these terms or later versions. See AUTHORS and README. Corresponding upstream source and contributed-library source are available in the official release directory: https://download.videolan.org/pub/videolan/vlc/3.0.24/ . The main source archive is https://download.videolan.org/pub/videolan/vlc/3.0.24/vlc-3.0.24.tar.xz . VideoLAN source/build instructions: https://code.videolan.org/videolan/vlc/-/tree/3.0.24 .

Auralis changes the copied binaries' dynamic dependency paths to `@loader_path` and applies local ad-hoc signatures; it does not modify their source code. The relocation/copy script is `scripts/bundle-macos-vlc.py` in Auralis's corresponding source. `manifest.json` records the exact payload and hashes. No browser/account data or VLC preferences are copied.
