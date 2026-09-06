#!/usr/bin/env bash
# Deploy the full TaxiAgent environment from this repository's deploy/k8s tree.
#
#   deploy-environment.sh [--infra-only | --apps-only | --all]
#
# Order for --all: config -> infra (runtime data) -> ci-infra -> apps.
# Every apply is followed by an explicit rollout wait (no fixed sleeps).
# apps are rendered with the IMAGE_TAG placeholder replaced by the repo HEAD
# commit SHA. On failure the script dumps namespace events, unready pod
# describes and container log tails, then exits non-zero.
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K8S_DIR="${REPO_ROOT}/deploy/k8s"

log() { printf '[deploy] %s\n' "$*"; }
die() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }

MODE="${1:---all}"
case "$MODE" in
  --infra-only|--apps-only|--all) ;;
  *) die "unknown mode '$MODE' (expected --infra-only|--apps-only|--all)" ;;
esac

# ---------- prechecks ----------
command -v git >/dev/null || die "git missing"
k3s kubectl get nodes >/dev/null 2>&1 || die "k3s kubectl not usable"
for d in config infra ci-infra apps; do
  [[ -d "${K8S_DIR}/$d" ]] || die "missing ${K8S_DIR}/$d"
done

dump_failure() {
  local ns="$1"
  log "dumping failure context for namespace ${ns}"
  k3s kubectl -n "$ns" get events --sort-by=.lastTimestamp 2>/dev/null | tail -30 || true
  for pod in $(k3s kubectl -n "$ns" get pods --no-headers 2>/dev/null | awk '$3 != "Running" && $3 != "Completed" {print $1}'); do
    k3s kubectl -n "$ns" describe pod "$pod" 2>/dev/null | tail -30 || true
    k3s kubectl -n "$ns" logs "$pod" --tail=50 --all-containers 2>/dev/null || true
  done
}

rollout() { # ns kind name timeout
  local ns="$1" kind="$2" name="$3" timeout="$4"
  if ! k3s kubectl rollout status "${kind}/${name}" -n "$ns" --timeout="$timeout" >/dev/null 2>&1; then
    dump_failure "$ns"
    die "rollout ${kind}/${name} in ${ns} did not become ready within ${timeout}"
  fi
  log "ready ${kind}/${name} in ${ns}"
}

apply_workloads() { # ns timeout kind name [kind name]...
  local ns="$1" timeout="$2"
  shift 2
  while (($# >= 2)); do
    rollout "$ns" "$1" "$2" "$timeout"
    shift 2
  done
}

infra_apply() {
  log "apply config"
  k3s kubectl apply -k "${K8S_DIR}/config"
  log "apply infra"
  k3s kubectl apply -k "${K8S_DIR}/infra"
  apply_workloads taxiagent 300s \
    statefulset mysql \
    statefulset redis \
    statefulset nacos \
    statefulset mongodb \
    statefulset elasticsearch \
    statefulset rocketmq-broker \
    deployment mailpit \
    deployment rocketmq-namesrv
  log "apply ci-infra"
  k3s kubectl apply -k "${K8S_DIR}/ci-infra"
  apply_workloads taxiagent-ci 300s \
    statefulset mysql \
    statefulset redis \
    statefulset mongodb \
    statefulset elasticsearch
}

apps_apply() {
  local tag
  tag="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
  log "apply apps (tag ${tag})"
  k3s kubectl kustomize "${K8S_DIR}/apps" | sed "s/:IMAGE_TAG/:${tag}/g" | k3s kubectl apply -f -
  apply_workloads taxiagent 300s \
    deployment taxiagent-gateway \
    deployment taxiagent-user-service \
    deployment taxiagent-auth-service \
    deployment taxiagent-order-service \
    deployment taxiagent-ticket-service \
    deployment taxiagent-rag-service \
    deployment taxiagent-agent-service \
    deployment taxiagent-client-service
}

case "$MODE" in
  --infra-only) infra_apply ;;
  --apps-only) apps_apply ;;
  --all) infra_apply; apps_apply ;;
esac

log "deploy complete (${MODE})"
