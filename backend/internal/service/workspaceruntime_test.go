// 工作空间运行时默认配置单测（契约 §2.1）：键白名单 / 三态 / 掩码 / 分类 / 值校验 / config 组骨架。
// sqlite 内存库沿用 testkit_test.go 的 newTestCore（其迁移清单不含 2026-10 导出新表，
// 由本文件 newRuntimeCore 补齐，不动共享 testkit）。
package service

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

// newRuntimeCore 组装带导出三表迁移的测试 Core。
func newRuntimeCore(t *testing.T) (*Core, func()) {
	t.Helper()
	core, _, done := newTestCore(t)
	if err := core.DB.AutoMigrate(&store.ExportOrderEntity{}, &store.ExportPackageMarkEntity{},
		&store.WorkspaceRuntimeDefaultEntity{}); err != nil {
		t.Fatal(err)
	}
	return core, done
}

// TestRuntimeDefaultsKeySpace 键空间：32 键（24 模板键 − CHECKPOINT_JDBC_URL + 8 导出键，契约 §1.4）；
// L3 派生键 / 平台保留键 PUT 均 400 且错误信息点名键名。
func TestRuntimeDefaultsKeySpace(t *testing.T) {
	core, done := newRuntimeCore(t)
	defer done()

	if got := len(runtimeDefaultKeys); got != 32 {
		t.Fatalf("runtime default key space = %d, want 32 (24 template + 8 export)", got)
	}
	for _, k := range []string{"CHECKPOINT_JDBC_URL", "AGENT_REDIS_PREFIX"} {
		if _, ok := runtimeDefaultKeys[k]; ok {
			t.Fatalf("L3 derived key %q must be structurally excluded from key space", k)
		}
	}
	for _, k := range []string{"AGENT_CONFIG_DIR", "AGENT_WORKSPACE_DIR", "SERVER_HOST", "SERVER_PORT", "HOST_NAME"} {
		if _, ok := runtimeDefaultKeys[k]; ok {
			t.Fatalf("reserved key %q must be excluded from key space", k)
		}
	}
	if _, err := core.UpdateRuntimeDefaults(1, "ops", map[string]string{"NOT_IN_SPACE": "x"}); !errors.Is(err, ErrUnknownConfigKey) {
		t.Fatalf("unknown key: %v", err)
	}
	for _, k := range []string{"CHECKPOINT_JDBC_URL", "AGENT_REDIS_PREFIX", "AGENT_CONFIG_DIR", "SERVER_PORT"} {
		_, err := core.UpdateRuntimeDefaults(1, "ops", map[string]string{k: "x"})
		if !errors.Is(err, ErrUnknownConfigKey) || !strings.Contains(err.Error(), k) {
			t.Fatalf("key %q must be rejected naming the key: %v", k, err)
		}
	}
}

// TestRuntimeDefaultsThreeState 三态语义：非空=设置、空串=删除、缺失=不变（对齐 UpdatePlatformConfig）；
// 键值按 workspace_id 隔离。
func TestRuntimeDefaultsThreeState(t *testing.T) {
	core, done := newRuntimeCore(t)
	defer done()

	if _, err := core.UpdateRuntimeDefaults(1, "ops", nil); !errors.Is(err, ErrEmptyConfigValues) {
		t.Fatalf("empty values: %v", err)
	}
	if _, err := core.UpdateRuntimeDefaults(1, "ops", map[string]string{
		"CHECKPOINT_JDBC_BASE": "jdbc:mysql://mysql:3306/oaf_checkpoint",
		"REGISTRY_ADDRESS":     "172.20.0.1:5001",
		"LLM_TEMPERATURE":      "0.3",
	}); err != nil {
		t.Fatal(err)
	}
	// 三态：改一个、删一个、第三个缺失不动
	view, err := core.UpdateRuntimeDefaults(1, "ops", map[string]string{
		"CHECKPOINT_JDBC_BASE": "",
		"REGISTRY_ADDRESS":     "reg.example.com:5000",
	})
	if err != nil {
		t.Fatal(err)
	}
	if view["CHECKPOINT_JDBC_BASE"].HasValue {
		t.Fatal("empty string must delete the key")
	}
	if view["REGISTRY_ADDRESS"].Value != "reg.example.com:5000" {
		t.Fatalf("set: %+v", view["REGISTRY_ADDRESS"])
	}
	if v := view["LLM_TEMPERATURE"]; !v.HasValue || v.Value != "0.3" {
		t.Fatalf("missing key must stay unchanged: %+v", v)
	}
	var cnt int64
	core.DB.Model(&store.WorkspaceRuntimeDefaultEntity{}).Where("workspace_id = ?", 1).Count(&cnt)
	if cnt != 2 {
		t.Fatalf("rows = %d, want 2", cnt)
	}
	// 空间隔离：空间 2 看不到空间 1 的键
	view2, err := core.GetRuntimeDefaults(2)
	if err != nil {
		t.Fatal(err)
	}
	if view2["REGISTRY_ADDRESS"].HasValue {
		t.Fatal("workspace isolation broken")
	}
	// 唯一索引 uk_ws_runtime_default (workspace_id, env_key) 在迁移后真实生效
	err = core.DB.Create(&store.WorkspaceRuntimeDefaultEntity{
		WorkspaceID: 1, EnvKey: "REGISTRY_ADDRESS", Value: "dup",
	}).Error
	if err == nil {
		t.Fatal("duplicate (workspace_id, env_key) must violate uk_ws_runtime_default")
	}
}

// TestRuntimeDefaultsMasking 敏感掩码：敏感键仅 HasValue 不回值；JSON 序列化不漏明文；
// Sensitive 标记与 UpdatedBy 随行落库；未配置键也在视图中（配置页表单 schema 来源）。
func TestRuntimeDefaultsMasking(t *testing.T) {
	core, done := newRuntimeCore(t)
	defer done()

	view, err := core.UpdateRuntimeDefaults(1, "ops", map[string]string{
		"CHECKPOINT_PASSWORD": "super-secret-pw",
		"AGENT_REDIS_URL":     "redis://redis:6379",
		"REGISTRY_ADDRESS":    "172.20.0.1:5001",
	})
	if err != nil {
		t.Fatal(err)
	}
	if v := view["CHECKPOINT_PASSWORD"]; !v.HasValue || v.Value != "" || !v.Sensitive {
		t.Fatalf("sensitive key must mask: %+v", v)
	}
	if v := view["AGENT_REDIS_URL"]; !v.Sensitive || v.Value != "" {
		t.Fatalf("sensitive key must mask: %+v", v)
	}
	if v := view["REGISTRY_ADDRESS"]; v.Sensitive || v.Value != "172.20.0.1:5001" {
		t.Fatalf("plain key must echo value: %+v", v)
	}
	b, _ := json.Marshal(view)
	if strings.Contains(string(b), "super-secret-pw") {
		t.Fatal("sensitive value leaked in JSON")
	}
	var row store.WorkspaceRuntimeDefaultEntity
	if err := core.DB.Where("workspace_id = ? AND env_key = ?", 1, "CHECKPOINT_PASSWORD").First(&row).Error; err != nil {
		t.Fatal(err)
	}
	if !row.Sensitive || row.Value != "super-secret-pw" || row.UpdatedBy != "ops" {
		t.Fatalf("row wrong: sensitive=%v updatedBy=%q", row.Sensitive, row.UpdatedBy)
	}
	if v := view["LLM_API_KEY"]; !v.Sensitive || v.HasValue {
		t.Fatalf("unconfigured sensitive template key: %+v", v)
	}
}

// TestRuntimeDefaultsCategory 分类：导出键按契约 §1.4 表，模板键取 platformconfig 分组名。
func TestRuntimeDefaultsCategory(t *testing.T) {
	core, done := newRuntimeCore(t)
	defer done()

	view, err := core.UpdateRuntimeDefaults(1, "ops", map[string]string{
		"CHECKPOINT_JDBC_BASE":        "jdbc:mysql://mysql:3306/oaf_checkpoint",
		"OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4318",
		"REGISTRY_ADDRESS":            "reg:5000",
		"REGISTRY_NAMESPACE":          "oaf",
		"IMAGE_PULL_SECRETS":          "regcred",
		"ROUTING_MODE":                "host",
		"ROUTING_HOST_SUFFIX":         ".e2e.export.local",
		"BASE_IMAGE_WHITELIST":        "reg:5000/agent-framework:v1|Agent Framework v1",
	})
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{
		"CHECKPOINT_JDBC_BASE":        "mysql",
		"OTEL_EXPORTER_OTLP_ENDPOINT": "otel",
		"REGISTRY_ADDRESS":            "registry",
		"REGISTRY_NAMESPACE":          "registry",
		"IMAGE_PULL_SECRETS":          "registry",
		"ROUTING_MODE":                "routing",
		"ROUTING_HOST_SUFFIX":         "routing",
		"BASE_IMAGE_WHITELIST":        "runtime",
		"CHECKPOINT_PASSWORD":         "mysql", // 模板键取分组名（未配置也在视图）
		"OPENSANDBOX_SERVER_URL":      "sandbox",
		"AGENT_PROTOCOL_AUTH_TOKEN":   "protocol",
	}
	for k, cat := range want {
		if view[k].Category != cat {
			t.Fatalf("key %q category = %q, want %q", k, view[k].Category, cat)
		}
	}
}

// TestRuntimeDefaultsValueValidation 值校验：ROUTING_MODE 枚举、ROUTING_HOST_SUFFIX 同
// config.go:150-172 口径、BASE_IMAGE_WHITELIST 同 config.go:174-200 解析、32KB 上限；合法值放行。
func TestRuntimeDefaultsValueValidation(t *testing.T) {
	core, done := newRuntimeCore(t)
	defer done()

	longSuffix := "." + strings.Repeat("a", 63) + "." + strings.Repeat("b", 63) + "." + strings.Repeat("c", 63)
	cases := []struct {
		name string
		in   map[string]string
	}{
		{"routing mode enum", map[string]string{"ROUTING_MODE": "bogus"}},
		{"suffix no leading dot", map[string]string{"ROUTING_HOST_SUFFIX": "e2e.export.local"}},
		{"suffix invalid label", map[string]string{"ROUTING_HOST_SUFFIX": ".e2e_export.local"}},
		{"suffix too long", map[string]string{"ROUTING_HOST_SUFFIX": longSuffix}},
		{"whitelist empty ref", map[string]string{"BASE_IMAGE_WHITELIST": "|label only"}},
		{"value too large", map[string]string{"REGISTRY_ADDRESS": strings.Repeat("a", 32<<10+1)}},
	}
	for _, tc := range cases {
		if _, err := core.UpdateRuntimeDefaults(1, "ops", tc.in); err == nil {
			t.Fatalf("%s: expected error", tc.name)
		}
	}
	if _, err := core.UpdateRuntimeDefaults(1, "ops", map[string]string{
		"ROUTING_MODE":         "router",
		"ROUTING_HOST_SUFFIX":  ".e2e.export.local",
		"BASE_IMAGE_WHITELIST": "reg:5000/a:v1|A,reg:5000/b:v2",
	}); err != nil {
		t.Fatalf("valid values rejected: %v", err)
	}
}

// TestRuntimeTemplateGroup 模板组骨架：platformconfig 模板驱动（llm 10 键 / sandbox 6 键），
// 写入落 runtime-defaults 同一存储，敏感字段掩码；设计 §9 之外的分组 404；跨组键拒绝。
func TestRuntimeTemplateGroup(t *testing.T) {
	core, done := newRuntimeCore(t)
	defer done()

	if _, err := core.GetRuntimeTemplateGroup(1, "mysql"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("mysql group is out of templates scope (design §9), got %v", err)
	}
	llm, err := core.GetRuntimeTemplateGroup(1, "llm")
	if err != nil {
		t.Fatal(err)
	}
	if len(llm) != 10 {
		t.Fatalf("llm fields = %d, want 10", len(llm))
	}
	sbx, err := core.GetRuntimeTemplateGroup(1, "sandbox")
	if err != nil || len(sbx) != 6 {
		t.Fatalf("sandbox fields = %d err=%v, want 6", len(sbx), err)
	}
	if _, err := core.UpdateRuntimeTemplateGroup(1, "ops", "llm", map[string]string{
		"LLM_BASE_URL": "https://llm.example.com/v1",
	}); err != nil {
		t.Fatal(err)
	}
	llm, _ = core.GetRuntimeTemplateGroup(1, "llm")
	for _, f := range llm {
		if f.EnvKey == "LLM_BASE_URL" && (!f.HasValue || f.Value != "https://llm.example.com/v1") {
			t.Fatalf("group write not reflected: %+v", f)
		}
	}
	if _, err := core.UpdateRuntimeTemplateGroup(1, "ops", "llm", map[string]string{
		"REGISTRY_ADDRESS": "reg:5000",
	}); !errors.Is(err, ErrUnknownConfigKey) {
		t.Fatalf("cross-group key must be rejected: %v", err)
	}
	if _, err := core.UpdateRuntimeTemplateGroup(1, "ops", "llm", map[string]string{"LLM_API_KEY": "sk-x"}); err != nil {
		t.Fatal(err)
	}
	llm, _ = core.GetRuntimeTemplateGroup(1, "llm")
	for _, f := range llm {
		if f.EnvKey == "LLM_API_KEY" && (f.Value != "" || !f.HasValue) {
			t.Fatalf("group sensitive field must mask: %+v", f)
		}
	}
}

// TestRuntimeRegistryConfig registry 组骨架：registry/routing/runtime 三分类视图与受限写入。
func TestRuntimeRegistryConfig(t *testing.T) {
	core, done := newRuntimeCore(t)
	defer done()

	if _, err := core.UpdateRuntimeRegistryConfig(1, "ops", map[string]string{"LLM_API_KEY": "sk"}); !errors.Is(err, ErrUnknownConfigKey) {
		t.Fatalf("llm key must be rejected in registry group: %v", err)
	}
	if _, err := core.UpdateRuntimeRegistryConfig(1, "ops", map[string]string{
		"REGISTRY_ADDRESS":     "172.20.0.1:5001",
		"BASE_IMAGE_WHITELIST": "172.20.0.1:5001/agent-framework:v1|AF v1",
	}); err != nil {
		t.Fatal(err)
	}
	view, err := core.GetRuntimeRegistryConfig(1)
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"REGISTRY_ADDRESS", "REGISTRY_NAMESPACE", "IMAGE_PULL_SECRETS",
		"ROUTING_MODE", "ROUTING_HOST_SUFFIX", "BASE_IMAGE_WHITELIST"} {
		if _, ok := view[k]; !ok {
			t.Fatalf("registry view missing %q: %+v", k, view)
		}
	}
	for _, k := range []string{"LLM_API_KEY", "CHECKPOINT_PASSWORD", "CHECKPOINT_JDBC_BASE", "OTEL_EXPORTER_OTLP_ENDPOINT"} {
		if _, ok := view[k]; ok {
			t.Fatalf("registry view must exclude %q", k)
		}
	}
	if view["REGISTRY_ADDRESS"].Value != "172.20.0.1:5001" {
		t.Fatalf("registry value: %+v", view["REGISTRY_ADDRESS"])
	}
}
