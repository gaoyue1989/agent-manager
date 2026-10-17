# M7 · 集成 Agent（Agent Protocol 远程子 agent 集成关系）

| | |
|---|---|
| 依赖 | M2（console 骨架）；写入生效依赖 M3（生成器消费集成关系） |
| 对应设计 | §3.2「集成其它 Agent」行、§8.4；上游机制 [../design/travel-fulfillment-agent-protocol-design.md](../design/travel-fulfillment-agent-protocol-design.md)（已实施） |
| demo 对照 | `c/integrations`（已建立集成关系表 + 可注册端点表） |
> **实装与验收**：页面实拍 [../screenshots/console-split/06-console-integrations.png](../screenshots/console-split/06-console-integrations.png)（画廊：[../screenshots/console-split/README.md](../screenshots/console-split/README.md)）；
> e2e 套件 [../../e2e/console-split/m7-integration.sh](../../e2e/console-split/m7-integration.sh)（kind 集群实跑 PASS，2026-10-17；聚合入口 [run-all.sh](../../e2e/console-split/run-all.sh)）。

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：integration 候选探测三态/endpoint 派生/重复 409/引用保护 + agentgen subAgents golden 全绿（`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（协议多副本 job 扩展 + 声明链路断言）：集成登记→创建 lead→spawn 一轮子任务回流（可用 travel demo 双服务形态）；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：`agent_integrations` 表 + 登记/解除端点 + 候选端点探测。
- frontend：集成 Agent 页；创建页 SubAgent 编辑器接「已集成端点」下拉（M3 留位）。
- 边界（设计已定）：运行时编排零改动——`agent_spawn` 调度、子事件回流、确认快照轮询全部是 agent-framework 既有能力；本模块只管理**声明数据**（endpoint/ask 策略/认证头键）。

## 2. 后端改动

### 2.1 数据模型（model.go 追加 + AutoMigrate）

```go
type AgentIntegration struct {
    ID               uint      `gorm:"primaryKey" json:"id"`
    WorkspaceID      uint      `gorm:"index" json:"workspaceId"`
    MemberServiceID  uint      `gorm:"index" json:"memberServiceId"` // 被集成（member）的 service
    Name             string    `gorm:"size:64" json:"name"`          // 集成名（默认取 member displayName）
    EndpointURL      string    `gorm:"size:256" json:"endpointUrl"`  // http://{k8sName}-svc:8100（集群内直连）
    AskPolicy        string    `gorm:"size:16" json:"askPolicy"`     // PROPAGATE | DENY（SubagentDeclaration.remoteAskPolicy）
    RemoteStreaming  bool      `json:"remoteStreaming"`              // 默认 true（事件回流父流）
    AuthHeaderKey    string    `gorm:"size:64" json:"authHeaderKey"` // 如 X-Agent-Protocol-Token；值走 env（AGENT_REMOTE_HEADERS_JSON）
    Enabled          bool      `json:"enabled"`
    CreatedAt        time.Time `json:"createdAt"`
    UpdatedAt        time.Time `json:"updatedAt"`
}
func (AgentIntegration) TableName() string { return "agent_integrations" }
```

### 2.2 端点契约

```
GET  /api/v1/console/workspaces/:ws/integrations
  → {data:{
      relations:[{id, memberServiceId, name, endpointUrl, askPolicy, remoteStreaming, authHeaderKey, enabled,
                  referencedBy:[serviceId…]}],   // 引用方 = 生成包 subAgents[].endpoint 命中的 lead 服务（粗统计）
      candidates:[{serviceId, displayName, shortName, k8sName,
                   protocolEnabled:boolean,      // services.env_json 含 AGENT_PROTOCOL_ENABLED=true
                   probe:"online"|"unreachable"|"skipped"}]
    }}
    // probe：对候选集群内地址 GET {svc}:8100/agent-card.json（已有拉卡函数复用）；protocolEnabled=false 不探测
POST /api/v1/console/workspaces/:ws/integrations    （owner/admin）
  {memberServiceId, name?, askPolicy?, authHeaderKey?, enabled?}
  → 201；endpoint 由 member 服务派生（同空间、running 优先）；重复集成同一 member → 409 duplicate_integration
PATCH …/:id    {name?, askPolicy?, authHeaderKey?, enabled?}
DELETE …/:id   // 被生成包引用中 → 409 integration_in_use（提示先在 Agent 版本中移除 subAgent 再升级）
```

### 2.3 创建页写入（与 M3 生成器的字段契约）

- SubAgent 编辑器「从集成 Agent 选择」下拉数据 = enabled 集成关系；选中后行内固化 `endpoint=EndpointURL`、`askPolicy`、`authHeaderKey`；
- 生成包 frontmatter：

```yaml
subAgents:
  - agent: trip-booking
    role: "订票"
    delegations: ["…"]
    endpoint: "http://trip-booking-svc:8100"
```

- 认证：发布 env 追加 `AGENT_REMOTE_HEADERS_JSON={"X-Agent-Protocol-Token":"${?}"}` 进 **Secret**（值由用户在创建页「集成凭证」输入框提供或留空；键名来自集成行 authHeaderKey）——**值不落集成表、不落包**，只进当次发布 Secret；
- `askPolicy=DENY` 时 frontmatter 不写 endpoint 的 ask 相关注记（运行时 DENY = 远端 ask 不回流、自动拒绝——SDK 语义，生成器无需额外字段，仅提示文案差异）。

## 3. 前端改动

### 3.1 集成 Agent 页（`src/app/(console)/console/integrations/page.tsx` 新建）

对照 demo `c/integrations` 双栏：

- 左：「已建立的集成关系（lead → member）」表（member Agent/Endpoint/被引用（lead 名）/ask 策略 tag（PROPAGATE/DENY）/状态/解除集成）；表下注（demo 原文）：创建 Agent 时在「资产配置 → SubAgent」勾选已集成端点，写入生成包 subAgents[].endpoint；
- 右：「可注册的 Agent 端点」表（Agent/协议端点 `{svc}:8100/tasks`/状态徽标：POST /tasks 在线·未开启 Agent Protocol/不可达/集成按钮——未开启与不可达时 disabled）；底部说明条：对端需开启 `agentscope.agent-protocol.enabled=true`；服务间认证经 lead 的 `AGENT_REMOTE_HEADERS_JSON` env 注入（X-Agent-Protocol-Token），值不落包。
- 新建/编辑走右侧「注册 Agent 端点」主按钮 → 弹窗（候选下拉 + ask 策略单选 + 认证头键名输入）。

### 3.2 创建页联动（M3 留位填充）

- SubAgent 行编辑器：手填名称/职责 + 形态切换（本地 | 远程）；远程 = 从集成下拉选（自动带 endpoint），本地 = 留空 endpoint（生成 workspace `subagents/*.md` 语义，M3 生成器已区分）；
- 勾选数计入「已选资产」条。

### 3.3 文件清单

| 文件 | 动作 |
|------|------|
| `src/app/(console)/console/integrations/page.tsx` | 新建 |
| `src/app/(console)/console/agents/create/page.tsx` | 修改：SubAgentEditor 接集成下拉 |
| `src/lib/api.ts` | 修改：`api.console.integrations = { list, create, patch, remove }` |

## 4. 验收标准

- [ ] 本空间开启协议的服务（可用 travel demo 的 trip-booking/trip-approval 双服务形态验证）出现在候选表且 probe=online；未开启的显示 disabled。
- [ ] 登记集成 → 创建 Agent 勾选该 member → 生成包 frontmatter `subAgents[].endpoint` 正确、`AGENT_REMOTE_HEADERS_JSON` 只在 lead 的 Secret；
- [ ] 调试 Tab 编排对话触发 `agent_spawn`：子任务派发到 member、子事件回流（PROPAGATE 策略时确认卡可出现在父流——上游 M0 已知限制：远端确认以 member `GET /tasks/{id}` 快照为准，UI 提示语对齐设计文档口径，不承诺父流实时卡）。
- [ ] 解除集成：被引用时 409；解除后新建 Agent 下拉不再出现。

## 5. 测试要点

- `integration_test.go`：候选探测三态（httptest 假 agent-card 成功/超时/未开启跳过）；endpoint 派生；重复集成 409；引用保护。
- `agentgen_test.go` 补：集成行 → subAgents 段 golden；AGENT_REMOTE_HEADERS_JSON 进 SecretKeys。
- 手工（可选）：接 travel-fulfillment demo 双服务走通 lead→member 一轮（已有 e2e 协议多副本 job 覆盖运行时，本模块只需声明链路正确）。
