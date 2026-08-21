# Agent Framework — 部署文档

**版本:** v2.1.0 (Java)
**日期:** 2026-08-06

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
java -jar target/agent-framework-2.1.0.jar
```

### 2.4 验证

```bash
curl http://localhost:8100/health
curl http://localhost:8100/debug
```

---

## 3. Docker 部署

### 3.1 构建镜像

多阶段构建（Stage 1: Maven 构建 → Stage 2: JRE 21 运行，非 root 用户）：

```bash
docker build -t agent-framework:latest .
# 或通过 Makefile
make docker-build
```

### 3.2 启动容器

```bash
docker run -d --name agent-framework \
  -p 8100:8100 \
  -e LLM_API_KEY=your_api_key \
  -e LLM_MODEL_ID=your_model_id \
  -e LLM_BASE_URL=https://your-api-endpoint/v1 \
  -e AGENT_CONFIG_DIR=/config \
  -e JAVA_OPTS="-Xmx2g" \
  -v ./config:/config \
  agent-framework:latest
```

> 说明:
> - 运行用户为非 root 的 `appuser`，`/config` 为挂载卷（内含 AGENTS.md/skills/mcp-configs）
> - `JAVA_OPTS` 可覆盖 JVM 参数（默认 `-XX:MaxRAMPercentage=75`）
> - 内置健康检查（`curl /health`，30s 间隔）
> - 链路追踪（OTel Java Agent，详见 `tracing-design.md`）：镜像内置 agent jar，设 `OTEL_EXPORTER_OTLP_ENDPOINT` 即自动启用；**切勿**设 `OTEL_TRACES_EXPORTER=none`（会连 Agent 导出一起禁掉）

### 3.3 查看日志

```bash
docker logs -f agent-framework
```

预期输出：
```
Loaded OAF: My Agent v1.0.0
DistributedStore initialized (agent_manager_test.agent_state + agent_fs)
Workspace initialized at: /config/.agentscope/workspace
HarnessAgent created: My Agent (model: your_model_id, filesystem: MySQL)
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
| `CONFIRM_TTL_MINUTES` | int | `30` | | HITL 确认上下文 TTL (分钟) |
| `TURN_LEASE_TTL_SECONDS` | int | `60` | | Turn 租约 TTL (秒) |
| `TURN_LEASE_RENEW_SECONDS` | int | `20` | | Turn 租约续期间隔 (秒) |
| `AUDIT_RETENTION_DAYS` | int | `30` | | 工具审计日志保留天数 |
| `SESSION_RETENTION_DAYS` | int | `30` | | 会话数据保留天数 |

### 4.2 AGENTS.md 配置字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✓ | Agent 显示名称 |
| `vendorKey` | string | ✓ | 发布者命名空间 |
| `agentKey` | string | ✓ | Agent 标识符 |
| `version` | string | ✓ | 语义版本号 |
| `slug` | string | ✓ | 唯一标识: `vendorKey/agentKey` |
| `description` | string | | 简要描述 |
| `skills` | list | | 技能列表 |
| `mcpServers` | list | | MCP 服务器列表 |
| `tools` | list | | 启用内置工具 |
| `deniedTools` | list | | 排除的工具 |
| `model` | object/string | | 模型配置 |
| `agents` | list | | 子 Agent 声明 |

### 4.3 数据表 DDL (stateless-single-stream 新增)

以下三张表在启动时自动创建（若不存在），或由 `SessionCleanupService` 联动清理：

```sql
-- HITL 确认上下文（跨副本共享）
CREATE TABLE IF NOT EXISTS confirm_context (
  session_id      VARCHAR(255) PRIMARY KEY,   -- fullThreadId（makeThreadId 补全 tenant 前缀）
  tool_calls_json MEDIUMTEXT NOT NULL,        -- [{id, name, input}]（ToolUseBlock 字段重建来源）
  reply_id        VARCHAR(64),
  created_at      DATETIME(3) NOT NULL,
  consumed        TINYINT(1) NOT NULL DEFAULT 0,
  KEY idx_created_at (created_at)
);

-- Turn 租约（执行权互斥，token + TTL 60s + 20s 续租）
CREATE TABLE IF NOT EXISTS turn_lease (
  session_id VARCHAR(255) PRIMARY KEY,
  token      CHAR(36) NOT NULL,             -- 本 turn 租约凭证（UUID）
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL
);

-- 工具调用轻量审计（仅元信息，不含参数/文本 delta）
CREATE TABLE IF NOT EXISTS tool_audit_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id  VARCHAR(255) NOT NULL,
  tool_name   VARCHAR(255) NOT NULL,
  tool_call_id VARCHAR(64),
  state       VARCHAR(32),                  -- TOOL_CALL_START / TOOL_CALL_END / TOOL_RESULT_START / TOOL_RESULT_END
  payload_json MEDIUMTEXT,                  -- SSE 词表一致（含 MCP ui 元数据）
  created_at  DATETIME(3) NOT NULL,
  KEY idx_session (session_id, id),
  KEY idx_created_at (created_at)
);
```

---

## 5. MCP 配置

### 5.1 config.yaml 格式

```yaml
server: weather-service
vendor: weather
version: "1.0.0"
connection:
  type: streamableHttp   # sse / streamableHttp / stdio
  url: http://127.0.0.1:8811/mcp
  timeout: 60
auth:
  type: bearer
  token: ${MCP_TOKEN}    # 支持环境变量
permissions:
  read_only: true         # 强制工具只读，绕过 HITL 授权
```

### 5.2 permissions.read_only 说明

AgentScope 的 `McpTool.checkPermissions()` 对非只读 MCP 工具返回 `PermissionDecision.ask()`（需 HITL 授权），导致工具调用挂起。两种方式让 MCP 工具自动放行：

| 方式 | 来源 | 说明 |
|------|------|------|
| server annotations | `ToolAnnotations(readOnlyHint=True)` | MCP 协议标准，server 端标注 |
| **config.yaml 配置** | `permissions.read_only: true` | 本框架支持，无需改 server |

**优先级**: config.yaml `permissions.read_only` > server `annotations.readOnlyHint` > 默认 HITL ask

### 5.3 MCP 工具执行流程

```
LLM 推理 → 选择工具 (如 get_weather)
  → McpTool.callAsync → McpSyncClientWrapper.callTool
  → streamable-http POST → MCP Server
  → 返回 JSON → ToolResultBlock → LLM 组织最终回答
```

---

## 6. 服务端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 服务信息 + 协议声明 |
| GET | `/health` | 健康检查 |
| GET | `/.well-known/agent-card.json` | Agent Card 发现 |
| GET | `/skills` | 技能列表 |
| GET | `/mcp` | MCP 服务器列表 |
| GET | `/tools` | 工具列表 |
| GET | `/debug` | 调试页面 (A2A/Channel 双模式) |
| GET | `/system-prompt` | 系统提示词 |
| GET | `/threads` | Thread 列表 |
| GET | `/chat/stream` | Channel SSE 一次性流对话 (旧) |
| POST | `/threads/{sid}/chat` | SSE 单次流直吐 (单次流模式, 含 Turn 租约排队) |
| POST | `/threads/{sid}/confirm-stream` | HITL 确认恢复流 (恢复执行需重新获取执行权) |
| POST | `/threads/{sid}/confirm` | HITL 同步确认 |
| GET | `/threads/{sid}/history` | 会话历史 (含 pendingConfirm) |
| GET | `/threads/{sid}/llm-calls` | LLM 调用日志 |
| POST | `/` | A2A JSON-RPC (message/send, message/stream) |

---

## 7. A2A JSON-RPC 方法

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
    },
    "metadata": {
      "thread_id": "optional-thread-id",
      "userId": "optional-user-id"
    }
  },
  "id": "1"
}
```

---

## 8. Channel SSE API

### 8.1 旧一次性流 (GET /chat/stream)

```
GET /chat/stream?message=<text>&userId=<id>[&sessionId=<id>]
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✓ | 用户消息 |
| `userId` | String | ✓ | 用户标识（自动创建独立 session） |
| `sessionId` | String | | 指定 session（同一用户多个对话） |
| `subagentId` | String | | 直接与子 Agent 对话 |

### 8.2 单次流模式 (POST /threads/{sid}/chat)

```
POST /threads/{sid}/chat
Content-Type: application/json

{"message": "用户消息", "userId": "用户标识"}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✓ | 用户消息 |
| `userId` | String | ✓ | 用户标识 |

**行为**：
- 返回 SSE 流，执行完即关闭（无长连接状态）
- 同 session 并发请求排队等待（等待期间 SSE 每 15s 发 `{type:"waiting"}` 帧）
- 排队超时 120s → HTTP 409 `turn_in_progress`
- permission_ask 时锁让出，挂起期间新消息可自由执行
- AGENT_END / error 后释放锁、关闭流

---

## 9. 验证

### 9.1 健康检查

```bash
curl -s http://localhost:8100/health
```

### 9.2 同步消息

```bash
curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"请只回复 welcome"}]}},"id":"1"}'
```

### 9.3 Channel SSE

```bash
curl -s -N "http://localhost:8100/chat/stream?message=请只回复welcome&userId=test-user"
```

### 9.4 LLM 连通性

```bash
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'
```

---

## 11. 内网离线开发镜像

预装 JDK 21 + Maven 3.9.9 + 全量依赖缓存，适用于无法访问外网的内网环境：

```bash
# 导出/导入
docker save gaoyue1989/agent-framework:java-dev | gzip > agent-framework-java-dev.tar.gz
docker load < agent-framework-java-dev.tar.gz

# 进入离线容器（挂载当前目录，mvn -o 强制离线构建）
docker run --rm -it -v $(pwd):/workspace -w /workspace \
  gaoyue1989/agent-framework:java-dev bash

# 接入内网 Nexus（挂载自定义 settings.xml 覆盖默认源）
docker run --rm -it -v $(pwd):/workspace -w /workspace \
  -v /path/to/settings.xml:/root/.m2/settings.xml:ro \
  gaoyue1989/agent-framework:java-dev bash
```

完整说明（Nexus 配置、导出传输、常见问题）见 [offline-dev-image.md](offline-dev-image.md)。

---

## 10. 常见问题（原 §10 不变）

### Q: 服务启动后 LLM 返回错误

检查 LLM 环境变量：
```bash
echo $LLM_API_KEY
echo $LLM_MODEL_ID
echo $LLM_BASE_URL
```

### Q: A2A message/send 返回 "Invalid parameters"

确认 Part 格式正确：
```json
// ✅ 正确
{"parts": [{"kind": "text", "text": "hello"}]}
// ✅ 也兼容
{"parts": [{"text": "hello"}]}
```

### Q: 端口被占用

修改 `SERVER_PORT` 环境变量。

### Q: MySQL 连接失败

K8s Pod 内需使用 Docker 网关 `172.20.0.1` 代替 `127.0.0.1`。

### Q: agent_fs 表不存在

`MysqlDistributedStore` 会在启动时自动创建。检查 MySQL 用户是否有 CREATE TABLE 权限。

### Q: MCP 工具调用卡住（无响应）

AgentScope 对非只读 MCP 工具返回 `PermissionDecision.ask()`（需 HITL 授权）。在 config.yaml 添加：
```yaml
permissions:
  read_only: true
```

### Q: 工具过滤后内置工具丢失

当前实现已移除 `allow` 白名单，改用 `deny` 排除模式。如仍有旧 `tools.json` 含 `allow`，删除 `.agentscope/workspace/tools.json` 重启。

### Q: 多用户数据串扰

确认 HarnessAgent 配置了 `IsolationScope.USER`，且 RuntimeContext 传入了正确的 `userId`。
