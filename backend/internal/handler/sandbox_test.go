// U5 补充（sandbox-pool-opensandbox-design §9.1/§5.1）：沙箱管理面 handler 层 HTTP 状态映射——
// 未配置 → 503、入参校验 → 400、上游 401 → 502、429 透传、创建成功 → 201。
// （service 层断言映射矩阵；本文件锚定 handler → HTTP 状态码契约，m12 e2e 的 503/502 分支同源。）
package handler

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"

	"agent-manager/backend/internal/k8s/k8sfake"
	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

// newSandboxAdminCore sqlite 内存库 + 指向 httptest 上游的 platform_config（空串 = 不写入）。
// K8s 注入 k8sfake（PR-4a）：建池缺省注入技能同步 env 须经 SecretExists 查池 workload ns
// token Secret 前提，Core.K8s 不再可缺省（fake 无 Secret → no-token 旧行为分支，201 契约不变）。
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
	return &service.Core{DB: db, K8s: k8sfake.New()}
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
	var lastState string
	var seenPoolCreate bool
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		lastAuth = r.Header.Get("OPEN-SANDBOX-API-KEY")
		lastState = r.URL.Query().Get("state")
		mu.Unlock()
		switch r.URL.Path {
		case "/v1/pools":
			if r.Method == http.MethodPost {
				mu.Lock()
				dup := seenPoolCreate
				seenPoolCreate = true
				mu.Unlock()
				if dup {
					// 同名创建 → 上游 409，backend 原样透传（m12 同款断言）
					w.WriteHeader(http.StatusConflict)
					_, _ = w.Write([]byte(`{"code":"POOL_ALREADY_EXISTS"}`))
					return
				}
				w.WriteHeader(http.StatusCreated)
				_, _ = w.Write([]byte(`{"name":"oaf-demo-pool"}`))
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

	core := newSandboxAdminCore(t, strings.TrimPrefix(up.URL, "http://"))
	if err := core.DB.Create(&store.ServiceEntity{K8sName: "oaf-demo", DisplayName: "oaf-demo"}).Error; err != nil {
		t.Fatal(err)
	}
	r := newSandboxAdminEngine(core)

	// 创建成功 → 201（包络 code=0）
	w, env := doJSON(t, r, http.MethodPost, "/api/v1/admin/sandboxes/pools", map[string]interface{}{
		"name":         "oaf-demo-pool",
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

	// 同名创建 → 上游 409 原样透传（载荷容量合法，仅 name 重复；设计 §5.2「409 = 同名已存在」）
	dupBody := map[string]interface{}{
		"name":         "oaf-demo-pool",
		"capacitySpec": map[string]int32{"bufferMin": 1, "bufferMax": 2, "poolMin": 1, "poolMax": 3},
	}
	if w, _ := doJSON(t, r, http.MethodPost, "/api/v1/admin/sandboxes/pools", dupBody); w.Code != http.StatusConflict {
		t.Fatalf("duplicate name passthrough = %d, want 409: %s", w.Code, w.Body.String())
	}

	// name 非法 → 400；capacitySpec 链破坏 → 400；池名未绑定服务 / extraEnv 键越界 → 400
	for _, body := range []map[string]interface{}{
		{"name": "Bad_Name", "capacitySpec": map[string]int32{"bufferMin": 1, "bufferMax": 2, "poolMin": 1, "poolMax": 3}},
		{"name": "p2", "capacitySpec": map[string]int32{"bufferMin": 3, "bufferMax": 2, "poolMin": 1, "poolMax": 4}},
		{"name": "oaf-none-pool", "capacitySpec": map[string]int32{"bufferMin": 1, "bufferMax": 2, "poolMin": 1, "poolMax": 3}},
		{"name": "oaf-demo-pool", "capacitySpec": map[string]int32{"bufferMin": 1, "bufferMax": 2, "poolMin": 1, "poolMax": 3},
			"extraEnv": []map[string]string{{"name": "FOO_BAR", "value": "x"}}},
	} {
		if w, _ := doJSON(t, r, http.MethodPost, "/api/v1/admin/sandboxes/pools", body); w.Code != http.StatusBadRequest {
			t.Fatalf("invalid input = %d, want 400: %s", w.Code, w.Body.String())
		}
	}

	// 列表 429 透传
	if w, _ := doJSON(t, r, http.MethodGet, "/api/v1/admin/sandboxes", nil); w.Code != http.StatusTooManyRequests {
		t.Fatalf("429 passthrough = %d", w.Code)
	}

	// state 过滤透传须 QueryEscape（评审遗留：含 & 的值不得在上游注入第二个过滤参数）
	esc := url.QueryEscape("Running&x=1")
	if w, _ := doJSON(t, r, http.MethodGet, "/api/v1/admin/sandboxes?state="+esc, nil); w.Code != http.StatusTooManyRequests {
		t.Fatalf("state filter passthrough = %d", w.Code)
	}
	mu.Lock()
	if lastState != "Running&x=1" {
		mu.Unlock()
		t.Fatalf("upstream state = %q, want decoded single value（& 已被转义）", lastState)
	}
	mu.Unlock()

	// 未配置 → 503 + 提示 /settings（Required 键走 API 清不掉，直删 DB 行模拟）
	core = newSandboxAdminCore(t, strings.TrimPrefix(up.URL, "http://"))
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

// TestConsoleSandboxesErrorMapping console 用户侧沙箱实例端点错误折叠（issue #297）：
// 未配置 → 503、上游 401 → 502，均折叠为成员通用文案（不含 /settings 运维指引）；
// 上游正常空列表 → 200 + data 空数组。走生产路由链（newTestServer 全表迁移 + Register
// 完整路由）+ 真 JWT 登录——registerConsole 无条件挂 resolveWorkspace（console.go:121），
// GetUser 把 DB 错误折叠为 401（core_auth.go），stub 引擎直注 uid 会先撞 401 假失败，
// 禁止绕过 registerConsole 注册路由。admin 侧可诊断文案由 TestAdminSandboxesHTTPMapping 锚定不变。
func TestConsoleSandboxesErrorMapping(t *testing.T) {
	r, core, _, done := newTestServer(t)
	defer done()

	// 种子：普通成员用户 + 工作空间 + owner 成员关系 → 真 JWT 登录（先例 TestConsoleDisabledUserRejected）
	u := store.User{Username: "member", PasswordHash: mustHash("p"), Role: "user", Status: "active"}
	if err := core.DB.Create(&u).Error; err != nil {
		t.Fatal(err)
	}
	ws := store.Workspace{Slug: "ws-cccc0001", Name: "member-ws", Status: "active", QuotaJSON: "{}"}
	if err := core.DB.Create(&ws).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: u.ID, Role: "owner"}).Error; err != nil {
		t.Fatal(err)
	}
	w, out := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "member", "password": "p"})
	if w.Code != http.StatusOK {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	token := out["data"].(map[string]interface{})["token"].(string)

	consoleGet := func() (*httptest.ResponseRecorder, map[string]interface{}) {
		req, _ := http.NewRequest(http.MethodGet,
			fmt.Sprintf("/api/v1/console/workspaces/%d/sandboxes", ws.ID), nil)
		req.Header.Set("Authorization", "Bearer "+token)
		w := doReq(t, r, req)
		var env map[string]interface{}
		_ = json.Unmarshal(w.Body.Bytes(), &env)
		return w, env
	}

	// ① 默认无 platform_config 行（newTestServer 初始态即未配置）→ 503 + 成员通用文案，
	//    不透出 /settings 运维指引
	w, _ = consoleGet()
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("not configured = %d, want 503: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "请联系平台管理员") {
		t.Fatalf("console 503 must use member message: %s", w.Body.String())
	}
	if strings.Contains(w.Body.String(), "settings") {
		t.Fatalf("console 503 must not leak /settings guidance: %s", w.Body.String())
	}

	// ② 写入指向 httptest 上游的配置（newSandboxAdminCore 同款 db.Save 写法），
	//    上游 GET /v1/sandboxes 恒 401 → 502（SandboxUpstreamError 折叠，不透出
	//    「改 API Key」运维提示）；mock-sandbox 的 unauthorized 故障不覆盖 GET /v1/sandboxes，
	//    故该分支只能由本用例覆盖
	var mu sync.Mutex
	var mode int // 0=恒 401，1=正常空列表
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		m := mode
		mu.Unlock()
		if m == 1 {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"items":[],"pagination":{"hasNextPage":false}}`))
			return
		}
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer up.Close()
	for k, v := range map[string]string{"OPENSANDBOX_SERVER_URL": strings.TrimPrefix(up.URL, "http://"), "OPENSANDBOX_API_KEY": "sk-h"} {
		if err := core.DB.Save(&store.PlatformConfigEntity{EnvKey: k, Value: v}).Error; err != nil {
			t.Fatal(err)
		}
	}
	w, _ = consoleGet()
	if w.Code != http.StatusBadGateway {
		t.Fatalf("upstream 401 = %d, want 502: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "请联系平台管理员") {
		t.Fatalf("console 502 must use member message: %s", w.Body.String())
	}
	if strings.Contains(w.Body.String(), "settings") {
		t.Fatalf("console 502 must not leak /settings guidance: %s", w.Body.String())
	}

	// ③ 上游恢复正常（200 空列表）→ 200 + data 空数组（包络不变，api.ts 仅消费 message）
	mu.Lock()
	mode = 1
	mu.Unlock()
	w, env := consoleGet()
	if w.Code != http.StatusOK {
		t.Fatalf("healthy upstream = %d, want 200: %s", w.Code, w.Body.String())
	}
	items, ok := env["data"].([]interface{})
	if !ok || len(items) != 0 {
		t.Fatalf("console healthy = empty array, got: %s", w.Body.String())
	}
}
