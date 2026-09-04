#!/usr/bin/env bash
# Create/refresh the taxiagent-secrets Kubernetes Secret from a root-only env file.
# Expands TAXIAGENT_DB_PASSWORD into the six per-service *_DB_PASSWORD keys and
# mounts jwt-private.pem / jwt-public.pem as files. Never prints secret values.
set -Eeuo pipefail

SECRETS_DIR="/opt/taxiagent/secrets"
SECRETS_FILE="${SECRETS_DIR}/taxiagent.env"
NAMESPACE="taxiagent"
SECRET_NAME="taxiagent-secrets"

REQUIRED_KEYS="MYSQL_ROOT_PASSWORD TAXIAGENT_DB_PASSWORD NACOS_AUTH_TOKEN NACOS_AUTH_IDENTITY_KEY NACOS_AUTH_IDENTITY_VALUE NACOS_PASSWORD DASHSCOPE_API_KEY DEEPSEEK_API_KEY AMAP_KEY QWEATHER_KEY QWEATHER_TOKEN"
OPTIONAL_WARN_KEYS="DASHSCOPE_API_KEY DEEPSEEK_API_KEY AMAP_KEY QWEATHER_KEY QWEATHER_TOKEN"
DB_SERVICES="USER AUTH ORDER TICKET RAG AGENT"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --secrets-file) SECRETS_FILE="$2"; shift 2 ;;
    --secrets-dir) SECRETS_DIR="$2"; shift 2 ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    *) echo "[secrets] ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { printf '[secrets] %s\n' "$*"; }
die() { printf '[secrets] ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "must run as root"
[[ -f "$SECRETS_FILE" ]] || die "secrets file missing: $SECRETS_FILE"
perms=$(stat -c %a "$SECRETS_FILE")
case "$perms" in
  400|600) ;;
  *) die "secrets file permissions too open ($perms), must be 0600 or 0400" ;;
esac

# ---------- read & validate keys ----------
declare -A VALUES
missing=""
for key in $REQUIRED_KEYS; do
  val=$(grep -E "^${key}=" "$SECRETS_FILE" | head -1 | cut -d= -f2- || true)
  if [[ -z "${val:-}" ]]; then
    missing="$missing $key"
  else
    VALUES["$key"]="$val"
  fi
done
if [[ -n "$missing" ]]; then
  die "missing keys in $SECRETS_FILE:$missing"
fi
for key in $OPTIONAL_WARN_KEYS; do
  if [[ -z "${VALUES[$key]}" ]]; then
    log "WARNING: ${key} is empty (API calls using it will fail at runtime)"
  fi
done

# ---------- JWT key pair ----------
JWT_PRIVATE="${SECRETS_DIR}/jwt-private.pem"
JWT_PUBLIC="${SECRETS_DIR}/jwt-public.pem"
if [[ ! -s "$JWT_PRIVATE" || ! -s "$JWT_PUBLIC" ]]; then
  log "generating fresh RSA-2048 JWT key pair..."
  mkdir -p "$SECRETS_DIR"
  umask 077
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$JWT_PRIVATE" >/dev/null 2>&1
  openssl pkey -in "$JWT_PRIVATE" -pubout -out "$JWT_PUBLIC" >/dev/null 2>&1
  chmod 0600 "$JWT_PRIVATE"
  chmod 0644 "$JWT_PUBLIC"
  log "generated $JWT_PRIVATE and $JWT_PUBLIC"
else
  openssl pkey -in "$JWT_PRIVATE" -noout >/dev/null 2>&1 || die "invalid private key PEM: $JWT_PRIVATE"
  openssl pkey -pubin -in "$JWT_PUBLIC" -noout >/dev/null 2>&1 || die "invalid public key PEM: $JWT_PUBLIC"
  log "JWT key pair OK (both PEM valid)"
fi

# ---------- build and apply secret (stdin only, nothing hits disk/logs) ----------
LITERALS=()
for key in $REQUIRED_KEYS; do
  LITERALS+=(--from-literal "${key}=${VALUES[$key]}")
done
for svc in $DB_SERVICES; do
  LITERALS+=(--from-literal "${svc}_DB_PASSWORD=${VALUES[TAXIAGENT_DB_PASSWORD]}")
done

k3s kubectl -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
  "${LITERALS[@]}" \
  --from-file="jwt-private.pem=${JWT_PRIVATE}" \
  --from-file="jwt-public.pem=${JWT_PUBLIC}" \
  --dry-run=client -o yaml \
  | k3s kubectl apply -f - >/dev/null

log "secret ${NAMESPACE}/${SECRET_NAME} applied (keys: ${REQUIRED_KEYS} $(for s in $DB_SERVICES; do printf '%s_DB_PASSWORD ' "$s"; done)jwt-private.pem jwt-public.pem)"
