# chat 链路可观测性三改（trace 补 MCP 调用参数 / turn 级 token+耗时 / 每帧带 session_id）— 设计文档

**日期**：2026-11-11
**状态**：已实施（feat/chat-trace-observability 分支；单测 2023 例全绿、e2e core 98 过/multi 11 过、kind 部署验证，实施记录见 §8）
**范围**：agent-framework 运行时；控制面（backend/frontend）零改动
**来源**：业务方 2026-11-11 电话沟通提出的三点配合改造（原文：①trace 工具加上 MCP 调用参数，方便排查问题；②chat 接口返回 token 和时间；③chat 接口每条事件都带上会话 id）；同日追加：④AGENT_START 事件把 agent 开始处理的时间传过来（见 §2.6）

---

## 0. 现状与缺口（逐条对账）

### 0.1 需求①：trace 补 MCP 调用参数

**现状**（OTel 链路，默认关闭 `OTEL_TRACES_EXPORTER=none`，`=otlp` 时启用）：

| 组件 | 位置 | 覆盖内容 |
|------|------|----------|
| `HttpTracingFilter` | `service/HttpTracingFilter.java` | HTTP 根 span（`GET /threads/chat` 等），给无父级的 invoke_agent/sandbox span 提供共同父级 |
| `OtelTracingMiddleware`（SDK 内置） | `HarnessAgentFactory.java:213` 注册 | `invoke_agent` / `chat` / `execute_tool` span 骨架（仅名称/条数/token 用量） |
| `FrameworkTracingMiddleware` | 同上 :219 | 全部 span 补 `agentscope.session.id` / `agentscope.user.id` / `agentscope.tenant.prefix`（规范键） |
| `ModelIoTracingMiddleware` | 同上 :215 | chat span 补 `gen_ai.input/output.messages`（8192 字符截断） |
| `ToolCallTracingMiddleware` | 同上 :217 | execute_tool span 补 `gen_ai.tool.call.arguments` / `gen_ai.tool.call.result`——**模型侧**工具入参/出参 |
| `ReasoningTracingMiddleware` | 同上 :222 | reasoning span |
| trace_id 落库 | `SessionMessageStore.currentTraceId()`（V14 迁移） | 每条消息归档 OTel traceId；monitor 页短码展示 + 复制（issue #205） |

**缺口**：MCP 工具的**出站调用边界**（`mcp/UserScopedMcpClientWrapper.callTool`，由 `McpToolRegistrar:1070` 按规则装配）没有任何 span。排查 MCP 业务工具问题时，trace 里只能看到 execute_tool span 上的**模型侧**入参（`gen_ai.tool.call.arguments`），看不到：

1. 这次调用打到了**哪个 MCP server**（server 名）；
2. **最终出站 arguments**——`arguments` 通道注入（mcp-header-arguments-injection-design）merge 后真正发到下游的值，与模型侧入参可能不同；
3. 注入了哪些 **header**（`headers` 通道按 UserHeaderRule 动态注入）；
4. MCP 调用**自身的失败与耗时**——出站失败混在 execute_tool span 里，分不清是模型侧问题还是网络/下游问题。

monitor 页问答回放已展示工具调用参数（模型侧，`/threads/{sid}/history` 的 `tool_calls[].input`），监控面无缺口；本需求只补 **OTel trace** 的出站边界。

### 0.2 需求②：chat 接口返回 token 和时间

**现状**：

- SSE 流中只有每次模型调用的 `MODEL_CALL_END` 帧携带**单次**调用用量（`AgentEventSseSerializer:107-112`：`inputTokens` / `outputTokens` / `totalTokens`）；一次 turn 经 ReAct 多轮（默认上限 20），客户端需自行累加；
- **没有 turn 级汇总帧、没有耗时**：流结束只有 SDK 的 `AGENT_END`（或 `error` / `cancelled`），无 done 帧；
- turn 起点时间戳已存在：`ChatStreamController` 的 `turnStartedAt`（AtomicLong，租约到手后置位，现用于 sandbox_rebuilt 判定）；
- **SDK 事件自带创建时间**：`AgentEvent.getCreatedAt()` 在事件构造时即置为 `Instant.now().toString()`（ISO-8601 UTC 字符串，agentscope-core 2.0.3 字节码核实）——但 `AgentEventSseSerializer` 从未透出，`AGENT_START` 帧只有 `type` / `id` / `replyId`。

**缺口**：轻量客户端拿不到「这轮对话一共用了多少 token、跑了多久」——要么逐帧累加（还漏掉 `usage` 为 null 的 provider 差异），要么无解。

### 0.3 需求③：chat 接口每条事件带会话 id

**现状**：

- 只有新会话首帧 `{"type":"session_created","session_id":"uuid"}` 带 `session_id`（`ChatStreamController.sessionCreatedSSE`）；
- 其余事件帧顶层有 `type` / `id` / `replyId` /（非 delta 帧的）`turnId`，**均不带 session_id**。`replyId` 的写路径注入机制已收口：`SessionEventBus.emitCore/emitSynthetic/emitSyntheticAgui` → `AgentEventSseSerializer.withReplyId`（delta 帧因 payload 自带 replyId 短路、零重写），读端 `toSseFrame` 对存量行兜底。

**缺口**：客户端若异步消费/多路复用 SSE 帧（先入队后处理），delta 帧脱离首帧上下文后无法归属会话——每帧带 `session_id` 可让帧自描述。

---

## 1. 需求①：MCP 出站调用 span（`mcp.call_tool`）

### 1.1 方案

在 MCP 出站调用唯一边界 `UserScopedMcpClientWrapper.callTool(toolName, arguments, meta)` 内创建 execute_tool 的**子 span** `mcp.call_tool`（新建 `mcp/McpCallTracing.java` 静态工具类，wrapper 调用；不动 `McpToolRegistrar` 装配链——`UserScopedMcpClientWrapper` 已是最内层装饰器，也是唯一同时知道 server 名、出站最终参数、注入头清单的地方）。

选择此处（而非 `ToolCallTracingMiddleware`）的理由：

- server 名与**注入后**的出站参数只在该 wrapper 内可见——中间件层只能重复模型侧 args，补不了缺口 1/2/3；
- 再包一层 tracing wrapper 需要动 `McpToolRegistrar:1070` 装配点，对已在调用边界的现状无增益。

### 1.2 span 属性（对齐 OTel GenAI 语义约定 + `mcp.*` 惯例）

| 属性 | 值 | 说明 |
|------|-----|------|
| `mcp.server.name` | `getName()` | MCP server 名（OafConfig `mcp.server[].server`） |
| `mcp.tool.name` | 出站 toolName | 下游注册的裸工具名 |
| `mcp.call.arguments` | `outboundArguments` JSON（8192 截断） | **注入 merge 后**的最终出站入参（`injectArguments` 产物），排查「下游收到的到底是什么」 |
| `mcp.call.header_names` | 注入 header 的 key 列表（逗号连接） | **只记名、永不记值**（header 值是凭据/透传头，泄漏面不可接受；与既有「值不可作为 header 时错误只含 key 名」同一安全口径） |
| `mcp.call.meta_keys` | 出站 `_meta` 的 key 列表（逗号连接） | 只记名（`reqHeader:*` 已剥离后），用于核对 meta 通道 |

错误路径：`span.setStatus(ERROR)` + `span.recordException(e)`；耗时由 `span.end()` 自带（Jaeger/Langfuse 直接读 duration）。

### 1.3 fail-soft 与零开销边界

- 经 `GlobalOpenTelemetry.getTracer("io.agentmanager.mcp")` 取 tracer：OTel 关闭时为 no-op tracer → `span.isRecording()` 为 false → **跳过 arguments JSON 序列化**，热路径零成本（与 `ToolCallTracingMiddleware`「无有效 span 不做任何序列化」同款守卫）；
- 父 span 不可用（`Span.current()` 无效，Reactor context 断链的极端场景）时**不建 span 直接放行**——不产生孤儿 trace；
- 序列化/属性写入任何异常吞掉记 DEBUG，不影响调用链；
- 8192 截断与 `...(truncated, total N chars)` 标注格式复用 `ModelIoTracingMiddleware.truncate`（service 包包级可见 → 将截断逻辑提取为 `util/` 共享小工具，`ModelIoTracingMiddleware` 改为委托，行为逐字节不变）。

### 1.4 非目标

- 不记 header/_meta 的**值**（安全约束，见 §1.2）；
- 不覆盖 `listTools`/`initialize`（发现期静态凭据链路，无排查诉求）；
- 不改 OTel 默认开关状态——trace 排查依赖部署方开启 `OTEL_TRACES_EXPORTER=otlp`（monitor 页 trace 列当前展示 `-` 的会话即未开启，开启后新 turn 自动生效）。

---

## 2. 需求②：turn 终态汇总帧 `turn_finished`（token + 耗时）

### 2.1 契约

turn 收尾时（终态帧之后、关流之前）追加一条合成帧，经 EventBus 落库广播——实时、订阅续传、刷新回放三条路径拿到的是同一帧：

```json
{"type":"turn_finished","state":"completed",
 "usage":{"input_tokens":123,"output_tokens":456,"total_tokens":579,"model_calls":3},
 "elapsed_ms":8234,
 "session_id":"uuid","replyId":"…","turnId":"…"}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `state` | string | `completed`（AGENT_END 正常收尾）/ `waiting_confirm`（本轮 HITL 挂起，确认恢复轮会再发自己的 turn_finished）/ `error`（源流异常收尾）/ `cancelled`（用户停止，cancelled 帧之后）/ `interrupted`（断连后孤儿看护强收） |
| `usage.input_tokens` / `output_tokens` / `total_tokens` | int | 本 turn 全部 `MODEL_CALL_END` 用量**求和**；单次 `usage` 为 null 的按 0 计（不伪造） |
| `usage.model_calls` | int | 本 turn 模型调用次数（含 usage 缺失的调用）——total 为 0 时据此区分「无调用」与「provider 不回 usage」 |
| `elapsed_ms` | long | `System.currentTimeMillis() - turnStartedAt`（`turnStartedAt` 在租约到手后置位，语义为 **agent 执行段耗时，不含排队等租约时间**，与既有字段时间语义一致） |
| `session_id` / `replyId` / `turnId` | string | 由写路径注入机制统一附加（§3），builder 不重复构造 |

命名取 snake_case：与较新合成帧词表（`permission_ask.tool_calls` / `file_ready.download_url` / `tool_result_data_delta`）一致。

### 2.2 发射点：收尾单一漏斗

**位置**：`ChatStreamController.endTurnAndCleanupBuckets`（四个终态共用：AGENT_END :973、complete :757、error :761、onCancel 看护 :800）。

时序约束（全部既有不变量，本帧顺路满足）：

1. **在 `turnEnded` CAS 首胜之后**——晚到回调/HITL 双触发路径只发一次；
2. **在 `TurnFinalizer.endTurn`（closeSession 刷缓冲）之前**——拿到紧邻终态帧的 seq，且落库可回放；
3. 在 `cancelled` 合成帧之后（stop 路径该帧已在 handleEventAndEmit 内先行发射）。

实现：`endTurnAndCleanupBuckets` 增加 `TurnOutcome` 参数（`COMPLETED / WAITING_CONFIRM / ERROR / CANCELLED / INTERRUPTED`），四个调用点各传自己的值；AGENT_END 路径按本轮是否见过 `RequireUserConfirmEvent`（闭包内新增 boolean 标记）分叉 `completed` / `waiting_confirm`。

### 2.3 用量累积：`TurnUsageTracker`（新类，仿 `TurnToolSummaryTracker`）

- chat 流闭包内的**局部对象**（不进共享 map、不落存储），`handleEventAndEmit` 挂一个分支：`MODEL_CALL_END` → 累加非 null 的 `getUsage()` 三项 + `model_calls` 计数；
- HITL 语义：`waiting_confirm` 帧携带的是**本段**（挂起前）用量——确认恢复轮是独立 turn（ConfirmController），发自己的 turn_finished，两帧相加即全程（客户端按 `replyId` 区分）。

### 2.4 终态词表同步（唯一口径点）

`SessionEventTailer.TERMINAL_TYPES`（:90，`/status` 的 completed 判定与 `probe` 收流判定的唯一口径）追加 `"turn_finished"`：

- 不加的后果：turn_finished 成为 latest event 后，`/status` 会把它当非终态 → 刷新恢复流程误判 `interrupted`/`idle`；
- `AGENT_END` / `error` / `cancelled` 仍保留（存量行 + turn_finished 未覆盖的旁路，如租约丢失的 `interrupted` 帧只发本连接不落库）。

### 2.5 ConfirmController 恢复轮同口径

`ConfirmController.endTurnAndLifecycle`（恢复轮收尾漏斗，:422/:425 两处幂等兜底）做同构改造：恢复轮同样累积 MODEL_CALL_END 用量并在收尾发 `turn_finished`（state 同表；确认轮自身的排队/挂起不计入 elapsed，起点为恢复流构建处）。chat 与 confirm 两侧的差异部分（确认上下文消费次序）不强行模板化——沿用 `TurnFinalizer` javadoc 的「只收口行为完全相同的成员」边界。

### 2.6 AGENT_START 帧携带 agent 开始处理时间（追加需求④）

**方案：透传 SDK 事件自带的创建时间，不新造时间源。** `AgentEventSseSerializer.payload` 的 `AgentStartEvent` 分支追加：

```json
{"type":"AGENT_START","id":"…","replyId":"…","session_id":"…",
 "created_at":"2026-11-11T02:14:05.123456789Z"}
```

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 值 | `event.getCreatedAt()` **原样透传**（ISO-8601 UTC 字符串，`Instant.parse` 可解析） | SDK 构造 `AGENT_START` 的瞬间即「agent 开始处理本 turn」的时刻——语义精确匹配诉求；改写/换格式（如 epoch ms）徒增两端对时成本，无收益 |
| 字段名 | `created_at`（snake_case） | 与 `session_created.session_id`、monitor 接口 `created_at` 同风格；仅此一帧携带，客户端按键名读 |
| 只挂 AGENT_START | 不扩散到其他事件类型 | 按诉求最小改动；SDK 每个 `AgentEvent` 都有 `createdAt`，后续要扩是加一行分支的事 |

语义边界（写进 javadoc）：

- `created_at` = **agent 处理起点**，不含排队等租约与消息构建段：排队时长 ≈ `created_at` − chat 请求到达时刻（客户端用首帧接收时间自算）；与 `turn_finished.elapsed_ms` 的计时锚点（`turnStartedAt` 租约到手）不同——两帧联合即可拆出「排队 + 准备 / 执行」两段；
- confirm 恢复轮经同一 serializer，`AGENT_START` 自动同口径（零额外改动）；
- 远程子 agent 转发的 `AGENT_START`（`source` 非空）：透传的是该事件**自身**的 `created_at`，即远端子 agent 真实开始时刻——如实记录，不作改写；
- **存量回放帧不兜底注入**：与 `session_id`（读端可从行归属补齐）不同，存量行无法找回事件构造时刻，绝不伪造——升级窗口内回放的旧 `AGENT_START` 帧无 `created_at`，客户端须容忍缺失（新数据随写随带）。

### 2.7 非目标

- `/status` 不加 usage 字段（turn_finished 已落库，订阅/回放可取）；
- A2A `invoke_agent` 流（`AgentRuntimeService.forwardEvent` 的 Map 词表）与 AG-UI `POST /threads/{sid}/runs` 不加——本需求只针对 `/threads/chat` 面，后续有诉求再对齐；
- `created_at` 不扩散到 AGENT_START 以外的事件类型（§2.6）；
- 不做跨 turn 累计/会话级用量账本。

---

## 3. 需求③：每帧带 `session_id`

### 3.1 契约

- **经 EventBus 的所有帧**（`emit` / `emitSynthetic` / `emitSyntheticAgui`，即实时广播 + 落库 + 订阅回放三路同源）data JSON 顶层恒有 `"session_id"`（snake_case，与 `session_created` 首帧键名一致）；
- 键序不承诺（客户端按键名读，不按序解析）；客户端对存量行（§3.4）仍须容忍缺失——回放升级窗口内由读端兜底补齐，实时路径恒有。

### 3.2 写路径：构建期注入为主、兜底注入收尾

**主路径（构建期，delta 零重写）**：`AgentEventSseSerializer.payload` 增加 sessionId 参数（`payload(event, sessionId)` / `payload(event, sessionId, uiResourceUri, uiServer)`），`LinkedHashMap` 直接 `put("session_id")`——与 `put("id")` 同级的一次写操作，**不做 readTree 重写**。这是 delta 帧（token 热路径）的零成本通道；`replyId` 走 `extractReplyId` 的现状不变。

调用方适配（均在 sessionId 作用域内，逐一改签名）：

| 调用点 | 说明 |
|--------|------|
| `ChatStreamController.payloadForEvent` | 原生事件帧（含 MCP Apps ui 覆写） |
| `ConfirmController` 事件转发 | 恢复轮同口径 |
| `TurnToolSummaryTracker` 摘要 payload 构建后经 emitSynthetic 发射 | 走写路径兜底即可，无需改 |

**兜底路径（写路径注入，覆盖手拼字符串帧与覆写帧）**：`AgentEventSseSerializer.withReplyId(payload, replyId, includeTurnId)` 扩展为 `withSessionContext(payload, sessionId, replyId, includeTurnId)`：`!has("session_id")` 时 `put`——构建期已带的新数据 `has` 短路、原引用返回（零重写）；手拼帧（`cancelled` / `sandbox_rebuilt` / `waiting` / 手拼 error）与 `payloadOverride` 帧在此补齐。`SessionEventBus` 三个发射入口本就有 sessionId 参数，直接传入。帧均为里程碑级（非 token 热路径），重写成本可忽略。

**前端校验类 error 帧**：`errorSSE` 手拼工厂（`ChatStreamController:1301`）直接 `sink.next` / `Flux.just` 返回、不经 EventBus。将 sessionId 生成（UUID）提到方法入口校验之前，使 `message` 校验失败 / model_reject / `thread_not_found` 等前置 error 帧也带 `session_id`——调用方拿到的任何一帧都能归属会话。

### 3.3 读路径：存量行兜底注入

- 实施口径（比初稿更收敛，不动 `EnvelopedEvent` record）：`toSseFrame(EnvelopedEvent, sessionId)` 增传会话参数（订阅端点路径参数，两处调用点均在作用域内）；`TailFrameRenderer.render(e, sessionId)` 接口同步增参——native 渲染器据此兜底，AG-UI 渲染器忽略（AG-UI 帧自带 `threadId`）。record 形状不变，存量测试构造零改动；
- p 内缺 `session_id` 的**存量行**经 `withSessionContext` 兜底注入（新数据 `has` 短路、零重写——与 `withReplyId` 同款生命周期：Redis TTL 耗尽存量后兜底分支退化为死代码）；

### 3.4 兼容性

- **纯增量字段**：debug console（chat.js 按 `type` 分发、未知 type 已容忍）、e2e 断言（JSON 解析按键名取值）不受影响；
- **不回填历史**：Redis 中升级前落库的行靠 §3.3 读端兜底，客户端可见流恒带；库内字节不迁移；
- AG-UI `pa` 帧数组不动（AG-UI `RunStarted` 自带 `threadId`，非本需求范围）；
- A2A `forwardEvent` 的 Map 词表不动（独立面，见 §2.6）。

---

## 4. 实施拆解（建议三个独立 commit，同一条 feature 分支）

| 步骤 | 内容 | 涉及文件 |
|------|------|----------|
| M1 = §3 | session_id 注入机制（先做——§2 的帧复用它） | `AgentEventSseSerializer`、`SessionEventBus`、`SessionEventStore`、`ChatStreamController`、`ConfirmController`、`util/`（无新增） |
| M2 = §2 | turn_finished + TurnUsageTracker + TERMINAL_TYPES + AGENT_START 透传 `created_at` | `ChatStreamController`、`ConfirmController`、`TurnUsageTracker`（新增）、`SessionEventTailer`、`AgentEventSseSerializer` |
| M3 = §1 | mcp.call_tool span | `mcp/McpCallTracing`（新增）、`UserScopedMcpClientWrapper`、`util/` 截断工具（新增，`ModelIoTracingMiddleware` 委托改造） |

约束对账：

- 原则一（控制面/业务面分层）：全部改动在业务运行时进程内，纯观测/帧字段，不触碰 K8s 资源、无启动路径 reconcile；
- 原则二（无状态可横扩）：TurnUsageTracker 为 turn 闭包局部对象；turn_finished 经 EventBus 落库（Redis Streams），任意副本订阅/回放同帧；MCP span 依赖 OTel context 随 Reactor 链传递，与副本无关；
- e2e-multi / e2e-protocol-multi 双副本门禁保持（本设计不引入任何 Pod 本地正确性状态）。

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| delta 帧加 session_id 的热路径成本 | 构建期 put（序列化本来就在做），无 readTree/重写；实测基线：单帧 JSON 构建微秒级，put 占比可忽略 |
| 「最后帧」类型变化破坏 `/status` 判定与前端收流 | `TERMINAL_TYPES` 同步追加（§2.4，唯一口径点一处改动）；前端 chat.js/e2e 终态归一仍认 AGENT_END（提前于 turn_finished，顺序不变） |
| OTel context 在 MCP 出站 Mono 链断链 → 父 span 无效 | `Span.current()` 无效即跳过建 span（§1.3），绝不产生孤儿 trace；SDK 的 ContextPropagationOperator 已在 onActing 链生效，正常路径可覆盖 |
| `usage` 为 null 的 provider 使 total 失真 | 按 0 计 + `model_calls` 独立计数（§2.1），不伪造数据 |
| header/_meta 值泄漏进 span | 只记 key 名清单（§1.2），与既有「错误只含 key 名」安全口径一致 |

## 6. 测试计划

**单测**（agent-framework-ci `mvn test` 门禁）：

- `AgentEventSseSerializer`：session_id 构建期注入（含 delta 帧恒带）、`withSessionContext` 的 has 短路/兜底注入/异常原串返回；`AGENT_START` 分支透传 `created_at`（值 = `getCreatedAt()` 原串、其他事件类型不携带、远程转发事件不改写）；
- `SessionEventBus`：emit/emitSynthetic 落库 payload 恒带 session_id（写读同源）；
- `TurnUsageTracker`：多 MODEL_CALL_END 求和、usage null 计 0、model_calls 计数；
- ChatStream/Confirm 收尾：turn_finished 帧序（终态帧之后）与 state 分叉（completed/waiting_confirm/error/cancelled/interrupted）各一例；CAS 幂等（晚到回调不重复发）；
- `SessionEventTailer`：`isTerminalType("turn_finished")`；
- `McpCallTracing`：OTel 开启（in-memory span exporter，仿 `OtelConfigTest`）断言 mcp.* 属性/错误状态；OTel 关闭（no-op）零异常零序列化；header 只记名不记值。

**e2e**（现有套件回归，不新增必需 job）：

- E2E 核心：chat 流新增帧的终态归一（AGENT_END 仍为业务终态）；
- E2E 多副本（R 组）：回放/续传帧带 session_id；
- 工具插件组（非必需）：HITL 挂起恢复两段各发 turn_finished 的 `waiting_confirm`/`completed` 序列。

## 7. 验收口径（对业务方三点的逐条回应）

1. **trace 里能看到 MCP 调用参数**：开启 OTLP 导出后，任一 MCP 工具调用的 trace 树为 `chat → execute_tool → mcp.call_tool`，末级 span 可见 server 名、最终出站参数、注入 header 名、MCP 调用自身错误与耗时；
2. **chat 接口返回 token 和时间**：每轮对话流末尾（AGENT_END/error/cancelled 之后）收到 `turn_finished` 帧，含本轮 token 汇总与执行耗时；断线重连经 `/subscribe` 回放同样拿得到；本轮的 `AGENT_START` 帧携带 `created_at`（agent 开始处理的墙钟时刻，ISO-8601 UTC），与 `elapsed_ms` 联合可拆出「排队+准备 / 执行」两段耗时；
3. **每条事件带会话 id**：`/threads/chat` 与 `/threads/{sid}/subscribe` 的每一条 data 帧顶层恒有 `session_id`。

## 8. 实施记录（2026-11-11，feat/chat-trace-observability 分支）

### 8.1 落点对账

| 步骤 | 实际改动 | 与设计的偏差 |
|------|----------|--------------|
| M1 §3 | `AgentEventSseSerializer`（payload 构建期注入 + `withSessionContext` + `toSseFrame(e, sid)`）、`SessionEventBus` 三发射入口、`SessionEventTailer`（`TailFrameRenderer.render` 增参）、`ChatStreamController`/`ConfirmController`（sessionId 生成前置 + errorSSE/waiting/cancelled/interrupted 帧带归属）、`AguiRunController`/`AguiTailRenderer`（接口适配，AG-UI 不消费 sessionId） | §3.3 未扩 `EnvelopedEvent` record，改 `toSseFrame` 增参（record 形状不变，存量测试构造零改动） |
| M2 §2 | `TurnUsageTracker`（新）、`TurnFinalizer`（`TurnOutcome` 枚举 + `emitTurnFinished` 收口 + `interruptedSSE` 带归属）、两侧收尾漏斗 CAS 前移、`SessionEventTailer.TERMINAL_TYPES` + `turn_finished` | 无偏差；confirm 恢复段挂起（`lease.release()` 后 AGENT_END 才到）路径的 turn_finished 在 closeSession 之后发射——seq 由流顶端重播种保证单调，落库可回放 |
| M3 §1 | `McpCallTracing`（新，mcp 包）、`UserScopedMcpClientWrapper.callTool` 挂接、`TraceText`（新，util）承接截断、`ModelIoTracingMiddleware.truncate` 改委托（包级签名不变） | 无偏差 |

### 8.2 验证结果

- **单测**：`mvn test` 2034 例全绿（本分支新增：serializer 注入/AGENT_START created_at/withSessionContext 短路、TurnUsageTracker 三态、McpCallTracing span 属性+错误状态+孤儿防护（TracingTestBase InMemorySpanExporter）、ChatStream turn_finished 帧序与逐帧 session_id、Tailer turn_finished 终态）；
- **e2e**：core 组 98 passed / 3 skipped（2.9 min）、multi 组 11 passed（R 组双副本接管 + U9，本地 kind 开发机 13306/16379 基础设施）；
- **kind 部署验证**：新镜像经 kind load 覆盖节点缓存后重启测试服务 `oaf-e2e-session-skills`（白名单 `:latest` 镜像；jar 等价构建——本机 docker 代理不可用无法重拉基础镜像，正式镜像由 CI 在 master push 时构建推送），Flyway 补应用 V16 后启动正常；真实 LLM 冒烟对话断言：10/10 data 帧带 `session_id`、`AGENT_START.created_at` 透传（ISO-8601 UTC）、`turn_finished` 于 AGENT_END 之后（`state=completed`，usage 与 MODEL_CALL_END 求和对账一致，`elapsed_ms` 合理）；
- **一处既有 e2e 竞态测试修复**：`ChatStreamControllerTest#chatRegistersQueuedIntentAndClearsItAfterLeaseAcquired` 的 `intentStore.clear` 验证改 `timeout(2000)`——clear 发生在 closeSession（流完成信号）之后的 boundedElastic 线程上，与 collectList 返回存在竞态（同文件 release 验证既有先例），本分支 turn_finished 使窗口微增后在该机全量跑中必现。

### 8.3 收流端与本地 e2e 基建的连带修正（两处，均为「最后一帧变化」的既有约定同步）

1. **`e2e/lib/sse.ts` 终态归一**（§5 风险表预告项）：last-frame 归一原只认 `AGENT_END`，turn_finished 成为落库流最后一帧后全部 chat 流被判「未收敛到终态」——首轮回归 43 例级联失败，归一补认 `turn_finished` 后 core/multi 全绿。前端控制台（ChatPane.tsx「流关闭即终态」）与 debug console 无需改动；
2. **`e2e/scripts/reset-data.mjs` DROP 清单补 V15 表**（既有缺口，与本分支语义无关但被本机二轮复用库暴露）：清单未含 `sandbox_user`/`sandbox_binding`，残留表使 schema 非空 + 历史表被清 → Flyway baseline(5) 跳过 V1-V5 → V6 因 `session_user` 缺失启动即死——正是该文件注释里 V2/V8/V10/V13 逐表记载的同一失效模式，CI 全新 services 不受影响。

### 8.4 遗留（非本分支范围）

- 工具插件 e2e 组的 HITL 两段 `waiting_confirm`/`completed` 断言（非必需 job，待该组回归时顺带补）；
- A2A `forwardEvent` Map 词表与 AG-UI 面的对齐（§2.6 非目标）。
