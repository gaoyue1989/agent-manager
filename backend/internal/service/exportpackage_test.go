// 导出包编辑与发布标记测试（契约 §2.2/§5.6）：冻结快照/checksum 确定性/marker 序号与
// 复合唯一（§9-D2）/草稿续用与 rebase/只读项与路径安全。
package service

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

// markEntries 反序列化标记快照为条目列表。
func markEntries(t *testing.T, filesJSON string) []exportFileEntry {
	t.Helper()
	entries, err := parseMarkFiles(filesJSON)
	if err != nil {
		t.Fatal(err)
	}
	return entries
}

// TestFreezeExportMarkSequence 打标记：无草稿直接冻结源包 → marker {版本}-e1、status frozen、
// checksum 确定性（同内容两次冻结 checksum 相同）；再冻结递增 e2；快照自包含（含全部文件）。
func TestFreezeExportMarkSequence(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()

	m1 := freezeMark(t, core, ws.ID, svc.ID)
	if m1.Marker != pkg.Version+"-e1" {
		t.Fatalf("marker = %q, want %q", m1.Marker, pkg.Version+"-e1")
	}
	if m1.Status != store.ExportMarkFrozen || m1.Checksum == "" || m1.FileCount == 0 {
		t.Fatalf("mark view wrong: %+v", m1)
	}
	if m1.FileCount != pkg.FileCount {
		t.Fatalf("snapshot fileCount = %d, source package = %d", m1.FileCount, pkg.FileCount)
	}
	m2 := freezeMark(t, core, ws.ID, svc.ID)
	if m2.Marker != pkg.Version+"-e2" {
		t.Fatalf("second marker = %q, want -e2", m2.Marker)
	}
	if m2.Checksum != m1.Checksum {
		t.Fatalf("checksum not deterministic: %q vs %q", m2.Checksum, m1.Checksum)
	}
	// 快照自包含：源包全部文件都在快照内（构建不回读源包，§5.6）
	paths := map[string]bool{}
	for _, e := range markEntries(t, func() string {
		var row store.ExportPackageMarkEntity
		core.DB.First(&row, m1.ID)
		return row.FilesJSON
	}()) {
		paths[e.Path] = true
	}
	if !paths["AGENTS.md"] || !paths["mcp-configs/fs/config.yaml"] {
		t.Fatalf("snapshot missing files: %v", paths)
	}
	// 标记列表
	marks, err := core.ListExportMarks(ws.ID, svc.ID)
	if err != nil || len(marks) != 2 {
		t.Fatalf("list marks: %v len=%d", err, len(marks))
	}
}

// TestExportMarkUniqueConstraint 复合唯一 (service_id, marker)（契约 §9-D2）：同服务同
// marker 直接落库必须被唯一索引拒绝；跨服务同 marker 放行。
func TestExportMarkUniqueConstraint(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	m := freezeMark(t, core, ws.ID, svc.ID)
	var row store.ExportPackageMarkEntity
	core.DB.First(&row, m.ID)

	// 同服务撞 marker → 唯一索引拒绝
	dup := row
	dup.ID = 0
	if err := core.DB.Create(&dup).Error; err == nil {
		t.Fatal("duplicate (service_id, marker) must be rejected by unique index")
	}
	// 跨服务同 marker 放行（单列唯一会撞的场景）
	pkg2 := uploadTestPkg(t, core, "export2")
	svc2 := &store.ServiceEntity{K8sName: "oaf-e2e-export-agent-2", DisplayName: "第二服务", PackageID: pkg2.ID,
		Image: "agent-framework:latest", Status: store.StatusRunning, WorkspaceID: ws.ID,
		EnvJSON: "{}", AgentCardJSON: "{}", SkillsJSON: "[]", ShortName: "e2e-export-agent-2"}
	core.DB.Create(svc2)
	m2 := freezeMark(t, core, ws.ID, svc2.ID)
	if m2.Marker != m.Marker {
		t.Fatalf("cross-service marker = %q, want same %q", m2.Marker, m.Marker)
	}
}

// TestExportDraftEditAndFreeze 草稿编辑：PUT 单文件 upsert 至多一条 draft → 预览叠加草稿 →
// 冻结带改动快照；新草稿从最近 frozen 标记续用（编辑会话跨标记连续，e2 = e1 + 新改动）。
func TestExportDraftEditAndFreeze(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()

	// 只读项：AGENTS.md 与 skills/** 拒绝（设计 §9 标注口径，服务端强校验）
	for _, p := range []string{"AGENTS.md", "skills/demo/SKILL.md"} {
		if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", p, "x"); err == nil {
			t.Fatalf("read-only file %q must 400", p)
		}
	}
	// 路径安全：.. 与根级保留目录名
	if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "../escape.yaml", "x"); !errors.Is(err, store.ErrZipSlip) {
		t.Fatalf("zip-slip: err = %v", err)
	}
	if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "skills", "x"); err == nil {
		t.Fatal("root reserved dir name must 400")
	}

	d1, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "mcp-configs/fs/config.yaml", "connection:\n  url: https://prod.example.com/sse\n")
	if err != nil {
		t.Fatalf("save draft: %v", err)
	}
	if d1.Status != store.ExportMarkDraft || d1.Marker != "" {
		t.Fatalf("draft view wrong: %+v", d1)
	}
	// 预览叠加草稿
	view, err := core.ExportPackagePreview(ws.ID, svc.ID)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, f := range view.Files {
		if f.Path == "mcp-configs/fs/config.yaml" {
			found = true
			if !strings.Contains(f.Content, "prod.example.com") {
				t.Fatalf("preview not overlaid with draft: %q", f.Content)
			}
		}
		if f.Path == "AGENTS.md" && !f.ReadOnly {
			t.Fatal("AGENTS.md must be marked read-only")
		}
	}
	if !found || view.Draft == nil {
		t.Fatalf("preview draft overlay wrong: found=%v draft=%v", found, view.Draft)
	}
	// 冻结带改动快照
	m1 := freezeMark(t, core, ws.ID, svc.ID)
	entries := markEntries(t, func() string {
		var row store.ExportPackageMarkEntity
		core.DB.First(&row, m1.ID)
		return row.FilesJSON
	}())
	for _, e := range entries {
		if e.Path == "mcp-configs/fs/config.yaml" && !strings.Contains(e.Content, "prod.example.com") {
			t.Fatalf("frozen snapshot missing draft edit: %q", e.Content)
		}
	}
	// 再编辑：新草稿从 e1 快照续用（同源包版本），e2 同时含 e1 改动与新改动
	if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "mcp-configs/fs/config.yaml", "connection:\n  url: https://prod2.example.com/sse\n"); err != nil {
		t.Fatal(err)
	}
	if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "versions/note.txt", "tweak"); err != nil {
		t.Fatal(err)
	}
	m2 := freezeMark(t, core, ws.ID, svc.ID)
	if m2.Marker == m1.Marker {
		t.Fatal("second freeze must allocate a new marker")
	}
	contents := map[string]string{}
	for _, e := range markEntries(t, func() string {
		var row store.ExportPackageMarkEntity
		core.DB.First(&row, m2.ID)
		return row.FilesJSON
	}()) {
		contents[e.Path] = e.Content
	}
	if !strings.Contains(contents["mcp-configs/fs/config.yaml"], "prod2.example.com") {
		t.Fatalf("e2 missing latest edit: %q", contents["mcp-configs/fs/config.yaml"])
	}
	if contents["versions/note.txt"] != "tweak" {
		t.Fatalf("e2 missing new file: %q", contents["versions/note.txt"])
	}
	// 至多一条 draft：冻结后再无草稿行
	drafts, err := core.loadDraftMark(svc.ID)
	if err != nil || drafts != nil {
		t.Fatalf("draft must be consumed by freeze: %v %v", drafts, err)
	}
}

// TestExportPackagePreviewBinary 预览对二进制不内联内容（binary=true 引导下载端点）。
func TestExportPackagePreviewBinary(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	// 注入一个二进制草稿文件（base64 快照编码路径）
	bin := []byte{0x00, 0x01, 0xFF, 0xFE}
	filesJSON, err := json.Marshal([]exportFileEntry{
		{Path: "assets/logo.png", Encoding: "base64", Content: base64.StdEncoding.EncodeToString(bin)},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.ExportPackageMarkEntity{
		ServiceID: svc.ID, WorkspaceID: ws.ID, PackageVersion: "1.0.0",
		Status: store.ExportMarkDraft, CreatedBy: "export-owner", FileCount: 1,
		FilesJSON: string(filesJSON),
	}).Error; err != nil {
		t.Fatal(err)
	}
	view, err := core.ExportPackagePreview(ws.ID, svc.ID)
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range view.Files {
		if f.Path == "assets/logo.png" {
			if !f.Binary || f.Content != "" {
				t.Fatalf("binary file must not inline content: %+v", f)
			}
			return
		}
	}
	t.Fatal("binary draft file missing from preview")
}

// TestMarkSnapshotChecksumStability canonical checksum：条目顺序无关（乱序入库快照同 checksum）。
func TestMarkSnapshotChecksumStability(t *testing.T) {
	a := []exportFileEntry{
		{Path: "AGENTS.md", Encoding: "utf8", Content: "a"},
		{Path: "mcp-configs/fs/config.yaml", Encoding: "utf8", Content: "b"},
	}
	b := []exportFileEntry{
		{Path: "mcp-configs/fs/config.yaml", Encoding: "utf8", Content: "b"},
		{Path: "AGENTS.md", Encoding: "utf8", Content: "a"},
	}
	if checksumOfEntries(a) != checksumOfEntries(b) {
		t.Fatal("checksum must be order-independent (canonical: path sorted)")
	}
	if checksumOfEntries(a) == checksumOfEntries(append(a, exportFileEntry{Path: "x", Encoding: "utf8", Content: "y"})) {
		t.Fatal("checksum must change with content")
	}
	// JSON 形状契约：{path, encoding, content}
	raw, _ := json.Marshal(a)
	if !strings.Contains(string(raw), `"encoding":"utf8"`) {
		t.Fatalf("entry json shape wrong: %s", raw)
	}
}
