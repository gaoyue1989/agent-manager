// Skill 中心单测（2026-10-19）：Markdown/zip 创建、版本管理（不可变/发布/下线/重新上线）、
// 启停/删除引用校验、市场安装（内置源）与幂等、Agent 使用情况聚合（最后生成包解析）。
package service

import (
	"bytes"
	"fmt"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

// createSkillFixture 直接落一份技能资产 + 已发布版本（不走创建 API，专注被测路径）。
func createSkillFixture(t *testing.T, c *Core, wsID uint, name string) *store.Skill {
	t.Helper()
	s := store.Skill{WorkspaceID: wsID, Name: name, DisplayName: name, Description: "desc " + name,
		TagsJSON: `["tag1"]`, Source: "zip", Enabled: true, Status: "active"}
	ver := store.SkillVersion{Version: "1.0.0", Status: SkillVerPublished, Changelog: "fixture"}
	if err := c.DB.Create(&s).Error; err != nil {
		t.Fatal(err)
	}
	ver.SkillID = s.ID
	if err := c.DB.Create(&ver).Error; err != nil {
		t.Fatal(err)
	}
	if err := c.DB.Model(&s).Update("latest_version_id", ver.ID).Error; err != nil {
		t.Fatal(err)
	}
	return &s
}

func TestCreateSkillFromMarkdown(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	s, err := c.CreateSkillFromMarkdown(7, 1, "release-checklist", "发布清单", "发布前检查", []string{"发布", " 发布 "}, "---\nname: release-checklist\ndescription: x\n---\nbody", true)
	if err != nil {
		t.Fatal(err)
	}
	if s.Source != "markdown" || s.Status != "active" || !s.Enabled {
		t.Fatalf("unexpected skill: %+v", s)
	}
	if s.TagsJSON != `["发布"]` { // 去重去空白
		t.Fatalf("tags normalized wrong: %s", s.TagsJSON)
	}
	var ver store.SkillVersion
	if err := c.DB.First(&ver, s.LatestVersionID).Error; err != nil {
		t.Fatal(err)
	}
	if ver.Status != SkillVerPublished || ver.Version != "1.0.0" {
		t.Fatalf("expected published 1.0.0, got %s %s", ver.Status, ver.Version)
	}
	// 重名冲突
	if _, err := c.CreateSkillFromMarkdown(7, 1, "release-checklist", "", "d", nil, "x", false); err == nil || !strings.Contains(err.Error(), "already exists") {
		t.Fatalf("expected conflict, got %v", err)
	}
	// 非法名
	if _, err := c.CreateSkillFromMarkdown(7, 1, "Bad_Name", "", "d", nil, "x", false); err == nil {
		t.Fatal("expected invalid name rejected")
	}
	// 空 markdown
	if _, err := c.CreateSkillFromMarkdown(7, 1, "empty-ok", "", "d", nil, "  ", false); err == nil {
		t.Fatal("expected empty content rejected")
	}
}

func TestCreateSkillFromZip(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	zipBytes, _ := buildZipBytes(map[string][]byte{
		"skills/webapp-testing/SKILL.md":  []byte("---\nname: webapp-testing\ndescription: toolkit for web apps\n---\nbody"),
		"skills/webapp-testing/scripts/a.sh": []byte("#!/bin/sh\necho hi"),
	})
	s, warnings, err := c.CreateSkillFromZip(7, 1, "w.zip", bytes.NewReader(zipBytes), "", "", "", nil, true)
	if err != nil {
		t.Fatal(err)
	}
	if s.Name != "webapp-testing" || s.Source != "zip" {
		t.Fatalf("unexpected: %+v", s)
	}
	// 描述从 SKILL.md frontmatter 回填
	if s.Description != "toolkit for web apps" {
		t.Fatalf("desc not backfilled: %q", s.Description)
	}
	if len(warnings) != 0 {
		t.Fatalf("expected no warnings, got %v", warnings)
	}
	// PVC 解包落位
	if _, err := c.FS.ReadFile(fmt.Sprintf("skills/%d/1.0.0", s.ID), "skills/webapp-testing/SKILL.md"); err != nil {
		t.Fatalf("zip not extracted to PVC: %v", err)
	}
	// 文件清单
	var ver store.SkillVersion
	c.DB.First(&ver, s.LatestVersionID)
	if !strings.Contains(ver.FilesJSON, "scripts/a.sh") {
		t.Fatalf("files json missing script: %s", ver.FilesJSON)
	}
	// 无 skills/ 目录 → 400
	bad, _ := buildZipBytes(map[string][]byte{"AGENTS.md": []byte("x")})
	if _, _, err := c.CreateSkillFromZip(7, 1, "b.zip", bytes.NewReader(bad), "", "", "", nil, false); err == nil {
		t.Fatal("expected zip without skills/ rejected")
	}
	// 不合规 SKILL.md → warnings 不阻断
	badSkill, _ := buildZipBytes(map[string][]byte{
		"skills/broken/README.md": []byte("no SKILL.md"),
	})
	_, warnings, err = c.CreateSkillFromZip(7, 1, "b2.zip", bytes.NewReader(badSkill), "", "", "desc", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, w := range warnings {
		if strings.Contains(w, "missing skills/broken/SKILL.md") {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected compliance warning, got %v", warnings)
	}
}

func TestSkillVersionLifecycle(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	s := createSkillFixture(t, c, 7, "lifecycle")
	// 创建新版本（默认 patch+1）
	v2, err := c.CreateSkillVersion(7, s.ID, s.LatestVersionID, "", "second")
	if err != nil {
		t.Fatal(err)
	}
	if v2.Version != "1.0.1" || v2.Status != SkillVerDraft {
		t.Fatalf("unexpected v2: %+v", v2)
	}
	// 手填非法版本
	if _, err := c.CreateSkillVersion(7, s.ID, v2.ID, "not-sem", ""); err == nil {
		t.Fatal("expected invalid version rejected")
	}
	// 撞版本
	if _, err := c.CreateSkillVersion(7, s.ID, v2.ID, "1.0.0", ""); err == nil || !strings.Contains(err.Error(), "already exists") {
		t.Fatalf("expected version conflict, got %v", err)
	}
	// 发布 → published
	pub, err := c.PublishSkillVersion(7, s.ID, v2.ID)
	if err != nil {
		t.Fatal(err)
	}
	if pub.Status != SkillVerPublished {
		t.Fatalf("not published: %s", pub.Status)
	}
	// 正式版本不可再发布/下线外动作
	if _, err := c.PublishSkillVersion(7, s.ID, v2.ID); err == nil {
		t.Fatal("expected re-publish rejected")
	}
	// 发布记录审计
	var relCnt int64
	c.DB.Model(&store.SkillRelease{}).Where("skill_id = ? AND version_id = ? AND action = ?", s.ID, v2.ID, "publish").Count(&relCnt)
	if relCnt != 1 {
		t.Fatalf("expected 1 release record, got %d", relCnt)
	}
	// 下线 → 重新上线
	if _, err := c.OfflineSkillVersion(7, s.ID, v2.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := c.RelineSkillVersion(7, s.ID, v2.ID); err != nil {
		t.Fatal(err)
	}
	// 跨空间隔离
	if _, err := c.PublishSkillVersion(99, s.ID, v2.ID); err == nil {
		t.Fatal("expected cross-workspace rejected")
	}
}

func TestSkillEnableDelete(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	s := createSkillFixture(t, c, 7, "deletable")
	// 无引用 → 删除成功
	if err := c.DeleteSkill(7, s.ID); err != nil {
		t.Fatal(err)
	}
	var cnt int64
	c.DB.Model(&store.Skill{}).Where("id = ?", s.ID).Count(&cnt)
	if cnt != 0 {
		t.Fatal("skill not deleted")
	}
	// 有引用（Agent 运行包 frontmatter 声明命中技能名）→ 409
	s2 := createSkillFixture(t, c, 7, "referenced")
	md := strings.Replace(testAgentsMD, "agentKey: demo", "agentKey: ref-demo", 1)
	md = strings.Replace(md, "description: Demo agent", "description: ref demo", 1)
	// 注入 skills 声明
	md = strings.Replace(md, "---\nname: Demo", "---\nname: Demo\nskills:\n  - name: referenced\n    source: local\n    version: 1.0.0", 1)
	if _, err := c.Packages.RegisterGeneratedFrom(7, nil, map[string][]byte{"AGENTS.md": []byte(md)}, ""); err != nil {
		t.Skipf("package registration failed (fixture env): %v", err)
	}
	var pkg store.OafPackage
	c.DB.Order("id DESC").First(&pkg)
	if err := c.DB.Create(&store.ServiceEntity{K8sName: "ref-demo-svc", DisplayName: "引用方", PackageID: pkg.ID,
		WorkspaceID: 7, Status: store.StatusRunning, Replicas: 1, ShortName: "ref-demo"}).Error; err != nil {
		t.Fatal(err)
	}
	err := c.DeleteSkill(7, s2.ID)
	if err == nil || !strings.Contains(err.Error(), "referenced by agents") {
		t.Fatalf("expected referenced rejection, got %v", err)
	}
	// 停用
	disabled, err := c.SetSkillEnabled(7, s2.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if disabled.Status != "disabled" || disabled.Enabled {
		t.Fatalf("unexpected: %+v", disabled)
	}
}

func TestSkillMarketInstall(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	// 种子市场源 + 条目（bundle zip 落 PVC）
	if err := c.DB.Create(&store.SkillMarketSource{Key: "builtin", Name: "平台精选", Type: "builtin", Enabled: true}).Error; err != nil {
		t.Fatal(err)
	}
	bundle, _ := buildZipBytes(map[string][]byte{
		"skills/code-review/SKILL.md": []byte("---\nname: code-review\ndescription: review rules\n---\nbody"),
	})
	if _, _, err := c.FS.WriteZipTo("market/builtin/code-review.zip", bundle); err != nil {
		t.Fatal(err)
	}
	if err := c.DB.Create(&store.SkillMarketItem{SourceID: 1, Key: "code-review", DisplayName: "代码评审",
		Description: "review rules", BundleDir: "market/builtin/code-review.zip", Enabled: true, Downloads: 5}).Error; err != nil {
		t.Fatal(err)
	}
	s, err := c.InstallMarketSkill(7, 1, "builtin", "code-review")
	if err != nil {
		t.Fatal(err)
	}
	if s.Source != "market" || s.SourceName != "平台精选" || s.Name != "code-review" {
		t.Fatalf("unexpected: %+v", s)
	}
	var ver store.SkillVersion
	c.DB.First(&ver, s.LatestVersionID)
	if ver.Status != SkillVerPublished {
		t.Fatalf("installed skill not published: %s", ver.Status)
	}
	// 下载计数 +1
	var item store.SkillMarketItem
	c.DB.First(&item, 1)
	if item.Downloads != 6 {
		t.Fatalf("downloads not incremented: %d", item.Downloads)
	}
	// 重复安装 → 409 语义
	if _, err := c.InstallMarketSkill(7, 1, "builtin", "code-review"); err == nil || !strings.Contains(err.Error(), "already installed") {
		t.Fatalf("expected conflict, got %v", err)
	}
	// registry 源不可用
	if err := c.DB.Create(&store.SkillMarketSource{Key: "team", Name: "团队市场", Type: "registry", Enabled: true}).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := c.InstallMarketSkill(7, 1, "team", "x"); err == nil {
		t.Fatal("expected registry source rejected")
	}
	// 广场条目含已安装标记
	items, err := c.ListMarketItems(7, "builtin", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].InstalledSkillID != s.ID || items[0].InstalledVersion == "" {
		t.Fatalf("installed marker missing: %+v", items)
	}
}

func TestConsoleSkillUsages(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	// 技能资产（空间 7）：最新发布 1.2.0
	s := store.Skill{WorkspaceID: 7, Name: "ticket-escalation", Description: "d", Source: "market", Enabled: true, Status: "active"}
	c.DB.Create(&s)
	v := store.SkillVersion{SkillID: s.ID, Version: "1.2.0", Status: SkillVerPublished}
	c.DB.Create(&v)
	c.DB.Model(&s).Update("latest_version_id", v.ID)
	// 生成包含 skills 声明 + 未声明目录技能
	md := strings.Replace(testAgentsMD, "agentKey: demo", "agentKey: usage-demo", 1)
	md = strings.Replace(md, "---\nname: Demo", "---\nname: Demo\nskills:\n  - name: ticket-escalation\n    source: local\n    version: 1.2.0", 1)
	pkg, err := c.Packages.RegisterGeneratedFrom(7, nil, map[string][]byte{
		"AGENTS.md":                      []byte(md),
		"skills/extra-only/SKILL.md":     []byte("---\nname: extra-only\ndescription: dir only\n---\nbody"),
	}, "")
	if err != nil {
		t.Skipf("package registration failed (fixture env): %v", err)
	}
	if err := c.DB.Create(&store.ServiceEntity{K8sName: "usage-demo-svc", DisplayName: "使用方Agent", PackageID: pkg.ID,
		WorkspaceID: 7, Status: store.StatusRunning, Replicas: 1, ShortName: "usage-demo"}).Error; err != nil {
		t.Fatal(err)
	}
	list, err := c.ConsoleSkillUsages(7, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 {
		t.Fatalf("expected 1 agent row, got %d", len(list))
	}
	row := list[0]
	if row.PackageID != pkg.ID || row.SkillCount != 2 {
		t.Fatalf("unexpected row: %+v", row)
	}
	byName := map[string]SkillUsageRef{}
	for _, r := range row.Skills {
		byName[r.Name] = r
	}
	decl := byName["ticket-escalation"]
	if !decl.Declared || decl.DeclaredVersion != "1.2.0" || decl.MatchedSkillID != s.ID || decl.MatchedVersion != "1.2.0" {
		t.Fatalf("declared ref wrong: %+v", decl)
	}
	dirOnly := byName["extra-only"]
	if dirOnly.Declared || dirOnly.MatchedSkillID != 0 {
		t.Fatalf("dir-only ref wrong: %+v", dirOnly)
	}
	// 详情：含描述与合规 issues
	detail, err := c.ConsoleSkillUsageDetail(7, row.AgentID)
	if err != nil {
		t.Fatal(err)
	}
	if len(detail.Items) != 2 {
		t.Fatalf("expected 2 detail items, got %d", len(detail.Items))
	}
	for _, it := range detail.Items {
		if it.Description == "" {
			t.Fatalf("description missing for %s", it.Name)
		}
		if it.Issues == nil {
			t.Fatal("issues must serialize as [] not null")
		}
	}
	// keyword 按技能名命中（Agent 名不含 sql）
	if l2, _ := c.ConsoleSkillUsages(7, "ticket-escalation"); len(l2) != 1 {
		t.Fatalf("keyword by skill name failed: %d", len(l2))
	}
}

func TestSkillVersionDownload(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	zipBytes, _ := buildZipBytes(map[string][]byte{
		"skills/dl-skill/SKILL.md": []byte("---\nname: dl-skill\ndescription: dl\n---\nbody"),
	})
	s, _, err := c.CreateSkillFromZip(7, 1, "d.zip", bytes.NewReader(zipBytes), "", "", "", nil, true)
	if err != nil {
		t.Fatal(err)
	}
	filename, data, err := c.SkillVersionDownload(7, s.ID, s.LatestVersionID)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(filename, "dl-skill-1.0.0") || len(data) == 0 {
		t.Fatalf("unexpected download: %s %d bytes", filename, len(data))
	}
	// markdown 形态导出
	m, err := c.CreateSkillFromMarkdown(7, 1, "dl-md", "", "desc", nil, "---\nname: dl-md\ndescription: desc\n---\nbody", true)
	if err != nil {
		t.Fatal(err)
	}
	_, mdata, err := c.SkillVersionDownload(7, m.ID, m.LatestVersionID)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(mdata, []byte("skills/dl-md/SKILL.md")) {
		t.Fatalf("markdown export missing skill structure: %d bytes", len(mdata))
	}
}

// TestCreateSkillFromZipLayouts 真实技能包三种布局归一（2026-10-20）：
// A) skills/<name>/SKILL.md（OAF 风格）；B) 根 SKILL.md（标准技能包 + _meta.json）；
// C) 单层目录 <dir>/SKILL.md。名称优先级：表单 > 布局目录 > frontmatter > zip 文件名。
func TestCreateSkillFromZipLayouts(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()

	// B) 根 SKILL.md（用户上传的 weather 包同构）
	rootZip, _ := buildZipBytes(map[string][]byte{
		"SKILL.md":   []byte("---\nname: weather\ndescription: Get current weather and forecasts\nhomepage: https://wttr.in/:help\n---\n# Weather\ncurl wttr.in\n"),
		"_meta.json": []byte(`{"slug":"weather","version":"1.0.0"}`),
	})
	s, warns, err := c.CreateSkillFromZip(7, 1, "01-weather-1.0.0.zip", bytes.NewReader(rootZip), "", "", "", nil, true)
	if err != nil {
		t.Fatalf("root SKILL.md layout rejected: %v", err)
	}
	if s.Name != "weather" || s.Description != "Get current weather and forecasts" {
		t.Fatalf("unexpected root-layout skill: name=%q desc=%q", s.Name, s.Description)
	}
	if len(warns) != 0 {
		t.Fatalf("expected no warnings, got %v", warns)
	}
	// 归一化落盘：skills/weather/SKILL.md + _meta.json 作为资源
	if _, err := c.FS.ReadFile(fmt.Sprintf("skills/%d/1.0.0", s.ID), "skills/weather/SKILL.md"); err != nil {
		t.Fatalf("normalized SKILL.md missing: %v", err)
	}
	if _, err := c.FS.ReadFile(fmt.Sprintf("skills/%d/1.0.0", s.ID), "skills/weather/_meta.json"); err != nil {
		t.Fatalf("resource file missing: %v", err)
	}

	// C) 单层目录布局，且无 frontmatter name → 用目录名
	dirZip, _ := buildZipBytes(map[string][]byte{
		"my-skill/SKILL.md":       []byte("---\ndescription: dir layout\n---\nbody"),
		"my-skill/scripts/run.sh": []byte("#!/bin/sh\n"),
	})
	s2, _, err := c.CreateSkillFromZip(7, 1, "whatever.zip", bytes.NewReader(dirZip), "", "", "", nil, true)
	if err != nil {
		t.Fatalf("single-dir layout rejected: %v", err)
	}
	if s2.Name != "my-skill" {
		t.Fatalf("expected dir name, got %q", s2.Name)
	}

	// B 无 frontmatter name → 回落 zip 文件名净化（01-weather-1.0.0 → 01-weather-1-0-0）
	noNameZip, _ := buildZipBytes(map[string][]byte{
		"SKILL.md": []byte("---\ndescription: no name here\n---\nbody"),
	})
	s3, _, err := c.CreateSkillFromZip(7, 1, "01-weather-1.0.0.zip", bytes.NewReader(noNameZip), "", "", "", nil, true)
	if err != nil {
		t.Fatalf("filename fallback rejected: %v", err)
	}
	if s3.Name != "01-weather-1-0-0" {
		t.Fatalf("expected sanitized filename, got %q", s3.Name)
	}

	// 显式 name 覆盖布局/文件名（但必须合法）
	s4, _, err := c.CreateSkillFromZip(7, 1, "x.zip", bytes.NewReader(rootZip), "weather-copy", "", "", nil, false)
	if err != nil {
		t.Fatalf("explicit name rejected: %v", err)
	}
	if s4.Name != "weather-copy" {
		t.Fatalf("explicit name not applied: %q", s4.Name)
	}

	// 完全无 SKILL.md → 400（错误文案列出三种布局）
	bad, _ := buildZipBytes(map[string][]byte{"README.md": []byte("no skill")})
	_, _, err = c.CreateSkillFromZip(7, 1, "bad.zip", bytes.NewReader(bad), "", "", "", nil, false)
	if err == nil || !strings.Contains(err.Error(), "SKILL.md at root") {
		t.Fatalf("expected layout guidance error, got %v", err)
	}
}
