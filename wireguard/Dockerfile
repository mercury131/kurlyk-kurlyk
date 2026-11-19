# WireGuard VPN for securegate. Kernel WireGuard (Linux >= 5.6) in the host network namespace,
# wireguard-go (userspace) as a fallback. Keys and clients live in ./data/wireguard (volume),
# so they survive container re-creation. Clients are managed with ./sgctl wg ... (wgctl here).

# wireguard-go: userspace fallback for hosts without the kernel module (e.g. OpenVZ/LXC VPS).
# Module version pinned, integrity checked by the Go checksum database.
FROM golang:1.26-alpine AS wggo
ENV CGO_ENABLED=0 GOFLAGS=-trimpath
RUN go install -ldflags="-s -w" golang.zx2c4.com/wireguard@v0.0.0-20261024220952-b3bc704ef0ee

FROM alpine:3.20
COPY --from=wggo /go/bin/wireguard /usr/local/bin/wireguard-go
RUN set -eux; \
    apk add --no-cache wireguard-tools-wg iptables iptables-legacy iproute2 libqrencode-tools kmod; \
    wg --version; iptables -V; qrencode -V
COPY entrypoint.sh /entrypoint.sh
COPY common.sh /usr/local/bin/wg-common.sh
COPY uplink.sh /usr/local/bin/wg-uplink.sh
COPY wgctl /usr/local/bin/wgctl
RUN chmod 755 /entrypoint.sh /usr/local/bin/wgctl /usr/local/bin/wg-uplink.sh
ENTRYPOINT ["/entrypoint.sh"]
