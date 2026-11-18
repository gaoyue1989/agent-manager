# M9 · P2 网关鉴权（router auth_request + introspect + API Key 数据面生效）

| | |
|---|---|
| 依赖 | M0–M8（本模块是二期独立立项，一期全部完成后开工） |
| 对应设计 | §5.4 数据面身份与网关鉴权（二期）、§7.1 introspect、§13 决议 3 |
| demo 对照 | 无新页面（后台链路；凭证页警示条「二期生效」兑现） |
| 修订 | 2026-11 issue #373：off 档语义修订为「免鉴权但不免身份」+ introspect 会话 cookie 通道 + 分档拦截重构（见 §7） |

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：introspect 双通道（JWT/API Key）、作用域裁决、last_used_at 节流写全绿（`cd backend && make test`）；router 配置以 `nginx -t` 渲染校验进 CI；②e2e（独立环境配置，enforce 三态）：无凭据 401 / 带凭据 200 且 `X-User-Id` 正确 / 跨作用域 403 / 吊销即时 403 + 四必需 job 带凭据回归全绿；report 灰度观察 48h 指标为上线前置（附记录）；③§5 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：`GET /api/v1/auth/introspect`（JWT 或 API Key 双通道校验）。
- platform-router：nginx `auth_request` 子请求接入。
- 生效后变化：直连 `/agent/{short}/*`（绕过 frontend）的调用也需鉴权；API Key 对外调用真正生效（M8 签发的 key 在此闭环）。

## 2. backend：introspect

```
GET /api/v1/auth/introspect          （router 内网调用；免 JWT——由 auth_request 附带的凭据本身裁决）
凭据来源：Authorization: Bearer <jwt>  或  Bearer ak-…
  → 200 {userId:"u-42", role:"user", scope:{workspaceId:3, agentId:null}}   // router 注入 X-User-Id 后放行
  → 401 {message:"invalid credential"}    // 无/坏凭据
  → 403 {message:"key revoked"}           // 吊销/过期 key
```

- JWT 通道：复用 M0 校验；scope 从 claims 直取（workspaceId 进 JWT payload 需 M0 预留——**回填要求**：M0 签发时 claims 加 `ws`（当前默认空间），切换空间由前端带 `X-Workspace-Id` 请求头而非重签，introspect 只验身份不验空间）；
- API Key 通道：sha256 查 `api_keys`（active + 未过期 + 作用域裁决：`agentId` 非空时仅该 short 的路由放行）；命中更新 `last_used_at`（异步 goroutine + 1s 节流写，避免热路径写放大；**不做**后台任务）。

## 3. platform-router：auth_request 接入

`manifests/platform-router.yaml`（nginx 配置）追加：

```nginx
# 每服务路由 location /agent/{short}/ 内追加：
auth_request /_auth;
auth_request_set $x_user_id $upstream_http_x_user_id;
proxy_set_header X-User-Id $x_user_id;

location = /_auth {
    internal;
    proxy_pass http://oaf-platform-backend.agent-platform.svc.cluster.local:8080/api/v1/auth/introspect;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header Authorization $http_authorization;
    # backend 以 X-User-Id 响应头回传身份
}
```

- **fail 策略开关**：`ROUTER_AUTH_MODE=off|report|enforce`（ConfigMap 注入 nginx 变量）：off=不挂 auth_request（升级窗口默认）；report=401 只记日志放行（灰度观察）；enforce=全量拒绝。三档渐进上线，回滚 = 改配置 reload，不发版；
- 兼容矩阵：`X-User-Id` 已由请求方显式携带时**以鉴权结果覆盖**（不信任客户端自报身份）；
- SSE：auth_request 仅握手时一次，不影响长流；`proxy_request_buffering off` 保持。

## 4. 需要同步的调用方

| 调用方 | 改造 |
|--------|------|
| frontend proxy（M2） | 透传 `Authorization` cookie→header 不需要：auth_request 读的是 `Authorization`；frontend 反代时把 `oaf_session` cookie 转为 `Authorization: Bearer <jwt>` 头再 rewrite（proxy.ts 一处改动） |
| release-agent MCP 调 backend `/mcp` | 不受影响（`/mcp` 不走 router） |
| A2A 注册拉卡（backend→svc 直连） | 不受影响（集群内 svc 直连，不经 router） |
| e2e 脚本 | enforce 前补 API Key 签发步骤（走 console api-keys 端点）+ 请求带 Bearer |
| 发布助手对外文档示例（curl） | 更新为带 `Authorization: Bearer ak-…` |

## 5. 验收标准

- [ ] enforce 模式：无凭据直连 `curl {host}/agent/{short}/threads/chat` → 401；带有效 JWT / API Key → 200 且业务 Pod 收到正确 `X-User-Id`。
- [ ] API Key 作用域：绑定单 Agent 的 key 访问其它 Agent 路由 403。
- [ ] 吊销即时生效（下一次请求 403）；`last_used_at` 更新。
- [ ] report 模式：全量放行 + 日志可统计未鉴权流量比例（灰度 48h 观察指标）。
- [ ] e2e 四必需 job 在 enforce 集群配置下仍绿（脚本带凭据后）。
- [ ] 业务面零改动复核：agent-framework 不发版。

## 6. 风险与回退

- router 单点配置错误影响全部业务路由 → 上线走 report 灰度 + `ROUTER_AUTH_MODE` 一键回 off；
- introspect 不可用 = 全量 401（fail-closed）——backend 是发布链路必需组件，此依赖在设计 §11 已评审接受；仍建议 router 对 `_auth` 加 5s 超时 + 一次重试。

## 7. 修订记录（issue #373，2026-11）

落地三态语义与拦截机制的承载真相源是 `manifests/platform-router.yaml` / `charts/.../router-configmap.yaml` 模板头注释；行为级断言真相源是 `e2e/scripts/router-auth-modes-verify.sh`（nginx 真容器 + body 级 marker，manifests 与 helm 渲染物双源各 13 案）。此处只记结论与防重蹈：

- **off 档语义修订：「免鉴权但不免身份」**。`/_auth` 不再直接 return 200（原实现跳过 introspect，`$auth_uid` 纯透传客户端自报），三档共用纯 introspect 子请求：浏览器只有 httpOnly `oaf_session` cookie、无自报头，introspect 增加 cookie 回落通道（header 优先、cookie 回落，backend `service.SessionCookieName` 单一真相源）注入 `X-User-Id`；introspect 失败/不可用时放行并回落自报（fail-open，业务面恒达）。此前 off 档经统一入口 :8911 的 threads 回放/删除、HITL confirm-stream、skills/users 因 SessionAccessGuard 匿名 fail-closed 404/400，即此根因。
- **report 档死代码修复**：原模板 `if ($router_auth_mode = "report") { error_page ... }` 实测 if 块内 error_page 不生效（401 原样透传拒绝），report「放行观察」语义从未落地；三态语义本次经分档 include 片段才真正生效——report 现网未启用（默认 off），无存量影响。
- **拦截机制承载点（防重蹈）**：分档拦截挂 `/_auth` 子请求内（`proxy_intercept_errors` + `error_page ... =200 @off_allow/@report_allow`，经 `include /etc/nginx/auth-mode/auth-${ROUTER_AUTH_MODE}.conf` 渲染期三选一）。**「数据面 location 挂 error_page」实测证伪**：状态码 200 但响应体为空、业务 upstream 未被调用（假放行）——本模块的 nginx 行为实验（docker nginx:1.27.1-alpine + body 级断言）确认唯一同时满足「fail-open + 业务恒达 + 身份正确」的落法是子请求内拦截；error_page if 块内不生效 / 目标变量化无效 / auth_request 不支持变量 URI 同为实验结论。
- **身份信任链**：`$auth_uid` 改组合源 map（模式源必须是 envsubst 字面量 `${ROUTER_AUTH_MODE}`，请求变量做模式源实测被子请求求值污染）：off 无鉴权结果回落自报、off 有结果 / report+enforce 一律以 introspect 结果覆盖客户端自报（M9 §4 语义不变）。
- **连带行为变化**：report 坏凭据从实际 401 变为真放行（死代码修复的语义兑现）；off 档新增每数据面请求一次 introspect 子请求（无凭据走 backend 401 快速路径无 DB 查询，有凭据 JWT 通道每请求一次用户查询）；经统一入口的调试/回放会话归属从匿名 debug-user 变为登录用户 `u-{uid}`。
