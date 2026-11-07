#!/usr/bin/env bash
# Builds the Windows Xbox player (xbox-player/windows → build/xbox-player/gamelodge-xbox-player.exe) on the
# Win11VM (MSVC, WebView2 SDK). Like tools/build-moonlight.sh: not during a stream unless --force.
set -euo pipefail
cd "$(dirname "$0")/.."
VM=../.android-dev/win11vm.sh
KEYS=../.android-dev/ssh-keys
SCP=(scp -q -O -i "$KEYS/win11vm_ed25519" -o UserKnownHostsFile="$KEYS/known_hosts" -o BatchMode=yes)
HOST=snage@192.168.168.68

if [ "${1:-}" = "--force" ] || "$VM" 'if (Get-Process sunshine_wgc_capture -ErrorAction SilentlyContinue) { exit 3 }'; then :; else
  echo "A stream is active on the build host - not building now." >&2; exit 3
fi
"$VM" 'New-Item -ItemType Directory C:\build\xbox-player -Force | Out-Null' >/dev/null
"${SCP[@]}" xbox-player/windows/main.cpp tools/windows/build-xbox-player.bat "$HOST:C:/build/xbox-player/"
"$VM" 'cmd /c "start /belownormal /wait /b cmd /c C:\build\xbox-player\build-xbox-player.bat > C:\build\xbox-player\build.log 2>&1"; if ($LASTEXITCODE -ne 0) { Get-Content C:\build\xbox-player\build.log -Tail 40; exit 1 }'
mkdir -p build/xbox-player
"${SCP[@]}" "$HOST:C:/build/xbox-player/gamelodge-xbox-player.exe" build/xbox-player/gamelodge-xbox-player.exe
ls -la build/xbox-player/gamelodge-xbox-player.exe
