# Agent Framework

基于 **AgentScope Java 2.0 HarnessAgent** 的独立可运行 Agent 服务框架。
支持 **OAF v0.8.0** 配置规范（`AGENTS.md` frontmatter）、**A2A v1.0.0** 通信协议（JSON-RPC + SSE）、**A2UI v0.8** 声明式 UI、MCP 工具（含 MCP Apps 卡片渲染 + 4.7 静默更新）、HITL 跨副本人工确认、无状态单次流 SSE 对话、文件上传下载（local/S3 双后端）、OpenSandbox 沙箱执行（可选）、OTel 链路追踪。

- 版本：v2.1.0（Spring Boot 3.3.5 / JDK 21 / Maven 3.9+）
- 默认端口：`8100`
- 测试：61 个测试类 / 456 个 `@Test`（含 4 个沙箱集成测试默认跳过）
- 部署形态：单 Agent 多副本无状态水平扩展

---

## 目录结构

```
agent-framework/
├── pom.xml                              # Maven 构建配置
├── Dockerfile                           # 镜像构建（多阶段：Maven 构建 → JRE 21 运行）
├── Dockerfile.dev                       # 离线开发镜像（JDK 21 + Maven + 全量依赖缓存）
├── Makefile                             # Maven 封装（build/test/docker-build/offline 等）
├── docker/                              # 离线 settings.xml 模板 + OTel agent jar
├── src/
│   ├── main/
│   │   ├── java/io/agentmanager/framework/
│   │   │   ├── AgentFrameworkApplication.java   # Spring Boot 入口
│   │   │   ├── config/                          # 属性绑定 / Bean 装配（AgentScope / A2A / Channel / Sandbox / Otel）
│   │   │   ├── model/OafConfig.java             # OAF 配置模型（Java Record）
│   │   │   ├── controller/                      # REST + SSE + A2A 控制器
│   │   │   ├── service/                         # 运行时 / 技能 / MCP / 沙箱 / 追踪 / 状态
│   │   │   │   ├── storage/                     # 文件存储后端（LocalFileStorage / S3FileStorage）
│   │   │   ├── sandbox/opensandbox/             # OpenSandbox 沙箱集成
│   │   │   └── tool/                            # @Tool 自定义工具
│   │   └── resources/
│   │       ├── application.yml                  # Spring Boot 配置
│   │       └── static/debug/                    # 调试页面（拆分架构：index.html + css/js/modules）
│   └── test/                                    # 61 个测试类
├── example/                                     # 示例包（审批 Demo：HITL + MCP App 卡片）
├── docs/                                        # 设计与改进方案（26 份，详见 docs 内 README/索引）
└── README.md                                    # 本文件
```

完整目录树（每个类/控制器/表）见 [AGENTS.md](AGENTS.md) §目录结构。

---

## 快速开始

```bash
# 1) 构建
mvn clean package -DskipTests            # 或：make package

# 2) 准备 OAF 配置（最小示例）
mkdir -p config && cat > config/AGENTS.md <<'YAML'
---
name: "My Agent"
vendorKey: "myorg"
agentKey: "my-agent"
version: "1.0.0"
slug: "myorg/my-agent"
description: "A custom agent"
---

You are a helpful AI assistant.
YAML

# 3) 启动（必填三项：LLM_API_KEY / LLM_MODEL_ID / LLM_BASE_URL）
LLM_API_KEY=your_api_key \
LLM_MODEL_ID=your_model_id \
LLM_BASE_URL=https://your-api-endpoint/v1 \
AGENT_CONFIG_DIR=./config \
SERVER_PORT=8100 \
java -jar target/agent-framework-2.1.0.jar

# 4) 验证
curl http://localhost:8100/health
curl http://localhost:8100/
```

### Docker

```bash
make docker-build                        # agent-framework:latest（自动下载 OTel agent jar）
docker run -d --name agent-framework -p 8100:8100 \
  -e LLM_API_KEY=... -e LLM_MODEL_ID=... -e LLM_BASE_URL=... \
  -e AGENT_CONFIG_DIR=/config -v ./config:/config \
  agent-framework:latest
```

### 离线开发镜像

适用于无法访问外网的内网环境：

```bash
make docker-save                         # 导出 agent-framework-java-dev.tar.gz
docker load < agent-framework-java-dev.tar.gz
docker run --rm -it -v $(pwd):/workspace -w /workspace \
  gaoyue1989/agent-framework:java-dev bash
# 容器内：
mvn -o clean package -DskipTests
mvn -o test                              # 离线模式
```

完整说明（含 Nexus 私有源接入）见 [docs/offline-dev-image.md](docs/offline-dev-image.md)。

---

## OAF 配置包

```
config/                                  # AGENT_CONFIG_DIR（默认 /config）
├── AGENTS.md                            # 主配置（YAML frontmatter + Markdown）
├── skills/                              # 可选：本地技能（运行时动态加载，无需重启）
│   └── <skill-name>/
│       ├── SKILL.md
│       └── scripts/tool.py
└── mcp-configs/                         # 可选：MCP 服务器
    └── <server-name>/
        ├── ActiveMCP.json               # 工具子集（selectedTools[].enabled）
        └── config.yaml                  # connection / auth / permissions / ui
```

**关键行为**

- `/config/skills` 注册为 L2 `FileSystemSkillRepository`（**只读、source=`oaf-package`**），`HarnessSkillMiddleware` 每轮推理重扫；PVC 上目录原位新增/修改/删除，**下一轮即生效**（不重启）。详见 [docs/oaf-skills-dynamic-loading-plan.md](docs/oaf-skills-dynamic-loading-plan.md)。
- `config.yaml` 支持 `permissions.read_only: true` 强制只读绕过 HITL 授权、`ui.tools.{tool}.resource_uri` 声明 `ui://` 资源、`ui.app_only: true` 仅卡片不入 LLM 工具集。详见 [docs/mcp-apps-extension-plan.md](docs/mcp-apps-extension-plan.md)。
- `tools.json` 只写 `deny` 列表；不写 `allow`（保留全部 Harness 内置工具）。MCP 服务器由 `McpClientBuilder` 原生注册。

---

## 服务端点

主对话入口为 **`POST /threads/{sessionId}/chat`**（无状态单次流 SSE）。完整参数与 SSE 帧格式见 [docs/api.md](docs/api.md)。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 服务信息 + 协议声明 |
| GET | `/metadata` | 完整 Agent 元数据（`?includeDetails=true` 含 tools/subAgents/model） |
| GET | `/health` | 健康检查 |
| GET | `/.well-known/agent-card.json` | Agent Card |
| GET | `/skills` | 技能列表（frontmatter 声明 ∪ `/config/skills` 目录事实合并） |
| GET | `/mcp` | MCP 服务器列表 |
| GET | `/tools` | 工具列表 |
| GET | `/debug` | 调试页面（302 → `/debug/`） |
| GET | `/system-prompt` | 系统提示词 |
| GET | `/threads` | Thread 列表 |
| GET | `/threads/{sid}/history` | 历史消息 + pendingConfirm（含文件下载卡片补齐） |
| GET | `/threads/{sid}/llm-calls` | LLM 调用记录 |
| POST | `/threads/{sid}/chat` | **主对话入口** 单次流 SSE（`{message?, userId?, fileIds?}`，Turn 租约排队 waiting 帧） |
| POST | `/threads/{sid}/confirm` | HITL 同步确认 |
| POST | `/threads/{sid}/confirm-stream` | HITL 流式确认（新执行段重新 acquire 租约） |
| POST | `/files/upload` | 文件上传（multipart，MIME/大小/pending 上限校验） |
| GET | `/files/{fileId}` | 文件下载/预览（`?inline=1` 内联） |
| GET | `/chat/stream` | Channel SSE 一次性流对话（旧，保留兼容） |
| GET | `/mcp/{server}/resources/ui` | MCP Apps：拉取工具 UI 资源（HtmlResource，CSP 注入） |
| GET | `/mcp/{server}/resources` | MCP Apps：列出服务器资源 |
| POST | `/mcp/{server}/tools/{tool}` | MCP Apps：卡片工具调用代理（ask 工具 403 → 走确认流） |
| POST | `/mcp/ui-context` | MCP Apps (4.7)：静默更新模型上下文 |
| POST | `/` | A2A JSON-RPC（message/send, message/stream, tasks/get, tasks/cancel, tasks/resubscribe） |

---

## 自定义工具（@Tool 注解）

| 工具 | 说明 |
|------|------|
| `get_current_time(timezone)` | 返回指定 IANA 时区当前时间 |
| `echo(text)` | 回显输入 |
| `present_file(file_path, file_content_base64?)` | 工作区产物注册到平台供用户下载（结果由 SSE 层合成 `file_ready` 帧） |
| `check_oaf_package(agents_md)` | OAF 包 AGENTS.md frontmatter 预校验 |
| `create_oaf_zip(package_name, agents_md, extra_files?)` | 生成 OAF 部署包 zip 并注册下载 |

注册方式：实现类声明为 Spring Bean，框架自动收集 `tool/` 包下所有 `@Tool` 方法并通过 `AgentScopeConfig.customTools` 注入 Toolkit。

**MCP 工具**通过 `McpToolRegistrar` 从 `mcp-configs/{server}/config.yaml` 注册，三种传输 `sse` / `streamableHttp` / `stdio`，`auth.token` 支持 `${ENV_VAR}` 语法。详见 [AGENTS.md](AGENTS.md) §工具体系。

---

## 核心架构（高层）

```
请求 (POST /threads/{sid}/chat 或 A2A POST /)
  │
  ▼ ChatUiChannel / AgentScopeA2aServer
TurnLeaseStore.acquire()  ── 抢租约（wait 15s 发 waiting 帧，120s 超时）
  │
  ▼ HarnessAgent.streamEvents(RuntimeContext(userId, sessionId))
  ├── Memory / Compaction / Plan Mode / Skill 自学习
  ├── Toolkit: @Tool 工具 + MCP 工具（含 MCP Apps UI 元数据）
  ├── Filesystem: RemoteFilesystemSpec(USER) [默认] / OpenSandboxFilesystemSpec [沙箱模式]
  ├── 工具类事件 → ToolAuditStore 异步批量落库
  └── permission_ask (HITL) → ConfirmContextStore 落库 + release 锁
  │
  ▼ AGENT_END / error → 关闭流 + release 租约 + 停续租
```

关键设计：

- **无状态单次流**：每请求抢 Turn 租约（`turn_lease` 表，60s TTL + 20s 续租），事件直吐、执行完即关闭；HITL 暂停点释放锁让出，下次 `confirm-stream` 新执行段。详见 [docs/stateless-single-stream-plan.md](docs/stateless-single-stream-plan.md)。
- **多租户**：`IsolationScope.USER` + `RuntimeContext(userId, sessionId)`；`AgentState` 按 `(userId, sessionId)`、`MEMORY.md/memory/skills/sessions` 按 userId 隔离。
- **MysqlDistributedStore**：`agent_state` + `agent_fs` 表，启动自动建表，结构见 [docs/checkpoint-design.md](docs/checkpoint-design.md)。
- **文件存储双后端**：`FILE_STORAGE_TYPE=local`（`/data/files`）/`s3`（七牛云兼容）。详见 [docs/file-upload-download-plan.md](docs/file-upload-download-plan.md)。
- **沙箱**：`SANDBOX_ENABLED=true` 时 `filesystem` 切到 OpenSandbox，`WorkspaceSyncService` 每次请求后回写 MEMORY.md/memory/ → agent_fs。详见 [docs/opensandbox-integration-plan.md](docs/opensandbox-integration-plan.md)。
- **追踪**：OTel Java Agent v2.12.0（设 `OTEL_EXPORTER_OTLP_ENDPOINT` 即注入 `-javaagent`），自研 OtelConfig/HttpTracingFilter 为无 Agent 环境 fallback。详见 [docs/tracing-design.md](docs/tracing-design.md)。

---

## 关键环境变量

完整列表见 [AGENTS.md](AGENTS.md) §环境变量 与 [docs/api.md](docs/api.md) §清理配置。

| 变量 | 默认 | 必填 | 说明 |
|------|------|------|------|
| `LLM_API_KEY` | — | ✓ | LLM API 密钥 |
| `LLM_MODEL_ID` | — | ✓ | 模型 ID |
| `LLM_BASE_URL` | — | ✓ | LLM API 端点 |
| `LLM_PROVIDER` | `openai` | | 提供商标识 |
| `LLM_TEMPERATURE` | `0.7` | | 生成温度 |
| `LLM_MAX_TOKENS` | `4096` | | 最大输出 token |
| `LLM_TIMEOUT` | `120` | | API 超时（秒） |
| `AGENT_CONFIG_DIR` | `/config` | | OAF 配置目录 |
| `SERVER_PORT` | `8100` | | 服务端口 |
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | | MySQL JDBC URL |
| `CHECKPOINT_DB_NAME` | — | | agent_state 所在库名（可选，未设时自动从 URL 解析） |
| `CHECKPOINT_USERNAME` | `agent_manager` | | MySQL 用户 |
| `CHECKPOINT_PASSWORD` | `Agent@Manager2026` | | MySQL 密码 |
| `SANDBOX_ENABLED` | `false` | | 沙箱模式开关（true 时文件/Shell 在 OpenSandbox 隔离执行） |
| `SANDBOX_IMAGE` | `opensandbox/code-interpreter:v1.1.0` | | 沙箱镜像 |
| `SANDBOX_ENTRYPOINT` | `/opt/code-interpreter/code-interpreter.sh` | | 沙箱启动命令（逗号分隔） |
| `SANDBOX_EXECD_GRACE_SHUTDOWN` | `100ms` | | execd 命令 SSE 尾窗保持（注入容器 `EXECD_API_GRACE_SHUTDOWN`） |
| `OPENSANDBOX_SERVER_URL` | `192.168.31.155:8090` | | OpenSandbox Server 地址 |
| `OPENSANDBOX_API_KEY` | — | ✓(沙箱) | OpenSandbox API 密钥 |
| `FILE_*` | 见 file-upload-download-plan | | 文件上传/下载/存储后端（`FILE_STORAGE_TYPE=local/s3`，上传上限 20MB 等） |
| `AGENT_CLEANUP_*` | 见 api.md | | confirm TTL / turn 租约 / 审计与会话保留期 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | | 设即启用 OTel Java Agent 追踪（**勿**设 `OTEL_TRACES_EXPORTER`） |

---

## 测试

```bash
mvn test                                  # 61 个测试类 / 456 个 @Test（默认跳过 4 个沙箱集成测试）
mvn -o test                               # 离线模式（离线开发镜像内）
```

按类别分布详见 [docs/agent-framework-test.md](docs/agent-framework-test.md) §测试统计。
端到端验证、Mock 行为与 fixture 见同文档。

---

## 调试

```bash
# 启动后浏览器访问
http://localhost:8100/debug/

# 关键数据端点
curl http://localhost:8100/debug/config/oaf    # OAF + 技能合并视图
curl http://localhost:8100/debug/database/status
curl http://localhost:8100/debug/sandbox       # 沙箱模式
curl http://localhost:8100/debug/memory
curl http://localhost:8100/debug/workspace
```

调试页架构与功能模块见 [docs/debug-page-refactor-plan.md](docs/debug-page-refactor-plan.md)。

---

## 文档索引

| 主题 | 文档 |
|------|------|
| 模块总览（目录树/AgentScope 功能/端点/工具/环境变量） | [AGENTS.md](AGENTS.md) |
| REST/SSE/API/表/清理配置 | [docs/api.md](docs/api.md) |
| 总体设计（架构/模块分层/请求流程/OAF 规范/MCP 集成/依赖） | [docs/agent-framework-design.md](docs/agent-framework-design.md) |
| 部署（构建/Docker/镜像/OTel/FAQ） | [docs/agent-framework-deploy.md](docs/agent-framework-deploy.md) |
| 测试（分层/用例清单/统计/手动验证） | [docs/agent-framework-test.md](docs/agent-framework-test.md) |
| 存储与多租户（`MysqlDistributedStore` + `agent_state` + `agent_fs`） | [docs/checkpoint-design.md](docs/checkpoint-design.md) |
| 离线开发镜像 | [docs/offline-dev-image.md](docs/offline-dev-image.md) |
| 改进方案（19 份） | 见 [docs/](docs/) 各文件头部「现状核对（2026-09-07）」块 |

---

## 维护约定

- 任何对端点、控制器、表结构、测试数（61/456）、环境变量默认值、`/config/skills` L2 仓库行为等已落地事项的修改，需同步更新：
  1. 涉及的具体方案文档（[docs/](docs/)）
  2. [docs/api.md](docs/api.md) / [docs/agent-framework-design.md](docs/agent-framework-design.md) / [docs/agent-framework-deploy.md](docs/agent-framework-deploy.md) / [docs/agent-framework-test.md](docs/agent-framework-test.md) / [docs/checkpoint-design.md](docs/checkpoint-design.md) 对应章节
  3. [AGENTS.md](AGENTS.md) 目录树、端点表、AgentScope 功能表、环境变量表、测试统计
  4. [README.md](README.md)（本文件）端点表/环境变量表/工具表（如新增/废弃）
- 文档内禁止存明文 LLM/中间件 API key；如必须示例，使用掩码（如 `sk-WBHF2x…`）。
- 「计划/方案」类文档头部加「**现状核对（YYYY-MM-DD）**」块，注明与代码的偏差与实施日期。
