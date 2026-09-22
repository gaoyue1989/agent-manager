package service

import (
	"strings"
	"testing"
)

// TestCreateVersionUpsertAndDelete 编辑派生主链路：改 AGENTS.md + 删文件 + 新增文件。
func TestCreateVersionUpsertAndDelete(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")

	// 改 AGENTS.md（升版本）+ 删 config.yaml + 新增 skills/new/SKILL.md
	newMD := replaceVersion(testAgentsMD, "1.1.0")
	rec, warnings, err := c.Packages.CreateVersion(base, VersionRequest{
		Upserts: []VersionUpsert{
			{Path: "AGENTS.md", Content: newMD},
			{Path: "skills/new/SKILL.md", Content: "---\nname: new\n---\n# new"},
		},
		Deletes: []string{"mcp-configs/fs/config.yaml"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if rec.ID == base.ID || rec.SourcePackageID != base.ID {
		t.Fatalf("new rec id=%d base=%d source=%d", rec.ID, base.ID, rec.SourcePackageID)
	}
	if rec.Version != "1.1.0" || rec.Slug != "acme/demo" {
		t.Fatalf("slug/version = %s@%s", rec.Slug, rec.Version)
	}
	// configDir mcp-configs/fs 已删 → warning 提示（宽松模式不阻断）
	if len(warnings) == 0 {
		t.Fatal("expected warning for missing mcp configDir")
	}
	// 基础包必须完全不变（copy-on-write）
	if md, _ := c.Packages.ReadFile(base, "AGENTS.md"); string(md) != testAgentsMD {
		t.Fatal("base package AGENTS.md was mutated")
	}
	if _, err := c.Packages.ReadFile(base, "mcp-configs/fs/config.yaml"); err != nil {
		t.Fatal("base package file was deleted")
	}
	// 新包内容正确
	if md, _ := c.Packages.ReadFile(rec, "AGENTS.md"); string(md) != newMD {
		t.Fatal("new AGENTS.md content mismatch")
	}
	if _, err := c.Packages.ReadFile(rec, "mcp-configs/fs/config.yaml"); err == nil {
		t.Fatal("deleted file still present in new package")
	}
	if sk, _ := c.Packages.ReadFile(rec, "skills/new/SKILL.md"); len(sk) == 0 {
		t.Fatal("new skill file missing")
	}
	// 基础包 2 文件（AGENTS.md + mcp config），删 1 增 1 → 仍为 2
	if rec.FileCount != 2 {
		t.Fatalf("fileCount=%d, want 2", rec.FileCount)
	}
}

// TestCreateVersionNoChange 无有效变更拒绝。
func TestCreateVersionNoChange(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	md, _ := c.Packages.ReadFile(base, "AGENTS.md")
	if _, _, err := c.Packages.CreateVersion(base, VersionRequest{
		Upserts: []VersionUpsert{{Path: "AGENTS.md", Content: string(md)}},
	}); err != ErrNoEffectiveChanges {
		t.Fatalf("want ErrNoEffectiveChanges, got %v", err)
	}
	// 删除不存在的文件也算无变更
	if _, _, err := c.Packages.CreateVersion(base, VersionRequest{
		Deletes: []string{"not/exist.txt"},
	}); err != ErrNoEffectiveChanges {
		t.Fatalf("want ErrNoEffectiveChanges (missing delete), got %v", err)
	}
}

// TestCreateVersionChecksumMismatch 乐观锁：checksum 不符拒绝。
func TestCreateVersionChecksumMismatch(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	if _, _, err := c.Packages.CreateVersion(base, VersionRequest{
		Upserts:              []VersionUpsert{{Path: "AGENTS.md", Content: "x"}},
		ExpectedBaseChecksum: "deadbeef",
	}); err != ErrChecksumMismatch {
		t.Fatalf("want ErrChecksumMismatch, got %v", err)
	}
}

// TestCreateVersionRejectsIllegalInput 路径/编码/删除保护等非法输入。
func TestCreateVersionRejectsIllegalInput(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	cases := []struct {
		name string
		req  VersionRequest
	}{
		{"upsert zip-slip", VersionRequest{Upserts: []VersionUpsert{{Path: "../evil", Content: "x"}}}},
		{"upsert backslash", VersionRequest{Upserts: []VersionUpsert{{Path: "a\\b.txt", Content: "x"}}}},
		{"delete AGENTS.md", VersionRequest{Deletes: []string{"AGENTS.md"}}},
		{"bad encoding", VersionRequest{Upserts: []VersionUpsert{{Path: "a.txt", Content: "x", Encoding: "rot13"}}}},
		{"bad base64", VersionRequest{Upserts: []VersionUpsert{{Path: "a.txt", Content: "!!!", Encoding: "base64"}}}},
		{"invalid frontmatter", VersionRequest{Upserts: []VersionUpsert{{Path: "AGENTS.md", Content: "no frontmatter"}}}},
		{"invalid semver", VersionRequest{Upserts: []VersionUpsert{{Path: "AGENTS.md", Content: replaceVersion(testAgentsMD, "not-semver")}}}},
	}
	for _, tc := range cases {
		if _, _, err := c.Packages.CreateVersion(base, tc.req); err == nil {
			t.Errorf("%s: want error, got nil", tc.name)
		}
	}
}

// TestCreateVersionInvalidYAML 校验错误原样回传（前端回显依赖错误消息可读）。
func TestCreateVersionInvalidYAML(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	_, _, err := c.Packages.CreateVersion(base, VersionRequest{
		Upserts: []VersionUpsert{{Path: "AGENTS.md", Content: "---\nname: [unclosed\n---\nbody"}},
	})
	if err == nil {
		t.Fatal("want error for broken yaml")
	}
}

// TestCreateVersionDupVersionWarning 同 slug+version 已有其他包时追加 warning。
func TestCreateVersionDupVersionWarning(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	// 直接再上传同 slug+version 的包（绕过 slug 辅助函数限制）
	dupZip := buildTestZip(map[string]string{"AGENTS.md": testAgentsMD})
	if _, err := c.Packages.Upload("dup.zip", bytesReader(dupZip)); err != nil {
		t.Fatal(err)
	}
	rec, warnings, err := c.Packages.CreateVersion(base, VersionRequest{
		Upserts: []VersionUpsert{{Path: "README.md", Content: "changed"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	// 生成的新版本 slug+version 与 dup 包相同 → 应有重复提示
	found := false
	for _, w := range warnings {
		if strings.Contains(w, "already exists") {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected dup warning, got %v (rec version=%s slug=%s)", warnings, rec.Version, rec.Slug)
	}
}

// TestPackageFileContent 预览：文本与二进制判定。
func TestPackageFileContent(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	fc, err := c.Packages.FileContent(base, "mcp-configs/fs/config.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if fc.Binary || fc.Content == "" {
		t.Fatalf("yaml should preview as text: %+v", fc)
	}
	// 路径穿越拒绝
	if _, err := c.Packages.FileContent(base, "../../etc/passwd"); err == nil {
		t.Fatal("zip-slip path should be rejected")
	}
	// 不存在的文件
	if _, err := c.Packages.FileContent(base, "no/such.txt"); err == nil {
		t.Fatal("missing file should error")
	}
}

// TestPackageZip 整包打包可再次通过上传管线。
func TestPackageZip(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	data, err := c.Packages.Zip(base)
	if err != nil {
		t.Fatal(err)
	}
	re, err := c.Packages.Upload("repack.zip", bytesReader(data))
	if err != nil {
		t.Fatalf("repackaged zip should pass upload pipeline: %v", err)
	}
	if re.Slug != base.Slug {
		t.Fatalf("slug mismatch %s vs %s", re.Slug, base.Slug)
	}
}

// TestListFilterBySlug slug 过滤（版本历史）。
func TestListFilterBySlug(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	_ = uploadTestPkg(t, c, "")
	_ = uploadTestPkg(t, c, "2")
	all, err := c.Packages.List("", "")
	if err != nil || len(all) != 2 {
		t.Fatalf("all=%d err=%v", len(all), err)
	}
	only, err := c.Packages.List("", "acme/demo2")
	if err != nil || len(only) != 1 || only[0].Slug != "acme/demo2" {
		t.Fatalf("slug filter: %d %v", len(only), err)
	}
}

// replaceVersion 替换 AGENTS.md 中 version 行。
func replaceVersion(md, v string) string {
	return strings.Replace(md, "version: 1.0.0", "version: "+v, 1)
}
