# Moonlight Launcher for Windows – Plan

Branch `desktop`. Decided 2026-10-15.

## Goal

The launcher runs on Windows with the same UI and the same functions as on Android, from the
same code. On Windows it also takes over everything the .NET companion does today, so the
companion is no longer needed:

- **Local PC games:** store libraries (Steam, Epic, GOG, Xbox, EA, Ubisoft, Battle.net, Amazon),
  emulators with ROM folders, Playnite import. Detected by the launcher itself.
- **Streaming** from other hosts (Sunshine/Apollo/Vibepollo) through a bundled Moonlight-Qt.
- **Host role** for Android launchers: Vibepollo app sync, J-PAKE pairing, host games feed with
  metadata, images, corrections, metadata proxy. Same protocol as the companion, so paired
  Android launchers keep working.
- **Pairing also through a web interface** (embedded web server, admin login), as in the
  companion today.

Differences by nature: no Android apps, Windows emulators instead of Android ones, no
WireGuard service (later), quick menu tiles that exist on Windows (volume, brightness).

The metadata server stays .NET/Docker (separate product, only the protocol is shared).

## Architecture

Kotlin Multiplatform with Compose Multiplatform (JetBrains), targets Android and JVM desktop.

```
moonlight-launcher/
├─ shared/          KMP module (android library + jvm "desktop")
│  ├─ commonMain    UI (all screens, theme, focus, hints, keyboard), view model, library,
│  │                metadata, Room DB, settings, HA, pairing client, protocol models.
│  │                Shared only by JVM targets, so JDK APIs (java.io, OkHttp, Bouncy Castle) are usable.
│  ├─ androidMain   Android implementations of the platform interfaces
│  └─ desktopMain   Windows implementations + host role (ported companion)
├─ app/             Android: activities, services, Artemis glue (as today, thinner)
├─ moonlight-core/  Artemis wrapper (unchanged)
└─ desktop/         Windows app: main(), window, tray, packaging
```

- Package names stay (`io.github.thecodingdad.moonlightlauncher…`): moving a file changes no imports.
- **Platform seam:** interfaces in commonMain, implemented per platform, handed to the UI through
  one `LauncherContainer` (CompositionLocal) instead of `LocalContext.current.container`.
  Main seams: hosts/streaming, local library sources, input devices, system status (battery,
  network, BT), quick tiles, feedback (sound/vibration), files/pickers, secrets, language,
  launcher mode, external display, VPN.
- **Strings:** Compose Multiplatform resources (`composeResources/values{,-de}/strings.xml`,
  `stringResource(Res.string.x)`); the Android-only resources (manifest labels, tiles,
  accessibility service) stay in `app/src/main/res`.
- **Libraries (all KMP):** Room 2.8 (bundled SQLite on desktop), DataStore, Coil 3, JetBrains
  navigation/lifecycle, kotlinx serialization/coroutines, OkHttp (JVM), Bouncy Castle.
- **Desktop specifics:** gamepads via SDL2 (JNA) mapped to the same key events as Android;
  WebM (animated heroes) via FFmpeg/JavaCV or VLCJ; DPAPI via JNA for secrets; jmDNS for
  `_mlcompanion._tcp`; Ktor server for client API (mTLS) and web admin UI.

## Streaming on Windows

Bundled **Moonlight-Qt** (GPLv3) in portable mode, its settings in the launcher's data folder.

- `moonlight stream <host> <app>` starts a stream, the launcher waits for the process (play time).
- Pairing: `moonlight pair <host> --pin <pin>`; the launcher shows the PIN in its own dialog.
- Host list, app list, box art, WoL: own NvHTTP client in Kotlin using Moonlight-Qt's client
  certificate (read from its portable settings), so both share one paired identity.
- To verify in phase 3: portable settings format, CLI flags of the bundled version, quitting apps.

## Phases

| Phase | Content | Done when |
|---|---|---|
| 0 | KMP foundation: `shared` module, code moved, platform interfaces, strings to Compose resources, Android app on top of `shared` | Android app behaves exactly as before, all tests green |
| 1 | Desktop shell: window/fullscreen, gamepad (SDL), library + metadata + settings + HA from shared code, data folder, test build | Windows build shows the library with metadata, fully controller operable |
| 2 | Local PC games: store scanners, emulators + ROMs, Playnite import, game runner (`--run`), process watch, play time | PC games start from the launcher |
| 3 | Hosts and streaming: NvHTTP, pairing dialog, host games/apps, Moonlight-Qt bundling and start, wake/power | Stream from another host |
| 4 | Host role: Vibepollo client + sync, covers, auto sync, client API (mTLS), J-PAKE pairing server, device registry, host games feed, metadata proxy, mDNS, web admin UI (login, pairing, devices, keys), tray + autostart, Steam login | Android launchers pair with and use the Windows launcher |
| 5 | Replace the companion: data migration (server identity, paired devices, overrides, settings), installer, shell/autostart mode, companion repo archived | Companion uninstalled, everything works |

Each phase: commits on `desktop`, Windows test build in `desktop/build/…` (no releases), the
user tests on Windows. Android stays releasable from `desktop` at all times.

## Porting rules (companion → Kotlin)

- Port C# tests along with the code (JUnit in `desktopTest`); they are the yardstick.
- Protocol: wire format byte-compatible with the companion (JSON names, J-PAKE messages,
  certificate fingerprints, error codes). Cross-check against the C# implementation.
- Security checklist of `moonlight-launcher-companion/docs/PLAN.md` applies unchanged
  (mTLS everywhere but pairing, pairing window, lockout, constant-time compares, limits,
  no secrets in logs, Argon2id admin password, CSRF, secure cookies, optional TOTP).

## Status

- 2026-10-15 – phase 0 done: `shared` holds data, metadata, settings, HA, launch flow, view
  model and the complete UI (strings as Compose resources). Platform seams: `LauncherContainer`,
  `PlatformUi`, `ArtworkPlatform`, `FeedbackOutput`, `HostRepository`/`HostActions`,
  `LaunchPlatform`, `StreamProfiles`, `LocalAppSource`, `Emulators`, `Vpn`, `SecretBackend`,
  `PlatformInfo`. Android implements them in `app`; platform-free tests run on the desktop JVM.

## Open points (decide when reached)

- Windows packaging from Linux: portable zip (jlinked Windows JRE + launcher exe) vs. MSI built
  on Windows/CI.
- WebM playback library (size vs. effort).
- WireGuard on Windows (control the official service) – later.
