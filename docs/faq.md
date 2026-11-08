# FAQ & troubleshooting

??? question "My host games don't show up"
    Check that the host is paired (**Settings → Streaming hosts**) and online. Games appear a few seconds after
    pairing; the task list shows when GameLodge loads a host's game list.

??? question "Shortcuts don't work in other Android apps"
    Enable the **GameLodge combos** accessibility service (see [Controllers & shortcuts](controllers.md#shortcuts-in-other-apps-android)).

??? question "No artwork or descriptions"
    Add a SteamGridDB key and IGDB credentials under **Settings → Metadata & artwork**. Uncertain matches
    wait in the review list.

??? question "The stream doesn't start away from home"
    Set up the [VPN](vpn.md) and make sure the host can be woken
    (Wake-on-LAN through the VPN usually doesn't work – use a Home Assistant entity instead).

??? question "The launcher stutters on my TV"
    Choose **Automatic** for the TV resolution: up to 1080p at a high refresh rate, the TV scales it up.

??? question "Can I move my setup to a new device?"
    Yes – back up on the old device, then choose **Restore a backup** in the setup wizard of the new one.
    See [Backup & restore](backup.md).

??? question "Where do I report a bug?"
    Open an issue on [GitHub](https://github.com/thecodingdad/gamelodge/issues).

??? question "The browser warns about the web interface's certificate"
    The web interface of a Windows PC uses the PC's own certificate, which no public authority signed.
    Check that the address is your PC's and trust it once (see [Devices & web interface](sharing.md#web-interface)).

??? question "My handheld doesn't find the GameLodge PC"
    On the PC, **Settings → This PC as host → Devices & pairing → Connection for launchers** must be on, and
    GameLodge has to run (the tray is enough). Start pairing on the PC first, then on the device.

??? question "An Xbox game waits at the profile selection or doesn't start"
    The console needs a profile that signs in automatically and its power mode on *Sleep* to be woken. See
    [Xbox consoles → Prepare the console](xbox.md#prepare-the-console).

??? question "The Xbox stream doesn't connect"
    Check that remote features and streaming are on at the console. Away from home, your router may block the
    connection: forward the console's UDP ports 3074 and 9002 to it. See [Xbox consoles](xbox.md#away-from-home).
