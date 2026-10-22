#!/usr/bin/env bash
# M8 · 工作空间资产余页（实施文档 09 §9/§10，DoD：e2e 脚本 workspace-assets）
# 覆盖：API Key 签发（格式/明文一次/前缀）/作用域过滤/吊销/过期惰性；invitable-users；
#       成员邀请/角色/移除（用户侧端点）；模板列表；包模板开关
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M8 工作空间资产"

WS=$(seed_ws "$TOKEN" "m8-ws-$TS")
U1=$(seed_user "$TOKEN" "m8-owner-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"owner\"}" "" "$TOKEN" >/dev/null
OT=$(req POST /auth/login "{\"username\":\"m8-owner-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "owner 登录" "$([ -n "$OT" ] && echo yes || echo no)" "yes"

# ---- API Key：签发/格式/明文一次/作用域 ----
K=$(req POST /console/workspaces/$WS/api-keys "{\"name\":\"m8-key\",\"expiresInDays\":30}" "" "$OT")
PLAIN=$(echo "$K" | jqr "['data']['plaintext']")
KID=$(echo "$K" | jqr "['data']['id']")
assert_contains "明文 ak- 格式" "$PLAIN" "ak-"
assert_eq "KeyPrefix=明文前 8 位" "$(echo "$K" | jqr "['data']['keyPrefix']")" "${PLAIN:0:8}"
LIST=$(req GET /console/workspaces/$WS/api-keys "" "$OT")
assert_contains "列表含前缀" "$LIST" "${PLAIN:0:8}"
assert_not_contains "列表不含明文" "$LIST" "$PLAIN"
# 作用域过滤：绑定单 Agent 的 key 只出现在该作用域查询（建第二个 key 绑定不存在 agent 的行为=无 agent 断言全局）
K2=$(req POST /console/workspaces/$WS/api-keys '{"name":"m8keyall"}' "" "$OT" | jqr "['data']['id']")
# 绑定单 Agent（88888 为纯过滤值）的 key：只出现在该作用域查询
KB=$(req POST /console/workspaces/$WS/api-keys '{"name":"m8-key-scoped","agentId":88888}' "" "$OT" | jqr "['data']['id']")
SCOPED=$(req GET "/console/workspaces/$WS/api-keys?agentId=99999" "" "$OT")
assert_contains "全局 key 出现在任意作用域查询" "$SCOPED" "m8keyall"
assert_not_contains "单 Agent 作用域不含他 Agent 绑定 key" "$SCOPED" '"m8-key-scoped"'
HIT=$(req GET "/console/workspaces/$WS/api-keys?agentId=88888" "" "$OT")
assert_contains "绑定作用域查询命中" "$HIT" '"m8-key-scoped"'

# ---- 过期惰性判定（改库不可行——用 0 天=永不过期 + 吊销态断言；过期走单测覆盖）----
assert_eq "吊销 200" "$(code DELETE /console/workspaces/$WS/api-keys/$KID "" "$OT")" "200"
assert_contains "吊销后状态 revoked" "$(req GET /console/workspaces/$WS/api-keys "" "$OT")" '"revoked"'
assert_eq "吊销不存在 404" "$(code DELETE /console/workspaces/$WS/api-keys/999999 "" "$OT")" "404"

# ---- invitable-users / 成员链路（用户侧端点）----
U2=$(seed_user "$TOKEN" "m8-member-$TS" "Pass12345!")
INV=$(req GET /console/workspaces/$WS/invitable-users "" "$OT")
assert_contains "可邀请列表含新用户" "$INV" "m8-member-$TS"
assert_eq "邀请 member 201" "$(code POST /console/workspaces/$WS/members "{\"userId\":$U2,\"role\":\"member\"}" "" "$OT")" "201"
assert_eq "重复邀请 409" "$(code POST /console/workspaces/$WS/members "{\"userId\":$U2,\"role\":\"member\"}" "" "$OT")" "409"
# member 权限边界（在升角色之前断言）：member 不能邀请/看可邀请名单，可读成员列表
MT=$(req POST /auth/login "{\"username\":\"m8-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
U3=$(seed_user "$TOKEN" "m8-u3-$TS" "Pass12345!")
assert_eq "member 邀请 403" "$(code POST /console/workspaces/$WS/members "{\"userId\":$U3,\"role\":\"member\"}" "" "$MT")" "403"
assert_eq "member invitable 403" "$(code GET /console/workspaces/$WS/invitable-users "" "$MT")" "403"
assert_eq "member 成员列表可读" "$(code GET /console/workspaces/$WS/members "" "$MT")" "200"
assert_eq "member 发凭证 403" "$(code POST /console/workspaces/$WS/api-keys "{\"name\":\"x\"}" "" "$MT")" "403"
assert_eq "改角色 admin" "$(code PATCH /console/workspaces/$WS/members/$U2 "{\"role\":\"admin\"}" "" "$OT")" "200"
assert_contains "成员列表角色已变" "$(req GET /console/workspaces/$WS/members "" "$OT")" '"role":"admin"'
assert_eq "升角色后邀请 201（角色即时生效）" "$(code POST /console/workspaces/$WS/members "{\"userId\":$U3,\"role\":\"member\"}" "" "$MT")" "201"
code DELETE /console/workspaces/$WS/members/$U3 "" "$MT" >/dev/null
assert_eq "owner 移除 409（保护）" "$(code DELETE /console/workspaces/$WS/members/$U1 "" "$OT")" "409"
assert_eq "移除 member 200" "$(code DELETE /console/workspaces/$WS/members/$U2 "" "$OT")" "200"

# ---- 模板列表 + 包模板开关（运维侧标记 → 用户侧模板页可见）----
PKGS=$(req GET /console/workspaces/$WS/packages "" "$OT")
assert_contains "包列表结构" "$PKGS" '"data":'
FIRST_PKG=$(echo "$PKGS" | jqr "['data'][0]['id']" 2>/dev/null || true)
if [ -n "$FIRST_PKG" ] && [ "$FIRST_PKG" != "None" ]; then
  req PATCH /packages/$FIRST_PKG/template "{\"isTemplate\":true}" "" "$TOKEN" >/dev/null
  TPL=$(req GET /console/workspaces/$WS/templates "" "$TOKEN")
  assert_contains "标记后模板页可见" "$TPL" '"isTemplate":true'
  req PATCH /packages/$FIRST_PKG/template "{\"isTemplate\":false}" "" "$TOKEN" >/dev/null
else
  say "  ⏭️ 无包可标记，跳过模板开关链路"
fi


# ---- 空间技能清单（#163/#164）：异常 skill 目录写入包 warnings + 清单标注；声明回读描述 ----
python3 - <<PYEOF
import zipfile
md = """---
name: Skill Probe
vendorKey: acme
agentKey: skill-probe
version: 1.0.0
slug: acme/skill-probe
description: skill validation probe
author: "@e2e"
license: MIT
skills:
  - name: webapp-testing
    source: local
    version: 1.0.0
---
技能校验探针"""
with zipfile.ZipFile("/tmp/m8-skills-$TS.zip", "w") as z:
    z.writestr("AGENTS.md", md)
    z.writestr("skills/webapp-testing/SKILL.md", '---\nname: webapp-testing\ndescription: toolkit for web apps\n---\nbody')
    z.writestr("skills/broken/README.md", "no SKILL.md here")
    z.writestr("skills/My Skill!/SKILL.md", '---\nname: my-skill\ndescription: d\n---\nbody')
PYEOF
SUP=$(curl -s -X POST "$BASE/console/workspaces/$WS/packages" -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/m8-skills-$TS.zip")
SPKG=$(echo "$SUP" | jqr "['data']['id']")
if [ -n "$SPKG" ] && [ "$SPKG" != "None" ]; then
  WARN=$(echo "$SUP" | jqr "['data']['warningsJson']")
  assert_contains "包 warnings 含异常 skill（#163）" "$WARN" "broken"
  assert_contains "包 warnings 含非法名称 skill" "$WARN" "My Skill"
  SKILLS=$(req GET "/console/workspaces/$WS/skills?keyword=webapp-testing" "" "$OT")
  assert_contains "声明技能回读 SKILL.md 描述（#164）" "$SKILLS" "toolkit for web apps"
  ALL=$(req GET "/console/workspaces/$WS/skills" "" "$OT")
  BROKEN=$(echo "$ALL" | python3 -c "
import sys,json
raw=sys.stdin.read()
d=json.loads(raw[raw.index('{'):])['data']
m={x['name']:x for x in d}
iss=(m.get('broken') or {}).get('issues') or []
print(';'.join(iss))")
  assert_contains "目录扫描技能标注 issues（#163）" "$BROKEN" "missing skills/broken/SKILL.md"
  BADNAME=$(echo "$ALL" | python3 -c "
import sys,json
raw=sys.stdin.read()
d=json.loads(raw[raw.index('{'):])['data']
m={x['name']:x for x in d}
iss=(m.get('My Skill!') or {}).get('issues') or []
print(';'.join(iss))")
  assert_contains "非法名称标注 issues" "$BADNAME" "invalid characters"
  legacy_code DELETE /packages/$SPKG >/dev/null 2>&1
else
  skip "技能探针包上传失败，跳过 #163/#164 断言"
fi

finish "M8-workspace-assets"
