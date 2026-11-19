# shared by entrypoint.sh and wgctl (installed as /usr/local/bin/wg-common.sh)
WGDIR=${WG_DIR:-/data/wireguard}
IFACE=${WG_INTERFACE:-sgwg0}
WG_PORT=${WG_PORT:-51820}
WG_SUBNET=${WG_SUBNET:-10.17.0.0/24}
WG_MTU=${WG_MTU:-1420}
WG_KEEPALIVE=${WG_KEEPALIVE:-25}
WG_ALLOWED_IPS=${WG_ALLOWED_IPS:-0.0.0.0/0}
WG_DNS=${WG_DNS:-${SSTP_DNS:-8.8.8.8,8.8.4.4}}

is_true() { case "$(echo "$1" | tr 'A-Z' 'a-z')" in 1|true|yes|on) return 0;; esac; return 1; }

ip2int() { # a.b.c.d -> integer
  local IFS=.; set -- $1
  echo $(( ($1 << 24) + ($2 << 16) + ($3 << 8) + $4 ))
}
int2ip() { echo "$(( ($1 >> 24) & 255 )).$(( ($1 >> 16) & 255 )).$(( ($1 >> 8) & 255 )).$(( $1 & 255 ))"; }

PREFIX=${WG_SUBNET#*/}
NETINT=$(( $(ip2int "${WG_SUBNET%/*}") & ~((1 << (32 - PREFIX)) - 1) & 0xFFFFFFFF ))
NET=$(int2ip "$NETINT")
SUBNET="$NET/$PREFIX"
SERVER_IP=$(int2ip $((NETINT + 1)))
LAST_INT=$((NETINT + (1 << (32 - PREFIX)) - 2))

# client config for NAME with endpoint host $2
client_conf() {
  local d="$WGDIR/clients/$1" host="$2"
  case "$host" in *:*:*) host="[$host]";; esac
  cat <<CONF
[Interface]
PrivateKey = $(cat "$d/private.key")
Address = $(cat "$d/address")/32
DNS = $(echo "$WG_DNS" | sed 's/ //g; s/,/, /g')
MTU = $WG_MTU

[Peer]
PublicKey = $(cat "$WGDIR/server/public.key")
PresharedKey = $(cat "$d/psk")
Endpoint = $host:$WG_PORT
AllowedIPs = $WG_ALLOWED_IPS
PersistentKeepalive = $WG_KEEPALIVE
CONF
}

apply_peer() { # add / update peer NAME on the running interface
  local d="$WGDIR/clients/$1"
  ip link show "$IFACE" >/dev/null 2>&1 || return 0
  wg set "$IFACE" peer "$(cat "$d/public.key")" preshared-key "$d/psk" allowed-ips "$(cat "$d/address")/32"
}

# remove the uplink kill-switch rules (wg-uplink.sh) - when the container runs as a server or the
# kill switch is turned off
ks_down_all() {
  for ipt in iptables ip6tables; do
    $ipt -w -S OUTPUT 2>/dev/null | grep -- "--comment securegate-uplink-ks" | sed 's/^-A /-D /' | while read -r r; do
      # shellcheck disable=SC2086
      eval "$ipt -w $r" 2>/dev/null || true
    done
  done
}
