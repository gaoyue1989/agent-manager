package oaf

import (
	"strings"
	"testing"
)

func mdWith(kv map[string]string) string {
	var b strings.Builder
	b.WriteString("---\n")
	for k, v := range kv {
		if strings.HasPrefix(v, "@") {
			v = `"` + v + `"` // YAML 保留指示符 @ 需引号包裹（与平台夹具一致）
		}
		b.WriteString(k + ": " + v + "\n")
	}
	b.WriteString("---\nbody")
	return b.String()
}

func baseKV() map[string]string {
	return map[string]string{
		"name": "weather-agent", "vendorKey": "acme", "agentKey": "weather",
		"version": "1.0.0", "description": "d", "author": "@acme", "license": "MIT",
	}
}

// 正则平移基线：助手侧契约与平台 Validate 存在有意差异（见 oaf_check.go 头注释），
// 此处锁定迁移前 Java OafPackageTools 的行为。
func TestCheckAgentsMDJavaParity(t *testing.T) {
	// name 数字开头：助手侧允许（Java 正则 ^[a-z0-9]+...），平台 Validate 不校验 name
	kv := baseKV()
	kv["name"] = "1weather"
	if r := CheckAgentsMD(mdWith(kv)); !r.Valid {
		t.Fatalf("leading-digit name should pass tool check (Java parity): %+v", r)
	}
	// semver 带 build 后缀：助手侧允许
	kv = baseKV()
	kv["version"] = "1.0.0+build.1"
	if r := CheckAgentsMD(mdWith(kv)); !r.Valid {
		t.Fatalf("semver build suffix should pass tool check (Java parity): %+v", r)
	}
	// name 非 kebab：助手侧校验（平台 Validate 不校验 name）
	kv = baseKV()
	kv["name"] = "Weather_Agent"
	if r := CheckAgentsMD(mdWith(kv)); r.Valid || !strings.Contains(strings.Join(r.Invalid, ","), "name") {
		t.Fatalf("non-kebab name should be invalid: %+v", r)
	}
	// vendorKey 非 kebab
	kv = baseKV()
	kv["vendorKey"] = "Acme"
	if r := CheckAgentsMD(mdWith(kv)); r.Valid {
		t.Fatalf("non-kebab vendorKey should be invalid: %+v", r)
	}
	// 缺失字段聚合
	kv = baseKV()
	delete(kv, "author")
	delete(kv, "license")
	r := CheckAgentsMD(mdWith(kv))
	if r.Valid || len(r.Missing) != 2 {
		t.Fatalf("missing should aggregate author+license: %+v", r)
	}
	// 引号值正常解出（Java 侧靠手工去引号，此处真 YAML）
	kv = baseKV()
	kv["description"] = `"quoted desc"`
	if r := CheckAgentsMD(mdWith(kv)); !r.Valid || len(r.Present) < 7 {
		t.Fatalf("quoted scalar should parse: %+v", r)
	}
}

// 字段类型异常（结构合法但标量位出现序列）不得 panic，须返回 invalid（迁移自查发现的 nil 解回归）
func TestCheckAgentsMDTypeMismatchNoPanic(t *testing.T) {
	r := CheckAgentsMD("---\nname: x\nversion: [1, 2]\nvendorKey: v\nagentKey: a\n---\nbody")
	if r.Valid {
		t.Fatalf("type mismatch must be invalid: %+v", r)
	}
	joined := strings.Join(r.Invalid, ",")
	if !strings.Contains(joined, "类型异常") {
		t.Fatalf("invalid should mention type error: %+v", r)
	}
	// MCP 工具层 create_oaf_zip 同样依赖该前置校验，非法类型走同一短路
}
