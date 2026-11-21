#!/bin/sh
# Xvfb for headed Chrome, then the bridge. A restarted container keeps /tmp, so drop a stale X lock first.
# The display number comes from ext-run.sh (99 for the shared copy, another for a dev copy): X's abstract
# socket lives in the network namespace, which every sidecar on one VPN exit shares.
d=${EXT_RUN_WV_DISPLAY:-99}
export DISPLAY=:$d
rm -f /tmp/.X$d-lock /tmp/.X11-unix/X$d
Xvfb :$d -screen 0 1366x900x24 -nolisten tcp >/dev/null 2>&1 &
exec python3 -u /bridge/bridge.py "$@"
