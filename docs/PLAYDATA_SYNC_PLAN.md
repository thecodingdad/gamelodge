# Play data sync between GameLodges

Playtime, last played, own rating, favourites, hidden games and collections of **host games** are
the same on every GameLodge paired with that host. Decided with the user on 2026-10-30.

## Decisions

| Topic | Decision |
|---|---|
| What | Playtime + last played (from sessions), own rating, favourites, hidden, collections |
| Where | The host's GameLodge (Windows host role) is the hub for **its** games; existing pairing (mutual TLS) |
| Scope | Only games on a host. Local games of a device (Android apps, ROMs on the Odin) stay per device |
| Playtime shown | Total over all devices; the detail page also lists it per device ("Odin 3 h · lucille 1 h") |
| Conflicts | Last change wins (rating, favourite, hidden, collection membership) |
| Streams of other clients | Counted by the host itself (runner) when no GameLodge client reports the stream |
| Earlier playtime | Taken over once as a starting value per device and game |
| Collections | Matched by name; only host games' membership is synced, local games stay per device |
| Hidden | Asked when hiding: only on this device or everywhere |

## Model

Playtime is never synced as a counter but as **sessions** (append only, each with a UUID): nothing is
counted twice, sessions recorded offline are sent later, and "today / this week" statistics work across
devices.

```
Session        id (UUID), gameId (host entry id), deviceId, deviceName, startedAt, seconds, kind
               kind: PLAY (a real session) | EARLIER (one-time starting value per device and game)
Flag           gameId, field (RATING | FAVORITE | HIDDEN), value, changedAt, deviceId
Membership     collection name, gameId, member (true / false = removed), changedAt, deviceId
```

The host keeps all three plus a growing sequence number per change; a client asks for "everything after
sequence N".

### Who counts a session

- A GameLodge client counts its own sessions (as today) and sends them.
- The host counts what is played on the PC itself (as today) and, new, streams that no GameLodge client
  reports: when its runner starts a game and no stream notice of a paired GameLodge is active
  (HostStreams, renewed every 30 s), the runner's start/end becomes a host session ("Switch", "LG TV" …
  are not known by name: shown as "other streaming clients").
- A GameLodge stream is never counted by the host: the client sends a stream notice before the start.

### Earlier playtime

On the first sync with a host every device sends, per host game with playtime, one EARLIER session with
its current total. Its id is derived from device + game, so a repeated first sync replaces it instead of
adding it again. Sessions recorded locally before the update are not sent individually (their sum is in
the EARLIER value).

### Last change wins

Flags and memberships carry the time of the change. The host keeps the newest per game and field;
ties go to the later arrival. Clock differences between devices of a few seconds don't matter for
user actions; a device whose clock is far off would lose or win wrongly (accepted).

### Hidden per device

Hiding a host game asks "Only here / Everywhere". "Only here" stays a local flag (not synced); "Everywhere"
sets the synced HIDDEN flag. Shown hidden = local OR synced. Showing again clears both.

## Protocol (host API, paired devices only)

```
GET  /v1/playdata?after=<seq>      → { seq, sessions[], flags[], memberships[] } changed after seq
POST /v1/playdata                  ← { sessions[], flags[], memberships[] } from this device
```

- The device comes from the client certificate (as for every API call); a device can't send sessions
  in another device's name.
- Sessions are idempotent by id; flags/memberships go through last-change-wins.
- Limits: at most 1000 entries per request, sessions between 1 s and 24 h, timestamps not in the
  future (+5 min tolerance).

## Clients

- **Outbox**: every new session / changed flag / membership of a host game goes into a local queue and
  is sent with the next contact; the queue empties on success.
- **When**: together with the existing host-games check (once a minute while the launcher runs, on "fetch
  now", after a game or stream ends) – no extra polling.
- **Display**: playtime of a host game = sum of all devices' sessions (host's view) + own sessions not yet
  sent; last played = newest session; the detail page lists the devices.
- Local playtime stays where it is today for local games.

## Host

- New tables in the host's database for sessions, flags, memberships (part of its backup).
- Its own launcher reads them like a client (the host is a device of its own).
- Runner sessions for streams without a GameLodge client (see above).

## Phases

1. Protocol + host storage + tests (incl. idempotency, last-change-wins, limits).
2. Sessions: outbox, push/pull, total and per-device display, host runner sessions, earlier playtime.
3. Rating, favourites, hidden (with the "only here / everywhere" choice).
4. Collections by name.

Each phase on Android and Windows, tested with Odin + Windows client against the host.
