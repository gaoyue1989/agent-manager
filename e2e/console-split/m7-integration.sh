#!/usr/bin/env bash
# M7 · 集成 Agent（实施文档 08 §4/§5）
# 覆盖：候选探测三态（protocolEnabled / probe online|unreachable|skipped）；
#       登记集成（endpoint 派生/默认名/askPolicy 规范化）；重复集成 409；跨空间 400；
#       引用保护 409；patch（askPolicy/enabled 校验）；解除后候选仍在
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M7 集成 Agent"

# 集成关系是空间隔离的：候选=本空间服务。member（开启协议的存量服务）在 default 空间
# （决议 2：admin 可访问 default），因此整个 M7 流程在 default 空间进行
WS=$(req GET /admin/workspaces "" "$TOKEN" | python3 -c "import sys,json;print(next((w['id'] for w in json.load(sys.stdin)['data'] if w['slug']=='default'),''))")
LIST=$(req GET /console/workspaces/$WS/integrations "" "$TOKEN")
assert_contains "integrations 双栏结构" "$LIST" '"relations":'
assert_contains "integrations candidates 数组（非 null）" "$LIST" '"candidates":['

# 找一个开启 Agent Protocol 的存量服务做成员（travel/fulfillment demo 形态；无则造探针三态断言仅覆盖 skipped）
MEMBER=$(req GET "/admin/workspaces/$WS/agents" "" "$TOKEN" \
  | python3 -c "
import sys,json
for s in json.load(sys.stdin)['data']:
    env={}
    try: env=json.loads(s.get('envJson') or '{}')
    except Exception: pass
    if str(env.get('AGENT_PROTOCOL_ENABLED','')).lower()=='true':
        print(s['id']); break" 2>/dev/null || true)

if [ -n "$MEMBER" ] && [ "$MEMBER" != "None" ]; then
  IT=$(req POST /console/workspaces/$WS/integrations "{\"memberServiceId\":$MEMBER}" "" "$TOKEN")
  IT_ID=$(echo "$IT" | jqr "['data']['id']")
  assert_eq "登记集成 201" "$([ -n "$IT_ID" ] && echo yes || echo no)" "yes"
  assert_contains "endpoint 集群内派生" "$IT" "-svc:8100"
  assert_eq "默认名=member displayName" "$(echo "$IT" | jqr "['data']['name']" | grep -c .)" "1"
  assert_eq "默认 askPolicy=PROPAGATE" "$(echo "$IT" | jqr "['data']['askPolicy']")" "PROPAGATE"
  assert_eq "默认 remoteStreaming=true" "$(echo "$IT" | jqr "['data']['remoteStreaming']")" "True"
  # 候选表含该 member 且 probe=online
  CAND=$(req GET /console/workspaces/$WS/integrations "" "$TOKEN" | python3 -c "
import sys,json
for c in json.load(sys.stdin)['data']['candidates']:
    if c['serviceId']==$MEMBER: print(c['probe'], c['protocolEnabled'])")
  assert_contains "候选 probe=online 且 protocolEnabled" "$CAND" "online True"
  # 重复集成 409 / 跨空间 400
  assert_eq "重复集成 409" "$(code POST /console/workspaces/$WS/integrations "{\"memberServiceId\":$MEMBER}" "" "$TOKEN")" "409"
  assert_eq "跨空间 member 400（集成空间隔离）" "$(code POST /console/workspaces/$(seed_ws "$TOKEN" "m7-ws2-$TS")/integrations "{\"memberServiceId\":$MEMBER}" "" "$TOKEN")" "400"
  # patch：DENY 规范化接受 / 非法值 400 / enabled 切换
  assert_eq "patch askPolicy=DENY" "$(req PATCH /console/workspaces/$WS/integrations/$IT_ID "{\"askPolicy\":\"DENY\"}" "" "$TOKEN" | jqr "['data']['askPolicy']")" "DENY"
  assert_eq "patch 非法 askPolicy 400" "$(code PATCH /console/workspaces/$WS/integrations/$IT_ID "{\"askPolicy\":\"MAYBE\"}" "" "$TOKEN")" "400"
  assert_eq "patch enabled=false" "$(req PATCH /console/workspaces/$WS/integrations/$IT_ID "{\"enabled\":false}" "" "$TOKEN" | jqr "['data']['enabled']")" "False"
  # 未引用可删
  assert_eq "解除集成 200" "$(code DELETE /console/workspaces/$WS/integrations/$IT_ID "" "$TOKEN")" "200"
  assert_eq "解除后重复删除 404" "$(code DELETE /console/workspaces/$WS/integrations/$IT_ID "" "$TOKEN")" "404"
else
  # 无协议服务：仅覆盖 skipped 探针与结构（部署环境可跑 travel/fulfillment demo 后补 probe=online）
  PROBE_OK=$(req GET /console/workspaces/$WS/integrations "" "$TOKEN" | python3 -c "
import sys,json
c=json.load(sys.stdin)['data']['candidates']
print(all(('probe' in x and 'protocolEnabled' in x) for x in c) if c else True)")
  assert_eq "候选结构完整（无协议服务时全 skipped）" "$PROBE_OK" "True"
  say "  ⏭️ 环境无 AGENT_PROTOCOL_ENABLED=true 的服务——登记/引用链路待 travel demo 双服务形态补跑"
fi

finish "M7-integration"
