// M3 包生成器与创建/编辑链路测试：命名派生 / frontmatter golden / mcp config /
// modelConnections 校验 / 创建发布集成 / 编辑 copy-on-write。
package service

import (
	"errors"
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
		{"---", "agent-"},       // 空结果回退 agent-{hex}
		{"客服工单助手", "agent-"},    // 纯中文回退
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
		`{"mode":"all"}`, `{"tools":{"ticket_query":"allow","ticket_escalate":"ask"}}`, "none")
	for _, want := range []string{
		"server: ticket-system", "vendor: agentmanager", "type: streamableHttp",
		"url: http://mcp.internal:9090/mcp", "timeout: 90",
		"ticket_query: allow", "ticket_escalate: ask",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("config.yaml missing %q:\n%s", want, got)
		}
	}
	// 无认证连接器不写 auth 段
	if strings.Contains(got, "auth:") {
		t.Fatalf("none authType should omit auth:\n%s", got)
	}
	// mode=all 未声明工具不写 tools 段
	empty := mcpConfigYAML("n", "sse", "http://x/sse", 0, `{"mode":"all"}`, `{}`, "")
	if strings.Contains(empty, "tools:") {
		t.Fatalf("empty permissions should omit tools:\n%s", empty)
	}
	if !strings.Contains(builtinPlatformMCPConfig(), "platform-publisher") {
		t.Fatal("builtin template")
	}
}

// TestAgentGenMcpConfigBearerAuth bearer 连接器必须输出 auth.token 占位符（issue #139）：
// 真实 token 走服务 Secret env（MCP_TOKEN_<NAME>），包内只有运行时可解析的 ${ENV} 引用。
func TestAgentGenMcpConfigBearerAuth(t *testing.T) {
	got := mcpConfigYAML("mcd-mcp", "streamableHttp", "https://mcp.mcd.cn", 120,
		`{"mode":"all"}`, `{}`, "bearer")
	if !strings.Contains(got, "token: ${MCP_TOKEN_MCD_MCP}") {
		t.Fatalf("bearer auth token placeholder missing:\n%s", got)
	}
	if strings.Contains(got, "sk-") {
		t.Fatalf("real token must never land in package:\n%s", got)
	}
	// env 键与 buildPublishEnv 注入键同源：sanitizeEnvKey（连字符转下划线）
	if got := mcpConfigYAML("my-mcp.x", "sse", "http://x/sse", 0, `{}`, `{}`, "bearer"); !strings.Contains(got, "${MCP_TOKEN_MY_MCP_X}") {
		t.Fatalf("sanitized env key missing:\n%s", got)
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

// TestCreateAgentChineseNameK8sDerive 纯中文显示名（决议 5 同源派生）：k8s 名从
// agentKey 派生而非中文名（中文 sanitize 后为兜底 "svc" 名，部署实测撞名 deploy_failed）。
func TestCreateAgentChineseNameK8sDerive(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-dddd4444", "中文名空间")
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "m1", "http://b1/v1")
	form := goldenForm()
	form.Name = "部署验证助手"
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills, form.SubAgents, form.McpServers = nil, nil, nil
	svc, pkg, err := c.CreateAgent(ws.ID, 1, form)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	agentKey := pkg.Slug[len("agentmanager/"):] // 从生成包反向取（确定性回退下与重新派生一致）
	if svc.K8sName != "oaf-"+agentKey {
		t.Fatalf("k8s name should derive from agentKey: %q (agentKey=%q)", svc.K8sName, agentKey)
	}
	if svc.DisplayName != "部署验证助手" {
		t.Fatalf("display name kept: %q", svc.DisplayName)
	}
	if pkg.Slug != "agentmanager/"+agentKey {
		t.Fatalf("slug: %q", pkg.Slug)
	}
}

// TestCreateAgentMergesPlatformDefaults 创建链路合并平台默认配置（发布向导同语义）：
// checkpoint/redis 运行必需 env 自动带入；模型连接注入的 LLM_* 优先于 defaults。
func TestCreateAgentMergesPlatformDefaults(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-eeee5555", "默认配置空间")
	// 平台默认配置：写入 checkpoint + LLM_*（后者应被连接注入覆盖）
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "CHECKPOINT_JDBC_URL",
		Value: "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "LLM_MODEL",
		Value: "wrong-default"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "LLM_MODEL_ID",
		Value: "wrong-default-id"}).Error; err != nil {
		t.Fatal(err)
	}
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "right-model", "http://b1/v1")
	form := goldenForm()
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills, form.SubAgents, form.McpServers = nil, nil, nil
	svc, _, err := c.CreateAgent(ws.ID, 1, form)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	env := map[string]string{}
	if err := jsonUnmarshalStrict(svc.EnvJSON, &env); err != nil {
		t.Fatal(err)
	}
	// 默认值合并后经发布隔离派生改写为 per-service 库名（issue #142）
	if env["CHECKPOINT_JDBC_URL"] != "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint_oaf_demo_agent?createDatabaseIfNotExist=true" {
		t.Fatalf("checkpoint default not merged+derived: %+v", env)
	}
	if env["LLM_MODEL"] != "right-model" {
		t.Fatalf("connection must override defaults: %q", env["LLM_MODEL"])
	}
	if env["LLM_MODEL_ID"] != "right-model" {
		t.Fatalf("legacy system-model key must follow connection: %q", env["LLM_MODEL_ID"])
	}
}

// TestSlugifyDigitLeading 中文名+数字尾缀派生出数字开头 agentKey 时补 agent- 前缀
// （OAF kebab 校验 ^[a-z] 开头；部署实测整包校验失败）。
func TestSlugifyDigitLeading(t *testing.T) {
	got := SlugifyAgentKey("部署验证-1792201311")
	if got[0] < 'a' || got[0] > 'z' {
		t.Fatalf("agentKey must start with letter, got %q", got)
	}
	if !regexp.MustCompile(`^[a-z][a-z0-9-]*$`).MatchString(got) {
		t.Fatalf("not kebab: %q", got)
	}
}

// TestSanitizeEnvKey 连接器名转 env 键：连字符转下划线（部署 e2e 实测非法键）。
func TestSanitizeEnvKey(t *testing.T) {
	for name, want := range map[string]string{
		"bearer-mcp-1792206205": "BEARER_MCP_1792206205",
		"ticket-system":         "TICKET_SYSTEM",
		"platform":              "PLATFORM",
		"a--b":                  "A_B",
	} {
		if got := sanitizeEnvKey(name); got != want {
			t.Errorf("sanitizeEnvKey(%q)=%q want %q", name, got, want)
		}
	}
}

// TestCreateAgentDescriptionRequired issue #150：描述留空必须 400 表单错误，
// 而不是包登记后的 502 invalid AGENTS.md。
func TestCreateAgentDescriptionRequired(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-55550005", "描述空间")
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "m1", "http://b1/v1")
	form := goldenForm()
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills, form.SubAgents, form.McpServers = nil, nil, nil
	form.Description = " "
	_, _, err := c.CreateAgent(ws.ID, 1, form)
	if err == nil || !errors.Is(err, ErrInvalidForm) || !strings.Contains(err.Error(), "description is required") {
		t.Fatalf("description required: %v", err)
	}
	// 未产生任何包记录
	var cnt int64
	c.DB.Model(&store.OafPackage{}).Where("workspace_id = ?", ws.ID).Count(&cnt)
	if cnt != 0 {
		t.Fatalf("no package should be created, got %d", cnt)
	}
}

// TestCreateAgentNameTooLong issue #149：超长名称 400（不再 502 Data too long）。
func TestCreateAgentNameTooLong(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-44440004", "长名空间")
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "m1", "http://b1/v1")
	mk := func(name string) *AgentForm {
		form := goldenForm()
		form.Name = name
		form.ModelConnections[0].ConnectionID = conn.ID
		form.Skills, form.SubAgents, form.McpServers = nil, nil, nil
		return form
	}
	// 展示名 200 字符（超 display_name 列 128）
	if _, _, err := c.CreateAgent(ws.ID, 1, mk(strings.Repeat("a", 200))); err == nil ||
		!strings.Contains(err.Error(), "name too long (max 128)") {
		t.Fatalf("200-char name: %v", err)
	}
	// ASCII 名 slug 后 49 字符（超 oaf- 前缀 + 子对象后缀预算 48）
	if _, _, err := c.CreateAgent(ws.ID, 1, mk(strings.Repeat("b", 49))); err == nil ||
		!strings.Contains(err.Error(), "max 48") {
		t.Fatalf("49-char slug name: %v", err)
	}
}

// TestCreateAgentCleansUpPackageOnPublishFailure issue #149/#151：包登记成功后
// Publish 失败必须回删包（不留孤儿），错误映射为 400/409（镜像白名单 → 400）。
func TestCreateAgentCleansUpPackageOnPublishFailure(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-33330003", "清理空间")
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "m1", "http://b1/v1")
	form := goldenForm()
	form.ModelConnections[0].ConnectionID = conn.ID
	form.Skills, form.SubAgents, form.McpServers = nil, nil, nil
	form.Image = "evil:not-allowed" // Publish 入口镜像白名单拒绝

	_, _, err := c.CreateAgent(ws.ID, 1, form)
	if err == nil {
		t.Fatal("publish should fail on image allowlist")
	}
	var cnt int64
	c.DB.Model(&store.OafPackage{}).Where("workspace_id = ?", ws.ID).Count(&cnt)
	if cnt != 0 {
		t.Fatalf("orphan package left behind: %d", cnt)
	}
}

// TestTranslateStorageError issue #149/#151：MySQL 驱动裸错误 → 业务哨兵。
func TestTranslateStorageError(t *testing.T) {
	_ = translateStorageError(nil)
	dup := translateStorageError(errors.New(
		"Error 1062 (23000): Duplicate entry 'oaf-x' for key 'services.idx_services_k8s_name'"))
	if !errors.Is(dup, ErrConflict) || !strings.Contains(dup.Error(), "duplicate_name") {
		t.Fatalf("1062 should map to ErrConflict: %v", dup)
	}
	long := translateStorageError(errors.New(
		"Error 1406 (22001): Data too long for column 'k8s_name' at row 1"))
	if !errors.Is(long, ErrInvalidForm) {
		t.Fatalf("1406 should map to ErrInvalidForm: %v", long)
	}
	other := errors.New("connection refused")
	if translateStorageError(other) != other {
		t.Fatal("unrelated errors must pass through")
	}
}

// TestCreateAgentImportMergesPlatformDefaults issue #161：导入链路与 harness 同语义——
// 平台默认 env 恒合并（包不带 DB/LLM env 时曾缺 CHECKPOINT_*/LLM_* → Pod Access
// denied CrashLoopBackOff）；勾选模型连接时 LLM_* 覆盖默认值；不勾选则保留平台默认。
func TestCreateAgentImportMergesPlatformDefaults(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-55550009", "导入空间")
	for _, kv := range [][2]string{
		{"CHECKPOINT_JDBC_URL", "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"},
		{"CHECKPOINT_USERNAME", "agent_manager"},
		{"CHECKPOINT_PASSWORD", "pw"},
		{"LLM_BASE_URL", "http://gw/v1"},
	} {
		if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: kv[0], Value: kv[1]}).Error; err != nil {
			t.Fatal(err)
		}
	}
	pkg := uploadTestPkg(t, c, "")
	envOf := func(svc *store.ServiceEntity) map[string]string {
		env := map[string]string{}
		if err := jsonUnmarshalStrict(svc.EnvJSON, &env); err != nil {
			t.Fatal(err)
		}
		return env
	}

	// 不勾选模型连接：平台默认 env 全量带入（含 secret 路由的密码键）
	form := &AgentForm{Mode: "import", Name: "import-no-conn", ImportPackageID: &pkg.ID, Replicas: 1}
	svc, _, err := c.CreateAgentImport(ws.ID, 1, form, pkg.ID)
	if err != nil {
		t.Fatalf("import without conns: %v", err)
	}
	env := envOf(svc)
	if env["CHECKPOINT_USERNAME"] != "agent_manager" || env["CHECKPOINT_JDBC_URL"] == "" {
		t.Fatalf("platform defaults not merged: %+v", env)
	}
	if env["CHECKPOINT_JDBC_URL"] != "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint_oaf_import_no_conn?createDatabaseIfNotExist=true" {
		t.Fatalf("isolation derivation missing: %q", env["CHECKPOINT_JDBC_URL"])
	}

	// 勾选模型连接：LLM_* 来自连接（覆盖平台默认），并登记下发
	conn := seedVerifiedConn(t, c, ws.ID, "c1", "gpt-x", "http://b1/v1")
	form = &AgentForm{Mode: "import", Name: "import-with-conn",
		ModelConnections: []ModelConnRef{{ConnectionID: conn.ID, IsDefault: true}},
		ImportPackageID:  &pkg.ID, Replicas: 1}
	svc2, _, err := c.CreateAgentImport(ws.ID, 1, form, pkg.ID)
	if err != nil {
		t.Fatalf("import with conns: %v", err)
	}
	env = envOf(svc2)
	if env["LLM_BASE_URL"] != "http://b1/v1" || env["LLM_MODEL"] != "gpt-x" {
		t.Fatalf("connection env not injected: %+v", env)
	}
	// API key 按 secretKeys 路由进服务 Secret（不落 plain env）
	secret := map[string]string{}
	if err := jsonUnmarshalStrict(svc2.EnvSecretJSON, &secret); err != nil {
		t.Fatal(err)
	}
	if secret["LLM_API_KEY"] != "sk-test" {
		t.Fatalf("LLM_API_KEY must route to service secret: %+v", secret)
	}
	if env["CHECKPOINT_USERNAME"] != "agent_manager" {
		t.Fatalf("platform defaults must merge with connection env: %+v", env)
	}
}

// TestCreateAgentImportCleanupOnPublishFailure 导入失败语义：派生副本回删（不留孤儿），
// 本空间已上传包保留（用户资产）。
func TestCreateAgentImportCleanupOnPublishFailure(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-55550010", "导入清理空间")
	pub := uploadTestPkg(t, c, "2") // 公共包（workspace_id=0）→ 导入派生副本
	own := uploadTestPkg(t, c, "3") // 需要本空间包：上传后改归属
	if err := c.DB.Model(&store.OafPackage{}).Where("id = ?", own.ID).
		Update("workspace_id", ws.ID).Error; err != nil {
		t.Fatal(err)
	}
	bad := "evil:not-allowed" // Publish 入口镜像白名单拒绝

	form := &AgentForm{Mode: "import", ImportPackageID: &pub.ID, Replicas: 1, Image: bad}
	if _, _, err := c.CreateAgentImport(ws.ID, 1, form, pub.ID); err == nil {
		t.Fatal("publish should fail on image allowlist")
	}
	var cnt int64
	c.DB.Model(&store.OafPackage{}).Where("workspace_id = ?", ws.ID).Count(&cnt)
	if cnt != 1 { // 仅 own 保留；派生副本已回删
		t.Fatalf("derived copy must be cleaned up, workspace pkgs = %d", cnt)
	}

	form = &AgentForm{Mode: "import", ImportPackageID: &own.ID, Replicas: 1, Image: bad}
	if _, _, err := c.CreateAgentImport(ws.ID, 1, form, own.ID); err == nil {
		t.Fatal("publish should fail on image allowlist")
	}
	c.DB.Model(&store.OafPackage{}).Where("id = ?", own.ID).Count(&cnt)
	if cnt != 1 {
		t.Fatalf("own-space package must be kept: %d", cnt)
	}
}

// TestSkillDedupSameName issue #173：同名技能重复勾选（不同源包）→ frontmatter 只保留
// 首个声明，skills/ 目录只复制首个勾选的源目录——不再产生重复声明与静默覆盖。
func TestSkillDedupSameName(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	// 两个源包都带同名技能 webapp-testing（内容与 slug 各异）
	mk := func(agentKey string) *store.OafPackage {
		md := strings.Replace(testAgentsMD, "agentKey: demo", "agentKey: "+agentKey, 1)
		md = strings.Replace(md, "slug: acme/demo", "slug: acme/"+agentKey, 1)
		zipBytes := buildTestZip(map[string]string{
			"AGENTS.md":                      md,
			"skills/webapp-testing/SKILL.md": "---\nname: webapp-testing\ndescription: from " + agentKey + "\n---\nbody " + agentKey,
		})
		rec, err := c.Packages.Upload(agentKey+".zip", bytesReader(zipBytes))
		if err != nil {
			t.Fatal(err)
		}
		return rec
	}
	pkgA := mk("pkg-a")
	pkgB := mk("pkg-b")

	form := &AgentForm{
		Name: "dedup-agent", Description: "d", Prompt: "p",
		Skills: []SkillRef{
			{Name: "webapp-testing", Source: "package", PackageID: pkgA.ID},
			{Name: "webapp-testing", Source: "package", PackageID: pkgB.ID},
		},
	}
	fm := c.deriveFrontmatter(form, "dedup-agent", "1.0.0")
	if len(fm.Skills) != 1 || fm.Skills[0].Name != "webapp-testing" {
		t.Fatalf("frontmatter skills must dedup to 1, got %+v", fm.Skills)
	}
	files, err := c.buildAgentFiles(fm, form, nil)
	if err != nil {
		t.Fatal(err)
	}
	skillMD := string(files["skills/webapp-testing/SKILL.md"])
	if !strings.Contains(skillMD, "from pkg-a") {
		t.Fatalf("first-checked source must win, got: %s", skillMD)
	}
	if strings.Contains(skillMD, "from pkg-b") {
		t.Fatal("second same-name skill silently overwrote the first")
	}
}
