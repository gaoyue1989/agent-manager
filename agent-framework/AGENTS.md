# Agent Framework — AGENTS.md

## 二级模块概述

Agent Framework 是基于 **AgentScope Java 2.0** 的独立可运行 Agent 服务框架。支持 **OAF v0.8.0** 配置规范 (`AGENTS.md` frontmatter)、**A2A v1.0.0** 通信协议 (JSON-RPC + SSE) 和 **A2UI v0.8** 声明式 UI 扩展。通过 MySQL AgentStateStore 实现会话持久化，支持 MCP 工具集成。

## 技术栈

| 层级 | 技术选型 |
|------|---------|
| Agent 引擎 | AgentScope Java 2.0 (io.agentscope:agentscope-harness) |
| LLM 适配 | agentscope-extensions-model-openai (OpenAI 兼容 API) |
| 服务框架 | Spring Boot 3.3 + Tomcat (port 8100) |
| 配置解析 | SnakeYAML (frontmatter) + @ConfigurationProperties (env) |
| 状态持久化 | agentscope-extensions-mysql (MySQL AgentStateStore) |
| MCP 集成 | AgentScope 内置 McpClientBuilder |
| A2A 协议 | 自定义 A2A Controller (JSON-RPC) |
| A2UI | AgentScope 事件流驱动 |
| 数据库 | GreatSQL 8.0 (端口 3307, DB `agent_manager_test`) |
| 构建工具 | Maven 3.9+ |
| JDK | 21+ |

---

## 目录结构

```
agent-framework/
├── pom.xml                         # Maven 构建配置
├── src/
│   ├── main/
│   │   ├── java/io/agentmanager/framework/
│   │   │   ├── AgentFrameworkApplication.java  # Spring Boot 入口
│   │   │   ├── config/
│   │   │   │   ├── AgentManagerProperties.java  # 环境变量配置
│   │   │   │   ├── OafConfigLoader.java         # AGENTS.md 解析
│   │   │   │   ├── AgentScopeConfig.java        # Bean 装配
│   │   │   │   └── A2AServerConfig.java         # A2A Server 配置
│   │   │   ├── model/
│   │   │   │   └── OafConfig.java               # OAF 配置模型 (Record)
│   │   │   ├── service/
│   │   │   │   ├── AgentRuntimeService.java     # Agent 运行时封装
│   │   │   │   ├── SkillManager.java            # Skill 加载
│   │   │   │   ├── McpManager.java              # MCP 管理
│   │   │   │   ├── A2uiService.java             # A2UI 协议
│   │   │   │   └── LLMLogger.java               # LLM 调用日志
│   │   │   └── controller/
│   │   │       ├── InfoController.java          # GET /、/system-prompt
│   │   │       ├── HealthController.java        # GET /health
│   │   │       ├── ToolController.java          # GET /skills、/mcp、/tools
│   │   │       ├── AgentCardController.java     # GET /.well-known/agent-card.json
│   │   │       ├── DebugController.java         # GET /debug
│   │   │       ├── StreamController.java        # POST /chat/stream (SSE)
│   │   │       ├── ThreadController.java        # GET /threads
│   │   │       └── A2AController.java           # POST / (A2A JSON-RPC)
│   │   └── resources/
│   │       ├── application.yml                  # Spring Boot 配置
│   │       └── templates/
│   │           └── debug_page.html              # 调试页面
│   └── test/
│       └── java/io/agentmanager/framework/
│           └── AgentFrameworkApplicationTests.java
├── docs/                           # 文档
├── Dockerfile                      # 镜像构建 (多阶段)
├── Dockerfile.mcp                  # MCP 服务器 Dockerfile
├── Makefile                        # Maven 封装
└── .env.example                    # 环境变量模板
```

---

## 核心模块

### 1. AgentRuntimeService — AgentScope Java 运行时

```java
class AgentRuntimeService:
    invoke(message, threadId)        → (response, threadId)       # 同步调用
    invokeStream(message, threadId)  → Flux<Map>                  # 流式调用 (SSE)
    buildSystemPrompt()              → String                     # 构建系统提示词
```

通过 AgentScope 的 `ReActAgent` 实现，使用 `RuntimeContext` 传递 `(userId, sessionId)` 实现多租户隔离。

### 2. AgentScopeConfig — Agent 装配

创建 AgentScope `ReActAgent` Bean：
- 从 OAF Config 读取模型配置
- 通过 `ModelRegistry` 字符串格式 (`provider:model_id`) 指定模型
- 配置 MySQL `AgentStateStore` 实现会话持久化
- 配置 `Toolkit` 注册内建工具和 MCP 工具

### 3. OafConfigLoader — OAF 配置加载

解析 `AGENTS.md` 的 YAML frontmatter + Markdown body。

### 4. A2AController — A2A JSON-RPC 处理

处理 `POST /` 上的 A2A JSON-RPC 请求：
- `message/send` — 返回 JSON 响应 (通过 AgentRuntimeService.invoke)
- `message/stream` — 返回 SSE 流 (通过 AgentRuntimeService.invokeStream)

---

## 环境变量

| 变量 | 默认值 | 必填 | 说明 |
|------|--------|------|------|
| `LLM_API_KEY` | — | ✓ | LLM API 密钥 |
| `LLM_MODEL_ID` | — | ✓ | 模型 ID |
| `LLM_BASE_URL` | — | ✓ | LLM API 端点 |
| `LLM_PROVIDER` | `openai` | | 提供商标识 |
| `LLM_TEMPERATURE` | `0.7` | | 生成温度 |
| `LLM_MAX_TOKENS` | `4096` | | 最大 token |
| `LLM_TIMEOUT` | `120` | | 超时(秒) |
| `AGENT_CONFIG_DIR` | `/config` | | Agent 配置目录 |
| `SERVER_HOST` | `0.0.0.0` | | 监听地址 |
| `SERVER_PORT` | `8100` | | 服务端口 |
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | | MySQL JDBC URL |
| `CHECKPOINT_USERNAME` | `agent_manager` | | MySQL 用户名 |
| `CHECKPOINT_PASSWORD` | `Agent@Manager2026` | | MySQL 密码 |

---

## 服务端点

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
| POST | `/` | A2A JSON-RPC (message/send, message/stream) |
| POST | `/chat/stream` | SSE 流式对话 |

---

## 启动

```bash
cd agent-framework
mvn clean package -DskipTests
LLM_API_KEY=... LLM_MODEL_ID=... LLM_BASE_URL=... \
  AGENT_CONFIG_DIR=/path/to/config \
  java -jar target/agent-framework-*.jar
```
