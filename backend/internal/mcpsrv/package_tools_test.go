package mcpsrv

import (
	"encoding/base64"
	"strings"
	"testing"
)

// TestMCPGetPackageFile 读取包内文本文件；缺 path / 非法路径返回 IsError。
func TestMCPGetPackageFile(t *testing.T) {
	cs, core, done := newMCPClient(t)
	defer done()

	zipBytes := buildTestZip(map[string]string{"AGENTS.md": testAgentsMD})
	isErr, out, _ := call(t, cs, "upload_package", map[string]any{
		"filename":       "demo.zip",
		"content_base64": base64.StdEncoding.EncodeToString(zipBytes),
	})
	if isErr {
		t.Fatalf("upload: %v", out)
	}
	pkgID := out["packageId"].(float64)
	_ = core

	isErr, out, _ = call(t, cs, "get_package_file", map[string]any{
		"packageId": pkgID, "path": "AGENTS.md",
	})
	if isErr || out["binary"] != false || !strings.Contains(out["content"].(string), "vendorKey: acme") {
		t.Fatalf("get_package_file: isErr=%v out=%v", isErr, out)
	}

	// 非法路径 → IsError
	isErr, _, raw := call(t, cs, "get_package_file", map[string]any{
		"packageId": pkgID, "path": "../hack",
	})
	if !isErr {
		t.Fatalf("zip-slip path should be error, got %s", raw)
	}
	// 缺 path（schema required 层面直接拒绝，协议错误 → call 内 t.Fatal 不会走到这里，
	// 因此改传空串走业务校验分支）
	isErr, _, _ = call(t, cs, "get_package_file", map[string]any{"packageId": pkgID, "path": ""})
	if !isErr {
		t.Fatal("empty path should be error")
	}
	// 包不存在
	isErr, _, _ = call(t, cs, "get_package_file", map[string]any{"packageId": 999, "path": "AGENTS.md"})
	if !isErr {
		t.Fatal("missing package should be error")
	}
}

// TestMCPCreatePackageVersion 编辑生成新版本：成功 + 无变更 + 删 AGENTS.md。
func TestMCPCreatePackageVersion(t *testing.T) {
	cs, _, done := newMCPClient(t)
	defer done()

	zipBytes := buildTestZip(map[string]string{"AGENTS.md": testAgentsMD})
	isErr, out, _ := call(t, cs, "upload_package", map[string]any{
		"filename":       "demo.zip",
		"content_base64": base64.StdEncoding.EncodeToString(zipBytes),
	})
	if isErr {
		t.Fatalf("upload: %v", out)
	}
	pkgID := out["packageId"].(float64)

	newMD := strings.Replace(testAgentsMD, "version: 1.0.0", "version: 2.0.0", 1)
	isErr, out, _ = call(t, cs, "create_package_version", map[string]any{
		"packageId": pkgID,
		"upserts":   []map[string]string{{"path": "AGENTS.md", "content": newMD}},
		"deletes":   []string{"nonexistent.txt"},
	})
	if isErr {
		t.Fatalf("create_package_version: %v", out)
	}
	// 删除不存在的文件不构成变更 → 服务层报错？（有 upsert 生效，属有效变更，应成功）
	newID := out["packageId"].(float64)
	if newID == pkgID || out["version"] != "2.0.0" || out["sourcePackageId"].(float64) != pkgID {
		t.Fatalf("new package wrong: %v", out)
	}

	// 回读新版本确认内容
	isErr, out, _ = call(t, cs, "get_package_file", map[string]any{
		"packageId": newID, "path": "AGENTS.md",
	})
	if isErr || !strings.Contains(out["content"].(string), "version: 2.0.0") {
		t.Fatalf("read back: %v", out)
	}

	// 纯无效变更 → IsError（no effective changes）
	isErr, _, raw := call(t, cs, "create_package_version", map[string]any{
		"packageId": pkgID,
		"deletes":   []string{"not-there.txt"},
	})
	if !isErr || !strings.Contains(raw, "no effective changes") {
		t.Fatalf("no-change should error, got %s", raw)
	}

	// 删除根级 AGENTS.md → IsError
	isErr, _, raw = call(t, cs, "create_package_version", map[string]any{
		"packageId": pkgID,
		"deletes":   []string{"AGENTS.md"},
		"upserts":   []map[string]string{{"path": "x.txt", "content": "x"}},
	})
	if !isErr || !strings.Contains(raw, "AGENTS.md") {
		t.Fatalf("delete AGENTS.md should error, got %s", raw)
	}

	// 空入参 → IsError
	isErr, _, _ = call(t, cs, "create_package_version", map[string]any{"packageId": pkgID})
	if !isErr {
		t.Fatal("empty request should error")
	}
}
