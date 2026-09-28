# travel-fulfillment 多 Agent 方案设计（B1：Agent Protocol 远程子 agent）

| | |
|---|---|
| 状态 | **设计定稿 v1.1（待实施）**，含 2026-09-28 两轮评审修订 |
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
6. 子 agent 执行到写 MCP 工具（ask）→ 子任务挂起（`awaiting_confirm`）→ PROPAGATE 将 `RequireUserConfirmEvent` 转发进父流 → **RemoteConfirmBridge**（§5）落 `confirm_context`（多行，FIFO）→ 前端确认卡。
7. 用户批准/拒绝 → Bridge 调 `resumeTask(..., RemoteConfirmDecision)`（approve→ALLOW，reject→DENY）→ **Bridge 异步监听子任务终态**（`/wait`）→ 终态即以合成消息（「任务 {task_id} 已终态，请汇总交付」）驱动一次内部 lead 汇总 turn（走 `invokeStream` 同一管线：acquire 租约 → `streamEvents` → 事件写 durable SSE）。**开着 confirm-stream 的用户在当前流尾直接看到汇总；离线用户经 `/threads/{sid}/subscribe` 续传与 history 可见**。唤醒仅对「经确认卡批准/拒绝而续跑的远程任务」启用。

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

- confirm-stream 收到决策：行含 `remote_task` → **不**走父 state 恢复，组 `RemoteConfirmDecision` 调 `POST /tasks/{id}/resume`（approve→ALLOW，reject→DENY）。
- resume 后 Bridge 异步 `/wait` 终态 → 驱动 lead 汇总 turn（§4 步骤 7）；终态与决策写 tool_audit。
- 远程行超 TTL 未消费 → Bridge 定时任务自动 `resume(DENY, reason=confirm_timeout)` 并记审计（用户不作为分支，§9）。

### 5.5 确认卡关联

- 转发事件的 replyId 是子侧的：Bridge 落库时以 `remote_task`（`{service, task_id, tool_calls, child_reply_id}`）为锚点，不依赖 replyId 与父 state 匹配。
- M0 断言①需分辨转发事件形态（`RequireUserConfirmEvent` 直达父流 vs 任务快照），两种形态对应 Bridge 两种识别实现。

## 6. 两阶段权限与安全（含认证 filter）

### 6.1 权限防线 L1–L4

| 层 | 机制 | 保障 |
|---|---|---|
| L1 软约束 | lead 提示词两阶段协议、plan schema、写任务串行规约 | 引导，可绕过 → L2 兜底 |
| L2 准入 | 子服务写工具 ask（frontmatter `config.permission.tools` + MCP `permissions.tools`）→ 任何写操作必然人工批准（对应官方"模型输出 approved 不算授权"） | 架构级"执行需批准" |
| L3 硬约束 | 写 MCP 工具契约强制 `expected_version` + `plan_id`，服务端拒绝版本不符/重复提交；查询工具 `read_only: true` | 与 L2 独立，防注入后错误写 |
| L4 阶段硬切（二期） | lead 委派时 `remoteContextAttributes` 传 `phase`；子服务 `RuntimeContextCustomizer.flatten("phase")` + `PhaseDenyMiddleware`：`phase=diagnose` 时强制 deny 写工具 | 等价官方"节点切授权" |
| 兜底 | `context.deny_rules`（父 DENY 规则下传子侧执行，F4） | 纵深防御 |

**显式残差（第二轮 P3 修订）**：L4 落地前，诊断阶段"只读"实为软约束——模型在诊断阶段误调写工具会被 L2 拦下弹确认卡（安全无损、体验有损）。验收以「诊断阶段零确认卡出现」为设计异常信号（§12 断言 9）。

### 6.2 服务间认证与信任边界（第二轮 P1-1 修订）

- **事实**（F1/F12）：Agent Protocol 端点无内建认证；业务 Ingress 单正则把服务根（含 `/tasks*`）全量暴露到 NodePort 30080——不设防即等于任何人可远程调度该 agent（其手握 shell/MCP 写工具）。
- **M1 强制措施**：agent-framework 新增 `AgentProtocolAuthFilter`（`/tasks*` 前置拦截）——`AGENT_PROTOCOL_ENABLED=true` 时**必须**配置 `AGENT_PROTOCOL_AUTH_TOKEN`，**缺失即启动失败**（fail-fast，符合"禁止硬编码密钥"约束）；无/错 token 一律 401。父侧 `SubagentDeclaration.headers` 注入同一 token（`X-Agent-Protocol-Token`，值走 env）。
- 不改内置 Ingress 形状（避免影响存量服务对外链路）；`INGRESS_TEMPLATE` overlay 可选用 PCRE 负向前瞻排除 `/tasks` 作纵深（M3 可选，非依赖项）。
- **信任假设（写入部署文档）**：内网链路（svc 直连 + token）之上，`context.user_id` 视为父服务可信声明；跨信任域调用不在本设计范围。

## 7. 状态与持久化

| 数据 | 方案 |
|---|---|
| TaskRecord（子任务协议元数据） | **bean override**：`@Bean ProtocolTaskRepository` 返回 `WorkspaceProtocolTaskRepository(本服务 HarnessAgent 的 WorkspaceManager)`（F6 公开构造），借 DistributedStore 落 `agent_fs`（MySQL、跨副本可见）；WorkspaceManager 可达性未验证（M0 ④b，风险 §13.7）——不可达则退化为本地 FS + `AGENT_PROTOCOL_TASK_STORE` 指向 emptyDir。**禁止指向 `/config`（PVC subPath 只读）** |
| TaskRecord 清理 | 终态记录保留 N 天后清理（`AGENT_PROTOCOL_TASK_RETENTION_DAYS`，默认 7）：扩展 `SessionCleanupService`；需验证与现有清理维度不冲突（合成桶 `agents/_agentscope_protocol/` 不在会话/附件清理路径内——M1 测试点） |
| SSE 事件 | `AgentProtocolEventBus` 默认内存（replay 256）——M1 单副本可接受；M3 提供 Redis Streams 实现（复用 oaf-redis + `AGENT_REDIS_PREFIX` 隔离）后放开多副本 |
| 对话状态 | 各服务自有 `agent_state`（共享 oaf_checkpoint）；子任务 taskId 即子会话，userId 继承自 `context.user_id`（经 §8 lead 侧 `RemoteUserIdMiddleware` 规范化，F10），`IsolationScope.USER` 语义连续 |
| 确认上下文 | 父 `confirm_context` 多行形态（§5.1）为唯一授权事实源 |

副本策略：M1 子服务 `replicas=1`（平台 Deployment 模板）；TaskRecord store 化后 resume 已可跨重启，EventBus Redis 化后放开多副本。

## 8. 框架改造清单（文件级，两轮评审后）

**member 端**（所有 OAF 服务获得可被远程调度能力，默认关闭、存量零影响）
1. `pom.xml`：+ `agentscope-extensions-agent-protocol`（2.0.3）。
2. `AgentManagerProperties` / `application.yml`：`AGENT_PROTOCOL_ENABLED`（默认 false）、`AGENT_PROTOCOL_AUTH_TOKEN`（启用时必填，缺失 fail-fast）、`AGENT_PROTOCOL_TASK_STORE`、`AGENT_PROTOCOL_TASK_RETENTION_DAYS`。
3. 新增 `config/AgentProtocolConfig.java`：TaskRepository bean override（§7）。
4. 新增 `service/protocol/AgentProtocolAuthFilter.java`：`/tasks*` token 校验（§6.2）。
5. 新增 `service/protocol/PhaseDenyMiddleware.java` + `RuntimeContextCustomizer`（L4，二期）。
6. `InfoController`/AgentCard：透出 `agent_protocol` 状态；`SessionCleanupService`：TaskRecord 清理。

**lead 端**
1. `HarnessAgentFactory`：`subAgents()` 中 endpoint 非空者构造远程 `SubagentDeclaration`（`.url(endpoint).remoteStreaming(true).remoteStreamDetail(FULL).remoteAskPolicy(PROPAGATE).headers(env 解析含 token)`）经 `.subagents(...)` 注册；headers 来源 `AGENT_REMOTE_HEADERS_JSON`（占位符规范，不进包）。
2. **`WorkspaceInitializer.writeSubagents` 修订**：endpoint 非空者**跳过 md 生成**（否则 `DynamicSubagentsMiddleware` 每轮重扫的本地声明与静态远程声明同名双注册，胜负未定义）；reload 的 stale 清理同步识别。
3. 新增 `service/RemoteConfirmBridge.java`（§5 全部职责：识别/多行落卡/FIFO/决策路由/终态唤醒/超时治理）+ `confirm_context` 表 `(session_id, confirm_key)` 复合键演进与存量回填（Flyway 新 V 文件）。
4. 新增 `service/RemoteUserIdMiddleware.java`：onActing/onAgent 阶段把 Channel 链路的 peer/gw-hash userId 经 `SessionUserStore` 反查规范 userId 写回 `RuntimeContext`（与 `McpUserContextMiddleware` 同源逻辑复用 `SessionKeyResolver`），供 `AgentSpawnTool.currentUserId` 取到真值（F10）。
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

- **M0 双服务探针（先行，1–2 天，/tmp 双进程不动主干）**，断言（两轮评审后扩为七项）：
  ① background spawn（timeout_seconds=0）→ 子 ask 挂起 → **确认事件形态**（`RequireUserConfirmEvent` 直达父流 vs 任务快照）→ Bridge 落卡 → 批准 → `resumeTask` → **批准后汇总 turn 可见**（SSE 流尾或 subscribe 续传）全链路；
  ② 拒绝分支（DENY 决策 → 子任务终止 → 汇总汇报）；
  ③ 父进程崩溃重启后任务状态重查与续跑；
  ④a `deny_rules` 下传生效；④b TaskRecord 经 WorkspaceManager 构造落 agent_fs（含 getter 可达性）；④c **子侧收到 `user_id` == 规范用户**（Channel 链路 peer 场景）；④d `McpUserContextMiddleware` 在 agent-protocol 路径的行为（含 session_user 反查分支）；④e jackson 2.21.1 与 Spring Boot 3.3 管理版本兼容。
  **任何一条不通即回炉设计**（① 决定 Bridge 识别实现；④b 决定 §7 首选/退化；④c 决定 RemoteUserIdMiddleware 必要性）。
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

1. **确认事件形态未知**（`RequireUserConfirmEvent` 直达父流 or 仅任务快照）——决定 Bridge 识别实现，M0 ①。
2. 子服务 `SANDBOX_GUARD_ENABLED` 会按 userId 串行化同用户沙箱获取——booking/approval 同用户并发诊断任务可能排队（非错误，容量规划计入）。
3. Agent Protocol 无版本协商——两端 SDK 版本必须一致（2.0.3），纳入 `AVAILABLE_IMAGES` 白名单语义。
4. OAF 规范演进：`agents[].endpoint` 语义从"保留未用"变为"远程子 agent"，需同步规范/发布助手/平台校验（§8 文档清单）。
5. lead 单轮需容纳两阶段多次 spawn，`AGENT_REACT_MAX_ITERS`（默认 20）按 M2 实测调。
6. 远程声明 `description` 现仅 `role` 一句（`delegations` SDK 不识别），路由质量依赖描述质量——M1 与本地 subagent 描述增强一并修。
7. **`HarnessAgent` 的 WorkspaceManager getter 可达性未验证**（§7 首选方案的前提，M0 ④b；退化路径已备）。
8. **多 agent 链路 token 成本放大**（子任务独立上下文），M2 量化后定预算策略；当前无配额机制。
9. Bridge 终态唤醒依赖 `/wait` 长轮询的可用性（默认 `sseTimeoutMs=3h` 覆盖）；极端情况退化为下一用户 turn 消费交付（不悬挂，仅延迟可见）。

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
