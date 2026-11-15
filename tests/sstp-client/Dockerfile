# Test SSTP client (sstpc) - used to verify the SSTP server end-to-end.
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends sstp-client ppp iproute2 iputils-ping curl ca-certificates && rm -rf /var/lib/apt/lists/*
