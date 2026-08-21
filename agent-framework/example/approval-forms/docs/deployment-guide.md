# 部署与创建指南（审批 Demo + agent-framework）

> 本文档面向：把「审批 Demo」与 `agent-framework` 部署到本地/K8s 环境，并说明 Demo 前端
> 使用到的 agent-framework API。包含 **裸 JAR 启动** 与 **Docker 镜像启动** 两种方式。

---

## 1. 架构总览

```
浏览器 :8913
   │
   ▼
┌──────────────┐        ┌─────────────────────────┐
│ proxy.py     │ 反代   │ agent-framework :8100    │
│ (静态+代理)   │───────▶│ (Spring Boot + HarnessAgent)│
└──────────────┘        └────────────┬────────────┘
                                     │ MCP streamableHttp
                                     ▼
                              mock MCP :8813
                          (approval_mcp.py, 标准库零依赖)
```

- **前端**（`approval-forms/ui/`）：同源页 + MCP App 卡片宿主，经 `proxy.py` 与后端同源（无 CORS）。
- **后端**（agent-framework）：Channel 会话 + HITL 权限确认 + MCP 工具注册。
- **MCP 模拟服务**（`approval_mcp.py`）：实现 `initialize / notifications/initialized / ping / tools/list / resources/read / tools/call` 六个方法，业务工具见 §4.2。

---

## 2. 前置条件

| 依赖 | 说明 |
|------|------|
| JDK 21+ | 运行 agent-framework |
| Maven 3.9+ | 构建（仅源码部署需要） |
| GreatSQL/MySQL :3307 | agent_state / agent_fs / confirm_context 等表（库 `agent_manager_test`） |
| LLM 配置 | `LLM_API_KEY` / `LLM_MODEL_ID` / `LLM_BASE_URL`（OpenAI 兼容 API） |
| Python 3 | 跑 mock MCP 与 proxy（标准库，无需 pip） |

---

## 3. 部署方式

### 3.1 方式 A：源码 + 裸 JAR 启动（开发/调试）

```bash
# 1) 构建 agent-framework
cd agent-framework
mvn package -DskipTests            # 产物 target/agent-framework-2.1.0.jar

# 2) 启动 mock MCP + 页面代理
cd example/approval-forms
./start.sh                          # mock :8813 + proxy :8913（后台运行）

# 3) 启动 agent-framework（另开终端）
cd agent-framework
LLM_API_KEY=sk-xxx \
LLM_MODEL_ID=mimo-v2.5 \
LLM_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1 \
AGENT_CONFIG_DIR=/root/agent-manager/agent-framework/example/approval-forms/agent-config \
java -jar target/agent-framework-2.1.0.jar
```

浏览器访问 <http://localhost:8913/>。

### 3.2 方式 B：Docker 镜像启动（生产/隔离环境）

镜像构建与运行：

```bash
# 1) 构建镜像（含 OTel agent，见 docs/tracing-design.md）
cd agent-framework
make docker-build                    # 产物 docker.io/agent-framework:latest（或设 IMAGE=xx）

# 2) 运行容器：挂载 Agent 配置目录，注入 LLM/DB 环境变量
docker run -d --name agent-framework -p 8100:8100 \
  -e LLM_API_KEY=sk-xxx \
  -e LLM_MODEL_ID=mimo-v2.5 \
  -e LLM_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1 \
  -e CHECKPOINT_JDBC_URL='jdbc:mysql://172.20.0.1:3307/agent_manager_test' \
  -e CHECKPOINT_USERNAME=agent_manager \
  -e CHECKPOINT_PASSWORD='密码' \
  -e AGENT_CONFIG_DIR=/config \
  -v /绝对路径/approval-forms/agent-config:/config \
  agent-framework:latest
```

要点：

- **LLM 三件套必填**（`config.go`/`AgentManagerProperties` 无默认值，缺失直接启动失败）。
- **AGENT_CONFIG_DIR=/config**，把 `agent-config/` 目录整体挂载（内含 `AGENTS.md`、`skills/`、`mcp-configs/approval/config.yaml`）。
- **Checkpoint 主机名**：容器内无法直连宿主 `127.0.0.1`，用 Docker 网关 `172.20.0.1`（或 host 网络）。
- 非 root 运行（镜像内置 `appuser`），`/config` 已 `chmod 777` 保证可读写。
- 磁盘/网络受限的内网环境用离线镜像：`make docker-build-dev && make docker-save`，目标机 `make docker-load`。

### 3.3 mock MCP 与 proxy 的容器化（可选）

Demo 的 mock 与 proxy 是纯 Python 标准库，可直接挂到同一网络：

```bash
docker run -d --name approval-mock --network host \
  -v /绝对路径/approval-forms:/app  python:3.12-slim \
  python3 /app/mock-mcp/approval_mcp.py        # :8813

docker run -d --name approval-proxy --network host \
  -v /绝对路径/approval-forms:/app  python:3.12-slim \
  python3 /app/proxy.py --port 8913 --backend 127.0.0.1:8100   # :8913
```

---

## 4. agent-framework API 使用说明（Demo 相关）

> 路径规范（O7）：会话业务接口统一在 `/threads` 下，页面数据端点保留 `/debug`。
> 单次流架构：`POST /threads/{sid}/chat` 直接返回 SSE，事件实时直吐；已删除长连接订阅端点。

### 4.1 会话与对话

| 方法 | 路径 | 说明 | Demo 前端使用 |
|------|------|------|---------------|
| `GET` | `/health` | 健康检查（含 `slug`/`llm_configured`） | 启动自检 |
| `GET` | `/threads` | 会话列表（按更新时间倒序） | 侧栏会话列表 |
| `GET` | `/threads/{sid}/history` | 会话历史（块级消息含 tool_calls；附 `pendingConfirm`） | 刷新/回显历史 |
| `POST` | `/threads/{sid}/chat` | **单次流对话**：body `{message, userId}`，SSE 直吐 Agent 事件词表 | 发送消息送 `api.triggerSessionChat` |
| `POST` | `/threads/{sid}/confirm-stream` | HITL 确认流：body `{results:[{tool_call_id, confirmed, accept_rule}]}`，SSE 恢复执行事件；404=上下文不存在、409=已消费 | 批准/拒绝后恢复提交 |

SSE 事件词表（`handleEvent` 直接消费）：

| 事件 | 用途 |
|------|------|
| `AGENT_START` / `AGENT_END` | turn 开始/结束 |
| `TEXT_BLOCK_DELTA` | 回复文本流式增量 |
| `THINKING_BLOCK_*` | 思维链增量 |
| `TOOL_CALL_START`（带 `ui` 字段）/`TOOL_CALL_DELTA`/`TOOL_CALL_END` | 工具调用（`ui: {resourceUri, server}` 触发 MCP App 卡片） |
| `TOOL_RESULT_START`/`TOOL_RESULT_TEXT_DELTA`/`TOOL_RESULT_END` | 工具结果 |
| `permission_ask` | HITL 需要人工审批（工具配置 `ask` 时由框架发出） |
| `error` / `done` | 错误 / 流结束 |

### 4.2 MCP 工具代理与卡片

| 方法 | 路径 | 说明 | Demo 使用 |
|------|------|------|-----------|
| `GET` | `/mcp` | MCP 服务器列表（含 `tool_count` / `has_ui`） | 「工具列表」面板 |
| `GET` | `/tools` | 工具列表（`{mcpCount, totalCount, tools:[...]}`，含 `uiResourceUri`/`appOnly`） | 「工具列表」面板 |
| `GET` | `/mcp/{server}/resources/ui?uri=ui://...` | 拉取 MCP App 卡片 HTML（含 CSP 注入） | `McpAppHost.mount` 读卡片资源 |
| `POST` | `/mcp/{server}/tools/{tool}` | 卡片内工具调用代理：body `{arguments, confirmed}`；`ask` 工具未确认返回 403+`needsConfirm`，前端弹确认卡后带 `confirmed=true` 重试 | 卡片「确认表单」→ `confirm_application` |
| `POST` | `/mcp/ui-context` | 静默更新模型上下文：body `{sessionId, content, structuredContent}`；`UiContextInjectionHook` 在下一次 PreCall 时注入 | 卡片确认后让 Agent 感知「表单已确认」 |

Mock MCP 业务工具（`mcp-configs/approval/config.yaml` 权限声明）：

| 工具 | 权限 | 说明 |
|------|------|------|
| `create_application` | `allow` | 创建申请单（title/description/file_links）→ application_id |
| `show_application_form` | `allow` | 展示 MCP App 卡片（`ui://approval/application-form.html`） |
| `get_application` | `allow` | 查询申请单状态（含 stage） |
| `confirm_application` | `allow` | 卡片内部确认/反悔表单（**app 专用，SKILL.md 禁止 LLM 直调**） |
| `submit_application` | **`ask`** | 提交审批——每次调用均触发 HITL 人工确认 |

> ⚠️ config.yaml **不要**加 `ui.app_only` 或 `permissions.read_only`：
> 这会触发 `registerReadOnly` 路径，工具注册为只读后 `checkPermissions` 直接放行，
> **HITL ask 确认流被短路**（详见 `McpToolRegistrar`）。

### 4.3 配置目录结构（AGENT_CONFIG_DIR）

```
agent-config/
├── AGENTS.md                       # OAF 配置 frontmatter + 系统提示（含「提交成功后禁止重试」约束）
├── skills/approval-flow/SKILL.md   # 审批流程技能（步骤顺序）
└── mcp-configs/approval/
    ├── config.yaml                 # 连接(streamableHttp :8813) + 权限(allow/ask)
    └── ActiveMCP.json              # （可选）工具子集过滤；Demo 刻意不添加（见上方警告）
```

---

## 5. 环境变量速查（agent-framework）

| 变量 | 必填 | 默认 | Demo 说明 |
|------|------|------|-----------|
| `LLM_API_KEY` | ✓ | — | LLM API 密钥 |
| `LLM_MODEL_ID` | ✓ | — | 模型 ID（Demo 用 mimo-v2.5） |
| `LLM_BASE_URL` | ✓ | — | OpenAI 兼容端点 |
| `LLM_PROVIDER` | | openai | 提供商标识 |
| `AGENT_CONFIG_DIR` | ✓ | /config | 指向 `approval-forms/agent-config` |
| `SERVER_PORT` | | 8100 | 服务端口 |
| `CHECKPOINT_JDBC_URL` | | jdbc:mysql://127.0.0.1:3307/agent_manager_test | 容器内用 `172.20.0.1` |
| `CHECKPOINT_USERNAME` / `CHECKPOINT_PASSWORD` | | agent_manager / Agent@Manager2026 | 数据库账号 |
| `CONFIRM_TTL_MINUTES` | | 30 | HITL 确认上下文 TTL（confirm_context 表） |
| `TURN_LEASE_TTL_SECONDS` | | 60 | 会话执行租约 TTL（turn_lease 表） |
| `TURN_LEASE_RENEW_SECONDS` | | 20 | 租约续租周期 |

---

## 6. 一键自检（Demo 快速验证）

```bash
# 健康
curl -s http://localhost:8100/health
# MCP 可达
curl -s http://localhost:8100/mcp
# 手动触发一次对话（SSE 单次流）
curl -N -X POST http://localhost:8100/threads/demo-1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好","userId":"debug-user"}'
```

完整功能/UI 验证与截图：见 `e2e/e2e-full.js`（25 项断言，运行 `node e2e/e2e-full.js`）。