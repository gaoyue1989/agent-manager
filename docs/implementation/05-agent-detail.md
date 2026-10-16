# M4 · Agent 详情（概览 / 调试 / 会话 / 技能 / 版本 / 调用凭证 六 Tab）

| | |
|---|---|
| 依赖 | M2（console 骨架 + proxy）；凭证 Tab 的 API Key 签发依赖 M8（可先渲染空态） |
| 对应设计 | §7.2 console API、§8.3 Agent 详情 |
| demo 对照 | `c/detail`（六 Tab + SSE 调试对话 + HITL 确认卡示例） |

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：console 动作端点角色矩阵全绿（`cd backend && make test`）+ 前端既有 `assistant-events.test.mjs` / `confirm-card.test.mjs` 不动全绿（证明组件抽取无损）+ `npm run lint && npm run build`；②e2e（核心 job）：详情六 Tab、调试对话含一次 ask 确认、会话用户隔离、下线；assistant 页回归；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：console 侧详情/生命周期动作端点（workspace 裁决版）。
- frontend：详情页六 Tab；调试 Tab 复用 assistant 页抽出的 chat 组件族。
- 不在本模块：模型下拉（M5）、API Key 管理（M8，本模块只渲染 Tab 壳 + 引导）。

## 2. 后端改动

### 2.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `internal/handler/console.go` | 修改 | 详情与动作端点（§2.2） |
| `internal/service/consoleview.go` | 修改 | 详情聚合（复用 `core.GetDetail` + workspace 归属校验包装） |

### 2.2 端点契约

```
GET    /api/v1/console/workspaces/:ws/agents/:id        → core.GetDetail 包装（pods/events/卡片），非本空间 404
POST   /api/v1/console/workspaces/:ws/agents/:id/publish|republish|unpublish|register
DELETE /api/v1/console/workspaces/:ws/agents/:id        （owner/admin）
```

- 动作端点逐个包 `requireRole(owner|admin)`（member 403）；内部直接调用既有 `core.StartAgain/Republish/Unpublish/Reregister/Delete`，**零新语义**。
- `GET /console/workspaces/:ws/agents/:id/versions`：包版本列表（`oaf_packages` 同 slug 各版本）+ 当前运行版本标注；`POST .../versions/:pkgId/upgrade` = `core.Republish` 到指定 packageId（复用既有 RepublishOptions）。

## 3. 前端改动

### 3.1 页面结构（`src/app/(console)/console/agents/[id]/page.tsx` 新建）

头部：头像字 + 名称 + 状态徽标 + 版本 tag；副行 endpoint（复制按钮）；右侧动作：**编辑（pri，跳 M3 编辑页 `/console/agents/[id]/edit`，owner/admin；member 隐藏）** / 重发布 / 下线（danger）。
Tab 条（URL query `?tab=` 同步，刷新保位）：

**Tab1 概览**（demo `p1`）：左列「基本信息」kv（工作空间/模型/规格·副本/镜像/包 slug·版本/注册状态）+「实时 Pod」表（name/状态/重启/就绪，5s 轮询）；右列「事件时间线」（ok/err 圆点，倒序）。

**Tab2 调试**（demo `p2`，核心复用件落地）：

| 组件 | 来源 | 改造点 |
|------|------|--------|
| `components/chat/ChatPane.tsx` | `app/assistant` 抽出 | props 化 `agentBase`（原硬编码 `/agent/release-agent`）；`X-User-Id` 由 proxy 注入（M2），组件零感知 |
| `components/chat/SessionList.tsx` | 同上抽 `oa-chat-side` 形态 | 会话列表走 `GET {agentBase}/threads?userId=u-{me}`（经 proxy，自动带身份） |
| `components/chat/ConfirmCard.tsx`、`assistant-events.ts`、`confirm-card.ts` | 原样复用 | SSE 帧解析与 HITL 三按钮（允许一次/本会话内允许/拒绝）不改 |
| 输入区 | demo `oa-chat-input` | 附件按钮（`POST {agentBase}/files`）；模型下拉 M5 前隐藏 |

头部说明条照 demo：「调试流量直连业务运行时（SSE，不经控制面后端）…」。

**Tab3 会话**（demo `p3`）：表格（会话 id/标题/用户/模型/消息数/更新时间/回放·删除），数据 `GET {agentBase}/threads?userId=u-{me}` 前端直取（**不经 backend**）；回放 = 跳调试 Tab 并 `?session=` 预载；删除 = `DELETE {agentBase}/threads/{sid}`。页脚注（demo 原文）：checkpoint + Redis Streams 跨副本续传说明。

**Tab4 技能**（demo `p4`）：左「包级技能（L2/L3 随包只读）」——解包 `skillsJson`/包 skills 声明；右「用户级技能（L4 本空间共享）」——`GET {agentBase}/skills/users`（经 proxy）+ 停用/删除动作 + 上传（复用 agent-framework 既有 L4 端点，backend 不转发）。

**Tab5 版本**（demo `p5`）：包版本表（版本/变更说明/派生自/发布状态/操作）；当前版本行「编辑」= 跳 M3 编辑页（表单级，copy-on-write 新版本 + republish）；历史版本行「升级到此版本」= `POST .../versions/:pkgId/upgrade`；包编辑器（`/admin/packages/{id}`，文件级高级编辑，owner/admin）从编辑页/版本说明进入，两者共用 copy-on-write 版本链。

**Tab6 调用凭证**（demo `p6`）：M8 前渲染引导空态（「到 资产管理 → 凭证管理 签发」+ 二期网关生效提示条）；M8 后展示本 Agent 作用域的 Key 列表 + curl 示例块（demo `oa-pre` 深色代码块，A2A JSON-RPC 样例字符串，endpoint 用真实值替换占位）。

### 3.2 文件清单

| 文件 | 动作 |
|------|------|
| `src/app/(console)/console/agents/[id]/page.tsx` | 新建（Tab 壳 + 概览/版本/凭证三个内联块） |
| `src/app/(console)/console/agents/[id]/debug.tsx`（或同页组件） | 新建：调试 Tab（装配 chat 组件族） |
| `src/components/chat/*` | 从 assistant 抽出（ChatPane/SessionList/ConfirmCard/输入区；assistant 页改为消费同组件，**行为回归零差异**） |
| `src/lib/api.ts` | 修改：`api.console.agentDetail/agentAction/agentVersions/upgradeVersion` |

## 4. 验收标准

- [ ] 概览 Tab：Pod 表实时刷新；事件时间线与运维侧服务详情同源同序。
- [ ] 调试 Tab：发消息走 SSE 流（AGENT_START/工具步骤/文本增量/AGENT_END 帧序列与发布助手一致）；HITL 卡出现且三按钮生效（挑 `platform-mcp` ask 工具或包内 ask 工具验证）；会话历史按当前用户隔离（另一账号看不到）。
- [ ] 会话 Tab：回放能带 session 打开调试；删除后列表消失（业务侧真删）。
- [ ] 技能 Tab：包级只读正确解包；L4 列表与停用/删除生效（debug console 可验证下发）。
- [ ] 版本 Tab：升级到旧版本 = republish 语义（Endpoint 不变、包切版本、事件时间线新增记录）。
- [ ] assistant 页（运维侧发布助手）重构后行为零回归（e2e 核心 job 覆盖）。
- [ ] member 可看全部 Tab 但动作按钮（重发布/下线/删除/升级/技能删）隐藏或 403。

## 5. 测试要点

- `console_test.go`：动作端点角色矩阵；跨空间 id 访问 404。
- 前端组件抽取的回归：`assistant-events.test.mjs` / `confirm-card.test.mjs` 全绿（现有测试不动即证明抽取无损）。
- e2e：进核心 job——创建（M3）→ 调试对话一轮（含一次 ask 确认）→ 下线。
