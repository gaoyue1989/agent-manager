package handler

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"

	k8sclient "agent-manager/backend/internal/k8s/k8sfake"
	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

const agentsMD = `---
name: Demo
vendorKey: acme
agentKey: demo
version: 1.0.0
slug: acme/demo
description: demo agent package
author: "@acme"
license: MIT
---
body`

func newTestServer(t *testing.T) (*gin.Engine, *service.Core, *k8sclient.FakeK8s, func()) {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if sqlDB, e := db.DB(); e == nil {
		sqlDB.SetMaxOpenConns(1)
	}
	if err := db.AutoMigrate(&store.OafPackage{}, &store.ServiceEntity{}, &store.ServiceEvent{}); err != nil {
		t.Fatal(err)
	}
	dir, _ := os.MkdirTemp("", "oaffs-*")
	fk := k8sclient.New()
	core := service.NewCore(db, &store.FS{Root: dir}, fk, service.ConfigView{
		Namespace: "test", IngressClass: "nginx", IngressHost: "1.2.3.4", IngressPort: 30080,
		DefaultImage: "agent-framework:latest",
		ImageAllowed: func(img string) bool { return img == "agent-framework:latest" },
		ResCPU:       "250m", ResMem: "256Mi", LimCPU: "1", LimMem: "1Gi",
		RegisterTimeout: 400 * time.Millisecond,
		RegisterRetry:   0,
	})
	gin.SetMode(gin.TestMode)
	r := gin.New()
	Register(r, core, nil, "")
	cleanup := func() { os.RemoveAll(dir) }
	return r, core, fk, cleanup
}

func doJSON(t *testing.T, r *gin.Engine, method, path string, body interface{}) (*httptest.ResponseRecorder, map[string]interface{}) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, path, rd)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	var out map[string]interface{}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w, out
}

// uploadZip multipart 上传一个合法 OAF zip。
func uploadZip(t *testing.T, r *gin.Engine) map[string]interface{} {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "demo.zip")
	zipBytes := buildTestZip(map[string]string{"AGENTS.md": agentsMD})
	fw.Write(zipBytes)
	mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/packages", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("upload zip failed: %d %s", w.Code, w.Body.String())
	}
	var out map[string]interface{}
	json.Unmarshal(w.Body.Bytes(), &out)
	data := out["data"].(map[string]interface{})
	return data
}

func buildTestZip(files map[string]string) []byte {
	buf := new(bytes.Buffer)
	zw := zip.NewWriter(buf)
	for name, content := range files {
		w, _ := zw.Create(name)
		w.Write([]byte(content))
	}
	zw.Close()
	return buf.Bytes()
}

func TestPackageUploadAndList(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	if rec["id"] == nil || rec["slug"] != "acme/demo" {
		t.Fatalf("upload resp: %+v", rec)
	}
	w, out := doJSON(t, r, http.MethodGet, "/api/v1/packages", nil)
	if w.Code != http.StatusOK || len(out["data"].([]interface{})) != 1 {
		t.Fatalf("list: %d %v", w.Code, out)
	}
}

func TestPackageUploadRejectsBadZip(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "bad.zip")
	fw.Write([]byte("this is not a zip"))
	mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/packages", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("bad zip should 400, got %d %s", w.Code, w.Body.String())
	}
}

func TestServicePublishEndpointValidationErrors(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	pkgID := uint(rec["id"].(float64))

	// 镜像不在白名单 → 400
	w, _ := doJSON(t, r, http.MethodPost, "/api/v1/services",
		map[string]interface{}{"packageId": pkgID, "image": "evil:x"})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("image check: %d %s", w.Code, w.Body.String())
	}
	// 保留键 env → 400
	w, _ = doJSON(t, r, http.MethodPost, "/api/v1/services",
		map[string]interface{}{"packageId": pkgID,
			"env": map[string]string{"AGENT_CONFIG_DIR": "/hack"}})
	if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), "reserved") {
		t.Fatalf("reserved key: %d %s", w.Code, w.Body.String())
	}
	// 不存在的包 → 404
	w, _ = doJSON(t, r, http.MethodPost, "/api/v1/services",
		map[string]interface{}{"packageId": 9999})
	if w.Code != http.StatusNotFound {
		t.Fatalf("missing package: %d", w.Code)
	}
}

func TestImagesEndpoint(t *testing.T) {
	_, _, _, done := newTestServer(t)
	defer done()
	images := []struct{ Image, Label string }{{"agent-framework:latest", "Agent Framework"}}
	r2 := gin.New()
	gin.SetMode(gin.TestMode)
	Register(r2, nil, images, "")
	w, out := doJSON(t, r2, http.MethodGet, "/api/v1/images", nil)
	if w.Code != http.StatusOK || len(out["data"].([]interface{})) != 1 {
		t.Fatalf("images: %d %v", w.Code, out)
	}
}

func TestAuthMiddleware(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(Auth("secret"))
	r.GET("/x", func(c *gin.Context) { OK(c, nil) })
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/x", nil))
	if w.Code != http.StatusUnauthorized {
		t.Fatal("missing token must 401")
	}
	req := httptest.NewRequest(http.MethodGet, "/x", nil)
	req.Header.Set("Authorization", "Bearer secret")
	w2 := httptest.NewRecorder()
	r.ServeHTTP(w2, req)
	if w2.Code != http.StatusOK {
		t.Fatal("valid token must pass")
	}
}

// waitForSvcStatus 轮询等待服务状态推进（handler 测试内联版，等异步注册 goroutine）。
func waitForSvcStatus(t *testing.T, core *service.Core, id uint, want string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		svc, err := core.Get(id)
		if err != nil {
			t.Fatal(err)
		}
		if svc.Status == want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("status not %q in time, got %q", want, svc.Status)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// PATCH /services/:id/env 全链路：deploying 拒绝 → 稳态生效 → 保留键拒绝 → stopped 拒绝。
func TestServicePatchEnvEndpoint(t *testing.T) {
	r, core, fk, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	pkgID := uint(rec["id"].(float64))
	w, out := doJSON(t, r, http.MethodPost, "/api/v1/services",
		map[string]interface{}{"packageId": pkgID, "image": "agent-framework:latest"})
	if w.Code != http.StatusOK {
		t.Fatalf("publish: %d %s", w.Code, w.Body.String())
	}
	data := out["data"].(map[string]interface{})
	svcID := uint(data["id"].(float64))
	k8sName := data["k8sName"].(string)

	// deploying 状态 → 400 ErrBadState
	w, _ = doJSON(t, r, http.MethodPatch, "/api/v1/services/1/env",
		map[string]interface{}{"env": map[string]string{"A": "1"}})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("patch while deploying: %d %s", w.Code, w.Body.String())
	}

	// 同 publishToRegisterFailed：改写注册地址避免 macOS mDNS 解析拖慢失败路径
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svcID).Update("cluster_url", "http://127.0.0.1:1")
	_ = fk.SetReady("test", k8sName, 1)
	waitForSvcStatus(t, core, svcID, store.StatusRegisterFailed)
	w, _ = doJSON(t, r, http.MethodPatch, fmt.Sprintf("/api/v1/services/%d/env", svcID),
		map[string]interface{}{"env": map[string]string{"LOG_LEVEL": "warn"}})
	if w.Code != http.StatusOK {
		t.Fatalf("patch env: %d %s", w.Code, w.Body.String())
	}

	// 保留键 → 400
	w, _ = doJSON(t, r, http.MethodPatch, fmt.Sprintf("/api/v1/services/%d/env", svcID),
		map[string]interface{}{"env": map[string]string{"AGENT_CONFIG_DIR": "/hack"}})
	if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), "reserved") {
		t.Fatalf("reserved key: %d %s", w.Code, w.Body.String())
	}

	// 等待 env 更新触发的异步注册收敛，避免 goroutine 事后覆盖状态
	_ = fk.SetReady("test", k8sName, 1)
	waitForSvcStatus(t, core, svcID, store.StatusRegisterFailed)

	// stopped 状态 → 400
	if _, err := core.Unpublish(svcID); err != nil {
		t.Fatal(err)
	}
	w, _ = doJSON(t, r, http.MethodPatch, fmt.Sprintf("/api/v1/services/%d/env", svcID),
		map[string]interface{}{"env": map[string]string{"A": "1"}})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("patch while stopped: %d %s", w.Code, w.Body.String())
	}
	if len(fk.Restarts()) == 0 {
		t.Fatal("successful patch should trigger restart")
	}
}

// POST /services/:id/register 手动重注册：agent-card 可达时恢复 running。
func TestServiceRegisterActionEndpoint(t *testing.T) {
	r, core, fk, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	pkgID := uint(rec["id"].(float64))
	w, out := doJSON(t, r, http.MethodPost, "/api/v1/services",
		map[string]interface{}{"packageId": pkgID, "image": "agent-framework:latest"})
	svcID := uint(out["data"].(map[string]interface{})["id"].(float64))
	// 同 publishToRegisterFailed：改写注册地址避免 macOS mDNS 解析拖慢失败路径
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svcID).Update("cluster_url", "http://127.0.0.1:1")
	_ = fk.SetReady("test", out["data"].(map[string]interface{})["k8sName"].(string), 1)
	waitForSvcStatus(t, core, svcID, store.StatusRegisterFailed)

	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/agent-card.json", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"Demo","version":"1.0.0"}`))
	})
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svcID).Update("cluster_url", srv.URL)

	w, _ = doJSON(t, r, http.MethodPost, fmt.Sprintf("/api/v1/services/%d/register", svcID), nil)
	if w.Code != http.StatusOK {
		t.Fatalf("register action: %d %s", w.Code, w.Body.String())
	}
	waitForSvcStatus(t, core, svcID, store.StatusRunning)
}

// 包详情组装（记录+文件树+AGENTS.md 原文）与删除守卫（被引用 400）。
func TestPackageDetailAndDeleteEndpoints(t *testing.T) {
	r, core, _, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	pkgID := uint(rec["id"].(float64))

	w, out := doJSON(t, r, http.MethodGet, fmt.Sprintf("/api/v1/packages/%d", pkgID), nil)
	if w.Code != http.StatusOK {
		t.Fatalf("detail: %d %s", w.Code, w.Body.String())
	}
	data := out["data"].(map[string]interface{})
	if data["package"] == nil || data["tree"] == nil ||
		!strings.Contains(data["agentsMd"].(string), "slug: acme/demo") {
		t.Fatalf("detail payload wrong: %v", data)
	}

	w, _ = doJSON(t, r, http.MethodGet, "/api/v1/packages/999", nil)
	if w.Code != http.StatusNotFound {
		t.Fatalf("missing package: %d", w.Code)
	}

	// 被服务引用 → 400
	w, out = doJSON(t, r, http.MethodPost, "/api/v1/services",
		map[string]interface{}{"packageId": pkgID, "image": "agent-framework:latest"})
	if w.Code != http.StatusOK {
		t.Fatalf("publish: %d %s", w.Code, w.Body.String())
	}
	svcID := uint(out["data"].(map[string]interface{})["id"].(float64))
	w, _ = doJSON(t, r, http.MethodDelete, fmt.Sprintf("/api/v1/packages/%d", pkgID), nil)
	if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), "referenced") {
		t.Fatalf("delete in-use package: %d %s", w.Code, w.Body.String())
	}

	// 引用释放 → 200
	if err := core.Delete(svcID); err != nil {
		t.Fatal(err)
	}
	w, _ = doJSON(t, r, http.MethodDelete, fmt.Sprintf("/api/v1/packages/%d", pkgID), nil)
	if w.Code != http.StatusOK {
		t.Fatalf("delete released package: %d %s", w.Code, w.Body.String())
	}
	w, _ = doJSON(t, r, http.MethodGet, fmt.Sprintf("/api/v1/packages/%d", pkgID), nil)
	if w.Code != http.StatusNotFound {
		t.Fatalf("deleted package detail: %d", w.Code)
	}
}

func TestHealthzEndpoint(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	w, out := doJSON(t, r, http.MethodGet, "/healthz", nil)
	if w.Code != http.StatusOK || out["data"].(map[string]interface{})["status"] != "up" {
		t.Fatalf("healthz: %d %v", w.Code, out)
	}
}

// CORS 预检：OPTIONS 短路 204 并带全开放头。
func TestCORSPreflight(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	req := httptest.NewRequest(http.MethodOptions, "/api/v1/services", nil)
	req.Header.Set("Origin", "http://localhost:3000")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNoContent {
		t.Fatalf("preflight: %d", w.Code)
	}
	if w.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Fatal("missing CORS allow-origin header")
	}
	if w.Header().Get("Access-Control-Allow-Headers") == "" {
		t.Fatal("missing CORS allow-headers header")
	}
}
