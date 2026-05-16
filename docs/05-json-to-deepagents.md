# OAF 配置 → DeepAgents 代码转换

**日期**: 2026-05-10  
**执行者**: opencode  
**版本**: v2.0 (OAF v0.8.0)

## 1. 概述

验证通过 OAF (Open Agent Format) 配置生成完整 DeepAgents 代码的完整链路。

## 2. OAF 配置格式

### 2.1 目录结构

```
agent-name/
├── AGENTS.md                    # 主清单 (必需)
├── skills/                      # 本地技能 (可选)
│   └── web-search/
│       └── SKILL.md
├── mcp-configs/                 # MCP 配置 (可选)
│   └── filesystem/
│       ├── ActiveMCP.json
│       └── config.yaml
└── versions/                    # 版本历史 (可选)
```

### 2.2 AGENTS.md 示例

```yaml
---
name: "Research Assistant"
vendorKey: "acme"
agentKey: "research"
version: "1.0.0"
slug: "acme/research"
description: "A research assistant with web search and file access"
author: "@acme"
license: "MIT"
tags: ["research", "web-search"]

skills:
  - name: "web-search"
    source: "local"
    version: "1.0.0"
    required: true

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: true

tools: ["Read", "Edit", "Bash", "Glob", "Grep"]

model:
  provider: "ctyun"
  name: "your_model_id_here"

config:
  temperature: 0.7
  max_tokens: 4096
---

# Agent Purpose

You are a research assistant specialized in finding and analyzing information.

## Core Responsibilities

- Search the web for information
- Read and analyze documents
- Provide accurate, well-sourced answers
```

## 3. 代码生成模块

### 3.1 模块结构

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
├── cli.py                       # CLI 入口
└── tests/                       # 测试
```

### 3.2 依赖版本

| 包 | 版本 |
|-----|------|
| deepagents | 0.5.5 |
| langchain | 1.2.17 |
| langchain-openai | 1.2.1 |
| langgraph | 1.1.10 |
| pyyaml | 6.0.2 |
| jinja2 | 3.1.4 |

## 4. CLI 用法

```bash
# 创建 OAF 脚手架
python cli.py scaffold --name "My Agent" --output ./output/

# 迁移旧格式
python cli.py migrate --config old_config.json --output ./output/

# 从 OAF 目录生成代码
python cli.py generate --oaf ./agent-dir/ --output ./generated/
```

## 5. 生成产物

| 文件 | 大小 | 说明 |
|------|------|------|
| main.py | ~300 行 | A2A Server 入口 |
| agent_card.json | ~50 行 | A2A Agent Card |
| Dockerfile | ~20 行 | Docker 构建文件 |
| requirements.txt | ~10 行 | Python 依赖 |
| skills/ | - | 技能目录 |
| mcp-configs/ | - | MCP 配置目录 |

### 生成的 Agent 特征：

- FastAPI Web 服务器 (端口 8000)
- A2A 协议支持 (JSON-RPC 2.0 + REST)
- Agent Card 端点 (`/.well-known/agent-card.json`)
- Skills 加载 (`/skills` 端点)
- MCP 配置 (`/mcp` 端点)
- A2UI Extension 支持
- 环境变量 LLM 配置

## 6. 环境变量配置

LLM 配置通过环境变量注入，不再硬编码在配置中：

```bash
export LLM_API_KEY=your_api_key
export LLM_MODEL_ID=your_model_id
export LLM_BASE_URL=https://api.example.com/v1
export LLM_PROVIDER=ctyun
export LLM_TEMPERATURE=0.7
export LLM_MAX_TOKENS=4096
```

## 7. MinIO 存储路径

文件成功上传到 MinIO `agent-manager` bucket：

```
agent-manager/agents/{id}/v{version}/
├── main.py
├── Dockerfile
├── requirements.txt
├── agent_card.json
├── skills/
│   └── web-search/
│       └── skill.py
└── mcp-configs/
    └── filesystem/
        ├── ActiveMCP.json
        └── config.yaml
```

## 8. 协议支持

| 协议 | 版本 | 实现方式 |
|------|------|---------|
| OAF | v0.8.0 | AGENTS.md + skills/ + mcp-configs/ |
| A2A | v1.0.0 | JSON-RPC 2.0 + REST, Agent Card discovery |
| A2UI | v0.8 | A2A Extension, surfaceUpdate/beginRendering |

## 9. 测试验证

### 单元测试

```bash
pytest codegen/tests/unit/ -v
```

### 集成测试

```bash
pytest codegen/tests/integration/ -v
```

### E2E 测试

```bash
# 启动服务
python codegen/examples/research-agent/main.py &

# 运行测试
pytest codegen/tests/e2e/test_research_agent.py -v
```

## 10. 下一步

生成的代码用于 Docker 镜像构建和 agent-sandbox 部署验证。
