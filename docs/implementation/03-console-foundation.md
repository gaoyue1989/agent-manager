# M2 · 用户侧控制台基础（骨架 / 概览 / Agent 列表 / 切换器 / proxy）

| | |
|---|---|
| 依赖 | M0；M1 的纳管端点（可选增强，非阻塞） |
| 对应设计 | §7.2 console API、§8.1 概览、§8.2 列表、§10 前端信息架构、§10.3 proxy |
| demo 对照 | `(console)` 布局与 `navConsole`、`c/overview` 概览、`c/agents` Agent 列表、`oa-ws` 工作空间切换器 |

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：console/consoleview 两组测试全绿（`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（核心 job 扩展）：概览/列表渲染、proxy 注入 `X-User-Id`（业务侧 session_user 前缀断言）、assistant 页回归零差异；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：console 路由组骨架 + workspace 上下文中间件 + `summary` / `agents` 列表 / `templates` 三个只读端点。
- frontend：`(console)` layout 完整化（顶栏 + WorkspaceSwitcher + 侧导航）、概览页、Agent 列表页（卡片网格）、`proxy.ts` `/agent/*` 通配 + `X-User-Id` 注入。
- 不在本模块：创建页（M3）、详情（M4）、资产页（M5–M8）——侧导航先挂占位路由。

## 2. 后端改动

### 2.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `internal/handler/console.go` | 新建 | console 端点装配 + `resolveWorkspace` 中间件 |
| `internal/service/consoleview.go` | 新建 | 概览聚合 / Agent 列表视图（含 agent_card 摘要解包） |
| `internal/handler/router.go` | 修改 | M0 预留的 `cg` 组填充 |

### 2.2 workspace 上下文中间件（核心复用件）

`:ws` 取值：`me`（默认 = 该用户第一个成员空间；admin = admin 虚拟空间仅允许访问 admin 语义端点，console 端点取其真实成员空间）| workspace 数字 id。

```
解析顺序：JWT uid → workspace_members 查 (user_id, workspace_id)
  命中   → c.Set("ws", wsID)；role 一并落 context（owner/admin/member，后续模块做写权限裁决）
  未命中 → 404 workspace_not_found（不暴露 403/404 差异细节）
额外规则：slug=default 的空间仅 admin 角色可解析（决议 2：存量服务对普通用户不可见）
```

### 2.3 端点契约

```
GET /api/v1/console/workspaces/:ws/summary
  → {data:{
      agents:{total, running, error, stopped},
      recentEvents:[{id, reason, createdAt, displayName}],     // service_events 倒序 10 条
      todaySessions?: null                                      // 一期无会话聚合，字段预留 null（观测大盘 M 决议：不做指标）
    }}

GET /api/v1/console/workspaces/:ws/agents?status=&keyword=
  → {data:[{                                    // = services 按 workspace 过滤 + 视图增强
      id, displayName, k8sName, shortName, status, endpoint,
      model, version, replicas,                     // model/version 从 agent_card_json / package 解包（M3 前历史包无则空串）
      cardSummary:{name, description},              // agentCardJSON 不整包直出，解出摘要
      updatedAt
    }]}

GET /api/v1/console/workspaces/:ws/templates
  → {data:[{packageId, name, slug, version, description, refCount}]}   // oaf_packages where workspace_id=0 and is_template=true
```

`is_template` 列：本模块给 `oaf_packages` 增列 `IsTemplate bool`（默认 false）+ `WorkspaceID uint`（M0 migrate 已建列，本模块补 AutoMigrate 字段），运维侧包列表页加「设为模板」开关（前端一行改动，随本模块带出）。

### 2.4 services 列表过滤

`core.List(status, keyword, packageId)` 追加 `workspaceId uint` 参数（0=不过滤）；console 层强制非 0。**default 空间**：仅 admin 经 console 访问（中间件已裁），agent 列表展示全部归属 default 的服务（= 待纳管视图）。

## 3. 前端改动

### 3.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `src/app/(console)/layout.tsx` | 修改 | M0 骨架完整化：WorkspaceSwitcher 真数据（`auth.me.workspaces`，localStorage 记最近选择）；侧导航按 demo `navConsole` 全量挂出（未实现模块路由 → 占位页组件 `<ComingSoon/>`，知识库/观测大盘直接用占位）；顶栏「运维侧」入口按 role 显隐 |
| `src/components/WorkspaceSwitcher.tsx` | 新建 | demo `oa-ws` 还原：下拉列空间（slug 副标注）+「创建工作空间」入口（普通用户一期不可建 → toast 引导找管理员，决议 6） |
| `src/app/(console)/console/page.tsx` | 新建 | demo `c/overview`：统计卡（Agent 总数/运行中/异常 + 今日会话位预留隐藏）+ 最近发布时间线 + 快捷入口（创建 Agent→M3 路由、打开调试→列表首 agent、签发凭证→M8） |
| `src/app/(console)/console/agents/page.tsx` | 新建 | demo `c/agents`：统计卡 + 筛选（状态下拉/关键字）+ 卡片网格（头像字/名称/状态徽标/描述/model·副本·版本 tag/时间/调试·详情按钮）+ 顶部「创建 Agent」（M3 路由占位）；数据 `api.console.agents` |
| `src/app/(console)/console/agents/[id]/...` | 占位 | M4 填充 |
| `src/app/(console)/console/assets/*/page.tsx` | 占位 | `<ComingSoon/>`（M5–M8 逐个替换） |
| `src/lib/api.ts` | 修改 | `api.console = { summary, agents, templates }`；BASE 保持 `/api/v1` |
| `src/proxy.ts` | 修改 | §3.2 |

### 3.2 proxy.ts 扩展（数据面身份，设计 §5.4 一期）

在现有 `/api/v1` 与 `/agent/release-agent` 两条规则之后追加通配规则（**保留 release-agent 专属规则在前**，其注入逻辑不动）：

```ts
// /agent/{short}/* → oaf-{short}-svc（与 platform-router 同名规则）
if (pathname.startsWith("/agent/")) {
  const rest = pathname.slice("/agent/".length);
  const short = rest.split("/")[0];
  if (isValidShortName(short)) {                       // ^[a-z0-9](-[a-z0-9])*$/, 防注入
    const uid = readUidFromJwtCookie(request);          // 解 oaf_session payload（无库校验，仅取 uid）
    const headers = new Headers(request.headers);
    if (uid) headers.set("X-User-Id", `u-${uid}`);      // 替代硬编码 "webui"
    return NextResponse.rewrite(joinTarget(`http://oaf-${short}-svc.agent-platform.svc.cluster.local:8100`, `/${rest.slice(short.length) || "/"}` + search), { headers });
  }
}
```

- 无 cookie/解不出 uid：不注入 `X-User-Id` 放行（业务侧按现状匿名处理）——控制台路径身份可信即可，直连暴露面与今天一致（决议 3）。
- SSE 透传沿用现有 assistant 页的 fetch 流处理，无新增。

## 4. 验收标准

- [ ] 普通用户登录 → `/console`：顶栏显示所属空间、可切换；侧导航分组与 demo 一致。
- [ ] 概览/列表数据来自 `summary`/`agents` 端点；default 空间对普通用户 404，admin 可见。
- [ ] agent 卡片字段完整（状态徽标 / model / 副本 / 版本 / endpoint）。
- [ ] proxy：登录态访问 `/agent/{short}/threads/chat` 时业务 Pod 收到 `X-User-Id: u-{uid}`（agent-framework `session_user` 落该前缀，可在 debug console 验证）；未登录不注入。
- [ ] 现有 `/agent/release-agent` 行为零变化（assistant 页回归）。

## 5. 测试要点

- `console_test.go`：resolveWorkspace 矩阵（成员/非成员/admin/default 空间/me 默认）；agents 列表过滤与视图字段。
- `consoleview_test.go`：agentCardJSON 缺失/损坏时摘要降级为空串不 500。
- 手工：双浏览器（张工/李工）验证 `/threads` 按 userId 隔离（复用 agent-framework 既有语义，只验透传）。
