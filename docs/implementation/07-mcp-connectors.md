# M6 · 工具管理（MCP 连接器配置：创建 → 保存 → 创建/更新 Agent 时注入）

| | |
|---|---|
| 依赖 | M2（console 骨架）；注入生效依赖 M3（生成器消费连接器） |
| 对应设计 | §3.2「工具管理（MCP）」行、§8.4；对标 AgentCore tools/create |
| demo 对照 | `c/tools`（连接器配置表 + 新建表单预览 + 三态权限默认策略） |

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：mcpconnector CRUD/verify 三分支/refCount + agentgen config.yaml golden 全绿（`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（脚本 mcp-connector）：连接器 verify（以平台自身 `/mcp` 为靶）→创建 Agent 勾选→包 config.yaml 断言→Secret 含 `MCP_TOKEN_*` 且包内无 token；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：`mcp_connectors` 表 + CRUD + 验证（连通性探测）端点。
- frontend：工具管理页（配置创建/维护/验证 + 权限默认策略展示）；创建页 MCP 勾选接真数据（M3 已留位）。
- 注入链路本身在 M3（agentgen 消费 `connectorId`），本模块交付数据源与 `mcp-configs/config.yaml` 的字段约定。

## 2. 后端改动

### 2.1 数据模型（model.go 追加 + AutoMigrate）

```go
type McpConnector struct {
    ID          uint      `gorm:"primaryKey" json:"id"`
    WorkspaceID uint      `gorm:"index" json:"workspaceId"`
    Name        string    `gorm:"size:64" json:"name"`            // 空间内唯一（uniqueIndex(workspace_id,name)）
    Transport   string    `gorm:"size:24" json:"transport"`       // streamable_http | sse
    Endpoint    string    `gorm:"size:512" json:"endpoint"`
    AuthType    string    `gorm:"size:16" json:"authType"`        // none | bearer
    AuthToken   string    `gorm:"size:512" json:"-"`              // 明文（内网库先例=model_config）；注入走服务 Secret
    TimeoutSec  int       `json:"timeoutSec"`                     // 默认 120
    ToolScope   string    `gorm:"type:json" json:"toolScope"`     // {"mode":"all"} | {"mode":"subset","tools":[…]}
    Permissions string    `gorm:"type:json" json:"permissions"`   // {"tools":{"ticket_query":"allow",…}} 三态默认策略
    Status      string    `gorm:"size:16" json:"status"`          // verified | unverified | failed
    LastVerifiedAt *time.Time `json:"lastVerifiedAt"`
    CreatedAt   time.Time `json:"createdAt"`
    UpdatedAt   time.Time `json:"updatedAt"`
}
func (McpConnector) TableName() string { return "mcp_connectors" }
```

### 2.2 端点契约

```
GET    /api/v1/console/workspaces/:ws/tools
  → {data:[{id,name,transport,endpoint,authType,timeoutSec,toolScope,permissions,status,lastVerifiedAt,refCount}]}
     // refCount = 引用该连接器的生成包数（按 mcpServers 声明统计，粗粒度：统计 services 包 frontmatter 含 server 名）
POST   /api/v1/console/workspaces/:ws/tools          （owner/admin）
  {name, transport, endpoint, authType?, authToken?, timeoutSec?, toolScope?, permissions?}
  → 201；name 冲突 400 duplicate_name；endpoint 须 http(s)
PATCH  /api/v1/console/workspaces/:ws/tools/:id      （局部更新；authToken 传 "" = 清除）
POST   /api/v1/console/workspaces/:ws/tools/:id/verify
  → {data:{ok, toolCount, tools:[{name,description}], latencyMs, error}}
     // MCP initialize + tools/list 探测（Go 侧最小 MCP 客户端：POST {endpoint} initialize → tools/list）
     // 成功回写 status=verified + tools 快照进 toolScope（subset 模式的选项源）；失败 status=failed
DELETE /api/v1/console/workspaces/:ws/tools/:id      // 被 Agent 引用中（refCount>0）→ 409 connector_in_use
```

- 读：member 可见；写：owner/admin。
- `platform-mcp（backend）` 不落表——创建页将其作为**内置虚拟连接器**注入选项（endpoint=/mcp、17 工具、ask 全量），生成 `mcp-configs/platform/config.yaml` 时按 release-agent 同款格式硬编码模板。

### 2.3 注入约定（与 M3 生成器的字段契约）

连接器行 → `mcp-configs/<name>/config.yaml`：

```yaml
server: <name>
vendor: agentmanager
version: "1.0.0"
connection:
  type: streamableHttp | sse
  url: <endpoint>
  timeout: <timeoutSec>
permissions:
  tools:
    <tool>: allow|ask|deny      # Permissions 列原样展开；mode=all 且未声明工具 → 不写 tools（运行时按未声明自动放行）
```

- bearer token → 发布 env `MCP_TOKEN_<NAME大写>` 追加进 SecretKeys（M3 §2.3 已约定）；
- `toolScope.mode=subset` → config.yaml 额外写 `tools.include: [...]`（对齐 OAF ActiveMCP 语义，若运行时不支持 include 则生成器降级为全量 + 前端提示——以 agent-framework McpToolRegistrar 实测为准，实施时核对）。

## 3. 前端改动

### 3.1 工具管理页（`src/app/(console)/console/assets/tools/page.tsx` 新建）

对照 demo `c/tools` 双栏：

- 左列上：「已保存的连接器配置（工作空间级）」表（连接器/传输/Endpoint/工具数/状态徽标：已验证·未验证·失败/被引用数/编辑·验证·删除）；platform-mcp 内置行置顶（tag「平台内置」，无操作）；
- 左列下：「工具权限默认策略（三态）」表（工具/权限 badge/说明），数据 = 选中连接器的 Permissions（行点击联动或默认首个 verified 连接器）；
- 右列：「新建连接器（表单）」卡片（名称/传输下拉/Endpoint/认证下拉（Bearer Token（存服务 Secret）/无认证）/超时/工具范围下拉（全部/指定子集——子集选项来自上次 verify 快照））+「保存并验证」提交 → 成功后左表新增行且 status=verified；
- 底部说明条（demo 原文要点）：注入 = Agent 创建/更新勾选后写入生成包 `mcpServers` + `mcp-configs/`；认证凭据不落包，经 `{name}-env-secret` 注入运行时。

### 3.2 创建页联动（M3 留位填充）

- MCP 勾选表数据 = `api.console.tools.list()` + 内置 platform-mcp 行；勾选数计入「已选资产」条；
- 权限预览 badge 组 = 选中连接器的 Permissions 前三项 + 「…」。

### 3.3 文件清单

| 文件 | 动作 |
|------|------|
| `src/app/(console)/console/assets/tools/page.tsx` | 新建 |
| `src/app/(console)/console/agents/create/page.tsx` | 修改：McpPicker 接真数据 |
| `src/lib/api.ts` | 修改：`api.console.tools = { list, create, patch, verify, remove }` |

## 4. 验收标准

- [ ] 创建连接器 → 保存并验证：探测真实 MCP server（可用 platform 自身 `/mcp` 当靶子：endpoint 指向 backend NodePort）返回 17 工具，status=verified。
- [ ] 三态权限保存后，创建 Agent 勾选该连接器 → 生成包 config.yaml 的 permissions 段与配置一致（下载包 diff 验证）；token 只在服务 Secret（`kubectl get secret {name}-env-secret` 有 `MCP_TOKEN_*`，包内无）。
- [ ] 被 Agent 引用的连接器删除 409；解除引用（重发布去掉勾选）后可删。
- [ ] member 只读（按钮隐藏）；owner/admin 全量可写。

## 5. 测试要点

- `mcpconnector_test.go`：CRUD 与唯一性；verify 对 httptest 假 MCP（initialize+tools/list 两步）的成功/超时/非 200 三分支与 status 回写；refCount 粗统计。
- `agentgen_test.go` 补（M3 基础上）：connectorId → config.yaml 生成的 golden 比对（三态混合 + subset + bearer SecretKeys 追加）。
