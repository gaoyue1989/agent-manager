// M5 模型连接测试：CRUD 唯一性/掩码/test 三分支/deliver 幂等（假 agent /models 计数）/
// usage 三态。M6 连接器测试：verify 三分支（httptest 假 MCP）/ refCount。
package service

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"agent-manager/backend/internal/store"
)

// fakeOpenAI 假 OpenAI chat/completions 端点（test 三分支）。
func fakeOpenAI(status int) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/chat/completions") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"pong"}}]}`))
	}))
}

func TestModelConnCRUDAndMask(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000aaa1", "模型空间")
	view, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: "qwen", ModelID: "qwen-plus", BaseURL: "https://api.x/v1", APIKey: "sk-abcdefgh1234"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if view.APIKeyMasked == "" || strings.Contains(view.APIKeyMasked, "abcdefgh") {
		t.Fatalf("apiKey must be masked: %q", view.APIKeyMasked)
	}
	b, _ := json.Marshal(view)
	if strings.Contains(string(b), "sk-abcdefgh") {
		t.Fatal("plain api key leaked in view")
	}
	if view.Temperature != 0.3 || view.MaxTokens != 16384 || view.TimeoutSec != 120 {
		t.Fatalf("defaults: %+v", view)
	}
	// 空间内唯一
	if _, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: "qwen", ModelID: "x", BaseURL: "http://x/v1"}); err == nil {
		t.Fatal("duplicate name should fail")
	}
	// baseUrl 非 http
	if _, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: "bad", ModelID: "x", BaseURL: "ftp://x"}); err == nil {
		t.Fatal("bad baseUrl should fail")
	}
	// PATCH 清除 apiKey + 改字段 → delivery 置 pending
	c.DB.Create(&store.ModelDelivery{ConnectionID: view.ID, ServiceID: 7, Status: "synced"})
	patched, err := c.UpdateModelConnection(ws.ID, view.ID, PatchModelConnInput{
		APIKey: strPtr(""), ModelID: strPtr("qwen-max")})
	if err != nil || patched.APIKeyMasked != "" {
		t.Fatalf("patch: %+v %v", patched, err)
	}
	var d store.ModelDelivery
	c.DB.Where("connection_id = ?", view.ID).First(&d)
	if d.Status != "pending" {
		t.Fatalf("delivery should reset to pending: %+v", d)
	}
	// 删除保护：默认连接（env 匹配）409
	svc := &store.ServiceEntity{K8sName: "oaf-mc", DisplayName: "mc", WorkspaceID: ws.ID,
		EnvJSON: `{"LLM_MODEL":"qwen-max","LLM_BASE_URL":"https://api.x/v1"}`}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	if err := c.DeleteModelConnection(ws.ID, view.ID); err == nil || !strings.Contains(err.Error(), "default_connection_in_use") {
		t.Fatalf("default conn delete: %v", err)
	}
	// 改回 baseUrl 使不匹配 → 可删
	if _, err := c.UpdateModelConnection(ws.ID, view.ID, PatchModelConnInput{
		BaseURL: strPtr("http://other/v1")}); err != nil {
		t.Fatal(err)
	}
	if err := c.DeleteModelConnection(ws.ID, view.ID); err != nil {
		t.Fatalf("delete after unbind: %v", err)
	}
}

func TestModelConnTestThreeBranches(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000aaa2", "测试空间")

	okSrv := fakeOpenAI(200)
	defer okSrv.Close()
	badSrv := fakeOpenAI(401)
	defer badSrv.Close()
	deadSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	deadSrv.Close() // 立即关闭 → 连接拒绝

	v1, _ := c.CreateModelConnection(ws.ID, CreateModelConnInput{Name: "ok", ModelID: "m", BaseURL: okSrv.URL})
	v2, _ := c.CreateModelConnection(ws.ID, CreateModelConnInput{Name: "bad", ModelID: "m", BaseURL: badSrv.URL})
	v3, _ := c.CreateModelConnection(ws.ID, CreateModelConnInput{Name: "dead", ModelID: "m", BaseURL: deadSrv.URL})

	r1, err := c.TestModelConnection(ws.ID, v1.ID)
	if err != nil || !r1.OK || r1.LatencyMs < 0 {
		t.Fatalf("ok branch: %+v %v", r1, err)
	}
	r2, _ := c.TestModelConnection(ws.ID, v2.ID)
	if r2.OK || !strings.Contains(r2.Error, "401") {
		t.Fatalf("non-200 branch: %+v", r2)
	}
	r3, _ := c.TestModelConnection(ws.ID, v3.ID)
	if r3.OK || r3.Error == "" {
		t.Fatalf("refused branch: %+v", r3)
	}
	// 状态回写（注意：gorm 复用目标结构体时会叠加主键条件，每次需重置零值）
	var m store.ModelConnection
	c.DB.First(&m, v1.ID)
	if m.Status != "verified" {
		t.Fatalf("status: %+v", m)
	}
	m = store.ModelConnection{}
	c.DB.First(&m, v2.ID)
	if m.Status != "failed" {
		t.Fatalf("failed status: %+v", m)
	}
}

// fakeAgentModels 假业务 Agent /models 端点：记录 POST/PATCH 次数与当前配置。
func fakeAgentModels(t *testing.T, postCnt, patchCnt *int32, mutate func(existing []map[string]interface{})) *httptest.Server {
	var existing []map[string]interface{}
	mu := safeMutex()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/models":
			_, _ = w.Write([]byte(`{"default_model":"system","models":[` + joinJSONMaps(existing) + `]}`))
		case r.Method == http.MethodPost && r.URL.Path == "/models":
			var body map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&body)
			atomic.AddInt32(postCnt, 1)
			for _, e := range existing {
				if e["name"] == body["name"] {
					w.WriteHeader(http.StatusBadRequest)
					_, _ = w.Write([]byte(`{"error":"duplicate_name","message":"model name already exists"}`))
					return
				}
			}
			body["id"] = "01J8XKTESTID"
			existing = append(existing, body)
			b, _ := json.Marshal(body)
			_, _ = w.Write(b)
		case r.Method == http.MethodPatch && strings.HasPrefix(r.URL.Path, "/models/"):
			atomic.AddInt32(patchCnt, 1)
			var body map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&body)
			id := strings.TrimPrefix(r.URL.Path, "/models/")
			for _, e := range existing {
				if e["id"] == id {
					for k, v := range body {
						e[k] = v
					}
				}
			}
			_, _ = w.Write([]byte(`{}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

func TestDeliverIdempotent(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000aaa3", "下发空间")
	var postCnt, patchCnt int32
	agent := fakeAgentModels(t, &postCnt, &patchCnt, nil)
	defer agent.Close()

	// 业务 Agent 服务（running，ClusterURL 指向假 agent）
	svc := &store.ServiceEntity{K8sName: "oaf-deliver", DisplayName: "deliver",
		WorkspaceID: ws.ID, Status: store.StatusRunning, ClusterURL: agent.URL}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	conn := seedVerifiedConn(t, c, ws.ID, "deepseek", "deepseek-v3", "https://ds.example/v1")

	// 首次下发：POST 一次 → synced + remote id
	d, err := c.DeliverConnectionToService(ws.ID, conn.ID, svc.ID)
	if err != nil {
		t.Fatalf("deliver: %v", err)
	}
	if d.Status != "synced" || d.RemoteModelID != "01J8XKTESTID" {
		t.Fatalf("delivery: %+v", d)
	}
	if atomic.LoadInt32(&postCnt) != 1 {
		t.Fatalf("post count: %d", postCnt)
	}
	// 重复下发：幂等跳过（POST/PATCH 计数不变）
	if _, err := c.DeliverConnectionToService(ws.ID, conn.ID, svc.ID); err != nil {
		t.Fatalf("re-deliver: %v", err)
	}
	if atomic.LoadInt32(&postCnt) != 1 || atomic.LoadInt32(&patchCnt) != 0 {
		t.Fatalf("idempotent counts: post=%d patch=%d", postCnt, patchCnt)
	}
	// 配置漂移（改 baseUrl）→ delivery 置 pending → 再下发走 PATCH
	if _, err := c.UpdateModelConnection(ws.ID, conn.ID, PatchModelConnInput{
		BaseURL: strPtr("https://ds2.example/v1")}); err != nil {
		t.Fatal(err)
	}
	if _, err := c.DeliverConnectionToService(ws.ID, conn.ID, svc.ID); err != nil {
		t.Fatalf("deliver after drift: %v", err)
	}
	if atomic.LoadInt32(&postCnt) != 1 || atomic.LoadInt32(&patchCnt) != 1 {
		t.Fatalf("drift counts: post=%d patch=%d", postCnt, patchCnt)
	}
	var fresh store.ModelDelivery
	c.DB.Where("connection_id = ?", conn.ID).First(&fresh)
	if fresh.Status != "synced" {
		t.Fatalf("after patch: %+v", fresh)
	}
	// 非 running Agent → agent_not_ready
	svc2 := &store.ServiceEntity{K8sName: "oaf-deliver2", DisplayName: "d2",
		WorkspaceID: ws.ID, Status: store.StatusStopped, ClusterURL: agent.URL}
	c.DB.Create(svc2)
	conn2 := seedVerifiedConn(t, c, ws.ID, "m2", "m2", "http://m2/v1")
	if _, err := c.DeliverConnectionToService(ws.ID, conn2.ID, svc2.ID); err == nil ||
		!strings.Contains(err.Error(), "agent_not_ready") {
		t.Fatalf("not ready: %v", err)
	}
}

func TestModelUsageAggregation(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000aaa4", "usage空间")
	svc := &store.ServiceEntity{K8sName: "oaf-usage", DisplayName: "usage",
		WorkspaceID: ws.ID, EnvJSON: `{"LLM_MODEL":"m1","LLM_BASE_URL":"http://b1/v1"}`}
	c.DB.Create(svc)
	conn1 := seedVerifiedConn(t, c, ws.ID, "c1", "m1", "http://b1/v1")
	conn2 := seedVerifiedConn(t, c, ws.ID, "c2", "m2", "http://b2/v1")
	c.DB.Create(&store.ModelDelivery{ConnectionID: conn1.ID, ServiceID: svc.ID, Status: "synced"})
	c.DB.Create(&store.ModelDelivery{ConnectionID: conn2.ID, ServiceID: svc.ID, Status: "failed", Error: "boom"})

	usage, err := c.ModelConnectionUsage(ws.ID)
	if err != nil || len(usage) != 1 {
		t.Fatalf("usage: %v n=%d", err, len(usage))
	}
	row := usage[0]
	if row.DefaultConnectionName != "c1" {
		t.Fatalf("default conn: %+v", row)
	}
	// 连接删除后 → removed 标记
	if err := c.DB.Delete(&store.ModelConnection{}, conn2.ID).Error; err != nil {
		t.Fatal(err)
	}
	usage, _ = c.ModelConnectionUsage(ws.ID)
	states := map[uint]string{}
	for _, cn := range usage[0].Connections {
		states[cn.ConnectionID] = cn.Status
	}
	if states[conn1.ID] != "synced" || states[conn2.ID] != "removed" {
		t.Fatalf("states: %+v", states)
	}
}

// TestModelUsageRuntimeModels issue c3095ee：usage 行补运行时 /models 真实清单。
// 断言映射（默认标记 / 本空间下发标记）与拉取失败语义（nil=业务面不可达，非空数组）。
func TestModelUsageRuntimeModels(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000aaa6", "runtime-models空间")

	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/models" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"default_model": "remote-default",
			"models": []map[string]interface{}{
				{"id": "remote-default", "name": "系统默认", "source": "system",
					"model_id": "m1", "base_url": "http://b1/v1"},
				{"id": "remote-delivered", "name": "下发模型", "source": "managed",
					"model_id": "m2", "base_url": "http://b2/v1"},
			},
		})
	}))
	defer agent.Close()

	svc := &store.ServiceEntity{K8sName: "oaf-usage-rt", DisplayName: "usage-rt",
		WorkspaceID: ws.ID, Status: store.StatusRunning, ClusterURL: agent.URL}
	c.DB.Create(svc)
	conn := seedVerifiedConn(t, c, ws.ID, "c-rt", "m2", "http://b2/v1")
	c.DB.Create(&store.ModelDelivery{ConnectionID: conn.ID, ServiceID: svc.ID,
		RemoteModelID: "remote-delivered", Status: "synced"})

	usage, err := c.ModelConnectionUsage(ws.ID)
	if err != nil || len(usage) != 1 {
		t.Fatalf("usage: %v n=%d", err, len(usage))
	}
	byID := map[string]RuntimeModel{}
	for _, m := range usage[0].RuntimeModels {
		byID[m.ID] = m
	}
	if len(byID) != 2 {
		t.Fatalf("runtime models: %+v", usage[0].RuntimeModels)
	}
	if !byID["remote-default"].IsDefault || byID["remote-default"].Delivered {
		t.Fatalf("default mapping: %+v", byID["remote-default"])
	}
	if byID["remote-delivered"].IsDefault || !byID["remote-delivered"].Delivered {
		t.Fatalf("delivered mapping: %+v", byID["remote-delivered"])
	}
	if byID["remote-delivered"].ModelID != "m2" || byID["remote-delivered"].BaseURL != "http://b2/v1" {
		t.Fatalf("field mapping: %+v", byID["remote-delivered"])
	}

	// 业务面不可达：保持 nil（JSON null），前端据此显示拉取失败而非空清单
	agent.Close()
	usage, err = c.ModelConnectionUsage(ws.ID)
	if err != nil {
		t.Fatal(err)
	}
	if usage[0].RuntimeModels != nil {
		t.Fatalf("unreachable runtime must be nil: %+v", usage[0].RuntimeModels)
	}
}

// ---- M6 MCP 连接器 ----

// fakeMCP 假 MCP server：initialize → tools/list 两步（streamable HTTP JSON 形态）。
func fakeMCP(status int, tools []map[string]string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if status != 200 {
			w.WriteHeader(status)
			return
		}
		var req mcpRPCRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		var resp map[string]interface{}
		switch req.Method {
		case "initialize":
			resp = map[string]interface{}{"jsonrpc": "2.0", "id": 1,
				"result": map[string]interface{}{"protocolVersion": "2025-03-26",
					"capabilities": map[string]interface{}{}, "serverInfo": map[string]string{"name": "fake"}}}
		case "tools/list":
			resp = map[string]interface{}{"jsonrpc": "2.0", "id": 2,
				"result": map[string]interface{}{"tools": tools}}
		default:
			resp = map[string]interface{}{"jsonrpc": "2.0", "id": 3, "error": map[string]int{"code": -32601}}
		}
		_ = json.NewEncoder(w).Encode(resp)
	}))
}

func TestMcpConnectorCRUDAndVerify(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000aaa5", "mcp空间")

	srv := fakeMCP(200, []map[string]string{
		{"name": "ticket_query", "description": "查询"},
		{"name": "ticket_escalate", "description": "升级"},
	})
	defer srv.Close()

	view, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{
		Name: "ticket-system", Transport: "streamable_http", Endpoint: srv.URL,
		Permissions: json.RawMessage(`{"tools":{"ticket_query":"allow"}}`)})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	// 重名 400
	if _, err := c.CreateMcpConnector(ws.ID, CreateMcpConnectorInput{
		Name: "ticket-system", Endpoint: srv.URL}); err == nil {
		t.Fatal("duplicate should fail")
	}
	// verify：成功 → verified + 工具快照
	res, err := c.VerifyMcpConnector(ws.ID, view.ID)
	if err != nil {
		t.Fatalf("verify: %v", err)
	}
	if res["ok"] != true || res["toolCount"].(int) != 2 {
		t.Fatalf("verify res: %+v", res)
	}
	var m store.McpConnector
	c.DB.First(&m, view.ID)
	if m.Status != "verified" {
		t.Fatalf("status: %s", m.Status)
	}
	var scope struct {
		Mode  string   `json:"mode"`
		Tools []string `json:"tools"`
	}
	if err := jsonUnmarshalStrict(m.ToolScope, &scope); err != nil || len(scope.Tools) != 2 {
		t.Fatalf("toolScope snapshot: %q %v", m.ToolScope, err)
	}
	// verify：非 200 → failed
	badSrv := fakeMCP(500, nil)
	defer badSrv.Close()
	if _, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{
		Endpoint: &badSrv.URL}); err != nil {
		t.Fatal(err)
	}
	res2, _ := c.VerifyMcpConnector(ws.ID, view.ID)
	if res2["ok"] != false {
		t.Fatalf("bad verify: %+v", res2)
	}
	c.DB.First(&m, view.ID)
	if m.Status != "failed" {
		t.Fatalf("failed status: %s", m.Status)
	}
	// 超时分支（立即关闭的 server）
	dead := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	dead.Close()
	if _, err := c.UpdateMcpConnector(ws.ID, view.ID, PatchMcpConnectorInput{
		Endpoint: &dead.URL}); err != nil {
		t.Fatal(err)
	}
	res3, _ := c.VerifyMcpConnector(ws.ID, view.ID)
	if res3["ok"] != false || res3["error"] == "" {
		t.Fatalf("timeout branch: %+v", res3)
	}
	// refCount：造一个引用该连接器的服务（运行包 frontmatter 命中）
	pkg := uploadTestPkg(t, c, "")
	c.DB.Model(&store.OafPackage{}).Where("id = ?", pkg.ID).
		Update("manifest_json", `{"agentKey":"demo","mcpServers":[{"server":"ticket-system"}]}`)
	svc := &store.ServiceEntity{K8sName: "oaf-ref", DisplayName: "ref", PackageID: pkg.ID, WorkspaceID: ws.ID}
	c.DB.Create(svc)
	list, _ := c.ListMcpConnectors(ws.ID)
	if len(list) != 1 || list[0].RefCount != 1 {
		t.Fatalf("refCount: %+v", list)
	}
	// 被引用删除 → 409
	if err := c.DeleteMcpConnector(ws.ID, view.ID); err == nil || !strings.Contains(err.Error(), "connector_in_use") {
		t.Fatalf("delete in-use: %v", err)
	}
}

func joinJSONMaps(ms []map[string]interface{}) string {
	if len(ms) == 0 {
		return ""
	}
	parts := make([]string, 0, len(ms))
	for _, m := range ms {
		b, _ := json.Marshal(m)
		parts = append(parts, string(b))
	}
	return strings.Join(parts, ",")
}

// safeMutex 测试互斥（map 并发保护）。
func safeMutex() *sync.Mutex { return &sync.Mutex{} }

// TestModelConnBaseURLHostRequired issue #156："http://"/"https://" 无主机值必须 400，
// 不再漏过校验后于测试阶段报 no Host in request URL。
func TestModelConnBaseURLHostRequired(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000bbb2", "URL空间")
	for _, bad := range []string{"http://", "https://", "http:///path", "not-a-url", "ftp://x"} {
		if _, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
			Name: "bad-" + bad, ModelID: "m", BaseURL: bad}); err == nil {
			t.Fatalf("baseUrl %q should be rejected", bad)
		}
	}
	// PATCH 路径同样拦截
	v, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: "ok", ModelID: "m", BaseURL: "https://api.x/v1"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	hostless := "http://"
	if _, err := c.UpdateModelConnection(ws.ID, v.ID, PatchModelConnInput{BaseURL: &hostless}); err == nil {
		t.Fatal("patch to hostless URL should be rejected")
	}
	// 合法含主机 URL 通过
	if _, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: "ok2", ModelID: "m", BaseURL: "http://192.168.1.10:8000/v1"}); err != nil {
		t.Fatalf("valid host URL rejected: %v", err)
	}
}

// TestModelConnFieldLengthCaps issue #149：超长字段 400 而非 500 Data too long。
func TestModelConnFieldLengthCaps(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-1000ccc3", "长度空间")
	if _, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: strings.Repeat("x", 200), ModelID: "m", BaseURL: "https://a/v1"}); err == nil ||
		!strings.Contains(err.Error(), "name too long") {
		t.Fatalf("long name: %v", err)
	}
	if _, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: "ok", ModelID: strings.Repeat("m", 200), BaseURL: "https://a/v1"}); err == nil {
		t.Fatal("long modelId should fail")
	}
	if _, err := c.CreateModelConnection(ws.ID, CreateModelConnInput{
		Name: "ok3", ModelID: "m", BaseURL: "https://a/" + strings.Repeat("p", 600)}); err == nil {
		t.Fatal("long baseUrl should fail")
	}
}
