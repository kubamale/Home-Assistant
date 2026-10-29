#!/bin/sh
# Starts Chromium full screen on the door display. Run it from the desktop session's autostart.
# Usage: kiosk.sh https://home.example.com
set -eu
BASE="${1:?usage: kiosk.sh https://your-domain}"
HERE="$(cd "$(dirname "$0")" && pwd)"

# Hide the mouse pointer and keep the screen from blanking on X11. Ignored if the tools are missing.
command -v unclutter >/dev/null 2>&1 && unclutter -idle 0.5 -root &
command -v xset >/dev/null 2>&1 && { xset s off; xset -dpms; xset s noblank; } || true

# The door screen listens for its wake word, and nobody is there to tap: let it use the microphone without asking, and let
# the page make sound without a tap. (Only for a dedicated kiosk: this allows every site that Chromium opens.)
# Voice is turned on once, by tapping the microphone pill on the screen; after that it starts by itself.

# Start from the local offline page: it forwards to the app as soon as the server answers.
exec chromium-browser \
  --kiosk \
  --noerrdialogs --disable-infobars --no-first-run \
  --disable-session-crashed-bubble --disable-features=TranslateUI \
  --overscroll-history-navigation=0 --check-for-update-interval=31536000 \
  --use-fake-ui-for-media-stream --autoplay-policy=no-user-gesture-required \
  "file://$HERE/offline.html?to=$BASE/wall"
