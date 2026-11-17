#!/usr/bin/env python3
"""Verify a macOS Auralis.app bundle has zero external non-system Mach-O dependencies."""
import argparse
from pathlib import Path
import subprocess

MACHO_MAGICS = {
    b"\xfe\xed\xfa\xce",
    b"\xfe\xed\xfa\xcf",
    b"\xce\xfa\xed\xfe",
    b"\xcf\xfa\xed\xfe",
    b"\xca\xfe\xba\xbe",
    b"\xbe\xba\xfe\xca",
}
ALLOWED_PREFIXES = ("/System/", "/usr/lib/", "@rpath/", "@loader_path/", "@executable_path/")


def verify(app: Path):
    app = app.resolve()
    launcher = app / "Contents/MacOS/Auralis"
    fontmanager = app / "Contents/runtime/Contents/Home/lib/libfontmanager.dylib"
    for required in (launcher, fontmanager):
        if not required.is_file():
            raise RuntimeError(f"Required bundle file missing: {required}")
        archs = subprocess.check_output(["lipo", "-archs", str(required)], text=True).split()
        if "arm64" not in archs:
            raise RuntimeError(f"Expected arm64 architecture for {required.relative_to(app)}: {archs}")

    external = []
    scanned = 0
    for item in sorted(app.rglob("*")):
        if item.is_symlink():
            if app not in item.resolve().parents and item.resolve() != app:
                raise RuntimeError(f"Symlink escapes app bundle: {item.relative_to(app)}")
            continue
        if not item.is_file():
            continue
        with item.open("rb") as fh:
            if fh.read(4) not in MACHO_MAGICS:
                continue
        res = subprocess.run(["otool", "-L", str(item)], capture_output=True, text=True)
        if res.returncode != 0 or "is not an object file" in res.stdout or "is not an object file" in res.stderr:
            continue
        scanned += 1
        id_res = subprocess.run(["otool", "-D", str(item)], capture_output=True, text=True)
        dylib_id = None
        if id_res.returncode == 0:
            id_lines = [line.strip() for line in id_res.stdout.splitlines()[1:] if line.strip()]
            if id_lines:
                dylib_id = id_lines[0]
        for idx, line in enumerate(res.stdout.splitlines()[1:]):
            dep = line.strip().split(" (", 1)[0]
            if idx == 0 and dylib_id and dep == dylib_id:
                continue
            if not dep.startswith(ALLOWED_PREFIXES):
                external.append((str(item.relative_to(app)), dep))

    if external:
        details = "\n".join(f"  {rel} -> {dep}" for rel, dep in external)
        raise RuntimeError(
            f"Found {len(external)} external non-system dependencies in {app.name}:\n{details}"
        )
    print(f"Verified {app.name}: {scanned} Mach-O binaries have only system or bundle-relative dependencies")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    verify(parser.parse_args().app)
