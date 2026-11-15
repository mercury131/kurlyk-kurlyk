# telemt MTProxy (https://github.com/telemt/telemt) packaged for securegate.
# Uses the official prebuilt static (musl) release binary, verified by sha256.
FROM debian:bookworm-slim

ARG TELEMT_VERSION=3.5.17
ARG TARGETARCH

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl; \
    case "${TARGETARCH:-amd64}" in \
      amd64) A=x86_64 ;; \
      arm64) A=aarch64 ;; \
      *) echo "unsupported arch ${TARGETARCH}"; exit 1 ;; \
    esac; \
    base="https://github.com/telemt/telemt/releases/download/${TELEMT_VERSION}"; \
    curl -fsSL -o /tmp/telemt.tgz "${base}/telemt-${A}-linux-musl.tar.gz"; \
    curl -fsSL -o /tmp/telemt.sha256 "${base}/telemt-${A}-linux-musl.tar.gz.sha256"; \
    echo "$(cut -d' ' -f1 /tmp/telemt.sha256)  /tmp/telemt.tgz" | sha256sum -c -; \
    tar -xzf /tmp/telemt.tgz -C /usr/local/bin telemt; \
    chmod 755 /usr/local/bin/telemt; \
    rm -rf /tmp/telemt.* /var/lib/apt/lists/*; \
    telemt --version

COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh

WORKDIR /etc/telemt
ENTRYPOINT ["/entrypoint.sh"]
