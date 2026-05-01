package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"agent-manager/backend/internal/model"
	"agent-manager/backend/internal/service"

	"github.com/gin-gonic/gin"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
)

func setupTestDB(t *testing.T) *gorm.DB {
	t.Helper()
	dsn := "agent_manager:Agent@Manager2026@tcp(127.0.0.1:3307)/agent_manager_test?charset=utf8mb4&parseTime=True&loc=Local"
	db, err := gorm.Open(mysql.Open(dsn), &gorm.Config{})
	if err != nil {
		t.Fatalf("failed to open db: %v", err)
	}
	db.Exec("DROP TABLE IF EXISTS deployments")
	db.Exec("DROP TABLE IF EXISTS image_builds")
	db.Exec("DROP TABLE IF EXISTS code_generations")
	db.Exec("DROP TABLE IF EXISTS agents")
	db.AutoMigrate(&model.Agent{}, &model.CodeGeneration{}, &model.ImageBuild{}, &model.Deployment{})
	t.Cleanup(func() {
		db.Exec("DROP TABLE IF EXISTS deployments")
		db.Exec("DROP TABLE IF EXISTS image_builds")
		db.Exec("DROP TABLE IF EXISTS code_generations")
		db.Exec("DROP TABLE IF EXISTS agents")
	})
	return db
}

func TestAgentHandler_Create_Success(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(db, nil, nil)
	h := NewAgentHandler(svc)
	h.Register(r.Group("/api/v1"))

	body := `{"config": "{\"name\": \"test\"}", "config_type": "json"}`
	req := httptest.NewRequest("POST", "/api/v1/agents", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected 201, got %d: %s", w.Code, w.Body.String())
	}

	var resp model.Agent
	json.Unmarshal(w.Body.Bytes(), &resp)
	if resp.Name != "test" {
		t.Errorf("expected name test, got %s", resp.Name)
	}
}

func TestAgentHandler_List_Empty(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(db, nil, nil)
	h := NewAgentHandler(svc)
	h.Register(r.Group("/api/v1"))

	req := httptest.NewRequest("GET", "/api/v1/agents", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}
}

func TestAgentHandler_Get_NotFound(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(db, nil, nil)
	h := NewAgentHandler(svc)
	h.Register(r.Group("/api/v1"))

	req := httptest.NewRequest("GET", "/api/v1/agents/999", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected 404, got %d", w.Code)
	}
}

func TestAgentHandler_Delete_Success(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(db, nil, nil)
	h := NewAgentHandler(svc)
	h.Register(r.Group("/api/v1"))

	agent := &model.Agent{Name: "delete-me", Config: `{}`, Status: model.StatusDraft, Version: 1}
	db.Create(agent)

	req := httptest.NewRequest("DELETE", "/api/v1/agents/1", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}
}

func TestDeployHandler_ImageInfo_NotFound(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	agentSvc := service.NewAgentService(db, nil, nil)
	svc := service.NewDeployService(db, nil, nil, nil, "", agentSvc)
	h := NewDeployHandler(svc)
	h.Register(r.Group("/api/v1"))

	req := httptest.NewRequest("GET", "/api/v1/agents/999/image-info", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected 404, got %d: %s", w.Code, w.Body.String())
	}
}

func TestDeployHandler_Chat_MissingBody(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	agentSvc := service.NewAgentService(db, nil, nil)
	svc := service.NewDeployService(db, nil, nil, nil, "", agentSvc)
	h := NewDeployHandler(svc)
	h.Register(r.Group("/api/v1"))

	req := httptest.NewRequest("POST", "/api/v1/agents/1/chat", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}
