#!/bin/bash
set -euo pipefail
umask 077
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_source="$task_root/windows/build/compose/binaries/main/app/Auralis.app"
task_profile="$task_root/windows/build/sync-pilot/mac-profile"
task_library_url=""
case "${1:-}" in
  --library) task_library_url="https://auralis-connect-staging.auralis018.workers.dev" ;;
  "") ;;
  *) echo 'Usage: run-sync-pilot-macos.sh [--library]' >&2; exit 2 ;;
esac
if [[ ! -x "$task_source/Contents/MacOS/Auralis" ]]; then
  echo 'Build the desktop app first: ./gradlew :windows:createDistributable' >&2
  exit 1
fi
mkdir -p "$task_profile"
# jpackage's packaged JVM options override JAVA_TOOL_OPTIONS. Use a disposable clone so the
# normal signed app keeps its production configuration and the pilot guard sees only staging.
task_launcher="$(mktemp -d "$task_root/windows/build/sync-pilot/launcher.XXXXXX")"
trap 'rm -rf "$task_launcher"' EXIT
task_bundle="$task_launcher/Auralis.app"
/bin/cp -cR "$task_source" "$task_bundle"
python3 - "$task_bundle/Contents/app/Auralis.cfg" "$task_library_url" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
values = {
    'auralis.accountSyncUrl': 'https://auralis-connect-staging.auralis018.workers.dev',
    'auralis.librarySyncUrl': sys.argv[2],
    'auralis.connectRealtimeUrl': 'wss://auralis-connect-staging.auralis018.workers.dev/v1/connect',
    'auralis.togetherSyncUrl': 'https://auralis-connect-staging.auralis018.workers.dev',
}
prefixes = tuple('java-options=-D' + key + '=' for key in values)
lines = [line for line in path.read_text().splitlines() if not line.startswith(prefixes)]
at = lines.index('[JavaOptions]') + 1
lines[at:at] = ['java-options=-D' + key + '=' + value for key, value in values.items()]
path.write_text('\n'.join(lines) + '\n')
PY
codesign --force --sign - "$task_bundle"
codesign --verify --deep --strict "$task_bundle"
export JAVA_TOOL_OPTIONS="-Dauralis.syncPilot=true \"-Dauralis.dataDir=$task_profile\""
"$task_bundle/Contents/MacOS/Auralis"
