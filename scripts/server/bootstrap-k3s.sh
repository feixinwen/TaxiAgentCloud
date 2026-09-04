#!/usr/bin/env bash
# TaxiAgent K3s bootstrap for the ZeroTier test server.
# Idempotent: read-only prechecks -> config files -> install (skipped if already installed) -> wait ready.
set -Eeuo pipefail

NODE_IP="10.243.194.108"
FLANNEL_IFACE="ztyjkcpqsk"
PROXY_URL="http://10.243.150.36:7897"
REQUIRED_PORTS=(80 443 6443 30080 30500)
MIN_FREE_GB=50
CHECK_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --node-ip) NODE_IP="$2"; shift 2 ;;
    --flannel-iface) FLANNEL_IFACE="$2"; shift 2 ;;
    --proxy) PROXY_URL="$2"; shift 2 ;;
    --check-only) CHECK_ONLY=1; shift ;;
    *) echo "[bootstrap] ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { printf '[bootstrap] %s\n' "$*"; }
die() { printf '[bootstrap] ERROR: %s\n' "$*" >&2; exit 1; }
pass() { printf '[bootstrap]   OK: %s\n' "$*"; }

# ---------- read-only prechecks ----------
log "prechecks start"
[[ "$(id -u)" -eq 0 ]] || die "must run as root"
pass "running as root"

ip -4 addr show dev "$FLANNEL_IFACE" | grep -Fq "$NODE_IP" \
  || die "interface $FLANNEL_IFACE does not carry $NODE_IP"
pass "ZeroTier interface $FLANNEL_IFACE holds $NODE_IP"

if command -v k3s >/dev/null 2>&1; then
  K3S_INSTALLED=1
  log "k3s already installed: $(k3s --version | head -1)"
else
  K3S_INSTALLED=0
  log "k3s not installed"
fi

if [[ $K3S_INSTALLED -eq 0 ]]; then
  for p in "${REQUIRED_PORTS[@]}"; do
    if ss -ltn | awk '{print $4}' | grep -qE "[:.]${p}\$"; then
      die "required port ${p} is already in use by a non-K3s process"
    fi
  done
  pass "required ports free: ${REQUIRED_PORTS[*]}"

  free_gb=$(df -PB1 / | awk 'NR==2 {print int($4/1073741824)}')
  [[ "$free_gb" -ge "$MIN_FREE_GB" ]] || die "only ${free_gb}G free on /, need >= ${MIN_FREE_GB}G"
  pass "disk free: ${free_gb}G"
fi

if [[ -n "$PROXY_URL" ]]; then
  curl -x "$PROXY_URL" --fail --head --silent --max-time 15 https://get.k3s.io >/dev/null \
    || die "proxy $PROXY_URL cannot reach https://get.k3s.io (Windows dev machine online?)"
  pass "proxy reachable: $PROXY_URL"
fi

if [[ $CHECK_ONLY -eq 1 ]]; then
  log "check-only mode: no changes were made"
  exit 0
fi

# ---------- config files ----------
mkdir -p /etc/rancher/k3s
cat > /etc/rancher/k3s/config.yaml <<EOF
node-ip: ${NODE_IP}
flannel-iface: ${FLANNEL_IFACE}
write-kubeconfig-mode: "0640"
EOF
log "wrote /etc/rancher/k3s/config.yaml"

cat > /etc/sysctl.d/99-taxiagent-elasticsearch.conf <<'EOF'
vm.max_map_count = 262144
EOF
sysctl -w vm.max_map_count=262144 >/dev/null
log "persisted vm.max_map_count=262144"

# ---------- install ----------
NO_PROXY_LIST="127.0.0.1,localhost,::1,10.42.0.0/16,10.43.0.0/16,10.243.0.0/16,192.168.0.0/16,.svc,.cluster.local,${NODE_IP}"
if [[ $K3S_INSTALLED -eq 0 ]]; then
  log "installing K3s (installer + binary via ${PROXY_URL}; update.k3s.io and github.com are not directly reachable)..."
  # Proxy is required for the whole installer: get.k3s.io works direct but
  # update.k3s.io (channel lookup) and github.com (binary download) do not.
  export HTTP_PROXY="$PROXY_URL" HTTPS_PROXY="$PROXY_URL"
  export http_proxy="$PROXY_URL" https_proxy="$PROXY_URL"
  export NO_PROXY="$NO_PROXY_LIST" no_proxy="$NO_PROXY_LIST"
  curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server" sh -s -
  log "installer finished"
else
  systemctl enable --now k3s >/dev/null 2>&1 || true
fi

# k3s.service.env must be written AFTER the installer — the installer recreates the file.
if [[ -n "$PROXY_URL" ]]; then
  cat > /etc/systemd/system/k3s.service.env <<EOF
HTTP_PROXY=${PROXY_URL}
HTTPS_PROXY=${PROXY_URL}
NO_PROXY=${NO_PROXY_LIST}
http_proxy=${PROXY_URL}
https_proxy=${PROXY_URL}
no_proxy=${NO_PROXY_LIST}
EOF
  log "wrote /etc/systemd/system/k3s.service.env (proxy for image pulls only)"
else
  rm -f /etc/systemd/system/k3s.service.env
  log "no proxy configured"
fi

systemctl daemon-reload
systemctl restart k3s
log "k3s service restarted, waiting for node Ready..."

KC=/etc/rancher/k3s/k3s.yaml
for _ in $(seq 1 60); do [[ -s "$KC" ]] && break; sleep 2; done
[[ -s "$KC" ]] || die "kubeconfig did not appear within 120s"
export KUBECONFIG="$KC"

# The standalone kubectl on this host has no cluster config; always use k3s kubectl.
kk() { k3s kubectl "$@"; }
kk wait --for=condition=Ready node --all --timeout=300s
kk get storageclass local-path
kk -n kube-system rollout status deployment/traefik --timeout=300s

log "K3s bootstrap complete:"
kk get nodes -o wide
