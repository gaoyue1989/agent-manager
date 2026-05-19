package model

import (
	"encoding/json"
	"testing"
)

func TestRuntimeModeDefaults(t *testing.T) {
	agent := &Agent{}
	// Go zero value for string is "", not RuntimeModeBuild
	// GORM default 'build' only applies at DB insert time
	if agent.RuntimeMode != RuntimeModeBuild && agent.RuntimeMode != "" {
		t.Errorf("RuntimeMode = %q, want empty or %q", agent.RuntimeMode, RuntimeModeBuild)
	}
	if agent.Image != "" {
		t.Errorf("Image = %q, want empty", agent.Image)
	}
	if agent.CheckpointDSN != "" {
		t.Errorf("CheckpointDSN = %q, want empty", agent.CheckpointDSN)
	}
}

func TestRuntimeModeJSON(t *testing.T) {
	agent := Agent{
		Name:          "mount-agent",
		Config:        `{}`,
		ConfigType:    ConfigOAF,
		RuntimeMode:   RuntimeModeMount,
		Image:         "agent-framework:latest",
		CheckpointDSN: "mysql+asyncmy://user:pass@host:3307/db",
		Status:        StatusDraft,
		Version:       1,
	}

	data, err := json.Marshal(agent)
	if err != nil {
		t.Fatalf("Marshal failed: %v", err)
	}

	var decoded Agent
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("Unmarshal failed: %v", err)
	}

	if decoded.RuntimeMode != RuntimeModeMount {
		t.Errorf("RuntimeMode = %q, want %q", decoded.RuntimeMode, RuntimeModeMount)
	}
	if decoded.Image != "agent-framework:latest" {
		t.Errorf("Image = %q, want %q", decoded.Image, "agent-framework:latest")
	}
	if decoded.CheckpointDSN != "mysql+asyncmy://user:pass@host:3307/db" {
		t.Errorf("CheckpointDSN = %q, want %q", decoded.CheckpointDSN, "mysql+asyncmy://user:pass@host:3307/db")
	}
}

func TestRuntimeModeEnum(t *testing.T) {
	if RuntimeModeBuild != "build" {
		t.Errorf("RuntimeModeBuild = %q, want 'build'", RuntimeModeBuild)
	}
	if RuntimeModeMount != "mount" {
		t.Errorf("RuntimeModeMount = %q, want 'mount'", RuntimeModeMount)
	}
}

func TestAgentRuntimeModeParsing(t *testing.T) {
	jsonStr := `{
		"name": "test-agent",
		"config": "{}",
		"config_type": "oaf",
		"runtime_mode": "mount",
		"image": "agent-framework:latest",
		"checkpoint_dsn": "mysql+asyncmy://u:p@h:3307/d",
		"status": "draft",
		"version": 1
	}`

	var agent Agent
	if err := json.Unmarshal([]byte(jsonStr), &agent); err != nil {
		t.Fatalf("Unmarshal failed: %v", err)
	}

	if agent.RuntimeMode != RuntimeModeMount {
		t.Errorf("RuntimeMode = %q, want 'mount'", agent.RuntimeMode)
	}
	if agent.Image != "agent-framework:latest" {
		t.Errorf("Image = %q, want 'agent-framework:latest'", agent.Image)
	}
	if agent.CheckpointDSN != "mysql+asyncmy://u:p@h:3307/d" {
		t.Errorf("CheckpointDSN = %q, want 'mysql+asyncmy://u:p@h:3307/d'", agent.CheckpointDSN)
	}
}

func TestAgentRuntimeModeBuildDefault(t *testing.T) {
	jsonStr := `{
		"name": "build-agent",
		"config": "{}",
		"config_type": "oaf",
		"status": "draft",
		"version": 1
	}`

	var agent Agent
	if err := json.Unmarshal([]byte(jsonStr), &agent); err != nil {
		t.Fatalf("Unmarshal failed: %v", err)
	}

	// JSON unmarshal sets empty string for missing fields, not GORM defaults
	if agent.RuntimeMode != "" {
		t.Errorf("RuntimeMode = %q, want empty string", agent.RuntimeMode)
	}
	if agent.Image != "" {
		t.Errorf("Image = %q, want empty", agent.Image)
	}
}
