# HITL 恢复流工具调用摘要兜底设计

- 日期：2026-09-25
- 状态：**已实施**
- 影响链路：`POST /threads/{sessionId}/confirm-stream` 的工具摘要事件
- 相关模块：`TurnToolSummaryTracker`、`ToolSummaryGenerator`、`ConfirmController`
- 相关文档：[api-frontend-sse.md](api-frontend-sse.md)、[hitl-permission-plan.md](hitl-permission-plan.md)、[e2e-ci-plan.md](e2e-ci-plan.md)

---

## 1. 背景

2026-09-23 的工具摘要功能把工具参数与结果合成两类人可读 SSE 帧：

| 帧 | 正常对话触发点 | 内容 |
|---|---|---|
| `tool_call_summary` | `TOOL_CALL_END` | 工具调用标题，如「创建 output/a.js 2行」「执行 npm test」 |
| `tool_result_preview` | `TOOL_RESULT_END` | 工具输出首行或失败终态 |

实现依赖 AgentScope 的原生事件：真实工具名在 `ToolCallStartEvent` 上登记，参数经
`ToolCallDeltaEvent` 累积，直到 `ToolCallEndEvent` 才能合成调用摘要。

### 1.1 实测问题

HITL 的执行被拆成两段：

1. 原始对话段：LLM 发起 `TOOL_CALL_*`，权限系统拦截后发 `permission_ask`，租约释放；
2. 恢复执行段：用户批准后调用 `confirm-stream`，SDK 从 checkpoint 恢复并直接执行已批准工具。

实测恢复段事件序列为：

```text
AGENT_START
USER_CONFIRM_RESULT
TOOL_RESULT_START
TOOL_RESULT_TEXT_DELTA
TOOL_RESULT_END
tool_result_preview
MODEL_CALL_START
...
AGENT_END
```

恢复段**不会重放**原始 `TOOL_CALL_START / DELTA / END`。因此：

- 结果预览可以生成；
- 调用摘要没有触发点，恢复后的工具行缺少可读标题；
- 事件经 Redis Streams 持久化，刷新与续传回放同样缺少该帧。

此前单测手工构造了完整 `ToolCallStart → Delta → End → ResultEnd` 序列，未能暴露该真实事件形态；
2026-09-25 的 H2 E2E 补齐断言后定位。

---

## 2. 目标与非目标

### 目标

1. HITL 批准/拒绝后的恢复流同样输出 `tool_call_summary` 与 `tool_result_preview`；
2. 摘要帧进入 `SessionEventBus.emitSynthetic`，实时流、刷新回放、多副本续传行为一致；
3. 正常对话链路不重复发调用摘要；
4. 同一 `toolCallId` 的重复 `ToolResultEnd` 不重复补发；
5. 不伪造或追加原生 `TOOL_CALL_*` 事件，保持 AgentScope 事件协议不变。

### 非目标

1. 不恢复原始工具参数，因此不承诺生成「提交 APP-xxxx」这类参数级摘要；
2. 不修改 AgentScope SDK 的 checkpoint 恢复事件；
3. 不改变 HITL 权限、租约、确认上下文与 history 权威源语义；
4. 不为旧历史数据补写缺失事件（修复只影响新发生的恢复段）。

---

## 3. 方案比较

### 方案 A：SDK 恢复时重放 `TOOL_CALL_*`

让恢复流重新发出调用开始、参数增量和参数结束事件。

- 优点：摘要器无需特殊逻辑，前端也能重放完整工具参数。
- 缺点：
  - 需要修改或包装 SDK 恢复链路；
  - 原生事件会被当成新事件广播，容易造成前端重复创建工具行；
  - 事件持久化后回放语义复杂，需区分「原始段事件」与「恢复段重放事件」。

**结论：侵入面过大，不采用。**

### 方案 B：`ConfirmController` 从 `confirm_context` 重建参数摘要

控制器读取待确认工具的 `ToolUseBlock.input`，自行生成参数级摘要。

- 优点：摘要可以包含 application_id 等参数。
- 缺点：
  - `confirm_context` 由 `AgentRuntimeService.resumeWithConfirmEvents` 消费，控制器再读会形成双入口；
  - AgentState 与 confirm_context 的权威关系被绕开；
  - 摘要拼装逻辑从 tracker 泄漏到控制器，破坏两条执行管道共用实现的收敛目标。

**结论：耦合较高，不采用。**

### 方案 C：`RESULT_END` 时按 `toolCallId` 兜底补发调用摘要（采用）

Tracker 维护「已产出调用摘要」的 `toolCallId` 集合。若 `ToolResultEndEvent` 到达时该 id
从未产出过调用摘要，则补发一帧无参数摘要，再按原逻辑发结果预览。

- 优点：
  - 修复点收敛在已有摘要状态机；
  - 不改变原生事件；
  - 天然按 `toolCallId` 幂等；
  - 正常链路与恢复链路继续共用同一实现。
- 代价：
  - 无法拿到原始参数，摘要只能是「执行 {工具名}」。

---

## 4. 详细设计

### 4.1 状态与判定

`TurnToolSummaryTracker` 新增 turn 级集合：

```java
private final Set<String> summarizedCalls = ConcurrentHashMap.newKeySet();
```

规则：

1. `onToolCallEnd` 成功合成调用摘要后，把 `toolCallId` 加入集合；
2. `onToolResultEnd` 中使用 `summarizedCalls.add(toolCallId)`：
   - 返回 `true`：本 turn 尚无调用摘要，补发兜底摘要；
   - 返回 `false`：正常链路已发过或恢复段已补过，不再发；
3. `clear()` 同时清空该集合，保持 tracker 与 turn 同生命周期。

`Set.add` 的原子性同时完成「判断是否缺失」与「标记已补发」，避免并发事件回调下双发。

### 4.2 摘要文案

新增 `ToolSummaryGenerator.resumedCallSummary(toolName)`：

```text
执行 {friendlyName}
```

示例：

| 事件工具名 | 兜底摘要 |
|---|---|
| `submit_application` | `执行 submit_application` |
| `mcp__oaf__publish_service` | `执行 publish_service` |
| 空/null | `调用工具` |

MCP 展示名沿用 `friendlyName` 的末段规则；文案仍经过单行压缩与长度截断。

### 4.3 事件顺序

恢复段每个工具的结果事件处理顺序为：

```text
TOOL_RESULT_END            （原生事件，先广播）
tool_call_summary          （兜底合成，本修复新增）
tool_result_preview        （结果合成，原有逻辑）
```

如果结果为空且无法生成预览，则只补发 `tool_call_summary`，保证工具行仍有标题。

### 4.4 持久化与回放

兜底摘要仍走：

```text
TurnToolSummaryTracker.emit
  → SessionEventBus.emitSynthetic
  → Redis Streams append
  → 当前 SSE 订阅 / GET /subscribe 回放
```

因此与既有 `file_ready`、正常工具摘要一致：

- 断连后续传能拿到；
- 页面刷新回放能拿到；
- 多副本接管后能拿到；
- 前端按 `toolCallId` 认领工具行即可。

### 4.5 与正常链路的关系

正常链路保持：

```text
TOOL_CALL_START
TOOL_CALL_DELTA*
tool_call_summary
TOOL_RESULT_START
TOOL_RESULT_TEXT_DELTA*
TOOL_RESULT_END
tool_result_preview
```

由于 `TOOL_CALL_END` 已先把 `toolCallId` 标记为「已摘要」，后续 `TOOL_RESULT_END`
不会再触发兜底，避免重复。

### 4.6 Debug UI 新回复认领既有工具调用

`confirm-stream` 在 Debug 页中按新执行段创建新的回复容器，但 SDK 恢复流不再发送
`TOOL_CALL_START`，因此新回复最初没有原 `toolCallId` 的工具行。浏览器实测发现：即使
`tool_call_summary` 已落库，前端仅查找当前回复内的行，仍无法展示摘要。

`onToolCallSummary` 因此增加「补建行」语义：

1. 按 `toolCallId` 查全局 `pendingToolCalls`；
2. 若当前回复没有该行，使用合成帧携带的 `toolName` 调用 `onToolCallStart` 补建并认领原调用；
3. 写入 `summary` 后重建工具行，使已到达的 result/state 一并渲染；
4. 更新工具组标题。

该逻辑只在摘要先于当前回复工具行到达时生效；正常链路已有行，不重复创建。

### 4.7 重建工具行只保留一个点击入口

`renderToolRow` 的 HTML 自带 `onclick="window.App.toolRowToggle(this)"`，供 history
静态回放使用；`rebuildToolRows` 原先又对相同行调用 `addEventListener`，导致一次点击
连续切换两次，用户看到的结果是“点击后仍未展开”。重建阶段不再重复绑定，保留内联入口；
`onToolCallStart` 创建的动态初始行仍使用 `addEventListener`。

---

## 5. 时序

```text
用户批准
  │
  ▼
POST /threads/{sid}/confirm-stream
  │
  ▼
ConfirmController.handleEventAndEmit
  ├─ eventBus.emit(TOOL_RESULT_END)
  └─ toolSummary.onToolResultEnd
       ├─ summarizedCalls.add(id)=true
       │    └─ emitSynthetic(tool_call_summary, 「执行 工具名」)
       └─ emitSynthetic(tool_result_preview, 结果首行/终态)
```

---

## 6. 兼容性

| 面 | 影响 |
|---|---|
| REST 请求/响应 | 无变化 |
| 原生 SSE 事件 | 无新增、无伪造，仍只由 SDK 产生 |
| 合成 SSE 事件 | HITL 恢复段多一帧 `tool_call_summary` |
| 前端 | 已按 `type + toolCallId` 处理摘要；未知帧忽略的旧客户端不受影响 |
| history | 不依赖该帧，消息级事实仍在 `agent_state` |
| 多副本 | 事件仍落 Redis Streams，语义一致 |
| 配置 | 无新增环境变量 |

---

## 7. 测试设计

### 7.1 单元测试

- 恢复段仅 `TOOL_RESULT_END`：先补 `tool_call_summary`，再发 `tool_result_preview`；
- 失败结果：兜底摘要后仍显示 `❌ 执行失败` 等终态预览；
- 空成功结果：无预览，但仍有兜底调用摘要；
- 正常链路已有 `TOOL_CALL_END`：不重复发调用摘要；
- 同一 `TOOL_RESULT_END` 重放：不重复发兜底摘要；
- MCP 名：`mcp__oaf__publish_service` 显示 `执行 publish_service`；
- 空工具名安全回落 `调用工具`。

### 7.2 E2E

H2 覆盖完整黑盒链：

1. 发起 `submit_application`；
2. 等待 `permission_ask`；
3. `confirm-stream` 批准；
4. 断言恢复流包含：
   - `tool_call_summary.toolName = submit_application`
   - `summary = 执行 submit_application`
   - `tool_result_preview`
5. 断言调用摘要先于结果预览；
6. 既有断言继续保证工具成功、pendingConfirm 清理与 history 状态。

---

## 8. 实施与验证记录（2026-09-25）

### 8.1 代码落点

| 文件 | 变更 |
|---|---|
| `TurnToolSummaryTracker.java` | 新增 `summarizedCalls`；`RESULT_END` 缺少调用摘要时兜底补发一次 |
| `ToolSummaryGenerator.java` | 新增 `resumedCallSummary`：「执行 {工具名}」 |
| `static/debug/modules/chat.js` | 摘要在新 confirm 回复中补建并认领原 `toolCallId` 工具行 |
| `TurnToolSummaryTrackerTest.java` | 恢复段兜底、失败终态、空结果、去重、正常链路不重复 |
| `ToolSummaryGeneratorTest.java` | 兜底文案与 MCP 末段名 |
| `api-core.spec.ts` H2 | 真实 HITL 批准流断言摘要、预览、顺序与 `/subscribe` 回放 |
| `ui.spec.ts` U4 | Debug 页展开工具组，断言摘要标题与成功结果可见 |

### 8.2 验证结果

- `mvn test`：992 个测试，0 失败，4 个既有沙箱集成测试跳过
- core API + models + reload E2E：38 通过，1 个既有 `F5` fixme 跳过
- multi E2E：6/6 通过（含跨副本 confirm 互斥与 kill 接管）
- sandbox E2E：7 通过，1 个既有 `X3` fixme 跳过
- Debug UI E2E：16 个启用场景通过，2 个既有 fixme（U8/U11）跳过
- Chromium 黑盒截图：`permission_ask` → 批准后显示「执行 submit_application」及真实成功结果，`pageErrors=[]`

---

## 9. 限制与后续方向

1. 兜底摘要没有参数信息，无法显示 application_id、路径或命令；
2. 依赖 `ToolResultEndEvent` 携带 `toolCallId`；缺失 id 时无法安全幂等，跳过兜底；
3. 旧会话历史中已缺失的摘要不会补写；
4. 若未来 SDK 恢复流开始重放 `TOOL_CALL_*`，本设计仍兼容：`TOOL_CALL_END` 先标记，
   `TOOL_RESULT_END` 不再兜底；
5. 若需要参数级恢复摘要，应在 SDK 事件层提供带「replayed」标记的调用事件，或让
   `AgentRuntimeService` 在恢复入口返回已批准工具元数据，再由 tracker 生成精确文案。
