#!/usr/bin/env bash
# 控制台拆分按功能 e2e 套件 runner（M0–M8 + M9 技能中心 + M10 沙箱配置 + M11 空间配置/导出
# + M12 沙箱管理面（sandbox-pool-opensandbox-design §9.4）；
# 原「M9 enforce 三态」属独立网关环境，见 10-gateway-auth.md，不在本 runner）。
# 用法：
#   BASE=http://localhost:30080/api/v1 FRONTEND_BASE=http://localhost:8911 \
#   [LLM_API_KEY=.. LLM_BASE_URL=.. LLM_MODEL=..] ./run-all.sh [m0 m2 ...]
#   ./run-all.sh --shard <分片名>     只跑该分片的模块（CI 按分片并行，各分片独占集群）
#   ./run-all.sh --check-shards      校验分片表：磁盘上每个模块恰好归属一个分片
# 不带参数 = 顺序全跑；传模块号只跑指定模块。
# 退出码约定：0=全 PASS/SKIP，1=有 FAIL；子脚本 exit 2 = SKIP（缺 LLM/运行时等前置）。
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
SHARDS=(
  "agent-create:m3"
  "agent-detail:m4"
  "sandbox-config:m10"
  "console-rest:m0 m1 m2 m5 m6 m7 m8 m9 m11 m12"
)

shard_modules() {
  local entry
  for entry in "${SHARDS[@]}"; do
    if [ "${entry%%:*}" = "$1" ]; then echo "${entry#*:}"; return 0; fi
  done
  return 1
}

check_shards() {
  local entry shard m f FILES problem=0
  declare -A OWNER=()
  for entry in "${SHARDS[@]}"; do
    shard="${entry%%:*}"
    for m in ${entry#*:}; do
      if [ -n "${OWNER[$m]:-}" ]; then
        echo "分片表错误：模块 $m 同时归属 ${OWNER[$m]} 与 $shard"; problem=1
      fi
      OWNER[$m]="$shard"
      FILES=( "$DIR/$m-"*.sh )
      if [ ! -e "${FILES[0]}" ]; then
        echo "分片表错误：分片 $shard 登记的模块 $m 不存在"; problem=1
      fi
    done
  done
  for f in "$DIR"/m[0-9]*-*.sh; do
    [ -e "$f" ] || continue
    m="$(basename "$f" | cut -d- -f1)"
    if [ -z "${OWNER[$m]:-}" ]; then
      echo "分片表错误：模块 $m 未登记到任何分片（请在 SHARDS 中归属）"; problem=1
    fi
  done
  if [ "$problem" = 0 ]; then
    echo "分片表校验通过：${SHARDS[*]}"
  fi
  return "$problem"
}

case "${1:-}" in
  --check-shards)
    check_shards
    exit $?
    ;;
  --shard)
    if [ -z "${2:-}" ] || [ -n "${3:-}" ]; then
      echo "用法：$0 --shard <分片名>（可选：${SHARDS[*]%%:*}）"; exit 1
    fi
    check_shards || exit 1
    SHARD_MODULES=$(shard_modules "$2") || { echo "未知分片：$2（可选：${SHARDS[*]%%:*}）"; exit 1; }
    read -r -a MODULES <<< "$SHARD_MODULES"
    ;;
  *)
    MODULES=("$@")
    [ ${#MODULES[@]} -eq 0 ] && MODULES=(m0 m1 m2 m3 m4 m5 m6 m7 m8 m9 m10 m11 m12)
    ;;
esac

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
