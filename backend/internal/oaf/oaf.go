// Package oaf 提供 OAF v0.8.0 配置包的解析与校验。
// 平移自 v1 backend/internal/model/oaf_config.go，ParseOAF/Validate 语义不变。
package oaf

import (
	"errors"
	"fmt"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

// Config 对应 AGENTS.md frontmatter 的完整结构。
type Config struct {
	// Identity（必填）
	Name      string `yaml:"name" json:"name"`
	VendorKey string `yaml:"vendorKey" json:"vendorKey"`
	AgentKey  string `yaml:"agentKey" json:"agentKey"`
	Version   string `yaml:"version" json:"version"`
	Slug      string `yaml:"slug" json:"slug"`

	// Metadata（必填）
	Description string   `yaml:"description" json:"description"`
	Author      string   `yaml:"author" json:"author"`
	License     string   `yaml:"license" json:"license"`
	Tags        []string `yaml:"tags,omitempty" json:"tags,omitempty"`

	Skills     []Skill     `yaml:"skills,omitempty" json:"skills,omitempty"`
	Packs      []Pack      `yaml:"packs,omitempty" json:"packs,omitempty"`
	Weblets    []Weblet    `yaml:"weblets,omitempty" json:"weblets,omitempty"`
	MCPServers []MCPServer `yaml:"mcpServers,omitempty" json:"mcpServers,omitempty"`
	Agents     []SubAgent  `yaml:"agents,omitempty" json:"agents,omitempty"`

	Tools         []string               `yaml:"tools,omitempty" json:"tools,omitempty"`
	Model         interface{}            `yaml:"model,omitempty" json:"-"`
	Config        *RuntimeConfig         `yaml:"config,omitempty" json:"config,omitempty"`
	Memory        *Memory                `yaml:"memory,omitempty" json:"memory,omitempty"`
	HarnessConfig map[string]interface{} `yaml:"harnessConfig,omitempty" json:"harnessConfig,omitempty"`

	Instructions string `yaml:"-" json:"instructions,omitempty"`
}

type Skill struct {
	Name     string `yaml:"name" json:"name"`
	Source   string `yaml:"source" json:"source"`
	Version  string `yaml:"version" json:"version"`
	Required bool   `yaml:"required" json:"required"`
}

type Pack struct {
	Vendor   string `yaml:"vendor" json:"vendor"`
	Pack     string `yaml:"pack" json:"pack"`
	Version  string `yaml:"version" json:"version"`
	Required bool   `yaml:"required" json:"required"`
}

type Weblet struct {
	Vendor  string `yaml:"vendor" json:"vendor"`
	Weblet  string `yaml:"weblet" json:"weblet"`
	Version string `yaml:"version" json:"version"`
	Launch  string `yaml:"launch" json:"launch"`
}

type MCPServer struct {
	Vendor    string `yaml:"vendor" json:"vendor"`
	Server    string `yaml:"server" json:"server"`
	Version   string `yaml:"version" json:"version"`
	ConfigDir string `yaml:"configDir" json:"configDir"`
	Required  bool   `yaml:"required" json:"required"`
}

type SubAgent struct {
	Vendor      string   `yaml:"vendor" json:"vendor"`
	Agent       string   `yaml:"agent" json:"agent"`
	Version     string   `yaml:"version" json:"version"`
	Role        string   `yaml:"role" json:"role"`
	Delegations []string `yaml:"delegations,omitempty" json:"delegations,omitempty"`
	Required    bool     `yaml:"required" json:"required"`
}

type RuntimeConfig struct {
	Temperature         float64 `yaml:"temperature,omitempty" json:"temperature,omitempty"`
	MaxTokens           int     `yaml:"max_tokens,omitempty" json:"max_tokens,omitempty"`
	RequireConfirmation bool    `yaml:"require_confirmation,omitempty" json:"require_confirmation,omitempty"`
}

type Memory struct {
	Type   string            `yaml:"type" json:"type"`
	Blocks map[string]string `yaml:"blocks,omitempty" json:"blocks,omitempty"`
}

var (
	kebabRe  = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
	semverRe = regexp.MustCompile(`^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$`)
)

// ParseOAF 解析 AGENTS.md（YAML frontmatter + Markdown 正文）。
func ParseOAF(content string) (*Config, error) {
	parts := strings.SplitN(content, "---", 3)
	if len(parts) < 3 {
		return nil, errors.New("invalid AGENTS.md: missing YAML frontmatter")
	}
	frontmatter := strings.TrimSpace(parts[1])

	var cfg Config
	if err := yaml.Unmarshal([]byte(frontmatter), &cfg); err != nil {
		return nil, fmt.Errorf("parse frontmatter: %w", err)
	}
	cfg.Instructions = strings.TrimSpace(parts[2])
	if cfg.Slug == "" && cfg.VendorKey != "" && cfg.AgentKey != "" {
		cfg.Slug = cfg.VendorKey + "/" + cfg.AgentKey
	}
	return &cfg, nil
}

// Validate 强制校验：identity 五字段 + metadata 四字段、kebab-case、semver。
func (c *Config) Validate() error {
	for _, f := range []struct{ k, v string }{
		{"name", c.Name}, {"vendorKey", c.VendorKey}, {"agentKey", c.AgentKey},
		{"version", c.Version}, {"description", c.Description},
		{"author", c.Author}, {"license", c.License},
	} {
		if f.v == "" {
			return fmt.Errorf("%s is required", f.k)
		}
	}
	if !kebabRe.MatchString(c.VendorKey) {
		return fmt.Errorf("vendorKey must be kebab-case: %s", c.VendorKey)
	}
	if !kebabRe.MatchString(c.AgentKey) {
		return fmt.Errorf("agentKey must be kebab-case: %s", c.AgentKey)
	}
	if !semverRe.MatchString(c.Version) {
		return fmt.Errorf("version must be semver: %s", c.Version)
	}
	return nil
}

// Warnings 宽松模式检查：引用缺失仅提示，不阻断上传与发布。
func (c *Config) Warnings() []string {
	var w []string
	for _, m := range c.MCPServers {
		if m.ConfigDir == "" {
			w = append(w, fmt.Sprintf("mcpServer %q has empty configDir", m.Server))
		}
	}
	for _, s := range c.Skills {
		if s.Source == "local" && s.Name == "" {
			w = append(w, "skill entry with empty local name")
		}
	}
	return w
}
