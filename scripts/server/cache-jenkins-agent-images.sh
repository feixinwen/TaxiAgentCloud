#!/usr/bin/env bash
# Cache fixed Jenkins agent images from the approved mirror in TaxiAgent's
# registry so runtime Pods do not depend on an external Docker Hub mirror.
set -Eeuo pipefail

SOURCE_REGISTRY="${SOURCE_REGISTRY:-docker.1panel.live}"
PUSH_REGISTRY="${PUSH_REGISTRY:-localhost:30500}"
TARGET_REPOSITORY="${TARGET_REPOSITORY:-taxiagent-ci}"
PULL_TIMEOUT_SECONDS="${PULL_TIMEOUT_SECONDS:-600}"

log() { printf '[jenkins-agent-images] %s\n' "$*"; }
die() { printf '[jenkins-agent-images] ERROR: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die 'docker command missing'
command -v timeout >/dev/null || die 'timeout command missing'
docker info >/dev/null 2>&1 || die 'docker daemon unavailable'

IMAGES=(
  'jenkins/inbound-agent:3385.vf1123fb_515da_-1-jdk21|jenkins-inbound-agent:3385.vf1123fb_515da_-1-jdk21'
  'library/maven:3.9.11-eclipse-temurin-21|maven:3.9.11-eclipse-temurin-21'
  'library/node:22-bookworm-slim|node:22-bookworm-slim'
)

for mapping in "${IMAGES[@]}"; do
  source_path="${mapping%%|*}"
  target_path="${mapping#*|}"
  source_image="${SOURCE_REGISTRY}/${source_path}"
  target_image="${PUSH_REGISTRY}/${TARGET_REPOSITORY}/${target_path}"

  log "caching ${source_path}"
  if ! timeout "$PULL_TIMEOUT_SECONDS" docker pull "$source_image"; then
    die "pull timed out or failed for ${source_path}"
  fi
  docker tag "$source_image" "$target_image"
  docker push "$target_image"
done

log 'all Jenkins agent images are cached'
