#!/bin/bash
set -euo pipefail
umask 077
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_app="$task_root/windows/build/compose/binaries/main/app/Auralis.app/Contents/MacOS/Auralis"
task_profile="$task_root/windows/build/sync-pilot/mac-profile"
if [[ ! -x "$task_app" ]]; then
  echo 'Build the desktop app first: ./gradlew :windows:createDistributable' >&2
  exit 1
fi
mkdir -p "$task_profile"
export JAVA_TOOL_OPTIONS="-Dauralis.syncPilot=true -Dauralis.accountSyncUrl=https://auralis-connect-staging.auralis018.workers.dev -Dauralis.librarySyncUrl= \"-Dauralis.dataDir=$task_profile\""
exec "$task_app"
