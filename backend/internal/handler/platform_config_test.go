package handler

import (
	"net/http"
	"strconv"
	"strings"
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"agent-manager/backend/internal/k8s"
)

// TestPlatformConfigAPI 平台默认配置端点：GET/PUT/apply-restart 与敏感值掩码。
func TestPlatformConfigAPI(t *testing.T) {
	r, _, fk, done := newTestServer(t)
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
	// 响应敏感键掩码：值不出现在任何位置
	if strings.Contains(w.Body.String(), "sk-api") {
		t.Fatal("PUT response must not echo sensitive value")
	}
	// 集群拆分渲染：敏感进 Secret，非敏感进 CM
	sec, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.DefaultSecretName, metav1.GetOptions{})
	if err != nil || string(sec.Data["LLM_API_KEY"]) != "sk-api" {
		t.Fatalf("platform secret wrong: %v %+v", err, sec)
	}
	cm, err := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), k8s.DefaultConfigCMName, metav1.GetOptions{})
	if err != nil || cm.Data["LLM_TEMPERATURE"] != "0.4" {
		t.Fatalf("platform CM wrong: %v %+v", err, cm)
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

	// apply-restart：无服务 → 空结果
	w, out = doJSON(t, r, "POST", "/api/v1/platform-config/apply-restart", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("apply-restart: %d %s", w.Code, w.Body.String())
	}
	res := out["data"].(map[string]interface{})
	if len(res["restarted"].([]interface{})) != 0 || len(res["skipped"].([]interface{})) != 0 {
		t.Fatalf("empty cluster should restart nothing: %+v", res)
	}
}

// TestPublishEnvRoutingAPI 发布 API 敏感路由 + 详情掩码。
func TestPublishEnvRoutingAPI(t *testing.T) {
	r, _, fk, done := newTestServer(t)
	defer done()
	pkg := uploadZip(t, r)
	pid := uint(pkg["id"].(float64))

	w, out := doJSON(t, r, "POST", "/api/v1/services", map[string]any{
		"packageId": pid, "image": "agent-framework:latest",
		"env":        map[string]string{"LLM_API_KEY": "sk-rest", "MY_TOKEN": "tok", "LOG_LEVEL": "info"},
		"secretKeys": []string{"MY_TOKEN"},
	})
	if w.Code != http.StatusOK {
		t.Fatalf("publish: %d %s", w.Code, w.Body.String())
	}
	svc := out["data"].(map[string]interface{})
	if svc["envJson"] != `{"LOG_LEVEL":"info"}` {
		t.Fatalf("envJson should exclude routed keys, got %v", svc["envJson"])
	}
	k8sName := svc["k8sName"].(string)
	sobj, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.EnvSecretName(k8sName), metav1.GetOptions{})
	if err != nil {
		t.Fatalf("service secret missing: %v", err)
	}
	if string(sobj.Data["LLM_API_KEY"]) != "sk-rest" || string(sobj.Data["MY_TOKEN"]) != "tok" {
		t.Fatalf("service secret data wrong: %v", sobj.Data)
	}

	// 详情：envSecretKeys 掩码，响应不含敏感明文
	id := strconv.FormatUint(uint64(svc["id"].(float64)), 10)
	w, out = doJSON(t, r, "GET", "/api/v1/services/"+id, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("detail: %d", w.Code)
	}
	if strings.Contains(w.Body.String(), "sk-rest") {
		t.Fatal("detail must not leak sensitive value")
	}
	keys := out["data"].(map[string]interface{})["envSecretKeys"].([]interface{})
	found := false
	for _, k := range keys {
		if k.(map[string]interface{})["key"] == "LLM_API_KEY" {
			found = true
		}
	}
	if !found {
		t.Fatalf("envSecretKeys missing LLM_API_KEY: %+v", keys)
	}
}
