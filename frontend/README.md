# Frontend

The frontend is one React single-page app that serves **two different screens** from the same code:

- the **phone app** (installable PWA): dashboard, Home tab (rooms, devices, scenes), settings;
- the **wall display**: a fixed, no-scroll layout for the 10.1" touchscreen of the Raspberry Pi,
  running in a Chromium kiosk.

It contains no business rules. The backend owns the data and the rules; this app shows the data,
sends the user's commands, and refreshes itself when the backend says something changed.

For the system-wide picture see [`ARCHITECTURE.md`](../ARCHITECTURE.md); the HTTP API it uses is
documented in [`backend/README.md`](../backend/README.md#5-rest-api).

## Contents

1. [Quick start](#1-quick-start)
2. [Stack and scripts](#2-stack-and-scripts)
3. [Screens and routes](#3-screens-and-routes)
4. [Code layout](#4-code-layout)
5. [Data flow](#5-data-flow)
6. [Feature guide](#6-feature-guide)
7. [The wall display](#7-the-wall-display)
8. [Design system](#8-design-system)
9. [PWA and offline behaviour](#9-pwa-and-offline-behaviour)
10. [Testing](#10-testing)
11. [Build and deployment](#11-build-and-deployment)
12. [Extending the app](#12-extending-the-app)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Quick start

You need Node 24 (the Docker build uses `node:24-alpine`) and a running backend.

```sh
# terminal 1: backend with development defaults (password "dev", fake lights, fake calendar)
cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

# terminal 2: the web app
cd frontend
npm install
npm run dev          # http://localhost:5173
```

The dev server forwards `/api` (including the voice WebSocket) to `http://localhost:8080`; point it elsewhere with
`BACKEND_URL=http://host:port npm run dev`. Sign in with the dev password `dev`.

To see the wall layout while developing, open `http://localhost:5173/wall` (after signing in as the user
the wall view is also reachable; the paired-display flow is shown in section 3).

> Cookies are shared between ports of the same host. If you also run the full local Docker setup on
> `localhost:8080`, sign-ins on `localhost:5173` can interfere with it. Use `127.0.0.1:5173` for one
> and `localhost` for the other.

## 2. Stack and scripts

| Piece | Choice |
|---|---|
| UI | React 19, TypeScript (strict), function components and hooks |
| Build | Vite, `vite-plugin-pwa` (Workbox) |
| Routing | `react-router-dom` 7 |
| Server state | TanStack Query 5 (the only client-side cache; no Redux or similar) |
| Styling | One hand-written stylesheet `src/styles.css` with CSS custom properties; no CSS framework |
| QR codes | `qrcode.react` (pairing screen) |
| Tests | Vitest, jsdom, Testing Library |

| Script | Does |
|---|---|
| `npm run dev` | Dev server with hot reload and the `/api` proxy |
| `npm run build` | `tsc --noEmit` (type check) then `vite build` into `dist/` |
| `npm run typecheck` | Type check only |
| `npm test` | Vitest, one run |
| `npm run preview` | Serve the built `dist/` locally |

`tsconfig.json` is strict and also fails on unused locals and parameters, so `npm run build` is a real
quality gate.

## 3. Screens and routes

`src/App.tsx` is the whole routing table:

| Route | Screen | Who | Notes |
|---|---|---|---|
| `/login` | Password sign-in | anyone | Redirects back to where the user came from. |
| `/pair` | Pair this screen | an unpaired wall display | Shows a QR code and a code; polls until approved. |
| `/wall` | Wall display | a paired display (or a signed-in user) | Redirects to `/pair` when nobody is signed in. |
| `/` | Dashboard | user | Weather, quick access, calendar, tasks. |
| `/home` | Home tab | user | Rooms, devices, scenes, with an Edit mode. |
| `/assistant` | Assistant | user | Talk or type to the assistant: lights, weather, calendar, tasks. |
| `/settings` | Settings | user | Location, phones and displays, home agent token, sign out. |
| `/approve` | Add display | user | Approve a pairing code (opened by the QR code). |
| anything else | | | Redirects to `/`. |

Two gates (`RequireAuth`) protect the routes: one for "any signed-in client" (the wall), one for "user
only" (everything else; a display that opens `/` is sent to `/wall`). Both wrap their content in
`ConnectionProvider` so live updates only run while signed in.

Phone navigation is a bottom tab bar with **Dashboard**, **Home** and **Assistant** (`TabBar.tsx`); Settings is the gear
in the page header.

### How a wall display gets signed in

1. The Pi opens `/wall` → not signed in → redirected to `/pair`.
2. `Pair` calls `POST /api/auth/pair/start`, shows the QR code (a link to `/approve?code=…`) and the code,
   and polls every 2 s. An expired code is replaced automatically; network errors are retried.
3. On the phone (signed in) the user scans the QR code, or opens **Settings → Add display** and types the
   code, and approves. The screen then receives a long-lived display cookie and navigates to `/wall`.

The display's cookie is HTTP-only and lasts five years; it can be revoked in **Settings → Phones and
displays**.

### Why `/` can redirect to `/wall`

A browser that holds a *display* cookie is treated as a display: the user-only routes send it to `/wall`.
If you paired a browser by accident, sign in at `/login`, which replaces the cookie.

## 4. Code layout

```
frontend/
├── index.html                   mobile/PWA meta tags, mount point
├── vite.config.ts               dev proxy, PWA manifest and service worker, Vitest setup
├── public/                      app icons and voice-worklet.js (the microphone's audio-thread code)
└── src/
    ├── main.tsx                 providers: StrictMode, QueryClient, Router
    ├── App.tsx                  routes and auth gates
    ├── api.ts                   every HTTP call, one typed function each; ApiError
    ├── types.ts                 TypeScript shapes of the API responses
    ├── connection.tsx           the live-update connection (SSE) and its status
    ├── hooks/
    │   ├── data.ts              TanStack Query hooks: reads and mutations
    │   ├── useVoice.ts          one voice conversation for a page
    │   ├── useClock.ts          current time, ticking each minute
    │   └── useIdle.ts           "nothing touched for N seconds"
    ├── homeModel.ts             pure logic: rooms, quick access, availability, scene results
    ├── sceneModel.ts            pure logic: editing scene actions
    ├── calendarRows.ts          pure logic: which calendar rows to show
    ├── format.ts                dates, times, "5 min ago"
    ├── color.ts                 hue/saturation to CSS, colour presets
    ├── voice/                   microphone, sentence detection, playback and the voice client (see below)
    ├── pages/                   one file per route
    ├── components/              cards, controls, sheets
    └── styles.css               the entire design system and all layouts
```

Pattern worth knowing: **logic lives in plain TypeScript modules** (`homeModel`, `sceneModel`,
`calendarRows`, `format`, `color`) with unit tests; components are thin and mostly wiring. When adding a
rule, put it in a model module and test it there.

Main components:

| Component | What it is |
|---|---|
| `WeatherCard`, `WeatherIcon` | Current weather, today's range, the next hours; a sky gradient that follows day and night; drawn icons. |
| `CalendarCard` | Today's events. All-day events first, as full-width rows labelled "All day". Finished events stay but are crossed out. |
| `TasksCard` | Today's tasks. `editable` (phone, user only) adds the add form and delete buttons; the wall can only tick. |
| `QuickAccessCard` | The pinned tiles (up to 4 lights or scenes). |
| `QuickAccessEditor` | Pins and their order, in Edit mode. |
| `RoomSection`, `DeviceRow`, `InlineEdit` | Devices by room; in Edit mode: assign room, rename, pin, forget. |
| `DevicePanel`, `ChannelControls`, `Slider` | The sheet with every control of one device. |
| `pages/Assistant.tsx`, `assistantModel.ts` | The chat screen and its pure helpers (bubbles, error wording, the 500 character limit). |
| `ScenesSection`, `SceneEditor`, `CaptureDialog` | Scene buttons, management list, the editor and "save current lights as a scene". |
| `WallDevices` | The wall's "All devices" view (switching only). |
| `ConnectionNotice`, `Stale` | "Reconnecting…" banner; "Updated 12 min ago" notes on cards. |
| `Icons` | Small line icons. |

## 5. Data flow

```
 component ──useQuery──▶ TanStack Query cache ◀──fetch── api.ts ──▶ backend REST
                                 ▲
        SSE event "devices.changed"                 ┌──────────────┐
 ConnectionProvider ─────────────┴── invalidate ────│ /api/stream  │ (Server-Sent Events)
                                                    └──────────────┘
```

- **Reads** are `useQuery` hooks in `hooks/data.ts` with shared defaults: `staleTime` 5 min, a safety
  `refetchInterval` of 5 min, one retry, `networkMode: 'always'`. The cache keeps the **last good data**
  when a refetch fails, which is what the "reconnecting" screens rely on.
- **Writes** are `useMutation` hooks. After a write they invalidate the affected queries. Writes to rooms,
  names, scenes and quick access share `useHomeMutation`, which refetches rooms, devices and scenes.
- **Live updates:** `ConnectionProvider` opens one `EventSource('/api/stream')` per signed-in session.
  The server sends *signals* (`tasks.changed`, `devices.changed`, `home.changed`, `weather.changed`,
  `calendar.changed`, `settings.changed`); the provider maps each to the query keys to refetch
  (`EVENT_KEYS`). `home.changed` refetches rooms, devices **and** scenes.
- **Connection health:** the server sends `ping` every 20 s; if nothing arrives for 50 s the connection
  is declared lost, the status becomes `reconnecting`, and it retries with backoff (1, 2, 5, 10, 30 s).
  After every (re)connect *all* queries are refetched, so missed events do not matter. A lost
  connection also re-checks the session, so a revoked login shows up quickly.
- **iOS and sleep:** when the page becomes visible again, or the browser goes back online, the
  connection is re-established immediately.
- **Session:** `useSession` calls `GET /api/auth/me`; `401` means "not signed in" (a `null`), anything else
  is an error that keeps retrying every 5 s.
- **Errors:** `api.ts` turns non-2xx responses into `ApiError(status, message)` where the message comes
  from the backend's problem-details `detail`. Components show it as is.
- **Time zone:** all times are formatted in the saved location's timezone (`useZone`), so the phone, the
  wall and the server agree on what "today" is, whatever the device's own zone.

## 6. Feature guide

### Dashboard (`/`)

A single scrolling column: date heading, weather, **quick access**, calendar, tasks. Each card handles
its own loading, empty, and stale states.

### Quick access

Up to four tiles, each a **light channel** or a **scene**, pinned from the Home tab.

- Tap a light tile: toggles that channel. **Long-press (500 ms)** opens the device panel; the hold is
  detected on pointer events, and the click that follows a hold is suppressed so it does not also toggle.
- Tap a scene tile: applies the scene; the tile shows "Applying…", then "Applied" or "n failed" for a few
  seconds.
- Tiles are disabled and say "Unavailable" when the agent is offline, the light is missing or unreachable,
  or the app has lost its connection.
- With nothing pinned the phone explains how to pin; the wall shows nothing unless it has the
  "All devices" button.

### Home tab (`/home`)

Devices grouped by room, plus an **Unassigned** group for new devices. **Edit** switches on the
organising controls:

- rooms: add, rename, delete, reorder (arrows);
- per device: choose a room, rename, pin a channel to quick access, forget (for a missing device);
- scenes: create, capture, edit, delete, reorder, pin.

New devices appear automatically (the agent reports them) and start unassigned.

### Device panel

A centred sheet with one section per channel: an iOS-style switch, then **Brightness**, **White**
(colour temperature, in Kelvin), **Colour** (hue), **Saturation**, and colour presets, each only if the
channel supports it. Details that matter:

- Controls are disabled when the light is off ("Turn it on to adjust"), unavailable, or the app is offline.
- **Sliders commit on release**, not while dragging (`Slider.tsx`). Bulbs accept only about 60 commands a
  minute, so dragging must not send a command per pixel. The dragged value stays on screen until the
  request finishes, so the thumb never jumps back.
- The slider matching the light's current colour mode is highlighted (White or Colour).

### Scenes

- **Scene buttons** apply a scene. If some lights fail, the message names them and the reason; the rest
  of the scene still applied.
- **Editor** (`SceneEditor`): for each light in the scene, each setting (power, brightness, white, colour)
  has an *include* switch; only included settings are stored, and the rest are left alone when the scene is
  applied. White and colour exclude each other (a light shows one). A light set to "off" stores only
  "off".
- **Capture** (`CaptureDialog`): choose lights, name it, and the scene is built from what they show
  right now; the editor then opens for fine tuning.
- The built-in **All off** scene can be renamed and pinned, not edited or deleted.

The pure rules (`draftFromAction`, `draftToAction`, `toggleWhite`, …) are in `sceneModel.ts`.

### Calendar and tasks

- Calendar rows come from `calendarRows()`: all-day events first, then timed events in order. Finished
  events are kept and crossed out. With a row limit (the wall), the **oldest finished events are dropped
  first**, and what still does not fit is cut with a "+n more" note.
- The next (or running) event gets a "Next" / "Now" badge, based on the id the server returns.
- Tasks: tick, add with an optional due date, delete (phone). Overdue and due-date chips are computed by
  the server.

### Assistant (`/assistant`)

A chat you can **talk or type to**, in your own words, in English or Polish
([`backend/README.md`](../backend/README.md#14-the-assistant-text-chat) for what the assistant can do). Replies appear as
bubbles; under each answer small tags show **what was actually done** ("Light bar · Background: off") and, in red, what
failed. Tapping an example sends it. The server remembers the conversation for about two minutes, so "and a bit dimmer"
works; **New chat** forgets it. When the server has no Anthropic key the page says the assistant is not set up.

Talking (needs a `GEMINI_API_KEY` on the server; without it the microphone button is greyed out with a hint):

- **Tap the microphone**, say a sentence, and stop: the page notices the pause, sends the sentence, shows what it heard as a
  bubble with a small microphone mark, and plays the spoken answer. The microphone is released afterwards. Tapping while the
  answer plays stops it. Nothing heard for 8 seconds ends the listening.
- **Hands-free** (switch): the microphone stays open and listens again after every answer. **Speaking over the answer
  interrupts it** and your new sentence is sent straight away.
- **Spoken answers** (switch): mutes the voice on this device; the text always appears. Both switches are remembered per
  device (`localStorage`, only a convenience).
- The status line under the chat says what is happening (Listening, Hearing you, Writing that down, Thinking, Speaking); the
  mic button's ring grows with how loud the room is. Typed and spoken sentences share one conversation.
- A blocked or missing microphone, a lost connection or a failure at Google are explained in the chat in plain words.

### The voice modules

`src/voice/` is plain TypeScript with the browser parts behind small interfaces, so the logic is testable without audio hardware.

**The wake word.** `VoiceClient` has a third mode, `wake` (the first two are tap to talk and hands-free): the microphone stays open and every frame goes to the wake word detector and, to learn how loud the room is, to the sentence detector; **nothing is sent to the server** (apart from a `ping` every 25 s to keep the connection alive). When the wake word is heard the client takes one sentence (the audio from before the wake word is not sent), and after each answer it keeps listening for 6 s with no wake word needed, then goes back to waiting. Saying the wake word over an answer silences it and listens. **The wake reply:** 0.4 s after the wake word (`ACK_DELAY_FRAMES`), if no sentence has begun, the client sends `{type:"wake"}`; the server answers with a short, silence-trimmed "Yes?" (`ack`, binary frames, `ack_end`) which is played, and which is dropped if it arrives after the person has begun. While it plays its sound is in the microphone, so a second detector (`ACKNOWLEDGING` in `vad.ts`: louder than the room, 160 ms of it) decides whether the person is talking over it, and if so it is cut off and what they say is the sentence. The look-back kept at a wake-up is 0.6 s (the wake word is noticed a little after it ends, so a sentence that goes straight on has its first word in it). The model is the pre-trained **hey jarvis** from [openWakeWord](https://github.com/dscripka/openWakeWord) release v0.5.1, in `public/models/` (checksums and licence in the README there); its pre-trained models are **CC BY-NC-SA 4.0 (non-commercial)**, fine for a home project, not for selling it. To use another wake word, put its model in `public/models/`, change the file name in `wakeDetector.ts` (`MODELS`) and the label in `WallVoice.tsx`. The runtime (`onnxruntime-web`, a 15 MB wasm file) and the models are not precached by the service worker; they are cached the first time they are fetched.

| File | Does |
|---|---|
| `public/voice-worklet.js` | Runs on the audio thread: averages the microphone down to 16 kHz mono and posts 20 ms frames of 16-bit samples with their loudness. Plain JS because the browser loads it as a module. |
| `mic.ts` | `openMic`: `getUserMedia` with the **browser's own echo cancellation, noise suppression and level control**, the worklet, and a stop that releases everything. `describeMicError` turns failures into sentences. |
| `vad.ts` | The sentence detector, pure arithmetic over loudness readings: measures the room for the first half second (the quietest quarter counts), then a sentence starts after 100 ms of clearly louder sound and ends after 700 ms of quiet (at most 12 s; under 300 ms is discarded). A second, stricter instance (louder, 300 ms sustained) decides whether the person is interrupting the answer, because the assistant's own voice leaks into the microphone. |
| `pcm.ts`, `player.ts` | Converts the server's 24 kHz PCM and plays it back to back without gaps (`planStart` schedules each piece after the last, with a 200 ms head start for the first); `stop()` silences it at once; `whenDrained` says when everything has been heard. |
| `transport.ts` | The WebSocket to `/api/voice/session`. |
| `voiceClient.ts` | The conversation: tap to talk and hands-free modes, 300 ms of lead-in kept so the first syllable is not lost, sending audio in 100 ms batches, following the server's events, barge-in, and releasing the microphone when a turn ends. Everything that counts time counts 20 ms frames, so it is deterministic. |
| `wakePipeline.ts` | The wake word's audio pipeline, a copy of openWakeWord's (each 80 ms of audio → mel-spectrogram frames, the last 76 → one 96-number embedding, the last 16 embeddings → a score), with the three ONNX models handed in. Pure, so it runs in the browser, in a worker or in Node. |
| `wakeWorker.ts` | A module web worker that loads onnxruntime-web (one thread, no cross-origin isolation needed) and the models and runs the pipeline off the page's thread; drops frames if it cannot keep up. |
| `wakeDetector.ts` | The page's side: sends microphone frames to the worker and decides from its scores: a score from the threshold (0.3 / 0.5 / 0.7 for high / normal / low sensitivity) for **2 steps in a row**, after which the score must drop and 1.5 s pass before it can fire again. |
| `hooks/useVoice.ts` | Wraps the client for React: state, a smoothed microphone level, cleanup on leaving the page. |

The browser lets a page open the microphone and make sound only after a tap, so the client starts from the tap's
handler. The microphone works on `https://` pages and on `localhost`, but on no other plain-`http` address.

### Settings (`/settings`)

- **Home location:** search a place (server-side geocoding); saving it sets the weather location and the
  timezone for the whole app.
- **Phones and displays:** list, revoke, and **Add display** (leads to `/approve`). Each display has a **room** picker (`PUT /api/credentials/{id}/room`): said to that display, “the light” and “all the lights” mean that room's lights.
- **Voice:** which languages you speak (hints for listening), the voice with a **Play sample** button (30 voices, a sentence
  in English and one in Polish), an optional style such as "calm and friendly", and whether answers are spoken. A note says
  that what you say goes to Google and that a paid key keeps it from being used for training.
- **Home agent:** create an agent token for the Pi; it is shown **once**, so copy it right away.
- **Account:** sign out (revokes this phone's session).

## 7. The wall display

`pages/Wall.tsx` is built for a 10.1" 1280×800 touchscreen in a hallway.

- **Layout:** a fixed three-column grid, no scrolling: clock, date and weather on the left; today's
  calendar (5 rows) in the middle; tasks (6 rows) and quick access on the right.
- **Scaling:** `html.wall-mode` sets the root font size from the viewport height
  (`clamp(12px, 2.45vh, 30px)`), and all sizes use `rem`, so the whole layout scales with the screen.
- **Always dark**, via `data-theme="dark"` on the root, which is removed again when leaving the page.
- **Kiosk behaviour:** no text selection, no mouse cursor, large touch targets, and no blur effects
  (they cost too much to draw on a Pi).
- **All devices:** the quick access card has an "All devices ›" button that switches to a list of scenes and
  every device by room. It is **switching only**; renaming, rooms and pinning happen on the phone.
- **Return to the dashboard:** after **30 s without a touch** (`useIdle`) in the devices view or a device
  panel, the screen returns to the dashboard.
- **Limited role:** the display can tick tasks, switch lights, and apply scenes. The wall's task card is
  read/tick only.
- **Offline:** controls disable and a banner explains it ("Reconnecting…" or "Home agent offline").
- **Voice** (`components/WallVoice.tsx`): a pill at the bottom of the screen. Off until it is tapped once (a browser rule for
  sound and the microphone; the choice is kept in `localStorage` under `wall-voice`), then it starts by itself and sets itself
  up again after a lost connection (2 s, doubling to 30 s). It says what is going on: *Say "Hey Jarvis"* (waiting), *Listening…*,
  *One moment…*, *Thinking…*, *Speaking…*, or why it cannot (the microphone is blocked, the browser needs a tap). What was said
  and answered appears above it as a solid card (no blur) for 9 s after things go quiet. Tapping the pill turns voice off, or
  stops an answer being spoken. A moment after the wake word, if you have not started talking, the screen says **"Yes?"** (or whatever is set in Settings → Voice, "After the wake word, say"; empty for silence) so you know it is listening. If you go straight on ("hey jarvis, turn off the light") nothing is said, so it never talks over you. It is hidden when the server has no speech or assistant key. The wake word's sensitivity comes
  from Settings → Voice. See "The wake word" below.

How the Pi starts the kiosk is in [`deploy/README.md`](../deploy/README.md).

## 8. Design system

`src/styles.css` is a single stylesheet, modelled on Apple's iOS and Home apps: grouped cards on a grey
canvas, large titles, hairline separators, iOS-green switches, thick Home-style sliders, a segmented
control, round check circles (as in Reminders), translucent tab bar, centred sheets.

- **Tokens** are CSS custom properties on `:root`: `--bg`, `--surface`, `--surface-2`, `--fill*`,
  `--text`, `--muted`, `--border`, `--accent`, `--danger`, `--ok`, `--radius`, `--ease`, …
- **Light and dark** follow the system (`prefers-color-scheme`). `data-theme="light|dark"` on the root
  forces one; the wall forces dark. Dark mode uses a charcoal canvas (`#1c1c1e`) with `#2c2c2e` cards, not
  pure black.
- **Sliders** paint their filled part from the CSS variable `--pf` (0 to 1) that `Slider` sets; the colour
  and white sliders override the track with their own gradients.
- **Sheets** (device panel, scene editor, capture dialog) are centred with a scrim.
- **Motion** respects `prefers-reduced-motion`.
- The file is organised in labelled sections (cards, weather, calendar, tasks, devices, quick access,
  settings, pairing, editing, sheets, sliders, scenes, tab bar, wall). Use the existing tokens and class
  names rather than adding one-off colours.

## 9. PWA and offline behaviour

- `vite-plugin-pwa` generates the web manifest (name "Home Display", standalone, icons) and a service
  worker that auto-updates. The app can be added to a phone's home screen; `index.html` carries the
  iOS meta tags.
- The service worker caches **only the app shell** (`navigateFallback: /index.html`, not for `/api/` or
  `/agent/`). **API data is never served from the service worker**; offline resilience comes from the
  Query cache keeping the last data, with the "Reconnecting…" banner and "Updated n min ago" notes.
- After a deploy, installed copies pick up the new version on their next load.

## 10. Testing

```sh
npm test            # vitest run
npm run typecheck
```

64 unit tests of the pure modules and the voice client: `homeModel.test.ts` (grouping, pinned items, availability, scene result
text), `sceneModel.test.ts` (drafts to actions, white/colour exclusivity), `calendarRows.test.ts`
(ordering and which rows are dropped), `format.test.ts` (time and relative-time formatting), `assistantModel.test.ts` (chat bubbles, error wording, status line), and in `src/voice/`: `vad.test.ts` (finding where a sentence starts and ends, learning the room's noise), `pcm.test.ts` (sample conversion, playback scheduling) `wakePipeline.test.ts` and `wakeDetector.test.ts` (the pipeline's arithmetic with fake models, the decision rules with a fake worker), `wakeModels.test.ts` (the **real models** on two short recordings in `src/voice/fixtures/`, compared step by step with openWakeWord's own scores) and `voiceClient.test.ts` (whole conversations driven with a fake microphone, connection and speaker: tap to talk, hands-free, interrupting, timeouts, a blocked microphone, a lost connection).

There are no component or end-to-end tests. The UI was checked by hand in a browser (phone width and
the wall at 1280×800, light and dark). For an end-to-end check run the full stack with
`docker compose -f docker-compose.local.yml up --build` from the repository root and use the fake or
real lights.

## 11. Build and deployment

```sh
npm run build        # outputs to dist/
```

The frontend is **not deployed on its own**. The repository's `Dockerfile` builds it (`node:24-alpine`,
`npm ci`, `npm run build`) and copies `dist/` into the backend's static resources, so one container serves
the API and the app. The backend returns `index.html` for unknown non-API paths, so deep links such as
`/wall` work after a reload. See [`deploy/README.md`](../deploy/README.md).

## 12. Extending the app

**A new card on the dashboard.**
1. Add the type to `types.ts` and the call to `api.ts`.
2. Add a query hook to `hooks/data.ts` (copy an existing one; it gets the shared defaults).
3. If the backend publishes a change event, add it to `EVENT_KEYS` in `connection.tsx`.
4. Build the component in `components/`, put decisions in a `…Model.ts` file with tests, and add the
   card to `Dashboard.tsx` (and to `Wall.tsx` with a `limit` if it belongs on the wall).
5. Style it in `styles.css` using the tokens.

**A new light setting** (say, a second colour mode): add it to `ChannelChange`/`Capabilities` in
`types.ts`, a control in `ChannelControls.tsx`, and handle it in `sceneModel.ts` so scenes can include it.
The backend and agent must support it first ([`agent/README.md`](../agent/README.md#6-wire-protocol)).

**A new page.** Add the route in `App.tsx` under the right gate. Only wall-safe things belong under the
display gate.

**Rules of thumb used throughout:**
- Disable controls instead of hiding them when something is unavailable, and say why.
- Never call an endpoint on every pointer move; commit on release.
- Show the last known data with a quiet note rather than an empty or error screen.
- Anything the display role may do must also be allowed by the backend's security rules; the UI is not a
  security boundary.

## 13. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Blank page, `Loading…` forever | The backend is not reachable; the app retries every 5 s. Check the dev proxy (`BACKEND_URL`). |
| Login works but you are signed out immediately (local) | The backend sets a `Secure` cookie over plain HTTP. Use the `dev` profile or `APP_SECURE_COOKIES=false`. |
| `/` opens the wall layout | This browser holds a display cookie. Sign in at `/login`. |
| Lights never update after a change on another device | The live connection is down: look for the "Reconnecting…" banner; check that a proxy does not buffer `/api/stream`. |
| No colour or background controls | The agent is older than the app. Restart the agent ([`agent/README.md`](../agent/README.md#1-quick-start)). |
| Sliders are greyed out | The light is off, unavailable, or the app is offline; turn it on first. |
| Old UI after a deploy | The service worker serves the old shell until the next load; reload twice or clear site data. |
| The microphone button is greyed out | The server has no `GEMINI_API_KEY`, or the page is on plain `http` that is not `localhost`. |
| "The microphone is blocked" | The browser's site settings block it; allow it for this address and tap again. |
| It hears itself and keeps interrupting in hands-free mode | Laptop speakers close to the microphone; turn the volume down or use headphones. A USB speakerphone is the plan for the wall. |
| `tsc` fails on unused variables | `noUnusedLocals`/`noUnusedParameters` are on; remove or use them. |
