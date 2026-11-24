#!/bin/bash
set -euo pipefail

export DISPLAY=:1

Xtigervnc :1 -geometry 1280x800x24 -AlwaysShared -rfbport 5901 &
XVNC_PID=$!

websockify --web=/usr/share/novnc 6080 localhost:5901 &
WEBSOCKIFY_PID=$!

python3 /opt/demo-site/server.py 8088 &
SITE_PID=$!

chromium \
  --no-sandbox \
  --disable-gpu \
  --disable-dev-shm-usage \
  --no-default-browser-check \
  --no-first-run \
  --disable-field-trial-config \
  --window-size=1280,800 \
  --remote-debugging-port=9222 \
  --user-data-dir=/tmp/chrome-data \
  http://127.0.0.1:8088/ &
CHROME_PID=$!

until python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:9222/json/version', timeout=1)" 2>/dev/null; do
  if ! kill -0 "$CHROME_PID" 2>/dev/null; then
    echo "chromium exited before DevTools was ready" >&2
    exit 1
  fi
  sleep 0.2
done

chrome-devtools start --logFile=/tmp/chrome-devtools.log &

wait -n "$XVNC_PID" "$WEBSOCKIFY_PID" "$SITE_PID" "$CHROME_PID"
