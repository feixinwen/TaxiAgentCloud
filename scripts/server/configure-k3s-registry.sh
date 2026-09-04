#!/usr/bin/env bash
# Configure K3s to pull/push from the in-cluster registry via NodePort 30500.
# Reads credentials from a root-only env file; never prints the password.
set -Eeuo pipefail

SECRETS_FILE="/opt/taxiagent/secrets/registry.env"
ENDPOINT="http://10.243.194.108:30500"
REGISTRIES_YAML="/etc/rancher/k3s/registries.yaml"
NODE_IP="10.243.194.108"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --secrets-file) SECRETS_FILE="$2"; shift 2 ;;
    --endpoint) ENDPOINT="$2"; shift 2 ;;
    --node-ip) NODE_IP="$2"; shift 2 ;;
    *) echo "[registry] ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { printf '[registry] %s\n' "$*"; }
die() { printf '[registry] ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "must run as root"
[[ -f "$SECRETS_FILE" ]] || die "secrets file missing: $SECRETS_FILE (expected keys REGISTRY_USERNAME / REGISTRY_PASSWORD)"

perms=$(stat -c %a "$SECRETS_FILE")
case "$perms" in
  400|600) ;;
  *) die "secrets file permissions too open ($perms), must be 0600 or 0400" ;;
esac

REGISTRY_USERNAME=$(grep -E '^REGISTRY_USERNAME=' "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '[:space:]' || true)
REGISTRY_PASSWORD=$(grep -E '^REGISTRY_PASSWORD=' "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '[:space:]' || true)
[[ -n "$REGISTRY_USERNAME" && -n "$REGISTRY_PASSWORD" ]] || die "REGISTRY_USERNAME / REGISTRY_PASSWORD missing or empty in $SECRETS_FILE"

umask 077
cat > "$REGISTRIES_YAML" <<EOF
mirrors:
  "${NODE_IP}:30500":
    endpoint:
      - "${ENDPOINT}"
configs:
  "${NODE_IP}:30500":
    auth:
      username: "${REGISTRY_USERNAME}"
      password: "${REGISTRY_PASSWORD}"
EOF
chmod 0600 "$REGISTRIES_YAML"
log "wrote $REGISTRIES_YAML (mode 0600) for endpoint ${ENDPOINT}"

systemctl restart k3s
log "k3s restarted, waiting for node Ready..."
k3s kubectl wait --for=condition=Ready node --all --timeout=300s
log "done: K3s now uses the in-cluster registry at ${ENDPOINT}"
