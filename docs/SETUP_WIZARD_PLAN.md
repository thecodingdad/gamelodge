# Setup wizard – plan

Guides through the most important settings on first start. Decided with the user on 2026-10-21.

## Frame

- **Form:** linear wizard, one full-screen step after the other.
- **When:**
  - First start of a fresh installation (no library, no hosts, no settings written yet; uses `onboardingDone`). Existing installations don't see it after the update.
  - Manually via the row "Setup wizard" in Settings › System.
- **Mandatory:** language and controller layout (both preset, confirming is enough). Every other step has "Skip". There is no "later" for the whole wizard; it always ends on the finish page.
- **Changes apply at once** (theme visible live, pairings immediate). Leaving early keeps what was done.
- **Existing state is shown, not hidden:** a step whose thing is already set up shows it with ✓ (e.g. "Win11VM paired ✓"); "Next" is enough, adding more is possible. This matters for the manual start, which always begins at step 1 with the current values.
- **Implementation:** simplified screens of their own per step, built from the same components and logic as the settings pages (no embedded settings pages).

## Screen layout

Landscape (Odin, tablet, PC):

```
┌──────────────────────────────────────────────────────────┐
│ ●●●◉○○○○○○○○○○   Step 4 of 14 · Controller               │
│                                                          │
│   [ big icon ]        │  step content                    │
│                       │  (choices, lists, buttons)       │
│   Title               │                                  │
│   Explanation text    │                                  │
│                       │                                  │
│──────────────────────────────────────────────────────────│
│ ← Back                 Skip                     Next →   │
└──────────────────────────────────────────────────────────┘
```

- Progress: a row of dots (done / current / open) plus "Step n of N · Name". N counts only the steps of this platform and path.
- Portrait (phone, tablet held upright): icon/title/text above, content below, scrolling.
- Buttons at the bottom: Back (missing on step 1), Skip (missing on mandatory steps), Next (primary; "Finish" on the last step).
- Keys: A = the focused element; Start = Next; B = previous step (nothing on step 1, never leaves the wizard); Y = Skip. No button hints in the wizard (decided after trying them: they took room and looked busy). Touch and mouse use the buttons.
- Initial focus on the first choice of the step, otherwise on Next.

## Order

Android:

1. Welcome + language*
2. Backup (choose path)
3. Controller*
4. Guide & accessibility service
5. Appearance
6. Launcher mode
7. Permissions
8. Emulators / ROMs
9. Streaming hosts
10. GameLodge PC
11. Metadata sources
12. Home Assistant
13. VPN
14. Finish

Windows:

1. Welcome + language*
2. Backup (choose path)
3. Role as streaming host
4. Controller*
5. Appearance
6. Startup & window
7. Game sources
8. Emulators / ROMs
9. Streaming hosts
10. GameLodge PC
11. Metadata sources
12. Home Assistant
13. Finish

(* = mandatory)

**Backup path:** pairings are not in the backup, and restoring before pairing loses the favourites and play time of host games. Choosing "Restore from backup" in step 2 therefore leads to its own path:

1. Pick the file.
2. Streaming hosts.
3. GameLodge PC.
4. Restore (password if the backup has credentials).
5. Only the device steps a backup can't carry:
   - Android: Guide & accessibility service, Launcher mode, Permissions.
   - Windows: Startup & window (on Windows the role and controller come from the backup).
6. Finish.

## Steps

### 1 · Welcome + language (mandatory)
- Left: logo, "Welcome to GameLodge", three small tiles, one sentence each: Library (all games and apps in one place), Streaming (games from your PC), Smart home (Home Assistant at hand).
- Right: language choice, a segmented list (System language / Deutsch / English). It applies live and the screen redraws.
- Next only; preset = system language.

### 2 · Backup
- Two big cards: "Set up new" (default focus) and "Restore from backup".
- Restore opens the backup picker:
  - Android: SAF folder / file.
  - Windows: file dialog.
  - The newest backup in a chosen folder is preselected.
- After picking, the wizard switches to the backup path (see Order). The step list and the dots adapt.

### 3 · Controller (mandatory)
- **Controller list:** connected controllers with name, type icon, battery and a "Test vibration" button each. Without a controller: "No controller connected – touch and mouse work too."
- **Layout per controller:** Xbox (A below) / Nintendo (A right) as a segmented choice per controller.
  - The global default comes first: "All controllers".
  - Each controller can deviate (existing per-controller layout overrides).
  - Odin's built-in pad is preset to Xbox.
- **Live button test:** a pad graphic for the controller used last. Pressed buttons light up, with A/B/X/Y labelled according to the chosen layout, so a wrong layout is obvious at once.
- Next once confirmed. Presets: Xbox everywhere; a Nintendo pad is preset to Nintendo when recognised.

### 4 · Button shortcuts & accessibility service (Android)
- One row per shortcut with its current buttons: Guide = back to GameLodge, Start (hold) = quick menu, Select (hold) = mouse emulation, Start + Select (hold) = performance overlay, LB + Select (hold) = on-screen keyboard (works in GameLodge and in the stream).
- **Status row "In other apps":**
  - "Enable service" opens the accessibility settings.
  - After coming back the row shows "On ✓" or stays "Off".
  - Text: without the service it works only in GameLodge and the stream.
- "Change" on a row opens the existing recording dialog for that shortcut.

### 5 · Appearance
- Theme: Dark / Black (OLED) / Light as big cards.
- Accent: "From cover" or "Fixed" plus the preset swatches.
- UI size (scale steps as in the settings) and animations on/off.
- Right: live preview (one sample tile and a button, in the chosen look). Everything applies at once.

### 6 · Launcher mode (Android)
- One sentence: "Make GameLodge the home app of this device."
- Button "Set as home app" opens the system dialog (`RoleManager`). After coming back: "GameLodge is the home app ✓".

### 7 · Permissions (Android)
- One row each with purpose, status (allowed ✓ / not allowed) and an "Allow" button that opens the matching system page. The status refreshes on return.
  - **Usage data:** play time of local games also for starts outside GameLodge.
  - **Display over other apps:** quick menu above other apps and the stream, edge handle.
  - **Modify system settings:** brightness in the quick menu.
  - **Quick menu tiles:** Bluetooth, location (Wi-Fi name), do-not-disturb, requested together.
- Next works with any status.

### 3W · Role as streaming host (Windows)
- Question: "Do you also stream games from this PC to other devices?" Yes / No.
- **Yes:**
  - **Detect host software:**
    - Installed Vibepollo / Apollo / Sunshine is found; type and URL are preset.
    - If none is found: a hint with links to the projects.
  - **Access + test:**
    - Vibepollo: API token, with a guide to the scopes it needs (apps).
    - Apollo / Sunshine: user + password.
    - "Test connection" shows ✓ or the error. Sync switched on.
  - **Allow device pairing:** switches the pairing server on (host games, metadata, Guide/resume for clients). Shows the address clients use.
  - **Metadata for devices:**
    - SteamGridDB key and IGDB client ID/secret, entered here (with QR codes to the key pages), to provide them to paired devices.
    - With this, the later "Metadata sources" step shows "Keys set ✓".
- **No:** the step ends; pairing server and sync stay off.

### 6W · Startup & window (Windows)
- Three switches, all **on** by default:
  - Start with Windows.
  - Keep running in the tray.
  - Start in full screen.

### 7W · Game sources (Windows)
- Detected stores (Steam, Epic, GOG, Battle.net …), each with status (found / not installed) and an on/off switch.
- Steam sign-in (optional) for not installed games and play time.
- Live counter while scanning: "123 games found".

### 8 · Emulators / ROMs
- **ROM folder:** the main folder, with per-platform subfolders detected. On Android this includes the file-access permission ("All files"), with explanation and button.
- **Detected emulators:** installed emulators with their platforms. The mapping can be confirmed or changed per platform.
- **Scan:** starts right away and shows the count per platform. Platforms whose emulator needs BIOS/firmware are marked with a hint.

### 9 · Streaming hosts
- **List:** hosts found in the network (live), each with status. "Add host by address" uses the controller keyboard.
- **Pairing:**
  - Choosing a host shows a big PIN and the hint "Enter it in the web interface of Vibepollo / Apollo / Sunshine".
  - After pairing: ✓ in the list.
- **Permission check (Apollo/Vibepollo):** if "launch apps" or "controller input" is missing, the hint says where to allow it.
- **Wake-up per host:**
  - Wake-on-LAN (MAC preset) or a Home Assistant action.
  - Choosing Home Assistant opens the HA login as a popup when HA isn't connected yet, then the entity picker.
- Next with at least one paired host or via Skip.

### 10 · GameLodge PC
- For every paired streaming host the wizard checks whether GameLodge runs on that PC (pairing API `/v1/pair/hello` at the host's address).
- Hosts with GameLodge are listed with "Pair": enter the PIN shown on the PC.
- Benefits in one sentence each: host games with metadata, Guide stays on this device while streaming, resume brings the game's window to the front.
- Plus "Pair by address".
- Skipped automatically when no paired host has GameLodge (and no address was entered). It still appears in the finish summary as skipped.

### 11 · Metadata sources
- Three cards, each marked ✓ when it already works:
  1. **Through the GameLodge PC:** available when a paired PC provides keys; nothing else to do.
  2. **Metadata server:** pair one (address + PIN).
  3. **Own keys:** SteamGridDB API key, IGDB client ID + secret. A QR code to each provider's page, so the key can be made on a phone.
- Next once one way works or via Skip. Without metadata, the library still works with store/host covers.

### 12 · Home Assistant
- **Search:** `homeassistant.local` (and mDNS) is tried automatically; otherwise the URL is entered with the controller keyboard.
- **Login:** OAuth as in the settings. When already connected (e.g. from the wake-up popup): "Connected ✓".
- **Favourites:** pick a few entities for the HA panel (searchable picker).

### 13 · VPN (Android)
- **Import:** a WireGuard `.conf` from a file.
- **Home networks:** "Use current network as home network". The current subnet goes into the home network list.
- **Auto at stream start:** switch "Connect automatically when the host isn't reachable", plus "Disconnect after the stream".

### Finish
- Library status: "214 games, 38 apps", with progress while sources, metadata and covers are still loading (live).
- Button "Let's go" sets `onboardingDone` and opens the start tab.

## Notes for the implementation

- Wizard state: current step and path (new / backup). Stored, so a restart during the wizard (e.g. after the role dialog or a permission page) continues there.
- Returning from system pages (permissions, home role, accessibility, SAF) refreshes the status of the step (`LifecycleResumeEffect`).
- Every step works with controller, touch and mouse. Popups (PIN, HA login, keyboard) follow the existing modal rules.
- Strings EN/DE in composeResources. Platform-specific steps use the `PlatformUi`/container seams (Android: permissions, role, accessibility; Windows: role, sources, startup).
