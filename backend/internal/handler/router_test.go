package handler

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

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
