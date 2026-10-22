#!/usr/bin/env bash
# M9 · Skill 中心（2026-10-19 技能资产/市场/版本管理/Agent 使用情况）
# 覆盖：Markdown 创建与校验矩阵 / 版本状态机（draft→published→offline→reline）/
#       zip 上传与下载 / 版本下载 / 市场源与安装 / Agent 引用（source=skill）与删除保护 /
#       skill-usages / member 403 / 跨空间 404。
# 前置：JWT admin；Agent 引用链路需要 verified LLM 连接（CI 用 mock LLM）。
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M9 Skill 中心"

WS=$(seed_ws "$TOKEN" "m9-ws-$TS")
WS2=$(seed_ws "$TOKEN" "m9-ws2-$TS")
resolve_llm_env

SKILL="m9-skill-$TS"
SKILL_ZIP="m9-zip-$TS"
CONTENT="# $SKILL

M9 skill body v1."
SID=""; ZID=""; MKT_ID=""; SVC=""; SVC_DRAFT=""
cleanup_m9() {
  [ -n "$SVC" ] && code DELETE /console/workspaces/$WS/agents/$SVC "" "$TOKEN" >/dev/null 2>&1
  [ -n "$SVC_DRAFT" ] && code DELETE /console/workspaces/$WS/agents/$SVC_DRAFT "" "$TOKEN" >/dev/null 2>&1
  for id in $SID $ZID $MKT_ID; do
    [ -n "$id" ] && code DELETE /console/workspaces/$WS/skill-center/$id "" "$TOKEN" >/dev/null 2>&1
  done
}
trap cleanup_m9 EXIT

body_md() { # body_md <name> <description> <content> <publishNow>
  python3 -c "
import json,sys
print(json.dumps({'name':sys.argv[1],'displayName':sys.argv[1],'description':sys.argv[2],
 'tags':[' e2e ','e2e','m9'],'content':sys.argv[3],'publishNow':sys.argv[4]=='true'}))" \
    "$1" "$2" "$3" "$4"
}

# ---- Markdown 创建（draft）+ 校验矩阵 ----
S=$(req POST /console/workspaces/$WS/skill-center "$(body_md "$SKILL" "M9 e2e skill" "$CONTENT" false)" "" "$TOKEN")
SID=$(echo "$S" | jqr "['data']['id']")
assert_eq "Markdown 创建返回 id" "$([ -n "$SID" ] && [ "$SID" != "None" ] && echo yes || echo no)" "yes"
assert_eq "创建来源 markdown" "$(echo "$S" | jqr "['data']['source']")" "markdown"
assert_eq "创建后启用" "$(echo "$S" | jqr "['data']['enabled']")" "True"
assert_eq "标签归一（去空白/去重）" "$(echo "$S" | jqr "['data']['tagsJson']")" '["e2e","m9"]'
assert_eq "同空间重名 409" "$(code POST /console/workspaces/$WS/skill-center "$(body_md "$SKILL" "dup" "$CONTENT" false)" "" "$TOKEN")" "409"
assert_eq "非法名称（大写）400" "$(code POST /console/workspaces/$WS/skill-center "$(body_md "BadName-$TS" "d" "$CONTENT" false)" "" "$TOKEN")" "400"
assert_eq "连续连字符 400" "$(code POST /console/workspaces/$WS/skill-center "$(body_md "bad--name-$TS" "d" "$CONTENT" false)" "" "$TOKEN")" "400"
assert_eq "空描述 400" "$(code POST /console/workspaces/$WS/skill-center "$(body_md "m9-nodesc-$TS" "" "$CONTENT" false)" "" "$TOKEN")" "400"
assert_eq "空正文 400" "$(code POST /console/workspaces/$WS/skill-center "$(body_md "m9-nocontent-$TS" "d" "" false)" "" "$TOKEN")" "400"
assert_contains "列表 keyword 命中" \
  "$(req GET "/console/workspaces/$WS/skill-center?keyword=$SKILL" "" "$TOKEN")" "$SKILL"
assert_eq "跨空间访问 404" "$(code GET /console/workspaces/$WS2/skill-center/$SID "" "$TOKEN")" "404"

# ---- 版本状态机 ----
VERS=$(req GET /console/workspaces/$WS/skill-center/$SID/versions "" "$TOKEN")
V1=$(echo "$VERS" | python3 -c "import sys,json;print([v['id'] for v in json.load(sys.stdin)['data'] if v['version']=='1.0.0'][0])")
assert_eq "初始版本 draft" "$(echo "$VERS" | python3 -c "import sys,json;print([v['status'] for v in json.load(sys.stdin)['data'] if v['version']=='1.0.0'][0])")" "draft"
assert_eq "draft 不能 offline 409" "$(code POST /console/workspaces/$WS/skill-center/$SID/versions/$V1/offline "" "$TOKEN")" "409"
assert_eq "发布 v1.0.0" "$(req POST /console/workspaces/$WS/skill-center/$SID/versions/$V1/publish "" "$TOKEN" | jqr "['data']['status']")" "published"
assert_contains "列表 latestPublish 版本 1.0.0" \
  "$(req GET "/console/workspaces/$WS/skill-center?keyword=$SKILL" "" "$TOKEN")" '"version":"1.0.0"'
assert_eq "重复发布 409（正式版本不可变）" "$(code POST /console/workspaces/$WS/skill-center/$SID/versions/$V1/publish "" "$TOKEN")" "409"
assert_eq "下线 v1.0.0" "$(req POST /console/workspaces/$WS/skill-center/$SID/versions/$V1/offline "" "$TOKEN" | jqr "['data']['status']")" "offline"
assert_eq "重新上线 v1.0.0" "$(req POST /console/workspaces/$WS/skill-center/$SID/versions/$V1/reline "" "$TOKEN" | jqr "['data']['status']")" "published"

# 基于 v1 创建 v1.0.1（默认 patch+1）
V2=$(req POST /console/workspaces/$WS/skill-center/$SID/versions \
  "{\"baseVersionId\":$V1,\"changelog\":\"m9 v2\"}" "" "$TOKEN")
V2_ID=$(echo "$V2" | jqr "['data']['id']")
assert_eq "新版本默认 1.0.1" "$(echo "$V2" | jqr "['data']['version']")" "1.0.1"
assert_eq "新版本 draft" "$(echo "$V2" | jqr "['data']['status']")" "draft"
assert_eq "非法 semver 400" "$(code POST /console/workspaces/$WS/skill-center/$SID/versions \
  "{\"baseVersionId\":$V1,\"version\":\"v1\"}" "" "$TOKEN")" "400"
assert_eq "重复版本 409" "$(code POST /console/workspaces/$WS/skill-center/$SID/versions \
  "{\"baseVersionId\":$V1,\"version\":\"1.0.0\"}" "" "$TOKEN")" "409"
assert_eq "发布 v1.0.1" \
  "$(req POST /console/workspaces/$WS/skill-center/$SID/versions/$V2_ID/publish "" "$TOKEN" | jqr "['data']['status']")" "published"

# ---- 版本下载（Markdown 形态导出 skills/<name>/SKILL.md）----
DL=$(curl -s "$BASE/console/workspaces/$WS/skill-center/$SID/versions/$V1/download" \
  -H "Authorization: Bearer $TOKEN" -o /tmp/m9-skill-$TS.zip -w '%{http_code}')
assert_eq "版本下载 200" "$DL" "200"
DLMD=$(python3 -c "
import zipfile
z=zipfile.ZipFile('/tmp/m9-skill-$TS.zip')
print(z.read('skills/$SKILL/SKILL.md').decode())")
assert_contains "下载内容含正文" "$DLMD" "M9 skill body v1"

# ---- Markdown 编辑 → 新 draft ----
req PATCH /console/workspaces/$WS/skill-center/$SID \
  "{\"description\":\"M9 updated\",\"content\":\"# $SKILL\\n\\nM9 skill body v2.\"}" "" "$TOKEN" >/dev/null
VERS2=$(req GET /console/workspaces/$WS/skill-center/$SID/versions "" "$TOKEN")
assert_eq "编辑后出现新 draft（1.0.2）" \
  "$(echo "$VERS2" | python3 -c "import sys,json;print([v['status'] for v in json.load(sys.stdin)['data'] if v['version']=='1.0.2'][0])")" "draft"

# ---- zip 上传（publishNow=true）----
python3 - <<PY
import zipfile
with zipfile.ZipFile("/tmp/m9-zip-$TS.zip", "w") as z:
    z.writestr("skills/$SKILL_ZIP/SKILL.md", "---\nname: $SKILL_ZIP\ndescription: zip skill\n---\nzip body")
PY
UP=$(curl -s -X POST "$BASE/console/workspaces/$WS/skill-center/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/m9-zip-$TS.zip" \
  -F "name=$SKILL_ZIP" -F "description=zip upload probe" -F "tags=e2e,m9" -F "publishNow=true")
ZID=$(echo "$UP" | jqr "['data']['skill']['id']")
assert_eq "zip 上传返回 skill" "$([ -n "$ZID" ] && [ "$ZID" != "None" ] && echo yes || echo no)" "yes"
assert_eq "zip 来源" "$(echo "$UP" | jqr "['data']['skill']['source']")" "zip"
assert_contains "zip warnings 为数组" "$UP" '"warnings":'
ZV=$(req GET /console/workspaces/$WS/skill-center/$ZID/versions "" "$TOKEN")
ZV1=$(echo "$ZV" | python3 -c "import sys,json;print([v['id'] for v in json.load(sys.stdin)['data'] if v['version']=='1.0.0'][0])")
assert_eq "zip 创建即发布" "$(echo "$ZV" | python3 -c "import sys,json;print([v['status'] for v in json.load(sys.stdin)['data'] if v['version']=='1.0.0'][0])")" "published"
ZDL=$(curl -s "$BASE/console/workspaces/$WS/skill-center/$ZID/versions/$ZV1/download" \
  -H "Authorization: Bearer $TOKEN" -o /tmp/m9-zip-dl-$TS.zip -w '%{http_code}')
assert_eq "zip 版本下载 200" "$ZDL" "200"
ZFILE=$(python3 -c "
import zipfile
print('\n'.join(zipfile.ZipFile('/tmp/m9-zip-dl-$TS.zip').namelist()))")
assert_contains "zip 下载保留 skills/<name>/SKILL.md" "$ZFILE" "skills/$SKILL_ZIP/SKILL.md"

# ---- 市场：源/条目/安装（条目由 CI seed 提供；无条目时跳过安装成功断言）----
SRC=$(req GET /console/workspaces/$WS/skill-market/sources "" "$TOKEN")
assert_contains "内置市场源 builtin" "$SRC" '"key":"builtin"'
assert_eq "未知市场源 404" "$(code GET "/console/workspaces/$WS/skill-market?source=nope" "" "$TOKEN")" "404"
ITEMS=$(req GET "/console/workspaces/$WS/skill-market?source=builtin" "" "$TOKEN")
MKT_KEY=$(echo "$ITEMS" | python3 -c "
import sys,json
raw=sys.stdin.read(); rows=json.loads(raw[raw.index('{'):])['data']
print(rows[0]['key'] if rows else '')" 2>/dev/null || true)
if [ -n "$MKT_KEY" ] && [ "$MKT_KEY" != "None" ]; then
  MI=$(req POST /console/workspaces/$WS/skill-market/builtin/$MKT_KEY/install "" "$TOKEN")
  MKT_ID=$(echo "$MI" | jqr "['data']['id']")
  assert_eq "市场安装返回 skill" "$([ -n "$MKT_ID" ] && [ "$MKT_ID" != "None" ] && echo yes || echo no)" "yes"
  assert_eq "市场安装来源 market" "$(echo "$MI" | jqr "['data']['source']")" "market"
  assert_contains "市场安装来源名" "$(echo "$MI" | jqr "['data']['sourceName']")" "平台精选"
  assert_eq "重复安装 409" "$(code POST /console/workspaces/$WS/skill-market/builtin/$MKT_KEY/install "" "$TOKEN")" "409"
  INST=$(req GET "/console/workspaces/$WS/skill-market?source=builtin" "" "$TOKEN" | python3 -c "
import sys,json
raw=sys.stdin.read(); rows=json.loads(raw[raw.index('{'):])['data']
row=[r for r in rows if r['key']=='$MKT_KEY'][0]
print(row['installedSkillId'] > 0)")
  assert_eq "市场列表标记已安装" "$INST" "True"
else
  skip "市场无内置条目（CI 未 seed），跳过安装成功/重复安装断言"
fi
assert_eq "安装不存在条目 404" "$(code POST /console/workspaces/$WS/skill-market/builtin/no-such-$TS/install "" "$TOKEN")" "404"

# ---- member 403 ----
U1=$(seed_user "$TOKEN" "m9-member-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
MT=$(req POST /auth/login "{\"username\":\"m9-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "member 创建技能 403" "$(code POST /console/workspaces/$WS/skill-center "$(body_md "m9-member-$TS" "d" "$CONTENT" false)" "" "$MT")" "403"
assert_eq "member 删除技能 403" "$(code DELETE /console/workspaces/$WS/skill-center/$SID "" "$MT")" "403"
assert_eq "member 读取技能列表 200" "$(code GET /console/workspaces/$WS/skill-center "" "$MT")" "200"

# ---- Agent 引用 source=skill（需 verified LLM）----
if [ -z "$LLM_KEY" ] || [ -z "$LLM_BASE" ]; then
  skip "LLM 凭据不可得——跳过 Agent 引用/删除保护/使用情况断言"
else
  MC=$(req POST /console/workspaces/$WS/model-connections \
    "{\"name\":\"m9-llm-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN" | jqr "['data']['id']")
  ensure_verified "$TOKEN" "$WS" "$MC" >/dev/null
  # 同名技能重复勾选只声明/复制一次（#173）
  FORM=$(python3 -c "
import json
ref={'name':'$SKILL','source':'skill','skillId':$SID,'version':'1.0.0'}
print(json.dumps({'mode':'harness','name':'m9agent-$TS','description':'M9 skill ref','modelConnections':[{'connectionId':$MC,'isDefault':True}],'prompt':'你是 M9 e2e 助手。','skills':[ref,ref],'subAgents':[],'mcpServers':[],'replicas':1}))")
  CREATE=$(req POST /console/workspaces/$WS/agents "$FORM" "" "$TOKEN")
  SVC=$(echo "$CREATE" | jqr "['data']['service']['id']")
  PKG=$(echo "$CREATE" | jqr "['data']['package']['id']")
  assert_eq "引用技能创建 Agent" "$([ -n "$SVC" ] && [ "$SVC" != "None" ] && echo yes || echo no)" "yes"
  ST=$(wait_status "$TOKEN" "$WS" "$SVC" 240 running register_failed deploy_failed)
  assert_eq "引用技能 Agent running" "$ST" "running"
  ZIP=$(curl -s "$BASE/packages/$PKG/download" -H "Authorization: Bearer $TOKEN" -o /tmp/m9-pkg-$TS.zip -w '%{http_code}')
  assert_eq "生成包下载 200" "$ZIP" "200"
  PKGINFO=$(python3 -c "
import zipfile
z=zipfile.ZipFile('/tmp/m9-pkg-$TS.zip')
md=z.read('AGENTS.md').decode()
files=[n for n in z.namelist() if n.startswith('skills/')]
print(md)
print('=====FILES=====')
print('\n'.join(files))
print('=====SKILLMD=====')
print(z.read('skills/$SKILL/SKILL.md').decode())")
  assert_contains "包内复制技能 SKILL.md" "$PKGINFO" "M9 skill body v1"
  assert_eq "同名技能只复制一份" "$(echo "$PKGINFO" | sed -n '/=====FILES=====/,$p' | grep -c "skills/$SKILL/SKILL.md")" "1"
  assert_contains "frontmatter 声明技能" "$PKGINFO" "name: $SKILL"
  assert_contains "frontmatter 技能 source=local" "$PKGINFO" "source: local"
  assert_contains "frontmatter 锁定版本" "$PKGINFO" "version: 1.0.0"
  # 引用关系
  SDETAIL=$(req GET /console/workspaces/$WS/skill-center/$SID "" "$TOKEN")
  assert_contains "技能详情列出引用 Agent" "$SDETAIL" "m9agent-$TS"
  assert_eq "技能列表 refAgentCount >= 1" \
    "$(req GET "/console/workspaces/$WS/skill-center?keyword=$SKILL" "" "$TOKEN" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['refAgentCount'] >= 1)")" "True"
  # 使用情况
  USAGES=$(req GET "/console/workspaces/$WS/skill-usages?keyword=$SKILL" "" "$TOKEN")
  assert_contains "使用情况列出 Agent" "$USAGES" "m9agent-$TS"
  assert_contains "使用情况 skills 为数组" "$USAGES" '"skills":'
  UDETAIL=$(req GET /console/workspaces/$WS/skill-usages/$SVC "" "$TOKEN")
  assert_contains "使用详情含包内技能" "$UDETAIL" "$SKILL"
  assert_contains "使用详情 issues 为数组" "$UDETAIL" '"issues":'
  # 被引用删除 409
  assert_eq "被 Agent 引用的技能删除 409" "$(code DELETE /console/workspaces/$WS/skill-center/$SID "" "$TOKEN")" "409"
  # draft 版本不可引用 400
  V3=$(req POST /console/workspaces/$WS/skill-center/$SID/versions "{\"baseVersionId\":$V1,\"version\":\"1.1.0\",\"changelog\":\"draft\"}" "" "$TOKEN")
  V3_VER=$(echo "$V3" | jqr "['data']['version']")
  DRAFTFORM=$(python3 -c "
import json
print(json.dumps({'mode':'harness','name':'m9draft-$TS','description':'draft ref','modelConnections':[{'connectionId':$MC,'isDefault':True}],'prompt':'draft ref','skills':[{'name':'$SKILL','source':'skill','skillId':$SID,'version':'$V3_VER'}],'subAgents':[],'mcpServers':[],'replicas':1}))")
  assert_eq "引用 draft 版本 400" "$(code POST /console/workspaces/$WS/agents "$DRAFTFORM" "" "$TOKEN")" "400"
  # 停用技能不可引用 400
  req POST /console/workspaces/$WS/skill-center/$SID/disable "" "$TOKEN" >/dev/null
  DISABLED_FORM=$(echo "$FORM" | python3 -c "import sys,json;d=json.load(sys.stdin);d['name']='m9disabled-$TS';print(json.dumps(d))")
  assert_eq "停用技能引用 400" "$(code POST /console/workspaces/$WS/agents "$DISABLED_FORM" "" "$TOKEN")" "400"
  req POST /console/workspaces/$WS/skill-center/$SID/enable "" "$TOKEN" >/dev/null
  # 删除 Agent 后可删技能
  code DELETE /console/workspaces/$WS/agents/$SVC "" "$TOKEN" >/dev/null
  SVC=""
  assert_eq "解除引用后删除技能 200" "$(code DELETE /console/workspaces/$WS/skill-center/$SID "" "$TOKEN")" "200"
  SID=""
fi

finish "M9-skill-center"
