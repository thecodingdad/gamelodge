#!/usr/bin/env bash
# Installs the setup built by tools/build-installer.sh on the Win11VM (the user's gaming PC).
# Refuses while a stream is active or a game runner (GameLodge.exe --run) is alive: the runner
# uses the installation's files and must never be stopped. Stops only the launcher itself, installs
# silently with a log, compares the installed files with the build, and starts the launcher again
# in the user's session (in the tray, like at Windows start) when it was running before.
set -euo pipefail
cd "$(dirname "$0")/.."
VM=../.android-dev/win11vm.sh

"$VM" '
$ErrorActionPreference = "Stop"
if (Get-Process sunshine_wgc_capture -ErrorAction SilentlyContinue) { "A stream is active - not installing."; exit 3 }
$runners = @(Get-CimInstance Win32_Process | ? { $_.CommandLine -like "*--run*" -and $_.Path -like "*GameLodge*" })
if ($runners.Count -gt 0) { "A game started through GameLodge runs - not installing."; exit 3 }
$launchers = @(Get-CimInstance Win32_Process | ? { $_.CommandLine -like "*MainKt*" -and $_.CommandLine -notlike "*--run*" })
$launchers | % { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep 2
$p = Start-Process C:\build\installer\out\GameLodge-Setup.exe -ArgumentList "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG=C:\build\installer\install.log" -Wait -PassThru
"setup exit $($p.ExitCode)"
$d = "$env:LOCALAPPDATA\Programs\GameLodge"
$ok = $p.ExitCode -eq 0
foreach ($s in "runtime", "lib", "moonlight") {
  $have = (Get-ChildItem "$d\$s" -Recurse -File -ErrorAction SilentlyContinue | Measure-Object).Count
  $want = (Get-ChildItem "C:\build\installer\stage\$s" -Recurse -File | Measure-Object).Count
  "{0}: {1}/{2}" -f $s, $have, $want
  if ($have -ne $want) { $ok = $false }
}
if ($launchers.Count -gt 0 -and $ok) {
  # Started from SSH it would run outside the desktop session: a one-time task in the user session.
  # schtasks warns about the start time on stderr (never used: the task runs by /Run).
  $ErrorActionPreference = "Continue"
  schtasks /Create /TN GameLodgeStart /TR "`"$d\GameLodge.exe`" --autostart" /SC ONCE /ST 00:00 /IT /F 2>&1 | Out-Null
  schtasks /Run /TN GameLodgeStart 2>&1 | Out-Null
  # The native starter needs a while until the launcher (Java) runs.
  $started = 0
  for ($i = 0; $i -lt 30 -and $started -eq 0; $i++) {
    Start-Sleep 1
    $started = @(Get-CimInstance Win32_Process | ? { $_.CommandLine -like "*MainKt*" -and $_.CommandLine -notlike "*--run*" }).Count
  }
  schtasks /Delete /TN GameLodgeStart /F 2>&1 | Out-Null
  if ($started -eq 0) { "launcher NOT started again"; exit 1 }
  "launcher started again (tray)"
}
if (-not $ok) { exit 1 }
'
