#!/bin/sh
# Starts a securegate instance in PROXY mode on the SAME test host, isolated in its own docker
# network (172.30.0.0/24, proxy at 172.30.0.10), relaying to the main server.
#
# Usage: tests/proxy-sandbox/up.sh <main-ip> <main-domain> <proxy-domain>
# On the main server (.env):  PROXY_FRONTENDS=<proxy-domain>  PROXY_TRUSTED_IPS=172.30.0.10
# Then run the normal tests against the proxy with DOCKER_NET=sgproxy, e.g.
#   DOCKER_NET=sgproxy sh tests/run-chat-test.sh 172.30.0.10 <proxy-domain> <chat_secret>
# Stop: docker rm -f sg-proxy-test
set -e
MAIN_IP=${1:?main server ip}; MAIN_DOMAIN=${2:?main domain}; PX_DOMAIN=${3:?proxy domain}
docker network inspect sgproxy >/dev/null 2>&1 || docker network create --subnet 172.30.0.0/24 sgproxy >/dev/null
docker rm -f sg-proxy-test >/dev/null 2>&1 || true
docker run -d --name sg-proxy-test --network sgproxy --ip 172.30.0.10 \
  --add-host "$MAIN_DOMAIN:$MAIN_IP" \
  -e PROXY=true -e DOMAIN="$PX_DOMAIN" -e DOMAIN_TO_PROXY="$MAIN_DOMAIN" \
  -e GUARD_NFTABLES=false -e DATA_DIR=/data -e MTPROXY_CONFIG_DIR=/data/mtproxy \
  securegate-gateway:local >/dev/null
sleep 3
docker logs sg-proxy-test 2>&1 | tail -5
