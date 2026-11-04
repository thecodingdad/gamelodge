# FAQ & troubleshooting

??? question "My host games don't show up"
    Check that the host is paired (**Settings → Hosts**) and online. Games appear a few seconds after
    pairing; the task list shows when GameLodge loads a host's game list.

??? question "Shortcuts don't work in other Android apps"
    Enable the **GameLodge combos** accessibility service (see [Android](android.md#shortcuts-in-every-app)).

??? question "No artwork or descriptions"
    Add a SteamGridDB key and IGDB credentials under **Settings → Metadata & artwork**. Uncertain matches
    wait in the review list.

??? question "The stream doesn't start away from home"
    Set up the [VPN](streaming.md#streaming-away-from-home-android) and make sure the host can be woken
    (Wake-on-LAN through the VPN usually doesn't work – use a Home Assistant entity instead).

??? question "The launcher stutters on my TV"
    Choose **Automatic** for the TV resolution: up to 1080p at a high refresh rate, the TV scales it up.

??? question "Can I move my setup to a new device?"
    Yes – back up on the old device, then choose **Restore a backup** in the setup wizard of the new one.
    See [Backup & restore](backup.md).

??? question "Where do I report a bug?"
    Open an issue on [GitHub](https://github.com/thecodingdad/gamelodge/issues).
