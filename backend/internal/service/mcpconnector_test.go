package service

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"agent-manager/backend/internal/store"
)

// TestValidatePermissions 三态权限声明入口强校验：对象形态放行；字符串 token（双重编码）、
// 非法枚举值、空工具名、非 tools 形状一律 ErrInvalidForm——不得静默修复
// （双重编码入库会让 agentgen 解析失败，生成包静默丢失 permissions 段）。
func TestValidatePermissions(t *testing.T) {
	valid := []string{
		`{}`,
		`{"tools":{}}`,
		`{"tools":{"a":"allow","b":"ask","c":"deny"}}`,
		`{"tools":{"a":"allow"},"extra":1}`,
	}
	for _, raw := range valid {
		if err := validatePermissions(json.RawMessage(raw)); err != nil {
			t.Fatalf("valid %q rejected: %v", raw, err)
		}
	}
	invalid := []string{
		`"{\"tools\":{\"a\":\"allow\"}}"`, // 双重编码字符串 token
		`"{}"`,                            // 字符串 token
		`[]`,                              // 非 tools 形状
		`42`,
		`{"tools":{"a":"block"}}`, // 非法枚举值
		`{"tools":{"a":""}}`,      // 空权限值
		`{"tools":{"":"allow"}}`,  // 空工具名
		`{"tools":"allow"}`,       // tools 非映射
	}
	for _, raw := range invalid {
		err := validatePermissions(json.RawMessage(raw))
		if err == nil {
			t.Fatalf("invalid %q accepted", raw)
		}
		if !errors.Is(err, ErrInvalidForm) {
			t.Fatalf("invalid %q wrong error: %v", raw, err)
		}
	}
}

// TestMcpConnectorPermissionEntryValidation Create/Update 入口同样拦截非法 permissions；
// 合法对象形态经 PATCH 落库为对象 JSON（非字符串化）。
func TestMcpConnectorPermissionEntryValidation(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-375a", "权限空间")

	if _, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{
		Name: "p1", Endpoint: "http://x/mcp",
		Permissions: json.RawMessage(`{"tools":{"a":"block"}}`)}); !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("create invalid perms: %v", err)
	}

	view, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{Name: "p1", Endpoint: "http://x/mcp"})
	if err != nil {
		t.Fatal(err)
	}
	bad := json.RawMessage(`"{\"tools\":{\"a\":\"allow\"}}"`)
	if _, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{Permissions: &bad}); !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("patch double-encoded perms: %v", err)
	}

	ok := json.RawMessage(`{"tools":{"a":"deny"}}`)
	v2, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{Permissions: &ok})
	if err != nil {
		t.Fatalf("patch valid perms: %v", err)
	}
	if v2.Permissions != `{"tools":{"a":"deny"}}` {
		t.Fatalf("patch response permissions: %q", v2.Permissions)
	}
	var m store.McpConnector
	if err := c.DB.First(&m, view.ID).Error; err != nil {
		t.Fatal(err)
	}
	if m.Permissions != `{"tools":{"a":"deny"}}` {
		t.Fatalf("persisted permissions: %q", m.Permissions)
	}
}

// TestMcpConnectorVerifyErrorLifecycle verify 失败路径写 lastVerifyError（512 字符截断、
// 多字节按 rune 数截断）；复验成功路径清空。
func TestMcpConnectorVerifyErrorLifecycle(t *testing.T) {
	prev := allowLoopbackProbeForTest
	allowLoopbackProbeForTest = true
	t.Cleanup(func() { allowLoopbackProbeForTest = prev })
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-375b", "验证错误空间")

	longMsg := strings.Repeat("错", 600)
	errSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"jsonrpc": "2.0", "id": 1,
			"error": map[string]interface{}{"code": -32000, "message": longMsg},
		})
	}))
	defer errSrv.Close()

	view, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{
		Name: "ve", Endpoint: errSrv.URL, TimeoutSec: 5})
	if err != nil {
		t.Fatal(err)
	}
	res, err := c.VerifyMcpConnector(ws.ID, view.ID)
	if err != nil {
		t.Fatal(err)
	}
	if res["ok"] != false {
		t.Fatalf("expect verify failure: %+v", res)
	}
	var m store.McpConnector
	if err := c.DB.First(&m, view.ID).Error; err != nil {
		t.Fatal(err)
	}
	if m.LastVerifyError == "" {
		t.Fatal("failure must record lastVerifyError")
	}
	if n := len([]rune(m.LastVerifyError)); n != 512 {
		t.Fatalf("lastVerifyError rune length = %d, want 512", n)
	}

	okSrv := fakeMCP(200, []map[string]string{{"name": "t1", "description": "d"}})
	defer okSrv.Close()
	if _, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{Endpoint: &okSrv.URL}); err != nil {
		t.Fatal(err)
	}
	if res, err = c.VerifyMcpConnector(ws.ID, view.ID); err != nil || res["ok"] != true {
		t.Fatalf("re-verify: %v %+v", err, res)
	}
	if err := c.DB.First(&m, view.ID).Error; err != nil {
		t.Fatal(err)
	}
	if m.Status != "verified" || m.LastVerifyError != "" {
		t.Fatalf("success must clear lastVerifyError: status=%s err=%q", m.Status, m.LastVerifyError)
	}
}

func TestValidateDirectReturn(t *testing.T) {
	valid := []string{
		`{}`,
		`{"tools":[]}`,
		`{"tools":["render_confirm_card","submit_order"]}`,
		`{"tools":["a"],"extra":1}`,
	}
	for _, raw := range valid {
		if err := validateDirectReturn(json.RawMessage(raw)); err != nil {
			t.Fatalf("valid %q rejected: %v", raw, err)
		}
	}
	invalid := []string{
		`"{\"tools\":[\"a\"]}"`,
		`"{}"`,
		`[]`,
		`42`,
		`{"tools":"a"}`,
		`{"tools":{"a":true}}`,
		`{"tools":[1]}`,
		`{"tools":[""]}`,
		`{"tools":["  "]}`,
		`{"tools":["a","a"]}`,
	}
	for _, raw := range invalid {
		err := validateDirectReturn(json.RawMessage(raw))
		if err == nil {
			t.Fatalf("invalid %q accepted", raw)
		}
		if !errors.Is(err, ErrInvalidForm) {
			t.Fatalf("invalid %q wrong error: %v", raw, err)
		}
	}
}

func TestMcpConnectorDirectReturnEntryValidation(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-454a", "直返空间")

	if _, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{
		Name: "d1", Endpoint: "http://x/mcp",
		DirectReturn: json.RawMessage(`{"tools":"a"}`)}); !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("create invalid directReturn: %v", err)
	}

	view, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{
		Name: "d1", Endpoint: "http://x/mcp", DirectReturn: json.RawMessage(`{"tools":["render_confirm_card"]}`)})
	if err != nil {
		t.Fatal(err)
	}
	if view.DirectReturn != `{"tools":["render_confirm_card"]}` {
		t.Fatalf("create response directReturn: %q", view.DirectReturn)
	}

	bad := json.RawMessage(`"{\"tools\":[\"a\"]}"`)
	if _, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{DirectReturn: &bad}); !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("patch double-encoded directReturn: %v", err)
	}
	cleared := json.RawMessage(`{}`)
	if _, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{DirectReturn: &cleared}); err != nil {
		t.Fatalf("patch cleared directReturn: %v", err)
	}
	endpoint := "http://y/mcp"
	v3, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{Endpoint: &endpoint})
	if err != nil {
		t.Fatal(err)
	}
	if v3.DirectReturn != `{}` {
		t.Fatalf("patch without directReturn must keep it: %q", v3.DirectReturn)
	}

	var m store.McpConnector
	if err := c.DB.First(&m, view.ID).Error; err != nil {
		t.Fatal(err)
	}
	if m.DirectReturn != `{}` {
		t.Fatalf("persisted directReturn: %q", m.DirectReturn)
	}
}

func TestMcpConnectorDirectReturnDefaultsAndLegacyNull(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-454b", "直返默认空间")

	view, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{Name: "d2", Endpoint: "http://x/mcp"})
	if err != nil {
		t.Fatal(err)
	}
	if view.DirectReturn != "{}" {
		t.Fatalf("default directReturn: %q", view.DirectReturn)
	}

	if err := c.DB.Exec("UPDATE mcp_connectors SET direct_return = NULL WHERE id = ?", view.ID).Error; err != nil {
		t.Fatal(err)
	}
	legacy, err := c.ListMcpConnectors(ws.ID)
	if err != nil {
		t.Fatalf("legacy NULL direct_return must not break listing: %v", err)
	}
	if len(legacy) != 1 || legacy[0].DirectReturn != "" {
		t.Fatalf("legacy row directReturn: %+v", legacy)
	}
}

func TestMcpListToolsBlocksLoopbackAndMetadata(t *testing.T) {
	prev := allowLoopbackProbeForTest
	allowLoopbackProbeForTest = false
	t.Cleanup(func() { allowLoopbackProbeForTest = prev })

	blocked := []string{
		"http://127.0.0.1:8080/mcp",
		"http://localhost:8080/mcp",
		"http://[::1]:8080/mcp",
		"http://169.254.169.254/latest/meta-data",
		"http://[fe80::1]/mcp",
	}
	for _, u := range blocked {
		_, _, err := mcpListTools(u, "", 5)
		if err == nil || !strings.Contains(err.Error(), "not allowed") {
			t.Fatalf("url %q should be blocked, got %v", u, err)
		}
	}
}

func TestMcpListToolsDoesNotFollowRedirect(t *testing.T) {
	prev := allowLoopbackProbeForTest
	allowLoopbackProbeForTest = true
	t.Cleanup(func() { allowLoopbackProbeForTest = prev })

	var targetHits int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&targetHits, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer target.Close()

	redir := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusFound)
	}))
	defer redir.Close()

	_, _, err := mcpListTools(redir.URL, "", 5)
	if err == nil || !strings.Contains(err.Error(), "HTTP 302") {
		t.Fatalf("expected HTTP 302 error without following redirect, got %v", err)
	}
	if atomic.LoadInt32(&targetHits) != 0 {
		t.Fatalf("redirect target must not be contacted, hits=%d", targetHits)
	}
}
