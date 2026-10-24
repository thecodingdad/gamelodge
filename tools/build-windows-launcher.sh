#!/usr/bin/env bash
# Builds build/windows-launcher/GameLodge.exe (native starter, tools/windows/launcher) and
# build/windows-launcher/runtime-GameLodge.exe (the bundled runtime's javaw.exe with the app's version
# info and icon) with MSVC and rcedit on the Windows build host (C:\build, low priority; also while a
# stream runs). The Windows package and the installer take both from there.
set -euo pipefail
cd "$(dirname "$0")/.."
VM=../.android-dev/win11vm.sh
KEYS=../.android-dev/ssh-keys
SCP=(scp -q -O -i "$KEYS/win11vm_ed25519" -o UserKnownHostsFile="$KEYS/known_hosts" -o BatchMode=yes)
HOST=snage@192.168.168.68
RCEDIT_URL=https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe
RCEDIT_SHA256=3E7801DB1A5EDBEC91B49A24A094AAD776CB4515488EA5A4CA2289C400EADE2A
version=$(sed -n 's/^launcher.versionName=//p' gradle.properties)

./gradlew -q --offline :desktop:downloadWindowsJdk
jre=$(sed -n 's/^val windowsJdkZip = gradle.gradleUserHomeDir.resolve("\(.*\)")$/\1/p' desktop/build.gradle.kts)
jre="${GRADLE_USER_HOME:-$HOME/.gradle}/$jre"
work=$(mktemp -d)
unzip -q -j "$jre" '*/bin/javaw.exe' -d "$work"
"$VM" "New-Item -ItemType Directory C:\\build\\launcher -Force | Out-Null; \$rc = 'C:\\build\\rcedit\\rcedit-x64.exe'; if (-not (Test-Path \$rc)) { New-Item -ItemType Directory C:\\build\\rcedit -Force | Out-Null; Invoke-WebRequest -UseBasicParsing $RCEDIT_URL -OutFile \$rc }; if ((Get-FileHash \$rc -Algorithm SHA256).Hash -ne '$RCEDIT_SHA256') { Write-Error 'rcedit checksum mismatch'; exit 1 }"
"${SCP[@]}" tools/windows/launcher/launcher.c tools/windows/launcher/launcher.rc tools/windows/launcher/build.bat desktop/windows/icon.ico "$work/javaw.exe" "$HOST:C:/build/launcher/"
rm -rf "$work"
"$VM" "cmd /c \"start /belownormal /wait /b cmd /c C:\\build\\launcher\\build.bat $version > C:\\build\\launcher\\build.log 2>&1\"; if (\$LASTEXITCODE -ne 0) { Get-Content C:\\build\\launcher\\build.log -Tail 30; exit 1 }"
mkdir -p build/windows-launcher
"${SCP[@]}" "$HOST:C:/build/launcher/GameLodge.exe" "$HOST:C:/build/launcher/runtime-GameLodge.exe" build/windows-launcher/
echo "built: build/windows-launcher/GameLodge.exe, runtime-GameLodge.exe ($version)"
