#!/usr/bin/env bash
# 控制台拆分按功能 e2e 套件 runner（M0–M8 + M9 技能中心 + M10 沙箱配置；
# 原「M9 enforce 三态」属独立网关环境，见 10-gateway-auth.md，不在本 runner）。
# 用法：
#   BASE=http://localhost:30080/api/v1 FRONTEND_BASE=http://localhost:8911 \
#   [LLM_API_KEY=.. LLM_BASE_URL=.. LLM_MODEL=..] ./run-all.sh [m0 m2 ...]
# 不带参数 = 顺序全跑；传模块号只跑指定模块。
# 退出码约定：0=全 PASS/SKIP，1=有 FAIL；子脚本 exit 2 = SKIP（缺 LLM/运行时等前置）。
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
MODULES=("$@")
[ ${#MODULES[@]} -eq 0 ] && MODULES=(m0 m1 m2 m3 m4 m5 m6 m7 m8 m9 m10)

declare -A RESULT
for M in "${MODULES[@]}"; do
  SCRIPT="$DIR/$M-"*.sh
  FILES=( $SCRIPT )
  if [ ! -e "${FILES[0]}" ]; then
    echo "== [$M] 脚本不存在，跳过"; RESULT[$M]="missing"; continue
  fi
  echo ""; echo "########## $M ##########"
  bash "${FILES[0]}"
  RC=$?
  case "$RC" in
    0) RESULT[$M]="PASS" ;;
    2) RESULT[$M]="SKIP" ;;
    *) RESULT[$M]="FAIL" ;;
  esac
done

echo ""; echo "========== 汇总 =========="
FAILTOTAL=0
for M in "${MODULES[@]}"; do
  echo "  $M: ${RESULT[$M]:-not-run}"
  [ "${RESULT[$M]}" = "FAIL" ] && FAILTOTAL=$((FAILTOTAL+1))
done
[ "$FAILTOTAL" = "0" ]
