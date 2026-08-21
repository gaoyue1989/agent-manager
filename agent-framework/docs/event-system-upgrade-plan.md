# Debug 页面事件系统升级方案

> **状态: ✅ 已完成 (2026-08-10)，事件交付已演进为单次流 SSE**
> 基于 [AgentScope 2.0 Message & Event 文档](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html)，
> 对 Debug 页面和后端接口进行升级，补齐缺失的事件类型支持。
> 实施时发现实际 JAR (agentscope-core 2.0.0) 与文档 API 存在 4 处差异（见 1.4 节），已按实际 API 适配。
> 新增/更新测试 8 个，全部 166 个测试通过。
> **后续演进**：长连接 SSE（SessionEventBus + GET /events 订阅）已被 stateless-single-stream-plan.md 取代，
> 改为 POST /threads/{sid}/chat 单次流 SSE（SSE 直吐，执行完即关闭），详见新架构文档。

---

## 一、现状分析

### 1.1 AgentScope 2.0 事件体系

AgentScope 2.0 定义了完整的事件生命周期模型，事件按 **start → delta → end** 模式组织：

```
AgentStartEvent
  ├── ModelCallStartEvent
  │     ├── TextBlockStartEvent → TextBlockDeltaEvent(×N) → TextBlockEndEvent
  │     ├── ThinkingBlockStartEvent → ThinkingBlockDeltaEvent(×N) → ThinkingBlockEndEvent
  │     ├── DataBlockStartEvent → DataBlockDeltaEvent(×N) → DataBlockEndEvent
  │     └── ToolCallStartEvent → ToolCallDeltaEvent(×N) → ToolCallEndEvent
  └── ModelCallEndEvent

  ├── ToolResultStartEvent → ToolResultTextDeltaEvent(×N) → ToolResultEndEvent
  └── AgentEndEvent
```

关键关联字段：
- `replyId` — 关联到正在构建的消息
- `blockId` — 关联文本/思考/数据块事件
- `toolCallId` — 关联工具调用和工具结果事件

### 1.2 当前实现覆盖情况

#### 后端 `AgentRuntimeService.invokeStream()` (行 130-175)

| 事件类型 | 处理状态 | 转发字段 |
|---|---|---|
| `TEXT_BLOCK_DELTA` | ✅ | `type=token`, `token` |
| `TOOL_CALL_START` | ✅ | `type=tool_call`, `name`, `tool_call_id` |
| `TOOL_RESULT_END` | ✅ | `type=tool_result`, `state` |
| `AGENT_END` | ✅ | `type=task_update` + `type=done` |
| `AGENT_START` | ❌ 丢弃 | — |
| `THINKING_BLOCK_*` | ❌ 丢弃 | — |
| `DATA_BLOCK_*` | ❌ 丢弃 | — |
| `TOOL_CALL_DELTA` | ❌ 丢弃 | — |
| `TOOL_CALL_END` | ❌ 丢弃 | — |
| `TOOL_RESULT_START` | ❌ 丢弃 | — |
| `TOOL_RESULT_TEXT_DELTA` | ❌ 丢弃 | — |
| `TOOL_RESULT_DATA_DELTA` | ❌ 丢弃 | — |
| `MODEL_CALL_START` | ❌ 丢弃 | — |
| `MODEL_CALL_END` | ❌ 丢弃 | — |
| HITL 事件 | ❌ 丢弃 | — |

#### 后端 `StreamController.toSSE()` (行 69-90)

| 事件类型 | 处理状态 | 转发字段 |
|---|---|---|
| `TextBlockDeltaEvent` | ✅ | `delta` |
| `ToolCallStartEvent` | ✅ | `toolName`, `toolCallId` |
| `ToolResultEndEvent` | ✅ | `state` |
| 其他所有事件 | ❌ 仅转发 `type` + `id` | — |

#### 前端 `chat.js` — Channel 模式 (行 299-339)

仅处理 `TEXT_BLOCK_DELTA` 事件类型。

#### 前端 `chat.js` — A2A 模式 (行 342-421)

| 事件 | 处理状态 |
|---|---|
| `data.token` | ✅ 文本增量 |
| `data.type === 'tool_call'` | ✅ 工具调用 |
| `data.type === 'tool_result'` | ✅ 工具结果 |
| `data.type === 'done'` | ✅ 完成 |
| 思维链事件 | ❌ |
| 工具参数增量 | ❌ |
| 工具结果增量 | ❌ |
| 模型调用元数据 | ❌ |

### 1.3 核心差距总结

| 差距 | 影响 | 优先级 |
|---|---|---|
| 工具参数增量 (`ToolCallDeltaEvent`) 丢失 | 无法实时展示工具参数构建过程 | **高** |
| 工具结果增量 (`ToolResultTextDeltaEvent`) 丢失 | 工具输出只能看到最终结果，无法实时流式展示 | **高** |
| 思维链事件 (`ThinkingBlock*`) 丢失 | 模型推理过程不可见，调试困难 | **中** |
| 多模态数据事件 (`DataBlock*`) 丢失 | 图片/音频/视频响应无法展示 | **中** |
| 模型调用元数据 (`ModelCallStart/End`) 丢失 | LLM Calls 弹窗依赖独立接口，事件流不携带模型名和 token 用量 | **中** |
| 事件关联 (`replyId/blockId/toolCallId`) 未使用 | 事件被独立处理，无法按逻辑块分组还原完整消息 | **低** |
| HITL 事件未处理 | 人工介入流程无法在 Debug 页面触发和展示 | **低** |

### 1.4 实际 JAR API 与文档差异（实施时发现）

实施前通过 `javap` 反编译 `agentscope-core-2.0.0.jar` 确认，以下文档描述与实际 API 不符，代码已按实际 API 适配：

| 项 | 文档写法 | 实际 API (v2.0.0) |
|---|---|---|
| `ModelCallStartEvent` | `getModelName()` | ❌ 不存在，仅 `getReplyId()`，无法获取模型名 |
| `ModelCallEndEvent` | `getInputTokens()`/`getOutputTokens()` | ❌ 不存在，改用 `getUsage()` → `ChatUsage.getInputTokens()/getOutputTokens()/getTotalTokens()` |
| `DataBlockStartEvent` | `getMediaType()` | ❌ 不存在，仅 `getReplyId()/getBlockId()` |
| `DataBlockDeltaEvent` | `getData()` | ❌ 实为 `getDelta()` |
| `ToolResultDataDeltaEvent` | `getMediaType()/getData()` | ❌ `getData()` 返回 `ContentBlock`（DataBlock），需从 `Source` 提取：`Base64Source.getMediaType()/getData()` 或 `URLSource.getUrl()/getMimeType()` |

---

## 二、改造方案

> **注意：本文档的事件转发逻辑（AgentRuntimeService/StreamController）已全量实施，但事件交付机制已从长连接 SSE 改为单次流 SSE。详见 [stateless-single-stream-plan.md](stateless-single-stream-plan.md)。**

### 2.1 整体架构

```
AgentScope Agent
    │
    ▼ streamEvents()
AgentRuntimeService.invokeStream()
    │  处理所有事件类型，构造标准化 Map
    ▼
Flux<Map<String, Object>>
    │
    ├──→ A2A 模式: HarnessAgentRunner.stream() → A2AController → SSE
    │
    ├──→ 单次流模式: ChatUiChannel.sendStream() → SessionStreamController → SSE (单次流直吐)
    │     POST /threads/{sid}/chat → 执行完即关闭，Turn 租约排队
    │
    └──→ 旧一次性流: ChatUiChannel.sendStream() → StreamController.toSSE() → SSE
          GET /chat/stream (保留兼容)
                                                          
                                              前端 chat.js
                                              统一事件处理渲染
```

### 2.2 后端改造

#### 2.2.1 `AgentRuntimeService.invokeStream()` — 事件处理扩展

**文件**: `src/main/java/io/agentmanager/framework/service/AgentRuntimeService.java`
**位置**: 行 130-175

**改造思路**: 在 `doOnNext` 中补充处理所有事件类型，每种事件构造标准化的 Map 结构转发。

**新增事件处理逻辑**:

```java
// 在现有 doOnNext 中扩展
agent.streamEvents(List.of(userMsg), ctx)
    .doOnNext(event -> {
        var type = event.getType();

        // ===== 生命周期事件 =====
        if (type == AgentEventType.AGENT_START) {
            var start = (io.agentscope.core.event.AgentStartEvent) event;
            sink.next(Map.of(
                "type", "agent_start",
                "task_id", tid,
                "reply_id", start.getReplyId(),
                "session_id", start.getSessionId() != null ? start.getSessionId() : "",
                "name", start.getName() != null ? start.getName() : ""
            ));
        }

        // ===== 文本流式事件 =====
        else if (type == AgentEventType.TEXT_BLOCK_START) {
            var e = (io.agentscope.core.event.TextBlockStartEvent) event;
            sink.next(Map.of(
                "type", "text_block_start",
                "task_id", tid,
                "reply_id", e.getReplyId() != null ? e.getReplyId() : "",
                "block_id", e.getBlockId() != null ? e.getBlockId() : ""
            ));
        }
        else if (type == AgentEventType.TEXT_BLOCK_DELTA) {
            var delta = ((io.agentscope.core.event.TextBlockDeltaEvent) event).getDelta();
            sink.next(Map.of("type", "token", "token", delta, "task_id", tid));
        }
        else if (type == AgentEventType.TEXT_BLOCK_END) {
            var e = (io.agentscope.core.event.TextBlockEndEvent) event;
            sink.next(Map.of(
                "type", "text_block_end",
                "task_id", tid,
                "reply_id", e.getReplyId() != null ? e.getReplyId() : "",
                "block_id", e.getBlockId() != null ? e.getBlockId() : ""
            ));
        }

        // ===== 思维链事件 =====
        else if (type == AgentEventType.THINKING_BLOCK_START) {
            var e = (io.agentscope.core.event.ThinkingBlockStartEvent) event;
            sink.next(Map.of(
                "type", "thinking_block_start",
                "task_id", tid,
                "reply_id", e.getReplyId() != null ? e.getReplyId() : "",
                "block_id", e.getBlockId() != null ? e.getBlockId() : ""
            ));
        }
        else if (type == AgentEventType.THINKING_BLOCK_DELTA) {
            var e = (io.agentscope.core.event.ThinkingBlockDeltaEvent) event;
            sink.next(Map.of(
                "type", "thinking_block_delta",
                "task_id", tid,
                "delta", e.getDelta() != null ? e.getDelta() : "",
                "reply_id", e.getReplyId() != null ? e.getReplyId() : "",
                "block_id", e.getBlockId() != null ? e.getBlockId() : ""
            ));
        }
        else if (type == AgentEventType.THINKING_BLOCK_END) {
            var e = (io.agentscope.core.event.ThinkingBlockEndEvent) event;
            sink.next(Map.of(
                "type", "thinking_block_end",
                "task_id", tid,
                "reply_id", e.getReplyId() != null ? e.getReplyId() : "",
                "block_id", e.getBlockId() != null ? e.getBlockId() : ""
            ));
        }

        // ===== 多模态数据事件 =====
        else if (type == AgentEventType.DATA_BLOCK_START) {
            var e = (io.agentscope.core.event.DataBlockStartEvent) event;
            sink.next(Map.of(
                "type", "data_block_start",
                "task_id", tid,
                "media_type", e.getMediaType() != null ? e.getMediaType() : "",
                "reply_id", e.getReplyId() != null ? e.getReplyId() : "",
                "block_id", e.getBlockId() != null ? e.getBlockId() : ""
            ));
        }
        else if (type == AgentEventType.DATA_BLOCK_DELTA) {
            var e = (io.agentscope.core.event.DataBlockDeltaEvent) event;
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("type", "data_block_delta");
            m.put("task_id", tid);
            m.put("data", e.getData() != null ? e.getData() : "");
            m.put("reply_id", e.getReplyId() != null ? e.getReplyId() : "");
            m.put("block_id", e.getBlockId() != null ? e.getBlockId() : "");
            sink.next(m);
        }
        else if (type == AgentEventType.DATA_BLOCK_END) {
            var e = (io.agentscope.core.event.DataBlockEndEvent) event;
            sink.next(Map.of(
                "type", "data_block_end",
                "task_id", tid,
                "reply_id", e.getReplyId() != null ? e.getReplyId() : "",
                "block_id", e.getBlockId() != null ? e.getBlockId() : ""
            ));
        }

        // ===== 工具调用流式事件 =====
        else if (type == AgentEventType.TOOL_CALL_START) {
            var tc = (io.agentscope.core.event.ToolCallStartEvent) event;
            sink.next(Map.of(
                "type", "tool_call",
                "task_id", tid,
                "name", tc.getToolCallName() != null ? tc.getToolCallName() : "",
                "tool_call_id", tc.getToolCallId() != null ? tc.getToolCallId() : ""
            ));
        }
        else if (type == AgentEventType.TOOL_CALL_DELTA) {
            var e = (io.agentscope.core.event.ToolCallDeltaEvent) event;
            sink.next(Map.of(
                "type", "tool_call_delta",
                "task_id", tid,
                "delta", e.getDelta() != null ? e.getDelta() : "",
                "tool_call_id", e.getToolCallId() != null ? e.getToolCallId() : ""
            ));
        }
        else if (type == AgentEventType.TOOL_CALL_END) {
            var e = (io.agentscope.core.event.ToolCallEndEvent) event;
            sink.next(Map.of(
                "type", "tool_call_end",
                "task_id", tid,
                "tool_call_id", e.getToolCallId() != null ? e.getToolCallId() : ""
            ));
        }

        // ===== 工具结果流式事件 =====
        else if (type == AgentEventType.TOOL_RESULT_START) {
            var e = (io.agentscope.core.event.ToolResultStartEvent) event;
            sink.next(Map.of(
                "type", "tool_result_start",
                "task_id", tid,
                "tool_call_id", e.getToolCallId() != null ? e.getToolCallId() : "",
                "tool_call_name", e.getToolCallName() != null ? e.getToolCallName() : ""
            ));
        }
        else if (type == AgentEventType.TOOL_RESULT_TEXT_DELTA) {
            var e = (io.agentscope.core.event.ToolResultTextDeltaEvent) event;
            sink.next(Map.of(
                "type", "tool_result_text_delta",
                "task_id", tid,
                "delta", e.getDelta() != null ? e.getDelta() : "",
                "tool_call_id", e.getToolCallId() != null ? e.getToolCallId() : ""
            ));
        }
        else if (type == AgentEventType.TOOL_RESULT_DATA_DELTA) {
            var e = (io.agentscope.core.event.ToolResultDataDeltaEvent) event;
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("type", "tool_result_data_delta");
            m.put("task_id", tid);
            m.put("tool_call_id", e.getToolCallId() != null ? e.getToolCallId() : "");
            m.put("media_type", e.getMediaType() != null ? e.getMediaType() : "");
            m.put("data", e.getData() != null ? e.getData() : "");
            sink.next(m);
        }
        else if (type == AgentEventType.TOOL_RESULT_END) {
            var tr = (io.agentscope.core.event.ToolResultEndEvent) event;
            sink.next(Map.of(
                "type", "tool_result",
                "task_id", tid,
                "state", tr.getState().name(),
                "tool_call_id", tr.getToolCallId() != null ? tr.getToolCallId() : ""
            ));
        }

        // ===== 模型调用事件 =====
        else if (type == AgentEventType.MODEL_CALL_START) {
            var e = (io.agentscope.core.event.ModelCallStartEvent) event;
            sink.next(Map.of(
                "type", "model_call_start",
                "task_id", tid,
                "model_name", e.getModelName() != null ? e.getModelName() : ""
            ));
        }
        else if (type == AgentEventType.MODEL_CALL_END) {
            var e = (io.agentscope.core.event.ModelCallEndEvent) event;
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("type", "model_call_end");
            m.put("task_id", tid);
            m.put("input_tokens", e.getInputTokens());
            m.put("output_tokens", e.getOutputTokens());
            sink.next(m);
        }

        // ===== 结束事件 =====
        else if (type == AgentEventType.AGENT_END) {
            sink.next(Map.of(
                "type", "task_update", "id", tid,
                "state", "completed",
                "metadata", Map.of("thread_id", tid)
            ));
            sink.next(Map.of("type", "done"));
            sink.complete();
        }
    })
```

**注意点**:
- 所有可能为 null 的字段都需要做 null 检查，避免 `Map.of()` 抛 NPE
- 使用 `LinkedHashMap` 替代 `Map.of()` 处理可选字段场景
- `ToolResultEndEvent` 需要额外转发 `tool_call_id`，便于前端关联

#### 2.2.2 `StreamController.toSSE()` — Channel 模式事件转发

**文件**: `src/main/java/io/agentmanager/framework/controller/StreamController.java`
**位置**: 行 69-90

**改造思路**: 扩展 `toSSE` 方法，为更多事件类型提取专属字段。

```java
private ServerSentEvent<String> toSSE(AgentEvent event) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", event.getType().name());
    payload.put("id", event.getId());

    if (event instanceof TextBlockDeltaEvent delta) {
        payload.put("delta", delta.getDelta());
    } else if (event instanceof ThinkingBlockDeltaEvent delta) {
        payload.put("delta", delta.getDelta());
    } else if (event instanceof ToolCallStartEvent tc) {
        payload.put("toolName", tc.getToolCallName());
        payload.put("toolCallId", tc.getToolCallId());
    } else if (event instanceof ToolCallDeltaEvent delta) {
        payload.put("delta", delta.getDelta());
        payload.put("toolCallId", delta.getToolCallId());
    } else if (event instanceof ToolCallEndEvent end) {
        payload.put("toolCallId", end.getToolCallId());
    } else if (event instanceof ToolResultStartEvent tr) {
        payload.put("toolCallId", tr.getToolCallId());
        payload.put("toolCallName", tr.getToolCallName());
    } else if (event instanceof ToolResultTextDeltaEvent tr) {
        payload.put("delta", tr.getDelta());
        payload.put("toolCallId", tr.getToolCallId());
    } else if (event instanceof ToolResultEndEvent tr) {
        payload.put("state", tr.getState().name());
        payload.put("toolCallId", tr.getToolCallId());
    } else if (event instanceof ModelCallStartEvent mcs) {
        payload.put("modelName", mcs.getModelName());
    } else if (event instanceof ModelCallEndEvent mce) {
        payload.put("inputTokens", mce.getInputTokens());
        payload.put("outputTokens", mce.getOutputTokens());
    } else if (event instanceof DataBlockStartEvent dbs) {
        payload.put("mediaType", dbs.getMediaType());
    } else if (event instanceof DataBlockDeltaEvent dbd) {
        payload.put("data", dbd.getData());
    }

    try {
        return ServerSentEvent.<String>builder()
            .data(MAPPER.writeValueAsString(payload))
            .build();
    } catch (Exception e) {
        return ServerSentEvent.<String>builder().data("{}").build();
    }
}
```

**需新增 import**:
```java
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
```

---

### 2.3 前端改造

#### 2.3.1 Channel 模式 — `sendChannelSSE()` 扩展

**文件**: `src/main/resources/static/debug/modules/chat.js`
**位置**: 行 299-339

**改造思路**: 在 SSE 数据解析循环中，补充处理新增的事件类型。

```javascript
// 现有: 仅处理 TEXT_BLOCK_DELTA
if (data.type === 'TEXT_BLOCK_DELTA' && data.delta && streamingDiv) {
    fullText += data.delta;
    updateStreamContent(fullText);
}

// 新增: 思维链
if (data.type === 'THINKING_BLOCK_DELTA' && data.delta) {
    appendThinkingDelta(data.delta);
}

// 新增: 工具调用开始
if (data.type === 'TOOL_CALL_START') {
    handleToolCallEvent({
        type: 'tool_call',
        name: data.toolName,
        tool_call_id: data.toolCallId
    });
}

// 新增: 工具参数增量
if (data.type === 'TOOL_CALL_DELTA' && data.delta) {
    handleToolCallDelta(data.toolCallId, data.delta);
}

// 新增: 工具调用结束
if (data.type === 'TOOL_CALL_END') {
    handleToolCallEnd(data.toolCallId);
}

// 新增: 工具结果开始
if (data.type === 'TOOL_RESULT_START') {
    handleToolResultStart(data.toolCallId, data.toolCallName);
}

// 新增: 工具结果文本增量
if (data.type === 'TOOL_RESULT_TEXT_DELTA' && data.delta) {
    handleToolResultTextDelta(data.toolCallId, data.delta);
}

// 新增: 工具结果结束
if (data.type === 'TOOL_RESULT_END') {
    handleToolResultEvent({
        type: 'tool_result',
        tool_call_id: data.toolCallId,
        state: data.state
    });
}

// 新增: 模型调用元数据
if (data.type === 'MODEL_CALL_START') {
    // 可选: 显示模型调用开始指示
}
if (data.type === 'MODEL_CALL_END') {
    addUsageStats({
        input_tokens: data.inputTokens,
        output_tokens: data.outputTokens
    }, null);
}
```

#### 2.3.2 A2A 模式 — `sendA2AStream()` 扩展

**文件**: `src/main/resources/static/debug/modules/chat.js`
**位置**: 行 342-421

**改造思路**: 在 A2A 数据解析分支中，补充处理新增事件类型。

```javascript
// 现有处理
if (data.token && streamingDiv) {
    fullText += data.token;
    updateStreamContent(fullText);
} else if (data.type === 'tool_call') {
    handleToolCallEvent(data);
} else if (data.type === 'tool_result') {
    await handleToolResultEvent(data);
} else if (data.type === 'done') { ... }

// 新增: 思维链增量
else if (data.type === 'thinking_block_delta' && data.delta) {
    appendThinkingDelta(data.delta);
}

// 新增: 工具参数增量
else if (data.type === 'tool_call_delta' && data.delta) {
    handleToolCallDelta(data.tool_call_id, data.delta);
}

// 新增: 工具调用结束
else if (data.type === 'tool_call_end') {
    handleToolCallEnd(data.tool_call_id);
}

// 新增: 工具结果开始
else if (data.type === 'tool_result_start') {
    handleToolResultStart(data.tool_call_id, data.tool_call_name);
}

// 新增: 工具结果文本增量
else if (data.type === 'tool_result_text_delta' && data.delta) {
    handleToolResultTextDelta(data.tool_call_id, data.delta);
}

// 新增: 模型调用元数据
else if (data.type === 'model_call_end') {
    addUsageStats({
        input_tokens: data.input_tokens,
        output_tokens: data.output_tokens
    }, null);
}
```

#### 2.3.3 新增渲染函数

**文件**: `src/main/resources/static/debug/modules/chat.js`

需要新增以下函数：

```javascript
// ========== 思维链渲染 ==========

let thinkingDiv = null;
let thinkingText = '';

function createThinkingBlock() {
    const div = document.createElement('div');
    div.className = 'msg thinking';
    div.innerHTML = '<div class="thinking-header" onclick="window.App.thinkingToggle(this)">' +
        '<span class="thinking-icon">🧠</span>' +
        '<span class="thinking-label">Thinking...</span>' +
        '<span class="thinking-toggle">▼</span></div>' +
        '<div class="thinking-body open"><pre class="thinking-content"></pre></div>';
    messagesEl.appendChild(div);
    ctx.utils.scrollBottom(messagesEl);
    return div;
}

function appendThinkingDelta(delta) {
    if (!thinkingDiv) {
        thinkingDiv = createThinkingBlock();
        thinkingText = '';
    }
    thinkingText += delta;
    const content = thinkingDiv.querySelector('.thinking-content');
    if (content) content.textContent = thinkingText;
    ctx.utils.scrollBottom(messagesEl);
}

function finishThinkingBlock() {
    if (thinkingDiv) {
        const label = thinkingDiv.querySelector('.thinking-label');
        if (label) label.textContent = 'Thinking (' + thinkingText.length + ' chars)';
        // 默认折叠
        const body = thinkingDiv.querySelector('.thinking-body');
        const toggle = thinkingDiv.querySelector('.thinking-toggle');
        if (body) body.classList.remove('open');
        if (toggle) { toggle.classList.remove('open'); toggle.textContent = '▼'; }
    }
    thinkingDiv = null;
    thinkingText = '';
}

function toggleThinking(headerEl) {
    const body = headerEl.nextElementSibling;
    const toggle = headerEl.querySelector('.thinking-toggle');
    body.classList.toggle('open');
    toggle.classList.toggle('open');
    toggle.textContent = body.classList.contains('open') ? '▲' : '▼';
}

// ========== 工具参数渐进渲染 ==========

function handleToolCallDelta(toolCallId, delta) {
    const block = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(toolCallId || '') + '"]');
    if (!block) return;
    // 累积参数到 pendingToolCalls
    if (!pendingToolCalls[toolCallId]) pendingToolCalls[toolCallId] = { argsRaw: '' };
    if (!pendingToolCalls[toolCallId].argsRaw) pendingToolCalls[toolCallId].argsRaw = '';
    pendingToolCalls[toolCallId].argsRaw += delta;
    // 实时更新参数显示
    const argsPre = block.querySelector('.tc-args pre');
    if (argsPre) {
        try {
            const parsed = JSON.parse(pendingToolCalls[toolCallId].argsRaw);
            argsPre.textContent = JSON.stringify(parsed, null, 2);
        } catch {
            argsPre.textContent = pendingToolCalls[toolCallId].argsRaw;
        }
    }
}

function handleToolCallEnd(toolCallId) {
    // 参数构建完成，尝试格式化
    const tc = pendingToolCalls[toolCallId];
    if (tc && tc.argsRaw) {
        try {
            tc.args = JSON.parse(tc.argsRaw);
        } catch { /* 保留原始文本 */ }
    }
}

// ========== 工具结果渐进渲染 ==========

let toolResultBuffers = {};

function handleToolResultStart(toolCallId, toolCallName) {
    toolResultBuffers[toolCallId] = { name: toolCallName, text: '' };
    // 在对应工具调用块下方创建结果区域
    const block = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(toolCallId || '') + '"]');
    if (block) {
        const body = block.querySelector('.tool-call-body');
        if (body && !body.querySelector('.tc-result')) {
            body.innerHTML += '<div class="tc-result"><div class="tc-label" style="margin-top:8px">Result</div>' +
                '<pre class="tc-result-content"></pre></div>';
            body.classList.add('open');
            const toggle = block.querySelector('.tc-toggle');
            if (toggle) { toggle.classList.add('open'); toggle.textContent = '▲'; }
        }
    }
}

function handleToolResultTextDelta(toolCallId, delta) {
    const buf = toolResultBuffers[toolCallId];
    if (buf) buf.text += delta;
    // 实时更新结果内容
    const block = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(toolCallId || '') + '"]');
    if (block) {
        const resultPre = block.querySelector('.tc-result-content');
        if (resultPre) resultPre.textContent = buf ? buf.text : delta;
    }
    ctx.utils.scrollBottom(messagesEl);
}

function cleanupToolResultBuffer(toolCallId) {
    delete toolResultBuffers[toolCallId];
}
```

#### 2.3.4 CSS 样式新增

**文件**: `src/main/resources/static/debug/css/components.css`

```css
/* 思维链块 */
.msg.thinking { background: var(--bg-secondary); border-left: 3px solid var(--purple, #a78bfa); }
.thinking-header { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 8px 12px; }
.thinking-icon { font-size: 14px; }
.thinking-label { font-size: 12px; color: var(--text-dim); }
.thinking-toggle { margin-left: auto; font-size: 10px; color: var(--text-dim); transition: transform 0.2s; }
.thinking-toggle.open { transform: rotate(180deg); }
.thinking-body { display: none; padding: 0 12px 8px; }
.thinking-body.open { display: block; }
.thinking-content { font-size: 12px; color: var(--text-secondary); white-space: pre-wrap; max-height: 300px; overflow-y: auto; background: var(--bg-tertiary); border-radius: 4px; padding: 8px; }

/* 工具参数渐进更新动画 */
.tc-args pre { transition: background-color 0.15s; }
.tc-args pre.updating { background-color: rgba(167, 139, 250, 0.05); }

/* 工具结果渐进更新 */
.tc-result-content { min-height: 20px; }
```

---

### 2.4 事件类型与字段映射表

| AgentScope 事件 | `type` 字段值 | 额外字段 | 前端处理 |
|---|---|---|---|
| `AgentStartEvent` | `agent_start` | `reply_id`, `session_id`, `name` | 可选: 显示回复开始标记 |
| `TextBlockStartEvent` | `text_block_start` | `reply_id`, `block_id` | 准备接收文本 |
| `TextBlockDeltaEvent` | `token` | `token` | 追加到 `fullText` 并渲染 |
| `TextBlockEndEvent` | `text_block_end` | `reply_id`, `block_id` | 文本块结束 |
| `ThinkingBlockStartEvent` | `thinking_block_start` | `reply_id`, `block_id` | 创建思维链块 |
| `ThinkingBlockDeltaEvent` | `thinking_block_delta` | `delta`, `reply_id`, `block_id` | 追加思维链文本 |
| `ThinkingBlockEndEvent` | `thinking_block_end` | `reply_id`, `block_id` | 折叠思维链块 |
| `DataBlockStartEvent` | `data_block_start` | `media_type`, `reply_id`, `block_id` | 准备接收多模态数据 |
| `DataBlockDeltaEvent` | `data_block_delta` | `data`, `reply_id`, `block_id` | 累积 base64 数据 |
| `DataBlockEndEvent` | `data_block_end` | `reply_id`, `block_id` | 渲染图片/音频/视频 |
| `ToolCallStartEvent` | `tool_call` | `name`, `tool_call_id` | 创建工具调用块 |
| `ToolCallDeltaEvent` | `tool_call_delta` | `delta`, `tool_call_id` | 实时更新参数显示 |
| `ToolCallEndEvent` | `tool_call_end` | `tool_call_id` | 格式化最终参数 |
| `ToolResultStartEvent` | `tool_result_start` | `tool_call_id`, `tool_call_name` | 创建结果区域 |
| `ToolResultTextDeltaEvent` | `tool_result_text_delta` | `delta`, `tool_call_id` | 实时更新结果文本 |
| `ToolResultDataDeltaEvent` | `tool_result_data_delta` | `media_type`, `data`, `tool_call_id` | 累积二进制结果 |
| `ToolResultEndEvent` | `tool_result` | `state`, `tool_call_id` | 标记结果完成 |
| `ModelCallStartEvent` | `model_call_start` | `model_name` | 可选: 显示模型名 |
| `ModelCallEndEvent` | `model_call_end` | `input_tokens`, `output_tokens` | 显示 token 统计 |
| `AgentEndEvent` | `done` | — | 清理状态，结束流式 |

---

## 三、实施计划

### 3.1 阶段划分

| 阶段 | 内容 | 工作量 | 优先级 | 状态 |
|---|---|---|---|---|
| 阶段一 | 后端 `AgentRuntimeService` 事件处理扩展 | 0.5 天 | P0 | ✅ 已完成 |
| 阶段二 | 后端 `StreamController` 事件转发扩展 | 0.5 天 | P0 | ✅ 已完成 |
| 阶段三 | 前端工具参数/结果渐进渲染 | 1 天 | P0 | ✅ 已完成 |
| 阶段四 | 前端思维链渲染 | 0.5 天 | P1 | ✅ 已完成 |
| 阶段五 | 前端模型调用元数据展示 | 0.5 天 | P1 | ✅ 已完成 |
| 阶段六 | 多模态数据事件处理 | 0.5 天 | P2 | ✅ 已完成 |

**总工作量**: 约 3.5 天

### 3.2 依赖关系

```
阶段一 (后端 AgentRuntimeService)
    │
    ├──→ 阶段二 (后端 StreamController) ──→ 阶段三 (前端工具渲染)
    │                                        │
    │                                        ├──→ 阶段四 (前端思维链)
    │                                        │
    │                                        └──→ 阶段五 (前端模型调用)
    │
    └──→ 阶段六 (多模态数据)
```

### 3.3 文件变更清单

| 文件 | 变更类型 | 说明 | 状态 |
|---|---|---|---|
| `service/AgentRuntimeService.java` | 修改 | `invokeStream()` 扩展事件处理（+2 helper 方法） | ✅ |
| `controller/StreamController.java` | 修改 | `toSSE()` 扩展事件字段提取 | ✅ |
| `static/debug/modules/chat.js` | 修改 | 新增事件处理 + 渲染函数 | ✅ |
| `static/debug/js/app.js` | 修改 | 新增 `thinkingToggle` 全局处理 | ✅ |
| `static/debug/css/components.css` | 修改 | 新增思维链/工具渲染样式 | ✅ |
| `test/.../AgentRuntimeServiceTest.java` | 修改 | 新增 4 个事件转发测试 | ✅ |
| `test/.../StreamControllerTest.java` | 修改 | 重写为 standaloneSetup + 新增 4 个 SSE 序列化测试 | ✅ |

---

## 四、测试验证方案

### 4.1 单元测试

#### 4.1.1 后端事件转发测试

**目标**: 验证 `AgentRuntimeService.invokeStream()` 正确转发所有事件类型。

```java
@Test
void testInvokeStreamThinkingBlockEvents() {
    // 模拟 Agent 产出 ThinkingBlock 事件
    // 验证 Flux 中包含 thinking_block_start, thinking_block_delta, thinking_block_end
}

@Test
void testInvokeStreamToolCallDeltaEvents() {
    // 模拟 Agent 产出 ToolCallDelta 事件
    // 验证 Flux 中包含 tool_call_delta 带 delta 和 tool_call_id
}

@Test
void testInvokeStreamToolResultTextDeltaEvents() {
    // 模拟 Agent 产出 ToolResultTextDelta 事件
    // 验证 Flux 中包含 tool_result_text_delta 带 delta 和 tool_call_id
}

@Test
void testInvokeStreamModelCallEvents() {
    // 模拟 Agent 产出 ModelCallStart/End 事件
    // 验证 Flux 中包含 model_call_start (model_name) 和 model_call_end (input_tokens, output_tokens)
}
```

#### 4.1.2 StreamController SSE 序列化测试

**目标**: 验证 `toSSE()` 对各事件类型的 JSON 序列化正确性。

```java
@Test
void testToSSEThinkingBlockDelta() {
    var event = new ThinkingBlockDeltaEvent(...);
    var sse = streamController.toSSE(event);
    var json = MAPPER.readTree(sse.data());
    assertEquals("THINKING_BLOCK_DELTA", json.get("type").asText());
    assertEquals("thinking content", json.get("delta").asText());
}

@Test
void testToSSEToolCallDelta() {
    var event = new ToolCallDeltaEvent(...);
    var sse = streamController.toSSE(event);
    var json = MAPPER.readTree(sse.data());
    assertEquals("TOOL_CALL_DELTA", json.get("type").asText());
    assertNotNull(json.get("delta"));
    assertNotNull(json.get("toolCallId"));
}

@Test
void testToSSEModelCallEnd() {
    var event = new ModelCallEndEvent(...);
    var sse = streamController.toSSE(event);
    var json = MAPPER.readTree(sse.data());
    assertEquals("MODEL_CALL_END", json.get("type").asText());
    assertEquals(100, json.get("inputTokens").asInt());
    assertEquals(50, json.get("outputTokens").asInt());
}
```

### 4.2 集成测试

#### 4.2.1 A2A 模式完整事件流测试

**前置条件**: Agent Framework 启动，LLM 配置就绪。

**步骤**:

1. 发送会触发思维链的消息：
```bash
curl -N -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "message/stream",
    "params": {
      "message": {"role": "user", "parts": [{"text": "请详细分析 1+1=2 的数学证明过程，先思考再回答"}]}
    },
    "id": "test-thinking"
  }'
```

2. **验证点**:
   - [ ] 事件流中包含 `thinking_block_delta` 事件
   - [ ] `thinking_block_delta` 事件携带 `delta` 字段
   - [ ] `thinking_block_end` 事件在思维链结束后出现

3. 发送会触发工具调用的消息：
```bash
curl -N -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "message/stream",
    "params": {
      "message": {"role": "user", "parts": [{"text": "读取当前目录下的 pom.xml 文件"}]}
    },
    "id": "test-tool"
  }'
```

4. **验证点**:
   - [ ] 事件流中包含 `tool_call` (TOOL_CALL_START)
   - [ ] 事件流中包含 `tool_call_delta` (TOOL_CALL_DELTA)，携带 `delta` 参数片段
   - [ ] 事件流中包含 `tool_call_end` (TOOL_CALL_END)
   - [ ] 事件流中包含 `tool_result_start`
   - [ ] 事件流中包含 `tool_result_text_delta`，携带 `delta` 结果片段
   - [ ] 事件流中包含 `tool_result` (TOOL_RESULT_END)，携带 `state=SUCCESS`

5. 验证模型调用元数据：
```bash
# 观察事件流中的 model_call_start 和 model_call_end
```

6. **验证点**:
   - [ ] `model_call_start` 携带 `model_name`
   - [ ] `model_call_end` 携带 `input_tokens` 和 `output_tokens`

#### 4.2.2 Channel 模式完整事件流测试

**步骤**:

```bash
curl -N "http://localhost:8100/chat/stream?message=读取pom.xml&userId=debug-user"
```

**验证点**:
- [ ] SSE 事件中包含 `THINKING_BLOCK_DELTA` 类型（如模型支持思维链）
- [ ] SSE 事件中包含 `TOOL_CALL_START` 类型
- [ ] SSE 事件中包含 `TOOL_CALL_DELTA` 类型
- [ ] SSE 事件中包含 `TOOL_RESULT_TEXT_DELTA` 类型
- [ ] SSE 事件中包含 `MODEL_CALL_END` 类型，携带 token 用量

### 4.3 Debug 页面 E2E 测试

#### 4.3.1 工具参数渐进展示

**步骤**:
1. 打开 Debug 页面 `http://localhost:8100/debug`
2. 切换到 A2A 模式
3. 发送消息: "读取 pom.xml 文件内容"
4. 观察工具调用块

**验证点**:
- [ ] 工具调用块在 `ToolCallStartEvent` 时立即出现，显示工具名
- [ ] 工具参数区域在 `ToolCallDeltaEvent` 期间实时更新（JSON 片段逐步拼接）
- [ ] `ToolCallEndEvent` 后参数格式化为完整 JSON
- [ ] 工具结果区域在 `ToolResultStartEvent` 时出现
- [ ] 工具结果在 `ToolResultTextDeltaEvent` 期间实时流式展示
- [ ] `ToolResultEndEvent` 后结果显示完整

#### 4.3.2 思维链展示

**步骤**:
1. 打开 Debug 页面
2. 发送需要深度思考的消息（如数学推理、代码分析）
3. 观察是否出现思维链块

**验证点**:
- [ ] 思维链块在 `ThinkingBlockStartEvent` 时出现，带 🧠 图标
- [ ] 思维链文本在 `ThinkingBlockDeltaEvent` 期间实时更新
- [ ] `ThinkingBlockEndEvent` 后思维链块自动折叠，显示字符数
- [ ] 点击思维链 header 可展开/折叠

#### 4.3.3 模型调用统计

**步骤**:
1. 发送任意消息
2. 观察消息完成后的使用统计

**验证点**:
- [ ] `ModelCallEndEvent` 后显示输入/输出 token 数
- [ ] 统计数据与 `ModelCallEndEvent` 中的 `inputTokens`/`outputTokens` 一致
- [ ] 多轮对话时 token 统计正确累加（或分别显示）

#### 4.3.4 Channel 模式验证

**步骤**:
1. 切换到 Channel 模式
2. 重复上述测试步骤

**验证点**:
- [ ] 所有事件类型在 Channel 模式下同样正确处理
- [ ] 思维链、工具渐进渲染、模型统计均正常工作

### 4.4 边界场景测试

| 场景 | 验证点 |
|---|---|
| Agent 无思维链输出 | 不出现思维链块 |
| Agent 无工具调用 | 不出现工具调用块 |
| 工具调用参数为空 JSON `{}` | 正常显示空参数 |
| 工具执行超时/失败 | `ToolResultEndEvent.state` 显示为 `ERROR` |
| 流式中断（用户取消） | 不崩溃，状态正确清理 |
| 多个工具连续调用 | 每个工具独立渲染，不互相干扰 |
| 思维链超长（>10000 字符） | 折叠显示，不卡顿 |
| 网络断开 | 前端显示错误提示，不挂起 |

### 4.5 回归测试

```bash
# 确保现有测试全部通过
cd /root/agent-manager/agent-framework
mvn test

# 确保 Debug 页面基础功能不受影响
# - Thread 列表加载
# - 新建 Thread
# - 基本对话（文本消息）
# - LLM Calls 弹窗
# - System Prompt 弹窗
# - Agent Card 弹窗
```

---

## 五、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|
| AgentScope 事件类型与文档不一致 | 中 | 事件处理逻辑需要调整 | 先写集成测试验证实际事件类型 |
| `null` 字段导致 `Map.of()` NPE | 高 | 后端异常 | 所有字段做 null 检查，使用 `LinkedHashMap` |
| 思维链事件 `blockId` 未实现 | 低 | 无法区分多个思维链 | 按文档描述，`blockId` 可用稳定标识 |
| 前端大量 DOM 操作导致卡顿 | 低 | 工具结果超长时卡顿 | 使用 `requestAnimationFrame` 节流渲染 |
| Channel 模式事件类型名与 A2A 不一致 | 中 | 前端需要两套处理逻辑 | 统一事件类型命名（后端标准化） |

---

## 六、相关文档

| 文档 | 说明 |
|---|---|
| [AgentScope 2.0 Message & Event](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html) | 官方事件体系文档 |
| [Debug Page 重构计划](debug-page-refactor-plan.md) | Debug 页面架构设计 |
| [Tool System 改进方案](tool-system-improvement-plan.md) | 工具体系改进 |
| [API 文档](api.md) | 接口文档 |
| [Agent Framework AGENTS.md](../AGENTS.md) | 项目总览 |
