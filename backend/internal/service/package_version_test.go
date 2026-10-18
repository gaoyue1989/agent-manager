package service

import (
	"errors"
	"fmt"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
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
		// issue #174：保留目录名不可被根文件占用
		{"upsert reserved skills", VersionRequest{Upserts: []VersionUpsert{{Path: "skills", Content: "x"}}}},
		{"upsert reserved mcp-configs", VersionRequest{Upserts: []VersionUpsert{{Path: "mcp-configs", Content: "x"}}}},
	}
	for _, tc := range cases {
		if _, _, err := c.Packages.CreateVersion(base, tc.req); err == nil {
			t.Errorf("%s: want error, got nil", tc.name)
		}
	}
}

// TestUploadReservedRootFileRejected issue #174：上传包内根文件 skills/mcp-configs
// 占用保留目录名 → 400（运行时按目录扫描技能/MCP 配置，同名文件让目录不可读）。
func TestUploadReservedRootFileRejected(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	bad := buildTestZip(map[string]string{
		"AGENTS.md": testAgentsMD,
		"skills":    "x",
	})
	if _, err := c.Packages.Upload("bad.zip", bytesReader(bad)); err == nil {
		t.Fatal("root file skills should be rejected")
	}
	bad2 := buildTestZip(map[string]string{
		"AGENTS.md":   testAgentsMD,
		"mcp-configs": "x",
	})
	if _, err := c.Packages.Upload("bad2.zip", bytesReader(bad2)); err == nil {
		t.Fatal("root file mcp-configs should be rejected")
	}
	// 正常 skills/ 目录条目不受影响（目录条目与 skills/<name>/ 文件均放行）
	ok := buildTestZip(map[string]string{
		"AGENTS.md":                  testAgentsMD,
		"skills/demo/SKILL.md":       "---\nname: demo\ndescription: d\n---\nbody",
		"mcp-configs/fs/config.yaml": "connection:\n  type: sse\n",
	})
	if _, err := c.Packages.Upload("ok.zip", bytesReader(ok)); err != nil {
		t.Fatalf("legit skills/ mcp-configs/ dirs should upload: %v", err)
	}
}

// TestCreateVersionPureDelete 纯删除（只删不改）生成新版本 —— 回归：早期实现把
// 删除集提前剔除后判 changed，导致纯删除恒误报 ErrNoEffectiveChanges。
func TestCreateVersionPureDelete(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	rec, warnings, err := c.Packages.CreateVersion(base, VersionRequest{
		Deletes: []string{"mcp-configs/fs/config.yaml"},
	})
	if err != nil {
		t.Fatalf("pure delete should succeed: %v", err)
	}
	if warnings == nil {
		t.Log("no warnings")
	}
	if _, err := c.Packages.ReadFile(rec, "mcp-configs/fs/config.yaml"); err == nil {
		t.Fatal("deleted file still in new package")
	}
	if _, err := c.Packages.ReadFile(base, "mcp-configs/fs/config.yaml"); err != nil {
		t.Fatal("base file was removed")
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

// TestCreateVersionDupVersionWarning 显式改版本撞已有包 → 409 冲突（issue #172：
// 同 slug+version 由唯一索引 + 锁内查重裁决，不再静默并存追加 warning）。
func TestCreateVersionDupVersionConflict(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	// 直接再上传同 slug、version=2.0.0 的包（绕过 slug 辅助函数限制）
	dupZip := buildTestZip(map[string]string{"AGENTS.md": replaceVersion(testAgentsMD, "2.0.0")})
	if _, err := c.Packages.Upload("dup.zip", bytesReader(dupZip)); err != nil {
		t.Fatal(err)
	}
	_, _, err := c.Packages.CreateVersion(base, VersionRequest{
		// 显式改版本（2.0.0 ≠ base.Version → 不触发自动 bump）撞 dup 包
		Upserts: []VersionUpsert{{Path: "README.md", Content: "changed"},
			{Path: "AGENTS.md", Content: replaceVersion(testAgentsMD, "2.0.0")}},
	})
	if !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("want ErrVersionConflict, got %v", err)
	}
}

// TestCreateVersionSerialBump 并发保存串行化（issue #172 核心）：同一基础包并发两次
// 编辑（不带 checksum），自动 bump 必须串行递增为 1.0.1 / 1.0.2，不产生重复版本行。
func TestCreateVersionSerialBump(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")

	const workers = 4
	type result struct {
		ver string
		err error
	}
	results := make(chan result, workers)
	for i := 0; i < workers; i++ {
		go func(i int) {
			rec, _, err := c.Packages.CreateVersion(base, VersionRequest{
				Upserts: []VersionUpsert{{Path: fmt.Sprintf("lock-%d.md", i), Content: "x"}},
			})
			if err != nil {
				results <- result{err: err}
				return
			}
			results <- result{ver: rec.Version}
		}(i)
	}
	vers := map[string]int{}
	for i := 0; i < workers; i++ {
		r := <-results
		if r.err != nil {
			t.Fatalf("concurrent save failed: %v", r.err)
		}
		vers[r.ver]++
	}
	if len(vers) != workers {
		t.Fatalf("duplicate versions from concurrent saves: %v", vers)
	}
	// 链上无重复版本行（唯一索引兜底验证）
	var dups int64
	c.DB.Model(&store.OafPackage{}).Select("COUNT(*)").
		Where("slug = ?", base.Slug).
		Group("slug, version").Having("COUNT(*) > 1").Count(&dups)
	if dups > 0 {
		t.Fatalf("slug %s has duplicate version rows", base.Slug)
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

// TestPackageZip 整包打包可再次通过上传管线。重打包产物升一个 patch 版本
// （issue #172：同 slug+version 唯一索引后，重复上传同版本返回 409）。
func TestPackageZip(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "")
	data, err := c.Packages.Zip(base)
	if err != nil {
		t.Fatal(err)
	}
	// 改写版本为 1.0.1 再上传（同 slug+version 已被 base 占用）
	zi, err := c.FS.InspectZip(bytesReader(data))
	if err != nil {
		t.Fatal(err)
	}
	md := strings.Replace(string(zi.AgentsMD), "version: 1.0.0", "version: 1.0.1", 1)
	repack, err := c.Packages.rezipWithAgentsMD(&zi, []byte(md))
	if err != nil {
		t.Fatal(err)
	}
	re, err := c.Packages.Upload("repack.zip", bytesReader(repack))
	if err != nil {
		t.Fatalf("repackaged zip should pass upload pipeline: %v", err)
	}
	if re.Slug != base.Slug {
		t.Fatalf("slug mismatch %s vs %s", re.Slug, base.Slug)
	}
}

// TestUploadDuplicateSlugVersionRejected issue #172 语义固化：上传链路重复 slug+version
// → 409（REDESIGN §3.3.5 的并存语义被唯一索引取代，版本链不出现重复行）。
func TestUploadDuplicateSlugVersionRejected(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	_ = uploadTestPkg(t, c, "")
	dupZip := buildTestZip(map[string]string{
		"AGENTS.md":                  testAgentsMD,
		"mcp-configs/fs/config.yaml": "connection:\n  type: sse\n",
	})
	_, err := c.Packages.Upload("dup.zip", bytesReader(dupZip))
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("duplicate slug+version upload should map ErrConflict, got %v", err)
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

// TestCreateVersionAutoBumpVersion issue #155：仅改普通文件（不动 frontmatter version）时
// 自动 bump patch，避免同 slug@version 重复行；显式改版原则尊重显式值。
func TestCreateVersionAutoBumpVersion(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	base := uploadTestPkg(t, c, "") // testAgentsMD version=1.0.0

	// 不改 version：自动 1.0.0 → 1.0.1
	rec, _, err := c.Packages.CreateVersion(base, VersionRequest{
		Upserts: []VersionUpsert{{Path: "note.md", Content: "hello"}}})
	if err != nil {
		t.Fatal(err)
	}
	if rec.Version != "1.0.1" {
		t.Fatalf("auto bump: version=%s (want 1.0.1)", rec.Version)
	}
	// 新包 AGENTS.md frontmatter 同步为 1.0.1（重写值带引号，合法 YAML、解析语义不变）
	md, _ := c.Packages.ReadFile(rec, "AGENTS.md")
	if !strings.Contains(string(md), "1.0.1") {
		t.Fatalf("AGENTS.md version not rewritten: %s", md)
	}
	if _, err := c.Packages.ReadFile(rec, "note.md"); err != nil {
		t.Fatal("upserted file missing in new package")
	}

	// 显式改版本：尊重 2.0.0，不再递增
	rec2, _, err := c.Packages.CreateVersion(base, VersionRequest{
		Upserts: []VersionUpsert{{Path: "AGENTS.md", Content: replaceVersion(testAgentsMD, "2.0.0")}}})
	if err != nil {
		t.Fatal(err)
	}
	if rec2.Version != "2.0.0" {
		t.Fatalf("explicit version: %s (want 2.0.0)", rec2.Version)
	}
}
