#!/usr/bin/env bash
# e2e 入口（e2e-ci-plan §3.4）：env-up → playwright → 总是 env-down → 退出码透传。
# 用法：E2E_GROUP=core|multi|sandbox ./scripts/run.sh
set -uo pipefail
cd "$(dirname "$0")/.."
GROUP="${1:-${E2E_GROUP:-core}}"
export E2E_GROUP="$GROUP"
export E2E_BASE="${E2E_BASE:-http://127.0.0.1:${E2E_BASE_PORT:-8100}}"
export E2E_LLM_MOCK="${E2E_LLM_MOCK:-http://127.0.0.1:${LLM_MOCK_PORT:-18081}}"
export E2E_RUN_ID="${E2E_RUN_ID:-ci-$GROUP-$(date +%s)-$RANDOM}"

FAILED=0
./scripts/check-fixtures.mjs || FAILED=1
if [ "$FAILED" -eq 0 ]; then
  ./scripts/env-up.sh || FAILED=1
fi
if [ "$FAILED" -eq 0 ]; then
  case "$GROUP" in
    core)    PROJECTS="--project=api-core --project=ui" ;;
    multi)   PROJECTS="--project=api-multi --project=ui-multi --project=api-multi-kill" ;;
    sandbox) PROJECTS="--project=api-sandbox" ;;
    *) echo "未知组 $GROUP"; PROJECTS="" ;;
  esac
  if ! npx playwright test $PROJECTS; then FAILED=1; fi
fi
./scripts/env-down.sh
exit "$FAILED"
