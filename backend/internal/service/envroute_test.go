package service

import (
	"reflect"
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
