#!/usr/bin/env bash
# M11 · 工作空间配置 + 跨集群导出（设计 §8/§9/§10，契约 §2.1/§2.2/§4；DoD：e2e 脚本 workspace-config）
# 覆盖：
#   config 面：runtime-defaults/templates/llm/registry 权限矩阵（member GET 200 / PUT 403）、
#              敏感键掩码（hasValue 不回值）、未知键/L3 派生键/空 values 400、
#              mcp-connectors GET 200（读=连接器列表）/PUT 501（v1 未实现）
#   export 面：源包视图/冻结标记/标记列表/预填（派生键形状 + 掩码）、导出参数键与
#              陌生 marker 负例 400、创建导出单 201 building →（直推 127.0.0.1:1 拒绝型
#              地址秒级失败）failed 带原因、导出记录区列表/详情/values.yaml 下载头、
#              终态 failed 推进拒绝 400（building 窗口有竞态，勿在此断言）
# LLM 无关：Agent 走 import 模式不勾模型连接（agentgen.go CreateAgentImport 包自带 env 场景）
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M11 工作空间配置 + 导出"

WS=$(seed_ws "$TOKEN" "m11-ws-$TS")
U1=$(seed_user "$TOKEN" "m11-owner-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"owner\"}" "" "$TOKEN" >/dev/null
OT=$(req POST /auth/login "{\"username\":\"m11-owner-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "owner 登录" "$([ -n "$OT" ] && echo yes || echo no)" "yes"
U2=$(seed_user "$TOKEN" "m11-member-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U2,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
MT=$(req POST /auth/login "{\"username\":\"m11-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "member 登录" "$([ -n "$MT" ] && echo yes || echo no)" "yes"

M11_SVC=""
M11_SVC2=""
M11_PKG=""
cleanup_m11() {
  [ -n "$M11_SVC" ] && code DELETE /console/workspaces/$WS/agents/$M11_SVC "" "$OT" >/dev/null 2>&1
  [ -n "$M11_SVC2" ] && code DELETE /console/workspaces/$WS/agents/$M11_SVC2 "" "$OT" >/dev/null 2>&1
  [ -n "$M11_PKG" ] && legacy_code DELETE /packages/$M11_PKG >/dev/null 2>&1
  rm -f /tmp/m11-pkg-$TS.zip /tmp/m11-create-$TS.json /tmp/m11-create2-$TS.json \
    /tmp/m11-values-$TS.yaml /tmp/m11-values2-$TS.yaml /tmp/m11-vhdr-$TS.txt
}
trap cleanup_m11 EXIT

CFG=/console/workspaces/$WS/config

# ---- config 面：权限矩阵（GET=成员，PUT=owner/admin，requireWsRole）----
assert_eq "member PUT runtime-defaults 403" "$(code PUT $CFG/runtime-defaults '{"values":{"REGISTRY_ADDRESS":"reg:5000"}}' "" "$MT")" "403"
assert_eq "member PUT templates/llm 403" "$(code PUT $CFG/templates/llm '{"values":{"LLM_BASE_URL":"https://x/v1"}}' "" "$MT")" "403"
assert_eq "member PUT registry 403" "$(code PUT $CFG/registry '{"values":{"ROUTING_MODE":"host"}}' "" "$MT")" "403"
assert_eq "member PUT mcp-connectors 403" "$(code PUT $CFG/mcp-connectors '{"connectors":[]}' "" "$MT")" "403"
assert_eq "member GET runtime-defaults 200" "$(code GET $CFG/runtime-defaults "" "$MT")" "200"
assert_eq "member GET templates/llm 200" "$(code GET $CFG/templates/llm "" "$MT")" "200"
assert_eq "member GET templates/sandbox 200" "$(code GET $CFG/templates/sandbox "" "$MT")" "200"
assert_eq "member GET registry 200" "$(code GET $CFG/registry "" "$MT")" "200"
assert_eq "member GET mcp-connectors 200" "$(code GET $CFG/mcp-connectors "" "$MT")" "200"

# ---- config 面：三态写入 + 敏感掩码 + 键空间校验 ----
PW="m11-prod-pw-$TS"
assert_eq "owner PUT 敏感键 200" "$(code PUT $CFG/runtime-defaults "{\"values\":{\"CHECKPOINT_PASSWORD\":\"$PW\"}}" "" "$OT")" "200"
RDBG=$(req GET $CFG/runtime-defaults "" "$OT")
assert_contains "敏感键 hasValue+掩码（Value 不回填）" "$RDBG" '"CHECKPOINT_PASSWORD":{"hasValue":true,"sensitive":true'
assert_not_contains "敏感值不回显" "$RDBG" "$PW"
assert_eq "PUT 未知键 400" "$(code PUT $CFG/runtime-defaults '{"values":{"NOT_A_RUNTIME_KEY":"x"}}' "" "$OT")" "400"
assert_eq "PUT L3 派生键 400" "$(code PUT $CFG/runtime-defaults '{"values":{"CHECKPOINT_JDBC_URL":"jdbc:mysql://x/db"}}' "" "$OT")" "400"
assert_eq "PUT 空 values 400" "$(code PUT $CFG/runtime-defaults '{"values":{}}' "" "$OT")" "400"
assert_eq "owner PUT mcp-connectors 501（v1 未实现）" "$(code PUT $CFG/mcp-connectors '{"connectors":[]}' "" "$OT")" "501"

# registry 组写入导出前置（为断言组二铺 L1）：拒绝型直推地址 + host 路由 + 白名单
assert_eq "owner PUT registry 200" "$(code PUT $CFG/registry '{"values":{"REGISTRY_ADDRESS":"http://127.0.0.1:1","ROUTING_MODE":"host","ROUTING_HOST_SUFFIX":".m11-export.test","BASE_IMAGE_WHITELIST":"busybox:1.36|busybox"}}' "" "$OT")" "200"
REGVIEW=$(req GET $CFG/registry "" "$OT")
assert_contains "registry 视图回填非敏感值" "$REGVIEW" '"REGISTRY_ADDRESS":{"value":"http://127.0.0.1:1","hasValue":true,"sensitive":false'
assert_contains "registry 视图含白名单键（runtime 分类）" "$REGVIEW" '"BASE_IMAGE_WHITELIST"'

# ---- 前置：import 模式 Agent（不勾模型连接，LLM 无关；发布异步进行，导出面只需 DB 行）----
python3 - <<PYEOF
import zipfile
md = """---
name: M11 Export Probe
vendorKey: acme
agentKey: m11-probe-$TS
version: 1.0.0
slug: acme/m11-probe-$TS
description: workspace config and export e2e probe
author: "@e2e"
license: MIT
---
M11 导出探针。"""
with zipfile.ZipFile("/tmp/m11-pkg-$TS.zip", "w") as z:
    z.writestr("AGENTS.md", md)
PYEOF
UP=$(curl -s -X POST "$BASE/console/workspaces/$WS/packages" -H "Authorization: Bearer $OT" -F "file=@/tmp/m11-pkg-$TS.zip")
IPKG=$(echo "$UP" | jqr "['data']['id']")
assert_eq "空间包上传" "$([ -n "$IPKG" ] && [ "$IPKG" != "None" ] && echo yes || echo no)" "yes"
M11_PKG="$IPKG"
IMPORT=$(req POST /console/workspaces/$WS/agents \
  "{\"mode\":\"import\",\"name\":\"m11-probe-$TS\",\"importPackageId\":$IPKG,\"replicas\":1,\"publicEndpoint\":false}" "" "$OT")
SVC=$(echo "$IMPORT" | jqr "['data']['service']['id']")
assert_eq "import 创建返回 service" "$([ -n "$SVC" ] && [ "$SVC" != "None" ] && echo yes || echo no)" "yes"
M11_SVC="$SVC"
SHORT=$(req GET /console/workspaces/$WS/agents/$SVC "" "$OT" | jqr "['data']['shortName']")
assert_eq "shortName 同源 agentKey" "$SHORT" "m11-probe-$TS"

EXP=/console/workspaces/$WS/agents/$SVC/export

# ---- export 面：源包视图 / 冻结 / 标记列表 / 预填（契约 §4.1，成员可读写）----
assert_eq "GET export/package 200" "$(code GET $EXP/package "" "$OT")" "200"
PKGVIEW=$(req GET $EXP/package "" "$OT")
assert_contains "源包视图含 AGENTS.md" "$PKGVIEW" '"path":"AGENTS.md"'
assert_contains "源包视图含 slug" "$PKGVIEW" "m11-probe-$TS"
assert_eq "member 冻结标记 200" "$(code POST $EXP/package/mark "" "$MT")" "200"
MARKS=$(req GET $EXP/package/marks "" "$OT")
assert_contains "标记列表含 frozen 状态" "$MARKS" '"status":"frozen"'
MARKER=$(echo "$MARKS" | jqr "['data'][0]['marker']")
assert_eq "marker 形状 1.0.0-e1" "$MARKER" "1.0.0-e1"
PREVIEW=$(req GET $EXP/preview "" "$OT")
assert_contains "预填标记列表含新标记" "$PREVIEW" '"marker":"1.0.0-e1"'
assert_contains "预填派生 redisPrefix={short}:" "$PREVIEW" "\"redisPrefix\":\"$SHORT:\""
assert_contains "预填 L1 未配基库时 checkpointDB 为空" "$PREVIEW" '"checkpointDB":""'
assert_contains "预填镜像引用形状（{marker} 占位）" "$PREVIEW" '"oafImageRef":"http://127.0.0.1:1/oaf-'"$SHORT"':{marker}"'
assert_contains "lockedKeys 升序全列（L1 已配置 5 键）" "$PREVIEW" \
  '"lockedKeys":["BASE_IMAGE_WHITELIST","CHECKPOINT_PASSWORD","REGISTRY_ADDRESS","ROUTING_HOST_SUFFIX","ROUTING_MODE"]'
assert_not_contains "预填不含敏感值" "$PREVIEW" "$PW"

# ---- export 面：创建导出单负例 ----
assert_eq "env 携带导出参数键 400（L1 锁定）" \
  "$(code POST $EXP "{\"marker\":\"$MARKER\",\"env\":{\"REGISTRY_ADDRESS\":\"http://evil:5000\"}}" "" "$OT")" "400"
assert_eq "陌生 marker 400（无 frozen 标记不可构建）" "$(code POST $EXP '{"marker":"9.9.9-e99"}' "" "$OT")" "400"
# chartVersion 注入 400（issue #226）：JSON "\n" 转义为真实换行，试图顶出顶层 YAML 键；
# NEL(U+0085)/LS(U+2028)/PS(U+2029) 多字节 Unicode 行终止符（yaml.v3 全集）同样不在
# chartVersionRe 的 ASCII 字符类内，一律 400（DisplayName 向量不在此拦，见下方产物级校验）
assert_eq "chartVersion 注入 400（issue #226）" \
  "$(code POST $EXP "{\"marker\":\"$MARKER\",\"chartVersion\":\"\\nresources: {} #\"}" "" "$OT")" "400"
assert_eq "chartVersion NEL(U+0085) 注入 400（issue #226）" \
  "$(code POST $EXP "{\"marker\":\"$MARKER\",\"chartVersion\":\"\\u0085resources: {} #\"}" "" "$OT")" "400"
assert_eq "chartVersion LS(U+2028) 注入 400（issue #226）" \
  "$(code POST $EXP "{\"marker\":\"$MARKER\",\"chartVersion\":\"\\u2028resources: {} #\"}" "" "$OT")" "400"
assert_eq "chartVersion PS(U+2029) 注入 400（issue #226）" \
  "$(code POST $EXP "{\"marker\":\"$MARKER\",\"chartVersion\":\"\\u2029resources: {} #\"}" "" "$OT")" "400"

# ---- export 面：正例创建 → building → failed（拒绝型地址秒级失败）----
CCODE=$(curl -s -o /tmp/m11-create-$TS.json -w '%{http_code}' -X POST "$BASE$EXP" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $MT" -d "{\"marker\":\"$MARKER\"}")
assert_eq "创建导出单 201" "$CCODE" "201"
ORDER=$(jqr "['data']['id']" < /tmp/m11-create-$TS.json)
assert_eq "初始状态 building" "$(jqr "['data']['status']" < /tmp/m11-create-$TS.json)" "building"

# 轮询 ≤120s：pullBase（docker.io 可达性）或 push（127.0.0.1:1 拒绝）哪步先失败均可；
# 不校验失败原因文案。exportBuildTimeout=10min，勿用挂起型地址。
FINAL=""
DEADLINE=$((SECONDS + 120))
while [ $SECONDS -lt $DEADLINE ]; do
  FINAL=$(req GET /console/exports/$ORDER "" "$OT" | jqr "['data']['status']")
  { [ "$FINAL" = "failed" ] || [ "$FINAL" = "built" ]; } && break
  sleep 3
done
MANUAL_FAILED=0
if [ "$FINAL" != "failed" ] && [ "$FINAL" != "built" ]; then
  # 出口挂起兜底：building→failed 为合法迁移（exportAdvanceAllow），保底推进让后续断言可继续
  MANUAL_FAILED=1
  req POST /console/exports/$ORDER/status '{"status":"failed"}' "" "$OT" >/dev/null
  FINAL="failed"
  skip "构建 120s 内未失败（出口拉取慢），人工 failed 保底——失败原因断言跳过"
fi
assert_eq "导出单终态 failed" "$FINAL" "failed"
if [ "$MANUAL_FAILED" = "0" ] && [ "$FINAL" = "failed" ]; then
  ERRMSG=$(req GET /console/exports/$ORDER "" "$OT" | jqr "['data']['error']")
  assert_eq "failed 带非空原因" "$([ -n "$ERRMSG" ] && [ "$ERRMSG" != "None" ] && echo yes || echo no)" "yes"
fi

# ---- export 面：导出记录区（issue #216）/ 详情 / values.yaml 下载 / 终态推进 ----
ORDERS=$(req GET $EXP/orders "" "$OT")
assert_contains "导出记录区含该单" "$ORDERS" "\"id\":$ORDER"
assert_contains "记录区 marker 独立列" "$ORDERS" '"marker":"1.0.0-e1"'
assert_contains "记录区状态跟随 failed" "$ORDERS" '"status":"failed"'
DETAIL=$(req GET /console/exports/$ORDER "" "$OT")
assert_contains "详情状态 failed" "$DETAIL" '"status":"failed"'
assert_contains "详情镜像引用含 marker tag" "$DETAIL" '"oafImageRef":"http://127.0.0.1:1/oaf-'"$SHORT"':1.0.0-e1"'
VCODE=$(curl -s -o /tmp/m11-values-$TS.yaml -D /tmp/m11-vhdr-$TS.txt -w '%{http_code}' \
  "$BASE/console/exports/$ORDER/values.yaml" -H "Authorization: Bearer $MT")
assert_eq "values.yaml 下载 200（渲染产物创建时落库，failed 态亦可下载）" "$VCODE" "200"
assert_contains "下载头 attachment" "$(cat /tmp/m11-vhdr-$TS.txt)" 'attachment; filename="values.yaml"'
assert_contains "values 含服务短名" "$(cat /tmp/m11-values-$TS.yaml)" "fullnameOverride: $SHORT"
assert_eq "member 推进 403" "$(code POST /console/exports/$ORDER/status '{"status":"approving"}' "" "$MT")" "403"
assert_eq "终态 failed 推进 400（无出边）" "$(code POST /console/exports/$ORDER/status '{"status":"approving"}' "" "$OT")" "400"

# ---- export 面：DisplayName 携带行终止符（issue #226 渲染层兜底，values 产物级校验）----
# DisplayName 仅「非空 + ≤128 rune」校验（agentgen.go/publish.go），NEL/LS/PS 多字节
# 终止符是合法 rune，创建侧 200 不拦；防线在渲染层——values.yaml 注释头全部插值字段经
# sanitizeHeaderLine 单行化（LF/CR/NEL/LS/PS → 空格）。独立第二个 Agent（不动主夹具：
# 主服务 shortName 断言依赖 name 与 agentKey 同形），values.yaml 创建时同步落库
# （building 态即可下载，无需等构建终态）
IMPORT2=$(req POST /console/workspaces/$WS/agents \
  "{\"mode\":\"import\",\"name\":\"m11-inject-$TS\\u0085resources: {limits: {cpu: 9}} #\\u2028inj-ls\\u2029inj-ps\",\"importPackageId\":$IPKG,\"replicas\":1,\"publicEndpoint\":false}" "" "$OT")
SVC2=$(echo "$IMPORT2" | jqr "['data']['service']['id']")
assert_eq "多字节 DisplayName 服务创建成功（创建侧不拦，兜底在渲染层）" \
  "$([ -n "$SVC2" ] && [ "$SVC2" != "None" ] && echo yes || echo no)" "yes"
M11_SVC2="$SVC2"
EXP2=/console/workspaces/$WS/agents/$SVC2/export
assert_eq "注入服务冻结标记 200" "$(code POST $EXP2/package/mark "" "$OT")" "200"
MARKER2=$(req GET $EXP2/package/marks "" "$OT" | jqr "['data'][0]['marker']")
assert_eq "注入服务 marker 形状 1.0.0-e1" "$MARKER2" "1.0.0-e1"
CCODE2=$(curl -s -o /tmp/m11-create2-$TS.json -w '%{http_code}' -X POST "$BASE$EXP2" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $OT" -d "{\"marker\":\"$MARKER2\"}")
assert_eq "注入服务创建导出单 201" "$CCODE2" "201"
ORDER2=$(jqr "['data']['id']" < /tmp/m11-create2-$TS.json)
VCODE2=$(curl -s -o /tmp/m11-values2-$TS.yaml -w '%{http_code}' \
  "$BASE/console/exports/$ORDER2/values.yaml" -H "Authorization: Bearer $OT")
assert_eq "注入服务 values.yaml 下载 200" "$VCODE2" "200"
# 产物级断言（字节级）：①不得残留任何 yaml.v3 行终止符（全部单行化为空格）；
# ②注入载荷必须出现在注释头行内（证明该链路真实流经渲染）；③按 \n 物理分行后
# 不得有以 resources: 开头的独立 YAML 行
CHK2=$(python3 - /tmp/m11-values2-$TS.yaml <<'PYEOF'
import sys
data = open(sys.argv[1], "rb").read()
terms = {"NEL(U+0085)": b"\xc2\x85", "LS(U+2028)": b"\xe2\x80\xa8", "PS(U+2029)": b"\xe2\x80\xa9"}
leaked = [n for n, b in terms.items() if b in data]
assert not leaked, "terminators leaked into values.yaml: %s" % leaked
assert b"resources: {limits: {cpu: 9}} #" in data, "injected payload missing from header (unexpected)"
standalone = [l for l in data.split(b"\n") if l.startswith(b"resources:")]
assert not standalone, "injected content became standalone YAML lines: %r" % standalone[:3]
print("OK")
PYEOF
)
assert_contains "values.yaml 无行终止符残留且载荷行内" "$CHK2" "OK"

finish "M11-workspace-config"
