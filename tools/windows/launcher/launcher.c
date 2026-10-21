// GameLodge.exe: starts the bundled Java runtime with the launcher (portable package and installed
// app). Keeps the JVM options and main class in one place for the start menu, the desktop icon
// and autostart; extra arguments (e.g. --autostart) are passed on.
#include <windows.h>
#include <wchar.h>

#define JVM_OPTIONS L"-Xmx1g -XX:+UseZGC -XX:+ZGenerational"
#define MAIN_CLASS L"io.github.thecodingdad.gamelodge.desktop.MainKt"

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show) {
    (void)instance; (void)previous; (void)show;
    wchar_t dir[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return 1;
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;

    // The runtime under the app's name (version info, taskbar), else the plain one.
    wchar_t java[MAX_PATH];
    swprintf(java, MAX_PATH, L"%s\\runtime\\bin\\GameLodge.exe", dir);
    if (GetFileAttributesW(java) == INVALID_FILE_ATTRIBUTES) swprintf(java, MAX_PATH, L"%s\\runtime\\bin\\javaw.exe", dir);

    static wchar_t line[32768], extra[2 * MAX_PATH];
    extra[0] = 0;
    // Opened by the user (no arguments, unlike autostart or a game started by the host): the
    // splash shows at once and closes with the first window.
    wchar_t splash[MAX_PATH];
    swprintf(splash, MAX_PATH, L"%s\\splash.png", dir);
    if ((!args || !*args) && GetFileAttributesW(splash) != INVALID_FILE_ATTRIBUTES) {
        swprintf(extra, 2 * MAX_PATH, L"\"-splash:%s\" ", splash);
    }
    swprintf(line, 32768, L"\"%s\" " JVM_OPTIONS L" %s-cp \"%s\\lib\\*\" " MAIN_CLASS L" %s", java, extra, dir, args ? args : L"");

    STARTUPINFOW si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(java, line, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"GameLodge could not start its Java runtime (runtime folder missing or damaged).", L"GameLodge", MB_ICONERROR);
        return 1;
    }
    // Its window may come to the front.
    AllowSetForegroundWindow(pi.dwProcessId);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
