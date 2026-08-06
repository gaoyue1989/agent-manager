# Agent Framework — 测试文档

**版本:** v2.1.0 (Java)
**日期:** 2026-08-06

---

## 1. 测试概览

Agent Framework (Java) 的测试基于 **Spring Boot Test** + **JUnit 5**。

| 层级 | 说明 |
|------|------|
| 单元测试 | Service/Config/Tool 级别, Mock 外部依赖 |
| 集成测试 | 全流程 Controller 测试 (MockMvc) |
| E2E 测试 | 真实 LLM + 工具调用 + MCP (手动验证) |

---

## 2. 测试 LLM 配置

所有 E2E 测试使用以下 LLM 配置：

```yaml
# application-test.yml
agent:
  llm:
    api-key: ${LLM_API_KEY}
    model-id: LongCat-2.0
    base-url: https://api.longcat.chat/openai/v1
    provider: openai
    temperature: 0.2
    max-tokens: 50
    timeout: 30
  checkpoint:
    jdbc-url: jdbc:mysql://127.0.0.1:3307/agent_manager_test
    username: agent_manager
    password: Agent@Manager2026
```

---

## 3. 运行测试

### 3.1 全部测试

```bash
cd agent-framework
mvn test -Dspring.profiles.active=test
```

### 3.2 指定测试类

```bash
mvn test -Dtest=AgentFrameworkApplicationTests -Dspring.profiles.active=test
```

### 3.3 跳过测试

```bash
mvn clean package -DskipTests
```

---

## 4. 测试用例

### 4.1 AgentFrameworkApplicationTests

```java
// src/test/java/.../AgentFrameworkApplicationTests.java
// Spring Boot context 加载测试
@SpringBootTest
class AgentFrameworkApplicationTests {
    @Test
    void contextLoads() {
        // 验证 ApplicationContext 加载成功
    }
}
```

### 4.2 OafConfigLoaderTest (12 个用例)

覆盖字段解析：name、vendorKey、agentKey、version、slug、description、author、license、tags、skills、mcpServers、tools、systemPrompt、model、runtimeConfig、memory、deniedTools（空默认值、显式值）。

### 4.3 A2AControllerTest (9 个用例)

覆盖场景：
- `message/send` 正常返回
- `metadata.userId` 透传到 `AgentRuntimeService`
- `metadata.thread_id` / `metadata.contextId` / `taskId` 回退
- 缺少 params / 缺少 message 返回 -32602
- 未知 method 返回 -32601
- 缺少 method 返回 -32600

### 4.4 StreamControllerTest (2 个用例)

- `GET /chat/stream` Channel SSE 流式事件
- 空消息处理

### 4.5 ToolControllerTest (3 个用例)

- `GET /skills` 返回 OAF skill 列表
- `GET /mcp` 返回 MCP 配置
- `GET /tools` 返回 builtin 列表

### 4.6 AgentRuntimeServiceTest (10 个用例)

覆盖场景：
- userId 透传 / 回退 vendorKey / 空值回退
- sessionId 自动生成 / slug `/` 替换
- invoke 异常返回 Error 响应
- invokeStream working 事件 + AGENT_END 触发 done
- userId 传播到 RuntimeContext
- 2 参 invoke 委托到 3 参
- tenantPrefix = slug

### 4.7 WorkspaceInitializerTest (8 个用例)

覆盖场景：
- workspace 结构创建（AGENTS.md + tools.json）
- AGENTS.md frontmatter 生成（name/model/temperature）
- 空 tools.json（无 deny 时为空对象）
- deny 列表生成
- 本地 skill 复制 / 远程 skill 跳过
- subagents 生成
- 幂等不覆盖已有文件

### 4.8 McpToolRegistrarTest (11 个用例)

覆盖场景：
- SSE / stdio / streamableHttp 三种传输构建
- auth 环境变量 token / 静态 token
- 缺失 config.yaml / 缺失 connection 段返回 null
- configDir 回退到 server 名目录
- `permissions.read_only: true` → `isReadOnlyConfigured()` 返回 true
- 无 permissions 段 → 返回 false
- `permissions.read_only: false` → 返回 false

### 4.9 BusinessToolsTest (5 个用例)

- `echo` 前缀 / 空值 / 空白保留
- `get_current_time` ISO 格式（含纳秒）、UTC、非法时区抛异常

---

## 5. 手动验证

### 5.1 健康检查

```bash
curl -s http://localhost:8101/health | python3 -m json.tool
```

### 5.2 A2A message/send

```bash
curl -s -X POST http://localhost:8101/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"请只回复 welcome"}]}},"id":"1"}' \
  | python3 -m json.tool
```

### 5.3 Channel SSE

```bash
curl -s -N "http://localhost:8101/chat/stream?message=请只回复welcome&userId=test-user"
```

### 5.4 MCP 工具调用

```bash
curl -s -X POST http://localhost:8101/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"用 get_weather 查询北京天气"}]},"metadata":{"userId":"alice"}},"id":"m1"}'
```

### 5.5 LLM 连通性

```bash
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'
```

---

## 6. 测试 Fixtures

### test-agent 配置

```
src/test/resources/fixtures/test-agent/
├── AGENTS.md
├── skills/
│   └── bash-tool/
│       └── SKILL.md
└── mcp-configs/
    └── weather/
        └── config.yaml          # connection: { type: streamableHttp, url: ... }
                                   # permissions: { read_only: true }
```

---

## 7. 测试环境要求

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | ≥ 21 | Java 运行时 |
| Maven | ≥ 3.9 | 构建工具 |
| MySQL | ≥ 8.0 | MysqlDistributedStore |
| JUnit 5 | 内置 | 测试框架 |
| Spring Boot Test | 3.3.5 | 集成测试支持 |
| LLM API | LongCat-2.0 | E2E 测试 |

---

## 8. 测试统计

| 类别 | 数量 | 状态 |
|------|------|------|
| 单元测试 | 68 | ✅ 全部通过 |
| OafConfigLoaderTest | 12 | ✅ |
| A2AControllerTest | 9 | ✅ |
| AgentRuntimeServiceTest | 10 | ✅ |
| WorkspaceInitializerTest | 8 | ✅ |
| McpToolRegistrarTest | 11 | ✅ |
| BusinessToolsTest | 5 | ✅ |
| ToolControllerTest | 3 | ✅ |
| StreamControllerTest | 2 | ✅ |
| 其他 Controller Tests | 8 | ✅ |
