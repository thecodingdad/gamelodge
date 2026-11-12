// GameLodge Xbox player for Windows: a full screen window with the player page (xbox-player/dist)
// in WebView2 (the Edge engine Windows brings). GameLodge starts it and talks to it through
// stdin/stdout, one JSON message per line: lines from GameLodge go to the page, the page's messages
// come out on stdout. Closing the window (or GameLodge closing stdin) ends the process.
//
// Usage: gamelodge-xbox-player.exe <player folder> <user data folder> [--windowed]

#include <windows.h>
#include <shobjidl.h>
#include <shellscalingapi.h>
#include <wrl.h>
#include <string>
#include <thread>
#include <cstdio>
#include "WebView2.h"
#include "WebView2EnvironmentOptions.h"

#pragma comment(lib, "shcore.lib")

using namespace Microsoft::WRL;

namespace {

const UINT WM_TO_PAGE = WM_APP + 1;
const wchar_t* HOST = L"gamelodge.player";

HWND window = nullptr;
ComPtr<ICoreWebView2Controller> controller;
ComPtr<ICoreWebView2> webview;
CRITICAL_SECTION outLock;

std::wstring widen(const std::string& s) {
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
    return w;
}

std::string narrow(const wchar_t* w) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return "";
    std::string s(n - 1, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), n, nullptr, nullptr);
    return s;
}

void writeLine(const std::string& line) {
    EnterCriticalSection(&outLock);
    fwrite(line.data(), 1, line.size(), stdout);
    fputc('\n', stdout);
    fflush(stdout);
    LeaveCriticalSection(&outLock);
}

void fitToWindow() {
    if (!controller) return;
    RECT r;
    GetClientRect(window, &r);
    controller->put_Bounds(r);
}

// Lines from GameLodge (stdin) to the page; end of input closes the window.
void readInput() {
    std::string line;
    int c;
    while ((c = fgetc(stdin)) != EOF) {
        if (c == '\n') {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) PostMessageW(window, WM_TO_PAGE, 0, (LPARAM) new std::wstring(widen(line)));
            line.clear();
        } else {
            line.push_back((char)c);
        }
    }
    PostMessageW(window, WM_CLOSE, 0, 0);
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_SIZE:
        fitToWindow();
        return 0;
    case WM_TO_PAGE: {
        auto* text = (std::wstring*)lp;
        if (webview) webview->PostWebMessageAsString(text->c_str());
        delete text;
        return 0;
    }
    case WM_SETCURSOR:
        // No pointer over the stream.
        if (LOWORD(lp) == HTCLIENT) { SetCursor(nullptr); return TRUE; }
        break;
    case WM_CLOSE:
        writeLine("{\"t\":\"closed\"}");
        if (controller) controller->Close();
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    int argc = 0;
    wchar_t** argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argc < 3) return 2;
    std::wstring playerDir = argv[1];
    std::wstring dataDir = argv[2];
    bool windowed = argc > 3 && wcscmp(argv[3], L"--windowed") == 0;

    InitializeCriticalSection(&outLock);
    SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    WNDCLASSW wc = {};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = instance;
    wc.lpszClassName = L"GameLodgeXboxPlayer";
    wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    // GameLodge's icon (an .ico the launcher writes), so the window looks like the launcher's.
    const wchar_t* iconPath = _wgetenv(L"GAMELODGE_ICON");
    HICON bigIcon = iconPath ? (HICON)LoadImageW(nullptr, iconPath, IMAGE_ICON, GetSystemMetrics(SM_CXICON), GetSystemMetrics(SM_CYICON), LR_LOADFROMFILE) : nullptr;
    HICON smallIcon = iconPath ? (HICON)LoadImageW(nullptr, iconPath, IMAGE_ICON, GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), LR_LOADFROMFILE) : nullptr;
    wc.hIcon = bigIcon;
    RegisterClassW(&wc);
    // One taskbar button with the launcher (before any window is created), like its Moonlight streams.
    if (const wchar_t* appId = _wgetenv(L"GAMELODGE_APPID")) SetCurrentProcessExplicitAppUserModelID(appId);

    // Full screen on the monitor GameLodge's window is on (the primary one as fallback).
    POINT cursor;
    GetCursorPos(&cursor);
    HWND front = GetForegroundWindow();
    HMONITOR monitor = front ? MonitorFromWindow(front, MONITOR_DEFAULTTOPRIMARY) : MonitorFromPoint(cursor, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO mi = { sizeof(mi) };
    GetMonitorInfoW(monitor, &mi);
    RECT r = mi.rcMonitor;
    DWORD style = windowed ? WS_OVERLAPPEDWINDOW : WS_POPUP;
    if (windowed) { r.left += 100; r.top += 100; r.right = r.left + 1280; r.bottom = r.top + 720; }
    const wchar_t* title = _wgetenv(L"GAMELODGE_TITLE");
    window = CreateWindowExW(0, wc.lpszClassName, title ? title : L"GameLodge", style | WS_VISIBLE,
        r.left, r.top, r.right - r.left, r.bottom - r.top, nullptr, nullptr, instance, nullptr);
    if (bigIcon) SendMessageW(window, WM_SETICON, ICON_BIG, (LPARAM)bigIcon);
    if (smallIcon) SendMessageW(window, WM_SETICON, ICON_SMALL, (LPARAM)smallIcon);
    SetForegroundWindow(window);

    std::wstring args = L"--autoplay-policy=no-user-gesture-required --disable-features=msSmartScreenProtection";
    auto options = Make<CoreWebView2EnvironmentOptions>();
    options->put_AdditionalBrowserArguments(args.c_str());

    HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(nullptr, dataDir.c_str(), options.Get(),
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [playerDir](HRESULT result, ICoreWebView2Environment* env) -> HRESULT {
                if (FAILED(result) || !env) {
                    writeLine("{\"t\":\"state\",\"state\":\"failed\",\"detail\":\"WebView2 runtime missing\"}");
                    PostMessageW(window, WM_CLOSE, 0, 0);
                    return S_OK;
                }
                env->CreateCoreWebView2Controller(window, Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                    [playerDir](HRESULT result, ICoreWebView2Controller* c) -> HRESULT {
                        if (FAILED(result) || !c) {
                            writeLine("{\"t\":\"state\",\"state\":\"failed\",\"detail\":\"WebView2 failed\"}");
                            PostMessageW(window, WM_CLOSE, 0, 0);
                            return S_OK;
                        }
                        controller = c;
                        controller->get_CoreWebView2(&webview);
                        ComPtr<ICoreWebView2Controller2> c2;
                        if (SUCCEEDED(controller.As(&c2))) c2->put_DefaultBackgroundColor({ 255, 0, 0, 0 });
                        ComPtr<ICoreWebView2Settings> settings;
                        webview->get_Settings(&settings);
                        settings->put_AreDefaultContextMenusEnabled(FALSE);
                        settings->put_AreDevToolsEnabled(FALSE);
                        settings->put_IsStatusBarEnabled(FALSE);
                        settings->put_IsZoomControlEnabled(FALSE);
                        ComPtr<ICoreWebView2Settings3> s3;
                        if (SUCCEEDED(settings.As(&s3))) s3->put_AreBrowserAcceleratorKeysEnabled(FALSE);
                        // The page is served from a virtual https host (a secure context for WebRTC).
                        ComPtr<ICoreWebView2_3> w3;
                        if (SUCCEEDED(webview.As(&w3))) {
                            w3->SetVirtualHostNameToFolderMapping(HOST, playerDir.c_str(), COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY_CORS);
                        }
                        EventRegistrationToken token;
                        webview->add_WebMessageReceived(Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                            [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                                LPWSTR text = nullptr;
                                if (SUCCEEDED(args->TryGetWebMessageAsString(&text)) && text) {
                                    writeLine(narrow(text));
                                    CoTaskMemFree(text);
                                }
                                return S_OK;
                            }).Get(), &token);
                        fitToWindow();
                        webview->Navigate((std::wstring(L"https://") + HOST + L"/index.html").c_str());
                        std::thread(readInput).detach();
                        return S_OK;
                    }).Get());
                return S_OK;
            }).Get());
    if (FAILED(hr)) {
        writeLine("{\"t\":\"state\",\"state\":\"failed\",\"detail\":\"WebView2 runtime missing\"}");
        return 3;
    }

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    CoUninitialize();
    return 0;
}
