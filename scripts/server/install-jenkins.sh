#!/usr/bin/env bash
# Install the Jenkins controller into the K3s jenkins namespace with pinned
# chart/plugin versions and secrets sourced from /opt/taxiagent/secrets.
# Never prints credential values; creates secrets from the local secrets dir.
set -Eeuo pipefail

CHART_REPO="https://charts.jenkins.io"
CHART_NAME="jenkinsci/jenkins"
CHART_VERSION="5.9.53"
NAMESPACE="jenkins"
SECRETS_DIR="/opt/taxiagent/secrets"
ADMIN_SECRET="jenkins-admin"
REGISTRY_SECRET="jenkins-registry"
VALUES_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/deploy/k8s/bootstrap/jenkins/values.yaml"
JENKINS_URL="http://10.243.194.108:30080"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"

log() { printf '[jenkins] %s\n' "$*"; }
die() { printf '[jenkins] ERROR: %s\n' "$*" >&2; exit 1; }

# ---------- prechecks ----------
command -v helm >/dev/null || die "helm missing"
command -v k3s >/dev/null || die "k3s command missing"
k3s kubectl get ns "$NAMESPACE" >/dev/null 2>&1 || die "namespace $NAMESPACE missing (run bootstrap first)"
k3s kubectl get storageclass local-path >/dev/null 2>&1 || die "storageclass local-path missing"
k3s kubectl get svc taxiagent-registry -n registry >/dev/null 2>&1 || die "registry service missing"
[[ -f "$VALUES_FILE" ]] || die "values file missing: $VALUES_FILE"

# ---------- admin secret ----------
ADMIN_USER="$(k3s kubectl get secret -n "$NAMESPACE" "$ADMIN_SECRET" -o jsonpath='{.data.jenkins-admin-user}' 2>/dev/null | base64 -d || true)"
if [[ -z "$ADMIN_USER" ]]; then
  ADMIN_USER="admin"
  PASSWORD_FILE="${SECRETS_DIR}/jenkins-admin-password"
  if [[ -f "$PASSWORD_FILE" ]]; then
    ADMIN_PASSWORD="$(<"$PASSWORD_FILE")"
  else
    ADMIN_PASSWORD="$(openssl rand -hex 16)"
    umask 177
    printf '%s' "$ADMIN_PASSWORD" > "$PASSWORD_FILE"
  fi
  k3s kubectl create secret generic "$ADMIN_SECRET" -n "$NAMESPACE" \
    --from-literal=jenkins-admin-user="$ADMIN_USER" \
    --from-literal=jenkins-admin-password="$ADMIN_PASSWORD" >/dev/null
  log "created secret $ADMIN_SECRET (password saved to $PASSWORD_FILE)"
else
  log "secret $ADMIN_SECRET already present"
fi

# ---------- registry push secret ----------
if ! k3s kubectl get secret "$REGISTRY_SECRET" -n "$NAMESPACE" >/dev/null 2>&1; then
  SECRETS_FILE="${SECRETS_DIR}/taxiagent.env"
  [[ -f "$SECRETS_FILE" ]] || die "missing $SECRETS_FILE"
  REGISTRY_USER="$(grep -E '^REGISTRY_USERNAME=' "$SECRETS_FILE" | cut -d= -f2- || true)"
  REGISTRY_PASS="$(grep -E '^REGISTRY_PASSWORD=' "$SECRETS_FILE" | cut -d= -f2- || true)"
  if [[ -z "$REGISTRY_USER" || -z "$REGISTRY_PASS" ]]; then
    die "REGISTRY_USERNAME/REGISTRY_PASSWORD not set in $SECRETS_FILE"
  fi
  k3s kubectl create secret generic "$REGISTRY_SECRET" -n "$NAMESPACE" \
    --from-literal=username="$REGISTRY_USER" \
    --from-literal=password="$REGISTRY_PASS" >/dev/null
  log "created secret $REGISTRY_SECRET from taxiagent.env"
else
  log "secret $REGISTRY_SECRET already present"
fi

# ---------- deploy ----------
log 'applying Jenkins RBAC'
k3s kubectl apply -k ${VALUES_FILE%/*}
export HTTPS_PROXY="${HTTPS_PROXY:-http://10.243.150.36:7897}"
export HTTP_PROXY="${HTTP_PROXY:-http://10.243.150.36:7897}"
export NO_PROXY="${NO_PROXY:-127.0.0.1,localhost,.svc,.cluster.local,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}"
helm repo add jenkinsci "$CHART_REPO" >/dev/null 2>&1 || true
helm repo update jenkinsci >/dev/null 2>&1 || true
log "installing $CHART_NAME $CHART_VERSION"
helm upgrade --install jenkins "$CHART_NAME" --version "$CHART_VERSION" \
  --namespace "$NAMESPACE" --create-namespace \
  --values "$VALUES_FILE" --timeout 15m --wait

k3s kubectl rollout status statefulset/jenkins -n "$NAMESPACE" --timeout=300s >/dev/null
log "waiting for HTTP readiness on port 30080"
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$JENKINS_URL/login" 2>/dev/null || true)"
  if [[ "$code" == "200" || "$code" == "403" ]]; then
    break
  fi
  sleep 10
done
[[ "$code" == "200" || "$code" == "403" ]] || die "jenkins not reachable at $JENKINS_URL (last code $code)"

log "Jenkins ready: $JENKINS_URL"
log "admin user: $ADMIN_USER (password in $SECRETS_DIR/jenkins-admin-password)"
