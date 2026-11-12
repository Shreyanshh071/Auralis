#!/usr/bin/env python3
"""Prepare a pinned Chromium fallback for the existing private YouTube sign-in flow."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile


def bundle(manifest_file: Path, cache: Path, output: Path):
    manifest = json.loads(manifest_file.read_text())
    url = manifest["url"]
    if not url.startswith("https://storage.googleapis.com/chromium-browser-snapshots/Mac_Arm/"):
        raise RuntimeError("Unexpected Chromium distribution source")
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"chromium-mac-arm64-{manifest['revision']}.zip"
    if not archive.is_file():
        partial = archive.with_suffix(".partial")
        urllib.request.urlretrieve(url, partial)
        partial.replace(archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != manifest["sha256"]:
        raise RuntimeError(f"Chromium checksum mismatch: {archive}")
    with zipfile.ZipFile(archive) as zip:
        if any(".." in Path(entry.filename).parts or Path(entry.filename).is_absolute() for entry in zip.infolist()):
            raise RuntimeError("Unsafe Chromium archive path")
    output.mkdir(parents=True, exist_ok=True)
    app = output / "Chromium.app"
    if app.exists():
        shutil.rmtree(app)
    with tempfile.TemporaryDirectory(prefix="auralis-chromium-") as temporary:
        subprocess.run(["ditto", "-x", "-k", str(archive), temporary], check=True)
        unpacked = next(Path(temporary).glob("*/Chromium.app"))
        subprocess.run(["ditto", str(unpacked), str(app)], check=True)
    info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
    minimum = tuple(int(n) for n in info.get("LSMinimumSystemVersion", "0").split("."))
    if minimum > (14, 0):
        raise RuntimeError(f"Chromium requires macOS {minimum}, exceeding Auralis's macOS 14 minimum")
    executable = app / "Contents/MacOS/Chromium"
    architectures = subprocess.check_output(["lipo", "-archs", str(executable)], text=True).split()
    if architectures != ["arm64"]:
        raise RuntimeError(f"Unexpected Chromium architecture: {architectures}")
    for item in app.rglob("*"):
        if item.is_symlink() and app.resolve() not in item.resolve().parents:
            raise RuntimeError(f"Chromium symlink escapes its application: {item}")
    # Snapshot executables have ad-hoc signatures but may not seal the complete application.
    # Preserve existing sandbox/JIT metadata while sealing the local distribution.
    subprocess.run(["codesign", "--force", "--deep", "--sign", "-", "--preserve-metadata=entitlements,flags,runtime", str(app)], check=True)
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
    for name in ("LICENSE", "NOTICE.md", "chromium.json"):
        shutil.copy2(manifest_file.parent / name, output / name)
    print(f"Bundled private Chromium {info['CFBundleShortVersionString']}, arm64, revision {manifest['revision']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    bundle(args.manifest, args.cache, args.output)
