# M1 · 运维侧治理（工作空间 / 用户 / 审计 / 纳管）

| | |
|---|---|
| 依赖 | M0（表与鉴权中间件） |
| 对应设计 | §9 运维侧调整、§7.3 admin API |
| demo 对照 | `a/overview` 平台概览、`a/workspaces` 工作空间管理、`a/users` 用户管理、`a/audit` 审计、`a/services` 服务列表（工作空间列） |

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：governance/admin 两组测试全绿（`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（新脚本 governance，首版非必需 job）：admin 建号→建空间→bind 存量服务→服务列表归属断言；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：`/api/v1/admin/workspaces*`、`/api/v1/admin/users*`、`/api/v1/admin/audit-logs`；服务列表 workspace 过滤；纳管 bind。
- frontend：运维侧四个新页面 + 服务列表加「工作空间」列与筛选 + 发布向导「归属工作空间」下拉。
- 不在本模块：console 侧任何页面；API Key（M8）。

## 2. 后端改动

### 2.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `internal/handler/admin.go` | 新建 | 全部 admin 端点（§2.2–2.4） |
| `internal/service/governance.go` | 新建 | 工作空间/用户/成员/纳管业务逻辑 |
| `internal/service/status.go` | 修改 | `List` 增加 workspaceId 过滤参数（0=全部） |
| `internal/handler/router.go` | 修改 | admin 组填充（M0 已建好 `ag` 组） |
| 对应 `_test.go` | 新建 | §5 |

### 2.2 工作空间管理

```
GET  /api/v1/admin/workspaces
  → {data:[{id, slug, name, description, owner:{id,displayName}, memberCount, agentCount, status, quota:{maxAgents}, createdAt}]}
POST /api/v1/admin/workspaces        {name, description?, ownerUserId, quota?{maxAgents}}
  → 201 {data: workspace}            // slug=ws-{8位随机hex}，冲突重试 3 次；同时写 owner 的 workspace_members(role=owner)
GET  /api/v1/admin/workspaces/:id
PATCH /api/v1/admin/workspaces/:id   {name?, description?, status?, quota?}
  → 停用（status=disabled）仅阻止新成员加入与新发布，不影响已运行业务面（架构原则一）
DELETE /api/v1/admin/workspaces/:id
  → 400 workspace_not_empty（存在成员或 agent 时拒绝；与设计 §7.3 一致）
GET  /api/v1/admin/workspaces/:id/agents   → 空间内服务列表（复用 core.List 过滤）
POST /api/v1/admin/workspaces/:id/bind     {serviceId}
  → 纳管：services.workspace_id 置为本空间（仅当当前归属为 default 空间或 0；已在其他空间则 409 already_bound）
```

memberCount/agentCount 用聚合子查询（`SELECT count(*)` 两条，不做 join 展开实现层）。

### 2.3 用户管理

```
GET   /api/v1/admin/users
  → {data:[{id,username,displayName,role,status,lastLoginAt,workspaces:[{id,name,role}]}]}
POST  /api/v1/admin/users            {username, password, displayName?, role}
  → 201；密码 bcrypt；username 冲突 400 duplicate_username
PATCH /api/v1/admin/users/:id        {role?, status?, password?}
  → 改密=重置 bcrypt；禁用仅挡 login 与 console JWT 新请求（已发 token 12h 内有效，M0 取舍）
POST  /api/v1/admin/workspaces/:id/members     {userId, role}        // 加入成员
PATCH /api/v1/admin/workspaces/:id/members/:uid {role}               // 改角色
DELETE /api/v1/admin/workspaces/:id/members/:uid                    // 移除（owner 不可移除，400）
```

### 2.4 审计（一期聚合视图，不建新表）

```
GET /api/v1/admin/audit-logs?page=1&size=50
  → {data:{items:[{time, actor, action, object, detail}], total}}
```

- 数据源：`service_events`（actor 现无落库——本期 services 增列 `created_by`，Publish 与 action 类端点写入；历史行 actor 显示 `—`）+ `platform_config_events`（action=`platform_config.update`）。
- 工作空间创建/成员变更等治理动作：M1 起写 `service_events` 同表（`reason` 字段编码 JSON：`{"kind":"workspace.create","actor":"admin","detail":...}`，对象列用 slug），避免一期建独立 audit_logs 表（设计 §7.3；独立表列 P2）。
- 动作词表：`publish / workspace.create / workspace.bind / user.create / member.update / api_key.revoke / platform_config.update`。

### 2.5 服务列表与发布向导联动

- `GET /api/v1/services`（legacy）与 console 列表均支持 `workspaceId` query（0=全部）。
- `PublishRequest` 增加可选 `WorkspaceID uint`、`CreatedBy uint`（M3 创建链路也用）：发布即归属，免二次纳管（设计 §9.3）。

## 3. 前端改动

### 3.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `src/app/(admin)/admin/page.tsx` | 修改 | 服务列表页加「工作空间」列（tag 展示）+ 筛选下拉（全部/各空间/未归属）+ 未归属行「纳管」按钮（弹空间选择 → `admin.workspaces.bind`） |
| `src/app/(admin)/admin/workspaces/page.tsx` | 新建 | demo `a/workspaces` 还原：表格（空间/Slug/Owner/成员/Agent/配额/状态/操作）+ 创建弹窗 + 停用 + 「查看 Agent」跳过滤后服务列表 + 「查看待纳管」（default 空间行） |
| `src/app/(admin)/admin/users/page.tsx` | 新建 | demo `a/users` 还原：表格（用户/平台角色/所属空间/最近登录/状态/操作：改角色/重置密码/禁用）+ 创建弹窗（M0 后端已就绪） |
| `src/app/(admin)/admin/audit/page.tsx` | 新建 | demo `a/audit`：时间/操作者/动作 tag/对象/详情 分页表格 |
| `src/app/(admin)/admin/overview/page.tsx` | 新建 | demo `a/overview`：统计卡（服务总数/运行/异常/用户/OAF 包）+ 最近平台事件 + 运维入口快捷按钮（数据来自 services+users+packages 聚合，新增薄端点 `GET /admin/overview` 或前端并行拉三个列表聚合——**采用前端聚合**，不加端点） |
| `src/app/(admin)/admin/publish/page.tsx` | 修改 | 向导加可选「归属工作空间」下拉（`admin.workspaces.list`，默认 default） |
| `src/lib/api.ts` | 修改 | `api.admin = { listWorkspaces, createWorkspace, patchWorkspace, deleteWorkspace, listUsers, createUser, patchUser, addMember, patchMember, removeMember, bind, auditLogs }` |

## 4. 验收标准

- [ ] admin 可创建工作空间（slug 自动生成）并指派 owner；重复 slug/空名 400。
- [ ] 纳管：default 空间服务 bind 进业务空间后，服务列表归属列变化；跨空间 bind 409。
- [ ] 用户 CRUD + 成员角色变更；owner 保护（不可移除/不可降级为 member）。
- [ ] 停用工作空间后该空间内已发布服务不受影响（只查 status，不动 K8s——单测断言无 K8s 调用）。
- [ ] 审计页能看到发布/配置变更/工作空间/用户动作（含 M1 新增动作词表）。
- [ ] AUTH_TOKEN 直调 `admin/*` 端点可用（脚本兼容）；JWT 普通用户 403。

## 5. 测试要点

- `governance_test.go`：slug 生成与冲突重试；bind 的三态（default→ok / 其他空间→409 / 不存在→404）；成员角色矩阵；删除保护。
- `admin_test.go`（HTTP 层）：端点鉴权（AUTH_TOKEN/JWT-admin/JWT-user 三态）、审计聚合分页。
- e2e 补一条（可选非必需 job）：admin 建号 → 建空间 → bind 存量服务。
