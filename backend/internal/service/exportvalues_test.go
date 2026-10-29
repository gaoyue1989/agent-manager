// 导出 values/manifest 渲染测试（契约 §5）：敏感路由（platformconfig 同源 + secretKeys）、
// L3 派生键、四层合并裁决（L2 仅新增）、渲染形状（values/manifest 字段）。
package service

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"gopkg.in/yaml.v3"

	"agent-manager/backend/internal/store"
)

// renderFixtureInput 以夹具空间为底渲染输入（可覆盖 L2 请求）。
func renderFixtureInput(t *testing.T, core *Core, ws *store.Workspace, svc *store.ServiceEntity,
	pkg *store.OafPackage, mark *ExportMarkView, req *ExportOrderRequest) *exportRenderInput {
	t.Helper()
	l1, err := core.loadRuntimeDefaultValues(ws.ID)
	if err != nil {
		t.Fatal(err)
	}
	if req == nil {
		req = &ExportOrderRequest{Marker: mark.Marker}
	}
	// baseImage 缺省取白名单首项（同 resolveExportLayers 语义，渲染夹具不重复裁决）
	if req.BaseImage == "" {
		if wl, werr := parseBaseImageWhitelist(l1["BASE_IMAGE_WHITELIST"]); werr == nil && len(wl) > 0 {
			req.BaseImage = wl[0].Image
		}
	}
	merged := map[string]string{}
	for k, v := range l1 {
		if v != "" {
			merged[k] = v
		}
	}
	for k, v := range req.Env {
		if v != "" {
			merged[k] = v
		}
	}
	return &exportRenderInput{
		OrderNo:      "EXP-20261030-0001",
		Svc:          svc,
		Workspace:    ws,
		Pkg:          pkg,
		Mark:         &store.ExportPackageMarkEntity{Marker: mark.Marker, Checksum: mark.Checksum, PackageVersion: mark.PackageVersion},
		L1:           l1,
		Merged:       merged,
		Req:          req,
		CreatedBy:    "export-owner",
		OafImageRef:  buildOafImageRef(merged["REGISTRY_ADDRESS"], merged["REGISTRY_NAMESPACE"], "e2e-export-agent", mark.Marker),
		ChartVersion: defaultExportChartVersion,
	}
}

// TestSplitExportEnvSensitiveRouting 敏感路由（契约 §5.2/§5.3）：模板敏感键 → secretEnv；
// secretKeys 逃生门键 → secretEnv；L3 派生/保留键 → env；导出参数键不注入 Pod env。
func TestSplitExportEnvSensitiveRouting(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	in := renderFixtureInput(t, core, ws, svc, pkg, mark, &ExportOrderRequest{
		Marker:     mark.Marker,
		Env:        map[string]string{"MCP_E2E_TOKEN": "tok-1", "LLM_PROVIDER": "openai"},
		SecretKeys: []string{"MCP_E2E_TOKEN"},
	})
	plain, secret, err := splitExportEnv(in)
	if err != nil {
		t.Fatal(err)
	}
	// 模板敏感键（L1 值）→ secretEnv
	for _, k := range []string{"CHECKPOINT_PASSWORD", "AGENT_REDIS_URL"} {
		if _, ok := secret[k]; !ok {
			t.Fatalf("%q must route to secretEnv: plain=%v secret=%v", k, plain, secret)
		}
	}
	// secretKeys 逃生门（L2 新增键，并集语义必须进 secretEnv——契约 §5.3 e2e §8.5 场景）
	if secret["MCP_E2E_TOKEN"] != "tok-1" {
		t.Fatalf("MCP_E2E_TOKEN = %q", secret["MCP_E2E_TOKEN"])
	}
	// L3 保留/派生键 → env
	if plain["AGENT_CONFIG_DIR"] != "/config" || plain["AGENT_WORKSPACE_DIR"] != "/workspace" ||
		plain["SERVER_HOST"] != "0.0.0.0" || plain["SERVER_PORT"] != "8100" {
		t.Fatalf("platform reserved keys wrong: %v", plain)
	}
	if !strings.HasPrefix(plain["CHECKPOINT_JDBC_URL"], "jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint_e2e_export_agent") {
		t.Fatalf("derived JDBC URL wrong: %q", plain["CHECKPOINT_JDBC_URL"])
	}
	if plain["AGENT_REDIS_PREFIX"] != "e2e-export-agent:" {
		t.Fatalf("redis prefix wrong: %q", plain["AGENT_REDIS_PREFIX"])
	}
	// 模板非敏感键（L1 值）→ env
	if plain["LLM_BASE_URL"] != "https://llm.prod.example.com/v1" || plain["LLM_PROVIDER"] != "openai" {
		t.Fatalf("template plain keys wrong: %v", plain)
	}
	// 导出参数键（REGISTRY_ADDRESS 等）不注入 Pod env
	for _, k := range []string{"REGISTRY_ADDRESS", "ROUTING_MODE", "BASE_IMAGE_WHITELIST", "CHECKPOINT_JDBC_BASE"} {
		if _, ok := plain[k]; ok {
			t.Fatalf("export meta key %q leaked into env", k)
		}
	}
}

// TestResolveExportLayersCollisions 四层合并裁决（契约 §5.1）：L2 撞 L1/L3 400、
// baseImage 缺省取白名单首项、REGISTRY_ADDRESS 必配、host 模式缺后缀 400。
func TestResolveExportLayersCollisions(t *testing.T) {
	core, _, ws, _, _, done := newExportFixture(t)
	defer done()

	// baseImage 缺省取白名单首项
	l1, merged, wl, err := core.resolveExportLayers(ws.ID, &ExportOrderRequest{})
	if err != nil {
		t.Fatal(err)
	}
	if len(wl) != 1 || wl[0].Image != "172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018" {
		t.Fatalf("whitelist parse wrong: %v", wl)
	}
	if merged["REGISTRY_ADDRESS"] == "" {
		t.Fatal("L1 merged into layers")
	}
	if l1["ROUTING_MODE"] != "host" {
		t.Fatalf("L1 = %v", l1)
	}
	// L2 撞 L1
	_, _, _, err = core.resolveExportLayers(ws.ID, &ExportOrderRequest{Env: map[string]string{"LLM_MODEL_ID": "other"}})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("L2 hit L1: err = %v", err)
	}
	// L2 撞 L3 派生键（经 secretKeys 同样拒绝）
	_, _, _, err = core.resolveExportLayers(ws.ID, &ExportOrderRequest{SecretKeys: []string{"AGENT_REDIS_PREFIX"}})
	if err == nil || !errors.Is(err, ErrInvalidForm) {
		t.Fatalf("L2 secretKeys hit L3: err = %v", err)
	}
	// host 模式缺后缀：清掉 ROUTING_HOST_SUFFIX 再试
	if err := core.DB.Where("workspace_id = ? AND env_key = ?", ws.ID, "ROUTING_HOST_SUFFIX").
		Delete(&store.WorkspaceRuntimeDefaultEntity{}).Error; err != nil {
		t.Fatal(err)
	}
	_, _, _, err = core.resolveExportLayers(ws.ID, &ExportOrderRequest{})
	if err == nil || !strings.Contains(err.Error(), "ROUTING_HOST_SUFFIX") {
		t.Fatalf("host mode without suffix: err = %v", err)
	}
	// router 模式不需要后缀：ROUTING_HOST_SUFFIX 已删除（上文 host 用例前置），
	// ROUTING_MODE 经 L1 置 router（评审修复：导出参数键 L2 不可携带，一律取 L1）
	if _, err := core.UpdateRuntimeDefaults(ws.ID, "ops", map[string]string{"ROUTING_MODE": "router"}); err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := core.resolveExportLayers(ws.ID, &ExportOrderRequest{}); err != nil {
		t.Fatalf("router mode: %v", err)
	}
	// 导出参数键 L2 携带一律 400（评审修复）：REGISTRY_NAMESPACE 不在夹具 L1，
	// 旧实现会作为「新键」放行并静默改写推送目标命名空间
	_, _, _, err = core.resolveExportLayers(ws.ID, &ExportOrderRequest{Env: map[string]string{"REGISTRY_NAMESPACE": "evil-ns"}})
	if err == nil || !errors.Is(err, ErrInvalidForm) || !strings.Contains(err.Error(), "export parameter") {
		t.Fatalf("L2 export meta key: err = %v", err)
	}
	// REGISTRY_ADDRESS 缺失
	if err := core.DB.Where("workspace_id = ? AND env_key = ?", ws.ID, "REGISTRY_ADDRESS").
		Delete(&store.WorkspaceRuntimeDefaultEntity{}).Error; err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := core.resolveExportLayers(ws.ID, &ExportOrderRequest{}); err == nil ||
		!strings.Contains(err.Error(), "REGISTRY_ADDRESS") {
		t.Fatalf("missing registry: err = %v", err)
	}
}

// TestRenderExportValuesShape values.yaml 形状（契约 §5.5）：顶层键齐备、image 段、
// ingress host 段、mysql.createDatabase、replicas、注释头部与明文警示行。
func TestRenderExportValuesShape(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	in := renderFixtureInput(t, core, ws, svc, pkg, mark, &ExportOrderRequest{
		Marker: mark.Marker, Replicas: 2,
		Env: map[string]string{"MCP_E2E_TOKEN": "tok-1"}, SecretKeys: []string{"MCP_E2E_TOKEN"},
	})
	raw, err := renderExportValues(in)
	if err != nil {
		t.Fatal(err)
	}
	// 注释头部（拼接字符串）：导出单号/服务/工作空间/源包/chart/基底镜像/明文警示行
	for _, frag := range []string{"# oaf-agent values.yaml — 由导出单 EXP-20261030-0001 自动生成",
		"e2e-export-agent", ws.Slug, pkg.Slug, mark.Marker, "oaf-agent-0.1.0",
		"172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018", "敏感值明文写入本文件"} {
		if !strings.Contains(raw, frag) {
			t.Fatalf("values header missing %q:\n%s", frag, raw)
		}
	}
	var doc map[string]interface{}
	if err := yaml.Unmarshal([]byte(raw), &doc); err != nil {
		t.Fatalf("rendered values not valid yaml: %v", err)
	}
	if doc["fullnameOverride"] != "e2e-export-agent" {
		t.Fatalf("fullnameOverride = %v", doc["fullnameOverride"])
	}
	img := doc["image"].(map[string]interface{})
	if img["oaf"] != "172.20.0.1:5001/oaf-e2e-export-agent:"+mark.Marker {
		t.Fatalf("image.oaf = %v", img["oaf"])
	}
	env := doc["env"].(map[string]interface{})
	if env["AGENT_CONFIG_DIR"] != "/config" || env["SERVER_PORT"] != "8100" {
		t.Fatalf("env section wrong: %v", env)
	}
	if _, ok := env["CHECKPOINT_JDBC_URL"]; !ok {
		t.Fatal("derived CHECKPOINT_JDBC_URL missing")
	}
	secret := doc["secretEnv"].(map[string]interface{})
	if secret["CHECKPOINT_PASSWORD"] != "prod-pw" || secret["MCP_E2E_TOKEN"] != "tok-1" {
		t.Fatalf("secretEnv wrong: %v", secret)
	}
	if doc["replicas"].(int) != 2 {
		t.Fatalf("replicas = %v", doc["replicas"])
	}
	ing := doc["ingress"].(map[string]interface{})
	if ing["mode"] != "host" || ing["hostSuffix"] != ".prod.example.com" {
		t.Fatalf("ingress wrong: %v", ing)
	}
	if doc["mysql"].(map[string]interface{})["createDatabase"] != true {
		t.Fatal("mysql.createDatabase must be true when L1 has CHECKPOINT_JDBC_BASE")
	}
	pa := doc["podAnnotations"].(map[string]interface{})
	if pa["oaf.platform/export-id"] != "EXP-20261030-0001" ||
		pa["oaf.platform/package-checksum"] != "sha256:"+mark.Checksum {
		t.Fatalf("podAnnotations wrong: %v", pa)
	}
	// router 模式渲染 pathPrefix
	in2 := renderFixtureInput(t, core, ws, svc, pkg, mark, &ExportOrderRequest{
		Marker: mark.Marker, Env: map[string]string{"ROUTING_MODE": "router"},
	})
	raw2, err := renderExportValues(in2)
	if err != nil {
		t.Fatal(err)
	}
	var doc2 map[string]interface{}
	yaml.Unmarshal([]byte(raw2), &doc2)
	ing2 := doc2["ingress"].(map[string]interface{})
	if ing2["mode"] != "router" || ing2["pathPrefix"] != "/agent/e2e-export-agent" {
		t.Fatalf("router ingress wrong: %v", ing2)
	}
}

// TestDerivedExportEnvBadBase 非法 CHECKPOINT_JDBC_BASE（非 jdbc: URL）→ 创建期 400。
func TestDerivedExportEnvBadBase(t *testing.T) {
	if _, err := derivedExportEnv(map[string]string{"CHECKPOINT_JDBC_BASE": "not-a-jdbc-url"}, "x"); err == nil {
		t.Fatal("bad base must fail derivation")
	}
	if _, err := derivedExportEnv(map[string]string{}, "x"); err != nil {
		t.Fatalf("no base = no derivation: %v", err)
	}
}

// TestRenderExportManifestShape manifest.json 字段（契约 §5.7）。
func TestRenderExportManifestShape(t *testing.T) {
	core, _, ws, svc, pkg, done := newExportFixture(t)
	defer done()
	mark := freezeMark(t, core, ws.ID, svc.ID)
	in := renderFixtureInput(t, core, ws, svc, pkg, mark, nil)
	_, secret, err := splitExportEnv(in)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := marshalExportManifest(renderExportManifest(in, len(secret)))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatal(err)
	}
	if m["exportOrder"] != "EXP-20261030-0001" {
		t.Fatalf("exportOrder = %v", m["exportOrder"])
	}
	svcM := m["service"].(map[string]interface{})
	if svcM["shortName"] != "e2e-export-agent" || svcM["k8sName"] != "oaf-e2e-export-agent" {
		t.Fatalf("service section wrong: %v", svcM)
	}
	pkgM := m["package"].(map[string]interface{})
	if pkgM["sourcePackageId"].(float64) != float64(pkg.ID) || pkgM["slug"] != pkg.Slug ||
		pkgM["markChecksum"] != "sha256:"+mark.Checksum || pkgM["marker"] != mark.Marker {
		t.Fatalf("package section wrong: %v", pkgM)
	}
	bundle := m["bundle"].(map[string]interface{})
	if bundle["chart"] != "oaf-agent-0.1.0" || bundle["values"] == "" {
		t.Fatalf("bundle section wrong: %v", bundle)
	}
	summary := m["overrideSummary"].(map[string]interface{})
	if summary["mcpServersRewritten"].(float64) != 0 || summary["runtimeDefaultsApplied"].(float64) == 0 {
		t.Fatalf("summary wrong: %v", summary)
	}
	// itsm 段经推进更新
	updated, err := manifestWithItsm(raw, "ITSM-9", "export-owner", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	var m2 ExportManifest
	json.Unmarshal([]byte(updated), &m2)
	if m2.Itsm.TicketID != "ITSM-9" || m2.Itsm.AdvancedBy != "export-owner" || m2.Itsm.AdvancedAt == nil {
		t.Fatalf("itsm update wrong: %+v", m2.Itsm)
	}
	// 下载审计追加
	afterDL, err := manifestAppendDownload(updated, "prod-op", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	var m3 ExportManifest
	json.Unmarshal([]byte(afterDL), &m3)
	if len(m3.Audit.DownloadedBy) != 1 || m3.Audit.DownloadedBy[0].User != "prod-op" {
		t.Fatalf("downloadedby wrong: %+v", m3.Audit.DownloadedBy)
	}
	// 损坏 manifest 容错
	if _, err := parseExportManifest("not-json"); err == nil {
		t.Fatal("corrupt manifest must error")
	}
	if m, err := parseExportManifest(""); err != nil || m == nil {
		t.Fatalf("empty manifest tolerated: %v %v", m, err)
	}
}
