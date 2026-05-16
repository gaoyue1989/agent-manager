# 新 Agent 框架实施计划

**版本:** v1.0  
**日期:** 2026-05-16  
**状态:** 待确认

---

## 1. 目标

基于 DeepAgents 实现一个 **独立可运行的 Agent 框架**，支持 OAF 配置规范、A2A/A2UI 协议，并提供 Docker 化部署和完整测试。

### 核心要求

| 项目 | 说明 |
|------|------|
| Agent 内核 | DeepAgents (langchain-ai/deepagents v0.5.5+) |
| 配置规范 | OAF v0.8.0 (AGENTS.md + skills/ + mcp-configs/) |
| 对外接口 | A2A v1.0.0 (JSON-RPC 2.0 + REST) + A2UI v0.8 (A2A Extension) |
| 前端调试页 | 内嵌流式对话页面，直接调试 Agent |
| 部署方式 | Docker 镜像，配置通过挂载文件方式加载 |

---

## 2. 现有实现分析

### 已具备的能力

| 组件 | 位置 | 状态 |
|------|------|------|
| OAF 配置解析 (Go) | `backend/internal/model/oaf_config.go` | 已有 |
| OAF 目录脚手架生成 | `codegen/core/scaffold_generator.py` | 已有 |
| A2A Server 代码生成骨架 | `codegen/frameworks/deepagents/a2a_server.py` | 已有（字符串拼接，需改为模板） |
| A2A Client | `codegen/frameworks/deepagents/a2a_client.py` | 已有 |
| A2UI Extension | `codegen/frameworks/deepagents/a2ui_extension.py` | 已有 |
| Agent Card 生成 | `codegen/frameworks/deepagents/agent_card_gen.py` | 已有 |
| LLM 配置 | `codegen/frameworks/deepagents/llm_config.py` | 已有 |
| Skill 代码生成 | `codegen/frameworks/deepagents/skill_code_gen.py` | 已有 |
| Research Agent 示例 | `codegen/examples/research-agent/` | 已有（综合性示例） |
| Dockerfile 模板 | `codegen/frameworks/deepagents/templates/Dockerfile.j2` | 已有 |

### 现有不足

1. **A2A Server 代码生成**：使用字符串拼接 f-string，维护性差，需改为 Jinja2 模板
2. **无独立可运行的 Agent 框架执行器**：Research Agent 示例虽然功能完整，但耦合在 codegen 模块中，不是独立框架
3. **流式传输不完整**：`message/stream` 方法实现粗糙，仅 SSE 包裹一次结果
4. **MCP 集成**：仅加载配置，未实际连接 MCP Server（`DeepAgentsOAF` 只将 MCP 信息注入 system prompt）
5. **前端调试页面**：现有 frontend 是管理平台，没有 Agent 调试对话页面
6. **Docker 配置挂载**：现有 Dockerfile 在构建时 COPY 代码，不支持运行时挂载配置
7. **测试覆盖**：E2E 测试依赖真实 LLM 调用，缺少 mock 和边界测试

---

## 3. 架构设计

### 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│                    新 Agent 框架 (Python)                      │
│                                                              │
│  ┌─────────────┐   ┌──────────────┐   ┌──────────────────┐  │
│  │  OAF Loader  │   │ DeepAgents   │   │  A2A Server      │  │
│  │  (AGENTS.md  │──▶│ Runtime      │──▶│  + A2UI Ext      │  │
│  │   +skills/   │   │ (agent +     │   │  + Streaming     │  │
│  │   +mcp/)     │   │  skills +    │   │  + Debug Page    │  │
│  └─────────────┘   │  mcp+tools)  │   └──────────────────┘  │
│                     └──────────────┘                         │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                    配置文件挂载                           │ │
│  │   /config/AGENTS.md (必需)                               │ │
│  │   /config/skills/  (可选)                                │ │
│  │   /config/mcp-configs/ (可选)                            │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 目录结构 (新框架目录)

```
agent-framework/                       # 新框架根目录
├── AGENTS.md                          # 框架自身说明
├── server/
│   ├── __init__.py
│   ├── main.py                        # 启动入口
│   ├── config.py                      # 配置加载（env + config 目录）
│   ├── app.py                         # FastAPI 应用工厂
│   ├── routes/
│   │   ├── __init__.py
│   │   ├── a2a_routes.py             # A2A JSON-RPC + REST 端点
│   │   ├── agent_card.py             # Agent Card 端点
│   │   └── debug_ui.py              # 调试页面端点
│   ├── services/
│   │   ├── __init__.py
│   │   ├── oaf_loader.py            # OAF 配置加载器
│   │   ├── agent_runtime.py         # DeepAgents 运行时封装
│   │   ├── skill_manager.py         # Skill 管理器
│   │   ├── mcp_manager.py           # MCP 管理器
│   │   └── a2ui_service.py          # A2UI 服务（从 codegen 迁移）
│   ├── models/
│   │   ├── __init__.py
│   │   ├── oaf_types.py             # OAF 配置类型（Pydantic）
│   │   └── a2a_types.py             # A2A 协议类型
│   └── templates/
│       ├── __init__.py
│       └── debug_page.html           # 前端调试页面（Vue/原生 JS）
├── tests/
│   ├── __init__.py
│   ├── conftest.py                    # 测试 fixtures
│   ├── .env.test                      # 测试环境变量
│   ├── fixtures/                      # 测试 fixture 数据
│   │   ├── minimal-agent/
│   │   │   └── AGENTS.md
│   │   ├── full-agent/
│   │   │   ├── AGENTS.md
│   │   │   ├── skills/
│   │   │   │   └── bash-tool/
│   │   │   │       ├── SKILL.md
│   │   │   │       └── scripts/
│   │   │   │           └── tool.py
│   │   │   └── mcp-configs/
│   │   │       └── filesystem/
│   │   │           ├── ActiveMCP.json
│   │   │           └── config.yaml
│   │   └── mock_mcp_server.py        # 测试用 Mock MCP Server
│   ├── unit/
│   │   ├── __init__.py
│   │   ├── test_oaf_loader.py        # OAF 加载器单测
│   │   ├── test_agent_runtime.py      # Agent 运行时单测
│   │   ├── test_skill_manager.py      # Skill 管理器单测
│   │   ├── test_mcp_manager.py        # MCP 管理器单测
│   │   ├── test_a2ui_service.py       # A2UI 服务单测
│   │   ├── test_a2a_routes.py         # A2A 路由单测
│   │   └── test_config.py             # 配置加载单测
│   ├── integration/
│   │   ├── __init__.py
│   │   └── test_server_integration.py # 服务器集成测试
│   └── e2e/
│       ├── __init__.py
│       ├── test_tool_bash.py          # Tool (bash) E2E 测试
│       ├── test_mcp_e2e.py            # MCP E2E 测试
│       ├── test_skill_e2e.py          # Skill E2E 测试
│       └── test_streaming.py          # 流式传输 E2E 测试
├── Dockerfile                          # 框架 Docker 镜像
├── docker-compose.yml                  # 本地开发编排
├── requirements.txt                    # Python 依赖
├── .env.example                        # 环境变量示例
├── Makefile                            # 构建/测试命令
└── pyproject.toml                      # Python 项目配置
```

---

## 4. 核心模块设计

### 4.1 OAF Loader (`server/services/oaf_loader.py`)

功能：
- 读取 AGENTS.md 的 YAML frontmatter + Markdown body
- 解析 skills、mcpServers、agents、tools、model、config 等字段
- 加载 skills/ 目录下的 SKILL.md + scripts/
- 加载 mcp-configs/ 目录下的 ActiveMCP.json + config.yaml
- 返回标准化的 OAFConfig 对象（Pydantic 模型）

```python
class OAFConfig(BaseModel):
    name: str
    vendor_key: str
    agent_key: str
    version: str
    slug: str
    description: str
    system_prompt: str                    # AGENTS.md body (不含 frontmatter)
    skills: list[SkillConfig]
    mcp_servers: list[MCPServerConfig]
    sub_agents: list[SubAgentConfig]
    tools: list[str]
    model: ModelConfig
    runtime_config: RuntimeConfig
```

### 4.2 Agent Runtime (`server/services/agent_runtime.py`)

功能：
- 基于 OAFConfig 创建 DeepAgents Agent 实例
- 集成 Skills、MCP Tools、内置 Tools
- 提供 invoke()、stream() 接口
- 支持对话历史管理
- 支持工具调用拦截和日志

```python
class AgentRuntime:
    def __init__(self, config: OAFConfig, skill_mgr, mcp_mgr)
    def invoke(self, message: str, history: list = None) -> str
    async def invoke_stream(self, message: str, history: list = None) -> AsyncGenerator[str]
    def invoke_with_tools(self, message: str) -> dict  # 含工具调用信息
```

### 4.3 Skill Manager (`server/services/skill_manager.py`)

功能：
- 从 OAF skills 配置动态加载 Python Skill 模块
- 支持 local 和 well-known URL 两种 skill 来源
- 将 Skills 注入 DeepAgents backend

### 4.4 MCP Manager (`server/services/mcp_manager.py`)

功能：
- 根据 mcp-configs/*/config.yaml 创建 MCP 客户端连接
- 根据 ActiveMCP.json 过滤工具子集
- 将 MCP Tools 注入 Agent
- 支持 SSE、HTTP、stdio 传输
- 连接健康检查

### 4.5 A2A Routes (`server/routes/a2a_routes.py`)

功能：
- JSON-RPC 2.0 端点 (`POST /`)
- REST 端点 (`POST /tasks`, `GET /tasks/{id}`, `GET /tasks`)
- `message/send` - 同步消息
- `message/stream` - SSE 流式消息（真实流式，逐 token 推送）
- `tasks/get`, `tasks/list`, `tasks/cancel`

### 4.6 Debug UI (`server/routes/debug_ui.py` + `server/templates/debug_page.html`)

功能：
- 内嵌 Web 调试页面，支持：
  - 流式对话（SSE 接收逐 token 输出）
  - A2UI 组件渲染
  - 工具调用过程可视化
  - 对话历史管理
  - Agent Card 查看
  - Skills/MCP 状态查看
- 纯前端实现（原生 JS/CSS，无额外构建工具）

### 4.7 A2UI Service (`server/services/a2ui_service.py`)

从 `codegen/frameworks/deepagents/a2ui_extension.py` 迁移并增强：
- 支持 surfaceUpdate / dataModelUpdate / beginRendering / endRendering
- 标准组件目录集成
- 流式 JSONL 生成

---

## 5. Docker 部署设计

### Dockerfile

```dockerfile
FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server/ ./server/

EXPOSE 8000

ENV PYTHONUNBUFFERED=1

# /config 目录通过挂载提供
VOLUME ["/config"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["python", "-m", "server.main"]
```

### docker-compose.yml

```yaml
services:
  agent-framework:
    build: .
    ports:
      - "8000:8000"
    volumes:
      - ./config:/config              # 挂载整个配置目录
    environment:
      - AGENT_CONFIG_DIR=/config      # 配置目录路径
      - LLM_API_KEY=xxx
      - LLM_MODEL_ID=xxx
      - LLM_BASE_URL=xxx
```

### 配置挂载结构

```
config/                    # 挂载到 /config
├── AGENTS.md              # 必需
├── skills/                # 可选
│   └── bash-tool/
│       ├── SKILL.md
│       └── scripts/
│           └── tool.py
└── mcp-configs/           # 可选
    └── filesystem/
        ├── ActiveMCP.json
        └── config.yaml
```

---

## 6. 测试计划

### 6.1 单元测试

| 测试文件 | 测试内容 |
|---------|---------|
| `test_config.py` | 环境变量加载、配置验证、默认值 |
| `test_oaf_loader.py` | AGENTS.md 解析、YAML frontmatter、Markdown body、字段验证 |
| `test_agent_runtime.py` | Agent 创建、invoke/stream 接口、历史管理 |
| `test_skill_manager.py` | Skill 加载、local/remote 来源、SKILL.md 解析 |
| `test_mcp_manager.py` | ActiveMCP.json 解析、config.yaml 解析、工具过滤 |
| `test_a2ui_service.py` | surfaceUpdate 生成、text-to-a2ui、JSONL 流 |
| `test_a2a_routes.py` | JSON-RPC 格式、任务状态管理、错误处理 |

### 6.2 集成测试

| 测试文件 | 测试内容 |
|---------|---------|
| `test_server_integration.py` | FastAPI 启动、健康检查、Agent Card、路由注册 |

### 6.3 E2E 测试（使用真实 LLM）

| 测试文件 | 测试内容 |
|---------|---------|
| `test_tool_bash.py` | Bash 工具调用（如 `ls`, `echo`），验证真实执行 |
| `test_mcp_e2e.py` | MCP Server 连接与工具调用（Mock MCP Server 或真实 Filesystem MCP） |
| `test_skill_e2e.py` | Skill 加载与执行，验证自定义 Skill 逻辑 |
| `test_streaming.py` | SSE 流式传输，逐 Token 接收验证 |

### 测试配置

```bash
# .env.test
LLM_API_KEY=your_api_key_here
LLM_MODEL_ID=your_model_id_here
LLM_BASE_URL=https://wishub-x6.ctyun.cn/v1
LLM_PROVIDER=ctyun
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=4096
AGENT_CONFIG_DIR=./tests/fixtures/full-agent
```

---

## 7. 实施步骤

| 步骤 | 内容 | 预计工时 |
|------|------|---------|
| **Step 1** | 创建 `agent-framework/` 目录结构，`pyproject.toml`，`requirements.txt` | 小 |
| **Step 2** | 实现 `config.py` - 配置加载（环境变量 + 配置目录） | 小 |
| **Step 3** | 实现 `oaf_types.py` - OAF Pydantic 类型定义 | 中 |
| **Step 4** | 实现 `oaf_loader.py` - OAF 配置解析器（AGENTS.md + skills/ + mcp-configs/） | 中 |
| **Step 5** | 实现 `skill_manager.py` - Skill 加载器 | 中 |
| **Step 6** | 实现 `mcp_manager.py` - MCP 管理器 | 中 |
| **Step 7** | 实现 `agent_runtime.py` - DeepAgents 运行时 | 中 |
| **Step 8** | 迁移并增强 `a2ui_service.py`（从 codegen 模块迁移） | 小 |
| **Step 9** | 实现 `a2a_routes.py` - A2A 端点（JSON-RPC + REST + SSE 流式） | 中 |
| **Step 10** | 实现 `agent_card.py` - Agent Card 端点 | 小 |
| **Step 11** | 实现 `debug_ui.py` + `debug_page.html` - 前端调试页面 | 中 |
| **Step 12** | 实现 `main.py` + `app.py` - 启动入口和应用工厂 | 小 |
| **Step 13** | Dockerfile + docker-compose.yml | 小 |
| **Step 14** | 编写测试 fixtures 和 mock 数据 | 中 |
| **Step 15** | 单元测试：test_config, test_oaf_loader, test_skill_manager | 中 |
| **Step 16** | 单元测试：test_mcp_manager, test_a2ui_service, test_agent_runtime | 中 |
| **Step 17** | 单元测试：test_a2a_routes | 中 |
| **Step 18** | 集成测试：test_server_integration | 中 |
| **Step 19** | E2E 测试：test_tool_bash (真实 LLM + Bash Tool) | 中 |
| **Step 20** | E2E 测试：test_mcp_e2e (Mock MCP Server) | 中 |
| **Step 21** | E2E 测试：test_skill_e2e (真实 LLM + Skill) | 中 |
| **Step 22** | E2E 测试：test_streaming (SSE 流式) | 小 |
| **Step 23** | 端到端验证：启动 Docker 容器，全流程测试 | 中 |
| **Step 24** | 文档完善 | 小 |

---

## 8. 技术依赖

```txt
deepagents>=0.5.0
langchain>=1.0.0
langchain-openai>=1.0.0
langchain-mcp-adapters>=0.1.0
fastapi>=0.100.0
uvicorn[standard]>=0.30.0
pydantic>=2.0.0
pyyaml>=6.0.0
httpx>=0.25.0
python-dotenv>=1.0.0
sse-starlette>=2.0.0   # SSE 流式支持
# 测试
pytest>=8.0.0
pytest-asyncio>=0.24.0
pytest-cov>=5.0.0
httpx>=0.25.0
```

---

## 9. 与现有项目的关系

| 关系 | 说明 |
|------|------|
| **独立运行** | agent-framework 是独立 Python 包，不依赖 backend/frontend/codegen |
| **复用 codegen** | A2UI Extension、AgentCard、LLMConfig 等稳定模块从 codegen 迁移/引用 |
| **替换后端 Agent 执行** | 未来可替代当前 backend 中通过 `kubectl exec curl` 调用 Agent 的方式 |
| **前端集成** | Debug Page 可嵌入管理平台作为 Agent 测试入口 |

---

## 10. 风险与注意事项

1. **DeepAgents 版本兼容**：DeepAgents v0.5.5 的 `create_deep_agent` API 可能变动，需要锁定版本
2. **MCP 连接稳定性**：容器内 MCP 服务可能不可用，需要优雅降级
3. **流式传输**：DeepAgents 的流式接口需要验证其 async generator 支持
4. **挂载文件热更新**：第一期不支持热更新，修改配置需重启容器
5. **调试页面前端**：保持轻量，单 HTML 文件实现，不引入 npm 构建

---

## 确认项

请确认以下内容后开始实施：

1. [ ] 框架目录命名为 `agent-framework/`，放在项目根目录下，是否合适？
2. [ ] 配置挂载路径 `/config` 是否合适？
3. [ ] 调试页面使用单 HTML 文件内嵌，不引入前端构建工具，是否接受？
4. [ ] E2E 测试使用指定的天翼云 GLM-5 模型，API Key 写入 `.env.test`（不提交 Git），是否接受？
5. [ ] 是否需要支持 OpenAI 兼容 API 以外的模型提供商？
