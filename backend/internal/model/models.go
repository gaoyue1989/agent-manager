package model

import "time"

type AgentStatus string

const (
	StatusDraft       AgentStatus = "draft"
	StatusGenerated   AgentStatus = "generated"
	StatusBuilt       AgentStatus = "built"
	StatusDeployed    AgentStatus = "deployed"
	StatusPublished   AgentStatus = "published"
	StatusUnpublished AgentStatus = "unpublished"
	StatusError       AgentStatus = "error"
)

type ConfigType string

const (
	ConfigForm ConfigType = "form"
	ConfigJSON ConfigType = "json"
	ConfigYAML ConfigType = "yaml"
	ConfigOAF  ConfigType = "oaf"
)

type Agent struct {
	ID          uint        `json:"id" gorm:"primaryKey;autoIncrement"`
	Name        string      `json:"name" gorm:"type:varchar(128);not null;index"`
	Description string      `json:"description" gorm:"type:text"`
	Config      string      `json:"config" gorm:"type:json;not null"`
	ConfigType  ConfigType  `json:"config_type" gorm:"type:enum('form','json','yaml','oaf');default:'oaf'"`
	Status      AgentStatus `json:"status" gorm:"type:enum('draft','generated','built','deployed','published','unpublished','error');default:'draft';index"`
	Version     int         `json:"version" gorm:"default:1"`
	CreatedAt   time.Time   `json:"created_at"`
	UpdatedAt   time.Time   `json:"updated_at"`
}

func (a *Agent) ParseOAFConfig() (*OAFConfig, error) {
	if a.ConfigType != ConfigOAF {
		return nil, nil
	}
	return ParseOAF(a.Config)
}

type GenStatus string

const (
	GenPending GenStatus = "pending"
	GenRunning GenStatus = "running"
	GenSuccess GenStatus = "success"
	GenFailed  GenStatus = "failed"
)

type CodeGeneration struct {
	ID             uint      `json:"id" gorm:"primaryKey;autoIncrement"`
	AgentID        uint      `json:"agent_id" gorm:"not null;index:idx_agent_version"`
	Version        int       `json:"version" gorm:"not null;index:idx_agent_version"`
	CodePath       string    `json:"code_path" gorm:"type:varchar(512)"`
	DockerfilePath string    `json:"dockerfile_path" gorm:"type:varchar(512)"`
	Status         GenStatus `json:"status" gorm:"type:enum('pending','running','success','failed');default:'pending'"`
	ErrorMsg       string    `json:"error_msg" gorm:"type:text"`
	CreatedAt      time.Time `json:"created_at"`
	Agent          Agent     `json:"-" gorm:"foreignKey:AgentID;constraint:OnDelete:CASCADE"`
}

type BuildStatus string

const (
	BuildPending  BuildStatus = "pending"
	BuildBuilding BuildStatus = "building"
	BuildSuccess  BuildStatus = "success"
	BuildFailed   BuildStatus = "failed"
)

type ImageBuild struct {
	ID        uint        `json:"id" gorm:"primaryKey;autoIncrement"`
	AgentID   uint        `json:"agent_id" gorm:"not null;index:idx_build_agent_version"`
	Version   int         `json:"version" gorm:"not null;index:idx_build_agent_version"`
	ImageTag  string      `json:"image_tag" gorm:"type:varchar(256)"`
	Status    BuildStatus `json:"status" gorm:"type:enum('pending','building','success','failed');default:'pending'"`
	BuildLog  string      `json:"build_log" gorm:"type:text"`
	CreatedAt time.Time   `json:"created_at"`
	Agent     Agent       `json:"-" gorm:"foreignKey:AgentID;constraint:OnDelete:CASCADE"`
}

type DeployStatus string

const (
	DeployPending   DeployStatus = "pending"
	DeployDeploying DeployStatus = "deploying"
	DeployRunning   DeployStatus = "running"
	DeployStopped   DeployStatus = "stopped"
	DeployFailed    DeployStatus = "failed"
)

type Deployment struct {
	ID             uint         `json:"id" gorm:"primaryKey;autoIncrement"`
	AgentID        uint         `json:"agent_id" gorm:"not null;index"`
	Version        int          `json:"version" gorm:"not null"`
	SandboxName    string       `json:"sandbox_name" gorm:"type:varchar(128)"`
	SandboxStatus  string       `json:"sandbox_status" gorm:"type:varchar(64)"`
	EndpointURL    string       `json:"endpoint_url" gorm:"type:varchar(1024)"`
	Status         DeployStatus `json:"status" gorm:"type:enum('pending','deploying','running','stopped','failed');default:'pending';index"`
	DeployedAt     *time.Time   `json:"deployed_at"`
	UnpublishedAt  *time.Time   `json:"unpublished_at"`
	CreatedAt      time.Time    `json:"created_at"`
	Agent          Agent        `json:"-" gorm:"foreignKey:AgentID;constraint:OnDelete:CASCADE"`
}
