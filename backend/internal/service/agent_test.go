package service

import (
	"testing"

	"agent-manager/backend/internal/model"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
)

func setupAgentTestDB(t *testing.T) *gorm.DB {
	dsn := "agent_manager:Agent@Manager2026@tcp(127.0.0.1:3307)/agent_manager_test?charset=utf8mb4&parseTime=True&loc=Local"
	db, err := gorm.Open(mysql.Open(dsn), &gorm.Config{
		DisableForeignKeyConstraintWhenMigrating: true,
	})
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	db.Migrator().DropTable(&model.Deployment{}, &model.ImageBuild{}, &model.CodeGeneration{}, &model.Agent{})
	if err := db.AutoMigrate(&model.Agent{}, &model.CodeGeneration{}, &model.ImageBuild{}, &model.Deployment{}); err != nil {
		t.Fatalf("failed to migrate: %v", err)
	}
	t.Cleanup(func() {
		db.Migrator().DropTable(&model.Deployment{}, &model.ImageBuild{}, &model.CodeGeneration{}, &model.Agent{})
	})
	return db
}

func TestDeleteWithCleanup_Draft(t *testing.T) {
	db := setupAgentTestDB(t)

	agent := &model.Agent{Name: "test-draft", Config: `{}`, Status: model.StatusDraft, Version: 1}
	db.Create(agent)

	svc := &AgentService{db: db}

	result, err := svc.DeleteWithCleanup(agent.ID)
	if err != nil {
		t.Fatalf("DeleteWithCleanup failed: %v", err)
	}

	if !result.Database {
		t.Error("expected Database to be true")
	}
	if result.MinIO {
		t.Error("expected MinIO to be false for draft status")
	}
	if len(result.DockerImages) > 0 {
		t.Error("expected no Docker images to be deleted for draft status")
	}

	var count int64
	db.Model(&model.Agent{}).Where("id = ?", agent.ID).Count(&count)
	if count != 0 {
		t.Error("expected agent to be deleted")
	}
}

func TestDeleteWithCleanup_Generated(t *testing.T) {
	db := setupAgentTestDB(t)

	agent := &model.Agent{Name: "test-generated", Config: `{}`, Status: model.StatusGenerated, Version: 1}
	db.Create(agent)

	svc := &AgentService{db: db}

	result, err := svc.DeleteWithCleanup(agent.ID)
	if err != nil {
		t.Fatalf("DeleteWithCleanup failed: %v", err)
	}

	if !result.Database {
		t.Error("expected Database to be true")
	}
}

func TestDeleteWithCleanup_Built(t *testing.T) {
	db := setupAgentTestDB(t)

	agent := &model.Agent{Name: "test-built", Config: `{}`, Status: model.StatusBuilt, Version: 1}
	db.Create(agent)

	build := &model.ImageBuild{
		AgentID:  agent.ID,
		Version:  1,
		ImageTag: "localhost:5000/agent-test:v1",
		Status:   model.BuildSuccess,
	}
	db.Create(build)

	svc := &AgentService{db: db}

	result, err := svc.DeleteWithCleanup(agent.ID)
	if err != nil {
		t.Fatalf("DeleteWithCleanup failed: %v", err)
	}

	if !result.Database {
		t.Error("expected Database to be true")
	}
}

func TestDeleteWithCleanup_NotFound(t *testing.T) {
	db := setupAgentTestDB(t)

	svc := &AgentService{db: db}

	_, err := svc.DeleteWithCleanup(99999)
	if err == nil {
		t.Fatal("expected error for non-existent agent")
	}
	if err != gorm.ErrRecordNotFound {
		t.Errorf("expected ErrRecordNotFound, got %v", err)
	}
}

func TestDeleteWithCleanup_Deployed(t *testing.T) {
	db := setupAgentTestDB(t)

	agent := &model.Agent{Name: "test-deployed", Config: `{}`, Status: model.StatusDeployed, Version: 1}
	db.Create(agent)

	build := &model.ImageBuild{
		AgentID:  agent.ID,
		Version:  1,
		ImageTag: "localhost:5000/agent-test:v1",
		Status:   model.BuildSuccess,
	}
	db.Create(build)

	dep := &model.Deployment{
		AgentID:     agent.ID,
		Version:     1,
		SandboxName: "agent-test",
		Status:      model.DeployRunning,
	}
	db.Create(dep)

	svc := &AgentService{db: db}

	result, err := svc.DeleteWithCleanup(agent.ID)
	if err != nil {
		t.Fatalf("DeleteWithCleanup failed: %v", err)
	}

	if !result.Database {
		t.Error("expected Database to be true")
	}
}

func TestDeleteWithCleanup_Published(t *testing.T) {
	db := setupAgentTestDB(t)

	agent := &model.Agent{Name: "test-published", Config: `{}`, Status: model.StatusPublished, Version: 1}
	db.Create(agent)

	build := &model.ImageBuild{
		AgentID:  agent.ID,
		Version:  1,
		ImageTag: "localhost:5000/agent-test:v1",
		Status:   model.BuildSuccess,
	}
	db.Create(build)

	dep := &model.Deployment{
		AgentID:     agent.ID,
		Version:     1,
		SandboxName: "agent-test",
		Status:      model.DeployRunning,
	}
	db.Create(dep)

	svc := &AgentService{db: db}

	result, err := svc.DeleteWithCleanup(agent.ID)
	if err != nil {
		t.Fatalf("DeleteWithCleanup failed: %v", err)
	}

	if !result.Database {
		t.Error("expected Database to be true")
	}
}

func TestDeleteWithCleanup_Error(t *testing.T) {
	db := setupAgentTestDB(t)

	agent := &model.Agent{Name: "test-error", Config: `{}`, Status: model.StatusError, Version: 1}
	db.Create(agent)

	svc := &AgentService{db: db}

	result, err := svc.DeleteWithCleanup(agent.ID)
	if err != nil {
		t.Fatalf("DeleteWithCleanup failed: %v", err)
	}

	if !result.Database {
		t.Error("expected Database to be true")
	}
}
