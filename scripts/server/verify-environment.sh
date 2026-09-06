#!/usr/bin/env bash
# Read-only health verification of the full TaxiAgent environment.
# Checks Node readiness, PVCs Bound, all Pods Ready, the client Ingress,
# and probes the public entrypoint and registry from this node.
# Never modifies cluster state and never resets CI data.
set -uo pipefail

log() { printf '[verify] %s\n' "$*"; }
die() { printf '[verify] FAIL: %s\n' "$*" >&2; exit 1; }

command -v k3s >/dev/null 2>&1 || die "k3s command missing"

# ---------- node ----------
k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready ' \
  || die "no Ready node found"

# ---------- pvcs ----------
pvcs="$(k3s kubectl get pvc -A --no-headers 2>/dev/null)"
[[ -n "$pvcs" ]] || die "no PVCs found"
unbound="$(printf '%s\n' "$pvcs" | awk '$2 != "Bound" {print}')"
[[ -z "$unbound" ]] || die "unbound PVCs: $(printf '%s\n' "$unbound" | head -5 | tr '\n' ';')"

# ---------- pods ----------
pods="$(k3s kubectl get pods -A --no-headers 2>/dev/null)"
[[ -n "$pods" ]] || die "no pods found"
notready="$(printf '%s\n' "$pods" | awk '$4 != "Running" && $4 != "Completed" {print}')"
[[ -z "$notready" ]] || die "pods not running: $(printf '%s\n' "$notready" | head -5 | tr '\n' ';')"

# ---------- ingress ----------
k3s kubectl get ingress taxiagent-client -n taxiagent >/dev/null 2>&1 \
  || die "ingress taxiagent-client not found"

# ---------- public entrypoint through Traefik ----------
for url in "http://10.243.194.108/" "http://10.243.194.108/api/auth/ping"; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url" 2>/dev/null)"
  case "$code" in
    200|401) log "entrypoint ${url} -> ${code}" ;;
    *) die "entrypoint ${url} -> ${code}" ;;
  esac
done

# ---------- in-cluster registry ----------
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:30500/v2/ 2>/dev/null)"
case "$code" in
  200|401) log "registry :30500 -> ${code}" ;;
  *) die "registry :30500 -> ${code}" ;;
esac

log "environment healthy"
