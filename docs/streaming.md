# Streaming

GameLodge streams games from PCs running [Sunshine](https://github.com/LizardByte/Sunshine),
[Apollo](https://github.com/ClassicOldSong/Apollo) or Vibepollo. The streaming client is built in:
[Artemis](https://github.com/ClassicOldSong/moonlight-android) on Android, Moonlight on Windows.

## Hosts and pairing

**Settings → Hosts** lists the hosts found in your network; add others by address.

1. Select the host and choose **Pair**.
2. GameLodge shows a PIN. Open the host's web interface and enter it there.
3. The host's apps appear in your library shortly after.

A host's settings hold how it wakes up and goes to sleep:

- **Wake-up**: Wake-on-LAN or a Home Assistant entity, with its own timeout
- **Sleep, shut down, restart**: Home Assistant entities (also in the quick menu's Hosts tab)
- **Auto sleep**: put the host to sleep some time after the last stream ended

With **Vibepollo** and its API token, GameLodge can also start apps directly and bring the running game's
window to the front when you resume.

## Stream profiles

**Settings → Stream profiles**: resolution, frame rate, bitrate, HDR and more. The active profile is used
for every stream; **Start with…** (X on a tile) picks another one for a single launch.

## Controllers in the stream

Every controller is passed to the host as its own gamepad, in the player order you set. Per controller you
choose vibration (controller, device or both), motion sensors and the battery report; see
[Controllers](controllers.md). Button shortcuts keep working in the stream: back to GameLodge, quick menu,
mouse emulation, performance overlay and on-screen keyboard.

## Streaming away from home (Android)

GameLodge has a built-in **WireGuard** VPN (**Settings → VPN**):

1. Import the tunnel configuration (`.conf`) from your router, e.g. a FRITZ!Box.
2. Choose **Connect when the host isn't reachable**: when you start a stream and the host can't be reached,
   GameLodge connects the VPN, then wakes the host.
3. Optionally **only outside the home network**: list your home networks as addresses, subnets or ranges.
4. **Disconnect after the stream** ends the tunnel again if GameLodge connected it.

Each host can use the tunnel that reaches its address automatically, a fixed tunnel or none.
