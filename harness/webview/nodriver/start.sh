#!/bin/sh
# Xvfb for headed Chrome, then the bridge. A restarted container keeps /tmp, so drop a stale X lock first.
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
Xvfb :99 -screen 0 1366x900x24 -nolisten tcp >/dev/null 2>&1 &
exec python3 -u /bridge/bridge.py "$@"
