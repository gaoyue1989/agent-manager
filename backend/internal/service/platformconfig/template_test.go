package platformconfig

import (
	"testing"
)

// 模板敏感键集合（设计 §3.2 "Secret 只需要"的集合 + travel-fulfillment §8 平台端
// 协议敏感键）。
func TestSensitiveKeys(t *testing.T) {
	want := map[string]bool{
		"LLM_API_KEY": true, "CHECKPOINT_PASSWORD": true,
		"AGENT_REDIS_URL": true, "OPENSANDBOX_API_KEY": true,
		"AGENT_PROTOCOL_AUTH_TOKEN": true, "AGENT_REMOTE_HEADERS_JSON": true,
	}
	got := SensitiveKeys()
	if len(got) != len(want) {
		t.Fatalf("sensitive keys = %v, want %v", got, want)
	}
	for k := range want {
		if !got[k] {
			t.Errorf("%s should be sensitive", k)
		}
	}
	// 非敏感代表键不得误标
	for _, k := range []string{"LLM_BASE_URL", "LLM_TEMPERATURE", "CHECKPOINT_JDBC_URL", "OPENSANDBOX_SERVER_URL"} {
		if got[k] {
			t.Errorf("%s should not be sensitive", k)
		}
	}
}

// SANDBOX_ENABLED 明确排除：进入平台默认配置会使 OAF 包 frontmatter 的
// sandbox.enabled 三层裁决失效（env 显式存在优先）。
func TestSandboxEnabledExcluded(t *testing.T) {
	if KnownKeys()["SANDBOX_ENABLED"] {
		t.Fatal("SANDBOX_ENABLED must not enter the template")
	}
}

// 协议启用/存储开关按服务在发布 env 声明，不进平台默认配置（默认预填会给全部
// 服务开协议、扩大 /tasks 暴露面）；模板只收敏感两项（见 template.go protocol 组）。
func TestProtocolSwitchesExcluded(t *testing.T) {
	for _, k := range []string{"AGENT_PROTOCOL_ENABLED", "AGENT_PROTOCOL_TASK_STORE", "AGENT_PROTOCOL_TASK_RETENTION_DAYS"} {
		if KnownKeys()[k] {
			t.Errorf("%s must not enter the template", k)
		}
	}
}

// 必填键集合（清除校验依据）。
func TestRequiredKeys(t *testing.T) {
	for _, k := range []string{"LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL_ID",
		"CHECKPOINT_JDBC_URL", "CHECKPOINT_PASSWORD", "AGENT_REDIS_URL", "OPENSANDBOX_SERVER_URL"} {
		if f := Lookup(k); f == nil || !f.Required {
			t.Errorf("%s should be required", k)
		}
	}
	if f := Lookup("LLM_TEMPERATURE"); f != nil && f.Required {
		t.Error("LLM_TEMPERATURE should be optional")
	}
}

func TestSplit(t *testing.T) {
	values := map[string]string{
		"LLM_API_KEY":  "sk-1",
		"LOG_LEVEL":    "info",
		"MY_MCP_TOKEN": "tok",
		"LLM_BASE_URL": "http://x",
	}
	plain, secret := Split(values, map[string]bool{"MY_MCP_TOKEN": true})
	if len(plain) != 2 || plain["LOG_LEVEL"] != "info" || plain["LLM_BASE_URL"] != "http://x" {
		t.Fatalf("plain wrong: %+v", plain)
	}
	if len(secret) != 2 || secret["LLM_API_KEY"] != "sk-1" || secret["MY_MCP_TOKEN"] != "tok" {
		t.Fatalf("secret wrong: %+v", secret)
	}
	if len(plain)+len(secret) != len(values) {
		t.Fatal("split must be a partition")
	}
}
