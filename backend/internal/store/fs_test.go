package store

import (
	"archive/zip"
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// buildZip 构造内存 zip。
func buildZip(t *testing.T, files map[string]string, symlink string) []byte {
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
	if symlink != "" {
		if err := zw.AddFS(os.DirFS(t.TempDir())); err != nil {
			_ = err
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
	data := buildZip(t, validPkgFiles(), "")
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
		zi, e := f.InspectZip(bytes.NewReader(buildZip(t, map[string]string{"a.txt": "x"}, "")))
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
	data := buildZip(t, validPkgFiles(), "")
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
	data := buildZip(t, validPkgFiles(), "")
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
