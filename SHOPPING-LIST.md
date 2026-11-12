# What to buy

Rewritten 2026-11-12 for the current plan: a **Raspberry Pi 5 at home as the server**, and **Samsung (or other Android) tablets as the wall
displays**, each running the wall page in a kiosk browser app. The earlier version of this list (a Pi 4 with a touchscreen and a
speakerphone at the door) is superseded.

**I did not look up any prices for this version**, and I have not tried any of this hardware. Check current prices and stock in the shop you
use. Where an earlier document gave a price for something that is still on the list, it is repeated and marked *(earlier estimate)*.

## 1. The server: Raspberry Pi 5

| # | Item | What for | Notes |
|---|---|---|---|
| 1 | **Raspberry Pi 5, 4 GB or 8 GB** | Runs the app (in Docker) and the home agent, and the tunnel or Tailscale | The app and a Java runtime are the main load; 4 GB is enough, 8 GB leaves room. The Pi never draws a screen |
| 2 | **Official 27 W USB-C power supply** | Powers it | A weak supply makes a Pi 5 throttle or restart |
| 3 | **Active cooler, or a case with a fan** | Keeps it from throttling | It runs all day |
| 4 | **Storage that survives writes** | The system, Docker and the database | Best: an **SSD**, either NVMe on a Pi 5 M.2 adapter, or a USB SSD. A good high-endurance microSD card works, but wears out first |
| 5 | **Ethernet cable** | Connects it to the router | More reliable than Wi-Fi for something always on; the Pi must be on the same network as the lights |
| 6 | **A case** | Protects it | Choose one that fits the cooler and, if you use it, the M.2 adapter |

You also need a computer to flash the system onto the card or SSD (Raspberry Pi Imager), and you already own the Yeelight light bar.

## 2. The wall displays: tablets

You need **one tablet per wall display**. Buy one first and try it before buying more.

| # | Item | What for | Notes |
|---|---|---|---|
| 1 | **Android tablet, about 10–11 inch** | The display, the microphone and the speaker, and the wake word | See "Choosing a tablet" below |
| 2 | **A wall mount or frame for it** | Holds it on the wall | Must fit that tablet's exact size and let you reach the charging port. Check before you buy both |
| 3 | **A charger and a cable that stay connected** | Keeps it powered | Prefer a cable with a right-angle plug so it sits flat on the wall; many Samsung tablets have a "Protect battery" option that stops charging at 85 %, which suits a tablet that is always plugged in |
| 4 | **A kiosk browser app** | Shows the wall page full screen and locks the tablet to it | One widely used example is Fully Kiosk Browser; its full features need a paid licence, so check the terms *(not tried with this project)* |

### Choosing a tablet

- **Recent Android with security updates still coming.** The wall page needs a current browser engine (WebAssembly with SIMD,
  AudioWorklet and the microphone, for the wake word). An old tablet may not run it.
- **A decent processor and 3 GB of RAM or more.** The wake word runs three small neural networks several times a second.
- **16:10 or similar.** The wall layout was designed for 1280×800 and scales with the screen height, so most sizes work, but a tablet's
  shape is a guess until you try one.
- **An OLED screen can burn in** if it shows a mostly fixed layout all day; the kiosk app can turn the screen off at night.
- **Microphone and speaker quality.** The tablet's own are used. Try it before mounting it: say "Hey Jarvis" from across the room while an
  answer plays.
- **A USB-C port you can reach** when it is mounted.

## 3. Worth buying too (cheap, saves trouble)

| Item | Why |
|---|---|
| **A spare microSD card or a second SSD** | To keep a copy of the server's data and to swap quickly if one fails |
| **A multi-socket or flat plug adapter** | Each tablet and the Pi need an outlet |
| **A short Ethernet cable and a cable clip set** | The Pi sits near the router; the tablets' cables should not hang loose on the wall |
| **A small uninterruptible power supply (UPS) for the Pi** | A power cut while the database is being written can corrupt it. Optional, but worth thinking about for a machine you cannot easily reach |

## 4. Later (not needed now)

- **A PIR motion sensor** to wake a screen when you walk past (earlier estimate €3–5 plus wires), though a tablet has its own sleep and wake
  options.
- **A Zigbee coordinator**, plugged into a Pi USB port, if you add Zigbee devices.
- **A USB speakerphone with echo cancellation**, only if a tablet's own microphone and speaker turn out to echo badly. See
  [`VOICE-CONTROL.md`](VOICE-CONTROL.md).

## 5. Running costs (not hardware)

| What | Cost |
|---|---|
| **Reaching the server from outside** | **Tailscale**: a free personal plan exists (check its limits). **Cloudflare Tunnel**: free, but you need a **domain name** (about €10–15 a year, earlier estimate) on Cloudflare. The choice is not made yet; see [`deploy/README.md`](deploy/README.md) section 5 |
| **A server (VPS)** | **Not needed any more**: the Pi is the server. (The earlier plan had about €4–6 a month for a VPS) |
| **Gemini** (hearing and speaking) and **Claude** (understanding) | Small per-use costs; use a **paid** Gemini key (the free tier may use your voice to improve its products) and set a spending limit on both keys |
| **The kiosk app licence** | Depends on the app you choose; check before buying |

## Why the Pi 5 and not the Pi 4

The Pi is now a server, not a display. It runs a Java application, a database and a small Go program, all the time, and it will also
run Docker builds when you update. A Pi 5 is faster at all of that, and the faster processor is what you want for update builds. A Pi 4
would probably run it too, but the Pi 5 is the plan; I have not tried either.

## Before you order, check

- [ ] **Where the Pi goes.** Near the router if you want Ethernet, with ventilation, and where the lights' Wi-Fi network reaches it (it
      must be on the **same network as the lights**).
- [ ] **How you will power the Pi** safely (the right supply, and ideally a UPS).
- [ ] **Where each tablet hangs.** An outlet nearby, good Wi-Fi at that spot, and a place for the microphone to hear you (away from a door
      that slams).
- [ ] **How each tablet is held.** The mount fits that tablet's size, and the charging port stays reachable.
- [ ] **Which way you will reach the server from outside** (Tailscale or Cloudflare Tunnel), and for Cloudflare, that you have a domain.
- [ ] **That a tablet runs the wall page** before you buy several: open it in the tablet's browser, pair it, and try the wake word.
