# OAF 跨集群导出发布 · 文件级实施契约

上游设计：[../design/cross-cluster-export-design.md](../design/cross-cluster-export-design.md)（下文引用为「设计 §N」）
日期：2026-10-29　状态：契约定稿（未实施）

本文档把设计拆解为 5 个可独立开工/验收的施工单元，给出**到文件级**的改动清单与签名契约。所有签名/路径均已对照当前代码核实（引用 `file:line`）；与设计的偏差集中记录于 §9。

**施工顺序**：U1（后端基础）→ U3（镜像构建）→ U2（导出链路）串行；U4（chart oaf-agent）、U5（前端两页）与后端并行、随时可开工。
**router.go 冲突分段**：U1 不改 router.go（其路由经 console.go 尾部追加装配），router.go 为 U2 独占——见 §2.4。

---

## 1. 数据模型：三个新表实体（U1）

### 1.1 实体定义（`backend/internal/store/model.go` 文件末尾追加，带分节注释，对齐 model.go:112 的既有分节风格）

```go
// ---- 跨集群导出（2026-10，docs/design/cross-cluster-export-design.md §8）----
// 类型名带 Entity 后缀严格对齐设计 §8 命名（与 ServiceEntity 同款；console 拆分新表
// User/Workspace 等省后缀属另一惯例，本组按设计文本走）。

// ExportOrderEntity 导出单：一次跨集群交付的载体（表名 export_orders）。
type ExportOrderEntity struct {
	ID              uint   `gorm:"primaryKey" json:"id"`
	ServiceID       uint   `gorm:"index" json:"serviceId"`
	WorkspaceID     uint   `gorm:"index;index:idx_export_ws_status" json:"workspaceId"`
	SourcePackageID uint   `json:"sourcePackageId"` // 导出时的源包
	ExportPackageID uint   `json:"exportPackageId"` // v1 恒 0（快照走 marks，不派生 oaf_packages，见 §9-D6）
	ChartVersion    string `gorm:"size:32" json:"chartVersion"`
	BaseImage       string `gorm:"size:256" json:"baseImage"`
	OafImageRef     string `gorm:"size:256" json:"oafImageRef"`
	OafImageDigest  string `gorm:"size:128" json:"oafImageDigest"` // sha256:...
	// ValuesYAML 渲染产物（含明文敏感值）：json:"-" 绝不随详情 JSON 直出，
	// 仅经下载端点交付（设计 §12.2）——先例：ServiceEntity.EnvSecretJSON（model.go:51）
	ValuesYAML  string `gorm:"type:mediumtext" json:"-"`
	ManifestJSON string `gorm:"type:json" json:"manifestJson"`
	// RequestJSON 导出请求快照（marker/baseImage/env/secretKeys/replicas）：
	// 异步构建完成后据此重渲染 values/manifest（回填 digest），保证可复现
	RequestJSON  string `gorm:"type:json" json:"-"`
	Status       string `gorm:"size:24;index:idx_export_ws_status" json:"status"`
	ItsmTicketID string `gorm:"size:128" json:"itsmTicketId"`
	Error        string `gorm:"size:512" json:"error"`
	CreatedBy    string `gorm:"size:64" json:"createdBy"` // username（JWT claims，jwt.go:44 注入）
	CreatedAt    time.Time `json:"createdAt"`
	UpdatedAt    time.Time `json:"updatedAt"`
}

func (ExportOrderEntity) TableName() string { return "export_orders" }

// ExportPackageMarkEntity 发布标记：导出级包编辑的不可变快照（表名 export_package_marks）。
type ExportPackageMarkEntity struct {
	ID             uint   `gorm:"primaryKey" json:"id"`
	ServiceID      uint   `gorm:"uniqueIndex:uk_export_mark_svc;index" json:"serviceId"`
	WorkspaceID    uint   `gorm:"index" json:"workspaceId"`
	ExportOrderID  uint   `gorm:"index" json:"exportOrderId"` // 0=尚未挂到导出单（草稿标记）
	Marker         string `gorm:"size:64;uniqueIndex:uk_export_mark_svc" json:"marker"` // {包版本}-e{序号}
	PackageVersion string `gorm:"size:32" json:"packageVersion"`
	// FilesJSON 完整文件快照：[{path,encoding(utf8|base64),content}]——自包含（构建不回读源包，
	// 源包可能已被换版）；文本/二进制以 store.IsTextContent 判定（fs.go:287），二进制 base64
	FilesJSON  string `gorm:"type:mediumtext" json:"-"`
	FileCount  int    `json:"fileCount"`
	Checksum   string `gorm:"size:64" json:"checksum"` // 快照内容 sha256（canonical JSON）
	Status     string `gorm:"size:16" json:"status"`   // draft | frozen
	CreatedBy  string `gorm:"size:64" json:"createdBy"`
	CreatedAt  time.Time `json:"createdAt"`
}

func (ExportPackageMarkEntity) TableName() string { return "export_package_marks" }

// WorkspaceRuntimeDefaultEntity 工作空间运行时默认配置（运维侧维护，导出页预填与 values L1 层来源）。
type WorkspaceRuntimeDefaultEntity struct {
	ID          uint   `gorm:"primaryKey" json:"id"`
	WorkspaceID uint   `gorm:"uniqueIndex:uk_ws_runtime_default;index" json:"workspaceId"`
	EnvKey      string `gorm:"size:128;uniqueIndex:uk_ws_runtime_default" json:"envKey"`
	Value       string `gorm:"type:text" json:"-"`
	Sensitive   bool   `json:"sensitive"`
	Category    string `gorm:"size:24;index" json:"category"` // mysql|redis|otel|llm|sandbox|protocol|registry|routing|runtime
	UpdatedBy   string `gorm:"size:64" json:"updatedBy"`
	CreatedAt   time.Time `json:"createdAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
}

func (WorkspaceRuntimeDefaultEntity) TableName() string { return "workspace_runtime_defaults" }
```

导出单状态常量（`model.go` 追加在既有状态常量块 model.go:30-38 之后）：

```go
// 导出单状态机（设计 §8：draft → building → built → approving → approved → deploying → deployed；
// 任意非终态可入 failed / cancelled）。
const (
	ExportStatusDraft     = "draft"     // v1 预留（创建即入 building，见 §4 注）
	ExportStatusBuilding  = "building"
	ExportStatusBuilt     = "built"
	ExportStatusApproving = "approving"
	ExportStatusApproved  = "approved"
	ExportStatusDeploying = "deploying"
	ExportStatusDeployed  = "deployed"
	ExportStatusFailed    = "failed"
	ExportStatusCancelled = "cancelled"
)

// 发布标记状态。
const (
	ExportMarkDraft  = "draft"
	ExportMarkFrozen = "frozen"
)
```

### 1.2 注册位置（`backend/internal/store/db.go`）

在 `db.AutoMigrate(...)` 列表（db.go:24-28）末尾追加三项：`&ExportOrderEntity{}, &ExportPackageMarkEntity{}, &WorkspaceRuntimeDefaultEntity{}`。三张表全为新增空表，**无需** `dedupPackageVersions`（db.go:21）式的存量预处理；唯一索引直接由 AutoMigrate 建立。

### 1.3 索引与唯一约束汇总

| 表 | 约束 | 说明 |
|----|------|------|
| export_orders | PRIMARY id；INDEX service_id、workspace_id；INDEX (workspace_id, status) `idx_export_ws_status` | 设计 §8「索引：(workspace_id, status)」→ 跨列复合（GORM 同名 tag，§1.1 WorkspaceID 与 Status 共用） |
| export_package_marks | PRIMARY id；**UNIQUE (service_id, marker)** `uk_export_mark_svc`；INDEX workspace_id / export_order_id | 设计 §8 写 marker 单列 uniqueIndex，跨服务会撞（两服务同包版本同序号），改复合唯一——偏差记录 §9-D2 |
| workspace_runtime_defaults | **UNIQUE (workspace_id, env_key)** `uk_ws_runtime_default`；INDEX category | 与设计 §8 一致 |

### 1.4 运行时默认键空间与分类（U1 `workspaceruntime.go` 的事实源）

- 模板键：以 `platformconfig.KnownKeys()`（template.go:108-116，全模板共 25 键：llm 10 + mysql 3 + redis 3 + sandbox 6 + protocol 3）为基底，但**排除 `CHECKPOINT_JDBC_URL`**（template.go:46，KnownKeys 实测含它）→ **有效模板键 24 键**。排除理由：它是 L3 派生键（§5.4 由 `CHECKPOINT_JDBC_BASE` 派生同名键），若允许 L1 合法持有，会与 L3 撞键且最坏把源集群 JDBC URL（可能内嵌凭据）明文导出进生产 values；与设计 §10 一致（mysql 分类只定义 `CHECKPOINT_JDBC_BASE`，未列 `CHECKPOINT_JDBC_URL`）。Sensitive 取 `platformconfig.Lookup(k).Sensitive`（template.go:96-105）。
- 导出专用键（模板之外，v1 固定 8 键，全部非敏感）：

| EnvKey | Category | 用途（设计 §10） |
|--------|----------|------------------|
| `CHECKPOINT_JDBC_BASE` | mysql | checkpoint 基库 URL，L3 按 `{基库}_{short}` 派生实际库 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | otel | OTLP 上报地址 |
| `REGISTRY_ADDRESS` | registry | OAF 镜像直推目标仓库地址（如 `172.20.0.1:5001`） |
| `REGISTRY_NAMESPACE` | registry | 仓库内命名空间段（可空） |
| `IMAGE_PULL_SECRETS` | registry | 逗号分隔 imagePullSecrets 名 |
| `ROUTING_MODE` | routing | `host` \| `router`（缺省 host） |
| `ROUTING_HOST_SUFFIX` | routing | host 模式域名后缀（以 `.` 开头，校验同 config.go:150-172 口径） |
| `BASE_IMAGE_WHITELIST` | runtime | 基础镜像白名单，格式同 `AVAILABLE_IMAGES`（`ref|label` 逗号分隔，config.go:174-200 同解析） |

- **L1/L3 撞键在键空间层面结构性消除（不靠运行时裁决）**：L1 键空间 = 24 模板键 + 8 导出键，已排除全部 L3 键——`CHECKPOINT_JDBC_URL`、`AGENT_REDIS_PREFIX`（L3 派生键）与 `AGENT_CONFIG_DIR`/`AGENT_WORKSPACE_DIR`/`SERVER_HOST`/`SERVER_PORT`/`HOST_NAME`（平台保留键，objects.go:18-22，本就不在 template）——PUT runtime-defaults 传入任一即 400（错误信息点名键名）；`SANDBOX_ENABLED` 沿用 template 排除理由（template.go:24-26 注释）。因此**撞键裁决只存在于 L2 对 L1/L3**（§5.1），L1 自身不可能与 L3 撞键。

---

## 2. 文件所有权矩阵（5 个施工单元）

通用纪律：只改本单元名下文件；不整文件重写；新文件带包级中文注释说明设计出处；单测与实现同 PR。

### 2.1 U1 后端基础（实体 + 运行时默认配置垂直）

| 文件 | 动作 | 内容 |
|------|------|------|
| `backend/internal/store/model.go` | 修改（末尾追加） | §1.1 三实体 + 状态常量 |
| `backend/internal/store/db.go` | 修改（1 行内追加） | AutoMigrate 注册（§1.2） |
| `backend/internal/service/workspaceruntime.go` | 新建 | GET/PUT runtime-defaults：键白名单校验（24 模板键 = KnownKeys − CHECKPOINT_JDBC_URL + §1.4 导出 8 键；L3 派生/平台保留键传入 400，§1.4）、三态语义（同 platformconfig.go:120-165：出现非空=设置、空串=删除、缺失=不变；未知键 400）、敏感掩码视图、UpdatedBy 记 username |
| `backend/internal/service/workspaceruntime_test.go` | 新建 | 白名单/三态/掩码/分分类用例（sqlite 内存库，先例 testkit_test.go） |
| `backend/internal/handler/workspaceconfig.go` | 新建 | `registerWorkspaceConfig(cg *gin.RouterGroup, core *service.Core)`：GET/PUT 两端点 |
| `backend/internal/handler/workspaceconfig_test.go` | 新建 | 路由 + 权限用例（member GET 200 / PUT 403，先例 router_test.go） |
| `backend/internal/handler/console.go` | 修改（1 行） | `registerConsole` 函数体末尾追加 `registerWorkspaceConfig(cg)` |

U1 权限口径：GET = workspace 成员（resolveWorkspace 已在 cg 链上，console.go:121）；PUT = owner/admin（handler 内 `requireWsRole`，console.go:77-88 → service.RequireWsRole，identity.go:193-197）。

### 2.2 U2 导出链路（后于 U1、U3 合入）

| 文件 | 动作 | 内容 |
|------|------|------|
| `backend/internal/service/exportpackage.go` | 新建 | 包树/单文件草稿/打标记：`ExportPackagePreview`、`SaveExportPackageFile`（PUT 草稿，upsert 每服务至多一条 draft）、`FreezeExportMark`（分配 marker `{包版本}-e{N}` + 冻结快照）、`ListExportMarks`；快照压缩边界见 §5.6 |
| `backend/internal/service/exportorder.go` | 新建 | `CreateExportOrder`（校验 marker/baseImage/env → 建 status=building 行 → 同步渲染 values/manifest 存库 → goroutine 调 `c.Images.Build` → 回填 ref/digest 并重渲染 → built；失败 failed+Error）、`GetExportOrder`、`AdvanceExportOrder`（人工推进，v1 允许 built→approving→approved→deploying→deployed 与任意非终态→failed/cancelled）、下载取数 |
| `backend/internal/service/exportvalues.go` | 新建 | 四层合并与 values/manifest 渲染（§5） |
| `backend/internal/service/export*_test.go` | 新建 | 标记冻结/序号/派生键/四层合并/敏感路由/渲染快照测试（`Images` 注入 fake builder） |
| `backend/internal/service/publish.go` | 修改（2 处小改） | Core 结构体（publish.go:21-35）追加字段 `Images imagebuilder.Builder`（nil = 未配置镜像构建，创建导出单 400）；**不改 NewCore 签名** |
| `backend/cmd/server/main.go` | 修改（1 行） | main.go:87 附近追加 `core.Images = imagebuilder.NewRemoteBuilder(cfg.ExportBaseImage, cfg.ExportRegistryUsername, cfg.ExportRegistryPassword)` |
| `backend/internal/handler/export.go` | 新建 | `registerWorkspaceExport(cg)`（workspace 作用域导出端点，表见 §4）+ `registerExportOrders(eg)`（导出单端点）+ values/manifest 下载（`c.DataFromReader` + Content-Disposition，先例 router.go:128-130） |
| `backend/internal/handler/export_test.go` | 新建 | 路由/权限/下载头/掩码（values_yaml 不出现在任何 JSON 响应）用例 |
| `backend/internal/handler/console.go` | 修改（1 行） | `registerConsole` 末尾追加 `registerWorkspaceExport(cg)`（在 U1 的 registerWorkspaceConfig 之后，串行无重叠） |
| `backend/internal/handler/router.go` | 修改（2 行） | `registerConsole` 调用（router.go:37）之后追加：`eg := g.Group("/console/exports", JWTAuth(jwtSecret))` + `registerExportOrders(eg, core)` |

### 2.3 U3 镜像构建（先于 U2 合入）

| 文件 | 动作 | 内容 |
|------|------|------|
| `backend/internal/imagebuilder/imagebuilder.go` | 新建 | Builder 接口 + RemoteBuilder 实现 + `NewRemoteBuilder`（§3） |
| `backend/internal/imagebuilder/tarlayer.go` | 新建 | 包目录 → 内存 tar 层（挂载点前缀 `/oaf/`，条目 mode ≥0644，对齐上传链路 fs 最低权限约定 backend/AGENTS.md「文件最低 0644」） |
| `backend/internal/imagebuilder/imagebuilder_test.go` | 新建 | tar 层内容/labels/输入校验用例（不依赖网络的单元测试；remote 推送路径靠 E2E §8 验证） |
| `backend/go.mod` / `backend/go.sum` | 修改 | `go get github.com/google/go-containerregistry@latest`（**全工作流唯一新增 Go 依赖**，通用纪律授权）；版本以落定为准 |
| `backend/config/config.go` | 修改 | Config 结构体（config.go:21-67）追加 `ExportBaseImage`（env `EXPORT_BASE_IMAGE`，缺省 `busybox:1.36`）、`ExportRegistryUsername` / `ExportRegistryPassword`（env `EXPORT_REGISTRY_USERNAME/PASSWORD`，可空=匿名推送）；Load()（config.go:69-128）三行 envStr 读取 |

### 2.4 router.go 分段规则（先后串行）

- **U1 不改 router.go**：其路由在 `registerConsole` 函数体末尾经一行 `registerWorkspaceConfig(cg)` 装配（cg 组的 JWTAuth 由 router.go:36 建立、resolveWorkspace 由 console.go:121 注入，全部 console 既有端点同模式）。
- **U2 独占 router.go**：仅在 router.go:37 `registerConsole(...)` 调用之后追加 §2.2 所列两行。`/console/exports` 子组**不挂 resolveWorkspace**（无 `:ws` 段；挂了会因 `c.Param("ws")` 为空恒 404——resolveWorkspace 语义见 console.go:33-64），导出单归属校验在 service 层按 order.workspace_id + 成员关系裁决。
- **串行先后**：U1 先合入 → U2 才可开工 console.go/router.go 段。两单元对 `registerConsole` 函数末尾的追加点相同，按先后顺序天然无重叠；若评审要求 U1 也走 router.go 装配，则同样 U1 先、U2 后，分段规则不变。
- 依据：gin 组中间件按「Use 时机 vs 路由注册时机」生效，router.go:25-27 注释即该机制的既有先例（auth/console/admin 子组先于 legacy `g.Use(Auth)` 创建）。

### 2.5 U4 chart oaf-agent / U5 前端两页

见 §6、§7（两单元与后端无文件交集；U5 只读后端 §4 契约开发）。

---

## 3. ImageBuilder 接口与注入（U3 定义、U2 消费）

### 3.1 接口与类型（`backend/internal/imagebuilder/imagebuilder.go`）

```go
// Package imagebuilder：导出 OAF 配置镜像构建器（设计 §5）。
// busybox 基底 + 包目录 tar 层（挂载点 /oaf），go-containerregistry remote.Write 直推
// 指定镜像仓库——纯数据镜像（KB~MB 级）、无 docker 依赖、无特权、同 tag 内容寻址幂等重推。

// BuildInput 构建输入。
type BuildInput struct {
	PackageDir string            // OAF 包目录绝对路径（{DATA_ROOT}/packages/{id}，backend 本地文件系统）
	TargetRef  string            // 目标镜像完整引用，如 172.20.0.1:5001/oaf-e2e-export-agent:1.2.0-e1
	Labels     map[string]string // 审计标签：oaf.platform/slug、/version、/checksum、/export-id（设计 §4/§5）
}

// BuildOutput 构建输出。
type BuildOutput struct {
	Digest string // sha256:...（内容寻址；remote.Write 成功后取 img.Digest()，与仓库内 digest 一致）
}

// Builder 导出配置镜像构建器（服务层以接口注入，便于单测替换 fake）。
type Builder interface {
	Build(ctx context.Context, in BuildInput) (BuildOutput, error)
}

// NewRemoteBuilder 直推实现。baseImage 为基底镜像引用（config EXPORT_BASE_IMAGE，缺省
// busybox:1.36）；username/password 为推送凭证（可空=匿名；生产经平台 Secret 注入 backend
// 进程 env，设计 §12.3，不进任何导出产物）。
func NewRemoteBuilder(baseImage, username, password string) *RemoteBuilder
```

### 3.2 实现要点（RemoteBuilder.Build）

1. 输入校验：PackageDir 存在且含根级 `AGENTS.md`（`os.Stat`，缺失即错——构建输入必须是合法 OAF 包）；TargetRef 非空；Labels 键值非空校验。
2. tar 层：`tarlayer.go` 遍历 PackageDir（跳过符号链接，防 zip-slip 同类逃逸）打 tar，所有条目前缀 `/oaf/`；经 `tarball.LayerFromReader` 生成 `v1.Layer`。
3. 基底：`remote.Image(name.ParseReference(baseImage), remote.WithAuth(...), remote.WithContext(ctx))` 拉取基底（busybox KB 级；**构建期需要 backend 运行环境可达基底镜像仓库**，离线部署必须把 `EXPORT_BASE_IMAGE` 指到内网仓库——记录 §9-D8）。
4. 组装：`mutate.Config(img, v1.Config{Labels: in.Labels})` 后 `mutate.AppendLayers(img, layer)`。
5. 推送：`remote.Write(targetRef, img, remote.WithAuth(...), remote.WithContext(ctx))`——幂等（同内容同 digest 重推 no-op）。
6. 返回 `img.Digest()` 字符串。

### 3.3 服务层依赖方式（U2）

- `Core.Images imagebuilder.Builder`（publish.go Core 结构体追加，U2）——模式对齐既有注入点：`ConfigView.ImageAllowed func(string) bool`（publish.go:57）与 `core.JWTSecret`/`core.OnServiceRunning`（main.go:86-87 赋值）。
- nil 语义：`Images == nil` 时 `CreateExportOrder` 返回 400 `image builder not configured`（不 panic、不降级假成功）。
- 单测：`exportorder_test.go` 内定义 `type fakeBuilder struct{ digest string; err error }` 实现 Build，构造 `Core{Images: fake, ...}`（sqlite 内存库 + tmp FS，先例 testkit_test.go）。

---

## 4. API 路由表（设计 §9 → 实际挂载）

所有端点位于 console JWT 之下（设计 §9 权限口径首句）。**路径前缀与设计 §9 表的差异**（`/api/v1/...` → `/api/v1/console/...`）见 §9-D3：复用 `cg` 组的 JWTAuth + resolveWorkspace，不重建第二条鉴权链。

Handler 文件：`backend/internal/handler/export.go`（U2）；workspaceconfig 端点在 `backend/internal/handler/workspaceconfig.go`（U1）。

### 4.1 workspace 作用域端点（`registerWorkspaceConfig(cg)` / `registerWorkspaceExport(cg)`，均挂 cg：JWTAuth + resolveWorkspace）

| 设计 §9 端点 | 实际路由 | Handler 函数 | Service 方法 | 权限 |
|---|---|---|---|---|
| `GET /api/v1/workspaces/:id/config/runtime-defaults` | `GET /api/v1/console/workspaces/:ws/config/runtime-defaults` | workspaceconfig.go | `Core.GetRuntimeDefaults(wsID)` | 成员可读（敏感掩码） |
| 同上 PUT | `PUT .../config/runtime-defaults` | workspaceconfig.go | `Core.UpdateRuntimeDefaults(wsID, username, values)` | **owner/admin**（requireWsRole） |
| `GET /api/v1/services/:id/export/package` | `GET /api/v1/console/workspaces/:ws/agents/:id/export/package` | export.go | `Core.ExportPackagePreview(wsID, svcID)` | 成员（服务须属本空间，校验模式同 console.go:676 起 agents/:id 端点） |
| `PUT /api/v1/services/:id/export/package/file` | `PUT .../export/package/file` | export.go | `Core.SaveExportPackageFile(...)` | 成员可写（导出编辑不限 owner/admin；写路径幂等 upsert draft） |
| `POST /api/v1/services/:id/export/package/mark` | `POST .../export/package/mark` | export.go | `Core.FreezeExportMark(...)` | 成员可写 |
| `GET /api/v1/services/:id/export/package/marks` | `GET .../export/package/marks` | export.go | `Core.ListExportMarks(svcID)` | 成员 |
| `GET /api/v1/services/:id/export/preview` | `GET .../export/preview` | export.go | `Core.ExportPreview(...)`：源包结构与版本链、现 env（敏感掩码）、空间默认值（掩码）、镜像白名单、派生键预览 | 成员 |
| `POST /api/v1/services/:id/export` | `POST .../export` | export.go | `Core.CreateExportOrder(...)` | 成员 |

`:ws` 取值沿用 console 约定（`me` / 数字 id / slug，identity.go:150-181）。无 body 的 POST（打标记）沿用 republish 的可选 body 语义（router.go:247-253：ContentLength > 0 才 ShouldBindJSON）。

### 4.2 导出单端点（`registerExportOrders(eg)`，eg = `/console/exports` 仅 JWTAuth，无 :ws 段）

| 设计 §9 端点 | 实际路由 | Service 方法 | 权限（service 层裁决） |
|---|---|---|---|
| `GET /api/v1/exports/:id` | `GET /api/v1/console/exports/:id` | `Core.GetExportOrder(uid, id)`：调用者须为 order.workspace_id 成员或平台 admin（复用 `Core.ResolveWorkspace` / `Core.GetUser`，identity.go） | 跟随 workspace（设计 §9） |
| `GET /api/v1/exports/:id/values.yaml` | `GET .../exports/:id/values.yaml` | 同上归属校验后返回 ValuesYAML；`Content-Disposition: attachment`；下载写审计日志（§5.7） | 同上 |
| `GET /api/v1/exports/:id/manifest.json` | `GET .../exports/:id/manifest.json` | 同上 | 同上 |
| `POST /api/v1/exports/:id/status` | `POST .../exports/:id/status` | `Core.AdvanceExportOrder(uid, id, {status, itsmTicketID})`：合法迁移 built→approving→approved→deploying→deployed + 非终态→failed/cancelled；记录操作人与 ITSM 单号（写 ItsmTicketID/ManifestJSON.itsm） | 同上 + **owner/admin**（推进属运维动作） |

设计 §9 的 `config/mcp-connectors`、`config/templates/:group`、`config/registry` 三组端点 v1 **以 workspaceconfig.go 分组视图骨架落地**（templates/registry 完整读写、mcp-connectors GET + PUT 501；导出键空间事实源仍为 runtime-defaults 单端点，前端两页不消费该组）——实现与 §9-D4 原裁剪的偏差见该条评审修订（2026-10-30）。

### 4.3 请求/响应要点

- `POST .../export` 请求体：`{ "marker": "1.2.0-e1", "baseImage": "…", "chartVersion": "0.1.0", "env": {"MCP_FLIGHT_TOKEN": "…"}, "secretKeys": ["MCP_FLIGHT_TOKEN"], "replicas": 2 }`；`marker` 必填且须为该服务 frozen 标记；`baseImage` 必须命中 L1 `BASE_IMAGE_WHITELIST`（缺省取白名单首项，模式同 config.go:122-124）；`env` 走 `service.ValidateEnv`（env.go:17-36）+ 导出保留键校验（§5.4）；`chartVersion` 缺省常量 `defaultExportChartVersion = "0.1.0"`。
- 响应统一 `OK(c, …)` 包装（respond.go）；错误经 `mapError`（401/400/403/404/409 语义沿用）。
- `GET .../exports/:id` 详情对 `ValuesYAML`/`RequestJSON` 永不回显（模型 json:"-"，§1.1）。

---

## 5. values 渲染规则（U2 `exportvalues.go`）

### 5.1 四层合并优先级（设计 §7.1，从低到高）

| 层 | 来源（v1 落点） | 覆盖关系 |
|----|----------------|----------|
| L0 chart 默认 | `charts/oaf-agent/values.yaml`（部署时 helm 生效，平台不渲染产出） | 被上层覆盖 |
| L1 工作空间运行时默认 | `workspace_runtime_defaults`（wsID 全量行 → map） | L2 不得覆盖 L1 已配置键（撞键 400），仅运维在配置管理改默认（设计 §2.1 锁定语义） |
| L2 用户导出覆盖 | POST export 请求体 `env`/`secretKeys`/`replicas` | **仅新增、不覆盖**：L2 键命中 L1 已配置键或 L3 保留键 → CreateExportOrder 校验期 400（不静默覆盖也不静默丢弃）；包内 MCP 改写已进 mark 快照、不参与 values（设计 §3 要点） |
| L3 平台生成 | 见 §5.4，**不可覆盖**（撞键 400） | 最高 |

### 5.2 敏感路由

- 敏感键集合 = `platformconfig.SensitiveKeys()`（template.go:119-129：`LLM_API_KEY`/`CHECKPOINT_PASSWORD`/`AGENT_REDIS_URL`/`OPENSANDBOX_API_KEY`/`AGENT_PROTOCOL_AUTH_TOKEN`/`AGENT_REMOTE_HEADERS_JSON`/`AGENT_A2A_JOB_TOKEN`）∪ 请求 `secretKeys`——与发布链路 `splitUserEnv`（envroute.go:14-20）同口径，直接复用 `platformconfig.Split`（template.go:134-145）。
- 敏感份 → values `secretEnv` 段（chart secret.yaml → `{fullname}-env-secret` Secret envFrom）；非敏感份 → `env` 段（configmap.yaml → `{fullname}-env` CM envFrom）。**v1 明文写入 values**（决议 ①）。
- 空值剔除：`dropEmptyEnv` 同语义（publish.go:772-778），空值键不出现在任一段（「LLM 空值不下发」自动满足）。

### 5.3 模板键清单（与 template.go 同源，代码不复制清单）

- 渲染循环直接遍历 `platformconfig.Template()` 分组，值取 **L1∪L2 合并结果**（并集语义，对齐 §5.1「L2 仅新增」：L1 已配置的键取 L1 值；L1 未配置而 L2 新增的键取 L2 值——如 e2e §8.5 的 `MCP_E2E_TOKEN` 即此类键，必须进入 secretEnv，交集实现会把它静默丢弃致 §8.7 校验 7 失败）。L2 撞 L1 已配置键在 CreateExportOrder 校验期 400（§5.1），渲染层只面对已裁决通过的并集。导出页表单 schema 同源（设计 §10「模板驱动」）。
- LLM 组 10 键：`LLM_API_KEY/BASE_URL/MODEL_ID/PROVIDER/TEMPERATURE/MAX_TOKENS/ENABLE_THINKING/CONTEXT_LENGTH/REASONING_EFFORT/FREQUENCY_PENALTY`（template.go:31-40）。
- Sandbox 组 6 键：`OPENSANDBOX_SERVER_URL/OPENSANDBOX_API_KEY/SANDBOX_IMAGE/SANDBOX_TIMEOUT_MINUTES/SANDBOX_MEMORY_MB/SANDBOX_CPU_COUNT`（template.go:64-69）。
- `SANDBOX_ENABLED`、`AGENT_PROTOCOL_ENABLED/TASK_STORE/TASK_RETENTION_DAYS`、`AGENT_A2A_JOB_ENABLED` 等开关键不在渲染范围（template.go:24-26、77-89 注释的理由）。

### 5.4 L3 派生键与保留键（不可覆盖清单）

| 键 | 取值 | 实现依据 |
|----|------|----------|
| `AGENT_CONFIG_DIR` / `AGENT_WORKSPACE_DIR` / `SERVER_HOST` / `SERVER_PORT` | `/config` / `/workspace` / `0.0.0.0` / `8100` | 平台保留键（objects.go:18-19，AgentPort=8100 objects.go:27） |
| `CHECKPOINT_JDBC_URL` | L1 `CHECKPOINT_JDBC_BASE` 派生 `{基库}_{short}`（`-`→`_`，总长 ≤64）+ `createDatabaseIfNotExist=true` | **复用** `deriveCheckpointURL`/`checkpointDBNameFor`（publishisolation.go:50-101，传 short 名，不复制逻辑）；建库另有 chart hook Job 双保险（§6） |
| `AGENT_REDIS_PREFIX` | `{short}:` | 复用 `redisPrefixFor`（publishisolation.go:105-107） |
| `image.base` / `image.oaf` / `image.oafDigest` | 请求 baseImage / `{REGISTRY_ADDRESS}/{REGISTRY_NAMESPACE}/oaf-{short}:{marker}` / 构建回填 | 设计 §4/§5 tag 规则 |
| `podAnnotations` | `oaf.platform/export-id: EXP-…`、`oaf.platform/package-checksum: sha256:…`（mark checksum） | 设计 §7.3 |
| fullnameOverride | `{short}`（= `k8s.ShortName(svc.K8sName)`，objects.go:81-83） | 设计 §7.3 示例 `travel-agent` |

`short` = `k8s.ShortName(svc.K8sName)`（如 `oaf-e2e-export-agent` → `e2e-export-agent`）。**L2 撞 L3 保留键 → 400**（保留键全集 = 上表 6 类键 + `image.*`/`podAnnotations` 等 L3 专属顶层键，错误信息点名保留键）；平台既有保留键由 `ValidateEnv` 先行拦截（env.go:26-30）。L1 撞 L3 不可能（键空间结构性排除，§1.4）。

### 5.5 values.yaml 形状（对齐设计 §7.3 示例）

顶层键：`fullnameOverride`、`image.{base,oaf,oafDigest}`、`imagePullSecrets`（L1 csv 拆分）、`env`、`secretEnv`、`configInjection.{mode: initContainer, dir: /config}`、`replicas`（可选 L2，缺省走 L0）、`ingress.{mode,tls,hostSuffix}`（L1 ROUTING_*）、`mysql.createDatabase`（L1 有 `CHECKPOINT_JDBC_BASE` 即 true）、`podAnnotations`。YAML 序列化用既有直依 `gopkg.in/yaml.v3`（go.mod，**零新增依赖**），map 序列化键序为字典序（与示例顺序差异可接受，注释头部为拼接字符串：导出单号/服务/工作空间/源包版本链/chart 版本/基底镜像/明文敏感值警示行，照设计 §7.3 注释块）。

### 5.6 快照与 checksum

- mark 快照 `FilesJSON` = `[{path, encoding: "utf8"|"base64", content}]`，覆盖包全部文件（含二进制 base64）——自包含，构建不回读源包（源包可能已被换版/删除）；路径合法性复用 `store.CleanSubPath`（fs.go:310）与 `reservedRootDirs` 同口径（package_version.go:91-94）。
- `Checksum` = 对「path 排序后的 canonical JSON」求 sha256 hex；`FileCount` = 条目数。mediumtext 上限 16MB，超限 400（release-agent.oaf.zip 实测 6563B，余量充足）——风险记录 §9-D7。

### 5.7 manifest.json 字段（设计 §7.3 尾段）

```json
{
  "exportOrder": "EXP-20261029-0001",
  "service": {"id": 7, "displayName": "…", "k8sName": "oaf-…", "shortName": "…"},
  "workspace": {"id": 1, "slug": "default"},
  "package": {"sourcePackageId": 3, "slug": "agentmanager/release-agent", "sourceVersion": "1.2.0", "markChecksum": "sha256:…", "marker": "1.2.0-e1"},
  "bundle": {"chart": "oaf-agent-0.1.0", "baseImage": "…", "oafImageRef": "…", "oafImageDigest": "sha256:…", "values": "values.yaml（本 manifest 配套附件）"},
  "overrideSummary": {"envKeysAdded": 2, "secretKeys": 2, "mcpServersRewritten": 0, "runtimeDefaultsApplied": 12},
  "itsm": {"ticketId": "", "advancedBy": "", "advancedAt": null},
  "audit": {"createdBy": "admin", "createdAt": "…", "downloadedBy": [], "…": "下载追加（§5.8）"}
}
```

导出单号规则：`EXP-YYYYMMDD-%04d`（%04d = export_orders 自增 id，创建行后回填）。人工推进 status 时同步更新 manifest 的 `itsm` 段。

### 5.8 下载审计（v1）

values/manifest 下载成功 → `log.Printf("[export_download] order=%d user=%s file=values.yaml ts=%s", …)` 结构化访问日志 + manifest `audit.downloadedBy` 追加。独立下载审计表留 v2（§9-D5）。

---

## 6. charts/oaf-agent（U4，设计 §6）

### 6.1 文件清单（全部新建）

```
charts/oaf-agent/
├── Chart.yaml               # name: oaf-agent, version: 0.1.0, appVersion: "0.1.0", type: application（形态对齐 charts/oaf-platform/Chart.yaml）
├── values.yaml              # L0 默认（§6.2）
├── ci-values.yaml           # CI 渲染全量对象用的占位必填值（§6.4）
├── .helmignore              # 常规
├── templates/
│   ├── _helpers.tpl         # oaf-agent.fullname / .labels / .selectorLabels / .imageBase / .imageOaf / oaf-agent.validate（required 守卫，先例 _helpers.tpl:15-40 的 oaf.fullname/oaf.labels 与 :84 起的 oaf.validate）
│   ├── deployment.yaml      # initContainer inject-oaf（image.oaf，command sh -c "cp -a /oaf/. /config/"，emptyDir /config）+ 主容器 agent（image.base，envFrom 两源：secret 在前 CM 在后，对齐平台 envFrom 顺序 backend/AGENTS.md）+ /health 探针（AgentPort 8100）+ HOST_NAME 注入
│   ├── service.yaml         # {fullname}-svc:8100（命名规则与单集群一致：objects.go:27-28 前缀语义由 fullname 承担）
│   ├── ingress.yaml         # 双模式（§6.3）
│   ├── configmap.yaml       # {fullname}-env：values.env（非敏感）
│   ├── secret.yaml          # {fullname}-env-secret：values.secretEnv（stringData）
│   ├── mysql-init-job.yaml  # {{- if .Values.mysql.createDatabase }}：pre-install hook Job（命名 {fullname}-mysql-init；镜像用独立值 mysql.jobImage 缺省 mysql:8.0——agent-framework Java 基底不含 mysql CLI），CREATE DATABASE IF NOT EXISTS `{基库}_{short}`；helm.sh/hook: pre-install + hook-weight + hook-delete-policy
│   ├── serviceaccount.yaml  # {fullname}-sa（最小；业务 Pod 无 K8s API 依赖）
│   └── NOTES.txt            # 就绪自检：rollout status / kubectl wait job / agent-card curl 示例（风格对齐 oaf-platform NOTES.txt）
└── README.md                # values 契约与 4 件套部署步骤
```

### 6.2 必填 values（`_helpers.tpl` 的 `oaf-agent.validate` + required 守卫）

| 键 | 必填 | 说明 |
|----|------|------|
| `fullnameOverride` | ★ | 生产侧对象名基底（导出渲染恒写入） |
| `image.base` | ★ | agent-framework 基础镜像完整 ref |
| `image.oaf` | ★ | OAF 配置镜像完整 ref |
| `env` / `secretEnv` | 可空 map | 空时 CM/Secret 仍渲染（空对象），保 envFrom 引用不悬空 |
| `ingress.mode` | 缺省 host | `host` 需 `ingress.hostSuffix`（以 `.` 开头，validate 校验）；`router` 需 `ingress.pathPrefix` |
| `mysql.createDatabase` | 缺省 false | true 时启用 hook Job，需 `mysql.jobImage` |
| `imagePullSecrets` / `resources` / `replicas`（缺省 2）/ `podAnnotations` | 可选 | replicas ≥2 默认（设计 §6 无 sessionAffinity，service 不写 affinity 字段） |

自包含红线（设计 §6）：模板**不得出现** `platform-data`、`platform-router`、`platform_config`、平台 backend 地址；namespace 一律 `.Release.Namespace`（不硬编码，oaf-platform 先例 `_helpers.tpl:3-5` 的 oaf.namespace 语义）。

### 6.3 ingress 双模式

- `host`（默认，本工作流 E2E 验证面）：host=`{fullnameOverride}{ingress.hostSuffix}`、path `/`、pathType Prefix、仅 ssl-redirect=false 与 proxy-read/send-timeout=3600——与平台 host 模式不变量一致（backend/AGENTS.md「host 模式不变量」：无 rewrite/x-forwarded-prefix 注解）。
- `router`：path=`/agent/{fullnameOverride}` + ingress-nginx `rewrite-target`/`use-regex`/`x-forwarded-prefix` 注解（生产集群无 platform-router，共享路由器语义由生产环境自担）——与平台「已删除注解 rewrite 模式」的差异记录 §9-D9；v1 仅 host 模式进 E2E。
- `ingress.className` 可配（缺省空 = 集群默认 IngressClass，同 oaf-platform values.yaml ingress.className 语义）。

### 6.4 ci-values.yaml（helm template 渲染全量对象）

```yaml
fullnameOverride: ci-export-agent
image:
  base: example.invalid/agent-framework:ci
  oaf: example.invalid/oaf-ci-export-agent:1.0.0-e1
imagePullSecrets: []
env:
  AGENT_CONFIG_DIR: /config
  AGENT_WORKSPACE_DIR: /workspace
  SERVER_HOST: 0.0.0.0
  SERVER_PORT: "8100"
  CHECKPOINT_JDBC_URL: jdbc:mysql://mysql:3306/oaf_checkpoint_ci
  AGENT_REDIS_PREFIX: ci-export-agent:
secretEnv:
  LLM_API_KEY: ci-sk
  CHECKPOINT_PASSWORD: ci-pw
  AGENT_REDIS_URL: redis://redis:6379/0
ingress:
  mode: host
  hostSuffix: .ci.example.invalid
  className: nginx
mysql:
  createDatabase: true
  jobImage: mysql:8.0
```

### 6.5 charts-ci.yml 增加法（`.github/workflows/charts-ci.yml`）

changes 过滤（charts-ci.yml:27-32 的 `charts: 'charts/**'`）已覆盖新目录，**无需改 filter**。在 `jobs` 下新增独立 job（与既有 lint 并列，未来转必需检查时单独勾选）：

```yaml
  lint-oaf-agent:
    name: oaf-agent Chart 校验 (helm lint + template)
    needs: changes
    if: needs.changes.outputs.charts == 'true' || needs.changes.outputs.workflows == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
        with:
          version: v3.16.3
      - name: helm lint
        run: helm lint charts/oaf-agent
      - name: helm template（CI 占位必填值，渲染全量对象）
        run: |
          helm template ci-export charts/oaf-agent -f charts/oaf-agent/ci-values.yaml > /tmp/oaf-agent-rendered.yaml
          test -s /tmp/oaf-agent-rendered.yaml
          grep -q 'kind: Deployment' /tmp/oaf-agent-rendered.yaml
          grep -q 'kind: Job' /tmp/oaf-agent-rendered.yaml     # hook Job 必须渲染
          grep -q 'kind: Ingress' /tmp/oaf-agent-rendered.yaml
          grep -q 'kind: Secret' /tmp/oaf-agent-rendered.yaml
```

新增 job 默认非必需检查（根 AGENTS.md「分支保护」节）；转必需需仓库管理员在分支保护加勾。

---

## 7. 前端两页（U5）

### 7.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `frontend/src/app/(console)/console/agents/[id]/export/page.tsx` | 新建 | **导出发布页**（从服务详情「导出发布」按钮进入；URL `/console/agents/{id}/export`）。结构对齐详情页模式（agents/[id]/page.tsx:1-27：`use client` + `useConsoleWs/useConsoleWsRole/useMe` + `@/components/ui` 的 PageHead/Card/Btn/Badge/Note/Tag + tab 经 URL query 同步）。区块：① 配置包编辑（包文件树复用 `@/lib/file-tree` + 文本编辑器，AGENTS.md/skills 只读标注；保存→`PUT export/package/file`；「打发布标记」→ mark + marker/checksum 展示 + 标记列表）；② 生产环境变量（行级增删改；**来自运行时默认的行锁定不可编辑**——以 `GET export/preview` 返回的 lockedKeys 渲染锁标）；③ 模型/沙箱参数（`GET export/preview` 模板全量键只读展示，默认锁定）；④ 确认导出（基底镜像白名单下拉 + marker 选择 + 新增 env/secretKeys 表单 → `POST export` → 导出单状态卡轮询 `GET exports/:id`，built 后出 values/manifest 下载按钮） |
| `frontend/src/app/(console)/console/workspace-config/page.tsx` | 新建 | **工作空间配置管理页**（URL `/console/workspace-config`；页签 = 运行时默认 / 模型参数 / 沙箱参数 / 仓库与路由，MCP 连接器模板与 API 页签 v1 占位 soon）。运行时默认页签 = `GET runtime-defaults` 分类分组表单（复用 platformconfig 分组形态，敏感键掩码输入）+ `PUT` 三态保存（空串=删除提示） |
| `frontend/src/app/(console)/console/agents/[id]/page.tsx` | 修改（1 处） | 详情页头部操作区加「导出发布」`Link`（TABS 不动） |
| `frontend/src/app/(console)/layout.tsx` | 修改（1 处） | NAV 数组（layout.tsx:11-34）资产组内加 `{ href: "/console/workspace-config", label: "空间配置" }` |
| `frontend/src/lib/api.ts` | 修改（追加） | 类型 + 方法（§7.2） |
| `frontend/src/lib/export-view.ts` + `export-view.test.mjs` | 新建 | 纯函数：锁行判定（`isLockedRow(key, lockedKeys)`）、四层预览分组（L1/L2/L3 键归类展示）、marker 合法性提示；node:test 单测（格式对齐 agent-route.test.mjs:1-8，`package.json` test glob `src/lib/*.test.mjs` 自动纳管） |

### 7.2 API client 追加（api.ts，按既有分区注释风格）

```ts
// ---- 跨集群导出（设计 §9；/console 前缀见契约 §4）----
export interface ExportPreview { service: {...}; package: {...}; marks: ExportMark[]; runtimeDefaults: {...};
  baseImageWhitelist: ImageOption[]; derivedPreview: { checkpointDB: string; redisPrefix: string }; lockedKeys: string[] }
export interface ExportMark { marker: string; checksum: string; packageVersion: string; fileCount: number; status: string; createdAt: string }
export interface ExportOrderView { id: number; status: string; oafImageRef: string; oafImageDigest: string; error: string; itsmTicketId: string; createdAt: string; updatedAt: string }

// api 对象内：
workspaceConfig: {
  runtimeDefaults: (ws) => request<Record<string, {value?: string; sensitive: boolean; category: string}>>(`/console/workspaces/${ws}/config/runtime-defaults`),
  updateRuntimeDefaults: (ws, values) => request(`/console/workspaces/${ws}/config/runtime-defaults`, { method: "PUT", body: JSON.stringify({ values }) }),
},
exportOrders: {
  preview: (ws, id) => request<ExportPreview>(`/console/workspaces/${ws}/agents/${id}/export/preview`),
  packageTree: (ws, id) => request(`/console/workspaces/${ws}/agents/${id}/export/package`),
  saveFile: (ws, id, body) => request(`/console/workspaces/${ws}/agents/${id}/export/package/file`, { method: "PUT", body: JSON.stringify(body) }),
  mark: (ws, id) => request<ExportMark>(`/console/workspaces/${ws}/agents/${id}/export/package/mark`, { method: "POST" }),
  marks: (ws, id) => request<ExportMark[]>(`/console/workspaces/${ws}/agents/${id}/export/package/marks`),
  create: (ws, id, body) => request<ExportOrderView>(`/console/workspaces/${ws}/agents/${id}/export`, { method: "POST", body: JSON.stringify(body) }),
  get: (orderId) => request<ExportOrderView>(`/console/exports/${orderId}`),
  downloadValues: (orderId) => `${BASE}/console/exports/${orderId}/values.yaml`,     // <a href> 直下：JWT 走 oaf_session cookie（jwt.go:14 httpOnly），与 downloadPackage 同模式（api.ts downloadPackage）
  downloadManifest: (orderId) => `${BASE}/console/exports/${orderId}/manifest.json`,
  advance: (orderId, body) => request(`/console/exports/${orderId}/status`, { method: "POST", body: JSON.stringify(body) }),
},
```

鉴权说明：console API 的 `request()` 不带 Authorization 头，依赖登录时下发的 httpOnly cookie `oaf_session`（auth.go:12-16、jwt.go:26-41 header/cookie 双通道）——下载链接可直接 `<a href>`（先例 api.ts `downloadPackage`）。

---

## 8. E2E 全链路方案（本地 backend + kind 集群）

环境事实来源：任务附带的环境核验记录（registry/节点 mirror/mysql/redis/包源/凭据获取方式均已实测），以下命令逐条对应。

### 8.0 前置核验（一次性）

```bash
kubectl config current-context                     # 期望 kind-agent-manager
curl -s http://172.20.0.1:5001/v2/_catalog | head  # 期望含 agent-framework、busybox
kubectl -n agent-platform get svc oaf-mysql oaf-redis platform-router-svc   # 均 Running/存在
```

### 8.1 独立元数据库（避开集群 oaf_platform 双控制器）

```bash
MYSQL_ROOT_PW=$(kubectl -n agent-platform get secret oaf-mysql-secret -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' | base64 -d)   # 密码只进环境变量，不落输出（凭据获取方式：环境事实 mysqlCredCmd）
kubectl -n agent-platform exec deploy/oaf-mysql -- mysql -uroot -p"$MYSQL_ROOT_PW" -e "CREATE DATABASE IF NOT EXISTS oaf_platform_export_e2e CHARACTER SET utf8mb4"
kubectl -n agent-platform port-forward svc/oaf-mysql 13306:3306 >/tmp/pf-mysql.log 2>&1 &   # 宿主 3306/33060/3307 被本机 GreatSQL 占用，必须用 13306
```

### 8.2 启动本地 backend（KUBECONFIG 模式）

```bash
mkdir -p /tmp/oaf-data && cd backend
KUBECONFIG=/root/.kube/config \
NAMESPACE=agent-platform \
DATA_ROOT=/tmp/oaf-data \
MYSQL_DSN="root:${MYSQL_ROOT_PW}@tcp(127.0.0.1:13306)/oaf_platform_export_e2e?charset=utf8mb4&parseTime=True&loc=Local" \
JWT_SECRET=local-e2e-jwt-secret \
ADMIN_INIT_USER=admin ADMIN_INIT_PASSWORD=local-e2e-admin \
AVAILABLE_IMAGES="172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018|Agent Framework 2.1.0 (1018),172.20.0.1:5001/agent-framework:latest|Agent Framework (registry)" \
DEFAULT_IMAGE=172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018 \
INGRESS_HOST=127.0.0.1 INGRESS_PORT=30080 \
EXPORT_BASE_IMAGE=172.20.0.1:5001/busybox:latest \
go run ./cmd/server &     # :8080；router 模式自检 platform-router-svc 存在即过（main.go:59-63）
```

注意：`INGRESS_HOST_SUFFIX` 留空 = router 模式（config.go:81），仅影响测试集群内源服务的展示地址，导出链路不读它（生产路由模式来自 L1 ROUTING_*）。

### 8.3 登录 + 建服务与工作空间

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"local-e2e-admin"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
AUTH="Authorization: Bearer $TOKEN"
# default 工作空间由 Backfill 种子（migrate.go:37-52），admin 可解析（identity.go:172-173）
PKG=$(curl -s localhost:8080/api/v1/packages -F file=@../charts/oaf-platform/resources/release-agent.oaf.zip | sed -E 's/.*"id":([0-9]+).*/\1/')
SVC=$(curl -s localhost:8080/api/v1/services -H "$AUTH" -H 'Content-Type: application/json' -d @- <<EOF | sed -E 's/.*"id":([0-9]+).*/\1/'
{"packageId": ${PKG}, "name": "e2e-export-agent", "workspaceId": 1,
 "image": "172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018", "env": {"LLM_BASE_URL": "https://example.invalid/v1"}}
EOF
)
```

→ 服务 k8sName `oaf-e2e-export-agent`（DeriveK8sName objects.go:72-79），short `e2e-export-agent`，workspace 1（default）。源服务在测试集群的真实状态（running/register_failed）不阻塞导出（导出只读包与 env 记录）。

### 8.4 PUT 运行时默认（L1）

```bash
curl -s -X PUT localhost:8080/api/v1/console/workspaces/1/config/runtime-defaults -H "$AUTH" -H 'Content-Type: application/json' -d @- <<EOF
{"values":{
 "CHECKPOINT_JDBC_BASE":"jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint",
 "CHECKPOINT_USERNAME":"root", "CHECKPOINT_PASSWORD":"${MYSQL_ROOT_PW}",
 "AGENT_REDIS_URL":"redis://oaf-redis.agent-platform.svc.cluster.local:6379",
 "OTEL_EXPORTER_OTLP_ENDPOINT":"http://localhost:4318",
 "REGISTRY_ADDRESS":"172.20.0.1:5001", "REGISTRY_NAMESPACE":"",
 "IMAGE_PULL_SECRETS":"", "ROUTING_MODE":"host", "ROUTING_HOST_SUFFIX":".e2e.export.local",
 "BASE_IMAGE_WHITELIST":"172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018|Agent Framework 2.1.0 (1018)"}}
EOF
```

（Redis URL 实测不含内嵌凭据，取自业务 secret `oaf-order-agent-env-secret` 的 AGENT_REDIS_URL 解码；同 namespace 短名等价。e2e 用 root 走通 hook 建库，生产用运维最小权限账号——§9-D10。）

### 8.5 打发布标记 → 导出

```bash
# （可选）文本直改草稿：PUT .../export/package/file；此处直接对原包打标记
MARKER=$(curl -s -X POST localhost:8080/api/v1/console/workspaces/1/agents/${SVC}/export/package/mark \
  -H "$AUTH" | sed -E 's/.*"marker":"([^"]+)".*/\1/')     # 期望 "1.2.0-e1"（release-agent 包 frontmatter version=1.2.0，实测解包确认）
curl -s -X POST localhost:8080/api/v1/console/workspaces/1/agents/${SVC}/export -H "$AUTH" -H 'Content-Type: application/json' -d @- <<EOF
{"marker":"${MARKER}", "baseImage":"172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018",
 "env":{"MCP_E2E_TOKEN":"e2e-token"}, "secretKeys":["MCP_E2E_TOKEN"], "replicas":2}
EOF
# 轮询直至 built（异步构建：busybox 基底拉取 + tar 层 + remote.Write 直推 172.20.0.1:5001；全新 schema 首个导出单 id=1）
for i in $(seq 1 30); do S=$(curl -s localhost:8080/api/v1/console/exports/1 -H "$AUTH" | sed -E 's/.*"status":"([a-z_]+)".*/\1/'); [ "$S" = built ] && break; sleep 2; done; echo "status=$S"
curl -s http://172.20.0.1:5001/v2/oaf-e2e-export-agent/tags/list   # 期望 {"name":"oaf-e2e-export-agent","tags":["1.2.0-e1"]}
```

### 8.6 下载交付物 → 生产形态部署

```bash
curl -s localhost:8080/api/v1/console/exports/1/values.yaml   -H "$AUTH" -o /tmp/e2e-values.yaml
curl -s localhost:8080/api/v1/console/exports/1/manifest.json -H "$AUTH" -o /tmp/e2e-manifest.json
python3 -c "import json;d=json.load(open('/tmp/e2e-manifest.json'));print(d['bundle']['oafImageDigest'])"   # 与 GET 详情 oafImageDigest 一致
helm upgrade --install e2e-export /root/agent-manager/charts/oaf-agent -n oaf-export-e2e --create-namespace -f /tmp/e2e-values.yaml
```

（values 内 image.base/oaf 指向 172.20.0.1:5001，kind 节点经 mirror 现拉——busybox 仅 latest 一个 tag 且未入节点缓存，首次拉取依赖 mirror 已就绪，环境事实已核。）

### 8.7 校验清单（逐条留输出证据）

对象名约定：release `e2e-export` + `fullnameOverride=e2e-export-agent`（渲染 values）→ Deployment/Service/Job/Ingress 名均从 fullname 派生。

```bash
# 1) hook Job 幂等建库执行完成
kubectl -n oaf-export-e2e wait --for=condition=complete job/e2e-export-agent-mysql-init --timeout=120s
kubectl -n agent-platform exec deploy/oaf-mysql -- mysql -uroot -p"$MYSQL_ROOT_PW" -e "SHOW DATABASES LIKE 'oaf_checkpoint_e2e_export_agent'"
# 2) 工作负载可用（replicas=2）
kubectl -n oaf-export-e2e wait --for=condition=available deploy/e2e-export-agent --timeout=300s
# 3) initContainer 注入 /config：主容器内读（agent-framework 镜像含 shell；无 shell 时 kubectl debug 兜底）
kubectl -n oaf-export-e2e exec deploy/e2e-export-agent -- ls /config | grep -x AGENTS.md
kubectl -n oaf-export-e2e exec deploy/e2e-export-agent -- ls /config/skills | head -3
# 4) 集群内 agent-card 可达（busybox wget）
kubectl -n oaf-export-e2e run probe-card --rm -i --restart=Never --image=172.20.0.1:5001/busybox:latest -- \
  wget -qO- http://e2e-export-agent-svc:8100/.well-known/agent-card.json | head -c 200
# 5) 宿主机经 ingress-nginx NodePort + Host 头（host 模式 Ingress，host=e2e-export-agent.e2e.export.local）
curl -s -H 'Host: e2e-export-agent.e2e.export.local' http://127.0.0.1:30080/.well-known/agent-card.json | head -c 200
# 6) 幂等复推：重复 POST export（同 marker）→ digest 不变；helm upgrade --install 重跑幂等
# 7) 敏感值边界：/tmp/e2e-values.yaml 内 secretEnv 含 MCP_E2E_TOKEN；GET exports/1 JSON 响应 grep 不到该值（json:"-"）
grep -c MCP_E2E_TOKEN /tmp/e2e-values.yaml
curl -s localhost:8080/api/v1/console/exports/1 -H "$AUTH" | grep -c MCP_E2E_TOKEN   # 期望 0
```

### 8.8 清理（避免残留）

```bash
helm uninstall e2e-export -n oaf-export-e2e && kubectl delete ns oaf-export-e2e
kill %1   # 停 backend 与 port-forward；/tmp/oaf-data、/tmp/e2e-*.yaml 删除
# 本地 schema 留存 oaf_platform_export_e2e 供复跑（ DROP 与否由主会话决定，不在本工作流内执行）
```

---

## 9. 风险与决策记录

| # | 类型 | 内容 |
|---|------|------|
| D1 | 偏差 | 设计 §8 实体草稿主键/外键为 `uint64`；按仓库惯例（model.go:10、42 全部 `uint`）改 `uint`，GORM MySQL 映射同为 bigint unsigned，JSON 形状不变。字段集、索引意图、状态机严格保留设计 §8。 |
| D2 | 设计缺陷修正 | 设计 §8 `Marker` 单列 `uniqueIndex` 在跨服务维度会撞（两服务对同版本包各打 e1）；改为复合唯一 `(service_id, marker)`。marker 格式仍为 `{包版本}-e{序号}`，序号按服务内递增。 |
| D3 | 偏差 | 设计 §9 路径 `/api/v1/workspaces/:id/config/…`、`/api/v1/services/:id/export/…`、`/api/v1/exports/:id` 统一挂到 `/api/v1/console/` 前缀下（`workspaces/:ws/agents/:id/export/…`、`console/exports/:id`）：设计 §9 自述「console JWT 之下」，复用 cg 组 JWTAuth + resolveWorkspace（console.go:33-121）与既有 console 路由形状，避免重建第二条鉴权/归属链；路径后缀与语义逐一对应。 |
| D4 | 范围裁剪（2026-10-30 评审修订） | 原记录「三组端点 v1 不实现」与实现不符，据实修订：v1 实际以 `workspaceconfig.go` 分组视图骨架落地——`GET/PUT /config/templates/:group`（llm\|sandbox，读写同一份 runtime-defaults）、`GET/PUT /config/registry`（registry/routing/runtime 分类键）、`GET /config/mcp-connectors`（连接器模板列表，tools CRUD 同源）+ `PUT /config/mcp-connectors`（**501**，连接器模板 v1 经 `/workspaces/:ws/tools` 管理，无独立键空间）。导出链路键空间事实源仍为 runtime-defaults 单端点（§1.4），前端两页全部经 runtime-defaults 读写、不消费分组端点；端点带权限守卫（PUT requireWsRole）与测试覆盖（workspaceconfig_test.go）。v2 再评估拆分独立端点或移除骨架。 |
| D5 | 范围裁剪 | 下载审计 v1 用结构化访问日志 + manifest `audit.downloadedBy` 追加；独立 `export_download_events` 表留 v2（避免超出三表范围；设计 §12.2「下载写审计日志」的最低满足）。同理 `PlatformConfigEvent` 式变更审计表不复用（该表无 workspace 维度），v1 以 `UpdatedBy` 列留痕。 |
| D6 | 设计内矛盾取舍 | 设计 §8 `ExportOrder.ExportPackageID` 注释「在线编辑派生的新包（复用 copy-on-write）」与 §14「导出快照落 export_package_marks 表（不派生 oaf_packages 记录）」矛盾；v1 取 §14：快照只落 marks，`ExportPackageID` 恒 0（字段保留，v2 可启用）。 |
| D7 | 边界风险 | mark 快照 FilesJSON 含二进制 base64，mediumtext 16MB 上限：超限 400 并提示改用更小包；源包自包含快照使构建不回读源包（可复现、防源包换版竞态），代价是快照体积 ≈ 包体积 ×1.33。 |
| D8 | 运行依赖 | ImageBuilder 构建期需拉基底镜像（remote.Image）：backend 运行环境必须可达 `EXPORT_BASE_IMAGE` 指向的仓库；离线/内网部署必须显式覆盖该 env（缺省 `busybox:1.36` 指向 docker.io）。推送凭证经 backend 进程 env 注入（设计 §12.3），v1 凭证全局一份（非 per-workspace）。 |
| D9 | 语义差异 | oaf-agent chart 的 `ingress.mode: router` 需渲染 ingress-nginx rewrite 注解（生产集群无 platform-router 承接剥前缀）——与平台「已删除注解 rewrite 模式」（subpath-routing v2 决议）不冲突：该决议针对平台自举的 router 形态，oaf-agent 的 router 模式是生产环境自担的共享路由器接线。v1 E2E 只验证 host 模式；router 模式模板存在但不进本工作流验收。 |
| D10 | e2e 取舍 | e2e hook 建库用 root 凭据（保证 CREATE DATABASE 必然成功）；生产 values 应使用具备 `{基库}_%` 授权的最小权限账号（对齐 publishisolation.go:5-6 前提）。e2e 的本地 backend 与集群内 backend 各自独立元数据库（oaf_platform_export_e2e），但**共享同一 kind 集群**：e2e 服务名带 `e2e-export` 前缀隔离；测试集群内源服务 Pod 状态不阻塞导出链路。 |
| D11 | 放弃方案 | 沿用设计 §0 四项决议的否决项：values 占位符脱敏（决议 ①，v2 渲染层可加）、平台自建生产部署接口（决议 ②）、独立镜像同步服务（决议 ③，改用 backend 内 go-containerregistry 直推）、生产 A2A 目录登记（决议 ④）。工程层另放弃：Kaniko（设计 §5 对比表，需特权）；导出单状态事件表（v1 单行 status+error 足够，v2 需要时间线时再加）；L2 覆盖 L1 的「临时改基线」能力（设计 §10 锁定语义，撞键 400）。 |
| D12 | 命名 | OAF 镜像 repo/派生键统一用 `short`（`k8s.ShortName` 去 `oaf-` 前缀，objects.go:81-83）：设计 §4/§5 的 `oaf-{k8s名}` 模板自带 oaf- 前缀、§7.3 示例 `oaf_checkpoint_travel_agent`/`travel-agent:` 均为 short 口径；复用 `checkpointDBNameFor`/`redisPrefixFor`（publishisolation.go:80-107，函数对任意名字成立）。镜像 tag 直接用 marker（`{包版本}-e{序号}`），语义版本含 `+` 时替换为 `-`（docker tag 字符集）。 |
| R1 | 风险 | 异步构建 goroutine 与既有 `asyncWaitAndRegister`（publish.go:350-377）同属「进程内后台推进」：backend 重启丢 in-flight 构建，导出单停留 building——v1 靠「重新创建导出单」解救（同 marker 幂等重推，镜像内容寻址不变）；恢复扫描留 v2。导出链路对测试集群 K8s 零写入（只读包目录与 DB），不触碰原则一红线。 |
| R2 | 风险 | 本地 e2e 与集群内 backend 同集群双控制器并存：发布/删除动作互相不可见。e2e 全程用独立 schema + 独立命名前缀；不在 e2e 中操作集群内既有服务。 |
| R3 | 风险 | busybox 基底本地 registry 仅 `latest` 一个 tag 且未入节点镜像缓存（环境核验 warning）：e2e 首次部署依赖节点 mirror 现拉（已就绪）；镜像构建期 backend（宿主机）直连 172.20.0.1:5001 无缓存问题。 |
| R4 | 风险 | values 含明文敏感值且存 DB（ValuesYAML mediumtext）：接口层 `json:"-"` 全遮挡 + 下载鉴权（跟随 workspace）+ 下载审计（§5.8）；DB 备份密级需与 ITSM 附件对齐——运维须知，非代码可控面。 |
| D13 | 安全修正（评审修订） | 运行时默认键空间**排除 `CHECKPOINT_JDBC_URL`**：`KnownKeys()`（template.go:108-116）实测含它（template.go:46），若「全量复用」会让 L1 合法持有源集群 JDBC URL（可能内嵌凭据）并与 L3 派生同名键冲突、经 values 明文流入生产。修订为 24 模板键（= KnownKeys − CHECKPOINT_JDBC_URL）+ 8 导出键，L1/L3 撞键结构性不可能（§1.4、§5.4）；与设计 §10 mysql 分类只定义 `CHECKPOINT_JDBC_BASE` 一致。同期修正 §5.3 渲染取值语义为 L1∪L2 并集（评审修订 2）：L2 撞 L1/L3 在 CreateExportOrder 校验期 400，渲染层不丢 L2 新增键（否则 e2e §8.7 校验 7 的 `MCP_E2E_TOKEN` 会从 secretEnv 消失）。 |
