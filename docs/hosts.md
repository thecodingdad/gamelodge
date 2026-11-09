# Hosts & pairing

A host is a PC running Sunshine, Apollo or Vibepollo. GameLodge shows its apps in the library and streams
them.

![Hosts](assets/screenshots/hosts.jpg)

## Add and pair a host

**Settings → Streaming hosts** lists your PCs and [Xbox consoles](xbox.md) in one list and finds PCs in the same
network while the page is open. For a PC in another network choose **Add PC** and enter its address; **Add Xbox**
adds a console. The page also holds the [stream profiles](profiles.md).

1. Select the host and choose **Pair**.
2. GameLodge shows a **PIN**. Open the host's web interface (Sunshine / Apollo / Vibepollo) and enter it.
3. The host's apps appear in the library a few seconds later.

The host card shows whether it's online and paired, with **Host settings**, **Wake up**, **Restart**, **Shut
down** and **Remove**. Its icon (a monitor for a PC, the Xbox logo for a console) is green while the host is
online, red while it is off and blue while it streams; during a wake-up, shutdown or restart it turns into a
spinner until the host follows – a notice says so if it doesn't in time. **Refresh** below the list reloads the
PCs' app lists and the consoles' games.

The status bar shows one host icon: the number of hosts online from two on, red when all are off.

!!! tip "GameLodge on the PC too"
    If the PC also runs GameLodge for Windows, pair it under **Settings → Metadata & artwork → Add source**
    (or in the wizard's *GameLodge on the PC* step). The library then gets the PC's games with artwork,
    platform and play data, see [Your PC as host](pc-host.md).

## Host settings

![Host settings](assets/screenshots/host-settings.jpg)

### Wake-up

- **Wake method**: *Wake-on-LAN*, *Home Assistant* (an entity that powers the PC on – a smart plug, a VM, a
  BIOS wake), *Both* or *None*. Used automatically when you start a game on an offline host.
- **Wait after waking**: how long a game start waits for the host to be ready.

### VPN

**Tunnel for streams**: *Automatic* uses the tunnel that reaches the host's address, else the default
tunnel; or a fixed tunnel, or no VPN. See [VPN](vpn.md).

### Home Assistant actions

Wake up, sleep, shut down and restart can each run a Home Assistant action – for PCs without Wake-on-LAN
or a VM on a hypervisor. They are also offered in the quick menu's *Hosts* tab.

![Home Assistant actions of a host](assets/screenshots/host-ha-actions.jpg){ width="600" }

### After streaming

![Auto sleep](assets/screenshots/host-auto-sleep.jpg)

**Put host to sleep after streaming**: some time after the last stream ended, GameLodge runs the host's
sleep (or shut down) action. Starting another stream in time cancels it.

## Apollo & Vibepollo

**Permissions.** Apollo and Vibepollo set per client what it may do. GameLodge needs **Launch apps** and
**Controller input**; when one is missing it says so on the host card. Allow it for this device in the host's
web interface under **Clients**.

**A game another device started.** Vibepollo shows a running game only to the device that started it. GameLodge
recognises such a session and shows the game as running, so you can take it over from the handheld.

**Games of the PC.** To fill Vibepollo, Apollo or Sunshine with the PC's games – with the right start
command, artwork and play time – set up GameLodge on the PC as [streaming host](streaming-host.md).

## See also

- [Streaming host](streaming-host.md) – the PC's games on Vibepollo, Apollo or Sunshine
- [Xbox consoles](xbox.md) – an Xbox as host
- [Stream profiles](profiles.md)
- [VPN (WireGuard)](vpn.md) – streaming away from home
- [Home Assistant](home-assistant.md) – wake up and sleep through Home Assistant
