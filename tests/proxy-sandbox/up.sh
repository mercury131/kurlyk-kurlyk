#!/bin/sh
# Starts a securegate instance in PROXY mode on the SAME test host, isolated in its own docker
# network (172.30.0.0/24), relaying to the main server. Several proxies can run side by side.
#
# Usage: tests/proxy-sandbox/up.sh <main-ip> <main-domain> <proxy-domain> [proxy-ip] [container]
#   defaults: proxy-ip 172.30.0.10, container sg-proxy-test
# On the main server (.env) list every proxy:
#   PROXY_FRONTENDS=px.example.net,px2.example.net  PROXY_TRUSTED_IPS=172.30.0.10,172.30.0.11
# Then run the normal tests against a proxy with DOCKER_NET=sgproxy, e.g.
#   DOCKER_NET=sgproxy sh tests/run-chat-test.sh 172.30.0.10 <proxy-domain> <chat_secret>
#   DOCKER_NET=sgproxy sh tests/wg_test.sh 172.30.0.10 <proxy-domain>
# Slim image (docker-compose.proxy.yml): IMAGE=securegate-proxy:local sh tests/proxy-sandbox/up.sh ...
# Stop: docker rm -f sg-proxy-test
set -e
MAIN_IP=${1:?main server ip}; MAIN_DOMAIN=${2:?main domain}; PX_DOMAIN=${3:?proxy domain}
PX_IP=${4:-172.30.0.10}; NAME=${5:-sg-proxy-test}
docker network inspect sgproxy >/dev/null 2>&1 || docker network create --subnet 172.30.0.0/24 sgproxy >/dev/null
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --network sgproxy --ip "$PX_IP" \
  --add-host "$MAIN_DOMAIN:$MAIN_IP" \
  -e PROXY=true -e DOMAIN="$PX_DOMAIN" -e DOMAIN_TO_PROXY="$MAIN_DOMAIN" \
  -e GUARD_NFTABLES=false -e DATA_DIR=/data -e MTPROXY_CONFIG_DIR=/data/mtproxy \
  ${IMAGE:-securegate-gateway:local} >/dev/null
sleep 3
docker logs "$NAME" 2>&1 | tail -5
