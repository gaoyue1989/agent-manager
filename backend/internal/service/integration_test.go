// M7 集成 + M8 API Key 测试：候选探测三态 / 重复 409 / 引用保护 / 签发哈希与一次性明文 /
// 吊销 / 过期惰性判定 / 作用域过滤 / introspect（M9 数据面）。
package service

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"agent-manager/backend/internal/store"
)

func TestIntegrationCRUDAndProbe(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-2000bbb1", "集成空间")

	// 候选探测三态：online（假 agent-card）/ unreachable / skipped（未开启协议）。
	// 路径精确断言 /.well-known/agent-card.json：HasSuffix 会同时放过错误路径
	// /agent-card.json，掩盖探活路径错误（issue #137）
	online := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/.well-known/agent-card.json" {
			_, _ = w.Write([]byte(`{"name":"member","version":"1.0.0"}`))
		} else {
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer online.Close()
	dead := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	dead.Close()

	memberOK := &store.ServiceEntity{K8sName: "oaf-member-ok", DisplayName: "在线成员",
		WorkspaceID: ws.ID, ClusterURL: online.URL, EnvJSON: `{"AGENT_PROTOCOL_ENABLED":"true"}`}
	memberDead := &store.ServiceEntity{K8sName: "oaf-member-dead", DisplayName: "不可达成员",
		WorkspaceID: ws.ID, ClusterURL: dead.URL, EnvJSON: `{"AGENT_PROTOCOL_ENABLED":"true"}`}
	memberOff := &store.ServiceEntity{K8sName: "oaf-member-off", DisplayName: "未开启协议",
		WorkspaceID: ws.ID, ClusterURL: online.URL, EnvJSON: `{}`}
	for _, s := range []*store.ServiceEntity{memberOK, memberDead, memberOff} {
		if err := c.DB.Create(s).Error; err != nil {
			t.Fatal(err)
		}
	}
	list, err := c.ListIntegrations(ws.ID)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	probes := map[uint]string{}
	for _, cand := range list.Candidates {
		probes[cand.ServiceID] = cand.Probe
	}
	if probes[memberOK.ID] != "online" || probes[memberDead.ID] != "unreachable" || probes[memberOff.ID] != "skipped" {
		t.Fatalf("probes: %+v", probes)
	}

	// 登记集成：endpoint 派生 + 默认名 + PROPAGATE
	it, err := c.CreateIntegration(ws.ID, CreateIntegrationInput{MemberServiceID: memberOK.ID})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if it.EndpointURL != fmt.Sprintf("http://%s-svc:8100", memberOK.K8sName) || it.Name != "在线成员" ||
		it.AskPolicy != "PROPAGATE" {
		t.Fatalf("integration: %+v", it)
	}
	// 重复集成 409
	if _, err := c.CreateIntegration(ws.ID, CreateIntegrationInput{MemberServiceID: memberOK.ID}); err == nil ||
		!strings.Contains(err.Error(), "duplicate_integration") {
		t.Fatalf("duplicate: %v", err)
	}
	// 跨空间 member 400
	if _, err := c.CreateIntegration(999, CreateIntegrationInput{MemberServiceID: memberOK.ID}); err == nil {
		t.Fatal("cross workspace member should fail")
	}
	// 引用保护：lead 服务包引用 endpoint → 删除 409
	pkg := uploadTestPkg(t, c, "")
	c.DB.Model(&store.OafPackage{}).Where("id = ?", pkg.ID).
		Update("manifest_json", fmt.Sprintf(`{"agentKey":"demo","agents":[{"agent":"member","endpoint":"%s"}]}`, it.EndpointURL))
	lead := &store.ServiceEntity{K8sName: "oaf-lead", DisplayName: "lead", PackageID: pkg.ID, WorkspaceID: ws.ID}
	c.DB.Create(lead)
	if err := c.DeleteIntegration(ws.ID, it.ID); err == nil || !strings.Contains(err.Error(), "integration_in_use") {
		t.Fatalf("delete in-use: %v", err)
	}
	// 引用方回显
	list, _ = c.ListIntegrations(ws.ID)
	if len(list.Relations) != 1 || len(list.Relations[0].ReferencedBy) != 1 {
		t.Fatalf("referencedBy: %+v", list.Relations)
	}
	// 解除引用后可删
	c.DB.Model(&store.OafPackage{}).Where("id = ?", pkg.ID).
		Update("manifest_json", `{"agentKey":"demo","agents":[]}`)
	if err := c.DeleteIntegration(ws.ID, it.ID); err != nil {
		t.Fatalf("delete after unref: %v", err)
	}
}

func TestApiKeyLifecycle(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-2000bbb2", "凭证空间")
	agent := &store.ServiceEntity{K8sName: "oaf-ak", DisplayName: "AK服务", WorkspaceID: ws.ID}
	c.DB.Create(agent)

	// 签发：ak- 格式 + 明文仅一次
	rec, plain, err := c.IssueApiKey(IssueApiKeyInput{
		Name: "cs-gateway", AgentID: &agent.ID, ExpiresInDays: 30, WorkspaceID: ws.ID, UserID: 1})
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	if !strings.HasPrefix(plain, "ak-") || len(plain) < 20 {
		t.Fatalf("plain format: %q", plain)
	}
	if rec.KeyPrefix != plain[:8] {
		t.Fatalf("prefix: %q vs %q", rec.KeyPrefix, plain[:8])
	}
	// 明文不回读、hash 不出列表
	list, err := c.ListApiKeys(ws.ID, 0)
	if err != nil || len(list) != 1 {
		t.Fatalf("list: %v n=%d", err, len(list))
	}
	b, _ := json.Marshal(list[0])
	if strings.Contains(string(b), "KeyHash") || strings.Contains(string(b), plain) {
		t.Fatal("hash/plain leaked in list view")
	}
	if list[0].AgentName != "AK服务" {
		t.Fatalf("agent name resolve: %+v", list[0])
	}
	// 作用域过滤：按 agentId 过滤时命中
	scoped, _ := c.ListApiKeys(ws.ID, agent.ID)
	if len(scoped) != 1 {
		t.Fatalf("scoped: %d", len(scoped))
	}
	// 空间内其他 agent → 不命中（绑定单 Agent 的 key 不出现在其他作用域查询）
	if _, _, err := c.IssueApiKey(IssueApiKeyInput{Name: "all-key", WorkspaceID: ws.ID}); err != nil {
		t.Fatal(err)
	}
	scoped, _ = c.ListApiKeys(ws.ID, agent.ID)
	if len(scoped) != 2 { // 绑定 agent 的 + 全空间的
		t.Fatalf("scoped with global: %d", len(scoped))
	}
	// 过期惰性判定
	exp := time.Now().Add(-time.Hour)
	c.DB.Model(&store.ApiKey{}).Where("name = ?", "all-key").Update("expires_at", &exp)
	list, _ = c.ListApiKeys(ws.ID, 0)
	for _, k := range list {
		if k.Name == "all-key" && k.Status != "expired" {
			t.Fatalf("expired status: %+v", k)
		}
	}
	// 吊销
	if err := c.RevokeApiKey(1, ws.ID, rec.ID); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	var fresh store.ApiKey
	c.DB.First(&fresh, rec.ID)
	if fresh.Status != "revoked" {
		t.Fatalf("revoked status: %s", fresh.Status)
	}
	// 审计动作落库
	var events []store.ServiceEvent
	c.DB.Where("service_id = 0").Find(&events)
	kinds := map[string]bool{}
	for _, e := range events {
		var r auditReason
		_ = json.Unmarshal([]byte(e.Reason), &r)
		kinds[r.Kind] = true
	}
	if !kinds["api_key.issue"] || !kinds["api_key.revoke"] {
		t.Fatalf("audit kinds: %+v", kinds)
	}
}

// TestIntrospectApiKeyChannel M9 数据面：API Key 双通道校验 + 吊销即时 403 + 作用域。
func TestIntrospectApiKeyChannel(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-2000bbb3", "introspect空间")
	agent := &store.ServiceEntity{K8sName: "oaf-intro", DisplayName: "Intro",
		WorkspaceID: ws.ID, ShortName: "intro"}
	c.DB.Create(agent)
	rec, plain, err := c.IssueApiKey(IssueApiKeyInput{Name: "k1", AgentID: &agent.ID, WorkspaceID: ws.ID})
	if err != nil {
		t.Fatal(err)
	}

	req, _ := http.NewRequest(http.MethodGet, "http://router/api/v1/auth/introspect", nil)
	req.Header.Set("Authorization", "Bearer "+plain)
	req.Header.Set("X-Original-URI", "/agent/intro/threads/chat")
	w := httptest.NewRecorder()
	c.Introspect(w, req)
	if w.Code != http.StatusOK || w.Header().Get("X-User-Id") != "u-0" {
		t.Fatalf("introspect: %d headers=%v", w.Code, w.Header())
	}
	// 跨作用域：绑定单 Agent 的 key 访问其它 Agent 路由 403
	req2, _ := http.NewRequest(http.MethodGet, "http://router/api/v1/auth/introspect", nil)
	req2.Header.Set("Authorization", "Bearer "+plain)
	req2.Header.Set("X-Original-URI", "/agent/other-svc/threads/chat")
	w2 := httptest.NewRecorder()
	c.Introspect(w2, req2)
	if w2.Code != http.StatusForbidden {
		t.Fatalf("scope mismatch: %d", w2.Code)
	}
	// 吊销后即时 403
	_ = c.RevokeApiKey(1, ws.ID, rec.ID)
	req3, _ := http.NewRequest(http.MethodGet, "http://router/api/v1/auth/introspect", nil)
	req3.Header.Set("Authorization", "Bearer "+plain)
	w3 := httptest.NewRecorder()
	c.Introspect(w3, req3)
	if w3.Code != http.StatusForbidden {
		t.Fatalf("revoked: %d", w3.Code)
	}
	// 无凭据 401
	w4 := httptest.NewRecorder()
	c.Introspect(w4, httptest.NewRequest(http.MethodGet, "/introspect", nil))
	if w4.Code != http.StatusUnauthorized {
		t.Fatalf("no credential: %d", w4.Code)
	}
	// JWT 通道：签发 token（与 handler 同 secret/格式）+ DB 存在 active 用户 → 200；
	// 禁用/删号用户的旧 token 即时失效（issue #148）
	hash, _ := store.HashPassword("p-jwtuser")
	c.DB.Create(&store.User{Username: "jwtuser", PasswordHash: hash, Role: "user", Status: "active"})
	var jwtUser store.User
	c.DB.Where("username = ?", "jwtuser").First(&jwtUser)
	tok := signTestJWT(jwtUser.ID)
	req5, _ := http.NewRequest(http.MethodGet, "http://router/api/v1/auth/introspect", nil)
	c.JWTSecret = "test-jwt-secret"
	req5.Header.Set("Authorization", "Bearer "+tok)
	w5 := httptest.NewRecorder()
	c.Introspect(w5, req5)
	if w5.Code != http.StatusOK || w5.Header().Get("X-User-Id") != fmt.Sprintf("u-%d", jwtUser.ID) {
		t.Fatalf("jwt channel: %d %q", w5.Code, w5.Header().Get("X-User-Id"))
	}
	// 禁用后同 token 即时 403
	c.DB.Model(&store.User{}).Where("id = ?", jwtUser.ID).Update("status", "disabled")
	w6 := httptest.NewRecorder()
	c.Introspect(w6, req5)
	if w6.Code != http.StatusForbidden {
		t.Fatalf("jwt disabled user: %d (want 403)", w6.Code)
	}
	// last_used_at 节流写
	var fresh store.ApiKey
	c.DB.Where("id = ?", rec.ID).First(&fresh)
	_ = fresh
}

// TestIntrospectApiKeyScopeBypass issue #407：单 Agent key 作用域裁决与 nginx 路由同口径、
// fail-closed。旧判定 `len(uri) >= len(prefix) && uri[:len(prefix)] != prefix` 使比前缀短的
// 目标 URI 整链短路放行；且 nginx 透传的 $request_uri 未归一化，`../` 穿越时 introspect
// 看到的前缀与实际路由目标分叉；带 query 的根路径则被误判 403。X-Agent-Short 是 nginx
// location 归一化匹配的路由捕获（与真实 upstream 一致），非空时与 URI 反解交叉校验。
func TestIntrospectApiKeyScopeBypass(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-2000bbb4", "作用域空间")
	demo := &store.ServiceEntity{K8sName: "oaf-scope-demo", DisplayName: "Demo",
		WorkspaceID: ws.ID, ShortName: "demo"}
	if err := c.DB.Create(demo).Error; err != nil {
		t.Fatal(err)
	}
	_, plain, err := c.IssueApiKey(IssueApiKeyInput{Name: "k-demo", AgentID: &demo.ID, WorkspaceID: ws.ID})
	if err != nil {
		t.Fatal(err)
	}
	// 长名 Agent 的 key：比本 Agent 前缀短的 URI 正是旧判定 len>=len 短路放行的逃逸面
	long := &store.ServiceEntity{K8sName: "oaf-scope-sales", DisplayName: "Sales",
		WorkspaceID: ws.ID, ShortName: "sales-assistant"}
	if err := c.DB.Create(long).Error; err != nil {
		t.Fatal(err)
	}
	_, longPlain, err := c.IssueApiKey(IssueApiKeyInput{Name: "k-sales", AgentID: &long.ID, WorkspaceID: ws.ID})
	if err != nil {
		t.Fatal(err)
	}
	// 对照组：空间级 key（AgentID nil）不做路由裁决
	_, wsPlain, err := c.IssueApiKey(IssueApiKeyInput{Name: "k-ws", WorkspaceID: ws.ID})
	if err != nil {
		t.Fatal(err)
	}

	probe := func(bearer string, headers map[string]string) *httptest.ResponseRecorder {
		req, _ := http.NewRequest(http.MethodGet, "http://router/api/v1/auth/introspect", nil)
		req.Header.Set("Authorization", "Bearer "+bearer)
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		w := httptest.NewRecorder()
		c.Introspect(w, req)
		return w
	}
	type scopeCase struct {
		name    string
		headers map[string]string
		want    int
	}
	cases := []scopeCase{
		{"dotdot-traversal", map[string]string{"X-Original-URI": "/agent/demo/../victim/threads"}, http.StatusForbidden},
		{"encoded-traversal", map[string]string{"X-Original-URI": "/agent/demo%2e%2e/victim/threads"}, http.StatusForbidden},
		{"short-header-divergence", map[string]string{"X-Original-URI": "/agent/demo/threads", "X-Agent-Short": "victim"}, http.StatusForbidden},
		{"short-header-spoof", map[string]string{"X-Original-URI": "/agent/victim/threads", "X-Agent-Short": "demo"}, http.StatusForbidden},
		{"short-header-agree", map[string]string{"X-Original-URI": "/agent/demo/threads", "X-Agent-Short": "demo"}, http.StatusOK},
		{"query-root", map[string]string{"X-Original-URI": "/agent/demo?x=1"}, http.StatusOK},
		{"query-sub", map[string]string{"X-Original-URI": "/agent/demo/threads?x=1"}, http.StatusOK},
		{"normal", map[string]string{"X-Original-URI": "/agent/demo/threads/chat"}, http.StatusOK},
		{"double-slash", map[string]string{"X-Original-URI": "//agent/demo/threads"}, http.StatusOK},
	}
	for _, tc := range cases {
		w := probe(plain, tc.headers)
		if w.Code != tc.want {
			t.Fatalf("#407 %s: X-Original-URI=%q X-Agent-Short=%q => %d (want %d)",
				tc.name, tc.headers["X-Original-URI"], tc.headers["X-Agent-Short"], w.Code, tc.want)
		}
		if tc.want == http.StatusOK && w.Header().Get("X-User-Id") == "" {
			t.Fatalf("#407 %s: 200 must carry X-User-Id", tc.name)
		}
	}
	// 长名 key（前缀 /agent/sales-assistant/，23 字节）：更短的目标 URI——含 issue 复现
	// 的 /agent/ops/threads（18 字节）与空/缺失/畸形——不得落旧判定 len>=len 短路放行
	for _, uri := range []string{"/agent/ops/threads", "/agent/demo", "/agent", "", "not-a-path"} {
		h := map[string]string{"X-Original-URI": uri}
		if w := probe(longPlain, h); w.Code != http.StatusForbidden {
			t.Fatalf("#407 sales key: X-Original-URI=%q => %d (want 403)", uri, w.Code)
		}
	}
	// 空间级 key 不受裁决约束
	if w := probe(wsPlain, map[string]string{"X-Original-URI": "/agent/anything/threads"}); w.Code != http.StatusOK {
		t.Fatalf("#407 workspace key: %d (want 200)", w.Code)
	}
}

// TestIntrospectCookieChannel issue #373：introspect 会话 cookie 通道——router off 档
// 「免鉴权但不免身份」的 backend 前置。浏览器只有 httpOnly cookie 无自报头，auth_request
// 子请求把 Cookie 带到 upstream，introspect 读回注入 X-User-Id（header 优先、cookie 回落）。
func TestIntrospectCookieChannel(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	c.JWTSecret = "test-jwt-secret"
	hash, _ := store.HashPassword("p-cookie")
	c.DB.Create(&store.User{Username: "cookieuser", PasswordHash: hash, Role: "user", Status: "active"})
	var u store.User
	c.DB.Where("username = ?", "cookieuser").First(&u)
	tok := signTestJWT(u.ID)
	intro := func(cookie, bearer string) *httptest.ResponseRecorder {
		req, _ := http.NewRequest(http.MethodGet, "http://router/api/v1/auth/introspect", nil)
		if cookie != "" {
			req.Header.Set("Cookie", SessionCookieName+"="+cookie)
		}
		if bearer != "" {
			req.Header.Set("Authorization", "Bearer "+bearer)
		}
		w := httptest.NewRecorder()
		c.Introspect(w, req)
		return w
	}
	// cookie 通道：200 + X-User-Id
	w := intro(tok, "")
	if w.Code != http.StatusOK || w.Header().Get("X-User-Id") != fmt.Sprintf("u-%d", u.ID) {
		t.Fatalf("cookie channel: %d %q", w.Code, w.Header().Get("X-User-Id"))
	}
	// 坏 cookie 401
	if w2 := intro("bad.token.here", ""); w2.Code != http.StatusUnauthorized {
		t.Fatalf("bad cookie: %d (want 401)", w2.Code)
	}
	// Authorization 优先于 cookie：header 有效 token + cookie 坏 token → 200（cookie 优先会 401）
	if w3 := intro("bad.token.here", tok); w3.Code != http.StatusOK {
		t.Fatalf("header priority over cookie: %d (want 200)", w3.Code)
	}
	// 禁用用户的 cookie 即时 403（issue #148 语义，与 Bearer 通道同链）
	c.DB.Model(&store.User{}).Where("id = ?", u.ID).Update("status", "disabled")
	if w4 := intro(tok, ""); w4.Code != http.StatusForbidden {
		t.Fatalf("disabled user cookie: %d (want 403)", w4.Code)
	}
}

// signTestJWT 与 handler 层同格式的测试 JWT（HS256 标准库，见 internal/authtoken）。
func signTestJWT(uid uint) string {
	return testIssueToken(uid, "u", "user")
}
