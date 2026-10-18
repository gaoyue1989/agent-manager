# M5 · 模型连接（工作空间级配置 → 测试 → 下发至 Agent `/models` → 使用情况展示）

| | |
|---|---|
| 依赖 | M2（console 骨架）、M3（创建页勾选与发布注入）、M4（调试 Tab 挂模型下拉） |
| 对应设计 | §3.2「模型连接」行、§8.4；对标 AgentCore 模型管理（[help.aliyun.com/zh/agentcore/model-management](https://help.aliyun.com/zh/agentcore/model-management)，add-connection 形态）；下发目标契约 [agent-framework/docs/session-model-switch-design.md](../../agent-framework/docs/session-model-switch-design.md)（已实施） |
| demo 对照 | `c/models`（连接配置表 + Agent 使用情况表 + 新建表单预览）、创建页「模型连接」多选勾选表 |
> **实装与验收**：页面实拍 [../screenshots/console-split/07-assets-models.png](../screenshots/console-split/07-assets-models.png) 与
> [07b-assets-models-runtime.png](../screenshots/console-split/07b-assets-models-runtime.png)（Agent 使用情况含**运行时存量模型**展开行：直连 Agent `/models` 真实清单，
> 来源标注 系统/存量/已下发 + 默认标记，与 model_deliveries 对照；业务面不可达时如实显示拉取失败。部署实测发现并修复：**模型热切换不得连带 spec 变更**——
> 编辑页切默认连接时若顺带改规格会滚动重启，单节点内存不足即 Pending「Pod 无法启动」，规格调整仅运维侧入口）；（画廊：[../screenshots/console-split/README.md](../screenshots/console-split/README.md)）；
> e2e 套件 [../../e2e/console-split/m5-model-connection.sh](../../e2e/console-split/m5-model-connection.sh)（kind 集群实跑 PASS，2026-10-17；聚合入口 [run-all.sh](../../e2e/console-split/run-all.sh)）。
| 上游参考 | M6（MCP 连接器）——两者同构：**先配置、测试，再经创建/更新 Agent 勾选下发，并展示 Agent 使用情况** |

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：modelconn CRUD/掩码/test 三分支/deliver 幂等（假 agent `/models` 计数断言）/usage 三态聚合全绿（`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（脚本 model-switch）：建连接→测试→创建 Agent 勾选→running 后 usage pending→synced→调试切换两轮（下发模型↔system）断言 session_user.model→删连接 usage 标记 removed；release-agent/远端停机降级为手工项（PR 附记录）；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：`model_connections` 表 + CRUD + 连通性测试 + **下发**（把连接配置同步到目标 Agent 的 `POST/PATCH /models`，幂等）+ **使用情况**聚合端点。
- frontend：模型连接页（连接表 + 使用情况表 + 新建表单）；创建页模型连接多选；调试 Tab 模型下拉（数据源 = 目标 Agent 自己的 `/models`）。
- 不在本模块：`model_config` 表本身（agent-framework 已实施，零改动）；预置供应商目录（一期仅 OpenAI 兼容自定义连接，对标 AgentCore「自定义模型供应商」形态）。

## 2. 后端改动

### 2.1 数据模型（model.go 追加 + AutoMigrate）

```go
type ModelConnection struct {
    ID          uint      `gorm:"primaryKey" json:"id"`
    WorkspaceID uint      `gorm:"index" json:"workspaceId"`
    Name        string    `gorm:"size:128" json:"name"`         // 空间内唯一（uniqueIndex(workspace_id,name)）
    Provider    string    `gorm:"size:32" json:"provider"`      // openai（一期仅 OpenAI 兼容）
    ModelID     string    `gorm:"size:128" json:"modelId"`      // 发给推理端点的模型名
    BaseURL     string    `gorm:"size:512" json:"baseUrl"`
    APIKey      string    `gorm:"size:512" json:"-"`            // 内网库明文（先例=model_config）；接口一律掩码回显
    Temperature float64   `json:"temperature"`                  // 默认 0.3
    MaxTokens   int       `json:"maxTokens"`                    // 默认 16384
    TimeoutSec  int       `json:"timeoutSeconds"`               // 默认 120
    Status      string    `gorm:"size:16" json:"status"`        // verified | unverified | failed
    LastTestedAt *time.Time `json:"lastTestedAt"`
    CreatedAt   time.Time `json:"createdAt"`
    UpdatedAt   time.Time `json:"updatedAt"`
}
func (ModelConnection) TableName() string { return "model_connections" }

// 下发状态（Agent 使用情况的数据源）
type ModelDelivery struct {
    ID             uint      `gorm:"primaryKey" json:"id"`
    ConnectionID   uint      `gorm:"index" json:"connectionId"`
    ServiceID      uint      `gorm:"index" json:"serviceId"`
    RemoteModelID  string    `gorm:"size:128" json:"remoteModelId"` // Agent /models 返回的 id（01J8XK…）
    Status         string    `gorm:"size:16" json:"status"`         // pending | synced | failed
    Error          string    `gorm:"size:256" json:"error"`
    SyncedAt       *time.Time `json:"syncedAt"`
    CreatedAt      time.Time `json:"createdAt"`
}
func (ModelDelivery) TableName() string { return "model_deliveries" }
```

### 2.2 端点契约

```
GET    /api/v1/console/workspaces/:ws/model-connections
  → {data:[{id,name,provider,modelId,baseUrl,apiKeyMasked:"sk-***a1b2",temperature,maxTokens,timeoutSeconds,
            status,lastTestedAt,deliveryCount}]}          // deliveryCount = 已成功下发的 Agent 数
POST   /api/v1/console/workspaces/:ws/model-connections   （owner/admin）
  {name, provider:"openai", modelId, baseUrl, apiKey, temperature?, maxTokens?, timeoutSeconds?}
  → 201；name 冲突 400 duplicate_name；baseUrl 须 http(s)
PATCH  /api/v1/console/workspaces/:ws/model-connections/:id   （apiKey 传 "" = 清除；改字段后 delivery 全部置 pending）
DELETE /api/v1/console/workspaces/:ws/model-connections/:id
  → 409 connection_in_use（仍为某 Agent 默认连接时拒绝；仅下发连接可直接删，同时删 delivery 行）
POST   /api/v1/console/workspaces/:ws/model-connections/:id/test
  → {data:{ok, latencyMs, error}}                          // backend 直连 baseUrl：最小 completion（max_tokens≈16）

POST /api/v1/console/workspaces/:ws/model-connections/:id/deliver    {serviceId}
  → 202 {data:{delivery}}                                   // 幂等：同 (connection,service) 已 synced 则跳过
GET  /api/v1/console/workspaces/:ws/model-connections/usage
  → {data:[{serviceId, displayName, defaultConnectionName,
            connections:[{connectionId, name, status: synced|pending|failed, remoteModelId, error, syncedAt}]}]}
GET  /api/v1/console/workspaces/:ws/models?serviceId=      // 调试下拉数据源：透传目标 Agent GET /models（含下发+系统默认）
```

### 2.3 下发实现（deliver，核心逻辑）

1. 目标 Agent 就绪校验（`services.status=running`，非 running → 400 `agent_not_ready`）；
2. 集群内直连 `http://{k8sName}-svc:8100/models`（与 A2A 拉卡同通道）：
   - `GET /models` 查重（按 `model_id+base_url` 匹配已存在项 → 跳过创建）；
   - 不存在 → `POST /models`（camelCase 请求体：name=连接名、provider、modelId、baseUrl、apiKey、temperature、maxTokens、timeoutSeconds、enabled:true）→ 记录响应 id 到 `model_deliveries.remote_model_id`；
   - 已存在但配置漂移（baseUrl/参数不一致）→ `PATCH /models/{id}`；
3. 全部成功 → `status=synced, synced_at=now`；失败 → `status=failed, error=摘要`（不重试，页面「重新下发」手动触发——无后台任务，守红线）；
4. 多副本安全：deliver 为幂等写（Agent 侧 POST /models 有 name 唯一约束 `duplicate_name`，重试命中即转 PATCH），任意副本可执行。

**发布链路联动**（M3 消费）：创建/更新 Agent 勾选的连接——

- 首个（默认连接）→ 发布 env：`LLM_BASE_URL/LLM_MODEL/LLM_API_KEY`（key 走 `SecretKeys`，进 `{name}-env-secret`，即该 Agent 的「系统默认模型」来源）；
- 全部勾选连接 → 发布成功（running）后自动创建 `model_deliveries(pending)` 行并逐个 deliver；M5 的 usage 表即展示这批状态。

### 2.4 删除/变更的级联语义

- 连接被删：Agent 侧 `model_config` 行**不自动删**（保守：删远端可能打断进行中会话）；usage 中该行标记 `failed(removed)`，可在 Agent 详情侧手动处理；引用会话回落默认模型（上游已实现）；
- 连接字段变更（PATCH）：全部 delivery 置 pending → 页面逐 Agent「重新下发」；
- 系统默认连接（空间首个验证通过的，或用户显式指定）删除：要求先把某 Agent 的默认连接改绑（409 `default_connection_in_use`）。

## 3. 前端改动

### 3.1 模型连接页（`src/app/(console)/console/assets/models/page.tsx` 新建）

对照 demo `c/models` 双栏：

- 左列上「已保存的模型连接（工作空间级）」表：连接名称（默认 tag）/model_id/Base URL/状态徽标（已验证·测试超时·未验证）/已下发 Agent 数/操作（编辑·测试·删除——默认连接无删除）；
- 左列下「Agent 使用情况」表：Agent/默认模型连接/已同步连接（tag 组）/下发状态（已同步·待同步·失败 + error tooltip）/操作（下发·重新下发）；
- 右列「新建模型连接（表单）」卡片：连接名称/模型 ID/Base URL（OpenAI 兼容端点 hint）/API Key（password，落库内网明文·接口掩码回显·下发时经服务 Secret 注入）/Temperature/Max Tokens +「保存并测试」；
- 底部说明条（demo 原文）：使用链路 = 创建/更新 Agent 勾选连接 → 默认连接经发布 env 注入 → 就绪后全部勾选连接幂等下发至 `/models`，调试对话可会话级切换。

### 3.2 创建页联动（M3 已留位填充）

- 「模型连接」多选勾选表（demo 形态）：列 = 勾选/连接/角色（首个勾选自动标「默认（注入 LLM_* env）」，其余「就绪后下发 /models」）/状态（未验证连接禁勾）；
- 提交体：`modelConnections:[{connectionId, isDefault}]`（M3 §2.2 的 `modelRef` 单选升级为多选）。

### 3.3 调试 Tab 模型下拉（M4 已留位填充）

- 数据源改为 `GET /console/.../models?serviceId={id}`（目标 Agent 自己的 `/models`：系统默认 + 已下发连接）；
- 切换语义不变（`POST /threads/chat {model}` / `PATCH /threads/{id}`，model=remote model id，`""`=回默认）。

### 3.4 文件清单

| 文件 | 动作 |
|------|------|
| `internal/handler/console.go` | 修改：model-connections 全部端点 |
| `internal/service/modelconn.go` | 新建：CRUD/test/deliver/usage 业务逻辑 |
| `internal/store/model.go`、`store/db.go` | 修改：两实体 + AutoMigrate |
| `src/app/(console)/console/assets/models/page.tsx` | 新建 |
| `src/app/(console)/console/agents/create/page.tsx` | 修改：模型连接多选 |
| `src/components/chat/ChatInput.tsx` | 修改：模型下拉接 `?serviceId=` 数据源 |
| `src/lib/api.ts` | 修改：`api.console.modelConnections = { list, create, patch, remove, test, deliver, usage, agentModels }` |

## 4. 验收标准

- [ ] 连接 CRUD：掩码回显正确（列表 `sk-***a1b2`、响应体无 apiKey 明文）；name 空间唯一。
- [ ] 测试：真实端点（dashscope/内网 vLLM）连通返回 latency；错误端点 status=failed + error 摘要。
- [ ] 创建 Agent 勾选两连接（deepseek 默认）→ Pod env 有 `LLM_BASE_URL/LLM_MODEL`（kubectl exec env 验证），key 仅在 Secret；running 后 usage 表两连接状态演进 pending→synced。
- [ ] 下发幂等：重复「下发」不产生 Agent 侧重复模型（`GET /models` 计数不变）；改连接 baseUrl 后重新下发走 PATCH（远端值更新）。
- [ ] 调试下拉 = 系统（注入的默认）+ 已下发连接；切换 `POST /threads/chat {model}` 生效（session_user.model 落 remote id）。
- [ ] 删除保护：默认连接 409；下发中连接删除后 usage 标记 `removed`，Agent 侧不自动删。
- [ ] member 只读（表单隐藏）；owner/admin 全量。

## 5. 测试要点（单测）

- `modelconn_test.go`：CRUD 唯一性/掩码/清除 apiKey；test 对 httptest 假 OpenAI 端点（成功/超时/非 200）；deliver 幂等（同连接二次 deliver 零 POST、漂移走 PATCH——httptest 假 agent `/models` 计数断言）；usage 聚合含 pending/failed/removed 三态。
- `agentgen_test.go` 补（联动 M3）：modelConnections 多选 → env 注入 + SecretKeys + delivery pending 行创建。
- e2e（脚本 model-switch，DoD 硬门槛）：建连接 → 测试 → 创建 Agent 勾选 → running 后 usage 全 synced → 调试切模型两轮（remote id ↔ system）断言 session_user.model → 删连接后 usage 标记 removed。
