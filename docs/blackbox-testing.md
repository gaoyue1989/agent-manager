# 黑盒测试配置文档（控制台拆分版本）

> 适用分支：`feat/console-user-workspace-split`（控制面拆分：用户侧 console + 运维侧 admin + 存量 legacy 面不变）。
> 面向黑盒测试人员：只描述外部可见行为（入口/账号/端点/预期状态码），不涉及内部实现。
> 端点与凭据均经 2026-10-17 kind 集群实测核对；如环境重建后值有变，以 `manifests/platform.yaml` 为准。

## 1. 环境入口

宿主机唯一统一入口为 **:8911**（nginx）与 **:30080**（kind ingress-nginx 直映），两者等价：

| 入口 | 地址 | 说明 |
|------|------|------|
| 统一入口（宿主 nginx） | http://localhost:8911 | 推荐；前端 UI + REST + MCP + 业务 Agent 全覆盖 |
| ingress-nginx 直连 | http://localhost:30080 | kind `extraPortMappings`（30080/30443）；测试脚本可直接用 |
| 前端登录页 | http://localhost:8911/login | 未登录访问 `/` 会 307 重定向到 `/login` |
| REST API base | http://localhost:30080/api/v1 | 下文所有端点均以此为前缀 |
| MCP | http://localhost:30080/mcp | 运维发布助手（AUTH_TOKEN 现状未启用，见 §3） |
| 业务 Agent 数据面 | http://localhost:30080/agent/{short}/ | 经 platform-router 剥前缀；调试/会话 SSE 走这里 |

> ⚠️ 仓库 manifests 中 backend/frontend 的 NodePort 30880/30881 **在当前部署未生效**（当前集群内 Service 为 ClusterIP 型 `oaf-platform-backend` / `oaf-platform-frontend`），直连会超时。一律走 8911 或 30080。

快速健康自检：

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:30080/healthz   # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8911/login      # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8911/           # 307（未登录守卫重定向）
```

## 2. 测试账号

首个 admin 由种子环境变量创建（仅 users 表为空时生效），当前环境取值：

| 项 | 值 | 来源 |
|----|----|------|
| admin 账号 | `admin` | `ADMIN_INIT_USER` |
| admin 密码 | `OafConsole2026` | `ADMIN_INIT_PASSWORD`（manifests 默认值，非生产密钥） |
| 会话 Cookie | `oaf_session`（httpOnly，SameSite=Lax） | 登录成功后自动下发；API 调用也可用 Bearer token 替代 |

普通用户没有预置种子，由 admin 在「运维侧 → 用户管理」或 `POST /api/v1/admin/users` 创建（测试自建即可，见 §6 示例）。

登录验证：

```bash
# 成功：200，data.token 为 JWT（同时种下 oaf_session cookie）
curl -s -X POST http://localhost:30080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"OafConsole2026"}'

# 错误密码：401 {"code":1,"message":"invalid username or password"}
# 被禁用账号：403
```

角色语义（测试用例设计的权限边界）：

- **用户角色** `admin | user`：admin 可进运维侧与**任意**工作空间；user 仅本人所属空间。
- **空间成员角色** `owner | admin | member`：作用于单个工作空间内——owner/admin 可写（创建/编辑/删除 Agent 等），**member 只读 + 调试**（写操作后端 403，前端不渲染写入口，设计 §5.1）。

## 3. 鉴权矩阵（路由分层）

| 端点前缀 | 鉴权方式 | 匿名访问预期 |
|----------|----------|--------------|
| `/api/v1/auth/login` `logout` | 无 | 200 可访问 |
| `/api/v1/auth/me` | JWT（Bearer 或 `oaf_session` cookie） | 401 |
| `/api/v1/auth/introspect` | 数据面专用：`Authorization: Bearer <JWT>` 或 `Bearer ak-…`（API Key） | 按无凭据语义返回 401/403 |
| `/api/v1/console/*` | JWT（任意角色）+ 空间成员裁决 | 401；无成员关系的空间 404 |
| `/api/v1/admin/*` | JWT（**admin 角色**，按 DB 实时角色/状态裁决）或 `AUTH_TOKEN` | 401；user 角色访问 403；admin 被降级/禁用后**旧 token 即时 403**（删号 401，issue #148） |
| `/api/v1/{services,packages,images,platform-config}` | `AUTH_TOKEN`（存量面，语义不变） | **当前环境未配置 AUTH_TOKEN → 匿名放行**（现状行为，配置后变 401） |
| `/mcp` | 同上 | 当前匿名放行 |
| `/agent/{short}/*`（数据面） | 网关三态 `ROUTER_AUTH_MODE`（见下） | 当前 `off` → 不鉴权 |

关键开关（测试前向运维确认当前档位）：

| 环境变量 | 当前值 | 影响 |
|----------|--------|------|
| `JWT_SECRET` | `oaf-console-jwt-secret-CHANGE-ME`（占位） | 缺失时 backend 拒绝启动；更换后所有已签发 token 失效（全员重新登录） |
| `AUTH_TOKEN` | **未配置** | legacy 面 + `/mcp` 匿名可用；如配置，未带凭据一律 401 |
| `ROUTER_AUTH_MODE`（platform-router） | `off` | `off`=不鉴权（历史行为）；`report`=只记日志不拦截；`enforce`=数据面强制 introspect（无凭据 401 / 跨作用域 403） |

## 4. API 端点清单

响应包络统一为 `{code, message, data}`（成功 `code:0`）；错误消息英文小写开头，前端按 `message` 直出。

### 4.1 auth（认证）

| 方法+路径 | 说明 | 预期 |
|-----------|------|------|
| `POST /auth/login` | body `{username, password}`；成功返回 `{token, user, workspaces}` 并种 cookie | 200 / 401 / 403(禁用) |
| `POST /auth/logout` | 清 cookie（JWT 无状态，语义性登出） | 200 |
| `GET /auth/me` | 当前用户 + 所属工作空间列表 | 200（需 JWT）/ 401 |
| `GET /auth/introspect` | 网关 introspect：JWT 校验签名/过期；`ak-` 前缀走 API Key（active+未过期）；经 `X-Forwarded-*` 回写身份 | 见 M9 三态语义 |

### 4.2 admin（运维侧；JWT-admin 或 AUTH_TOKEN）

| 方法+路径 | 说明 |
|-----------|------|
| `POST /admin/users` | 建用户，body `{username, password, displayName, role}`（role 缺省 `user`；重名 400 `duplicate_username`） |
| `GET /admin/users` | 用户列表 |
| `PATCH /admin/users/:id` | 改角色/状态/重置密码 |
| `POST /admin/workspaces` | 建工作空间，body `{name, ownerUserId}` |
| `GET /admin/workspaces` / `GET /admin/workspaces/:id` | 列表 / 详情 |
| `PATCH /admin/workspaces/:id` | 改名/转移 owner |
| `DELETE /admin/workspaces/:id` | 删空间（有归属服务时受保护，预期拒绝） |
| `POST /admin/workspaces/:id/bind` | 纳管存量服务（default 空间服务绑到业务空间；跨空间冲突 409） |
| `POST /admin/workspaces/:id/members` | 邀请成员；`GET :id/members` 列表；`PATCH :id/members/:uid` 改角色；`DELETE :id/members/:uid` 移除 |
| `GET /admin/workspaces/:id/agents` | 空间内 Agent（服务）清单 |
| `GET /admin/audit-logs` | 审计日志 |

### 4.3 console（用户侧；JWT，路径携带空间 id）

路径模式：`/api/v1/console/workspaces/{wsId}/...`（`wsId` 为数字空间 id；user 访问非成员空间返回 404，admin 隐式可见所有空间）。

| 组 | 方法+路径 |
|----|-----------|
| 概览 | `GET .../workspaces/:ws/summary` |
| Agent（服务） | `GET .../agents`；`POST .../agents`（创建：生成 OAF 包并复用发布链路）；`GET/PUT .../agents/:id`（编辑为 copy-on-write 新版本）；`DELETE .../agents/:id` |
| Agent 动作 | `POST .../agents/:id/{publish,unpublish,register,republish}` |
| 版本 | `GET .../agents/:id/versions`；`POST .../agents/:id/versions/:pkgId/upgrade`（回滚升级） |
| 创建辅助 | `GET .../agents/preview-name`；`GET .../agents/:id/edit-form` |
| 包 | `GET/POST .../workspaces/:ws/packages`（列表 / 上传导入） |
| 技能/模板 | `GET .../workspaces/:ws/skills`；`GET .../workspaces/:ws/templates` |
| 模型连接 | `GET/POST .../workspaces/:ws/model-connections`；`PATCH/DELETE .../:id`；`POST .../:id/test`（连通性测试）；`POST .../:id/deliver`（下发至 Agent）；`GET .../model-connections/usage`；`GET .../model-connections/models` |
| MCP 连接器（工具） | `GET/POST .../workspaces/:ws/tools`；`PATCH/DELETE .../:id`；`POST .../:id/verify` |
| 集成（Agent Protocol） | `GET/POST/PATCH/DELETE .../workspaces/:ws/integrations(/:id)` |
| API Key（凭证） | `GET .../workspaces/:ws/api-keys`（支持 `?agentId=` 过滤）；`POST`（body `{name, agentId?, expiresInDays?}`）签发**明文仅返回一次**，格式 `ak-{8hex}-{24hex}`，列表只见 `keyPrefix`；`DELETE .../:id` 吊销 |
| 成员 | `GET/POST .../workspaces/:ws/members`；`PATCH/DELETE .../members/:uid`；`GET .../invitable-users` |

### 4.4 legacy 存量面（语义与拆分前一致）

`/api/v1/packages`（含 `:id/files`、`:id/download`、`:id/versions`、`PATCH :id/template`）、`/api/v1/services`（含 `PATCH :id/env` 全量覆盖语义、`POST :id/republish`、动作端点、`DELETE :id`）、`/api/v1/images`、`/api/v1/platform-config`（`GET /defaults` 表单默认预填、`PUT` 保存）。当前环境无 AUTH_TOKEN，匿名可访问；黑盒用例请覆盖「配置 AUTH_TOKEN 后 401」的期望（需运维配合改档）。

## 5. 前端页面路由清单

### 用户侧（(console) 布局，登录后访问）

| 路径 | 页面 |
|------|------|
| `/console` | 概览（工作空间摘要） |
| `/console/agents` | Agent 列表；`/console/agents/create` 创建页；`/console/agents/[id]` 详情（六 Tab）；`/console/agents/[id]/edit` 编辑页 |
| `/console/agents/templates` | 模板 |
| `/console/assets/models` | 模型连接（右侧抽屉新建/编辑） |
| `/console/assets/tools` | 工具管理（MCP 连接器，右侧抽屉） |
| `/console/assets/skills` / `assets/memory` / `assets/knowledge` / `assets/credentials` | 技能 / 记忆 / 知识库 / 凭证（API Key 签发一次性明文弹窗） |
| `/console/integrations` | 集成登记 |
| `/console/settings` | 用户侧设置 |

### 运维侧（(admin) 布局，仅 admin 角色可达；user 访问预期 403/重定向）

| 路径 | 页面 |
|------|------|
| `/admin/overview` / `/admin` | 运维概览 |
| `/admin/workspaces` | 工作空间管理（成员矩阵、纳管） |
| `/admin/users` | 用户管理（建号/改角色/重置密码） |
| `/admin/audit` | 审计日志 |
| `/admin/packages`（+ `/[id]`、`/[id]/edit`） | 包管理（原存量页面迁移） |
| `/admin/services/[id]` | 存量服务详情 |
| `/admin/publish` | 发布向导（归属空间下拉） |
| `/admin/assistant` | 发布助手对话 |
| `/admin/settings` | 平台默认配置（redis/mysql/llm/sandbox，仅表单默认值） |

### 重定向与守卫（M0 断言点）

- 存量 URL 自动迁移：`/services/:id` → `/admin/services/:id`、`/packages*` → `/admin/packages*`、`/publish` → `/admin/publish`、`/settings` → `/admin/settings`。
- 未登录访问受保护页 → 307 到 `/login`；登录后 user 直访 `/admin/*` 被拒。
- 工作空间切换器（左下角）切换后所有 console 页面按当前空间重新取数。

### 数据面（不进 backend）

Agent 调试/会话 SSE 走 `/agent/{short}/*`，前端 Next.js proxy 注入 `X-User-Id: u-{uid}`（会话按 `session_user` 落业务库，多副本可落任意 Pod）。黑盒验证点：同用户跨请求会话连续、不同用户会话隔离。

## 6. 典型测试链路（可直接照抄的最小请求序列）

```bash
BASE=http://localhost:30080/api/v1

# 1. admin 登录拿 token
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"OafConsole2026"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

# 2. 建普通用户（重名返回 400 duplicate_username；重复执行请换用户名）
TS=$(date +%s)
curl -s -X POST $BASE/admin/users -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json; charset=utf-8' \
  -d "{\"username\":\"bb-user-$TS\",\"password\":\"BbPass123!\",\"displayName\":\"黑盒用户\",\"role\":\"user\"}"

# 3. 建工作空间（ownerUserId 用第 2 步响应里的 data.id，下同）
curl -s -X POST $BASE/admin/workspaces -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"bb-ws-$TS\",\"ownerUserId\":<user-id>}"

# 4. user 登录 → 应只看到自己的空间，访问 /admin/* 返回 403
UTOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"bb-user-$TS\",\"password\":\"BbPass123!\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
curl -s -o /dev/null -w '%{http_code}\n' $BASE/admin/workspaces -H "Authorization: Bearer $UTOKEN"   # 403

# 5. 签发 API Key（明文只出现这一次，注意留存；workspace id 用第 3 步响应的 data.id）
curl -s -X POST $BASE/console/workspaces/<ws-id>/api-keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"bb-key"}'
```

高频边界用例清单（历史缺陷回归点，建议必测）：

- 错误密码 401、禁用账号 403、匿名 `/auth/me` 401（统一文案不泄露账号是否存在）。
- user 访问非成员空间 → 404（不是 403，避免空间存在性泄露）；user 直访 admin 面 → 403。
- 重复用户名 → **400**（`duplicate_username`；曾出现 500，已修复）。注意状态码语义分组：表单/重名类错误统一 400，资源冲突类（重复加入成员、owner 移除/降级保护、删除保护、跨空间 bind）统一 409，归属缺失 404。
- 工作空间 owner 管理（issue #157 修复后）：`PATCH /admin/workspaces/:id {"ownerUserId":<id>}` 即 owner 转移（原 owner 自动降为 member、可再移除；新 owner 无成员行则补建）；删除规则为「无 Agent 且成员 ≤1」——仅剩 owner 的空间可删（级联清成员关系），owner 创建的空间不再死锁。
- 表单长度上限（issue #149）：用户名 ≤64、空间名 ≤128、Agent 展示名 ≤128（slug 化后 ≤59）、模型连接名 ≤128 / baseUrl ≤512、MCP 连接器名 ≤64；超限一律 400，不再出现 500 `Data too long`。模型连接 baseUrl 必须是含主机的 http(s) URL（`http://` 裸协议 400，issue #156）。
- admin 对自己创建的空间是隐式成员（无需邀请即可访问 console）。
- 重复邀请同一成员 → 拒绝（曾出现 500，已修复）。
- default 空间存量服务 bind 到业务空间成功；再次 bind 或跨空间冲突 → 409。
- 工作空间仍有归属服务时删除 → 拒绝（删除保护）。
- MCP 连接器 env 键含连字符 → 400（曾出现非法 K8s 键，已修复）。
- 模型连接下发后 Agent `/models` 回读应包含下发模型；usage 状态 pending → synced。
- 导入模式创建 Agent（issue #161 修复后）：env 与 harness 同语义——平台默认配置恒合并（CM 含 `CHECKPOINT_USERNAME` 等、Secret 含 `CHECKPOINT_PASSWORD`/`LLM_API_KEY`），勾选模型连接则注入 `LLM_*`；无 env 合法包导入后 Pod 应 running（曾缺 env 导致 `Access denied` CrashLoopBackOff）。
- 包内 skills/ 目录合规（issue #163 修复后）：缺 `SKILL.md` / 无 frontmatter / frontmatter name 与目录不一致 / 非法名称（大写、空格、`!`、超 64 字符、连续连字符）/ 描述超 1024 / 声明但目录缺失 → 上传不阻断但 `warningsJson` 逐条提示，`GET /console/workspaces/:ws/skills` 条目带 `issues[]` 标注；合法技能无 issues。
- 声明式技能描述（issue #164 修复后）：frontmatter `skills:` 声明（无 description 字段）在 Skill 清单中回读包内 `SKILL.md` 描述，不再为空串。
- 包上传超限错误形态（issue #165 修复后）：>20MB zip → 400 JSON `zip exceeds …`（曾为入口 nginx HTML 413，ingress `proxy-body-size` 已放宽 200m）；解压总量 >100MB → 400 JSON `extracted content exceeds limit`（曾为 500）。
- 并发包编辑保存（issue #172 修复后）：同一基础包并发 N 个 `POST /packages/:id/versions`（相同 `expectedBaseChecksum`）→ 全部成功时版本链严格串行（1.0.1/1.0.2/…），**不再出现重复 version**；显式指定已被占用的版本 → 409。同 slug+version 全局唯一（`(slug,version)` 唯一索引 + 启动时存量重复行自动加 `.fix{N}` 后缀归一），上传重复 slug+version → 409（原允许并存）。
- 包编辑器保留目录名（issue #174 修复后）：upsert 根路径 `skills` / `mcp-configs` 文件 → 400 `reserved directory name`；上传含同名根文件的 zip → 400（曾 200 生成损坏包，`skills/AGENTS.md` 读取 500）。
- 同名技能重复勾选（issue #173 修复后）：创建/编辑页候选池同名技能只展示一条（多来源时标注）；后端 `buildAgentFiles` 对 `form.Skills` 按 name 去重——生成包 frontmatter 只一条声明、`skills/` 目录只复制首个勾选来源，不再重复声明静默覆盖。

## 7. 现成 e2e 套件（回归优先跑这些）

套件位置 `e2e/console-split/`（按模块 m0–m8 一个脚本 + `run-all.sh` 聚合，180+ 断言，2026-10-17 九模块全 PASS 实跑记录见 `docs/implementation/README.md`）：

```bash
# 全量（顺序 m0→m8）
BASE=http://localhost:30080/api/v1 FRONTEND_BASE=http://localhost:8911 ./e2e/console-split/run-all.sh

# 只跑指定模块（如登录与治理）
BASE=http://localhost:30080/api/v1 ./e2e/console-split/run-all.sh m0 m1

# 单发冒烟（21 断言：登录三分支/路由分层/治理链/API Key/introspect）
BASE=http://localhost:30080/api/v1 ./e2e/console-split-e2e.sh
```

模块与被测面对照：m0 认证与路由分层、m1 运维治理、m2 console 基础（概览/列表/切换器）、m3 创建/编辑主链路（含包契约与 SSE 热生效）、m4 详情六 Tab 与生命周期、m5 模型连接、m6 MCP 连接器、m7 集成、m8 API Key 与成员。M9（enforce 三态）需要独立环境改 `ROUTER_AUTH_MODE`，默认 off 档不在常规套件内。

依赖：`curl`、`jq`、`python3`；m3/m4 涉及真实对话的断言需 LLM 可用（可选传 `LLM_API_KEY/LLM_BASE_URL/LLM_MODEL`，缺省自动从 release-agent env 读取）。

## 8. 测试注意事项

1. **数据污染**：测试创建的用户/工作空间/Agent 建议统一带 `bb-` 前缀便于清理；删除工作空间有归属保护，先删 Agent 再删空间。
2. **env 全量覆盖语义**：`PATCH /api/v1/services/:id/env`（console 创建的 Agent 走编辑页）是全量替换不是增量合并，测试改 env 后注意核对未提交键是否丢失。
3. **控制面/业务面隔离（原则一）**：backend 重启/升级不得影响已发布业务 Pod。黑盒可在测试前后对比业务 Pod 启动时间（`kubectl -n agent-platform get pods -o wide`）验证。
4. **敏感值**：LLM API Key 等在 `.env.secrets`（gitignored）；发布后自动路由进 Secret `{name}-env-secret`，ConfigMap 与 OAF 包内不应出现明文 key——用例可断言 `kubectl -n agent-platform get cm -o yaml | grep -c <key前缀>` 为 0。
5. **改档需运维**：`AUTH_TOKEN`、`ROUTER_AUTH_MODE=enforce`、更换 `JWT_SECRET` 都会改变本档 §3 矩阵的预期结果，测试计划里涉及这些用例前先与运维确认环境档位。
