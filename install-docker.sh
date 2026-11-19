#!/usr/bin/env bash
# install-docker.sh - installs Docker Engine + docker compose plugin from Docker's official apt
# repository (Ubuntu 22.04 / 24.04), following https://docs.docker.com/engine/install/ubuntu/
#   1. removes conflicting unofficial packages (docker.io, docker-compose, docker-compose-v2,
#      docker-doc, docker-buildx, podman-docker, containerd, runc) - only those that are installed
#   2. adds Docker's GPG key and apt repository (/etc/apt/sources.list.d/docker.sources)
#   3. installs docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
#   4. enables and starts the docker service, prints versions
# Usage (as root):  bash install-docker.sh        (sudo bash install-docker.sh)
# Images/containers/volumes in /var/lib/docker are NOT deleted.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run as root: sudo bash $0"; exit 1; }
. /etc/os-release
[ "${ID:-}" = ubuntu ] || { echo "this script is for Ubuntu (found: ${ID:-unknown}); see docs.docker.com/engine/install/"; exit 1; }
CODENAME=${UBUNTU_CODENAME:-$VERSION_CODENAME}
export DEBIAN_FRONTEND=noninteractive

echo "== 1/4 removing conflicting packages (if installed)"
conflicts=$(dpkg --get-selections docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc 2>/dev/null | awk '$2=="install"{print $1}' || true)
if [ -n "$conflicts" ]; then
  echo "   will remove: $conflicts"
  echo "   (running containers of the old docker.io are stopped; data in /var/lib/docker is kept)"
  # shellcheck disable=SC2086
  apt-get remove -y $conflicts
else
  echo "   none installed"
fi

echo "== 2/4 Docker apt repository ($CODENAME)"
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $CODENAME
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
# an older one-line repository file from a previous manual install would duplicate the source
[ -f /etc/apt/sources.list.d/docker.list ] && mv /etc/apt/sources.list.d/docker.list /etc/apt/sources.list.d/docker.list.disabled
apt-get update

echo "== 3/4 installing Docker Engine + compose plugin"
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

echo "== 4/4 starting docker"
systemctl enable --now docker containerd
systemctl is-active --quiet docker || { systemctl status docker --no-pager; exit 1; }
docker --version
docker compose version
timeout 120 docker run --rm hello-world >/dev/null 2>&1 && echo "docker run hello-world: OK" || echo "WARNING: docker run hello-world failed (no access to Docker Hub?)"
echo "Docker is installed and running (systemctl status docker)."
