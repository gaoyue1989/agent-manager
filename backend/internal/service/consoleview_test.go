// M2 console 视图测试：概览聚合 / 列表视图（agent_card 摘要降级）/ 模板列表。
package service

import (
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
	sum, err := c.ConsoleSummary(ws.ID)
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if sum.Agents.Total != 4 || sum.Agents.Running != 2 || sum.Agents.Error != 1 || sum.Agents.Stopped != 1 {
		t.Fatalf("counts: %+v", sum.Agents)
	}

	list, err := c.ConsoleAgents(ws.ID, "", "")
	if err != nil || len(list) != 4 {
		t.Fatalf("agents: %v n=%d", err, len(list))
	}
	if list[0].Model != "qwen-plus" {
		t.Fatalf("model from env: %+v", list[0])
	}
	// 状态过滤
	running, _ := c.ConsoleAgents(ws.ID, store.StatusRunning, "")
	if len(running) != 2 {
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
		AgentCardJSON: "{corrupt", }
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
