# Architecture – Smart Door Display (MVP)

Companion to `PRD.md` (requirement IDs such as W2, T4, G2 refer to it) and `smart-door-display-mvp.md` (original plan, superseded where `PRD.md` says so). Diagrams are Mermaid and render on GitHub, in VS Code and in most Markdown viewers.

Revision: 2026-10-24, v3. The backend runs on a VPS by default, and the Raspberry Pi hosts a home agent that is the only component that talks to local devices. Section 12 records what the first implementation settled.

## 1. Principles

1. **The backend can live anywhere.** It needs only a public HTTPS URL. Nothing in it assumes the home LAN.
2. **The Pi agent is the only component on the home network that talks to devices.** It connects *out* to the backend; nothing at home listens for the internet.
3. **Clients are thin.** Phone and door screen render state and send intents ("complete task 7", "turn light off"). No business logic, no secrets.
4. **Serve cached data first, refresh in the background.** Every external source has a cache with a `fetchedAt` timestamp; requests never wait on the upstream.
5. **Devices sit behind interfaces at two levels.** The backend knows "devices" and "power" (`DeviceAdapter`); the agent knows protocols (`DeviceDriver`).
6. **One codebase, two layouts.** Phone and wall mode are the same React app with different layouts, served from the same URL.
7. **Push state, don't poll from clients.** The backend pushes change signals over SSE; clients refetch a full snapshot after any reconnect.
8. **Least privilege per credential.** Phone (full), display (read, tick tasks, toggle lights) and agent (device channel only) each get their own token.

## 2. System context

Who talks to whom, and over what.

```mermaid
flowchart LR
  subgraph Cloud["Cloud"]
    subgraph VPS["VPS, EU, Docker Compose"]
      Proxy["Reverse proxy<br/>Caddy, HTTPS"]
      Backend["Backend<br/>Spring Boot"]
      DB[("Task database<br/>SQLite or H2 file")]
    end
    Weather["Open-Meteo<br/>HTTPS"]
    iCloud["iCloud Calendar<br/>CalDAV over HTTPS"]
  end
  subgraph Home["Home network, no inbound ports"]
    Door["Door screen<br/>Pi 4, Chromium kiosk"]
    Agent["Home agent<br/>on the same Pi"]
    Lights["Yeelight lights<br/>LAN control, TCP JSON"]
    Zigbee["Zigbee devices<br/>later, via coordinator on the Pi"]
  end
  Phone["Phone<br/>PWA, Safari<br/>anywhere"]

  Phone -- "HTTPS + SSE" --> Proxy
  Door -- "HTTPS + SSE" --> Proxy
  Agent -- "outbound WSS" --> Proxy
  Proxy --> Backend
  Backend --- DB
  Backend -- "fetch forecast" --> Weather
  Backend -- "read events" --> iCloud
  Agent -- "commands and state" --> Lights
  Agent -. "later" .-> Zigbee
```

The Pi runs two independent processes: the browser (display) and the agent (gateway). They do not talk to each other in the MVP.

## 3. Component responsibilities

### 3.1 Overview

```mermaid
flowchart TB
  subgraph Clients["Clients - one React + TypeScript app"]
    UI["UI layouts<br/>phone and /wall"]
    Store["Client state store<br/>last known snapshot"]
    Net["Connection manager<br/>REST, SSE, reconnect"]
    SW["Service worker<br/>PWA install, asset cache"]
  end
  subgraph PiAgent["Pi home agent - Go"]
    Link["Backend link<br/>WSS client, reconnect, heartbeat"]
    Router["Command router<br/>and state reporter"]
    Drivers["Device drivers<br/>Yeelight with LAN discovery<br/>Zigbee later"]
    ACfg["Agent config<br/>backend URL and token"]
  end
  subgraph BE["Backend - Spring Boot"]
    Sec["Security<br/>login, tokens, rate limiting"]
    API["REST controllers"]
    Stream["SSE hub"]
    AgentGw["Agent gateway<br/>WebSocket endpoint"]
    TaskSvc["Task service"]
    WeatherSvc["Weather service"]
    CalSvc["Calendar service"]
    SettingsSvc["Settings service<br/>location and timezone"]
    DevSvc["Device service"]
    RAdapter["RemoteAgentAdapter"]
    Sched["Schedulers"]
    Cache["In-memory caches<br/>with fetchedAt"]
    Cfg["Config and secrets"]
    Repo["Task repository"]
  end
  UI --> Store --> Net
  Net --> Sec
  Sec --> API
  Sec --> Stream
  API --> TaskSvc
  API --> WeatherSvc
  API --> CalSvc
  API --> DevSvc
  API --> SettingsSvc
  SettingsSvc --> WeatherSvc
  SettingsSvc --> TaskSvc
  SettingsSvc --> CalSvc
  SettingsSvc --> Stream
  Sched --> WeatherSvc
  Sched --> CalSvc
  TaskSvc --> Repo
  WeatherSvc --> Cache
  CalSvc --> Cache
  DevSvc --> RAdapter
  RAdapter --> AgentGw
  AgentGw <-- "WSS" --> Link
  Link --> Router --> Drivers
  ACfg --> Drivers
  TaskSvc --> Stream
  WeatherSvc --> Stream
  CalSvc --> Stream
  DevSvc --> Stream
  Cfg --> WeatherSvc
  Cfg --> CalSvc
  Cfg --> Sec
```

### 3.2 Responsibility table

| Component | Owns | Does **not** do |
| --- | --- | --- |
| **Phone PWA** | Task entry and editing, light control, installable shell | Talk to Apple, Open-Meteo or lights; store secrets |
| **Door screen (Chromium kiosk)** | Showing the wall layout, ticking tasks, toggling lights, showing the reconnecting notice, a local offline page at boot | Run any logic; talk to the agent in the MVP |
| **UI layouts** | Rendering (phone layout, `/wall` layout), touch targets, clock | Fetching directly from services |
| **Client state store** | The last known snapshot of weather, tasks, events and devices; staleness flags | Persisting anything beyond a cache for offline display |
| **Connection manager** | REST calls, the SSE stream, backoff reconnect, full refetch after reconnect, `visibilitychange` handling on iOS | Interpreting business rules |
| **Service worker** | PWA install, caching static assets so the shell loads when the backend is briefly down | Caching API data as truth |
| **Security** | Phone login and session, display pairing (device-code style, QR or typed code) and token, agent token, scope checks, login rate limiting, token revocation | Business logic |
| **REST controllers** | Validation, mapping DTOs, HTTP status codes | Business logic, upstream calls |
| **SSE hub** | Tracking connected clients, broadcasting change events, heartbeats | Holding state; it only forwards "something changed" |
| **Agent gateway** | The `/agent/ws` endpoint: authenticating the agent, holding the single connection, correlating commands with results, heartbeat | Knowing device protocols |
| **Task service / repository** | Task rules ("today's list"), persistence, publishing `tasks` events | Calendar data (tasks live only in the app) |
| **Settings service** | The saved home location (`name`, `latitude`, `longitude`, `timezone`), place search through the Open-Meteo Geocoding API, publishing `settings.changed`, serving the timezone to every service that needs "today" | Forecast fetching; it never invents a default location |
| **Weather service** | Calling Open-Meteo on a schedule with the location from the Settings service, mapping to the app's model, caching, flagging stale data; skipping the fetch when no location is set | Calling upstream during a client request; holding coordinates itself |
| **Calendar service** | CalDAV auth, listing calendars, today's events, all-day handling, finding the next event, caching | Writing to the calendar (read-only) |
| **Device service** | The device registry, last known states, routing commands through the adapter, publishing `devices` events | Knowing protocol details |
| **RemoteAgentAdapter** | Implementing `DeviceAdapter` by sending commands to the agent and waiting for results (5 s limit), marking devices unavailable when the agent is offline | Talking to bulbs |
| **Schedulers** | Timing: weather ~15 min, calendar ~5-10 min, midnight rollover | Business logic (they just call services) |
| **Caches** | Latest good value per source plus `fetchedAt` | Long-term storage |
| **Config and secrets** | iCloud app-specific password, password hash, calendar include-list, deployment values (domain, ports) | Holding location or timezone (those are user settings in the database); being exposed to clients, ever |
| **Agent backend link** | Outbound WSS, auth with the agent token, reconnect with backoff, heartbeat | Inbound listening of any kind |
| **Agent command router / state reporter** | Dispatching commands to the right driver, sending `hello` and `state` messages, polling states every 30-60 s | Business rules, UI concerns |
| **Device drivers** | One per protocol: discover devices, keep the device id to current address map, read state, set power, re-discover when an address stops working, report unreachable | Talking to the backend directly; storing IP addresses anywhere permanent |
| **Agent config** | Backend URL and agent token in a local file on the Pi | Device IP addresses (found by discovery); living in the repo |

## 4. Backend internals

### 4.1 Package layout (proposed)

```
backend/src/main/java/.../homedisplay/
  api/        REST controllers, DTOs, error handling
  security/   login, display pairing, tokens, filters, rate limiting
  stream/     SSE hub, event types
  tasks/      Task entity, repository, service
  settings/   Setting entity, SettingsService, GeocodingClient (Open-Meteo Geocoding)
  weather/    OpenMeteoClient, WeatherService, WeatherModel
  calendar/   CalDavClient, CalendarService, EventModel
  devices/    DeviceService, DeviceAdapter, Device, DeviceState,
              DeviceRepository + DeviceInventory (stored devices, quick access)
  rooms/      Room, RoomRepository, RoomService
    agent/    AgentGateway (WebSocket), RemoteAgentAdapter, protocol messages
  schedule/   scheduled jobs
  config/     typed @ConfigurationProperties, clock bean

agent/                         Go module, one static binary
  cmd/agent/       main.go: wiring, signal handling
  internal/link/   WebSocket client, reconnect, heartbeat, protocol messages
  internal/drivers/
    driver.go      DeviceDriver interface
    yeelight/      discovery (multicast), connection per bulb, id -> address map
    (zigbee/       later)
  internal/config/ loads the local config file (backend URL, token)
```

Dependency rule: `api` depends on the services; services depend on clients, adapters and repositories; nothing depends on `api`. Services publish events to `stream` through a small `ChangePublisher` interface so they stay testable. A fake `DeviceAdapter` lets the UI be built before the agent exists.

### 4.2 Class view of the device layers (the extension point)

```mermaid
classDiagram
  class DeviceService {
    +listDevices() List~Device~
    +setPower(id, on)
    +setChannel(id, channel, change)
  }
  class DeviceAdapter {
    <<interface, backend>>
    +devices() List~Device~
    +getState(id) DeviceState
    +setPower(id, on)
    +setChannel(id, channel, change)
  }
  class RemoteAgentAdapter {
    sends command, awaits result
  }
  class FakeDeviceAdapter {
    for UI dev and tests
  }
  class AgentGateway {
    WebSocket endpoint
    send(command) Future
  }
  class DeviceDriver {
    <<interface, agent>>
    +discover() Device[]
    +getState(id) DeviceState
    +setPower(id, on)
    +set(id, channel, change)
  }
  class YeelightDriver
  class ZigbeeDriver {
    <<later>>
  }
  DeviceService --> DeviceAdapter
  DeviceAdapter <|.. RemoteAgentAdapter
  DeviceAdapter <|.. FakeDeviceAdapter
  RemoteAgentAdapter --> AgentGateway
  AgentGateway ..> DeviceDriver : via WSS
  DeviceDriver <|.. YeelightDriver
  DeviceDriver <|.. ZigbeeDriver
```

Adding Zigbee means adding one `ZigbeeDriver` in the agent. The backend, API, SSE events and UI do not change (L4): a device is a list of channels, and the UI is driven by each channel's capabilities, never by the protocol behind it (D1-D2).

## 5. Data model

Persisted: tasks, settings, credentials and, for the home organisation, rooms, devices and quick-access slots. Everything else is a cache rebuilt from upstream (or from the agent's `hello`) after a restart. Credentials are stored as hashes, not as tokens.

```mermaid
erDiagram
  TASK {
    long id PK
    string title
    date dueDate "nullable"
    boolean done
    instant createdAt
    instant completedAt "nullable"
  }
  CREDENTIAL {
    string id PK
    string kind "user, display or agent"
    string tokenHash
    string label
    instant createdAt
    instant revokedAt "nullable"
  }
  SETTING {
    string key PK "for example location"
    json value "name, latitude, longitude, timezone"
    instant updatedAt
  }
  ROOM {
    long id PK
    string name
    int position
  }
  DEVICE {
    string id PK "stable id from the agent, e.g. yeelight-0x15243F"
    string type
    string reportedName "what the device calls itself"
    string nameOverride "nullable, set in the app only"
    json channels "capabilities per channel, from the agent"
    long roomId FK "nullable"
    instant firstSeenAt
    instant lastSeenAt
  }
  SCENE {
    long id PK
    string name "unique ignoring case"
    int position
    string kind "custom or all_off"
  }
  SCENE_ACTION {
    long id PK
    long sceneId FK "cascade"
    string deviceId FK "cascade"
    string channel
    json settings "on, brightness, colorTemp, hue, saturation; absent means leave alone"
    int position
  }
  QUICK_ACCESS {
    int position PK "1 to 4"
    string deviceId FK "a light: deviceId and channel"
    string channel "unique with deviceId"
    long sceneId FK "or a scene; exactly one kind"
  }
  ROOM ||--o{ DEVICE : "contains (set null on delete)"
  SCENE ||--o{ SCENE_ACTION : "has"
  DEVICE ||--o{ SCENE_ACTION : "targeted by"
  SCENE ||--o| QUICK_ACCESS : "pinned (cascade)"
  DEVICE ||--o| QUICK_ACCESS : "pinned (cascade)"
```

`CREDENTIAL` holds user, display and agent tokens (hashed, revocable). `SETTING` is a small key-value table; the `location` row holds the home location chosen in the app (no row means "not set yet").

**Devices.** The agent decides which devices exist and reports their live state. The `DEVICE` table is an inventory plus the user's organisation of it: it is filled by a `DevicesDiscoveredEvent` whenever the agent reports devices (first seen, last seen, reported name) and never stores power or reachability, which change every few seconds and are owned by the agent. A device the agent stops reporting keeps its row, so it stays in its room as unavailable; only the user can forget it. Because the primary key is the agent's stable id (not an IP), an assignment survives address changes. `DEVICE.channels` holds each channel's capabilities as reported by the agent (live channel state is not stored), so controls and pinned tiles exist while the bulb is offline. `QUICK_ACCESS` has one row per pinned slot; the primary key on `position` with a check `position between 1 and 4` makes the limit of four a database rule, and `(deviceId, channel)` is unique so a channel is pinned once. A slot pins a channel, so a light bar's main and background lights can be two tiles, or a scene.

**Scenes.** A scene is a row plus ordered `SCENE_ACTION` rows. An action stores only the settings that are included (the JSON has no entry for a setting left alone), which is what makes "set the colour, keep the brightness" possible. The built-in `All off` scene is a row with `kind = all_off` and no actions; its effect is computed when it is applied. There is no `HOME` table: this is a single household, so rooms are top-level.

In-memory cache shapes (not persisted):

| Cache | Content | Refresh |
| --- | --- | --- |
| Weather | current + today's forecast, `fetchedAt` | ~15 min |
| Calendar | today's events, next-event id, `fetchedAt` | ~5-10 min, also at midnight rollover |
| Device state | per device: power, reachable, `updatedAt`; agent online flag. Merged with the stored devices on read | pushed by the agent (poll 30-60 s on its side, plus after each command) |

## 6. Flows

### 6.1 Client startup and live connection

The same sequence for the phone and the Pi after boot (credentials are already present).

```mermaid
sequenceDiagram
  participant U as Client app
  participant B as Backend
  U->>B: GET / (app shell)
  B-->>U: HTML, JS, CSS
  par Load snapshot
    U->>B: GET /api/weather
    U->>B: GET /api/tasks
    U->>B: GET /api/calendar/today
    U->>B: GET /api/devices
  end
  B-->>U: Cached data with fetchedAt
  U->>B: GET /api/stream (SSE)
  B-->>U: stream open, ping every ~20 s
  Note over U: Render. Show stale badges if fetchedAt is old.
```

### 6.2 Sign-in and display pairing (A2, A3, A3a, A3b)

Pairing starts on the display, because the Pi has no keyboard or camera. The user finishes it on the phone either by scanning the QR code or by typing the short code.

```mermaid
sequenceDiagram
  participant P as Phone
  participant B as Backend
  participant D as Door screen
  P->>B: POST /api/auth/login {password}
  B-->>P: session cookie (long-lived)
  Note over D: Unpaired kiosk opens the pairing page
  D->>B: POST /api/auth/pair/start
  B-->>D: deviceCode (secret), userCode, approveUrl, expiresIn ~10 min
  Note over D: Shows userCode and a QR code of approveUrl
  loop until approved or expired
    D->>B: GET /api/auth/pair/poll?deviceCode=...
    B-->>D: pending
  end
  alt user scans the QR code
    P->>B: open approveUrl (signed in)
  else user types the code
    P->>B: Settings, Add display, enter userCode
  end
  P->>B: POST /api/auth/pair/approve {userCode}
  B->>B: create scoped display token, store its hash
  B-->>P: approved
  D->>B: GET /api/auth/pair/poll?deviceCode=...
  B-->>D: display token (returned once)
  Note over D: Token stored in the browser. Kiosk now opens /wall signed in
  P->>B: DELETE /api/credentials/{id}
  Note over P,B: Revoke a lost display or an old agent token
```

Safety rules: the QR code and `userCode` are useless without the secret `deviceCode`, which only the display knows; codes expire, work once, are rate-limited and compared in constant time; the display token is handed over exactly once.

### 6.3 Task created on the phone, appears on the door screen (T1, T4)

```mermaid
sequenceDiagram
  participant P as Phone
  participant B as Backend
  participant DB as Database
  participant D as Door screen
  P->>B: POST /api/tasks {title}
  B->>DB: insert task
  DB-->>B: saved task
  B-->>P: 201 + task
  B-->>P: SSE event tasks.changed
  B-->>D: SSE event tasks.changed
  D->>B: GET /api/tasks
  B-->>D: today's tasks
  Note over D: List updates within about 2 s
```

Events carry only "what changed", so clients refetch the small payload instead of merging deltas.

### 6.4 Agent connects (G2, G3)

```mermaid
sequenceDiagram
  participant A as Pi agent
  participant G as Agent gateway
  participant S as Device service
  participant H as SSE hub
  A->>G: open WSS /agent/ws with agent token
  alt token valid
    G-->>A: accepted
    A->>A: read config, query drivers
    A->>G: hello {agentId, version, devices, states}
    G->>S: agent online, devices and states
    S->>H: devices.changed
    loop every ~20 s
      A->>G: ping
      G-->>A: pong
    end
  else token invalid or revoked
    G--xA: 401, close
    Note over A: Back off and retry, log the error
  end
```

### 6.5 Light toggled from the door screen (L1, L2, L3, L7)

```mermaid
sequenceDiagram
  participant D as Door screen
  participant B as Backend
  participant R as RemoteAgentAdapter
  participant A as Pi agent
  participant L as Light bar
  participant P as Phone
  D->>B: POST /api/devices/{id}/power {on:false}
  B->>R: setPower(id, false)
  R->>A: command {id:c1, setPower, on:false}
  A->>L: set_power off (TCP JSON, LAN)
  alt light replies ok
    L-->>A: ok
    A-->>R: result {id:c1, ok:true, state}
    R-->>B: new state off
    B-->>D: 200 + device state
    B-->>P: SSE event devices.changed
    B-->>D: SSE event devices.changed
  else light unreachable
    L--xA: timeout
    A-->>R: result {id:c1, ok:false, error}
    B-->>D: 502 + device marked unreachable
    Note over D: Toggle reverts, shows "light unavailable"
  else agent offline or no result in 5 s
    R--xB: no result
    B-->>D: 503 agent unavailable
    Note over D: Toggle reverts, shows "offline"
  end
```

The UI may toggle optimistically but must revert on failure. The same flow applies for the phone, wherever it is.

### 6.6 Weather refresh (W2, O3)

```mermaid
sequenceDiagram
  participant S as Scheduler
  participant W as Weather service
  participant G as Settings service
  participant O as Open-Meteo
  participant C as Weather cache
  participant H as SSE hub
  participant X as Clients
  S->>W: refresh (every ~15 min)
  W->>G: current location
  alt no location saved
    G-->>W: none
    Note over W,X: Skip the call. Weather card shows "Set your location"
  else location saved
    G-->>W: name, latitude, longitude, timezone
    W->>O: GET forecast (latitude, longitude, timezone)
  end
  alt success
    O-->>W: JSON
    W->>C: store model, fetchedAt = now
    W->>H: weather.changed
    H-->>X: SSE event
    X->>W: GET /api/weather (via controller)
  else failure or timeout
    O--xW: error
    W->>C: keep last value, record lastError
    Note over C,X: Clients still get cached data and see it is stale by fetchedAt
  end
```

### 6.7 Calendar refresh (C1-C4)

```mermaid
sequenceDiagram
  participant S as Scheduler
  participant K as Calendar service
  participant I as iCloud CalDAV
  participant C as Calendar cache
  participant H as SSE hub
  S->>K: refresh (every 5-10 min and at midnight)
  K->>I: PROPFIND/REPORT calendars (app-specific password)
  I-->>K: calendars and today's events
  K->>K: filter by include-list, split all-day vs timed, sort, pick next event
  K->>C: store events, fetchedAt = now
  K->>H: calendar.changed (only if content differs)
  Note over K,I: On auth or network failure keep the last cache and record lastError
```

The "next event" highlight is recomputed on read using the current time, so it stays right between refreshes.

### 6.8 Connection loss and recovery on the door screen (O1, O2, M5)

```mermaid
stateDiagram-v2
  [*] --> OfflinePage: Pi boots, backend not reachable
  [*] --> Loading: Pi boots, backend reachable
  OfflinePage --> Loading: local page retries and backend answers
  Loading --> Live: snapshot loaded and SSE open
  Live --> Reconnecting: SSE error, ping missed, or fetch fails
  Reconnecting --> Reconnecting: retry with backoff (1s, 2s, 5s, max 30s)
  Reconnecting --> Live: SSE reopened, then full refetch of all data
  Live --> Live: SSE event, refetch changed resource
```

While in `Reconnecting` the UI keeps rendering the last snapshot, shows a "reconnecting" notice and disables light toggles (O4); the page is never reloaded or blanked. Reaching `Live` always triggers a full refetch so missed events do not matter. `OfflinePage` is a tiny static page stored on the Pi that polls the backend URL.

### 6.9 Agent link states (G2, G8)

```mermaid
stateDiagram-v2
  [*] --> Connecting
  Connecting --> Online: token accepted and hello received
  Connecting --> Connecting: failure, retry with backoff (max 60s)
  Online --> Offline: close, error, or no ping for 60s
  Offline --> Connecting: agent reconnects
  Online --> Online: state messages and command results
```

Backend view: in `Offline` all devices are shown "unavailable" with their last known state, and commands fail fast with 503.

### 6.10 Failure isolation

```mermaid
flowchart TB
  F1["Open-Meteo down"] --> R1["Weather card shows stale badge<br/>other cards unaffected"]
  F2["iCloud auth or network error"] --> R2["Calendar card shows last events<br/>with stale badge"]
  F3["Light unreachable"] --> R3["Device shown as unavailable<br/>commands return an error"]
  F4["Pi agent offline or crashed"] --> R4["All devices unavailable, last state shown<br/>weather, tasks, calendar unaffected"]
  F5["Home internet down"] --> R5["Door screen keeps last snapshot with reconnecting notice<br/>agent offline, light toggles disabled"]
  F6["Backend or VPS down"] --> R6["Clients keep last snapshot, auto retry<br/>agent reconnects with backoff"]
  F7["Database error"] --> R7["Task writes fail with an error toast<br/>reads and other cards still work"]
```

### 6.11 Setting the home location (S1-S3, W7)

```mermaid
sequenceDiagram
  participant P as Phone
  participant B as Backend
  participant G as Settings service
  participant GC as Open-Meteo Geocoding
  participant W as Weather service
  participant H as SSE hub
  participant D as Door screen
  P->>B: GET /api/geocode?q=Kraków
  B->>G: search("Kraków")
  G->>GC: GET search?name=Kraków
  GC-->>G: candidates (name, country, lat, lon, timezone)
  G-->>P: candidates
  Note over P: User picks one
  P->>B: PUT /api/settings/location {name, latitude, longitude, timezone}
  B->>G: validate (ranges, IANA timezone) and save
  G->>W: location changed, refresh now
  G->>H: settings.changed
  H-->>P: SSE event
  H-->>D: SSE event
  D->>B: GET /api/settings and GET /api/weather
  Note over D: Weather and "today" now use the new place and timezone
```

The timezone changes what "today" means, so on a location change the backend also recomputes the today views of tasks and calendar and refreshes the calendar. Both come from the same saved timezone, so the system never disagrees with itself about the date.

### 6.12 Device discovery and address changes (G9, G10, G12)

```mermaid
sequenceDiagram
  participant Y as Yeelight driver
  participant L as LAN (multicast)
  participant Bulb as Light bar
  participant R as Agent link
  participant B as Backend
  Y->>L: M-SEARCH wifi_bulb to 239.255.255.250:1982
  Bulb-->>Y: response {id, name, power, address}
  Y->>Y: update id to address map
  Y->>R: devices and states changed
  R->>B: devices / state messages
  Note over Y,Bulb: Repeats about every 60 s, and bulb advertisements are also heard
  B->>R: command setPower(id)
  R->>Y: setPower(id, on)
  Y->>Bulb: connect to last known address
  alt address no longer valid (DHCP changed it)
    Bulb--xY: connection failed
    Y->>L: M-SEARCH immediately
    Bulb-->>Y: response with the new address
    Y->>Y: update id to address map
    Y->>Bulb: retry once at the new address
    Bulb-->>Y: ok
  else still not found
    Y-->>R: error, device unreachable
    Note over Y,R: Device kept in the list, reachable false
  end
```

Devices are always identified by their stable id; IP addresses exist only inside the driver at runtime.

### 6.13 New device, rooms and quick access (R1-R12)

```mermaid
sequenceDiagram
  autonumber
  participant Agent as Home agent
  participant GW as AgentGateway
  participant API as Backend API
  participant Inv as DeviceInventory
  participant DB as SQLite
  participant Hub as SseHub
  participant Phone as Phone app
  participant Wall as Door screen

  Agent->>GW: devices (new bulb found)
  GW->>Inv: DevicesDiscoveredEvent
  Inv->>DB: upsert device (first_seen on insert, last_seen always)
  Inv->>Hub: home.changed (only if something new)
  Hub-->>Phone: home.changed
  Phone->>Phone: refetch /api/devices and /api/rooms
  Note over Phone: The bulb shows under "Unassigned"
  Phone->>API: PATCH /api/devices/{id} {roomId, name}
  Phone->>API: PUT /api/quick-access {deviceIds}
  API->>DB: update (one transaction)
  API->>Hub: home.changed
  Hub-->>Wall: home.changed
  Wall->>Wall: refetch, tile appears in Quick access
```

Reads merge two sources: `DeviceRepository` (what exists and how it is organised) and the `DeviceAdapter` (live power and reachability). A stored device with no live entry is returned as unreachable. This is also what makes the structure visible right after a backend restart, before the agent has reconnected (R11).

Write rules: only the user credential may change rooms, assignments, names and quick access; the display may toggle power as before. Setting quick access replaces all slots in one transaction, rejects more than four, duplicates and unknown ids, and the same call is how a device is unpinned or reordered. Deleting a room sets `room_id` to null on its devices; forgetting a device removes its row and, through the cascade, its quick-access slot.

### 6.14 Setting brightness or colour (D1-D9)

```mermaid
sequenceDiagram
  autonumber
  participant UI as Phone or door screen
  participant API as Backend API
  participant GW as AgentGateway
  participant Agent as Home agent
  participant Bulb as Yeelight bulb

  UI->>UI: user drags the brightness slider
  UI->>API: POST /api/devices/{id}/channels/background {brightness: 40} (on release)
  API->>API: check channel exists, brightness supported, 1..100
  API->>GW: command set {channel, args}
  GW->>Agent: command (over WSS)
  Agent->>Bulb: bg_set_bright [40, "smooth", 300]
  Bulb-->>Agent: ok
  Agent->>Bulb: get_prop (read back)
  Bulb-->>Agent: power, bright, ct, hue, sat, bg_*...
  Agent-->>GW: result {ok, state with channels}
  GW-->>API: new device state
  API-->>UI: updated device
  API-->>UI: devices.changed to the other screens
```

Capabilities travel agent to backend only: the agent builds them from the bulb's discovery answer (`support` list: `set_bright`, `set_ct_abx`, `set_hsv`, and the `bg_` variants for a background light) plus a model table for the colour temperature range, and the backend validates against them (400 for an unsupported or out-of-range value). The agent still refuses what the bulb cannot do, so a stale backend cannot send a bad command. Sliders send on release because Yeelight accepts about 60 commands a minute per bulb; the agent also spaces commands 250 ms apart. For a change with several fields the agent sends them in a fixed order: power, brightness, then temperature or colour.

### 6.15 Applying a scene (SC3-SC4, SC7)

```mermaid
sequenceDiagram
  autonumber
  participant UI as Phone or door screen
  participant API as Backend API
  participant DB as SQLite
  participant Agent as Home agent

  UI->>API: POST /api/scenes/{id}/apply
  API->>DB: scene and its actions
  Note over API: all_off: actions = every channel of every present, reachable device, on = false
  API->>API: group actions by device
  par device A, actions in order
    API->>Agent: set channel (on implied by brightness or colour)
    Agent-->>API: result with read-back state
  and device B
    API->>Agent: set channel
    Agent-->>API: result
  end
  API-->>UI: {applied, failed[]}
  API-->>UI: devices.changed to the other screens
```

An action's stored settings are turned into the change that is sent: if it sets brightness, white or colour and does not mention power, power is added as `on: true`; `on: false` drops the other settings. Each change is validated against the channel's current capabilities first, so a light that no longer supports a stored setting fails that action with a reason instead of being sent. A command error never aborts the scene: it is recorded for that action and the others continue. If the agent is offline the call fails with 503 before anything is sent. **Capture** reads each chosen light's live values from the merged device state (on or off; when on, brightness and either the white or the colour it is currently showing) and stores them as an ordinary scene.

## 7. API and event contract

REST endpoints and credentials are listed in `PRD.md` section 6. SSE and the agent protocol are below.

### SSE events (backend to clients)

| Event name | Meaning | Client reaction |
| --- | --- | --- |
| `tasks.changed` | Task list changed | `GET /api/tasks` |
| `devices.changed` | Any device state or agent availability changed | `GET /api/devices` |
| `home.changed` | Rooms, assignments, names, quick access or the set of known devices changed | `GET /api/rooms` and `GET /api/devices` |
| `weather.changed` | New weather cached | `GET /api/weather` |
| `calendar.changed` | New calendar content | `GET /api/calendar/today` |
| `settings.changed` | Location or other settings changed | `GET /api/settings`, then refetch weather, tasks and calendar |
| `ping` | Heartbeat about every 20 s | Reset the stale-connection timer; none for ~60 s means the connection is lost |

Every data response includes `fetchedAt` (or `updatedAt`) and a `stale` flag, set when the age exceeds a threshold (for example 2x the refresh interval). `GET /api/devices` also includes `agentOnline`.

### Agent WebSocket messages (JSON)

| Direction | Message | Fields |
| --- | --- | --- |
| agent → backend | `hello` | `agentId`, `version`, `devices[]`, `states[]` |
| agent → backend | `devices` | full list of known devices (`deviceId`, `name`, `type`, `channels[]` with `id`, `label`, `capabilities`), sent when discovery finds or loses a device |
| agent → backend | `state` | `deviceId`, `power` (any channel on), `reachable`, `updatedAt`, `channels[]` (`id`, `on`, `brightness?`, `colorTemp?`, `hue?`, `saturation?`, `colorMode?`) |
| backend → agent | `command` | `id`, `deviceId`, `action` (`setPower` for the whole device, or `set` with `channel` and `args`) |
| agent → backend | `result` | `id`, `ok`, `error?`, `state?` |
| both | `ping` / `pong` | heartbeat every ~20 s |

The channel fields are additions to protocol version 1 and are all optional: an agent without them is treated as a single power-only `main` channel, and an agent that does not know `set` answers with an error result.

Rules: the backend accepts one agent connection at a time (a new valid connection replaces the old one); every `command` gets exactly one `result` or times out after 5 s; unknown message types are ignored and logged; messages carry a `v` protocol version field so the agent and backend can be upgraded independently.

## 8. Deployment

```mermaid
flowchart TB
  subgraph VPSBox["VPS, Linux"]
    subgraph Compose["Docker Compose"]
      Caddy["caddy container<br/>HTTPS, automatic certificates<br/>ports 80 and 443"]
      App["app container<br/>Spring Boot + built React assets<br/>restart: unless-stopped"]
      Vol[("volume: data<br/>task database")]
    end
    Env[".env or secrets file<br/>iCloud password, password hash, domain"]
    Backup["Scheduled backup<br/>copy of the data volume to off-site storage"]
  end
  subgraph PiBox["Raspberry Pi 4 at home"]
    Chromium["Chromium kiosk<br/>starts on boot, opens https://domain/wall<br/>local offline page as fallback"]
    AgentSvc["Home agent<br/>systemd service<br/>Go binary<br/>config file: backend URL and token"]
  end
  Domain["Domain name<br/>DNS to the VPS"]
  PhoneD["Phone<br/>PWA from Home Screen"]

  Caddy --> App
  App --- Vol
  Env --> App
  Vol --> Backup
  Domain --> Caddy
  Chromium -- "HTTPS" --> Caddy
  AgentSvc -- "outbound WSS" --> Caddy
  PhoneD -- "HTTPS" --> Caddy
```

- **Single app image:** the Vite build is copied into Spring's static resources, so there is one URL and no separate web server for the app. Caddy only terminates HTTPS and proxies.
- **VPS firewall:** open only 80/443 (and SSH with key-only login, ideally restricted by IP). The home router needs no changes.
- **Pi:** a systemd unit for the agent (restart on failure) and an autostart entry for Chromium kiosk; both start at boot. Avoid frequent disk writes on the SD card (logs to RAM or journald limits).
- **Portability:** the same Compose file runs on a laptop or a home server for development; only the domain/proxy section differs.
- **Dev setup:** during phase 3-4 you can run the backend on a laptop and point the Pi and phone at it before the VPS exists.

## 9. Cross-cutting concerns

| Concern | Approach |
| --- | --- |
| **Secrets** | Server env/secrets file loaded into typed config; agent token in the Pi's config file; tokens stored hashed; nothing logged, nothing sent to clients, nothing committed |
| **Authentication** | Single-user password login with a hashed password and rate limiting; display token from one-time pairing; agent token; scope checked on every endpoint; revocation supported |
| **Transport** | HTTPS everywhere; agent uses WSS; SSE uses same-origin cookies or the display token header |
| **Time and location** | One injected `Clock` and one timezone, read from the saved location setting (nothing hard-coded). Until a location is saved, the server's own timezone is the fallback and weather is off. "Today" is computed on the server in that timezone; midnight rollover (in that zone) triggers calendar and task view refresh. The timezone value is validated as an IANA zone on save |
| **Observability** | Structured logs, `/api/health` for liveness (public: up/down only), an authenticated status view with per-source `lastSuccess`, `lastError`, agent online |
| **Resilience** | Timeouts on every upstream call, one in-flight refresh per source, keep last good value on failure, backoff everywhere, 5 s command timeout |
| **Rate limits** | Open-Meteo is cached, so about 100 calls a day, far under the ~10,000 limit |
| **Testing** | Unit tests for services with fake clients/adapters; contract tests for the REST API and the agent protocol; a fake `DeviceAdapter` and a fake agent for UI and backend work without lights |
| **Pi performance** | Pi 4 with 2 GB: light wall layout, no heavy animation; agent stays well under ~150 MB |
| **Versioning** | Protocol version field on agent messages; agent and backend deployable independently |

## 10. Decisions log

| Decision | Choice | Alternatives and reason |
| --- | --- | --- |
| Backend location | VPS by default, location-independent | Home server: needs hardware and a VPN for remote access; still supported because the agent connects out |
| Local device access | Pi home agent connecting out over WSS | Backend talking to the LAN: needs a VPN or open port when hosted away from home |
| Agent link | WebSocket (bidirectional, one connection) | MQTT: adds a broker; polling: higher latency |
| Agent runtime | Go, single static binary | Node.js: shares a language with the frontend but needs a runtime and more RAM; Spring Boot: too heavy for a 2 GB Pi sharing RAM with Chromium |
| Device addressing | Stable device id plus runtime discovery; no IPs in config | Fixed IPs: break whenever DHCP hands a bulb a new address |
| Display pairing | Display-initiated device code, finished by QR scan or typed code | Phone-initiated code typed on the Pi: needs an on-screen keyboard on a kiosk |
| Device state updates | Polling by the agent (30-60 s) | Yeelight pushed property notifications: lower latency, more connection handling; parked for later |
| HTTPS | Caddy with automatic certificates | Self-managed nginx + certbot: more manual steps |
| Location and timezone | User setting in the database, chosen by place search (Open-Meteo Geocoding), no default | Env/config values: hard-coded per deployment and need a redeploy to change; browser geolocation only: no timezone name and no label |
| Auth | Password login (phone), pairing token (display), agent token | Passkeys: nicer but more work; defer |
| Live updates | SSE | WebSocket is unnecessary for clients; commands go through REST |
| Event style | "changed" signal, then refetch | Delta merging adds client complexity and drift risk |
| Task storage | SQLite or H2 file | Postgres is overkill for one user |
| Hosting | Docker Compose on the VPS | K3s: no benefit for one container |
| Frontend delivery | Static assets served by the backend | Separate web server or CDN is not needed at this scale |
| Device organisation | Rooms, assignments, names and quick access stored in the backend database, keyed by the agent's stable device id | In the agent's config on the Pi: the agent has no UI and the phone must be able to edit it; as one JSON blob in `setting`: no integrity for dangling ids |
| Live device state | Not stored; merged on read from the agent's reports | Persisting power and reachability: stale within seconds and a second source of truth |
| Home entity | None; rooms are top-level | A `home` table: only needed for several households, which is out of scope |
| Rooms vs. tags | One room per device (nullable foreign key) | Many-to-many tags: more flexible but harder to present and explain |
| Quick access | Separate table with a four-slot primary key | A `pinned` flag on `device`: the limit and the order would live in application code only |
| Device model | Device has channels; each channel has capabilities and state | Flat Yeelight-specific fields (`bgBrightness`...): break for any other device; one entity per light as separate devices: loses the grouping under one bulb |
| Capability source | Agent derives them from the device; backend stores and validates against them | Hard-coded model tables in the backend or UI: spreads protocol knowledge outside the driver |
| Command shape | One `set` command with a partial change per channel | One command per property: more round trips and a quota hit for each |
| Slider commits | On release | Throttled live dragging: 60 commands a minute per bulb is easy to exceed |
| Quick access pins | A channel | A whole device: cannot show main and background as separate tiles |
| Scene model | Named list of per-channel actions across devices, settings individually optional | Snapshot of whole device states: cannot leave a setting alone; one scene per room or device: cannot span devices |
| Scene apply | Backend fans out ordinary `set` commands, parallel across devices, no rollback, partial failures reported | A new batch message to the agent: fewer round trips but a protocol change; rollback: needs state capture and can itself fail |
| All off | Built-in scene computed at apply time | Stored actions: new lights would be missed |
| Calendar access | Read-only CalDAV with an app-specific password | Apple has no public calendar API for web backends |

## 11. Open architecture questions

1. VPS provider, region, size and domain (nothing chosen yet).
2. ~~Display pairing UX~~ Decided: display-initiated, user chooses to scan the QR code or type the code (section 6.2). Still open: how the kiosk shows the pairing page before it holds a token (a route that is public but shows nothing sensitive).
3. ~~Agent language~~ Decided: Go.
4. ~~Yeelight addressing~~ Decided: discovery, not fixed IPs (section 6.12). Still open: whether the Pi's network passes multicast between the Pi and the bulbs; check early in phase 5.
5. ~~Yeelight state updates~~ Decided: polling for now; pushed property notifications parked for after the MVP.
6. ~~SQLite vs. H2 file~~ Decided: SQLite.
7. Local-first lights (parked): letting the kiosk talk to the agent on the LAN so lights work without internet. The agent could expose a tiny local-only API later without changing the cloud design.

## 12. Implementation notes

- **Stack as built:** Spring Boot 4.1.2 on Java 25 (Jackson 3), SQLite through Spring `JdbcClient` and Flyway, React 19 with TanStack Query, Go 1.25 with `coder/websocket`.
- **One mechanism for all credentials:** every client (phone, display, agent) presents a random token whose SHA-256 hash is in the `credential` table. Browsers get it as an HttpOnly, SameSite=Lax cookie (the display receives its cookie from the pairing poll, so the token never passes through page scripts); the agent sends it as a bearer token. A writes-must-be-same-origin filter backs up the cookie's SameSite setting.
- **Client data flow:** TanStack Query caches each resource. An SSE event invalidates the matching query, and every (re)connect invalidates everything. Because queries keep their last data when a refetch fails, the "reconnecting" state needs no extra storage.
- **Device layers as built:** `FakeDeviceAdapter` (dev) or `RemoteAgentAdapter` + `AgentGateway` + `AgentRegistry` (agent mode) in the backend; `DeviceDriver` with a Yeelight driver in the agent. The Yeelight driver searches by multicast, keeps an id-to-address map, re-searches and retries once when a command cannot reach a bulb, and opens a short-lived connection per command.
- **Calendar as built:** `CalendarSource` has `off`, `fake` and `caldav` implementations. The CalDAV source discovers principal, calendar home and calendars (cached one hour), queries each calendar by time range, and `ICalExpander` (ical4j) expands recurring events, handling excluded dates, moved and cancelled occurrences.
- **Not covered by automated tests:** real Yeelight bulbs, a real iCloud account, multicast on a real network, the Pi kiosk, and a live VPS deployment.
- **Rooms and quick access as built:** migration `V2__rooms_and_devices.sql` adds `room`, `device` and `quick_access`. `DeviceInventory` listens for `DevicesDiscoveredEvent` (published by `AgentGateway` on `hello` and on every device list, and by `FakeDeviceAdapter` at startup) and upserts the devices; it publishes `home.changed` only when a device is new or its reported name or type changed. `DeviceService.snapshot()` returns the stored devices merged with live state from the adapter (`present` is false for a stored device the agent does not list). A device the user forgot disappears from the list until the agent reports it again (on its next `hello` or device-list message). The test database enables `foreign_keys` like production, because the schema relies on `on delete set null` and `on delete cascade`.
- **Home tab as built:** the phone has a bottom tab bar (Dashboard, Home). `homeModel.ts` holds the pure grouping, ordering and availability logic (unit-tested). Edit mode on the Home tab covers rooms (add, rename, move, delete), assigning a device to a room, renaming, pinning and ordering quick access, and forgetting a missing device. The wall screen shows the Quick access panel; its "All devices" button switches the wall to `WallDevices`, a toggle-only list of rooms and devices (the same `DeviceRow` as the phone, without edit mode). `useIdle` returns it to the dashboard after 30 s without a touch. The wall stays one route and one piece of local state, not a router with tabs.
- **Channels as built:** `drivers.Channel` (capabilities) and `drivers.ChannelState` in the agent; `Change.Check` refuses unsupported or out-of-range changes before anything reaches the bulb. The Yeelight driver builds channels from the discovery `support` list (`bg_set_power` means a background light), asks `get_prop` for six properties per channel (`power`, `bright`, `ct`, `hue`, `sat`, `color_mode`, with `bg_` names for the background light), and sends `set_power`, `set_bright`, `set_ct_abx`, `set_hsv` (with `bg_` prefixes) with a 300 ms transition. After a command the agent reads the device back and puts that state in the command result; if the read-back fails it applies the command to the previous state instead.
- **Backend channels:** `V3__device_channels.sql` adds `device.channels` (JSON capabilities) and rebuilds `quick_access` with a `channel` column. `Device` carries `channels` and `states`; an agent that reports no channels gets a single power-only `main` channel. `ChannelChange.validate` implements the 400 rules; `AgentRegistry` merges reported channel states and applies commands optimistically only when a result carries no state.
- **Device panel:** `DevicePanel` and `ChannelControls` are driven by each channel's capabilities. `Slider` commits on release and keeps the dragged value until the request and the refetch finish. Quick access tiles toggle on tap and open the panel on a 500 ms hold. On the wall the panel and the All devices view share the 30 s idle return.
- **Yeelight lamp15 quirk:** on the Monitor Light Bar Pro (firmware 38) `bg_set_power "on"` and `bg_toggle` answer `ok` but leave the background light off, while `bg_set_power "off"` works and `bg_set_scene` switches it on. The driver therefore turns the background light on with `bg_set_scene` (when the bulb lists it in `support`), reading the light's last colour or white and brightness first and letting the change's own values override them; everything else uses the plain commands. Found by probing the real bar; the same driver code was run against it afterwards.
- **Scenes as built:** `V4__scenes.sql` adds `scene` and `scene_action`, seeds the built-in `All off` row (`kind = all_off`) and rebuilds `quick_access` so a slot holds a light or a scene (a check enforces exactly one). `SceneService` validates actions against the stored capabilities, applies a scene by grouping actions per device and running each group on its own virtual thread (the groups in parallel, the actions of a device in order), and returns `{applied, failed[]}`; `capture` reads the merged device state (`currentSettings`: off, or on with brightness and either colour or white). Settings are stored as the JSON of a `ChannelChange`, so a setting left alone is simply absent.
- **Scene UI:** `ScenesSection` (apply grid, or manage list with pin, order, edit, delete), `SceneEditor` (a block per light with a checkbox per setting, white and colour exclusive), `CaptureDialog` (name and which lights), and scene tiles in `QuickAccessCard`. The editing model (`sceneModel.ts`: drafts, include flags, conversion to the request) is pure and unit-tested.
- **Visual design:** the interface follows Apple's iOS and Home app conventions. `styles.css` defines the tokens (system blue, green, red and orange, the grouped grey canvas and white or dark cards, translucent fills, hairline separators, continuous 20-26 px corners, the `-apple-system` font stack) for light and dark; the wall forces dark. Components: large page titles, small capitalised card headings, an iOS switch (green, white knob), thick Home-style sliders (the `--pf` custom property set by `Slider` fills the track up to the handle; the white and colour sliders keep their gradients), a segmented control, round Reminders-style task checks, Home-app tiles (grey when off, white when on, bulb or sparkle icon), a translucent tab bar and centred sheets with a blurred scrim. The weather card has a day or night sky. The wall avoids blur effects because they are expensive on a Raspberry Pi. Colours that must work in both themes go through tokens, so a theme change needs no component changes.
- **Yeelight lamp15, main light and device power:** `power` is the power of the **device** (on when any light is on); the bar also reports **`main_power`**, the main light alone. The driver reads `main_power` for the main channel's state, so a bar that is in its *background-only* mode (power on, `main_power` off) shows the main light off. Measured on the real bar: `set_power off` switches the whole device off when both lights are on, but is a no-op when only the background glows; `set_power on` turns the main light on and keeps the background; `bg_set_power on` is ignored; `bg_set_scene` switches the background on and, from "everything off", leaves the main light off. "Main off, background on" is therefore built as: read the background's colour and brightness, `set_power off`, then `bg_set_scene` with those values (`mainOff`). A device-wide off switches the background off first, so it is not lit again for nothing. A main-off on a bar that reports no `main_power`, or whose background is off, is a plain `set_power off`.
