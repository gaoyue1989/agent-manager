# A2A 协议合规性改进方案

## 1. 现状分析

### 1.1 当前实现覆盖度

| A2A v1.0.0 方法 | 实现状态 | 文件位置 |
|---|---|---|
| `message/send` | ✅ 已实现 | `A2AController.java:51` |
| `message/stream` | ✅ 已实现 | `A2AController.java:54` |
| `tasks/get` | ❌ 未实现 | — |
| `tasks/list` | ❌ 未实现 | — |
| `tasks/cancel` | ❌ 未实现 | — |
| `tasks/subscribe` | ❌ 未实现 | — |
| Push Notification (4个方法) | ❌ 未实现 | — |
| `agentExtendedCard` | ❌ 未实现 | — |

**覆盖率: 2/11 (18%)**

### 1.2 关键协议差异

#### (a) Task 响应结构不符合规范

当前实现 (`A2AController.java:152-156`):
```java
var task = Map.of(
    "id", taskId,
    "status", "completed",        // ❌ 字符串，应为 TaskStatus 对象
    "result", Map.of("message", responseMessage)
);
```

A2A v1.0.0 要求:
```json
{
  "id": "task-id",
  "contextId": "ctx-id",
  "status": {
    "state": "TASK_STATE_COMPLETED",
    "timestamp": "2026-01-01T00:00:00Z",
    "message": { "role": "agent", "parts": [{"type": "text", "text": "..."}] }
  },
  "artifacts": [],
  "metadata": {}
}
```

#### (b) SSE 流式事件格式不符合规范

当前实现 (`AgentRuntimeService.java:127-158`):
```java
Map.of("type", "token", "token", delta, "task_id", tid)          // ❌ 自定义格式
Map.of("type", "task_update", "id", tid, "state", "working")     // ❌ 自定义格式
Map.of("type", "done")                                           // ❌ 自定义结束标记
```

A2A v1.0.0 要求 (JSON-RPC notification):
```
data: {"jsonrpc":"2.0","method":"tasks/statusUpdate","params":{"id":"...","status":{"state":"TASK_STATE_WORKING"}}}
data: {"jsonrpc":"2.0","method":"tasks/artifactUpdate","params":{"id":"...","artifact":{"parts":[{"type":"text","text":"..."}]}}}
data: {"jsonrpc":"2.0","method":"tasks/statusUpdate","params":{"id":"...","status":{"state":"TASK_STATE_COMPLETED"}}}
```

#### (c) Agent Card 缺少必需字段

当前实现 (`AgentCardController.java:38-62`) 缺少:
- `interfaces` 字段（声明支持的协议绑定）
- `defaultInputModes`/`defaultOutputModes` 作为 `AgentInterface` 数组
- `securitySchemes` 格式不完整

#### (d) 缺少多轮对话支持

A2A 要求通过 `contextId` 实现多轮对话关联，当前实现完全忽略 `contextId`。

---

## 2. 改进方案

### 2.1 方案选择：利用 AgentScope A2A Server 扩展

**选择理由**:
- `pom.xml` 已引入 `agentscope-extensions-a2a-server:2.0.0`
- `A2AServerConfig` 已创建 `AgentScopeA2aServer` Bean
- AgentScope A2A Server 扩展内置完整的 A2A v1.0.0 支持
- 最小化自定义代码量，降低维护成本

### 2.2 实施步骤

#### 第一步：重构 A2AServerConfig（完善配置）

**文件**: `config/A2AServerConfig.java`

**变更内容**:
- 使用 `ConfigurableAgentCard.Builder` 构建完整的 Agent Card
- 配置 `interfaces` 声明 JSON-RPC 协议绑定
- 配置 `capabilities` 包含 streaming/stateTransitionHistory
- 配置 `securitySchemes` 声明认证方式
- 配置 `defaultInputModes`/`defaultOutputModes`

**目标结构**:
```java
var card = new ConfigurableAgentCard.Builder()
    .name(oafConfig.name())
    .description(oafConfig.description())
    .url("http://localhost:8100")
    .version(oafConfig.version())
    .provider(new AgentProvider(oafConfig.vendorKey()))
    .capabilities(AgentCapabilities.builder()
        .streaming(true)
        .pushNotifications(false)
        .stateTransitionHistory(true)
        .build())
    .defaultInputModes(List.of("text/plain"))
    .defaultOutputModes(List.of("text/plain", "a2ui/v0.8"))
    .interfaces(List.of(
        AgentInterface.builder().protocol("JSONRPC").url("/").build()
    ))
    .skills(mapSkills(oafConfig.skills()))
    .securitySchemes(Map.of(
        "bearer", new HTTPAuthSecurityScheme("bearer")
    ))
    .build();
```

#### 第二步：删除自定义 A2AController

**文件**: `controller/A2AController.java` → 删除

**理由**: AgentScope A2A Server 扩展自动处理 `POST /` 上的 JSON-RPC 请求，包括:
- `message/send` — 同步消息发送
- `message/stream` — 流式消息发送
- `tasks/get` — 查询任务状态
- `tasks/list` — 列出任务
- `tasks/cancel` — 取消任务
- `tasks/subscribe` — 订阅任务更新

#### 第三步：删除自定义 AgentCardController

**文件**: `controller/AgentCardController.java` → 删除

**理由**: AgentScope A2A Server 扩展自动提供 `GET /.well-known/agent-card.json` 端点。

#### 第四步：保留并适配辅助端点

**保留的端点**:
- `GET /` (InfoController) — 改为返回服务信息，不再处理 A2A JSON-RPC
- `GET /health` (HealthController) — 健康检查
- `GET /debug` (DebugController) — 调试页面
- `GET /skills` (ToolController) — 技能列表
- `GET /mcp` (ToolController) — MCP 服务器列表
- `GET /tools` (ToolController) — 工具列表
- `GET /system-prompt` (InfoController) — 系统提示词
- `GET /threads` (ThreadController) — Thread 列表
- `POST /chat/stream` (StreamController) — SSE 流式对话（非 A2A）

**适配要点**:
- `InfoController` 中 `protocols` 字段保持不变，声明支持的协议版本
- `ThreadController` 改为从 AgentScope 的 TaskStore 查询任务列表

#### 第五步：适配 AgentRuntimeService

**文件**: `service/AgentRuntimeService.java`

**变更内容**:
- `invoke()` 方法: 返回值适配 AgentScope 的 Task 结构
- `invokeStream()` 方法: 返回值适配 AgentScope 的 SSE 事件格式
- 保留 `buildSystemPrompt()` 等辅助方法

**关键变更**:
```java
// 当前: 自定义 Map 返回
public Map<String, Object> invoke(String message, String threadId) { ... }

// 改为: 委托给 AgentScope ReActAgent，由 A2A Server 处理响应格式
// AgentScope A2A Server 自动将 agent.call() 结果转换为 A2A Task 格式
```

### 2.3 依赖确认

**当前 pom.xml 已包含**:
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-server</artifactId>
    <version>2.0.0</version>
</dependency>
```

**可能需要新增** (待确认 AgentScope A2A Server 扩展的依赖):
- 无额外依赖（A2A Server 扩展已包含所有必需的 A2A 数据模型）

### 2.4 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| AgentScope A2A Server 扩展不支持某些方法 | 中 | 先验证扩展的能力，不足部分自行补充 |
| SSE 事件格式与现有前端不兼容 | 低 | 前端同时支持新旧格式，渐进式迁移 |
| 删除自定义 Controller 后端点路径变化 | 低 | AgentScope A2A Server 使用相同路径 `/` |

### 2.5 验证方案

1. **单元测试**: 使用 AgentScope 的测试工具验证 A2A Server 配置
2. **集成测试**: 使用 A2A Python SDK 编写端到端测试
   ```python
   from a2a.client import A2AClient
   client = A2AClient(url="http://localhost:8100")
   
   # 测试 message/send
   task = client.send_message(Message(parts=[Part(text="Hello")]))
   assert task.status.state == "TASK_STATE_COMPLETED"
   
   # 测试 message/stream
   events = client.send_message_streaming(Message(parts=[Part(text="Hello")]))
   for event in events:
       assert event.jsonrpc == "2.0"
   ```
3. **互操作性测试**: 使用 A2A 官方测试套件验证合规性

---

## 3. 验证用例

### 3.1 测试 LLM 配置

```yaml
agent:
  llm:
    api-key: ${LLM_API_KEY}
    model-id: LongCat-2.0
    base-url: https://api.longcat.chat/openai/v1
    provider: openai
    temperature: 0.2
    max-tokens: 50
```

### 3.2 验证用例

#### TC-A2A-01: Agent Card 字段完整性

```java
@Test
void testAgentCardCompleteness() {
    var card = agentCardController.agentCard();

    assertThat(card.get("name")).isNotNull();
    assertThat(card.get("description")).isNotNull();
    assertThat(card.get("version")).isNotNull();
    assertThat(card.get("capabilities")).isNotNull();
    assertThat(card.get("skills")).isNotNull();
    assertThat(card.get("securitySchemes")).isNotNull();

    var capabilities = (Map<String, Object>) card.get("capabilities");
    assertThat(capabilities.get("streaming")).isEqualTo(true);
    assertThat(capabilities.get("stateTransitionHistory")).isEqualTo(true);
}
```

#### TC-A2A-02: message/send JSON-RPC 格式

```java
@Test
void testMessageSendJsonRpcFormat() {
    var request = Map.of(
        "jsonrpc", "2.0",
        "id", "test-1",
        "method", "message/send",
        "params", Map.of(
            "message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("type", "text", "text", "请只回复 welcome"))
            )
        )
    );

    var response = a2aController.handleA2A(
        mapper.writeValueAsString(request), HttpHeaders.EMPTY, mockResponse);

    var respMap = (Map<String, Object>) response;
    assertThat(respMap.get("jsonrpc")).isEqualTo("2.0");
    assertThat(respMap.get("id")).isEqualTo("test-1");
    assertThat(respMap.get("result")).isNotNull();
}
```

#### TC-A2A-03: message/send Task 结构合规

```java
@Test
void testMessageSendTaskStructure() {
    var request = Map.of(
        "jsonrpc", "2.0",
        "id", "test-2",
        "method", "message/send",
        "params", Map.of(
            "message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("type", "text", "text", "请只回复 welcome"))
            )
        )
    );

    var response = a2aController.handleA2A(
        mapper.writeValueAsString(request), HttpHeaders.EMPTY, mockResponse);

    var respMap = (Map<String, Object>) response;
    var result = (Map<String, Object>) respMap.get("result");

    // A2A Task 必须有 id 和 status
    assertThat(result.get("id")).isNotNull();
    assertThat(result.get("status")).isNotNull();

    // status 应为对象（非字符串）
    var status = result.get("status");
    assertThat(status).isInstanceOf(Map.class);
    var statusMap = (Map<String, Object>) status;
    assertThat(statusMap.get("state")).isNotNull();
}
```

#### TC-A2A-04: message/stream SSE 格式

```java
@Test
void testMessageStreamSseFormat() {
    var request = Map.of(
        "jsonrpc", "2.0",
        "id", "test-3",
        "method", "message/stream",
        "params", Map.of(
            "message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("type", "text", "text", "请只回复 welcome"))
            )
        )
    );

    // 验证 SSE 事件格式
    a2aController.handleA2A(
        mapper.writeValueAsString(request), HttpHeaders.EMPTY, mockResponse);

    // 验证响应 Content-Type 为 text/event-stream
    assertThat(mockResponse.getContentType()).contains("text/event-stream");
}
```

#### TC-A2A-05: 错误处理 - 缺少 method

```java
@Test
void testErrorHandlingMissingMethod() {
    var request = Map.of("jsonrpc", "2.0", "id", "test-4");

    var response = a2aController.handleA2A(
        mapper.writeValueAsString(request), HttpHeaders.EMPTY, mockResponse);

    var respMap = (Map<String, Object>) response;
    assertThat(respMap.get("error")).isNotNull();
    var error = (Map<String, Object>) respMap.get("error");
    assertThat(error.get("code")).isEqualTo(-32600);
}
```

#### TC-A2A-06: 错误处理 - 未知方法

```java
@Test
void testErrorHandlingUnknownMethod() {
    var request = Map.of(
        "jsonrpc", "2.0", "id", "test-5",
        "method", "unknown/method", "params", Map.of());

    var response = a2aController.handleA2A(
        mapper.writeValueAsString(request), HttpHeaders.EMPTY, mockResponse);

    var respMap = (Map<String, Object>) response;
    assertThat(respMap.get("error")).isNotNull();
    var error = (Map<String, Object>) respMap.get("error");
    assertThat(error.get("code")).isEqualTo(-32601);
}
```

#### TC-A2A-07: 多轮对话 contextId 支持

```java
@Test
void testMultiTurnWithContextId() {
    // 第一轮
    var request1 = Map.of(
        "jsonrpc", "2.0", "id", "test-6a",
        "method", "message/send",
        "params", Map.of(
            "message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("type", "text", "text", "记住：我是 Alice"))
            )
        )
    );
    var resp1 = a2aController.handleA2A(
        mapper.writeValueAsString(request1), HttpHeaders.EMPTY, mockResponse);

    // 第二轮（使用相同 contextId）
    var request2 = Map.of(
        "jsonrpc", "2.0", "id", "test-6b",
        "method", "message/send",
        "params", Map.of(
            "message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("type", "text", "text", "我叫什么名字？"))
            )
        )
    );
    var resp2 = a2aController.handleA2A(
        mapper.writeValueAsString(request2), HttpHeaders.EMPTY, mockResponse);

    var result2 = (Map<String, Object>) ((Map<String, Object>) resp2).get("result");
    var status2 = (Map<String, Object>) result2.get("status");
    var message2 = (Map<String, Object>) status2.get("message");
    var parts2 = (List<Map<String, Object>>) message2.get("parts");
    var text2 = parts2.get(0).get("text").toString();

    assertThat(text2).containsIgnoringCase("Alice");
}
```

#### TC-A2A-08: LLM 连通性验证

```bash
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'
```

#### TC-A2A-09: Agent Card HTTP 端点

```bash
# 获取 Agent Card
curl -s http://localhost:8100/.well-known/agent-card.json | jq .

# 验证必要字段
curl -s http://localhost:8100/.well-known/agent-card.json | jq '.name, .version, .capabilities.streaming, .securitySchemes'
```
