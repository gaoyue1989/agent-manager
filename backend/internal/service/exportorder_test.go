// 导出单链路测试（契约 §2.2/§3.3/§4.2）：夹具（sqlite + fake builder）+ 状态机 +
// 异步构建成功/失败 + 校验矩阵 + 归属/下载审计。
package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"agent-manager/backend/internal/imagebuilder"
	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/store"
	"gorm.io/gorm"
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
	// manifest audit：createdAt 非零且与导出单 createdAt 同源（issue #214——构建完成重渲染
	// 取 DB 行 CreatedAt，审计链时间锚点不得为零值 0001-01-01，秒级精度比对容忍时钟截断）
	audit := m["audit"].(map[string]interface{})
	if audit["createdBy"] != "export-owner" {
		t.Fatalf("audit.createdBy = %v", audit["createdBy"])
	}
	manifestCreatedAt, err := time.Parse(time.RFC3339, audit["createdAt"].(string))
	if err != nil || manifestCreatedAt.IsZero() {
		t.Fatalf("audit.createdAt zero/unparseable: %v", audit["createdAt"])
	}
	if diff := manifestCreatedAt.Sub(built.CreatedAt); diff > time.Second || diff < -time.Second {
		t.Fatalf("audit.createdAt = %v drifts from order createdAt %v", manifestCreatedAt, built.CreatedAt)
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
	// secretKeys 65 键 → ErrInvalidForm（issue #234 数量上限对齐 MaxEnvKeys；此前仅 env
	// 有限量，secretKeys 超量可经创建导出单撑大 request_json 快照）
	many := make([]string, 0, 65)
	for i := 0; i < 65; i++ {
		many = append(many, fmt.Sprintf("EXPORT_KEY_%d", i))
	}
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, SecretKeys: many,
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("65 secret keys: err = %v", err)
	}
	// 重复 secretKeys → ErrInvalidForm（issue #234 显式拒绝，不静默去重）
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, SecretKeys: []string{"MCP_FLIGHT_TOKEN", "MCP_FLIGHT_TOKEN"},
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("duplicate secret keys: err = %v", err)
	}
	// baseImage 越白名单
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, BaseImage: "docker.io/evil:1",
	})
	if err == nil || !errors.Is(err, ErrImageNotAllowed) {
		t.Fatalf("baseImage off whitelist: err = %v", err)
	}
	// chartVersion 注入（issue #226）：换行试图顶出顶层 YAML 键 resources，len=32 恰好卡
	// ChartVersion 列宽（model.go:362 size:32，无校验时撞 MySQL 1406 → 500 或注入生效），
	// 须 ErrInvalidForm 400
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, ChartVersion: "\nresources: {limits: {cpu: 9}} #",
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("chartVersion injection: err = %v", err)
	}
	// 超长 chartVersion（>32 撞列宽 → 1406/500）同样 400 拦截
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, ChartVersion: "0.1.0" + strings.Repeat(".0", 15),
	})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("chartVersion overlong: err = %v", err)
	}
	// 无 marker 冻结标记的陌生 marker
	_, err = core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: "1.0.0-e99"})
	if err == nil || !errors.Is(err, ErrMarkNotBuildable) {
		t.Fatalf("unknown marker: err = %v", err)
	}
	// chartVersion 正例不回归：显式合法 semver 与缺省（默认 0.1.0）均可创建并走完构建
	good, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{
		Marker: mark.Marker, ChartVersion: "1.2.3",
	})
	if err != nil {
		t.Fatalf("legal chartVersion: %v", err)
	}
	if good.ChartVersion != "1.2.3" {
		t.Fatalf("chartVersion = %q, want 1.2.3", good.ChartVersion)
	}
	def, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatalf("default chartVersion: %v", err)
	}
	if def.ChartVersion != defaultExportChartVersion {
		t.Fatalf("default chartVersion = %q, want %q", def.ChartVersion, defaultExportChartVersion)
	}
	waitForExportStatus(t, core, good.ID, store.ExportStatusBuilt)
	waitForExportStatus(t, core, def.ID, store.ExportStatusBuilt)
}

// TestListServiceExportOrders 服务导出单列表（issue #216 导出记录区）：id 倒序、marker 从
// 请求快照解析、EXP 单号生成、创建人透出；他空间/不存在服务统一 404 语义。
func TestListServiceExportOrders(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	first, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID,
		&ExportOrderRequest{Marker: mark.Marker, Env: map[string]string{"MCP_E2E_TOKEN": "tok"}})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, first.ID, store.ExportStatusBuilt)
	second, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	// 构建是异步 goroutine（CreateExportOrder 落库即 building），列表断言前需等两单都到 built
	waitForExportStatus(t, core, second.ID, store.ExportStatusBuilt)
	list, err := core.ListServiceExportOrders(ws.ID, svc.ID)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("list len = %d, want 2", len(list))
	}
	if list[0].ID != second.ID || list[1].ID != first.ID {
		t.Fatalf("list not id-desc: %d, %d", list[0].ID, list[1].ID)
	}
	for _, item := range list {
		if item.Marker != mark.Marker {
			t.Fatalf("marker not from dedicated column: %+v", item)
		}
		if !strings.HasPrefix(item.OrderNo, "EXP-") {
			t.Fatalf("orderNo = %q", item.OrderNo)
		}
		if item.Status != store.ExportStatusBuilt || item.CreatedBy != "export-owner" {
			t.Fatalf("status/createdBy wrong: %+v", item)
		}
		if item.OafImageDigest == "" {
			t.Fatalf("digest not surfaced: %+v", item)
		}
	}
	// issue #234：列表 Select 不再拖 request_json 大列——经 GORM 回调捕获本次列表查询
	// 实际执行的 SQL 断言（前提：行内快照确实存在，仅靠列表语句不带该列）
	var captured []string
	core.DB.Callback().Query().After("gorm:query").Register("test_capture_export_list_sql",
		func(tx *gorm.DB) {
			if s := tx.Statement.SQL.String(); s != "" {
				captured = append(captured, s)
			}
		})
	if _, err := core.ListServiceExportOrders(ws.ID, svc.ID); err != nil {
		t.Fatalf("list for sql capture: %v", err)
	}
	joined := strings.Join(captured, "\n")
	var snapProbe store.ExportOrderEntity
	if err := core.DB.Select("request_json").First(&snapProbe, first.ID).Error; err != nil {
		t.Fatal(err)
	}
	if snapProbe.RequestJSON == "" {
		t.Fatal("precondition: request snapshot must exist on the row")
	}
	if strings.Contains(joined, "request_json") {
		t.Fatalf("list query must not drag request_json big column: %s", joined)
	}
	if !strings.Contains(joined, "marker") {
		t.Fatalf("list query should read the marker column: %s", joined)
	}
	// 容忍语义：存量坏快照行（marker 列为空）列表照常返回、该行 marker 留空不报错
	if err := core.DB.Model(&store.ExportOrderEntity{}).Where("id = ?", first.ID).
		Update("marker", "").Error; err != nil {
		t.Fatal(err)
	}
	tolerant, err := core.ListServiceExportOrders(ws.ID, svc.ID)
	if err != nil {
		t.Fatalf("list with empty-marker row: %v", err)
	}
	for _, item := range tolerant {
		if item.ID == first.ID && item.Marker != "" {
			t.Fatalf("empty-marker row must surface empty marker, got %q", item.Marker)
		}
	}
	// 服务不存在 / 不属于本空间 → 404（loadServiceForExport 同口径）
	if _, err := core.ListServiceExportOrders(ws.ID, svc.ID+999); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown service: err = %v", err)
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

// TestExportPreviewMaskAndDerived 导出页预填视图（契约 §4.1）：敏感值绝不回显
// （服务 secret env / L1 敏感键 / 源服务 env 明文三路都不进 JSON）、敏感键条目仅
// hasValue/sensitive 两键、派生键预览三字段形状、lockedKeys 升序且剔除空值键。
func TestExportPreviewMaskAndDerived(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	// 空值键行（模拟历史残留）：lockedKeys 过滤器必须剔除（exportorder.go :587-593 只收非空值）
	if err := core.DB.Create(&store.WorkspaceRuntimeDefaultEntity{
		WorkspaceID: ws.ID, EnvKey: "REGISTRY_NAMESPACE", Value: "", Category: "registry",
	}).Error; err != nil {
		t.Fatal(err)
	}
	preview, err := core.ExportPreview(ws.ID, svc.ID)
	if err != nil {
		t.Fatalf("ExportPreview: %v", err)
	}
	// ① 整体 JSON 泄漏扫描：L1 敏感值 / 源服务 secret env 值 / 源服务 plain env 值均不出现
	raw, err := json.Marshal(preview)
	if err != nil {
		t.Fatal(err)
	}
	body := string(raw)
	for _, leak := range []string{"prod-pw", "sk-source", "https://src.example.com/v1"} {
		if strings.Contains(body, leak) {
			t.Fatalf("preview JSON leaks %q: %s", leak, body)
		}
	}
	// ② 敏感键条目仅 hasValue/sensitive 两键（ExportPreviewEnvEntry 无 value 字段，
	// 结构性掩码——该断言钉住红线，防止后续加便捷字段无感穿透）
	for key, wantSensitive := range map[string]bool{"LLM_API_KEY": true, "LLM_BASE_URL": false} {
		entry, ok := preview.ServiceEnv[key]
		if !ok {
			t.Fatalf("ServiceEnv missing %q: %v", key, preview.ServiceEnv)
		}
		eraw, err := json.Marshal(entry)
		if err != nil {
			t.Fatal(err)
		}
		var emap map[string]json.RawMessage
		if err := json.Unmarshal(eraw, &emap); err != nil {
			t.Fatal(err)
		}
		if len(emap) != 2 {
			t.Fatalf("env entry %q must carry exactly hasValue/sensitive, got %s", key, eraw)
		}
		if entry.HasValue != true || entry.Sensitive != wantSensitive {
			t.Fatalf("env entry %q = %+v, want hasValue=true sensitive=%v", key, entry, wantSensitive)
		}
	}
	// L1 敏感键掩码：HasValue 不回填 Value（workspaceruntime.go GetRuntimeDefaults）
	if pw, ok := preview.RuntimeDefaults["CHECKPOINT_PASSWORD"]; !ok || !pw.HasValue || pw.Value != "" || !pw.Sensitive {
		t.Fatalf("L1 sensitive entry wrong: %+v", pw)
	}
	// ③ 派生键预览三字段：{基库}_{short}（- 转 _）/ {short}: / {registry}/oaf-{short}:{marker 占位}
	short := preview.Service.ShortName
	if short != "e2e-export-agent" {
		t.Fatalf("shortName = %q", short)
	}
	dp := preview.DerivedPreview
	if dp.CheckpointDB != "oaf_checkpoint_e2e_export_agent" {
		t.Fatalf("derivedPreview.checkpointDB = %q", dp.CheckpointDB)
	}
	if dp.RedisPrefix != "e2e-export-agent:" {
		t.Fatalf("derivedPreview.redisPrefix = %q", dp.RedisPrefix)
	}
	wantRef := "172.20.0.1:5001/oaf-e2e-export-agent:{marker}"
	if dp.OafImageRef != wantRef {
		t.Fatalf("derivedPreview.oafImageRef = %q, want %q", dp.OafImageRef, wantRef)
	}
	// ④ lockedKeys：升序、剔除空值键、与非空 L1 事实源一致
	l1, err := core.loadRuntimeDefaultValues(ws.ID)
	if err != nil {
		t.Fatal(err)
	}
	want := make([]string, 0, len(l1))
	for k, v := range l1 {
		if v != "" {
			want = append(want, k)
		}
	}
	sort.Strings(want)
	got := preview.LockedKeys
	if !sort.StringsAreSorted(got) {
		t.Fatalf("lockedKeys not ascending: %v", got)
	}
	if strings.Contains(strings.Join(got, ","), "REGISTRY_NAMESPACE") {
		t.Fatalf("lockedKeys must exclude empty-valued key: %v", got)
	}
	if len(got) != len(want) {
		t.Fatalf("lockedKeys = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("lockedKeys[%d] = %q, want %q", i, got[i], want[i])
		}
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

// exportManifestHeads 断言用：manifest itsm 段 + audit.downloadedBy 条目数。
type exportManifestHeads struct {
	Itsm struct {
		TicketID string `json:"ticketId"`
	} `json:"itsm"`
	Audit struct {
		DownloadedBy []struct {
			User string `json:"user"`
		} `json:"downloadedBy"`
	} `json:"audit"`
}

// TestAdvanceExportOrderVsDownloadRace issue #233 核心竞态回归：人工推进持 stale 快照
// CAS（下载审计已先落库并 bump manifest_version）→ CAS 不命中重读重试，最终 manifest
// 同时保留 downloadedBy 条目与 itsm 段，互不覆盖（修复前 advance 以 (id,status) 条件
// 整段覆盖 manifest，静默丢下载审计条目）。
func TestAdvanceExportOrderVsDownloadRace(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, order.ID, store.ExportStatusBuilt)
	// stale 快照（此刻与库内一致）
	var snap store.ExportOrderEntity
	if err := core.DB.First(&snap, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	// 并发写者先落：下载审计追加（manifest_version bump）→ stale 快照 CAS 必不命中。
	// 基线先取值：CAS 函数重读时会刷新传入快照（含 version），断言不能事后引用
	wantVersion := snap.ManifestVersion + 2
	if _, err := core.ExportOrderDownload(1, order.ID, "export-owner", "values.yaml"); err != nil {
		t.Fatalf("download: %v", err)
	}
	got, err := core.advanceExportOrderCAS(&snap, "owner", "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusApproving, ItsmTicketID: "ITSM-233"})
	if err != nil {
		t.Fatalf("advance with stale snapshot: %v", err)
	}
	if got.Status != store.ExportStatusApproving {
		t.Fatalf("status = %q, want approving", got.Status)
	}
	var fresh store.ExportOrderEntity
	if err := core.DB.First(&fresh, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	if fresh.ManifestVersion != wantVersion {
		t.Fatalf("manifest_version = %d, want %d (download + advance each bump once)",
			fresh.ManifestVersion, wantVersion)
	}
	var heads exportManifestHeads
	if err := json.Unmarshal([]byte(fresh.ManifestJSON), &heads); err != nil {
		t.Fatal(err)
	}
	if heads.Itsm.TicketID != "ITSM-233" {
		t.Fatalf("itsm segment lost: %+v", heads.Itsm)
	}
	if len(heads.Audit.DownloadedBy) != 1 || heads.Audit.DownloadedBy[0].User != "export-owner" {
		t.Fatalf("downloadedBy lost/incorrect: %+v", heads.Audit.DownloadedBy)
	}
}

// TestAdvanceExportOrderConcurrentStatusChange 并发推进语义两分支（issue #233）：
// ① stale built + 库内已被推进至 approving + 目标 approving → 非法迁移 ErrBadState，
// DB 状态不被改写（现语义保持）；② stale built + 目标 failed + 库内已 approving →
// approving→failed 属 exportAdvanceAllow 合法迁移，重读后继续落库成功（效果等价客户端
// 刷新重试），itsm 单号随目标落库。
func TestAdvanceExportOrderConcurrentStatusChange(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, order.ID, store.ExportStatusBuilt)
	// stale 快照（built）；随后库内被并发推进 built→approving（manifest_version 随之 bump）
	var snap store.ExportOrderEntity
	if err := core.DB.First(&snap, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := core.AdvanceExportOrder(1, order.ID, "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusApproving, ItsmTicketID: "ITSM-FIRST"}); err != nil {
		t.Fatalf("concurrent advance: %v", err)
	}

	// ① 目标 approving：重读后发现库内已 approving（非合法迁移起点）→ ErrBadState，不改写
	if _, err := core.advanceExportOrderCAS(&snap, "owner", "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusApproving}); !errors.Is(err, ErrBadState) {
		t.Fatalf("stale advance to approving: err = %v, want ErrBadState", err)
	}
	var mid store.ExportOrderEntity
	if err := core.DB.First(&mid, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	if mid.Status != store.ExportStatusApproving || mid.ItsmTicketID != "ITSM-FIRST" {
		t.Fatalf("DB must keep concurrent writer's state: status=%q ticket=%q", mid.Status, mid.ItsmTicketID)
	}

	// ② 目标 failed：approving→failed 合法迁移，重读后继续落库成功
	got, err := core.advanceExportOrderCAS(&snap, "owner", "export-owner",
		&ExportAdvanceInput{Status: store.ExportStatusFailed, ItsmTicketID: "ITSM-SECOND"})
	if err != nil {
		t.Fatalf("stale advance to failed: %v", err)
	}
	if got.Status != store.ExportStatusFailed || got.ItsmTicketID != "ITSM-SECOND" {
		t.Fatalf("got status=%q ticket=%q, want failed/ITSM-SECOND", got.Status, got.ItsmTicketID)
	}
	var fin store.ExportOrderEntity
	if err := core.DB.First(&fin, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	if fin.Status != store.ExportStatusFailed || fin.ItsmTicketID != "ITSM-SECOND" {
		t.Fatalf("final DB status=%q ticket=%q, want failed/ITSM-SECOND", fin.Status, fin.ItsmTicketID)
	}
	var heads exportManifestHeads
	if err := json.Unmarshal([]byte(fin.ManifestJSON), &heads); err != nil {
		t.Fatal(err)
	}
	if heads.Itsm.TicketID != "ITSM-SECOND" {
		t.Fatalf("manifest itsm = %+v, want ITSM-SECOND (重渲基于重读后的最新 manifest)", heads.Itsm)
	}
}

// TestDownloadAuditConcurrentAppend 下载×下载并发闭合（issue #233）：两个同基线独立
// 快照各自下载（等价并发请求各自 resolveExportOrderAccess）——第二次 CAS 不命中（首次
// 已 bump manifest_version）触发重读重试，manifest.downloadedBy 两条条目共存不丢
// （修复前第二次按 (id,status) 整段覆盖丢首条）。
func TestDownloadAuditConcurrentAppend(t *testing.T) {
	core, _, ws, svc, _, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, err := core.CreateExportOrder("export-owner", ws.ID, svc.ID, &ExportOrderRequest{Marker: mark.Marker})
	if err != nil {
		t.Fatal(err)
	}
	waitForExportStatus(t, core, order.ID, store.ExportStatusBuilt)
	// 两个独立快照必须在下载 #1 落库前各自读出：快照 #2 若在 #1 之后才读，version 已
	// bump、第二次 CAS 首次尝试即命中、重试分支不执行（回归无效——评审 g2 实证）。
	// downloadExportArtifactCAS 成功会回写传入快照的 ManifestJSON/ManifestVersion，
	// 共用同一快照同样会使第二次首试即命中
	var snap1, snap2 store.ExportOrderEntity
	if err := core.DB.First(&snap1, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.First(&snap2, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	wantVersion := snap1.ManifestVersion + 2
	if _, err := core.downloadExportArtifactCAS(&snap1, "dl-one", "values.yaml"); err != nil {
		t.Fatalf("download #1: %v", err)
	}
	// 下载 #2 持同基线 stale 快照进入 CAS：首次尝试必 MISS（version 已被 #1 bump）→
	// 重读最新行再追加 → HIT。err==nil 且两条条目共存，只有经重试分支才可达
	// （重试分支损坏时 #2 三次 MISS 耗尽返 ErrBadState，本用例即失败）
	got, err := core.downloadExportArtifactCAS(&snap2, "dl-two", "manifest.json")
	if err != nil {
		t.Fatalf("download #2 with stale snapshot: %v", err)
	}
	if got.ManifestVersion != wantVersion {
		t.Fatalf("manifest_version = %d, want %d (second download retried once)",
			got.ManifestVersion, wantVersion)
	}
	var fresh store.ExportOrderEntity
	if err := core.DB.First(&fresh, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	var heads exportManifestHeads
	if err := json.Unmarshal([]byte(fresh.ManifestJSON), &heads); err != nil {
		t.Fatal(err)
	}
	if len(heads.Audit.DownloadedBy) != 2 ||
		heads.Audit.DownloadedBy[0].User != "dl-one" || heads.Audit.DownloadedBy[1].User != "dl-two" {
		t.Fatalf("downloadedBy = %+v, want [dl-one dl-two] both retained", heads.Audit.DownloadedBy)
	}
}

// TestRebuildPreservesDownloadAudit building 期下载被构建回写覆盖的残余通道闭合（issue #233）：
// 手工造 building 行（不启异步 goroutine）→ building 期下载追加审计 → 直调包内私有
// rebuildExportArtifacts（以构建开始前的 stale 快照，manifest_version 必已漂移）→
// seedBuildingOrder 手工造 building 行（不启异步 goroutine，时序确定性）：同
// CreateExportOrder 形态——占位 manifest 落库 → 首渲产物返回（不落库，由用例决定
// 经 saveFirstRenderArtifacts 或并发交错写入），version=0。返回行、请求快照（rebuild
// 重渲复用）与首渲 values/manifest。
func seedBuildingOrder(t *testing.T, core *Core, ws *store.Workspace, svc *store.ServiceEntity,
	pkg *store.OafPackage, mark *ExportMarkView) (*store.ExportOrderEntity, *ExportOrderRequest, string, string) {
	t.Helper()
	req := &ExportOrderRequest{Marker: mark.Marker}
	l1, merged, _, err := core.resolveExportLayers(ws.ID, req)
	if err != nil {
		t.Fatal(err)
	}
	markEntity, err := core.loadFrozenMark(svc.ID, mark.Marker)
	if err != nil {
		t.Fatal(err)
	}
	order := &store.ExportOrderEntity{
		ServiceID:       svc.ID,
		WorkspaceID:     ws.ID,
		SourcePackageID: pkg.ID,
		ChartVersion:    defaultExportChartVersion,
		BaseImage:       req.BaseImage,
		OafImageRef:     buildOafImageRef(merged["REGISTRY_ADDRESS"], merged["REGISTRY_NAMESPACE"], k8s.ShortName(svc.K8sName), mark.Marker),
		Status:          store.ExportStatusBuilding,
		RequestJSON:     mustJSON(req),
		ManifestJSON:    "{}",
		CreatedBy:       "export-owner",
	}
	if err := core.DB.Create(order).Error; err != nil {
		t.Fatal(err)
	}
	var wsr store.Workspace
	if err := core.DB.First(&wsr, ws.ID).Error; err != nil {
		t.Fatal(err)
	}
	in := &exportRenderInput{
		OrderNo:      exportOrderNo(order),
		Svc:          svc,
		Workspace:    &wsr,
		Pkg:          pkg,
		Mark:         markEntity,
		L1:           l1,
		Merged:       merged,
		Req:          req,
		CreatedBy:    order.CreatedBy,
		CreatedAt:    order.CreatedAt,
		OafImageRef:  order.OafImageRef,
		ChartVersion: order.ChartVersion,
	}
	values, manifest, err := renderExportArtifacts(in)
	if err != nil {
		t.Fatal(err)
	}
	return order, req, values, manifest
}

// TestRebuildPreservesDownloadAudit building 期下载被构建回写覆盖的残余通道闭合（issue #233）：
// 手工造 building 行（不启异步 goroutine）→ building 期下载追加审计 → 直调包内私有
// rebuildExportArtifacts（以构建开始前的 stale 快照，manifest_version 必已漂移）→
// downloadedBy 条目保留 + digest 回填 + status=built（修复前整段覆盖重渲产物丢条目）。
func TestRebuildPreservesDownloadAudit(t *testing.T) {
	core, builder, ws, svc, pkg, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	order, req, values, manifest := seedBuildingOrder(t, core, ws, svc, pkg, mark)
	// 首渲落库（无并发写者，CAS 命中：version 0→1）
	if err := core.saveFirstRenderArtifacts(order, values, manifest); err != nil {
		t.Fatal(err)
	}
	// 构建开始前快照（version=1）
	stale := *order
	// building 期下载：manifest_version 1→2，manifest 增 downloadedBy 条目——快照就此
	// 漂移（正是被修复的「构建窗口内 version 必已漂移」场景；compare 基准为每轮现行值）
	if _, err := core.downloadExportArtifactCAS(order, "dl-during-build", "values.yaml"); err != nil {
		t.Fatalf("download during building: %v", err)
	}
	if err := core.rebuildExportArtifacts(&stale, req, builder.digest); err != nil {
		t.Fatalf("rebuild: %v", err)
	}
	var fresh store.ExportOrderEntity
	if err := core.DB.First(&fresh, order.ID).Error; err != nil {
		t.Fatal(err)
	}
	if fresh.Status != store.ExportStatusBuilt {
		t.Fatalf("status = %q, want built", fresh.Status)
	}
	if fresh.OafImageDigest != builder.digest {
		t.Fatalf("digest = %q, want %q", fresh.OafImageDigest, builder.digest)
	}
	var heads exportManifestHeads
	if err := json.Unmarshal([]byte(fresh.ManifestJSON), &heads); err != nil {
		t.Fatal(err)
	}
	if len(heads.Audit.DownloadedBy) != 1 || heads.Audit.DownloadedBy[0].User != "dl-during-build" {
		t.Fatalf("downloadedBy lost during rebuild: %+v", heads.Audit.DownloadedBy)
	}
	// 重渲产物 digest 同步进 manifest bundle 段（下载条目并入不破坏构建回写内容）
	if !strings.Contains(fresh.ManifestJSON, builder.digest) {
		t.Fatalf("digest missing in rebuilt manifest: %s", fresh.ManifestJSON)
	}
}

// TestCreateFirstRenderVsDownloadRace 创建窗口首渲 vs 下载竞态（issue #233 评审 g1）：
// DB.Create（version=0）与首渲落库之间的窗口是 manifest 第四写者——并发下载可 CAS 0→1
// 追加审计条目，原全行 Save 会整段覆盖并把 manifest_version 复位 0。修复后首渲同为
// 乐观锁 CAS：① 并发下载先行 → 首渲 CAS 不命中放弃，审计条目保留、version 不复位、
// values 不落（留痕，构建回写仍会补全产物）；② 无并发写者 → 首渲正常落库 version 0→1
// （building 期 values.yaml 下载依赖首渲产物）。
func TestCreateFirstRenderVsDownloadRace(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)

	// ② 命中分支：无并发写者 → 首渲正常落库
	orderHit, _, values, manifest := seedBuildingOrder(t, core, ws, svc, pkg, mark)
	if err := core.saveFirstRenderArtifacts(orderHit, values, manifest); err != nil {
		t.Fatalf("first render (no race): %v", err)
	}
	var hit store.ExportOrderEntity
	if err := core.DB.First(&hit, orderHit.ID).Error; err != nil {
		t.Fatal(err)
	}
	if hit.ManifestVersion != 1 {
		t.Fatalf("version = %d, want 1 (first render bumps once)", hit.ManifestVersion)
	}
	if hit.ValuesYAML != values || !strings.Contains(hit.ManifestJSON, "EXP-") {
		t.Fatalf("first render must land values/manifest: values empty=%v manifest prefix ok=%v",
			hit.ValuesYAML == "", strings.Contains(hit.ManifestJSON, "EXP-"))
	}

	// ① 竞态分支：并发下载走独立实体（等价并发请求各自 resolveExportOrderAccess 的独立
	// 读；downloadExportArtifactCAS 会回写传入实体，复用创建方实体会使首渲从已 bump 的
	// version 合法命中、反而覆盖条目——非真实交错形态）→ 首渲 CAS（version=0）不命中
	orderMiss, _, missValues, missManifest := seedBuildingOrder(t, core, ws, svc, pkg, mark)
	var concurrent store.ExportOrderEntity
	if err := core.DB.First(&concurrent, orderMiss.ID).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := core.downloadExportArtifactCAS(&concurrent, "dl-in-create-window", "values.yaml"); err != nil {
		t.Fatalf("download in create window: %v", err)
	}
	if err := core.saveFirstRenderArtifacts(orderMiss, missValues, missManifest); err != nil {
		t.Fatalf("first render after concurrent download: %v", err)
	}
	var missed store.ExportOrderEntity
	if err := core.DB.First(&missed, orderMiss.ID).Error; err != nil {
		t.Fatal(err)
	}
	if missed.ManifestVersion != 1 {
		t.Fatalf("version = %d, want 1 (download's bump must survive create-window race)", missed.ManifestVersion)
	}
	if !strings.Contains(missed.ManifestJSON, `"user": "dl-in-create-window"`) {
		t.Fatalf("downloadedBy lost to first-render overwrite: %s", missed.ManifestJSON)
	}
	if missed.ValuesYAML != "" {
		t.Fatal("first render must be abandoned on CAS miss (values_yaml must stay empty)")
	}
}
