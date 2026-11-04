# Backup & restore

**Settings → Data** saves and restores your setup.

## What a backup contains

Settings, stream profiles, favourites, ratings, play time, filters, groups, metadata corrections, Home
Assistant tiles and host settings. Your own images are always included; choose whether to add the
downloaded artwork and the (much larger) animated artwork.

**Credentials** – API keys, the Home Assistant login and VPN keys – are only included when you choose so,
encrypted with a password you enter twice.

!!! info "Pairings are never part of a backup"
    The keys that pair a device with your hosts are stored in the device's secure storage and can't be
    exported. A file with them would let anyone act as your device. Pair the new device instead – the setup
    wizard does that before it restores.

## Backing up

- **Back up now** writes into the backup folder, or asks where to save when no folder is set.
- **Daily automatic backup** keeps the newest 3, 7, 14 or 30 backups in the folder (without credentials).
- Backups run in the background with their progress in the task list and can be cancelled. Only one runs at
  a time.

## Restoring

Pair your hosts first, so favourites and play time find their games. Then **Restore**: the dialog shows
what the backup contains and asks for the password if it has credentials.

By default the backup **replaces** the current state: tiles, groups, profiles and host settings not in the
backup are removed, favourites and ratings of other games reset. Switch on **Keep current entries** to add
the backup to what's there instead. Play time always keeps the higher value, and credentials not in the
backup stay.
