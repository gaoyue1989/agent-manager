// M3 包生成器与创建/编辑链路测试：命名派生 / frontmatter golden / mcp config /
// modelConnections 校验 / 创建发布集成 / 编辑 copy-on-write。
package service

import (
	"regexp"
	"strings"
	"testing"

	"agent-manager/backend/internal/oaf"
	"agent-manager/backend/internal/store"
)

func TestSlugifyAgentKey(t *testing.T) {
	cases := []struct{ in, wantPrefix string }{
		{"Hello World", "hello-world"},
		{"  --Foo__Bar!!--  ", "foo-bar"},
		{"API 网关 v2", "api-v2"}, // 中文剥离、空格转连字符
		{"---", "agent-"},        // 空结果回退 agent-{hex}
		{"客服工单助手", "agent-"},  // 纯中文回退
	}
	for _, tc := range cases {
		got := SlugifyAgentKey(tc.in)
		if !strings.HasPrefix(got, tc.wantPrefix) {
			t.Errorf("SlugifyAgentKey(%q) = %q, want prefix %q", tc.in, got, tc.wantPrefix)
		}
		if !regexp.MustCompile(`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`).MatchString(got) {
			t.Errorf("not kebab: %q", got)
		}
	}
	// 回退形态带随机后缀
	if got := SlugifyAgentKey("中文"); got == "agent-" {
		t.Fatalf("fallback should carry hex suffix: %q", got)
	}
}

func TestNextVersion(t *testing.T) {
	for _, tc := range [][2]string{{"1.0.0", "1.0.1"}, {"1.0.999", "1.1.0"}, {"2.3.4", "2.3.5"}} {
		if got := nextVersion(tc[0]); got != tc[1] {
			t.Errorf("nextVersion(%s)=%s want %s", tc[0], got, tc[1])
		}
	}
}

// seedVerifiedConn 造一条已验证模型连接。
func seedVerifiedConn(t *testing.T, c *Core, wsID uint, name, modelID, baseURL string) *store.ModelConnection {
	t.Helper()
	view, err := c.CreateModelConnection(wsID, CreateModelConnInput{
		Name: name, ModelID: modelID, BaseURL: baseURL, APIKey: "sk-test", Provider: "openai"})
	if err != nil {
		t.Fatalf("seed conn: %v", err)
	}
	if err := c.DB.Model(&store.ModelConnection{}).Where("id = ?", view.ID).
		Update("status", "verified").Error; err != nil {
		t.Fatal(err)
	}
	return &store.ModelConnection{ID: view.ID, WorkspaceID: wsID, Name: name,
		Provider: "openai", ModelID: modelID, BaseURL: baseURL, APIKey: "sk-test",
		Status: "verified", Temperature: 0.3, MaxTokens: 16384, TimeoutSec: 120}
}

func goldenForm() *AgentForm {
	pub := true
	return &AgentForm{
		Mode: "harness", Name: "demo-agent", Description: "demo desc",
		ModelConnections: []ModelConnRef{{ConnectionID: 1, IsDefault: true}},
		Prompt:           "你是测试助手。",
		Skills:           []SkillRef{{Name: "extra-skill", Source: "package", PackageID: 1}},
		SubAgents:        []SubAgentRef{{Agent: "sub-1", Role: "分类", Delegations: []string{"任务分类"}, Endpoint: "http://sub-1-svc:8100"}},
		McpServers:       []McpServerRef{{Builtin: "platform"}},
		Spec:             "medium", Replicas: 2, PublicEndpoint: &pub,
	}
}

// TestAgentGenFrontmatterGolden frontmatter 快照：identity/model/skills/agents/mcpServers 段
// 与决议 5 + agent-framework 契约一致（agents 键 + endpoint 字段）。
func TestAgentGenFrontmatterGolden(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-77770007", "生成空间")
	pkg := uploadTestPkg(t, c, "") // skills 源包（extra-skill 目录不存在 → 宽松 warning）
	conn := seedVerifiedConn(t, c, ws.ID, "qwen-plus", "qwen-plus", "https://llm.example/v1")
	form := goldenForm()
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills[0].PackageID = pkg.ID

	fm := c.deriveFrontmatter(form, "demo-agent", "1.0.0")
	if fm.Name != "demo-agent" || fm.AgentKey != "demo-agent" || fm.Version != "1.0.0" {
		t.Fatalf("identity: %+v", fm)
	}
	if fm.Model == nil || fm.Model.ModelID != "qwen-plus" {
		t.Fatalf("model section: %+v", fm.Model)
	}
	if len(fm.Skills) != 1 || fm.Skills[0].Name != "extra-skill" || fm.Skills[0].Source != "local" {
		t.Fatalf("skills: %+v", fm.Skills)
	}
	if len(fm.Agents) != 1 || fm.Agents[0].Endpoint != "http://sub-1-svc:8100" || fm.Agents[0].Agent != "sub-1" {
		t.Fatalf("agents (runtime contract key=agents + endpoint): %+v", fm.Agents)
	}
	if len(fm.MCPServers) != 1 || fm.MCPServers[0].ConfigDir != "mcp-configs/platform" {
		t.Fatalf("mcpServers: %+v", fm.MCPServers)
	}
	md, err := buildAgentsMD(fm, form.Prompt)
	if err != nil {
		t.Fatal(err)
	}
	// 生成包必须能通过 OAF 解析与校验（与上传包同管线）
	cfg, err := oaf.ParseOAF(md)
	if err != nil {
		t.Fatalf("parse generated AGENTS.md: %v\n%s", err, md)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("validate: %v\n%s", err, md)
	}
	if !strings.Contains(md, "endpoint: http://sub-1-svc:8100") {
		t.Fatalf("endpoint missing in frontmatter:\n%s", md)
	}
	if strings.HasSuffix(md, "\n\n\n") || !strings.Contains(md, "你是测试助手。") {
		t.Fatalf("body: %q", md)
	}
}

// TestAgentGenMcpConfigGolden config.yaml 字段契约（M6 §2.3）。
func TestAgentGenMcpConfigGolden(t *testing.T) {
	got := mcpConfigYAML("ticket-system", "streamable_http", "http://mcp.internal:9090/mcp", 90,
		`{"mode":"all"}`, `{"tools":{"ticket_query":"allow","ticket_escalate":"ask"}}`)
	for _, want := range []string{
		"server: ticket-system", "vendor: agentmanager", "type: streamableHttp",
		"url: http://mcp.internal:9090/mcp", "timeout: 90",
		"ticket_query: allow", "ticket_escalate: ask",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("config.yaml missing %q:\n%s", want, got)
		}
	}
	// mode=all 未声明工具不写 tools 段
	empty := mcpConfigYAML("n", "sse", "http://x/sse", 0, `{"mode":"all"}`, `{}`)
	if strings.Contains(empty, "tools:") {
		t.Fatalf("empty permissions should omit tools:\n%s", empty)
	}
	if !strings.Contains(builtinPlatformMCPConfig(), "platform-publisher") {
		t.Fatal("builtin template")
	}
}

// TestCreateAgentHappyPath 创建→发布（fake k8s）→ running → 包 frontmatter/env/delivery 全链。
func TestCreateAgentHappyPath(t *testing.T) {
	c, fk, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-88880008", "创建空间")
	conn := seedVerifiedConn(t, c, ws.ID, "qwen-plus", "qwen-plus", "https://llm.example/v1")
	form := goldenForm()
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills = nil

	_ = fk
	svc, pkg, err := c.CreateAgent(ws.ID, 1, form)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if pkg.Version != "1.0.0" || pkg.WorkspaceID != ws.ID {
		t.Fatalf("pkg: %+v", pkg)
	}
	// frontmatter 落包（读 PVC 文件）
	agentsMD, err := c.Packages.ReadFile(pkg, "AGENTS.md")
	if err != nil {
		t.Fatalf("read agents.md: %v", err)
	}
	if !strings.Contains(string(agentsMD), "vendorKey: agentmanager") ||
		!strings.Contains(string(agentsMD), "slug: agentmanager/demo-agent") {
		t.Fatalf("frontmatter:\n%s", agentsMD)
	}
	// 默认连接 env 注入 + key 走 Secret
	env := map[string]string{}
	secret := map[string]string{}
	if err := c.DB.First(svc, svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	if err := jsonUnmarshalStrict(svc.EnvJSON, &env); err != nil {
		t.Fatal(err)
	}
	if env["LLM_MODEL"] != "qwen-plus" || env["LLM_BASE_URL"] != "https://llm.example/v1" {
		t.Fatalf("LLM env: %+v", env)
	}
	if err := jsonUnmarshalStrict(svc.EnvSecretJSON, &secret); err != nil {
		t.Fatal(err)
	}
	if secret["LLM_API_KEY"] != "sk-test" {
		t.Fatalf("secret key: %+v", secret)
	}
	if _, ok := env["LLM_API_KEY"]; ok {
		t.Fatal("LLM_API_KEY must not be in plain env")
	}
	// 下发登记 pending
	deliveries, err := c.ListServiceDeliveries(svc.ID)
	if err != nil || len(deliveries) != 1 || deliveries[0].Status != "pending" {
		t.Fatalf("deliveries: %+v err=%v", deliveries, err)
	}
	// 归属
	if svc.WorkspaceID != ws.ID || svc.CreatedBy != 1 {
		t.Fatalf("ownership: %+v", svc)
	}
	_ = fk
}

// TestCreateAgentValidation 校验矩阵：空 prompt / 空连接 / 未验证连接 / 多默认 / 重名。
func TestCreateAgentValidation(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-99990009", "校验空间")
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "m1", "http://b1/v1")
	bad := seedVerifiedConn(t, c, ws.ID, "c2", "m2", "http://b2/v1")
	if err := c.DB.Model(&store.ModelConnection{}).Where("id = ?", bad.ID).
		Update("status", "unverified").Error; err != nil {
		t.Fatal(err)
	}
	form := goldenForm()
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills, form.SubAgents, form.McpServers = nil, nil, nil

	// 空 prompt
	f := *form
	f.Prompt = " "
	if _, _, err := c.CreateAgent(ws.ID, 1, &f); err == nil {
		t.Fatal("empty prompt should fail")
	}
	// 未验证连接
	f = *form
	f.ModelConnections = []ModelConnRef{{ConnectionID: bad.ID}}
	if _, _, err := c.CreateAgent(ws.ID, 1, &f); err == nil || !strings.Contains(err.Error(), "not verified") {
		t.Fatalf("unverified conn: %v", err)
	}
	// 多默认
	f = *form
	f.ModelConnections = []ModelConnRef{{ConnectionID: conn.ID, IsDefault: true}, {ConnectionID: conn.ID, IsDefault: true}}
	if _, _, err := c.CreateAgent(ws.ID, 1, &f); err == nil || !strings.Contains(err.Error(), "default") {
		t.Fatalf("multi default: %v", err)
	}
	// 重名（空间内 slug 冲突）
	if _, _, err := c.CreateAgent(ws.ID, 1, form); err != nil {
		t.Fatalf("first create: %v", err)
	}
	if _, _, err := c.CreateAgent(ws.ID, 1, form); err == nil || !strings.Contains(err.Error(), "duplicate") {
		t.Fatalf("duplicate: %v", err)
	}
}

// TestUpdateAgentCopyOnWrite 编辑链路：改提示词 → v1.0.1 新版本（源包保留）→ republish。
func TestUpdateAgentCopyOnWrite(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-aaaa1111", "编辑空间")
	owner := seedUserIn(t, c, "ed", "user", ws.ID, "owner")
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "m1", "http://b1/v1")
	form := goldenForm()
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills, form.SubAgents, form.McpServers = nil, nil, nil
	svc, pkgV1, err := c.CreateAgent(ws.ID, owner.ID, form)
	if err != nil {
		t.Fatalf("create: %v", err)
	}

	// 等待状态机推进完成再编辑（running/register_failed 皆可 republish）
	if err := c.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).
		Update("status", store.StatusRunning).Error; err != nil {
		t.Fatal(err)
	}

	edit := *form
	edit.Prompt = "改写后的提示词 v2。"
	_, pkgV2, err := c.UpdateAgent(ws.ID, owner.ID, svc.ID, &edit)
	if err != nil {
		t.Fatalf("update: %v", err)
	}
	if pkgV2.Version != "1.0.1" {
		t.Fatalf("version bump: %s", pkgV2.Version)
	}
	if pkgV2.SourcePackageID != pkgV1.ID {
		t.Fatalf("copy-on-write source: %+v", pkgV2)
	}
	// 旧版本仍在（版本链两条）
	versions, err := c.ConsoleAgentVersions(ws.ID, svc.ID)
	if err != nil || len(versions) != 2 {
		t.Fatalf("versions: %v n=%d", err, len(versions))
	}
	// 显示名同步（未改 name → 不变）；改 name → displayName 跟随
	edit2 := edit
	edit2.Prompt = "v3"
	edit2.Name = "新显示名"
	updated2, pkgV3, err := c.UpdateAgent(ws.ID, owner.ID, svc.ID, &edit2)
	if err != nil {
		t.Fatalf("update2: %v", err)
	}
	if updated2.DisplayName != "新显示名" {
		t.Fatalf("display name: %q", updated2.DisplayName)
	}
	if pkgV3.Version != "1.0.2" {
		t.Fatalf("v3: %s", pkgV3.Version)
	}
	// 编辑预填回读
	view, err := c.EditForm(ws.ID, svc.ID)
	if err != nil {
		t.Fatalf("edit-form: %v", err)
	}
	if view.Prompt != "v3" || view.PackageID != pkgV3.ID {
		t.Fatalf("edit form: %+v", view)
	}
	if len(view.ModelConnections) != 1 {
		t.Fatalf("model connections: %+v", view.ModelConnections)
	}
}

func TestEditFormCrossWorkspace(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-bbbb2222", "编辑空间B")
	wsX := seedWs(t, c, "ws-cccc3333", "别处")
	uploadTestPkg(t, c, "")
	svc := &store.ServiceEntity{K8sName: "oaf-edit-x", DisplayName: "x",
		PackageID: 1, WorkspaceID: wsX.ID, Status: store.StatusRunning}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := c.EditForm(ws.ID, svc.ID); err != ErrNotFound {
		t.Fatalf("cross workspace edit-form: %v", err)
	}
}
