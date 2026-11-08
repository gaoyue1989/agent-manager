// U5（sandbox-pool-opensandbox-design §9.1）：OpenSandbox 管理面代理单测。
// httptest 模拟上游七端点：方法/路径/body/APIKey 透传断言；错误映射矩阵（501→503、
// 401→502、429/5xx 透传、上游超时/不可达→502）；createPool 校验矩阵与预设模板（d18）；
// console 只读过滤（metadata.service ∈ ws 服务集合）；管理操作写审计行（PlatformConfigEvent
// 同款先例，governance recordAudit 通路）。
package service

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"agent-manager/backend/internal/store"
)

// recReq 上游收到的单次请求记录。
type recReq struct {
	Method string
	Path   string
	Body   string
	APIKey string
}

// upstreamHarness httptest 模拟上游 + 请求记录。
type upstreamHarness struct {
	srv  *httptest.Server
	mu   sync.Mutex
	Reqs []recReq
}

// handle 包装 handler：先记录再委托。
func (h *upstreamHarness) handle(fn func(w http.ResponseWriter, r *http.Request, got recReq)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body := ""
		if r.Body != nil {
			b, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
			body = string(b)
		}
		got := recReq{Method: r.Method, Path: r.URL.RequestURI(), Body: body, APIKey: r.Header.Get("OPEN-SANDBOX-API-KEY")}
		h.mu.Lock()
		h.Reqs = append(h.Reqs, got)
		h.mu.Unlock()
		fn(w, r, got)
	}
}

// newProxyCore 组装：sqlite core + platform_config 指向 httptest 上游。
func newProxyCore(t *testing.T, extra map[string]string) (*Core, *upstreamHarness, func()) {
	t.Helper()
	core, _, done := newTestCore(t)
	h := &upstreamHarness{}
	h.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot) // 未注册路由兜底：测试应显式声明各端点行为
	}))
	values := map[string]string{
		"OPENSANDBOX_SERVER_URL": strings.TrimPrefix(h.srv.URL, "http://"),
		"OPENSANDBOX_API_KEY":    "sk-upstream-test",
	}
	for k, v := range extra {
		values[k] = v
	}
	if _, err := core.UpdatePlatformConfig(values); err != nil {
		t.Fatal(err)
	}
	return core, h, func() { h.srv.Close(); done() }
}

// statusWithBody 按路径前缀路由到固定 (status, body)（有序切片：前缀最长优先，防 map 序随机）。
func statusWithBody(routes map[string]func() (int, string)) http.HandlerFunc {
	prefixes := make([]string, 0, len(routes))
	for p := range routes {
		prefixes = append(prefixes, p)
	}
	// 降序排列：长前缀先匹配（/v1/pools/impl 优先于 /v1/pools）
	for i := 0; i < len(prefixes); i++ {
		for j := i + 1; j < len(prefixes); j++ {
			if len(prefixes[j]) > len(prefixes[i]) {
				prefixes[i], prefixes[j] = prefixes[j], prefixes[i]
			}
		}
	}
	return func(w http.ResponseWriter, r *http.Request) {
		uri := r.URL.RequestURI()
		for _, p := range prefixes {
			if strings.HasPrefix(uri, p) {
				code, body := routes[p]()
				w.WriteHeader(code)
				_, _ = w.Write([]byte(body))
				return
			}
		}
		w.WriteHeader(http.StatusNotFound)
	}
}

// auditKinds 读取治理审计行（service_id=0）的 kind 列表。
func auditKinds(t *testing.T, c *Core) []string {
	t.Helper()
	var rows []store.ServiceEvent
	if err := c.DB.Where("service_id = 0").Order("id ASC").Find(&rows).Error; err != nil {
		t.Fatal(err)
	}
	kinds := []string{}
	for _, r := range rows {
		var parsed map[string]interface{}
		if err := json.Unmarshal([]byte(r.Reason), &parsed); err != nil {
			t.Fatalf("audit reason not json: %v (%s)", err, r.Reason)
		}
		if k, ok := parsed["kind"].(string); ok {
			kinds = append(kinds, k)
		}
	}
	return kinds
}

const poolsJSON = `{"items":[{"name":"pool-a","capacitySpec":{"bufferMin":1,"bufferMax":2,"poolMin":1,"poolMax":3},"status":{"total":2,"allocated":1,"available":1,"revision":"r1"}}]}`
const sandboxPageJSON = `{"items":[{"id":"sb-1","status":{"state":"Running"},"metadata":{"service":"oaf-a","session_id":"s1"},"expiresAt":"2026-11-07T15:00:00Z","createdAt":"2026-11-07T14:00:00Z"}],"pagination":{"page":1,"pageSize":1,"totalItems":3,"totalPages":3,"hasNextPage":false}}`

// TestSandboxProxySevenEndpoints 七端点 happy path：方法/路径/body/APIKey 透传断言。
func TestSandboxProxySevenEndpoints(t *testing.T) {
	core, h, done := newProxyCore(t, nil)
	defer done()

	h.srv.Config.Handler = h.handle(func(w http.ResponseWriter, r *http.Request, got recReq) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/pools":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(poolsJSON))
		case r.Method == http.MethodGet && r.URL.Path == "/v1/pools/oaf-pool":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"name":"oaf-pool","capacitySpec":{"bufferMin":1,"bufferMax":2,"poolMin":1,"poolMax":3}}`))
		case r.Method == http.MethodPost && r.URL.Path == "/v1/pools":
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"name":"oaf-pool"}`))
		case r.Method == http.MethodPut && r.URL.Path == "/v1/pools/oaf-pool":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"name":"oaf-pool"}`))
		case r.Method == http.MethodDelete && r.URL.Path == "/v1/pools/oaf-pool":
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodGet && r.URL.Path == "/v1/sandboxes":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(sandboxPageJSON))
		case r.Method == http.MethodDelete && r.URL.Path == "/v1/sandboxes/sb-1":
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	})

	// GET /v1/pools
	if _, err := core.AdminSandboxPools(); err != nil {
		t.Fatalf("list pools: %v", err)
	}
	// GET /v1/pools/{name}
	if _, err := core.AdminGetSandboxPool("oaf-pool"); err != nil {
		t.Fatalf("get pool: %v", err)
	}
	// POST /v1/pools（显式 image）
	in := CreateSandboxPoolInput{
		Name: "oaf-pool", Image: "img-x:1",
		CapacitySpec: SandboxCapacitySpec{BufferMin: 1, BufferMax: 2, PoolMin: 1, PoolMax: 3},
	}
	if _, err := core.AdminCreateSandboxPool(in, 7); err != nil {
		t.Fatalf("create pool: %v", err)
	}
	// PUT /v1/pools/{name}（仅 capacitySpec）
	if _, err := core.AdminUpdateSandboxPool("oaf-pool", SandboxCapacitySpec{BufferMin: 2, BufferMax: 3, PoolMin: 2, PoolMax: 4}, 7); err != nil {
		t.Fatalf("update pool: %v", err)
	}
	// DELETE /v1/pools/{name}
	if err := core.AdminDeleteSandboxPool("oaf-pool", 7); err != nil {
		t.Fatalf("delete pool: %v", err)
	}
	// GET /v1/sandboxes?state=&page=&pageSize=
	if _, err := core.AdminListSandboxes("Running", 2, 50); err != nil {
		t.Fatalf("list sandboxes: %v", err)
	}
	// DELETE /v1/sandboxes/{id}
	if err := core.AdminDeleteSandbox("sb-1", 7); err != nil {
		t.Fatalf("delete sandbox: %v", err)
	}

	h.mu.Lock()
	reqCount := len(h.Reqs)
	h.mu.Unlock()
	if reqCount != 7 {
		t.Fatalf("upstream requests = %d, want 7", reqCount)
	}
	// summary：pools Σavailable/allocated + Running totalItems（先解锁再打上游，防 harness 锁死锁）
	sum, err := core.AdminSandboxSummary()
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if sum.Pools != 1 || sum.WarmAvailable != 1 || sum.Allocated != 1 || sum.RunningSandboxes != 3 {
		t.Fatalf("summary = %+v", sum)
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for i, got := range h.Reqs {
		if got.APIKey != "sk-upstream-test" {
			t.Fatalf("req[%d] API key header missing/wrong: %q", i, got.APIKey)
		}
	}
	// POST body：name/template/capacitySpec；评审 d7 —— recycleStrategy 不是上游请求字段，不得出现
	createBody := h.Reqs[2]
	if createBody.Method != http.MethodPost || createBody.Path != "/v1/pools" {
		t.Fatalf("create path = %s %s", createBody.Method, createBody.Path)
	}
	var payload map[string]interface{}
	if err := json.Unmarshal([]byte(createBody.Body), &payload); err != nil {
		t.Fatalf("create body not json: %v", err)
	}
	if payload["name"] != "oaf-pool" {
		t.Fatalf("create body name = %v", payload["name"])
	}
	if _, ok := payload["recycleStrategy"]; ok {
		t.Fatal("create body must not carry recycleStrategy (d7: upstream ignores it silently)")
	}
	if _, ok := payload["template"]; !ok {
		t.Fatal("create body must carry preset template")
	}
	// PUT body：仅 capacitySpec
	var putBody map[string]interface{}
	if err := json.Unmarshal([]byte(h.Reqs[3].Body), &putBody); err != nil {
		t.Fatalf("put body not json: %v", err)
	}
	if len(putBody) != 1 || putBody["capacitySpec"] == nil {
		t.Fatalf("put body must be capacitySpec-only, got %v", putBody)
	}
	// 列表查询串翻译为上游 page/pageSize（评审 d15：limit/offset 上游不存在）
	listPath := h.Reqs[5].Path
	if !strings.Contains(listPath, "state=Running") || !strings.Contains(listPath, "page=2") || !strings.Contains(listPath, "pageSize=50") {
		t.Fatalf("list query not translated to page/pageSize: %s", listPath)
	}
}

// TestSandboxProxyPresetTemplate 预设模板生成（d18：官方 sample 全量复刻，image 参数只换主容器镜像）。
func TestSandboxProxyPresetTemplate(t *testing.T) {
	// recycleStrategy 枚举校验（设计 §5.2，黑盒第 1 轮实锤契约偏差）：
	// 合法值（Delete/Restart/Noop/缺省）接受但不透传上游；非法值 400
	{
		core, h, done := newProxyCore(t, nil)
		defer done()
		h.srv.Config.Handler = h.handle(func(w http.ResponseWriter, r *http.Request, got recReq) {
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"name":"p-r"}`))
		})
		for _, strategy := range []string{"", "Delete", "Restart", "Noop"} {
			if _, err := core.AdminCreateSandboxPool(CreateSandboxPoolInput{
				Name: "p-r", RecycleStrategy: strategy,
				CapacitySpec: SandboxCapacitySpec{BufferMin: 1, BufferMax: 2, PoolMin: 1, PoolMax: 3},
			}, 0); err != nil {
				t.Fatalf("recycleStrategy %q 应合法: %v", strategy, err)
			}
		}
		// 合法值不透传上游：转发 body 不含 recycleStrategy 键（d7：上游 CreatePoolRequest 不收）
		if strings.Contains(h.Reqs[0].Body, "recycleStrategy") {
			t.Fatalf("合法 recycleStrategy 不应透传上游: %s", h.Reqs[0].Body)
		}
		if _, err := core.AdminCreateSandboxPool(CreateSandboxPoolInput{
			Name: "p-r", RecycleStrategy: "Bogus",
			CapacitySpec: SandboxCapacitySpec{BufferMin: 1, BufferMax: 2, PoolMin: 1, PoolMax: 3},
		}, 0); !errors.Is(err, ErrInvalidForm) {
			t.Fatalf("recycleStrategy Bogus 应 400 ErrInvalidForm, got %v", err)
		}
	}
	// image 缺省 → 取平台默认 SANDBOX_IMAGE
	core, h, done := newProxyCore(t, map[string]string{"SANDBOX_IMAGE": "reg.example.com/ci:v9"})
	defer done()
	h.srv.Config.Handler = h.handle(func(w http.ResponseWriter, r *http.Request, got recReq) {
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"name":"p1"}`))
	})
	if _, err := core.AdminCreateSandboxPool(CreateSandboxPoolInput{
		Name: "p1", CapacitySpec: SandboxCapacitySpec{BufferMin: 1, BufferMax: 2, PoolMin: 1, PoolMax: 3},
	}, 0); err != nil {
		t.Fatal(err)
	}
	assertPreset := func(t *testing.T, body string, wantImage string) {
		t.Helper()
		var payload struct {
			Template struct {
				Spec struct {
					Containers []struct {
						Name    string   `json:"name"`
						Image   string   `json:"image"`
						Command []string `json:"command"`
						Args    []string `json:"args"`
						Env     []struct {
							Name  string `json:"name"`
							Value string `json:"value"`
						} `json:"env"`
					} `json:"containers"`
					InitContainers []struct {
						Name  string `json:"name"`
						Image string `json:"image"`
					} `json:"initContainers"`
					Volumes     []map[string]interface{} `json:"volumes"`
					Tolerations []map[string]interface{} `json:"tolerations"`
				} `json:"spec"`
			} `json:"template"`
		}
		if err := json.Unmarshal([]byte(body), &payload); err != nil {
			t.Fatalf("payload not json: %v", err)
		}
		tpl := payload.Template.Spec
		if len(tpl.Containers) != 1 || tpl.Containers[0].Name != "sandbox" {
			t.Fatalf("main container = %+v", tpl.Containers)
		}
		main := tpl.Containers[0]
		if main.Image != wantImage {
			t.Fatalf("main image = %q, want %q", main.Image, wantImage)
		}
		// 官方 sample 三要素：task-executor 监听命令 / SANDBOX_MAIN_CONTAINER+EXECD_ENVS+EXECD env / installer×2
		joined := strings.Join(main.Args, " ")
		if !strings.Contains(joined, "task-executor -listen-addr=0.0.0.0:5758") {
			t.Fatalf("main command missing task-executor listener: %s", joined)
		}
		envs := map[string]string{}
		for _, e := range main.Env {
			envs[e.Name] = e.Value
		}
		for _, k := range []string{"SANDBOX_MAIN_CONTAINER", "EXECD_ENVS", "EXECD"} {
			if envs[k] == "" {
				t.Fatalf("main container env missing %s (d18: official sample fields must be replicated)", k)
			}
		}
		if len(tpl.InitContainers) != 2 {
			t.Fatalf("initContainers = %d, want 2 (task-executor-installer + execd-installer)", len(tpl.InitContainers))
		}
		if len(tpl.Volumes) < 3 {
			t.Fatalf("volumes = %d, want 3 (emptyDir trio)", len(tpl.Volumes))
		}
		if len(tpl.Tolerations) == 0 {
			t.Fatal("tolerations missing (official sample)")
		}
	}
	h.mu.Lock()
	if len(h.Reqs) != 1 {
		t.Fatalf("requests = %d", len(h.Reqs))
	}
	assertPreset(t, h.Reqs[0].Body, "reg.example.com/ci:v9")
	h.mu.Unlock()

	// 显式 image 优先
	if _, err := core.AdminCreateSandboxPool(CreateSandboxPoolInput{
		Name: "p1", Image: "custom:2", CapacitySpec: SandboxCapacitySpec{BufferMin: 1, BufferMax: 2, PoolMin: 1, PoolMax: 3},
	}, 0); err != nil {
		t.Fatal(err)
	}
	h.mu.Lock()
	if len(h.Reqs) != 2 {
		t.Fatalf("requests = %d", len(h.Reqs))
	}
	assertPreset(t, h.Reqs[1].Body, "custom:2")
	h.mu.Unlock()
}

// TestSandboxProxyValidation createPool 校验矩阵：name DNS label + capacitySpec 四值链。
func TestSandboxProxyValidation(t *testing.T) {
	core, _, done := newProxyCore(t, nil)
	defer done()
	cases := []struct {
		desc string
		in   CreateSandboxPoolInput
	}{
		{"empty name", CreateSandboxPoolInput{CapacitySpec: SandboxCapacitySpec{1, 2, 1, 3}}},
		{"uppercase name", CreateSandboxPoolInput{Name: "Bad_Name", CapacitySpec: SandboxCapacitySpec{1, 2, 1, 3}}},
		{"bufferMin below poolMin", CreateSandboxPoolInput{Name: "p", CapacitySpec: SandboxCapacitySpec{BufferMin: 0, BufferMax: 2, PoolMin: 1, PoolMax: 3}}},
		{"bufferMax below bufferMin", CreateSandboxPoolInput{Name: "p", CapacitySpec: SandboxCapacitySpec{BufferMin: 2, BufferMax: 1, PoolMin: 1, PoolMax: 3}}},
		{"poolMax below bufferMax", CreateSandboxPoolInput{Name: "p", CapacitySpec: SandboxCapacitySpec{BufferMin: 1, BufferMax: 3, PoolMin: 1, PoolMax: 2}}},
		{"negative value", CreateSandboxPoolInput{Name: "p", CapacitySpec: SandboxCapacitySpec{BufferMin: -1, BufferMax: 2, PoolMin: 0, PoolMax: 3}}},
	}
	for _, tc := range cases {
		if _, err := core.AdminCreateSandboxPool(tc.in, 0); err == nil {
			t.Fatalf("%s: expected 400", tc.desc)
		}
	}
	// 容量更新同链
	if _, err := core.AdminUpdateSandboxPool("p", SandboxCapacitySpec{BufferMin: 5, BufferMax: 2, PoolMin: 1, PoolMax: 3}, 0); err == nil {
		t.Fatal("update pool: chain violation must 400")
	}
	// 沙箱 id / 池名路径注入拒绝
	if err := core.AdminDeleteSandbox("../etc", 0); err == nil {
		t.Fatal("path traversal id must 400")
	}
}

// TestSandboxProxyErrorMapping 错误映射矩阵（§5.1，d19：按状态码判定）。
func TestSandboxProxyErrorMapping(t *testing.T) {
	core, h, done := newProxyCore(t, nil)
	defer done()
	h.srv.Config.Handler = statusWithBody(map[string]func() (int, string){
		"/v1/pools/impl": func() (int, string) { return 501, `{"code":"K8S_POOL_NOT_SUPPORTED"}` },
		"/v1/pools/auth": func() (int, string) { return 401, `{"message":"no key"}` },
		"/v1/pools/busy": func() (int, string) { return 429, `{"code":"KUBERNETES::POOL_CAPACITY_EXHAUSTED"}` },
		"/v1/pools/boom": func() (int, string) { return 500, `{"message":"internal"}` },
		"/v1/pools/dupe": func() (int, string) { return 409, `{"message":"exists"}` },
		"/v1/pools":      func() (int, string) { return 200, poolsJSON },
	})
	// 501 → SandboxUpstreamError{503, kubernetes runtime 提示}
	_, err := core.AdminGetSandboxPool("impl")
	ue, ok := err.(*SandboxUpstreamError)
	if !ok || ue.Status != 503 || !strings.Contains(ue.Msg, "kubernetes") {
		t.Fatalf("501 mapping = %v", err)
	}
	// 401 → SandboxUpstreamError{502, 指向 /settings}
	_, err = core.AdminGetSandboxPool("auth")
	ue, ok = err.(*SandboxUpstreamError)
	if !ok || ue.Status != 502 || !strings.Contains(ue.Msg, "/settings") {
		t.Fatalf("401 mapping = %v", err)
	}
	// 429 透传 429；500 透传 500；409 透传 409
	for name, want := range map[string]int{"busy": 429, "boom": 500, "dupe": 409} {
		_, err := core.AdminGetSandboxPool(name)
		ue, ok := err.(*SandboxUpstreamError)
		if !ok || ue.Status != want {
			t.Fatalf("%s passthrough = %v, want status %d", name, err, want)
		}
	}
	// 上游不可达（连接拒绝）→ 502
	core2, _, done2 := newProxyCore(t, nil)
	defer done2()
	if _, err := core2.UpdatePlatformConfig(map[string]string{"OPENSANDBOX_SERVER_URL": "127.0.0.1:1"}); err != nil {
		t.Fatal(err)
	}
	_, err = core2.AdminSandboxPools()
	ue, ok = err.(*SandboxUpstreamError)
	if !ok || ue.Status != 502 {
		t.Fatalf("unreachable mapping = %v", err)
	}
}

// TestSandboxProxyUpstreamTimeout 上游超时 → 502（GET 5s 超时纪律）。
func TestSandboxProxyUpstreamTimeout(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 5s 超时用例")
	}
	core, h, done := newProxyCore(t, nil)
	defer done()
	release := make(chan struct{})
	h.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release // 挂住直至超时
	})
	start := time.Now()
	_, err := core.AdminSandboxPools()
	close(release)
	ue, ok := err.(*SandboxUpstreamError)
	if !ok || ue.Status != 502 {
		t.Fatalf("timeout mapping = %v", err)
	}
	if el := time.Since(start); el < 4*time.Second {
		t.Fatalf("returned too early (%v): upstream timeout discipline not applied", el)
	}
}

// TestSandboxProxyNotConfigured URL / APIKey 任一缺失 → ErrSandboxNotConfigured（handler 映射 503）。
func TestSandboxProxyNotConfigured(t *testing.T) {
	core, _, done := newProxyCore(t, nil)
	defer done()
	// OPENSANDBOX_SERVER_URL 为 Required 键（API 拒绝清除），直删 DB 行模拟「未配置」
	clearKey := func(t *testing.T, key string) {
		t.Helper()
		if err := core.DB.Where("env_key = ?", key).Delete(&store.PlatformConfigEntity{}).Error; err != nil {
			t.Fatal(err)
		}
	}
	clearKey(t, "OPENSANDBOX_SERVER_URL")
	if _, err := core.AdminSandboxPools(); err != ErrSandboxNotConfigured {
		t.Fatalf("missing URL = %v, want ErrSandboxNotConfigured", err)
	}
	// 恢复 URL、清空 APIKey（非 Required，可直删）
	if _, err := core.UpdatePlatformConfig(map[string]string{"OPENSANDBOX_SERVER_URL": "127.0.0.1:8090"}); err != nil {
		t.Fatal(err)
	}
	clearKey(t, "OPENSANDBOX_API_KEY")
	if _, err := core.AdminSandboxPools(); err != ErrSandboxNotConfigured {
		t.Fatalf("missing API key = %v, want ErrSandboxNotConfigured", err)
	}
	// URL 无 scheme 自动补 http://（平台配置形态 host:port）
	if _, err := core.UpdatePlatformConfig(map[string]string{"OPENSANDBOX_API_KEY": "k"}); err != nil {
		t.Fatal(err)
	}
	cfg, err := core.sandboxProxyConfig()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cfg.base, "http://") || strings.HasSuffix(cfg.base, "/") {
		t.Fatalf("base url normalized wrong: %q", cfg.base)
	}
}

// seedWsService 直插 workspace 服务行（console 过滤测试造数）。
func seedWsService(t *testing.T, c *Core, wsID uint, k8sName string) {
	t.Helper()
	if err := c.DB.Create(&store.ServiceEntity{K8sName: k8sName, DisplayName: k8sName, WorkspaceID: wsID}).Error; err != nil {
		t.Fatal(err)
	}
}

// TestConsoleSandboxInstancesFilter console 只读过滤：metadata.service ∈ ws 服务集合
// （oaf- 前缀 K8sName 口径，d4）；跨空间与他 metadata 的实例被滤除；只读字段投影。
func TestConsoleSandboxInstancesFilter(t *testing.T) {
	core, h, done := newProxyCore(t, nil)
	defer done()
	seedWsService(t, core, 1, "oaf-a")
	seedWsService(t, core, 1, "oaf-b")
	seedWsService(t, core, 2, "oaf-c") // 他工作空间

	h.srv.Config.Handler = h.handle(func(w http.ResponseWriter, r *http.Request, got recReq) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"items":[
			{"id":"s1","status":{"state":"Running"},"metadata":{"service":"oaf-a","session_id":"sess-1"},"expiresAt":"2026-11-07T15:00:00Z","createdAt":"2026-11-07T14:00:00Z"},
			{"id":"s2","status":{"state":"Running"},"metadata":{"service":"oaf-c","session_id":"sess-2"},"expiresAt":"2026-11-07T15:00:00Z","createdAt":"2026-11-07T14:00:00Z"},
			{"id":"s3","status":{"state":"Pending"},"metadata":{"service":"oaf-b"},"expiresAt":"2026-11-07T15:30:00Z","createdAt":"2026-11-07T14:10:00Z"},
			{"id":"s4","status":{"state":"Running"},"expiresAt":"2026-11-07T15:00:00Z","createdAt":"2026-11-07T14:00:00Z"},
			{"id":"s5","status":{"state":"Running"},"metadata":{"service":"oaf-a","session_id":"sess-5"},"expiresAt":"2026-11-07T16:00:00Z","createdAt":"2026-11-07T14:20:00Z"}
		],"pagination":{"page":1,"pageSize":200,"totalItems":5,"totalPages":1,"hasNextPage":false}}`))
	})
	list, err := core.ConsoleSandboxInstances(1)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 3 {
		t.Fatalf("filtered instances = %d, want 3 (oaf-a ×2 + oaf-b): %+v", len(list), list)
	}
	ids := map[string]bool{}
	for _, it := range list {
		ids[it.ID] = true
		if it.State == "" || it.CreatedAt == "" {
			t.Fatalf("instance missing read-only projection: %+v", it)
		}
		if it.Service == "oaf-c" {
			t.Fatalf("cross-workspace service leaked: %+v", it)
		}
	}
	if !ids["s1"] || !ids["s3"] || !ids["s5"] || ids["s2"] || ids["s4"] {
		t.Fatalf("filter result wrong: %v", ids)
	}
	// 只读投影字段核对（id/state/service/sessionId/expiresAt/createdAt）
	first := list[0]
	var raw map[string]interface{}
	b, _ := json.Marshal(first)
	if err := json.Unmarshal(b, &raw); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"id", "state", "service", "sessionId", "expiresAt", "createdAt"} {
		if _, ok := raw[k]; !ok && !(k == "sessionId" && first.SessionID == "") {
			t.Fatalf("projection missing %s: %s", k, b)
		}
	}
	for _, forbidden := range []string{"image", "entrypoint", "extensions", "allocation", "status"} {
		if _, ok := raw[forbidden]; ok {
			t.Fatalf("read-only view leaked field %s: %s", forbidden, b)
		}
	}
}

// TestConsoleSandboxInstancesPagination 多页拉取：hasNextPage 翻页直至收齐（POC 内存过滤，封顶 10 页）。
func TestConsoleSandboxInstancesPagination(t *testing.T) {
	core, h, done := newProxyCore(t, nil)
	defer done()
	seedWsService(t, core, 1, "oaf-a")
	pages := 0
	h.srv.Config.Handler = h.handle(func(w http.ResponseWriter, r *http.Request, got recReq) {
		pages++
		hasNext := pages < 3
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(fmt.Sprintf(`{"items":[{"id":"p%d-sb","status":{"state":"Running"},"metadata":{"service":"oaf-a"}}],"pagination":{"page":%d,"pageSize":200,"totalItems":3,"totalPages":3,"hasNextPage":%t}}`, pages, pages, hasNext)))
	})
	list, err := core.ConsoleSandboxInstances(1)
	if err != nil {
		t.Fatal(err)
	}
	if pages != 3 || len(list) != 3 {
		t.Fatalf("pages=%d items=%d, want 3/3", pages, len(list))
	}
}

// TestSandboxAdminAuditTrail 管理操作写审计行（PlatformConfigEvent 同款先例；§10/m12-3）。
func TestSandboxAdminAuditTrail(t *testing.T) {
	core, h, done := newProxyCore(t, nil)
	defer done()
	h.srv.Config.Handler = h.handle(func(w http.ResponseWriter, r *http.Request, got recReq) {
		switch r.Method {
		case http.MethodPost:
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{}`))
		case http.MethodPut:
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{}`))
		default:
			w.WriteHeader(http.StatusNoContent)
		}
	})
	if _, err := core.AdminCreateSandboxPool(CreateSandboxPoolInput{
		Name: "p1", CapacitySpec: SandboxCapacitySpec{BufferMin: 1, BufferMax: 2, PoolMin: 1, PoolMax: 3},
	}, 42); err != nil {
		t.Fatal(err)
	}
	if _, err := core.AdminUpdateSandboxPool("p1", SandboxCapacitySpec{BufferMin: 2, BufferMax: 2, PoolMin: 1, PoolMax: 3}, 42); err != nil {
		t.Fatal(err)
	}
	if err := core.AdminDeleteSandboxPool("p1", 42); err != nil {
		t.Fatal(err)
	}
	if err := core.AdminDeleteSandbox("sb-9", 42); err != nil {
		t.Fatal(err)
	}
	kinds := auditKinds(t, core)
	want := []string{"sandbox.pool.create", "sandbox.pool.update", "sandbox.pool.delete", "sandbox.sandbox.delete"}
	if len(kinds) != len(want) {
		t.Fatalf("audit kinds = %v, want %v", kinds, want)
	}
	for i, k := range want {
		if kinds[i] != k {
			t.Fatalf("audit kinds[%d] = %s, want %s", i, kinds[i], k)
		}
	}
	// 只读操作不产生审计
	before := len(auditKinds(t, core))
	if _, err := core.AdminSandboxPools(); err != nil {
		t.Fatal(err)
	}
	if _, err := core.AdminListSandboxes("", 1, 20); err != nil {
		t.Fatal(err)
	}
	if after := len(auditKinds(t, core)); after != before {
		t.Fatalf("read-only ops must not audit: %d → %d", before, after)
	}
}
