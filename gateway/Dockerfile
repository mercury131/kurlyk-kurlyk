FROM golang:1.26-bookworm AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOFLAGS=-trimpath
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go vet ./... && go build -ldflags="-s -w" -o /out/gateway ./cmd/gateway

FROM debian:bookworm-slim
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ppp iptables nftables kmod iproute2 ca-certificates tzdata; \
    rm -rf /var/lib/apt/lists/*
COPY --from=build /out/gateway /usr/local/bin/gateway
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD ["/usr/local/bin/gateway", "healthcheck"]
ENTRYPOINT ["/usr/local/bin/gateway"]
