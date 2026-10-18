// 鉴权测试共用 helper（auth_test.go 专用；通用请求工具在 router_test.go）。
package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"

	k8sclient "agent-manager/backend/internal/k8s/k8sfake"
	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

// mustHash bcrypt 包装（测试数据构造）。
func mustHash(p string) string {
	h, err := store.HashPassword(p)
	if err != nil {
		panic(err)
	}
	return h
}

func doReq(t *testing.T, r *gin.Engine, req *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// doCode 只看状态码。
func doCode(t *testing.T, r *gin.Engine, req *http.Request) int {
	t.Helper()
	return doReq(t, r, req).Code
}

// newTestServerToken 带可选 AUTH_TOKEN 的测试服务端（newTestServer 的参数化形态）。
func newTestServerToken(t *testing.T, authToken string) (*gin.Engine, *service.Core, *k8sclient.FakeK8s, func()) {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if sqlDB, e := db.DB(); e == nil {
		sqlDB.SetMaxOpenConns(1)
	}
	if err := db.AutoMigrate(&store.OafPackage{}, &store.ServiceEntity{}, &store.ServiceEvent{},
		&store.PlatformConfigEntity{}, &store.PlatformConfigEvent{},
		&store.User{}, &store.Workspace{}, &store.WorkspaceMember{}, &store.ApiKey{},
		&store.ModelConnection{}, &store.ModelDelivery{}, &store.McpConnector{}, &store.AgentIntegration{}); err != nil {
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
	core.JWTSecret = "test-secret"
	gin.SetMode(gin.TestMode)
	r := gin.New()
	Register(r, core, nil, authToken, "test-secret")
	cleanup := func() { os.RemoveAll(dir) }
	return r, core, fk, cleanup
}

// newTestServerWithToken 别名（语义命名）。
func newTestServerWithToken(t *testing.T, authToken string) (*gin.Engine, *service.Core, *k8sclient.FakeK8s, func()) {
	return newTestServerToken(t, authToken)
}

// decodeBody 响应体 JSON 解析。
func decodeBody(t *testing.T, w *httptest.ResponseRecorder) map[string]interface{} {
	t.Helper()
	var out map[string]interface{}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return out
}
