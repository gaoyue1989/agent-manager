// M0 鉴权与路由分层测试：login 三分支 / me 工作空间 / AUTH_TOKEN 与 JWT 并存放行矩阵。
package handler

import (
	"net/http"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

// TestAuthLoginBranches 登录三态 + Set-Cookie 属性（用真实服务端流程驱动）。
func TestAuthLoginBranches(t *testing.T) {
	r, core, _, done := newTestServer(t)
	defer done()
	if err := core.DB.Create(&store.User{Username: "admin", PasswordHash: mustHash("pass123"),
		DisplayName: "管理员", Role: "admin", Status: "active"}).Error; err != nil {
		t.Fatal(err)
	}

	// 正确登录：200 + token + cookie
	w, out := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "admin", "password": "pass123"})
	if w.Code != http.StatusOK {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	data := out["data"].(map[string]interface{})
	if data["token"] == "" || data["token"] == nil {
		t.Fatalf("token missing: %+v", data)
	}
	user := data["user"].(map[string]interface{})
	if user["role"] != "admin" {
		t.Fatalf("role: %+v", user)
	}
	cookie := w.Result().Cookies()
	found := false
	for _, ck := range cookie {
		if ck.Name == "oaf_session" {
			found = true
			if !ck.HttpOnly || ck.Path != "/" {
				t.Fatalf("cookie attrs: %+v", ck)
			}
		}
	}
	if !found {
		t.Fatal("oaf_session cookie missing")
	}

	// 错误密码：401 统一文案（不区分用户不存在/密码错）
	w, _ = doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "admin", "password": "wrong"})
	if w.Code != http.StatusUnauthorized || !strings.Contains(w.Body.String(), "invalid username or password") {
		t.Fatalf("bad password: %d %s", w.Code, w.Body.String())
	}
	// 不存在的用户：同样 401 统一文案
	w, _ = doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "nobody", "password": "x"})
	if w.Code != http.StatusUnauthorized || !strings.Contains(w.Body.String(), "invalid username or password") {
		t.Fatalf("missing user: %d %s", w.Code, w.Body.String())
	}

	// disabled 用户：403
	if err := core.DB.Create(&store.User{Username: "frozen",
		PasswordHash: mustHash("p"), Role: "user", Status: "disabled"}).Error; err != nil {
		t.Fatal(err)
	}
	w, _ = doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "frozen", "password": "p"})
	if w.Code != http.StatusForbidden {
		t.Fatalf("disabled: %d %s", w.Code, w.Body.String())
	}

	// me：JWT（header 通道）→ 用户 + 工作空间（admin 虚拟项）
	token := data["token"].(string)
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/auth/me", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w2 := doReq(t, r, req)
	if w2.Code != http.StatusOK {
		t.Fatalf("me: %d %s", w2.Code, w2.Body.String())
	}
	// cookie 通道亦可
	req2, _ := http.NewRequest(http.MethodGet, "/api/v1/auth/me", nil)
	req2.AddCookie(&http.Cookie{Name: "oaf_session", Value: token})
	w3 := doReq(t, r, req2)
	if w3.Code != http.StatusOK {
		t.Fatalf("me via cookie: %d %s", w3.Code, w3.Body.String())
	}
	// 无凭据 401
	req3, _ := http.NewRequest(http.MethodGet, "/api/v1/auth/me", nil)
	w4 := doReq(t, r, req3)
	if w4.Code != http.StatusUnauthorized {
		t.Fatalf("me anonymous: %d", w4.Code)
	}
}

// TestRequireAdminRevocation 降级/禁用/删号后旧 JWT 立即失效（issue #148）：
// RequireAdmin 以 DB 实时 role/status 裁决，不再信任 token 内嵌 claims。
func TestRequireAdminRevocation(t *testing.T) {
	r, core, _, done := newTestServer(t)
	defer done()
	if err := core.DB.Create(&store.User{Username: "ex-admin", PasswordHash: mustHash("p"),
		Role: "admin", Status: "active"}).Error; err != nil {
		t.Fatal(err)
	}
	w, out := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "ex-admin", "password": "p"})
	if w.Code != http.StatusOK {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	token := out["data"].(map[string]interface{})["token"].(string)
	adminGet := func() int {
		req, _ := http.NewRequest(http.MethodGet, "/api/v1/admin/workspaces", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		return doCode(t, r, req)
	}
	if code := adminGet(); code != http.StatusOK {
		t.Fatalf("fresh admin token: %d", code)
	}

	// 降级 role=user → 同一 token 立即 403
	if err := core.DB.Model(&store.User{}).Where("username = ?", "ex-admin").
		Update("role", "user").Error; err != nil {
		t.Fatal(err)
	}
	if code := adminGet(); code != http.StatusForbidden {
		t.Fatalf("demoted admin old token: %d (want 403)", code)
	}

	// 重新提权后再禁用 → 同一 token 立即 403（非 401，语义=身份仍在但被停用）
	if err := core.DB.Model(&store.User{}).Where("username = ?", "ex-admin").
		Updates(map[string]interface{}{"role": "admin"}).Error; err != nil {
		t.Fatal(err)
	}
	if code := adminGet(); code != http.StatusOK {
		t.Fatalf("re-promoted: %d", code)
	}
	if err := core.DB.Model(&store.User{}).Where("username = ?", "ex-admin").
		Update("status", "disabled").Error; err != nil {
		t.Fatal(err)
	}
	if code := adminGet(); code != http.StatusForbidden {
		t.Fatalf("disabled admin old token: %d (want 403)", code)
	}

	// 删号 → 401
	if err := core.DB.Where("username = ?", "ex-admin").Delete(&store.User{}).Error; err != nil {
		t.Fatal(err)
	}
	if code := adminGet(); code != http.StatusUnauthorized {
		t.Fatalf("deleted admin old token: %d (want 401)", code)
	}

	// AUTH_TOKEN 通道不受影响（uid=0 无 DB 用户）
	r2, _, _, done2 := newTestServerWithToken(t, "tok-1")
	defer done2()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/admin/workspaces", nil)
	req.Header.Set("Authorization", "Bearer tok-1")
	if code := doCode(t, r2, req); code != http.StatusOK {
		t.Fatalf("auth token channel broken: %d", code)
	}
}

// TestConsoleDisabledUserRejected console 面 resolveWorkspace 对禁用用户 403（issue #148 同口径）。
func TestConsoleDisabledUserRejected(t *testing.T) {
	r, core, _, done := newTestServer(t)
	defer done()
	u := store.User{Username: "dev", PasswordHash: mustHash("p"), Role: "user", Status: "active"}
	if err := core.DB.Create(&u).Error; err != nil {
		t.Fatal(err)
	}
	ws := store.Workspace{Slug: "ws-bbbb0001", Name: "dev-ws", Status: "active", QuotaJSON: "{}"}
	if err := core.DB.Create(&ws).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: u.ID, Role: "owner"}).Error; err != nil {
		t.Fatal(err)
	}
	w, out := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "dev", "password": "p"})
	if w.Code != http.StatusOK {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	token := out["data"].(map[string]interface{})["token"].(string)
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/console/workspaces/me/summary", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	if code := doCode(t, r, req); code != http.StatusOK {
		t.Fatalf("active user console: %d", code)
	}
	if err := core.DB.Model(&store.User{}).Where("id = ?", u.ID).
		Update("status", "disabled").Error; err != nil {
		t.Fatal(err)
	}
	if code := doCode(t, r, req); code != http.StatusForbidden {
		t.Fatalf("disabled user console old token: %d (want 403)", code)
	}
}

// TestAuthRouterLayering AUTH_TOKEN 与 JWT 并存时的分层放行矩阵（M0 §2.5）：
//
//	/auth/login            匿名 200（不被 AUTH_TOKEN 拦）
//	/api/v1/services       无 AUTH_TOKEN 401（存量行为不变）
//	/api/v1/services       带 AUTH_TOKEN 200
//	/console/admin/users   JWT-admin 200（不被 AUTH_TOKEN 拦）
//	/console/admin/users   JWT-user 403
func TestAuthRouterLayering(t *testing.T) {
	r, core, _, done := newTestServer(t)
	defer done()
	admin := store.User{Username: "root", PasswordHash: mustHash("p1"), Role: "admin", Status: "active"}
	user := store.User{Username: "dev", PasswordHash: mustHash("p2"), Role: "user", Status: "active"}
	if err := core.DB.Create(&admin).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&user).Error; err != nil {
		t.Fatal(err)
	}

	// AUTH_TOKEN 版服务端（独立 DB，同步种子同一批账号）
	r2, core2, _, done2 := newTestServerWithToken(t, "tok-123")
	defer done2()
	if err := core2.DB.Create(&store.User{Username: "root",
		PasswordHash: mustHash("p1"), Role: "admin", Status: "active"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := core2.DB.Create(&store.User{Username: "dev",
		PasswordHash: mustHash("p2"), Role: "user", Status: "active"}).Error; err != nil {
		t.Fatal(err)
	}

	// 1) /auth/login 匿名可达（AUTH_TOKEN 不拦）
	w, _ := doJSON(t, r2, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "root", "password": "p1"})
	if w.Code != http.StatusOK {
		t.Fatalf("login under AUTH_TOKEN: %d %s", w.Code, w.Body.String())
	}
	// 2) 存量 /services 仍要求 AUTH_TOKEN
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/services", nil)
	if code := doCode(t, r2, req); code != http.StatusUnauthorized {
		t.Fatalf("legacy services without token: %d", code)
	}
	req.Header.Set("Authorization", "Bearer tok-123")
	if code := doCode(t, r2, req); code != http.StatusOK {
		t.Fatalf("legacy services with token: %d", code)
	}
	// 3) admin 端点：AUTH_TOKEN 通道放行
	req3, _ := http.NewRequest(http.MethodGet, "/api/v1/admin/users", nil)
	req3.Header.Set("Authorization", "Bearer tok-123")
	if code := doCode(t, r2, req3); code != http.StatusOK {
		t.Fatalf("admin via auth token: %d", code)
	}
	// 4) admin 端点：JWT-admin 200 / JWT-user 403（登录走无 AUTH_TOKEN 版服务端拿 token）
	wA, outA := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "root", "password": "p1"})
	if wA.Code != http.StatusOK {
		t.Fatalf("admin login: %d", wA.Code)
	}
	adminToken := outA["data"].(map[string]interface{})["token"].(string)
	wU, outU := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "dev", "password": "p2"})
	userToken := outU["data"].(map[string]interface{})["token"].(string)
	_ = wU

	req4, _ := http.NewRequest(http.MethodGet, "/api/v1/admin/users", nil)
	req4.Header.Set("Authorization", "Bearer "+adminToken)
	if code := doCode(t, r, req4); code != http.StatusOK {
		t.Fatalf("admin via jwt-admin: %d", code)
	}
	req5, _ := http.NewRequest(http.MethodGet, "/api/v1/admin/users", nil)
	req5.Header.Set("Authorization", "Bearer "+userToken)
	if code := doCode(t, r, req5); code != http.StatusForbidden {
		t.Fatalf("admin via jwt-user: %d", code)
	}
	// 5) console 端点 JWT 通道不被 AUTH_TOKEN 拦（分层核心断言）：
	//    在带 AUTH_TOKEN 的服务端上用 JWT 访问 /console/*：过 JWT 进 workspace 裁决 → 200
	ws := store.Workspace{Slug: "ws-aaaa0001", Name: "dev-ws", Status: "active", QuotaJSON: "{}"}
	if err := core2.DB.Create(&ws).Error; err != nil {
		t.Fatal(err)
	}
	var dev2 store.User
	if err := core2.DB.Where("username = ?", "dev").First(&dev2).Error; err != nil {
		t.Fatal(err)
	}
	if err := core2.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: dev2.ID, Role: "owner"}).Error; err != nil {
		t.Fatal(err)
	}
	wTok, outTok := doJSON(t, r2, http.MethodPost, "/api/v1/auth/login",
		map[string]string{"username": "dev", "password": "p2"})
	if wTok.Code != http.StatusOK {
		t.Fatalf("user login on token server: %d %s", wTok.Code, wTok.Body.String())
	}
	tok2 := outTok["data"].(map[string]interface{})["token"].(string)
	req6, _ := http.NewRequest(http.MethodGet, "/api/v1/console/workspaces/me/summary", nil)
	req6.Header.Set("Authorization", "Bearer "+tok2)
	if code := doCode(t, r2, req6); code != http.StatusOK {
		t.Fatalf("console via jwt on AUTH_TOKEN server: %d (expect JWT layer to pass, not AUTH_TOKEN 401)", code)
	}
}
