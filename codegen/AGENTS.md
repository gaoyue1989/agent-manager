# CodeGen — AGENTS.md

## 模块概述

Python 代码生成模块，将 Agent 配置转换为 DeepAgents 微服务代码。

**v2.0** 新增：OAF v0.8.0 原生支持、A2A v1.0.0 通信协议、A2UI v0.8 扩展支持、Skills/MCP 加载。

---

## 目录结构

```
codegen/
├── core/
│   ├── scaffold_generator.py    # OAF 目录脚手架生成
│   ├── legacy_migrator.py       # 旧格式 → OAF 迁移
│   └── skill_packager.py        # 远程技能构建时打包
├── frameworks/
│   └── deepagents/
│       ├── agent_scaffold.py    # Agent 完整脚手架
│       ├── agent_card_gen.py    # A2A Agent Card 生成
│       ├── a2a_server.py        # A2A Server 代码生成
│       ├── a2a_client.py        # A2A Client (子 Agent)
│       ├── a2ui_extension.py    # A2UI Extension 处理
│       ├── skill_code_gen.py    # Skill 实现代码生成
│       ├── llm_config.py        # LLM 环境变量配置
│       └── templates/           # Jinja2 模板
├── examples/
│   └── research-agent/          # 完整示例
│       ├── AGENTS.md            # OAF manifest
│       ├── skills/              # 本地技能
│       ├── mcp-configs/         # MCP 配置
│       └── main.py              # A2A Server
├── tests/
│   ├── unit/                    # 单元测试
│   ├── integration/             # 集成测试
│   └── e2e/                     # 端到端测试
├── cli.py                       # CLI 入口
└── AGENTS.md                    # 本文件
```

---

## CLI 用法

```bash
# 创建 OAF 脚手架
python cli.py scaffold --name "My Agent" --output ./output/

# 迁移旧格式
python cli.py migrate --config old_config.json --output ./output/

# 从 OAF 目录生成代码
python cli.py generate --oaf ./agent-dir/ --output ./generated/
```

---

## 核心组件

| 组件 | 功能 |
|------|------|
| `ScaffoldGenerator` | 生成 OAF 目录 (AGENTS.md + skills/ + mcp-configs/) |
| `LegacyMigrator` | 转换旧 JSON 配置 → OAF |
| `SkillPackager` | 构建时下载远程技能 |
| `SkillCodeGenerator` | 生成 Skill Python 实现代码 |
| `AgentCardGenerator` | 生成 A2A Agent Card (+ A2UI extension) |
| `A2AServerGenerator` | 生成 A2A Server 代码 |
| `A2AClient` | A2A 子 Agent 调用客户端 |
| `A2UIExtension` | A2UI JSONL 生成与解析 |
| `LLMConfig` | 环境变量 LLM 配置管理 |

---

## 环境变量

```bash
export LLM_API_KEY=your_api_key
export LLM_MODEL_ID=your_model_id
export LLM_BASE_URL=https://api.example.com/v1
export LLM_PROVIDER=ctyun
export LLM_TEMPERATURE=0.7
export LLM_MAX_TOKENS=4096
```

---

## 测试

```bash
# 全量测试
python codegen/tests/run_all_tests.py --all

# 单元测试
pytest codegen/tests/unit/ -v

# LLM 集成测试
pytest codegen/tests/e2e/test_llm_integration.py -v

# Agent E2E 测试 (需启动服务)
pytest codegen/tests/e2e/test_research_agent.py -v
```

---

## 协议支持

| 协议 | 版本 | 实现方式 |
|------|------|---------|
| OAF | v0.8.0 | AGENTS.md + skills/ + mcp-configs/ |
| A2A | v1.0.0 | JSON-RPC 2.0 + REST, Agent Card discovery |
| A2UI | v0.8 | A2A Extension, surfaceUpdate/beginRendering |
