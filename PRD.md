# PRD – Smart Door Display (MVP)

Source: `smart-door-display-mvp.md` (24 Oct 2026). This document turns that plan into requirements I can build against, and **supersedes the source where they differ** (see "Changes from the source plan" below). Where the source is silent I mark an **Assumption**; change any of them and I will follow.

Revision: 2026-10-24, v3. Backend moved from a home server to a VPS by default, the Raspberry Pi became the local gateway for home devices, and the first implementation exists (see section 13).

## 1. Summary

One web app, served by a Spring Boot backend on a **VPS**, shows weather, today's tasks and Apple Calendar events and switches Yeelight lights. It runs as an installable PWA on the phone and full screen ("wall mode") in Chromium on a Raspberry Pi 4 with a 10.1" touchscreen by the door.

The Pi has two jobs: it **displays** the app, and it runs a small **home agent** that is the only thing that talks to local devices (Yeelight now, Zigbee later). The agent connects *out* to the backend, so the backend can live anywhere and nothing at home is exposed to the internet.

**Success test:** before leaving, the user reads the weather, tasks and next calendar events on the door screen and switches the lights off from it, without opening the phone.

### Changes from the source plan
| Source plan | This PRD | Why |
| --- | --- | --- |
| Backend on a used PC at home (up to 500 zł) | Backend on a small EU VPS (default) | Reachable from anywhere over HTTPS, no VPN, no server hardware to buy |
| Backend talks to Yeelight over the LAN | Pi home agent talks to Yeelight; backend talks to the agent | Backend no longer needs to be on the home network |
| Network is the security boundary | Login for the phone, device token for the display, token for the agent | Backend is reachable from the internet |
| VPN for use away from home | Not needed | Phone uses HTTPS directly |
| K3s as a later option | Dropped from scope | One container on a VPS gains nothing from it |

## 2. Users and context

- **Primary user:** Kuba, single household, single user. No multi-user accounts in the MVP.
- **Devices:** the phone (iPhone, Safari, PWA on Home Screen) and the door screen (Pi 4, Waveshare 1280×800 touch, Chromium kiosk).
- **Network:** the phone works from anywhere over HTTPS. The Pi sits on home Wi-Fi and needs outbound internet only; no inbound ports.
- **Backend location:** a VPS by default. The same container also runs on a laptop or a home server because it only depends on its public URL and the agent's outbound connection.

## 3. Goals and non-goals

**Goals**
- Pass the success test above.
- Work the same whether the backend is on a VPS, a laptop or a home server.
- Keep the screens working when an outside service is slow or down (cached data plus a "reconnecting" notice).
- Keep every secret (iCloud app-specific password, tokens) on the backend or the agent only.
- Put devices behind adapters, so Zigbee can be added without changing the app or the backend API.

**Non-goals (MVP)**
Zigbee devices, motion sensor / screen wake and dim, transit and commute, household notes board, native iPhone app and push notifications, Apple Reminders, calendar editing, multiple user accounts, direct local control of lights from the kiosk when the internet is down (parked, see section 11).

## 4. Functional requirements

IDs are used in the task list and tests.

### 4.1 Weather (W)
| ID | Requirement |
| --- | --- |
| W1 | Show current conditions (temperature, condition icon/text, wind, precipitation) and today's forecast (min/max, hourly or part-of-day outlook). |
| W2 | The backend fetches from Open-Meteo and caches the result; clients never call Open-Meteo. Refresh about every 15 min, well within the fair-use limit of ~10,000 calls/day. |
| W3 | Clients update without a manual reload. |
| W4 | Open-Meteo credit is visible in the UI (about/footer). |
| W5 | Latitude, longitude and timezone are **never hard-coded**. The weather service reads them from the saved location setting (S1-S3) on every refresh. |
| W6 | If no location is set yet, the weather card shows a "Set your location" prompt (on the phone it links to Settings) and no Open-Meteo forecast call is made. |
| W7 | Changing the location triggers an immediate weather refresh. |

### 4.2 Tasks (T)
| ID | Requirement |
| --- | --- |
| T1 | Create, complete, uncomplete and delete tasks from the phone. |
| T2 | Tasks are stored in the backend database, not in the calendar. |
| T3 | The door screen shows today's task list and lets the user tick a task off. **Assumption:** completing from the screen is allowed; creating tasks stays phone-only because the wall screen has no keyboard. |
| T4 | A change on one client appears on all others within about 2 s (live updates). |
| T5 | **Assumption:** "today's list" means tasks with a due date of today or no date, plus overdue open tasks. Task fields: title, optional due date, done flag. |

### 4.3 Calendar (C)
| ID | Requirement |
| --- | --- |
| C1 | Show today's Apple Calendar events, read-only, fetched via CalDAV from iCloud. |
| C2 | Highlight the next upcoming event; past events are visually muted. |
| C3 | The backend polls CalDAV (about every 5-10 min) and caches; credentials (app-specific password) live only in backend config. |
| C4 | All-day events appear in a separate row above timed events. **Assumption.** |
| C5 | **Assumption:** include all of the user's calendars by default, with an optional include-list in config. |

### 4.4 Lights (L)
| ID | Requirement |
| --- | --- |
| L1 | Each Yeelight turns on and off from the phone and from the door screen. |
| L2 | Only the Pi home agent talks to the Yeelight LAN API. Clients call the backend; the backend relays to the agent. |
| L3 | Light state is shown on every screen and updates live when changed elsewhere (including via the physical button or the Yeelight app, on the agent's next poll). |
| L4 | Devices are implemented behind two interfaces: `DeviceAdapter` in the backend (the `RemoteAgentAdapter` is the MVP implementation) and `DeviceDriver` in the agent (Yeelight first, Zigbee later). Adding a protocol means adding a driver in the agent only. |
| L5 | ~~On/off only.~~ Superseded by section 4.12: devices have channels with brightness, colour temperature and colour where the device supports them. |
| L6 | Unreachable lights, or an offline agent, show an "unavailable" state, not an error screen. |
| L7 | A command that gets no result from the agent within ~5 s fails with a clear error and the toggle reverts. |

### 4.5 Home agent (G)
| ID | Requirement |
| --- | --- |
| G1 | A small program on the Pi, started at boot as a service, that owns all local device protocols. |
| G2 | The agent opens an **outbound** secure WebSocket to the backend and reconnects automatically with backoff. No inbound ports on the home network. |
| G3 | On connect the agent reports its devices and their current state; afterwards it reports changes and answers commands (power on/off). |
| G4 | The agent authenticates with its own long-lived token; the backend accepts only one registered agent in the MVP. |
| G5 | The agent **polls** Yeelight state about every 30-60 s and after each command, and reports changes. Subscribing to the bulbs' pushed property notifications is parked for after the MVP. |
| G6 | Agent configuration is minimal and lives in a local file on the Pi, not in the repo: backend URL and agent token. **No device IP addresses are configured**; devices are found by discovery (G9). |
| G7 | The agent is written in **Go** and ships as a single static binary cross-compiled for the Pi (arm64 or armv7, matching the Pi OS). It must stay well under 150 MB RAM so it fits alongside Chromium on the Pi 4 2 GB; Go should use a small fraction of that. |
| G8 | When the agent is offline, the backend marks all its devices unavailable and keeps the last known state. |
| G9 | **Discovery:** the Yeelight driver finds bulbs on the LAN with the Yeelight discovery protocol (SSDP-style multicast search to 239.255.255.250:1982, search target `wifi_bulb`) at startup and then periodically (about every 60 s), and listens for the bulbs' own advertisements. New bulbs appear in the app without any configuration. |
| G10 | **Changing IPs:** if a command or poll cannot reach a bulb at its last known address, the driver immediately re-runs discovery, updates the address and retries once before reporting the bulb unreachable. A bulb that stays undiscovered for several cycles is reported `reachable: false` but is not removed. |
| G11 | The Pi and the bulbs must be on the same network segment for multicast discovery to work (see risks). |
| G12 | Devices are identified by their stable hardware id (for Yeelight, the `id` in the discovery response), never by IP address. The IP is runtime-only state inside the driver. |

### 4.6 Wall mode (M)
| ID | Requirement |
| --- | --- |
| M1 | The Pi boots straight into Chromium kiosk showing the app full screen. |
| M2 | Wall layout: large text, large touch targets (comfortable finger size), no scrolling at 1280×800. |
| M3 | Wall mode is selected by URL (e.g. `/wall`) or flag so the same codebase serves phone and screen. |
| M4 | Always-visible clock and date. **Assumption.** |
| M5 | If the internet is down at boot, the kiosk shows a local "offline, retrying" page and loads the app as soon as the backend is reachable. |

### 4.7 Offline handling (O)
| ID | Requirement |
| --- | --- |
| O1 | If the backend or Wi-Fi drops, the client keeps showing the last known data and a "reconnecting…" notice. |
| O2 | The client reconnects automatically and refreshes data when the connection returns. |
| O3 | If an upstream service (Open-Meteo, iCloud) fails, the backend serves stale cached data flagged with its age; one failing source never blanks the others. |
| O4 | While offline, light toggles on the screen are disabled and show "offline". |

### 4.8 Access and security (A)
| ID | Requirement |
| --- | --- |
| A1 | The app is served over HTTPS only, with an automatically renewed certificate. |
| A2 | The phone logs in once (single user) and stays signed in with a long-lived session. **Assumption:** password login with a hashed password and rate limiting; passkeys later. |
| A3 | The door screen is **paired once** and then holds a long-lived display token with limited scope: read everything, tick tasks, toggle lights; no creating or deleting tasks, no settings. |
| A3a | **Pairing starts on the display** (the Pi has no keyboard or camera). The unpaired screen shows a pairing page with a short code (for example `K7QM-4RTD`) and a QR code that encodes the approval link. The user then chooses either way: **scan** the QR with the phone, which opens the approval page in the already signed-in app, or **type** the code into Settings → Add display. Both end at the same Approve button. |
| A3b | Pairing codes expire after about 10 minutes, work once, are rate-limited and are compared in constant time. The display holds a separate secret `deviceCode` that only it knows, so someone who sees the QR or the code on the screen cannot collect the token. |
| A4 | The agent uses its own token, valid only for the agent WebSocket (G4). |
| A5 | Tokens can be revoked from the backend (config or a small admin command). |
| A6 | All API and SSE endpoints require one of the three credentials; `/api/health` exposes only an up/down status publicly. |
| A7 | Secrets are never exposed to clients and never committed to the repo. |

### 4.9 Settings (S)
| ID | Requirement |
| --- | --- |
| S1 | The user sets the home location in the app (phone, Settings screen) by searching a place name. The backend proxies the Open-Meteo Geocoding API and returns candidates (name, region, country, latitude, longitude, IANA timezone); the user picks one. |
| S2 | The chosen location (`name`, `latitude`, `longitude`, `timezone`) is saved in the backend database, so it survives restarts and applies to every client. Only the user credential can change it; the display can read it. |
| S3 | The saved timezone is the single timezone for the whole system: it defines "today" for tasks (T5), calendar events (C1, C4) and the midnight rollover. |
| S4 | Until a location is saved, the system works with a neutral fallback: weather is off (W6) and "today" uses the server's timezone. The UI nags gently on the phone until a location is set. |
| S5 | **Optional:** a "Use my current location" button on the phone using the browser's geolocation (works because the app is served over HTTPS). It saves latitude/longitude and takes the timezone and a place label from the forecast response or from a user-entered name. |
| S6 | Settings changes are pushed to all clients with a `settings.changed` event. |

### 4.10 Should-have (post-core, if time allows)
- Smart weather advice ("bring a jacket, rain starts at 15:00").
- "Leaving the house" checklist view on the screen.

### 4.11 Home: rooms and quick access (R)
| ID | Requirement |
| --- | --- |
| R1 | The backend **stores the devices it has seen** (stable id, type, the name the device reports, first and last seen). The agent stays the source of truth for which devices exist and for live state; the database holds what is needed to organise them. Live state (power, reachable) is **not** stored. |
| R2 | A device the agent reports for the first time is added automatically and starts **unassigned**. The Home tab lists unassigned devices first so new bulbs are easy to find. |
| R3 | The user creates, renames, reorders and deletes **rooms**. There is no separate "home" entity (single household). Deleting a room does not delete its devices; they become unassigned. |
| R4 | A device is in **at most one room**. The user assigns it from the Home tab. |
| R5 | The user can give a device a **name of their own**. It is stored in the app only; nothing is written to the bulb. Clearing it goes back to the name the device reports. |
| R6 | A stored device that the agent no longer reports (unplugged, removed) **stays in its room**, shown as unavailable with when it was last seen. The user can **forget** it. If a forgotten device is discovered again it returns as a new, unassigned device. |
| R7 | **Quick access:** the user pins up to **4 items**, in a chosen order. An item is a light (a device channel: a single-channel device is its own channel; a light bar's main and background lights are two) or a **scene** (SC8). The dashboard shows them as large tiles (name, room, state, brightness when on); tapping a light tile toggles that channel, tapping a scene tile applies the scene; a long press on a light opens the device panel (D5). With nothing pinned the phone shows a hint explaining how to pin; the wall screen shows nothing for the panel. |
| R8 | **Navigation (phone):** a bottom tab bar with **Dashboard** and **Home**. Settings stays reachable from the header. The Home tab lists unassigned devices, then each room with its devices as switch rows, and has an edit mode for rooms, assignment, names and pins. |
| R9 | **Wall screen:** the Quick access panel replaces the Lights card and has an **All devices** button (also shown when nothing is pinned). It opens a full-screen list of every device grouped by room, with large switches, for **switching only**: no editing of rooms, names or pins. The view returns to the dashboard after 30 s without a touch, or at once with the Back button. Unassigned devices appear under "Other", and rooms without devices are hidden. |
| R10 | Structure changes (rooms, assignments, names, pins, new devices) are pushed to every client with a `home.changed` event; power changes keep using `devices.changed`. |
| R11 | When the agent is offline the stored structure is still shown (it comes from the database) with every device unavailable, including right after a backend restart before the agent has reconnected. |
| R12 | Only the user credential can change rooms, assignments, names and pins; the display can read them and toggle devices (A3). |

Out of scope for R: a "turn off the room" button. It is a natural next step and needs no change to this model.

### 4.12 Device channels and controls (D)
| ID | Requirement |
| --- | --- |
| D1 | A device has one or more **channels**. A Yeelight light bar has `main` and `background`; a plain bulb or a plug has only `main`. Channels are what is switched, adjusted and pinned. |
| D2 | Each channel declares its **capabilities**: power (always), brightness (1-100), colour temperature (with a Kelvin range) and colour (hue 0-359, saturation 0-100). The UI shows only the controls a channel supports. For the Yeelight `lamp15` bar (the user's): main has brightness and colour temperature; background has brightness, colour temperature and colour. |
| D3 | The agent derives capabilities from what the device reports (for Yeelight the `support` list and the model) and sends them with the device list. The backend stores them with the device so channels, controls and pinned tiles still make sense while the agent or the bulb is offline. |
| D4 | Each channel has a **state**: on, brightness, colour temperature, hue, saturation and the active mode (white or colour). The agent reports it with the device state and reads it back after every command, so the app shows what the bulb actually did. A device counts as on when any channel is on. |
| D5 | From the **Home tab** (phone) and the **All devices** view (wall), tapping a device opens a **device panel**: per channel a power switch, a brightness slider, a warm-to-cool temperature slider and, for colour channels, hue and saturation sliders with preset swatches. A long press on a Quick access tile opens the same panel. Controls are disabled while the channel is off or the device is unavailable. |
| D6 | **Sliders send when released**, not while dragging: Yeelight accepts about 60 commands a minute per bulb. The slider shows the dragged value until the command finishes. |
| D7 | The switch on a device row (Home tab, All devices) is a **device-wide toggle**: if any channel is on it turns all channels off, otherwise all on. A Quick access tile toggles only its own channel. |
| D8 | The wall may use every control the panel offers (set brightness, temperature, colour, power); organising stays phone-only (R9, R12). |
| D9 | The backend validates every change against the channel's capabilities and ranges (HTTP 400 for an unsupported or out-of-range value, 404 for an unknown device or channel) and the agent refuses what the device cannot do. |
| D10 | Compatibility: an agent that reports no channels is treated as a device with a single power-only `main` channel, and the protocol additions are optional, so agent and backend can still be upgraded separately. |
| D11 | Out of scope: per-segment colours (the `lamp15` supports them), colour flows, scenes, music sync, night-light modes, remembering a colour per device. |

### 4.13 Scenes (SC)
| ID | Requirement |
| --- | --- |
| SC1 | A **scene** is a named list of actions across any devices. An action targets one light (a device channel) and carries the settings to apply: power, brightness, colour temperature, colour. Example: "Movie": light bar main off, light bar background on at 20% blue, desk lamp off. |
| SC2 | Each setting of an action is **individually included or left alone** ("set the background to blue but keep its brightness"). An action must include at least one setting. Settings are validated against the channel's capabilities and ranges when the scene is saved (HTTP 400). |
| SC3 | **Setting anything implies on:** an action that sets brightness, white or colour and does not say otherwise turns the light on, because bulbs ignore adjustments while off. An action with `on: false` turns the light off and ignores the other settings. |
| SC4 | **Applying** a scene sends every action to the agent as a normal channel command. Actions for different devices run in parallel; the actions of one device run in order. There is no rollback. The answer says how many actions were applied and lists the ones that failed (unavailable, refused, timed out); one unavailable light does not stop the rest. If the home agent is offline the whole call fails with 503. |
| SC5 | Scenes are created two ways: **capture** (set the lights in the device panel, choose "Save as scene", tick which lights to include, and their current settings are recorded: on or off, brightness, and the white or colour they currently show) and an **editor** with one block per light, a checkbox per setting and the panel's sliders. Capture opens the editor so the result can be adjusted. |
| SC6 | A scene can be renamed, edited, reordered and deleted. Names are unique ignoring case. Forgetting a device removes its actions from scenes (a scene may end up empty, which the editor shows). An action whose device is missing or whose channel no longer supports a setting fails when applied, not silently. |
| SC7 | A built-in scene **"All off"** exists from the start. It stores no actions: when applied it switches off every channel of every device that is currently present and reachable, so lights added later are included. It can be renamed, reordered and pinned but not edited or deleted. |
| SC8 | Scenes appear in a **Scenes** section at the top of the Home tab and of the wall's All devices view (a tap applies the scene), and can be **pinned into Quick access** next to lights. After a tap the tile or button shows that the scene was applied, or how many lights failed. |
| SC9 | Only the user credential can create, edit, reorder, delete or pin scenes; the display can read and apply them (A3, R12). |
| SC10 | Structure changes (create, edit, delete, reorder, pin) are pushed with `home.changed`; applying a scene produces the usual `devices.changed` as the lights change. |
| SC11 | Out of scope: triggers (at sunset, when the door opens, on a schedule), showing which scene is currently active, scene transitions and per-action delays. |

## 5. Non-functional requirements

- **Resilience:** backend starts on boot (Docker restart policy); screens and agent recover without manual action.
- **Performance:** first paint on the Pi under ~3 s; updates visible in ~2 s; a light toggle completes in under ~1.5 s typical (phone to cloud to Pi to bulb), 5 s hard limit (L7).
- **Security:** HTTPS only, three scoped credentials (A2-A4), no inbound ports at home, backend host kept patched, only needed ports open on the VPS (443, SSH key-only).
- **Privacy:** calendar events and tasks are stored/cached on the VPS. Use an EU region and an encrypted disk if the provider offers it. The iCloud credential is an app-specific password, revocable at any time.
- **Data safety:** task database backed up off the VPS (scheduled copy to another location) in addition to provider snapshots.
- **Footprint:** the Pi runs a browser and the small agent; all other logic lives in the backend.
- **Browser support:** Safari on iOS (PWA) and Chromium on Pi 4 (2 GB RAM, so keep the UI light, with no heavy animations).
- **Portability:** the backend must run unchanged on any Linux host with Docker and a public HTTPS URL.

## 6. Architecture

```
Phone (PWA) ──HTTPS──┐
                      ├─► Backend (Spring Boot, VPS) ─┬─► Open-Meteo
Door (Pi browser) ─HTTPS┘      DB + cache + SSE         ├─► iCloud CalDAV
                                    ▲                   └─ (devices via agent)
                                    │ outbound WSS
                          Pi home agent ──► Yeelight (LAN)
                                        ─·► Zigbee (later)
```

Details, diagrams and flows are in `ARCHITECTURE.md`.

- **Backend:** Spring Boot (Java), containerised, Docker Compose on the VPS behind a reverse proxy that terminates HTTPS (**Assumption:** Caddy, automatic Let's Encrypt; needs a domain name).
- **Frontend:** React + TypeScript, Vite, PWA; built assets are served by the backend (one URL for phone and screen).
- **Home agent:** small Go service on the Pi (single binary under systemd), with a `DeviceDriver` interface and a Yeelight driver that discovers bulbs on the LAN.
- **Live updates:** Server-Sent Events from backend to clients; commands go through REST. Agent link uses a WebSocket (bidirectional).
- **Storage:** **Assumption:** SQLite or H2-file for tasks (single user, trivial backup). Weather and calendar are cached in memory with timestamps.
- **Scheduling:** Spring `@Scheduled` jobs refresh weather and calendar; the agent polls device state.

### Proposed API (draft)

| Method | Path | Credential | Purpose |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | none | Phone login, sets session cookie |
| POST | `/api/auth/pair/start` | none (rate-limited) | Display asks to pair; gets `deviceCode` (secret), `userCode`, `approveUrl` (for the QR), `expiresIn` |
| GET | `/api/auth/pair/poll?deviceCode=` | `deviceCode` | Display polls until approved, then receives its display token once |
| POST | `/api/auth/pair/approve` | user | Phone approves a `userCode` (from the QR link or typed in Settings) |
| GET | `/api/weather` | user, display | Current + today's forecast, with `fetchedAt` |
| GET | `/api/tasks` | user, display | Today's tasks |
| POST | `/api/tasks` | user | Create `{title, dueDate?}` |
| PATCH | `/api/tasks/{id}` | user (all fields), display (`done` only) | Update |
| DELETE | `/api/tasks/{id}` | user | Delete |
| GET | `/api/calendar/today` | user, display | Today's events, next event flagged, with `fetchedAt` |
| GET | `/api/devices` | user, display | Stored devices with live state: effective `name`, `reportedName`, `roomId`, `power` (any channel on), `reachable`, `lastSeenAt`, and `channels[]` (each with `id`, `label`, `capabilities`, its live values `on`, `brightness`, `colorTemp`, `hue`, `saturation`, `colorMode`, and a `quickAccess` slot or null); plus `agentOnline` |
| POST | `/api/devices/{id}/power` | user, display | `{on: boolean}`; switches every channel of the device |
| POST | `/api/devices/{id}/channels/{channel}` | user, display | Partial change `{on?, brightness?, colorTemp?, hue?, saturation?}`, validated against the channel's capabilities; `hue` and `saturation` go together. Returns the updated device |
| PATCH | `/api/devices/{id}` | user | `{name?, roomId?, clearRoom?}`; a blank name clears the override, `clearRoom: true` unassigns |
| DELETE | `/api/devices/{id}` | user | Forget a device |
| GET | `/api/rooms` | user, display | Rooms in order |
| POST | `/api/rooms` | user | Create `{name}` |
| PATCH | `/api/rooms/{id}` | user | Rename `{name}` |
| DELETE | `/api/rooms/{id}` | user | Delete; its devices become unassigned |
| PUT | `/api/rooms/order` | user | `{roomIds: []}` new order |
| PUT | `/api/quick-access` | user | `{items: [{deviceId, channel} or {sceneId}]}`, at most 4, in order |
| GET | `/api/scenes` | user, display | Scenes in order, each with `id`, `name`, `kind` (`custom` or `all_off`), `quickAccess` slot or null and `actions[]` (`deviceId`, `deviceName`, `channel`, `channelLabel`, `capabilities`, `settings`) |
| POST | `/api/scenes` | user | Create `{name, actions: [{deviceId, channel, settings}]}` |
| POST | `/api/scenes/capture` | user | Create `{name, channels: [{deviceId, channel}]}` from the lights' current settings |
| PUT | `/api/scenes/{id}` | user | Replace name and actions (an `all_off` scene accepts only a name) |
| DELETE | `/api/scenes/{id}` | user | Delete (not the built-in scene) |
| PUT | `/api/scenes/order` | user | `{sceneIds: []}` new order, every scene once |
| POST | `/api/scenes/{id}/apply` | user, display | Apply; returns `{applied, failed: [{deviceId, deviceName, channel, reason}]}` |
| GET | `/api/settings` | user, display | Saved settings, including the location (`name`, `latitude`, `longitude`, `timezone`) or `null` |
| PUT | `/api/settings/location` | user | Save the chosen location; triggers a weather refresh |
| GET | `/api/geocode?q=` | user | Place search, proxied to the Open-Meteo Geocoding API |
| GET | `/api/stream` | user, display | SSE: `tasks`, `devices`, `home`, `weather`, `calendar`, `settings` events |
| GET | `/api/health` | none | Up/down only |
| WS | `/agent/ws` | agent token | Agent link (see below) |

### Device layers (sketch)

Backend:
```java
interface DeviceAdapter {
    String type();                       // "agent" in the MVP
    List<Device> devices();              // as last reported by the agent
    DeviceState getState(String id);
    void setPower(String id, boolean on);                    // all channels; sends a command to the agent, waits up to ~5 s
    void setChannel(String id, String channel, Change change); // one channel: on, brightness, colour temperature, colour
}
```

Agent (Go):
```go
type DeviceDriver interface {
    Type() string                                          // "yeelight", later "zigbee"
    Discover(ctx context.Context) ([]Device, error)        // find devices; keeps id -> address map internally
    State(ctx context.Context, id string) (DeviceState, error)
    SetPower(ctx context.Context, id string, on bool) error                // all channels
    Set(ctx context.Context, id, channel string, change Change) error      // one channel
}
// Device carries its Channels with their capabilities; State carries per-channel state.
```

### Agent protocol (draft, JSON over WSS)

| Direction | Message | Fields |
| --- | --- | --- |
| agent → backend | `hello` | `agentId`, `version`, `devices[]`, `states[]` |
| agent → backend | `devices` | full list of known devices (`deviceId`, `name`, `type`, `channels[]` with `id`, `label` and `capabilities`); sent when discovery finds or loses a device |
| agent → backend | `state` | `deviceId`, `power` (any channel on), `reachable`, `updatedAt`, `channels[]` (`id`, `on`, `brightness?`, `colorTemp?`, `hue?`, `saturation?`, `colorMode?`) |
| backend → agent | `command` | `id`, `deviceId`, `action`: `setPower` with `args: {on}` for the whole device, or `set` with `channel` and `args: {on?, brightness?, colorTemp?, hue?, saturation?}` |
| agent → backend | `result` | `id` (of the command), `ok`, `error?`, `state?` |
| both | `ping` / `pong` | heartbeat every ~20 s; no traffic for ~60 s means the link is dead |

## 7. Hardware and budget (reference)

About €210-225 for Pi 4 2 GB, Waveshare 10.1" touchscreen, PSUs, SD card and mount (prices from the source, 24 Oct 2026).

**Server side (replaces the used-PC budget):** a small EU VPS. **Estimate, not a quote:** about €4-6 per month for 1-2 GB RAM, plus a domain at roughly €10-15 per year. That is about €60-85 in the first year, so the first-year total is roughly €270-310, with no home server to buy, power or maintain. Check current provider prices before choosing.

Later: a PIR motion sensor (~3-5) plus jumper wires (~2), and a Zigbee coordinator (not priced; plugs into a Pi USB port).

## 8. Milestones and acceptance gates

| # | Phase | Gate | Covers |
| --- | --- | --- | --- |
| 1 | **Hardware bring-up.** Pi OS, touchscreen, Chromium kiosk on a test page | Boots to the page, touch works | M1 |
| 2 | **VPS and delivery.** Provision VPS, domain, Docker Compose, Caddy HTTPS, deploy a hello-world container | Phone opens `https://<domain>`; container survives a reboot | A1, resilience |
| 3 | **Backend and web skeleton.** Auth (login, pairing), settings and location search, weather, tasks, SSE, phone layout | Phone and Pi show the same data from one URL; Pi paired once; changing the location on the phone changes the weather on the Pi | W*, T*, S*, A*, O3 |
| 4 | **Calendar.** Read-only CalDAV | Today's events appear on both devices | C* |
| 5 | **Home agent and lights.** Go agent on the Pi, `RemoteAgentAdapter`, Yeelight driver with discovery | New bulbs appear without configuration; a bulb whose IP changed is still controlled; switch the light bar from the phone (away from home Wi-Fi) and from the screen | G*, L* |
| 6 | **Wall mode and mounting.** Wall layout, reconnecting and offline states, mount by the door | Success test passes | M*, O1-O2, O4 |
| 7 | **Rooms and quick access.** Stored devices, rooms, assignment, names, up to 4 pinned devices, Dashboard and Home tabs | A new bulb appears as unassigned; after assigning it to a room and pinning it, the tile on the phone and the wall screen toggles it; the structure is still visible with the agent offline | R* |
| 8 | **Scenes.** Stored scenes with per-setting actions across devices, capture and editor, apply, built-in All off, pinnable in Quick access | Capturing the current lights creates a scene; changing the lights and applying it restores them; "All off" switches every present light off; one unreachable light is reported without stopping the rest | SC* |

Phases 1 and 2 are mostly setup work by the user; I can supply scripts and checklists. Phases 3-6 are the code I build. Development can start in phase 3 on a laptop before the VPS exists, because the backend is location-independent.

## 9. Open questions and assumptions to confirm

1. **VPS provider, region and size** (EU, 1-2 GB RAM) and **domain name**. Nothing decided yet.
2. **Reverse proxy:** assumed Caddy for automatic HTTPS.
3. **Phone login method** (A2): assumed password plus long-lived session; passkeys later.
4. **Display pairing flow** (A3a): decided: both ways (scan a QR or type a code), user's choice, display-initiated. Open detail: how the kiosk shows the pairing page before it has a token.
5. **Agent language** (G7): decided: Go.
6. **Task semantics:** the definition of "today's list" and screen-side completion (T3, T5).
7. **Task storage:** decided: SQLite (single file, `sqlite3 .backup` for backups).
8. **Calendar scope:** which calendars to include (C5); anything beyond "today".
9. **Yeelight discovery:** decided: discovery (G9, G10), because the bulbs' IPs change. Open: whether the Pi's network allows multicast between the Pi and the bulbs (to be checked in phase 5).
10. **Location and timezone:** decided: not hard-coded, set in the app (S1-S3) and stored in the database. Open: whether to build the optional "use my current location" button (S5) in the MVP.
11. **Language:** English UI (assumed).
12. **Repo layout:** assumed monorepo with `backend/` (Spring Boot), `frontend/` (Vite), `agent/` (Go module), `deploy/` (Compose, Caddy, Pi setup scripts).
13. **Off-site backup target** for the task database.

## 10. Risks

| Risk | Mitigation |
| --- | --- |
| Backend is internet-reachable | HTTPS only, scoped tokens, rate-limited login, no extra open ports, patched host |
| Calendar and tasks stored on a third-party host | EU region, encrypted disk where possible, revocable app-specific password |
| Pi is both display and device gateway: if it is down, no light control from the phone | Yeelight's own app still works; agent runs as a service and restarts; SD card wear reduced with a good card and few writes |
| Door screen depends on the internet | Cached snapshot plus "reconnecting" notice; local offline page at boot (M5); local light control parked for later |
| Pi 4 2 GB memory with Chromium and the agent | Small agent (G7), light UI, no heavy animation |
| Yeelight LAN API accepts commands from any device on the Wi-Fi | Only the agent sends commands; guests on a separate Wi-Fi |
| Multicast discovery fails (router or access point blocks multicast, Pi and bulbs on different segments or a guest network) | Put the Pi and the bulbs on the same segment; if it still fails, add a fallback in the driver that scans the subnet for the Yeelight control port |
| Yeelight limits concurrent connections and command rate per bulb | The driver serializes commands per bulb, spaces them at least 250 ms apart and uses a short-lived connection per command, so no connection slot is held |
| VPS is a single point of failure | Provider snapshots, off-site backup, container restarts on boot; accept short outages |
| Zigbee needs extra hardware | Stays behind `DeviceDriver`; coordinator plugs into the Pi |
| Secrets leaking | Backend and agent config only; `.env` and agent config not committed |
| iOS PWA quirks (SSE in background, install) | Reconnect on `visibilitychange`; test on the real phone early; HTTPS is now available |

## 11. Out of scope / after the MVP

automations and scene triggers, Zigbee lights (Zigbee2MQTT on the Pi behind a `ZigbeeDriver`), motion sensor wake/dim, commute and transit info, household notes board, auto day/night mode, native iPhone app, passkey login, **local-first lights**: let the kiosk talk to the agent directly on the LAN so the screen can still switch lights when the internet is down.

## 12. Working notes for building

- Build order follows section 8; each phase ends with a visible, testable result.
- I will keep this file and `ARCHITECTURE.md` updated as decisions change; `smart-door-display-mvp.md` stays as the original plan.
- First coding step (phase 3): scaffold `backend/` and `frontend/`, add login and display pairing, the settings/location endpoints, `/api/weather` and the tasks CRUD, add SSE, and show both in a phone layout, then the `/wall` layout.
- In phase 3, stub the device layer with a fake `DeviceAdapter` so the UI can be built before the agent exists.

## 13. Implementation status (2026-10-24)

| Area | State |
| --- | --- |
| Backend: auth, pairing, settings and location search, weather, tasks, SSE, devices, agent WebSocket, calendar, rooms, stored devices and quick access | Built and tested (`mvn test`) |
| Calendar via iCloud CalDAV | Built; tested against a fake CalDAV server and iCalendar samples, **not yet against a real iCloud account** |
| Scenes (section 4.13): stored scenes with per-setting actions across devices, capture and editor, apply (parallel across devices, partial failures reported), built-in All off, pinnable in Quick access, shown on the phone and the wall | Built and tested (backend integration tests, a scene applied through a scripted agent over the real WebSocket, frontend model tests) and checked in a browser against fake lights; **not yet applied to the real bar** |
| Device channels and controls (section 4.12): the Yeelight driver reads capabilities from the bulb, reports per-channel state and takes `set` commands; the backend stores capabilities, validates changes and serves channels; the web app has the device panel and per-channel quick access tiles | Built and tested against a fake bulb, a scripted agent and a browser; **not yet run against the real `lamp15` bar** (colour temperature range and the effect of setting a value while a light is off are assumptions) |
| Web app: phone layout with Dashboard and Home tabs, wall layout, pairing, settings, reconnecting notice, rooms and quick access | Built; checked in a real browser |
| Calendar card: all-day events as full-width rows, finished events kept and crossed out | Built; checked in a real browser |
| Go home agent with Yeelight discovery and control | Built; unit-tested with a fake bulb; connected to the real backend; **not yet run against real bulbs or on a Pi** |
| Docker image, Compose, Caddy, Pi service and kiosk files | Built; the image runs; **not yet deployed to a VPS or a Pi** |

Decisions made during implementation:
- Spring Boot 4.1.2 on Java 25; SQLite with Flyway migrations; the web app is built into the backend image.
- The device list in `fake` mode (the dev default) shows two simulated lights; production uses `agent` mode.
- Completed tasks stay on today's list until the end of the day; `GET /api/tasks?scope=all` includes future tasks.
- The pairing endpoint allows 5 starts per 15 minutes per client address.
- The client treats 50 seconds without any server event (pings come every ~20 s) as a lost connection.
- Yeelight bulbs get a stable id `yeelight-<hardware id>`; the driver marks a bulb unreachable after 3 missed discovery rounds.
- Rooms and quick access (section 4.11) are built as specified. Cuts and choices: the wall screen has no tab bar (the Quick access panel opens the toggle-only All devices view instead); the quick-access panel sits directly below the weather card on the phone dashboard; the phone has no Lights card any more, since the Home tab lists every device.
- Calendar rows: all-day events come first and use the same rows as timed ones with "All day" in the time column; finished events stay in order, faded and crossed out. On the wall, when rows run out, the oldest finished events are dropped first ("N done").
- Device channels (section 4.12): the Yeelight colour temperature range is taken from the kind of channel (1700-6500 K when it can show colour, 2700-6500 K for white-only), not from the bulb, because the bulb does not announce it; check on the real bar. The agent sends changes in the order power, brightness, colour temperature or colour and reads the state back after every command.
- The panel's sliders are disabled while a channel is off, so no change is sent to a light that is off; the Yeelight behaviour for that case is untested.
- Turning the lamp15's background light on needs `bg_set_scene` (its `bg_set_power on` is ignored); see `ARCHITECTURE.md` section 12. Checked against the real bar.
- Scenes (section 4.13): the settings of an action are individually included; white and colour exclude each other in the editor because a light shows one or the other. An action that sets brightness, white or colour without power turns the light on. The Scenes section is at the top of the Home tab (apply; manage in edit mode) and of the wall's All devices view; scenes can also be pinned into Quick access.
- Applying a scene sends ordinary `set` commands, so a background light is switched on with the `bg_set_scene` workaround for the lamp15 and an action that turns it on takes two commands to the bar (a read and the scene).
- A scene (or the panel) can leave the lamp15 with the main light off and the background on: the bar's background-only mode. The agent reads `main_power` to know the main light's real state; see `ARCHITECTURE.md` section 12. Checked against the real bar.
