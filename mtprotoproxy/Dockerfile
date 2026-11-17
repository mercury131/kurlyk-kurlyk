# mtprotoproxy (https://github.com/alexbers/mtprotoproxy, Python) - the 2nd MTProxy
# implementation of securegate, on its own port. Source pinned to a commit, verified by sha256.
FROM alpine:3.20

ARG MTPROTOPROXY_COMMIT=bd5b03cd998eee5e916c5fcea8519d6d2c331d4d
ARG MTPROTOPROXY_SHA256=81d3bc9bdaa4cf0d02bc03d65b5b2dd15b3ecfae7a9162df6ccf074e26318588

RUN set -eux; \
    apk add --no-cache python3 py3-cryptography py3-uvloop ca-certificates curl; \
    curl -fsSL -o /tmp/src.tgz \
      "https://github.com/alexbers/mtprotoproxy/archive/${MTPROTOPROXY_COMMIT}.tar.gz"; \
    echo "${MTPROTOPROXY_SHA256}  /tmp/src.tgz" | sha256sum -c -; \
    mkdir -p /opt/mtprotoproxy; \
    tar -xzf /tmp/src.tgz -C /opt/mtprotoproxy --strip-components=1; \
    rm -f /tmp/src.tgz /opt/mtprotoproxy/config.py; \
    python3 -c "import cryptography, uvloop"

COPY sg-run.sh /opt/sg-run.sh
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
WORKDIR /opt/mtprotoproxy
ENTRYPOINT ["/entrypoint.sh"]
