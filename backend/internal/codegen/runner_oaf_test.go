package codegen

import (
	"os"
	"path/filepath"
	"testing"

	"agent-manager/backend/internal/model"
)

func TestRunWithOAFMinimal(t *testing.T) {
	cfg := &model.OAFConfig{
		Name:        "Test Agent",
		VendorKey:   "acme",
		AgentKey:    "test",
		Version:     "1.0.0",
		Slug:        "acme/test",
		Description: "A test agent",
		Author:      "@acme",
		License:     "MIT",
		Tags:        []string{"test"},
		Instructions: "You are a test agent.",
	}

	tmpDir, err := os.MkdirTemp("", "oaf-test-*")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	oafDir := filepath.Join(tmpDir, "agent")
	if err := os.MkdirAll(oafDir, 0755); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}

	agentsMd, err := cfg.ToYAML()
	if err != nil {
		t.Fatalf("ToYAML: %v", err)
	}

	agentsMdPath := filepath.Join(oafDir, "AGENTS.md")
	if err := os.WriteFile(agentsMdPath, []byte(agentsMd), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	data, err := os.ReadFile(agentsMdPath)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}

	if len(data) == 0 {
		t.Error("AGENTS.md should not be empty")
	}

	parsed, err := model.ParseOAF(string(data))
	if err != nil {
		t.Fatalf("ParseOAF: %v", err)
	}

	if parsed.Name != cfg.Name {
		t.Errorf("Name = %q, want %q", parsed.Name, cfg.Name)
	}
}

func TestRunWithOAFFull(t *testing.T) {
	cfg := &model.OAFConfig{
		Name:        "Research Agent",
		VendorKey:   "acme",
		AgentKey:    "research",
		Version:     "1.0.0",
		Slug:        "acme/research",
		Description: "A research agent",
		Author:      "@acme",
		License:     "MIT",
		Tags:        []string{"research"},
		Skills: []model.OAFSkill{
			{Name: "web-search", Source: "local", Version: "1.0.0", Required: true},
		},
		MCPServers: []model.OAFMCPServer{
			{Vendor: "block", Server: "filesystem", Version: "1.0.0", ConfigDir: "mcp-configs/filesystem", Required: true},
		},
		Tools:        []string{"Read", "Edit", "Bash"},
		Instructions: "You are a research agent.",
	}

	tmpDir, err := os.MkdirTemp("", "oaf-test-*")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	oafDir := filepath.Join(tmpDir, "agent")
	if err := os.MkdirAll(oafDir, 0755); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}

	agentsMd, err := cfg.ToYAML()
	if err != nil {
		t.Fatalf("ToYAML: %v", err)
	}

	agentsMdPath := filepath.Join(oafDir, "AGENTS.md")
	if err := os.WriteFile(agentsMdPath, []byte(agentsMd), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	skillsDir := filepath.Join(oafDir, "skills", "web-search")
	if err := os.MkdirAll(skillsDir, 0755); err != nil {
		t.Fatalf("MkdirAll skills: %v", err)
	}
	skillMd := "---\nname: web-search\nversion: 1.0.0\n---\n"
	if err := os.WriteFile(filepath.Join(skillsDir, "SKILL.md"), []byte(skillMd), 0644); err != nil {
		t.Fatalf("WriteFile SKILL.md: %v", err)
	}

	mcpDir := filepath.Join(oafDir, "mcp-configs", "filesystem")
	if err := os.MkdirAll(mcpDir, 0755); err != nil {
		t.Fatalf("MkdirAll mcp: %v", err)
	}
	activeMCP := `{"vendor":"block","server":"filesystem","version":"1.0.0"}`
	if err := os.WriteFile(filepath.Join(mcpDir, "ActiveMCP.json"), []byte(activeMCP), 0644); err != nil {
		t.Fatalf("WriteFile ActiveMCP.json: %v", err)
	}

	data, err := os.ReadFile(agentsMdPath)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}

	parsed, err := model.ParseOAF(string(data))
	if err != nil {
		t.Fatalf("ParseOAF: %v", err)
	}

	if len(parsed.Skills) != 1 {
		t.Errorf("Skills count = %d, want 1", len(parsed.Skills))
	}
	if len(parsed.MCPServers) != 1 {
		t.Errorf("MCPServers count = %d, want 1", len(parsed.MCPServers))
	}
}

func TestCodeGenWithLocalSkills(t *testing.T) {
	cfg := &model.OAFConfig{
		Name:        "Test",
		VendorKey:   "acme",
		AgentKey:    "test",
		Version:     "1.0.0",
		Description: "Test",
		Author:      "@acme",
		License:     "MIT",
		Skills: []model.OAFSkill{
			{Name: "local-skill", Source: "local", Version: "1.0.0"},
		},
	}

	if !cfg.HasLocalSkills() {
		t.Error("HasLocalSkills() = false, want true")
	}

	local := cfg.GetLocalSkills()
	if len(local) != 1 {
		t.Errorf("GetLocalSkills() count = %d, want 1", len(local))
	}
	if local[0].Source != "local" {
		t.Errorf("Source = %q, want %q", local[0].Source, "local")
	}
}

func TestCodeGenWithMCPConfigs(t *testing.T) {
	cfg := &model.OAFConfig{
		Name:        "Test",
		VendorKey:   "acme",
		AgentKey:    "test",
		Version:     "1.0.0",
		Description: "Test",
		Author:      "@acme",
		License:     "MIT",
		MCPServers: []model.OAFMCPServer{
			{Vendor: "block", Server: "filesystem", Version: "1.0.0", ConfigDir: "mcp-configs/filesystem"},
		},
	}

	if !cfg.HasMCPServers() {
		t.Error("HasMCPServers() = false, want true")
	}

	if len(cfg.MCPServers) != 1 {
		t.Errorf("MCPServers count = %d, want 1", len(cfg.MCPServers))
	}
}

func TestCodeGenWithSubAgents(t *testing.T) {
	cfg := &model.OAFConfig{
		Name:        "Test",
		VendorKey:   "acme",
		AgentKey:    "test",
		Version:     "1.0.0",
		Description: "Test",
		Author:      "@acme",
		License:     "MIT",
		Agents: []model.OAFSubAgent{
			{Vendor: "openai", Agent: "reviewer", Version: "1.0.0", Role: "reviewer"},
		},
	}

	if !cfg.HasSubAgents() {
		t.Error("HasSubAgents() = false, want true")
	}

	if len(cfg.Agents) != 1 {
		t.Errorf("Agents count = %d, want 1", len(cfg.Agents))
	}
}

func TestOAFDirectoryStructure(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "oaf-structure-*")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	oafDir := filepath.Join(tmpDir, "agent")
	if err := os.MkdirAll(oafDir, 0755); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}

	agentsMd := `---
name: Test
vendorKey: acme
agentKey: test
version: 1.0.0
slug: acme/test
description: Test
author: "@acme"
license: MIT
tags: []
---
Test`
	if err := os.WriteFile(filepath.Join(oafDir, "AGENTS.md"), []byte(agentsMd), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	skillsDir := filepath.Join(oafDir, "skills", "web-search")
	if err := os.MkdirAll(skillsDir, 0755); err != nil {
		t.Fatalf("MkdirAll skills: %v", err)
	}

	mcpDir := filepath.Join(oafDir, "mcp-configs", "filesystem")
	if err := os.MkdirAll(mcpDir, 0755); err != nil {
		t.Fatalf("MkdirAll mcp: %v", err)
	}

	if _, err := os.Stat(filepath.Join(oafDir, "AGENTS.md")); err != nil {
		t.Error("AGENTS.md should exist")
	}
	if _, err := os.Stat(skillsDir); err != nil {
		t.Error("skills/web-search/ should exist")
	}
	if _, err := os.Stat(mcpDir); err != nil {
		t.Error("mcp-configs/filesystem/ should exist")
	}
}
