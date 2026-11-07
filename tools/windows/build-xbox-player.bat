@echo off
rem Builds GameLodge's Xbox player for Windows (xbox-player\windows\main.cpp) in C:\build\xbox-player with
rem MSVC 2022 and the WebView2 SDK (NuGet Microsoft.Web.WebView2, loader linked statically: no extra DLL).
setlocal
cd /d C:\build\xbox-player || exit /b 1
set "SDKVER=1.0.2903.40"
if not exist sdk-%SDKVER%\build\native\include\WebView2.h (
  powershell -NoProfile -Command "Invoke-WebRequest https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/%SDKVER% -OutFile webview2.zip; Expand-Archive -Force webview2.zip sdk-%SDKVER%" || exit /b 1
)
for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath`) do call "%%i\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul
cl /nologo /O2 /EHsc /std:c++17 /DUNICODE /D_UNICODE /MT main.cpp /I sdk-%SDKVER%\build\native\include ^
  sdk-%SDKVER%\build\native\x64\WebView2LoaderStatic.lib user32.lib gdi32.lib ole32.lib shell32.lib advapi32.lib version.lib ^
  /link /SUBSYSTEM:WINDOWS /OUT:gamelodge-xbox-player.exe || exit /b 1
echo built
