# teleproxy (https://github.com/teleproxy/teleproxy, C, maintained fork of the official
# Telegram MTProxy) - the 3rd MTProxy implementation of securegate, on its own port.
# Official static release binary, verified by sha256.
FROM alpine:3.20

ARG TELEPROXY_VERSION=v4.16.1
ARG TARGETARCH
ARG SHA_AMD64=6afbc40af530e61e4b6275f630dde947f29e84b6e72b22a43a83f5f5ce36d340
ARG SHA_ARM64=cf9f67e4db48deba7456d086387866a2be1eebeee2b9cd625ef8686c880969b6

RUN set -eux; \
    apk add --no-cache ca-certificates curl; \
    case "${TARGETARCH:-amd64}" in \
      amd64) A=amd64; S=$SHA_AMD64 ;; \
      arm64) A=arm64; S=$SHA_ARM64 ;; \
      *) echo "unsupported arch ${TARGETARCH}"; exit 1 ;; \
    esac; \
    curl -fsSL -o /usr/local/bin/teleproxy \
      "https://github.com/teleproxy/teleproxy/releases/download/${TELEPROXY_VERSION}/teleproxy-linux-${A}"; \
    echo "${S}  /usr/local/bin/teleproxy" | sha256sum -c -; \
    chmod 755 /usr/local/bin/teleproxy

COPY sg-run.sh /opt/sg-run.sh
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
WORKDIR /etc/teleproxy
ENTRYPOINT ["/entrypoint.sh"]
