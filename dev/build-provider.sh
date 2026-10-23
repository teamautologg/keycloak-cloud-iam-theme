#!/usr/bin/env bash
# Builds the extension with Maven in Docker and puts a provider-only jar into
# target/dev-providers for docker-compose (the theme itself is mounted as files).
set -euo pipefail
cd "$(dirname "$0")/.."

MSYS_NO_PATHCONV=1 docker run --rm \
  -e BuildNr="${BuildNr:-0.0.0-local}" \
  -v "$(pwd -W 2>/dev/null || pwd):/build" \
  -v autologg-m2:/root/.m2 \
  -w /build \
  maven:3.9-eclipse-temurin-21 \
  sh -c 'mvn -B -ntp package && rm -rf target/dev-providers && mkdir -p target/dev-providers && cd target/classes && jar cf ../dev-providers/autologg-login-link-dev.jar at META-INF/services'

echo "Built target/dev-providers/autologg-login-link-dev.jar - restart Keycloak to load it: docker compose restart keycloak"
