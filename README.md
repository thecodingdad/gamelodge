# GameLodge

A controller-first game launcher for Android handhelds (built on and for the AYN Odin2 Portal),
combining local Android games and games streamed from Sunshine / Apollo / Vibepollo hosts into
one library. Streaming is powered by [Artemis](https://github.com/ClassicOldSong/moonlight-android).

> Status: early development (phase 0 – Artemis integrated as a library).

## Repository layout

| Path | Purpose |
|---|---|
| `artemis/` | Git submodule with the Artemis sources (kept unmodified where possible) |
| `moonlight-core/` | Android library module that builds the Artemis sources from the submodule |
| `app/` | The launcher app (Kotlin, Jetpack Compose) |

## Building

Requirements: JDK 21, Android SDK 36, NDK 27.0.12077973.

```bash
git clone --recurse-submodules https://github.com/thecodingdad/gamelodge.git
cd gamelodge
./gradlew assembleDebug
```

## License

GPLv3, see [LICENSE](LICENSE). Artemis and Moonlight are licensed under GPLv3 as well.
