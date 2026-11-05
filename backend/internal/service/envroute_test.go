package service

import (
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"
)

func TestSplitUserEnv(t *testing.T) {
	plain, secret := splitUserEnv(map[string]string{
		"LLM_API_KEY": "sk-1", "LOG_LEVEL": "info", "MY_TOKEN": "t",
	}, []string{"MY_TOKEN"})
	if !reflect.DeepEqual(plain, map[string]string{"LOG_LEVEL": "info"}) {
		t.Fatalf("plain: %+v", plain)
	}
	if !reflect.DeepEqual(secret, map[string]string{"LLM_API_KEY": "sk-1", "MY_TOKEN": "t"}) {
		t.Fatalf("secret: %+v", secret)
	}
}

// sticky：传入未出现的存量敏感键保持不变；空串=显式删除且优先于迁移。
func TestResolveEnvMergeStickyAndDelete(t *testing.T) {
	oldPlain := map[string]string{"LOG_LEVEL": "info"}
	oldSecret := map[string]string{"LLM_API_KEY": "sk-old", "OPENSANDBOX_API_KEY": "sbx"}
	plain, secret := resolveEnvMerge(oldPlain, oldSecret,
		map[string]string{"LOG_LEVEL": "debug", "OPENSANDBOX_API_KEY": ""}, nil)
	if !reflect.DeepEqual(plain, map[string]string{"LOG_LEVEL": "debug"}) {
		t.Fatalf("plain should be fully replaced: %+v", plain)
	}
	if !reflect.DeepEqual(secret, map[string]string{"LLM_API_KEY": "sk-old"}) {
		t.Fatalf("sticky keep + explicit delete wrong: %+v", secret)
	}
}

// 存量迁移：旧 env_json 中的模板敏感键并入敏感份（任意一次写路径完成迁移）。
func TestResolveEnvMergeMigratesLegacyKeys(t *testing.T) {
	oldPlain := map[string]string{"LOG_LEVEL": "info", "LLM_API_KEY": "sk-legacy"}
	oldSecret := map[string]string{}
	plain, secret := resolveEnvMerge(oldPlain, oldSecret,
		map[string]string{"LOG_LEVEL": "debug"}, nil)
	if _, ok := plain["LLM_API_KEY"]; ok {
		t.Fatal("legacy sensitive key must leave plain")
	}
	if secret["LLM_API_KEY"] != "sk-legacy" {
		t.Fatalf("legacy key must migrate to secret: %+v", secret)
	}
}

// 显式空串删除优先于存量迁移（用户明确要求回落平台默认）。
func TestResolveEnvMergeDeleteBeatsMigration(t *testing.T) {
	oldPlain := map[string]string{"LLM_API_KEY": "sk-legacy"}
	_, secret := resolveEnvMerge(oldPlain, map[string]string{},
		map[string]string{"LLM_API_KEY": ""}, nil)
	if _, ok := secret["LLM_API_KEY"]; ok {
		t.Fatalf("explicit delete must win over migration: %+v", secret)
	}
}

// secretKeys 强制路由的键同样走三态合并。
func TestResolveEnvMergeForceKeys(t *testing.T) {
	oldSecret := map[string]string{"MY_TOKEN": "old"}
	plain, secret := resolveEnvMerge(map[string]string{}, oldSecret,
		map[string]string{"MY_TOKEN": "new", "OTHER": "x"}, []string{"MY_TOKEN"})
	if plain["MY_TOKEN"] != "" || plain["OTHER"] != "x" {
		t.Fatalf("force key must not stay in plain: %+v", plain)
	}
	if secret["MY_TOKEN"] != "new" {
		t.Fatalf("force key set wrong: %+v", secret)
	}
}

// 非敏感份空串=不设置：plain 全量覆盖语义下空串键不落 CM/env_json（设计 §3.3，
// 与 secret 份空串删除对齐；issue #97 此前仅 secret 份处理，非敏感空串键会原样落库）。
func TestResolveEnvMergeDropsEmptyPlainKeys(t *testing.T) {
	plain, secret := resolveEnvMerge(
		map[string]string{"LOG_LEVEL": "info"},
		map[string]string{"LLM_API_KEY": "sk-old"},
		map[string]string{"LOG_LEVEL": "debug", "EMPTY_KEY": "", "OTHER": "x"}, nil)
	if _, ok := plain["EMPTY_KEY"]; ok {
		t.Fatalf("empty plain key must not fall into CM/env_json: %+v", plain)
	}
	if plain["LOG_LEVEL"] != "debug" || plain["OTHER"] != "x" {
		t.Fatalf("non-empty plain keys must be kept: %+v", plain)
	}
	if secret["LLM_API_KEY"] != "sk-old" {
		t.Fatalf("sticky secret must stay: %+v", secret)
	}
}

// validateSecretKeys 输入规模校验（issue #234）：数量上限对齐 MaxEnvKeys、重复键显式拒绝，
// 新错误均 %w wrap ErrInvalidForm 保证 handler 400；既有格式/保留键校验不回归。
func TestValidateSecretKeys(t *testing.T) {
	// 边界正例：恰好 64 键通过
	keys64 := make([]string, 0, MaxEnvKeys)
	for i := 0; i < MaxEnvKeys; i++ {
		keys64 = append(keys64, fmt.Sprintf("MY_KEY_%d", i))
	}
	if err := validateSecretKeys(keys64); err != nil {
		t.Fatalf("64 keys must pass: %v", err)
	}
	// 65 键 → ErrInvalidForm（数量上限）
	keys65 := append(append([]string{}, keys64...), "MY_KEY_EXTRA")
	err := validateSecretKeys(keys65)
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("65 keys must wrap ErrInvalidForm: %v", err)
	}
	if !containsAll(err.Error(), "too many secret keys") {
		t.Fatalf("65 keys message = %q, want 'too many secret keys'", err.Error())
	}
	// 重复键 → ErrInvalidForm（显式拒绝，不静默去重）
	err = validateSecretKeys([]string{"MY_TOKEN", "MY_TOKEN"})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("duplicate keys must wrap ErrInvalidForm: %v", err)
	}
	if !containsAll(err.Error(), "duplicate secret key") {
		t.Fatalf("duplicate message = %q, want 'duplicate secret key'", err.Error())
	}
	// 既有校验不回归：非法格式（不带 ErrInvalidForm 包装，靠 respond.go "env key" 子串 400）
	err = validateSecretKeys([]string{"illegal-key"})
	if err == nil || errors.Is(err, ErrInvalidForm) {
		t.Fatalf("illegal key: err = %v", err)
	}
	// 保留键（平台保留键出现在 secretKeys 同样拒绝）
	err = validateSecretKeys([]string{"AGENT_CONFIG_DIR"})
	if err == nil || errors.Is(err, ErrInvalidForm) {
		t.Fatalf("reserved key: err = %v", err)
	}
}

// containsAll 断言消息包含全部子串（测试辅助）。
func containsAll(s string, subs ...string) bool {
	for _, sub := range subs {
		if !strings.Contains(s, sub) {
			return false
		}
	}
	return true
}
