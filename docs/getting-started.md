# Getting started

## Install

Download the latest version from the
[releases page](https://github.com/thecodingdad/gamelodge/releases/latest).

=== "Android"

    1. Download `GameLodge-<version>.apk` on the device (or copy it there).
    2. Open it and allow installing apps from this source when Android asks.
    3. Start GameLodge – the setup wizard opens.

    Android 10 or newer. GameLodge is built on the AYN Odin 2 Portal and runs on other handhelds,
    tablets, phones and Android TV devices as well.

=== "Windows"

    1. Download `GameLodge-Setup-<version>.exe` and run it.
    2. Choose an installation for your user or for all users.
    3. Start GameLodge from the start menu – the setup wizard opens.

    Windows 10 or 11, 64-bit. The installer brings its own Java runtime and the streaming client.

## The setup wizard

The wizard walks you through everything once; each step can be skipped and changed later in the
settings. Language and controller layout are the only required steps.

| Step | What you set |
|---|---|
| Welcome | Language, or **restore a backup** from another device |
| Controller | Button layout of your controller (Xbox / Nintendo), with a live button test |
| Button shortcuts | Back to GameLodge, quick menu, mouse emulation, performance overlay, on-screen keyboard (see [Controllers](controllers.md)) |
| Appearance | Theme, accent colour, size and animations |
| Home app | Android: use GameLodge as the home screen |
| Permissions | Android: usage access for play time, overlays, Bluetooth and more |
| Startup | Windows: full screen, start with Windows, tray |
| Game sources | Windows: PC stores to scan |
| Emulators | Android: ROM folders and emulators |
| Streaming hosts | Find and pair your Sunshine / Apollo / Vibepollo PCs |
| Metadata | SteamGridDB and IGDB keys, with step-by-step guides and QR codes |
| Home Assistant | Sign in to show your entities as tiles |
| VPN | Android: WireGuard tunnel for streaming away from home |

!!! tip "Moving to a new device"
    Choose **Restore a backup** on the welcome page. The wizard first pairs your hosts and then restores
    favourites, play time and settings, so everything finds its games again. Pairings themselves are never
    part of a backup (see [Backup & restore](backup.md)).
