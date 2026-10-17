# Moonlight Metadata Server

SteamGridDB and IGDB for paired Moonlight Launchers, with your own API keys kept on the server.
Same client API, pairing and web admin as the Windows launcher's host role.

## Run with Docker

Build on the Docker host (amd64 or arm64) from the repository root:

```sh
docker build -f server/Dockerfile -t moonlight-metadata-server .
```

`docker-compose.yml`:

```yaml
services:
  metadata-server:
    image: moonlight-metadata-server
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
| `MLSERVER_DATA` | `/data` | data folder (identity, devices, admin account, keys, cache) |
| `MLSERVER_API_PORT` | `47900` | client API |
| `MLSERVER_ADMIN_PORT` | `47901` | web admin |
| `MLSERVER_ADMIN_PUBLIC` | `false` | `true`: web admin also from public addresses (default: private networks and VPN only) |

Forgotten password: `docker exec <container> /app/bin/mlserver reset-admin` prints a new setup code.

## Without Docker

```sh
./gradlew -PserverOnly :server:installDist
MLSERVER_DATA=./data server/build/install/mlserver/bin/mlserver
```
