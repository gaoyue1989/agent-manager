# Codegen 重构计划 —— 原生 OAF 支持与 A2A/A2UI 集成 (v3 终版)

## 1. 协议关系总览

经过查阅三个核心规范，明确了完整的协议栈：

```
┌────────────────────────────────────────────────────┐
│                    OAF v0.8.0                       │
│    Agent 配置格式 (AGENTS.md + skills/ + mcp/)      │
│    DeepAgents 原生加载                               │
├────────────────────────────────────────────────────┤
│                    A2A v1.0.0                        │
│    独立协议 (Linux Foundation, ex-Google)            │
│    Agent 间通信标准 (Task/Message/Part/Artifact)     │
│    支持 JSON-RPC / gRPC / HTTP REST                 │
├────────────────────────────────────────────────────┤
│               A2A Extensions                         │
│    └── A2UI v0.8 (A2A 扩展)                         │
│        声明式 UI 流 (surfaceUpdate/dataModelUpdate)  │
│        通过 A2A metadata 传递 clientCapabilities     │
└────────────────────────────────────────────────────┘
```

### 1.1 三层协议的关系

| 协议 | 定位 | 核心概念 | 标准组织 |
|------|------|---------|---------|
| **OAF** | Agent 配置格式 | AGENTS.md, skills/, mcp-configs/ | Open Agent Format |
| **A2A** | Agent 间通信协议 | Task, Message, Part, Artifact, AgentCard | Linux Foundation |
| **A2UI** | A2A 的 UI 扩展 | surfaceUpdate, dataModelUpdate, beginRendering | Google (Apache 2.0) |

### 1.2 交互架构
```
  Client (Renderer)
      │
      │ A2A SendStreamingMessage
      │   └── metadata.a2uiClientCapabilities
      ▼
  A2A Server (Main Agent)
      │
      │ 响应: A2A Task + SSE Stream
      │   ├── TaskStatusUpdateEvent
      │   └── TaskArtifactUpdateEvent
      │       └── Artifact.parts[].data = A2UI JSONL
      │           ├── surfaceUpdate
      │           ├── dataModelUpdate
      │           └── beginRendering
      │
      │ A2A SendMessage (子 Agent 调用)
      ▼
  Sub Agent (A2A Server)
```

### 1.3 A2UI 作为 A2A 扩展的声明方式

```json
// Agent Card 中声明 A2UI 支持
{
  "capabilities": {
    "extensions": [
      {
        "uri": "https://a2ui.org/a2a-extension/a2ui/v0.8",
        "params": {
          "supportedCatalogIds": [
            "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
          ],
          "acceptsInlineCatalogs": true
        }
      }
    ]
  }
}
```

```json
// Client 在 A2A message metadata 中声明 A2UI 渲染能力
{
  "message": {
    "prompt": {"text": "帮我找餐厅"},
    "metadata": {
      "a2uiClientCapabilities": {
        "supportedCatalogIds": ["https://a2ui.org/specification/v0_8/standard_catalog_definition.json"]
      }
    }
  }
}
```

---

## 2. Key Concepts 来自 A2A v1.0 规范

### 2.1 三层架构
```
Layer 1: Canonical Data Model (proto-based)
    Task, Message, Part, Artifact, AgentCard, Extension

Layer 2: Abstract Operations
    SendMessage, SendStreamingMessage, GetTask, ListTasks,
    CancelTask, SubscribeToTask, PushNotificationConfig

Layer 3: Protocol Bindings
    JSON-RPC 2.0, gRPC, HTTP+JSON/REST
```

### 2.2 核心数据对象（来自 `spec/a2a.proto`）

| 对象 | 说明 |
|------|------|
| **AgentCard** | 能力声明：name, description, url, capabilities, skills, auth schemes |
| **Task** | 有状态的工作单元：id, status, state, artifacts, history |
| **TaskStatus** | 任务状态：state (working/pending/completed/failed/canceled/rejected), message |
| **Message** | 一次通信轮次：messageId, role (user/agent), parts[] |
| **Part** | 内容容器 (oneof)：text, file(url/bytes), data(JSON) |
| **Artifact** | 任务输出：artifactId, name, parts[] |
| **TaskStatusUpdateEvent** | 流事件：taskId, status |
| **TaskArtifactUpdateEvent** | 流事件：taskId, artifact (增量或完整) |

### 2.3 核心操作（11 个）

| 操作 | 用途 |
|------|------|
| SendMessage | 发送消息，返回 Task 或直接 Message |
| SendStreamingMessage | 发送消息 + SSE 流式返回 |
| GetTask | 轮询任务状态和历史 |
| ListTasks | 列出任务（支持过滤和分页） |
| CancelTask | 取消任务 |
| SubscribeToTask | 订阅已有任务的 SSE 流 |
| CreatePushNotificationConfig | 创建 Webhook 推送 |
| GetPushNotificationConfig | 获取推送配置 |
| ListPushNotificationConfigs | 列出推送配置 |
| DeletePushNotificationConfig | 删除推送配置 |
| GetExtendedAgentCard | 获取鉴权后的 Agent Card |

### 2.4 Agent Discovery
- Well-Known URI: `GET /.well-known/agent-card.json`
- Curated Registries (中心化注册中心)
- Direct Configuration (直接配置)

---

## 3. 关键决策（已确认）

| # | 决策项 | 最终方案 |
|---|--------|---------|
| 1 | OAF 解析 | DeepAgents 原生加载，codegen 生成 OAF 目录 |
| 2 | A2A 角色 | 独立通信协议，Agent 作为 A2A Server |
| 3 | A2UI 角色 | A2A 协议的 UI 扩展 (Extension) |
| 4 | 向后兼容 | migrate_legacy.py 迁移脚本 |
| 5 | 子 Agent | A2A SendMessage 远程调用 |
| 6 | 远程技能 | 构建时下载打包 |

---

## 4. 架构设计

### 4.1 Codegen 目录结构
```
codegen/
├── core/
│   ├── scaffold_generator.py    # OAF 目录脚手架
│   ├── legacy_migrator.py       # 旧格式迁移
│   └── skill_packager.py        # 远程技能构建时打包
├── frameworks/
│   └── deepagents/
│       ├── __init__.py
│       ├── agent_scaffold.py    # 生成 main.py (A2A Server)
│       ├── agent_card_gen.py    # 生成 Agent Card
│       ├── a2a_server.py        # 生成 A2A Server 端点
│       ├── a2a_client.py        # 生成 A2A Client (子 Agent)
│       ├── a2ui_extension.py    # 生成 A2UI Extension 处理
│       ├── skill_code_gen.py    # Skill 实现代码
│       └── templates/
│           ├── main.py.j2
│           ├── agent_card.json.j2
│           ├── a2a_routes.py.j2
│           ├── Dockerfile.j2
│           └── requirements.txt.j2
├── examples/
│   └── research-agent/
│       ├── AGENTS.md
│       ├── skills/
│       ├── mcp-configs/
│       └── generated/
├── cli.py
└── AGENTS.md
```

### 4.2 Codegen 产出物

| 产出 | 说明 |
|------|------|
| `main.py` | A2A Server 入口 (FastAPI) |
| `agent_card.json` | A2A Agent Card (+ A2UI extension) |
| `a2a_routes.py` | A2A 操作端点 (SendMessage, GetTask...) |
| `a2ui_handler.py` | A2UI Extension 处理 (生成 JSONL 流) |
| `a2a_clients.py` | 子 Agent A2A 客户端 |
| `Dockerfile` | 镜像构建 |
| `requirements.txt` | Python 依赖 |

### 4.3 职责边界

| 职责 | Codegen | DeepAgents |
|------|---------|------------|
| OAF 加载 | ✗ | ✓ |
| A2A Server 端点 | ✓ (生成代码) | ✓ (运行时) |
| A2A Agent Card | ✓ (生成模板) | ✓ (服务) |
| A2UI Extension | ✓ (生成 handler) | ✓ (LLM 生成 JSON) |
| A2A 子 Agent 调用 | ✓ (生成 client) | ✓ (运行时) |
| Skill/MCP 代码 | ✓ | ✗ |
| Dockerfile | ✓ | ✗ |

---

## 5. A2A Server 实现设计

### 5.1 OAF 配置 (harnessConfig)
```yaml
# AGENTS.md
harnessConfig:
  deep-agents:
    a2a:
      protocol: "1.0.0"
      bindings: ["jsonrpc", "rest"]    # 支持的协议绑定
      streaming: true
      push_notifications: false
    a2ui:
      enabled: true
      version: "v0.8"
      catalog_id: "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
    sub_agents:
      - slug: "acme/data-analyst"
        endpoint: "http://data-analyst:8000"
        version: "1.0.0"
```

### 5.2 生成的 main.py
```python
#!/usr/bin/env python3
"""Generated by Codegen from OAF: acme/research-assistant v1.0.0

A2A Server + A2UI Extension
"""

from pathlib import Path
from fastapi import FastAPI
from deepagents import load_oaf_agent
from agent_card import serve_agent_card
from a2a_routes import register_a2a_routes
from a2ui_handler import A2UIExtension
from a2a_clients import SubAgentRegistry
import uvicorn

AGENT_DIR = Path(__file__).parent.parent

app = FastAPI(title="Research Assistant")

# ============================================================
# 1. DeepAgents 加载 OAF
# ============================================================
agent = load_oaf_agent(AGENT_DIR)

# ============================================================
# 2. A2UI Extension (A2A 扩展)
# ============================================================
a2ui = A2UIExtension(
    agent=agent,
    catalog_id="https://a2ui.org/specification/v0_8/standard_catalog_definition.json",
    supported_catalogs=[
        "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
    ],
    accepts_inline_catalogs=True
)

# ============================================================
# 3. 子 Agent 注册表 (A2A Client)
# ============================================================
sub_agents = SubAgentRegistry()
sub_agents.register(
    slug="acme/data-analyst",
    endpoint="http://data-analyst:8000",
    version="1.0.0"
)
agent.register_tools(sub_agents.as_tools())

# ============================================================
# 4. Agent Card (Discovery)
# ============================================================
@app.get("/.well-known/agent-card.json")
async def agent_card():
    return serve_agent_card(a2ui_enabled=True)

# ============================================================
# 5. A2A 操作端点
# ============================================================
register_a2a_routes(app, agent, a2ui, sub_agents)

# ============================================================
# 6. 健康检查
# ============================================================
@app.get("/health")
async def health():
    return {"status": "healthy", "agent": agent.name}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

### 5.3 Agent Card 生成 (agent_card.json.j2)
```json
{
  "name": "Research Assistant",
  "description": "智能研究助手，支持 A2UI 交互界面",
  "url": "http://{{ host }}:{{ port }}/",
  "version": "1.0.0",
  "provider": {
    "organization": "acme",
    "url": "https://acme.example.com"
  },
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "stateTransitionHistory": true
  },
  "defaultInputModes": ["text", "text/plain"],
  "defaultOutputModes": ["text", "text/plain", "a2ui/v0.8"],
  "skills": [
    {
      "id": "research",
      "name": "Research",
      "description": "执行深度信息检索和数据分析",
      "tags": ["search", "analysis"],
      "examples": ["搜索最新AI论文", "分析市场趋势"],
      "inputModes": ["text"],
      "outputModes": ["text", "a2ui/v0.8"]
    }
  ],
  "securitySchemes": {
    "bearer": {
      "scheme": "bearer",
      "description": "Bearer token authentication"
    }
  },
  "extensions": [
    {
      "uri": "https://a2ui.org/a2a-extension/a2ui/v0.8",
      "params": {
        "supportedCatalogIds": [
          "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
        ],
        "acceptsInlineCatalogs": true
      }
    }
  ]
}
```

### 5.4 A2A 路由生成 (a2a_routes.py.j2)
```python
"""A2A v1.0 操作端点 - JSON-RPC + REST 双绑定"""

from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse, JSONResponse
import json
import uuid

def register_a2a_routes(app: FastAPI, agent, a2ui, sub_agents):
    """注册 A2A 操作端点"""
    
    # ============================================================
    # JSON-RPC 2.0 Binding
    # ============================================================
    @app.post("/")  # JSON-RPC endpoint
    async def jsonrpc_handler(request: Request):
        body = await request.json()
        method = body.get("method")
        params = body.get("params", {})
        msg_id = body.get("id")
        
        try:
            if method == "message/send":
                result = await _handle_send_message(params, agent, a2ui)
            elif method == "message/stream":
                # Stream 返回 SSE
                return StreamingResponse(...)
            elif method == "tasks/get":
                result = await _handle_get_task(params, agent)
            elif method == "tasks/list":
                result = await _handle_list_tasks(params, agent)
            elif method == "tasks/cancel":
                result = await _handle_cancel_task(params, agent)
            elif method == "tasks/subscribe":
                return StreamingResponse(...)
            else:
                return JSONResponse({
                    "jsonrpc": "2.0",
                    "error": {"code": -32601, "message": "Method not found"},
                    "id": msg_id
                })
            
            return JSONResponse({
                "jsonrpc": "2.0",
                "result": result,
                "id": msg_id
            })
        except Exception as e:
            return JSONResponse({
                "jsonrpc": "2.0",
                "error": {"code": -1, "message": str(e)},
                "id": msg_id
            })
    
    # ============================================================
    # HTTP+JSON/REST Binding (兼容)
    # ============================================================
    @app.post("/tasks")
    async def rest_send_message(request: Request):
        """REST: 创建新任务"""
        body = await request.json()
        return await _handle_send_message(body, agent, a2ui)
    
    @app.get("/tasks/{task_id}")
    async def rest_get_task(task_id: str):
        """REST: 获取任务状态"""
        return await _handle_get_task({"id": task_id}, agent)


async def _handle_send_message(params: dict, agent, a2ui):
    """
    A2A SendMessage 核心逻辑
    
    1. 解析 message.prompt.text 获得用户输入
    2. 检查 metadata.a2uiClientCapabilities 确定是否启用 A2UI
    3. 调用 Agent 处理 → 生成 Task + (可选的 A2UI Artifact)
    4. 返回 Task 对象
    """
    message_config = params.get("configuration", {})
    user_text = params.get("message", {}).get("prompt", {}).get("text", "")
    metadata = params.get("metadata", {})
    a2ui_caps = metadata.get("a2uiClientCapabilities", {})
    
    # 1. 创建 Task
    task_id = str(uuid.uuid4())
    task = {
        "id": task_id,
        "status": {"state": "working"},
        "artifacts": []
    }
    
    # 2. 处理用户请求
    agent_response = await agent.invoke(user_text)
    
    # 3. 如果客户端支持 A2UI，生成 A2UI Artifact
    if a2ui_caps.get("supportedCatalogIds"):
        a2ui_artifact = a2ui.generate_artifact(
            surface_id=task_id,
            response_text=agent_response,
            catalog_id=a2ui_caps["supportedCatalogIds"][0]
        )
        task["artifacts"].append(a2ui_artifact)
    else:
        # 纯文本响应
        task["artifacts"].append({
            "artifactId": str(uuid.uuid4()),
            "name": "response",
            "parts": [{"text": agent_response}]
        })
    
    # 4. 标记完成
    task["status"]["state"] = "completed"
    
    return task
```

### 5.5 A2UI Extension 处理 (a2ui_handler.py)
```python
"""A2UI Extension Handler - 生成 A2UI JSONL 流"""

import json
import re
from typing import Optional

class A2UIExtension:
    """A2A 的 A2UI 扩展实现
    
    将 Agent 的文本响应转换为 A2UI JSONL 流 (Artifact 格式)
    """
    
    def __init__(self, agent, catalog_id: str,
                 supported_catalogs: list[str],
                 accepts_inline_catalogs: bool = True):
        self.agent = agent
        self.catalog_id = catalog_id
        self.supported_catalogs = supported_catalogs
        self.accepts_inline_catalogs = accepts_inline_catalogs
    
    def generate_artifact(self, surface_id: str, response_text: str,
                          catalog_id: Optional[str] = None) -> dict:
        """
        从 Agent 响应中生成 A2UI Artifact
        
        支持两种模式:
        1. LLM 在代码块中直接输出 A2UI JSONL (```a2ui ... ```)
        2. Codegen 提取文本内容，包装为基础 Text 组件
        """
        used_catalog = catalog_id or self.catalog_id
        a2ui_lines = self._extract_a2ui_from_text(response_text)
        
        if a2ui_lines:
            # LLM 已生成 A2UI JSONL
            jsonl_content = "\n".join(a2ui_lines)
        else:
            # 自动包装：将文本作为 Text 组件展示
            jsonl_content = self._wrap_text_as_a2ui(surface_id, response_text)
        
        # 包装为 A2A Artifact
        return {
            "artifactId": surface_id,
            "name": "A2UI Interface",
            "parts": [{
                "data": {"a2ui_stream": jsonl_content},
                "mediaType": "application/x-a2ui+jsonl"
            }]
        }
    
    def _extract_a2ui_from_text(self, text: str) -> list[str]:
        """从 LLM 响应中提取 A2UI JSONL 代码块"""
        match = re.search(r'```a2ui\n(.*?)\n```', text, re.DOTALL)
        if match:
            return [line for line in match.group(1).split("\n") if line.strip()]
        return []
    
    def _wrap_text_as_a2ui(self, surface_id: str, text: str) -> str:
        """将纯文本包装为 A2UI Text 组件"""
        # 对文本分段
        paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
        
        lines = []
        
        # surfaceUpdate: 定义组件
        components = []
        child_ids = []
        for i, para in enumerate(paragraphs):
            comp_id = f"para_{i}"
            child_ids.append(comp_id)
            components.append({
                "id": comp_id,
                "component": {
                    "Text": {
                        "text": {"literalString": para}
                    }
                }
            })
        
        # root Column
        components.insert(0, {
            "id": "root",
            "component": {
                "Column": {
                    "children": {"explicitList": child_ids}
                }
            }
        })
        
        # 生成 JSONL
        for comp in components:
            lines.append(json.dumps({
                "surfaceUpdate": {
                    "surfaceId": surface_id,
                    "components": [comp]
                }
            }, ensure_ascii=False))
        
        lines.append(json.dumps({
            "beginRendering": {
                "surfaceId": surface_id,
                "root": "root"
            }
        }, ensure_ascii=False))
        
        return "\n".join(lines)
```

### 5.6 子 Agent A2A 客户端 (a2a_clients.py)
```python
"""A2A 客户端 - 调用远程子 Agent"""

import json
import httpx

class A2AClient:
    """A2A Client (JSON-RPC binding)"""
    
    def __init__(self, slug: str, endpoint: str, version: str = "1.0.0"):
        self.slug = slug
        self.endpoint = endpoint.rstrip("/")
        self.version = version
        self._agent_card = None
    
    async def discover(self) -> dict:
        """获取子 Agent 的 Agent Card"""
        url = f"{self.endpoint}/.well-known/agent-card.json"
        async with httpx.AsyncClient() as client:
            resp = await client.get(url)
            self._agent_card = resp.json()
        return self._agent_card
    
    async def send_message(self, text: str, context_id: str = None,
                           metadata: dict = None) -> dict:
        """通过 A2A 发送消息给子 Agent (JSON-RPC)"""
        payload = {
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {
                    "role": "user",
                    "messageId": str(uuid.uuid4()),
                    "parts": [{"text": text}]
                },
                "configuration": {},
                "metadata": metadata or {}
            },
            "id": str(uuid.uuid4())
        }
        if context_id:
            payload["params"]["configuration"]["contextId"] = context_id
        
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{self.endpoint}/",
                json=payload,
                headers={"A2A-Version": self.version}
            )
            return resp.json()
    
    def as_tool(self):
        """包装为 DeepAgents Tool"""
        return {
            "name": f"delegate_to_{self.slug.replace('/', '_')}",
            "description": f"委托任务给 {self.slug} 子 Agent",
            "callable": lambda task, **kwargs: self.send_message(task, **kwargs)
        }


class SubAgentRegistry:
    """子 Agent 注册表"""
    
    def __init__(self):
        self.clients: dict[str, A2AClient] = {}
    
    def register(self, slug: str, endpoint: str, version: str = "1.0.0"):
        self.clients[slug] = A2AClient(slug, endpoint, version)
    
    async def delegate(self, agent_slug: str, task: str,
                       context_id: str = None) -> dict:
        client = self.clients.get(agent_slug)
        if not client:
            raise ValueError(f"Unknown sub-agent: {agent_slug}")
        return await client.send_message(task, context_id=context_id)
    
    def as_tools(self) -> list:
        return [c.as_tool() for c in self.clients.values()]
```

---

## 6. LLM 大模型配置设计

### 6.1 环境变量配置

LLM 配置通过环境变量独立管理，不硬编码在配置文件中：

```bash
# .env 文件 (不提交到 Git)
# LLM 基础配置
LLM_API_KEY=your_api_key_here
LLM_MODEL_ID=your_model_id_here
LLM_BASE_URL=https://wishub-x6.ctyun.cn/v1
LLM_PROVIDER=ctyun

# 可选配置
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=4096
LLM_TIMEOUT=60
```

### 6.2 环境变量说明

| 环境变量 | 必填 | 说明 | 示例 |
|---------|------|------|------|
| `LLM_API_KEY` | ✓ | LLM API 密钥 | `your_api_key_here` |
| `LLM_MODEL_ID` | ✓ | 模型 ID | `your_model_id_here` |
| `LLM_BASE_URL` | ✓ | API 端点 | `https://wishub-x6.ctyun.cn/v1` |
| `LLM_PROVIDER` | ✓ | 提供商标识 | `ctyun`, `openai`, `anthropic` |
| `LLM_TEMPERATURE` | | 温度参数 | `0.7` |
| `LLM_MAX_TOKENS` | | 最大输出 token | `4096` |
| `LLM_TIMEOUT` | | 请求超时(秒) | `60` |

### 6.3 OAF 中的模型配置引用

```yaml
# AGENTS.md
model:
  provider: "${LLM_PROVIDER}"
  name: "${LLM_MODEL_ID}"
  endpoint: "${LLM_BASE_URL}"
  # api_key 从环境变量读取，不写入配置
config:
  temperature: ${LLM_TEMPERATURE:-0.7}
  max_tokens: ${LLM_MAX_TOKENS:-4096}
```

### 6.4 生成的代码中读取环境变量

```python
# generated/config.py
import os
from pydantic import BaseSettings

class LLMConfig(BaseSettings):
    """LLM 配置 - 从环境变量读取"""
    api_key: str = os.getenv("LLM_API_KEY", "")
    model_id: str = os.getenv("LLM_MODEL_ID", "")
    base_url: str = os.getenv("LLM_BASE_URL", "")
    provider: str = os.getenv("LLM_PROVIDER", "ctyun")
    temperature: float = float(os.getenv("LLM_TEMPERATURE", "0.7"))
    max_tokens: int = int(os.getenv("LLM_MAX_TOKENS", "4096"))
    timeout: int = int(os.getenv("LLM_TIMEOUT", "60"))
    
    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"

llm_config = LLMConfig()
```

### 6.5 测试环境配置

```bash
# tests/.env.test
LLM_API_KEY=your_api_key_here
LLM_MODEL_ID=your_model_id_here
LLM_BASE_URL=https://wishub-x6.ctyun.cn/v1
LLM_PROVIDER=ctyun
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=4096
```

---

## 7. 测试设计

### 7.1 测试目录结构
```
codegen/
├── tests/
│   ├── __init__.py
│   ├── conftest.py              # pytest 配置 + fixtures
│   ├── .env.test                # 测试环境变量
│   ├── unit/                    # 单元测试
│   │   ├── test_scaffold.py
│   │   ├── test_agent_card.py
│   │   ├── test_a2a_server.py
│   │   ├── test_a2ui_extension.py
│   │   ├── test_a2a_client.py
│   │   └── test_legacy_migrator.py
│   ├── integration/             # 集成测试
│   │   ├── test_full_workflow.py
│   │   ├── test_a2a_communication.py
│   │   └── test_sub_agent_delegation.py
│   └── e2e/                     # 端到端测试
│       ├── test_research_agent.py
│       └── test_llm_integration.py
```

### 7.2 单元测试用例

#### test_scaffold.py
```python
"""OAF 脚手架生成测试"""
import pytest
from pathlib import Path
from core.scaffold_generator import ScaffoldGenerator

class TestScaffoldGenerator:
    """脚手架生成器测试"""
    
    def test_create_oaf_directory_structure(self, tmp_path):
        """测试创建 OAF 目录结构"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_path
        )
        
        assert (agent_dir / "AGENTS.md").exists()
        assert (agent_dir / "skills").is_dir()
        assert (agent_dir / "mcp-configs").is_dir()
    
    def test_agents_md_has_required_fields(self, tmp_path):
        """测试 AGENTS.md 包含必填字段"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_path,
            description="Test agent"
        )
        
        content = (agent_dir / "AGENTS.md").read_text()
        assert "name:" in content
        assert "vendorKey:" in content
        assert "description:" in content
```

#### test_agent_card.py
```python
"""Agent Card 生成测试"""
import pytest
import json
from frameworks.deepagents.agent_card_gen import AgentCardGenerator

class TestAgentCardGenerator:
    """Agent Card 生成器测试"""
    
    def test_generate_valid_agent_card(self):
        """测试生成有效的 Agent Card"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test Description",
            host="localhost",
            port=8000
        )
        card = generator.generate()
        
        assert card["name"] == "Test Agent"
        assert "url" in card
        assert "capabilities" in card
        assert "skills" in card
    
    def test_agent_card_has_a2ui_extension(self):
        """测试 Agent Card 包含 A2UI 扩展"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
            a2ui_enabled=True
        )
        card = generator.generate()
        
        extensions = card.get("extensions", [])
        a2ui_ext = next(
            (e for e in extensions if "a2ui" in e.get("uri", "")),
            None
        )
        assert a2ui_ext is not None
        assert "supportedCatalogIds" in a2ui_ext["params"]
    
    def test_agent_card_json_serializable(self):
        """测试 Agent Card 可序列化为 JSON"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test"
        )
        card = generator.generate()
        
        json_str = json.dumps(card, ensure_ascii=False)
        parsed = json.loads(json_str)
        assert parsed == card
```

#### test_a2a_server.py
```python
"""A2A Server 端点测试"""
import pytest
from fastapi.testclient import TestClient
from unittest.mock import Mock, AsyncMock

class TestA2AServerEndpoints:
    """A2A Server 端点测试"""
    
    @pytest.fixture
    def client(self):
        """创建测试客户端"""
        from main import app
        return TestClient(app)
    
    def test_agent_card_endpoint(self, client):
        """测试 Agent Card 发现端点"""
        resp = client.get("/.well-known/agent-card.json")
        assert resp.status_code == 200
        
        card = resp.json()
        assert "name" in card
        assert "capabilities" in card
    
    def test_jsonrpc_send_message(self, client, mock_agent):
        """测试 JSON-RPC message/send"""
        resp = client.post("/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {
                    "role": "user",
                    "parts": [{"text": "Hello"}]
                }
            },
            "id": "test-1"
        })
        
        assert resp.status_code == 200
        result = resp.json()
        assert "result" in result
        assert "id" in result["result"]  # Task ID
    
    def test_rest_send_message(self, client):
        """测试 REST POST /tasks"""
        resp = client.post("/tasks", json={
            "message": {
                "role": "user",
                "parts": [{"text": "Hello"}]
            }
        })
        
        assert resp.status_code == 200
        task = resp.json()
        assert "id" in task
        assert "status" in task
    
    def test_get_task(self, client, existing_task_id):
        """测试 GET /tasks/{id}"""
        resp = client.get(f"/tasks/{existing_task_id}")
        assert resp.status_code == 200
    
    def test_list_tasks(self, client):
        """测试 GET /tasks"""
        resp = client.get("/tasks")
        assert resp.status_code == 200
        tasks = resp.json()
        assert isinstance(tasks, list)
```

#### test_a2ui_extension.py
```python
"""A2UI Extension 处理测试"""
import pytest
import json
from frameworks.deepagents.a2ui_extension import A2UIExtension

class TestA2UIExtension:
    """A2UI Extension 测试"""
    
    @pytest.fixture
    def a2ui(self):
        return A2UIExtension(
            agent=None,
            catalog_id="https://a2ui.org/specification/v0_8/standard_catalog_definition.json",
            supported_catalogs=[],
            accepts_inline_catalogs=True
        )
    
    def test_wrap_text_as_a2ui(self, a2ui):
        """测试将文本包装为 A2UI JSONL"""
        text = "Hello\n\nWorld"
        jsonl = a2ui._wrap_text_as_a2ui("test-surface", text)
        
        lines = [l for l in jsonl.split("\n") if l.strip()]
        assert len(lines) >= 2  # surfaceUpdate + beginRendering
        
        # 解析第一行
        first = json.loads(lines[0])
        assert "surfaceUpdate" in first
    
    def test_extract_a2ui_from_text(self, a2ui):
        """测试从 LLM 响应中提取 A2UI"""
        response = '''这是响应
        
```a2ui
{"surfaceUpdate": {"surfaceId": "main", "components": []}}
{"beginRendering": {"surfaceId": "main", "root": "root"}}
```
        '''
        
        lines = a2ui._extract_a2ui_from_text(response)
        assert len(lines) == 2
        assert "surfaceUpdate" in lines[0]
    
    def test_generate_artifact_with_a2ui(self, a2ui):
        """测试生成 A2UI Artifact"""
        artifact = a2ui.generate_artifact(
            surface_id="test",
            response_text="Hello World"
        )
        
        assert "artifactId" in artifact
        assert "parts" in artifact
        assert artifact["parts"][0]["mediaType"] == "application/x-a2ui+jsonl"
```

#### test_a2a_client.py
```python
"""A2A Client 测试"""
import pytest
from frameworks.deepagents.a2a_client import A2AClient, SubAgentRegistry
from unittest.mock import AsyncMock, patch

class TestA2AClient:
    """A2A 客户端测试"""
    
    @pytest.fixture
    def client(self):
        return A2AClient(
            slug="test/agent",
            endpoint="http://localhost:8001",
            version="1.0.0"
        )
    
    @pytest.mark.asyncio
    async def test_discover_agent_card(self, client):
        """测试发现 Agent Card"""
        with patch("httpx.AsyncClient.get") as mock_get:
            mock_get.return_value.status_code = 200
            mock_get.return_value.json = lambda: {"name": "Test"}
            
            card = await client.discover()
            assert card["name"] == "Test"
    
    @pytest.mark.asyncio
    async def test_send_message(self, client):
        """测试发送消息"""
        with patch("httpx.AsyncClient.post") as mock_post:
            mock_post.return_value.json = lambda: {
                "jsonrpc": "2.0",
                "result": {"id": "task-1"},
                "id": "test"
            }
            
            result = await client.send_message("Hello")
            assert "result" in result

class TestSubAgentRegistry:
    """子 Agent 注册表测试"""
    
    def test_register_sub_agent(self):
        """测试注册子 Agent"""
        registry = SubAgentRegistry()
        registry.register("test/agent", "http://localhost:8001")
        
        assert "test/agent" in registry.clients
    
    def test_as_tools(self):
        """测试转换为 Tool 列表"""
        registry = SubAgentRegistry()
        registry.register("test/agent", "http://localhost:8001")
        
        tools = registry.as_tools()
        assert len(tools) == 1
        assert "name" in tools[0]
        assert "callable" in tools[0]
```

#### test_legacy_migrator.py
```python
"""旧格式迁移测试"""
import pytest
from core.legacy_migrator import migrate_legacy_config

class TestLegacyMigrator:
    """迁移脚本测试"""
    
    def test_migrate_basic_config(self):
        """测试迁移基础配置"""
        legacy = {
            "name": "Test Agent",
            "description": "Test Description",
            "model": "gpt-4",
            "system_prompt": "You are helpful.",
            "tools": ["Read", "Edit"],
            "memory": True
        }
        
        agents_md, name = migrate_legacy_config(legacy)
        
        assert name == "Test Agent"
        assert "name:" in agents_md
        assert "Test Description" in agents_md
        assert "You are helpful." in agents_md
    
    def test_migrate_preserves_tools(self):
        """测试迁移保留工具列表"""
        legacy = {
            "name": "Test",
            "description": "Test",
            "system_prompt": "Test",
            "tools": ["Read", "Edit", "Bash"]
        }
        
        agents_md, _ = migrate_legacy_config(legacy)
        assert "Read" in agents_md
        assert "Edit" in agents_md
```

### 7.3 集成测试用例

#### test_full_workflow.py
```python
"""完整工作流集成测试"""
import pytest
from pathlib import Path
from core.scaffold_generator import ScaffoldGenerator
from frameworks.deepagents.agent_card_gen import AgentCardGenerator
from frameworks.deepagents.a2a_server import register_a2a_routes

class TestFullWorkflow:
    """完整工作流测试"""
    
    @pytest.mark.asyncio
    async def test_generate_and_run_agent(self, tmp_path):
        """测试生成并运行 Agent"""
        # 1. 生成脚手架
        scaffold = ScaffoldGenerator()
        agent_dir = scaffold.create_scaffold(
            name="integration-test-agent",
            output_dir=tmp_path,
            description="Integration Test Agent"
        )
        
        # 2. 生成 Agent Card
        card_gen = AgentCardGenerator(
            name="Integration Test Agent",
            description="Test",
            host="localhost",
            port=8000,
            a2ui_enabled=True
        )
        card = card_gen.generate()
        
        # 3. 验证生成的文件
        assert (agent_dir / "AGENTS.md").exists()
        assert card["name"] == "Integration Test Agent"
        assert len(card.get("extensions", [])) > 0
```

### 7.4 端到端测试（真实 LLM）

#### test_llm_integration.py
```python
"""LLM 集成测试 - 使用真实 API"""
import pytest
import os
from dotenv import load_dotenv

load_dotenv("tests/.env.test")

class TestLLMIntegration:
    """LLM 真实调用测试"""
    
    @pytest.fixture
    def llm_config(self):
        """加载 LLM 配置"""
        return {
            "api_key": os.getenv("LLM_API_KEY"),
            "model_id": os.getenv("LLM_MODEL_ID"),
            "base_url": os.getenv("LLM_BASE_URL"),
            "provider": os.getenv("LLM_PROVIDER")
        }
    
    @pytest.mark.asyncio
    async def test_llm_connection(self, llm_config):
        """测试 LLM 连接"""
        import httpx
        
        headers = {
            "Authorization": f"Bearer {llm_config['api_key']}",
            "Content-Type": "application/json"
        }
        
        payload = {
            "model": llm_config["model_id"],
            "messages": [{"role": "user", "content": "Hello"}],
            "max_tokens": 10
        }
        
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{llm_config['base_url']}/chat/completions",
                headers=headers,
                json=payload,
                timeout=30
            )
            
            assert resp.status_code == 200
            data = resp.json()
            assert "choices" in data
            assert len(data["choices"]) > 0
    
    @pytest.mark.asyncio
    async def test_agent_invoke_with_real_llm(self, llm_config):
        """测试 Agent 使用真实 LLM 调用"""
        from deepagents import create_agent
        
        agent = create_agent(
            name="test-agent",
            model={
                "provider": llm_config["provider"],
                "name": llm_config["model_id"],
                "endpoint": llm_config["base_url"],
                "api_key": llm_config["api_key"]
            },
            instructions="You are a helpful assistant."
        )
        
        result = await agent.invoke("Say hello")
        assert result is not None
        assert len(result) > 0
```

### 7.5 测试运行命令

```bash
# 运行所有测试
pytest codegen/tests/ -v

# 运行单元测试
pytest codegen/tests/unit/ -v

# 运行集成测试
pytest codegen/tests/integration/ -v

# 运行端到端测试 (需要真实 LLM)
pytest codegen/tests/e2e/ -v --run-e2e

# 生成覆盖率报告
pytest codegen/tests/ --cov=codegen --cov-report=html
```

---

## 8. 实施步骤

| 阶段 | 内容 | 产出 |
|------|------|------|
| 1 | OAF 脚手架 + 迁移脚本 | scaffold_generator.py, legacy_migrator.py |
| 2 | Agent Card 生成 | agent_card_gen.py |
| 3 | A2A Server 端点 (JSON-RPC + REST) | a2a_server.py, a2a_routes.py.j2 |
| 4 | A2UI Extension Handler | a2ui_extension.py |
| 5 | A2A 子 Agent Client | a2a_client.py |
| 6 | Skill/MCP 代码生成 | skill_code_gen.py |
| 7 | LLM 环境变量配置 | config.py, .env.example |
| 8 | 完整示例 (research-agent) | examples/research-agent/ |
| 9 | 单元测试 | tests/unit/*.py |
| 10 | 集成测试 | tests/integration/*.py |
| 11 | 端到端测试 (真实 LLM) | tests/e2e/*.py |

---

## 9. 最终确认清单

| # | 决策 | 状态 |
|---|------|------|
| 1 | 不在 codegen 中解析 OAF | ✓ |
| 2 | A2A v1.0 作为独立通信协议 (JSON-RPC + REST) | ✓ |
| 3 | A2UI v0.8 作为 A2A Extension | ✓ |
| 4 | Agent Card 发现 (/.well-known/agent-card.json) | ✓ |
| 5 | 子 Agent 通过 A2A message/send 调用 | ✓ |
| 6 | 向后兼容：迁移脚本 | ✓ |
| 7 | 远程技能：构建时打包 | ✓ |

确认后开始执行。
