# JSON → DeepAgents 代码转换验证

**日期**: 2026-05-01  
**执行者**: opencode

## 1. 概述

验证通过 JSON 配置生成完整 DeepAgents 代码的完整链路。

## 2. 代码生成模块

### 2.1 模块结构

```
codegen/
├── generator.py          # 核心代码生成器
├── schema/
│   └── agent_config.json  # JSON Schema 配置规范
├── templates/             # 模板目录 (预留)
├── test/
│   ├── test-config.json   # 测试配置
│   └── output/            # 生成输出
│       ├── agent.py       # Agent 脚本
│       ├── Dockerfile     # Docker 构建文件
│       └── requirements.txt # Python 依赖
└── venv/                  # Python 虚拟环境
```

### 2.2 依赖版本

| 包 | 版本 |
|-----|------|
| deepagents | 0.5.5 |
| langchain | 1.2.17 |
| langchain-openai | 1.2.1 |
| langgraph | 1.1.10 |

## 3. 测试配置

```json
{
  "name": "customer-service-agent",
  "description": "智能客服助手",
  "model": "qwen3.6-plus",
  "model_endpoint": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "api_key": "sk-0440b76852944f019bb142a715bc2cab",
  "system_prompt": "你是一个友好的智能客服助手...",
  "tools": [],
  "sub_agents": [],
  "memory": true,
  "max_iterations": 50
}
```

## 4. 生成结果

| 文件 | 大小 | 状态 |
|------|------|------|
| agent.py | 119 行 | ✅ 语法验证通过 |
| Dockerfile | 21 行 | ✅ |
| requirements.txt | 6 个依赖 | ✅ |

### 生成的 Agent 特征：
- FastAPI Web 服务器 (端口 8000)
- `/health` 健康检查端点
- `/chat` 对话端点 (支持历史消息)
- `/` 信息端点
- CORS 中间件
- 阿里云 DashScope (qwen3.6-plus) LLM 集成
- 基于 `create_deep_agent()` 的 Agent 创建

## 5. MinIO 存储验证

文件成功上传到 MinIO `agent-manager` bucket：
```
agent-manager/agents/1/v1/agent.py      (3.18 KB)
agent-manager/agents/1/v1/Dockerfile    (459 B)
agent-manager/agents/1/v1/requirements.txt (108 B)
```

## 6. 生成命令

```bash
# 通过文件生成
./codegen/venv/bin/python3 codegen/generator.py config.json output_dir/

# 通过 stdin 生成
cat config.json | ./codegen/venv/bin/python3 codegen/generator.py --stdin output_dir/
```

## 7. 下一步

生成的代码将用于步骤 2.2 的 Docker 镜像构建和 agent-sandbox 部署验证。
