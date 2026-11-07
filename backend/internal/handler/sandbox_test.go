// U5 补充（sandbox-pool-opensandbox-design §9.1/§5.1）：沙箱管理面 handler 层 HTTP 状态映射——
// 未配置 → 503、入参校验 → 400、上游 401 → 502、429 透传、创建成功 → 201。
// （service 层断言映射矩阵；本文件锚定 handler → HTTP 状态码契约，m12 e2e 的 503/502 分支同源。）
package handler

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"

	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

// newSandboxAdminCore sqlite 内存库 + 指向 httptest 上游的 platform_config（空串 = 不写入）。
func newSandboxAdminCore(t *testing.T, upstreamURL string) *service.Core {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&store.ServiceEntity{}, &store.ServiceEvent{}, &store.PlatformConfigEntity{}, &store.PlatformConfigEvent{}); err != nil {
		t.Fatal(err)
	}
	if sqlDB, e := db.DB(); e == nil {
		sqlDB.SetMaxOpenConns(1)
	}
	for k, v := range map[string]string{"OPENSANDBOX_SERVER_URL": upstreamURL, "OPENSANDBOX_API_KEY": "sk-h"} {
		if v == "" {
			continue
		}
		if err := db.Save(&store.PlatformConfigEntity{EnvKey: k, Value: v}).Error; err != nil {
			t.Fatal(err)
		}
	}
	return &service.Core{DB: db}
}

// newSandboxAdminEngine 最小 admin 引擎：uid 注入中间件替身 + 沙箱路由组（鉴权由 RequireAdmin
// 分层单测覆盖，此处只锚定业务错误 → HTTP 状态码）。
func newSandboxAdminEngine(core *service.Core) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	g := r.Group("/api/v1/admin", func(c *gin.Context) {
		c.Set("uid", uint(7))
		c.Next()
	})
	registerAdminSandboxes(g, core)
	return r
}

// TestAdminSandboxesHTTPMapping 错误/成功状态码契约。
func TestAdminSandboxesHTTPMapping(t *testing.T) {
	var mu sync.Mutex
	var lastAuth string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		lastAuth = r.Header.Get("OPEN-SANDBOX-API-KEY")
		mu.Unlock()
		switch r.URL.Path {
		case "/v1/pools":
			if r.Method == http.MethodPost {
				w.WriteHeader(http.StatusCreated)
				_, _ = w.Write([]byte(`{"name":"p1"}`))
				return
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"items":[]}`))
		case "/v1/sandboxes":
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"code":"KUBERNETES::POOL_CAPACITY_EXHAUSTED"}`))
		default:
			w.WriteHeader(http.StatusUnauthorized)
		}
	}))
	defer up.Close()

	r := newSandboxAdminEngine(newSandboxAdminCore(t, strings.TrimPrefix(up.URL, "http://")))

	// 创建成功 → 201（包络 code=0）
	w, env := doJSON(t, r, http.MethodPost, "/api/v1/admin/sandboxes/pools", map[string]interface{}{
		"name":         "p1",
		"capacitySpec": map[string]int32{"bufferMin": 1, "bufferMax": 2, "poolMin": 1, "poolMax": 3},
	})
	if w.Code != http.StatusCreated {
		t.Fatalf("create = %d, want 201: %s", w.Code, w.Body.String())
	}
	if env["code"] != float64(0) {
		t.Fatalf("create envelope = %s", w.Body.String())
	}
	// API Key 注入断言（header 通路，d12）
	mu.Lock()
	if lastAuth != "sk-h" {
		mu.Unlock()
		t.Fatalf("upstream api key header = %q", lastAuth)
	}
	mu.Unlock()

	// name 非法 → 400；capacitySpec 链破坏 → 400
	for _, body := range []map[string]interface{}{
		{"name": "Bad_Name", "capacitySpec": map[string]int32{"bufferMin": 1, "bufferMax": 2, "poolMin": 1, "poolMax": 3}},
		{"name": "p2", "capacitySpec": map[string]int32{"bufferMin": 3, "bufferMax": 2, "poolMin": 1, "poolMax": 4}},
	} {
		if w, _ := doJSON(t, r, http.MethodPost, "/api/v1/admin/sandboxes/pools", body); w.Code != http.StatusBadRequest {
			t.Fatalf("invalid input = %d, want 400: %s", w.Code, w.Body.String())
		}
	}

	// 列表 429 透传
	if w, _ := doJSON(t, r, http.MethodGet, "/api/v1/admin/sandboxes", nil); w.Code != http.StatusTooManyRequests {
		t.Fatalf("429 passthrough = %d", w.Code)
	}

	// 未配置 → 503 + 提示 /settings（Required 键走 API 清不掉，直删 DB 行模拟）
	core := newSandboxAdminCore(t, strings.TrimPrefix(up.URL, "http://"))
	if err := core.DB.Where("env_key IN ?", []string{"OPENSANDBOX_SERVER_URL", "OPENSANDBOX_API_KEY"}).
		Delete(&store.PlatformConfigEntity{}).Error; err != nil {
		t.Fatal(err)
	}
	r2 := newSandboxAdminEngine(core)
	w, _ = doJSON(t, r2, http.MethodGet, "/api/v1/admin/sandboxes/pools", nil)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("not configured = %d, want 503: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "settings") {
		t.Fatalf("503 must guide to /settings: %s", w.Body.String())
	}

	// 上游 401 → 502（单池详情走 default 分支）
	w, _ = doJSON(t, r, http.MethodGet, "/api/v1/admin/sandboxes/pools/some-pool", nil)
	if w.Code != http.StatusBadGateway {
		t.Fatalf("401 mapping = %d, want 502: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "settings") {
		t.Fatalf("502 must point to /settings: %s", w.Body.String())
	}
}
