# Agent Manager 部署与 API 访问指南

**日期**: 2026-05-01  
**版本**: v1.1  
**状态**: ✅ 已更新

---

## 一、网络配置与访问地址

本项目已配置为通过局域网 IP 进行访问，适用于服务器部署环境。

### 1.1 访问地址

| 服务 | 访问地址 | 说明 |
| :--- | :--- | :--- |
| **前端页面** | `http://100.66.1.5:3000` | Agent 管理控制台 |
| **后端 API** | `http://100.66.1.5:8080` | RESTful API 接口 |

### 1.2 配置说明

*   **前端 API 地址**: 已硬编码为 `http://100.66.1.5:8080/api/v1`。
    *   配置文件: `frontend/src/lib/api.ts`
    *   修改方式: 更新 `const BASE` 变量。
*   **前端服务绑定**: Next.js 服务已配置为监听 `0.0.0.0:3000`，允许外部 IP 访问。
    *   启动命令: `pm2 start npm --name "frontend" -- start -- -p 3000 -H 0.0.0.0`

---

## 二、Agent API 调用指南

Agent 部署到 K8s 后，可以通过两种方式进行 API 调用。推荐使用 **后端代理模式**。

### 2.1 方式一：后端代理调用 (推荐)

由于 Agent 运行在 K8s 集群内部，外部无法直接通过域名访问。Go 后端提供了代理接口，将请求转发给对应的 Agent。

*   **接口地址**: `POST http://100.66.1.5:8080/api/v1/agents/{id}/invoke`
*   **适用场景**: 前端页面调用、外部系统集成、第三方应用对接。

#### 请求示例 (cURL)

```bash
curl -X POST http://100.66.1.5:8080/api/v1/agents/2/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "message": "请用中文简单介绍一下你自己",
    "history": []
  }'
```

#### 响应示例

```json
{
  "success": true,
  "data": {
    "response": "你好！我是智能客服助手，很高兴为你服务。请问有什么我可以帮你的吗？"
  },
  "error": null
}
```

#### 前端调用示例 (TypeScript)

```typescript
import { api } from '@/lib/api';

// 调用 ID 为 2 的 Agent
const response = await api.agents.invoke(2, {
  message: "你好",
  history: []
});

console.log(response.data.response);
```

### 2.2 方式二：K8s 内部直接访问 (调试用)

如果你需要在 K8s 集群内部（例如通过 `kubectl exec`）直接调试 Agent，可以使用 K8s 内部 DNS。

*   **内部域名格式**: `http://agent-{id}.default.svc.cluster.local:8000/chat`
*   **示例**: `http://agent-2.default.svc.cluster.local:8000/chat`

> **注意**: 此地址仅在 K8s 集群内部或配置了 DNS 解析的宿主机上有效，浏览器无法直接访问。

---

## 三、实现细节

### 3.1 后端代理实现

在 `backend/internal/handler/agent.go` 中新增了 `Invoke` 方法：

1.  **路由注册**: `r.POST("/agents/:id/invoke", h.Invoke)`
2.  **转发逻辑**:
    *   接收前端请求。
    *   构造 K8s 内部目标 URL (`http://agent-{id}.default.svc.cluster.local:8000/chat`)。
    *   使用 Go `http.Client` 发起内部请求。
    *   将 Agent 的响应原样返回给前端。

### 3.2 前端接口封装

在 `frontend/src/lib/api.ts` 中新增了 `invoke` 方法：

```typescript
invoke: (id: number, data: any) =>
  request(`/agents/${id}/invoke`, { 
    method: 'POST', 
    body: JSON.stringify(data) 
  }),
```

---

## 四、常见问题

### Q1: 调用 `/invoke` 接口报错 "Agent unreachable"？

*   **原因**: Agent 未成功部署或 Pod 处于 CrashLoopBackOff 状态。
*   **排查**:
    1.  检查 Agent 状态是否为 `published`。
    2.  在 K8s 中检查 Pod 状态: `kubectl get pods | grep agent-{id}`。
    3.  查看 Pod 日志: `kubectl logs agent-{id}`。

### Q2: 前端页面无法加载数据？

*   **原因**: 浏览器无法访问 `100.66.1.5:8080`。
*   **排查**:
    1.  检查服务器防火墙是否开放了 8080 和 3000 端口。
    2.  检查后端服务是否正在运行: `curl http://100.66.1.5:8080/api/v1/agents`。

### Q3: 如何修改 API 地址？

*   编辑 `frontend/src/lib/api.ts` 文件，修改 `BASE` 常量：
    ```typescript
    const BASE = 'http://你的新 IP:8080/api/v1';
    ```
*   重新构建并启动前端:
    ```bash
    cd frontend
    npm run build
    pm2 restart frontend
    ```
