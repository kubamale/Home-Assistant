# Deployment

How the system is packaged, started and operated: the container image, the two Docker Compose setups
(a local one and a production one for a VPS), the Caddy reverse proxy, and everything that runs on the
Raspberry Pi (the home agent service and the door-screen kiosk).

The files are spread over two places on purpose, because Docker expects some at the repository root:

| File | Purpose |
|---|---|
| `Dockerfile` (root) | Builds the web app and the backend into **one image** |
| `docker-compose.yml` (root) | **Production** on a VPS: the app plus Caddy with automatic HTTPS |
| `docker-compose.local.yml` (root) | **Local** trial on your own machine; no domain, no HTTPS |
| `.env.example` (root) | Template of the production settings; copy to `.env` |
| `.dockerignore` (root) | Keeps `node_modules`, builds, docs and secrets out of the image |
| `deploy/Caddyfile` | Caddy: HTTPS, compression, security headers, WebSocket/SSE-safe proxying |
| `deploy/pi/agent.json.example` | Config template for the home agent |
| `deploy/pi/homedisplay-agent.service` | systemd unit for the home agent |
| `deploy/pi/kiosk.sh` | Starts Chromium full screen on the door display |
| `deploy/pi/offline.html` | Local page shown until the server is reachable |

## Contents

1. [Topology](#1-topology)
2. [The image](#2-the-image)
3. [Local setup](#3-local-setup)
4. [Production on a VPS](#4-production-on-a-vps)
5. [Caddy](#5-caddy)
6. [Raspberry Pi: the agent](#6-raspberry-pi-the-agent)
7. [Raspberry Pi: the door screen](#7-raspberry-pi-the-door-screen)
8. [Operations](#8-operations)
9. [Security checklist](#9-security-checklist)
10. [Troubleshooting](#10-troubleshooting)
11. [What has been verified](#11-what-has-been-verified)

---

## 1. Topology

```
                         Internet
  Phone ───HTTPS────────▶ ┌──────────────────── VPS (Docker Compose) ─────────────────────┐
  Door screen ──HTTPS───▶ │ caddy :80/:443 ──▶ app :8080 (Spring Boot + web app + SQLite)  │
                          │      │ certificates in volume caddy_data      │ volume data   │
  Home agent ──WSS──────▶ └──────┴─────────────────────────────────────────┴──────────────┘
      ▲ (outbound only)
      │
 ┌────┴──────── Home network ────────┐
 │ Raspberry Pi 4                    │
 │  ├─ homedisplay-agent (systemd) ──┼──LAN──▶ Yeelight lights
 │  └─ Chromium kiosk ───────────────┼──HTTPS─▶ the VPS (/wall)
 └───────────────────────────────────┘
```

- Only the VPS needs open ports (80 and 443). **The home router needs no changes**: the agent and the
  kiosk connect outwards.
- The backend does not have to be on a VPS: anything that runs Docker and is reachable over HTTPS works
  (see "Alternatives" in [`ARCHITECTURE.md`](../ARCHITECTURE.md)).

## 2. The image

The root `Dockerfile` has three stages:

| Stage | Base | Does |
|---|---|---|
| `web` | `node:24-alpine` | `npm ci` and `npm run build` for the frontend |
| `build` | `maven:3-eclipse-temurin-25` | Resolves Maven dependencies (cached layer), copies the frontend's `dist/` into `src/main/resources/static`, runs `mvn package` (tests are skipped in the image build; run them before building) |
| final | `eclipse-temurin:25-jre` | Runs the jar as the unprivileged user `app` (uid 10001) |

Runtime details:

- Data directory `/data` (a volume), set with `APP_DATA_DIR`. The SQLite file is `/data/homedisplay.db`.
- Port 8080.
- JVM flags: `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC` (small, suits a 1-2 GB VPS) and
  `--enable-native-access=ALL-UNNAMED`.
- Compose health check: opens a TCP connection to `127.0.0.1:8080` (30 s interval, 40 s start period).
- The agent is **not** part of the image; it is a separate binary for the Pi (section 6).

Build it alone with `docker build -t homedisplay .` from the repository root.

## 3. Local setup

For trying everything on your own machine, with nothing else to install.

```sh
docker compose -f docker-compose.local.yml up --build
```

Open <http://localhost:8080> and sign in with the password `dev`; open <http://localhost:8080/wall> for the
door layout (approve its pairing from the phone view).

What it runs: the app only, with `APP_SECURE_COOKIES=false` (so cookies work over plain HTTP) and
`APP_DEVICES_MODE=agent`. In agent mode the lights come from a real home agent: run the agent on your
laptop against `http://localhost:8080` with a token from **Settings → Home agent** (see
[`agent/README.md`](../agent/README.md#1-quick-start)). To use two simulated lights instead and no agent, set
`APP_DEVICES_MODE: fake` in the file. Weather is real once a location is set in Settings.

| Variable | Effect |
|---|---|
| `LOCAL_PORT` | Host port (default 8080). |
| `ANTHROPIC_API_KEY` | Enables the Assistant tab locally. Put it in the untracked `.env`. |
| `LOCAL_CALENDAR_MODE` | `fake` (default, sample events) or `caldav` for a real calendar. |
| `LOCAL_CALDAV_USERNAME`, `LOCAL_CALDAV_PASSWORD` | iCloud login for `caldav`: the Apple ID e-mail and an **app-specific password**. |

Put the `LOCAL_*` values in an untracked `.env` file next to the compose file (see `.env.example`); Compose
reads it automatically.

Data lives in the `local-data` volume; `docker compose -f docker-compose.local.yml down -v` deletes it.

> **Do not expose this setup to the internet.** The password is public and cookies are not `Secure`.
>
> **Keep credentials out of the compose file.** It reads the calendar login from `.env`, which is listed in
> `.gitignore`. If a password was ever shared or committed, revoke it at account.apple.com and create a new one.

## 4. Production on a VPS

### What you need

- A small Linux VPS with Docker and the Compose plugin (1 GB of RAM is enough; 2 GB is comfortable).
- A **domain name** with an `A` (and, if the VPS has IPv6, `AAAA`) record pointing at the VPS.
- Ports **80 and 443** open to the internet (Caddy needs both to obtain and renew certificates).

### Steps

```sh
# on the VPS, in the project directory
cp .env.example .env
$EDITOR .env                       # DOMAIN, APP_PASSWORD_HASH, optional calendar
docker compose up -d --build
docker compose ps                  # both services running; app "healthy"
docker compose logs -f             # watch Caddy get the certificate
```

Then open `https://<DOMAIN>`, sign in, and set your location in Settings.

### The `.env` file

| Variable | Required | Meaning |
|---|---|---|
| `DOMAIN` | yes | The domain Caddy serves and gets a certificate for. |
| `APP_PASSWORD_HASH` | yes | bcrypt hash of the login password. Make one with `docker run --rm caddy:2 caddy hash-password --plaintext 'your password'` and keep the **single quotes** around the value in `.env` (the hash contains `$`). |
| `APP_CALENDAR_MODE` | no | `off` (default) or `caldav`. |
| `CALDAV_USERNAME` | with `caldav` | Apple ID e-mail. |
| `CALDAV_PASSWORD` | with `caldav` | An **app-specific password** (account.apple.com → Sign-In and Security → App-Specific Passwords). Never the Apple ID password. |
| `CALDAV_CALENDARS` | no | Only these calendars, by name, comma-separated. Empty means all. |
| `ANTHROPIC_API_KEY` | no | Enables the Assistant tab (Claude). Without it the tab says it is not set up. Create one at console.anthropic.com; it is billed per use. |

`.env` is in `.gitignore` and `.dockerignore`. Compose passes these to the app as `APP_*` variables
(full list in [`backend/README.md`](../backend/README.md#2-configuration)). `APP_DEVICES_MODE` is fixed to
`agent` in the file.

### Services and volumes

| Name | What | Note |
|---|---|---|
| `app` | The image built from the `Dockerfile` | No published ports; only Caddy reaches it. `restart: unless-stopped`. |
| `caddy` | `caddy:2` | Publishes 80 and 443, depends on `app`. |
| volume `data` | The SQLite database | **Back this up** (section 8). |
| volume `caddy_data` | Certificates and ACME account | Keep it. Losing it forces new certificates and can hit the certificate authority's rate limits. |
| volume `caddy_config` | Caddy's autosaved config | Disposable. |

### Updating

```sh
# put the new source on the server, then
docker compose up -d --build
```

Database migrations run automatically when the app starts (Flyway) and are **forward only**: take a
backup first if you might want to go back. The Pi agent and the app are version-tolerant (the agent
protocol only gains optional fields), but update the **server first**, then the agent.

## 5. Caddy

`deploy/Caddyfile` is short:

```
{$DOMAIN} {
    encode zstd gzip
    reverse_proxy app:8080 { flush_interval -1 }
    header { …security headers… }
}
```

- **Automatic HTTPS:** naming the domain is all Caddy needs; it obtains and renews a Let's Encrypt
  certificate and redirects HTTP to HTTPS.
- **`flush_interval -1`** sends bytes to the client immediately. Without it Caddy may buffer the
  Server-Sent Events stream and live updates stop working. WebSocket upgrades (the agent) are proxied
  without extra configuration.
- **Forwarded headers:** Caddy adds `X-Forwarded-*`. Tomcat trusts them only from private addresses
  (the Docker network), so the login rate limit sees real client addresses.
- **Headers set here** (the app itself sets none of these):

  | Header | Value | Why |
  |---|---|---|
  | `Strict-Transport-Security` | `max-age=31536000` | Always use HTTPS |
  | `X-Content-Type-Options` | `nosniff` | |
  | `Referrer-Policy` | `no-referrer` | |
  | `X-Frame-Options` | `DENY` | No embedding |
  | `Content-Security-Policy` | `default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` | Same-origin scripts, styles and connections only |

  If you add an external script, font or image host to the frontend, the CSP must allow it, or the
  browser will block it.

## 6. Raspberry Pi: the agent

The agent is one static Go binary run by systemd. Full details (configuration, protocol, troubleshooting)
are in [`agent/README.md`](../agent/README.md). In short:

1. In the app: **Settings → Home agent → Create agent token** (shown once).
2. On your computer: `agent/build.sh`. It produces `agent/dist/homedisplay-agent-arm64` (64-bit Pi OS)
   and `…-armv7` (32-bit). `uname -m` on the Pi tells which: `aarch64` or `armv7l`.
3. Copy to the Pi and install:

   ```sh
   scp agent/dist/homedisplay-agent-arm64 pi@<pi>:/tmp/homedisplay-agent
   scp deploy/pi/homedisplay-agent.service deploy/pi/agent.json.example pi@<pi>:/tmp/

   # on the Pi
   sudo install -m 0755 /tmp/homedisplay-agent /usr/local/bin/homedisplay-agent
   sudo install -d /etc/homedisplay
   sudo install -m 0644 /tmp/agent.json.example /etc/homedisplay/agent.json
   sudo nano /etc/homedisplay/agent.json          # backendUrl, token, agentId
   sudo cp /tmp/homedisplay-agent.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now homedisplay-agent
   journalctl -u homedisplay-agent -f
   ```

**The unit** (`homedisplay-agent.service`): starts after the network is up, `Restart=always` with a 5 s
delay, runs as a throw-away `DynamicUser`, with `NoNewPrivileges`, `ProtectSystem=strict`,
`ProtectHome=yes` and `PrivateTmp`. Because of `DynamicUser`, the agent cannot read a root-only (`0600`)
config file: either keep it `0644` as above (the token is then visible to local users of the Pi) or pass
the token another way (`systemctl edit homedisplay-agent`, then `Environment=AGENT_TOKEN=…`, which is
stored root-only).

**Network requirements:** the Pi must be on the **same subnet as the lights** (Yeelight discovery uses
multicast to `239.255.255.250:1982`), with outbound HTTPS to the server. Each light needs **LAN Control**
enabled in the Yeelight app.

**Updating the agent:** build, copy the binary over `/usr/local/bin/homedisplay-agent`, then
`sudo systemctl restart homedisplay-agent`.

## 7. Raspberry Pi: the door screen

The door screen is a Chromium browser in kiosk mode showing `https://<domain>/wall`. The Pi runs both
this and the agent.

### Hardware and OS

Raspberry Pi 4 with a 10.1" 1280×800 touchscreen, Raspberry Pi OS with the desktop (64-bit recommended),
a stable power supply, and Wi-Fi or Ethernet. The layout is designed for 1280×800 and scales with the
screen height, so other resolutions work too.

### Files

- `deploy/pi/kiosk.sh <base-url>` starts Chromium. It: hides the mouse pointer (`unclutter`, if installed),
  turns off X11 screen blanking (`xset`, if present), then runs `chromium-browser` full screen with
  `--kiosk`, no error dialogs, info bars, translate prompts or crash bubbles, and no browser-history
  swipe gestures. It opens `offline.html?to=<base-url>/wall`.
- `deploy/pi/offline.html` is a local page that shows "Connecting…". It checks `<base-url>/api/health`
  (no-cors, no cache) every 5 s and, as soon as the server answers, replaces itself with the wall URL.
  This is what you see when the Pi boots before the network or the server is up; it shows "Offline"
  and keeps trying.

### Install

```sh
# on the Pi
sudo apt install -y chromium-browser unclutter      # unclutter is optional
mkdir -p ~/home-display && cp kiosk.sh offline.html ~/home-display/
chmod +x ~/home-display/kiosk.sh
~/home-display/kiosk.sh https://<domain>            # try it by hand first
```

Then start it automatically when the desktop session starts. The place depends on the desktop of your
Pi OS version; two common ones:

| Desktop | Add this line to |
|---|---|
| Wayland (labwc, Raspberry Pi OS Bookworm and later default) | `~/.config/labwc/autostart`: `/home/pi/home-display/kiosk.sh https://<domain> &` |
| X11 (LXDE-pi) | `~/.config/lxsession/LXDE-pi/autostart`: `@/home/pi/home-display/kiosk.sh https://<domain>` |

Also enable desktop auto-login (`raspi-config` → System Options → Boot / Auto Login → Desktop
Autologin). The package and binary name (`chromium-browser` vs `chromium`) and the screen-blanking
settings differ between OS versions; on Wayland, `xset` does nothing, so disable blanking in
`raspi-config` (Display Options) or the desktop's power settings.

### First start

1. The screen opens `/wall`, is not signed in, and goes to `/pair`: it shows a QR code and a code.
2. On the phone, signed in: scan the QR code, or **Settings → Add display** and type the code. Approve.
3. The screen switches to the wall layout. Its login (a cookie in Chromium's profile) lasts five years
   and survives reboots. It is revoked from **Settings → Phones and displays**.

If the Chromium profile is wiped, the screen simply asks to be paired again.

### Reducing wear and load

SD cards wear out from frequent writes: limit journald size (`SystemMaxUse=` in
`/etc/systemd/journald.conf`) or log to RAM. The wall layout deliberately avoids blur effects and heavy
animation for the Pi's GPU.

## 8. Operations

**Logs.**

```sh
docker compose logs -f app          # backend
docker compose logs -f caddy        # proxy and certificates
journalctl -u homedisplay-agent -f  # on the Pi
```

**Health.** `https://<domain>/api/health` returns `{"status":"up"}` (public). `docker compose ps` shows
the `app` health status. The app's own screens flag a missing agent ("Home agent offline").

**Back up the database.** The data is one SQLite file (WAL mode). Stop the app so the file is
consistent, archive the volume, start again:

```sh
docker compose stop app
docker run --rm -v <project>_data:/data -v "$PWD":/backup alpine \
  tar czf /backup/homedisplay-$(date +%F).tgz -C /data .
docker compose start app
```

`<project>_data` is the volume name Compose created; `docker volume ls` shows it (the prefix is the
project directory name). Copy the archive off the server. To restore: stop the app, extract the archive
into the volume, start the app. What is in the database: rooms, device names and assignments, scenes,
quick access, tasks, the location, and the **credential hashes** (restoring an old backup restores old
logins, and revokes any made since). Weather and calendar are cached in memory and are not stored.

**Rotate secrets.**
- Login password: create a new hash, edit `.env`, `docker compose up -d` (existing sessions stay valid).
  To sign everyone out, remove phones/displays in Settings.
- Agent token: create a new one in the app, update the Pi's config, restart the agent, then revoke the old one.
- Calendar password: create a new app-specific password, edit `.env`, `docker compose up -d`, then revoke
  the old one at account.apple.com.

**Resources.** The app runs with a 60 % heap cap and the serial garbage collector; it idles at a few
hundred MB. Caddy needs little.

## 9. Security checklist

- [ ] VPS firewall: allow only 22 (SSH, key login only, ideally restricted by IP), 80 and 443.
- [ ] `.env` is not in a repository or a chat; permissions `0600`.
- [ ] `APP_PASSWORD_HASH` is set (not the plain `APP_PASSWORD`), and the password is long.
- [ ] `docker-compose.local.yml` contains no real secrets (calendar login lives in `.env`).
- [ ] The calendar uses an app-specific password, which can be revoked on its own.
- [ ] The Anthropic key lives only in `.env`; it is a spending credential, so set a spend limit on it in the Anthropic console.
- [ ] Backups of the `data` volume exist and are stored off the server.
- [ ] The agent token exists only in `/etc/homedisplay/agent.json` (or the unit's override) on the Pi.
- [ ] Only HTTPS is exposed; the app container publishes no ports itself.
- [ ] Unused phones and displays are removed in Settings.

## 10. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Compose refuses to start: `set APP_PASSWORD_HASH in .env` or `set DOMAIN in .env` | A required variable is missing | Fill in `.env`. |
| `app` exits at start with "Set APP_PASSWORD_HASH (bcrypt)…" | The hash is empty, or `$` characters were mangled | Quote the value with single quotes in `.env`. |
| Login always fails with the right password | The hash was truncated by shell expansion of `$` | Re-create the hash; keep single quotes. |
| Caddy never gets a certificate | DNS does not point at the VPS yet, or ports 80/443 are blocked | Fix DNS or the firewall; see `docker compose logs caddy`. |
| Site works, but screens never update live | Something in front of Caddy buffers `/api/stream` (a CDN or proxy) | Disable buffering/caching for that path, or connect directly. |
| Browser blocks a resource, console shows a CSP error | The CSP in the Caddyfile does not allow it | Add the host to the matching directive. |
| Lights show "Home agent offline" | The agent is not connected | `journalctl -u homedisplay-agent`; check token, URL, and that the app runs in `agent` mode. |
| No lights found by the agent | LAN Control off, other subnet, multicast blocked | See [`agent/README.md`](../agent/README.md#10-troubleshooting). |
| The agent logs `permission denied` reading its config | `DynamicUser` cannot read a root-only file | Make the file `0644` or use an `Environment=` override (section 6). |
| Kiosk shows "Offline" forever | The server is unreachable from the Pi, or `kiosk.sh` got the wrong URL | Open `<url>/api/health` from the Pi; check the argument and DNS. |
| Kiosk shows the pairing code after every reboot | Chromium does not keep its profile or cookies | Do not run Chromium with a throw-away profile (`--incognito`, tmpfs profile). |
| Screen goes black after a few minutes | Screen blanking is on | Disable it (section 7). |
| `/` shows the wall layout on a phone | That browser holds a display cookie | Sign in at `/login`. |
| `docker compose up --build` fails while downloading | Network or a registry limit | Retry; the Maven and npm layers are cached afterwards. |

## 11. What has been verified

Verified: the production image builds and runs; the local Compose setup works end to end, including a
real home agent against real Yeelight lights (a Monitor Light Bar Pro) and a real iCloud calendar;
`caddy validate` accepts the Caddyfile and `docker compose config` accepts the production file.

**Not verified** (they need hardware or an account that was not available): a real VPS with a domain and
certificate, the production `docker-compose.yml` on such a server, the kiosk script and its autostart
on a real Raspberry Pi 4 with the touchscreen, and the agent systemd unit on a Pi. The notes on
Wayland/X11 autostart and screen blanking in section 7 are typical settings, not tested ones; expect to
adjust them for your Pi OS version.
