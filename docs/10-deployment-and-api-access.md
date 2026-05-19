# Agent Manager 部署与 API 访问指南

**日期**: 2026-05-19  
**版本**: v1.3  
**状态**: ✅ 已更新

---

## 一、网络架构与访问地址

### 1.1 Nginx 反向代理架构

```
浏览器 (100.66.1.5)
    │
    ▼ :8911
┌──────────┐
│  Nginx   │  (宿主 /etc/nginx/conf.d/agent-manager.conf)
│  :8911   │
└──────────┘
  │          │            │
  │ /        │ /api/      │ /agent/
  ▼ :3000    ▼ :8080      ▼ :30080
Frontend    Backend       K8s Ingress
(Next.js)   (Go/Gin)      Controller
                           │
                           ▼ agent-{id}-svc:8100
                        Agent Pod
```

### 1.2 访问地址

| 服务 | 访问地址 | 说明 |
| :--- | :--- | :--- |
| **统一入口** | `http://100.66.1.5:8911` | Nginx 反向代理入口 |
| 前端页面 | `http://100.66.1.5:8911/` | Agent 管理控制台 |
| 后端 API | `http://100.66.1.5:8911/api/v1/...` | 通过 Nginx 代理 |
| Agent 端点 | `http://100.66.1.5:8911/agent/{id}/...` | Agent 服务直连 |
| Agent 调试页 | `http://100.66.1.5:8911/agent/{id}/debug` | Debug Console |

### 1.3 配置说明

*   **前端 API 地址**: 使用相对路径 `/api/v1`，通过 Nginx 代理至后端。
    *   配置文件: `frontend/src/lib/api.ts`
    *   修改方式: 设置 `NEXT_PUBLIC_API_URL` 环境变量或保持默认。
*   **后端服务**: 监听 `:8080`，由 Nginx `/api/` 路径代理。
*   **Nginx 配置**: `/etc/nginx/conf.d/agent-manager.conf`，统一入口 `:8911`。

---

## 二、Agent API 调用指南

Agent 部署到 K8s 后，可通过以下方式访问。

### 2.1 方式一：后端代理 Chat (推荐)

Go 后端将请求转换为 JSON-RPC 格式，通过 Ingress 转发给 Agent Pod，解析 JSON-RPC 响应并返回。

*   **接口地址**: `POST /api/v1/agents/{id}/chat`
*   **适用场景**: 前端页面、外部系统集成。

#### 请求示例 (cURL)

```bash
curl -X POST http://127.0.0.1:8911/api/v1/agents/61/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "hello", "history": []}'
```

#### 响应示例

```json
{
  "success": true,
  "data": {
    "response": "Hello! I'm your assistant..."
  },
  "latency_ms": 6385
}
```

#### 内部转发流程

```
前端 /api/v1/agents/:id/chat
  → 后端 DeployService.ChatWithAgent()
    → 构造 JSON-RPC body: {jsonrpc:"2.0",method:"message/send",params:{message:{role:"user",parts:[{text:"..."}]}}}
    → POST {endpointURL}  (Ingress: http://127.0.0.1:8911/agent/{id})
    → Nginx → K8s Ingress → agent-{id}-svc:8100
    ← 解析 JSON-RPC response → extract result.artifacts[0].parts[0].text
  ← 返回 {success, data:{response}}
```

### 2.2 方式二：直接访问 Agent 端点

通过 Nginx `/agent/` 代理路径直接访问 Agent 容器的所有端点：

#### 可用端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/agent/{id}/health` | 健康检查 |
| GET | `/agent/{id}/` | 服务信息 |
| GET | `/agent/{id}/threads` | Thread 列表 |
| GET | `/agent/{id}/threads/{tid}` | Thread 对话历史 |
| GET | `/agent/{id}/skills` | Skills 列表 |
| GET | `/agent/{id}/mcp` | MCP 服务器列表 |
| GET | `/agent/{id}/tools` | 工具列表 |
| GET | `/agent/{id}/debug` | Debug Console 页面 |
| GET | `/agent/{id}/.well-known/agent-card.json` | Agent Card 发现 |
| POST | `/agent/{id}/` | JSON-RPC 2.0 (message/send, message/stream) |

#### 示例

```bash
# 健康检查
curl http://127.0.0.1:8911/agent/61/health

# 发送 JSON-RPC 消息
curl -X POST http://127.0.0.1:8911/agent/61/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","id":"1","params":{"message":{"role":"user","parts":[{"text":"hello"}]}}}'
```

### 2.3 方式三：K8s 内部直接访问 (调试用)

```bash
kubectl exec -it agent-61 -- curl http://localhost:8100/health
```

> **注意**: Agent 容器监听端口 **8100**，非 8000。

---

## 三、环境变量配置

### 3.1 后端必需环境变量

| 变量 | 说明 | 示例 |
|------|------|------|
| `LLM_API_KEY` | LLM API 密钥 | `209df1...` |
| `LLM_MODEL` | 模型 ID (注入为 `LLM_MODEL_ID`) | `5df2c9ff...` |
| `LLM_ENDPOINT` | API 端点 URL (注入为 `LLM_BASE_URL`) | `https://wishub-x6.ctyun.cn/v1` |
| `INGRESS_HOST` | Ingress 入口地址 | `127.0.0.1:8911` 或 `100.66.1.5:8911` |
| `DEFAULT_CHECKPOINT_DSN` | MySQL checkpoint DSN | `mysql+asyncmy://user:pass@172.20.0.1:3307/db` |

> **重要**: Checkpoint DSN 中的主机地址须用 `172.20.0.1`（Docker 网关），因为 K8s Pod 内 `127.0.0.1` 指向 Pod 自身而非宿主机 MySQL。

### 3.2 后端启动命令

```bash
LLM_API_KEY=<key> \
LLM_MODEL=<model_id> \
LLM_ENDPOINT=<api_url> \
INGRESS_HOST=127.0.0.1:8911 \
DEFAULT_CHECKPOINT_DSN="mysql+asyncmy://agent_manager:Agent%40Manager2026@172.20.0.1:3307/agent_manager" \
nohup /root/agent-manager/backend/server > /tmp/agent-manager-backend.log 2>&1 &
```

---

## 四、运行模式

### 4.1 挂载模式 (Mount)

使用预构建的 `agent-framework` 镜像，配置通过 ConfigMap 挂载到容器 `/config` 目录。

- 无需构建镜像，部署速度快
- 镜像统一管理，便于批量更新
- 配置存储于 MinIO，部署时自动生成 ConfigMap + Secret
- 服务端口: **8100**
- 支持独立的 Checkpoint DSN

### 4.2 构建模式 (Build)

从 Agent 配置生成代码，构建自定义 Docker 镜像后部署。

- 适用于需要自定义依赖的复杂 Agent
- 支持基础镜像 `agent-base:latest` 加速构建
- 服务端口: 8000

---

## 五、常见问题

### Q1: Chat 返回 "agent unreachable"？

*   **原因**: Pod 未就绪、Ingress 路由错误、或端口配置不正确。
*   **排查**:
    1.  检查 Pod 状态: `kubectl get pod agent-{id}`
    2.  检查 Ingress: `kubectl get ingress agent-{id}-ingress`
    3.  直接测试: `curl http://127.0.0.1:8911/agent/{id}/health`
    4.  检查 `INGRESS_HOST` 是否设置正确

### Q2: Pod 处于 ImagePullBackOff？

*   **原因**: K8s 无法拉取镜像。
*   **排查**:
    1.  镜像是否推送到本地 registry: `docker push 172.20.0.1:5001/agent-framework:latest`
    2.  Kind 节点 containerd 是否配置了 HTTP registry: `insecure_skip_verify = true`
    3.  挂载模式重新发布时是否走了 DeployWithMount（非 Deploy）
    4.  镜像名是否正确（挂载模式自动补全 registry 前缀）

### Q3: Debug 页面加载后所有 API 请求 404？

*   **原因**: Debug 页面的 `BASE` 路径使用了 `window.location.origin`，导致请求发到根路径而非 `/agent/{id}/`。
*   **确认**: 刷新页面后问题应已修复（`debug_page.html` 已更新为从 `pathname` 提取路径前缀）。

### Q4: 前端页面 API 请求失败？

*   **原因**: Nginx 未配置或后端未启动。
*   **排查**:
    1.  `curl http://127.0.0.1:8911/api/v1/agents`
    2.  检查后端进程: `ps aux | grep backend/server`
    3.  检查 Nginx: `nginx -t && curl http://127.0.0.1:8911/`

### Q5: 如何修改 Agent 的 LLM 配置？

*   重启后端时传入正确的 `LLM_API_KEY` / `LLM_MODEL` / `LLM_ENDPOINT` 环境变量。
*   重新发布 Agent 使新配置生效到 Pod。
*   Pod 的 `LLM_API_KEY` 存于 K8s Secret `agent-{id}-secret`，`LLM_MODEL_ID` 和 `LLM_BASE_URL` 通过 Sandbox env 注入。
