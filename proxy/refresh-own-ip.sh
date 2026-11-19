#!/bin/bash
# Writes a Squid deny rule for this host's own public IPs (v4 and v6) so nothing sent through the
# proxy can hairpin back to whatever the host's connection exposes (a router page, a reverse
# proxy), and reloads Squid when they change. Run it from a timer every ~15 min.
#
#   refresh-own-ip.sh <compose project, e.g. kmon-home> <own-ip.conf path>
#
# The IPs are looked up from the HOST's own connection on purpose: behind a VPN upstream the
# proxy's exit IP is the VPN's, but the address worth protecting is still the host's.
set -euo pipefail
PROJECT=$1; CONF=$2
v4=$(curl -4 -fsS --max-time 10 https://api.ipify.org 2>/dev/null || true)
v6=$(curl -6 -fsS --max-time 10 https://api6.ipify.org 2>/dev/null || true)
ips=$(printf '%s %s' "$v4" "$v6" | xargs)
# Keep the previous rule if the lookup failed; never write an empty one over a good one.
[ -n "$ips" ] || exit 0
new="acl own_ip dst $ips"$'\n'"http_access deny own_ip"$'\n'
[ -f "$CONF" ] && [ "$(cat "$CONF")" = "$(printf '%s' "$new")" ] && exit 0
printf '%s' "$new" > "$CONF.tmp" && mv "$CONF.tmp" "$CONF"
docker compose -p "$PROJECT" exec -T squid squid -k reconfigure >/dev/null 2>&1 || true
