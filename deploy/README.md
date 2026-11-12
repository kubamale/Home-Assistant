# Deployment

How the system is packaged, started and operated. **The plan** (changed 2026-11-12): a **Raspberry Pi 5 at home is the
server** (the app in Docker, and the home agent next to it), **tablets are the wall displays** (each running the wall page
in a kiosk browser app), and phones reach the server from anywhere through **Tailscale or a Cloudflare Tunnel** (not yet
chosen between the two: section 5 compares them).

Older plans are still supported by the software and are kept as appendices: a server on a VPS with its own domain and Caddy
(Appendix A), and a Raspberry Pi used as a display (Appendix B).

| File | Purpose |
|---|---|
| `Dockerfile` (root) | Builds the web app and the backend into **one image** |
| `docker-compose.pi.yml` (root) | **The home server**: the app only, published on the machine itself; optional Cloudflare Tunnel service |
| `docker-compose.yml` (root) | A server reachable directly from the internet: the app plus Caddy with automatic HTTPS (Appendix A) |
| `docker-compose.local.yml` (root) | **Local** trial on your own computer; no domain, no HTTPS |
| `.env.example` (root) | Template of the settings; copy to `.env` |
| `.dockerignore` (root) | Keeps `node_modules`, builds, docs and secrets out of the image |
| `deploy/Caddyfile` | Caddy: HTTPS, compression, security headers (Appendix A only) |
| `deploy/pi/agent.json.example` | Config template for the home agent |
| `deploy/pi/homedisplay-agent.service` | systemd unit for the home agent |
| `deploy/pi/kiosk.sh`, `offline.html` | Chromium kiosk for a Raspberry Pi used as a display (Appendix B) |

## Contents

1. [Topology](#1-topology)
2. [The image](#2-the-image)
3. [Local setup](#3-local-setup)
4. [The home server (Raspberry Pi 5)](#4-the-home-server-raspberry-pi-5)
5. [HTTPS and reaching it from outside](#5-https-and-reaching-it-from-outside)
6. [The home agent](#6-the-home-agent)
7. [The wall tablets](#7-the-wall-tablets)
8. [Operations](#8-operations)
9. [Security checklist](#9-security-checklist)
10. [Troubleshooting](#10-troubleshooting)
11. [What has been verified](#11-what-has-been-verified)
- [Appendix A: a server with its own domain (Caddy)](#appendix-a-a-server-with-its-own-domain-caddy)
- [Appendix B: a Raspberry Pi as a display](#appendix-b-a-raspberry-pi-as-a-display)

---

## 1. Topology

```
                       ┌───────────────────── Raspberry Pi 5 at home ──────────────────────┐
  Phone ──HTTPS──┐     │  Tailscale Serve  or  Cloudflare Tunnel   (HTTPS in, nothing open) │
  (anywhere)     ├────▶│            │                                                       │
  Wall tablets ──┘     │            ▼  127.0.0.1:8080                                       │
  (HTTPS)              │   app (Docker: Spring Boot + web app + SQLite, volume "data")      │
                       │            ▲                                                       │
                       │            │ ws://127.0.0.1:8080/agent/ws                          │
                       │   homedisplay-agent (systemd, outside Docker) ──LAN──▶ Yeelight    │
                       └───────────────────────────────────────────────────────────────────┘
```

- **The router needs no changes.** Tailscale and Cloudflare Tunnel both connect *out* from the Pi, so no port is forwarded.
- **The agent and the app are on the same machine**, so the lamps are controlled over the home network and the agent talks to
  the app on `127.0.0.1`. The agent has to run **outside Docker**, because lamp discovery uses multicast, which does not cross
  Docker's default network.
- **The tablets only run a browser page.** The wall page, the sky background, the "Hey Jarvis" wake word and the microphone all
  run on the tablet, not on the Pi. The Pi never draws a screen.
- **Why HTTPS even at home:** browsers allow the **microphone** and installing the app only on `https://` or `localhost`, and
  the app's cookies are `Secure`. A tablet opening `http://pi.local:8080` would work for the lights and tasks but not for voice.
  That is why section 5 exists.

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
- JVM flags: `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC` (small, suits a few GB) and `--enable-native-access=ALL-UNNAMED`.
- Compose health check: opens a TCP connection to `127.0.0.1:8080`.
- The agent is **not** part of the image; it is a separate binary (section 6).
- **On a Pi 5 (arm64)** the image builds on the Pi itself with `docker compose ... up --build`. The three base images publish
  arm64 variants, but that has not been tried here. The first build downloads Maven and npm dependencies and takes a while.

Build it alone with `docker build -t homedisplay .` from the repository root.

## 3. Local setup

For trying everything on your own machine, with nothing else to install.

```sh
docker compose -f docker-compose.local.yml up --build
```

Open <http://localhost:8080> and sign in with the password `dev` (leave the name empty: you are the admin); open
<http://localhost:8080/wall> for the wall layout (approve its pairing from the phone view).

What it runs: the app only, with `APP_SECURE_COOKIES=false` (so cookies work over plain HTTP) and
`APP_DEVICES_MODE=agent`. In agent mode the lights come from a real home agent: run the agent on your
laptop against `http://localhost:8080` with a token from **Settings → Home agent** (see
[`agent/README.md`](../agent/README.md#1-quick-start)). To use two simulated lights instead and no agent, set
`APP_DEVICES_MODE: fake` in the file. Weather is real once a location is set in Settings.

| Variable | Effect |
|---|---|
| `LOCAL_PORT` | Host port (default 8080). |
| `ANTHROPIC_API_KEY` | Enables the Assistant tab locally. Put it in the untracked `.env`. |
| `GEMINI_API_KEY` | Enables the microphone locally (also in `.env`). The browser allows the microphone on `http://localhost`, but on any other address it needs HTTPS. |
| `LOCAL_CALENDAR_MODE` | `fake` (default, sample events) or `caldav` for a real calendar. |
| `LOCAL_CALDAV_USERNAME`, `LOCAL_CALDAV_PASSWORD` | iCloud login for `caldav`: the Apple ID e-mail and an **app-specific password**. |

Data lives in the `local-data` volume; `docker compose -f docker-compose.local.yml down -v` deletes it.

> **Do not expose this setup to the internet.** The password is public and cookies are not `Secure`.

## 4. The home server (Raspberry Pi 5)

### What you need

- A **Raspberry Pi 5** (4 GB is enough; 8 GB is comfortable), the official 27 W power supply, and an active cooler or a case with
  a fan, because the app and a Java runtime keep it busy at start-up and during voice turns.
- **Storage that survives writes.** The database is written all the time. A good high-endurance microSD card works, but an
  **SSD** (USB, or NVMe with a Pi 5 M.2 adapter) is much safer for a machine that runs for years.
- **Raspberry Pi OS Lite, 64-bit.** No desktop is needed: the Pi never shows a screen.
- A wired connection (Ethernet) if the router is near, otherwise Wi-Fi. The Pi must be on the **same network as the lights**.
- A static address for the Pi on your router (a DHCP reservation), so it is always found at the same place.

### Install Docker

Use the official instructions for Raspberry Pi OS / Debian (the "convenience script" or the apt repository), then add your user
to the `docker` group. Check with `docker compose version`.

### Steps

```sh
# on the Pi, in the project directory (clone the repository or copy it over)
cp .env.example .env
chmod 600 .env
$EDITOR .env             # APP_PASSWORD_HASH, APP_SECRET_KEY, and the keys you use
docker compose -f docker-compose.pi.yml up -d --build
docker compose -f docker-compose.pi.yml ps      # app "healthy"
curl -s http://127.0.0.1:8080/api/health        # {"status":"up"}
```

The app is now running, but only reachable **from the Pi itself**. Section 5 makes it reachable from your phone and tablets over
HTTPS; section 6 adds the agent.

### The `.env` file

| Variable | Required | Meaning |
|---|---|---|
| `APP_PASSWORD_HASH` | yes | bcrypt hash of the **admin's** password. Make one with `docker run --rm caddy:2 caddy hash-password --plaintext 'your password'` and keep the **single quotes** around the value in `.env` (the hash contains `$`). |
| `APP_SECRET_KEY` | for personal calendars | Any long random text (`openssl rand -base64 32`). Encrypts the calendar passwords people save in Settings → My calendar. Without it people cannot save one. **Keep it with your backups:** if it is lost or changed, they connect their calendars again. |
| `CLOUDFLARE_TUNNEL_TOKEN` | with a Cloudflare Tunnel | The token Cloudflare gives you when you create the tunnel (section 5). |
| `APP_CALENDAR_MODE` | no | `off` (default) or `caldav`. The one calendar set up on the server: it is the admin's until they connect their own; other people connect theirs in the app. |
| `CALDAV_USERNAME`, `CALDAV_PASSWORD`, `CALDAV_CALENDARS` | with `caldav` | Apple ID e-mail; an **app-specific password** (account.apple.com → Sign-In and Security → App-Specific Passwords), never the Apple ID password; and calendar names to show, or empty for all. |
| `ANTHROPIC_API_KEY` | no | Enables the assistant (Claude). Without it the Assistant tab says it is not set up. Billed per use. |
| `GEMINI_API_KEY` | no | Enables talking and listening (Google Gemini). Create it at aistudio.google.com and **turn on billing**: on the free tier Google may use your requests, including your voice, to improve its products. |

`.env` is in `.gitignore` and `.dockerignore`. Compose passes these to the app as `APP_*` variables (full list in
[`backend/README.md`](../backend/README.md#2-configuration)). `DOMAIN` is not used by the home server.

### Services and volumes (`docker-compose.pi.yml`)

| Name | What | Note |
|---|---|---|
| `app` | The image built from the `Dockerfile` | Published only on `127.0.0.1:8080`, so nothing on the network can reach it directly. `restart: unless-stopped` |
| `cloudflared` | `cloudflare/cloudflared` | Only with `--profile cloudflare` (section 5) |
| volume `data` | The SQLite database | **Back this up** (section 8) |

### Updating

```sh
# put the new source on the Pi, then
docker compose -f docker-compose.pi.yml up -d --build
```

Database migrations run automatically when the app starts (Flyway) and are **forward only**: take a backup first if you might want
to go back. Update the **server first**, then the agent (the agent protocol only gains optional fields).

## 5. HTTPS and reaching it from outside

The app must be served over **HTTPS** to your phone and tablets, for the reasons in section 1. Both options below give you a real
certificate **without opening a port on your router**. **The choice between them has not been made yet.**

| | Tailscale | Cloudflare Tunnel |
|---|---|---|
| Who can reach the app | Only devices you sign in to your private Tailscale network | Anyone who knows the address (the app's own login protects it) |
| What phones and tablets need | The **Tailscale app**, signed in and always connected (the tablets need it too) | Nothing: just the web address |
| Address | `https://<machine>.<your-tailnet>.ts.net` (long, fixed by Tailscale) | Your own, for example `https://home.example.com` |
| You need | A Tailscale account | A **domain name** whose DNS is managed by Cloudflare, and a Cloudflare account |
| At home | Tablets reach the Pi directly over the home network | Tablets go out to Cloudflare and back, which adds a little delay, including to voice |
| If it fails | A tablet whose VPN drops shows an error until it reconnects | The Pi losing its internet connection takes everything offline, including at home |
| Cost | A free personal plan exists (check its limits) | Tunnels are free; you pay for the domain |

How to choose: **Tailscale is more private** and nothing is exposed to the internet, but every device must run its app, and a tablet
kiosk depends on that VPN staying up. **Cloudflare Tunnel is simpler for the tablets** (they just open an address) and gives you a
nice name, but the login page is public. Neither has been tried on your Pi.

### Option 1: Tailscale

1. Install Tailscale on the Pi (the official install script or package for Raspberry Pi OS) and sign in: `sudo tailscale up`.
2. In the Tailscale admin console, enable **MagicDNS** and **HTTPS certificates** for your tailnet.
3. Serve the app: `sudo tailscale serve --bg 8080`. Tailscale terminates HTTPS and proxies to `127.0.0.1:8080`. `tailscale serve status`
   shows the address.
4. Install the Tailscale app on your phone and on **each tablet**, sign in to the same account, and turn on "Always-on VPN" (Android
   settings) for the tablets so the connection survives a restart.
5. Open the address on the phone, sign in, and continue with section 6.

Do **not** use Tailscale Funnel: it publishes the address to the whole internet, which defeats the point.

### Option 2: Cloudflare Tunnel

1. Put your domain's DNS on Cloudflare (its nameservers), then in the Cloudflare dashboard (Zero Trust → Networks → Tunnels) create a
   tunnel and copy its **token**.
2. In `.env` on the Pi set `CLOUDFLARE_TUNNEL_TOKEN=<the token>`.
3. In the tunnel's configuration add a **public hostname** (for example `home.example.com`) with the service **`http://app:8080`**
   (the app's name inside Docker's network). **Do not** override the HTTP host header: the app checks that a browser's `Origin` matches
   the host it was sent to, and an override makes every change fail with `403`.
4. Start the tunnel with the app: `docker compose -f docker-compose.pi.yml --profile cloudflare up -d`.
5. Open `https://home.example.com` on the phone, sign in, and continue with section 6.

### After either option: check

- [ ] Sign in on the phone from outside the home network (mobile data).
- [ ] A change on one screen appears on another without reloading (live updates use a long-lived stream; the app sends a heartbeat
      every 20 seconds to keep it open).
- [ ] On a tablet, open `/wall`, pair it, and tap the microphone pill: the browser must offer the microphone (it will not on `http://`).
- [ ] Several wrong passwords from one phone lock only that phone, not everyone. If everyone is locked together, the proxy is not passing
      client addresses on; see section 10.

## 6. The home agent

The agent is one static Go binary run by systemd, **on the same Pi as the app**. Full details (configuration, protocol,
troubleshooting) are in [`agent/README.md`](../agent/README.md). In short:

1. In the app: **Settings → Home agent → Create an agent token** (shown once).
2. On your computer: `agent/build.sh`. It produces `agent/dist/homedisplay-agent-arm64` (64-bit Pi OS) and `…-armv7`.
3. Copy to the Pi and install:

   ```sh
   scp agent/dist/homedisplay-agent-arm64 pi@<pi>:/tmp/homedisplay-agent
   scp deploy/pi/homedisplay-agent.service deploy/pi/agent.json.example pi@<pi>:/tmp/

   # on the Pi
   sudo install -m 0755 /tmp/homedisplay-agent /usr/local/bin/homedisplay-agent
   sudo install -d /etc/homedisplay
   sudo install -m 0644 /tmp/agent.json.example /etc/homedisplay/agent.json
   sudo nano /etc/homedisplay/agent.json          # token; backendUrl stays http://127.0.0.1:8080
   sudo cp /tmp/homedisplay-agent.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now homedisplay-agent
   journalctl -u homedisplay-agent -f
   ```

Because the agent and the app are on the same machine, `backendUrl` is `http://127.0.0.1:8080`: it does not go through Tailscale or
Cloudflare, and keeps working if the internet is down. (The agent reconnects with a growing delay, so it does not matter whether it
starts before the app.)

**The unit** (`homedisplay-agent.service`): starts after the network is up, `Restart=always` with a 5 s delay, runs as a throw-away
`DynamicUser`, with `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome=yes` and `PrivateTmp`. Because of `DynamicUser`, the agent
cannot read a root-only (`0600`) config file: keep it `0644` as above (the token is then visible to local users of the Pi) or pass
the token another way (`systemctl edit homedisplay-agent`, then `Environment=AGENT_TOKEN=…`).

**Network requirements:** the Pi must be on the **same subnet as the lights** (Yeelight discovery uses multicast to
`239.255.255.250:1982`). Each light needs **LAN Control** enabled in the Yeelight app. Do not run the agent in a Docker container
with the default network: multicast will not reach the lights (host networking would, but running it directly is simpler).

**Updating the agent:** build, copy the binary over `/usr/local/bin/homedisplay-agent`, then `sudo systemctl restart homedisplay-agent`.

## 7. The wall tablets

Each wall display is a tablet running a **kiosk browser app** that shows `https://<your address>/wall` full screen. The tablet needs
no setup of the server: it pairs like any display (section 7.3).

### 7.1 What the tablet needs

- **A recent browser engine.** The wall page uses a web worker, WebAssembly with SIMD, AudioWorklet and the microphone for the wake
  word. A current Android with an up-to-date *Android System WebView* (or Chrome) has these; an old tablet may not. If the wake word
  does not load, the app says so on the screen.
- **Always powered.** Mount it where a charger can stay connected. Many Samsung tablets have a **"Protect battery"** option that stops
  charging at 85 %; check yours, because a tablet held at 100 % for years ages its battery.
- **A good place for the microphone.** The tablet's own microphone and speaker are used. The speaker plays the assistant's answers
  right next to the microphone, so the first thing to try is the tablet's built-in echo cancellation; see
  [`VOICE-CONTROL.md`](../VOICE-CONTROL.md).
- **Network.** Wi-Fi with a good signal at the wall. With Tailscale the tablet also needs the Tailscale app connected (section 5).

### 7.2 The kiosk app

Use an app that does these things (settings vary by app; one widely used example is **Fully Kiosk Browser**, which has a paid
licence for some features, so check its terms; it has not been tried with this project):

| Setting | Why |
|---|---|
| Start URL `https://<your address>/wall` | The wall page |
| Start on boot, and bring itself back after a crash | So a power cut or a restart needs no one |
| Keep the screen on (or on a schedule, and off at night) | A wall display that sleeps is useless; a screen on all night wears an OLED |
| Lock to the page (no navigation bar, no way to other apps) | It is a wall, not a tablet |
| Reload the page when the connection comes back | After the server or the Wi-Fi was down |
| Allow the **microphone** and **autoplay of sound** for the page | Nobody is there to tap a permission prompt. Also grant the app the Android microphone permission |
| Keep the web view updated | The wake word needs a current engine |

### 7.3 First start

1. The tablet opens `/wall`, is not signed in, and goes to `/pair`: it shows a QR code and a code.
2. On a phone, signed in: scan the QR code, or **Settings → Add a display** and type the code. Choose whose display it is, and approve.
3. The tablet switches to the wall layout. Its login (a cookie in the app's web view) lasts five years. It is removed from the display's
   page in Settings.
4. Tap the small microphone pill once. Voice is then on, and starts by itself after a restart.
5. Open the display's page in Settings and **arrange** it: which cards, where, how many items.

If the app's data is cleared, the tablet simply asks to be paired again.

### 7.4 What to know

- **Screen size.** The wall layout was designed for 1280×800 and scales with the screen height, so a tablet with another resolution works
  but has **not been tried**; check that nothing is cut off at the edges and adjust the layout if so.
- **Weather motion.** The wall's background can drift clouds, rain, snow, stars and fog behind the cards, softly. It is a switch on the
  display's page in Settings (**Move with the weather**), on for new displays and off for layouts saved before it existed. It uses
  only transform and opacity animations, which a tablet handles on its graphics chip; it has not been measured on a tablet. Android's
  "remove animations" setting turns it off, so if you set that for the kiosk, expect a still background.
- **No blur on the wall page.** The wall page switches off the frosted-glass blur because it was planned for a slow Raspberry Pi. A tablet
  is far faster, so this will become a per-display choice; until then the wall is solid-ish glass.
- **Voice** (wake word "Hey Jarvis", then a sentence): runs in the tablet's browser, so the tablet's processor does the listening for the
  wake word. This has been tried in a laptop browser, **not on a tablet**.
- **Sensitivity.** If the tablet does not hear "Hey Jarvis" (the model is trained on English), raise **Wake word sensitivity** in Settings →
  Voice; if it wakes by itself, lower it.

## 8. Operations

**Logs.**

```sh
docker compose -f docker-compose.pi.yml logs -f app          # backend
docker compose -f docker-compose.pi.yml logs -f cloudflared  # the tunnel (if used)
journalctl -u homedisplay-agent -f                           # the agent
tailscale status                                             # Tailscale (if used)
```

**Health.** `curl http://127.0.0.1:8080/api/health` on the Pi returns `{"status":"up"}`; the same path works through your HTTPS address.
The app's own screens flag a missing agent ("Home agent offline").

**Back up the database.** The data is one SQLite file (WAL mode). Stop the app so the file is consistent, archive the volume, start again:

```sh
docker compose -f docker-compose.pi.yml stop app
docker run --rm -v <project>_data:/data -v "$PWD":/backup alpine \
  tar czf /backup/homedisplay-$(date +%F).tgz -C /data .
docker compose -f docker-compose.pi.yml start app
```

`<project>_data` is the volume name Compose created; `docker volume ls` shows it (the prefix is the project directory name). **Copy the
archive off the Pi**: a backup on the same SSD does not survive that SSD. Keep `.env` with it. To restore: stop the app, extract the
archive into the volume, start the app. The database holds rooms, device names and assignments, scenes, favourites, tasks, people and
their calendar settings (encrypted), the location, and the **credential hashes** (restoring an old backup restores old logins and revokes
any made since).

**Rotate secrets.**
- Admin password: create a new hash, edit `.env`, `docker compose -f docker-compose.pi.yml up -d` (existing sessions stay valid). To sign
  everyone out, remove phones and displays in Settings.
- Agent token: create a new one in the app, update `/etc/homedisplay/agent.json`, restart the agent, then revoke the old one.
- Calendar password: create a new app-specific password, update it in the app (Settings → My calendar), then revoke the old one at
  account.apple.com.
- Cloudflare token: roll it in the Cloudflare dashboard, update `.env`, restart the tunnel.

**Keep the Pi updated:** `sudo apt update && sudo apt upgrade` regularly (or enable unattended upgrades), and restart after kernel updates.

**Resources.** The app runs with a 60 % heap cap and the serial garbage collector. It idles at a few hundred MB.

## 9. Security checklist

- [ ] The router forwards **no ports** to the Pi. The app listens only on `127.0.0.1`, so the home network cannot reach it directly either.
- [ ] SSH on the Pi uses keys only (no password login), and the Pi's OS is kept updated.
- [ ] `.env` is not in a repository or a chat; permissions `0600`.
- [ ] `APP_PASSWORD_HASH` is set (not the plain `APP_PASSWORD`), and the password is long. It is the **admin's** password; other people's are
      set in the app (8 characters at least), so choose them well.
- [ ] `APP_SECRET_KEY` is set to a long random value and is backed up with `.env`.
- [ ] `docker-compose.local.yml` contains no real secrets (calendar login lives in `.env`).
- [ ] The calendar uses an app-specific password, which can be revoked on its own.
- [ ] The Anthropic and Gemini keys live only in `.env`; they are spending credentials, so set spend limits on them. The Gemini key must be
      on a **paid** plan, because a microphone in a home must not be on the free tier that may train on requests.
- [ ] Backups of the `data` volume exist and are stored **off the Pi**.
- [ ] The agent token exists only in `/etc/homedisplay/agent.json` (or the unit's override) on the Pi.
- [ ] **Tailscale:** devices are removed from the tailnet when they are lost or sold; consider key expiry and access rules.
- [ ] **Cloudflare:** the tunnel token is kept secret; the login page is public, so rely on a strong admin password.
- [ ] Each tablet is locked to the kiosk app, and a lost tablet's display is removed in Settings.
- [ ] Unused phones and displays are removed in Settings.

## 10. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Compose refuses to start: `set APP_PASSWORD_HASH in .env` | A required variable is missing | Fill in `.env`. |
| `app` exits at start with "Set APP_PASSWORD_HASH (bcrypt)…" | The hash is empty, or `$` characters were mangled | Quote the value with single quotes in `.env`. |
| Login always fails with the right password | The hash was truncated by shell expansion of `$` | Re-create the hash; keep single quotes. |
| Everything works except changes: saving anything gives `403` "Cross-origin request rejected" | The proxy changed the `Host` header (a Cloudflare host-header override, or another proxy) | Remove the override so the original host reaches the app. |
| The microphone button is off, or the browser refuses the microphone | The page is on `http://` | Use the HTTPS address from section 5, not the Pi's local address. |
| Site works, but screens never update live | Something buffers `/api/stream` | Check that the tunnel or proxy streams responses; test a change on two screens. |
| Everyone is locked out after a few wrong tries | The proxy hides client addresses, so all requests look like one | Make sure it sends `X-Forwarded-For` (the app trusts it from local and private addresses). |
| Lights show "Home agent offline" | The agent is not connected | `journalctl -u homedisplay-agent`; check the token, `backendUrl` (`http://127.0.0.1:8080`) and that the app is up. |
| No lights found by the agent | LAN Control off, other subnet, multicast blocked | See [`agent/README.md`](../agent/README.md#10-troubleshooting). |
| The agent logs `permission denied` reading its config | `DynamicUser` cannot read a root-only file | Make the file `0644` or use an `Environment=` override (section 6). |
| A tablet shows an error page, or never connects (Tailscale) | The tablet's VPN is off | Turn on Always-on VPN for Tailscale on the tablet. |
| A tablet shows the pairing code after every restart | The kiosk app does not keep its cookies | Turn off any "clear data on exit" setting. |
| The wake word never loads on a tablet | An old web view | Update Android System WebView and the kiosk app. |
| The Pi throttles or restarts | Weak power supply, or no cooling | Use the official 27 W supply and an active cooler; check `vcgencmd get_throttled`. |
| `docker compose up --build` is very slow or fails while downloading | First build, or a registry limit | Retry; the Maven and npm layers are cached afterwards. |
| `/` shows the wall layout on a phone | That browser holds a display cookie | Sign in at `/login`. |

## 11. What has been verified

Verified: the production image builds and runs on a laptop; the local Compose setup works end to end, including a real home agent against
real Yeelight lights (a Monitor Light Bar Pro) and a real iCloud calendar; `docker compose config` accepts `docker-compose.pi.yml` (with
and without the Cloudflare profile).

**Not verified** (it needs hardware or accounts that were not available): everything on the new plan. That is: the image building and
running on a Raspberry Pi 5, the agent as a service on that Pi talking to the app on `127.0.0.1`, Tailscale Serve or a Cloudflare Tunnel in
front of the app (including live updates and the voice WebSocket through them), a tablet running the wall page in a kiosk app, and the
wake word and microphone on a tablet. The commands for Tailscale and Cloudflare are the usual ones, not tested here; check their current
documentation.

---

## Appendix A: a server with its own domain (Caddy)

The previous plan, still supported: the app on a server that is reachable directly from the internet (a small VPS, or a machine at
home with ports 80 and 443 forwarded), with **Caddy** providing HTTPS.

**What you need:** a Linux server with Docker, a **domain name** pointing at it (`A`, and `AAAA` if it has IPv6), and ports **80 and 443**
open (Caddy needs both to obtain and renew certificates).

```sh
cp .env.example .env
$EDITOR .env                       # DOMAIN, APP_PASSWORD_HASH, optional calendar
docker compose up -d --build       # docker-compose.yml: the app plus Caddy
docker compose ps
docker compose logs -f             # watch Caddy get the certificate
```

Then open `https://<DOMAIN>`, sign in, and set the location in Settings. `DOMAIN` is required in this setup.

| Name | What | Note |
|---|---|---|
| `app` | The image built from the `Dockerfile` | No published ports; only Caddy reaches it |
| `caddy` | `caddy:2` | Publishes 80 and 443, depends on `app` |
| volume `data` | The SQLite database | **Back this up** |
| volume `caddy_data` | Certificates and ACME account | Keep it; losing it can hit the certificate authority's rate limits |

`deploy/Caddyfile` is short:

```
{$DOMAIN} {
    encode zstd gzip
    reverse_proxy app:8080 { flush_interval -1 }
    header { …security headers… }
}
```

- **Automatic HTTPS:** naming the domain is all Caddy needs; it obtains and renews a Let's Encrypt certificate and redirects HTTP to HTTPS.
- **`flush_interval -1`** sends bytes to the client immediately; without it Caddy may buffer the Server-Sent Events stream and live updates
  stop. WebSocket upgrades are proxied without extra configuration.
- **Forwarded headers:** Caddy adds `X-Forwarded-*`. Tomcat trusts them only from private addresses (the Docker network), so the login rate
  limit sees real client addresses.
- **Security headers** set here (the app sets none itself): `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, and a `Content-Security-Policy` that allows only same-origin scripts, styles and
  connections. If you add an external script, font or image host to the frontend, the CSP must allow it.
- The home agent connects to `https://<DOMAIN>` (outbound WSS) instead of `http://127.0.0.1:8080`, and can run on any machine on the home
  network, not necessarily the server.
- **Firewall:** allow only 22 (SSH, key login only), 80 and 443.

## Appendix B: a Raspberry Pi as a display

The previous plan for the wall display: a Raspberry Pi 4 with a touchscreen running Chromium in kiosk mode. It still works, and is the way
to use a Pi (or any Linux computer with a screen) as a display instead of a tablet.

- `deploy/pi/kiosk.sh <base-url>` starts Chromium full screen: it hides the mouse pointer (`unclutter`, if installed), turns off X11 screen
  blanking (`xset`, if present), and runs `chromium-browser --kiosk` with no error dialogs, info bars or swipe gestures, and with
  `--use-fake-ui-for-media-stream --autoplay-policy=no-user-gesture-required` so the microphone and sound need no tap. It opens
  `offline.html?to=<base-url>/wall`, a local page that checks `<base-url>/api/health` every 5 seconds and forwards to the wall as soon as
  the server answers, which covers a Pi that boots before the network.
- Install with `sudo apt install -y chromium-browser unclutter`, copy `kiosk.sh` and `offline.html` into one folder, try it by hand, then
  start it from the desktop session's autostart (`~/.config/labwc/autostart` on Wayland, `~/.config/lxsession/LXDE-pi/autostart` on X11),
  with desktop auto-login on. Settings differ between Pi OS versions; on Wayland `xset` does nothing, so disable blanking in `raspi-config`.
- The kiosk flags allow the microphone for every site Chromium opens, so use them only on a dedicated screen.
- The wall page draws **no blur** for exactly this hardware: a Raspberry Pi renders it slowly. The wake word runs three small neural
  networks every 80 ms in the browser, which will cost a Pi several times what it costs a laptop (**not measured**). A speakerphone with
  echo cancellation is the plan for hardware here (see [`VOICE-CONTROL.md`](../VOICE-CONTROL.md)).
- SD cards wear out from frequent writes: limit journald size (`SystemMaxUse=` in `/etc/systemd/journald.conf`) or log to RAM.
- None of this has been tried on a real Pi with the touchscreen.
