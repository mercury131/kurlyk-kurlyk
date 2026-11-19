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

# ---- iptables helpers (entrypoint.sh: server rules, uplink.sh: relay rules) ----
# iptables backends to use. The kernel applies BOTH the nf_tables and the legacy rule sets and drops a
# packet if either drops it; on hosts whose ufw/docker/WG scripts use iptables-legacy (Ubuntu 20.04,
# older Debian/CentOS) the FORWARD DROP policy lives in the legacy tables, so our rules go there too.
ipt_bins() { # $1 = -I (backends in use) | -D (every loaded backend)
  echo iptables
  iptables -V 2>/dev/null | grep -q legacy && return 0
  command -v iptables-legacy >/dev/null 2>&1 || return 0
  grep -q . /proc/net/ip_tables_names 2>/dev/null || return 0
  if [ "$1" = "-D" ]; then echo iptables-legacy; return 0; fi
  iptables-legacy-save 2>/dev/null | grep -qE '^-A |^:[A-Z]+ (DROP|REJECT)' && echo iptables-legacy
  return 0
}
# ipt_apply -I|-D TAG RULES_FN: add (on top, if missing) / delete the rules printed by RULES_FN, one per
# line: "[-t table] CHAIN match... -j TARGET", tagged with comment TAG
ipt_apply() {
  _op=$1; _tag=$2; _fn=$3
  for _ipt in $(ipt_bins "$_op"); do
    $_fn | while read -r l; do
      [ -n "$l" ] || continue
      # shellcheck disable=SC2086
      set -- $l
      if [ "$1" = "-t" ]; then t="-t $2"; shift 2; else t=""; fi
      chain=$1; shift
      if [ "$_op" = "-I" ]; then
        $_ipt -w $t -C "$chain" "$@" -m comment --comment "$_tag" 2>/dev/null ||
          $_ipt -w $t -I "$chain" 1 "$@" -m comment --comment "$_tag" || echo "wireguard: $_ipt failed: $t $chain $*"
      else
        while $_ipt -w $t -D "$chain" "$@" -m comment --comment "$_tag" 2>/dev/null; do :; done
      fi
    done
  done
}
# delete every rule tagged TAG (filter/nat/mangle, all loaded backends) - whatever its arguments
ipt_purge_tag() {
  for _ipt in $(ipt_bins -D); do
    for _t in filter nat mangle; do
      $_ipt -w -t $_t -S 2>/dev/null | grep -- "--comment $1\( \|$\)" | sed 's/^-A /-D /' | while read -r r; do
        # shellcheck disable=SC2086
        eval "$_ipt -w -t $_t $r" 2>/dev/null || true
      done
    done
  done
}
RELAY_TAG=securegate-uplink-relay
