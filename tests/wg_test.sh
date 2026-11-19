#!/bin/sh
# WireGuard end-to-end test: creates (or reuses) a client with ./sgctl, brings the tunnel up in an
# isolated container and pings the server side and 8.8.8.8 through the tunnel.
#
# Usage: tests/wg_test.sh <server-ip> <endpoint-host> [client-name]
#   endpoint-host: DOMAIN, or a proxy domain / IP (the config is made with --via <host>)
#   DOCKER_NET=sgproxy  run the client in that docker network (proxy sandbox)
#   WG_TEST_IMAGE       client image (default securegate-wireguard:local - has wg + iproute2, so the
#                       test needs no package downloads)
# Run on the MAIN server in the repository folder.
set -e
IP=${1:?server ip}; HOST=${2:?endpoint host}; NAME=${3:-wgtest}
IMG=${WG_TEST_IMAGE:-securegate-wireguard:local}
cd "$(dirname "$0")/.."
./sgctl wg show "$NAME" >/dev/null 2>&1 || ./sgctl wg add "$NAME" >/dev/null
CONF=$(./sgctl wg conf "$NAME" --via "$HOST")
SERVER=$(echo "$CONF" | sed -n 's/^Address = \([0-9.]*\)\.[0-9]*\/32/\1.1/p')
docker run --rm --privileged ${DOCKER_NET:+--network $DOCKER_NET} --add-host "$HOST:$IP" \
  --entrypoint sh -e CONF="$CONF" -e SERVER="$SERVER" "$IMG" -c '
  v() { echo "$CONF" | sed -n "s/^$1 = //p" | head -n1; }
  echo "$(v PrivateKey)" > /tmp/k; echo "$(v PresharedKey)" > /tmp/p
  ip link add wg0 type wireguard || { echo "WG FAILED: no kernel wireguard"; exit 1; }
  wg set wg0 private-key /tmp/k peer "$(v PublicKey)" preshared-key /tmp/p endpoint "$(v Endpoint)" \
    allowed-ips 0.0.0.0/0 persistent-keepalive 25 || { echo "WG FAILED: wg set"; exit 1; }
  ip address add "$(v Address)" dev wg0; ip link set wg0 mtu "$(v MTU)" up
  EPIP=$(wg show wg0 endpoints | awk "{print \$2}" | cut -d: -f1)
  GW=$(ip route show default | awk "{print \$3; exit}")
  ip route add "$EPIP/32" via "$GW"
  ip route add 0.0.0.0/1 dev wg0; ip route add 128.0.0.0/1 dev wg0
  echo "client: endpoint $(v Endpoint) -> $EPIP"
  ping -c 3 -W 2 "$SERVER" || { echo "WG FAILED: no ping to $SERVER"; wg show; exit 1; }
  ping -c 3 -W 2 8.8.8.8 || { echo "WG FAILED: no internet via tunnel"; wg show; exit 1; }
  wg show wg0 | grep -E "endpoint|handshake|transfer"
  echo "WG OK: tunnel + internet via WireGuard"'
