# travel-fulfillment 多 Agent 方案设计（B1：Agent Protocol 远程子 agent）

| | |
|---|---|
| 状态 | **设计定稿 v1.4（M1 已实施，PR #62）**，含 2026-09-28 三轮评审修订 + M0 双服务探针实测结论（§15）+ 2026-09-29 M1 双进程/平台部署实测（§16） |
| 日期 | 2026-09-28 |
| 参考 | 官方案例 [order-fulfillment](https://java.agentscope.io/v2/zh/service/cases/order-fulfillment)（AgentScope Service + Team 形态）；协议文档 [integration/protocol](https://java.agentscope.io/v2/zh/integration/protocol) |
| 关联代码 | agent-framework SDK `io.agentscope:*:2.0.3`；`extensions-a2a-client`（已在 pom 未用）；`extensions-agent-protocol`（待引入） |

## 0. 设计立场与官方案例的关系

官方案例由 AgentScope Service 平台（aistio 控制平面）承担三件事：Team 编排、Workflow 节点级授权切换、治理审计（Issue/Run/Attempt）。本方案**不引入 Service 平台**，分别以下述机制替代：

| 官方能力 | 本方案替代 | 残差 |
|---|---|---|
| Team/Leader 编排 | trip-lead 主 agent 提示词 + 远程子 agent 声明（`SubagentDeclaration.url`） | 编排质量依赖模型，需离线评测兜底 |
| Workflow 节点级授权切换 | 三层防线 + L4 硬约束（§6） | L4 落地前诊断阶段"只读"是软约束（§6 显式残差） |
| Issue/Run/Attempt 治理 + Task map | OTel trace + tool_audit_log + session_message + `plan_id` 对账 | 无工作流级重放视图 |
| Job Endpoint（API key + Idempotency-Key） | A2A `message/send` 为入口；**幂等需平台薄封装**（§12，taskId 只是执行句柄非幂等键） | 认证与显式幂等头缺省 |

角色映射：`fulfillment-lead`→`trip-lead`（主 agent，OAF 服务）；`order/inventory/logistics/after-sales`→`booking`（订票）、`approval`（提单审批），各一个 OAF 服务，开启 Agent Protocol 服务端。

## 1. 已验证事实基础（全部经本地 SDK 2.0.3 拆解 / v2.0.3 源码 / 本仓库代码核实，2026-09-28）

| # | 事实 | 验证方式 |
|---|------|----------|
| F1 | `agentscope-extensions-agent-protocol:2.0.3` 已发布 Maven Central；Spring Boot 自动配置：`agentscope.agent-protocol.enabled=true`（默认 false）+ 容器有 `HarnessAgent` bean 即生效，注册 `POST /tasks`、`GET /tasks/{id}`、`/wait`、`/cancel`、`/events`(SSE, `from_seq`/`Last-Event-ID` 重放)、`POST /tasks/{id}/resume`；**属性面无任何认证字段** | Maven Central + v2.0.3 tag 源码（`AgentProtocolAutoConfiguration`/`AgentProtocolController`） |
| F2 | harness 内建远程子 agent 客户端：`SubagentDeclaration.builder().url(...).headers(...).remoteStreaming(true).remoteStreamDetail(FULL/VERBOSE).remoteAskPolicy(DENY/PROPAGATE)`；`remoteAskPolicy=PROPAGATE` 且流式开启时**转发 `RequireUserConfirmEvent` 到父流**；子挂起时快照 `status=awaiting_confirm`（TaskStatus 仍 RUNNING，父侧 barrier 继续等） | javap/strings 拆解 + 官方 harness/subagent、agent-protocol 文档 |
| F3 | `agent_spawn`：`timeout_seconds>0` 同步（默认 30、上限 600，超时自动升格后台）；`=0` 立即返回 `task_id` 后台执行；结果以下一轮 `<system-reminder>` 自动注入或 `wait_async_results` 收割；同步工具默认并行（`ToolkitConfig.parallel=true`）；子 agent 错误作为 TOOL_RESULT 返回不打断父流；子不能再 spawn 孙（3 层上限） | 官方 harness/subagent 文档 + AgentSpawnTool strings |
| F4 | 提交上下文：`context.user_id`→子 `RuntimeContext`；`parent_session_id` 追踪；`deny_rules` 父侧 DENY 规则下传子侧执行；`attributes` 命名空间化挂 `agentprotocol.context.attributes`，可经 `RuntimeContextCustomizer.flatten` 白名单提升 | 官方 agent-protocol 文档 + `AgentProtocolConstants` 源码 |
| F5 | 恢复 API：`AgentProtocolTaskClient.resumeTask(url, headers, taskId, List<RemoteConfirmDecision>)`（决策随 resume 下发）；`AgentFactory` 每次运行（提交 + 每次 resume）被调用，resume 时仍拿最初提交上下文，HITL 前后路由一致 | javap + 官方文档 |
| F6 | 默认 `ProtocolTaskRepository = WorkspaceProtocolTaskRepository(Path.of(taskStorePath))`——**新建本地 WorkspaceManager，落容器本地 FS**，重启即丢、副本间不可见；但存在公开构造 `WorkspaceProtocolTaskRepository(WorkspaceManager)`，可传入 store-backed 实例落 `agent_fs`（合成命名空间 `agents/_agentscope_protocol/tasks/`）；`AgentProtocolEventBus` 默认内存实现（replay buffer 256） | v2.0.3 `AgentProtocolAutoConfiguration`/`WorkspaceProtocolTaskRepository` 源码 |
| F7 | `extensions-a2a-client` 已是 agent-framework 直接依赖（未使用）；`A2aAgent`/`WellKnownAgentCardResolver` 构建已本地探针验证 | pom + 探针 |
| F8 | 我们侧 HITL 现状：confirm 与 chat 抢**同一把** `turn_lease`（`TurnLeaseStore` 头注释 #47/#48；`ConfirmController` 两次 `acquire`）；`confirm_context` 落库**事件驱动**（`AgentRuntimeService.storeConfirmContext` 捕获 `forwardEvent` 中的 `RequireUserConfirmEvent`）且 `ConfirmContextStore.put` 是**同 session 覆盖式**（单行、TTL 30min）；A2A 通道挂起态不落 confirm_context 是既有已知限制 | 本仓库代码 |
| F9 | 官方协议分层：AG-UI（用户面）/ Agent Protocol（内部远程子 agent 面）/ A2A（外部互操作面） | 官方 integration/protocol 文档 |
| F10 | 远程提交的 userId 链：`AgentSpawnTool` 直接取父 `RuntimeContext.userId`（`currentUserId`）填入 `RemoteSubmitContext.userId` → 序列化为 `context.user_id` 下传——**父侧 ctx.userId 是网关 peer/gw-hash 时（Channel 链路，issue #44），子服务将拿到错误 userId** | AgentSpawnTool/RemoteSubmitContext 拆解 + 本仓库 issue #44 |
| F11 | 我们的对话路径是**每请求直调** `agent.streamEvents(...)`（`AgentRuntimeService.invokeStream:165`），无常驻分发循环——后台任务完成**没有任何东西自动替 lead 推进 turn**；"结果注入下一轮"的"下一轮"缺触发方 | 本仓库代码 |
| F12 | 业务 Ingress 单正则全量路由：`path /agent/{short}(/|$)(.*) → rewrite /$2`（`backend/internal/k8s/objects.go:247`，use-regex）——服务根下**所有**端点（含启用协议后的 `/tasks*`）经 NodePort 30080 外可达，叠加 F1 无认证 = 未防护的远程调度入口 | backend objects.go |
| F13 | AgentSpawnTool 字节码内部类型为 `List<RemotePendingConfirm>`（pendingConfirms），**无裸 `RequireUserConfirmEvent` 字样**——远程确认大概率以 RemotePendingConfirm 快照/事件形态进入父流（官方文档口径为"转发 RequireUserConfirmEvent"，静态无法定论，M0 ① 终裁） | AgentSpawnTool strings 拆解 |
| F14 | agent-protocol 默认 `AgentFactory = agentProvider.getObject()`（拿 **Spring bean**）；我们 OAF reload 是 `swapAgent` 换 volatile 引用 + holder 模式、**bean 永不替换**（A2A 服务端正是为此经 `A2aAgentRefHolder` 间接持有）——member 不自定义工厂则 reload 后协议任务全部跑在旧 agent 实例上 | AgentProtocolAutoConfiguration 源码 + 本仓库 reload 链路 |
| F15 | **（M0 实测）远端子 agent 的 `remoteAskPolicy=PROPAGATE` 不产生任何父流确认事件**——背景/同步两种 spawn 均无 `RequireUserConfirmEvent`/`RemotePendingConfirm` 进父流；远端 spawn 恒异步（`timeout_seconds=60` 也立即返回 task_id，不走阻塞语义）；父侧 `wait_async_results` 对 awaiting 任务固定 60s 超时、`task_output` 只显示 Running。**父侧唯一可靠确认源 = member `GET /tasks/{id}` 快照** | M0 探针 run3（§15） |
| F16 | **（M0 实测）`context.deny_rules` 不被 agent-protocol 扩展消费**（扩展源码零 deny/Permission 处理；harness 负责在父侧填充 `RemoteSubmitContext.denyRules`，member 侧应用需自实现）；实测原样 POST 带 deny_rules 的提交仍走 ask 挂起（fail-closed 方向，安全无损） | M0 探针 ④a + 扩展源码检索 |
| F17 | **（M0 实测）SDK 批准续跑缺陷**：ask 挂起 → resume(approved=true) 后，子 run 的**所有**后续工具执行持续 ERROR（"content 参数验证失败"），任务以错误报告**伪 COMPLETED**；被批准的工具未产生副作用。与已知 SDK HITL 缺陷家族同源（e2e-ci-plan §11.3 ①：批准恢复后 tool_use.input 丢失）。**拒绝路径（approved=false）不受影响，干净终止** | M0 探针 ①/②（§15） |
| F18 | **（M0 实测）部分权限上下文 fail-closed**：只声明两条规则时，未声明的内置工具（glob_files/list_files）也逐一 ask 挂起——member 必须复用完整"声明三态 + 未声明自动放行"规则展开（`HarnessAgentFactory.buildPermissionContext` 已有），否则确认卡密度爆炸 | M0 探针 ① |
| F19 | **（M0 实测）身份与持久化链路成立**：`context.user_id` 全链路透传（子侧中间件捕获 == lead ctx.userId）；子侧 sessionId = taskId；TaskRecord 文件存储跨 member kill -9 重启保留快照且可 resume；全链路 JSON 序列化在 Spring Boot 3.3.5 BOM（jackson 管控 2.17.2，覆盖扩展声明的 2.21.1）下正常 | M0 探针 ④b/④c/④d/④e（§15） |

## 2. 总体架构

```
用户 / 业务后台
   │  /threads/chat (SSE)              │  A2A message/send（metadata.userId）
   ▼                                   ▼
┌────────────── trip-lead（OAF 服务，K8s Deployment）──────────────┐
│ HarnessAgent（编排者）                                            │
│  ├─ 远程子 agent（programmatic 声明，.subagents(...)）：           │
│  │   booking  → http://booking.agent-platform.svc:8100            │
│  │   approval → http://approval.agent-platform.svc:8100           │
│  │   （remoteStreaming=true, detail=FULL, askPolicy=PROPAGATE,    │
│  │    headers 含 X-Agent-Protocol-Token）                          │
│  ├─ agent_spawn / agent_list / wait_async_results                 │
│  ├─ RemoteUserIdMiddleware（规范 userId 写回，F10）                │
│  └─ RemoteConfirmBridge（§5，确认路由 + 终态唤醒）                  │
└───────────┬──────────────────────────────┬──────────────────────┘
            │ Agent Protocol（集群内 svc 直连，不经 ingress）
            ▼                              ▼
┌─ booking（OAF 服务）─┐        ┌─ approval（OAF 服务）─┐
│ AgentProtocolAuth    │        │ AgentProtocolAuth     │
│ Filter（/tasks* 401） │        │ Filter（/tasks* 401）  │
│ MCP: 查询(read_only) │        │ MCP: 规则查询(allow)   │
│      下单/退改(ask)   │        │      建单/放行(ask)    │
│ MysqlDistributedStore│        │ MysqlDistributedStore │
└──────────────────────┘        └───────────────────────┘
```

## 3. OAF 包与角色设计

**trip-lead/AGENTS.md**（节选）：

```yaml
---
name: trip-lead
description: 行程履约主管：受理差旅请求，诊断问题并编排订票与提单审批
agents:
  - vendor: internal
    agent: booking
    role: 订票专员：机票/火车票查询与预订；查询类请求直接执行，写操作必须携带已批准的处置方案（plan_id + expected_version）
    endpoint: http://booking.agent-platform.svc.cluster.local:8100
  - vendor: internal
    agent: approval
    role: 提单审批专员：审批规则查询、提单创建与放行；写操作必须携带已批准的处置方案
    endpoint: http://approval.agent-platform.svc.cluster.local:8100
---
（正文：两阶段协议、plan JSON schema、委派规范、诊断阶段禁止委派写操作、
  写操作串行规约（前一写任务终态前不得再 spawn 写任务，见 §5.3）、降级路径）
```

**booking/AGENTS.md**（节选）与 **approval** 同构：

```yaml
---
name: booking
description: 订票服务
config:
  permission:
    tools:
      mcp__booking__create_order: ask     # 写操作 HITL
      mcp__booking__refund_order: ask
---
```

- mcp-configs：查询工具 `permissions.read_only: true`；写工具 ask，且工具契约强制入参 `expected_version`（乐观并发）与 `plan_id`（幂等键）。
- 两个子服务 env：`AGENT_PROTOCOL_ENABLED=true`、`AGENT_PROTOCOL_AUTH_TOKEN`（§6）、`AGENT_PROTOCOL_TASK_STORE`（§7）。
- 本方案**只用 `agent_spawn`，不用 `agent_send`**（远程 spawn 一任务一实例，persistSession 默认 false，send 语义未定义——显式划界）。

## 4. 端到端时序（两轮评审修订版：写路径后台化 + 批准后显式唤醒）

> 修订原因（第一轮 P0-1）：`agent_spawn` 同步模式在父 turn 内持 `turn_lease`；confirm-stream 是新执行段需重新 acquire 同一把锁（F8）。故**诊断委派同步（只读、秒级）、写操作委派一律 `timeout_seconds=0` 后台化**。
> 修订原因（第二轮 P1-3）：对话路径是每请求直调 `streamEvents`（F11），后台任务完成无自动触发方——批准后的汇总 turn 由 RemoteConfirmBridge 显式驱动（步骤 7）。

1. 用户向 trip-lead 发起请求（如「处理订单 O-1024 未发货」）。
2. **诊断阶段**：lead `agent_spawn(booking, 查询…)`、`agent_spawn(approval, 查询…)`（同步、默认 30s；单轮双工具调用为优化，模型单发连续 spawn 亦必须可走通）。子事件经 SSE 流回父流（source 标签）。写工具在此阶段三层封死（§6）。
3. lead 汇总事实，产出**处置方案**（结构化 JSON：动作清单、费用、`expected_version`、`plan_id`），请求用户批准。
4. 用户批准（对话级；写操作本身还会在步骤 6 再过一道工具级确认）。
5. **执行阶段**：lead `agent_spawn(booking, 按方案执行…, timeout_seconds=0)` → 立即返回 `task_id`，**父 turn 结束、释放租约**。**写任务串行规约**：前一写任务终态前不得再 spawn 写任务（§5.3 有框架级兜底）。
6. 子 agent 执行到写 MCP 工具（ask）→ 子任务挂起（`awaiting_confirm`）。**（M0 修订，F15）**：PROPAGATE 不产生父流事件、父侧 barrier/task_output 均不可见——**RemoteConfirmBridge 以快照轮询为唯一确认源**（对 lead 声明的 endpoint 周期 `GET /tasks/{id}`，或扫描任务清单），发现 `awaiting_confirm` 即落 `confirm_context`（多行，FIFO）→ 前端确认卡。
   **（M0 修订，F17）一期人工闸门上移到父级**：SDK 批准续跑存在缺陷（resume 后工具执行持续 ERROR），一期子服务写工具**不设 ask**——「执行需批准」由 §4 步骤 4 的 **plan 批准**承担（与官方案例"阶段间批准"同构），子工具全 allow + L3 服务端校验（expected_version/plan_id）兜底；子侧工具级 ask 作为 SDK 修复后的可选增强。相应地步骤 6 的子侧挂起在常态业务流中不出现（快照轮询保留，覆盖 L4/异常路径与未来增强）。
7. 用户批准/拒绝 → （一期：批准作用于 plan，spawn 携带已批准 plan 执行；若走子侧 ask 增强路径）Bridge 调 `resumeTask(..., RemoteConfirmDecision)`（approve→ALLOW，reject→DENY——M0 实测拒绝路径干净终止，F17）→ **Bridge 异步监听子任务终态**（`/wait`）→ 终态即以合成消息（「任务 {task_id} 已终态，请汇总交付」）驱动一次内部 lead 汇总 turn（走 `invokeStream` 同一管线：acquire 租约 → `streamEvents` → 事件写 durable SSE）。**开着 confirm-stream 的用户在当前流尾直接看到汇总；离线用户经 `/threads/{sid}/subscribe` 续传与 history 可见**。唤醒仅对「经确认卡批准/拒绝而续跑的远程任务」启用。
   **实现要点（第三轮确认）**：`invokeStream` 本身**不写** durable 事件流——SessionEventBus 的 emit 是 Controller 层职责（`AgentRuntimeService:486` 注释明示）。Bridge 驱动汇总 turn 时必须复刻该 emit（或抽公共方法供 controller 与 Bridge 共用），否则离线用户经 `/subscribe` 不可见。

## 5. RemoteConfirmBridge（远程确认适配层，两轮评审后的完整形态）

**问题确认**（F8/F10/F11）：confirm_context 落库事件驱动（PROPAGATE 转发的正是 `RequireUserConfirmEvent`，卡片落库可能部分自动成立），但缺口确定存在：① 决策路由（无任何代码把用户决策转成 `RemoteConfirmDecision` 调 `/resume`）；② `confirm_context.put` 同 session 覆盖式单行——并发第二个远程挂起会覆盖第一个，先到任务永久悬挂；③ 30min TTL 不适配审批场景；④ 批准后无唤醒载体。

### 5.1 存储：confirm_context 演进为多行

- 复合键 `(session_id, confirm_key)`：本地 HITL 固定 `confirm_key='local'`（维持现状覆盖语义，零回归）；远程确认 `confirm_key='task:{task_id}'`。
- 存量行回填 `confirm_key='local'`（同版本配套回填迁移，遵守 Flyway 纪律）。
- `confirm_key='local'` 行沿用 30min TTL；**远程行独立 TTL**（`AGENT_REMOTE_CONFIRM_TTL_HOURS`，默认 24h）——本地 TTL 的 AgentState 首选兜底只覆盖父 state 内挂起，远程挂起不在父 state，必须独立判定。

### 5.2 消费：FIFO 单卡，前端零改动

- history 的 `pendingConfirm` 与 confirm-stream 均取 FIFO 头（最早未消费行）——前端仍是"一次一张卡"的既有形态，并发远程挂起排队等待而非悬挂。
- 消费一行后自动推进到下一 pending（若存在），卡片连续出现。

### 5.3 并发防御与串行规约

- 提示词层（L1）：lead 两阶段协议内置「写任务串行」规约（§3 正文）。
- 框架层兜底：Bridge 检测到同 session 已有未消费远程行且又收到新远程挂起 → 记 ERROR 审计日志（违反串行规约的可观测信号），新任务照常排队（不悬挂、不丢弃）；lead 在汇总 turn 会从队列语义自然得知顺序。

### 5.4 决策路由与终态唤醒

- confirm-stream 收到决策：行含 `remote_task` → **不**走父 state 恢复，组 `RemoteConfirmDecision` 调 `POST /tasks/{id}/resume`（approve→ALLOW，reject→DENY；**M0 实测拒绝路径干净终止、无副作用**）。
- resume 后 Bridge 异步 `/wait` 终态 → 驱动 lead 汇总 turn（§4 步骤 7）；终态与决策写 tool_audit。**（M0 修订）**一期闸门在父级 plan 批准时，此路由作用于子侧 ask 增强路径；plan 未批准时 lead 不发起写委派（L1 规约 + Bridge 快照巡检兜底）。
- 远程行超 TTL 未消费 → Bridge 定时任务自动 `resume(DENY, reason=confirm_timeout)` 并记审计（用户不作为分支，§9）。

### 5.5 确认来源：快照轮询（M0 定论，F15）

- **原"监听父流转发事件"形态被 M0 排除**：PROPAGATE 下背景/同步 spawn 均无确认事件进父流；远端 spawn 恒异步（立即返回 task_id），`wait_async_results` 对 awaiting 任务固定 60s 超时，`task_output` 只显示 Running——父侧完全不可见。
- **Bridge 唯一确认源 = member `GET /tasks/{id}` 快照**（`status=awaiting_confirm` + `pending_confirms[{toolCallId,toolName,toolInputJson}]`）：对 lead 声明的每个 endpoint 维护在途任务清单（spawn 记录 + 定时对账），轮询周期 `AGENT_REMOTE_POLL_SECONDS`（默认 5s；轮询仅在存在在途任务时进行）。
- 落库锚点：`remote_task`（`{service, task_id, tool_calls, child_reply_id}`），不依赖 replyId 与父 state 匹配。

### 5.6 已知偏差：parent_session_id 传 gw-hash

- `RemoteSubmitContext.parentSessionId` 取父 `ctx.sessionId`，Channel 链路下是全进程共享的 gw-hash（issue #44 同源）而非规范 sid。
- 影响仅限子侧日志/追踪的会话聚合；跨服务会话关联由 Bridge 的 `remote_task` 表承担（落卡以规范 sid 为键），`plan_id` 对账兜底。
- **决策**：不在 `RemoteUserIdMiddleware` 里顺手写回 sessionId（SDK 内部对该 ctx 可能有其他假设，沿用 `SessionKeyResolver` 后期翻译模式）；M0 ③ 观察到子侧按 gw-hash 聚合**属预期偏差，不判 bug**。

## 6. 两阶段权限与安全（含认证 filter）

### 6.1 权限防线 L1–L4

| 层 | 机制 | 保障 |
|---|---|---|
| L1 软约束 | lead 提示词两阶段协议、plan schema、写任务串行规约 | 引导，可绕过 → L2 兜底 |
| L2 准入 | **一期（M0 修订，F17）**：人工闸门 = §4 步骤 4 的 **plan 批准**（对话级确认卡，走既有 confirm 链路）——plan 未批准 lead 不得发起写委派；子服务写工具全 allow（SDK 批准续跑缺陷规避）。**二期（SDK 修复后）**：子服务写工具 ask（frontmatter + MCP permissions）→ 工具级确认卡经 Bridge 路由 | 一期架构级"执行需批准"（阶段间批准，与官方案例同构）；二期细化为工具级 |
| L3 硬约束 | 写 MCP 工具契约强制 `expected_version` + `plan_id`，服务端拒绝版本不符/重复提交（**plan_id 仅在 plan 批准后签发有效**）；查询工具 `read_only: true` | 与 L2 独立，防注入后错误写；即使写工具无 ask 也不可绕过批准 |
| L4 阶段硬切（二期） | lead 委派时 `remoteContextAttributes` 传 `phase`；子服务 `RuntimeContextCustomizer.flatten("phase")` + `PhaseDenyMiddleware`：`phase=diagnose` 时强制 deny 写工具 | 等价官方"节点切授权" |
| 兜底 | ~~`context.deny_rules` 下传~~ **（M0 修订，F16）扩展不消费该字段**：M1 在 member `AgentFactory`/`RuntimeContextCustomizer` 读 `context.deny_rules` 自行注册动态 DENY 规则（实测未配置时 fail-closed 到 ask，方向安全） | 纵深防御（需自实现） |

**显式残差（第二轮 P3 修订）**：L4 落地前，诊断阶段"只读"实为软约束——一期由 L3 服务端校验兜底（诊断期 plan_id 未签发，写工具必然被拒），验收以「诊断阶段零写审计」为口径（§12 断言 1/9）。

### 6.2 服务间认证与信任边界（第二轮 P1-1 修订）

- **事实**（F1/F12）：Agent Protocol 端点无内建认证；业务 Ingress 单正则把服务根（含 `/tasks*`）全量暴露到 NodePort 30080——不设防即等于任何人可远程调度该 agent（其手握 shell/MCP 写工具）。
- **M1 强制措施**：agent-framework 新增 `AgentProtocolAuthFilter`（`/tasks*` 前置拦截）——`AGENT_PROTOCOL_ENABLED=true` 时**必须**配置 `AGENT_PROTOCOL_AUTH_TOKEN`，**缺失即启动失败**（fail-fast，符合"禁止硬编码密钥"约束）；无/错 token 一律 401。父侧 `SubagentDeclaration.headers` 注入同一 token（`X-Agent-Protocol-Token`，值走 env）。
- 不改内置 Ingress 形状（避免影响存量服务对外链路）；`INGRESS_TEMPLATE` overlay 可选用 PCRE 负向前瞻排除 `/tasks` 作纵深（M3 可选，非依赖项）。
- **信任假设（写入部署文档）**：内网链路（svc 直连 + token）之上，`context.user_id` 视为父服务可信声明；跨信任域调用不在本设计范围。

## 7. 状态与持久化

| 数据 | 方案 |
|---|---|
| TaskRecord（子任务协议元数据） | **bean override**：`@Bean ProtocolTaskRepository` 返回 `WorkspaceProtocolTaskRepository(本服务 HarnessAgent 的 WorkspaceManager)`（F6 公开构造），借 DistributedStore 落 `agent_fs`（MySQL、跨副本可见）；**getter 可达性已验证**（`HarnessAgent.getWorkspaceManager()` 公开方法，javap 实证——M0 ④b 收窄为验证 store 落库行为）；异常时退化为本地 FS + `AGENT_PROTOCOL_TASK_STORE` 指向 emptyDir。**禁止指向 `/config`（PVC subPath 只读）** |
| TaskRecord 清理 | 终态记录保留 N 天后清理（`AGENT_PROTOCOL_TASK_RETENTION_DAYS`，默认 7）：扩展 `SessionCleanupService`；需验证与现有清理维度不冲突（合成桶 `agents/_agentscope_protocol/` 不在会话/附件清理路径内——M1 测试点） |
| SSE 事件 | `AgentProtocolEventBus` 默认内存（replay 256）——M1 单副本可接受；M3 提供 Redis Streams 实现（复用 oaf-redis + `AGENT_REDIS_PREFIX` 隔离）后放开多副本 |
| 对话状态 | 各服务自有 `agent_state`（共享 oaf_checkpoint）；子任务 taskId 即子会话，userId 继承自 `context.user_id`（经 §8 lead 侧 `RemoteUserIdMiddleware` 规范化，F10），`IsolationScope.USER` 语义连续 |
| 确认上下文 | 父 `confirm_context` 多行形态（§5.1）为唯一授权事实源 |

副本策略：M1 子服务 `replicas=1`（平台 Deployment 模板）；TaskRecord store 化后 resume 已可跨重启，EventBus Redis 化后放开多副本。

## 8. 框架改造清单（文件级，两轮评审后）

**member 端**（所有 OAF 服务获得可被远程调度能力，默认关闭、存量零影响）
1. `pom.xml`：+ `agentscope-extensions-agent-protocol`（2.0.3）。
2. `AgentManagerProperties` / `application.yml`：`AGENT_PROTOCOL_ENABLED`（默认 false）、`AGENT_PROTOCOL_AUTH_TOKEN`（启用时必填，缺失 fail-fast）、`AGENT_PROTOCOL_TASK_STORE`、`AGENT_PROTOCOL_TASK_RETENTION_DAYS`。
3. 新增 `config/AgentProtocolConfig.java`：TaskRepository bean override（§7）+ **自定义 `AgentFactory`：`request -> agentRuntimeService.getAgent()`**——复刻 `A2aAgentRefHolder` 间接持有模式。**不可依赖默认工厂**（F14）：默认工厂拿 Spring bean，而 OAF reload 的 `swapAgent` 只换 volatile 引用不换 bean，不覆盖则 reload 后协议任务全部跑在旧 agent 实例（旧配置、MCP 已收尾）。
4. 新增 `service/protocol/AgentProtocolAuthFilter.java`：`/tasks*` token 校验（§6.2）。
5. 新增 `service/protocol/PhaseDenyMiddleware.java` + `RuntimeContextCustomizer`（L4，二期）。
6. `InfoController`/AgentCard：透出 `agent_protocol` 状态；`SessionCleanupService`：TaskRecord 清理。

**lead 端**
1. `HarnessAgentFactory`：`subAgents()` 中 endpoint 非空者构造远程 `SubagentDeclaration`（`.url(endpoint).remoteStreaming(true).remoteStreamDetail(FULL).remoteAskPolicy(PROPAGATE).headers(env 解析含 token)`）经 `.subagents(...)` 注册；headers 来源 `AGENT_REMOTE_HEADERS_JSON`（占位符规范，不进包）。
2. **`WorkspaceInitializer.writeSubagents` 修订**：endpoint 非空者**跳过 md 生成**（否则 `DynamicSubagentsMiddleware` 每轮重扫的本地声明与静态远程声明同名双注册，胜负未定义）；reload 的 stale 清理同步识别。
3. 新增 `service/RemoteConfirmBridge.java`（§5 全部职责：识别/多行落卡/FIFO/决策路由/终态唤醒/超时治理）+ `confirm_context` 表 `(session_id, confirm_key)` 复合键演进与存量回填（Flyway 新 V 文件）。
4. 新增 `service/RemoteUserIdMiddleware.java`：onActing/onAgent 阶段把 Channel 链路的 peer/gw-hash userId 经 `SessionUserStore` 反查规范 userId 写回 `RuntimeContext`（与 `McpUserContextMiddleware` 同源逻辑复用 `SessionKeyResolver`），供 `AgentSpawnTool.currentUserId` 取到真值（F10）。**只写回 userId、不写回 sessionId**（§5.6 决策）。
5. `OafReloadService` 整包重建已重走 factory，声明随 reload 刷新（现有机制，e2e 覆盖）。

**平台端（backend）**
- env 对接（遵循 platform-default-config-secret 机制）：`AGENT_PROTOCOL_ENABLED`/`AGENT_PROTOCOL_TASK_STORE`/`AGENT_PROTOCOL_TASK_RETENTION_DAYS` → 服务 ConfigMap；`AGENT_PROTOCOL_AUTH_TOKEN`、`AGENT_REMOTE_HEADERS_JSON` → **敏感键清单新增，路由进 `{name}-env-secret`**；发布表单 env 说明更新。
- 服务详情透出 agent-protocol 状态；Job 化薄封装：`Idempotency-Key → taskId` 映射表（A2A `message/send` 重复提交会建新任务，taskId 非幂等键）。

**文档更新清单**（随 M1 同 PR）：`docs/oaf-specification.md`（`agents[].endpoint` 语义启用）、`agent-framework/AGENTS.md`（新 env/新组件/T 组）、发布助手提示词（endpoint 填写指引）、`backend/AGENTS.md`（敏感键清单）。

## 9. 失败语义

| 场景 | 行为（F2/F3） | 补充 |
|---|---|---|
| 子服务不可达/报错 | TOOL_RESULT 返回错误，不打断父流 | lead 提示词约定降级（单边执行/转人工） |
| 同步等待超时（诊断） | 自动升格后台任务 | lead 用 `wait_async_results` 收割 |
| 用户拒绝确认卡 | Bridge resume 携带 DENY → 子任务终止 | lead 汇总 turn 汇报并回到方案修订 |
| **用户不作为/确认超时**（第二轮新增） | 远程行超独立 TTL（默认 24h）→ Bridge 自动 `resume(DENY, reason=confirm_timeout)` + 审计 | 卡片过 30min 本地 TTL 后 404 属预期（远程行独立判定，§5.1），超时治理兜底 |
| 父进程崩溃 | 子任务独立存活（TaskRecord store 化，§7） | 父重启后经任务状态重查；M0 断言 |
| 子服务重启 | TaskRecord 在 agent_fs，可 resume | EventBus 内存态丢失仅影响历史 SSE 重放 |
| 3 层上限 | 子不能再 spawn 孙 | 两层扁平；子服务内部分工用本地 subagent |
| 写任务并发悬挂 | FIFO 排队（§5.3），不悬挂不覆盖 | 违反串行规约记 ERROR 审计 |

## 10. 可观测性

- OTel 两侧同 collector；子事件进父 SSE 带 source（`AgentEventSseSerializer` 透出，调试页区分说话方）。
- 审计：子写工具经 `tool_audit_log`（含 userId/plan_id 入参）；`plan_id` 贯穿 父方案→子工具→MCP 服务端。**检索现状 = tool_audit args JSON LIKE（低频人工排障够用）；量级大后加生成列/索引（后续优化，非 M1 依赖项）**。
- Bridge 决策/超时/唤醒均写审计（决策、task_id、reason）。

## 11. 实施计划

- **M0 双服务探针（✅ 已完成，2026-09-28，/tmp 双进程；七项断言结果与两个设计级发现见 §15）**：
  ① 全链路 ✅（spawn→ask 挂起→快照→resume→COMPLETED→交付下一轮回流）；并发现在：PROPAGATE 不转发事件（F15）、批准续跑 SDK 缺陷（F17）；
  ② 拒绝分支 ✅（干净终止、无副作用、不受缺陷影响）；
  ③ 父崩溃 ✅（kill -9 lead 后 member 任务存活 awaiting、可继续 resume）；
  ④a deny_rules ❌（扩展不消费，转 M1 自实现，F16）；④b TaskRecord 持久化 ✅（文件存储跨 member 重启保留+可 resume；agent_fs 变体 M1）；④c userId 透传 ✅；④d 中间件上下文可见 ✅；④e 依赖兼容 ✅（Boot 3.3.5 BOM 管控 jackson 2.17.2）。
- **M1 框架能力**：§8 member 1–4/6 + lead 1–4；单测（endpoint→声明映射、md 跳过、Bridge 决策路由与 FIFO、AuthFilter fail-fast、RemoteUserIdMiddleware）+ e2e 新增 **T 组**（远程子 agent：正常/确认/拒绝/超时/子服务下线/父崩溃恢复/无 token 401/存量服务零影响；双进程编排参考 `plugin-smoke.sh` 先例）。
- **M2 业务示例包**：trip-lead + booking + approval 三包经平台发布走通，验收按 §12；**顺带量化 token 成本**（子任务独立上下文的放大倍数，写入运维文档）。
- **M3 加固**：L4 PhaseDenyMiddleware、EventBus Redis 化与多副本放开、平台 Job 薄封装、`AGENT_REACT_MAX_ITERS` 实测调优、Ingress overlay 排除 /tasks（可选纵深）。

## 12. 验收标准（M2 场景断言）

1. 诊断阶段写工具**零调用**（tool_audit 按 phase/plan_id 检索为空）；
2. 批准前零写审计（无任何 create/refund/submit 落单）；
3. 拒绝分支：子任务终止、无写落单、lead 回到方案修订话术；
4. `expected_version` 不符 → 子服务写工具拒绝（错误码可断言）；
5. `plan_id` 重放 → 幂等拒绝；
6. 父崩溃恢复：重启后任务状态可查、可续跑或明确终态；
7. 全链路 trace 中 `plan_id` 可端到端检索；
8. 存量服务回归：未设 `AGENT_PROTOCOL_ENABLED` 的服务 `/tasks` 404、行为与现状完全一致；
9. **诊断阶段零确认卡**（L4 未落地期的设计异常信号，§6.1 残差）；
10. **批准后 30s 内汇总可见**（confirm-stream 流尾或 subscribe 续传，§4 步骤 7）；
11. **`/tasks` 无 token/错 token 一律 401**（含经 Ingress `/agent/{name}/tasks` 路径）。

## 13. 风险清单

1. ~~确认事件形态未知~~ **已定论（M0，F15）**：无任何父流转发事件，Bridge 以快照轮询为唯一确认源（§5.5）。**新增首险：SDK 批准续跑缺陷（F17）**——一期以"闸门上移父级 plan 批准"规避（§6.1 L2），子侧 ask 转二期，跟进 SDK 版本修复。
2. 子服务 `SANDBOX_GUARD_ENABLED` 会按 userId 串行化同用户沙箱获取——booking/approval 同用户并发诊断任务可能排队（非错误，容量规划计入）。
3. Agent Protocol 无版本协商——两端 SDK 版本必须一致（2.0.3），纳入 `AVAILABLE_IMAGES` 白名单语义。
4. OAF 规范演进：`agents[].endpoint` 语义从"保留未用"变为"远程子 agent"，需同步规范/发布助手/平台校验（§8 文档清单）。
5. lead 单轮需容纳两阶段多次 spawn，`AGENT_REACT_MAX_ITERS`（默认 20）按 M2 实测调。
6. 远程声明 `description` 现仅 `role` 一句（`delegations` SDK 不识别），路由质量依赖描述质量——M1 与本地 subagent 描述增强一并修。
7. ~~WorkspaceManager getter 可达性未验证~~ **已验证存在**（`HarnessAgent.getWorkspaceManager()` 公开方法，javap 实证，第三轮 C1）；M0 ④b 收窄为验证 store 落库行为，异常走 §7 退化路径。
8. **多 agent 链路 token 成本放大**（子任务独立上下文），M2 量化后定预算策略；当前无配额机制。
9. Bridge 终态唤醒依赖 `/wait` 长轮询的可用性（默认 `sseTimeoutMs=3h` 覆盖）；极端情况退化为下一用户 turn 消费交付（不悬挂，仅延迟可见）。
10. Bridge 复刻 controller 层 SessionEventBus emit（或抽公共方法）涉及 `ChatStreamController` 现有链路重构——回归依赖 T 组与既有 durable SSE 用例（第三轮 C5）。

## 14. 附录：评审记录

### 第一轮（2026-09-28）

| 级别 | 问题 | 处置 |
|------|------|------|
| P0-1 | 同步 spawn 持父 turn 租约 × confirm 需重新 acquire 同锁 → 写路径确认必然排队/死锁面 | §4 重写：写路径一律 `timeout_seconds=0` 后台化（证据：`TurnLeaseStore` #47/#48、`ConfirmController` acquire） |
| P0-2 | 「confirm_context 已有」误判：agent-protocol 是第三条执行路径，决策路由到 `/resume` 全缺 | §5 RemoteConfirmBridge 升格为独立设计；事件驱动落卡可能部分自动成立（`AgentRuntimeService.storeConfirmContext`），M0 定形态 |
| P1-1 | endpoint 非空仍生成 `subagents/*.md` → 本地/远程同名双注册 | §8 lead-2：endpoint 非空跳过 md 生成 |
| P1-2 | TaskRecord 默认容器本地 FS；`/config` 只读不可指 | §7：bean override 走 agent_fs（源码确认构造可用）；禁指 /config |
| P1-3 | user_id 为父声明、子无认证——信任边界未声明 | §6 补信任假设（第二轮升级为强制 token filter，见下） |
| P1-4 | 「taskId 天然幂等锚点」不实 | §0/§8：幂等需平台薄封装 |
| P2 | 单轮双 spawn 依赖模型行为、e2e 组名撞 R 组、验收薄、两阶段纪律无评测 | §4 注明连续 spawn 兜底、T 组命名、§12 断言清单、M3 离线评测 |
| P3 | description 单薄、agent_send 边界、版本治理、V 文件编号 | §13.3/13.6、§3 划界不用 agent_send、§7/§5 遵守 Flyway 纪律 |

### 第二轮（2026-09-28）

| 级别 | 问题 | 证据 | 处置 |
|------|------|------|------|
| P1-1 | Ingress 单正则把无认证 `/tasks` 暴露到 NodePort，等于开放远程调度 | F12（objects.go:247 `(/|$)(.*) → /$2`）+ F1（无 auth 字段） | §6.2：`AgentProtocolAuthFilter` 强制 token、缺失 fail-fast；验收断言 11（含 Ingress 路径 401）；overlay 排除为可选纵深 |
| P1-2 | `confirm_context` 同 session 覆盖 × 并发任务确认互踩 → 先到任务永久悬挂 | F8（`ConfirmContextStore.put` 覆盖式单行） | §5.1/5.2：`(session_id, confirm_key)` 多行 + FIFO 单卡消费（前端零改动）+ 存量回填；§5.3 串行规约与违反审计 |
| P1-3 | 批准后无唤醒载体：`invokeStream` 每请求直调 `streamEvents`，后台交付无 turn 触发方，UX 断链 | F11（AgentRuntimeService:165） | §4 步骤 7/§5.4：Bridge 监听终态（`/wait`）→ 合成消息驱动汇总 turn，事件写 durable SSE（流尾/续传可见）；验收断言 10 |
| P2-4 | Channel 链路 userId 污染：spawn 直取 `ctx.userId`（peer/gw-hash）传子 → 多租户桶错位 | F10（AgentSpawnTool `currentUserId` → `user_id`）+ issue #44 | §8 lead-4：`RemoteUserIdMiddleware`（SessionUserStore 反查写回）；M0 ④c 断言 |
| P2-5 | confirm 30min TTL 不适配审批；"用户不作为"分支缺失 | F8（TTL 30min） | §5.1 远程行独立 TTL（默认 24h）；§5.4 超时自动 DENY + 审计；§9 新增行 |
| P2-6 | 新 env 与平台 Secret/模板机制对接遗漏 | platform-default-config-secret 机制 | §8 平台端：敏感键清单新增（AUTH_TOKEN/REMOTE_HEADERS_JSON 路由 `{name}-env-secret`） |
| P2-7 | M0 缺 MCP 多租户链路验证 | — | §11 M0 ④d |
| P3-8 | L4 二期 → 诊断"只读"是软约束（误触写工具弹卡） | — | §6.1 显式残差 + 验收断言 9 |
| P3-9 | TaskRecord 无清理策略、与 SessionCleanupService 交互未知 | — | §7 清理行（保留 N 天，扩展 SessionCleanupService，M1 测试点） |
| P3-10 | plan_id 检索=JSON LIKE，量级未定义 | — | §10 明确现状与后续优化定位 |
| P3-11 | WorkspaceManager getter 可达性未入风险清单 | — | §13.7 |
| P3-12 | token 成本治理缺失；AGENTS.md 等文档更新未列 | — | §13.8 + §8 文档更新清单 + M2 量化 |

### 第三轮（2026-09-28，残余确认）

| # | 事项 | 结论 | 处置 |
|---|------|------|------|
| C1 | WorkspaceManager getter 可达性（原风险 13.7） | javap 实证 `HarnessAgent.getWorkspaceManager()` 存在 | §7/§13.7 关闭；M0 ④b 收窄为落库行为验证 |
| C2 | 确认事件形态静态线索 | AgentSpawnTool 内部为 `List<RemotePendingConfirm>`，无裸 `RequireUserConfirmEvent` 字样（F13） | §5.5 以 RemotePendingConfirm 为主形态准备，M0 ① 终裁 |
| C3 | member 端默认 AgentFactory × OAF reload | 默认工厂拿 Spring bean，`swapAgent` 只换 volatile 引用不换 bean → reload 后协议任务跑旧 agent 实例（**新发现遗漏**，F14） | §8 member-3：自定义 AgentFactory 接 `agentRuntimeService.getAgent()`（复刻 A2aAgentRefHolder 模式） |
| C4 | parent_session_id 传 gw-hash | Channel 链路 `ctx.sessionId` 即 gw-hash（issue #44 同源），静态确认 | §5.6 已知偏差：不写回 sessionId、Bridge 表承担关联、M0 ③ 不判 bug |
| C5 | invokeStream 是否写 durable 事件流 | 否——SessionEventBus emit 是 Controller 层职责（`AgentRuntimeService:486` 注释） | §4 步骤 7 实现要点 + §13.10 回归风险 |
| C6 | agent-protocol 依赖面 | 不带 grpc（aistio 才有 grpc 传递面） | M0 ④e 范围收窄为 jackson + 自动配置共存 |

## 15. M0 双服务探针实测报告（2026-09-28，已完成）

探针形态：`/tmp/m0/{member,lead}` 两个最小 Spring Boot 3.3.5 应用（JDK 21，未动主干）——member 挂 `extensions-agent-protocol:2.0.3`（`enabled=true`，file TaskStore）+ HarnessAgent（`query_order`=allow、`create_order`=ask）+ ctx 捕获中间件；lead 挂远程子 agent 声明（`url=http://127.0.0.1:8201`、`remoteStreaming=true`、`remoteAskPolicy=PROPAGATE`）+ SSE 触发端点与事件留痕。模型为真实 LLM（OpenAI 兼容端点）。

### 断言结果

| 断言 | 结果 | 证据要点 |
|---|---|---|
| ① 全链路 | ✅ 机制闭环 + ⚠️ 两个设计级发现 | `agent_spawn`→member `POST /tasks`→子调 `create_order`→`awaiting_confirm`+`pending_confirms[{toolCallId,toolName,toolInputJson}]`→`resume(approved=true)`→任务 COMPLETED→**交付在 lead 下一轮以结果回流并汇报**（无自动推送，F11 实证） |
| ①-发现 A | ⚠️ **批准续跑 SDK 缺陷（F17）** | resume 后子 run 所有工具执行持续 ERROR（"content 参数验证失败"，AgentTraceMiddleware `state=ERROR` ×4），被批准的 `create_order` 无副作用，任务伪 COMPLETED；与 e2e-ci-plan §11.3 ① 同族 |
| ①-发现 B | ⚠️ **PROPAGATE 不转发（F15）** | 背景/同步 spawn 均无确认事件进父流；`timeout_seconds=60` 同步 spawn 立即返回 `task_id`（远端恒异步）；`wait_async_results` 60s 超时（"empty wait"）；`task_output` 只显示 Running |
| ② 拒绝分支 | ✅✅ | `resume(approved=false)`→终态 success、result=「订单创建操作被用户拒绝。」、**无副作用、不受发现 A 影响** |
| ③ 父崩溃 | ✅ | `kill -9` lead 后 member 任务保持 `awaiting_confirm`，期间直接 resume 成功转换终态（会话上下文在默认存储下不跨重启，生产 MysqlDistributedStore 覆盖，M1 确认） |
| ④a deny_rules | ❌ | 原样 `context.deny_rules` 提交仍走 ask 挂起；扩展源码零 deny/Permission 处理（F16），M1 自实现（AgentFactory/Customizer 读 context 注册动态规则） |
| ④b TaskRecord | ✅ | file TaskStore 跨 member `kill -9` 重启保留快照（同 toolCallId）且 resume 可转换终态；agent_fs（MySQL）变体按 §7 在 M1 落地 |
| ④c userId 透传 | ✅ | 子侧中间件捕获 `userId=u-canonical-42` == lead ctx.userId；子侧 sessionId = taskId |
| ④d 中间件可见性 | ✅ | onModelCall 中间件在协议路径正常捕获 ctx（McpUserContextMiddleware 前置条件成立；MCP header 端到端验证留 M1 mock MCP） |
| ④e 依赖兼容 | ✅ | Boot 3.3.5 BOM 将 jackson 管控至 2.17.2（覆盖扩展声明 2.21.1），全链路 JSON 正常；自动配置与我们的 Bean 共存无冲突 |
| 附加 | ⚠️ | 部分权限上下文 fail-closed：未声明内置工具（glob_files/list_files）逐一 ask（F18）——member 必须走完整规则展开（buildPermissionContext 已有） |

### 设计落点（v1.3 变更汇总）

1. **Bridge 确认源转正为快照轮询**（§5.5）：对在途任务周期 `GET /tasks/{id}`，`AGENT_REMOTE_POLL_SECONDS` 默认 5s；原"父流事件监听"从设计中移除。
2. **一期人工闸门上移父级 plan 批准**（§4 步骤 6/§6.1 L2）：子服务写工具一期全 allow（规避 F17），`plan_id` 批准后签发 + L3 服务端校验兜底；子侧工具级 ask 转 SDK 修复后的二期增强——与官方案例"阶段间批准"语义同构。
3. **deny_rules 兜底转自实现**（§6.1）：M1 在 member 侧读 context 注册动态 DENY 规则。
4. 新增事实 F15–F19；§13 风险 1 关闭并替换为 F17 缺陷跟进。
5. M1 清单增补：Bridge 快照轮询器与在途任务对账、deny_rules 自实现、member 走 buildPermissionContext 完整展开的确认、member/lead 会话状态依赖 MysqlDistributedStore 的确认。

### 第四轮（2026-09-28，M0 实测）

| # | 事项 | 结论 | 处置 |
|---|------|------|------|
| R4-1 | M0 七项断言 | ①②③④b④c④d④e 通过；④a 不通过；①含两个设计级发现 | §15 实测报告；v1.3 变更汇总 |
| R4-2 | PROPAGATE 转发 | 不转发（F15），父侧全盲 | §5.5 快照轮询转正 |
| R4-3 | 批准续跑 | SDK 缺陷（F17），伪 COMPLETED | 一期闸门上移父级 plan 批准（§6.1 L2），子侧 ask 转二期 |
| R4-4 | deny_rules | 扩展不消费（F16） | M1 自实现动态 DENY 注册 |

## 16. M1 交付状态与双进程/平台部署实测（2026-09-29，PR #62）

**M1 交付（PR #62，commit c0c398c）**：§8 member 1–4 + lead 1–4 全部落地（member-6 的 AgentCard 透出、平台端对接、文档三件套除外），mvn test / 三项 E2E 门禁全绿。

### 双进程实测（lead+member 真实 LLM，共享 e2e 库）

| # | 断言 | 结果 |
|---|------|------|
| D1 | /tasks* 无/错 token 一律 401，对 token 200 | ✅ |
| D2 | 登记→快照轮询落远程卡（~2s）→ routeDecision → 子任务终态 → 终态唤醒汇总 turn → 全程 tool_audit | ✅（修复两缺陷后） |
| D3 | TaskRecord 跨 member 重启保留且可 resume（§7 agent_fs） | ✅ |
| D4 | 子事件回流父 SSE（remoteStreamDetail=FULL） | ✅ |
| D5 | 残留远程行自愈：任务自行终态后 Bridge CAS 收口 | ✅ |
| D6 | member 权限覆盖：web_fetch/web_search/load_skill_through_path 未覆盖 → DEFAULT 弹 ask（F18 实证） | ❌→已修（BUILT_IN_TOOL_NAMES 补齐） |

### 实测发现（设计事实修订）

| # | 发现 | 处置 |
|---|------|------|
| F20 | **PROPAGATE 实际会把子 ask 转发进父流**（修订 F15）：permission_ask 事件到达父流并被既有 storeConfirmContext 落成 confirm_key='local' 行；但**走 local 行的确认不转发决策给远程任务**（member 收不到 resume，子任务永久挂起）——local 行为"幽灵卡" | Bridge routeDecision（confirm_key='task:{id}'）是**唯一有效决策路由**（§5.4 原则强化）；幽灵卡治理（抑制捕获/去重）转 T 组与后续迭代；双卡并存时 FIFO 与终态自愈行为正常 |
| F21 | **F17 新形态**：resume(approved=true) 后批准未生效，同一工具以新 toolCallId **重新 ask**（M0 观察为伪 COMPLETED）——同族缺陷 | 一期闸门上移父级 plan 批准（§6.1 L2）的必要性再确认；reject 路径始终干净终止 |
| F22 | SDK 2.0.3 AgentSpawnTool 实际 schema 主键为 **agent_id**（agent_key/label 为别名口径） | RemoteSpawnCaptureMiddleware/agentNameFromInput 按 agent_id 优先匹配（PR #62 修复）；TASK_ID_PATTERN 剥离尾随引号（超时升格变体结果文本实证） |
| F23 | **远程 spawn 恒异步的收敛手段**：收割全靠模型自觉调 task_output/wait_async_results 不可靠（快照式返回 + 模型反复放弃，order-fulfillment demo 三轮实证）；SDK AgentSpawnTool 暴露 RuntimeContext 属性 `agentscope.subagent.force_sync`(+`force_sync_timeout_seconds`)，注入后 spawn 阻塞等子任务完成、结果确定性回流 | 框架新增 `RemoteSpawnForceSyncMiddleware`（纯 spawn 轮次作用域注入，env `AGENT_REMOTE_SPAWN_SYNC_WAIT[_SECONDS]` 默认 true/120，§8 lead 挂载）；同步窗口内完成即内联回流，超时走既有升格后台语义 |

**同步超时升格语义实测**：`timeout_seconds=60` 的远程 spawn 同步等待 60s 后升格后台并返回 task_id（F15"恒异步"的准确表述应为"超过同步窗口后恒异步"；窗口内完成则同步返回、无后台句柄、Bridge 不登记——符合设计）。
