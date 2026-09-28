# travel-fulfillment 多 Agent 方案设计（B1：Agent Protocol 远程子 agent）

| | |
|---|---|
| 状态 | **设计定稿（待实施）**，含 2026-09-28 评审修订 |
| 日期 | 2026-09-28 |
| 参考 | 官方案例 [order-fulfillment](https://java.agentscope.io/v2/zh/service/cases/order-fulfillment)（AgentScope Service + Team 形态）；协议文档 [integration/protocol](https://java.agentscope.io/v2/zh/integration/protocol) |
| 关联代码 | agent-framework SDK `io.agentscope:*:2.0.3`；`extensions-a2a-client`（已在 pom 未用）；`extensions-agent-protocol`（待引入） |

## 0. 设计立场与官方案例的关系

官方案例由 AgentScope Service 平台（aistio 控制平面）承担三件事：Team 编排、Workflow 节点级授权切换、治理审计（Issue/Run/Attempt）。本方案**不引入 Service 平台**，分别以下述机制替代：

| 官方能力 | 本方案替代 | 残差 |
|---|---|---|
| Team/Leader 编排 | trip-lead 主 agent 提示词 + 远程子 agent 声明（`SubagentDeclaration.url`） | 编排质量依赖模型，需离线评测兜底 |
| Workflow 节点级授权切换 | 三层防线 + L4 硬约束（§6） | 无引擎级"节点切授权"，靠工具 ask + 服务端校验 |
| Issue/Run/Attempt 治理 + Task map | OTel trace + tool_audit_log + session_message + `plan_id` 对账 | 无工作流级重放视图 |
| Job Endpoint（API key + Idempotency-Key） | A2A `message/send` 为入口；**幂等需平台薄封装**（§12，taskId 只是执行句柄非幂等键） | 认证与显式幂等头缺省 |

角色映射：`fulfillment-lead`→`trip-lead`（主 agent，OAF 服务）；`order/inventory/logistics/after-sales`→`booking`（订票）、`approval`（提单审批），各一个 OAF 服务，开启 Agent Protocol 服务端。

## 1. 已验证事实基础（全部经本地 SDK 2.0.3 拆解 / v2.0.3 源码 / 官方文档核实，2026-09-28）

| # | 事实 | 验证方式 |
|---|------|----------|
| F1 | `agentscope-extensions-agent-protocol:2.0.3` 已发布 Maven Central；Spring Boot 自动配置：`agentscope.agent-protocol.enabled=true`（默认 false）+ 容器有 `HarnessAgent` bean 即生效，注册 `POST /tasks`、`GET /tasks/{id}`、`/wait`、`/cancel`、`/events`(SSE, `from_seq`/`Last-Event-ID` 重放)、`POST /tasks/{id}/resume` | Maven Central + v2.0.3 tag 源码（`AgentProtocolAutoConfiguration`/`AgentProtocolController`） |
| F2 | harness 内建远程子 agent 客户端：`SubagentDeclaration.builder().url(...).headers(...).remoteStreaming(true).remoteStreamDetail(FULL/VERBOSE).remoteAskPolicy(DENY/PROPAGATE)`；`remoteAskPolicy=PROPAGATE` 且流式开启时**转发 `RequireUserConfirmEvent` 到父流**；子挂起时快照 `status=awaiting_confirm`（TaskStatus 仍 RUNNING，父侧 barrier 继续等） | javap/strings 拆解 + 官方 harness/subagent、agent-protocol 文档 |
| F3 | `agent_spawn`：`timeout_seconds>0` 同步（默认 30、上限 600，超时自动升格后台）；`=0` 立即返回 `task_id` 后台执行；结果以下一轮 `<system-reminder>` 自动注入或 `wait_async_results` 收割；同步工具默认并行（`ToolkitConfig.parallel=true`）；子 agent 错误作为 TOOL_RESULT 返回不打断父流；子不能再 spawn 孙（3 层上限） | 官方 harness/subagent 文档 + AgentSpawnTool strings |
| F4 | 提交上下文：`context.user_id`→子 `RuntimeContext`（多租户继承）；`parent_session_id` 追踪；`deny_rules` 父侧 DENY 规则下传子侧执行；`attributes` 命名空间化挂 `agentprotocol.context.attributes`，可经 `RuntimeContextCustomizer.flatten` 白名单提升 | 官方 agent-protocol 文档 + `AgentProtocolConstants` 源码 |
| F5 | 恢复 API：`AgentProtocolTaskClient.resumeTask(url, headers, taskId, List<RemoteConfirmDecision>)`（决策随 resume 下发）；`AgentFactory` 每次运行（提交 + 每次 resume）被调用，resume 时仍拿最初提交上下文，HITL 前后路由一致 | javap + 官方文档 |
| F6 | 默认 `ProtocolTaskRepository = WorkspaceProtocolTaskRepository(Path.of(taskStorePath))`——**新建本地 WorkspaceManager，落容器本地 FS**（默认 `${user.dir}/.agentscope/agent-protocol`），重启即丢、副本间不可见；但存在公开构造 `WorkspaceProtocolTaskRepository(WorkspaceManager)`，可传入 store-backed 实例落 `agent_fs`（合成命名空间 `agents/_agentscope_protocol/tasks/`）；`AgentProtocolEventBus` 默认内存实现（replay buffer 256） | v2.0.3 `AgentProtocolAutoConfiguration`/`WorkspaceProtocolTaskRepository` 源码 |
| F7 | `extensions-a2a-client` 已是 agent-framework 直接依赖（未使用）；`A2aAgent`/`WellKnownAgentCardResolver` 构建已本地探针验证 | pom + 探针 |
| F8 | 我们侧关键现状：confirm 与 chat 抢**同一把** `turn_lease`（`TurnLeaseStore` 头注释 #47/#48；`ConfirmController` 两次 `acquire`，带超时排队）；`confirm_context` 落库是**事件驱动**（`AgentRuntimeService.storeConfirmContext` 捕获 `forwardEvent` 中的 `RequireUserConfirmEvent`）；A2A 通道挂起态不落 confirm_context 是既有已知限制 | 本仓库代码 |
| F9 | 官方协议分层：AG-UI（用户面）/ Agent Protocol（内部远程子 agent 面）/ A2A（外部互操作面） | 官方 integration/protocol 文档 |

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
│  │   （remoteStreaming=true, detail=FULL, askPolicy=PROPAGATE）    │
│  ├─ agent_spawn / agent_send / agent_list / wait_async_results    │
│  └─ 远程确认适配层（§5，本设计新增组件）                             │
└───────────┬──────────────────────────────┬──────────────────────┘
            │ Agent Protocol（集群内 svc 直连，不经 ingress）
            ▼                              ▼
┌─ booking（OAF 服务）─┐        ┌─ approval（OAF 服务）─┐
│ agent-protocol 端点  │        │ agent-protocol 端点    │
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
（正文：两阶段协议、plan JSON schema、委派规范、诊断阶段禁止委派写操作、降级路径）
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
- 两个子服务 env：`AGENT_PROTOCOL_ENABLED=true`、`AGENT_PROTOCOL_TASK_STORE`（§7）。
- 本方案**只用 `agent_spawn`，不用 `agent_send`**（远程 spawn 一任务一实例，persistSession 默认 false，send 语义未定义——显式划界）。

## 4. 端到端时序（评审修订版：写路径一律 background spawn）

> 修订原因（P0-1）：`agent_spawn` 同步模式在父 turn 内持 `turn_lease`；confirm-stream 是新执行段需重新 acquire 同一把锁（F8）。若写路径同步阻塞在 `awaiting_confirm`，用户批准将排队到 spawn 超时之后，且订票/审批天然分钟级，同步语义不成立。故**诊断委派同步（只读、秒级）、写操作委派一律 `timeout_seconds=0` 后台化**。

1. 用户向 trip-lead 发起请求（如「处理订单 O-1024 未发货」）。
2. **诊断阶段**：lead `agent_spawn(booking, 查询…)`、`agent_spawn(approval, 查询…)`（同步、默认 30s；单轮双工具调用为优化，模型单发连续 spawn 亦必须可走通）。子事件经 SSE 流回父流（source 标签）。写工具在此阶段三层封死（§6）。
3. lead 汇总事实，产出**处置方案**（结构化 JSON：动作清单、费用、`expected_version`、`plan_id`），请求用户批准。
4. 用户批准（对话级；写操作本身还会在步骤 6 再过一道工具级确认）。
5. **执行阶段**：lead `agent_spawn(booking, 按方案执行…, timeout_seconds=0)` → 立即返回 `task_id`，**父 turn 结束、释放租约**。
6. 子 agent 执行到写 MCP 工具（ask）→ 子任务挂起（`awaiting_confirm`）→ PROPAGATE 将 `RequireUserConfirmEvent` 转发进父流 → **远程确认适配层**（§5）落 `confirm_context` → 前端确认卡。
7. 用户批准/拒绝 → 适配层调 `resumeTask(..., RemoteConfirmDecision)` → 子续跑/终止；结果以任务交付（`<system-reminder>`）注入 lead 下一轮，lead 汇总交付。

## 5. 远程确认适配层（本设计核心新增，修复 P0-2）

**问题确认**（F8）：confirm_context 落库事件驱动（好事——PROPAGATE 转发的就是 `RequireUserConfirmEvent`，卡片落库**可能部分自动成立**），但两条缺口确定存在：① 转发事件携带的是子侧 replyId/上下文，父侧 `buildResumeContext` 的恢复路径面向**父 state 内挂起的工具**，对"父 spawn 早已返回、挂起在子侧"的场景无路由；② 没有任何代码把用户的确认决策转成 `RemoteConfirmDecision` 调 `/resume`（F5）。agent-protocol 是我们继 Channel、A2A 之后的**第三条执行路径**，与前两者同构地绕过既有 HITL 集成。

**设计**（父服务新增 `service/RemoteConfirmBridge`）：

1. **识别**：监听转发确认事件/远程任务快照，识别 `awaiting_confirm` + task 锚点（`parent_session_id` 提交时已回传，F4）。
2. **落卡**：写 `confirm_context`，新增列 `remote_task`（JSON：`{service, task_id, tool_calls, reply_id}`；Flyway 新增 V 文件，遵守禁改已合并 V 文件纪律）；无 `remote_task` 的行维持现有本地 HITL 语义，零回归。
3. **决策路由**：`/threads/{sid}/confirm-stream` 收到决策后分流——行含 `remote_task` 时**不**走父 state 恢复，改为组装 `RemoteConfirmDecision` 调子服务 `POST /tasks/{id}/resume`；approve→`ALLOW`，reject→`DENY`。
4. **收尾**：resume 后子任务终态经任务交付回流（F3），适配层同步清理 `confirm_context` 行（沿用现有 TTL 兜底）。
5. **前端**：确认卡复用现有 SSE 帧与 confirm 链路；`AgentEventSseSerializer` 补 source/remote_task 透出。

**M0 验证断言**：转发事件确实以 `RequireUserConfirmEvent` 形态进入父 `forwardEvent`（若形态是 `RemotePendingConfirm` 快照则适配层改为订阅任务快照轮询——探针必须区分这两种形态）。

## 6. 两阶段权限设计（L1–L4）

| 层 | 机制 | 保障 |
|---|---|---|
| L1 软约束 | lead 提示词两阶段协议、plan schema | 引导，可绕过 → L2 兜底 |
| L2 准入 | 子服务写工具 ask（frontmatter `config.permission.tools` + MCP `permissions.tools`）→ 任何写操作必然人工批准（对应官方"模型输出 approved 不算授权"） | 架构级"执行需批准" |
| L3 硬约束 | 写 MCP 工具契约强制 `expected_version` + `plan_id`，服务端拒绝版本不符/重复提交；查询工具 `read_only: true` | 与 L2 独立，防注入后错误写 |
| L4 阶段硬切（二期） | lead 委派时 `remoteContextAttributes` 传 `phase`；子服务 `RuntimeContextCustomizer.flatten("phase")` + `PhaseDenyMiddleware`：`phase=diagnose` 时强制 deny 写工具 | 等价官方"节点切授权" |
| 兜底 | `context.deny_rules`（父 DENY 规则下传子侧执行，F4） | 纵深防御 |

安全边界补充（P1-3 修复）：`context.user_id` 是父服务声明、子服务无内建 auth（F1 属性面无认证字段）——**信任边界 = namespace 网络隔离 + `SubagentDeclaration.headers` 静态 token（值走 env 占位符，不进 OAF 包）**；二期在 `/tasks` 前加网关 filter。此假设写入部署文档。

## 7. 状态与持久化（P1-2 修订后结论）

| 数据 | 方案 |
|---|---|
| TaskRecord（子任务协议元数据） | **bean override**：`@Bean ProtocolTaskRepository` 返回 `WorkspaceProtocolTaskRepository(本服务 HarnessAgent 的 WorkspaceManager)`（F6 公开构造），借 DistributedStore 落 `agent_fs`（MySQL、跨副本可见）；若 WorkspaceManager 不可达则退化为本地 FS + `AGENT_PROTOCOL_TASK_STORE` 指向 emptyDir 挂载（Pod 内存活，重启可接受降级）。**禁止指向 `/config`（PVC subPath 只读）** |
| SSE 事件 | `AgentProtocolEventBus` 默认内存（replay 256）——M1 单副本可接受；M3 提供 Redis Streams 实现（复用 oaf-redis + `AGENT_REDIS_PREFIX` 隔离）后放开多副本 |
| 对话状态 | 各服务自有 `agent_state`（共享 oaf_checkpoint）；子任务 taskId 即子会话，userId 继承自 `context.user_id`，`IsolationScope.USER` 语义连续 |
| 确认上下文 | 父 `confirm_context`（§5 扩展 `remote_task` 列）为唯一授权事实源 |

副本策略：M1 子服务 `replicas=1`（平台 Deployment 模板）；TaskRecord store 化后 resume 已可跨重启，EventBus Redis 化后放开多副本。

## 8. 框架改造清单（文件级）

**member 端**（所有 OAF 服务获得可被远程调度能力，默认关闭、存量零影响）
1. `pom.xml`：+ `agentscope-extensions-agent-protocol`（2.0.3）。
2. `AgentManagerProperties` / `application.yml`：`AGENT_PROTOCOL_ENABLED`（默认 false）、`AGENT_PROTOCOL_TASK_STORE`、`AGENT_PROTOCOL_REMOTE_TOKEN`。
3. 新增 `config/AgentProtocolConfig.java`：TaskRepository bean override（§7）。
4. 新增 `service/protocol/PhaseDenyMiddleware.java` + `RuntimeContextCustomizer`（L4，二期）。
5. `InfoController`/AgentCard：透出 `agent_protocol` 状态。

**lead 端**
1. `HarnessAgentFactory`：`subAgents()` 中 endpoint 非空者构造远程 `SubagentDeclaration`（`.url(endpoint).remoteStreaming(true).remoteStreamDetail(FULL).remoteAskPolicy(PROPAGATE).headers(env 解析)`）经 `.subagents(...)` 注册；headers 来源 `AGENT_REMOTE_HEADERS_JSON`（占位符规范，不进包）。
2. **`WorkspaceInitializer.writeSubagents` 修订（P1-1 修复）**：endpoint 非空者**跳过 md 生成**（否则 `DynamicSubagentsMiddleware` 每轮重扫的本地声明与静态远程声明同名双注册，胜负未定义）；reload 的 stale 清理同步识别。
3. 新增 `service/RemoteConfirmBridge.java`（§5）+ `confirm_context` 表 `remote_task` 列（Flyway 新 V 文件）。
4. `OafReloadService` 整包重建已重走 factory，声明随 reload 刷新（现有机制，e2e 覆盖）。

**平台端（backend，后置）**
- 服务详情透出 agent-protocol 状态；Job 化薄封装：`Idempotency-Key → taskId` 映射表（A2A `message/send` 重复提交会建新任务，taskId 非幂等键——P1-4 修复表述）。

## 9. 失败语义

| 场景 | 行为（F2/F3） | 补充 |
|---|---|---|
| 子服务不可达/报错 | TOOL_RESULT 返回错误，不打断父流 | lead 提示词约定降级（单边执行/转人工） |
| 同步等待超时（诊断） | 自动升格后台任务 | lead 用 `wait_async_results` 收割 |
| 用户拒绝确认卡 | 适配层 resume 携带 DENY → 子任务终止 | lead 汇报并回到方案修订 |
| 父进程崩溃 | 子任务独立存活（TaskRecord store 化，§7） | 父重启后经任务状态重查；M0 断言 |
| 子服务重启 | TaskRecord 在 agent_fs，可 resume | EventBus 内存态丢失仅影响历史 SSE 重放 |
| 3 层上限 | 子不能再 spawn 孙 | 两层扁平；子服务内部分工用本地 subagent |

## 10. 可观测性

- OTel 两侧同 collector；子事件进父 SSE 带 source（`AgentEventSseSerializer` 透出，调试页区分说话方）。
- 审计：子写工具经 `tool_audit_log`（含 userId/plan_id 入参）；`plan_id` 贯穿 父方案→子工具→MCP 服务端，端到端可检索。

## 11. 实施计划

- **M0 双服务探针（先行，1–2 天，/tmp 双进程不动主干）**，四断言（按修订时序）：
  ① background spawn（timeout_seconds=0）→ 子 ask 挂起 → 父流确认事件形态（`RequireUserConfirmEvent` vs `RemotePendingConfirm` 快照）→ 适配层落卡 → 批准 → `resumeTask` → 结果回流全链路；
  ② 拒绝分支（DENY 决策 → 子任务终止）；
  ③ 父进程崩溃重启后任务状态重查与续跑；
  ④ `deny_rules` 下传生效 + TaskRecord 经 WorkspaceManager 构造落 agent_fs 验证 + jackson 2.21.1 与 Spring Boot 3.3 管理版本兼容。
  **任何一条不通即回炉设计**（尤其 ① 决定适配层两种实现形态）。
- **M1 框架能力**：§8 member 1–3 + lead 1–3；单测（endpoint→声明映射、md 跳过、RemoteConfirmBridge 决策路由）+ e2e 新增 **T 组**（远程子 agent：正常/确认/拒绝/超时/子服务下线/父崩溃恢复；双进程编排参考 `plugin-smoke.sh` 先例）。
- **M2 业务示例包**：trip-lead + booking + approval 三包经平台发布走通，验收按 §12。
- **M3 加固**：L4 PhaseDenyMiddleware、EventBus Redis 化与多副本放开、平台 Job 薄封装、`AGENT_REACT_MAX_ITERS` 实测调优。

## 12. 验收标准（M2 场景断言）

1. 诊断阶段写工具**零调用**（tool_audit 按 phase/plan_id 检索为空）；
2. 批准前零写审计（无任何 create/refund/submit 落单）；
3. 拒绝分支：子任务终止、无写落单、lead 回到方案修订话术；
4. `expected_version` 不符 → 子服务写工具拒绝（错误码可断言）；
5. `plan_id` 重放 → 幂等拒绝；
6. 父崩溃恢复：重启后任务状态可查、可续跑或明确终态；
7. 全链路 trace 中 `plan_id` 可端到端检索；
8. 存量服务回归：未设 `AGENT_PROTOCOL_ENABLED` 的服务 `/tasks` 404、行为与现状完全一致。

## 13. 风险清单

1. **确认事件形态未知**（`RequireUserConfirmEvent` 直达父流 or 仅任务快照）——决定适配层实现形态，M0 首验；预期"需要开发适配层"而非"直接可用"。
2. 子服务 `SANDBOX_GUARD_ENABLED` 会按 userId 串行化同用户沙箱获取——booking/approval 同用户并发诊断任务可能排队（非错误，需在容量规划中计入）。
3. Agent Protocol 无版本协商——两端 SDK 版本必须一致（2.0.3），纳入 `AVAILABLE_IMAGES` 白名单语义。
4. OAF 规范演进：`agents[].endpoint` 语义从"保留未用"变为"远程子 agent"，需同步 `oaf-specification.md`、发布助手提示词与平台校验（防存量包误填）。
5. lead 单轮需容纳两阶段多次 spawn，`AGENT_REACT_MAX_ITERS`（默认 20）按 M2 实测调。
6. 远程声明 `description` 现仅 `role` 一句（`delegations` SDK 不识别），路由质量依赖描述质量——M1 与本地 subagent 描述增强一并修。

## 14. 附录：评审记录（2026-09-28）

| 级别 | 问题 | 处置 |
|------|------|------|
| P0-1 | 同步 spawn 持父 turn 租约 × confirm 需重新 acquire 同锁 → 写路径确认必然排队/死锁面 | §4 重写：写路径一律 `timeout_seconds=0` 后台化（证据：`TurnLeaseStore` #47/#48、`ConfirmController` acquire） |
| P0-2 | 「confirm_context 已有」误判：agent-protocol 是第三条执行路径，决策路由到 `/resume` 全缺 | §5 远程确认适配层升格为独立设计；事件驱动落卡可能部分自动成立（`AgentRuntimeService.storeConfirmContext`），M0 定形态 |
| P1-1 | endpoint 非空仍生成 `subagents/*.md` → 本地/远程同名双注册 | §8 lead-2：endpoint 非空跳过 md 生成 |
| P1-2 | TaskRecord 默认容器本地 FS；`/config` 只读不可指 | §7：bean override 走 agent_fs（源码确认构造可用）；禁指 /config |
| P1-3 | user_id 为父声明、子无认证——信任边界未声明 | §6 补：namespace 隔离 + 静态 token + 文档化假设 |
| P1-4 | 「taskId 天然幂等锚点」不实 | §0/§8：幂等需平台薄封装 |
| P2 | 单轮双 spawn 依赖模型行为、e2e 组名撞 R 组、验收薄、两阶段纪律无评测 | §4 注明连续 spawn 兜底、T 组命名、§12 断言清单、M3 离线评测 |
| P3 | description 单薄、agent_send 边界、版本治理、V 文件编号 | §13.3/13.6、§3 划界不用 agent_send、§7/§5 遵守 Flyway 纪律 |
