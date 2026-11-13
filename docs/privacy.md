# Privacy

GameLodge has no account, no telemetry, no ads and no crash reporting. It sends nothing to its developer. Your
library, settings, play time and sign-ins stay on your devices; GameLodge only talks to the services you set up
yourself, and only for the purpose described below.

## What stays on your device

- **Your library** – games, apps, ROM folders, artwork, metadata, filters, collections and play time
- **Settings and stream profiles**
- **Sign-ins and keys** – the Microsoft (Xbox) and Steam sign-ins, API keys, the Home Assistant token, VPN keys and
  pairings. They are stored encrypted (Android Keystore / Windows data protection) and never leave the device;
  [backups](backup.md) leave pairings and sign-ins out and encrypt the other credentials with your password.

## Who GameLodge talks to

| Service | When | What is sent |
|---|---|---|
| Your streaming hosts (Sunshine, Apollo, Vibepollo) | You pair a PC and stream | Pairing, app lists, the stream and your controller input |
| Your GameLodge PC (Windows app) | You pair it as host or metadata source | Library, artwork, metadata and play time between your own devices |
| [IGDB](https://www.igdb.com) / Twitch | You add your own IGDB credentials | Game titles to find descriptions, ratings and time to beat |
| [SteamGridDB](https://www.steamgriddb.com) | You add your own key | Game titles to find covers, logos and backgrounds |
| Your metadata server | You add one | Game titles, like IGDB and SteamGridDB above |
| Microsoft / Xbox Live | You add an Xbox console | Sign-in, your consoles' state, installed games and licenses, the stream; Microsoft Store pages for artwork |
| Steam | You sign in to Steam (Windows) | Your list of owned games; Steam's store for artwork |
| Your Home Assistant | You connect it | Entity states and the actions you set up; with MQTT the running game and your library |
| Your WireGuard server | You add a VPN tunnel (Android) | The VPN connection |

Epic, GOG, Ubisoft, EA, Battle.net, Amazon and Playnite games are read from those launchers' files on your PC; no
sign-in and no connection is involved.

## Permissions (Android)

Every permission is optional and used for one feature only – see [Getting started](getting-started.md#permissions-android).
Usage access, for example, only counts the play time of games started outside GameLodge; the location permission is
what Android requires to show the Wi-Fi name.

## Removing GameLodge

Uninstalling removes GameLodge and all of its data. On Windows the uninstaller asks whether to keep your data
(`%LOCALAPPDATA%\GameLodge`) for a later reinstall or to delete it. GameLodge as your home app or default launcher is undone in
Android's settings (**Apps → Default apps → Home app**).

## License

GameLodge is free software under the [GNU General Public License v3.0](https://github.com/thecodingdad/gamelodge/blob/main/LICENSE).
Questions about privacy: [open an issue](https://github.com/thecodingdad/gamelodge/issues).
