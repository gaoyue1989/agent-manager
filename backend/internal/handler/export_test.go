// 导出链路端点测试（契约 §2.2/§4）：路由装配（console.go 尾部 + router.go /console/exports 段）
// + 权限矩阵（匿名 401 / 成员可读写）+ values/manifest 下载头 + 掩码红线
// （values_yaml 不出现在任何 JSON 响应，契约 §4.3）。
package handler

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/imagebuilder"
	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

// fakeExportBuilder handler 侧内存假实现（契约 §3.3 fake 形态；service 包测试桩不可跨包复用）。
type fakeExportBuilder struct{ digest string }

func (f *fakeExportBuilder) Build(ctx context.Context, in imagebuilder.BuildInput) (imagebuilder.BuildOutput, error) {
	return imagebuilder.BuildOutput{Digest: f.digest}, nil
}

// newExportHandlerFixture 组装 owner/member 登录态 + 空间/服务/包 + L1 运行时默认 + fake builder。
func newExportHandlerFixture(t *testing.T) (*gin.Engine, *service.Core, uint, uint, string, string, func()) {
	t.Helper()
	r, core, _, done := newTestServer(t)
	if err := core.DB.AutoMigrate(&store.ExportOrderEntity{}, &store.ExportPackageMarkEntity{},
		&store.WorkspaceRuntimeDefaultEntity{}); err != nil {
		t.Fatal(err)
	}
	core.Images = &fakeExportBuilder{digest: "sha256:" + strings.Repeat("ef", 32)}

	owner := &store.User{Username: "exp-owner", PasswordHash: mustHash("pass123"), Role: "user", Status: "active"}
	member := &store.User{Username: "exp-member", PasswordHash: mustHash("pass123"), Role: "user", Status: "active"}
	for _, u := range []*store.User{owner, member} {
		if err := core.DB.Create(u).Error; err != nil {
			t.Fatal(err)
		}
	}
	ws := &store.Workspace{Slug: "ws-export", Name: "导出空间", OwnerUserID: owner.ID, Status: "active"}
	if err := core.DB.Create(ws).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: owner.ID, Role: "owner"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Create(&store.WorkspaceMember{WorkspaceID: ws.ID, UserID: member.ID, Role: "member"}).Error; err != nil {
		t.Fatal(err)
	}
	pkg := uploadZip(t, r)
	pkgID := uint(pkg["id"].(float64))
	svc := &store.ServiceEntity{
		K8sName: "oaf-e2e-export-agent", DisplayName: "导出测试服务", PackageID: pkgID,
		Image: "agent-framework:latest", Replicas: 2, Status: store.StatusRunning,
		EnvJSON: "{}", WorkspaceID: ws.ID, CreatedBy: owner.ID,
		ShortName: "e2e-export-agent", AgentCardJSON: "{}", SkillsJSON: "[]",
	}
	if err := core.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := core.UpdateRuntimeDefaults(ws.ID, "ops", map[string]string{
		"CHECKPOINT_JDBC_BASE": "jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint",
		"CHECKPOINT_PASSWORD":  "prod-pw",
		"AGENT_REDIS_URL":      "redis://redis.prod.svc:6379",
		"REGISTRY_ADDRESS":     "172.20.0.1:5001",
		"ROUTING_MODE":         "host",
		"ROUTING_HOST_SUFFIX":  ".prod.example.com",
		"BASE_IMAGE_WHITELIST": "172.20.0.1:5001/agent-framework:agentscope-2.1.0-v20261018|Agent Framework 2.1.0",
	}); err != nil {
		t.Fatal(err)
	}
	login := func(username string) string {
		w, out := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
			map[string]string{"username": username, "password": "pass123"})
		if w.Code != http.StatusOK {
			t.Fatalf("login %s: %d %s", username, w.Code, w.Body.String())
		}
		return out["data"].(map[string]interface{})["token"].(string)
	}
	return r, core, ws.ID, svc.ID, login("exp-owner"), login("exp-member"), done
}

// waitForExportBuiltHTTP 轮询导出单至 built（异步构建 goroutine 推进）。
func waitForExportBuiltHTTP(t *testing.T, r *gin.Engine, token string, orderID uint) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		w, out := authJSON(t, r, http.MethodGet, exportOrderPath(orderID), token, nil)
		if w.Code != http.StatusOK {
			t.Fatalf("get order: %d %s", w.Code, w.Body.String())
		}
		if out["data"].(map[string]interface{})["status"] == "built" {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("order not built in time: %s", w.Body.String())
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func exportOrderPath(id uint) string {
	return "/api/v1/console/exports/" + strconv.FormatUint(uint64(id), 10)
}

// TestExportRoutesAndMask 端到端：打标记 → 创建导出单 → 轮询 built → 详情掩码红线 →
// values/manifest 下载头 → 人工推进权限（member 403 / owner 200）。
func TestExportRoutesAndMask(t *testing.T) {
	r, _, wsID, svcID, ownerTok, memberTok, done := newExportHandlerFixture(t)
	defer done()
	svcPath := fmt.Sprintf("/api/v1/console/workspaces/%d/agents/%d/export", wsID, svcID)

	// 匿名 → 401（JWTAuth 层）
	w, _ := authJSON(t, r, http.MethodGet, svcPath+"/package", "", nil)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous: %d", w.Code)
	}
	// member 打标记（成员可写，契约 §4.1）
	w, out := authJSON(t, r, http.MethodPost, svcPath+"/package/mark", memberTok, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("member mark: %d %s", w.Code, w.Body.String())
	}
	marker := out["data"].(map[string]interface{})["marker"].(string)
	if marker != "1.0.0-e1" {
		t.Fatalf("marker = %q", marker)
	}
	// 标记列表 + 预填（lockedKeys/白名单）
	if w, _ = authJSON(t, r, http.MethodGet, svcPath+"/package/marks", memberTok, nil); w.Code != http.StatusOK {
		t.Fatalf("marks: %d", w.Code)
	}
	w, out = authJSON(t, r, http.MethodGet, svcPath+"/preview", memberTok, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("preview: %d %s", w.Code, w.Body.String())
	}
	preview := out["data"].(map[string]interface{})
	if _, ok := preview["lockedKeys"]; !ok {
		t.Fatalf("preview missing lockedKeys: %v", preview)
	}
	if _, ok := preview["baseImageWhitelist"].([]interface{}); !ok {
		t.Fatalf("preview missing baseImageWhitelist: %v", preview)
	}
	// 创建导出单：L2 敏感键经 secretKeys 进 values secretEnv
	body := map[string]interface{}{
		"marker":     marker,
		"env":        map[string]string{"MCP_E2E_TOKEN": "e2e-token-123"},
		"secretKeys": []string{"MCP_E2E_TOKEN"},
		"replicas":   2,
	}
	w, out = authJSON(t, r, http.MethodPost, svcPath, memberTok, body)
	if w.Code != http.StatusOK && w.Code != http.StatusCreated {
		t.Fatalf("create export: %d %s", w.Code, w.Body.String())
	}
	order := out["data"].(map[string]interface{})
	orderID := uint(order["id"].(float64))
	waitForExportBuiltHTTP(t, r, ownerTok, orderID)

	// 详情 JSON 掩码红线：values_yaml / request_json / 敏感值绝不回显（契约 §4.3）
	w, _ = authJSON(t, r, http.MethodGet, exportOrderPath(orderID), memberTok, nil)
	detail := w.Body.String()
	for _, leak := range []string{"e2e-token-123", "prod-pw", "values_yaml", "valuesYaml", "requestJson"} {
		if strings.Contains(detail, leak) {
			t.Fatalf("detail JSON leaks %q: %s", leak, detail)
		}
	}
	// values 下载：Content-Disposition attachment + 明文交付（决议 ①）
	w = serve(r, func() *http.Request {
		req := newReq(http.MethodGet, exportOrderPath(orderID)+"/values.yaml")
		req.Header.Set("Authorization", "Bearer "+memberTok)
		return req
	}())
	if w.Code != http.StatusOK || !strings.Contains(w.Header().Get("Content-Disposition"), "attachment") {
		t.Fatalf("values download: %d %q", w.Code, w.Header().Get("Content-Disposition"))
	}
	if !strings.Contains(w.Body.String(), "e2e-token-123") || !strings.Contains(w.Body.String(), "oafDigest: ") {
		t.Fatalf("values file wrong:\n%s", w.Body.String())
	}
	// manifest 下载（缩进 JSON，exportOrder 落 EXP- 单号）
	w = serve(r, func() *http.Request {
		req := newReq(http.MethodGet, exportOrderPath(orderID)+"/manifest.json")
		req.Header.Set("Authorization", "Bearer "+memberTok)
		return req
	}())
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), `"exportOrder": "EXP-`) {
		t.Fatalf("manifest download: %d %s", w.Code, w.Body.String())
	}
	// 人工推进：member → 403；owner → built→approving（记录操作人）
	w, _ = authJSON(t, r, http.MethodPost, exportOrderPath(orderID)+"/status", memberTok,
		map[string]string{"status": "approving"})
	if w.Code != http.StatusForbidden {
		t.Fatalf("member advance: %d (want 403)", w.Code)
	}
	w, out = authJSON(t, r, http.MethodPost, exportOrderPath(orderID)+"/status", ownerTok,
		map[string]string{"status": "approving", "itsmTicketId": "ITSM-1"})
	if w.Code != http.StatusOK {
		t.Fatalf("owner advance: %d %s", w.Code, w.Body.String())
	}
	if got := out["data"].(map[string]interface{})["status"]; got != "approving" {
		t.Fatalf("status = %v", got)
	}
	// 非法跳跃 → 400（approving → deployed）
	w, _ = authJSON(t, r, http.MethodPost, exportOrderPath(orderID)+"/status", ownerTok,
		map[string]string{"status": "deployed"})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("illegal transition: %d (want 400)", w.Code)
	}
}

// TestExportOrderScopeForeign 导出单归属：非本空间成员不可见（service 层 404 裁决，
// 契约 §4.2——/console/exports 组无 :ws 段，归属校验不下沉 handler）。
func TestExportOrderScopeForeign(t *testing.T) {
	r, core, wsID, svcID, ownerTok, _, done := newExportHandlerFixture(t)
	defer done()
	// 另建一个空间 + 非成员用户
	foreign := &store.User{Username: "foreign-user", PasswordHash: mustHash("pass123"), Role: "user", Status: "active"}
	core.DB.Create(foreign)
	loginForeign := func() string {
		_, out := doJSON(t, r, http.MethodPost, "/api/v1/auth/login",
			map[string]string{"username": "foreign-user", "password": "pass123"})
		return out["data"].(map[string]interface{})["token"].(string)
	}
	svcPath := fmt.Sprintf("/api/v1/console/workspaces/%d/agents/%d/export", wsID, svcID)
	wMark, out := authJSON(t, r, http.MethodPost, svcPath+"/package/mark", ownerTok, nil)
	if wMark.Code != http.StatusOK {
		t.Fatalf("mark: %d %s", wMark.Code, wMark.Body.String())
	}
	marker := out["data"].(map[string]interface{})["marker"].(string)
	wCreate, out := authJSON(t, r, http.MethodPost, svcPath, ownerTok,
		map[string]interface{}{"marker": marker})
	if wCreate.Code != http.StatusOK && wCreate.Code != http.StatusCreated {
		t.Fatalf("create: %d %s", wCreate.Code, wCreate.Body.String())
	}
	orderID := uint(out["data"].(map[string]interface{})["id"].(float64))
	// 外部用户 → 404（不可见）
	wGet, _ := authJSON(t, r, http.MethodGet, exportOrderPath(orderID), loginForeign(), nil)
	if wGet.Code != http.StatusNotFound {
		t.Fatalf("foreign user get: %d (want 404)", wGet.Code)
	}
	// 外部用户下载同样 404
	wDL := serve(r, func() *http.Request {
		req := newReq(http.MethodGet, exportOrderPath(orderID)+"/values.yaml")
		req.Header.Set("Authorization", "Bearer "+loginForeign())
		return req
	}())
	if wDL.Code != http.StatusNotFound {
		t.Fatalf("foreign download: %d (want 404)", wDL.Code)
	}
}

// TestExportCreateUnknownMarker400 陌生 marker（不存在/未冻结）→ 表单类 400：
// ErrMarkNotBuildable 已显式映射进 mapError 的 400 分支（评审修复——原裸 sentinel
// 无匹配分支落 default 500，与「无标记不可构建」的业务主路径语义相悖）。
func TestExportCreateUnknownMarker400(t *testing.T) {
	r, _, wsID, svcID, ownerTok, _, done := newExportHandlerFixture(t)
	defer done()
	svcPath := fmt.Sprintf("/api/v1/console/workspaces/%d/agents/%d/export", wsID, svcID)
	w, out := authJSON(t, r, http.MethodPost, svcPath, ownerTok, map[string]interface{}{"marker": "9.9.9-e99"})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("unknown marker: code = %d, body = %s", w.Code, w.Body.String())
	}
	if msg, _ := out["message"].(string); msg == "" {
		t.Fatalf("error message missing: %s", w.Body.String())
	}
}
