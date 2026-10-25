@echo off
rem Builds the launcher's Moonlight-Qt (Release x64) from C:\build\moonlight-qt.bundle into
rem C:\build\moonlight-qt with MSVC 2022 and Qt 6.11.1. Nested submodules come from their public repos.
setlocal
set "PATH=C:\Program Files\Git\cmd;%LOCALAPPDATA%\Programs\Python\Python312;C:\Program Files\7-Zip;%PATH%"
cd /d C:\build
if not exist moonlight-qt git clone C:\build\moonlight-qt.bundle moonlight-qt || exit /b 1
cd moonlight-qt
git fetch C:\build\moonlight-qt.bundle +launcher:refs/remotes/bundle/launcher || exit /b 1
git checkout -f --detach bundle/launcher || exit /b 1
git submodule sync --recursive
git submodule update --init --recursive || exit /b 1
rem Protocol additions the submodule's upstream lacks (idempotent, keeps the checkout's line endings).
python moonlight-common-c\patches\apollo-server-commands.py moonlight-common-c\moonlight-common-c\src || exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File setup-deps.ps1 || exit /b 1
for /f "usebackq delims=" %%i in (`scripts\vswhere.exe -latest -products * -property installationPath`) do call "%%i\VC\Auxiliary\Build\vcvarsall.bat" x64
set "PATH=C:\build\Qt\6.11.1\msvc2022_64\bin;%PATH%"
call scripts\build-arch.bat Release x64
