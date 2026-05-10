# OAF / A2A / A2UI 协议架构

## 协议关系总览

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

---

## 1. OAF (Open Agent Format) v0.8.0

### 定位

Agent 配置格式，定义 Agent 的身份、能力、组合关系。

### 核心概念

| 概念 | 说明 |
|------|------|
| AGENTS.md | 主清单文件（YAML frontmatter + Markdown body） |
| skills/ | 本地技能目录 |
| mcp-configs/ | MCP 服务器配置 |
| agents | 子 Agent 引用 |

### 在本项目中的角色

- **配置存储**：Backend 将 Agent 配置以 OAF 格式存储
- **代码生成**：Codegen 从 OAF 目录生成 DeepAgents 代码
- **前端表单**：Frontend 表单生成 OAF YAML 配置

### 关键字段映射

| OAF 字段 | 用途 |
|---------|------|
| `name` | Agent 显示名称 |
| `skills[]` | 技能列表（local 或 well-known URL） |
| `mcpServers[]` | MCP 服务器配置 |
| `agents[]` | 子 Agent（A2A 调用目标） |
| `tools[]` | 工具访问控制 |
| `model` | LLM 配置（provider + name） |
| `config` | 运行时配置（temperature, max_tokens） |

---

## 2. A2A (Agent-to-Agent) v1.0.0

### 定位

独立协议，用于 Agent 间通信，由 Linux Foundation Agentic AI Foundation 维护。

### 核心概念

| 概念 | 说明 |
|------|------|
| Task | 任务实体，包含状态、消息、产物 |
| Message | 消息，包含角色和 Part 数组 |
| Part | 消息部分（TextPart / FilePart / DataPart） |
| Artifact | 产物，包含 Part 数组 |
| AgentCard | Agent 能力声明（发现协议） |

### 三层架构

```
Layer 1: Canonical Data Model (proto-based)
    Task, Message, Part, Artifact, AgentCard, Extension

Layer 2: Abstract Operations
    SendMessage, SendStreamingMessage, GetTask, ListTasks,
    CancelTask, SetTaskPushNotification, GetTaskPushNotification

Layer 3: Wire Protocols
    JSON-RPC 2.0 / gRPC / HTTP REST
```

### 在本项目中的角色

- **主 Agent**：作为 A2A Server 接收请求
- **子 Agent**：通过 A2A Client 调用远程 Agent
- **发现**：通过 Agent Card 发现 Agent 能力

### Agent Card 端点

```
GET /.well-known/agent-card.json
```

返回格式：
```json
{
  "name": "Research Assistant",
  "description": "...",
  "capabilities": {
    "extensions": [
      {
        "uri": "https://a2ui.org/a2a-extension/a2ui/v0.8",
        "params": {
          "supportedCatalogIds": ["https://a2ui.org/specification/v0_8/standard_catalog_definition.json"]
        }
      }
    ]
  }
}
```

### JSON-RPC 2.0 调用

```json
{
  "jsonrpc": "2.0",
  "method": "message/send",
  "params": {
    "message": {
      "role": "user",
      "parts": [{"text": "Hello"}]
    }
  },
  "id": "request-1"
}
```

---

## 3. A2UI v0.8 (A2A Extension)

### 定位

A2A 的 UI 扩展，提供声明式 UI 流，通过 A2A metadata 传递渲染指令。

### 核心概念

| 概念 | 说明 |
|------|------|
| surfaceUpdate | 更新 UI 表面（表单、列表、详情） |
| dataModelUpdate | 更新数据模型 |
| beginRendering | 开始渲染会话 |
| endRendering | 结束渲染会话 |

### 声明方式

**1. Agent Card 中声明 A2UI 支持：**
```json
{
  "capabilities": {
    "extensions": [
      {
        "uri": "https://a2ui.org/a2a-extension/a2ui/v0.8",
        "params": {
          "supportedCatalogIds": ["https://a2ui.org/specification/v0_8/standard_catalog_definition.json"],
          "acceptsInlineCatalogs": true
        }
      }
    ]
  }
}
```

**2. Client 在 A2A message metadata 中声明渲染能力：**
```json
{
  "message": {
    "role": "user",
    "parts": [{"text": "帮我找餐厅"}],
    "metadata": {
      "a2uiClientCapabilities": {
        "supportedCatalogIds": ["https://a2ui.org/specification/v0_8/standard_catalog_definition.json"]
      }
    }
  }
}
```

### 在本项目中的角色

- **Framework 层**：`a2ui_extension.py` 生成 A2UI JSONL
- **Codegen 层**：生成 A2UI Extension 适配代码
- **前端渲染**：解析 A2UI JSONL 渲染动态 UI

---

## 4. 交互架构

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

---

## 5. 本项目实现

### Codegen 模块

| 文件 | 功能 |
|------|------|
| `core/scaffold_generator.py` | 生成 OAF 目录结构 |
| `frameworks/deepagents/agent_card_gen.py` | 生成 A2A Agent Card |
| `frameworks/deepagents/a2a_server.py` | 生成 A2A Server 代码 |
| `frameworks/deepagents/a2a_client.py` | 生成 A2A Client 代码 |
| `frameworks/deepagents/a2ui_extension.py` | 生成 A2UI Extension |

### Backend 模块

| 文件 | 功能 |
|------|------|
| `internal/model/oaf_config.go` | OAF 配置解析 |
| `internal/codegen/runner.go` | 调用 Codegen CLI |
| `internal/service/agent.go` | Agent 生命周期管理 |

### Frontend 模块

| 文件 | 功能 |
|------|------|
| `src/lib/oaf-types.ts` | OAF TypeScript 类型 |
| `src/lib/oaf-parser.ts` | OAF YAML 解析 |
| `src/app/agents/create/page.tsx` | OAF 配置表单 |

---

## 6. 协议版本

| 协议 | 版本 | 标准组织 |
|------|------|---------|
| OAF | v0.8.0 | Open Agent Format |
| A2A | v1.0.0 | Linux Foundation (Agentic AI Foundation) |
| A2UI | v0.8 | Google (Apache 2.0) |

---

## 7. 参考链接

- [OAF Specification](https://openagentformat.com/spec.html)
- [AGENTS.md Standard](https://agents.md/)
- [AgentSkills.io](https://agentskills.io/)
- [A2A Specification](https://github.com/a2a-ai/a2a-spec)
- [A2UI Specification](https://a2ui.org/)
