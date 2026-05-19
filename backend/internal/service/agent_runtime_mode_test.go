package service

import (
	"testing"

	"agent-manager/backend/internal/model"
)

func TestCreateWithRuntimeMode_Mount(t *testing.T) {
	db := setupAgentTestDB(t)

	svc := &AgentService{db: db}

	config := `{"name": "Mount Agent"}`

	agent, err := svc.CreateWithRuntimeMode(config, model.ConfigJSON, model.RuntimeModeMount, "agent-framework:latest", "mysql+asyncmy://test:pass@host:3307/db")
	if err != nil {
		t.Fatalf("CreateWithRuntimeMode failed: %v", err)
	}

	if agent.RuntimeMode != model.RuntimeModeMount {
		t.Errorf("RuntimeMode = %q, want %q", agent.RuntimeMode, model.RuntimeModeMount)
	}
	if agent.Image != "agent-framework:latest" {
		t.Errorf("Image = %q, want %q", agent.Image, "agent-framework:latest")
	}
	if agent.CheckpointDSN != "mysql+asyncmy://test:pass@host:3307/db" {
		t.Errorf("CheckpointDSN = %q, want %q", agent.CheckpointDSN, "mysql+asyncmy://test:pass@host:3307/db")
	}
	if agent.Status != model.StatusDraft {
		t.Errorf("Status = %q, want %q", agent.Status, model.StatusDraft)
	}
	if agent.Version != 1 {
		t.Errorf("Version = %d, want 1", agent.Version)
	}

	var saved model.Agent
	if err := db.First(&saved, agent.ID).Error; err != nil {
		t.Fatalf("Failed to load saved agent: %v", err)
	}
	if saved.RuntimeMode != model.RuntimeModeMount {
		t.Errorf("Saved RuntimeMode = %q, want %q", saved.RuntimeMode, model.RuntimeModeMount)
	}
	if saved.Image != "agent-framework:latest" {
		t.Errorf("Saved Image = %q, want %q", saved.Image, "agent-framework:latest")
	}
}

func TestCreateWithRuntimeMode_BuildDefault(t *testing.T) {
	db := setupAgentTestDB(t)

	svc := &AgentService{db: db}

	config := `{"name": "Build Agent"}`

	agent, err := svc.CreateWithRuntimeMode(config, model.ConfigJSON, model.RuntimeModeBuild, "", "")
	if err != nil {
		t.Fatalf("CreateWithRuntimeMode failed: %v", err)
	}

	if agent.RuntimeMode != model.RuntimeModeBuild {
		t.Errorf("RuntimeMode = %q, want %q", agent.RuntimeMode, model.RuntimeModeBuild)
	}
	if agent.Image != "" {
		t.Errorf("Image = %q, want empty", agent.Image)
	}
	if agent.CheckpointDSN != "" {
		t.Errorf("CheckpointDSN = %q, want empty", agent.CheckpointDSN)
	}
}

func TestUpdateWithRuntimeMode(t *testing.T) {
	db := setupAgentTestDB(t)

	svc := &AgentService{db: db}

	config := `{"name": "Update Agent"}`

	agent, err := svc.CreateWithRuntimeMode(config, model.ConfigJSON, model.RuntimeModeBuild, "", "")
	if err != nil {
		t.Fatalf("Create failed: %v", err)
	}

	newConfig := `{"name": "Updated Agent"}`

	updatedAgent, err := svc.UpdateWithRuntimeMode(agent.ID, newConfig, model.RuntimeModeMount, "agent-framework:v0.5.5", "mysql+asyncmy://new:pass@host:3307/db")
	if err != nil {
		t.Fatalf("UpdateWithRuntimeMode failed: %v", err)
	}

	if updatedAgent.RuntimeMode != model.RuntimeModeMount {
		t.Errorf("RuntimeMode = %q, want %q", updatedAgent.RuntimeMode, model.RuntimeModeMount)
	}
	if updatedAgent.Image != "agent-framework:v0.5.5" {
		t.Errorf("Image = %q, want %q", updatedAgent.Image, "agent-framework:v0.5.5")
	}
	if updatedAgent.CheckpointDSN != "mysql+asyncmy://new:pass@host:3307/db" {
		t.Errorf("CheckpointDSN = %q, want %q", updatedAgent.CheckpointDSN, "mysql+asyncmy://new:pass@host:3307/db")
	}
	if updatedAgent.Version != 2 {
		t.Errorf("Version = %d, want 2", updatedAgent.Version)
	}

	var saved model.Agent
	db.First(&saved, agent.ID)
	if saved.RuntimeMode != model.RuntimeModeMount {
		t.Errorf("Saved RuntimeMode = %q, want mount", saved.RuntimeMode)
	}
}

func TestCreateWithRuntimeMode_DefaultFallback(t *testing.T) {
	db := setupAgentTestDB(t)

	svc := &AgentService{db: db}

	config := `{"name": "fallback-agent"}`

	agent, err := svc.CreateWithRuntimeMode(config, model.ConfigJSON, model.RuntimeModeBuild, "", "custom-dsn")
	if err != nil {
		t.Fatalf("CreateWithRuntimeMode failed: %v", err)
	}

	if agent.RuntimeMode != model.RuntimeModeBuild {
		t.Errorf("RuntimeMode = %q, want %q", agent.RuntimeMode, model.RuntimeModeBuild)
	}
	if agent.CheckpointDSN != "custom-dsn" {
		t.Errorf("CheckpointDSN = %q, want %q", agent.CheckpointDSN, "custom-dsn")
	}
}
