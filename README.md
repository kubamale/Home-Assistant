# Home Display

Weather, tasks, calendar and lights for the home, shown on a phone and on a door-mounted touchscreen.
Requirements are in `PRD.md`, the design in `ARCHITECTURE.md`; `VOICE-CONTROL.md` is the plan for a voice agent that listens and speaks (the typed chat, the first stage, is built; speech is not); `smart-door-display-mvp.md` is the original plan.

```
backend/   Spring Boot 4 (Java 25): REST + SSE API, auth, SQLite, serves the built web app
frontend/  React + TypeScript (Vite), installable PWA: phone layout and /wall layout for the door screen
agent/     Go home agent for the Raspberry Pi: finds Yeelight bulbs, connects out to the backend
deploy/    Caddy config, Pi agent service file, kiosk script and offline page
```

Each module has its own reference documentation: [`backend/README.md`](backend/README.md),
[`frontend/README.md`](frontend/README.md), [`agent/README.md`](agent/README.md) and
[`deploy/README.md`](deploy/README.md) (which also covers the Dockerfile and the Compose files).

## Run it locally

**Fastest: Docker only.** One command, nothing else to install:

```sh
docker compose -f docker-compose.local.yml up --build
```

Open http://localhost:8080 and sign in with the password `dev`. As shipped it expects lights from a home
agent (`APP_DEVICES_MODE: agent`; run the agent on your laptop, see `agent/README.md`) and you can switch it to
`fake` for two simulated lights; the calendar can be `fake` (sample events) or `caldav`. Weather is real once you
set your town in Settings. Open http://localhost:8080/wall for the
door-screen layout. Set `LOCAL_PORT=9090` if port 8080 is taken; `docker compose -f docker-compose.local.yml down -v`
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
"today" follow it). Open http://localhost:5173/wall for the door-screen layout; it asks to pair, which you
approve on the phone view (scan the QR code or type the code in Settings → Add display).

## Tests

```sh
cd backend && mvn test            # API, auth, pairing, weather, tasks, calendar, CalDAV, agent WebSocket
cd frontend && npm test           # formatting helpers
agent/build.sh                    # cross-compiles the agent for the Pi (uses Docker, no Go install needed)
docker run --rm -v $PWD/agent:/src -w /src golang:1.25 go test ./...    # agent tests
```

## Deploy

**Server (a small VPS with Docker):**

1. Point a domain name at the server and open ports 80 and 443.
2. `cp .env.example .env` and fill in `DOMAIN` and `APP_PASSWORD_HASH` (the file explains how to make the hash).
   For the calendar, set `APP_CALENDAR_MODE=caldav` with an iCloud *app-specific* password.
3. `docker compose up -d --build`. Caddy gets the HTTPS certificate by itself.
4. Open `https://<domain>`, sign in and set the location in Settings.

**Raspberry Pi (agent):**

1. In the app, Settings → Home agent → Create agent token (shown once).
2. On your computer run `agent/build.sh`, then copy `agent/dist/homedisplay-agent-arm64` (or `-armv7` for 32-bit
   Pi OS) to the Pi as `/usr/local/bin/homedisplay-agent`.
3. Copy `deploy/pi/agent.json.example` to `/etc/homedisplay/agent.json` and fill in the URL and token.
4. Install `deploy/pi/homedisplay-agent.service` into `/etc/systemd/system/`, then
   `sudo systemctl enable --now homedisplay-agent`.

**Raspberry Pi (door screen):** run `deploy/pi/kiosk.sh https://<domain>` from the desktop session's
autostart. It opens Chromium full screen on a local "offline" page that forwards to `/wall` as soon as the
server answers. The first time, the screen shows a pairing code; approve it from the phone.

## What has and has not been verified

Verified in this repository: the backend (automated tests, plus a live run against Open-Meteo), the web
app in a real browser (phone and 1280×800 wall layouts, pairing, reconnect after a backend outage), the
agent (Go tests with a fake bulb, and a real agent binary connecting to the real backend), and the
production Docker image.

Since then, a real Yeelight light bar (Monitor Light Bar Pro), a real iCloud calendar and multicast discovery
on a home network have been tried by hand and work.

Not yet verified, because it needs hardware or accounts: the Pi kiosk script on your Pi OS version, the
agent as a systemd service on a Pi, and a real VPS with a domain and certificate.
