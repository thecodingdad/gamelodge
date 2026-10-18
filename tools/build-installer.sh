#!/usr/bin/env bash
# Builds the Windows installer (bin/GameLodge-Setup.exe): stages the app with Gradle here,
# compiles desktop/windows/GameLodge.iss with Inno Setup on the Windows build host
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
RCEDIT_URL=https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe
RCEDIT_SHA256=3E7801DB1A5EDBEC91B49A24A094AAD776CB4515488EA5A4CA2289C400EADE2A
version=$(sed -n 's/^launcher.versionName=//p' gradle.properties)
./gradlew -q --offline :desktop:windowsApp
stage=desktop/build/windows/GameLodge
zip=$(mktemp --suffix=.zip)
rm -f "$zip"
python3 -c "import shutil,sys; shutil.make_archive(sys.argv[1][:-4], 'zip', sys.argv[2])" "$zip" "$stage"
"$VM" 'Remove-Item C:\build\installer\stage, C:\build\installer\out -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory C:\build\installer\stage, C:\build\installer\out -Force | Out-Null'
"${SCP[@]}" "$zip" "$HOST:C:/build/installer/app.zip"
"${SCP[@]}" "desktop/windows/GameLodge.iss" "$HOST:C:/build/installer/GameLodge.iss"
"${SCP[@]}" desktop/windows/icon.ico "$HOST:C:/build/installer/icon.ico"
rm -f "$zip"
# GameLodge.exe: the runtime's javaw.exe under the app's name, version info and icon (Windows shows
# it in the taskbar, the task manager and when the setup closes a running launcher).
"$VM" "Expand-Archive C:\\build\\installer\\app.zip -DestinationPath C:\\build\\installer\\stage -Force; \$rc = 'C:\\build\\rcedit\\rcedit-x64.exe'; if (-not (Test-Path \$rc)) { New-Item -ItemType Directory C:\\build\\rcedit -Force | Out-Null; Invoke-WebRequest -UseBasicParsing $RCEDIT_URL -OutFile \$rc }; if ((Get-FileHash \$rc -Algorithm SHA256).Hash -ne '$RCEDIT_SHA256') { Write-Error 'rcedit checksum mismatch'; exit 1 }; \$exe = 'C:\\build\\installer\\stage\\runtime\\bin\\GameLodge.exe'; Copy-Item C:\\build\\installer\\stage\\runtime\\bin\\javaw.exe \$exe; & \$rc \$exe --set-icon C:\\build\\installer\\icon.ico --set-version-string FileDescription GameLodge --set-version-string ProductName GameLodge --set-version-string CompanyName thecodingdad --set-version-string OriginalFilename GameLodge.exe --set-version-string InternalName GameLodge --set-file-version $version --set-product-version $version; if (\$LASTEXITCODE -ne 0) { exit 1 }; cmd /c \"start /belownormal /wait /b C:\\build\\InnoSetup\\ISCC.exe /Q /DAppVersion=$version /DSourceDir=C:\\build\\installer\\stage /DOutputDir=C:\\build\\installer\\out C:\\build\\installer\\GameLodge.iss\"; if (\$LASTEXITCODE -ne 0) { exit 1 }"
"${SCP[@]}" "$HOST:C:/build/installer/out/GameLodge-Setup.exe" bin/GameLodge-Setup.exe
echo "built: bin/GameLodge-Setup.exe ($version, $(du -h bin/GameLodge-Setup.exe | cut -f1))"
