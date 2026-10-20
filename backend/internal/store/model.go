// Package store 持久化：GORM 模型、数据库初始化与 PVC 目录文件操作。
package store

import (
	"time"
)

// OafPackage 上传的 OAF 配置包。
type OafPackage struct {
	ID              uint      `gorm:"primaryKey" json:"id"`
	Name            string    `gorm:"size:128" json:"name"`
	Slug            string    `gorm:"size:200;uniqueIndex:uk_pkg_slug_version" json:"slug"`
	Version         string    `gorm:"size:32;uniqueIndex:uk_pkg_slug_version" json:"version"`
	Description     string    `gorm:"type:text" json:"description"`
	ManifestJSON    string    `gorm:"type:json" json:"manifestJson"` // frontmatter 原文
	WarningsJSON    string    `gorm:"type:json" json:"warningsJson"` // 宽松模式 warnings
	DirPath         string    `gorm:"size:256" json:"dirPath"`       // PVC 内相对路径 packages/{id}
	FileCount       int       `json:"fileCount"`
	TotalSize       int64     `json:"totalSize"`
	Checksum        string    `gorm:"size:64" json:"checksum"`      // zip sha256
	SourcePackageID uint      `gorm:"index" json:"sourcePackageId"` // 0=上传原始包；>0=由该包编辑派生
	RefCount        int       `json:"refCount"`
	// WorkspaceID 归属工作空间：0=平台公共包（所有空间可见，决议 7）；>0=仅本空间可见
	WorkspaceID uint      `gorm:"index" json:"workspaceId"`
	IsTemplate  bool      `gorm:"index" json:"isTemplate"` // 模板标记（用户侧模板页数据源）
	CreatedAt   time.Time `json:"createdAt"`
}

// 服务状态常量（状态机见 REDESIGN §3.9）。
const (
	StatusCreated        = "created"
	StatusDeploying      = "deploying"
	StatusRunning        = "running"
	StatusRegisterFailed = "register_failed"
	StatusDeployFailed   = "deploy_failed"
	StatusStopped        = "stopped"
	StatusError          = "error"
)

// ServiceEntity 已发布的服务实例（表名 services）。
type ServiceEntity struct {
	ID          uint   `gorm:"primaryKey" json:"id"`
	K8sName     string `gorm:"size:63;uniqueIndex" json:"k8sName"`
	DisplayName string `gorm:"size:128" json:"displayName"`
	PackageID   uint   `gorm:"index" json:"packageId"`
	Image       string `gorm:"size:256" json:"image"`
	EnvJSON     string `gorm:"type:json" json:"envJson"`
	// EnvSecretJSON 服务级敏感 env（模板敏感键 + secretKeys 指定键）。
	// json:"-"：List/Detail/Publish 响应直接内嵌本实体序列化，敏感值绝不随实体直出，
	// 掩码键集由视图层补充 envSecretKeys（设计见 docs/design/platform-default-config-secret-design.md §3.3）。
	EnvSecretJSON string `gorm:"type:json" json:"-"`
	Replicas      int    `json:"replicas"`

	// WorkspaceID 归属工作空间（0=未归属，仅运维可见）；CreatedBy 发布人用户 id（0=历史行）
	WorkspaceID uint `gorm:"index" json:"workspaceId"`
	CreatedBy   uint `json:"createdBy"`
	// Spec 资源规格档（"" | small | medium | large，决议 4；空=沿用平台默认资源配置）
	Spec string `gorm:"size:16" json:"spec"`
	// PublicEndpoint 是否发布对外访问入口；nil 视为 true（存量行为，M3 §2.4）
	PublicEndpoint *bool `gorm:"index" json:"publicEndpoint"`

	Status     string `gorm:"size:24;index" json:"status"`
	Endpoint   string `gorm:"size:256" json:"endpoint"`
	ClusterURL string `gorm:"size:256" json:"clusterUrl"`
	ShortName  string `gorm:"size:63" json:"shortName"` // ingress path 用

	AgentCardJSON     string     `gorm:"type:json" json:"agentCardJson"`
	RegisteredName    string     `gorm:"size:128" json:"registeredName"`
	RegisteredVersion string     `gorm:"size:32" json:"registeredVersion"`
	SkillsJSON        string     `gorm:"type:json" json:"skillsJson"`
	RegisteredAt      *time.Time `json:"registeredAt"`

	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// EndpointPublic 兜底取值：nil（存量行）= true（发布对外入口，历史行为）。
func (s *ServiceEntity) EndpointPublic() bool {
	return s.PublicEndpoint == nil || *s.PublicEndpoint
}

func (ServiceEntity) TableName() string { return "services" }

// ServiceEvent 状态变迁历史。
type ServiceEvent struct {
	ID         uint      `gorm:"primaryKey" json:"id"`
	ServiceID  uint      `gorm:"index" json:"serviceId"`
	FromStatus string    `gorm:"size:24" json:"fromStatus"`
	ToStatus   string    `gorm:"size:24" json:"toStatus"`
	Reason     string    `gorm:"size:512" json:"reason"`
	CreatedAt  time.Time `gorm:"index" json:"createdAt"`
}

// PlatformConfigEntity 平台默认配置事实源（键 → 值，含敏感与非敏感）；
// 渲染时按模板 Sensitive 拆分到平台 CM（非敏感）/ Secret（敏感），键集合 ⊆ 模板键集合。
type PlatformConfigEntity struct {
	EnvKey    string    `gorm:"primaryKey;column:env_key;size:128" json:"envKey"`
	Value     string    `gorm:"type:text" json:"-"` // 敏感值不随 JSON 直出
	UpdatedAt time.Time `json:"updatedAt"`
}

func (PlatformConfigEntity) TableName() string { return "platform_config" }

// PlatformConfigEvent 平台配置审计：只记动作与键名，不记值。
type PlatformConfigEvent struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	Action    string    `gorm:"size:32" json:"action"`    // update / apply_restart
	EnvKeys   string    `gorm:"type:json" json:"envKeys"` // 本次涉及键名数组
	CreatedAt time.Time `gorm:"index" json:"createdAt"`
}

// ---- 控制面拆分新增实体（docs/design/console-user-workspace-split-design.md §6）----

// User 平台用户（role=admin 可进运维侧与任意空间；user 仅本人所属空间）。
type User struct {
	ID           uint       `gorm:"primaryKey" json:"id"`
	Username     string     `gorm:"size:64;uniqueIndex" json:"username"`
	PasswordHash string     `gorm:"size:128" json:"-"` // bcrypt
	DisplayName  string     `gorm:"size:128" json:"displayName"`
	Email        string     `gorm:"size:128" json:"email"`
	Role         string     `gorm:"size:16;index" json:"role"` // admin | user
	Status       string     `gorm:"size:16" json:"status"`     // active | disabled
	LastLoginAt  *time.Time `json:"lastLoginAt"`
	CreatedAt    time.Time  `json:"createdAt"`
	UpdatedAt    time.Time  `json:"updatedAt"`
}

func (User) TableName() string { return "users" }

// Workspace 工作空间：用户侧资源隔离与协作单元（slug 形如 ws-{8 hex}）。
type Workspace struct {
	ID          uint   `gorm:"primaryKey" json:"id"`
	Slug        string `gorm:"size:32;uniqueIndex" json:"slug"`
	Name        string `gorm:"size:128" json:"name"`
	Description string `gorm:"size:256" json:"description"`
	OwnerUserID uint   `gorm:"index" json:"ownerUserId"`
	Status      string `gorm:"size:16" json:"status"`      // active | disabled
	QuotaJSON   string `gorm:"type:json" json:"quotaJson"` // {"maxAgents":N}，一期仅记录
	CreatedAt   time.Time `json:"createdAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
}

func (Workspace) TableName() string { return "workspaces" }

// WorkspaceMember 空间成员关系（role: owner | admin | member）。
type WorkspaceMember struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	WorkspaceID uint      `gorm:"uniqueIndex:uk_ws_user;index" json:"workspaceId"`
	UserID      uint      `gorm:"uniqueIndex:uk_ws_user;index" json:"userId"`
	Role        string    `gorm:"size:16" json:"role"`
	CreatedAt   time.Time `json:"createdAt"`
}

func (WorkspaceMember) TableName() string { return "workspace_members" }

// ApiKey 调用凭证（明文仅签发时返回一次；sha256 落库）。
type ApiKey struct {
	ID          uint       `gorm:"primaryKey" json:"id"`
	WorkspaceID uint       `gorm:"index" json:"workspaceId"`
	AgentID     *uint      `gorm:"index" json:"agentId"` // nil=空间内全部 Agent
	UserID      uint       `json:"userId"`               // 签发人
	Name        string     `gorm:"size:128" json:"name"`
	KeyPrefix   string     `gorm:"size:16" json:"keyPrefix"`     // ak- 前 8 位（列表识别）
	KeyHash     string     `gorm:"size:64;uniqueIndex" json:"-"` // sha256 hex，不存明文
	Status      string     `gorm:"size:16" json:"status"`        // active | revoked
	LastUsedAt  *time.Time `json:"lastUsedAt"`
	ExpiresAt   *time.Time `json:"expiresAt"`
	CreatedAt   time.Time  `json:"createdAt"`
}

func (ApiKey) TableName() string { return "api_keys" }

// ModelConnection 工作空间级模型连接（对标 AgentCore 模型管理；内网库明文先例=model_config）。
type ModelConnection struct {
	ID          uint       `gorm:"primaryKey" json:"id"`
	WorkspaceID uint       `gorm:"uniqueIndex:uk_mc_ws_name;index" json:"workspaceId"`
	Name        string     `gorm:"size:128;uniqueIndex:uk_mc_ws_name" json:"name"`
	Provider    string     `gorm:"size:32" json:"provider"` // 一期仅 openai（OpenAI 兼容）
	ModelID     string     `gorm:"size:128" json:"modelId"`
	BaseURL     string     `gorm:"size:512" json:"baseUrl"`
	APIKey      string     `gorm:"size:512" json:"-"` // 接口一律掩码回显
	Temperature float64    `json:"temperature"`
	MaxTokens   int        `json:"maxTokens"`
	TimeoutSec  int        `json:"timeoutSeconds"`
	Status      string     `gorm:"size:16" json:"status"` // verified | unverified | failed
	LastTestedAt *time.Time `json:"lastTestedAt"`
	CreatedAt   time.Time  `json:"createdAt"`
	UpdatedAt   time.Time  `json:"updatedAt"`
}

func (ModelConnection) TableName() string { return "model_connections" }

// ModelDelivery 连接 → Agent 下发状态（usage 表数据源；无后台任务，页面手动/running 回调触发）。
type ModelDelivery struct {
	ID            uint       `gorm:"primaryKey" json:"id"`
	ConnectionID  uint       `gorm:"index" json:"connectionId"`
	ServiceID     uint       `gorm:"index" json:"serviceId"`
	RemoteModelID string     `gorm:"size:128" json:"remoteModelId"` // Agent /models 返回的 id
	Status        string     `gorm:"size:16" json:"status"`         // pending | synced | failed | removed
	Error         string     `gorm:"size:256" json:"error"`
	SyncedAt      *time.Time `json:"syncedAt"`
	CreatedAt     time.Time  `json:"createdAt"`
}

func (ModelDelivery) TableName() string { return "model_deliveries" }

// McpConnector 工作空间级 MCP 连接器配置（创建/更新 Agent 时勾选注入生成包）。
type McpConnector struct {
	ID             uint       `gorm:"primaryKey" json:"id"`
	WorkspaceID    uint       `gorm:"uniqueIndex:uk_conn_ws_name;index" json:"workspaceId"`
	Name           string     `gorm:"size:64;uniqueIndex:uk_conn_ws_name" json:"name"`
	Transport      string     `gorm:"size:24" json:"transport"` // streamable_http | sse
	Endpoint       string     `gorm:"size:512" json:"endpoint"`
	AuthType       string     `gorm:"size:16" json:"authType"` // none | bearer
	AuthToken      string     `gorm:"size:512" json:"-"`       // 注入走服务 Secret，不落包
	TimeoutSec     int        `json:"timeoutSec"`
	ToolScope      string     `gorm:"type:json" json:"toolScope"`   // {"mode":"all"} | {"mode":"subset","tools":[…]}
	Permissions    string     `gorm:"type:json" json:"permissions"` // {"tools":{"t":"allow|ask|deny"}}
	Status         string     `gorm:"size:16" json:"status"`
	LastVerifiedAt *time.Time `json:"lastVerifiedAt"`
	CreatedAt      time.Time  `json:"createdAt"`
	UpdatedAt      time.Time  `json:"updatedAt"`
}

func (McpConnector) TableName() string { return "mcp_connectors" }

// AgentIntegration 集成关系声明（Agent Protocol 远程子 agent；运行时编排零改动）。
type AgentIntegration struct {
	ID              uint      `gorm:"primaryKey" json:"id"`
	WorkspaceID     uint      `gorm:"index" json:"workspaceId"`
	MemberServiceID uint      `gorm:"index" json:"memberServiceId"`
	Name            string    `gorm:"size:64" json:"name"`
	EndpointURL     string    `gorm:"size:256" json:"endpointUrl"`
	AskPolicy       string    `gorm:"size:16" json:"askPolicy"` // PROPAGATE | DENY
	RemoteStreaming bool      `json:"remoteStreaming"`
	AuthHeaderKey   string    `gorm:"size:64" json:"authHeaderKey"`
	Enabled         bool      `json:"enabled"`
	CreatedAt       time.Time `json:"createdAt"`
	UpdatedAt       time.Time `json:"updatedAt"`
}

func (AgentIntegration) TableName() string { return "agent_integrations" }

// ---- Skill 中心（2026-10-19，docs/design/console-user-workspace-split-design.md §6.3）----
// 工作空间级独立技能资产：市场导入 / zip 上传 / Markdown 定义创建，版本管理 + Agent 引用
// （生成包自包含，最后生成的 OAF 包即引用快照）。全为平台自有表，AutoMigrate 同步建表。

// Skill 技能资产主表（name 空间内唯一；语义对齐 AgentCore Skill）。
type Skill struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	WorkspaceID uint      `gorm:"uniqueIndex:uk_skill_ws_name;index" json:"workspaceId"`
	Name        string    `gorm:"size:64;uniqueIndex:uk_skill_ws_name" json:"name"` // skillNameRe 口径
	DisplayName string    `gorm:"size:128" json:"displayName"`
	Description string    `gorm:"size:1024" json:"description"`
	TagsJSON    string    `gorm:"type:json" json:"tagsJson"` // ["客服","工单"]
	Source      string    `gorm:"size:16" json:"source"`     // market | zip | markdown
	SourceName  string    `gorm:"size:64" json:"sourceName"` // market 来源市场名（多源预留）
	// LatestVersionID 最新 skill_versions 行（发布后=「最新发布版」展示目标）
	LatestVersionID uint      `json:"latestVersionId"`
	Enabled         bool      `json:"enabled"` // 停用后创建/编辑候选不再出现；已生成包不回变
	Status          string    `gorm:"size:16" json:"status"` // active | disabled
	CreatedBy       uint      `json:"createdBy"`
	CreatedAt       time.Time `json:"createdAt"`
	UpdatedAt       time.Time `json:"updatedAt"`
}

func (Skill) TableName() string { return "skills" }

// SkillVersion 版本行（对标 AgentCore：工作区草稿可续改 + 正式版本不可变快照）。
type SkillVersion struct {
	ID       uint   `gorm:"primaryKey" json:"id"`
	SkillID  uint   `gorm:"uniqueIndex:uk_skillver;index" json:"skillId"`
	Version  string `gorm:"size:32;uniqueIndex:uk_skillver" json:"version"` // major.minor.patch
	Status   string `gorm:"size:16;index" json:"status"` // draft | review(预留) | pending(预留) | published | offline
	Changelog string `gorm:"size:512" json:"changelog"`
	// ContentJSON Markdown 定义形态的正文（zip 形态为空）；FilesJSON zip 文件清单
	ContentJSON string `gorm:"type:json" json:"contentJson"`
	FilesJSON   string `gorm:"type:json" json:"filesJson"` // [{path,size,sha256}]
	// StorageDir PVC 相对路径 skills/{skillID}/{version}（zip 解包内容；markdown 形态为空，
	// 内容在 ContentJSON——读取侧两种数据源都支持）
	StorageDir string    `gorm:"size:256" json:"storageDir"`
	Checksum   string    `gorm:"size:64" json:"checksum"`
	CreatedBy  uint      `json:"createdBy"`
	CreatedAt  time.Time `json:"createdAt"`
}

func (SkillVersion) TableName() string { return "skill_versions" }

// SkillRelease 发布记录（版本生命周期审计：publish | offline | reline）。
type SkillRelease struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	SkillID   uint      `gorm:"index" json:"skillId"`
	VersionID uint      `gorm:"index" json:"versionId"`
	Action    string    `gorm:"size:16" json:"action"`
	Note      string    `gorm:"size:512" json:"note"`
	CreatedBy uint      `json:"createdBy"`
	CreatedAt time.Time `gorm:"index" json:"createdAt"`
}

func (SkillRelease) TableName() string { return "skill_releases" }

// SkillMarketSource 市场源注册表（多源预留，2026-10-19 §6.3 决议 8；一期仅内置源一行种子）。
type SkillMarketSource struct {
	ID       uint   `gorm:"primaryKey" json:"id"`
	Key      string `gorm:"size:32;uniqueIndex" json:"key"` // builtin | 外部源自定义键
	Name     string `gorm:"size:64" json:"name"`
	Type     string `gorm:"size:16" json:"type"` // builtin | registry（registry 远期开放）
	Endpoint string `gorm:"size:512" json:"endpoint"` // registry 源目录 JSON 地址（预留）
	Enabled  bool   `json:"enabled"`
	Sort     int    `json:"sort"`
}

func (SkillMarketSource) TableName() string { return "skill_market_sources" }

// SkillMarketItem Skill 广场条目（只读；安装=按条目建 skill 资产 v1.0.0 published）。
type SkillMarketItem struct {
	ID          uint   `gorm:"primaryKey" json:"id"`
	SourceID    uint   `gorm:"uniqueIndex:uk_mktitem;index" json:"sourceId"`
	Key         string `gorm:"size:64;uniqueIndex:uk_mktitem" json:"key"`
	DisplayName string `gorm:"size:128" json:"displayName"`
	Description string `gorm:"size:1024" json:"description"`
	TagsJSON    string `gorm:"type:json" json:"tagsJson"`
	Downloads   int    `json:"downloads"`
	// BundleDir 内置 zip 的只读路径（镜像/PVC）；registry 源为远端 URL（远期）
	BundleDir string `gorm:"size:512" json:"bundleDir"`
	Enabled   bool   `json:"enabled"`
}

func (SkillMarketItem) TableName() string { return "skill_market_items" }
