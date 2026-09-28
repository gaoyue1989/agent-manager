package handler

import (
	"net/http"
	"strings"
	"testing"
)

// TestPlatformConfigAPI 平台默认配置端点：GET/PUT 掩码 + defaults 数据源（R3：无集群渲染、无 apply-restart）。
func TestPlatformConfigAPI(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()

	// GET：模板四组 schema，初始无值
	w, out := doJSON(t, r, "GET", "/api/v1/platform-config", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("GET: %d %s", w.Code, w.Body.String())
	}
	data := out["data"].(map[string]interface{})
	groups := data["groups"].([]interface{})
	if len(groups) != 4 {
		t.Fatalf("want 4 groups, got %d", len(groups))
	}

	// PUT：敏感 + 非敏感各一
	w, _ = doJSON(t, r, "PUT", "/api/v1/platform-config", map[string]any{
		"values": map[string]string{"LLM_API_KEY": "sk-api", "LLM_TEMPERATURE": "0.4"},
	})
	if w.Code != http.StatusOK {
		t.Fatalf("PUT: %d %s", w.Code, w.Body.String())
	}
	// 展示视图敏感键掩码：值不出现在任何位置
	if strings.Contains(w.Body.String(), "sk-api") {
		t.Fatal("PUT response must not echo sensitive value")
	}

	// defaults 数据源（表单默认填入用）：含敏感明文
	w, out = doJSON(t, r, "GET", "/api/v1/platform-config/defaults", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("GET defaults: %d %s", w.Code, w.Body.String())
	}
	values := out["data"].(map[string]interface{})["values"].(map[string]interface{})
	if values["LLM_API_KEY"] != "sk-api" || values["LLM_TEMPERATURE"] != "0.4" {
		t.Fatalf("defaults wrong: %+v", values)
	}

	// 未知键 400
	w, _ = doJSON(t, r, "PUT", "/api/v1/platform-config", map[string]any{
		"values": map[string]string{"NOPE": "x"},
	})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("unknown key should 400, got %d", w.Code)
	}

	// 空 values 400（非 500）
	w, _ = doJSON(t, r, "PUT", "/api/v1/platform-config", map[string]any{"values": map[string]string{}})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("empty values should 400, got %d", w.Code)
	}
}
