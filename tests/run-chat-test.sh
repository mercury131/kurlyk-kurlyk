#!/bin/sh
# Usage: tests/run-chat-test.sh <server-ip> <domain> <chat_secret>
set -e
IP=${1:?server ip}; DOMAIN=${2:?domain}; SECRET=${3:?chat secret}
cd "$(dirname "$0")"
docker run --rm ${DOCKER_NET:+--network $DOCKER_NET} --add-host "$DOMAIN:$IP" -v "$PWD:/t:ro" python:3.12-slim sh -c \
  "pip install -q websockets cryptography >/dev/null 2>&1 && python /t/chat_test.py '$DOMAIN' '$SECRET'"
