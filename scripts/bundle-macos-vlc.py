#!/usr/bin/env python3
"""Build a relocatable Apple Silicon libVLC payload; never depend on the tester's VLC."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import subprocess


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def bundle(source: Path, destination: Path, notices: Path):
    source = source.resolve()
    destination = destination.resolve()
    assert destination.name == "vlc" and destination != source and source not in destination.parents
    info = plistlib.loads((source / "Contents/Info.plist").read_bytes())
    version = info["CFBundleShortVersionString"]
    if version != "3.0.24":
        raise RuntimeError(f"Expected audited VLC 3.0.24, found {version} at {source}")
    native = source / "Contents/MacOS"
    if destination.exists():
        shutil.rmtree(destination)
    (destination / "plugins").mkdir(parents=True)
    for library in (native / "lib").glob("*.dylib"):
        shutil.copy2(library, destination / library.name, follow_symlinks=True)
    # These are VLC's application UI/auto-updater and OS notification integration, not playback.
    excluded = {"libmacosx_plugin.dylib", "libosx_notifications_plugin.dylib"}
    for plugin in (native / "plugins").glob("*.dylib"):
        if plugin.name not in excluded:
            shutil.copy2(plugin, destination / "plugins" / plugin.name)
    hrtfs = native / "share/hrtfs"
    if hrtfs.exists():
        shutil.copytree(hrtfs, destination / "share/hrtfs")
    binaries = sorted(destination.rglob("*.dylib"))
    for binary in binaries:
        architectures = run("lipo", "-archs", str(binary)).split()
        if architectures != ["arm64"]:
            raise RuntimeError(f"Expected arm64 only: {binary.name}: {architectures}")
        for line in run("otool", "-L", str(binary)).splitlines()[2:]:
            dependency = line.strip().split(" (")[0]
            if dependency.startswith(("/System/Library/", "/usr/lib/")):
                continue
            target = destination / Path(dependency).name
            if not target.is_file():
                raise RuntimeError(f"Unbundled dependency: {binary.name} -> {dependency}")
            relative = "../" if binary.parent.name == "plugins" else ""
            replacement = "@loader_path/" + relative + target.name
            run("install_name_tool", "-change", dependency, replacement, str(binary))
        run("codesign", "--force", "--sign", "-", str(binary))
    for name in ("COPYING", "COPYING.LIB", "AUTHORS", "README", "NOTICE.md"):
        shutil.copy2(notices / name, destination / name)
    manifest = {
        "version": version,
        "architecture": "arm64",
        "upstream": "https://download.videolan.org/pub/videolan/vlc/3.0.24/",
        "excluded_application_plugins": sorted(excluded),
        "libraries": {str(p.relative_to(destination)): hashlib.sha256(p.read_bytes()).hexdigest() for p in binaries},
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Bundled VLC {version}: {len(binaries)} signed arm64 libraries with only bundled/system dependencies")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--notices", type=Path, required=True)
    args = parser.parse_args()
    bundle(args.source, args.destination, args.notices)
