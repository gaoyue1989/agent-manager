# Agent Framework — AGENTS.md

## 二级模块概述

Agent Framework 是基于 **AgentScope Java 2.0 HarnessAgent** 的独立可运行 Agent 服务框架。支持 **OAF v0.8.0** 配置规范 (`AGENTS.md` frontmatter)、**A2A v1.0.0** 通信协议 (JSON-RPC + SSE) 和 **A2UI v0.8** 声明式 UI 扩展。通过 MysqlDistributedStore 实现 AgentState + 工作区文件统一持久化，支持 MCP 工具原生集成、记忆管理、上下文压缩、技能自学习、Plan Mode、Channel SSE。

## 技术栈

| 层级 | 技术选型 |
|------|---------|
| Agent 引擎 | AgentScope Java 2.0 HarnessAgent (io.agentscope:agentscope-harness) |
| LLM 适配 | agentscope-extensions-model-openai (OpenAI 兼容 API) |
| 服务框架 | Spring Boot 3.3 + Tomcat (port 8100) |
| 配置解析 | SnakeYAML (frontmatter) + @ConfigurationProperties (env) |
| 状态持久化 | MysqlDistributedStore (MysqlAgentStateStore + JdbcStore → agent_state + agent_fs) |
| MCP 集成 | McpToolRegistrar (config.yaml → McpClientBuilder 原生注册) |
| A2A 协议 | AgentScopeA2aServer + HarnessAgentRunner (JSON-RPC) |
| A2UI | AgentScope 事件流驱动 |
| 数据库 | GreatSQL 8.0 (端口 3307, DB `agent_manager_test`) |
| 构建工具 | Maven 3.9+ |
| JDK | 21+ |

---

## 目录结构

```
agent-framework/
├── pom.xml                              # Maven 构建配置
├── src/
│   ├── main/
│   │   ├── java/io/agentmanager/framework/
│   │   │   ├── AgentFrameworkApplication.java  # Spring Boot 入口
│   │   │   ├── config/
│   │   │   │   ├── AgentManagerProperties.java  # 环境变量配置
│   │   │   │   ├── OafConfigLoader.java         # AGENTS.md 解析
│   │   │   │   ├── AgentScopeConfig.java        # Bean 装配 (HarnessAgent + MysqlDistributedStore)
│   │   │   │   ├── A2AServerConfig.java         # A2A Server 配置 (HarnessAgentRunner)
│   │   │   │   └── ChannelConfig.java           # ChatUiChannel Bean
│   │   │   ├── model/
│   │   │   │   └── OafConfig.java               # OAF 配置模型 (含 deniedTools)
│   │   │   ├── service/
│   │   │   │   ├── AgentRuntimeService.java     # Agent 运行时封装 (invoke/invokeStream)
│   │   │   │   ├── WorkspaceInitializer.java    # OAF → Workspace 目录转换
│   │   │   │   ├── McpToolRegistrar.java        # MCP 原生注册 (config.yaml → McpClientBuilder)
│   │   │   │   ├── McpManager.java              # MCP 配置加载
│   │   │   │   ├── HarnessAgentRunner.java      # A2A Server 适配器
│   │   │   │   ├── A2uiService.java             # A2UI 协议
│   │   │   │   └── LLMLogger.java               # LLM 调用日志
│   │   │   ├── tool/
│   │   │   │   └── BusinessTools.java           # @Tool 注解自定义工具 (get_current_time, echo)
│   │   │   └── controller/
│   │   │       ├── InfoController.java          # GET /、/system-prompt
│   │   │       ├── HealthController.java        # GET /health
│   │   │       ├── ToolController.java          # GET /skills、/mcp、/tools
│   │   │       ├── AgentCardController.java     # GET /.well-known/agent-card.json
│   │   │       ├── DebugController.java         # GET /debug
│   │   │       ├── StreamController.java        # GET /chat/stream (Channel SSE)
│   │   │       ├── ThreadController.java        # GET /threads
│   │   │       └── A2AController.java           # POST / (A2A JSON-RPC)
│   │   └── resources/
│   │       ├── application.yml                  # Spring Boot 配置
│   │       └── static/debug/                    # 调试页面 (拆分架构)
│   │           ├── index.html                   # 调试页入口
│   │           ├── css/                         # 样式 (base/components/layout)
│   │           ├── js/                          # 脚本 (api/app/router)
│   │           └── modules/                     # 功能模块 (chat/tools/config 等)
│   └── test/                                  # 68 个测试用例
├── docs/                                     # 改进方案文档 (14 份)
├── Dockerfile                                # 镜像构建 (多阶段: Maven 构建 → JRE 21 运行)
├── Dockerfile.dev                            # 离线开发镜像 (JDK 21 + Maven + 全量依赖缓存)
├── Makefile                                  # Maven 封装 (build/test/docker-build/offline 等)
├── docker/
│   └── offline-settings.xml                  # Maven 默认配置模板 (支持 Nexus 镜像)
└── .env.example                              # 环境变量模板
```

---

## 核心模块

### 1. AgentScopeConfig — Agent 装配

创建 `HarnessAgent` Bean，配置：
- **MysqlDistributedStore**: AgentState + 工作区文件统一持久化
- **RemoteFilesystemSpec(IsolationScope.USER)**: 按 userId 多租户隔离
- **MemoryConfig**: 记忆管理（MEMORY.md + memory/，flush 节流）
- **CompactionConfig**: 上下文压缩（30 条触发，保留 10 条）
- **ToolResultEvictionConfig**: 大工具结果卸载
- **Plan Mode / Skill 自学习**: 启用
- **Toolkit**: 自定义工具 (BusinessTools) + MCP 工具 (McpToolRegistrar)
- **WorkspaceInitializer**: OAF → Workspace 转换

### 2. AgentRuntimeService — 运行时封装

```java
invoke(message, threadId)            → (response, threadId)
invoke(message, threadId, userId)    → (response, threadId)  // 多租户
invokeStream(message, threadId)      → Flux<Map>  // 流式
invokeStream(message, threadId, userId) → Flux<Map>
```

- `userId` 来源：A2A `metadata.userId` / Channel `SendOptions.userId()` / 默认回退 `vendorKey`
- `sessionId` 生成：`tenantPrefix.replace("/","-") + ":" + threadId`

### 3. McpToolRegistrar — MCP 原生注册

- 从 `mcp-configs/{server}/config.yaml` 读取 `connection` + `auth` + `permissions`
- 支持 `sse` / `streamableHttp` / `stdio` 三种传输
- **支持 `permissions.read_only: true`**: 非只读 MCP 工具被权限系统拦截时，强制注册为只读绕过 HITL
- **支持 ActiveMCP.json 子集过滤**: `selectedTools` 中 `enabled: false` 的工具不注册到 Toolkit
- **工具注册名**: 使用远端裸名（`tool.name()`），确保 `McpTool.callAsync` 正确执行；`mcp__{server}__{tool}` 前缀名仅用于 API 展示和注册缓存（因 `McpTool.getName()` 是 `final` 字段，无法分离 LLM 暴露名和执行名）

### 4. A2AController — A2A JSON-RPC

- `POST /` 处理 `message/send` + `message/stream`
- 支持 `metadata.thread_id` / `metadata.contextId` / `metadata.userId`
- 与 AgentScopeA2aServer 共存

---

## AgentScope 2.0 功能使用状态

| 功能 | 状态 | 说明 |
|------|------|------|
| 技能（Skill） | ✅ | 自定义 SkillManager，未用 Workspace skills/ |
| 记忆管理 | ✅ | MEMORY.md + memory/，flush 节流 10 分钟 |
| 上下文压缩 | ✅ | CompactionConfig，30 条触发保留 10 条 |
| Plan Mode | ✅ | enablePlanMode() |
| Channel | ✅ | ChatUiChannel (GET /chat/stream) |
| 工作区（Workspace） | ✅ | WorkspaceInitializer 生成 .agentscope/workspace/ |
| 子 Agent | ✅ | subagents/*.md |
| 沙箱 | ❌ | 未配置 |
| Agent 状态存储 | ✅ | MysqlDistributedStore (agent_state + agent_fs) |
| 模型集成 | ✅ | OpenAI 兼容 API |
| MCP 集成 | ✅ | McpToolRegistrar (config.yaml permissions.read_only) |
| A2A 协议 | ✅ | AgentScopeA2aServer + HarnessAgentRunner |
| 多租户 | ✅ | IsolationScope.USER (按 userId 隔离) |

---

## 工具体系

### 内置工具（Harness 自动注册，约 26 个）

文件: `read_file`, `write_file`, `edit_file`, `grep_files`, `glob_files`, `list_files`
记忆: `memory_search`, `memory_get`, `memory_save`
会话: `session_search`, `session_list`, `session_history`
子Agent: `agent_spawn`, `agent_send`, `agent_list`
计划: `plan_enter`, `plan_write`, `plan_exit`
技能: `propose_skill`, `skill_manage`, `load_skill_through_path`
任务: `task_list`, `task_output`, `task_cancel`, `wait_async_results`

### 自定义工具（@Tool 注解）

| 工具 | 说明 |
|------|------|
| `get_current_time(timezone)` | 返回指定时区当前时间 |
| `echo(text)` | 回显输入 |

### MCP 工具

通过 `McpToolRegistrar` 从 `mcp-configs/{server}/config.yaml` 注册。
- 传输: `sse` / `streamableHttp` / `stdio`
- 认证: `auth.token` 支持 `${ENV_VAR}` 语法
- 权限: `permissions.read_only: true` 强制只读
- 子集: `mcp-configs/{server}/ActiveMCP.json` 的 `selectedTools.enabled` 控制注册子集
- 命名: Toolkit 注册用远端裸名；`/tools`、`/mcp` API 用 `mcp__{server}__{tool}` 展示名

### 工具过滤

`tools.json` 只写 `deny`，不写 `allow`（保留全部内置工具）。
OAF `deniedTools` 字段控制排除列表。

---

## 多租户隔离

通过 `RemoteFilesystemSpec(IsolationScope.USER)` + `RuntimeContext(userId, sessionId)` 实现：

| 数据类型 | 隔离维度 | 存储位置 |
|----------|---------|---------|
| AgentState | (userId, sessionId) | agent_state 表 |
| MEMORY.md | userId | agent_fs 表 |
| memory/ | userId | agent_fs 表 |
| skills/ | 共享 + 用户覆盖 | agent_fs 表 |
| sessions/ | userId | agent_fs 表 |

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
| GET | `/.well-known/agent-card.json` | Agent Card |
| GET | `/skills` | 技能列表 |
| GET | `/mcp` | MCP 服务器列表 |
| GET | `/tools` | 工具列表 |
| GET | `/debug` | 调试页面 |
| GET | `/system-prompt` | 系统提示词 |
| GET | `/threads` | Thread 列表 |
| GET | `/chat/stream` | Channel SSE 流式对话 |
| POST | `/` | A2A JSON-RPC (message/send, message/stream) |

---

## 启动

```bash
cd agent-framework
mvn clean package -DskipTests            # Maven 直接构建
make package                             # 或通过 Makefile
LLM_API_KEY=... LLM_MODEL_ID=... LLM_BASE_URL=... \
  AGENT_CONFIG_DIR=/path/to/config \
  java -jar target/agent-framework-*.jar
```

Docker 部署（多阶段构建，运行时非 root 用户，JAVA_OPTS 可覆盖 JVM 参数）：

```bash
make docker-build                         # 构建 docker.io/agent-framework:latest
docker run -d --name agent-framework -p 8100:8100 \
  -e LLM_API_KEY=... -e LLM_MODEL_ID=... -e LLM_BASE_URL=... \
  -e AGENT_CONFIG_DIR=/config -v ./config:/config \
  agent-framework:latest
```

内网离线开发镜像（JDK 21 + Maven + 全量依赖缓存）：

```bash
make docker-build-dev  # 或 docker build -f Dockerfile.dev -t gaoyue1989/agent-framework:java-dev .
make docker-save       # 导出 tar.gz 传输到内网机器
make offline           # 进入离线容器 (挂载当前工作目录)
mvn -o test            # 容器内离线测试 (68 用例)
```

Nexus 私有源接入、离线开发完整说明见 [docs/offline-dev-image.md](docs/offline-dev-image.md)。

---

## 测试

```bash
mvn test     # 68 个测试，全部通过
mvn -o test  # 离线模式 (离线开发镜像内)
```
