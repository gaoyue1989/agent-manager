package oaf

import (
	"strings"
	"testing"
)

const validMD = `---
name: "Demo Agent"
vendorKey: "acme"
agentKey: "demo"
version: "1.0.0"
slug: "acme/demo"
description: "A demo agent for testing purposes"
author: "@acme"
license: "MIT"
tags: ["demo"]

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
---

# Demo

instructions here.
`

func TestParseOAFValid(t *testing.T) {
	cfg, err := ParseOAF(validMD)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if cfg.Name != "Demo Agent" || cfg.Slug != "acme/demo" || cfg.Version != "1.0.0" {
		t.Fatalf("unexpected identity: %+v", cfg)
	}
	if len(cfg.MCPServers) != 1 || cfg.MCPServers[0].ConfigDir != "mcp-configs/filesystem" {
		t.Fatalf("unexpected mcpServers: %+v", cfg.MCPServers)
	}
	if cfg.Instructions != "# Demo\n\ninstructions here." {
		t.Fatalf("instructions parse wrong: %q", cfg.Instructions)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("validate: %v", err)
	}
}

func TestParseOAFSlugDerived(t *testing.T) {
	md := `---
name: X
vendorKey: a-b
agentKey: c-d
version: 1.2.3
description: d
author: me
license: MIT
---
body`
	cfg, err := ParseOAF(md)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Slug != "a-b/c-d" {
		t.Fatalf("slug not derived: %q", cfg.Slug)
	}
}

func TestValidateMissingFields(t *testing.T) {
	cases := map[string]string{
		"name":        "---\nvendorKey: v\nagentKey: a\nversion: 1.0.0\ndescription: d\nauthor: x\nlicense: MIT\n---\n",
		"vendorKey":   "---\nname: n\nagentKey: a\nversion: 1.0.0\ndescription: d\nauthor: x\nlicense: MIT\n---\n",
		"description": "---\nname: n\nvendorKey: v\nagentKey: a\nversion: 1.0.0\nauthor: x\nlicense: MIT\n---\n",
		"license":     "---\nname: n\nvendorKey: v\nagentKey: a\nversion: 1.0.0\ndescription: d\nauthor: x\n---\n",
	}
	for field, md := range cases {
		cfg, err := ParseOAF(md)
		if err != nil {
			t.Fatalf("%s: parse: %v", field, err)
		}
		if err := cfg.Validate(); err == nil {
			t.Errorf("%s should be required", field)
		}
	}
}

func TestValidateFormats(t *testing.T) {
	base := map[string]string{
		"name": "n", "vendorKey": "v", "agentKey": "a", "version": "1.0.0",
		"description": "d", "author": "x", "license": "MIT",
	}
	mk := func(mut func(map[string]string)) *Config {
		m := map[string]string{}
		for k, v := range base {
			m[k] = v
		}
		mut(m)
		return &Config{Name: m["name"], VendorKey: m["vendorKey"], AgentKey: m["agentKey"],
			Version: m["version"], Description: m["description"], Author: m["author"], License: m["license"]}
	}
	if err := mk(func(m map[string]string) { m["vendorKey"] = "Bad_Key" }).Validate(); err == nil {
		t.Error("non-kebab vendorKey should fail")
	}
	if err := mk(func(m map[string]string) { m["version"] = "1.0" }).Validate(); err == nil {
		t.Error("bad semver should fail")
	}
	if err := mk(func(m map[string]string) { m["version"] = "1.0.0-rc.1" }).Validate(); err != nil {
		t.Errorf("prerelease semver should pass: %v", err)
	}
}

func TestWarnings(t *testing.T) {
	cfg, _ := ParseOAF(validMD)
	if w := cfg.Warnings(); len(w) != 0 {
		t.Fatalf("no warnings expected, got %v", w)
	}
	cfg.MCPServers[0].ConfigDir = ""
	if w := cfg.Warnings(); len(w) != 1 {
		t.Fatalf("expected empty-configDir warning, got %v", w)
	}
}

func TestParseOAFNoFrontmatter(t *testing.T) {
	if _, err := ParseOAF("# just markdown"); err == nil {
		t.Fatal("expected error for missing frontmatter")
	}
}

// agentKey 与 vendorKey 同样强制 kebab-case。
func TestValidateAgentKeyKebab(t *testing.T) {
	cfg, _ := ParseOAF(validMD)
	cfg.AgentKey = "Demo"
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "agentKey must be kebab-case") {
		t.Fatalf("expect agentKey kebab error, got %v", err)
	}
}

// 嵌套 model.name 不得覆盖顶层 name（与 agent-framework 33f1aa1 修复语义对齐）。
func TestParseOAFNestedModelKeepsTopLevelName(t *testing.T) {
	md := strings.Replace(validMD, "version: \"1.0.0\"",
		"version: \"1.0.0\"\nmodel:\n  provider: openai\n  name: should-not-override", 1)
	cfg, err := ParseOAF(md)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Name != "Demo Agent" {
		t.Fatalf("top-level name overridden: %q", cfg.Name)
	}
	m, ok := cfg.Model.(map[string]any)
	if !ok || m["name"] != "should-not-override" {
		t.Fatalf("model section lost: %#v", cfg.Model)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("validate with nested model: %v", err)
	}
}

// frontmatter 未闭合（仅一个 ---）必须报错。
func TestParseOAFUnclosedFrontmatter(t *testing.T) {
	if _, err := ParseOAF("---\nname: Demo\n"); err == nil {
		t.Fatal("unclosed frontmatter should error")
	}
}

// frontmatter 非法 YAML 必须报错并带上下文。
func TestParseOAFBadYAML(t *testing.T) {
	if _, err := ParseOAF("---\n: :\n  - [\n---\nbody"); err == nil ||
		!strings.Contains(err.Error(), "parse frontmatter") {
		t.Fatalf("expect frontmatter parse error, got %v", err)
	}
}

// CRLF 行尾的 frontmatter 可正常解析（Windows 编辑器产出的包）。
func TestParseOAFCRLF(t *testing.T) {
	cfg, err := ParseOAF(strings.ReplaceAll(validMD, "\n", "\r\n"))
	if err != nil {
		t.Fatalf("CRLF parse: %v", err)
	}
	if cfg.VendorKey != "acme" || cfg.Slug != "acme/demo" {
		t.Fatalf("CRLF fields wrong: %+v", cfg)
	}
}

// Warnings：local 技能缺 name 提示；命名完整不提示。
func TestWarningsLocalSkillName(t *testing.T) {
	cfg, _ := ParseOAF(validMD)
	cfg.Skills = []Skill{{Source: "local"}}
	if w := cfg.Warnings(); len(w) != 1 || !strings.Contains(w[0], "empty local name") {
		t.Fatalf("expected local skill warning, got %v", w)
	}
	cfg.Skills = []Skill{{Source: "local", Name: "echo"}}
	if w := cfg.Warnings(); len(w) != 0 {
		t.Fatalf("named local skill should be clean, got %v", w)
	}
	cfg.Skills = []Skill{{Source: "registry"}} // 非 local 来源不查 name
	if w := cfg.Warnings(); len(w) != 0 {
		t.Fatalf("non-local skill should be skipped, got %v", w)
	}
}
