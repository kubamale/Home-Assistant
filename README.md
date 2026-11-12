# Home Display

Weather, tasks, calendar and lights for the home, shown on a phone and on wall-mounted tablets. Several people can share one home: each has their own account, tasks, calendar, pinned lights and scenes, and arranges their own wall display.
Requirements are in `PRD.md`, the design in `ARCHITECTURE.md`; `VOICE-CONTROL.md` is the plan for a voice agent that listens and speaks (the typed chat, talking in the browser and the wall's "Hey Jarvis" wake word are built; none of it has been tried on a tablet yet); `smart-door-display-mvp.md` is the original plan; `SHOPPING-LIST.md` is what to buy: a Raspberry Pi 5 for the server and tablets for the wall displays.

```
backend/   Spring Boot 4 (Java 25): REST + SSE API, auth, SQLite, serves the built web app
frontend/  React + TypeScript (Vite), installable PWA: phone layout and /wall layout for the wall tablets
agent/     Go home agent, run on the Raspberry Pi 5 next to the app: finds Yeelight bulbs, connects to the backend
deploy/    Pi agent service file and the deployment guide; Caddy config, kiosk script and offline page for older setups
```

Each module has its own reference documentation: [`backend/README.md`](backend/README.md),
[`frontend/README.md`](frontend/README.md), [`agent/README.md`](agent/README.md) and
[`deploy/README.md`](deploy/README.md) (which also covers the Dockerfile and the Compose files).

## Run it locally

**Fastest: Docker only.** One command, nothing else to install:

```sh
docker compose -f docker-compose.local.yml up --build
```

Open http://localhost:8080 and sign in with the password `dev` (leave the name empty: you are the admin; add other people in Settings → People). As shipped it expects lights from a home
agent (`APP_DEVICES_MODE: agent`; run the agent on your laptop, see `agent/README.md`) and you can switch it to
`fake` for two simulated lights; the calendar can be `fake` (sample events) or `caldav`. Weather is real once you
set your town in Settings. Open http://localhost:8080/wall for the
wall layout. Set `LOCAL_PORT=9090` if port 8080 is taken; `docker compose -f docker-compose.local.yml down -v`
removes the data. This setup is for your own machine only: the password is public and cookies are not Secure.

**For development** (hot reload): you need Java 25 and Maven, and Node 24 or newer. No Pi, bulbs or iCloud account are needed: the `dev`
profile uses the same simulated lights and sample calendar.

```sh
# 1. backend on :8080 (login password in dev is "dev")
cd backend && SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

# 2. web app on :5173, proxying /api to the backend
cd frontend && npm install && npm run dev
```

Open http://localhost:5173, sign in, then Settings → search for your town to set the location (weather and
"today" follow it). Open http://localhost:5173/wall for the wall layout; it asks to pair, which you
approve on the phone view (scan the QR code or type the code in Settings → Add display).

## Tests

```sh
cd backend && mvn test            # API, auth, pairing, weather, tasks, calendar, CalDAV, agent WebSocket
cd frontend && npm test           # formatting helpers
agent/build.sh                    # cross-compiles the agent for the Raspberry Pi (uses Docker, no Go install needed)
docker run --rm -v $PWD/agent:/src -w /src golang:1.25 go test ./...    # agent tests
```

## Deploy

The plan: a **Raspberry Pi 5 at home is the server** (the app in Docker, and the home agent beside it), **tablets are the wall
displays** (a kiosk browser app showing `/wall`), and phones reach the server from anywhere through **Tailscale or a Cloudflare Tunnel**
(not yet chosen between the two). No port is opened on the router. The full guide, with the comparison of the two ways in, is
[`deploy/README.md`](deploy/README.md). In short:

**The server (the Pi 5, Raspberry Pi OS Lite 64-bit, Docker installed):**

1. `cp .env.example .env` and fill in `APP_PASSWORD_HASH` (the file explains how to make the hash) and `APP_SECRET_KEY`. For the
   calendar, people connect their own in the app; `APP_CALENDAR_MODE=caldav` sets one for the admin on the server.
2. `docker compose -f docker-compose.pi.yml up -d --build`. The app now listens on `127.0.0.1:8080`, on the Pi only.
3. Make it reachable over HTTPS: Tailscale (`tailscale serve --bg 8080`) or a Cloudflare Tunnel (`--profile cloudflare`). HTTPS is
   needed even at home, because browsers only allow the microphone on it.
4. Open the address on your phone, sign in (leave the name empty: you are the admin) and set the location in Settings.

**The home agent (on the same Pi, outside Docker):**

1. In the app, Settings → Home agent → Create an agent token (shown once).
2. On your computer run `agent/build.sh`, then copy `agent/dist/homedisplay-agent-arm64` to the Pi as `/usr/local/bin/homedisplay-agent`.
3. Copy `deploy/pi/agent.json.example` to `/etc/homedisplay/agent.json` and put in the token (`backendUrl` stays `http://127.0.0.1:8080`).
4. Install `deploy/pi/homedisplay-agent.service` into `/etc/systemd/system/`, then
   `sudo systemctl enable --now homedisplay-agent`.

**A wall tablet:** install a kiosk browser app, set its start address to `https://<your address>/wall`, keep the screen on and allow the
microphone. The first time, the tablet shows a pairing code; approve it from your phone, choose whose display it is, then arrange it in
Settings. Details and what to look for in a kiosk app: [`deploy/README.md`](deploy/README.md), section 7.

Older setups still work: a server on a VPS with its own domain and Caddy (`docker-compose.yml`), and a Raspberry Pi with a touchscreen as a
display (`deploy/pi/kiosk.sh`). They are described in the appendices of the deployment guide.

## What has and has not been verified

Verified in this repository: the backend (automated tests, plus a live run against Open-Meteo), the web
app in a real browser (phone and 1280×800 wall layouts, pairing, reconnect after a backend outage), the
agent (Go tests with a fake bulb, and a real agent binary connecting to the real backend), and the
production Docker image.

Since then, a real Yeelight light bar (Monitor Light Bar Pro), a real iCloud calendar and multicast discovery
on a home network have been tried by hand and work.

Not yet verified, because it needs hardware or accounts, and this is **all of the current plan**: the image on a Raspberry Pi 5,
the agent as a service on that Pi, Tailscale or a Cloudflare Tunnel in front of the app, and the wall page and its microphone in a kiosk
app on a tablet.
