# AG-UI 协议迁移执行计划（agui-migration-plan）

> 状态：**迁移完成（Phase 0/1/2/3 全部落地）**（v10，2026-09-11 Phase 3 下线与清理：① 删除 SessionStreamController/ConfirmController/StreamController/AgentEventSseSerializer/ConfirmContextStore/UiContextInjectionHook/ChannelConfig（+7 测试类与 CleanupConfig.confirmTtlMinutes，500→455 用例全绿）；② AgentRuntimeService 删 resumeWithConfirm/Stream、putConfirmContext、ConfirmContext record 与 Channel gw-hash 派生（A2A HITL 恢复随 D8 退役，forwardEvent 不再写 confirm_context）；ThreadController/AguiThreadsController 删 pendingConfirm 展示；SessionCleanupService 删 confirm 清理；confirm_context 表已在集群 DROP；③ Debug Console 删 a2a/channel 模式（chat.js 旧词表渲染 handleEvent/handleFrame/submitConfirm/consumeConfirmStream/renderConfirmCard + 模式按钮 + api.js sendA2A/confirmStream/getThreads/getThreadHistory 封装），streamMode 收敛 agui 单值；④ AGENTS.md/api.md 同步（端点表/目录树/表文档新增 AG-UI 端点族与 agui_interrupt）；⑤ 镜像重建部署集群 + 回归（3.4 验收）；hitl-test 服务/包保留作 HITL 回归载体（v9，2026-09-11 集群回归：agent-framework/platform-frontend 新镜像部署集群（tag 重指 + rollout restart），集群入口 :8911/:30080 全通；**集群回归结果：chat-ui 4/4（T4 MCP 工具在集群转绿）、file-support 15/15（U16 断言补 "csv/读取" token——上下文恢复实际成功，另以暗号跨线程命中共识验证）、debug-agui 冒烟 7/7、HITL 集群全链路通过（详见下）**；**HITL 集群全链路**：发布 hitl-test 包（require_confirmation:true）→ ASK 触发 RUN_FINISHED(interrupt)（多工具同时挂起）→ agui_interrupt 落库 → **pod rollout restart 后 resume[] 跨进程恢复** → 工具真实执行 → 多轮 ASK 循环（publish_service 批准后真实创建 oaf-hitl-demo Deployment）→ hitl-agui-e2e.js 浏览器级 6/6（Debug Console AG-UI 模式确认卡片 Approve 闭环）；**关键契约实测**：resume[] 是 RunAgentInput **顶层字段**（非 forwardedProps），元素形状 {interruptId, status:"resolved"|"cancelled", payload:{approved:bool}}，SDK AguiMessageConverter.toMsgList(input,interrupts) 将 payload.approved 转 metadata agentscope_confirm_results（ConfirmResult）→ HarnessAgent 恢复；覆盖率校验要求 resume[] 覆盖全部挂起 interrupts；**实施期修复：/info agent 身份硬编码**（AguiThreadsController/AguiRunService 固定 release-agent → 改为 OafConfig.agentKey()/description() 动态派生，业务 agent 镜像共用时 /info 与路由校验一致；新增 prepareShouldResolveAgentIdFromOafConfig 单测，499→500 全绿）；mcp_ui 集群回归跳过（平台无携带 ui.tools 的 OAF 包，卡片链路本地已验证）（v8，2026-09-11：2.7 Debug Console 新增 AG-UI 模式（默认，A2A/Channel 保留至 Phase 3 删除）——RUN_*/TEXT_*/TOOL_CALL_*/CUSTOM 全词表 + token_usage 累计 + oaf.mcp_ui 卡片 + oaf.file_ready/tool_image 卡片 + resume[] 确认闭环 + R11 挂起恢复，无头浏览器冒烟 7/7；2.8 四个 e2e 适配完成本地全绿（chat-ui 3/4——T4 依赖集群 MCP，debug-markdown 15/15，file-support 15/15，debug-console 5/5 免适配）；**实施期修复：file_ready 合成范围扩至 create_oaf_zip**（原仅 present_file，生成包卡片依赖 LLM 概率性追加 present_file，OafPackageTools 结果 JSON 与 present_file 同构）；剩余：集群镜像部署 + HITL/mcp_ui 集群回归 + Phase 3 旧端点下线（提交点 4 待执行）（2026-09-10 Phase 2 记录：R7 spike 完成并回填 §5.1/§5.2 定稿——CopilotKit 1.65.0 实测 REST 传输契约/双路由//connect 204 语义/info 对象 map，新增 AguiChatController /connect 端点；R1 一并验证通过（Next16+React19 build 绿 + 浏览器运行）；assistant 页重构为 CopilotKit v2 + useInterrupt + oaf.* 事件监听，无头浏览器冒烟 7/7 零控制台错误；R11 兜底（pendingInterrupts 自渲染 + 手工 resume[]）已实现；剩余 2.7 Debug Console 切词表、2.8 e2e 改造）（2026-09-10 实施记录：① D1 修正——2.0.3 存在 breaking change `AgentRunner.stream → streamEvents`（返回类型 `Flux<Event>→Flux<AgentEvent>`，release notes 未列），HarnessAgentRunner+测试已适配，455→499 用例全绿；② Phase 0 冒烟（REST/SSE/A2A/真实 LLM）通过；③ Phase 1 九件套落地 + curl 验收通过（对话/多轮 R3/file_ready/租约排队 waiting/断连释放/错误路径），mcp_ui 与 HITL 跨进程 resume 因本地无 ASK 工具延至集群部署后回归（store 层真实库回环 AGUI_IT 已过）；④ D5 实测修正——agent_state store key 实为 `{userId}:{threadId}` 复合形态，threads 列表 id 提取 threadId 部分 + metadata.userId，archived 判定改按 uuid 形态）
> 范围：agent-framework（升级 AgentScope Java 2.0.0 → 2.0.3）+ frontend（CopilotKit）+ e2e
> 配套调研：agentscope-java v2.0.3 源码（agentscope-extensions-agui / agui-spring-boot-starter）、
> 上游 PR #2554（CopilotKit + AG-UI 全栈示例）、`docs/v2/zh/integration/protocol/agui.md`（官方协议文档）

---

## 1. 背景与目标

### 1.1 背景

当前对外 API 的 SSE 事件是**自定义词表**（两套并存）：

- camelCase 词表（`AgentEventSseSerializer`）：`AGENT_START` / `TEXT_BLOCK_DELTA` / `TOOL_CALL_START` / `permission_ask` …，服务 assistant 页面与 Debug Console；
- snake_case 词表（`AgentRuntimeService.forwardEvent`）：`agent_start` / `tool_result_text_delta` / `task_update` …，服务 A2A JSON-RPC 与 confirm-stream。

自定义词表的问题：前端解析层与 SDK 事件模型强耦合，SDK 升级即两端联动改造；协议私有，无法对接 AG-UI 生态（CopilotKit 等）。

agentscope-java **v2.0.3** 提供 `agentscope-extensions-agui` 模块：把 `AgentEvent` 流转换为 [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) 标准事件（`RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `CUSTOM` / interrupt outcome），并内置 HITL 中断恢复协议（`resume[]`）。

### 1.2 目标

1. **升级 AgentScope Java 2.0.0 → 2.0.3**，引入 `agentscope-extensions-agui`；
2. **对外 SSE 事件切换为 AG-UI 标准协议**：新增 `POST /agui/run`，替代 `POST /threads/{sid}/chat` 与 `/threads/{sid}/confirm-stream` 的职能（共存一个回归周期后下线旧端点）；
3. **前端引入 CopilotKit**：assistant 页面重构为 CopilotKit v2 + AG-UI transport；Debug Console 保留自研解析但切换 AG-UI 词表；
4. **服务保持无状态**：HITL interrupt 元数据落 MySQL（新表 `agui_interrupt`），任意副本可恢复任意 thread；
5. A2A JSON-RPC、MCP `/mcp`、文件、MCP Apps 代理端点零改动（例外：A2A HITL 恢复随旧链路退役，见 D8/R12——confirm resume 本就依赖平台私有 REST confirm-stream，非 JSON-RPC 协议内能力）。

### 1.3 非目标

- 不引入事件回放/全量事件持久化（会话恢复走 `agent_state`，对齐 stateless-single-stream-plan 的取舍）；
- 不做多 agent 进程内路由（平台层隔离：一个 OAF 包 = 一个 pod = 一个 agent）；
- 不做认证体系（保持现状，内部平台）；
- 不迁移 A2A snake_case 词表（JSON-RPC 独立协议，不在本次范围）；
- 不做旧会话数据迁移（旧 `gw-hash` 会话只读，见 D5）。

---

## 2. 决策记录

| # | 决策 | 结论 | 理由 |
|---|------|------|------|
| D1 | 升级目标版本 | **2.0.3** | 用户指定；2.0.1-2.0.3 release notes 均无 breaking API 变更（另见 §8 R6 传递依赖核查） |
| D2 | AG-UI 接入方式 | **直接使用 `agentscope-extensions-agui`，绕开 `agui-spring-boot-starter`** | ① starter 基于 Spring Boot 4.0.3 编译（`agentscope-agui-spring-boot-starter-2.0.3.pom`，Central 实测），与项目 Boot 3.3.5 存在二进制兼容风险；② 进程内状态避无可避：starter 的 `ThreadSessionManager`（内存 agent 缓存）无替换扩展点；且 **`AguiResumeCoordinator`（package-private，内存 Map 校验 resume）位于 extensions-agui 而非 starter**，由 `AguiRequestProcessor` 构造持有（AguiRequestProcessor.java:84）——controller 必须自研 parse/extract 逻辑，不得注入该 processor（见 Phase 1 要点 2 / 风险 R10）；③ `extensions-agui` 零 Spring 依赖（仅 agentscope-core，provided），事件转换内核完整可用 |
| D3 | HITL interrupt 持久化 | **新建 `agui_interrupt` 表** | 与旧 `confirm_context` 过渡期共存互不干扰；语义贴合 AG-UI（interruptId/replyId 维度）；旧端点删除后无需改表。建表方式对齐现有惯例：代码内 `CREATE TABLE IF NOT EXISTS`（对齐 `ConfirmContextStore.java:59`） |
| D4 | 前端方案 | **引入 CopilotKit**（`@copilotkit/react-core` ^1.65 + `@ag-ui/client` ^0.0.57） | 用户确认。参照 PR #2554 前端装配：`<CopilotKit runtimeUrl="/agent/release-agent/agui/run" agent={agentId} useSingleEndpoint={false}>` + `<CopilotChat>` + `useInterrupt` 渲染确认卡片 |
| D5 | 旧会话兼容 | **只读** | 切换后 `RuntimeContext.sessionId` 直接取 `threadId`（AG-UI adapter 语义，绕开 ChatUiChannel gateway 的 `gw-hash` 派生）。**实测修正（Phase 1）**：agent_state store key = `{userId}:{threadId}` 复合形态（SDK 内部约定）——threads 列表 id 返回拆解后的 threadId 部分 + metadata 携带 userId（续聊需同 userId 才命中同一 store key）；archived 判定按 threadId 是否 uuid（旧 gw-hash/用户命名 → 只读；展示手段依赖 CopilotKit 对 archived 的处理，R7 spike 确认；若直接隐藏则接受——`GET /threads` 仍可查）。不做数据迁移 |
| D6 | 旧端点下线节奏 | **共存一个回归周期后删** | Phase 1 后端新端点上线（旧端点不动）→ Phase 2 前端切换验收 → Phase 3 删除旧端点与 camelCase 词表。可随时回退 |
| D7 | 消息历史语义 | **服务端只取最新用户输入** | AG-UI 客户端每次 run 发全量本地 messages（CopilotKit 行为），而 HarnessAgent 会话记忆在 `agent_state`；若全量下发会与 SDK 记忆叠加造成重复。对齐上游 `AguiRequestProcessor.extractLatestUserMessage`（AguiRequestProcessor.java:307）的做法：取末尾第一条 role=user 消息 + `resume[]`，转换后传 agent |
| D8 | A2A HITL 恢复 | **随旧链路退役，缺位显式接受** | 代码核实：resumeWithConfirm/resumeWithConfirmStream 仅被 ConfirmController.java:55,86 调用（HarnessAgentRunner 纯透传 agent.stream，无 confirm 处理），Phase 3 删除即断。该能力本就依赖平台私有 REST confirm-stream，非 A2A JSON-RPC 协议内能力；HITL 场景由 assistant 页面（AG-UI 链路）承接。回归风险 R12；如未来需要，A2A 客户端改走 `/agui/run` resume[]（需客户端支持 AG-UI，另行评估） |

---

## 3. 现状盘点

| 能力 | 现状 | 处置 |
|------|------|------|
| 对话流 | `POST /threads/{sid}/chat`（SessionStreamController，camelCase 词表） | **保留至 Phase 3 删除**；新链路 `POST /agui/run` |
| HITL 恢复 | `POST /threads/{sid}/confirm-stream` + `/confirm`（ConfirmController，snake_case 词表，`confirm_context` 表 CAS） | **保留至 Phase 3 删除**；新链路 `resume[]` + `agui_interrupt` 表 |
| 并发互斥 | `TurnLeaseStore`（`turn_lease` 表，60s TTL，waiting 心跳帧 15s / 120s 超时） | **复用**，新端点同样抢租约 |
| 会话历史 | `agent_state` 表（SDK MysqlAgentStateStore 写入）；`GET /threads/{sid}/history`（StateDataParser 解析） | **复用**；`agui_interrupt` 落库时同表结构思路对齐 confirm_context |
| 会话列表 | `GET /threads`（`GROUP BY session_id`） | **复用**；key 语义新增纯 `threadId` 形态 |
| 文件 | `POST /files/upload` + `fileIds` → `UploadWorkspaceInjector` 注入 workspace + ImageBlock；`present_file` 合成 `file_ready` 帧 | **复用**；fileIds 改走 `forwardedProps.fileIds`；`file_ready` 改 CUSTOM 事件 |
| MCP Apps | `TOOL_CALL_START.ui`（`McpToolRegistrar.resolveUiRef` 注入）+ `McpResourceProxy` + `ui_context` | **复用**；ui 元数据改 CUSTOM 事件 `oaf.mcp_ui` |
| UI 上下文 | `POST /mcp/ui-context` → `ui_context` 表 → `UiContextInjectionHook`（PreCallEvent 注入；会话 key 经用户消息 metadata 传递） | **hook 逻辑复用、传递通路改造**：AG-UI 路径消息由内置 converter 构建、不携带该 metadata，且 `PreCallEvent` 不暴露 RuntimeContext（v2.0.3 源码核实）→ 注入载体改为 middleware `onSystemPrompt`（MiddlewareBase.java:158，签名含 ctx；inputMessages 注入 SYSTEM 被 core 禁止，HookEvent.java:66），controller 经 RuntimeContext 传 `uiContextSessionId`（Phase 1 要点 6） |
| 审计 | `ToolAuditStore` 异步批量 | **复用**（controller 事件旁路） |
| 中断 | 断连 → dispose 订阅 + 释放租约 | **复用** + `HarnessAgent.interrupt(RuntimeContext)`（HarnessAgent.java:598） |
| A2A | `POST /` JSON-RPC（`AgentRuntimeService.invokeStream`，snake_case） | 对话/任务端点**不动**；HITL 恢复随 D8 退役（唯一消费入口 ConfirmController 删除，R12） |
| MCP 平台端点 | `/mcp` streamableHttp（同进程） | **不动** |
| Debug Console | `static/debug/` 自研页（camelCase 词表） | Phase 2 切 AG-UI 词表（自研轻量解析，不引 CopilotKit） |

---

## 4. 目标架构

```
CopilotKit 前端（Next.js assistant 页）
  │  ① POST /agent/release-agent/agui/run   （RunAgentInput，SSE 响应）
  │  ② GET  /agent/release-agent/agui/run/info|threads|threads/{id}/messages|state
  ▼
nginx ingress（/agent/{name} 前缀，proxy-timeout 3600）
  ▼
AguiChatController（自建，Phase 1 新增）
  ├─ TurnLeaseStore.tryAcquire ── 抢租约，排队发 CUSTOM "oaf.waiting" 心跳（120s 超时）
  ├─ forwardedProps.fileIds → UploadWorkspaceInjector（现有）
  ├─ extractLatestUserMessage(input)（D7：只取最新用户输入）
  ├─ RuntimeContext：
  │    sessionId = threadId（D5：新会话 key）
  │    userId    = forwardedProps.userId ?? "webui"
  │    "agui.resume.interrupts" ← AguiInterruptStore.load(threadId)（MySQL，跨副本 ✅）
  │    "uiContextSessionId"    ← controller 注入（注入载体改 middleware onSystemPrompt，见要点 6）
  ├─ AguiAgentAdapter.run(input, ctx)   ← extensions-agui 官方无状态 adapter
  │    ├─ 反射调用 HarnessAgent.streamEvents(List<Msg>, RuntimeContext)
  │    ├─ 内置 converter：RUN_* / TEXT_MESSAGE_* / REASONING_* / TOOL_CALL_*
  │    │                  / TOOL_CALL_RESULT / token_usage(CUSTOM) / subagent.*(CUSTOM)
  │    ├─ 自定义 enricher：TOOL_CALL_START 后发 CUSTOM "oaf.mcp_ui"（resolveUiRef）
  │    ├─ 自定义 converter：present_file 结果 → CUSTOM "oaf.file_ready"
  │    │                    工具图片 DataBlock → CUSTOM "oaf.tool_image"
  │    ├─ 自定义 enricher：RUN_FINISHED(interrupts) → AguiInterruptStore.persist + 释放租约
  │    └─ HITL：RequireUserConfirmEvent → RUN_FINISHED.outcome.interrupts[]（官方协议）
  └─ AguiEventEncoder → SSE（data: {json}\n\n）
       断连：sink.onCancel → agent.interrupt(ctx) + 释放租约 + toolInjection 清理

HITL 恢复（无状态闭环）：
  RUN_FINISHED(interrupt) ──persist──▶ agui_interrupt 表
  前端 useInterrupt 卡片 → resolve({approved}) 
  → CopilotKit 同 threadId 发起新 run，请求体带 resume[]（interruptId + status + payload）
  → controller 从 agui_interrupt 加载元数据 → **覆盖率校验（resume[] vs open interrupts 交集，不足则 HTTP 400）** → RuntimeContext 注入
  → CAS consumed 0→1（失败 409）
  → adapter 构造 Msg(METADATA_CONFIRM_RESULTS)（AguiMessageConverter.toConfirmResultMsg）
  → HarnessAgent 从 agent_state(threadId) 恢复挂起的 tool_use → 执行/拒绝
```

---

## 5. 接口契约

### 5.1 `POST /agui/run`（主端点）

路由（R7 spike 定稿，1.65.0 源码实测）：同时注册 `POST /agui/run` 与 `POST /agui/run/agent/{agentId}/run`（等价，agentId 固定校验 `release-agent`）。D4 `runtimeUrl=/agent/release-agent/agui/run` 维持：REST 传输客户端在 runtimeUrl 上拼 `/info`、`/threads`、`/agent/{id}/run`、`/agent/{id}/stop/{tid}`、`/agent/{id}/connect`，经 Next rewrite 后与本节路径完全吻合。**新增 connect 端点**：客户端挂载/重连时 POST `{runtimeUrl}/agent/{id}/connect` 探测在跑流，204=无可重连（本平台断连即 interrupt，恒 204）；未实现时客户端报 agent_connect_failed 噪音错误。排队心跳/无前置 RUN_STARTED 的 RUN_ERROR 帧实测被客户端正常解析（0 控制台错误），该降级预案解除。

> ~~排队心跳降级预案~~（已解除）：`oaf.waiting` 与无前置 `RUN_STARTED` 的 `RUN_ERROR` 帧经无头浏览器实测被客户端正常解析。

请求体：AG-UI 标准 `RunAgentInput`（v2.0.3 `io.agentscope.core.agui.model.RunAgentInput`）+ 平台扩展：

```jsonc
{
  "threadId": "uuid",              // 必填，会话标识（= RuntimeContext.sessionId）
  "runId": "uuid",                 // 必填，本次 run 标识
  "messages": [                    // CopilotKit 发全量本地历史；服务端只取最新 user 消息（D7）
    { "id": "...", "role": "user", "content": "..." }
  ],
  "tools": [],                     // 前端工具（暂不使用，ToolMergeMode.MERGE_FRONTEND_PRIORITY）
  "state": {},                     // AG-UI 共享状态（暂不使用）
  "forwardedProps": {              // 平台扩展（非可信身份来源，仅传输用途）
    "userId": "webui",             // 可选，缺省 "webui"
    "fileIds": ["f-xxx"]           // 可选，/files/upload 返回的 fileId → workspace 注入
  },
  "resume": [                      // HITL 恢复时必填（官方协议）
    {
      "interruptId": "reply-1:call-1",   // RUN_FINISHED.interrupts[].id
      "status": "resolved",              // resolved | cancelled
      "payload": { "approved": true }    // approved=true 放行；false/缺失=拒绝
    }
  ]
}
```

响应：`text/event-stream`，每帧 `data: <AguiEvent JSON>`（`AguiEventEncoder` 编码）。事件词表：

| 事件 | 来源 | 说明 |
|------|------|------|
| `RUN_STARTED` / `RUN_FINISHED` | 内置（AgentStart/EndEvent） | HITL 时 FINISHED 带 `outcome.interrupts[]` |
| `RUN_ERROR` | 内置（异常路径，带 timestamp） | 与 FINISHED 互斥终态（`emitRunFinishedAfterError=false`） |
| `TEXT_MESSAGE_START/CONTENT/END` | 内置 | 增量文本 |
| `REASONING_MESSAGE_START/CONTENT/END` | 内置（`enableReasoning=true`） | 思考流 |
| `TOOL_CALL_START/ARGS/END` | 内置 | 工具调用与参数增量 |
| `TOOL_CALL_RESULT` | 内置 | 工具结果（一次性全量，非增量） |
| `CUSTOM` name=`token_usage` | 内置（`emitTokenUsage=true`） | `{delta, cumulative}` |
| `CUSTOM` name=`oaf.mcp_ui` | 自定义 enricher | `{toolCallId, resourceUri, server}`（MCP Apps 卡片） |
| `CUSTOM` name=`oaf.file_ready` | 自定义 converter | `{file_id, file_name, mime_type, size, download_url}`（present_file） |
| `CUSTOM` name=`oaf.tool_image` | 自定义 converter | `{toolCallId, media_type, data|url}`（工具结果图片） |
| `CUSTOM` name=`oaf.waiting` | controller | `{queued:true}`（租约排队心跳，15s） |
| `CUSTOM` name=`subagent.*` | 内置（默认归并） | 子 agent 事件（`emitSubagentEventsAsNative=false`） |

错误帧：`RUN_ERROR`（异常）；SSE 层错误（如租约超时）→ `RUN_ERROR` + 立即关闭流。

resume 约束：平台仅产生 `permission_confirm` 类型 interrupt（无前端工具、无外部执行工具），`resume[]` 仅需处理该类型。**`resume[]` 必须覆盖 `agui_interrupt` 中该 thread 的全部 open interrupts**——绕开 `AguiResumeCoordinator` 后，其"覆盖率校验"职责由 controller 自担（部分覆盖 → HTTP 400），校验通过才允许整 thread CAS 消费（§5.3）；否则 HarnessAgent 恢复时未被覆盖的挂起 tool_use 拿不到 ConfirmResult，行为未定义。

### 5.2 CopilotKit 配套 REST（对齐 PR #2554 路由，数据源全部 MySQL 化）

> **契约定稿（R7 spike 实测，CopilotKit 1.65.0 源码+浏览器验证）**：客户端实际调用——GET /info（**agents 为以 id 为键的对象 map**，含 description/capabilities）、GET /threads?agentId=&limit=[&includeArchived]（响应 {threads:[{id,updatedAt,…}],nextCursor}）、POST /agent/{id}/run、POST /agent/{id}/stop/{tid}、POST /agent/{id}/connect（204=无在跑流）、DELETE /threads/{id}（body 含 agentId）；PATCH rename/unarchive 与 POST archive 未实现（405，UI 仅暴露删除）。**客户端不拉取服务端 messages**（会话内存态）——/threads/{id}/messages 为平台自有扩展，仅作 R11 刷新兜底数据源。`oaf.*` CUSTOM 事件经 agent.subscribe(onCustomEvent) 消费（MetaEvent 形态）。

| 端点 | 数据源 | 说明 |
|------|--------|------|
| `GET /agui/run/info` | 静态 | agents 列表（单 agent `release-agent`）、capabilities（threads/hitl=true）、transport=sse |
| `GET /agui/run/threads?limit=&cursor=` | `agent_state`（GROUP BY session_id） | 返回 CopilotKit `ThreadsResponse` 格式；含旧 `gw-hash` 会话——只读标注手段（`archived` 或自定义字段）待 spike 确认 CopilotKit 展示行为后定（R7） |
| `POST /agui/run/threads` | **落点未定** | `agent_state` 由 SDK 写入，平台直接插行不合适；先 spike 确认 CopilotKit 是否必须调用——若 threadId 由前端生成、首 run 隐式建会话，则此端点可为 200 空操作 |
| `DELETE /agui/run/threads/{id}` | **新实现** | 现有 ThreadController **无删除端点**（此前的"现有 SQL 删除语义"为溯源错误），需新写：DELETE `agent_state` **+ `agent_fs`**（by session_id，对齐 SessionCleanupService 清理范围，否则 workspace 文件残留）+ 同 thread 的 `agui_interrupt` / `turn_lease` 记录 |
| `GET /agui/run/threads/{id}/messages` | `agent_state` → StateDataParser → AguiMessage[] | 切换线程恢复消息列表；含挂起 interrupt 时附 `pendingConfirm` 元数据 |
| `GET /agui/run/threads/{id}/state` | 空对象 `{}` | 共享状态暂未使用 |
| `POST /agui/run/agent/{agentId}/stop/{threadId}` | — | `HarnessAgent.interrupt(RuntimeContext)`；agentId 固定校验 `release-agent` |

> 现有 `GET /threads`、`/threads/{sid}/history`、`/threads/{sid}/llm-calls` 保留不动（平台管理面继续使用；例外：history 的 pendingConfirm 附加字段随 confirm_context 退役而消失，见 3.2/D8）。

### 5.3 `agui_interrupt` 表（Phase 1 建，代码内 `CREATE TABLE IF NOT EXISTS`）

```sql
CREATE TABLE IF NOT EXISTS agui_interrupt (
  thread_id       VARCHAR(255) NOT NULL COMMENT 'AG-UI threadId',
  interrupts_json MEDIUMTEXT   NOT NULL COMMENT '[{interruptId,toolCallId,toolName,toolInput,toolContent,replyId}]',
  run_id          VARCHAR(128)          COMMENT '产生 interrupt 的 runId',
  consumed        TINYINT      NOT NULL DEFAULT 0 COMMENT 'CAS 消费标志 0→1',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AG-UI HITL interrupt 元数据（跨副本恢复）';
```

操作语义（对齐 `ConfirmContextStore` 模式）：
- `persist(threadId, interrupts)`：UPSERT 覆盖（新 interrupt 到来，consumed 归零）；TTL 30min 清理（挂 `SessionCleanupService` cron）
- `load(threadId)`：`consumed=0` 且未过期 → 反序列化 `Map<interruptId, AguiEvent.Interrupt>`（注入 RuntimeContext `agui.resume.interrupts`）；重建对象必须完整（`id`/`reason` 非空强制，`metadata` 携带 toolName/toolInput/toolContent，缺失处理见 R10）
- `validate(threadId, resume[])`：resume[] 中的 interruptId 必须覆盖 load 返回的全部 open interrupts，部分覆盖返回 400（§5.1 resume 约束）
- `consume(threadId)`：CAS `consumed 0→1`，失败返回 409 语义（前端提示已处理）

---

## 6. 阶段计划

### Phase 0：升级 v2.0.3（0.5 天，独立提交可回滚）

| # | 动作 | 产物 |
|---|------|------|
| 0.1 | `agent-framework/pom.xml:22` 版本 2.0.0 → 2.0.3；新增依赖 `agentscope-extensions-agui`（无版本号写 `${agentscope.version}`） | pom.xml |
| 0.2 | `mvn dependency:tree -Dincludes=io.opentelemetry,io.modelcontextprotocol` 核对传递依赖漂移；必要时调整 `dependencyManagement`（现 pin OTel 1.61.0，见 pom.xml:24-27 注释） | 依赖树记录 |
| 0.3 | `cd agent-framework && mvn clean test`（61 测试类 / 456 用例） | 全绿 |
| 0.4 | `make run-dev` 冒烟：REST / `/threads/{sid}/chat` SSE / A2A / `/mcp` 全链路回归 | 冒烟记录 |

验收：全部用例绿 + 冒烟通过；**提交点 1**。

### Phase 1：后端 AG-UI 端点（2-3 天）

新增（包路径 `io.agentmanager.framework`，目录对齐现有分层）：

| 文件 | 职责 |
|------|------|
| `controller/AguiChatController.java` | `POST /agui/run` **与** `POST /agui/run/agent/{agentId}/run`（§5.1 双路由等价）、`POST /agui/run/agent/{agentId}/stop/{threadId}`；SSE 装配、断连清理 |
| `controller/AguiThreadsController.java` | §5.2 配套 REST |
| `service/AguiRunService.java` | 租约/waiting/fileIds/extractLatestUserMessage/RuntimeContext 组装（含 uiContextSessionId）/encoder/断连 interrupt/**工具事件旁路审计（ToolAuditStore）**（对齐 SessionStreamController 的 Flux.create 结构与 handleEvent 职责） |
| `service/AguiInterruptStore.java` | §5.3 表 CRUD + CAS + TTL 清理注册 |
| `agui/OafAguiEventEnrichers.java` | `oaf.mcp_ui` / interrupt 持久化 enricher |
| `agui/OafAguiEventConverters.java` | `oaf.file_ready` / `oaf.tool_image` converter |
| `agui/OafAguiMiddleware.java` | `MiddlewareBase.onSystemPrompt`（签名含 ctx）：读 RuntimeContext `uiContextSessionId` 向 system prompt 追加 UiContext 注入（要点 6）；同载体承接 R2 的 resume 防循环指引追加。无 key 时 no-op（共享 bean 旧链路不受影响；hook 与 middleware 注入条件互斥——hook 依赖消息 metadata、middleware 依赖 RuntimeContext key，共存期无双重注入） |
| `config/AguiAdapterConfiguration.java` | `AguiAgentAdapter` bean：复用现有 `HarnessAgent` bean（AgentScopeConfig:232），`AguiAdapterConfig.builder().enableReasoning(true).emitTokenUsage(true).runTimeout(配置化，默认值见要点 8)` + 注册自定义 converter/enricher |
| `model/AguiRunProps.java` | forwardedProps 解析（userId/fileIds），含边界校验 |

改造：
- `application.yml`：新增 `agui:` 配置段（agentId、租约参数复用现有）
- `SessionCleanupService`：注册 `agui_interrupt` 过期清理
- `AgentScopeConfig`：HarnessAgent bean 构建处注册 `OafAguiMiddleware`（onSystemPrompt 无 key 时 no-op，旧链路共用 bean 不受影响）

实现要点（按 D2/D5/D7）：
1. **不引 starter 依赖**，仅 `agentscope-extensions-agui`；不注册 `AguiAgentRegistry`（单 agent 直用 bean）；
2. `extractLatestUserMessage`：取 messages 末尾第一条 `role=user`；**resume run（请求携带 resume[]）一律不下发用户文本**——无论 messages 是否仍含原 user 消息（CopilotKit 发全量本地历史，触发 HITL 的那条 user 消息仍在其中，再下发即重复入库，R3 变体），仅携带 resume 转换结果。**照抄上游思路自研，禁止注入 `AguiRequestProcessor`**——其构造即内部 `new AguiResumeCoordinator()`（进程内 Map 校验 resume，跨副本/重启即失效），与 `agui_interrupt` 无状态目标冲突；
3. resume 元数据注入：`RuntimeContext.builder()...put("agui.resume.interrupts", map)`（常量 `AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY`）；
4. interrupt 持久化时机：enricher 捕获 `RUN_FINISHED` 携带 interrupts → `AguiInterruptStore.persist` + `TurnLeaseStore.release`（流随后正常关闭）；
5. 多模态：`forwardedProps.fileIds` 走现有 `UploadWorkspaceInjector`；messages 中的图片内容（CopilotKit attachments）转 `ImageBlock`（InputContent→ContentBlock 映射由内置 `AguiMessageConverter` 完成，验证 Base64 路径）；
6. **UiContext 注入载体改造**：`UiContextInjectionHook` 现从用户消息 metadata 读会话 key，AG-UI 路径消息由内置 converter 构建、不携带 metadata，通路断裂；且 `PreCallEvent` 不暴露 RuntimeContext（v2.0.3 源码核实）——注入逻辑迁至 `MiddlewareBase.onSystemPrompt(agent, ctx, prompt)`（签名含 ctx；装配点 ReActAgent.applySystemPromptMiddlewares，有 override 才执行，ReActAgent.java:783-810；HarnessAgent wraps ReActAgent delegate（HarnessAgent.java:171）→ 对本项目 agent 生效），controller 把 `uiContextSessionId` 写入 RuntimeContext，middleware 读取后向 system prompt 追加注入。**不向 input messages 注入 SYSTEM**：HookEvent javadoc 官方语义禁止；显式拦截在 **hook 修改 PreCallEvent.inputMessages 路径**（AgentBase.java:784-794，instanceof ReActAgent 分支抛 IllegalStateException，注释并注明无记忆 agent 的调用参数可合法含 SYSTEM）——直接调用参数传 SYSTEM 是否被拒未逐一验证，converter 的 `"system"` 映射仅说明词表支持，统一走 onSystemPrompt 规避整个问题域（原 hook 在 Phase 3 随旧链路退役）；
7. **reactive 流内 JDBC 隔离**：enricher 的 `enrich()` 为同步函数（返回 List），JDBC 直接调用会阻塞 emit 线程——persist 不得同步出现在 `enrich()` 内，须包成异步执行：`Mono.fromCallable(...).subscribeOn(boundedElastic).subscribe()` fire-and-forget，或对齐现有 ToolAuditStore 的入队 + 后台批量刷写模式（单独的 `subscribeOn` 作用于流水线订阅线程，管不到 enrich() 内部的同步调用）；
8. **runTimeout 为新引入的 run 级超时**（现链路无此限制）：发布/长工具场景可能长跑，取值配置化且默认不低于现有 ingress 侧行为（建议 ≥30min 或禁用），防误杀长任务。

单测（对齐现有 JUnit 风格）：
- `AguiRunServiceTest`：extractLatest 边界（空/多轮/带图片）、fileIds 注入、租约超时 error 帧、断连释放
- `AguiInterruptStoreTest`：UPSERT/CAS/TTL（复用 SandboxAwareMysqlAgentStateStoreTest 的测试库模式）
- converter/enricher 逐个：固定 AgentEvent 输入 → AguiEvent JSON 断言
- `AguiChatControllerTest`：WebTestClient SSE 流断言（对话/工具/HITL 全场景 mock agent）

验收（curl 全场景）：普通对话、多轮、工具调用、HITL 中断→跨进程重启后 resume 恢复（无状态铁证）、file_ready、mcp_ui、断连 interrupt、租约并发排队。**提交点 2**。

### Phase 2：前端 CopilotKit（3-5 天）

| # | 动作 |
|---|------|
| 2.1 | `frontend/package.json` 新增 `@copilotkit/react-core@^1.65`、`@ag-ui/client@^0.0.57`（锁版本）；**先完成 R7 spike**（§8）确认契约后铺开；`npm run lint && npm run build` 过 CI |
| 2.2 | assistant 页重构：`<CopilotKit runtimeUrl="/agent/release-agent/agui/run" agent="release-agent" useSingleEndpoint={false}>` + `<CopilotChat attachments>`；react-markdown 自渲染管线评估 CopilotChat 的 markdown 配置后决定保留或替换 |
| 2.3 | HITL 确认卡片：`useInterrupt({agentId, render})`（参照 PR #2554 useHitlInterrupt.tsx），resolve({approved}) 驱动 resume[] |
| 2.4 | 自定义渲染：监听 CUSTOM 事件实现 `oaf.file_ready` 下载卡片、`oaf.tool_image` 内嵌图片、`oaf.mcp_ui` → MCP Apps iframe host（迁移现有 `mcp-app-host.js` postMessage 协议为 React 组件，spike 优先，风险 R8） |
| 2.5 | 文件上传：保留 `/files/upload` + forwardedProps.fileIds 链路；CopilotChat attachments 直传图片走 AG-UI 多模态 |
| 2.6 | threads 列表：对接 `GET /agui/run/threads`（旧会话展示方式随 R7 spike 结论：标注只读或接受隐藏）；新建会话默认入口 |
| 2.7 | Debug Console（static/debug/）：自研解析层切 AG-UI 词表（RUN_*/TEXT_MESSAGE_*/TOOL_CALL_*/CUSTOM），保留 MCP Apps / token 统计 / 确认卡片功能；确认交互改 resume[] 模式（刷新后挂起确认的恢复：pendingConfirm 改读新 messages 端点元数据，R11 同源方案） |
| 2.8 | e2e 改造：chat-ui-e2e.js / debug-console-e2e.js / debug-markdown-e2e.js / file-support-ui-e2e.js 适配新交互 |

验收：`cd frontend && npm run build` 绿 + e2e 全绿 + 旧会话展示符合 R7 spike 结论（只读可见或隐藏，二选一记录）。**提交点 3**。

### Phase 3：下线与清理（0.5-1 天）

| # | 动作 |
|---|------|
| 3.1 | 删除 `SessionStreamController`、`ConfirmController`、`AgentEventSseSerializer`（camelCase 词表）、`StreamController`（`GET /chat/stream`，仅测试使用）；相关单测同步删除/迁移 |
| 3.2 | `AgentRuntimeService`：保留 A2A 路径（invokeStream/forwardEvent snake_case）；删除仅服务旧 confirm-stream 的 resumeWithConfirmStream 分支。**A2A HITL 恢复随 D8 退役**（已核实：resumeWithConfirm/resumeWithConfirmStream 仅 ConfirmController.java:55,86 调用，无其他消费方；`putConfirmContext` 仍由 A2A forwardEvent 写入）——删除后 A2A 恢复缺位（回归 R12），`ConfirmContextStore`/`confirm_context` 表一并退役（表归档或 DROP；ThreadController.findPending 展示同步下线） |
| 3.3 | e2e 清理 + `agent-framework/AGENTS.md`、`docs/api.md`、本文件状态更新（"已实施"附录） |
| 3.4 | 镜像构建 → kind 导入 → rollout restart（docs/deployment.md 流程） |

验收：全量测试绿、e2e 绿、平台发布/对话/MCP Apps 全流程回归。**提交点 4**。

---

## 7. 测试策略

| 层 | 覆盖 | 方式 |
|----|------|------|
| 单元 | extractLatestUserMessage 边界、AguiInterruptStore CRUD/CAS/TTL、4 个自定义 converter/enricher 的 AgentEvent→AguiEvent 映射、AguiRunProps 校验 | JUnit（对齐现有 456 用例风格，目标新增 ~40 用例） |
| 集成 | `/agui/run` SSE 全场景：对话/工具/HITL（含**重启进程后 resume**——无状态验收核心）/断连/租约排队/错误路径 | WebTestClient + mock HarnessAgent |
| e2e | 浏览器级：CopilotKit 页面对话、确认卡片、MCP Apps 卡片、文件上传/下载、threads 切换、旧会话只读 | 现有 e2e 脚本改造 |
| 回归 | 升级后现有全部端点（REST/A2A/MCP/文件）| Phase 0 冒烟 + CI（`mvn test` / `npm run lint && build`） |

---

## 8. 风险清单

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | ~~CopilotKit ^1.65 与 Next.js 16 SSR 兼容性~~ | 已解除 | Phase 2 实测：1.65.0 + Next16/React19 build 绿、无头浏览器运行正常（组件 "use client"，CSS 经 public 静态加载绕过 Tailwind3 postcss 管线） |
| R2 | resume 恢复时 adapter 生成的 ConfirmResult 消息仅含 `approved/denied` 文本，缺现有中文执行指引（buildResumeMsg 的防循环指令） | 中 | Phase 1 验收用例专测 resume 后行为（执行/拒绝是否正常、是否循环重调）；必要时复用要点 6 的 middleware `onSystemPrompt`（含 ctx）追加防循环指引——**不**向 `input.messages` 追加 system 角色 AG-UI 消息（converter 虽映射 `"system"->MsgRole.SYSTEM`，AguiMessageConverter.java:261，但 HookEvent javadoc 官方语义禁止 inputMessages 注入 SYSTEM；显式拦截在 hook 修改路径（AgentBase.java:784-794），直接入参行为未验证——统一走 onSystemPrompt 规避） |
| R3 | CopilotKit 发全量 messages，服务端若漏做 extractLatest 会导致 SDK 记忆重复 | 高 | D7 为 controller 强制逻辑 + 单测边界覆盖；集成测试断言 agent_state 无重复消息 |
| R4 | 自定义词表→AG-UI 映射遗漏（前端某事件未处理静默丢显示） | 中 | §7 集成 + e2e 全场景矩阵；CUSTOM 事件命名统一 `oaf.` 前缀便于排查 |
| R5 | 会话 key 语义变化：新 key=纯 threadId，旧 `gw-hash` 会话不可续聊 | 已接受（D5） | 列表展示随 R7 结论（标注只读或隐藏）；history 正常可查 |
| R6 | v2.0.3 传递依赖漂移（OTel 混版 NoClassDefFoundError / MCP SDK 行为变化） | 中 | Phase 0.2 dependency:tree 强制核查 + 0.3 全量用例；pom.xml:24-27 注释即上次同类问题的处理先例 |
| R7 | ~~CopilotKit 契约未验证~~ | 已关闭 | **Spike 完成（1.65.0 源码 + 无头浏览器实测）**，结论回填 §5.1/§5.2：REST 传输 URL 拼接规则、/info 对象 map、threads 契约、/connect 204、不拉取服务端 messages、`oaf.*` 经 onCustomEvent 消费。锁死精确版本 1.65.0（@ag-ui/client 0.0.57 传递锁定） |
| R8 | MCP Apps iframe（postMessage JSON-RPC）嵌入 CopilotChat 消息流的方式未知 | 中 | Phase 2.4 先 spike：CopilotKit 自定义消息渲染能力验证；不行则降级为消息流外置面板（现 debug 页右侧模式） |
| R9 | `TOOL_CALL_RESULT` 一次性全量，长结果（present_file 大文件清单等）首帧延迟变大 | 低 | 现有 present_file 本就等 END 合成，语义无退化；观察后必要时加 CUSTOM 增量 |
| R10 | resume 时从 `agui_interrupt` 表 JSON 重建 `AguiEvent.Interrupt`（record 强制 id/reason 非空，metadata 需含 toolName/toolInput/toolContent）；字段缺失/类型不符时 `AguiAgentAdapter.resumeInterrupts` 仅做 instanceof 校验并**静默丢弃**（AguiAgentAdapter.java:319）→ ConfirmResult 对不上挂起 tool_use，HITL 恢复退化为重复询问 | 中 | `AguiInterruptStore.load` 反序列化后逐字段完整性校验，缺失 fail-fast 报 RUN_ERROR；单测覆盖"表数据缺字段"边界（对齐 confirm_context content=null 前科回归模式） |
| R11 | 刷新/重开页面后挂起 HITL 的恢复渲染：客户端确认状态为会话内存态，刷新即失（spike 证实客户端不拉服务端 messages） | 中 | **已实现兜底**：assistant 页于 threadId 变化时拉取 `/threads/{id}/messages` 的 pendingInterrupts → 自渲染 ManualConfirmCard → 手工构造 resume[] 直接 POST /agui/run（绕过 useInterrupt，服务端覆盖率/CAS 校验兜底）；集群 HITL 回归时验证全链路 |
| R12 | **A2A HITL 恢复缺位**：ConfirmController（唯一消费入口）删除后，A2A forwardEvent 写入的 confirm_context 不再可消费 | 中 | D8 显式接受（confirm resume 本属平台私有 REST 扩展，非 JSON-RPC 协议内能力）；Phase 3 前确认无在用 A2A HITL 调用方（平台侧检索调用日志）；未来如需，A2A 客户端改走 `/agui/run` resume[]（需支持 AG-UI，另行评估） |

---

## 9. 回滚预案

- **Phase 0**：独立 commit，回滚 = revert pom 提交；
- **Phase 1**：新旧端点共存，`/agui/run` 问题不影响存量链路；回滚 = 前端切回旧端点（Phase 3 前随时可行）；
- **Phase 2**：前端回滚 = revert frontend 提交（旧页面代码共存至 Phase 3 删除）；
- **Phase 3**：删除旧端点前打 tag，回滚 = 重新部署 tag 镜像 + revert 前端。
