#!/usr/bin/env bash
# Builds the Windows installer (bin/MoonlightLauncher-Setup.exe): stages the app with Gradle here,
# compiles desktop/windows/Moonlight Launcher.iss with Inno Setup on the Windows build host
# (C:\build\InnoSetup) and copies the setup back. Needs ../.android-dev/win11vm.sh (SSH access);
# scp runs with -O, as SFTP to Windows OpenSSH stops at 200 KiB. Refuses while a stream is active
# there, unless called with --force (only when the user said so).
set -euo pipefail
cd "$(dirname "$0")/.."
VM=../.android-dev/win11vm.sh
KEYS=../.android-dev/ssh-keys
SCP=(scp -q -O -i "$KEYS/win11vm_ed25519" -o UserKnownHostsFile="$KEYS/known_hosts" -o BatchMode=yes)
HOST=snage@192.168.168.68

if [ "${1:-}" = "--force" ] || "$VM" 'if (Get-Process sunshine_wgc_capture -ErrorAction SilentlyContinue) { exit 3 }'; then :; else
  echo "A stream is active on the build host - not building now." >&2; exit 3
fi
[ -f build/moonlight/MoonlightPortable-x64.zip ] || { echo "build/moonlight/MoonlightPortable-x64.zip missing (tools/build-moonlight.sh)" >&2; exit 1; }
version=$(sed -n 's/^launcher.versionName=//p' gradle.properties)
./gradlew -q --offline :desktop:windowsApp
stage=desktop/build/windows/MoonlightLauncher
zip=$(mktemp --suffix=.zip)
rm -f "$zip"
python3 -c "import shutil,sys; shutil.make_archive(sys.argv[1][:-4], 'zip', sys.argv[2])" "$zip" "$stage"
"$VM" 'Remove-Item C:\build\installer\stage, C:\build\installer\out -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory C:\build\installer\stage, C:\build\installer\out -Force | Out-Null'
"${SCP[@]}" "$zip" "$HOST:C:/build/installer/app.zip"
"${SCP[@]}" "desktop/windows/Moonlight Launcher.iss" "$HOST:C:/build/installer/MoonlightLauncher.iss"
"${SCP[@]}" desktop/windows/icon.ico "$HOST:C:/build/installer/icon.ico"
rm -f "$zip"
"$VM" "Expand-Archive C:\\build\\installer\\app.zip -DestinationPath C:\\build\\installer\\stage -Force; cmd /c \"start /belownormal /wait /b C:\\build\\InnoSetup\\ISCC.exe /Q /DAppVersion=$version /DSourceDir=C:\\build\\installer\\stage /DOutputDir=C:\\build\\installer\\out C:\\build\\installer\\MoonlightLauncher.iss\"; if (\$LASTEXITCODE -ne 0) { exit 1 }"
"${SCP[@]}" "$HOST:C:/build/installer/out/MoonlightLauncher-Setup.exe" bin/MoonlightLauncher-Setup.exe
echo "built: bin/MoonlightLauncher-Setup.exe ($version, $(du -h bin/MoonlightLauncher-Setup.exe | cut -f1))"
