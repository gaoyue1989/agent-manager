// 工作空间配置管理端点测试（契约 §2.1/§4.1）：路由装配 + 权限矩阵（匿名 401 / member GET 200、
// PUT 403 / owner PUT 200）+ 掩码与 config 组骨架行为。经 registerConsole 全链路驱动，
// 同时锁住 console.go 尾部的 registerWorkspaceConfig 装配。
package handler

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

// newWorkspaceConfigFixture 组装 owner + member 两个登录态、一个工作空间与导出新表迁移。
func newWorkspaceConfigFixture(t *testing.T) (*gin.Engine, *service.Core, uint, string, string, func()) {
	t.Helper()
	r, core, _, done := newTestServer(t)
	// testkit 既有迁移不含 2026-10 导出三表，本文件内补齐
	if err := core.DB.AutoMigrate(&store.ExportOrderEntity{}, &store.ExportPackageMarkEntity{},
		&store.WorkspaceRuntimeDefaultEntity{}); err != nil {
		t.Fatal(err)
	}
	owner := &store.User{Username: "ws-owner", PasswordHash: mustHash("pass123"), Role: "user", Status: "active"}
	member := &store.User{Username: "ws-member", PasswordHash: mustHash("pass123"), Role: "user", Status: "active"}
	for _, u := range []*store.User{owner, member} {
		if err := core.DB.Create(u).Error; err != nil {
			t.Fatal(err)
		}
	}
	ws := &store.Workspace{Slug: "ws-cfgtest", Name: "配置测试空间", OwnerUserID: owner.ID, Status: "active"}
	if err := core.DB.Create(ws).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: owner.ID, Role: "owner"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: member.ID, Role: "member"}).Error; err != nil {
		t.Fatal(err)
	}
	login := func(username string) string {
		w, out := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
			map[string]string{"username": username, "password": "pass123"})
		if w.Code != http.StatusOK {
			t.Fatalf("login %s: %d %s", username, w.Code, w.Body.String())
		}
		return out["data"].(map[string]interface{})["token"].(string)
	}
	return r, core, ws.ID, login("ws-owner"), login("ws-member"), done
}

// authJSON 带 Bearer 的 JSON 请求（doJSON 不支持请求头，console 面需要 Authorization）。
func authJSON(t *testing.T, r *gin.Engine, method, path, token string, body interface{}) (*httptest.ResponseRecorder, map[string]interface{}) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, path, rd)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	var out map[string]interface{}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w, out
}

// TestWorkspaceConfigRuntimeDefaultsRoutes 路由与权限矩阵：匿名 401、member GET 200/PUT 403、
// owner PUT 200；写入后 GET 反映且敏感值不回明文；未知空间 404；未知键 400。
func TestWorkspaceConfigRuntimeDefaultsRoutes(t *testing.T) {
	r, _, wsID, ownerTok, memberTok, done := newWorkspaceConfigFixture(t)
	defer done()
	base := fmt.Sprintf("/api/v1/console/workspaces/%d/config/runtime-defaults", wsID)

	// 匿名 → 401（JWTAuth 层）
	w, _ := authJSON(t, r, http.MethodGet, base, "", nil)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous GET: %d", w.Code)
	}
	// member GET → 200；member PUT → 403
	w, out := authJSON(t, r, http.MethodGet, base, memberTok, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("member GET: %d %s", w.Code, w.Body.String())
	}
	view := out["data"].(map[string]interface{})
	if _, ok := view["REGISTRY_ADDRESS"]; !ok {
		t.Fatalf("view must carry full whitelist, missing REGISTRY_ADDRESS: %v", view)
	}
	w, _ = authJSON(t, r, http.MethodPut, base, memberTok,
		map[string]interface{}{"values": map[string]string{"REGISTRY_ADDRESS": "reg:5000"}})
	if w.Code != http.StatusForbidden {
		t.Fatalf("member PUT: %d (want 403)", w.Code)
	}
	// owner PUT → 200（含敏感键）；GET 掩码
	values := map[string]string{"REGISTRY_ADDRESS": "reg:5000", "CHECKPOINT_PASSWORD": "pw-secret-1"}
	w, _ = authJSON(t, r, http.MethodPut, base, ownerTok, map[string]interface{}{"values": values})
	if w.Code != http.StatusOK {
		t.Fatalf("owner PUT: %d %s", w.Code, w.Body.String())
	}
	w, out = authJSON(t, r, http.MethodGet, base, memberTok, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("GET after PUT: %d", w.Code)
	}
	body := w.Body.String()
	if strings.Contains(body, "pw-secret-1") {
		t.Fatal("sensitive value leaked in GET response")
	}
	view = out["data"].(map[string]interface{})
	pw := view["CHECKPOINT_PASSWORD"].(map[string]interface{})
	if pw["hasValue"] != true || pw["value"] != nil || pw["sensitive"] != true {
		t.Fatalf("sensitive entry wrong: %+v", pw)
	}
	ra := view["REGISTRY_ADDRESS"].(map[string]interface{})
	if ra["value"] != "reg:5000" || ra["category"] != "registry" {
		t.Fatalf("plain entry wrong: %+v", ra)
	}
	// 未知键 → 400
	w, _ = authJSON(t, r, http.MethodPut, base, ownerTok,
		map[string]interface{}{"values": map[string]string{"CHECKPOINT_JDBC_URL": "jdbc:x"}})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("L3 derived key: %d %s", w.Code, w.Body.String())
	}
	// 未知空间 → 404
	w, _ = authJSON(t, r, http.MethodGet, "/api/v1/console/workspaces/9999/config/runtime-defaults", ownerTok, nil)
	if w.Code != http.StatusNotFound {
		t.Fatalf("unknown workspace: %d", w.Code)
	}
}

// TestWorkspaceConfigTemplateGroupRoutes 模板组骨架路由：llm 10 字段、未知分组 404、跨组键 400。
func TestWorkspaceConfigTemplateGroupRoutes(t *testing.T) {
	r, _, wsID, ownerTok, memberTok, done := newWorkspaceConfigFixture(t)
	defer done()
	base := fmt.Sprintf("/api/v1/console/workspaces/%d/config/templates", wsID)

	w, out := authJSON(t, r, http.MethodGet, base+"/llm", memberTok, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("GET llm: %d %s", w.Code, w.Body.String())
	}
	if got := len(out["data"].([]interface{})); got != 10 {
		t.Fatalf("llm fields = %d, want 10", got)
	}
	w, _ = authJSON(t, r, http.MethodGet, base+"/bogus", memberTok, nil)
	if w.Code != http.StatusNotFound {
		t.Fatalf("unknown group: %d", w.Code)
	}
	w, _ = authJSON(t, r, http.MethodPut, base+"/llm", memberTok,
		map[string]interface{}{"values": map[string]string{"LLM_BASE_URL": "https://llm.example.com/v1"}})
	if w.Code != http.StatusForbidden {
		t.Fatalf("member PUT template group: %d (want 403)", w.Code)
	}
	w, _ = authJSON(t, r, http.MethodPut, base+"/llm", ownerTok,
		map[string]interface{}{"values": map[string]string{"REGISTRY_ADDRESS": "reg:5000"}})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("cross-group key: %d %s", w.Code, w.Body.String())
	}
	w, _ = authJSON(t, r, http.MethodPut, base+"/llm", ownerTok,
		map[string]interface{}{"values": map[string]string{"LLM_BASE_URL": "https://llm.example.com/v1"}})
	if w.Code != http.StatusOK {
		t.Fatalf("owner PUT llm: %d %s", w.Code, w.Body.String())
	}
}

// TestWorkspaceConfigRegistryRoutes registry 组骨架路由：视图仅含 registry/routing/runtime 分类键。
func TestWorkspaceConfigRegistryRoutes(t *testing.T) {
	r, _, wsID, ownerTok, memberTok, done := newWorkspaceConfigFixture(t)
	defer done()
	base := fmt.Sprintf("/api/v1/console/workspaces/%d/config/registry", wsID)

	w, _ := authJSON(t, r, http.MethodPut, base, memberTok,
		map[string]interface{}{"values": map[string]string{"ROUTING_MODE": "host"}})
	if w.Code != http.StatusForbidden {
		t.Fatalf("member PUT registry: %d (want 403)", w.Code)
	}
	w, _ = authJSON(t, r, http.MethodPut, base, ownerTok,
		map[string]interface{}{"values": map[string]string{"LLM_API_KEY": "sk"}})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("llm key in registry group: %d", w.Code)
	}
	w, _ = authJSON(t, r, http.MethodPut, base, ownerTok,
		map[string]interface{}{"values": map[string]string{"ROUTING_MODE": "host", "REGISTRY_ADDRESS": "reg:5000"}})
	if w.Code != http.StatusOK {
		t.Fatalf("owner PUT registry: %d %s", w.Code, w.Body.String())
	}
	w, out := authJSON(t, r, http.MethodGet, base, memberTok, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("GET registry: %d", w.Code)
	}
	view := out["data"].(map[string]interface{})
	if _, ok := view["ROUTING_MODE"]; !ok {
		t.Fatal("registry view missing ROUTING_MODE")
	}
	if _, ok := view["LLM_API_KEY"]; ok {
		t.Fatal("registry view must exclude llm keys")
	}
}

// TestWorkspaceConfigMcpConnectorsRoutes MCP 连接器骨架路由：GET=既有连接器列表（读视图）；
// 写 v1 未实现（member 403 先裁决，owner 501 显式声明）。
func TestWorkspaceConfigMcpConnectorsRoutes(t *testing.T) {
	r, _, wsID, ownerTok, memberTok, done := newWorkspaceConfigFixture(t)
	defer done()
	base := fmt.Sprintf("/api/v1/console/workspaces/%d/config/mcp-connectors", wsID)

	w, out := authJSON(t, r, http.MethodGet, base, memberTok, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("GET mcp-connectors: %d %s", w.Code, w.Body.String())
	}
	if _, ok := out["data"].([]interface{}); !ok {
		t.Fatalf("connector list shape wrong: %v", out)
	}
	w, _ = authJSON(t, r, http.MethodPut, base, memberTok,
		map[string]interface{}{"connectors": []interface{}{}})
	if w.Code != http.StatusForbidden {
		t.Fatalf("member PUT mcp-connectors: %d (want 403)", w.Code)
	}
	w, _ = authJSON(t, r, http.MethodPut, base, ownerTok,
		map[string]interface{}{"connectors": []interface{}{}})
	if w.Code != http.StatusNotImplemented {
		t.Fatalf("owner PUT mcp-connectors: %d (want 501, v1 未实现)", w.Code)
	}
}
