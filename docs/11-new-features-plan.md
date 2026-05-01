# Agent Manager 新增功能开发计划

**日期**: 2026-05-01  
**版本**: v1.2  
**状态**: 待确认

---

## 一、功能需求概述

### 1.1 功能列表

| 编号 | 功能 | 描述 | 优先级 |
|------|------|------|--------|
| F1 | 镜像信息展示 | 构建镜像后显示 Docker 镜像地址和名称 | 高 |
| F2 | Pod 状态监控 | 发布后动态获取 Agent Pod 运行状态 | 高 |
| F3 | Agent 聊天测试 | 发布后增加聊天按钮，支持前端直接测试 Agent | 高 |

---

## 二、详细设计

### 2.1 F1: 镜像信息展示

#### 2.1.1 后端修改

**文件**: `backend/internal/service/deploy.go`

**修改内容**:
- `BuildImage` 方法返回时，将 `image_tag` 保存到 `ImageBuild` 记录中（已实现）。
- 新增 `GetImageInfo` 方法，返回镜像详细信息。

**新增 API**:
```
GET /api/v1/agents/:id/image-info
```

**响应示例**:
```json
{
  "image_tag": "172.20.0.1:5001/agent-2:v1",
  "image_name": "agent-2",
  "registry": "172.20.0.1:5001",
  "version": "v1",
  "build_status": "success",
  "build_time": "2026-05-01T20:00:00Z"
}
```

#### 2.1.2 前端修改

**文件**: `frontend/src/app/agents/[id]/page.tsx`

**修改内容**:
- 在 Agent 详情页增加"镜像信息"卡片。
- 显示镜像标签、仓库地址、构建状态、构建时间。
- 提供"复制镜像地址"按钮。

**UI 示例**:
```
┌─────────────────────────────────────┐
│ 镜像信息                             │
├─────────────────────────────────────┤
│ 镜像地址: 172.20.0.1:5001/agent-2:v1 │
│ 构建状态: ✅ 成功                    │
│ 构建时间: 2026-05-01 20:00:00        │
│ [复制地址]                           │
└─────────────────────────────────────┘
```

---

### 2.2 F2: Pod 状态监控

#### 2.2.1 后端修改

**文件**: `backend/internal/k8s/sandbox.go`

**新增方法**:
```go
// GetPodStatus 获取 Agent Pod 的运行状态
func (s *SandboxClient) GetPodStatus(sandboxName string) (map[string]interface{}, error)
```

**返回数据结构**:
```json
{
  "pod_name": "agent-2",
  "status": "Running",
  "ready": true,
  "restarts": 0,
  "age": "5m",
  "ip": "10.244.0.15",
  "node": "agent-manager-control-plane"
}
```

**新增 API**:
```
GET /api/v1/agents/:id/pod-status
```

**响应示例**:
```json
{
  "sandbox_name": "agent-2",
  "pod_status": "Running",
  "ready": true,
  "restarts": 0,
  "age": "5m",
  "ip": "10.244.0.15",
  "last_checked": "2026-05-01T20:05:00Z"
}
```

#### 2.2.2 前端修改

**文件**: `frontend/src/app/agents/[id]/page.tsx`

**修改内容**:
- 在 Agent 详情页增加"Pod 状态"卡片。
- 显示 Pod 名称、状态、就绪情况、重启次数、运行时长、IP 地址。
- 添加"刷新"按钮，点击后调用接口获取最新 Pod 状态。
- **不自动轮询**，仅在用户点击刷新或页面加载时获取状态。

**UI 示例**:
```
┌─────────────────────────────────────┐
│ Pod 状态              [刷新]         │
├─────────────────────────────────────┤
│ Pod 名称: agent-2                   │
│ 状态: 🟢 Running                    │
│ 就绪: ✅ Ready                      │
│ 重启次数: 0                         │
│ 运行时长: 5m                        │
│ Pod IP: 10.244.0.15                 │
│ 最后更新: 2026-05-01 20:05:00       │
│                                     │
│ ℹ️ 点击"刷新"按钮获取最新状态        │
└─────────────────────────────────────┘
```

---

### 2.3 F3: Agent 聊天测试

#### 2.3.1 后端修改

**文件**: `backend/internal/handler/agent.go`

**已有功能**: `/api/v1/agents/:id/invoke` 代理接口（已在上一版本实现）。

**新增功能**:
- 增强 `Invoke` 方法，支持流式响应（可选）。
- 添加请求日志记录，方便调试。

**新增 API**:
```
POST /api/v1/agents/:id/chat
```

**请求体**:
```json
{
  "message": "你好",
  "history": [
    {"role": "user", "content": "你好"},
    {"role": "assistant", "content": "你好！有什么可以帮你的？"}
  ]
}
```

**响应体**:
```json
{
  "success": true,
  "data": {
    "response": "你好！我是智能客服助手，很高兴为你服务。",
    "latency_ms": 1250
  },
  "error": null
}
```

#### 2.3.2 前端修改

**文件**: `frontend/src/app/agents/[id]/page.tsx`

**修改内容**:
- 在 Agent 详情页增加"聊天测试"区域（仅当 Agent 状态为 `published` 时显示）。
- 提供聊天输入框和发送按钮。
- 显示聊天历史记录。
- 显示响应延迟时间。

**UI 示例**:
```
┌─────────────────────────────────────┐
│ Agent 聊天测试                       │
├─────────────────────────────────────┤
│ [聊天记录区域]                       │
│ 用户: 你好                           │
│ Agent: 你好！有什么可以帮你的？       │
│                                     │
│ [输入框]                    [发送]   │
│                                     │
│ 响应时间: 1.25s                     │
└─────────────────────────────────────┘
```

**新增文件**: `frontend/src/components/ChatTest.tsx`
- 独立的聊天测试组件。
- 支持消息发送、历史记录显示、延迟统计。

---

## 三、数据库变更

### 3.1 现有表结构

无需修改现有表结构，所有新增信息均可通过 API 实时获取。

### 3.2 可选优化

如果需要持久化 Pod 状态历史，可新增表：

```sql
CREATE TABLE pod_status_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT NOT NULL,
    pod_name    VARCHAR(128),
    status      VARCHAR(64),
    ready       BOOLEAN,
    restarts    INT,
    ip          VARCHAR(64),
    checked_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    INDEX idx_agent_id (agent_id)
);
```

> **注**: 本版本暂不实现持久化，仅实时查询。

---

## 四、单元测试计划

### 4.1 后端单元测试

| 测试文件 | 测试内容 | 覆盖率目标 |
|---------|---------|-----------|
| `backend/internal/service/deploy_test.go` | `BuildImage`, `GetImageInfo` | > 80% |
| `backend/internal/k8s/sandbox_test.go` | `GetPodStatus`, `CreateSandbox`, `DeleteSandbox` | > 80% |
| `backend/internal/handler/agent_test.go` | `Invoke`, `GetImageInfo`, `GetPodStatus` | > 80% |

**测试策略**:
- 使用 `testify` 进行断言。
- 使用 `gomock` 模拟 K8s 客户端和 Docker 客户端。
- 使用 `httptest` 测试 HTTP 接口。

### 4.2 前端单元测试

| 测试文件 | 测试内容 | 覆盖率目标 |
|---------|---------|-----------|
| `frontend/src/lib/api.test.ts` | API 调用方法 | > 80% |
| `frontend/src/components/ChatTest.test.tsx` | 聊天组件渲染和交互 | > 70% |

**测试策略**:
- 使用 `jest` 和 `@testing-library/react`。
- 使用 `msw` 模拟 API 响应。

---

## 五、端到端测试计划

### 5.1 测试场景

| 场景编号 | 场景描述 | 预期结果 |
|---------|---------|---------|
| E2E-01 | 构建镜像后查看镜像信息 | 显示正确的镜像地址、名称、构建状态 |
| E2E-02 | 发布后查看 Pod 状态 | 显示 Pod Running 状态，IP 地址正确 |
| E2E-03 | 发布后点击刷新按钮 | Pod 状态实时更新 |
| E2E-04 | 发布后使用聊天功能 | 发送消息后收到 Agent 响应，显示延迟时间 |
| E2E-05 | 聊天功能多轮对话 | 历史记录正确显示，上下文保持 |
| E2E-06 | 下线后聊天按钮隐藏 | 聊天区域不显示 |

### 5.2 测试工具

- **Puppeteer**: 浏览器自动化测试。
- **Jest**: 测试框架。
- **测试脚本**: `e2e/test-new-features.js`

### 5.3 测试执行流程

1.  启动后端服务 (`pm2 start backend`)。
2.  启动前端服务 (`pm2 start frontend`)。
3.  创建测试 Agent。
4.  执行构建、发布操作。
5.  运行 Puppeteer 脚本，自动截图验证。
6.  生成测试报告。

---

## 六、执行顺序

| 阶段 | 任务 | 预估时间 | 依赖 |
|------|------|---------|------|
| **1** | 后端开发 (F1, F2, F3) | 2h | 无 |
| **2** | 前端开发 (F1, F2, F3) | 2h | 阶段 1 |
| **3** | 后端单元测试 | 1h | 阶段 1 |
| **4** | 前端单元测试 | 1h | 阶段 2 |
| **5** | 端到端测试 | 1h | 阶段 3, 4 |
| **6** | 文档更新 | 30min | 阶段 5 |

**总预估时间**: 约 7.5 小时

---

## 七、验收标准

### 7.1 F1 验收标准
- [ ] 构建镜像后，Agent 详情页显示镜像地址卡片。
- [ ] 镜像地址格式正确：`{registry}/{name}:{version}`。
- [ ] 点击"复制"按钮可复制镜像地址到剪贴板。

### 7.2 F2 验收标准
- [ ] 发布后，Agent 详情页显示 Pod 状态卡片。
- [ ] Pod 状态实时更新（自动轮询或手动刷新）。
- [ ] 显示 Pod 名称、状态、就绪情况、重启次数、IP 地址。

### 7.3 F3 验收标准
- [ ] 发布后，Agent 详情页显示聊天测试区域。
- [ ] 输入消息并发送，能收到 Agent 响应。
- [ ] 显示响应延迟时间。
- [ ] 聊天记录正确显示。
- [ ] 下线后聊天区域自动隐藏。

---

## 八、风险与注意事项

1.  **K8s API 访问**: `GetPodStatus` 需要 K8s 客户端有权限读取 Pod 状态。需确保 ServiceAccount 权限正确。
2.  **网络延迟**: 聊天测试的延迟时间可能受网络影响，需在测试中设置合理的超时时间。
3.  **资源限制**: 自动轮询 Pod 状态会增加 K8s API 调用频率，需控制轮询间隔（建议 ≥ 10 秒）。
4.  **安全性**: 聊天测试接口需添加认证机制（本版本暂不实现，后续版本补充）。

---

## 九、下一步

请审核以上计划文档，确认后我将按顺序执行：
1.  后端开发 (F1, F2, F3)
2.  前端开发 (F1, F2, F3)
3.  单元测试
4.  端到端测试
5.  文档更新

**审核要点**:
- [ ] 功能设计是否满足需求
- [ ] API 设计是否合理
- [ ] UI 设计是否清晰
- [ ] 测试计划是否完整
- [ ] 执行顺序是否需要调整
