# Agent Framework — 部署文档

**版本:** v2.0.0 (Java)
**日期:** 2026-07-13

---

## 1. 前置条件

| 条件 | 说明 |
|------|------|
| JDK | ≥ 21 |
| Maven | ≥ 3.9 |
| MySQL | ≥ 8.0 (GreatSQL 3307) |
| Docker | ≥ 24.0 (Docker 部署时需要) |
| LLM API | OpenAI 兼容接口 |

---

## 2. 快速启动 (本地开发)

### 2.1 构建

```bash
cd agent-framework
mvn clean package -DskipTests
```

### 2.2 准备配置目录

创建 `config/AGENTS.md`:

```yaml
---
name: "My Agent"
vendorKey: "myorg"
agentKey: "my-agent"
version: "1.0.0"
slug: "myorg/my-agent"
description: "A custom agent"
tools:
  - Read
  - Bash
---

# Agent Purpose
You are a helpful AI assistant.
```

### 2.3 启动

```bash
LLM_API_KEY=your_api_key \
LLM_MODEL_ID=your_model_id \
LLM_BASE_URL=https://your-api-endpoint/v1 \
AGENT_CONFIG_DIR=./config \
SERVER_PORT=8100 \
java -jar target/agent-framework-2.0.0.jar
```

### 2.4 验证

```bash
curl http://localhost:8100/health
# {"status":"healthy","llm_configured":true,...}

curl http://localhost:8100/debug
# HTML debug page
```

---

## 3. Docker 部署

### 3.1 构建镜像

```bash
docker build -t agent-framework:latest .
```

### 3.2 启动容器

```bash
docker run -d --name agent-framework \
  -p 8100:8100 \
  -e LLM_API_KEY=your_api_key \
  -e LLM_MODEL_ID=your_model_id \
  -e LLM_BASE_URL=https://your-api-endpoint/v1 \
  -e AGENT_CONFIG_DIR=/config \
  -v ./config:/config \
  agent-framework:latest
```

### 3.3 查看日志

```bash
docker logs -f agent-framework
```

预期输出：
```
Loaded OAF: My Agent v1.0.0
  Skills: 0 - []
  MCP: 0 - []
  Tools: ['Read', 'Bash', 'Edit', 'Grep']
Agent created: My Agent (model: your_model_id)
Tomcat started on port 8100
```

---

## 4. 配置参考

### 4.1 环境变量完整列表

| 变量 | 类型 | 默认值 | 必填 | 说明 |
|------|------|--------|------|------|
| `LLM_API_KEY` | string | — | ✓ | LLM API 密钥 |
| `LLM_MODEL_ID` | string | — | ✓ | 模型 ID |
| `LLM_BASE_URL` | string | — | ✓ | LLM API 端点 |
| `LLM_PROVIDER` | string | `openai` | | 提供商标识 |
| `LLM_TEMPERATURE` | float | `0.7` | | 生成温度 |
| `LLM_MAX_TOKENS` | int | `4096` | | 最大输出 token |
| `LLM_TIMEOUT` | int | `120` | | API 超时(秒) |
| `AGENT_CONFIG_DIR` | path | `/config` | | 配置目录 |
| `SERVER_HOST` | string | `0.0.0.0` | | 监听地址 |
| `SERVER_PORT` | int | `8100` | | 服务端口 |
| `CHECKPOINT_JDBC_URL` | string | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | | MySQL JDBC URL |
| `CHECKPOINT_USERNAME` | string | `agent_manager` | | MySQL 用户名 |
| `CHECKPOINT_PASSWORD` | string | `Agent@Manager2026` | | MySQL 密码 |

### 4.2 AGENTS.md 配置字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✓ | Agent 显示名称 |
| `vendorKey` | string | ✓ | 发布者命名空间 (kebab-case) |
| `agentKey` | string | ✓ | Agent 标识符 (kebab-case) |
| `version` | string | ✓ | 语义版本号 |
| `slug` | string | ✓ | 唯一标识: `vendorKey/agentKey` |
| `description` | string | | 简要描述 |
| `skills` | list[object] | | 技能列表 |
| `mcpServers` | list[object] | | MCP 服务器列表 |
| `tools` | list[string] | | 启用内置工具: `Read`, `Bash`, `Edit`, `Grep` |

---

## 5. 服务端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 服务信息 + 协议声明 |
| GET | `/health` | 健康检查 |
| GET | `/.well-known/agent-card.json` | Agent Card 发现 |
| GET | `/skills` | 技能列表 |
| GET | `/mcp` | MCP 服务器列表 |
| GET | `/tools` | 工具列表 |
| GET | `/debug` | 调试页面 |
| GET | `/system-prompt` | 系统提示词 |
| GET | `/threads` | Thread 列表 |
| POST | `/` | A2A JSON-RPC |
| POST | `/chat/stream` | SSE 流式对话 |

---

## 6. A2A JSON-RPC 方法

| 方法 | 说明 |
|------|------|
| `message/send` | 同步消息 (JSON 响应) |
| `message/stream` | 流式消息 (SSE 响应) |

### 请求格式

```json
{
  "jsonrpc": "2.0",
  "method": "message/send",
  "params": {
    "message": {
      "role": "user",
      "parts": [{"kind": "text", "text": "hello"}]
    }
  },
  "id": "1"
}
```

> 注意: Part 的 `kind` 字段为 `"text"`（不是 `"type"`）。

---

## 7. 验证

### 7.1 健康检查

```bash
curl -s http://localhost:8100/health | python3 -m json.tool
```

### 7.2 同步消息

```bash
curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"hello"}]}},"id":"1"}'
```

### 7.3 流式消息

```bash
curl -s -N -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/stream","params":{"message":{"role":"user","parts":[{"text":"hello"}]}},"id":"s1"}'
```

### 7.4 调试页面

浏览器访问 `http://localhost:8100/debug`

---

## 8. 常见问题

### Q: 服务启动后 LLM 返回错误

检查 LLM 环境变量是否设置:
```bash
echo $LLM_API_KEY
echo $LLM_MODEL_ID
echo $LLM_BASE_URL
```

### Q: A2A message/send 返回 "Invalid parameters"

确认 Part 格式正确:
```json
// ✅ 正确
{"parts": [{"kind": "text", "text": "hello"}]}
// ✅ 也兼容
{"parts": [{"text": "hello"}]}
```

### Q: 端口被占用

修改 `SERVER_PORT` 环境变量。

### Q: MySQL 连接失败

确认 `CHECKPOINT_JDBC_URL` 中的主机地址在 K8s Pod 内需使用 Docker 网关 `172.20.0.1` 代替 `127.0.0.1`。
