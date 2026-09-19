#!/usr/bin/env bash
# 通用就绪轮询：wait-ready <url> <超时秒> [名称]
set -uo pipefail
URL="$1"; TIMEOUT="${2:-60}"; NAME="${3:-$URL}"
for ((i = 0; i < TIMEOUT; i++)); do
  if curl -fsS -m 2 "$URL" >/dev/null 2>&1; then echo "[wait-ready] $NAME 就绪"; exit 0; fi
  sleep 1
done
echo "[wait-ready] $NAME 超时（${TIMEOUT}s）：$URL" >&2
exit 1
