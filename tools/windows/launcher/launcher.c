// GameLodge.exe: starts the bundled Java runtime with the launcher (portable package and installed
// app). Keeps the JVM options and main class in one place for the start menu, the desktop icon
// and autostart; extra arguments (e.g. --autostart) are passed on.
#include <windows.h>
#include <wchar.h>

// Class cache (CDS): loading the UI's classes is most of the start time. The launcher writes an
// archive of them while running (gamelodge.cds); later starts map it. Its name is a hash of the
// runtime and the jars, so an update starts a new one.
static unsigned long long fnv(unsigned long long h, const void *data, size_t size) {
    const unsigned char *b = data;
    for (size_t i = 0; i < size; i++) { h ^= b[i]; h *= 1099511628211ULL; }
    return h;
}

static unsigned long long fileHash(unsigned long long h, const wchar_t *pattern) {
    WIN32_FIND_DATAW f;
    HANDLE find = FindFirstFileW(pattern, &f);
    if (find == INVALID_HANDLE_VALUE) return h;
    do {
        h = fnv(h, f.cFileName, wcslen(f.cFileName) * sizeof(wchar_t));
        h = fnv(h, &f.nFileSizeLow, sizeof(f.nFileSizeLow));
        h = fnv(h, &f.ftLastWriteTime, sizeof(f.ftLastWriteTime));
    } while (FindNextFileW(find, &f));
    FindClose(find);
    return h;
}

/** Archive path under %LOCALAPPDATA%\GameLodge\cache\cds; false without a usable folder. */
static BOOL cdsArchive(const wchar_t *dir, const wchar_t *java, wchar_t *out, size_t size) {
    wchar_t base[MAX_PATH], pattern[MAX_PATH];
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH);
    if (n == 0 || n >= MAX_PATH - 64) return FALSE;
    unsigned long long h = 14695981039346656037ULL;
    h = fileHash(h, java);
    swprintf(pattern, MAX_PATH, L"%s\\lib\\*.jar", dir);
    h = fileHash(h, pattern);
    wcscat_s(base, MAX_PATH, L"\\GameLodge");
    CreateDirectoryW(base, NULL);
    wcscat_s(base, MAX_PATH, L"\\cache");
    CreateDirectoryW(base, NULL);
    wcscat_s(base, MAX_PATH, L"\\cds");
    CreateDirectoryW(base, NULL);
    swprintf(out, size, L"%s\\gamelodge-%016llx.jsa", base, h);
    return TRUE;
}

#define JVM_OPTIONS L"-Xmx1g -XX:+UseZGC"
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

    static wchar_t line[32768], extra[4 * MAX_PATH];
    extra[0] = 0;
    // Opened by the user (no arguments, unlike autostart or a game started by the host): the
    // splash shows at once and closes with the first window.
    wchar_t splash[MAX_PATH];
    swprintf(splash, MAX_PATH, L"%s\\splash.png", dir);
    if ((!args || !*args) && GetFileAttributesW(splash) != INVALID_FILE_ATTRIBUTES) {
        swprintf(extra, 4 * MAX_PATH, L"\"-splash:%s\" ", splash);
    }
    wchar_t cds[MAX_PATH];
    if (cdsArchive(dir, java, cds, MAX_PATH)) {
        size_t used = wcslen(extra);
        if (GetFileAttributesW(cds) != INVALID_FILE_ATTRIBUTES) {
            swprintf(extra + used, 4 * MAX_PATH - used, L"\"-XX:SharedArchiveFile=%s\" ", cds);
        } else {
            swprintf(extra + used, 4 * MAX_PATH - used, L"-XX:+RecordDynamicDumpInfo \"-Dgamelodge.cds=%s\" ", cds);
        }
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
