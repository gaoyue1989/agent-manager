// M2 console 视图测试：概览聚合 / 列表视图（agent_card 摘要降级）/ 模板列表。
package service

import (
	"encoding/json"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

func TestConsoleSummaryAndAgents(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-11110001", "概览空间")
	_ = ws
	uploadTestPkg(t, c, "")

	// 直接造三条不同状态的服务
	for i, st := range []string{store.StatusRunning, store.StatusRunning, store.StatusError, store.StatusStopped} {
		svc := &store.ServiceEntity{K8sName: "oaf-svc-" + itoa(uint(i+10)), DisplayName: "svc" + itoa(uint(i+10)),
			PackageID: 1, WorkspaceID: ws.ID, Status: st, EnvJSON: `{"LLM_MODEL":"qwen-plus"}`}
		if err := c.DB.Create(svc).Error; err != nil {
			t.Fatal(err)
		}
	}
	// 造事件：概览「最近发布」时间线（join services 过滤本空间，倒序 10 条）
	for i, st := range []string{store.StatusRunning, store.StatusError} {
		svc := &store.ServiceEntity{K8sName: "oaf-ev-" + itoa(uint(i+30)), DisplayName: "ev" + itoa(uint(i+30)),
			PackageID: 1, WorkspaceID: ws.ID, Status: st}
		if err := c.DB.Create(svc).Error; err != nil {
			t.Fatal(err)
		}
		if err := c.DB.Create(&store.ServiceEvent{ServiceID: svc.ID, FromStatus: store.StatusCreated,
			ToStatus: st, Reason: "publish requested"}).Error; err != nil {
			t.Fatal(err)
		}
	}
	sum, err := c.ConsoleSummary(ws.ID)
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if sum.Agents.Total != 6 || sum.Agents.Running != 3 || sum.Agents.Error != 2 || sum.Agents.Stopped != 1 {
		t.Fatalf("counts: %+v", sum.Agents)
	}
	// 时间线非空（漏赋值时恒空数组，issue #144）且字段映射正确
	if len(sum.RecentEvents) != 2 {
		t.Fatalf("recentEvents: %+v", sum.RecentEvents)
	}
	if sum.RecentEvents[0].DisplayName == "" || sum.RecentEvents[0].ToStatus == "" || sum.RecentEvents[0].ServiceID == 0 {
		t.Fatalf("recentEvent mapping: %+v", sum.RecentEvents[0])
	}

	list, err := c.ConsoleAgents(ws.ID, "", "")
	if err != nil || len(list) != 6 {
		t.Fatalf("agents: %v n=%d", err, len(list))
	}
	byName, _ := c.ConsoleAgents(ws.ID, "", "svc10")
	if len(byName) != 1 || byName[0].Model != "qwen-plus" {
		t.Fatalf("model from env: %+v", byName)
	}
	// 状态过滤
	running, _ := c.ConsoleAgents(ws.ID, store.StatusRunning, "")
	if len(running) != 3 {
		t.Fatalf("status filter: %d", len(running))
	}
	// 关键字过滤
	byKw, _ := c.ConsoleAgents(ws.ID, "", "svc13")
	if len(byKw) != 1 {
		t.Fatalf("keyword filter: %d", len(byKw))
	}
}

func TestAgentCardSummaryDegrade(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-22220002", "降级空间")
	pkg := uploadTestPkg(t, c, "")
	// agent_card_json 损坏 → 摘要降级为包名+描述，不 500
	svc := &store.ServiceEntity{K8sName: "oaf-degrade", DisplayName: "降级服务",
		PackageID: pkg.ID, WorkspaceID: ws.ID, Status: store.StatusRunning,
		AgentCardJSON: "{corrupt"}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	list, err := c.ConsoleAgents(ws.ID, "", "")
	if err != nil || len(list) != 1 {
		t.Fatalf("list: %v", err)
	}
	if list[0].CardSummary.Name != pkg.Name {
		t.Fatalf("degraded summary: %+v", list[0].CardSummary)
	}
	if list[0].Version != pkg.Version {
		t.Fatalf("version fallback to package: %+v", list[0])
	}
}

func TestConsoleAgentDetailCrossWorkspace404(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	wsA := seedWs(t, c, "ws-33330003", "A")
	wsB := seedWs(t, c, "ws-44440004", "B")
	pkg := uploadTestPkg(t, c, "")
	svc := &store.ServiceEntity{K8sName: "oaf-cross", DisplayName: "跨空间",
		PackageID: pkg.ID, WorkspaceID: wsA.ID, Status: store.StatusRunning}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := c.ConsoleAgentDetail(wsA.ID, svc.ID); err != nil {
		t.Fatalf("same ws: %v", err)
	}
	if _, err := c.ConsoleAgentDetail(wsB.ID, svc.ID); err != ErrNotFound {
		t.Fatalf("cross ws should 404: %v", err)
	}
}

func TestTemplatesListAndToggle(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-55550005", "模板空间")
	pkg := uploadTestPkg(t, c, "")
	// 默认不在模板列表
	tpl, _ := c.Packages.ListTemplates()
	if len(tpl) != 0 {
		t.Fatalf("templates before toggle: %d", len(tpl))
	}
	if err := c.Packages.SetTemplate(pkg.ID, true); err != nil {
		t.Fatalf("set template: %v", err)
	}
	tpl, _ = c.Packages.ListTemplates()
	if len(tpl) != 1 || tpl[0].ID != pkg.ID {
		t.Fatalf("templates after toggle: %+v", tpl)
	}
	_ = ws
}

// TestSpaceSkillsUsedByNotNull 未被引用的技能 usedBy 必须序列化为 []（nil → null 会让
// 前端直接取 length 整页崩，issue #138）；被引用时回填服务显示名。
func TestSpaceSkillsUsedByNotNull(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-66660006", "技能空间")
	pkg := uploadTestPkg(t, c, "")
	if err := c.DB.Model(&store.OafPackage{}).Where("id = ?", pkg.ID).
		Update("manifest_json", `{"skills":[{"name":"skill-a","description":"d-a"}]}`).Error; err != nil {
		t.Fatal(err)
	}

	// 未被引用：usedBy = []（JSON 层面断言非 null）
	items, err := c.SpaceSkills(ws.ID, "")
	if err != nil || len(items) != 1 {
		t.Fatalf("items: %v n=%d", err, len(items))
	}
	b, _ := json.Marshal(items[0])
	if !strings.Contains(string(b), `"usedBy":[]`) {
		t.Fatalf("usedBy must serialize as [], got: %s", b)
	}

	// 空间内服务引用该包：usedBy = [显示名]
	svc := &store.ServiceEntity{K8sName: "oaf-skill-ref", DisplayName: "引用方",
		PackageID: pkg.ID, WorkspaceID: ws.ID, Status: store.StatusRunning}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	items, _ = c.SpaceSkills(ws.ID, "")
	if len(items) != 1 || len(items[0].UsedBy) != 1 || items[0].UsedBy[0] != "引用方" {
		t.Fatalf("usedBy ref: %+v", items)
	}
}

// TestSpaceSkillsDeclaredDescriptionFallback issue #164：声明式技能（frontmatter
// skills 无 description 字段）回读包内 SKILL.md 的 frontmatter 描述。
func TestSpaceSkillsDeclaredDescriptionFallback(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-66660007", "技能描述空间")
	pkg := uploadTestPkg(t, c, "")
	if err := c.DB.Model(&store.OafPackage{}).Where("id = ?", pkg.ID).
		Update("manifest_json", `{"skills":[{"name":"webapp-testing"},{"name":"ghost"}]}`).Error; err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.FS.WriteZipTo(pkg.DirPath, buildTestZip(map[string]string{
		"AGENTS.md":                      testAgentsMD,
		"skills/webapp-testing/SKILL.md": "---\nname: webapp-testing\ndescription: toolkit for web apps\n---\nbody",
	})); err != nil {
		t.Fatal(err)
	}
	items, err := c.SpaceSkills(ws.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	byName := map[string]SpaceSkillItem{}
	for _, it := range items {
		byName[it.Name] = it
	}
	if it, ok := byName["webapp-testing"]; !ok || !it.Declared || it.Description != "toolkit for web apps" {
		t.Fatalf("declared skill must read SKILL.md description: %+v", byName["webapp-testing"])
	}
	// 声明但目录缺失：标注 issue（不阻断清单）
	if it, ok := byName["ghost"]; !ok || len(it.Issues) != 1 || !strings.Contains(it.Issues[0], "directory missing") {
		t.Fatalf("ghost skill must be annotated: %+v", byName["ghost"])
	}
}

// TestSpaceSkillsIssuesAnnotation issue #163：目录扫描技能的不合规项标注在条目上。
func TestSpaceSkillsIssuesAnnotation(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-66660008", "技能合规空间")
	pkg := uploadTestPkg(t, c, "")
	if _, _, err := c.FS.WriteZipTo(pkg.DirPath, buildTestZip(map[string]string{
		"AGENTS.md":               testAgentsMD,
		"skills/broken/README.md": "no SKILL.md",
		"skills/valid/SKILL.md":   "---\nname: valid\ndescription: ok\n---\nbody",
	})); err != nil {
		t.Fatal(err)
	}
	items, err := c.SpaceSkills(ws.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	byName := map[string]SpaceSkillItem{}
	for _, it := range items {
		byName[it.Name] = it
	}
	if it, ok := byName["broken"]; !ok || len(it.Issues) != 1 || !strings.Contains(it.Issues[0], "missing skills/broken/SKILL.md") {
		t.Fatalf("broken must be annotated: %+v", byName["broken"])
	}
	if it, ok := byName["valid"]; !ok || len(it.Issues) != 0 {
		t.Fatalf("valid must have no issues: %+v", byName["valid"])
	}
}
