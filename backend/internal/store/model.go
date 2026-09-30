// Package store 持久化：GORM 模型、数据库初始化与 PVC 目录文件操作。
package store

import (
	"time"
)

// OafPackage 上传的 OAF 配置包。
type OafPackage struct {
	ID              uint      `gorm:"primaryKey" json:"id"`
	Name            string    `gorm:"size:128" json:"name"`
	Slug            string    `gorm:"size:200;index" json:"slug"`
	Version         string    `gorm:"size:32" json:"version"`
	Description     string    `gorm:"type:text" json:"description"`
	ManifestJSON    string    `gorm:"type:json" json:"manifestJson"` // frontmatter 原文
	WarningsJSON    string    `gorm:"type:json" json:"warningsJson"` // 宽松模式 warnings
	DirPath         string    `gorm:"size:256" json:"dirPath"`       // PVC 内相对路径 packages/{id}
	FileCount       int       `json:"fileCount"`
	TotalSize       int64     `json:"totalSize"`
	Checksum        string    `gorm:"size:64" json:"checksum"`      // zip sha256
	SourcePackageID uint      `gorm:"index" json:"sourcePackageId"` // 0=上传原始包；>0=由该包编辑派生
	RefCount        int       `json:"refCount"`
	CreatedAt       time.Time `json:"createdAt"`
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
