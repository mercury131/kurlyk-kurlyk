#!/bin/sh
# Brings up the securegate WireGuard interface (default sgwg0, UDP WG_PORT) in the host network
# namespace, NAT for clients (WG_SUBNET) and loads all clients from /data/wireguard/clients.
# On stop: removes the interface and the firewall rules (keys/clients stay in the volume).
. /usr/local/bin/wg-common.sh

idle() { echo "wireguard: $1. Idle."; trap 'exit 0' TERM INT; while :; do sleep 3600 & wait $!; done; }
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
ipt_each() { # $1 = -I (add if missing) | -D (delete)
  op=$1
  rules | while read -r l; do
    # shellcheck disable=SC2086
    set -- $l
    if [ "$1" = "-t" ]; then t="-t $2"; shift 2; else t=""; fi
    chain=$1; shift
    if [ "$op" = "-I" ]; then
      iptables -w $t -C "$chain" "$@" -m comment --comment $TAG 2>/dev/null || \
        iptables -w $t -I "$chain" 1 "$@" -m comment --comment $TAG || echo "wireguard: iptables failed: $t $chain $*"
    else
      while iptables -w $t -D "$chain" "$@" -m comment --comment $TAG 2>/dev/null; do :; done
    fi
  done
}
cleanup() {
  ipt_each -D
  ip link del "$IFACE" 2>/dev/null || true
  [ -n "$WGGO" ] && kill "$WGGO" 2>/dev/null
}

# ---- interface ----
ip link del "$IFACE" 2>/dev/null || true   # stale interface of a previous run
WGGO=""
if ip link add "$IFACE" type wireguard 2>/dev/null || { modprobe wireguard 2>/dev/null && ip link add "$IFACE" type wireguard; }; then
  echo "wireguard: kernel module"
else
  echo "wireguard: kernel WireGuard not available - using wireguard-go (userspace)"
  WG_GO_FOREGROUND=1 wireguard-go -f "$IFACE" &
  WGGO=$!
  i=0; while ! ip link show "$IFACE" >/dev/null 2>&1; do i=$((i+1)); [ $i -gt 50 ] && { echo "wireguard: wireguard-go failed"; exit 1; }; sleep 0.2; done
fi
trap 'cleanup; exit 0' TERM INT
wg set "$IFACE" listen-port "$WG_PORT" private-key "$WGDIR/server/private.key"
ip address add "$SERVER_IP/$PREFIX" dev "$IFACE"
ip link set "$IFACE" mtu "$WG_MTU" up

n=0
for d in "$WGDIR"/clients/*/; do
  [ -f "$d/public.key" ] || continue
  apply_peer "$(basename "$d")" && n=$((n+1))
done

echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || sysctl -qw net.ipv4.ip_forward=1 || echo "wireguard: WARNING cannot enable ip_forward"
ipt_each -D; ipt_each -I
echo "wireguard: ready iface=$IFACE port=$WG_PORT/udp subnet=$SUBNET server=$SERVER_IP clients=$n pubkey=$(cat "$WGDIR/server/public.key")"
echo "wireguard: add a client:  ./sgctl wg add <name>"

# keep the rules in place (e.g. after a firewall reload) and the interface alive
while :; do
  sleep 60 & wait $!
  ipt_each -I
  ip link show "$IFACE" >/dev/null 2>&1 || { echo "wireguard: interface disappeared - restarting"; cleanup; exit 1; }
done
