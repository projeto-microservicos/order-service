#!/usr/bin/env bash
# Use disposable dependencies; always remove only this test's containers/volumes.
set -Eeuo pipefail
project="order-smoke-$(python3 -c 'import secrets; print(secrets.token_hex(4))')"
export SMOKE_RABBIT_PASSWORD
SMOKE_RABBIT_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
compose=(docker compose -p "$project" -f compose.test.yaml)
trap '"${compose[@]}" down --volumes' EXIT
"${compose[@]}" up -d --wait --wait-timeout 180
redis_address=$("${compose[@]}" port redis 6379)
rabbit_address=$("${compose[@]}" port rabbit 5672)
export REDIS_HOST=127.0.0.1 REDIS_PORT="${redis_address##*:}"
export RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT="${rabbit_address##*:}"
export RABBITMQ_USERNAME=smoke RABBITMQ_PASSWORD="$SMOKE_RABBIT_PASSWORD"
RUN_INFRA_SMOKE=true mvn -B -ntp -Dtest=OrderInfrastructureTest test
