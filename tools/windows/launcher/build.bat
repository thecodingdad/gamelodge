@echo off
rem Builds GameLodge.exe (the starter) and runtime-GameLodge.exe (javaw.exe with the app's version info
rem and icon) in C:\build\launcher. Arguments: version as "0.1.0".
setlocal
cd /d C:\build\launcher || exit /b 1
set "VER=%~1"
for /f "tokens=1-3 delims=." %%a in ("%VER%") do set "VERC=%%a,%%b,%%c,0"
for /f "usebackq delims=" %%i in (`"C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath`) do call "%%i\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul || exit /b 1
rc /nologo /dVER_COMMA=%VERC% /dVER_TEXT=\"%VER%\" /fo launcher.res launcher.rc || exit /b 1
cl /nologo /O2 /MT /W3 /DUNICODE /D_UNICODE launcher.c launcher.res /link /SUBSYSTEM:WINDOWS user32.lib /OUT:GameLodge.exe || exit /b 1
copy /y javaw.exe runtime-GameLodge.exe >nul || exit /b 1
C:\build\rcedit\rcedit-x64.exe runtime-GameLodge.exe --set-icon icon.ico --set-version-string FileDescription GameLodge --set-version-string ProductName GameLodge --set-version-string CompanyName thecodingdad --set-version-string OriginalFilename GameLodge.exe --set-version-string InternalName GameLodge --set-file-version %VER% --set-product-version %VER% || exit /b 1
