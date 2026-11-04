# Home Assistant

GameLodge connects to your [Home Assistant](https://www.home-assistant.io) to control your home from the
couch and to let your home react to your games.

![Home Assistant tiles](assets/screenshots/android-home-assistant.jpg)

## Sign in

**Settings → Home Assistant**: enter the address and sign in in the browser – on this device or, with the
QR code, on your phone.

## Tiles

Add lights, switches, buttons, sensors, covers and climate entities as tiles. They show in the quick
menu's Home Assistant tab and, when switched on per tile, as a row on the Home tab.

- **A** switches, the right stick sets brightness or position
- Select a tile in the list to give it **its own name** in GameLodge or show it on the Home tab
- Reorder tiles by dragging or with the controller

## Game actions

Run Home Assistant actions **before a game starts** and **after it ended** – dim the lights, start the TV,
switch the soundbar. Set them per game under **Actions** in the tile menu (X): which action, when, an
optional delay and whether the start waits for it. On a Windows host they can also run when another device
starts a game through Vibepollo.

For your own automations GameLodge fires the events `gamelodge_started` and `gamelodge_stopped` with title,
kind and whether it's a stream.

## Hosts

A host's wake-up, sleep, shut down and restart can use Home Assistant entities – useful for PCs without
Wake-on-LAN or a VM behind a switch.

## MQTT status

With an MQTT broker (e.g. the Mosquitto app), GameLodge reports its status to Home Assistant: library
size, play time and the running or last played title with its images. Every device appears as its own
device through MQTT discovery. TLS is supported, a self-signed certificate is pinned by its fingerprint.
