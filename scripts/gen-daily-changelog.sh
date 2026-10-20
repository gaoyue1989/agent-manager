#!/usr/bin/env bash
# 每日更新日志生成：汇总指定日期（北京时间口径）落地 master 的全部提交，
# 调用 OpenAI 兼容接口生成 zcode changelog 风格的中文总结，markdown 输出到 stdout。
# 设计说明见 docs/design/daily-changelog-design.md
#
# 用法: gen-daily-changelog.sh [YYYY-MM-DD]   （缺省为北京时间昨天）
# 环境变量:
#   LLM_API_KEY        必填，OpenAI 兼容接口密钥
#   LLM_BASE_URL       选填，默认 https://token-plan-cn.xiaomimimo.com/v1
#   LLM_MODEL          选填，默认 mimo-v2.6-flash
#   CHANGELOG_GIT_REF  选填，统计的分支，默认 origin/master
# 退出码: 0 成功（含 LLM 失败降级为原始列表）；2 该日期无提交；1 参数/环境错误
set -euo pipefail

TZ_NAME='Asia/Shanghai'
DEFAULT_BASE_URL='https://token-plan-cn.xiaomimimo.com/v1'
DEFAULT_MODEL='mimo-v2.6-flash'
MAX_BRANCH_COMMITS=30   # 每个 merge 展示的分支内提交上限，防止 prompt 过长
MAX_RETRIES=2           # LLM 请求尝试次数

log() { printf '%s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

command -v git  >/dev/null 2>&1 || die "缺少 git"
command -v jq   >/dev/null 2>&1 || die "缺少 jq"
command -v curl >/dev/null 2>&1 || die "缺少 curl"

# ---- 参数与日期窗口 ----
DATE="${1:-}"
if [ -z "$DATE" ]; then
  DATE="$(TZ="$TZ_NAME" date -d '1 day ago' +%F)"
fi
# 正则 + round-trip 双重校验，避免 date 对非法日期做静默滚动（如 2026-13-40）
if [[ ! "$DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
  || [ "$(TZ="$TZ_NAME" date -d "$DATE" +%F 2>/dev/null)" != "$DATE" ]; then
  die "日期格式应为 YYYY-MM-DD，收到: $DATE"
fi
NEXT="$(TZ="$TZ_NAME" date -d "$DATE + 1 day" +%F)"
SINCE="${DATE}T00:00:00+08:00"
UNTIL="${NEXT}T00:00:00+08:00"
REF="${CHANGELOG_GIT_REF:-origin/master}"
BASE_URL="${LLM_BASE_URL:-$DEFAULT_BASE_URL}"
BASE_URL="${BASE_URL%/}"
MODEL="${LLM_MODEL:-$DEFAULT_MODEL}"
[ -n "${LLM_API_KEY:-}" ] || die "缺少环境变量 LLM_API_KEY"

# ---- 收集提交材料（first-parent 视角 = 逐条落地 master 的 PR 合并/直接提交）----
LOG_FMT='%H%x1f%an%x1f%P%x1f%s'
entries="$(git log --first-parent --no-color --format="$LOG_FMT" --since="$SINCE" --until="$UNTIL" "$REF")" \
  || die "git log 失败：请确认 $REF 存在（需先 git fetch）"
if [ -z "$entries" ]; then
  log "北京时间 $DATE 当天 $REF 无提交，跳过"
  exit 2
fi

# 单行记录按 \x1f 切前 4 个字段，最后一段（subject）用前缀剥离取余，避免误切
split_fields() {
  local rec="$1"
  H="${rec%%$'\x1f'*}"; rec="${rec#*$'\x1f'}"
  AN="${rec%%$'\x1f'*}"; rec="${rec#*$'\x1f'}"
  P="${rec%%$'\x1f'*}"; rec="${rec#*$'\x1f'}"
  S="$rec"
}

pr_count=0
direct_count=0
n=0
material="（first-parent 视角，每条 = 一次落地 master 的 PR 合并或直接提交）"
while IFS= read -r line; do
  [ -z "$line" ] && continue
  split_fields "$line"
  n=$((n + 1))
  case "$P" in
    *\ *)
      # merge commit：附带分支内提交主题，给模型更多归纳依据
      pr_count=$((pr_count + 1))
      material+=$'\n'"$(printf '%d. [PR合并] %s (%s) %s' "$n" "${H:0:8}" "$AN" "$S")"$'\n'
      if git cat-file -e "${H}^2" 2>/dev/null; then
        sub_list="$(git log --no-merges --no-color --format='%s' "${H}^1..${H}^2")"
        sub_total="$(printf '%s\n' "$sub_list" | grep -c . || true)"
        if [ "$sub_total" -gt 0 ]; then
          material+="   分支内提交（${sub_total} 条）："$'\n'
          # 末尾 || true：head 提前退出会让 grep 收到 SIGPIPE（exit 141），配合 pipefail 需兜住
          shown="$(printf '%s\n' "$sub_list" | grep . | head -n "$MAX_BRANCH_COMMITS" || true)"
          while IFS= read -r s; do
            [ -z "$s" ] && continue
            material+="   - ${s}"$'\n'
          done <<< "$shown"
          if [ "$sub_total" -gt "$MAX_BRANCH_COMMITS" ]; then
            material+="   - …（其余 $((sub_total - MAX_BRANCH_COMMITS)) 条略）"$'\n'
          fi
        fi
      fi
      ;;
    *)
      direct_count=$((direct_count + 1))
      material+=$'\n'"$(printf '%d. [直接提交] %s (%s) %s' "$n" "${H:0:8}" "$AN" "$S")"$'\n'
      ;;
  esac
done <<< "$entries"

# ---- 提示词 ----
SYSTEM_PROMPT='你是 OAF 服务发布平台仓库的发布编辑。根据给出的「某一天落地到 master 分支的 git 提交记录」，产出一份面向团队成员的中文更新日志。

要求：
1. 只依据给定材料归纳，不编造材料外的内容；技术名词、组件名、PR 编号保持原样。
2. 输出由若干个三级标题小节组成，按需选用且保持顺序：### 新功能、### 优化调整、### 问题修复、### 其他变更；某类没有内容就整体省略该小节。
3. 每个小节下是无序列表，每条一句话，说明用户或开发者可感知的变化，句尾以（#PR号）标注来源；同一个 PR 的多条分支内提交归纳为一个条目。
4. 语言风格示例："新增动态工作流，用一段脚本编排多个子代理协作完成复杂任务"、"修复刷新进入会话无响应续传的问题"。
5. 直接输出 markdown 正文，不要代码围栏，不要任何开场白、解释或总结陈词。'

USER_PROMPT="日期：${DATE}（北京时间）
统计范围：${REF} first-parent，共 ${pr_count} 个 PR 合并、${direct_count} 次直接提交

提交材料：
${material}"

# ---- LLM 调用 ----
# 去掉模型习惯性包裹的代码围栏与首尾空行
strip_fences() {
  printf '%s' "$1" \
    | sed -e '1{/^[[:space:]]*```/d}' -e '${/[[:space:]]*```[[:space:]]*$/d}' \
    | sed -e '/./,$!d'
}

call_llm() {
  local payload resp err content
  payload="$(jq -n --arg model "$MODEL" --arg sys "$SYSTEM_PROMPT" --arg user "$USER_PROMPT" '
    {model: $model,
     messages: [{role: "system", content: $sys}, {role: "user", content: $user}],
     temperature: 0.3, max_tokens: 4096}')"
  resp="$(curl -sS --max-time 180 \
    -H "Authorization: Bearer ${LLM_API_KEY}" \
    -H 'Content-Type: application/json' \
    -d "$payload" "${BASE_URL}/chat/completions")" || return 1
  err="$(printf '%s' "$resp" | jq -r '.error.message // empty' 2>/dev/null)" || return 1
  if [ -n "$err" ]; then log "LLM 返回错误: $err"; return 1; fi
  content="$(printf '%s' "$resp" | jq -r '.choices[0].message.content // empty' 2>/dev/null)" || return 1
  [ -z "$content" ] && return 1
  strip_fences "$content"
}

content=""
for attempt in $(seq 1 "$MAX_RETRIES"); do
  log "调用 LLM 生成总结（第 ${attempt}/${MAX_RETRIES} 次尝试，model=${MODEL}）…"
  if content="$(call_llm)"; then break; fi
  content=""
  [ "$attempt" -lt "$MAX_RETRIES" ] && sleep 3
done

# 降级兜底：LLM 失败也要出日报，直接给原始提交列表
if [ -z "$content" ]; then
  log "WARN: LLM 生成失败，降级输出原始提交列表"
  content="> ⚠️ 自动总结生成失败，以下为当日原始提交列表。"$'\n'"### 原始提交列表"$'\n'"$material"
fi

# ---- 输出 ----
{
  printf '## 更新日志 · %s\n\n' "$DATE"
  printf '> 当天 %s 个 PR 合并、%s 次直接提交落地 master\n\n' "$pr_count" "$direct_count"
  printf '%s\n' "$content"
}
