# AG-UI 原生接口·生产对等设计

> **现状核对**：2026-10-24 编制；**同日已实施 M0–M6 全量落地**（实施记录见文末 §17），
> 索引状态见 docs/README.md。文档主体保留编制时点表述。
> SDK 基线：`io.agentscope:2.0.3`（`agentscope-extensions-agui` 本地 m2 已有 jar；`agentscope-agui-spring-boot-starter` 未引入且**本设计决定不引入**，见 D1）。
> 官方文档：https://java.agentscope.io/v2/zh/integration/protocol/agui
> 前置评估链：本文档是「AG-UI 原生暴露」三档（裸挂 / 桥接 / 生产对等）中的**生产对等档**正式设计；
> 「同接口 + 编码切换」备选方案的边界对比见 §15。

## 0. 摘要

以官方 AG-UI 协议契约（`RunAgentInput` → SSE `AguiEvent`）原生暴露一套 `/agui/*` 端点，标准 AG-UI 客户端
（`@ag-ui/client` 等）可直连；同时将该面完全纳入平台既有会话治理（Durable SSE / turn_lease /
confirm_context / 每用户 MCP），使其与 native 面行为对等。核心手段：**不使用官方 starter 的请求处理链**
（其 resume 协调与单 run 仲裁为进程内存态，多副本不可用），自建 `AguiRunController` 直接组合 SDK 的
public 组件（`AguiAgentAdapter` + `AgentEventConverterRegistry`），事件在 `SessionEventBus` 层
**单流双编码**（native payload `p` + AG-UI 帧 `pa`），续传/HITL/仲裁全部复用共享存储机制。

## 1. 目标与非目标

### 1.1 生产对等判定标准（六条，验收即对照此表）

| # | 对等项 | 判定 |
|---|--------|------|
| P1 | 断联续传 | AG-UI 客户端断线重连可补齐错过事件（afterSeq 回放 + 追赶）；**断连不杀 run** |
| P2 | 多副本 | run / 续传 / confirm / status 任意请求落任意副本均正确；无进程内必态（e2e 双副本可验证） |
| P3 | HITL 对等 | AG-UI interrupt 与 native `permission_ask` 同源 `confirm_context`，可从**任一面**确认；`/status` 的 `waiting_confirm` 对两面成立；`accept_rule` 能力保留 |
| P4 | 跨面可见 | AG-UI 轮次对 native 面 `/subscribe`、`/status`、history 可见（同一 Redis 流） |
| P5 | 平台特性 | `tool_call_summary` / `file_ready` 合成帧、MCP Apps `ui` 卡片、工具审计、每用户 MCP 在 AG-UI 面等价可用 |
| P6 | 并发防护 | 同会话并发 turn 由 `turn_lease` 仲裁，无论哪个面发起 |

### 1.2 非目标

- 不替代 native 面（`/threads/*` 契约与词表不变），AG-UI 为**附加面**；
- A2A 面、Agent Protocol 面不动；
- 不支持「任意客户端任意时刻换协议渲染存量会话」（AG-UI 续传端点对 native-only 历史段降级渲染，见 §6.3）；
- 不做 run 级幂等（与 native `/chat` 口径一致，runId 重复不拒绝，文档标注）。

## 2. 证据基线（设计依据）

### 2.1 SDK agui 扩展 2.0.3（javap 实证，jar：`~/.m2/io/agentscope/agentscope-extensions-agui/2.0.3/`）

| 事实 | 结论 |
|------|------|
| `AguiRequestProcessor$Builder` 仅 4 个注入点：agentResolver / config / adapterFactory / runtimeContextResolver | **resume 协调器不可注入替换** |
| `AguiResumeCoordinator` 为 package-private final，持 `ConcurrentMap pendingInterruptsByThread` 与 `activeRunsByThread` | interrupt 登记 + 单 run 仲裁均为**进程内存态**，多副本下失效 → 必须绕开（D1/D3/D4） |
| `AguiAgentAdapter` public，构造 `(Agent, AguiAdapterConfig)`，`run(RunAgentInput[, RuntimeContext])` public | 可自行组合，不经 Processor |
| `AgentEventConverterRegistry` public，构造 `(List<AgentEventConverter>, List<AguiEventEnricher>[, boolean])`，`convert()` / `enrich()` public | 双写编码可直接复用；自定义 converter/enricher 可插 |
| `AguiEventEncoder.encode(AguiEvent) → String` / `keepAlive()` / `encodeComment()` | SSE 帧 / 心跳序列化入口现成 |
| `AguiAdapterConfig`：toolMergeMode / emitStateEvents / emitToolCallArgs / emitTokenUsage / enableReasoning / emitRunFinishedAfterError / runTimeout / eventConverters / eventEnrichers / emitSubagentEventsAsNative | 行为开关齐备，Builder 可构造 |

### 2.2 平台现有收口点（复用清单）

| 收口点 | 位置 | 本设计复用方式 |
|--------|------|----------------|
| 事件持久化单点（先落库再广播，A3 原则） | `SessionEventBus.emit/emitSynthetic`（SessionEventBus.java:100/139） | 新增 `emitDual`（D2） |
| Redis Stream 信封 `t/r/p`，seq 显式 `<seq>-0` | `RedisEventLog`（RedisEventLog.java:71/122） | 增可选 `pa` 字段（D2） |
| 攒批按 native 类型名 `_DELTA` 后缀 | SessionEventBus.java:92、SessionEventStore.java:44 | 不动（`t` 恒存 native 名） |
| 回放 + 追赶 + 终态 probe + 心跳 | `SessionEventTailer`（SessionEventTailer.java:190-330） | AG-UI 续传端点复用同构逻辑（D5） |
| turn 仲裁 | `TurnLeaseStore`（MySQL `turn_lease`，acquire/renew/release） | AG-UI run 生命周期接入（D3） |
| HITL 状态机 | `ConfirmContextStore`（`confirm_context` 多行 + CAS consume + 409 幂等） | interrupt 同源落库（D4） |
| 恢复消息装配 | `AgentRuntimeService.buildResumeMsg`（:673）→ `ConfirmResult(boolean, ToolUseBlock[, rules])` | resume[] 翻译层对接（D4） |
| 每 run agent 装配 + 权限规则 | `HarnessAgentFactory`（:187 collectPermissionRules） | controller 内等价调用（D6） |
| 工具摘要合成帧 | `TurnToolSummaryTracker` + emitter 回调（ChatStreamController.java:472） | AG-UI 轮挂同一 tracker（D7） |
| 每 run 恢复消息装配 | `buildResumeMsg` | resume[] 翻译产物走同一入口（D4） |
| 挂起态快照 | SDK MySQL checkpoint（ASKING → agent_state，2.0.3 选型依据） | 跨副本恢复兜底，不动 |

## 3. 总体架构

```
AG-UI 标准客户端（@ag-ui/client，自定义 HttpAgentTransport）
  │ ①POST /agui/runs            RunAgentInput{threadId,runId,messages,tools,state,context,forwardedProps,resume}
  │ ②GET /agui/threads/{id}/events?afterSeq=        ← 平台扩展：durable 续传
  ▼
AguiRunController（新增，本设计核心，@ConditionalOnProperty agent.agui.enabled）
  ├─ 身份：仅信请求头（平台网关注入），forwardedProps 一律不可信
  ├─ 预检：confirm_context.findPending → 有 pending 且无 resume[] ⇒ RUN_ERROR(PENDING_CONFIRM)
  ├─ resume[] 翻译（ResumeTranslator，新增）：interruptId=replyId:toolCallId → ConfirmResult(+acceptRule)
  │    └─ CAS consume confirm_context（幂等，409 语义 → RUN_ERROR）
  ├─ turn_lease.acquire(sessionId)  ⇒ 失败 RUN_ERROR(TURN_IN_PROGRESS)      [P6/P2]
  ├─ agent 装配：HarnessAgentFactory + 用户上下文头（与 McpUserHeaderCustomizer 同源） [P5]
  ├─ AguiAgentAdapter(agent, config{converters+enrichers 可注入})            [SDK public]
  │     Flux<AgentEvent> ──tap──→ SessionEventBus.emitDual(sid, replyId, event, aguiEvents)
  │     │                            ├─ p  = AgentEventSseSerializer.payload(event)   ← native 面/跨面可见 [P4]
  │     │                            └─ pa = List<AguiEvent> JSON（registry.convert 预算） [P1]
  │     └─ Flux<AguiEvent> ──→ SSE 响应（AguiEventEncoder，id:=seq，:hb 心跳）
  └─ 终态：REQUIRE_USER_CONFIRM ⇒ confirm_context.put + 双写(permission_ask / RUN_FINISHED+interrupts)
           AGENT_END ⇒ release lease；SSE 断连（cancel）⇒ 不杀任务不 release（复刻 native onCancel 语义）[P1]
```

读路径（续传）与 `/status`、`/confirm` 全部走既有共享存储，任一副本可服务 [P2]。

## 4. 关键决策

### D1 不引 spring-boot-starter、不用 AguiRequestProcessor，自建 AguiRunController

**依据**：§2.1 前两行——Processor 的 resume 合约校验（pendingInterruptsByThread）与单 run 仲裁
（activeRunsByThread）不可注入替换且为进程内存态，直接踩平台「任意请求落任意副本」红线（AGENTS.md 原则二）。
**做法**：Controller 直接 new `AguiAgentAdapter(agent, config)` 并注入自定义 converter/enricher 列表
（`AguiAdapterConfig.builder()` 支持）；Processor 承担的三件事由平台机制承接：RUN_ERROR 映射（自建，
预检/异常路径统一）、单 run 仲裁（→D3 turn_lease）、resume 合约校验（→D4 confirm_context）。
**代价**：自维护约 200 行薄控制层；SDK 升级时需回归 golden（§11 M5）。

### D2 事件单流双编码：Redis 条目增可选 `pa` 字段

- 写入：AG-UI 轮经 `SessionEventBus.emitDual` 恒写 **p + pa**（p 保证 native 面/跨面可见 [P4]；pa 供 AG-UI
  回放 [P1]）；native 轮照旧只写 p。
- 编码：pa 在 emit 侧预算（`AgentEventConverterRegistry.convert(event, ctx)` → `List<AguiEvent>` →
  `AguiEventEncoder`），与响应流同源同序，回放/实时零漂移。
- 收口在 bus 层而非 controller 层：延续 A3「持久化失败不广播」；转换失败降级 p-only + 计数告警。
- 信封 `t` 恒为 native 类型名 ⇒ 攒批（`_DELTA` 后缀）、TurnFinalizer、Tailer 终态探测**零改动**。
- 单 seq 空间：`id:` 行、afterSeq、replies ZSET 全复用。
- 存储增量：仅 AG-UI 轮，pa 约 +1.5~2× 条目体积；TTL（7d）与 25w 条上限不变，压测项见 §13 R3。

### D3 turn 仲裁 = turn_lease（替代 activeRunsByThread）

run 开始 acquire、AGENT_END 正常 release；**SSE 断连不 release 不杀任务**（对齐 native：ChatStreamController
的 onCancel 不杀任务、事件照常落库），租约由既有 TTL 过期回收，Tailer probe 负责终态判定与补帧。

### D4 HITL 双面同源（confirm_context 为唯一事实）

- **产生**：tap 到 `RequireUserConfirmEvent` 时：`confirm_context.put`（key=`local`，与 native 完全同表同语义）
  + 双写（p=`permission_ask` 帧；pa=`RUN_FINISHED{interrupts[]}`，每个 tool_call 一个 interrupt，
  `interruptId={replyId}:{toolCallId}`，metadata 沿用 SDK 转换器的 `agentscope.interruptKind=permission_confirm`、
  toolName/toolInput）+ release lease（挂起等确认，复刻 ConfirmController 语义）。
- **恢复（任一面）**：
  - AG-UI 面：下次 run 带 `resume[]` → ResumeTranslator 翻译为 `ConfirmResult(boolean, ToolUseBlock[, rules])`
    → 复用 `buildResumeMsg` 恢复消息注入 → **CAS consume confirm_context**（二次提交 →
    RUN_ERROR(confirm_already_consumed)，与 native 409 同源）→ 新 run 续流输出。
  - native 面：`/confirm`、`/confirm-stream` 原样可用（同一行记录，双面互认 [P3]）。
- **合约校验跨副本化**：resume 的 interruptId 集合必须恰等于 confirm_context 未消费 tool_call_id 集合
  （替代 SDK 内存 validateResumeStatuses）。
- **accept_rule 平台扩展**：`resume[].payload` 增可选 `acceptRule` 字段（官方客户端不传即无规则，向后兼容），
  翻译层透传 ConfirmResult.rules；native 面不受影响。
- **崩溃恢复**：ASKING 快照（checkpoint）+ confirm_context 双源兜底，复用现有；AG-UI 面新 run 预检
  pending 且无 resume → RUN_ERROR(PENDING_CONFIRM)（对齐 native `/chat` 预检 `turn_pending_confirm`）。

### D5 断联续传：自建端点复用 Tailer 语义

- `GET /agui/threads/{threadId}/events?afterSeq=N[&runId=R]`：回放（queryAfter 分页）+ 追赶（退避轮询）+
  终态 probe + `:hb` 心跳——与 `SessionEventTailer` 同构，抽公共尾随内核或参数化复用；帧 = pa + `id:=seq`。
- 终态补帧映射：`done` → 流关闭（RUN_FINISHED 已是数据事件，不重复）；崩溃/中断 →
  `CUSTOM{name=platform.control, value={kind:interrupted}}`（AG-UI 词表无此语义，用 CUSTOM 保证标准客户端
  安全忽略；**不用 RUN_ERROR**，避免把 turn 中断误标为 run 失败）。
- 官方客户端经自定义 transport 指向本端点（`@ag-ui/client` transport 可插拔）；端点为**平台扩展**，
  非协议标准，文档显式标注。

### D6 每用户 agent 装配

Controller 内等价调用 HarnessAgentFactory（含 collectPermissionRules 权限规则、UserScopedMcpClientWrapper
用户级 MCP），用户上下文取请求头（与 McpUserHeaderCustomizer / Channel 链路 session_user 反查同源约定）；
`threadId` 即平台 sessionId（SDK 侧 session 身份复用，checkpoint 恢复天然成立）。

### D7 平台特性对等

- **MCP Apps `ui`**：自定义 `AguiEventEnricher` 往 TOOL_CALL_START 注入 `ui{resourceUri, server}`
  （McpToolRegistrar.resolveUiRef 同源数据）。
- **合成帧**：AG-UI 轮挂与 native 相同的 TurnToolSummaryTracker（emitter 回调），`tool_call_summary` /
  `tool_result_preview` / `file_ready` 双写为 `CUSTOM{name=<原名>}`（p 侧词表不变）。
- **审计**：emitDual 路径复用 toolAuditStore 审计埋点。

### D8 DATA_BLOCK 兜底

SDK 无 DATA_BLOCK 转换器，首版落 RAW；前端如需结构化展示，二期加自定义 converter → `CUSTOM{name=data_block}`。

## 5. 端点契约

### 5.1 `POST /agui/runs`（标准 AG-UI，SSE）

- 请求：`RunAgentInput`（threadId=sessionId、runId=turn 标识、messages/tools/state/context/forwardedProps/resume）；
  多模态 text/image/audio/video，document → `RUN_ERROR(INVALID_INPUT_ERROR)`（SDK 语义照搬）。
- 响应：SSE，data=AguiEvent JSON（AguiEventEncoder），`id:` 行=seq，`:hb` 心跳（间隔沿用
  `agent.manager.sse.heartbeat-seconds`）。
- 预检/错误统一走 `RUN_ERROR`：code=STANDARD_ERROR_CODE，message 内嵌平台错误码 JSON
  （`turn_in_progress` / `turn_pending_confirm` / `confirm_already_consumed` / `confirm_context_not_found`），
  与 native 面错误码字符串同源。
- 前端工具合并（tools + toolMergeMode）沿用 SDK 能力，默认 MERGE_EXTERNAL_PRIORITY。

### 5.2 `GET /agui/threads/{threadId}/events?afterSeq=[&runId=]`（平台扩展，SSE）

- 回放 + 追赶 + 心跳 + 终态补帧（§D5）；帧优先 pa，native-only 段降级见 §6.3。
- 不读 `Last-Event-ID` 头（与 `/subscribe` 口径一致，游标显式传参）。

### 5.3 复用端点（协议无关，零改动）

`GET /threads/{id}/status`（waiting_confirm 双面成立）、`POST /threads/{id}/confirm(/-stream)`、
`history`、文件上传下载、`/mcp/*` 代理、`/llm-calls`。

## 6. 事件流与存储

### 6.1 写序（AG-UI 轮）

AgentEvent → registry.convert → List\<AguiEvent\>（多帧：如 TEXT_BLOCK_START 可能对应 1 帧，REQUIRE_USER_CONFIRM
对应 RUN_FINISHED+interrupts）→ emitDual 同 seq 落 p+pa → 广播（native sinks 收 p 帧、AG-UI 响应流直发帧）。
攒批窗口对 pa 同样适用（按 `t` 的 `_DELTA` 判定，聚合刷出时 p/pa 成对）。

### 6.2 seq 与幂等

单流单 seq，`id:`=seq；重连 afterSeq 语义与 native 完全一致；replies ZSET（member=replyId）照写，
history/`/status` 的 reply 维度过滤直接可用。

### 6.3 降级规则（AG-UI 订阅者读 native-only 段）

存量/native 轮次条目无 pa → 回放该段合成 `CUSTOM{name=platform.native_event, value=<p 原文>}`；
标准客户端安全忽略或按需渲染。**不试图从 p 反向重建 AgentEvent 走 RAW**（p 是有损投影，语义不保真）。

## 7. HITL 双面状态机（汇总）

```
            AG-UI run                       native /chat
                │ permission 规则命中 ASK        │ 同
                ▼                               ▼
        REQUIRE_USER_CONFIRM ──► confirm_context.put(key=local, pending)
        p=permission_ask 帧；pa=RUN_FINISHED{interrupts[]: replyId:toolCallId}
        release turn_lease；SDK 落 ASKING 快照(checkpoint)
                │                               │
   resume[](AG-UI run)                POST /confirm(/-stream)
                │                               │
                └───────────► CAS consume(0→1) ◄┘
                     二次提交 → 409 / RUN_ERROR(confirm_already_consumed)
                │
        ConfirmResult(+acceptRule) → buildResumeMsg → 新 run 续流
```

## 8. 多副本正确性论证（对照根 AGENTS.md 原则二）

| 状态 | 存储 | 任意副本可裁决 |
|------|------|----------------|
| 事件流/游标 | Redis `sess:{sid}:events`（p+pa 同条目） | ✅ 回放/追赶只读共享存储 |
| turn 仲裁 | MySQL `turn_lease` | ✅ acquire 原子 |
| 确认上下文/幂等 | MySQL `confirm_context`（CAS） | ✅ |
| agent 挂起态 | MySQL checkpoint（agent_state ASKING） | ✅ 每 run 新 agent 从 checkpoint 恢复 |
| interrupt 合约 | confirm_context 未消费集合（**非** SDK 内存 map） | ✅（对比：starter 此项 ✗） |
| 进程内仅存 | live SSE sink 缓存（可丢弃） | ✅ 断连即弃，续传走共享存储 |

e2e 双副本用例见 §11 M5 与 §12.4 UA8。

## 9. 安全

- 身份只认网关/平台注入的请求头；`forwardedProps` 不作可信来源（官方文档同款告警）；
- 路由：`/agui/*` 经 platform-router `/agent/{short}` 前缀剥离直达，与 native 面同链路；数据面网关
  `ROUTER_AUTH_MODE`（off/report/enforce）对 `/agui/*` 同样生效；
- `RUN_STARTED.input` 保留原始请求（含被拒 document）——日志/转发侧脱敏责任同官方提示，平台侧不额外处理；
- CORS 沿用现有全局配置，不单独放开。

## 10. 配置项（`agent.agui.*`，env 前缀 `AGENT_AGUI_*`）

| 键 | 默认 | 说明 |
|----|------|------|
| `enabled` | `false` | 总开关（`@ConditionalOnProperty`，关闭零开销） |
| `path` | `/agui` | 端点前缀（runs / threads/{id}/events） |
| `run-timeout` | `10m` | 对齐 SDK 默认 |
| `emit-token-usage` | `true` | CUSTOM(token_usage) |
| `emit-tool-call-args` | `true` | TOOL_CALL_ARGS 增量 |
| `enable-reasoning` | `true` | REASONING_MESSAGE_*（THINKING 映射） |
| `emit-subagent-events-as-native` | `false` | 子 agent 走 `subagent.*` CUSTOM |

不引入 `interrupt-on-disconnect` 配置——自建 controller 天然「断连不杀 run」，语义固定。

## 11. 实施计划（约 15.5 人日）

| 阶段 | 内容 | 工作量 |
|------|------|--------|
| M0 | Spike：官方 `@ag-ui/client` 自定义 transport 对接 + CUSTOM/扩展字段/raw 忽略行为验证（go/no-go 前置） | 0.5d |
| M1 | 依赖（仅 `agentscope-extensions-agui`）+ AguiRunController 骨架 + agent 装配 + turn_lease + emitDual 双写 | 3d |
| M2 | HITL：interrupt 产生/confirm_context 同源/ResumeTranslator + acceptRule/CAS 幂等/双面互认 | 2d |
| M3 | 续传端点：Tailer 公共内核抽取 + pa 回放 + CUSTOM 降级 + 终态补帧 + 心跳 | 2d |
| M4 | 特性对等：ui Enricher + TurnToolSummaryTracker 挂接 + file_ready CUSTOM + 审计埋点 | 2d |
| M5 | 测试与文档：单测 golden（29 事件类型 × 双面）+ e2e AG-UI job（先非必需：单副本冒烟 + 双副本）+ api-frontend-sse.md 增补 AG-UI 章 | 3d |
| M6 | Debug 页一致性验证：AG-UI 协议模式（适配层 + 双协议客户端 + 开关）+ UA 用例组（§12） | 3d |

Flyway：无新表（复用 confirm_context/turn_lease/agent_state；历史遗留 `agui_interrupt` 表仍不启用，见 §13 R6）。

## 12. Debug 页面一致性验证（M6 / UA 用例组）

Debug 页（`src/main/resources/static/debug/`，无构建链原生 JS ES modules，vendor 手动管理）是平台的
**参照客户端**，作为双面一致性的验证载体。现状耦合点（改造依据）：

- `js/api.js`：手写 fetch/SSE 客户端——`sendChat`(:177) / `subscribe`(:253，afterSeq 游标 :258) /
  `confirmStream`(:326)，`id:` 行收集 seq 游标（:221/:286）；`BASE` 前缀计算（:5）对 ingress
  `/agent/{name}` 前缀的剥离逻辑对 `/agui/*` 直接复用；
- `modules/chat.js`（2447 行）：`handleEvent` 按事件类型 switch 渲染 native 词表（:1324-1335 文本/思考/工具）；
  确认卡渲染 :1436、批量提交走 confirm-stream :1482-1549；刷新恢复 status→subscribe :494-560（含
  waiting_confirm 重建确认卡、interrupted 预置气泡）；**无协议抽象层**；
- e2e：`agent-framework/e2e`（Playwright）已有 `tests/ui.spec.ts` U1–U16（HITL 批准/拒绝、MCP 卡片、
  刷新续传、附件、压缩分隔条等）与 `tests/ui-multi.spec.ts` U9（多副本刷新续传）——UA 组在此体系上扩展。

### 12.1 分层验证设计

| 层 | 内容 | debug 页改动 | 可用时机 |
|----|------|--------------|----------|
| **L1 跨面观察** | 脚本驱动 `/agui/runs` 发起轮次，debug 页（native 面）渲染该轮——验证 P4（p 帧完整、渲染与 native 轮无差别）与 P3（AG-UI 挂起 → debug 页出现确认卡 → 页面批准 → AG-UI resume 续流） | **零改动** | M1 双写落地后即可手工执行（最早的可视验证手段） |
| **L2 双协议模式** | debug 页 chat 顶栏加协议开关（native / AG-UI，默认 native，sessionStorage 记忆）；AG-UI 模式下发送/续传/确认全走 `/agui/*`，经适配层喂给**同一渲染器**——同场景两种模式跑出的转录应等价 | 新增适配层 + 客户端方法 + 开关（§12.2） | M3 后（需 runs + 续传端点） |
| **L3 自动化 e2e** | UA 用例组（§12.4）扩展进 `ui.spec.ts` / `ui-multi.spec.ts` 体系 | — | M5/M6 |

### 12.2 L2 改造清单（保持零构建链，不引 npm 包）

1. **`js/agui-client.js`（新增）**：api.js 同风格三个手写方法——
   `aguiRun(threadId, message, {runId, resume, onEvent})`（POST /agui/runs，RunAgentInput 组装：
   messages 为 role=user 的 text content）、`aguiSubscribe(threadId, {afterSeq, onEvent})`
   （GET /agui/threads/{id}/events）、游标仍取 `id:` 行（与 native 同一 seq 空间）；
2. **`modules/agui-adapter.js`（新增）**：AguiEvent → 现有 handleEvent 等价调用的**映射表**（渲染器零改动）：

   | AguiEvent | 等价 native 处理 |
   |-----------|------------------|
   | RUN_STARTED | AGENT_START（建回复容器） |
   | TEXT_MESSAGE_START/CONTENT/END | TEXT_BLOCK_START/DELTA/END |
   | REASONING_MESSAGE_* | THINKING_BLOCK_* |
   | TOOL_CALL_START/ARGS/END | TOOL_CALL_START/DELTA/END（`ui` 字段透传卡片渲染） |
   | TOOL_CALL_RESULT | TOOL_RESULT_START/TEXT_DELTA/END（按 content 组装，state 映射 SUCCESS/ERROR/DENIED） |
   | RUN_FINISHED（含 interrupts[]） | permission_ask 确认卡（interrupts → tool_calls[]，interruptId 记入卡片供提交组装 resume） |
   | RUN_FINISHED（无 interrupts） | AGENT_END / done 收流 |
   | RUN_ERROR | error 帧（message 内嵌平台错误码透出） |
   | CUSTOM(token_usage) | MODEL_CALL_END 等价展示 |
   | CUSTOM(tool_call_summary / tool_result_preview / file_ready) | 对应合成帧处理（§4 D7 同名） |
   | CUSTOM(platform.control=interrupted) | interrupted 帧 |
   | CUSTOM(platform.native_event) | 续传历史降级段：按 value.type 分发到 handleEvent（该段本就是 native 帧） |
   | RAW / 未知 | 折叠原样展示（调试价值） |

3. **chat.js 分流点（两处，按当前协议模式）**：确认卡提交——native 走 confirm-stream（现状），AG-UI 走
   `aguiRun(resume[])`（approved / editedArgs / reason / acceptRule 组装）；刷新恢复——native 走 subscribe，
   AG-UI 走 aguiSubscribe（`/status` 协议无关复用）；
4. **state.js**：`protocol` 字段 + 顶栏开关 UI。

### 12.3 一致性判定原则

不比 DOM 逐字节；抽**语义转录**两面共用一个提取函数 `extractTranscript()`，断言相等：
最终文本内容、工具调用序列 `[(name, args 摘要, 终态)]`、HITL 决策与恢复段走向、file_ready 卡片数、
token usage 终值、turn 终态（done/interrupted/error）。

### 12.4 UA 用例矩阵（映射既有 U 系列）

| # | 用例 | 对应 | 验证 |
|---|------|------|------|
| UA1 | AG-UI 模式基础对话流式 | U2 | 词表映射正确性 |
| UA2 | AG-UI 工具调用 + 摘要 + 分段顺序 | U16/U4 | TOOL_CALL_* 链 + CUSTOM 合成帧 |
| UA3 | AG-UI HITL 批准全流程（resume[] approved） | U4 | interrupt 卡 + 恢复续流 |
| UA4 | AG-UI HITL 拒绝 + acceptRule 扩展字段 | U5+ | 平台扩展 resume payload |
| UA5 | 跨面互认：脚本 AG-UI run → native 页渲染/确认；native 挂起 → AG-UI resume | — | P3/P4（L1 自动化） |
| UA6 | AG-UI 模式刷新恢复（status → aguiSubscribe afterSeq） | U7 | P1（UI 面） |
| UA7 | 双面语义转录一致性对照（同 prompt 双面跑，extractTranscript 断言等价） | — | **核心一致性门禁** |
| UA8 | 多副本：AG-UI run 断连 → 副本 B aguiSubscribe 续传 → 副本 C 确认 | U9 | P2（入 multi job） |

### 12.5 约束

- debug 页保持零构建链：AG-UI 客户端手写（与现有 sendChat/subscribe 同风格），**不引 `@ag-ui/client`**；
  官方客户端生态兼容仍由 M0 spike 承担——两者互补：debug 页验**语义一致性**，M0 验**协议合规性**；
- 协议开关默认 native、仅 debug 页提供，产品前端不暴露；
- UA7 为一致性核心用例，建议升必需检查（与既有 U 组同门禁级别）；UA8 随 multi job。

## 13. 风险与验证清单

| # | 风险 | 处置 |
|---|------|------|
| R1 | 官方客户端对 CUSTOM/扩展字段实际行为未知 | M0 spike 前置验证，不通过则调整承载方式 |
| R2 | SDK 升级改变 converter 行为 | golden 用例锁词表；升级跑回归 |
| R3 | pa 增大 Redis 条目（AG-UI 轮 ~1.5-2×） | 压测确认；必要时 pa 侧只存精简帧（TOOL_CALL_ARGS 聚合） |
| R4 | 每 run 新 agent 与 close 时机错配（资源泄漏/提前关） | 对齐 native closeSession 时序；M1 加泄漏观测 |
| R5 | resume `editedArgs` 完整替换语义与 acceptRule 组合行为 | M2 专项用例（编辑+批准/编辑+拒绝/规则持久化三态） |
| R6 | `agui_interrupt` 历史表与本设计无关易混淆 | 表注释标注「历史 AG-UI 分支遗留，生产对等设计不使用」；e2e 清库脚本保留 |
| R7 | Tailer 内核抽取影响 native 面 | 纯参数化重构 + native e2e 全量回归（必需门禁照跑） |
| R8 | 双写转换失败静默降级 p-only | 计数指标 + 日志告警；AG-UI 续传对该轮降级 §6.3 规则 |
| R9 | debug 页适配层与渲染器耦合漂移（chat.js handleEvent 改动未同步映射表） | 适配层只依赖 handleEvent 稳定签名；UA 组全量回归随 U 组门禁跑 |

## 14. 验收对照（↔ §1.1）

P1 断连重连续传用例；P2 双副本三节点交叉用例；P3 双面确认交叉用例（AG-UI 挂起→native confirm；native
挂起→AG-UI resume）；P4 native /subscribe 能看到 AG-UI 轮全量 p 帧；P5 ui 卡片/摘要/审计断言；P6 同会话
双面并发 run 只允许一个。**UA 用例组（§12.4）为上述 P1–P6 的 UI 级实现载体**，UA5/UA7/UA8 分别对应
P3+P4 / 全量 / P2。

## 15. 附录 A：与「同接口 + 编码切换」方案边界

| 维度 | 本方案（原生 /agui/runs） | 同接口 + 参数（前轮评估） |
|------|--------------------------|---------------------------|
| 客户端 | 标准 AG-UI SDK **零改造直连**（传输契约即标准） | 需自定义 transport 适配平台端点形状 |
| 端点数 | 两套（native + /agui/*） | 一套 |
| 断联续传 | 自建续传端点（平台扩展，非协议标准） | /subscribe 原生即得 |
| 工作量 | ~12.5d | ~7-8d（含参数化 +0.5-1d） |
| 收敛路径 | pa 编码层可整体迁往同接口方案，双写/翻译层资产全复用 | — |

选型建议：需要官方生态直连（第三方 AG-UI 前端/工具链）→ 本方案；仅自有前端换词表 → 同接口方案。

## 16. 附录 B：被否决的替代方案（记录依据）

- **引 spring-boot-starter 裸挂/桥接**：starter 的 `AguiResumeCoordinator`（内存 interrupt 登记与单 run 仲裁）
  多副本失效（§2.1），桥接它不如绕开它——D1。
- **双流（独立 agui stream）**：seq 空间分裂、TTL/批量双份治理，劣于单流 p/pa——D2。
- **存原生事件 + 发时编码**：需重构 store/tailer 且 p 为有损投影，反向重建不保真；留作二期若出现
  「存量会话换协议渲染」真实需求再评估——§6.3。


## 17. 实施记录（2026-10-24，同日落地）

### 17.1 交付物

| 里程碑 | 交付 | 偏差记录 |
|--------|------|----------|
| M1 | pom 增 `agentscope-extensions-agui`（不引 starter，D1）；`AguiProperties` 独立配置组（未并入 AgentManagerProperties record，规避按位构造测试波及）；Redis 信封增可选 `pa` 字段（Entry/Event/EnvelopedEvent 均 4 参兼容构造，存量调用点零改动）；`SessionEventBus.emitAgui/emitSyntheticAgui/subscribeEnveloped` | ToolMergeMode 实际枚举为 `AGENT_ONLY/FRONTEND_ONLY/MERGE_FRONTEND_PRIORITY`（无文档所称 MERGE_EXTERNAL_PRIORITY）；本面不消费前端工具，取 AGENT_ONLY |
| M2 | `AguiRunController`（runs + resume 双链路）：与 /chat 同走 ChatUiChannel 网关路径（运行时会话身份/ASKING 快照同源）、turn_lease、toolSummary/审计/file_ready 合成帧对等；`AguiEventCodec`（registry + 平台 PermissionConfirmConverter：interruptId=`runId:toolCallId` 登记、RUN_FINISHED 由 lifecycle 转换器在 AGENT_END 统一携带） | 恢复段的孤儿 TOOL_RESULT 被 SDK `hasStartedToolCall` 守卫丢弃——按 pending confirm 上下文**预播种** `ctx.startToolCall + drainEvents`（丢弃种子帧）解锁；resume 面与 native /confirm 同表 CAS（双面幂等实测） |
| M3 | `GET /agui/threads/{id}/events`：SessionEventTailer 增 `TailFrameRenderer` 接口参数化（native 行为收口 `nativeRenderer()` 零变化）；`AguiTailRenderer`（pa 拆帧/§6.3 降级/终态补帧） | **实修两处 generate 契约缺陷**：Flux.generate 每次调用只允许一次 onNext——pa 拆帧的多帧下发改走 LiveState.frameQueue 跨调用消化；空渲染/取消退出路径补 `continue`/`complete` 兜底（违例表现为 IllegalStateException → 全局异常处理器映射 409） |
| M4 | ui 卡片经 CUSTOM(platform.native_event/value 透传) 对等；MCP Apps `ui` 内联字段改由 pa 侧 CUSTOM 承载（SDK AguiEvent record 不可变，v1 不内联）——与 §D7 的 Enricher 方案有出入，记录为 v1 简化 | |
| M5 | 单测 1603 全绿（新增 AguiEventCodecTest 8 例 / AguiTailRendererTest 9 例 / SessionEventStoreDualEncodeTest 5 例；存量 SessionEventBus/ChatStream/Confirm 测试桩迁 5 参 append）；e2e `api-agui` AG1–AG6（帧序/跨面可见/HITL 双面/resume 幂等/断联续传/预检）独立 `e2e-agui` job（**非必需**，独立组 AGENT_AGUI_ENABLED=true 单实例）；`api-frontend-sse.md` §15 | |
| M6 | debug 页 `js/agui.js`（与 api.sendChat/subscribe/confirmStream **同签名**传输 + AguiEvent→native 词表翻译，渲染器零改动）；chat.js 三类调用点经 `transport()` 分流；Proto 开关（sessionStorage 记忆，默认 native）；UA API 级断言由 AG 组承担，浏览器级 UA 用例待 e2e 扩充 | |

### 17.2 实测 wire 样例（AG-UI HITL 挂起，e2e 环境实录）

RUN_STARTED → TOOL_CALL_START/ARGS/END → CUSTOM(tool_call_summary) → CUSTOM(token_usage) →
CUSTOM(platform.native_event{permission_ask}) → RAW(REQUEST_STOP) → RUN_FINISHED{outcome.interrupts[0]:
id=`{runId}:{toolCallId}`, reason=tool_call, responseSchema={approved}, metadata.agentscope.interruptKind=permission_confirm}

### 17.3 验收对照（§1.1 P1–P6）

P1=AG5（断连→events 续传补齐 RUN_FINISHED）；P2=共享存储全复用（turn_lease/confirm_context/Redis 单流，
双副本专项用例待 e2e-multi 扩充）；P3=AG3/AG4（interrupt 双面状态一致 + resume 后 native 409 幂等实测）；
P4=AG2（native history//subscribe 对 AG-UI 轮全程可见）；P5=AG4 帧（tool_call_summary/file_ready CUSTOM 对等）；
P6=turn_lease 复用（同会话双面并发互斥由租约保证）。
