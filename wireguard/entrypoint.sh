#!/bin/sh
# Brings up the securegate WireGuard interface (default sgwg0, UDP WG_PORT) in the host network
# namespace, NAT for clients (WG_SUBNET) and loads all clients from /data/wireguard/clients.
# On stop: removes the interface and the firewall rules (keys/clients stay in the volume).
. /usr/local/bin/wg-common.sh

idle() { echo "wireguard: $1. Idle."; trap 'exit 0' TERM INT; while :; do sleep 3600 & wait $!; done; }
# WG_MODE=uplink: WireGuard CLIENT of the main server (proxy-only install: telemt -> tunnel -> main)
case "$(echo "${WG_MODE:-server}" | tr 'A-Z' 'a-z')" in
  uplink|client) exec /usr/local/bin/wg-uplink.sh ;;
  server|"") ks_down_all; ipt_purge_tag "$RELAY_TAG" ;;   # leftovers of a previous WG_MODE=uplink
  *) echo "wireguard: unknown WG_MODE=$WG_MODE (server | uplink)"; exit 1 ;;
esac
if is_true "${PROXY:-false}"; then
  idle "proxy mode (PROXY=true): WG_PORT/udp is relayed to the main server ${DOMAIN_TO_PROXY}, clients are added there"
fi
is_true "${WG_ENABLE:-true}" || idle "disabled (WG_ENABLE=false)"
[ "$WG_PORT" -gt 0 ] 2>/dev/null || idle "disabled (WG_PORT=0)"

umask 077
mkdir -p "$WGDIR/server" "$WGDIR/clients"
if [ ! -s "$WGDIR/server/private.key" ]; then
  wg genkey > "$WGDIR/server/private.key"
  echo "wireguard: generated new server key in data/wireguard/server/"
fi
wg pubkey < "$WGDIR/server/private.key" > "$WGDIR/server/public.key"

# ---- firewall rules (tagged, inserted on top so ufw/docker policies do not block them) ----
TAG=securegate-wg
rules() {
  cat <<R
-t nat POSTROUTING -s $SUBNET ! -d $SUBNET -j MASQUERADE
FORWARD -i $IFACE -j ACCEPT
FORWARD -o $IFACE -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
-t mangle FORWARD -i $IFACE -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
-t mangle FORWARD -o $IFACE -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
R
}
ipt_each() { ipt_apply "$1" "$TAG" rules; }   # $1 = -I (add if missing) | -D (delete); helpers in wg-common.sh
cleanup() {
  ipt_each -D
  ip link del "$IFACE" 2>/dev/null || true
  [ -n "$WGGO" ] && kill "$WGGO" 2>/dev/null
}

# ---- interface ----
WGGO=""
iface_down() {
  ip link del "$IFACE" 2>/dev/null || true
  [ -n "$WGGO" ] && kill "$WGGO" 2>/dev/null; WGGO=""
}
iface_up() { # (re)create the interface with the server key and all clients
  ip link del "$IFACE" 2>/dev/null || true   # stale interface of a previous run
  if ip link add "$IFACE" type wireguard 2>/dev/null || { modprobe wireguard 2>/dev/null && ip link add "$IFACE" type wireguard; }; then
    echo "wireguard: kernel module"
  else
    echo "wireguard: kernel WireGuard not available - using wireguard-go (userspace)"
    WG_GO_FOREGROUND=1 wireguard-go -f "$IFACE" &
    WGGO=$!
    i=0; while ! ip link show "$IFACE" >/dev/null 2>&1; do i=$((i+1)); [ $i -gt 50 ] && { echo "wireguard: wireguard-go failed"; return 1; }; sleep 0.2; done
  fi
  wg set "$IFACE" listen-port "$WG_PORT" private-key "$WGDIR/server/private.key"
  ip address add "$SERVER_IP/$PREFIX" dev "$IFACE"
  ip link set "$IFACE" mtu "$WG_MTU" up
  n=0
  for d in "$WGDIR"/clients/*/; do
    [ -f "$d/public.key" ] || continue
    apply_peer "$(basename "$d")" && n=$((n+1))
  done
}
cleanup() {
  ipt_each -D
  iface_down
}
iface_up || exit 1
trap 'cleanup; exit 0' TERM INT

echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || sysctl -qw net.ipv4.ip_forward=1 || echo "wireguard: WARNING cannot enable ip_forward"
ipt_each -D; ipt_each -I
echo "wireguard: ready iface=$IFACE port=$WG_PORT/udp subnet=$SUBNET server=$SERVER_IP clients=$n pubkey=$(cat "$WGDIR/server/public.key")"
echo "wireguard: add a client:  ./sgctl wg add <name>"

# ---- WG_AUTO_FIX: watch the tunnels to the listed clients (proxy uplinks), re-create the interface
# when they are dead. Anti-loop: the main reacts only after WG_AUTO_FIX_FAILS (default 6 = ~3 min,
# twice as long as the proxy side), only when the peer's last handshake is stale too, then waits a
# grace period for the tunnel (doubling after every unsuccessful restart, up to 30 min) - so the two
# sides never restart in turn without giving the other one time to reconnect.
AF=false; is_true "${WG_AUTO_FIX:-false}" && AF=true
AF_INT=${WG_AUTO_FIX_INTERVAL:-30}; AF_FAILS=${WG_AUTO_FIX_FAILS:-6}; AF_GRACE0=${WG_AUTO_FIX_GRACE:-180}
AF_PEERS=$(echo "${WG_AUTO_FIX_PEERS:-}" | tr ',;' '  ')
if $AF; then
  if [ -z "$AF_PEERS" ]; then
    echo "wireguard: WG_AUTO_FIX=true but WG_AUTO_FIX_PEERS is empty (client names of the proxies, e.g. proxy1) - auto-fix inactive"; AF=false
  else
    echo "wireguard: auto-fix on: peers=[$AF_PEERS] check every ${AF_INT}s, restart after $AF_FAILS failed checks, grace ${AF_GRACE0}s"
  fi
fi
# 0 = peer alive: answers ping through the tunnel or handshake younger than 180s
peer_alive() {
  d="$WGDIR/clients/$1"; [ -f "$d/address" ] || return 0   # unknown name: ignore
  ping -c1 -W3 "$(cat "$d/address")" >/dev/null 2>&1 && return 0
  hs=$(wg show "$IFACE" latest-handshakes 2>/dev/null | awk -v k="$(cat "$d/public.key")" '$1==k{print $2}')
  [ -n "$hs" ] && [ "$hs" -gt 0 ] && [ $(( $(date +%s) - hs )) -lt 180 ]
}
fails=0; grace=$AF_GRACE0; hold_until=0; restarts=0; tick=0

# keep the rules in place (e.g. after a firewall reload) and the interface alive
while :; do
  if $AF; then sleep "$AF_INT" & else sleep 60 & fi
  wait $!
  tick=$((tick + 1))
  if ! $AF || [ $((tick * AF_INT)) -ge 60 ]; then tick=0; ipt_each -I; fi
  ip link show "$IFACE" >/dev/null 2>&1 || { echo "wireguard: interface disappeared - restarting"; cleanup; exit 1; }
  $AF || continue
  now=$(date +%s)
  dead=""
  for p in $AF_PEERS; do peer_alive "$p" || dead="$dead $p"; done
  if [ -z "$dead" ]; then
    [ $fails -gt 0 ] || [ $restarts -gt 0 ] && echo "wireguard: auto-fix: tunnels OK again"
    fails=0; restarts=0; grace=$AF_GRACE0; hold_until=0
    continue
  fi
  [ "$now" -lt "$hold_until" ] && continue          # waiting for the tunnel after our restart
  fails=$((fails + 1))
  echo "wireguard: auto-fix: no traffic with:$dead (check $fails/$AF_FAILS)"
  [ $fails -lt "$AF_FAILS" ] && continue
  restarts=$((restarts + 1))
  echo "wireguard: auto-fix: re-creating $IFACE (restart #$restarts), then waiting ${grace}s for the tunnel"
  iface_down; iface_up || { echo "wireguard: auto-fix: interface re-create failed"; cleanup; exit 1; }
  fails=0; hold_until=$(( $(date +%s) + grace ))
  grace=$((grace * 2)); [ $grace -gt 1800 ] && grace=1800
done
