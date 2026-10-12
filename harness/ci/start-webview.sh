#!/bin/bash
# Starts the nodriver WebView sidecar natively on a GitHub Actions runner: no Docker, no /dev/dri.
# Runner images ship Google Chrome stable and (usually) Xvfb; this installs Xvfb if missing and the
# one Python dependency (websockets), then launches Xvfb + bridge.py in the background.
#
#   start-webview.sh <socket-path>
#
# Writes the bridge's PID to <socket-path>.pid and its log to <socket-path>.log. Waits for the
# socket file to appear before returning.
set -euo pipefail
SOCK=$1
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)

if ! command -v Xvfb >/dev/null 2>&1; then
  sudo apt-get update -qq && sudo apt-get install -y -qq xvfb fonts-noto fonts-noto-cjk >/dev/null
fi
python3 -c "import websockets" 2>/dev/null || pip install --quiet websockets==15.0.1

rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
Xvfb :99 -screen 0 1366x900x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
echo $! >"$SOCK.xvfb.pid"
sleep 1

CHROME_BIN=$(command -v google-chrome-stable || command -v google-chrome || command -v chromium-browser || command -v chromium)
[ -n "$CHROME_BIN" ] || { echo "no Chrome found on this runner" >&2; exit 1; }

mkdir -p "$(dirname "$SOCK")"
rm -f "$SOCK"
export DISPLAY=:99
export CHROME_BIN
export EXT_RUN_WV_NO_GPU=1   # no real GPU on the runner; see bridge.py
# GitHub-hosted runners run as root inside a container image where Chrome's own sandbox setup is
# blocked; this is the one place --no-sandbox is still unavoidable (see bridge.py's comment). The
# recheck node image runs rootless and leaves Chrome's sandbox on. this used to
# hardcode =0 unconditionally, which would also force the node's run to --no-sandbox if this
# script were ever reused there — respect an explicit override (e.g. a caller that already knows
# its container can run Chrome sandboxed) and only default to 0 for this GitHub-hosted path.
export EXT_RUN_WV_CHROME_SANDBOX=${EXT_RUN_WV_CHROME_SANDBOX:-0}
export TZ=${EXT_RUN_TZ:-UTC}   # configurable; defaults to UTC rather than any specific locale/region
nohup python3 -u "$HERE/../webview/nodriver/bridge.py" "$SOCK" >"$SOCK.log" 2>&1 &
echo $! >"$SOCK.pid"

for _ in $(seq 100); do
  [ -S "$SOCK" ] && { echo "[start-webview] socket ready: $SOCK"; exit 0; }
  sleep 0.2
done
echo "[start-webview] socket never appeared; log:" >&2
cat "$SOCK.log" >&2 || true
exit 1
