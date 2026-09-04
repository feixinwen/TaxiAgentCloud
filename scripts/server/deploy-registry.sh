#!/usr/bin/env bash
# Deploy the in-cluster registry: ensure credentials, create registry-auth Secret
# (bcrypt htpasswd via local htpasswd or a temporary httpd:2.4 pod), apply bootstrap
# manifests, wait for rollout. Idempotent; never prints the password.
set -Eeuo pipefail

MANIFEST_DIR="/tmp/taxiagent-deploy/bootstrap"
SECRETS_DIR="/opt/taxiagent/secrets"
SECRETS_FILE="${SECRETS_DIR}/registry.env"
GEN_POD="registry-htpasswd-gen"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest-dir) MANIFEST_DIR="$2"; shift 2 ;;
    --secrets-file) SECRETS_FILE="$2"; shift 2 ;;
    *) echo "[registry] ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { printf '[registry] %s\n' "$*"; }
die() { printf '[registry] ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "must run as root"
[[ -d "$MANIFEST_DIR" ]] || die "manifest dir missing: $MANIFEST_DIR (scp deploy/k8s/bootstrap first)"

# ---------- credentials ----------
if [[ ! -f "$SECRETS_FILE" ]]; then
  mkdir -p "$SECRETS_DIR"
  GENERATED_PASSWORD=$(openssl rand -hex 16)
  umask 077
  cat > "$SECRETS_FILE" <<EOF
REGISTRY_USERNAME=admin
REGISTRY_PASSWORD=${GENERATED_PASSWORD}
EOF
  chmod 0600 "$SECRETS_FILE"
  unset GENERATED_PASSWORD
  log "generated new credentials at $SECRETS_FILE (0600)"
else
  perms=$(stat -c %a "$SECRETS_FILE")
  case "$perms" in
    400|600) ;;
    *) die "secrets file permissions too open ($perms), must be 0600 or 0400" ;;
  esac
fi

REGISTRY_USERNAME=$(grep -E '^REGISTRY_USERNAME=' "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '[:space:]' || true)
REGISTRY_PASSWORD=$(grep -E '^REGISTRY_PASSWORD=' "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '[:space:]' || true)
[[ -n "$REGISTRY_USERNAME" && -n "$REGISTRY_PASSWORD" ]] || die "credentials missing in $SECRETS_FILE"

# ---------- htpasswd (bcrypt) -> secret ----------
# Host apt is broken (stale docker-ce source) and registry:2.8.3 ships no htpasswd,
# so fall back to a temporary httpd:2.4 pod (image pull goes through the k3s proxy).
POD_USED=0
if command -v htpasswd >/dev/null 2>&1; then
  log "generating htpasswd with local htpasswd"
  HTPASSWD_PRODUCER=(htpasswd -Bbn "$REGISTRY_USERNAME" "$REGISTRY_PASSWORD")
else
  log "htpasswd not on host; generating via temporary httpd:2.4 pod..."
  POD_USED=1
  k3s kubectl delete pod "$GEN_POD" --ignore-not-found --wait=true >/dev/null 2>&1 || true
  k3s kubectl run "$GEN_POD" \
    --image=httpd:2.4 \
    --restart=Never \
    --env=HTPASSWD_USER="$REGISTRY_USERNAME" \
    --env=HTPASSWD_PASSWORD="$REGISTRY_PASSWORD" \
    --command -- sh -c 'htpasswd -Bbn "$HTPASSWD_USER" "$HTPASSWD_PASSWORD"' >/dev/null
  k3s kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/"$GEN_POD" --timeout=300s >/dev/null
  HTPASSWD_PRODUCER=(k3s kubectl logs "$GEN_POD")
fi
"${HTPASSWD_PRODUCER[@]}" \
  | k3s kubectl -n registry create secret generic registry-auth \
      --from-file=htpasswd=/dev/stdin \
      --dry-run=client -o yaml \
  | k3s kubectl apply -f - >/dev/null
if [[ $POD_USED -eq 1 ]]; then
  k3s kubectl delete pod "$GEN_POD" --ignore-not-found >/dev/null 2>&1 || true
fi
log "secret registry-auth applied"

# ---------- manifests ----------
k3s kubectl apply -k "$MANIFEST_DIR"
log "waiting for registry rollout..."
k3s kubectl -n registry rollout status deployment/taxiagent-registry --timeout=300s

NODE_IP=$(k3s kubectl get node -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
log "registry ready: http://${NODE_IP}:30500 (auth: ${REGISTRY_USERNAME})"
