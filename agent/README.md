# Home agent

The home agent is a small Go program that runs on the Raspberry Pi (or on any machine on the home
network, such as a laptop while developing). It is the **only** part of the system that talks to
lights. It finds them on the local network, reads their state, carries out the backend's commands, and
reports back.

The agent **connects out** to the backend over a WebSocket (`wss://…/agent/ws`). Nothing at home
listens for connections from the internet, so the router needs no port forwarding and the backend can
live on a VPS.

```
 Phone / wall screen ──HTTPS──▶ Backend (VPS) ◀──outbound WSS── Home agent ──LAN──▶ Yeelight lights
                                                                (this module)
```

Where this fits in the whole system: [`ARCHITECTURE.md`](../ARCHITECTURE.md) (sections on the agent
link and flows 6.13 to 6.15) and [`PRD.md`](../PRD.md) (the "Agent protocol" section). This document is
the module reference: how to run it, how it is built, and the full wire protocol.

## Contents

1. [Quick start](#1-quick-start)
2. [Configuration](#2-configuration)
3. [Building and installing on the Pi](#3-building-and-installing-on-the-pi)
4. [How it works](#4-how-it-works)
5. [Code layout](#5-code-layout)
6. [Wire protocol](#6-wire-protocol)
7. [The Yeelight driver](#7-the-yeelight-driver)
8. [Adding a new device driver](#8-adding-a-new-device-driver)
9. [Testing](#9-testing)
10. [Troubleshooting](#10-troubleshooting)
11. [Limits and known gaps](#11-limits-and-known-gaps)

---

## 1. Quick start

You need a running backend in agent mode (`APP_DEVICES_MODE=agent`; see `docker-compose.local.yml`)
and an **agent token**. Create the token in the app: **Settings → Home agent**. It is shown once.

```sh
cd agent
AGENT_BACKEND_URL=http://localhost:8080 \
AGENT_TOKEN=<token from the app> \
go run ./cmd/agent -v
```

Go 1.25 is required. If Go is not installed, run the same thing in a container. Use `--network host`
only on Linux: on macOS and Windows Docker runs in a VM, so Yeelight's multicast discovery does not
reach your network from a container, and you should run the agent natively there.

```sh
docker run --rm -it --network host -v "$PWD":/src -w /src golang:1.25 \
  go run ./cmd/agent -v     # with AGENT_BACKEND_URL and AGENT_TOKEN set via -e
```

Within a minute, the app's Home tab lists every Yeelight on your Wi-Fi as a new device.

> **Restart the agent after every change to its code.** `go run` compiles once at start; a running
> agent keeps the old behaviour. (This once caused "I have no colour options": the agent running was
> older than the code.)

For the lights to be found, the Yeelight app needs **LAN Control** switched on for each light
(Yeelight app → device → settings), and the agent has to be on the same subnet as the lights.

## 2. Configuration

Configuration is deliberately tiny. **Device addresses are never configured**: devices are discovered.

| Setting | JSON key | Environment variable | Required | Meaning |
|---|---|---|---|---|
| Backend address | `backendUrl` | `AGENT_BACKEND_URL` | yes | Full address, e.g. `https://home.example.com`. `https` becomes `wss`, `http` becomes `ws`. The path `/agent/ws` is added. |
| Agent token | `token` | `AGENT_TOKEN` | yes | Created in the app. Sent as `Authorization: Bearer <token>`. |
| Agent name | `agentId` | `AGENT_ID` | no | Appears in the backend's log. Defaults to the host name. |

Rules (`internal/config/config.go`):

- The file is optional. If it is missing, only environment variables are used.
- **Environment variables win** over the file.
- A missing token, or a `backendUrl` that is not a full `http(s)://host` address, stops the agent at
  start with exit code 2 and a message saying what is wrong.

Command-line flags:

| Flag | Default | Meaning |
|---|---|---|
| `-config` | `/etc/homedisplay/agent.json` | Path to the JSON config file. |
| `-v` | off | Debug logging (unknown messages, bulbs that stopped answering). |

Logs go to stderr in `slog` text format (under systemd: `journalctl -u homedisplay-agent`).

Example file: [`deploy/pi/agent.json.example`](../deploy/pi/agent.json.example).

Timing values (poll and discovery intervals, heartbeats) are constants/fields in the code, not
configuration; see [How it works](#4-how-it-works).

## 3. Building and installing on the Pi

### Build

Go does not need to be installed; `build.sh` uses the official Go container.

```sh
./build.sh
```

It produces static binaries (`CGO_ENABLED=0`, stripped) in `dist/`:

| File | For |
|---|---|
| `dist/homedisplay-agent-arm64` | 64-bit Raspberry Pi OS (Pi 4 and newer, the recommended OS) |
| `dist/homedisplay-agent-armv7` | 32-bit Raspberry Pi OS |

`uname -m` on the Pi tells you which: `aarch64` is arm64, `armv7l` is armv7.

### Install

```sh
# on your computer
scp dist/homedisplay-agent-arm64 pi@<pi>:/tmp/homedisplay-agent
scp ../deploy/pi/homedisplay-agent.service pi@<pi>:/tmp/
scp ../deploy/pi/agent.json.example pi@<pi>:/tmp/agent.json

# on the Pi
sudo install -m 0755 /tmp/homedisplay-agent /usr/local/bin/homedisplay-agent
sudo install -d /etc/homedisplay
sudo install -m 0644 /tmp/agent.json /etc/homedisplay/agent.json   # edit: backendUrl, token, agentId (see the note below)
sudo cp /tmp/homedisplay-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now homedisplay-agent
journalctl -u homedisplay-agent -f
```

The config file holds the token, so keep it restricted. Note the unit uses `DynamicUser=yes`: systemd
runs the agent as a throw-away user, which **cannot read a root-owned `0600` file**. If the agent logs
`permission denied` for the config, either use mode `0644` (the token is then readable by every local
user on the Pi, acceptable on a single-purpose Pi) or change the unit to read the file as root with
`LoadCredential=agent.json:/etc/homedisplay/agent.json` and `-config ${CREDENTIALS_DIRECTORY}/agent.json`.
The simplest alternative is to pass the token with `Environment=AGENT_TOKEN=…` in a root-only drop-in
(`systemctl edit homedisplay-agent`).

The unit (`deploy/pi/homedisplay-agent.service`) starts after the network is up, restarts always (5 s
delay), and is sandboxed: no privileges, read-only system, no access to home directories. More Pi
setup (kiosk browser, offline page): [`deploy/README.md`](../deploy/README.md).

To update the agent, build, copy the new binary over the old one, and `sudo systemctl restart homedisplay-agent`.

## 4. How it works

```
main.go
  ├─ config.Load            read file + env, validate
  ├─ yeelight.New           the Yeelight driver
  ├─ agent.New(drivers)     device registry, polling loop, command handling
  ├─ link.New(url, token)   WebSocket client with reconnect
  ├─ yeelight.Listen        passive: bulbs advertise themselves on multicast (best effort)
  └─ run agent.Run and link.Run until SIGINT/SIGTERM
```

### Two loops

**`agent.Run`** (device side). Immediately runs a refresh with discovery, then:

| What | Every | Constant |
|---|---|---|
| Discovery (find new lights, notice lost ones) | 60 s | `Agent.DiscoverEvery` |
| State polling (read each light) | 30 s | `Agent.PollEvery` |

A refresh reads the state of every known device and **sends only what changed**. If the device set
changed, one `devices` message is sent instead (it already carries all states).

**`link.Run`** (backend side). Connects, sends `hello`, then reads messages until the connection
fails, and reconnects with backoff.

| What | Value |
|---|---|
| Dial timeout | 15 s |
| Heartbeat (`ping`) | every 20 s |
| Silence limit (nothing received) | 60 s, then the connection is dropped and re-established |
| Reconnect backoff | 1, 2, 5, 10, 30, 60 s (stays at 60 s), plus up to 20 % random jitter |
| Backoff reset | after a connection that lasted over a minute |
| Maximum message size | 64 KiB |

### A command, end to end

1. The backend sends `command` (with a unique `id`).
2. The link starts a goroutine for it, so a slow light does not block reading or heartbeats.
3. `Agent.HandleCommand` finds the device and calls the driver's `SetPower` or `Set`.
4. After success the device is **read back**, so the result shows what the light really did, not what
   was asked. If the read-back fails, the previous state with the command applied (`expectedState`) is
   used instead.
5. If that state differs from the stored one, a `state` message is sent too (so other clients refresh).
6. Exactly one `result` is sent for the command, success or not. Failures carry a human-readable
   `error` that the app shows to the user.

### While disconnected

The agent keeps polling lights. Messages sent while the link is down are **dropped**, because the next
connection starts with a `hello` containing everything. Nothing is queued, so a command issued while
the agent was offline is never replayed later.

## 5. Code layout

```
agent/
├── cmd/agent/main.go              entry point: flags, wiring, signal handling
├── build.sh                       cross-compiles for the Pi using a Go container
├── internal/
│   ├── config/                    config file + environment, validation, WebSocket URL
│   ├── protocol/                  JSON message types shared with the backend
│   ├── drivers/
│   │   ├── driver.go              DeviceDriver interface, Channel/State/Change, Change.Check
│   │   └── yeelight/
│   │       ├── driver.go          the driver: discovery, state, power, set, rediscovery
│   │       ├── channels.go        what a bulb can do (from `support`), property parsing
│   │       ├── announce.go        parsing discovery answers and advertisements
│   │       └── search.go          UDP multicast search and passive listening
│   ├── agent/
│   │   ├── agent.go               registry of devices, polling, command handling
│   │   └── convert.go             driver types ⇄ protocol types
│   └── link/link.go               WebSocket client, heartbeat, reconnect
```

Dependency direction: `main` → `agent` → `drivers` and `protocol`; `link` → `protocol`. The `agent`
package knows devices and channels, never Yeelight. Only one external dependency:
`github.com/coder/websocket`.

## 6. Wire protocol

All messages are JSON text frames. Every message has `"v"` (protocol version, currently `1`) and
`"type"`. The reader first decodes only these two fields and ignores unknown types, so both sides can
add message types later.

**Compatibility rule:** additions are always optional. An older agent that sends no channels is
treated by the backend as one power-only channel named `main`; the backend tolerates unknown
fields. Keep it that way when extending the protocol.

### Connecting

`GET /agent/ws` upgraded to WebSocket with the header `Authorization: Bearer <agent token>`. The
backend accepts one agent at a time; a new connection closes the previous one with code `4000`.

### Messages from the agent

**`hello`**: first message after every (re)connect; the full snapshot.

```json
{
  "v": 1, "type": "hello", "agentId": "door-pi", "version": "0.1.0",
  "devices": [
    {
      "deviceId": "yeelight-000000000015243f", "name": "Light bar", "type": "yeelight",
      "channels": [
        { "id": "main", "label": "Main",
          "capabilities": { "brightness": true, "colorTemp": { "min": 2700, "max": 6500 }, "color": false } },
        { "id": "background", "label": "Background",
          "capabilities": { "brightness": true, "colorTemp": { "min": 1700, "max": 6500 }, "color": true } }
      ]
    }
  ],
  "states": [
    {
      "deviceId": "yeelight-000000000015243f", "power": true, "reachable": true,
      "updatedAt": "2026-10-24T18:00:00.123456789Z",
      "channels": [
        { "id": "main", "on": true, "brightness": 100, "colorTemp": 4584, "colorMode": "temperature" },
        { "id": "background", "on": true, "brightness": 100, "hue": 359, "saturation": 80, "colorMode": "color" }
      ]
    }
  ]
}
```

**`devices`**: same shape (`devices` and `states`, no `agentId`), sent when discovery found a new
device, lost one, or a device's channels changed.

**`state`**: one device's changed state.

```json
{ "v": 1, "type": "state", "deviceId": "yeelight-000000000015243f", "power": true,
  "reachable": true, "updatedAt": "…", "channels": [ … ] }
```

**`result`**: the answer to a command (exactly one per command).

```json
{ "v": 1, "type": "result", "id": "<command id>", "ok": true,
  "state": { "deviceId": "…", "power": true, "reachable": true, "channels": [ … ] } }
{ "v": 1, "type": "result", "id": "<command id>", "ok": false,
  "error": "bulb Light bar did not accept the command: …" }
```

**`ping` / `pong`**: heartbeat, either direction. The agent pings every 20 s and answers any `ping`
with `pong`.

### Messages from the backend

**`command`**

```json
{ "v": 1, "type": "command", "id": "uuid", "deviceId": "yeelight-…",
  "action": "set", "channel": "background",
  "args": { "on": true, "brightness": 60, "hue": 120, "saturation": 80 } }
```

| `action` | Channel | `args` | Effect |
|---|---|---|---|
| `setPower` | not used | `on` (required) | Switches **every** channel of the device on or off. |
| `set` | required | any of `on`, `brightness`, `colorTemp`, `hue`, `saturation` | Changes one channel. Absent settings stay as they are. |

### Field reference

| Field | Type | Range / values | Notes |
|---|---|---|---|
| `deviceId` | string | | Stable. For Yeelight: `yeelight-<hardware id without 0x, lowercase>`. Survives IP changes and restarts. |
| `type` | string | `yeelight` | The driver. |
| `power` | bool | | True when **any** channel is on. |
| `reachable` | bool | | False when the device did not answer. State is then the last known. |
| `updatedAt` | RFC 3339 | | When the agent produced this state. |
| `on` | bool | | |
| `brightness` | int | 1 to 100 | Only for channels with the `brightness` capability. |
| `colorTemp` | int, Kelvin | channel's `colorTemp.min` to `.max` | Only for channels with a range. |
| `hue` | int | 0 to 359 | Hue and saturation always come together. |
| `saturation` | int | 0 to 100 | |
| `colorMode` | string | `temperature`, `color` | Which of the two the channel currently shows. Omitted when unknown. |

**Validation:** `drivers.Change.Check` rejects, before anything is sent to a light: an empty change,
a setting the channel lacks, a value out of range, and only one of hue/saturation. The backend
validates the same rules first (`ChannelChange.validate`), so a refusal here means the two have
drifted apart.

## 7. The Yeelight driver

Package `internal/drivers/yeelight`. It speaks the [Yeelight LAN protocol][yl]: JSON lines over a TCP
connection to port 55443 of the light.

[yl]: https://www.yeelight.com/download/Yeelight_Inter-Operation_Spec.pdf

### Discovery

- **Active search:** an SSDP-like `M-SEARCH` UDP datagram to `239.255.255.250:1982` (search target
  `wifi_bulb`), collecting answers for 2 s. Runs at every discovery round.
- **Passive listening:** the agent joins the same multicast group and notes `NOTIFY` advertisements
  that lights send on their own. Best effort: on networks that do not forward multicast, it logs
  `not listening for bulb advertisements` and carries on with active search only.

An answer carries a hardware id, model, name, `location: yeelight://ip:port`, current power, and a
`support:` list of methods the light accepts.

### Identity, addresses and unreachable lights

- The light's identity is its **hardware id**, not its IP. If a command fails, the driver searches
  again (at most once per 5 s) and retries once at the new address ("bulb moved").
- A light that no discovery round finds **3 rounds in a row** is reported `reachable: false`. It is
  kept in the list with its last known state, never silently dropped, so room assignments survive a
  power cut.
- Unnamed lights get `Yeelight <model> <last 4 of id>`. Renaming happens in the app, not on the light.

### Channels and capabilities

Capabilities come from the `support:` list, so the agent never has a model table to maintain.

| Channel | Present when | Brightness | Colour temperature | Colour |
|---|---|---|---|---|
| `main` | always | `set_bright` | `set_ct_abx` | `set_hsv` or `set_rgb` |
| `background` | `bg_set_power` | `bg_set_bright` | `bg_set_ct_abx` | `bg_set_hsv` or `bg_set_rgb` |

The light does not announce its colour-temperature range, so the driver assumes **1700–6500 K** for
lights that can also show colour and **2700–6500 K** for white-only ones. Verify on new hardware.

### Commands sent to the light

- One short-lived TCP connection per command. Per light, commands are **serialised and spaced at least
  250 ms apart** (the light rate-limits).
- 3 s timeout per command. The light's pushed `props` notifications are skipped while waiting for the
  reply.
- Several settings in one `Set` are sent in a fixed order: power, brightness, then colour temperature
  or colour. Fades take 300 ms (`smooth`).
- `SetPower(off)` switches channels off in **reverse** order (background first); `SetPower(on)` goes
  forward (main first). See the quirks below for why.

### State reading

One `get_prop` call per light, six properties per channel plus `main_power`:

`power, bright, ct, hue, sat, color_mode` (main), `bg_power, bg_bright, bg_ct, bg_hue, bg_sat,
bg_lmode` (background), then `main_power`.

The light answers an empty string for anything it does not have, which becomes "not set" rather than
an error. The device `power` reported to the backend is "any channel on".

### Quirks of the light bar (Monitor Light Bar Pro, model `lamp15`)

These were measured on real hardware and each has a test in `driver_test.go`.

| Behaviour of the bar | What the driver does |
|---|---|
| `power` is the whole device, and `main_power` is the main light alone. | The main channel's `on` is read from `main_power` when the light reports it. Without this, the app showed the main light "on" while only the background glowed, and it could not be toggled. |
| `bg_set_power on` and `bg_toggle` answer `ok` but are ignored. | Background **on** is done with `bg_set_scene`, using the colour or white it last had at its last brightness (or what the command sets). Falls back to plain `bg_set_power` for lights without scene support. |
| Plain `set_power off` turns the whole device off, including the background. | **Main off** while the background is lit: read the background values, `set_power off`, then `bg_set_scene` to relight only the background. If main is already off, nothing is sent. |
| From "everything off", `bg_set_scene` leaves the main light off (background-only mode). | Used on purpose: this is how a scene "main off, background on" works. |
| With only the background lit, `set_power off` is a no-op. | Whole-device off switches the background off first, then the rest. |
| `set_power on` turns the main light on and keeps the background as it was. | Used as is. |

### Caveats

- Lights keep a single TCP connection per client; if the Yeelight app or Home Assistant also controls
  the light there is no conflict, but their commands do share the rate limit.
- Different models and firmwares may differ from `lamp15`. The `support:` list handles most of it. If
  a new model misbehaves, capture the discovery response and the `get_prop` answer and add a test with
  the fake bulb in `driver_test.go`.

## 8. Adding a new device driver

The rest of the system (backend, app) is driver-agnostic: it knows devices with channels and
capabilities. To add, say, Zigbee or a smart plug:

1. Create `internal/drivers/<name>/` and implement `drivers.DeviceDriver`:

   ```go
   type DeviceDriver interface {
       Type() string                                              // "zigbee"
       Discover(ctx context.Context) ([]Device, error)            // everything known so far, with channels
       State(ctx context.Context, id string) (State, error)      // unreachable => Reachable:false, not an error
       SetPower(ctx context.Context, id string, on bool) error   // every channel
       Set(ctx context.Context, id, channel string, c Change) error
   }
   ```

2. Rules to keep:
   - Device ids are **stable** and prefixed with the driver (`zigbee-<ieee address>`), because rooms,
     quick access and scenes in the backend are keyed by them.
   - Declare only capabilities the channel really has; the UI shows controls from them.
   - `Discover` returns lost-but-known devices too (the agent marks them unreachable); return a device
     only when it should disappear.
   - Use `Change.Check(channel)` before talking to hardware.
3. Register it in `cmd/agent/main.go`: add it to the slice passed to `agent.New`.
4. If the driver needs configuration (a Zigbee coordinator address), add optional fields to `config.Config`
   and keep the "no device addresses" rule where possible.
5. Test it with a fake transport as `yeelight/driver_test.go` does (a fake bulb over `net.Pipe`/TCP, a fake `Searcher`).

Nothing changes in `protocol` unless the driver needs a new setting type; if it does, add it as an
optional field.

## 9. Testing

Go is not required locally; use the container (this is what the project's own helper does):

```sh
docker run --rm -v "$PWD":/src -w /src golang:1.25 go vet ./...
docker run --rm -v "$PWD":/src -w /src golang:1.25 go test -race ./...
```

(The first run downloads modules. Mount named volumes at `/go/pkg/mod` and `/root/.cache/go-build`
to keep them between runs.) With Go installed: `go test -race ./...`.

What is covered:

| Package | What the tests prove |
|---|---|
| `config` | file and environment loading, env override, the WebSocket URL for http/https, rejection of a missing token or bad URL. |
| `drivers` | `Change.Check`: unsupported settings, ranges, hue and saturation together. |
| `yeelight` announce | parsing discovery answers and `NOTIFY`, rejecting other traffic, reading `support`. |
| `yeelight` driver | discovery and naming, power/state against a fake bulb over a real TCP socket, rediscovery after an address change, unreachable handling (and the 3-miss rule), bulb errors, channels from `support`, property parsing, command order, refusal of impossible changes, announcements without `support` not erasing channels, and every lamp15 quirk in section 7. |
| `agent` | first refresh reports devices and states, only changes are reported, new and lost devices announced, command read-back, failures reported (never dropped), channel changes, the expected-state fallback. |
| `link` | hello with authentication, answering commands, heartbeats, reconnect after a drop, retry with a wrong token, send failing when disconnected. |

Tests use fakes (a fake `Searcher`, an in-process fake bulb, an in-process WebSocket server) and
never touch the real network, so they are fast and run anywhere.

**Testing against real hardware** is manual: run the agent with `-v` against a local backend and
operate the app. When diagnosing a light, prefer read-only calls (`get_prop`) and restore any state
you change.

## 10. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Exit code 2: `invalid configuration` | No token, or `backendUrl` is not a full URL. | Fix the file or environment (section 2). |
| `backend link down, will retry … 401` | Wrong or revoked agent token. | Create a new token in the app (Settings → Home agent) and update the config. |
| `backend link down … connection refused` / timeout | Backend not running, wrong URL, or a proxy that blocks WebSocket upgrades. | Open `<backendUrl>/api/health` in a browser; check Caddy/proxy WebSocket support. |
| `connected to backend`, but the app shows the home agent offline | The backend closed an older/duplicate connection, or the clock jumped. | Make sure only one agent runs (a second one steals the connection, close code 4000). |
| No devices found | LAN Control off in the Yeelight app; agent on a different subnet/VLAN; multicast blocked; agent in a macOS/Windows Docker VM. | Enable LAN Control; run natively or with `--network host` on Linux; check router "multicast / IGMP" settings. |
| `not listening for bulb advertisements` | Multicast listening is unavailable. | Harmless; active search still works. |
| A light is listed but "unreachable" | It lost Wi-Fi or power, or its IP changed and discovery did not see it. | Power-cycle it; it returns by itself on the next discovery round (≤ 60 s). |
| Colour or background controls missing | Old agent process still running. | Restart the agent (section 1). |
| "Background light will not switch on" / "main light shows on but is stuck" | Light-bar quirks. | Fixed in this agent (section 7); make sure the running binary is current. |
| Commands "did not answer in time" | Backend waits `app.devices.command-timeout` for the result; the agent or the light is slow. | Check agent logs with `-v`; check Wi-Fi quality at the light. |

## 11. Limits and known gaps

- One driver (Yeelight). Zigbee and others are designed for ([ARCHITECTURE.md](../ARCHITECTURE.md)) but
  not implemented.
- State is **polled** (30 s), not pushed. Changes made with the Yeelight app or a wall switch appear
  in this app within about 30 s. (The Yeelight protocol can push notifications over a persistent
  connection; this agent uses short-lived connections instead.)
- No local fallback when the backend or internet is down: lights then work only through their own
  apps or switches. This is a deliberate trade-off of the VPS design.
- The agent has no local UI or health endpoint; use its log.
- The colour temperature range is assumed, not read (section 7).
- Commands are not queued while offline.
