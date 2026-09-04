#!/usr/bin/env bash
# Deploy the isolated CI test infrastructure (taxiagent-ci) and prove isolation:
#  1. ensure namespaces jenkins / taxiagent-ci exist
#  2. ensure /opt/taxiagent/secrets/ci-infra.env exists (generate random values once)
#  3. apply taxiagent-ci-secrets (into taxiagent-ci + jenkins) and all CI manifests
#  4. apply the runtime data-store NetworkPolicy (taxiagent namespace)
#  5. wait for the four StatefulSets
#  6. verify from an ephemeral pod in the jenkins namespace:
#     - CI MySQL has the six *_test schemas, Redis/MongoDB/ES answer on their DNS names
#     - the runtime MySQL in taxiagent is NOT reachable from jenkins (NetworkPolicy proof)
# All verification pods run with the taxiagent.io/ci-data-access=true label that the
# CI ingress NetworkPolicy requires.
set -Eeuo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_DIR="/opt/taxiagent/secrets"
SECRETS_FILE="${SECRETS_DIR}/ci-infra.env"
MANIFESTS_DIR="${MANIFESTS_DIR:-/opt/taxiagent/manifests}"
CI_MANIFESTS="${MANIFESTS_DIR}/ci-infra"
RUNTIME_NETPOL="${MANIFESTS_DIR}/config/runtime-database-networkpolicy.yaml"

VERIFY_PODS="ci-verify-mysql ci-verify-redis ci-verify-mongodb ci-verify-es"
STS_LIST="mysql redis mongodb elasticsearch"

log() { printf '[ci-deploy] %s\n' "$*"; }
die() { printf '[ci-deploy] ERROR: %s\n' "$*" >&2; exit 1; }

cleanup() {
  for p in $VERIFY_PODS; do
    k3s kubectl -n jenkins delete pod "$p" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

# ---------- prechecks ----------
[[ "$(id -u)" -eq 0 ]] || die "must run as root"
k3s kubectl get nodes >/dev/null || die "k3s kubectl not usable"
k3s kubectl apply -f - >/dev/null <<'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: jenkins
---
apiVersion: v1
kind: Namespace
metadata:
  name: taxiagent-ci
EOF
log "namespaces jenkins / taxiagent-ci present"

# ---------- CI credentials (idempotent: only generate when file absent) ----------
if [[ ! -f "$SECRETS_FILE" ]]; then
  mkdir -p "$SECRETS_DIR"
  umask 077
  cat > "$SECRETS_FILE" <<EOF
CI_MYSQL_ADMIN_USER=ci_admin
CI_MYSQL_ADMIN_PASSWORD=$(openssl rand -hex 16)
CI_REDIS_PASSWORD=$(openssl rand -hex 16)
CI_MONGODB_USER=ci_mongo
CI_MONGODB_PASSWORD=$(openssl rand -hex 16)
EOF
  chmod 0600 "$SECRETS_FILE"
  log "generated $SECRETS_FILE (random values, root-only)"
else
  log "secrets file already exists: $SECRETS_FILE"
fi

"${SCRIPTS_DIR}/apply-ci-infra-secrets.sh" || die "apply-ci-infra-secrets.sh failed"

# ---------- apply manifests ----------
[[ -d "$CI_MANIFESTS" ]] || die "CI manifests missing: $CI_MANIFESTS (expected local-path layout of deploy/k8s/ci-infra)"
k3s kubectl kustomize "$CI_MANIFESTS" | k3s kubectl apply -f - >/dev/null
log "CI manifests applied (4 StatefulSets + services + NetworkPolicy + ServiceAccount)"

if [[ -f "$RUNTIME_NETPOL" ]]; then
  k3s kubectl apply -f "$RUNTIME_NETPOL" >/dev/null
  log "runtime data-store NetworkPolicy applied (taxiagent namespace)"
else
  log "WARNING: $RUNTIME_NETPOL missing, runtime data stores stay open to other namespaces"
fi

for sts in $STS_LIST; do
  k3s kubectl rollout status "sts/${sts}" -n taxiagent-ci --timeout=300s >/dev/null
done
log "all four CI StatefulSets rolled out"

# ---------- helper: run a labelled verification pod in jenkins ns and exec in it ----------
verify_exec() {  # $1 pod name, $2 image, rest: sh -c command
  local pod="$1" image="$2"
  shift 2
  k3s kubectl -n jenkins delete pod "$pod" --ignore-not-found --wait=true >/dev/null 2>&1 || true
  k3s kubectl -n jenkins apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $pod
  namespace: jenkins
  labels:
    taxiagent.io/ci-data-access: "true"
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  containers:
    - name: verify
      image: $image
      imagePullPolicy: IfNotPresent
      command: ["sh", "-c", "sleep 3600"]
      envFrom:
        - secretRef:
            name: taxiagent-ci-secrets
EOF
  k3s kubectl -n jenkins wait --for=condition=Ready "pod/$pod" --timeout=120s >/dev/null || die "verify pod $pod not ready"
  k3s kubectl -n jenkins exec "$pod" -- sh -c "$*"
}

FAILED=0

# MySQL: six _test schemas reachable over DNS + runtime MySQL blocked
if out=$(verify_exec ci-verify-mysql mysql:8.4.8 \
    "mysql -h mysql.taxiagent-ci -u\"\$CI_MYSQL_ADMIN_USER\" -p\"\$CI_MYSQL_ADMIN_PASSWORD\" -N -e \"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE '%_test' ORDER BY 1\" 2>&1"); then
  count=$(echo "$out" | grep -c '_test' || true)
  if [[ "$count" -eq 6 ]]; then
    log "PASS CI MySQL: six _test schemas reachable via mysql.taxiagent-ci ($(echo "$out" | grep '_test' | tr '\n' ' '))"
  else
    log "FAIL CI MySQL: expected 6 schemas, got: $out"
    FAILED=1
  fi
else
  log "FAIL CI MySQL: connection failed: $out"
  FAILED=1
fi

if [[ -f "$SECRETS_DIR/taxiagent.env" ]]; then
  rt_root=$(grep -E '^MYSQL_ROOT_PASSWORD=' "$SECRETS_DIR/taxiagent.env" | head -1 | cut -d= -f2- || true)
  if [[ -n "$rt_root" ]]; then
    if out=$(verify_exec ci-verify-mysql mysql:8.4.8 \
        "mysql --connect-timeout=4 -h mysql.taxiagent -uroot -p\"$rt_root\" -e 'SELECT 1' 2>&1"); then
      log "FAIL isolation: runtime MySQL in taxiagent was reachable from jenkins namespace!"
      FAILED=1
    else
      log "PASS isolation: runtime MySQL in taxiagent NOT reachable from jenkins namespace ($(echo "$out" | head -1 | cut -c1-120))"
    fi
  else
    log "SKIP runtime isolation check (MYSQL_ROOT_PASSWORD empty in taxiagent.env)"
  fi
else
  log "SKIP runtime isolation check (taxiagent.env not found)"
fi

# Redis auth over DNS
if out=$(verify_exec ci-verify-redis redis:7-alpine \
    "REDISCLI_AUTH=\"\$CI_REDIS_PASSWORD\" redis-cli -h redis.taxiagent-ci ping 2>&1"); then
  [[ "$out" == PONG ]] && log "PASS CI Redis: PONG via redis.taxiagent-ci" || { log "FAIL CI Redis: $out"; FAILED=1; }
else
  log "FAIL CI Redis: $out"; FAILED=1
fi

# MongoDB auth over DNS
if out=$(verify_exec ci-verify-mongodb mongo:8.0 \
    "mongosh --quiet --username \"\$CI_MONGODB_USER\" --password \"\$CI_MONGODB_PASSWORD\" --authenticationDatabase admin --host mongodb.taxiagent-ci --eval \"db.adminCommand('ping').ok\" 2>&1"); then
  [[ "$out" == 1 ]] && log "PASS CI MongoDB: ping ok via mongodb.taxiagent-ci" || { log "FAIL CI MongoDB: $out"; FAILED=1; }
else
  log "FAIL CI MongoDB: $out"; FAILED=1
fi

# Elasticsearch health over DNS
if out=$(verify_exec ci-verify-es 10.243.194.108:30500/taxiagent/elasticsearch-ik:8.19.7 \
    "curl -s -m 10 http://elasticsearch.taxiagent-ci:9200/_cluster/health 2>&1"); then
  echo "$out" | grep -q '"status"' && log "PASS CI Elasticsearch: health via elasticsearch.taxiagent-ci ($(echo "$out" | head -c 120))" || { log "FAIL CI Elasticsearch: $out"; FAILED=1; }
else
  log "FAIL CI Elasticsearch: $out"; FAILED=1
fi

[[ "$FAILED" -eq 0 ]] || die "verification failed"
log "CI infrastructure verification passed"
