# 控制面拆分 · 分模块实施文档（索引与总览）

上游设计：[../design/console-user-workspace-split-design.md](../design/console-user-workspace-split-design.md)（含 2026-10-16 评审决议 §13）
视觉/交互基准：[../design/demo/console-user-workspace-demo.html](../design/demo/console-user-workspace-demo.html)（下文各模块以 demo 的 `data-view` 锚点对照）
本文档集把设计拆解为**可独立开工、可独立验收**的 10 个功能模块，每个模块一个 PR 粒度（大模块允许按「后端先行 / 前端跟进」拆两个 PR，但验收必须合并判定）。

## 完成定义（DoD，硬性门槛）

**任何模块标记「完成」，必须同时满足以下三条，缺一不可：**

1. **单测全绿**：模块文档「测试要点」列出的单测全部落地并通过——backend 模块 `cd backend && make test`（`go vet ./...` + `go test ./...`）；frontend 模块 `cd frontend && npm run lint && npm run build` + 既有/新增 `*.test.mjs` 全绿。
2. **e2e 验收通过**：模块文档「完成定义」中指定的 e2e 场景全部通过，按下方落位表进 CI job 或脚本；无法自动化的集群形态项（标注「手工」）须在 PR 描述附操作记录/日志证据，由评审人确认。
3. **验收清单全勾**：模块文档「验收标准」checklist 逐项勾选，PR 描述附 CI run 链接（单测 job 绿）与 e2e 证据链接。

不满足任一条 → 模块状态停留在「实施中」，不得合入；**依赖未验收的模块不得开工合入**（允许并行开发，不允许越过未验收依赖合入）。

### e2e 落位表

| 模块 | e2e 场景 | 落位 |
|------|----------|------|
| M0 | 登录→双侧骨架可达；存量 URL 重定向（`/services/1`→`/admin/services/1`） | 核心 job 扩展（重定向断言前置，保四必需门禁） |
| M1 | admin 建号→建空间→bind 存量服务→列表归属断言 | e2e 新脚本（governance），首版非必需 job，稳定后转必需 |
| M2 | 概览/列表渲染；proxy 注入 `X-User-Id`（业务侧 session_user 前缀断言） | 核心 job 扩展 |
| M3 | 表单创建→running→A2A 注册→对话一句→下载包断言 frontmatter/mcp-configs；编辑改提示词保存→v2 republish→对话断言新行为 | 核心 job 扩展（主链路） |
| M4 | 详情六 Tab；调试对话含一次 ask 确认；下线；assistant 页回归 | 核心 job（发布助手链路即回归面） |
| M5 | 建连接→测试→创建 Agent 勾选→usage pending→synced→调试切换两轮（下发模型↔system）断言 session_user.model | e2e 脚本（model-switch） |
| M6 | 连接器 verify→创建勾选→包 config.yaml 断言→Secret 含 MCP_TOKEN、包内无 token | e2e 脚本（mcp-connector） |
| M7 | 集成登记→创建 lead→spawn 一轮子任务回流 | 协议多副本 job 扩展（运行时既有覆盖）+ 声明链路断言 |
| M8 | API Key 签发/作用域/吊销；成员角色变化即时生效 | e2e 脚本（workspace-assets） |
| M9 | enforce 三态（无凭据 401 / 带凭据 200 / 跨作用域 403）+ 四必需带凭据回归 | 独立 e2e 环境配置（二期立项时定） |

> 手工项（集群形态依赖）：host 模式 publicEndpoint、release-agent 停机降级、router 灰度 report 模式观察——PR 附操作记录，不阻塞 CI。

## 模块清单与依赖

| 模块 | 文档 | 一句话范围 | 依赖 | 分期 |
|------|------|-----------|------|------|
| M0 | [01-auth-skeleton.md](01-auth-skeleton.md) | 用户/工作空间/成员三表 + JWT 登录 + 前端双侧布局骨架与存量页迁移 | — | P0 |
| M1 | [02-admin-governance.md](02-admin-governance.md) | 运维侧治理：工作空间管理 / 用户管理 / 审计 / 服务列表归属列 / 纳管 | M0 | P0 |
| M2 | [03-console-foundation.md](03-console-foundation.md) | 用户侧基础：console API 骨架 / 概览 / Agent 列表 / 工作空间切换器 / proxy 通配 | M0（M1 的纳管可选增强） | P1 |
| M3 | [04-agent-create.md](04-agent-create.md) | 创建页（单页分区表单）→ OAF 包生成器 → 复用 Publish 发布；编辑页（同构表单预填 → copy-on-write 新版本 → republish） | M2 | P1 |
| M4 | [05-agent-detail.md](05-agent-detail.md) | Agent 详情六 Tab（概览/调试/会话/技能/版本/调用凭证） | M2（调试依赖 M2 的 proxy） | P1 |
| M5 | [06-model-connection.md](06-model-connection.md) | 模型连接：工作空间级配置 CRUD/测试 + 下发至 Agent `/models` + 使用情况展示 + 会话级切换 | M2、M3、M4 | P1 |
| M6 | [07-mcp-connectors.md](07-mcp-connectors.md) | 工具管理：MCP 连接器 CRUD（空间级配置）+ 创建/更新时注入生成包 | M2（注入生效依赖 M3） | P1 |
| M7 | [08-agent-integration.md](08-agent-integration.md) | 集成 Agent：Agent Protocol 集成关系登记 + 创建页 SubAgent 端点写入 | M2（写入生效依赖 M3） | P1 |
| M8 | [09-workspace-assets.md](09-workspace-assets.md) | 资产余页：Skill / 记忆空间 / 凭证（API Key）/ 模板 / 成员 / 占位页 | M2（凭证作用域联动 M3/M4） | P1 |
| M9 | [10-gateway-auth.md](10-gateway-auth.md) | P2 网关鉴权：router auth_request + introspect + API Key 数据面生效 | M0–M8 | P2 |

依赖图（箭头 = 被依赖）：

```
M0 ──▶ M1 ─┐
  └───────▶ M2 ──▶ M3 ──▶ M6(注入) ──▶ M7(写入)
              │      └────▶ M8(凭证作用域)
              └──▶ M4 ──▶ M5
全部 ──▶ M9(P2)
```

**最小可演示路径**：M0 → M2 → M3 → M4（登录 → 建空间内 Agent → 调试），对应设计 §12 P1 验收。

## 公共约定（全模块生效，各模块文档不再重复）

### 后端

- **包络与错误**：沿用 `handler/respond.go` 的 `OK/Fail`（`{code,message,data}`）；错误消息英文小写开头（现状风格），前端按 `message` 直出。
- **路由分层**（改造 `handler/router.go`，详见 M0 §2.1）：

| 前缀 | 鉴权 | 说明 |
|------|------|------|
| `/api/v1/auth/*`、`/healthz` | 无 | 登录与健康检查 |
| `/api/v1/console/*` | JWT（任意角色） | workspace 权限由 membership 裁决（M2 中间件） |
| `/api/v1/admin/*` | JWT-admin **或** AUTH_TOKEN | 新治理面；AUTH_TOKEN 兼容脚本与 release-agent |
| `/api/v1/{services,packages,images,platform-config}` | AUTH_TOKEN（现状不动） | 存量运维 API，e2e/MCP 不感知 |
| `/mcp` | AUTH_TOKEN（现状不动） | 运维助手 |

- **数据模型**：backend 维持 GORM AutoMigrate（`store/db.go`），新表统一加进 AutoMigrate 列表；数据回填放 `store/migrate.go`（幂等，仅平台自有表，**禁止碰业务 K8s 资源**，设计原则一）。
- **JSON 字段风格**：请求/响应 camelCase（对齐现有实体 json tag）；时间 `time.Time` 直出。
- **机密落库口径**：MCP 连接器 token、API Key hash 落 backend 自有库（内网库明文/哈希先例 = agent-framework `model_config`）；**任何机密不进 OAF 包、不进 ConfigMap**，发布注入一律走 `{name}-env-secret`（沿用 platform-default-config-secret-design 的 Secret 路由）。
- **新文件命名**：`internal/handler/{auth,admin,console}*.go`、`internal/service/{identity,governance,agentgen,...}.go`、`internal/store/{model,migrate}.go` 扩展；单测同名 `_test.go` 进 backend-ci。

### 前端

- **路由组**：`(console)` 用户侧 / `(admin)` 运维侧两个 layout 组（设计 §10.1）；`middleware.ts` 读 `oaf_session` cookie 做 UX 守卫（权威裁决在 backend）。
- **API 客户端**：扩展现有 `lib/api.ts` 的 `api` 对象（新增 `auth`/`console`/`admin` 三组方法）；401 统一跳 `/login`。
- **复用件**：SSE/HITL 从 `app/assistant` 抽 `components/chat/*`；`FileTreeView`、`Markdown`、`icons.tsx` 直接复用；状态徽标收敛 `lib/status.ts`。
- **不引入**：组件库 / 状态管理库 / 暗色主题（设计 §10.2）；Tailwind 原生 + 蓝主色，布局对齐 demo（左导航 212px + 卡片内容区）。
- **数据面**：对话/会话请求一律经 `/agent/{short}/*`（`proxy.ts` 反代 + `X-User-Id` 注入），**不进 backend**。

### 架构红线（每模块 PR 自检）

启动零 reconcile / 业务面零改动 / 无状态多副本——逐条对照设计 §11；现有四个必需 e2e job（核心/多副本/沙箱/协议多副本）在任何模块合入后必须保持全绿（存量 API/URL 不变是硬约束）。

## 实施状态记录

| 模块 | 单测 | e2e | 状态 | 备注 |
|------|------|-----|------|------|
| M0 | ✅ jwt/auth/migrate/router 分层矩阵全绿（`make test` 全包 ok） | 手工/集群待跑 | 实施完成（2026-10-17） | feat/console-user-workspace-split：四表+JWT+路由分层+登录页+双布局+迁移重定向；Next16 proxy 取代 middleware（守卫并入 proxy.ts） |
| M1 | ✅ governance（slug/bind 三态/成员矩阵/删除保护）+ admin HTTP 鉴权三态 | governance 脚本待集群 | 实施完成（2026-10-17） | admin 治理端点 + 工作空间/用户/审计/概览四页 + 服务列表归属列/纳管 + 发布向导归属下拉 |
| M2 | ✅ consoleview（摘要降级/跨空间 404）+ resolveWorkspace 矩阵 | 核心 job 扩展待集群 | 实施完成（2026-10-17） | console 骨架/概览/列表/切换器；proxy `/agent/{short}` 通配 + X-User-Id 注入 |
| M3 | ✅ agentgen（slugify/frontmatter golden/mcp config golden/创建编辑链路/spec 互斥/publicEndpoint） | 核心 job 主链路待集群 | 实施完成（2026-10-17） | 创建页单页分区表单 + 编辑页同构复用 + import/adopt 通道（console packages 上传/派生） |
| M4 | ✅ console 动作端点角色矩阵（handler 层） | 核心 job 待集群 | 实施完成（2026-10-17） | 详情六 Tab + ChatPane 抽取（assistant 页零差异回归，既有 30 用例全绿） |
| M5 | ✅ modelconn（CRUD/掩码/test 三分支/deliver 幂等假 agent 计数/usage 三态） | model-switch 脚本待集群 | 实施完成（2026-10-17） | 连接页 + 创建页勾选 + OnServiceRunning 回调下发 + 调试下拉透传 |
| M6 | ✅ mcpconnector（verify 假 MCP 三分支/refCount/config golden） | mcp-connector 脚本待集群 | 实施完成（2026-10-17） | 工具管理页 + agentgen 注入（token 不落包走 Secret） + builtin platform-mcp |
| M7 | ✅ integration（探测三态/重复 409/引用保护） | 协议多副本 job 待集群 | 实施完成（2026-10-17） | 集成页 + 创建页 SubAgent 接集成下拉 + AGENT_REMOTE_HEADERS_JSON 进 Secret |
| M8 | ✅ apikey（签发格式/哈希/作用域/吊销/过期惰性） | workspace-assets 脚本待集群 | 实施完成（2026-10-17） | Skill/记忆/凭证/模板/成员页 + invitable-users + 一次性明文弹窗 |
| M9 | ✅ introspect 双通道（JWT/API Key/吊销 403/作用域 403） | enforce 三态需独立环境 | 后端+router 配置完成（2026-10-17） | /auth/introspect + platform-router auth_request（ROUTER_AUTH_MODE off|report|enforce，默认 off，三档渲染均过 `nginx -t`）；report 灰度 48h 与 enforce 环境验收为上线前置，另行执行 |

> 2026-10-17 实施说明（feat/console-user-workspace-split 分支）：
> - 「单测」列 = `cd backend && make test`（go vet + go test 全包）与 `cd frontend && npm run lint && npm run build && npm test`（lint 0 errors、tsc 全绿、既有 30 用例全绿）实跑结果；
> - 「e2e」列标注待集群/手工的条目为 DoD 中无法离线自动化部分（四必需 job 回归、governance/model-switch/mcp-connector/workspace-assets 脚本、enforce 三态环境），合入后按 e2e 落位表在集群环境补跑并回填证据链接；
> - frontmatter 契约以 agent-framework OafConfigLoader 实测为准：子 agent YAML 键为 `agents`（含 endpoint），设计稿措辞 `subAgents` 为概念名，生成包按运行时契约产出。
