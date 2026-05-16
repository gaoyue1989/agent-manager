# Agent Framework — 测试文档

**版本:** v1.0.0  
**日期:** 2026-05-16

---

## 1. 测试概览

Agent Framework 包含三层测试体系:

| 层级 | 数量 | 说明 |
|------|------|------|
| 单元测试 | 71 | 函数/类级别, 无需外部依赖 |
| 集成测试 | 9 | 服务器启动全流程, 使用 TestClient |
| E2E 测试 | 17 | 真实 LLM + 工具调用 + 流式传输 |

---

## 2. 测试环境准备

### 2.1 安装依赖

```bash
cd agent-framework
pip install -r requirements.txt pytest pytest-asyncio pytest-cov python-dotenv httpx
```

### 2.2 配置测试环境变量

编辑 `tests/.env.test`:

```bash
LLM_API_KEY=your_api_key
LLM_MODEL_ID=your_model_id
LLM_BASE_URL=https://your-api-endpoint/v1
LLM_PROVIDER=openai
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=4096
AGENT_CONFIG_DIR=./tests/fixtures/minimal-agent
```

> 注意: `tests/.env.test` 已在 `.gitignore` 中排除，不会提交到版本控制。

---

## 3. 运行测试

### 3.1 全部测试

```bash
# 单元测试 + 集成测试
pytest tests/unit/ tests/integration/ -v

# 使用 Makefile
make test
```

### 3.2 按层级运行

```bash
# 仅单元测试 (无需 LLM API)
pytest tests/unit/ -v

# 仅集成测试
pytest tests/integration/ -v

# 仅 E2E 测试 (需要 LLM API)
pytest tests/e2e/ -v
```

### 3.3 按模块运行

```bash
# 配置测试
pytest tests/unit/test_config.py -v

# OAF 加载器测试
pytest tests/unit/test_oaf_loader.py -v

# A2A 路由测试
pytest tests/unit/test_a2a_routes.py -v

# A2UI 服务测试
pytest tests/unit/test_a2ui_service.py -v

# Agent 运行时测试
pytest tests/unit/test_agent_runtime.py -v

# Skill 管理器测试
pytest tests/unit/test_skill_manager.py -v

# MCP 管理器测试
pytest tests/unit/test_mcp_manager.py -v
```

### 3.4 E2E 按功能运行

```bash
# Bash 工具测试
pytest tests/e2e/test_tool_bash.py -v

# MCP 集成测试
pytest tests/e2e/test_mcp_e2e.py -v

# Skill 集成测试
pytest tests/e2e/test_skill_e2e.py -v

# 流式传输测试
pytest tests/e2e/test_streaming.py -v
```

### 3.5 覆盖率

```bash
pytest tests/unit/ tests/integration/ -v --cov=server --cov-report=term-missing

# 或
make test-cov
```

---

## 4. 测试用例说明

### 4.1 单元测试 (71 个)

#### test_config.py — 配置加载

| 测试 | 说明 |
|------|------|
| `test_load_config_defaults` | 验证默认配置值 |
| `test_load_config_from_env` | 验证从环境变量加载 |
| `test_llm_config_is_valid` | 验证 LLM 配置有效性检查 |
| `test_llm_config_is_invalid_when_missing_fields` | 验证缺失字段检测 |
| `test_llm_config_to_openai` | 验证 OpenAI 格式转换 |
| `test_app_config_paths` | 验证配置路径属性 |

#### test_oaf_loader.py — OAF 配置解析

| 测试 | 说明 |
|------|------|
| `test_load_minimal_agent` | 解析最小化 AGENTS.md |
| `test_load_full_agent` | 解析完整 AGENTS.md (含 skills/MCP) |
| `test_has_skills` / `test_has_mcp` | 验证 skills/MCP 检测 |
| `test_load_skill_description` | 读取 SKILL.md 描述 |
| `test_load_mcp_configs_full` | 读取 ActiveMCP.json + config.yaml |
| `test_missing_agents_md_raises_error` | 缺失文件抛异常 |
| `test_system_prompt_parsing` | Markdown body 解析 |
| `test_a2ui_config` | A2UI 配置提取 |
| `test_parse_frontmatter_*` | Frontmatter 解析边界情况 |

#### test_skill_manager.py — Skill 管理

| 测试 | 说明 |
|------|------|
| `test_load_all_skills` | 加载本地 Skill 模块 |
| `test_load_nonexistent_skill` | 加载不存在的 Skill |
| `test_load_remote_skill` | 加载远程 Skill (stub) |
| `test_invoke_skill` | 调用 Skill main() 函数 |
| `test_invoke_nonexistent_skill` | 调用不存在的 Skill |
| `test_get_skill_summaries` | 获取 Skill 摘要列表 |

#### test_mcp_manager.py — MCP 管理

| 测试 | 说明 |
|------|------|
| `test_load_configs` | 加载 MCP 配置 |
| `test_get_enabled_tools` | 过滤启用的工具 |
| `test_get_excluded_tools` | 获取排除工具列表 |
| `test_empty_mcp_dir` | 空目录处理 |
| `test_get_mcp_summaries` | MCP 摘要列表 |

#### test_a2ui_service.py — A2UI 服务

| 测试 | 说明 |
|------|------|
| `test_init_defaults` | 默认参数 |
| `test_generate_surface_update` | 生成 surfaceUpdate JSONL |
| `test_generate_data_model_update` | 生成 dataModelUpdate JSONL |
| `test_generate_begin_rendering` / `end_rendering` | 渲染指令 |
| `test_wrap_text_as_a2ui_short` | 短文转 A2UI |
| `test_wrap_text_as_a2ui_multiple_paragraphs` | 多段落转 A2UI |
| `test_extract_a2ui_from_text` | 提取 a2ui 代码块 |
| `test_generate_artifact_*` | 生成 A2UI artifact |
| `test_get_client_capabilities` | 客户端能力声明 |

#### test_agent_runtime.py — Agent 运行时

| 测试 | 说明 |
|------|------|
| `test_init` | 初始化验证 |
| `test_system_prompt_includes_instructions` | System prompt 包含指令 |
| `test_invoke_without_llm` | 无 LLM 时返回 mock 响应 |
| `test_invoke_with_skills_in_prompt` | Skills 注入 prompt |
| `test_invoke_with_mcp_in_prompt` | MCP 注入 prompt |
| `test_build_skills_context` | Skills 上下文生成 |
| `test_build_mcp_context` | MCP 上下文生成 |
| `test_invoke_direct_no_llm` | 直连路径无 LLM |
| `test_invoke_skill_nonexistent` | 调用不存在 Skill |

#### test_a2a_routes.py — A2A 路由

| 测试 | 说明 |
|------|------|
| `test_health_endpoint` | 健康检查端点 |
| `test_root_endpoint` | 根端点 |
| `test_agent_card_endpoint` | Agent Card 端点 |
| `test_skills_endpoint` / `test_mcp_endpoint` | Skills/MCP 列表 |
| `test_debug_page` | 调试页面 |
| `test_jsonrpc_send_message` | JSON-RPC 消息发送 |
| `test_jsonrpc_method_not_found` | 未知方法错误 |
| `test_jsonrpc_send_message_with_a2ui` | A2UI 消息 |
| `test_rest_tasks_endpoint` | REST 任务创建 |
| `test_tasks_list` / `test_task_get_not_found` | 任务列表/查询 |

### 4.2 集成测试 (9 个)

| 测试 | 说明 |
|------|------|
| `test_health` | 服务启动并健康 |
| `test_root` | 根信息包含协议版本 |
| `test_agent_card` | Agent Card 含 A2UI 扩展 |
| `test_skills` / `test_mcp` | Skills/MCP 列表 |
| `test_debug_page` | 调试页面可访问 |
| `test_jsonrpc_message_send` | JSON-RPC 消息 |
| `test_rest_tasks` / `test_tasks_list` | REST 任务 |

### 4.3 E2E 测试 (17 个, 需 LLM)

#### test_tool_bash.py — Bash 工具

| 测试 | 说明 |
|------|------|
| `test_health` | 服务健康 |
| `test_agent_card` | Agent Card 发现 |
| `test_simple_query` | 基础 LLM 对话 |
| `test_bash_echo` | `echo` 命令执行 |
| `test_bash_ls` | `ls /tmp` 命令执行 |

#### test_mcp_e2e.py — MCP 集成

| 测试 | 说明 |
|------|------|
| `test_health` | 服务健康 |
| `test_mcp_endpoint` | MCP 列表端点 |
| `test_agent_aware_of_mcp` | Agent 感知 MCP 工具 |

#### test_skill_e2e.py — Skill 集成

| 测试 | 说明 |
|------|------|
| `test_health` | 服务健康 |
| `test_skills_endpoint` | Skills 列表 |
| `test_agent_aware_of_skills` | Agent 感知 Skills |
| `test_bash_skill_via_agent` | 通过 Agent 调用 Skill |
| `test_pwd_skill` | `pwd` 命令 |

#### test_streaming.py — 流式传输

| 测试 | 说明 |
|------|------|
| `test_health` | 服务健康 |
| `test_sse_streaming_basic` | SSE 流式 token 接收 |
| `test_sse_streaming_events` | SSE 事件格式 |
| `test_sse_streaming_multiple_tokens` | 多 token 验证 |

---

## 5. 创建测试 Agent

### 5.1 最小化测试 Agent

创建 `tests/fixtures/minimal-agent/AGENTS.md`:

```yaml
---
name: "Minimal Test Agent"
vendorKey: "test"
agentKey: "minimal"
version: "1.0.0"
slug: "test/minimal"
description: "A minimal test agent"
author: "@test"
license: "MIT"
tags: ["test"]
---

# Agent Purpose

You are a minimal test agent.
```

### 5.2 完整测试 Agent (含 Skills + MCP)

创建 `config/AGENTS.md`:

```yaml
---
name: "Demo Agent"
vendorKey: "demo"
agentKey: "demo-agent"
version: "1.0.0"
slug: "demo/demo-agent"
description: "A demo agent with tools and skills"
author: "@demo"
license: "MIT"
tags: ["demo"]

skills:
  - name: "echo-skill"
    source: "local"
    version: "1.0.0"

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"

tools:
  - Read
  - Bash

config:
  temperature: 0.7
  max_tokens: 1024
---

# Agent Purpose

You are a demo agent for testing.

## Core Responsibilities

- Answer questions concisely
- Execute bash commands when asked
```

创建 Skill: `config/skills/echo-skill/SKILL.md`:

```yaml
---
name: "echo-skill"
description: "Echo back the input"
license: "MIT"
metadata:
  author: "demo"
  version: "1.0.0"
allowed-tools: ["python"]
---

# Echo Skill

Returns the input unchanged.
```

创建 Skill 实现: `config/skills/echo-skill/scripts/tool.py`:

```python
def main(input_data: str = None) -> str:
    return f"Echo: {input_data}"
```

### 5.3 启动并测试

```bash
AGENT_CONFIG_DIR=./config python -m server.main &
sleep 3

# 健康检查
curl http://localhost:8100/health

# 对话测试
curl -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"text":"What tools do you have?"}]}},"id":"1"}'

# 工具调用测试
curl -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"text":"用bash执行 uname -a 并告诉我结果"}]}},"id":"2"}'

# 流式输出测试
curl -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/stream","params":{"message":{"role":"user","parts":[{"text":"介绍深度学习三个要点"}]}},"id":"3"}'

# 调试页面
echo "Open http://localhost:8100/debug"
```

---

## 6. 测试 fixtures 目录

```
tests/fixtures/
├── minimal-agent/
│   └── AGENTS.md                  # 最小化配置
└── full-agent/
    ├── AGENTS.md                  # 完整配置 (skills + MCP + tools)
    ├── skills/
    │   └── bash-tool/
    │       ├── SKILL.md
    │       └── scripts/
    │           └── tool.py         # subprocess.run bash 执行器
    └── mcp-configs/
        └── filesystem/
            ├── ActiveMCP.json      # read_file + list_directory + write_file
            └── config.yaml         # SSE 连接配置
```

---

## 7. E2E 测试模型配置

E2E 测试使用真实 LLM API。当前测试配置 (`tests/.env.test`):

```bash
LLM_API_KEY=209df1cb15124118a9c4583f4c5b7997
LLM_MODEL_ID=5df2c9ff4ad347cb95ea42ad6e9e1729
LLM_BASE_URL=https://wishub-x6.ctyun.cn/v1
LLM_PROVIDER=ctyun
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=4096
```

> **注意**: 如更换模型，需确保 API 是 OpenAI 兼容格式，且模型支持 `stream: true` (流式测试需要)。

---

## 8. 测试结果示例

```
============================= test session starts ==============================
collected 97 items

tests/unit/                      71 passed
tests/integration/                9 passed
tests/e2e/                       17 passed

================== 97 passed, 2 warnings in 174.41s (0:02:54) ==================
```
