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

// uploadPkgVersion 上传同 slug 指定版本的新包（换版 rebase 用例的 2.0.0 包构造）。
func uploadPkgVersion(t *testing.T, core *Core, slugSuffix, version string) *store.OafPackage {
	t.Helper()
	md := strings.ReplaceAll(replaceSlug(testAgentsMD, slugSuffix), "version: 1.0.0", "version: "+version)
	pkg, err := core.Packages.Upload("demo.zip", bytesReader(buildTestZip(map[string]string{
		"AGENTS.md":                  md,
		"mcp-configs/fs/config.yaml": "connection:\n  type: sse\n",
		"versions/" + version + "-only.txt": "v" + version,
	})))
	if err != nil {
		t.Fatalf("upload pkg version %s: %v", version, err)
	}
	if pkg.Version != version {
		t.Fatalf("pkg version = %q, want %q", pkg.Version, version)
	}
	return pkg
}

// markPathsByRow 读标记行快照并展开为 path→content 映射。
func markPathsByRow(t *testing.T, core *Core, markID uint) map[string]string {
	t.Helper()
	var row store.ExportPackageMarkEntity
	if err := core.DB.First(&row, markID).Error; err != nil {
		t.Fatal(err)
	}
	out := map[string]string{}
	for _, e := range markEntries(t, row.FilesJSON) {
		out[e.Path] = e.Content
	}
	return out
}

// TestExportDraftRebaseOnSourcePackageVersionChange 源包换版 rebase 分支（seedExportEntries，
// 契约 §5.6）：最近 frozen 快照与当前源包版本不一致时，新草稿必须重新取源包快照——
// 旧版本文件不得带进新版本导出镜像（回归成恒续用旧快照则旧包文件静默混入新版本构建
// 输入，checksum 照常落库、错误不可见）；本次编辑叠加在新版本基线之上；marker 序号
// 跨包版本连续递增。
func TestExportDraftRebaseOnSourcePackageVersionChange(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()

	// 1. e1 独有改动先落进 frozen 快照（钉住旧血脉供对照）；冻结消费 draft，换版前无残留
	if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "e1-only.txt", "e1 edit"); err != nil {
		t.Fatal(err)
	}
	m1 := freezeMark(t, core, ws.ID, svc.ID)
	if m1.Marker != pkg.Version+"-e1" || m1.PackageVersion != pkg.Version {
		t.Fatalf("e1 marker = %q version = %q", m1.Marker, m1.PackageVersion)
	}
	if _, ok := markPathsByRow(t, core, m1.ID)["e1-only.txt"]; !ok {
		t.Fatal("precondition: e1 snapshot must contain the e1 edit")
	}

	// 2. 构造 2.0.0 新包（同 slug 不同 version，不撞 uk_pkg_slug_version）并换版
	pkg2 := uploadPkgVersion(t, core, "export", "2.0.0")
	if err := core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).
		Update("package_id", pkg2.ID).Error; err != nil {
		t.Fatal(err)
	}

	// 3. 换版后首次 Save（draft==nil）：seedExportEntries 走 rebase（last frozen 1.0.0 ≠ 2.0.0）
	d2, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "new-edit.txt", "new edit")
	if err != nil {
		t.Fatalf("save after version change: %v", err)
	}
	if d2.PackageVersion != "2.0.0" {
		t.Fatalf("draft packageVersion = %q, want 2.0.0 (must rebase, not resume 1.0.0 snapshot)", d2.PackageVersion)
	}
	d2Paths := markPathsByRow(t, core, d2.ID)
	if _, ok := d2Paths["e1-only.txt"]; ok {
		t.Fatal("rebased draft must drop old-version snapshot file e1-only.txt")
	}
	if c := d2Paths["versions/2.0.0-only.txt"]; c != "v2.0.0" {
		t.Fatalf("rebased draft must carry new package file: %q", d2Paths["versions/2.0.0-only.txt"])
	}
	if c := d2Paths["new-edit.txt"]; c != "new edit" {
		t.Fatalf("rebased draft must carry the new edit on top of the 2.0.0 baseline: %q", d2Paths["new-edit.txt"])
	}

	// 4. 冻结 rebase 草稿：marker 序号跨包版本连续（e1 → e2），版本跟随新包
	m2 := freezeMark(t, core, ws.ID, svc.ID)
	if m2.Marker != "2.0.0-e2" || m2.PackageVersion != "2.0.0" {
		t.Fatalf("rebased marker = %q version = %q, want 2.0.0-e2 / 2.0.0", m2.Marker, m2.PackageVersion)
	}
	m2Paths := markPathsByRow(t, core, m2.ID)
	if _, ok := m2Paths["e1-only.txt"]; ok {
		t.Fatal("frozen rebased snapshot must not contain e1-only.txt")
	}
	if _, ok := m2Paths["versions/2.0.0-only.txt"]; !ok {
		t.Fatal("frozen rebased snapshot must contain 2.0.0-only.txt")
	}
	if _, ok := m2Paths["new-edit.txt"]; !ok {
		t.Fatal("frozen rebased snapshot must contain new-edit.txt")
	}
}

// TestExportRebaseFreezeWithoutDraft 换版后无草稿直接冻结（FreezeExportMark 无 draft seed
// 路径）：与 Save 路径正交的另一条 rebase 入口——直接冻结同样必须取新版本源包快照，
// 不得续用旧版本 frozen 快照。
func TestExportRebaseFreezeWithoutDraft(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()

	// e1 带独有改动冻结（draft 被消费），换版后无任何草稿
	if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "e1-only.txt", "e1 edit"); err != nil {
		t.Fatal(err)
	}
	m1 := freezeMark(t, core, ws.ID, svc.ID)
	if m1.Marker != pkg.Version+"-e1" {
		t.Fatalf("e1 marker = %q", m1.Marker)
	}
	pkg2 := uploadPkgVersion(t, core, "export", "2.0.0")
	if err := core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).
		Update("package_id", pkg2.ID).Error; err != nil {
		t.Fatal(err)
	}

	m2 := freezeMark(t, core, ws.ID, svc.ID)
	if m2.Marker != "2.0.0-e2" || m2.PackageVersion != "2.0.0" {
		t.Fatalf("direct-freeze marker = %q version = %q, want 2.0.0-e2 / 2.0.0", m2.Marker, m2.PackageVersion)
	}
	m2Paths := markPathsByRow(t, core, m2.ID)
	if _, ok := m2Paths["e1-only.txt"]; ok {
		t.Fatal("rebased direct-freeze snapshot must not contain e1-only.txt")
	}
	if _, ok := m2Paths["versions/2.0.0-only.txt"]; !ok {
		t.Fatal("rebased direct-freeze snapshot must contain 2.0.0-only.txt")
	}
}

// TestExportDraftResumeAfterVersionRevert 版本回退方向的 rebase + 回退后的同版本续用
// （seedExportEntries 两分支交界）：① 换回旧版本包再编辑同样触发 rebase（续用判定只看
// 版本相等，不区分前进/回退），回退基线不得携带 2.0.0 期间文件；② 回退版本冻结后再编辑
// 恢复同版本续用（rebase 改动不得误伤续用语义）。
func TestExportDraftResumeAfterVersionRevert(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()

	// 前进到 2.0.0 并冻结（无草稿直接冻结 = rebase 取 2.0.0 源包）
	m1 := freezeMark(t, core, ws.ID, svc.ID)
	if m1.Marker != pkg.Version+"-e1" {
		t.Fatalf("e1 marker = %q", m1.Marker)
	}
	pkg2 := uploadPkgVersion(t, core, "export", "2.0.0")
	if err := core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).
		Update("package_id", pkg2.ID).Error; err != nil {
		t.Fatal(err)
	}
	m2 := freezeMark(t, core, ws.ID, svc.ID)
	if m2.Marker != "2.0.0-e2" {
		t.Fatalf("e2 marker = %q", m2.Marker)
	}

	// ① 回退到 1.0.0 再编辑：last frozen（2.0.0）≠ 1.0.0 → rebase，回退基线纯净
	if err := core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).
		Update("package_id", pkg.ID).Error; err != nil {
		t.Fatal(err)
	}
	d3, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "back-edit.txt", "back")
	if err != nil {
		t.Fatalf("save after version revert: %v", err)
	}
	if d3.PackageVersion != "1.0.0" {
		t.Fatalf("draft after revert packageVersion = %q, want 1.0.0 (rebase)", d3.PackageVersion)
	}
	d3Paths := markPathsByRow(t, core, d3.ID)
	if _, ok := d3Paths["versions/2.0.0-only.txt"]; ok {
		t.Fatal("rebased draft after revert must drop 2.0.0-only.txt")
	}
	if c := d3Paths["back-edit.txt"]; c != "back" {
		t.Fatalf("rebased draft after revert must carry the edit: %q", d3Paths["back-edit.txt"])
	}
	m3 := freezeMark(t, core, ws.ID, svc.ID)
	if m3.Marker != "1.0.0-e3" {
		t.Fatalf("marker after revert = %q, want 1.0.0-e3", m3.Marker)
	}

	// ② 回退版本冻结后再编辑：同版本续用恢复（草稿含 1.0.0-e3 快照内容 + 新改动）
	d4, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "again.txt", "again")
	if err != nil {
		t.Fatalf("save after revert freeze: %v", err)
	}
	d4Paths := markPathsByRow(t, core, d4.ID)
	if c := d4Paths["back-edit.txt"]; c != "back" {
		t.Fatalf("resume must keep last frozen snapshot content: %q", d4Paths["back-edit.txt"])
	}
	if c := d4Paths["again.txt"]; c != "again" {
		t.Fatalf("resume must carry the new edit: %q", d4Paths["again.txt"])
	}
}

// TestMarshalMarkFilesSizeLimit 16MB 快照上限（marshalMarkFiles，契约 §9-D7）：超限拒绝
// 返回 ErrInvalidForm 且提示 snapshot exceeds（改用更小包）；base64 二进制条目同样计入
// 上限（编码后 ≈ 原始体积 ×1.33）；边界内正常序列化并经 parseMarkFiles round-trip 一致。
func TestMarshalMarkFilesSizeLimit(t *testing.T) {
	// 超限拒绝：17MB utf8 内容（json.Marshal 纯 ASCII 不转义，len 确定超 16,777,216）
	huge := []exportFileEntry{{Path: "data/large.txt", Encoding: "utf8", Content: strings.Repeat("a", 17<<20)}}
	_, err := marshalMarkFiles(huge)
	if !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("oversize snapshot must be ErrInvalidForm: %v", err)
	}
	if !strings.Contains(err.Error(), "snapshot exceeds") {
		t.Fatalf("oversize error must mention snapshot exceeds: %v", err)
	}

	// base64 条目同样计入上限：12MB 原始字节 base64 编码后恰 16MB，JSON 包装必然超限
	encoded := base64.StdEncoding.EncodeToString(make([]byte, 12<<20))
	b64 := []exportFileEntry{{Path: "data/blob.bin", Encoding: "base64", Content: encoded}}
	if _, err := marshalMarkFiles(b64); !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("oversize base64 snapshot must be ErrInvalidForm: %v", err)
	}

	// 边界内正常：1KB 内容序列化成功且 round-trip 一致
	small := []exportFileEntry{{Path: "mcp-configs/fs/config.yaml", Encoding: "utf8", Content: strings.Repeat("a", 1024)}}
	raw, err := marshalMarkFiles(small)
	if err != nil || raw == "" {
		t.Fatalf("small snapshot must marshal: err=%v raw-empty=%v", err, raw == "")
	}
	back, err := parseMarkFiles(raw)
	if err != nil {
		t.Fatal(err)
	}
	if len(back) != 1 || back[0].Path != small[0].Path || back[0].Content != small[0].Content {
		t.Fatalf("round-trip mismatch: %+v", back)
	}
}
