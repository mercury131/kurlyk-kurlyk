#!/bin/sh
# WG_MODE=uplink - this server is a WireGuard CLIENT of the main server (proxy-only install).
#
# Brings up interface WG_UPLINK_IFACE (default sgup0) from WG_UPLINK_CONF (default
# /data/wireguard/uplink.conf = a client config made on the MAIN server with
#   ./sgctl wg add <name> --via <main IP>   /   ./sgctl wg conf <name> --via <main IP> > uplink.conf).
# The default route of the host is NOT changed: only traffic with the tunnel address as SOURCE is
# routed into the tunnel (ip rule from <addr> -> table WG_UPLINK_TABLE). telemt binds its outgoing
# connections to that address (gateway: MTPROXY_UPSTREAM_IFACE=sgup0), so Telegram traffic leaves
# through the main server, while clients, ACME, SSH etc. keep using the normal route.
# Fail-closed: the table also holds a blackhole default route, so with the tunnel down nothing with
# the tunnel source address goes out directly.
. /usr/local/bin/wg-common.sh

UP_IFACE=${WG_UPLINK_IFACE:-sgup0}
UP_CONF=${WG_UPLINK_CONF:-$WGDIR/uplink.conf}
UP_TABLE=${WG_UPLINK_TABLE:-51821}
UP_PRIO=${WG_UPLINK_RULE_PRIORITY:-5182}

log() { echo "wireguard-uplink: $*"; }
idle() { log "$1. Idle."; trap 'exit 0' TERM INT; while :; do sleep 3600 & wait $!; done; }

if [ ! -s "$UP_CONF" ]; then
  log "no uplink config: put the client config made on the MAIN server into data/wireguard/$(basename "$UP_CONF")"
  log "  main:  ./sgctl wg add proxy1 --via <main IP>  ;  ./sgctl wg conf proxy1 --via <main IP> > uplink.conf"
  log "  here:  ./sgctl wg uplink import uplink.conf"
  # wait for the file instead of exiting (no restart loop)
  trap 'exit 0' TERM INT
  while [ ! -s "$UP_CONF" ]; do sleep 5 & wait $!; done
  log "config appeared - starting"
fi

# value of KEY in [SECTION] of a wg-quick style config (first occurrence, trimmed)
val() {
  awk -v s="$(echo "$1" | tr 'A-Z' 'a-z')" -v k="$(echo "$2" | tr 'A-Z' 'a-z')" '
    { sub(/\r$/, "") }
    /^[ \t]*\[/ { sec = tolower($0); gsub(/[][ \t]/, "", sec); next }
    sec == s && index($0, "=") {
      key = tolower(substr($0, 1, index($0, "=") - 1)); gsub(/[ \t]/, "", key)
      if (key == k) { v = substr($0, index($0, "=") + 1); gsub(/^[ \t]+|[ \t]+$/, "", v); print v; exit }
    }' "$UP_CONF"
}

PRIV=$(val interface privatekey)
ADDR=$(val interface address | tr ',' '\n' | sed 's/ //g; s#/.*##' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | head -n1)
MTU=$(val interface mtu); MTU=${MTU:-$WG_MTU}
PUB=$(val peer publickey)
PSK=$(val peer presharedkey)
EP=$(val peer endpoint)
KA=$(val peer persistentkeepalive); KA=${KA:-25}
[ -n "$PRIV" ] && [ -n "$ADDR" ] && [ -n "$PUB" ] && [ -n "$EP" ] || {
  log "ERROR: $UP_CONF must have [Interface] PrivateKey, Address (IPv4) and [Peer] PublicKey, Endpoint"
  sleep 30; exit 1
}
GW=$(echo "$ADDR" | awk -F. '{print $1"."$2"."$3".1"}')   # server address of the main (WG_SUBNET .1)

keyfile=$(mktemp); pskfile=$(mktemp)
printf '%s\n' "$PRIV" > "$keyfile"; printf '%s\n' "$PSK" > "$pskfile"

rules_up() { # idempotent: policy rule + table (blackhole fallback + default via the tunnel)
  ip rule show | grep -q "from $ADDR lookup $UP_TABLE" || ip rule add from "$ADDR" lookup "$UP_TABLE" priority "$UP_PRIO"
  ip route show table "$UP_TABLE" 2>/dev/null | grep -q '^blackhole default' || ip route add blackhole default table "$UP_TABLE" metric 4000
  if ip link show "$UP_IFACE" >/dev/null 2>&1; then
    ip route show table "$UP_TABLE" 2>/dev/null | grep -q "^default dev $UP_IFACE" || ip route replace default dev "$UP_IFACE" table "$UP_TABLE" metric 10
  fi
}
rules_down() {
  while ip rule del from "$ADDR" lookup "$UP_TABLE" 2>/dev/null; do :; done
  ip route flush table "$UP_TABLE" 2>/dev/null || true
}
# ---- kill switch: this host may reach Telegram ONLY through the tunnel ----
# telemt's `interface` binding silently falls back to the normal route when the tunnel address is
# missing (tunnel down / container stopped) - verified. So outgoing traffic to Telegram networks that
# does not leave via $UP_IFACE is rejected. The rules are installed BEFORE the tunnel and are NOT
# removed when the container stops (stopped tunnel = no Telegram traffic at all, never a direct
# path). They are removed when the container runs in server mode (WG_MODE=server) or with
# WG_UPLINK_KILLSWITCH=false.
KS_TAG=securegate-uplink-ks
KS_V4=${WG_UPLINK_KILLSWITCH_NETS:-149.154.160.0/20,91.108.0.0/16,91.105.192.0/23,95.161.64.0/20,185.76.151.0/24}
KS_V6=${WG_UPLINK_KILLSWITCH_NETS6:-2001:67c:4e8::/48,2001:b28:f23c::/46,2a0a:f280::/32}
ks_rules() { # $1 = iptables|ip6tables, $2 = nets
  for n in $(echo "$2" | tr ',;' '  '); do echo "$1 $n"; done
}
ks_up() {
  { ks_rules iptables "$KS_V4"; ks_rules ip6tables "$KS_V6"; } | while read -r ipt net; do
    $ipt -w -C OUTPUT -d "$net" ! -o "$UP_IFACE" -m comment --comment $KS_TAG -j REJECT 2>/dev/null ||
      $ipt -w -I OUTPUT 1 -d "$net" ! -o "$UP_IFACE" -m comment --comment $KS_TAG -j REJECT 2>/dev/null ||
      { [ "$ipt" = iptables ] && log "WARNING: kill-switch rule failed: $ipt $net"; }
  done
}

WGGO=""
cleanup() {
  ip link del "$UP_IFACE" 2>/dev/null || true
  [ -n "$WGGO" ] && kill "$WGGO" 2>/dev/null
  rules_down
  rm -f "$keyfile" "$pskfile"
}

iface_up() { # create + configure the tunnel interface (rules/blackhole stay in place)
  ip link del "$UP_IFACE" 2>/dev/null || true
  if ip link add "$UP_IFACE" type wireguard 2>/dev/null || { modprobe wireguard 2>/dev/null && ip link add "$UP_IFACE" type wireguard; }; then
    log "kernel module"
  else
    log "kernel WireGuard not available - using wireguard-go (userspace)"
    WG_GO_FOREGROUND=1 wireguard-go -f "$UP_IFACE" &
    WGGO=$!
    i=0; while ! ip link show "$UP_IFACE" >/dev/null 2>&1; do i=$((i+1)); [ $i -gt 50 ] && { log "wireguard-go failed"; return 1; }; sleep 0.2; done
  fi
  wg set "$UP_IFACE" private-key "$keyfile"
  set -- peer "$PUB" endpoint "$EP" allowed-ips 0.0.0.0/0 persistent-keepalive "$KA"
  [ -n "$PSK" ] && set -- "$@" preshared-key "$pskfile"
  if ! wg set "$UP_IFACE" "$@"; then
    log "ERROR: cannot set peer (endpoint $EP not resolvable?)"; return 1
  fi
  ip address add "$ADDR/32" dev "$UP_IFACE"
  ip link set "$UP_IFACE" mtu "$MTU" up
  rules_up
}
iface_down() {
  ip link del "$UP_IFACE" 2>/dev/null || true
  [ -n "$WGGO" ] && kill "$WGGO" 2>/dev/null; WGGO=""
}

# rule + blackhole first: the address never exists without its routing (no direct leak window)
ip link del "$UP_IFACE" 2>/dev/null || true
rules_down; rules_up
if is_true "${WG_UPLINK_KILLSWITCH:-true}"; then
  ks_up; log "kill-switch: Telegram networks reachable only via $UP_IFACE ($KS_V4)"
else
  ks_down_all; log "WARNING: kill-switch disabled (WG_UPLINK_KILLSWITCH=false): if the tunnel is down telemt may connect directly"
fi
trap 'cleanup; exit 0' TERM INT
iface_up || { log "retry in 15s"; cleanup; sleep 15; exit 1; }
log "up iface=$UP_IFACE addr=$ADDR endpoint=$EP table=$UP_TABLE (only traffic from $ADDR uses the tunnel)"

# ---- health + WG_AUTO_FIX ----
# Check = ping of the main's tunnel address ($GW) from our tunnel address. With WG_AUTO_FIX=true the
# interface is re-created after WG_AUTO_FIX_FAILS failed checks in a row (default 3 = ~90 s; the main
# side waits twice as long), then we WAIT a grace period for the handshake (WG_AUTO_FIX_GRACE,
# doubling after every unsuccessful restart, max 30 min) - no restart storm, and the main (if it
# restarts too) gets time to come back before we would try again.
AF=false; is_true "${WG_AUTO_FIX:-false}" && AF=true
AF_INT=${WG_AUTO_FIX_INTERVAL:-30}; AF_FAILS=${WG_AUTO_FIX_FAILS:-3}; AF_GRACE0=${WG_AUTO_FIX_GRACE:-120}
$AF && log "auto-fix on: check every ${AF_INT}s, restart after $AF_FAILS failed checks, grace ${AF_GRACE0}s (doubling, max 1800s)"
fails=0; grace=$AF_GRACE0; hold_until=0; restarts=0
state=""
while :; do
  sleep "$AF_INT" & wait $!
  ip link show "$UP_IFACE" >/dev/null 2>&1 || { log "interface disappeared - restarting"; cleanup; exit 1; }
  rules_up
  is_true "${WG_UPLINK_KILLSWITCH:-true}" && ks_up
  hs=$(wg show "$UP_IFACE" latest-handshakes | awk '{print $2}')
  now=$(date +%s)
  ok=false
  if ping -c1 -W3 -I "$ADDR" "$GW" >/dev/null 2>&1 || ping -c1 -W3 -I "$ADDR" "$GW" >/dev/null 2>&1; then
    ok=true; new="ok"
  elif [ -n "$hs" ] && [ "$hs" -gt 0 ] && [ $((now - hs)) -lt 180 ]; then
    new="handshake ok, but main $GW does not answer ping (traffic stuck?)"
  else
    new="no handshake with $EP for >180s (main down? UDP blocked? keys?)"
    wg set "$UP_IFACE" peer "$PUB" endpoint "$EP" 2>/dev/null || true   # re-resolve a hostname endpoint
  fi
  if [ "$new" != "$state" ]; then log "tunnel: $new"; state=$new; fi
  if $ok; then
    [ $restarts -gt 0 ] && log "auto-fix: tunnel is back after $restarts restart(s)"
    fails=0; restarts=0; grace=$AF_GRACE0; hold_until=0
    continue
  fi
  $AF || continue
  [ "$now" -lt "$hold_until" ] && continue        # waiting for the tunnel after our restart
  fails=$((fails + 1))
  [ $fails -lt "$AF_FAILS" ] && continue
  restarts=$((restarts + 1))
  log "auto-fix: re-creating $UP_IFACE (restart #$restarts), then waiting ${grace}s for the tunnel"
  iface_down
  iface_up || log "auto-fix: re-create failed (will retry)"
  fails=0; state=""; hold_until=$(( $(date +%s) + grace ))
  grace=$((grace * 2)); [ $grace -gt 1800 ] && grace=1800
done
