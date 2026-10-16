#!/usr/bin/env bash
# Builds the moonlight-qt submodule (branch launcher, committed state) on the Windows build host and
# copies the portable package to build/moonlight/MoonlightPortable-x64.zip for the desktop app.
# Needs ../.android-dev/win11vm.sh (SSH access); scp runs with -O, as SFTP to Windows OpenSSH stops
# at 200 KiB. Refuses while a stream is active there, unless called with --force (only when the
# user said so, e.g. they are just on the desktop).
set -euo pipefail
cd "$(dirname "$0")/.."
VM=../.android-dev/win11vm.sh
KEYS=../.android-dev/ssh-keys
SCP=(scp -q -O -i "$KEYS/win11vm_ed25519" -o UserKnownHostsFile="$KEYS/known_hosts" -o BatchMode=yes)
HOST=snage@192.168.168.68

if [ "${1:-}" = "--force" ] || "$VM" 'if (Get-Process sunshine_wgc_capture -ErrorAction SilentlyContinue) { exit 3 }'; then :; else
  echo "A stream is active on the build host - not building now." >&2; exit 3
fi
bundle=$(mktemp --suffix=.bundle)
git -C moonlight-qt bundle create "$bundle" launcher 2>/dev/null
"${SCP[@]}" "$bundle" "$HOST:C:/build/moonlight-qt.bundle"
"${SCP[@]}" tools/windows/build-moonlight.bat "$HOST:C:/build/build-moonlight.bat"
rm -f "$bundle"
"$VM" 'cmd /c "start /belownormal /wait /b cmd /c C:\build\build-moonlight.bat > C:\build\build-moonlight.log 2>&1"; if ($LASTEXITCODE -ne 0) { Get-Content C:\build\build-moonlight.log -Tail 40; exit 1 }'
zip=$("$VM" '(Get-ChildItem C:\build\moonlight-qt\build\installer-x64-release\MoonlightPortable-x64-*.zip | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName' | tr -d '\r')
mkdir -p build/moonlight
"${SCP[@]}" "$HOST:${zip//\\//}" build/moonlight/MoonlightPortable-x64.zip
echo "built: build/moonlight/MoonlightPortable-x64.zip ($(git -C moonlight-qt rev-parse --short HEAD))"
