package store

import (
	"archive/zip"
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// buildZip 构造内存 zip。
func buildZip(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range files {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// rawEntry 带元数据的 zip 条目（支持自定义权限与符号链接）。
type rawEntry struct {
	name    string
	content []byte
	mode    os.FileMode // 0 表示默认 0644
}

// buildRawZip 逐条目指定 FileHeader，构造 validateEntry 需要的特殊 zip。
func buildRawZip(t *testing.T, entries []rawEntry) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, e := range entries {
		h := &zip.FileHeader{Name: e.name, Method: zip.Deflate}
		mode := e.mode
		if mode == 0 {
			mode = 0o644
		}
		h.SetMode(mode)
		w, err := zw.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write(e.content); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func validPkgFiles() map[string]string {
	return map[string]string{
		"AGENTS.md": `---
name: Demo
vendorKey: acme
agentKey: demo
version: 1.0.0
slug: acme/demo
description: demo agent package
author: "@acme"
license: MIT
---
body`,
		"mcp-configs/fs/config.yaml": "connection:\n  type: sse\n",
		"skills/a/SKILL.md":          "# skill a",
	}
}

func newTestFS(t *testing.T) (*FS, func()) {
	t.Helper()
	dir, _ := os.MkdirTemp("", "oaffs-*")
	f := &FS{Root: dir}
	return f, func() { os.RemoveAll(dir) }
}

func TestInspectZipValid(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	data := buildZip(t, validPkgFiles())
	zi, err := f.InspectZip(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("inspect: %v", err)
	}
	if zi.FileCount != 3 || zi.TotalSize == 0 || len(zi.Checksum) != 64 {
		t.Fatalf("unexpected info: count=%d size=%d sum=%s", zi.FileCount, zi.TotalSize, zi.Checksum)
	}
	if !zi.HasDir("mcp-configs/fs") {
		t.Error("HasDir(mcp-configs/fs) should be true")
	}
	if zi.HasDir("mcp-configs/missing") {
		t.Error("HasDir(missing) should be false")
	}
	if !strings.Contains(string(zi.AgentsMD), "slug: acme/demo") {
		t.Errorf("AgentsMD not captured")
	}
}

func TestInspectZipNoAgentsMD(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	err := func() error {
		zi, e := f.InspectZip(bytes.NewReader(buildZip(t, map[string]string{"a.txt": "x"})))
		if e != nil {
			return e
		}
		_ = zi
		return e
	}()
	if !errors.Is(err, ErrNoAgentsMD) {
		t.Fatalf("expected ErrNoAgentsMD, got %v", err)
	}
}

func TestExtractZipToAndTreeReadFile(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	data := buildZip(t, validPkgFiles())
	zi, err := f.InspectZip(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	cnt, total, _, err := f.ExtractZipTo("packages/1", zi)
	if err != nil || cnt != 3 || total == 0 {
		t.Fatalf("extract: cnt=%d total=%d err=%v", cnt, total, err)
	}
	tree, err := f.Tree("packages/1")
	if err != nil {
		t.Fatal(err)
	}
	if len(tree) < 2 { // AGENTS.md + mcp-configs + skills
		t.Fatalf("tree too small: %+v", tree)
	}
	body, err := f.ReadFile("packages/1", "skills/a/SKILL.md")
	if err != nil || string(body) != "# skill a" {
		t.Fatalf("readfile: %v %q", err, body)
	}
	if _, err := f.ReadFile("packages/1", "../../etc/passwd"); err == nil {
		t.Fatal("path traversal should be rejected")
	}
}

func TestRemovePackage(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	data := buildZip(t, validPkgFiles())
	zi, _ := f.InspectZip(bytes.NewReader(data))
	f.ExtractZipTo("packages/9", zi)
	p := filepath.Join(f.Root, "packages/9")
	if _, err := os.Stat(p); err != nil {
		t.Fatal("dir should exist")
	}
	if err := f.RemovePackage("packages/9"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Fatal("dir should be removed")
	}
}

func TestZipTooLarge(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	big := bytes.Repeat([]byte("a"), MaxZipSize+10)
	_, err := f.InspectZip(bytes.NewReader(big))
	if !errors.Is(err, ErrZipTooLarge) {
		t.Fatalf("expected ErrZipTooLarge, got %v", err)
	}
}

// 回归锁：zip-slip 与符号链接条目必须全部拒绝（包内容会 subPath 挂进业务 Pod /config）。
func TestInspectZipRejectsIllegalEntries(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	longName := strings.Repeat("a", 513)
	cases := []struct {
		desc string
		ents []rawEntry
	}{
		{"absolute path", []rawEntry{{name: "/etc/passwd"}}},
		{"parent traversal", []rawEntry{{name: "../evil.txt"}}},
		{"nested traversal", []rawEntry{{name: "a/../../evil.txt"}}},
		{"backslash separator", []rawEntry{{name: `a\..\evil.txt`}}},
		{"name over 512", []rawEntry{{name: longName}}},
		{"symlink entry", []rawEntry{{name: "link", content: []byte("/etc/passwd"), mode: os.ModeSymlink | 0o777}}},
	}
	for _, tc := range cases {
		ents := append([]rawEntry{{name: "AGENTS.md", content: []byte("no frontmatter")}}, tc.ents...)
		_, err := f.InspectZip(bytes.NewReader(buildRawZip(t, ents)))
		if !errors.Is(err, ErrZipSlip) {
			t.Errorf("%s: expected ErrZipSlip, got %v", tc.desc, err)
		}
	}
}

func TestInspectZipTooManyEntries(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	ents := make([]rawEntry, MaxEntries+1)
	for i := range ents {
		ents[i] = rawEntry{name: fmt.Sprintf("f%04d.txt", i), content: []byte("x")}
	}
	_, err := f.InspectZip(bytes.NewReader(buildRawZip(t, ents)))
	if !errors.Is(err, ErrTooManyFiles) {
		t.Fatalf("expected ErrTooManyFiles, got %v", err)
	}
}

// 解压总量上限：零字节高度可压缩，可绕过 zip 原始 20MB 限制，必须在条目累计处拦截。
func TestInspectZipExtractedTooLarge(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	zeros := bytes.Repeat([]byte{0}, 51<<20) // 两个 51MiB 条目累计 >100MiB
	data := buildRawZip(t, []rawEntry{
		{name: "a.bin", content: zeros},
		{name: "b.bin", content: zeros},
	})
	if len(data) > MaxZipSize {
		t.Fatalf("fixture should stay under raw zip limit, got %d", len(data))
	}
	_, err := f.InspectZip(bytes.NewReader(data))
	if err == nil || !strings.Contains(err.Error(), "extracted content exceeds limit") {
		t.Fatalf("expected extracted limit error, got %v", err)
	}
}

// 回归锁：zip 条目自带 0600 等私有权限时，解包必须归一化为最低可读
// （业务 Pod 非 root 用户需读 /config，否则 CrashLoop）。
func TestExtractZipNormalizesPermissions(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	data := buildRawZip(t, []rawEntry{
		{name: "AGENTS.md", content: []byte("no frontmatter"), mode: 0o600},
		{name: "run.sh", content: []byte("#!/bin/sh\n"), mode: 0o700},
	})
	zi, err := f.InspectZip(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := f.ExtractZipTo("packages/perm", zi); err != nil {
		t.Fatal(err)
	}
	base := f.PackageDir("packages/perm")
	for name, want := range map[string]os.FileMode{"AGENTS.md": 0o644, "run.sh": 0o755} {
		info, err := os.Stat(filepath.Join(base, name))
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != want {
			t.Errorf("%s perm=%o want %o", name, info.Mode().Perm(), want)
		}
	}
}

// 二道防线：绕过 InspectZip 构造的 ZipInfo（恶意条目已混入）时，
// ExtractZipTo 的前缀检查必须兜底拒绝。
func TestExtractZipToRejectsZipSlipFromRawInfo(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	data := buildRawZip(t, []rawEntry{
		{name: "AGENTS.md", content: []byte("no frontmatter")},
		{name: "../evil.txt", content: []byte("x")},
	})
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	_, _, _, err = f.ExtractZipTo("packages/raw", &ZipInfo{Reader: zr})
	if !errors.Is(err, ErrZipSlip) {
		t.Fatalf("expected ErrZipSlip from second-line defense, got %v", err)
	}
}
