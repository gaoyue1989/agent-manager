package model

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

type OAFConfig struct {
	Name      string   `yaml:"name" json:"name"`
	VendorKey string   `yaml:"vendorKey" json:"vendorKey"`
	AgentKey  string   `yaml:"agentKey" json:"agentKey"`
	Version   string   `yaml:"version" json:"version"`
	Slug      string   `yaml:"slug" json:"slug"`

	Description string   `yaml:"description" json:"description"`
	Author      string   `yaml:"author" json:"author"`
	License     string   `yaml:"license" json:"license"`
	Tags        []string `yaml:"tags" json:"tags"`

	Skills     []OAFSkill     `yaml:"skills,omitempty" json:"skills,omitempty"`
	Packs      []OAFPack      `yaml:"packs,omitempty" json:"packs,omitempty"`
	Weblets    []OAFWeblet    `yaml:"weblets,omitempty" json:"weblets,omitempty"`
	MCPServers []OAFMCPServer `yaml:"mcpServers,omitempty" json:"mcpServers,omitempty"`
	Agents     []OAFSubAgent  `yaml:"agents,omitempty" json:"agents,omitempty"`

	Orchestration *OAFOrchestration `yaml:"orchestration,omitempty" json:"orchestration,omitempty"`

	Tools []string `yaml:"tools,omitempty" json:"tools,omitempty"`

	Model      interface{}        `yaml:"model,omitempty" json:"model,omitempty"`
	ModelObj   *OAFModel          `yaml:"-" json:"-"`
	ModelAlias string             `yaml:"-" json:"-"`
	Config     *OAFRuntimeConfig  `yaml:"config,omitempty" json:"config,omitempty"`
	Memory     *OAFMemory         `yaml:"memory,omitempty" json:"memory,omitempty"`

	HarnessConfig map[string]interface{} `yaml:"harnessConfig,omitempty" json:"harnessConfig,omitempty"`

	Instructions string `yaml:"-" json:"instructions,omitempty"`
}

type OAFSkill struct {
	Name     string `yaml:"name" json:"name"`
	Source   string `yaml:"source" json:"source"`
	Version  string `yaml:"version" json:"version"`
	Required bool   `yaml:"required" json:"required"`
}

type OAFPack struct {
	Vendor   string `yaml:"vendor" json:"vendor"`
	Pack     string `yaml:"pack" json:"pack"`
	Version  string `yaml:"version" json:"version"`
	Required bool   `yaml:"required" json:"required"`
}

type OAFWeblet struct {
	Vendor   string `yaml:"vendor" json:"vendor"`
	Weblet   string `yaml:"weblet" json:"weblet"`
	Version  string `yaml:"version" json:"version"`
	Launch   string `yaml:"launch" json:"launch"`
}

type OAFMCPServer struct {
	Vendor    string `yaml:"vendor" json:"vendor"`
	Server    string `yaml:"server" json:"server"`
	Version   string `yaml:"version" json:"version"`
	ConfigDir string `yaml:"configDir" json:"configDir"`
	Required  bool   `yaml:"required" json:"required"`
}

type OAFSubAgent struct {
	Vendor      string   `yaml:"vendor" json:"vendor"`
	Agent       string   `yaml:"agent" json:"agent"`
	Version     string   `yaml:"version" json:"version"`
	Role        string   `yaml:"role" json:"role"`
	Delegations []string `yaml:"delegations,omitempty" json:"delegations,omitempty"`
	Required    bool     `yaml:"required" json:"required"`
}

type OAFOrchestration struct {
	Entrypoint string        `yaml:"entrypoint" json:"entrypoint"`
	Fallback   string        `yaml:"fallback,omitempty" json:"fallback,omitempty"`
	Triggers   []OAFTrigger  `yaml:"triggers,omitempty" json:"triggers,omitempty"`
}

type OAFTrigger struct {
	Event  string `yaml:"event" json:"event"`
	Action string `yaml:"action" json:"action"`
}

type OAFModel struct {
	Provider  string `yaml:"provider" json:"provider"`
	Name      string `yaml:"name" json:"name"`
	Embedding string `yaml:"embedding,omitempty" json:"embedding,omitempty"`
}

type OAFRuntimeConfig struct {
	Temperature         float64 `yaml:"temperature,omitempty" json:"temperature,omitempty"`
	MaxTokens           int     `yaml:"max_tokens,omitempty" json:"max_tokens,omitempty"`
	RequireConfirmation bool    `yaml:"require_confirmation,omitempty" json:"require_confirmation,omitempty"`
}

type OAFMemory struct {
	Type   string            `yaml:"type" json:"type"`
	Blocks map[string]string `yaml:"blocks,omitempty" json:"blocks,omitempty"`
}

func ParseOAF(content string) (*OAFConfig, error) {
	parts := strings.SplitN(content, "---", 3)
	if len(parts) < 3 {
		return nil, errors.New("invalid AGENTS.md: missing YAML frontmatter")
	}

	frontmatter := strings.TrimSpace(parts[1])
	instructions := strings.TrimSpace(parts[2])

	var cfg OAFConfig
	if err := yaml.Unmarshal([]byte(frontmatter), &cfg); err != nil {
		return nil, fmt.Errorf("parse frontmatter: %w", err)
	}

	cfg.Instructions = instructions

	if err := cfg.parseModelField(); err != nil {
		return nil, err
	}

	if cfg.Slug == "" && cfg.VendorKey != "" && cfg.AgentKey != "" {
		cfg.Slug = cfg.VendorKey + "/" + cfg.AgentKey
	}

	return &cfg, nil
}

func (c *OAFConfig) parseModelField() error {
	if c.Model == nil {
		return nil
	}

	switch v := c.Model.(type) {
	case string:
		c.ModelAlias = v
	case map[string]interface{}:
		data, err := yaml.Marshal(v)
		if err != nil {
			return fmt.Errorf("marshal model: %w", err)
		}
		var model OAFModel
		if err := yaml.Unmarshal(data, &model); err != nil {
			return fmt.Errorf("parse model object: %w", err)
		}
		c.ModelObj = &model
	default:
		return fmt.Errorf("invalid model type: %T", v)
	}

	return nil
}

func (c *OAFConfig) Validate() error {
	if c.Name == "" {
		return errors.New("name is required")
	}
	if c.VendorKey == "" {
		return errors.New("vendorKey is required")
	}
	if c.AgentKey == "" {
		return errors.New("agentKey is required")
	}
	if c.Version == "" {
		return errors.New("version is required")
	}
	if c.Description == "" {
		return errors.New("description is required")
	}
	if c.Author == "" {
		return errors.New("author is required")
	}
	if c.License == "" {
		return errors.New("license is required")
	}

	kebabRegex := regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
	if !kebabRegex.MatchString(c.VendorKey) {
		return fmt.Errorf("vendorKey must be kebab-case: %s", c.VendorKey)
	}
	if !kebabRegex.MatchString(c.AgentKey) {
		return fmt.Errorf("agentKey must be kebab-case: %s", c.AgentKey)
	}

	semverRegex := regexp.MustCompile(`^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$`)
	if !semverRegex.MatchString(c.Version) {
		return fmt.Errorf("version must be semver: %s", c.Version)
	}

	return nil
}

func (c *OAFConfig) ToYAML() (string, error) {
	data, err := yaml.Marshal(c)
	if err != nil {
		return "", err
	}

	if c.Instructions != "" {
		return fmt.Sprintf("---\n%s---\n\n%s", string(data), c.Instructions), nil
	}
	return fmt.Sprintf("---\n%s---", string(data)), nil
}

func (c *OAFConfig) ToJSON() ([]byte, error) {
	type jsonConfig struct {
		Name        string                 `json:"name"`
		VendorKey   string                 `json:"vendorKey"`
		AgentKey    string                 `json:"agentKey"`
		Version     string                 `json:"version"`
		Slug        string                 `json:"slug"`
		Description string                 `json:"description"`
		Author      string                 `json:"author"`
		License     string                 `json:"license"`
		Tags        []string               `json:"tags"`
		Skills      []OAFSkill             `json:"skills,omitempty"`
		Packs       []OAFPack              `json:"packs,omitempty"`
		Weblets     []OAFWeblet            `json:"weblets,omitempty"`
		MCPServers  []OAFMCPServer         `json:"mcpServers,omitempty"`
		Agents      []OAFSubAgent          `json:"agents,omitempty"`
		Tools       []string               `json:"tools,omitempty"`
		ModelObj    *OAFModel              `json:"modelObj,omitempty"`
		ModelAlias  string                 `json:"modelAlias,omitempty"`
		Config      *OAFRuntimeConfig      `json:"config,omitempty"`
		Memory      *OAFMemory             `json:"memory,omitempty"`
		Instructions string                `json:"instructions,omitempty"`
	}
	jc := jsonConfig{
		Name:         c.Name,
		VendorKey:    c.VendorKey,
		AgentKey:     c.AgentKey,
		Version:      c.Version,
		Slug:         c.Slug,
		Description:  c.Description,
		Author:       c.Author,
		License:      c.License,
		Tags:         c.Tags,
		Skills:       c.Skills,
		Packs:        c.Packs,
		Weblets:      c.Weblets,
		MCPServers:   c.MCPServers,
		Agents:       c.Agents,
		Tools:        c.Tools,
		ModelObj:     c.ModelObj,
		ModelAlias:   c.ModelAlias,
		Config:       c.Config,
		Memory:       c.Memory,
		Instructions: c.Instructions,
	}
	return json.Marshal(jc)
}

func OAFFromJSON(data []byte) (*OAFConfig, error) {
	type jsonConfig struct {
		Name         string                 `json:"name"`
		VendorKey    string                 `json:"vendorKey"`
		AgentKey     string                 `json:"agentKey"`
		Version      string                 `json:"version"`
		Slug         string                 `json:"slug"`
		Description  string                 `json:"description"`
		Author       string                 `json:"author"`
		License      string                 `json:"license"`
		Tags         []string               `json:"tags"`
		Skills       []OAFSkill             `json:"skills,omitempty"`
		Packs        []OAFPack              `json:"packs,omitempty"`
		Weblets      []OAFWeblet            `json:"weblets,omitempty"`
		MCPServers   []OAFMCPServer         `json:"mcpServers,omitempty"`
		Agents       []OAFSubAgent          `json:"agents,omitempty"`
		Tools        []string               `json:"tools,omitempty"`
		ModelObj     *OAFModel              `json:"modelObj,omitempty"`
		ModelAlias   string                 `json:"modelAlias,omitempty"`
		Config       *OAFRuntimeConfig      `json:"config,omitempty"`
		Memory       *OAFMemory             `json:"memory,omitempty"`
		Instructions string                 `json:"instructions,omitempty"`
	}
	var jc jsonConfig
	if err := json.Unmarshal(data, &jc); err != nil {
		return nil, err
	}
	cfg := &OAFConfig{
		Name:         jc.Name,
		VendorKey:    jc.VendorKey,
		AgentKey:     jc.AgentKey,
		Version:      jc.Version,
		Slug:         jc.Slug,
		Description:  jc.Description,
		Author:       jc.Author,
		License:      jc.License,
		Tags:         jc.Tags,
		Skills:       jc.Skills,
		Packs:        jc.Packs,
		Weblets:      jc.Weblets,
		MCPServers:   jc.MCPServers,
		Agents:       jc.Agents,
		Tools:        jc.Tools,
		ModelObj:     jc.ModelObj,
		ModelAlias:   jc.ModelAlias,
		Config:       jc.Config,
		Memory:       jc.Memory,
		Instructions: jc.Instructions,
	}
	if cfg.ModelObj != nil {
		cfg.Model = cfg.ModelObj
	} else if cfg.ModelAlias != "" {
		cfg.Model = cfg.ModelAlias
	}
	return cfg, nil
}

func (c *OAFConfig) HasLocalSkills() bool {
	for _, s := range c.Skills {
		if s.Source == "local" {
			return true
		}
	}
	return false
}

func (c *OAFConfig) HasRemoteSkills() bool {
	for _, s := range c.Skills {
		if s.Source != "" && s.Source != "local" {
			return true
		}
	}
	return false
}

func (c *OAFConfig) HasMCPServers() bool {
	return len(c.MCPServers) > 0
}

func (c *OAFConfig) HasSubAgents() bool {
	return len(c.Agents) > 0
}

func (c *OAFConfig) GetLocalSkills() []OAFSkill {
	var local []OAFSkill
	for _, s := range c.Skills {
		if s.Source == "local" {
			local = append(local, s)
		}
	}
	return local
}

func (c *OAFConfig) GetRemoteSkills() []OAFSkill {
	var remote []OAFSkill
	for _, s := range c.Skills {
		if s.Source != "" && s.Source != "local" {
			remote = append(remote, s)
		}
	}
	return remote
}
