#!/usr/bin/env bash
# 本地基础设施一键拉起（开发机复现 CI services；e2e-ci-plan §3.4）。
# 复用本机既有 docker 镜像；端口避开 3306/6379 常规占用。
set -euo pipefail

if ! docker inspect e2e-mysql >/dev/null 2>&1; then
  IMG=$(docker images --format '{{.Repository}}:{{.Tag}}' | grep -m1 -E 'mysql|greatsql' || true)
  [ -n "$IMG" ] || IMG=mysql:8.0
  docker run -d --name e2e-mysql -p 13306:3306 -e MYSQL_ROOT_PASSWORD=e2e-root \
    -e MYSQL_DATABASE=agent_framework_e2e -e MYSQL_USER=e2e -e MYSQL_PASSWORD=e2e-pass "$IMG" >/dev/null
  echo "[local-infra] e2e-mysql($IMG) :13306"
else
  docker start e2e-mysql >/dev/null
fi
if ! docker inspect e2e-redis >/dev/null 2>&1; then
  IMG=$(docker images --format '{{.Repository}}:{{.Tag}}' | grep -m1 redis || true)
  [ -n "$IMG" ] || IMG=redis:7-bookworm
  docker run -d --name e2e-redis -p 16379:6379 "$IMG" \
    redis-server --appendonly yes --maxmemory-policy noeviction >/dev/null
  echo "[local-infra] e2e-redis($IMG) :16379"
else
  docker start e2e-redis >/dev/null
fi
echo "[local-infra] 就绪：MYSQL_URL=jdbc:mysql://127.0.0.1:13306/agent_framework_e2e REDIS_URL=redis://127.0.0.1:16379"
