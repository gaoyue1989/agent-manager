package store

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newZipWriter 便于循环构造多条目 zip。
func newZipWriter(buf *bytes.Buffer) *zip.Writer { return zip.NewWriter(buf) }

// writePkgFiles 直接在 FS 内落一组文件（模拟已解包的包目录）。
func writePkgFiles(t *testing.T, f *FS, rel string, files map[string]string) {
	t.Helper()
	for name, content := range files {
		target := filepath.Join(f.PackageDir(rel), name)
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(target, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestIsTextContent(t *testing.T) {
	cases := []struct {
		name string
		data []byte
		want bool
	}{
		{"AGENTS.md", []byte("---\nname: x\n---\nbody"), true},
		{"config.yaml", []byte("key: value\n"), true},
		{"data.json", []byte(`{"a":1}`), true},
		{"script.sh", []byte("#!/bin/sh\necho hi\n"), true},
		{"Dockerfile", []byte("FROM alpine\n"), true},
		{".gitignore", []byte("node_modules\n"), true},
		{"logo.png", []byte{0x89, 'P', 'N', 'G', 0x00, 0x0a}, false},
		{"data.bin", []byte{0x00, 0x01, 0x02}, false},
		{"unknown.xyz", []byte("plain text"), false},                   // 白名单外扩展名
		{"big.md", bytes.Repeat([]byte("a"), MaxPreviewSize+1), false}, // 超预览上限
	}
	for _, c := range cases {
		if got := IsTextContent(c.name, c.data); got != c.want {
			t.Errorf("IsTextContent(%q) = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestCleanSubPath(t *testing.T) {
	for _, ok := range []string{"AGENTS.md", "skills/a/SKILL.md", "./README.md", "a//b.txt"} {
		if _, err := CleanSubPath(ok); err != nil {
			t.Errorf("CleanSubPath(%q) unexpected error: %v", ok, err)
		}
	}
	for _, bad := range []string{"", "../etc/passwd", "a/../../b", "a\\b.txt", "sub/..", strings.Repeat("x", 600)} {
		if _, err := CleanSubPath(bad); err == nil {
			t.Errorf("CleanSubPath(%q) should fail", bad)
		}
	}
}

func TestZipPackageRoundTrip(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	files := map[string]string{
		"AGENTS.md":             "---\nname: x\n---\nbody",
		"skills/greet/SKILL.md": "# greet",
	}
	writePkgFiles(t, f, "packages/9", files)

	data, err := f.ZipPackage("packages/9")
	if err != nil {
		t.Fatal(err)
	}
	// 打包产物必须仍能通过 InspectZip（复用上传管线的保证）
	zi, err := f.InspectZip(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("repackaged zip fails InspectZip: %v", err)
	}
	if zi.FileCount != 2 || zi.AgentsMD == nil {
		t.Fatalf("repackaged zip files=%d agentsMD nil=%v", zi.FileCount, zi.AgentsMD == nil)
	}

	// 空目录与不存在目录
	if _, err := f.ZipPackage("packages/nonexistent"); err == nil {
		t.Error("ZipPackage on missing dir should fail")
	}
}

func TestWriteZipTo(t *testing.T) {
	f, done := newTestFS(t)
	defer done()
	zipData := buildZip(t, map[string]string{
		"AGENTS.md":           "---\nname: y\n---\nbody",
		"nested/dir/file.txt": "hello",
	})
	count, total, err := f.WriteZipTo("packages/10", zipData)
	if err != nil {
		t.Fatal(err)
	}
	if count != 2 || total == 0 {
		t.Fatalf("count=%d total=%d", count, total)
	}
	got, err := f.ReadFile("packages/10", "nested/dir/file.txt")
	if err != nil || string(got) != "hello" {
		t.Fatalf("read back: %q %v", got, err)
	}
	// 权限最低 0644（业务 Pod 非 root 需可读）
	info, _ := os.Stat(filepath.Join(f.PackageDir("packages/10"), "AGENTS.md"))
	if info.Mode().Perm() < 0o644 {
		t.Fatalf("perm too strict: %v", info.Mode().Perm())
	}

	// zip-slip 拒绝
	slip := buildRawZip(t, []rawEntry{{name: "../evil.txt", content: []byte("x")}})
	if _, _, err := f.WriteZipTo("packages/11", slip); err == nil {
		t.Error("zip-slip should be rejected")
	}
	// 条目数超限拒绝
	var many bytes.Buffer
	zw := newZipWriter(&many)
	for i := 0; i < MaxEntries+1; i++ {
		zw.Create(string(rune('a'+i%26)) + string(rune(i)))
	}
	zw.Close()
	if _, _, err := f.WriteZipTo("packages/12", many.Bytes()); err == nil {
		t.Error("too many entries should be rejected")
	}
	// 非 zip 内容拒绝
	if _, _, err := f.WriteZipTo("packages/13", []byte("not a zip")); err == nil {
		t.Error("invalid zip should be rejected")
	}
}
