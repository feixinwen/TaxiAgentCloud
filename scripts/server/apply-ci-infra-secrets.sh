#!/usr/bin/env bash
# Create/refresh the taxiagent-ci-secrets Kubernetes Secret from a root-only env file.
# Applies to both taxiagent-ci (数据实例自身) and jenkins (Agent 运行 CI 时挂载).
# Never prints secret values.
set -Eeuo pipefail

SECRETS_DIR="/opt/taxiagent/secrets"
SECRETS_FILE="${SECRETS_DIR}/ci-infra.env"
NAMESPACES="taxiagent-ci jenkins"
SECRET_NAME="taxiagent-ci-secrets"

REQUIRED_KEYS="CI_MYSQL_ADMIN_USER CI_MYSQL_ADMIN_PASSWORD CI_REDIS_PASSWORD CI_MONGODB_USER CI_MONGODB_PASSWORD"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --secrets-file) SECRETS_FILE="$2"; shift 2 ;;
    --secrets-dir) SECRETS_DIR="$2"; shift 2 ;;
    --namespaces) NAMESPACES="$2"; shift 2 ;;
    *) echo "[ci-secrets] ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { printf '[ci-secrets] %s\n' "$*"; }
die() { printf '[ci-secrets] ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "must run as root"
[[ -f "$SECRETS_FILE" ]] || die "secrets file missing: $SECRETS_FILE"
perms=$(stat -c %a "$SECRETS_FILE")
case "$perms" in
  400|600) ;;
  *) die "secrets file permissions too open ($perms), must be 0600 or 0400" ;;
esac

declare -A VALUES
missing=""
for key in $REQUIRED_KEYS; do
  if ! grep -qE "^${key}=" "$SECRETS_FILE"; then
    missing="$missing $key"
    continue
  fi
  val=$(grep -E "^${key}=" "$SECRETS_FILE" | head -1 | cut -d= -f2- || true)
  VALUES["$key"]="$val"
done
if [[ -n "$missing" ]]; then
  die "absent keys in $SECRETS_FILE:$missing"
fi
for key in $REQUIRED_KEYS; do
  if [[ -z "${VALUES[$key]}" ]]; then
    die "${key} is empty in $SECRETS_FILE"
  fi
done

LITERALS=()
for key in $REQUIRED_KEYS; do
  LITERALS+=(--from-literal "${key}=${VALUES[$key]}")
done

for ns in $NAMESPACES; do
  k3s kubectl -n "$ns" create secret generic "$SECRET_NAME" \
    "${LITERALS[@]}" \
    --dry-run=client -o yaml \
    | k3s kubectl apply -f - >/dev/null
  log "secret ${ns}/${SECRET_NAME} applied (keys: $REQUIRED_KEYS)"
done
