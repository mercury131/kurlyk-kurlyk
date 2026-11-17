#!/bin/sh
# WireGuard end-to-end test: creates (or reuses) a client with ./sgctl, brings the tunnel up with
# wg-quick in an isolated container and pings the server side and 8.8.8.8 through the tunnel.
#
# Usage: tests/wg_test.sh <server-ip> <endpoint-host> [client-name]
#   endpoint-host: DOMAIN, or a proxy domain (the config is made with --via <host>)
#   DOCKER_NET=sgproxy  run the client in that docker network (proxy sandbox)
# Run on the MAIN server in the repository folder.
set -e
IP=${1:?server ip}; HOST=${2:?endpoint host}; NAME=${3:-wgtest}
cd "$(dirname "$0")/.."
./sgctl wg show "$NAME" >/dev/null 2>&1 || ./sgctl wg add "$NAME" >/dev/null
CONF=$(./sgctl wg conf "$NAME" --via "$HOST")
SERVER=$(echo "$CONF" | sed -n 's/^Address = \([0-9.]*\)\.[0-9]*\/32/\1.1/p')
docker run --rm --privileged ${DOCKER_NET:+--network $DOCKER_NET} --add-host "$HOST:$IP" \
  -e CONF="$CONF" -e SERVER="$SERVER" alpine:3.20 sh -c '
  apk add --no-cache -q wireguard-tools-wg-quick iproute2 iptables >/dev/null
  mkdir -p /etc/wireguard; echo "$CONF" | sed "/^DNS/d" > /etc/wireguard/wg0.conf; chmod 600 /etc/wireguard/wg0.conf
  wg-quick up wg0 >/dev/null 2>&1 || { echo "WG FAILED: wg-quick up"; wg-quick up wg0; exit 1; }
  ping -c 3 -W 2 "$SERVER" || { echo "WG FAILED: no ping to $SERVER"; wg show; exit 1; }
  ping -c 3 -W 2 8.8.8.8 || { echo "WG FAILED: no internet via tunnel"; wg show; exit 1; }
  wg show wg0 | grep -E "endpoint|handshake|transfer"
  echo "WG OK: tunnel + internet via WireGuard"
  wg-quick down wg0 >/dev/null 2>&1'
