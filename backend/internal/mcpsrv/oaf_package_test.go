package mcpsrv

import (
	"strings"
	"testing"
)

// 平移自 agent-framework OafPackageToolsTest 的行为基线（助手侧契约不变）。
const validAgentsMD = `---
name: weather-agent
vendorKey: acme
agentKey: weather-agent
version: 1.0.0
description: demo agent package
author: "@acme"
license: MIT
---
body`

func TestMCPCheckOafPackageValid(t *testing.T) {
	cs, _, done := newMCPClient(t)
	defer done()

	isErr, out, _ := call(t, cs, "check_oaf_package", map[string]any{"agents_md": validAgentsMD})
	if isErr {
		t.Fatalf("check_oaf_package: %v", out)
	}
	if out["valid"] != true {
		t.Fatalf("valid=%v want true (raw: %v)", out["valid"], out)
	}
	if len(out["missing"].([]any)) != 0 || len(out["invalid"].([]any)) != 0 {
		t.Fatalf("missing/invalid should be empty: %v", out)
	}
	present := out["present"].([]any)
	joined := strings.Join(toStrings(present), ",")
	for _, k := range []string{"name", "vendorKey", "agentKey", "version"} {
		if !strings.Contains(joined, k) {
			t.Fatalf("present should contain %s: %v", k, present)
		}
	}
}

func TestMCPCheckOafPackageMissingAndInvalid(t *testing.T) {
	cs, _, done := newMCPClient(t)
	defer done()

	md := "---\nname: Weather_Agent\nvendorKey: acme\nagentKey: weather\nversion: 1.0\n---\nbody"
	isErr, out, _ := call(t, cs, "check_oaf_package", map[string]any{"agents_md": md})
	if isErr {
		t.Fatalf("check_oaf_package protocol error: %v", out)
	}
	if out["valid"] != false {
		t.Fatalf("valid=%v want false", out["valid"])
	}
	missing := strings.Join(toStrings(out["missing"].([]any)), ",")
	for _, k := range []string{"description", "author", "license"} {
		if !strings.Contains(missing, k) {
			t.Fatalf("missing should contain %s: %v", k, out["missing"])
		}
	}
	invalid := strings.Join(toStrings(out["invalid"].([]any)), ",")
	if !strings.Contains(invalid, "name") {
		t.Fatalf("invalid should flag name kebab: %v", out["invalid"])
	}
	if !strings.Contains(invalid, "version") {
		t.Fatalf("invalid should flag version semver: %v", out["invalid"])
	}
}

func TestMCPCheckOafPackageNoFrontmatterOrEmpty(t *testing.T) {
	cs, _, done := newMCPClient(t)
	defer done()

	_, out, _ := call(t, cs, "check_oaf_package", map[string]any{"agents_md": "# 只有正文没有 frontmatter"})
	if out["valid"] != false {
		t.Fatalf("no-frontmatter valid=%v want false", out["valid"])
	}
	invalid := strings.Join(toStrings(out["invalid"].([]any)), ",")
	if !strings.Contains(invalid, "frontmatter") {
		t.Fatalf("invalid should mention frontmatter: %v", out["invalid"])
	}

	_, out, _ = call(t, cs, "check_oaf_package", map[string]any{"agents_md": "  "})
	missing := strings.Join(toStrings(out["missing"].([]any)), ",")
	if !strings.Contains(missing, "AGENTS.md 内容为空") {
		t.Fatalf("missing should flag empty content: %v", out["missing"])
	}
}

func TestMCPCreateOafZipRegistersPackage(t *testing.T) {
	cs, core, done := newMCPClient(t)
	defer done()

	isErr, out, _ := call(t, cs, "create_oaf_zip", map[string]any{
		"package_name": "weather-agent",
		"agents_md":    validAgentsMD,
		"extra_files":  []map[string]any{{"path": "skills/help/SKILL.md", "content": "# help\n"}},
	})
	if isErr {
		t.Fatalf("create_oaf_zip: %v", out)
	}
	pkgID := uint(out["packageId"].(float64))
	if pkgID == 0 {
		t.Fatalf("packageId should be > 0: %v", out)
	}
	if out["file_name"] != "weather-agent.zip" {
		t.Fatalf("file_name=%v want weather-agent.zip", out["file_name"])
	}
	url, _ := out["download_url"].(string)
	if !strings.HasSuffix(url, "/api/v1/packages/1/download") {
		t.Fatalf("download_url=%v want suffix /api/v1/packages/1/download", url)
	}
	if size := out["size"].(float64); size <= 0 {
		t.Fatalf("size should be > 0: %v", out["size"])
	}
	// 包内容核验：AGENTS.md 原文 + 附加文件
	pkg, err := core.Packages.Get(pkgID)
	if err != nil {
		t.Fatalf("package %d not found: %v", pkgID, err)
	}
	if pkg.Slug != "acme/weather-agent" {
		t.Fatalf("slug=%v", pkg.Slug)
	}
	if md, _ := core.Packages.ReadFile(pkg, "AGENTS.md"); string(md) != validAgentsMD {
		t.Fatalf("AGENTS.md mismatch in stored package")
	}
	if f, _ := core.Packages.ReadFile(pkg, "skills/help/SKILL.md"); string(f) != "# help\n" {
		t.Fatalf("extra file mismatch: %q", string(f))
	}
}

func TestMCPCreateOafZipRejectsInvalid(t *testing.T) {
	cs, core, done := newMCPClient(t)
	defer done()

	// frontmatter 非法（name 非 kebab）
	isErr, _, raw := call(t, cs, "create_oaf_zip", map[string]any{
		"package_name": "bad.zip", "agents_md": "---\nname: Bad\nvendorKey: v\nagentKey: a\nversion: 1.0.0\ndescription: d\nauthor: x\nlicense: MIT\n---\nbody",
	})
	if !isErr || !strings.Contains(raw, "校验未通过") {
		t.Fatalf("invalid frontmatter should be rejected, got isErr=%v raw=%s", isErr, raw)
	}

	// 空 agents_md
	isErr, _, _ = call(t, cs, "create_oaf_zip", map[string]any{"agents_md": " "})
	if !isErr {
		t.Fatal("empty agents_md should be rejected")
	}

	// 路径逃逸
	isErr, _, raw = call(t, cs, "create_oaf_zip", map[string]any{
		"agents_md": validAgentsMD,
		"extra_files": []map[string]any{
			{"path": "../escape.txt", "content": "x"},
		},
	})
	if !isErr || !strings.Contains(raw, "escape") {
		t.Fatalf("../ path should be rejected, got isErr=%v raw=%s", isErr, raw)
	}

	// 重复条目（再次写 AGENTS.md）
	isErr, _, raw = call(t, cs, "create_oaf_zip", map[string]any{
		"agents_md": validAgentsMD,
		"extra_files": []map[string]any{
			{"path": "AGENTS.md", "content": "override"},
		},
	})
	if !isErr || !strings.Contains(raw, "duplicate") {
		t.Fatalf("duplicate AGENTS.md should be rejected, got isErr=%v raw=%s", isErr, raw)
	}

	// 全部失败场景均不应落数据库
	var cnt int64
	core.DB.Table("oaf_packages").Count(&cnt)
	if cnt != 0 {
		t.Fatalf("rejected calls must not create packages, got %d", cnt)
	}
}

func toStrings(list []any) []string {
	out := make([]string, 0, len(list))
	for _, v := range list {
		out = append(out, v.(string))
	}
	return out
}
