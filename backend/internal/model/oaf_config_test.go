package model

import (
	"testing"
)

func TestParseOAFMinimal(t *testing.T) {
	content := `---
name: "Simple Assistant"
vendorKey: "acme"
agentKey: "simple"
version: "1.0.0"
slug: "acme/simple"
description: "A simple helpful assistant"
author: "@acme"
license: "MIT"
tags: ["assistant"]
---
I am a simple helpful assistant.`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	if cfg.Name != "Simple Assistant" {
		t.Errorf("Name = %q, want %q", cfg.Name, "Simple Assistant")
	}
	if cfg.VendorKey != "acme" {
		t.Errorf("VendorKey = %q, want %q", cfg.VendorKey, "acme")
	}
	if cfg.AgentKey != "simple" {
		t.Errorf("AgentKey = %q, want %q", cfg.AgentKey, "simple")
	}
	if cfg.Version != "1.0.0" {
		t.Errorf("Version = %q, want %q", cfg.Version, "1.0.0")
	}
	if cfg.Description != "A simple helpful assistant" {
		t.Errorf("Description = %q, want %q", cfg.Description, "A simple helpful assistant")
	}
	if cfg.Instructions != "I am a simple helpful assistant." {
		t.Errorf("Instructions = %q, want %q", cfg.Instructions, "I am a simple helpful assistant.")
	}
}

func TestParseOAFFull(t *testing.T) {
	content := `---
name: "Research Assistant"
vendorKey: "acme"
agentKey: "research"
version: "1.0.0"
slug: "acme/research"
description: "A research assistant"
author: "@acme"
license: "MIT"
tags: ["research", "web-search"]

skills:
  - name: "web-search"
    source: "local"
    version: "1.0.0"
    required: true
  - name: "pdf-reader"
    source: "https://example.com/.well-known/skills/pdf-reader"
    version: "2.0.0"
    required: false

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: true

agents:
  - vendor: "openai"
    agent: "code-reviewer"
    version: "1.5.0"
    role: "reviewer"
    delegations: ["code-quality", "security-check"]
    required: false

tools: ["Read", "Edit", "Bash", "Glob", "Grep"]

model:
  provider: "ctyun"
  name: "gpt-4-model-id-example"

config:
  temperature: 0.7
  max_tokens: 4096
---
# Agent Purpose

You are a research assistant.`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	if len(cfg.Skills) != 2 {
		t.Errorf("Skills count = %d, want 2", len(cfg.Skills))
	}
	if cfg.Skills[0].Source != "local" {
		t.Errorf("Skills[0].Source = %q, want %q", cfg.Skills[0].Source, "local")
	}
	if cfg.Skills[1].Source != "https://example.com/.well-known/skills/pdf-reader" {
		t.Errorf("Skills[1].Source = %q, want well-known URL", cfg.Skills[1].Source)
	}

	if len(cfg.MCPServers) != 1 {
		t.Errorf("MCPServers count = %d, want 1", len(cfg.MCPServers))
	}
	if cfg.MCPServers[0].Vendor != "block" {
		t.Errorf("MCPServers[0].Vendor = %q, want %q", cfg.MCPServers[0].Vendor, "block")
	}

	if len(cfg.Agents) != 1 {
		t.Errorf("Agents count = %d, want 1", len(cfg.Agents))
	}
	if cfg.Agents[0].Role != "reviewer" {
		t.Errorf("Agents[0].Role = %q, want %q", cfg.Agents[0].Role, "reviewer")
	}

	if len(cfg.Tools) != 5 {
		t.Errorf("Tools count = %d, want 5", len(cfg.Tools))
	}

	if cfg.ModelObj == nil {
		t.Error("ModelObj should not be nil")
	}
	if cfg.ModelObj.Provider != "ctyun" {
		t.Errorf("ModelObj.Provider = %q, want %q", cfg.ModelObj.Provider, "ctyun")
	}

	if cfg.Config == nil {
		t.Error("Config should not be nil")
	}
	if cfg.Config.Temperature != 0.7 {
		t.Errorf("Config.Temperature = %f, want 0.7", cfg.Config.Temperature)
	}
}

func TestParseOAFWithInstructions(t *testing.T) {
	content := `---
name: "Test Agent"
vendorKey: "test"
agentKey: "agent"
version: "1.0.0"
slug: "test/agent"
description: "Test"
author: "@test"
license: "MIT"
tags: []
---
# Agent Purpose

This is a multi-line
instruction block.

## Section

More content here.`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	if !containsStr(cfg.Instructions, "# Agent Purpose") {
		t.Errorf("Instructions should contain '# Agent Purpose'")
	}
	if !containsStr(cfg.Instructions, "## Section") {
		t.Errorf("Instructions should contain '## Section'")
	}
}

func containsStr(s, sub string) bool {
	return len(s) >= len(sub) && (s[:len(sub)] == sub || containsStr(s[1:], sub))
}

func TestValidateOAFRequired(t *testing.T) {
	tests := []struct {
		name    string
		config  *OAFConfig
		wantErr bool
	}{
		{
			name: "missing name",
			config: &OAFConfig{
				VendorKey:   "acme",
				AgentKey:    "test",
				Version:     "1.0.0",
				Description: "Test",
				Author:      "@acme",
				License:     "MIT",
			},
			wantErr: true,
		},
		{
			name: "missing vendorKey",
			config: &OAFConfig{
				Name:        "Test",
				AgentKey:    "test",
				Version:     "1.0.0",
				Description: "Test",
				Author:      "@acme",
				License:     "MIT",
			},
			wantErr: true,
		},
		{
			name: "missing agentKey",
			config: &OAFConfig{
				Name:        "Test",
				VendorKey:   "acme",
				Version:     "1.0.0",
				Description: "Test",
				Author:      "@acme",
				License:     "MIT",
			},
			wantErr: true,
		},
		{
			name: "missing version",
			config: &OAFConfig{
				Name:        "Test",
				VendorKey:   "acme",
				AgentKey:    "test",
				Description: "Test",
				Author:      "@acme",
				License:     "MIT",
			},
			wantErr: true,
		},
		{
			name: "missing description",
			config: &OAFConfig{
				Name:      "Test",
				VendorKey: "acme",
				AgentKey:  "test",
				Version:   "1.0.0",
				Author:    "@acme",
				License:   "MIT",
			},
			wantErr: true,
		},
		{
			name: "valid config",
			config: &OAFConfig{
				Name:        "Test",
				VendorKey:   "acme",
				AgentKey:    "test",
				Version:     "1.0.0",
				Description: "Test",
				Author:      "@acme",
				License:     "MIT",
			},
			wantErr: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.config.Validate()
			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestOAFSkillLocalSource(t *testing.T) {
	content := `---
name: "Test"
vendorKey: "acme"
agentKey: "test"
version: "1.0.0"
slug: "acme/test"
description: "Test"
author: "@acme"
license: "MIT"
tags: []
skills:
  - name: "local-skill"
    source: "local"
    version: "1.0.0"
    required: true
---
Test`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	if !cfg.HasLocalSkills() {
		t.Error("HasLocalSkills() = false, want true")
	}
	if cfg.HasRemoteSkills() {
		t.Error("HasRemoteSkills() = true, want false")
	}

	local := cfg.GetLocalSkills()
	if len(local) != 1 {
		t.Errorf("GetLocalSkills() count = %d, want 1", len(local))
	}
}

func TestOAFSkillWellKnownURL(t *testing.T) {
	content := `---
name: "Test"
vendorKey: "acme"
agentKey: "test"
version: "1.0.0"
slug: "acme/test"
description: "Test"
author: "@acme"
license: "MIT"
tags: []
skills:
  - name: "remote-skill"
    source: "https://example.com/.well-known/skills/remote-skill"
    version: "1.0.0"
    required: true
---
Test`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	if cfg.HasLocalSkills() {
		t.Error("HasLocalSkills() = true, want false")
	}
	if !cfg.HasRemoteSkills() {
		t.Error("HasRemoteSkills() = false, want true")
	}

	remote := cfg.GetRemoteSkills()
	if len(remote) != 1 {
		t.Errorf("GetRemoteSkills() count = %d, want 1", len(remote))
	}
}

func TestOAFSlugAutoGenerate(t *testing.T) {
	content := `---
name: "Test"
vendorKey: "mycompany"
agentKey: "my-agent"
version: "1.0.0"
description: "Test"
author: "@test"
license: "MIT"
tags: []
---
Test`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	expectedSlug := "mycompany/my-agent"
	if cfg.Slug != expectedSlug {
		t.Errorf("Slug = %q, want %q", cfg.Slug, expectedSlug)
	}
}

func TestParseOAFModelAlias(t *testing.T) {
	content := `---
name: "Test"
vendorKey: "acme"
agentKey: "test"
version: "1.0.0"
slug: "acme/test"
description: "Test"
author: "@acme"
license: "MIT"
tags: []
model: "sonnet"
---
Test`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	if cfg.ModelAlias != "sonnet" {
		t.Errorf("ModelAlias = %q, want %q", cfg.ModelAlias, "sonnet")
	}
	if cfg.ModelObj != nil {
		t.Error("ModelObj should be nil for alias")
	}
}

func TestParseOAFModelFull(t *testing.T) {
	content := `---
name: "Test"
vendorKey: "acme"
agentKey: "test"
version: "1.0.0"
slug: "acme/test"
description: "Test"
author: "@acme"
license: "MIT"
tags: []
model:
  provider: "anthropic"
  name: "claude-sonnet-4-5"
  embedding: "voyage-2"
---
Test`

	cfg, err := ParseOAF(content)
	if err != nil {
		t.Fatalf("ParseOAF failed: %v", err)
	}

	if cfg.ModelObj == nil {
		t.Fatal("ModelObj should not be nil")
	}
	if cfg.ModelObj.Provider != "anthropic" {
		t.Errorf("ModelObj.Provider = %q, want %q", cfg.ModelObj.Provider, "anthropic")
	}
	if cfg.ModelObj.Name != "claude-sonnet-4-5" {
		t.Errorf("ModelObj.Name = %q, want %q", cfg.ModelObj.Name, "claude-sonnet-4-5")
	}
	if cfg.ModelObj.Embedding != "voyage-2" {
		t.Errorf("ModelObj.Embedding = %q, want %q", cfg.ModelObj.Embedding, "voyage-2")
	}
}

func TestOAFToYAML(t *testing.T) {
	cfg := &OAFConfig{
		Name:        "Test Agent",
		VendorKey:   "acme",
		AgentKey:    "test",
		Version:     "1.0.0",
		Slug:        "acme/test",
		Description: "A test agent",
		Author:      "@acme",
		License:     "MIT",
		Tags:        []string{"test"},
		Instructions: "# Purpose\n\nTest instructions.",
	}

	yaml, err := cfg.ToYAML()
	if err != nil {
		t.Fatalf("ToYAML failed: %v", err)
	}

	if !containsStr(yaml, "---") {
		t.Error("YAML should contain frontmatter delimiters")
	}
	if !containsStr(yaml, "name: Test Agent") {
		t.Error("YAML should contain name field")
	}
	if !containsStr(yaml, "# Purpose") {
		t.Error("YAML should contain instructions")
	}
}

func TestJSONToOAF(t *testing.T) {
	cfg := &OAFConfig{
		Name:        "Test Agent",
		VendorKey:   "acme",
		AgentKey:    "test",
		Version:     "1.0.0",
		Slug:        "acme/test",
		Description: "A test agent",
		Author:      "@acme",
		License:     "MIT",
		Tags:        []string{"test"},
	}

	json, err := cfg.ToJSON()
	if err != nil {
		t.Fatalf("ToJSON failed: %v", err)
	}

	parsed, err := OAFFromJSON(json)
	if err != nil {
		t.Fatalf("OAFFromJSON failed: %v", err)
	}

	if parsed.Name != cfg.Name {
		t.Errorf("Name = %q, want %q", parsed.Name, cfg.Name)
	}
	if parsed.VendorKey != cfg.VendorKey {
		t.Errorf("VendorKey = %q, want %q", parsed.VendorKey, cfg.VendorKey)
	}
}

func TestOAFHasMCPServers(t *testing.T) {
	cfg := &OAFConfig{
		MCPServers: []OAFMCPServer{
			{Vendor: "block", Server: "filesystem"},
		},
	}

	if !cfg.HasMCPServers() {
		t.Error("HasMCPServers() = false, want true")
	}

	cfg2 := &OAFConfig{}
	if cfg2.HasMCPServers() {
		t.Error("HasMCPServers() = true for empty config, want false")
	}
}

func TestOAFHasSubAgents(t *testing.T) {
	cfg := &OAFConfig{
		Agents: []OAFSubAgent{
			{Vendor: "openai", Agent: "reviewer"},
		},
	}

	if !cfg.HasSubAgents() {
		t.Error("HasSubAgents() = false, want true")
	}

	cfg2 := &OAFConfig{}
	if cfg2.HasSubAgents() {
		t.Error("HasSubAgents() = true for empty config, want false")
	}
}

func TestValidateKebabCase(t *testing.T) {
	tests := []struct {
		name      string
		vendorKey string
		agentKey  string
		wantErr   bool
	}{
		{"valid", "acme", "my-agent", false},
		{"invalid vendor uppercase", "Acme", "my-agent", true},
		{"invalid vendor underscore", "ac_me", "my-agent", true},
		{"invalid agent uppercase", "acme", "MyAgent", true},
		{"valid with numbers", "acme2", "agent-123", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := &OAFConfig{
				Name:        "Test",
				VendorKey:   tt.vendorKey,
				AgentKey:    tt.agentKey,
				Version:     "1.0.0",
				Description: "Test",
				Author:      "@test",
				License:     "MIT",
			}
			err := cfg.Validate()
			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestValidateSemver(t *testing.T) {
	tests := []struct {
		name    string
		version string
		wantErr bool
	}{
		{"valid", "1.0.0", false},
		{"valid with prerelease", "1.0.0-beta", false},
		{"valid with prerelease dot", "1.2.3-alpha.1", false},
		{"invalid missing patch", "1.0", true},
		{"invalid letters", "v1.0.0", true},
		{"invalid empty", "", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := &OAFConfig{
				Name:        "Test",
				VendorKey:   "acme",
				AgentKey:    "test",
				Version:     tt.version,
				Description: "Test",
				Author:      "@test",
				License:     "MIT",
			}
			err := cfg.Validate()
			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}
