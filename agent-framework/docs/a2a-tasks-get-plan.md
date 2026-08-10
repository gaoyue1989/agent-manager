# A2A `tasks/get` + `message/send` 复用 SDK 方案

> **状态: ✅ 已完成 (2026-08-10)**
> 目标：复用 AgentScopeA2aServer（SDK）实现标准 A2A 协议能力：
> - `tasks/get` — 通过标准 A2A 协议查询任务历史消息（替代内部 `/debug/threads/{id}/history`）
> - `message/send` / `message/stream` — 复用 SDK 完整链路（标准返回 + Task 持久化）
> **实施结果**：
> 1. `PartParserRouter` 缺失已通过 pom.xml 添加 `agentscope-extensions-a2a-client` 依赖修复
> 2. 新增 `MySqlTaskStore`（读 agent_state）+ `StateDataParser`（公共解析类）
> 3. `A2AServerConfig` 注入 TaskStore + `postEndpointReady()`
> 4. **A2AController 完全官方化**（参考官方 `agentscope-a2a-spring-boot-starter` 的 `A2aJsonRpcController`）：
>    全量透传 SDK（message/send、message/stream、tasks/get、tasks/cancel、tasks/resubscribe 全部由 SDK 处理），
>    仅保留 message/send + message/stream 的兼容转换（kind/messageId/parts kind/blocking/userId/sessionId）
> 5. 前端 `chat.js sendA2AStream` 适配 SDK 标准 streaming 事件（artifact-update/status-update/message）
> 6. **186 个测试全部通过**
> 7. 集成验证通过：tasks/get、message/send（标准 Message）、message/stream（SDK SSE 事件流 task→working→artifact-update→completed→message）
> **关键发现**：
> - SDK 反序列化要求：message 需 `kind:"message"` + `messageId`，parts 需 `kind:"text"`（缺失则 -32602）
> - SDK message/send 写入 agent_state 的 session_id 为 `{userId}:{sessionId}`（A2A 规范格式）
> - SDK streaming 事件：`task(submitted)` → `status-update(working)` → `artifact-update`×N（含 `_agentscope_block_type` 区分 thinking/text）→ `status-update(completed, final:true)` → `message`

---

## 一、现状分析

### 1.0 历史背景：为何当初自实现 A2AController（重要）

**结论：当初的"SDK 缺陷（PartParserRouter 缺失）"判断不准确——真相是 `PartParserRouter` 位于 `agentscope-extensions-a2a-client` 模块，而 a2a-server 将其声明为 `provided + optional`（不传递引入），项目未显式引入导致运行时 `NoClassDefFoundError`。已通过添加 a2a-client 依赖修复（2026-08-10 验证）。**

| 事实 | 证据 |
|---|---|
| commit `658cb5b` 设计文档记载："`agentscope-extensions-a2a-server` 依赖了 `agentscope-core` 中不存在的 `PartParserRouter` 类，因此使用自定义 A2A Controller 替代" | git 历史（当时的错误判断） |
| `MessageConvertUtil`（a2a-server jar）静态初始化块引用 `io.agentscope.core.a2a.agent.message.PartParserRouter` | javap `static {}` 确认 |
| **PartParserRouter 实际位于 a2a-client 模块**：`agentscope-extensions-a2a-client/src/main/java/io/agentscope/core/a2a/agent/message/PartParserRouter.java`（GitHub 主分支源码） | GitHub 代码树确认 |
| **a2a-server 的 pom 将 a2a-client 声明为 `scope=provided` + `optional=true`**（Maven 不传递引入，使用方必须自己声明） | 解压 a2a-server jar 内嵌 pom 确认 |
| 项目 pom.xml 只引入 `a2a-server`，未引入 `a2a-client` → 运行时 `NoClassDefFoundError: PartParserRouter` | 依赖树确认 |
| `AgentScopeAgentExecutor.execute()` 调用 `MessageConvertUtil.convertFromMessageToMsgs()` | javap 确认 |
| `DefaultRequestHandler`（tasks/get 路径）**不引用** MessageConvertUtil | javap 确认 |

**缺陷触发链路**：
```
message/send → AgentScopeAgentExecutor.execute()
  → MessageConvertUtil.convertFromMessageToMsgs()   ← static{} 实例化 PartParserRouter
    → NoClassDefFoundError: PartParserRouter（因 a2a-client 未引入）
```

**修复方案（已验证）**：pom.xml 添加 a2a-client 依赖：

```xml
<!-- a2a-server 的 MessageConvertUtil 依赖 PartParserRouter（位于 a2a-client），
     a2a-server pom 中该依赖为 provided+optional，必须显式声明，否则运行时 NoClassDefFoundError -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-client</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

**验证结果（2026-08-10）**：
- ✅ `mvn compile` 通过，依赖树显示 `a2a-client:jar:2.0.0:compile`
- ✅ 运行时验证：`MessageConvertUtil.convertFromMessageToMsgs()` 正常执行（PartParserRouter 加载成功，不再 NoClassDefFoundError）
- ✅ SDK 全链路类加载正常（JsonRpcTransportWrapper / AgentScopeA2aServer 等）
- ✅ 186 个测试 0 失败 0 错误
- ⏳ 待实测：重启服务验证 message/send 走 SDK 完整链路

**对复用方案的关键影响（已更新）**：

| 链路 | 经过 PartParserRouter | 复用 SDK 可行性 |
|---|---|---|
| `tasks/get` | ❌ 不经过（TaskStore.get 直接构造 Task） | ✅ 安全 |
| `message/send` | ✅ 经过（Executor → MessageConvertUtil） | ✅ **已修复**（补 a2a-client 依赖后） |
| `message/stream` | ✅ 经过 | ✅ 已修复（依赖层面），仍需评估事件格式差异 |

> 这解释了为何当前架构是"A2AController 自实现 message/send/stream + AgentScopeA2aServer 仅用于 agent-card"。
> **修正后的结论：不是 SDK 缺陷导致不能复用，而是缺少 a2a-client 依赖；补齐后 message/send 复用 SDK 的技术障碍已消除**（见第四部分）。

### 1.1 当前 A2A 方法支持

| 方法 | A2AController 处理 | 说明 |
|---|---|---|
| `message/send` | ✅ | 同步消息发送 |
| `message/stream` | ✅ | 流式消息发送 |
| `tasks/get` | ❌ 返回 `-32601 Method not found` | 未实现 |
| `tasks/cancel` | ❌ | 未实现 |
| `tasks/resubscribe` | ❌ | 未实现 |
| `tasks/pushNotificationConfig/*` | ❌ | 未实现 |

### 1.2 获取历史的现有途径

| 途径 | 路径 | 适用场景 |
|---|---|---|
| Debug API | `GET /debug/threads/{sessionId}/history` | 仅内部调试，非标准协议 |
| A2A `tasks/get` | `POST /` `{"method":"tasks/get",...}` | **当前不可用** |

### 1.3 A2A SDK 已有支持（经反编译确认）

A2A SDK (0.3.3) 的 `DefaultRequestHandler` 已实现完整的 `tasks/get` 逻辑：

```
JSONRPCHandler.onGetTask(GetTaskRequest)
  → DefaultRequestHandler.onGetTask(TaskQueryParams, ServerCallContext)
    → TaskStore.get(id)
      → Task(id, contextId, status, artifacts, history, metadata)
```

**关键调研结论（2026-08-10 反编译确认）**：

| 组件 | 能力 | 证据 |
|---|---|---|
| `AgentScopeA2aServer.Builder.taskStore(TaskStore)` | ✅ 支持注入自定义 TaskStore | javap 确认 |
| SDK 默认 TaskStore | `InMemoryTaskStore`（内存，重启丢失） | 反编译确认 |
| `DefaultRequestHandler.onGetTask` | 从 `TaskStore.get(id)` 读 Task | 反编译确认 |
| `JsonRpcTransportWrapper.handleRequest(method, headers, params)` | 直接处理 JSON-RPC 请求 | javap 确认签名 |
| `AgentScopeA2aServer.getTransportWrapper("JSONRPC")` | 获取 wrapper 转发请求 | 公有方法 |
| `AgentScopeA2aServer.postEndpointReady()` | 完成 agentRegistry 注册 | 公有方法 |

**当前项目未接入的现状**：
- `A2AController` 直接处理 `POST /`，只分发 `message/send` 和 `message/stream`，其余返回 `-32601`
- `AgentScopeA2aServer` Bean 在 `A2AServerConfig` 创建（仅用于 agent-card），**从未被请求路由使用**
- `HarnessAgentRunner` 只实现 `AgentRunner`（stream/stop），TaskStore 由 SDK 内部注入

---

## 二、实现方案

### 2.1 方案选型

| 方案 | 说明 | 优缺点 |
|---|---|---|
| **A. 复用 AgentScopeA2aServer（推荐）** | 实现自定义 `MySqlTaskStore` 注入 SDK，A2AController 将 `tasks/get` 请求转发给 `JsonRpcTransportWrapper` | ✅ SDK 标准实现，tasks/cancel/resubscribe/pushNotification 全部免费获得；✅ 消息格式转换由 SDK 处理；✅ 与 AgentScope 官方行为一致；❌ 需实现 TaskStore 接口 |
| B. A2AController 自实现 | 在 A2AController 中新增 `tasks/get` 分支，直接查 MySQL 构造 Task JSON | ✅ 改动小；❌ 需手动解析 state_data 构造 Task；❌ 只支持 tasks/get，其他方法仍需补 |
| C. 接入 SDK 完整链路（重构） | 用 AgentScopeA2aServer 完全替换 A2AController | ✅ 最标准；❌ message/send 响应结构变化，破坏现有客户端兼容 |

**推荐方案 A**：复用 AgentScopeA2aServer + 自定义 TaskStore，仅新增 `tasks/get` 转发，不影响现有 message/send/stream 行为。

### 2.2 整体设计

```
POST / {"method":"tasks/get","params":{"id":"session_id","historyLength":10}}
  │
  ▼ A2AController.handleA2A()
  │ method == "tasks/get"
  ▼ a2aServer.getTransportWrapper("JSONRPC").handleRequest(method, headers, params)
  │
  ▼ JsonRpcTransportWrapper → JSONRPCHandler.onGetTask
  │
  ▼ DefaultRequestHandler.onGetTask(TaskQueryParams)
  │
  ▼ MySqlTaskStore.get(id)            ← 自定义实现
  │
  ├── 1. 查 MySQL: agent_state WHERE session_id = ?
  ├── 2. 解析 state_data JSON → context[] 消息数组
  ├── 3. 构造 A2A Task (id, contextId, status, history)
  └── 4. 返回 Task → SDK 序列化为标准 JSON-RPC Response
```

### 2.3 自定义 MySqlTaskStore

**文件**: `src/main/java/io/agentmanager/framework/service/MySqlTaskStore.java`（新增）

实现 SDK 的 `io.a2a.server.tasks.TaskStore` 接口：

```java
public class MySqlTaskStore implements io.a2a.server.tasks.TaskStore {
    // save: message/send 链路保存 Task（可选，tasks/get 主要依赖 get）
    // get:  查 agent_state 表 → 构造 Task（核心）
    // delete: 删除（可选，暂不实现）
}
```

**get() 核心逻辑**（复用 DebugApiController 已验证的 context[] 解析逻辑）：
1. `SELECT state_data FROM agent_state WHERE session_id = ? ORDER BY item_index DESC LIMIT 1`
2. 解析 `context[]` → 转换 A2A Message 列表（role → USER/AGENT，text 块 → TextPart）
3. 构造 `Task(id, contextId, status, artifacts, history, metadata)`

### 2.4 A2AServerConfig 改造

**文件**: `src/main/java/io/agentmanager/framework/config/A2AServerConfig.java`

```java
var server = AgentScopeA2aServer.builder(runner)
    .agentCard(card)
    .withTransport(transportProps)
    .taskStore(new MySqlTaskStore(dataSource))   // ← 注入自定义 TaskStore
    .build();
server.postEndpointReady();                       // ← 完成 agentRegistry 注册
```

### 2.5 A2AController 转发

**文件**: `src/main/java/io/agentmanager/framework/controller/A2AController.java`

```java
// 注入 AgentScopeA2aServer
public A2AController(AgentRuntimeService agentRuntime, AgentScopeA2aServer a2aServer) { ... }

// handleA2A() 新增分支
if ("tasks/get".equals(method)) {
    var wrapper = a2aServer.getTransportWrapper("JSONRPC");
    return wrapper.handleRequest(method, headers, req);  // SDK 完整处理
}
```

**注意**: 转发时需传递完整 JSON-RPC 请求结构（method/params/id），SDK 会返回标准 `GetTaskResponse`。

### 2.6 Task 响应结构

```json
{
  "jsonrpc": "2.0",
  "id": "request-id",
  "result": {
    "kind": "task",
    "id": "session_id",
    "contextId": "session_id",
    "status": {
      "state": "completed",
      "timestamp": "2026-08-10T16:53:32Z"
    },
    "history": [
      {
        "kind": "message",
        "role": "user",
        "messageId": "uuid",
        "parts": [{"kind": "text", "text": "你好"}]
      },
      {
        "kind": "message",
        "role": "agent",
        "messageId": "uuid",
        "parts": [{"kind": "text", "text": "你好！有什么可以帮助你的？"}]
      }
    ],
    "artifacts": [],
    "metadata": {}
  }
}
```

### 2.7 消息转换规则

| AgentScope 2.0 context[] | A2A Message |
|---|---|
| `role: "USER"` | `role: "user"` |
| `role: "ASSISTANT"` | `role: "agent"` |
| `content: [{type:"text",text:"..."}]` | `parts: [{kind:"text",text:"..."}]` |
| `content: [{type:"thinking",...}]` | 跳过（不暴露内部推理） |
| `content: [{type:"tool_use",...}]` | 跳过（或转为 DataPart，待定） |
| `content: [{type:"tool_result",...}]` | 跳过（或转为 DataPart，待定） |

### 2.8 historyLength 参数

A2A SDK 的 `TaskQueryParams.historyLength` 控制返回消息数量：
- `historyLength = -1` 或未指定：返回全部消息
- `historyLength = 0`：不返回消息（仅返回 Task 元信息）
- `historyLength = N`：返回最后 N 条消息

### 2.9 TaskState 映射

从 `agent_state.state_data` 中推断任务状态：

| 条件 | TaskState |
|---|---|
| `shutdown_interrupted == true` | `CANCELED` |
| `cur_iter > 0`（正在推理中） | `WORKING` |
| 默认（有数据即完成） | `COMPLETED` |
| 无 agent_state 记录 | 返回 `-32001 Task not found` |

---

## 三、实现细节

### 3.1 MySqlTaskStore（新增）

**文件**: `src/main/java/io/agentmanager/framework/service/MySqlTaskStore.java`

实现 SDK 接口 `io.a2a.server.tasks.TaskStore`：

```java
@Service
public class MySqlTaskStore implements TaskStore {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public MySqlTaskStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(Task task) {
        // message/send 链路由 SDK TaskManager 调用。
        // 本实现以 agent_state 为事实来源，save 可 no-op 或仅记录日志。
        // （若未来 message/send 也复用 SDK，则 save 需落库 Task JSON）
    }

    @Override
    public Task get(String taskId) {
        // 核心：从 agent_state 表读取并构造 A2A Task
        // SELECT state_data FROM agent_state WHERE session_id = ? ORDER BY item_index DESC LIMIT 1
        // 解析 state_data JSON → context[] → A2A Message 列表
        // 构造 Task(id, contextId, status, artifacts, history, metadata)
    }

    @Override
    public void delete(String taskId) {
        // 可选：暂不实现（与 agent_state 生命周期解耦）
    }
}
```

**get() 内部逻辑**（复用 DebugApiController 已验证的 context[] 解析）：
1. `SELECT state_data FROM agent_state WHERE session_id = ? ORDER BY item_index DESC LIMIT 1`
2. 解析 `context[]`：USER → `Message.Role.USER`，ASSISTANT → `Message.Role.AGENT`
3. content 块：`text` → `TextPart`，`thinking`/`tool_use`/`tool_result` 跳过
4. `historyLength` 由 SDK 在调用 `TaskStore.get()` 前从 `TaskQueryParams` 提取并截断（SDK 内部逻辑）
5. 构造 `Task(id, contextId, status, List.of(), history, metadata)`

### 3.2 A2AServerConfig 改造

**文件**: `src/main/java/io/agentmanager/framework/config/A2AServerConfig.java`

```java
@Bean
@DependsOn("harnessAgent")
public AgentScopeA2aServer a2aServer(HarnessAgent harnessAgent, OafConfig oafConfig,
                                     DataSource dataSource) {
    var card = new ConfigurableAgentCard.Builder()
        .name(oafConfig.name())
        .description(oafConfig.description() != null ? oafConfig.description() : "")
        .url("http://localhost:8100")
        .build();

    var transportProps = TransportProperties.builder("JSONRPC")
        .path("/")
        .build();

    var runner = new HarnessAgentRunner(harnessAgent);

    var server = AgentScopeA2aServer.builder(runner)
        .agentCard(card)
        .withTransport(transportProps)
        .taskStore(new MySqlTaskStore(dataSource))   // ← 注入自定义 TaskStore
        .build();

    server.postEndpointReady();   // ← 完成 agentRegistry 注册（当前 Bean 未调用）

    log.info("A2A server configured with JSON-RPC transport (HarnessAgentRunner + MySqlTaskStore)");
    return server;
}
```

### 3.3 A2AController 转发改造

**文件**: `src/main/java/io/agentmanager/framework/controller/A2AController.java`

**改动 1**: 注入 `AgentScopeA2aServer`

```java
private final AgentRuntimeService agentRuntime;
private final AgentScopeA2aServer a2aServer;

public A2AController(AgentRuntimeService agentRuntime, AgentScopeA2aServer a2aServer) {
    this.agentRuntime = agentRuntime;
    this.a2aServer = a2aServer;
}
```

**改动 2**: `handleA2A()` 新增 `tasks/get` 转发

```java
if ("message/send".equals(method)) {
    return handleMessageSend(id, req);
}
if ("message/stream".equals(method)) {
    handleStreaming(req, response);
    return null;
}
if ("tasks/get".equals(method)) {
    // 复用 AgentScopeA2aServer（SDK）完整处理 tasks/get
    var wrapper = a2aServer.getTransportWrapper("JSONRPC");
    return wrapper.handleRequest(method, headers, req);
}
return jsonRpcError(id, -32601, "Method not found: " + method);
```

**说明**:
- `headers` 参数已存在于 `handleA2A()` 签名（`@RequestHeader HttpHeaders headers`），直接透传
- SDK 返回标准 `GetTaskResponse`（含 `result: Task` 或 `error`）
- 转发仅限 `tasks/get`，`message/send`/`message/stream` 保持现状，不破坏现有客户端

### 3.4 错误码

SDK 自动处理标准错误码（与 A2A 规范一致）：

| 场景 | code | 来源 |
|---|---|---|
| Task 未找到 | -32001 | SDK `TaskStore.get()` 返回 null 时 |
| 缺少 id | -32602 | SDK 参数校验 |
| 内部异常 | -32603 | SDK 包装 |

---

## 四、message/send 复用 SDK 方案

### 4.1 SDK message/send 完整链路（经反编译确认）

```
POST / message/send
  → JSONRPCHandler.onMessageSend
    → DefaultRequestHandler.onMessageSend(MessageSendParams)
      → initMessageSend() → RequestContext(taskId, contextId)
      → queueManager.createOrTap(taskId)
      → TaskManager(taskId, contextId, taskStore, message)   ← 使用注入的 TaskStore
      → ResultAggregator
      → AgentExecutor.execute(RequestContext, EventQueue)     ← 异步执行
        → AgentScopeAgentExecutor.execute()
          → AgentRunner.stream(List<Msg>, AgentRequestOptions)  ← 我们的 HarnessAgentRunner 已实现
            → HarnessAgent.streamEvents(List<Msg>, RuntimeContext) → AgentScope 事件流
          → 事件转换为 A2A 事件（statusUpdate / message / artifact）
          → TaskManager.saveTaskEvent() → taskStore.save(Task)  ← 写回 TaskStore
      → 返回 EventKind（Message 或 Task，取决于 blocking）
```

**关键发现**：
1. SDK message/send **完全兼容现有 `HarnessAgentRunner`**（`stream(List<Msg>, AgentRequestOptions)` 已实现，内部调用 HarnessAgent）
2. SDK 通过 `TaskManager` 自动 `save()` Task 到注入的 TaskStore —— 与 tasks/get 天然衔接
3. `AgentRequestOptions` 仅含 taskId/sessionId/userId 三字段，HarnessAgentRunner 已处理

### 4.2 复用 message/send 的差异分析

**阻塞项已消除**：原 `PartParserRouter` 缺失问题已通过 pom.xml 添加 a2a-client 依赖修复（见 1.0 节，已实施并验证）。剩余差异为行为/结构层面的适配。

**SDK 关键行为（反编译确认）**：

| 项 | 详情 |
|---|---|
| blocking 配置 | `MessageSendConfiguration.blocking()` 为 true 时同步返回完整结果；false 时异步返回任务状态 |
| userId 来源 | **`message.metadata.userId`**（`AgentScopeAgentExecutor.getUserId(Message)` 从 message metadata 读取） |
| sessionId 来源 | **`message.metadata.sessionId`**（同样从 message metadata 读取） |
| 返回结构 | 标准 A2A `EventKind`：blocking=true 返回 Message，否则返回 Task |

| 维度 | 当前实现 (A2AController) | SDK 复用 |
|---|---|---|
| 执行方式 | `agentRuntime.invoke()` 同步阻塞 | `AgentScopeAgentExecutor` 异步 + blocking 配置 |
| 返回结构 | 简化 Task `{id, status:"completed", result:{message}}` | 标准 `EventKind`（Message 或 Task） |
| 事件转换 | 无（仅文本） | SDK 将 AgentScope 事件 → A2A 事件（thinking/工具调用等） |
| Task 持久化 | 依赖 AgentScope 自身 state 存储 | TaskManager 额外写 TaskStore |
| userId 传递 | `resolveUserId(params)` 兼容顶层 + metadata | **`message.metadata.userId`**（需在转发前把 params.userId 写入 message.metadata） |
| sessionId | `resolveThreadId(params)` 兼容多种格式 | **`message.metadata.sessionId`**（需转换） |
| 多租户 | ✅ 显式 userId | ✅ 通过 message.metadata 传递 |

### 4.3 复用 message/send 的改造方案（详细）

#### 改动 1：A2AController 转发 + 参数转换

**文件**: `src/main/java/io/agentmanager/framework/controller/A2AController.java`

```java
// handleA2A() 中 message/send 分支改为转发 SDK
if ("message/send".equals(method)) {
    return handleMessageSendViaSdk(id, req);
}

private ResponseEntity<Object> handleMessageSendViaSdk(Object id, Map<String, Object> req) {
    // 1. 参数兼容转换：将顶层 userId/sessionId 及旧格式 metadata 写入 message.metadata
    //    （SDK 从 message.metadata.userId / message.metadata.sessionId 读取）
    var params = (Map<String, Object>) req.get("params");
    var message = (Map<String, Object>) params.get("message");
    // 合并 metadata: 顶层 userId → message.metadata.userId
    //            顶层 sessionId → message.metadata.sessionId
    //            旧格式 params.metadata.thread_id → message.metadata.sessionId

    // 2. 设置 blocking=true 保持同步行为（与现有 message/send 一致）
    params.put("configuration", Map.of("blocking", true));

    // 3. 转发 SDK
    var wrapper = a2aServer.getTransportWrapper("JSONRPC");
    return wrapper.handleRequest("message/send", headers, req);
}
```

#### 改动 2：MySqlTaskStore.save() — 复用 agent_state，无需新建表（2026-08-10 调研确认）

**核心洞察：A2A 与 Channel 的消息持久化已由 AgentScope 内部自动完成，MySqlTaskStore 只需读取，无需重复保存。**

**前置认知：三种模式底层全部是 HarnessAgent（2026-08-10 反编译确认）**

| 模式 | 入口 | 底层 Agent | 绑定方式 |
|---|---|---|---|
| **Channel** | `ChatUiChannel.sendStream` | **HarnessAgent** | `agent.channel(ChatUiChannel.create())`（ChannelConfig.java:10，agent = HarnessAgent Bean） |
| **A2A** | `AgentScopeA2aServer` + `HarnessAgentRunner` | **HarnessAgent** | `HarnessAgentRunner` 实现 SDK `AgentRunner` 接口，包装 HarnessAgent |
| **SDK 默认 A2A**（未采用） | `builder(ReActAgent.Builder)` | ReActAgent | SDK 内置 `ReActAgentWithBuilderRunner`，仅限无 Harness 场景 |

关键点：
- `AgentScopeA2aServer` 提供两个 builder 重载：`builder(ReActAgent.Builder)`（SDK 默认 runner）和 `builder(AgentRunner)`（自定义 runner）——**SDK 通过 `AgentRunner` 接口解耦，不强制 ReActAgent**
- 本项目 `A2AServerConfig` 使用 `builder(runner)` 方式，runner = `HarnessAgentRunner(harnessAgent)`，**A2A 模式用的就是 HarnessAgent**
- HarnessAgent **不继承 ReActAgent**（`implements io.agentscope.core.agent.Agent`），但提供工作区/记忆/技能/MCP/多租户等 ReActAgent 不具备的能力，因此本项目选择自定义 runner 而非 SDK 默认 runner
- Channel 与 A2A 是**同一个 HarnessAgent Bean 实例**、同一条执行路径、同一个持久化来源

**现有持久化链路（反编译确认）**：

```
A2A 模式:  A2AController/AgentScopeAgentExecutor → HarnessAgentRunner.stream()
             → HarnessAgent.streamEvents(List<Msg>, RuntimeContext)
Channel:   ChatUiChannel.sendStream → HarnessGateway.runStream
             → HarnessAgent.streamEvents(List<Msg>, RuntimeContext)
                            │
                            ▼ 同一路径
              ReActAgent 内部自动 saveAgentState(sessionId, userId)
                            │
                            ▼
              MysqlAgentStateStore.save() → agent_state 表 (state_data 含 context[])
```

**结论**：
- A2A 与 Channel **共享同一持久化路径**（都是 `HarnessAgent.streamEvents` → `ReActAgent.saveAgentState`）
- message/send 走 SDK 后，`AgentScopeAgentExecutor` 最终也调用 `HarnessAgentRunner.stream()` → 同样自动写 agent_state
- **因此 Task 的消息历史（context[]）已持久化，MySqlTaskStore.get() 直接读 agent_state 即可，save() 可为 no-op**

**agent_state 与 A2A Task 字段映射**：

| A2A Task 字段 | agent_state 来源 | 完整性 |
|---|---|---|
| `id` / `contextId` | `session_id` | ✅ 完整 |
| `status.state` | `shutdown_interrupted` / `cur_iter` 推断 | ⚠️ 近似（见下） |
| `status.timestamp` | 表 `updated_at`（可在 get() 中查询） | ✅ 可补 |
| `artifacts` | 无（A2A 特有，agent_state 不存） | ⚠️ 返回空列表 |
| `history` | `context[]`（消息完整，含 USER/ASSISTANT + text 块） | ✅ 完整 |
| `metadata` | `user_id` 等 | ✅ 可填 |

**status.state 推断规则**（与 2.9 节一致）：
| 条件 | TaskState |
|---|---|
| `shutdown_interrupted == true` | `CANCELED` |
| `cur_iter > 0`（推理中） | `WORKING` |
| 默认 | `COMPLETED` |

**save() 实现（no-op + 日志）**：

```java
@Override
public void save(Task task) {
    // 消息历史已由 AgentScope ReActAgent.saveAgentState() 自动写入 agent_state，
    // MySqlTaskStore 以 agent_state 为唯一事实来源，save 无需重复落库。
    // 仅记录日志便于排查（artifacts/status 等 A2A 特有字段暂不持久化）。
    log.debug("TaskStore.save skipped (agent_state is source of truth): {}", task.getId());
}
```

**为什么不需要 task_store 表**：
1. agent_state 已存全部消息历史（context[]），Task 的 history 可直接还原
2. 单独建表会导致双写、数据一致性风险（AgentScope 状态与 A2A Task 快照可能不一致）
3. 唯一损失：`artifacts`（工具产物等 A2A 特有字段）不持久化，但当前业务未使用该字段，返回空列表即可

#### 改动 3：MySqlTaskStore.get() 直接读 agent_state（唯一数据源）

```java
@Override
public Task get(String taskId) {
    // 1. 查 agent_state 表（所有模式共用的事实来源）
    //    SELECT state_data, updated_at FROM agent_state
    //    WHERE session_id = ? ORDER BY item_index DESC LIMIT 1
    // 2. 解析 state_data JSON:
    //    - context[] → A2A Message 列表（USER→USER, ASSISTANT→AGENT, text 块→TextPart）
    //    - shutdown_interrupted/cur_iter → TaskStatus.state
    //    - updated_at → TaskStatus.timestamp
    // 3. 未命中返回 null（SDK 转 -32001）
}
```

### 4.4 结论与建议（更新）

| 场景 | 建议 |
|---|---|
| **tasks/get** | ✅ 复用 AgentScopeA2aServer（推荐，见方案 A）—— 不经过 PartParserRouter，安全 |
| **message/send 同步** | ✅ 可复用。依赖层障碍已修复（a2a-client）；需实现 4.3 的参数转换 + TaskStore.save；**建议同步改造**（获得标准 A2A 返回 + Task 持久化，与 tasks/get 数据衔接） |
| **message/stream** | ❌ 暂不复用。依赖已修复，但 streaming 事件格式与现有前端渲染（token/thinking/tool_call_delta）差异大，改造成本高，维持自实现 |

**推荐路径（分两步）**：
1. **第一步**：`tasks/get` + `message/send` 复用 SDK（本方案扩展）—— message/send 转发后返回标准 A2A，同时 Task 落库使 tasks/get 立即可用
2. **第二步**（可选）：`message/stream` 保持自实现（前端已深度耦合自定义事件格式），待前端重构后再评估

### 4.5 前端适配（message/send 复用后）

**文件**: `src/main/resources/static/debug/js/api.js`

```javascript
// 现有 sendA2A 解析 result.message（简化 Task）
// SDK 返回标准 Message: {kind:"message", role:"agent", messageId, parts:[{kind:"text",text}]}
// 适配: 提取 parts 中 text 拼接为文本
sendA2A: async (text, { userId, sessionId } = {}) => {
    const resp = await fetch(BASE + '/', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            jsonrpc: '2.0',
            method: 'message/send',
            params: {
                message: { role: 'user', parts: [{ text }],
                           metadata: { userId, sessionId } },  // ← SDK 从 metadata 读
                configuration: { blocking: true }
            },
            id: 'debug-' + Date.now()
        })
    });
    const data = await resp.json();
    // 兼容 SDK 返回: result 可能为 Task 或 Message
    const result = data.result;
    const text = result.parts
        ? result.parts.filter(p => p.kind === 'text').map(p => p.text).join('')
        : (result.result?.message?.parts || []).filter(p => p.kind === 'text').map(p => p.text).join('');
    return text;
}
```

**注意**：`chat.js sendA2AStream`（message/stream）维持现状（自实现），不受影响。

---

## 五、开发镜像与 Debug 页面影响分析

### 5.1 开发镜像（Dockerfile.dev / 离线环境）是否需要更新

**结论：需要重新构建离线开发镜像（一次），但无需改 Dockerfile 本身。**

| 项 | 分析 | 结论 |
|---|---|---|
| `Dockerfile.dev` | 通过 `COPY pom.xml` + `mvn clean install` 预下载全部依赖，**依赖清单来自 pom.xml**——新增 a2a-client 后，镜像构建时 Maven 会自动下载 `agentscope-extensions-a2a-client-2.0.0` 及其传递依赖到 `/root/.m2/repository` | ✅ 无需改文件 |
| 离线镜像缓存 | 现有已导出的 `agent-framework:java-dev` 镜像的 .m2 缓存**不含 a2a-client**，内网离线构建会失败（找不到依赖） | ⚠️ 必须重新 `make docker-build-dev` + `docker-save` 导出 |
| 内网 Nexus | 若内网走 Nexus 私服，需确认 Nexus 已同步 `agentscope-extensions-a2a-client` 2.0.0（`offline-settings.xml` 无需改，mirror 配置已存在） | ⚠️ 需检查 Nexus 同步状态 |
| `docs/offline-dev-image.md` | 文档中"离线测试（68 用例）"等说明与依赖无关 | ✅ 无需改（测试数已更新为 186 在 AGENTS.md） |

**动作项**：
```bash
# 1. 有网机器重新构建开发镜像（自动缓存 a2a-client）
make docker-build-dev
# 2. 重新导出传输到内网
make docker-save
# 3. 内网验证离线构建
make offline   # 进入容器
mvn -o package # 应成功（含 a2a-client 依赖）
```

### 5.2 Debug 页面是否需要更新

**结论：tasks/get 复用 SDK 无需前端改动；message/send 复用 SDK 需适配 `api.js sendA2A`（方案见 4.5），`message/stream` 维持自实现不受影响。**

#### 场景一：`tasks/get` 复用 SDK（无前端改动）

| Debug 页面功能 | 当前数据源 | tasks/get 影响 | 是否需更新 |
|---|---|---|---|
| Thread 列表 | `GET /debug/threads`（agent_state 去重） | 不变 | ❌ |
| Thread 历史加载 | `GET /debug/threads/{id}/history`（chat.js `loadThreadHistory`） | 不变（仍走 Debug API） | ❌ |
| LLM Calls 弹窗 | `GET /debug/threads/{id}/llm-calls` | 不变 | ❌ |
| A2A 同步发送 `sendA2A` | `message/send`（自实现） | 不变 | ❌ |
| A2A 流式 `sendA2AStream` | `message/stream`（自实现） | 不变 | ❌ |
| Channel 模式 | `/chat/stream` | 不变 | ❌ |

**结论**：`tasks/get` 是纯新增能力（供外部 A2A 客户端使用），Debug 页面不消费它，**无需任何前端改动**。

#### 场景二：`message/send` 复用 SDK（本方案扩展，前端需小幅适配）

| 前端位置 | 当前解析 | SDK 返回差异 | 需适配 |
|---|---|---|---|
| `api.js sendA2A` | 解析 `result` 简化 Task | SDK 返回标准 `EventKind`（Message/Task），结构不同 | ⚠️ 需改（见 4.5） |
| `chat.js sendA2AStream`（message/stream） | 解析自定义 SSE 格式（token/tool_call） | 维持自实现，不受影响 | ❌ |
| `chat.js loadThreadHistory` | Debug API（不变） | — | ❌ |

**结论**：message/send 复用 SDK 仅需改造 `api.js sendA2A`（约 20 行，见 4.5），`message/stream` 与历史加载均不受影响。

### 5.3 影响矩阵总结

| 变更项 | 开发镜像 | Debug 页面 |
|---|---|---|
| pom.xml 添加 a2a-client（已实施） | ⚠️ 需重建镜像（依赖缓存） | ❌ 无影响 |
| tasks/get 复用 SDK（本方案） | ❌ 无影响 | ❌ 无影响 |
| message/send 复用 SDK（本方案扩展） | ❌ 无影响 | ⚠️ `api.js sendA2A` 需适配（见 4.5，约 20 行） |
| message/stream 维持自实现 | ❌ 无影响 | ❌ 无影响 |

---

## 六、实施计划

### 6.1 阶段划分

| 阶段 | 内容 | 工作量 | 状态 |
|---|---|---|---|
| 阶段 0 | pom.xml 添加 a2a-client 依赖 | 0.1 天 | ✅ 已完成 |
| 阶段一 | 新增 `MySqlTaskStore`（get 读 agent_state + save 落库） | 0.5 天 | ⏳ |
| 阶段二 | `A2AServerConfig` 注入 TaskStore + 调 `postEndpointReady()` | 0.25 天 | ⏳ |
| 阶段三 | `A2AController` 转发 `tasks/get` + `message/send` 到 SDK（含参数转换） | 0.5 天 | ⏳ |
| 阶段四 | 前端 `api.js sendA2A` 适配 SDK 返回结构 | 0.25 天 | ⏳ |
| 阶段五 | 单元测试 + 集成测试 + mvn test | 0.5 天 | ⏳ |

**总工作量**: 约 2 天（含前端适配）

### 6.2 文件变更清单

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `pom.xml` | 修改 | **新增 `agentscope-extensions-a2a-client` 依赖**（修复 PartParserRouter NoClassDefFoundError，✅ 已实施） |
| `service/MySqlTaskStore.java` | 新增 | 实现 `TaskStore` 接口（get 读 agent_state，save no-op——复用 AgentScope 既有持久化，无需新表） |
| `config/A2AServerConfig.java` | 修改 | 注入 TaskStore + 调 `postEndpointReady()` |
| `controller/A2AController.java` | 修改 | 新增 `tasks/get` 转发 + `message/send` 转发（含 userId/sessionId 参数转换） |
| `static/debug/js/api.js` | 修改 | `sendA2A` 适配 SDK 标准返回结构 |
| `test/.../A2AControllerTest.java` | 修改 | 新增 tasks/get + message/send 转发测试 |
| `test/.../MySqlTaskStoreTest.java` | 新增 | TaskStore.get/save 测试 |

> **注**：pom.xml 变更已先行落地（2026-08-10），其余待实施。

---

## 七、测试验证方案

### 7.1 单元测试

**MySqlTaskStoreTest**：

```java
@Test
void getShouldBuildTaskFromAgentState() throws Exception {
    // mock agent_state 返回含 context 的 state_data
    // 验证 Task: id / status / history 数组 / history[0].role == USER
}

@Test
void getShouldRespectHistoryLength() throws Exception {
    // historyLength=1 → 只返回最后 1 条消息
}

@Test
void getShouldReturnNullWhenNotFound() throws Exception {
    // 无 agent_state 记录 → 返回 null（SDK 转 -32001）
}
```

**A2AControllerTest**：

```java
@Test
void tasksGetShouldDelegateToA2aServer() throws Exception {
    // mock AgentScopeA2aServer.getTransportWrapper() 返回 mock wrapper
    // 验证 wrapper.handleRequest 被调用，且参数透传
}

@Test
void messageSendShouldDelegateToA2aServer() throws Exception {
    // POST / message/send → 验证转发到 wrapper
    // 验证 params.message.metadata.userId 被正确写入（参数转换）
    // 验证 configuration.blocking == true
}

@Test
void messageSendShouldMergeLegacyMetadata() throws Exception {
    // 旧格式 params.metadata.thread_id / params.metadata.userId
    // → 应转换为 message.metadata.sessionId / message.metadata.userId 后转发
}
```

### 7.2 集成测试

```bash
# 1. tasks/get 查询已知 session 的历史
curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tasks/get",
    "params": {"id": "debug:debug-debug-agent:c3ae292d-bae3-4b81-9a87-b9f72f89faef", "historyLength": 5},
    "id": "test-1"
  }'

# 验证：
# - result.id == 请求的 id
# - result.history 为消息数组（A2A Message 格式）
# - history[0].role == "user"
# - history[0].parts[0].kind == "text"
# - historyLength=5 时 history 长度 <= 5

# 2. tasks/get 查询不存在的 session
curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tasks/get","params":{"id":"nonexistent"},"id":"test-2"}'
# 验证 error.code == -32001

# 3. message/send 走 SDK（blocking 同步）
curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "message/send",
    "params": {
      "message": {"role": "user", "parts": [{"text": "你好"}],
                  "metadata": {"userId": "debug-user", "sessionId": "a2a-sdk-test-1"}},
      "configuration": {"blocking": true}
    },
    "id": "test-3"
  }'
# 验证：
# - 返回标准 A2A Message（kind=message / role=agent / parts[0].kind=text）
# - 耗时正常（同步执行完成）

# 4. message/send 后立即 tasks/get（验证 Task 落库衔接）
curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tasks/get","params":{"id":"a2a-sdk-test-1"},"id":"test-4"}'
# 验证：history 包含刚发送的 user 消息与 agent 回复
```

### 7.3 边界场景

| 场景 | 验证点 |
|---|---|
| historyLength = 0 | history 数组为空，Task 元信息正常 |
| historyLength = -1 | 返回全部消息 |
| historyLength > 实际消息数 | 返回全部消息（不报错） |
| state_data 含 thinking 块 | history 中不含 thinking 块 |
| state_data 仅有 USER 消息 | 只返回 USER 消息 |
| 无 state_data 记录 | SDK 返回 -32001 |
| message/send 后立即 tasks/get | 新消息出现在 history 中（save 落库生效） |
| message/send 带旧格式 metadata | 正确转换为 message.metadata.sessionId |
| message/send 不带 sessionId | SDK 自动生成 taskId（与自实现 UUID 行为一致） |
| message/send blocking=false | 返回 Task 而非 Message（异步模式） |
| message/stream | 维持自实现，事件格式不变（回归验证） |

### 7.4 回归测试

```bash
cd /root/agent-manager/agent-framework
mvn test
# 确保现有 186 个测试全部通过（message/stream、Channel 等行为不变）
```

---

## 八、相关文档

| 文档 | 说明 |
|---|---|
| [A2A 协议规范](https://google.github.io/A2A/#/documentation) | tasks/get 标准定义 |
| [A2A SDK RequestHandler](io/a2a/server/requesthandlers/RequestHandler) | SDK 接口定义 |
| [DebugApiController threadHistory](../controller/DebugApiController.java:162) | 现有历史查询实现（context[] 解析参照） |
| [event-system-upgrade-plan.md](event-system-upgrade-plan.md) | 事件系统升级方案 |
