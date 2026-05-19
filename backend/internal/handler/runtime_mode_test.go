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
)

func TestListImages_API(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(nil, nil, nil)
	h := NewAgentHandler(svc, "agent-framework:latest|Agent Framework v0.5.5,agent-framework:v0.5.5|Agent Framework v0.5.5 (stable)")
	h.Register(r.Group("/api/v1"))

	req := httptest.NewRequest("GET", "/api/v1/images", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var resp struct {
		Items []struct {
			Name        string `json:"name"`
			Description string `json:"description"`
		} `json:"items"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	if len(resp.Items) != 2 {
		t.Errorf("expected 2 images, got %d", len(resp.Items))
	}
	if resp.Items[0].Name != "agent-framework:latest" {
		t.Errorf("expected first image name 'agent-framework:latest', got %s", resp.Items[0].Name)
	}
	if resp.Items[0].Description != "Agent Framework v0.5.5" {
		t.Errorf("expected first image description 'Agent Framework v0.5.5', got %s", resp.Items[0].Description)
	}
}

func TestListImages_Empty(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(nil, nil, nil)
	h := NewAgentHandler(svc, "")
	h.Register(r.Group("/api/v1"))

	req := httptest.NewRequest("GET", "/api/v1/images", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	var resp struct {
		Items []interface{} `json:"items"`
	}
	json.Unmarshal(w.Body.Bytes(), &resp)
	if len(resp.Items) != 0 {
		t.Errorf("expected 0 images, got %d", len(resp.Items))
	}
}

func TestCreateAgent_MountMode(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(db, nil, nil)
	h := NewAgentHandler(svc, "agent-framework:latest|Agent Framework v0.5.5")
	h.Register(r.Group("/api/v1"))

	body := `{"config": "{\"name\": \"Mount Agent\"}", "config_type": "json", "runtime_mode": "mount", "image": "agent-framework:latest"}`
	req := httptest.NewRequest("POST", "/api/v1/agents", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected 201, got %d: %s", w.Code, w.Body.String())
	}

	var agent model.Agent
	json.Unmarshal(w.Body.Bytes(), &agent)

	if agent.RuntimeMode != model.RuntimeModeMount {
		t.Errorf("RuntimeMode = %q, want %q", agent.RuntimeMode, model.RuntimeModeMount)
	}
	if agent.Image != "agent-framework:latest" {
		t.Errorf("Image = %q, want %q", agent.Image, "agent-framework:latest")
	}
}

func TestCreateAgent_BuildMode(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(db, nil, nil)
	h := NewAgentHandler(svc, "")
	h.Register(r.Group("/api/v1"))

	body := `{"config": "{\"name\": \"build-agent\"}", "config_type": "json"}`
	req := httptest.NewRequest("POST", "/api/v1/agents", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected 201, got %d", w.Code)
	}

	var agent model.Agent
	json.Unmarshal(w.Body.Bytes(), &agent)

	if agent.RuntimeMode != "" && agent.RuntimeMode != model.RuntimeModeBuild {
		t.Errorf("RuntimeMode = %q, want empty or %q", agent.RuntimeMode, model.RuntimeModeBuild)
	}
	if agent.Image != "" {
		t.Errorf("Image = %q, want empty", agent.Image)
	}
}

func TestCreateAgent_MountModeWithCheckpointDSN(t *testing.T) {
	db := setupTestDB(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()

	svc := service.NewAgentService(db, nil, nil)
	h := NewAgentHandler(svc, "agent-framework:latest|Agent Framework v0.5.5")
	h.Register(r.Group("/api/v1"))

	body := `{"config": "{\"name\": \"Checkpoint Agent\"}", "config_type": "json", "runtime_mode": "mount", "image": "agent-framework:latest", "checkpoint_dsn": "mysql+asyncmy://custom:pass@host:3307/db"}`
	req := httptest.NewRequest("POST", "/api/v1/agents", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected 201, got %d: %s", w.Code, w.Body.String())
	}

	var agent model.Agent
	json.Unmarshal(w.Body.Bytes(), &agent)

	if agent.CheckpointDSN != "mysql+asyncmy://custom:pass@host:3307/db" {
		t.Errorf("CheckpointDSN = %q, want custom DSN", agent.CheckpointDSN)
	}
}
