// 导出单链路测试（契约 §2.2/§3.3/§4.2）：夹具（sqlite + fake builder）+ 状态机 +
// 异步构建成功/失败 + 校验矩阵 + 归属/下载审计。
package service

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"agent-manager/backend/internal/imagebuilder"
	"agent-manager/backend/internal/store"
)

// fakeBuilder 内存假实现（契约 §3.3 形态）：记录构建输入，可注入 digest/错误/延迟。
type fakeBuilder struct {
	mu     sync.Mutex
	digest string
	err    error
	delay  time.Duration
	inputs []imagebuilder.BuildInput
}

func (f *fakeBuilder) Build(ctx context.Context, in imagebuilder.BuildInput) (imagebuilder.BuildOutput, error) {
	if f.delay > 0 {
		time.Sleep(f.delay)
	}
	f.mu.Lock()
	f.inputs = append(f.inputs, in)
	f.mu.Unlock()
	if f.err != nil {
		return imagebuilder.BuildOutput{}, f.err
	}
	return imagebuilder.BuildOutput{Digest: f.digest}, nil
}

func (f *fakeBuilder) calls() []imagebuilder.BuildInput {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]imagebuilder.BuildInput(nil), f.inputs...)
}

// newExportFixture 导出链路夹具：导出三表迁移 + 空间/owner 用户/服务/包 + L1 运行时默认 +
// 注入 fake builder。服务 env 预置敏感/非敏感各一键（preview 掩码用例消费）。
func newExportFixture(t *testing.T) (*Core, *fakeBuilder, *store.Workspace, *store.ServiceEntity, *store.OafPackage, func()) {
	t.Helper()
	core, _, done := newTestCore(t)
	if err := core.DB.AutoMigrate(&store.ExportOrderEntity{}, &store.ExportPackageMarkEntity{},
		&store.WorkspaceRuntimeDefaultEntity{}); err != nil {
		t.Fatal(err)
	}
	owner := &store.User{Username: "export-owner", PasswordHash: "x", Role: "user", Status: "active"}
	if err := core.DB.Create(owner).Error; err != nil {
		t.Fatal(err)
	}
	ws := &store.Workspace{Slug: "ws-export", Name: "导出空间", OwnerUserID: owner.ID, Status: "active"}
	if err := core.DB.Create(ws).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: owner.ID, Role: "owner"}).Error; err != nil {
		t.Fatal(err)
	}
	pkg := uploadTestPkg(t, core, "export")
	svc := &store.ServiceEntity{
		K8sName: "oaf-e2e-export-agent", DisplayName: "导出测试服务", PackageID: pkg.ID,
		Image: "agent-framework:latest", Replicas: 2, Status: store.StatusRunning,
		EnvJSON:       `{"LLM_BASE_URL":"https://src.example.com/v1"}`,
		EnvSecretJSON: `{"LLM_API_KEY":"sk-source"}`,
		WorkspaceID:   ws.ID,
		CreatedBy:     owner.ID,
		ShortName:     "e2e-export-agent",
		AgentCardJSON: "{}",
		SkillsJSON:    "[]",
		Endpoint:      "http://ep",
	}
	if err := core.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	// L1 工作空间运行时默认（真实 PUT 链路落库，键空间/三态语义同被隐式覆盖）
	if _, err := core.UpdateRuntimeDefaults(ws.ID, "ops", map[string]string{
		"CHECKPOINT_JDBC_BASE":        "jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint",
		"CHECKPOINT_USERNAME":         "oaf_writer",
		"CHECKPOINT_PASSWORD":         "prod-pw",
		"AGENT_REDIS_URL":             "redis://redis.prod.svc:6379",
		"LLM_BASE_URL":                "https://llm.prod.example.com/v1",
		"LLM_MODEL_ID":                "glm-4.7",
		"OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel.prod.svc:4318",
		"REGISTRY_ADDRESS":            "172.20.0.1:5001",
		"ROUTING_MODE":                "host",
		"ROUTING_HOST_SUFFIX":         ".prod.example.com",
		"BASE_IMAGE_WHITELIST":        "172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018|Agent Framework 2.1.0",
	}); err != nil {
		t.Fatal(err)
	}
	builder := &fakeBuilder{digest: "sha256:" + strings.Repeat("ab", 32)}
	core.Images = builder
	return core, builder, ws, svc, pkg, done
}

// freezeMark 直接对源包打冻结标记（e2e §8.5 主流程形态）。
func freezeMark(t *testing.T, core *Core, wsID, svcID uint) *ExportMarkView {
	t.Helper()
	mark, err := core.FreezeExportMark(wsID, svcID, "export-owner")
	if err != nil {
		t.Fatalf("freeze mark: %v", err)
	}
	return mark
}

// waitForExportStatus 轮询等待导出单到达目标状态（异步 goroutine 推进）。
func waitForExportStatus(t *testing.T, core *Core, id uint, want string) store.ExportOrderEntity {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		var order store.ExportOrderEntity
		if err := core.DB.First(&order, id).Error; err != nil {
			t.Fatal(err)
		}
		if order.Status == want {
			return order
		}
		if order.Status == store.ExportStatusFailed && want != store.ExportStatusFailed {
			t.Fatalf("order failed while waiting for %q: %s", want, order.Error)
		}
		if time.Now().After(deadline) {
			t.Fatalf("export status not %q in time, got %q (error=%q)", want, order.Status, order.Error)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// TestCreateExportOrderBuildSuccess 创建即 building → 异步构建回填 digest 重渲染 → built；
// 构建输入（目标引用/审计标签）与交付物（values 含 digest、manifest digest 一致）逐项断言。
func TestCreateExportOrderBuildSuccess(t *testing.T) {
	core, builder, ws, svc, pkg, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)

	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker:     mark.Marker,
		Env:        map[string]string{"MCP_E2E_TOKEN": "e2e-token-123"},
		SecretKeys: []string{"MCP_E2E_TOKEN"},
	})
	if err != nil {
		t.Fatalf("create export order: %v", err)
	}
	if order.Status != store.ExportStatusBuilding {
		t.Fatalf("initial status = %q, want building", order.Status)
	}
	if order.OafImageRef == "" || order.BaseImage == "" {
		t.Fatalf("oafImageRef/baseImage must be derived at create: %+v", order)
	}
	built := waitForExportStatus(t, core, order.ID, store.ExportStatusBuilt)
	if built.OafImageDigest != builder.digest {
		t.Fatalf("digest not backfilled: %q", built.OafImageDigest)
	}
	// 目标引用规则：{registry}/oaf-{short}:{marker}（契约 §5.4/§9-D12）
	wantRef := "172.20.0.1:5001/oaf-e2e-export-agent:" + mark.Marker
	if built.OafImageRef != wantRef {
		t.Fatalf("oafImageRef = %q, want %q", built.OafImageRef, wantRef)
	}
	// 构建输入：TargetRef 一致；审计标签齐备（仅标记级不变量——export-id 随单自增，
	// 注入 labels 会使同 marker 重推 digest 变化，契约 §8.7#6/R1 幂等承诺，评审修复）
	calls := builder.calls()
	if len(calls) != 1 {
		t.Fatalf("builder called %d times, want 1", len(calls))
	}
	if calls[0].TargetRef != wantRef {
		t.Fatalf("build TargetRef = %q, want %q", calls[0].TargetRef, wantRef)
	}
	for _, k := range []string{"oaf.platform/slug", "oaf.platform/version", "oaf.platform/checksum"} {
		if calls[0].Labels[k] == "" {
			t.Fatalf("build label %q missing: %v", k, calls[0].Labels)
		}
	}
	if calls[0].Labels["oaf.platform/slug"] != pkg.Slug {
		t.Fatalf("slug label = %q, want %q", calls[0].Labels["oaf.platform/slug"], pkg.Slug)
	}
	if _, ok := calls[0].Labels["oaf.platform/export-id"]; ok {
		t.Fatalf("export-id must not be in image labels (digest stability per marker): %v", calls[0].Labels)
	}
	// values 重渲染含 digest 与 L2 新增敏感键（并集语义，契约 §5.3）
	if !strings.Contains(built.ValuesYAML, "oafDigest: "+builder.digest) {
		t.Fatalf("values missing digest:\n%s", built.ValuesYAML)
	}
	if !strings.Contains(built.ValuesYAML, "MCP_E2E_TOKEN") {
		t.Fatal("L2-added secret key missing from values secretEnv")
	}
	if !strings.Contains(built.ValuesYAML, "e2e-token-123") {
		t.Fatal("secret value must be plaintext in values (决议 ①)")
	}
	// manifest：digest 一致 + 覆盖摘要
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(built.ManifestJSON), &m); err != nil {
		t.Fatalf("manifest json: %v", err)
	}
	bundle := m["bundle"].(map[string]interface{})
	if bundle["oafImageDigest"] != builder.digest || bundle["oafImageRef"] != wantRef {
		t.Fatalf("manifest bundle wrong: %v", bundle)
	}
	if !strings.HasPrefix(m["exportOrder"].(string), "EXP-") {
		t.Fatalf("exportOrder = %v", m["exportOrder"])
	}
	summary := m["overrideSummary"].(map[string]interface{})
	// envKeysAdded = L2 新增 1 键；secretKeys = L1 模板敏感 2 键（CHECKPOINT_PASSWORD/AGENT_REDIS_URL）+ L2 MCP_E2E_TOKEN
	if summary["envKeysAdded"].(float64) != 1 || summary["secretKeys"].(float64) != 3 {
		t.Fatalf("overrideSummary wrong: %v", summary)
	}
}

// TestCreateExportOrderBuildFailure 构建失败 → failed 带原因。
func TestCreateExportOrderBuildFailure(t *testing.T) {
	core, builder, ws, svc, _, done := newExportFixture(t)
	defer done()
	builder.err = errors.New("push denied: 403")
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	got := waitForExportStatus(t, core, order.ID, store.ExportStatusFailed)
	if !strings.Contains(got.Error, "push denied") {
		t.Fatalf("error reason = %q, want push denied", got.Error)
	}
}

// TestCreateExportOrderValidation 创建校验矩阵：Images 未装配 / marker 缺失或未冻结 /
// L2 撞 L1 / L2 撞 L3 派生键 / baseImage 越白名单。
func TestCreateExportOrderValidation(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)

	// Images nil → 400（契约 §3.3：不 panic、不降级假成功）
	saved := core.Images
	core.Images = nil
	_, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err == nil || !strings.Contains(err.Error(), "image builder not configured") {
		t.Fatalf("Images nil: err = %v", err)
	}
	core.Images = saved

	// marker 缺失
	if _, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{}); err == nil {
		t.Fatal("empty marker must 400")
	}
	// marker 未冻结（draft 不可构建，无标记不可构建）
	if _, err := core.SaveExportPackageFile(ws.ID, svc.ID, "export-owner", "mcp-configs/fs/config.yaml", "x: 1"); err != nil {
		t.Fatal(err)
	}
	if _, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: ""}); err == nil {
		t.Fatal("draft-only service without frozen marker must fail")
	}
	// L2 撞 L1 已配置键（锁定语义，契约 §5.1）
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, Env: map[string]string{"LLM_BASE_URL": "https://override.example.com"},
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("L2 overrides L1: err = %v", err)
	}
	// L2 撞 L3 派生键
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, Env: map[string]string{"CHECKPOINT_JDBC_URL": "jdbc:mysql://x/db"},
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("L2 overrides L3: err = %v", err)
	}
	// L2 携带导出参数键（REGISTRY_NAMESPACE 在 L1 未配置，旧实现会作为「新键」放行并
	// 静默改写推送目标命名空间；契约 §5.5 该组键一律取 L1，评审修复：命中 exportMetaKeys 即 400）
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, Env: map[string]string{"REGISTRY_NAMESPACE": "evil-ns"},
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("L2 export meta key: err = %v", err)
	}
	// 同口径：secretKeys 路径同样拦截
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, SecretKeys: []string{"IMAGE_PULL_SECRETS"},
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("L2 export meta key via secretKeys: err = %v", err)
	}
	// baseImage 越白名单
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, BaseImage: "docker.io/evil:1",
	})
	if err == nil || !errors.Is(err, ErrImageNotAllowed) {
		t.Fatalf("baseImage off whitelist: err = %v", err)
	}
	// 无 marker 冻结标记的陌生 marker
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: "1.0.0-e99"})
	if err == nil || !errors.Is(err, ErrMarkNotBuildable) {
		t.Fatalf("unknown marker: err = %v", err)
	}
}

// TestExportOrderStateMachine 状态机：built→approving→approved→deploying→deployed 前向链 +
// 非终态→failed/cancelled + 非法跳跃与终态拒绝；推进记录操作人与 ITSM 单号（manifest itsm）。
func TestExportOrderStateMachine(t *testing.T) {
	core, builder, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, order.ID, store.ExportStatusBuilt)

	// member（非 owner/admin）推进 → 403：临时挂 member 用户与成员关系
	member := &store.User{Username: "export-member", PasswordHash: "x", Role: "user", Status: "active"}
	core.DB.Create(member)
	core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: member.ID, Role: "member"})
	if _, err := core.AdvanceExportOrder(member.ID, order.ID, "export-member",
		&ExportAdvanceInput{Status: store.ExportStatusApproving}); !errors.Is(err, ErrForbidden) {
		t.Fatalf("member advance: err = %v", err)
	}

	// 非法跳跃：built → deploying（跳过审批链）
	if _, err := core.AdvanceExportOrder(1, order.ID, "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusDeploying}); !errors.Is(err, ErrBadState) {
		t.Fatalf("built->deploying: err = %v", err)
	}
	// 前向链逐步推进 + ITSM 单号/操作人落 manifest
	for step, want := range []string{store.ExportStatusApproving, store.ExportStatusApproved,
		store.ExportStatusDeploying, store.ExportStatusDeployed} {
		got, err := core.AdvanceExportOrder(1, order.ID, "export-owner",
			&ExportAdvanceInput{Status: want, ItsmTicketID: "ITSM-007"})
		if err != nil {
			t.Fatalf("advance step %d to %s: %v", step, want, err)
		}
		if got.Status != want {
			t.Fatalf("status = %q, want %q", got.Status, want)
		}
		if got.ItsmTicketID != "ITSM-007" {
			t.Fatalf("itsmTicketId = %q", got.ItsmTicketID)
		}
	}
	// manifest itsm 段记录操作人
	var order2 store.ExportOrderEntity
	core.DB.First(&order2, order.ID)
	var m struct {
		Itsm struct {
			TicketID   string  `json:"ticketId"`
			AdvancedBy string  `json:"advancedBy"`
			AdvancedAt *string `json:"advancedAt"`
		} `json:"itsm"`
	}
	if err := json.Unmarshal([]byte(order2.ManifestJSON), &m); err != nil {
		t.Fatal(err)
	}
	if m.Itsm.TicketID != "ITSM-007" || m.Itsm.AdvancedBy != "export-owner" || m.Itsm.AdvancedAt == nil {
		t.Fatalf("manifest itsm wrong: %+v", m.Itsm)
	}
	// 终态不可再迁移
	if _, err := core.AdvanceExportOrder(1, order.ID, "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusFailed}); !errors.Is(err, ErrBadState) {
		t.Fatalf("terminal advance: err = %v", err)
	}

	// built → cancelled（任意非终态可入 cancelled）
	builder.digest = "sha256:" + strings.Repeat("cd", 32)
	orderB, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, orderB.ID, store.ExportStatusBuilt)
	if _, err := core.AdvanceExportOrder(1, orderB.ID, "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusCancelled}); err != nil {
		t.Fatalf("built->cancelled: %v", err)
	}
}

// TestExportOrderBuildRaceWithCancel 构建期间人工取消（building→cancelled）：构建完成后
// 不得把终态拉回 built。goroutine 两条路径均合法——起点看到 cancelled 提前退出，或构建
// 跑完后被条件更新守卫（仅 building→built 落盘）丢弃结果；不变量：终态保持 cancelled
// 且 digest 永不回填。
func TestExportOrderBuildRaceWithCancel(t *testing.T) {
	core, builder, ws, svc, _, done := newExportFixture(t)
	defer done()
	builder.digest = "sha256:" + strings.Repeat("dd", 32)
	builder.delay = 150 * time.Millisecond // 拉长构建窗口，给取消留出时间
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := core.AdvanceExportOrder(1, order.ID, "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusCancelled}); err != nil {
		t.Fatalf("cancel during build: %v", err)
	}
	// 给 goroutine 走完任一路径的机会（build 已跑 / 起点退出）；不强制要求 Build 发生——
	// 起点提前退出是守卫的合法行为
	deadline := time.Now().Add(2 * time.Second)
	for len(builder.calls()) == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond) // 若 Build 已跑，留出守卫落盘时间
	var final store.ExportOrderEntity
	core.DB.First(&final, order.ID)
	if final.Status != store.ExportStatusCancelled {
		t.Fatalf("status = %q, want cancelled (build result must be discarded)", final.Status)
	}
	if final.OafImageDigest != "" {
		t.Fatalf("digest must not be backfilled after cancel: %q", final.OafImageDigest)
	}
}

// TestExportOrderAccessAndDownload 归属裁决（非成员 404）与下载审计（downloadedBy 追加 +
// 结构化日志路径不 panic）；values/manifest 之外的工件名 400。
func TestExportOrderAccessAndDownload(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, order.ID, store.ExportStatusBuilt)

	// 非成员用户（未加入空间、非 admin）→ 404
	stranger := &store.User{Username: "stranger", PasswordHash: "x", Role: "user", Status: "active"}
	core.DB.Create(stranger)
	if _, err := core.GetExportOrder(stranger.ID, order.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stranger get: err = %v", err)
	}
	if _, err := core.ExportOrderDownload(stranger.ID, order.ID, "stranger", "values.yaml"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stranger download: err = %v", err)
	}
	// 平台 admin（非成员）→ 隐式 admin 可见
	admin := &store.User{Username: "platform-admin", PasswordHash: "x", Role: "admin", Status: "active"}
	core.DB.Create(admin)
	if _, err := core.GetExportOrder(admin.ID, order.ID); err != nil {
		t.Fatalf("admin get: %v", err)
	}
	// owner 下载 values → downloadedBy 追加；未知工件 → 400
	if _, err := core.ExportOrderDownload(1, order.ID, "export-owner", "values.yaml"); err != nil {
		t.Fatalf("values download: %v", err)
	}
	if _, err := core.ExportOrderDownload(1, order.ID, "export-owner", "secret.bin"); err == nil {
		t.Fatal("unknown artifact must 400")
	}
	var fresh store.ExportOrderEntity
	core.DB.First(&fresh, order.ID)
	if !strings.Contains(fresh.ManifestJSON, `"user": "export-owner"`) {
		t.Fatalf("downloadedBy not appended: %s", fresh.ManifestJSON)
	}
}

// TestExportOrderSameMarkerStableBuildInputs 同一 frozen marker 重复创建导出单（§9-R1
// 重建解救路径）：两次构建输入的 Labels/TargetRef 必须逐字段一致——digest 由 config+tar
// 内容寻址，输入一致即同 tag 幂等重推 digest 不变（契约 §8.7#6；export-id 已移出 labels，
// 评审修复）。values/manifest 的 per-order 字段（export-id/digest）不在镜像内。
func TestExportOrderSameMarkerStableBuildInputs(t *testing.T) {
	core, builder, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	first, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, first.ID, store.ExportStatusBuilt)
	second, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatalf("re-create with same marker: %v", err)
	}
	waitForExportStatus(t, core, second.ID, store.ExportStatusBuilt)
	calls := builder.calls()
	if len(calls) != 2 {
		t.Fatalf("builder called %d times, want 2", len(calls))
	}
	if calls[0].TargetRef != calls[1].TargetRef {
		t.Fatalf("target ref drift: %q vs %q", calls[0].TargetRef, calls[1].TargetRef)
	}
	for k, v := range calls[0].Labels {
		if calls[1].Labels[k] != v {
			t.Fatalf("label %q drift across same-marker exports: %q vs %q", k, v, calls[1].Labels[k])
		}
	}
	if len(calls[1].Labels) != len(calls[0].Labels) {
		t.Fatalf("label set size drift: %d vs %d", len(calls[0].Labels), len(calls[1].Labels))
	}
}

// TestExportOrderFailAfterCancel 构建期间取消后构建失败：failExportOrder 的条件更新
// （仅 building→failed，评审修复）不得把已 cancelled 终态改写为 failed，error 也不得落。
func TestExportOrderFailAfterCancel(t *testing.T) {
	core, builder, ws, svc, _, done := newExportFixture(t)
	defer done()
	builder.digest = "sha256:" + strings.Repeat("ee", 32)
	builder.err = errors.New("push: unauthorized")
	builder.delay = 150 * time.Millisecond // 拉长构建窗口，保证取消先落库
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := core.AdvanceExportOrder(1, order.ID, "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusCancelled}); err != nil {
		t.Fatalf("cancel during build: %v", err)
	}
	// 等 goroutine 走完构建失败路径（failExportOrder 在守卫下应为 no-op）
	deadline := time.Now().Add(2 * time.Second)
	for len(builder.calls()) == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond)
	var final store.ExportOrderEntity
	core.DB.First(&final, order.ID)
	if final.Status != store.ExportStatusCancelled {
		t.Fatalf("status = %q, want cancelled (failure must not override terminal state)", final.Status)
	}
	if final.Error != "" {
		t.Fatalf("error must stay empty on cancelled order: %q", final.Error)
	}
}
