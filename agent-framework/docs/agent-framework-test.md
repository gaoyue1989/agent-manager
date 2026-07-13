# Agent Framework — 测试文档

**版本:** v2.0.0 (Java)
**日期:** 2026-07-13

---

## 1. 测试概览

Agent Framework (Java) 的测试基于 **Spring Boot Test** + **JUnit 5**。

| 层级 | 说明 |
|------|------|
| 单元测试 | Service/Config 级别, Mock 外部依赖 |
| 集成测试 | 全流程 Controller 测试 (MockMvc) |
| E2E 测试 | 真实 LLM + 工具调用 (手动验证) |

---

## 2. 运行测试

### 2.1 全部测试

```bash
cd agent-framework
mvn test
```

### 2.2 指定测试类

```bash
mvn test -Dtest=AgentFrameworkApplicationTests
```

### 2.3 跳过测试

```bash
mvn clean package -DskipTests
```

---

## 3. 测试用例

### 3.1 AgentFrameworkApplicationTests

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

---

## 4. 手动验证

### 4.1 健康检查

```bash
curl -s http://localhost:8100/health | python3 -m json.tool
```

预期输出:
```json
{
    "status": "healthy",
    "agent": "test-agent",
    "llm_configured": true,
    "engine": "AgentScope Java 2.0",
    "version": "1.0.0"
}
```

### 4.2 Agent 信息

```bash
curl -s http://localhost:8100/ | python3 -m json.tool
```

### 4.3 Agent Card

```bash
curl -s http://localhost:8100/.well-known/agent-card.json | python3 -m json.tool
```

### 4.4 同步消息

```bash
curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"say hi"}]}},"id":"1"}' | python3 -m json.tool
```

预期输出:
```json
{
    "jsonrpc": "2.0",
    "id": "1",
    "result": {
        "id": "<uuid>",
        "status": "completed",
        "result": {
            "message": {
                "kind": "message",
                "role": "agent",
                "parts": [{"kind": "text", "text": "Hi! I'm the test agent..."}]
            }
        }
    }
}
```

### 4.5 流式消息

```bash
curl -s -N -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/stream","params":{"message":{"role":"user","parts":[{"text":"hello"}]}},"id":"s1"}'
```

预期输出:
```
data: {"type":"task_update","state":"working",...}
data: {"type":"token","token":"Hello",...}
data: {"type":"token","token":"! I",...}
...
data: {"type":"done"}
data: [DONE]
```

### 4.6 Skills / MCP / Tools

```bash
curl -s http://localhost:8100/skills | python3 -m json.tool
curl -s http://localhost:8100/mcp | python3 -m json.tool
curl -s http://localhost:8100/tools | python3 -m json.tool
```

### 4.7 System Prompt

```bash
curl -s http://localhost:8100/system-prompt | python3 -m json.tool
```

### 4.8 Threads

```bash
curl -s http://localhost:8100/threads
```

### 4.9 Debug 页面

浏览器访问 `http://localhost:8100/debug`

---

## 5. 测试 Fixtures

### test-agent 配置

```
src/test/resources/fixtures/test-agent/
├── AGENTS.md           # Agent 主配置
└── mcp-configs/
    └── weather/
        ├── ActiveMCP.json
        └── config.yaml
```

### AGENTS.md

```yaml
---
name: "test-agent"
version: "1.0.0"
slug: "acme/test-agent"
description: "A test agent for OAF config loader tests"

skills:
  - name: "bash-tool"
    source: "local"
    version: "1.0.0"

mcpServers:
  - vendor: "weather"
    server: "weather-service"
    version: "1.0.0"
    configDir: "mcp-configs/weather"

tools:
  - Read
  - Bash
  - Edit
---

# Test Agent

This is a test agent used for OAF config loader validation.
```

---

## 6. 测试环境要求

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | ≥ 21 | Java 运行时 |
| Maven | ≥ 3.9 | 构建工具 |
| MySQL | ≥ 8.0 | 状态存储 (可选) |
| JUnit 5 | 内置 | 测试框架 |
| Spring Boot Test | 3.3.5 | 集成测试支持 |
