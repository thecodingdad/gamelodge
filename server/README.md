# GameLodge Metadata Server

SteamGridDB and IGDB for paired GameLodges, with your own API keys kept on the server.
Same client API, pairing and web admin as the Windows launcher's host role.

## Run with Docker

Each release publishes the image for amd64 and arm64:
`ghcr.io/thecodingdad/gamelodge-metadata-server` (tags `latest`, `<version>`, `<major>.<minor>`).
To build it yourself instead, from the repository root:

```sh
docker build -f server/Dockerfile -t gamelodge-metadata-server .
```

`docker-compose.yml`:

```yaml
services:
  metadata-server:
    image: ghcr.io/thecodingdad/gamelodge-metadata-server:latest
    restart: unless-stopped
    ports:
      - "47900:47900"   # client API (mutual TLS)
      - "47901:47901"   # web admin
    volumes:
      - ./data:/data
```

The first start prints a setup code (`docker logs`). Open `https://<host>:47901`, set the admin
password with it, enter the SteamGridDB key and the IGDB client id/secret under **Server**, then
pair launchers under **Pair device** (Launcher: Settings → Metadata → Add source → Metadata server).

## Settings

| Variable | Default | |
|---|---|---|
| `GAMELODGE_DATA` | `/data` | data folder (identity, devices, admin account, keys, cache) |
| `GAMELODGE_API_PORT` | `47900` | client API |
| `GAMELODGE_ADMIN_PORT` | `47901` | web admin |
| `GAMELODGE_ADMIN_PUBLIC` | `false` | `true`: web admin also from public addresses (default: private networks and VPN only) |

Forgotten password: `docker exec <container> /app/bin/gamelodge-server reset-admin` prints a new setup code.

## Without Docker

```sh
./gradlew -PserverOnly :server:installDist
GAMELODGE_DATA=./data server/build/install/gamelodge-server/bin/gamelodge-server
```
