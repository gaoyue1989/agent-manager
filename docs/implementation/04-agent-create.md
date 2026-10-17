# M3 · 创建 Agent（单页分区表单 + OAF 包生成器 + 复用发布）

| | |
|---|---|
| 依赖 | M2（console 骨架）；M6/M7 提供勾选源（可后置：表单先渲染空态） |
| 对应设计 | §8.2 创建页与生成包映射、§13 决议 4/5 |
| demo 对照 | `c/create`（选择创建方式三卡片 + 1~5 编号区块 + 右上/底部「立即创建」） |
> **实装与验收**：页面实拍 [../screenshots/console-split/04-agent-create.png](../screenshots/console-split/04-agent-create.png)（画廊：[../screenshots/console-split/README.md](../screenshots/console-split/README.md)）；
> e2e 套件 [../../e2e/console-split/m3-agent-create.sh](../../e2e/console-split/m3-agent-create.sh)（kind 集群实跑 PASS，2026-10-17；聚合入口 [run-all.sh](../../e2e/console-split/run-all.sh)）。

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：agentgen/publish/console 三组测试全绿（含 frontmatter golden 比对与编辑链路，`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（核心 job 扩展，主链路）：表单创建→running→A2A 注册→对话一句→下载包断言 frontmatter/mcp-configs/token 不落包→**编辑改提示词保存→v2 republish→再对话断言新行为**；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：`service/agentgen.go` 包生成器（表单 → AGENTS.md frontmatter + skills/mcp-configs → zip → 登记包）；`POST /console/workspaces/:ws/agents` 创建并发布；PublishRequest 扩展（spec 档/workspace/endpoint 开关）。
- frontend：创建页单页分区表单（托管 Harness 主模式；导入 OAF 包 / 纳管次要模式）。
- 不在本模块：MCP 连接器管理（M6）、集成关系管理（M7）——本模块只消费其数据。

## 2. 后端改动

### 2.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `internal/service/agentgen.go` | 新建 | 表单 → OAF 包（§2.3） |
| `internal/service/package.go` | 修改 | 新增 `RegisterGenerated(slug, version, zipBytes)`：复用 Upload 的落盘 + 登记核心（Upload 保持 multipart 入口不变，内部抽公共函数） |
| `internal/service/publish.go` | 修改 | PublishRequest 扩展（§2.4）+ spec 档映射 + 与 DEPLOYMENT_TEMPLATE 互斥校验 |
| `internal/handler/console.go` | 修改 | 新增 `POST /agents`（§2.2） |
| `internal/store/model.go` | 修改 | ServiceEntity 增列 `WorkspaceID/CreatedBy/PublicEndpoint/Spec` |
| 对应 `_test.go` | 新建/扩展 | §5 |

### 2.2 API 契约

```
POST /api/v1/console/workspaces/:ws/agents        （owner/admin，member 403）
{
  "mode": "harness",                              // harness（本模块）| import | adopt（后两个走既有链路，见 §2.5）
  "name": "客服工单助手-压测版",
  "description": "承接客服二级工单",
  "image": "gaoyue1989/agent-framework:agentscope-2.1.0-v20260907",
  "modelConnections": [                            // 工作空间模型连接多选（M5）：首个 = 默认
    {"connectionId": 1, "isDefault": true},
    {"connectionId": 2, "isDefault": false}
  ],
  "prompt": "你是客服工单平台的智能助手，负责：…",
  "skills":  [{"name":"ticket-escalation","source":"package","packageId":7},{"name":"cs-glossary","scope":"l4"}],
  "subAgents":[{"name":"ticket-classifier","role":"分类","delegations":["…"],"endpoint":"http://trip-booking-svc:8100"}],
  "mcpServers":[{"connectorId":3}],               // 引用 M6 连接器；M6 未上线时传空数组
  "spec": "medium",                               // small(1c2g) | medium(2c4g) | large(4c8g)
  "replicas": 2,
  "publicEndpoint": true
}
  → 202 {data:{service, package}}                 // 立即返回 deploying 态实体 + 生成包记录（id/version=1.0.0）
  → 400 invalid_form（prompt 空 / name 非法 / modelConnections 空或含未验证连接 / spec 非法 / replicas 越界）
  → 409 duplicate_name（k8s_name 冲突 = 既有发布重名校验同源；slug 冲突 = 空间内包名唯一）
```

实现即「生成包 v1 → core.Publish」，事务边界：包登记成功 → Publish 失败时包保留（孤儿包无害，可在包列表删除；返回 502 带 publish 错误详情）。

### 2.3 包生成器（agentgen.go，决议 5 的落地）

**命名派生**（与服务发布同源）：

```
vendorKey = "agentmanager"（固定平台命名空间，同 release-agent 先例）
agentKey  = slugify(name)：小写、非 [a-z0-9-] 转 -、压缩连续 -、去首尾 -；空结果 → 400 invalid_name
slug      = "agentmanager/" + agentKey
k8s_name  = 既有发布链路从 name 生成的同一函数（复用 publish.go 现有 normalize，不另写）
```

**AGENTS.md 生成**（YAML frontmatter + 正文）：

```yaml
---
name: "客服工单助手-压测版"
vendorKey: "agentmanager"
agentKey: "kefu-gongdan-zhushou-yace-ban"
version: "1.0.0"
slug: "agentmanager/kefu-gongdan-zhushou-yace-ban"
description: "承接客服二级工单"
author: "@console"
license: "MIT"

model:
  provider: openai
  modelId: deepseek-v3          # 默认连接（isDefault）的 modelId，仅展示语义；实际经 env 注入 + 就绪后下发（见 §2.4 模型链路）

skills:                          # 仅 source=package 的勾选进包
  - name: ticket-escalation
    source: local
    version: "1.0.0"

subAgents:                       # 远程形态写 endpoint；无 endpoint = 本地子代理（workspace subagents/*.md）
  - agent: ticket-classifier
    role: "分类"
    delegations: ["工单分类与优先级判定"]
    endpoint: "http://trip-booking-svc:8100"

mcpServers:
  - vendor: agentmanager
    server: ticket-system
    version: "1.0.0"
    configDir: "mcp-configs/ticket-system"
---

# Agent Purpose
（系统提示词原文，作为正文；不做骨架改写——所见即所得，包编辑器里再演进）
```

**目录组装**：

- `skills/<name>/**`：source=package 的勾选，从被引用公共包**复制**技能目录进生成包（copy-on-write 语义）；scope=l4 的勾选**不进包**（运行时注入，frontmatter 不写）；
- `mcp-configs/<server>/config.yaml`：由 M6 连接器行生成——`server/vendor(=agentmanager)/version/connection{type,url,timeout}/permissions.tools`（格式对齐 `release-agent/mcp-configs/platform/config.yaml`）；连接器 authType=bearer 时 token **不写入包**，走发布 env：`MCP_TOKEN_{SERVER}` 进 Secret（SecretKeys 追加）；
- 其余目录不生成（SOUL.md、脚本由用户后续在包编辑器补）。

**zip 登记**：内存 zip → `RegisterGenerated`（落 PVC `packages/{id}` + `oaf_packages` 行：workspace_id=当前空间、source_package_id=nil、is_template=false）。

### 2.4 PublishRequest 扩展与 spec 档

```go
type PublishRequest struct {
    ...现有字段不动...
    WorkspaceID    uint   `json:"workspaceId"`     // M1 已加；console 链路强制 = ctx 空间
    CreatedBy      uint   `json:"createdBy"`
    Spec           string `json:"spec"`            // "" | small | medium | large
    PublicEndpoint *bool  `json:"publicEndpoint"`  // nil = true（存量默认）
}
```

- spec 映射（Deployment requests/limits 直译，决议 4）：small=1c2g / medium=2c4g / large=4c8g；
- **互斥校验**：`DEPLOYMENT_TEMPLATE` overlay 已配置 resources 时，Publish 带 spec ≠ 空 → 400 `spec_conflicts_with_template`（overlay 与显式档二选一，防双写漂移）；存量不带 spec 调用零影响。
- `PublicEndpoint=false`：host 模式跳过 Ingress 创建；router 模式**仅置空 endpoint 展示字段**（统一 router 域名路径为静态泛化，一期不做网络面强制——与决议 3「直连仍开放」同口径，页面上标注「仅集群内」）。

### 2.5 模型连接链路（与 M5 的契约）

创建/更新请求的 `modelConnections` 多选按以下顺序落地：

1. **校验**：至少一条、全部 `status=verified`（M5 连接表）、`isDefault` 恰好一个（缺省取首个）；
2. **发布 env 注入**（默认连接）：`LLM_BASE_URL=baseUrl`、`LLM_MODEL=modelId`、`LLM_API_KEY=apiKey`——key 加入 `SecretKeys` 自动路由 `{name}-env-secret`（复用 platform-default-config-secret 敏感键机制；此后该 Agent 的「系统默认模型」即此连接）；
3. **下发登记**：全部勾选连接写 `model_deliveries(connection_id, service_id, status=pending)`（M5 表）；
4. **异步下发**：service 转 running 后（既有状态机事件处挂回调，或 usage 页手动触发）逐条 `deliver`——`POST/PATCH {svc}:8100/models` 幂等同步（详见 M5 §2.3），失败置 failed 可重发；
5. frontmatter `model` 段仅写默认连接 modelId（展示语义，key 永不落包）。

更新（republish/改 env）同理：勾选集变化时增量登记/清理 delivery 行；被移除的连接**不自动删 Agent 侧 model_config**（保守，见 M5 §2.4）。

### 2.6 次要创建方式

- `import`：直接复用现有 `POST /packages`（上传）+ `POST /services`（发布），console 层只做归属透传；公共包派生 = 既有 `POST /packages/:id/versions`（copy-on-write）后发布；
- `adopt`：透传 M1 的 `admin bind`（console 侧仅 admin 角色显示入口）。

### 2.7 编辑链路（对标 AgentCore 编辑页，设计 §8.2.1）

编辑页 = 创建页表单组件的 `mode=edit` 复用，保存语义 = **生成包新版本（copy-on-write）→ republish**：

```
GET  /api/v1/console/workspaces/:ws/agents/:id/edit-form
  → {data:{name(displayName), k8sName(锁定展示), description, image, modelConnections:[…当前勾选],
           prompt(当前版本 AGENTS.md 正文), skills, subAgents, mcpServers, spec, replicas, publicEndpoint}}
PUT  /api/v1/console/workspaces/:ws/agents/:id        （owner/admin；body 同创建表单，无 mode）
  → 202 {data:{service, package}}                     // package.version = vN+1
  → 400 invalid_form / 409 duplicate_name（空间内包名冲突）/ 403 member
```

实现顺序（全部复用既有件，零新语义）：

1. `GET edit-form`：从当前运行版本包读 AGENTS.md 正文（`Packages.ReadFile`）+ services 行 + M5/M6/M7 关联数据组装预填体；
2. `PUT`：`agentgen` 以**源包 = 当前版本包**生成新包（frontmatter `version` 递增，`source_package_id` = 当前包 id——即既有 `CreateVersion` copy-on-write 语义；skills/mcp-configs 变更 diff 后重新复制/注入）；
3. `core.Republish(serviceId, {packageId: 新包id})`（既有 republish，含 Endpoint 重算与滚动重启）；
4. 模型连接变更：默认连接变化 → `UpdateEnv`（LLM_* 全量覆盖语义）；勾选集增量 → `model_deliveries` 登记 + running 后自动下发（M5 §2.3）；
5. 热生效/重启语义（页面提示条口径）：提示词/Skill/SubAgent/MCP 随新包 reload 热生效；镜像/规格/副本滚动重启生效（对标 AgentCore 热更新说明）。

名称锁定：请求体中的 name 仅允许等于当前 displayName（改显示名走此字段，k8s_name 不可变；若传不同 name → 400 `name_immutable`，前端直接 disabled）。

## 3. 前端改动

### 3.1 页面（`src/app/(console)/console/agents/create/page.tsx` 新建）

单页分区表单，结构对照 demo `c/create`：

1. 头部右侧「取消 / 立即创建」，表单底部重复一组提交按钮；
2. 「选择创建方式」三卡片（harness 默认选中；import 切上传区（复用发布向导上传组件）；adopt 切存量表格（admin））；
3. harness 面板五个编号区块（antd 风格 section 分隔线 + 编号标题）：
   - **1 基本信息**：名称（失焦即预览派生 slug，调 `POST /console/.../agents/preview-name` 轻端点（本模块新增，返回 {agentKey,slug,k8sName} 或 conflict））、镜像下拉（`GET /images`）、模型连接多选勾选表（`api.console.modelConnections.list`，首个勾选自动标「默认（注入 LLM_* env）」、未验证连接禁勾，M5 前空态提示「先到模型连接页创建」）、描述；
   - **2 文件配置**：系统提示词 textarea（100000 上限、字数统计）+ 「将生成的 OAF 包」文件树预览（AGENTS.md/skills//mcp-configs/ 按勾选动态渲染）；
   - **3 Agent 资产配置**：顶部已选计数条；Skill 勾选表（L4 标注「运行时注入，不写入包」）；SubAgent 表（行添加 + 从「集成 Agent」下拉选端点，M7 前隐藏下拉）；MCP 勾选表（`api.console.tools.list`，M6 前空态提示「先到工具管理创建连接器」）；凭证/知识库占位行；
   - **4 资源配置**：规格三卡片（0.5/1/2 CU 标注）+ 副本数下拉 + 最大实例数/Session 数/Session TTL 置灰（远期）；
   - **5 网络配置**：发布入口开关（联动 endpoint 预览文本 `http://{INGRESS_HOST}/agent/{short}/`）+ VPC 置灰；
4. 提交成功 → toast + 跳 `/console/agents/{id}`（M4 前临时跳列表）。

**编辑页**（`src/app/(console)/console/agents/[id]/edit/page.tsx`）：**复用同一表单组件** `<CreateForm mode="edit" initial={edit-form}>`——无创建方式卡片、名称 disabled、标题/按钮换「保存」、提交调 `PUT .../agents/:id`；顶部说明条说明保存语义（copy-on-write 新版本 + republish + 热/重启生效差异）。入口：详情头部「编辑」、版本 Tab 当前版本行、列表卡片（M4 页面承接）。

### 3.2 文件清单

| 文件 | 动作 |
|------|------|
| `src/app/(console)/console/agents/create/page.tsx` | 新建（主表单，可拆 `components/create/*`：ModeCards / FormSection / SkillsPicker / SubAgentEditor / McpPicker / SpecCards） |
| `src/app/(console)/console/agents/[id]/edit/page.tsx` | 新建（CreateForm mode=edit 复用，§3.1 编辑页） |
| `src/lib/api.ts` | 修改：`api.console.createAgent(form)`、`editForm(id)`、`updateAgent(id, form)`、`previewAgentName(name)` |
| `src/components/ComingSoon.tsx` | M2 已建则复用 |

## 4. 验收标准（= 设计 §12 P1 验收主链路）

- [ ] 表单完整填写 → 提交：包列表出现 v1.0.0 生成包（frontmatter 各段正确、mcp-configs/skills 按勾选落包、token 不在包内）；服务进入 deploying → running（既有状态机与事件时间线复用）。
- [ ] 模型链路：默认连接注入 Pod env（LLM_BASE_URL/LLM_MODEL 可见、LLM_API_KEY 仅在 Secret）；running 后勾选连接在 M5 usage 表 pending→synced；调试可切下发模型。
- [ ] slug/agentKey 派生符合决议 5；重名（k8s_name 或空间内包名）409 且表单可感知。
- [ ] spec 与 DEPLOYMENT_TEMPLATE 互斥：配置 overlay 的环境发布带 spec 报 400；不带 spec 走 overlay 不变。
- [ ] publicEndpoint=false：host 模式无 Ingress；router 模式 endpoint 展示「仅集群内」。
- [ ] import：上传 zip → 发布归属正确空间；公共包派生不影响原包（既有行为回归）。
- [ ] 编辑：edit-form 预填完整；改提示词保存 → 版本 Tab 出现 vN+1（copy-on-write，vN 仍在）→ republish 事件时间线新增 → 调试对话行为反映新提示词（热生效）；改规格 → Pod 滚动重启；名称 disabled 不可改；member 无编辑入口且 PUT 403。
- [ ] member 角色提交 403。

## 5. 测试要点

- `agentgen_test.go`：命名派生边界（中文/emoji/连续分隔符/空结果）；frontmatter 生成快照（golden 文件比对）；skills 复制（源包删除技能后生成包自包含）；l4 不进包；mcp token 不进包但 SecretKeys 追加；modelConnections 校验（空/未验证/多默认 400）与 env+SecretKeys+delivery pending 行生成。
- `publish_test.go` 扩展：spec 映射进 requests/limits；互斥校验两分支；PublicEndpoint=false 的 host/router 双模式行为（k8sfake 断言 Ingress 不建/建）。
- `console_test.go`：POST /agents 权限矩阵（owner/admin/member）+ 事务边界（Publish 失败包保留）；PUT 编辑链路（预填体组装、copy-on-write 版本递增、name_immutable、member 403）。
- e2e（进核心 job 或新增）：创建 → 轮询 running → agent-card 注册成功 → debug 对话一句话 → **编辑改提示词保存 → v2 出现且 republish → 再对话断言新行为** → 下线。
