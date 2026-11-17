#!/bin/sh
# Usage: tests/sstp-client/run.sh <server-ip> <domain> <user> <password>
# Connects with sstpc from an isolated container, prints interface, pings server side and 8.8.8.8 via VPN.
set -e
IP=${1:?server ip}; DOMAIN=${2:?domain}; USER=${3:?user}; PASS=${4:?password}
cd "$(dirname "$0")"
docker build -q -t securegate-sstp-test . >/dev/null
docker run --rm --privileged ${DOCKER_NET:+--network $DOCKER_NET} --add-host "$DOMAIN:$IP" -e U="$USER" -e P="$PASS" -e D="$DOMAIN" securegate-sstp-test sh -c '
  sstpc --cert-warn --tls-ext --log-stderr --log-level 2 --user "$U" --password "$P" "$D" \
     require-mschap-v2 noauth noipdefault nodefaultroute nodetach logfd 2 &
  for i in $(seq 1 20); do ip -4 addr show ppp0 2>/dev/null | grep -q inet && break; sleep 1; done
  ip -4 addr show ppp0 || { echo "SSTP FAILED"; exit 1; }
  ping -c 3 -W 2 192.168.17.1
  ip route add 8.8.8.8/32 dev ppp0
  ping -c 3 -W 2 8.8.8.8 && echo "SSTP OK: internet via VPN"
  kill %1 2>/dev/null; sleep 1'
