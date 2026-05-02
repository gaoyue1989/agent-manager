package service

import (
	"testing"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"

	"agent-manager/backend/internal/model"
)

func setupTestDB(t *testing.T) *gorm.DB {
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

func TestGetImageInfo_Success(t *testing.T) {
	db := setupTestDB(t)

	agent := &model.Agent{Name: "test-agent", Config: `{}`, Status: model.StatusBuilt, Version: 1}
	db.Create(agent)

	build := &model.ImageBuild{
		AgentID:  agent.ID,
		Version:  1,
		ImageTag: "172.20.0.1:5001/agent-99:v1",
		Status:   model.BuildSuccess,
	}
	db.Create(build)

	svc := &DeployService{db: db, agentSvc: &AgentService{db: db}, registry: "172.20.0.1:5001"}

	info, err := svc.GetImageInfo(agent.ID)
	if err != nil {
		t.Fatalf("GetImageInfo failed: %v", err)
	}

	if info["image_tag"] != "172.20.0.1:5001/agent-99:v1" {
		t.Errorf("expected image_tag, got %v", info["image_tag"])
	}
	if info["build_status"] != "success" {
		t.Errorf("expected build_status success, got %v", info["build_status"])
	}
	if info["registry"] != "172.20.0.1:5001" {
		t.Errorf("expected registry 172.20.0.1:5001, got %v", info["registry"])
	}
}

func TestGetImageInfo_NoBuildFound(t *testing.T) {
	db := setupTestDB(t)

	svc := &DeployService{db: db, agentSvc: &AgentService{db: db}}

	_, err := svc.GetImageInfo(999)
	if err == nil {
		t.Fatal("expected error for non-existent agent")
	}
}

func TestGetImageInfo_EmptyImageTag(t *testing.T) {
	db := setupTestDB(t)

	agent := &model.Agent{Name: "test-agent", Config: `{}`, Status: model.StatusBuilt, Version: 1}
	db.Create(agent)

	build := &model.ImageBuild{
		AgentID:  agent.ID,
		Version:  1,
		ImageTag: "",
		Status:   model.BuildFailed,
	}
	db.Create(build)

	svc := &DeployService{db: db, agentSvc: &AgentService{db: db}, registry: "172.20.0.1:5001"}

	info, err := svc.GetImageInfo(agent.ID)
	if err != nil {
		t.Fatalf("GetImageInfo failed: %v", err)
	}

	if info["image_tag"] != "" {
		t.Errorf("expected empty image_tag, got %v", info["image_tag"])
	}
	if info["build_status"] != "failed" {
		t.Errorf("expected build_status failed, got %v", info["build_status"])
	}
}
