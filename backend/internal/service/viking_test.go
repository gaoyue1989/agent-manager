package service

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"agent-manager/backend/internal/store"
)

// 派生公式跨端对齐向量（openviking-memory-integration-design §4；Go/Java 同值，
// Java 侧断言见 VikingMemoryClientTest.DERIVED_KEY_VECTOR）。PoC 261116 实测该公式
// 与 OpenViking 服务端返回逐字节一致。
const vikingDeriveVector = "b2FmLWRlbW8tc3Zj.dS0xMDAx.MTE3YzBlYzBiNjhkZjdhMDliNDQyOTk1MjMzMTlmNDY2ZDRmNGM2OGY0YzM5ZDg4ZTY3OGE5MDhlNmU0ZDU0Mw"

func TestDeriveVikingUserKey(t *testing.T) {
	got := DeriveVikingUserKey("oaf-demo-svc", "u-1001", "test-seed-123")
	if got != vikingDeriveVector {
		t.Fatalf("derive mismatch:\n got %s\nwant %s", got, vikingDeriveVector)
	}
	if again := DeriveVikingUserKey("oaf-demo-svc", "u-1001", "test-seed-123"); again != got {
		t.Fatalf("derive not deterministic")
	}
	if DeriveVikingUserKey("oaf-demo-svc", "u-1001", "other") == got {
		t.Fatalf("different seed must yield different key")
	}
}

func TestVikingAccountID(t *testing.T) {
	if got := VikingAccountID("oaf-my-svc-1"); got != "oaf-my-svc-1" {
		t.Fatalf("account id must reuse k8s name verbatim, got %q", got)
	}
}

// fakeOpenViking 模拟 Admin API、数据面 content/write 与记忆浏览 ls/read（PoC 实测
// 响应形状），记录收到的 account 建户数、write URI 与 browse 入参 URI 供断言。未覆盖
// 路径一律 404（测试内显式）。ls/read 条目 uri 模仿真机返回本人展开形态
// viking://user/u-1001/…；plan.md 的 size 取 2^53+1 探测出参重序列化数字精度。
type fakeOpenViking struct {
	mu        sync.Mutex
	creates   int
	writeURI  []string
	browseURI []string
}

func (f *fakeOpenViking) recordCreate() { f.mu.Lock(); f.creates++; f.mu.Unlock() }
func (f *fakeOpenViking) recordWrite(uri string) {
	f.mu.Lock(); f.writeURI = append(f.writeURI, uri); f.mu.Unlock()
}
func (f *fakeOpenViking) recordBrowse(uri string) {
	f.mu.Lock(); f.browseURI = append(f.browseURI, uri); f.mu.Unlock()
}
func (f *fakeOpenViking) createCount() int { f.mu.Lock(); defer f.mu.Unlock(); return f.creates }
func (f *fakeOpenViking) uris() []string   { f.mu.Lock(); defer f.mu.Unlock(); return append([]string{}, f.writeURI...) }
func (f *fakeOpenViking) browseURIs() []string {
	f.mu.Lock(); defer f.mu.Unlock(); return append([]string{}, f.browseURI...)
}
func (f *fakeOpenViking) resetBrowse() { f.mu.Lock(); f.browseURI = nil; f.mu.Unlock() }

func (f *fakeOpenViking) handler(okWrites bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/admin/accounts" && r.Method == http.MethodPost:
			f.recordCreate()
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok",
				"result": map[string]string{"user_key": "ak-test"}})
		case strings.HasSuffix(r.URL.Path, "/users") && r.Method == http.MethodPost:
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok",
				"result": map[string]string{"user_key": "x"}})
		case r.URL.Path == "/api/v1/content/write":
			var body struct {
				URI string `json:"uri"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			f.recordWrite(body.URI)
			if okWrites {
				_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok",
					"result": map[string]string{}})
			} else {
				w.WriteHeader(http.StatusBadGateway)
			}
		case r.URL.Path == "/api/v1/fs/ls" && r.Method == http.MethodGet:
			f.recordBrowse(r.URL.Query().Get("uri"))
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok", "result": []map[string]any{
				{"uri": "viking://user/u-1001/memories/plan.md", "size": 9007199254740993,
					"isDir": false, "modTime": "2026-11-18T10:00:00+08:00", "abstract": "user u-1001 的计划摘要"},
				{"uri": "viking://user/u-1001/memories/sessions", "size": 0,
					"isDir": true, "modTime": "2026-11-18T10:00:00+08:00", "abstract": ""},
			}})
		case r.URL.Path == "/api/v1/content/read" && r.Method == http.MethodGet:
			f.recordBrowse(r.URL.Query().Get("uri"))
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok", "result": "# 计划\n正文内容"})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}
}

func newVikingTestCore(t *testing.T, h http.HandlerFunc) (*Core, string) {
	t.Helper()
	core, _, cleanup := newTestCore(t)
	t.Cleanup(cleanup)
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	core.Cfg.OpenVikingEnabled = true
	core.Viking = NewVikingAdmin(srv.URL, "root-key-test")
	return core, srv.URL
}

// applyOpenVikingEnv 行为矩阵（Publish/UpdateEnv/Republish 共用同一函数）。
func TestApplyOpenVikingEnvMatrix(t *testing.T) {
	t.Run("platform disabled zero touch", func(t *testing.T) {
		c := &Core{} // Viking=nil
		plain, secret := map[string]string{}, map[string]string{}
		if err := c.applyOpenVikingEnv("oaf-svc", nil, plain, secret); err != nil {
			t.Fatalf("disabled + no explicit provider must be zero touch: %v", err)
		}
		if len(plain) != 0 || len(secret) != 0 {
			t.Fatalf("env mutated: %v %v", plain, secret)
		}
	})

	t.Run("platform disabled explicit openviking rejected", func(t *testing.T) {
		c := &Core{}
		plain := map[string]string{AgentMemoryProviderEnv: MemoryProviderOpenViking}
		if err := c.applyOpenVikingEnv("oaf-svc", nil, plain, map[string]string{}); err == nil {
			t.Fatalf("explicit provider without platform config must fail")
		}
	})

	t.Run("explicit local opt-out", func(t *testing.T) {
		c := &Core{Viking: NewVikingAdmin("http://unused", "k")} // 退出路径不触网
		plain := map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}
		secret := map[string]string{}
		if err := c.applyOpenVikingEnv("oaf-svc", nil, plain, secret); err != nil {
			t.Fatalf("explicit local: %v", err)
		}
		if len(plain) != 1 || len(secret) != 0 {
			t.Fatalf("opt-out must not inject anything: %v %v", plain, secret)
		}
	})

	t.Run("invalid provider rejected", func(t *testing.T) {
		c := &Core{Viking: NewVikingAdmin("http://unused", "k")}
		plain := map[string]string{AgentMemoryProviderEnv: "redis"}
		if err := c.applyOpenVikingEnv("oaf-svc", nil, plain, map[string]string{}); err == nil {
			t.Fatalf("invalid provider must fail")
		}
	})
}

// 发布端到端（平台启用默认接管）：建户 → env/Secret 注入 → knowledge 导入 → 幂等 UpdateEnv。
func TestPublishWithOpenVikingProvision(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler(true))

	zip := buildTestZip(map[string]string{
		"AGENTS.md":          testAgentsMD,
		"knowledge/guide.md": "# 指南\n平台发布流程说明",
		"skills/greet/SKILL.md": "---\nname: greet\ndescription: 打招呼技能\n---\n\n说你好。",
	})
	pkg, err := core.Packages.Upload("demo.zip", bytesReader(zip))
	if err != nil {
		t.Fatalf("upload pkg: %v", err)
	}

	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "svc",
		Env: map[string]string{OvkKnowledgeImportEnv: "true", OvkSharedSkillsEnv: "true"}})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if fake.createCount() != 1 {
		t.Fatalf("provision must create account once, got %d", fake.createCount())
	}
	var got store.ServiceEntity
	if err := core.DB.First(&got, svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	var plain, secret map[string]string
	_ = json.Unmarshal([]byte(got.EnvJSON), &plain)
	_ = json.Unmarshal([]byte(got.EnvSecretJSON), &secret)
	if plain[AgentMemoryProviderEnv] != MemoryProviderOpenViking ||
		plain[OpenVikingURLEnv] != baseURL ||
		plain[OpenVikingAccountEnv] != "oaf-svc" {
		t.Fatalf("plain env injection incomplete: %v", plain)
	}
	if secret[OvkAdminKeyEnv] != "ak-test" || secret[OvkUserSeedEnv] == "" {
		t.Fatalf("secret injection incomplete: %v", secret)
	}
	seed := secret[OvkUserSeedEnv]
	wantURI := "viking://resources/" + pkg.Slug + "/guide.md"
	wantSkill := "viking://agent/skills/" + pkg.Slug + "/greet/SKILL.md"
	uris := fake.uris()
	if len(uris) != 2 || uris[0] != wantURI || uris[1] != wantSkill {
		t.Fatalf("import URIs = %v, want [%s %s]", uris, wantURI, wantSkill)
	}

	waitForVikingTerminal(t, core, svc.ID)
	if _, err := core.UpdateEnv(svc.ID, map[string]string{"FOO": "bar"}, nil); err != nil {
		t.Fatalf("update env: %v", err)
	}
	if fake.createCount() != 1 {
		t.Fatalf("update env must skip provisioning, creates=%d", fake.createCount())
	}
	var after store.ServiceEntity
	if err := core.DB.First(&after, svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	var secretAfter, plainAfter map[string]string
	_ = json.Unmarshal([]byte(after.EnvSecretJSON), &secretAfter)
	_ = json.Unmarshal([]byte(after.EnvJSON), &plainAfter)
	if secretAfter[OvkUserSeedEnv] != seed {
		t.Fatalf("seed must be sticky across update env")
	}
	// UpdateEnv 全量覆盖后 OpenViking 派生键必须被补回（provider 切换可逆的前提）
	if plainAfter[AgentMemoryProviderEnv] != MemoryProviderOpenViking ||
		plainAfter[OpenVikingURLEnv] != baseURL {
		t.Fatalf("derived keys must be re-derived on update env: %v", plainAfter)
	}
}

func TestIssueMemoryKey(t *testing.T) {
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler(true))
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "svc"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}

	// IssueMemoryKey 无状态机门槛，发布落库即可签发
	key, err := core.IssueMemoryKey(svc.ID, "u-1001")
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	var secret map[string]string
	_ = json.Unmarshal([]byte(svc.EnvSecretJSON), &secret)
	if key != DeriveVikingUserKey("oaf-svc", "u-1001", secret[OvkUserSeedEnv]) {
		t.Fatalf("issued key mismatch derivation")
	}
	// 同参数重复签发 = 同 key（确定性派生）
	if again, err := core.IssueMemoryKey(svc.ID, "u-1001"); err != nil || again != key {
		t.Fatalf("re-issue must be stable: %q %v", again, err)
	}
	if _, err := core.IssueMemoryKey(svc.ID, "bad id!"); err == nil {
		t.Fatalf("illegal user id must fail")
	}
}

func TestDeleteRemovesAccount(t *testing.T) {
	var deletes atomicCounter
	core, _ := newVikingTestCore(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete && r.URL.Path == "/api/v1/admin/accounts/oaf-svc" {
			deletes.add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok", "result": nil})
			return
		}
		(&fakeOpenViking{}).handler(true)(w, r)
	})
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "svc"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if err := core.Delete(svc.ID); err != nil {
		t.Fatalf("delete: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for deletes.load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if deletes.load() != 1 {
		t.Fatalf("delete must remove openviking account, deletes=%d", deletes.load())
	}
}

// OpenViking 故障不阻断删除（best-effort 语义）。
func TestDeleteToleratesVikingOutage(t *testing.T) {
	core, _ := newVikingTestCore(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		(&fakeOpenViking{}).handler(true)(w, r)
	})
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "svc"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if err := core.Delete(svc.ID); err != nil {
		t.Fatalf("service delete must not fail on viking outage: %v", err)
	}
}

// waitForVikingTerminal 等待发布异步推进到任一终态（fake k8s 就绪时序不定：
// running / deploy_failed / register_failed 均可作为 UpdateEnv 等操作的起点）。
func waitForVikingTerminal(t *testing.T, c *Core, id uint) store.ServiceEntity {
	t.Helper()
	terminal := map[string]bool{
		store.StatusRunning: true, store.StatusDeployFailed: true, store.StatusRegisterFailed: true,
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		svc, err := c.Get(id)
		if err != nil {
			t.Fatal(err)
		}
		if terminal[svc.Status] {
			return *svc
		}
		if time.Now().After(deadline) {
			t.Fatalf("service not terminal in time, got %q", svc.Status)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// atomicCounter 最小计数器（atomic.Int32 等价，避免测试内多 import 版本差异）。
type atomicCounter struct {
	mu sync.Mutex
	n  int
}

func (c *atomicCounter) add(_ int) { c.mu.Lock(); c.n++; c.mu.Unlock() }
func (c *atomicCounter) load() int { c.mu.Lock(); defer c.mu.Unlock(); return c.n }

// UpdateEnv 全量覆盖回显含保留键不再死锁（真机验证发现的实现缺陷）：剥离后 provider
// 切换成功、派生键重建；平台未启用时显式设置注入键仍被拒绝（恶意值进不来）。
func TestUpdateEnvSwitchProviderWithReservedEcho(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler(true))
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "svc"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)

	// 回显场景:现有 env + 保留键原样带上（前端全量覆盖 UX）+ 切 local
	var plain, secret map[string]string
	var rec store.ServiceEntity
	core.DB.First(&rec, svc.ID)
	_ = json.Unmarshal([]byte(rec.EnvJSON), &plain)
	_ = json.Unmarshal([]byte(rec.EnvSecretJSON), &secret)
	echo := map[string]string{}
	for k, v := range plain {
		echo[k] = v
	}
	echo[OpenVikingAccountEnv] = "oaf-svc" // 回显保留键
	echo[AgentMemoryProviderEnv] = MemoryProviderLocal
	up, err := core.UpdateEnv(svc.ID, echo, nil)
	if err != nil {
		t.Fatalf("update env with reserved echo must not deadlock: %v", err)
	}
	var upPlain map[string]string
	_ = json.Unmarshal([]byte(up.EnvJSON), &upPlain)
	if upPlain[AgentMemoryProviderEnv] != MemoryProviderLocal {
		t.Fatalf("provider must switch to local, got %q", upPlain[AgentMemoryProviderEnv])
	}
	if _, ok := upPlain[OpenVikingAccountEnv]; ok {
		t.Fatalf("local opt-out must clear stale account key")
	}

	// 切回 openviking:幂等补装（Secret sticky,不重建 account）
	waitForVikingTerminal(t, core, svc.ID)
	createsBefore := fake.createCount()
	echo2 := map[string]string{}
	for k, v := range upPlain {
		echo2[k] = v
	}
	echo2[AgentMemoryProviderEnv] = MemoryProviderOpenViking
	up2, err := core.UpdateEnv(svc.ID, echo2, []string{OvkAdminKeyEnv}) // 保留键走 secretKeys 侧同样被剥离
	if err != nil {
		t.Fatalf("switch back: %v", err)
	}
	var up2Plain map[string]string
	_ = json.Unmarshal([]byte(up2.EnvJSON), &up2Plain)
	if up2Plain[OpenVikingAccountEnv] != "oaf-svc" || up2Plain[OpenVikingURLEnv] != baseURL {
		t.Fatalf("derived keys must be re-derived: %v", up2Plain)
	}
	if fake.createCount() != createsBefore {
		t.Fatalf("switch back must not re-provision (sticky secret)")
	}
}

// 用户侧自助签发:userId 固定 u-{uid},workspace 归属校验(他人空间 404 语义),签发
// key 与推导公式一致且可重复。
func TestConsoleIssueMemoryKey(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler(true))
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", WorkspaceID: 7})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)

	out, err := core.ConsoleIssueMemoryKey(7, svc.ID, 1001)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	if out["userId"] != "u-1001" {
		t.Fatalf("userId must be business identity u-{uid}, got %q", out["userId"])
	}
	if out["openVikingUrl"] != baseURL {
		t.Fatalf("studio url mismatch: %q", out["openVikingUrl"])
	}
	var secret map[string]string
	core.DB.First(&store.ServiceEntity{}, svc.ID)
	_ = json.Unmarshal([]byte(mustRead(t, core, svc.ID, "env_secret_json")), &secret)
	if out["userKey"] != DeriveVikingUserKey("oaf-svc", "u-1001", secret[OvkUserSeedEnv]) {
		t.Fatalf("issued key mismatch derivation")
	}
	// 跨 workspace 访问他人服务:404 语义(不暴露存在性)
	if _, err := core.ConsoleIssueMemoryKey(999, svc.ID, 1001); err == nil {
		t.Fatalf("cross-workspace issue must fail")
	}
	// 未供给服务拒绝
	noprov, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc-local", WorkspaceID: 7, Env: map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}})
	if err != nil {
		t.Fatalf("publish local: %v", err)
	}
	if _, err := core.ConsoleIssueMemoryKey(7, noprov.ID, 1001); err == nil {
		t.Fatalf("non-provisioned service must reject")
	}
}

func mustRead(t *testing.T, c *Core, id uint, col string) string {
	t.Helper()
	var rec store.ServiceEntity
	if err := c.DB.First(&rec, id).Error; err != nil {
		t.Fatal(err)
	}
	switch col {
	case "env_secret_json":
		return rec.EnvSecretJSON
	default:
		return rec.EnvJSON
	}
}

// 记忆供给聚合(Console/Admin 共用映射)+ 浏览代理(归属/前缀白名单/provider 校验)。
func TestMemorySupplyAndBrowse(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler(true))
	pkg := uploadTestPkg(t, core, "")
	ov, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc-ov", WorkspaceID: 7})
	if err != nil {
		t.Fatalf("publish ov: %v", err)
	}
	local, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc-local", WorkspaceID: 7, Env: map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}})
	if err != nil {
		t.Fatalf("publish local: %v", err)
	}

	rows, err := core.ConsoleMemorySupply(7, "")
	if err != nil {
		t.Fatalf("supply: %v", err)
	}
	if len(rows) != 2 {
		t.Fatalf("supply rows = %d, want 2", len(rows))
	}
	byName := map[string]MemorySupplyItem{}
	for _, r := range rows {
		byName[r.K8sName] = r
	}
	if byName["oaf-svc-ov"].Provider != MemoryProviderOpenViking || !byName["oaf-svc-ov"].Provisioned ||
		byName["oaf-svc-ov"].OpenVikingURL != baseURL {
		t.Fatalf("ov row wrong: %+v", byName["oaf-svc-ov"])
	}
	if byName["oaf-svc-local"].Provider != MemoryProviderLocal || byName["oaf-svc-local"].Provisioned {
		t.Fatalf("local row wrong: %+v", byName["oaf-svc-local"])
	}
	if adminRows, err := core.AdminMemorySupply(""); err != nil || len(adminRows) < 2 {
		t.Fatalf("admin supply: %v %d", err, len(adminRows))
	}

	// 浏览代理:uri 白名单(viking://~/ 契约口径 + 自有 user/{uid} 归一)+ provider 校验
	if _, err := core.ConsoleMemoryBrowse(7, ov.ID, 1001, "list", "viking://user/u-9999/memories"); err == nil {
		t.Fatalf("other user-form uri must be rejected")
	}
	if _, err := core.ConsoleMemoryBrowse(7, local.ID, 1001, "list", "viking://~/memories"); err == nil {
		t.Fatalf("local provider must be rejected")
	}
	// 归属外服务 404
	if _, err := core.ConsoleMemoryBrowse(999, ov.ID, 1001, "list", "viking://~/memories"); err == nil {
		t.Fatalf("cross-workspace browse must fail")
	}
}

// mustDecodeBrowseList 解码 ls 响应 envelope 的 result 条目数组（出参形状断言共用）。
func mustDecodeBrowseList(t *testing.T, raw json.RawMessage) []map[string]any {
	t.Helper()
	var env struct {
		Status string           `json:"status"`
		Result []map[string]any `json:"result"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("ls envelope: %v\nraw: %.400s", err, raw)
	}
	if env.Status != "ok" {
		t.Fatalf("ls status = %q", env.Status)
	}
	return env.Result
}

// 记忆浏览代理 URI 双向归一（issue #374）：入参 ~/ 与自有 user/{uid} 两形态等价、
// 出参条目统一 ~/ 契约口径、他人形态拒绝且绝不触上游、read 回传闭环、白名单闸门不变。
func TestMemoryBrowseURINormalization(t *testing.T) {
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler(true))
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", WorkspaceID: 7})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)

	// ① ~/ 入参 → 出参条目 uri 全部归一为 viking://~/ 前缀（size 数字字面量、abstract 原样保留）
	raw, err := core.ConsoleMemoryBrowse(7, svc.ID, 1001, "list", "viking://~/memories")
	if err != nil {
		t.Fatalf("home-form list: %v", err)
	}
	if !strings.Contains(string(raw), "9007199254740993") {
		t.Fatalf("size number literal must survive re-marshal (UseNumber), raw: %.400s", raw)
	}
	entries := mustDecodeBrowseList(t, raw)
	if len(entries) != 2 {
		t.Fatalf("entries = %d, want 2", len(entries))
	}
	for _, e := range entries {
		if u, _ := e["uri"].(string); !strings.HasPrefix(u, "viking://~/memories/") {
			t.Fatalf("entry uri not normalized to ~/: %q", u)
		}
	}
	if entries[0]["abstract"] != "user u-1001 的计划摘要" {
		t.Fatalf("non-uri fields must be preserved verbatim, got %v", entries[0]["abstract"])
	}

	// ② 自有展开形态入参 → 200，代理上游前先归一为 ~/ 形态，出参同样归一
	fake.resetBrowse()
	raw, err = core.ConsoleMemoryBrowse(7, svc.ID, 1001, "list", "viking://user/u-1001/memories")
	if err != nil {
		t.Fatalf("own user-form list: %v", err)
	}
	if got := fake.browseURIs(); len(got) != 1 || got[0] != "viking://~/memories" {
		t.Fatalf("upstream must receive normalized ~/ uri, got %v", got)
	}
	for _, e := range mustDecodeBrowseList(t, raw) {
		if u, _ := e["uri"].(string); !strings.HasPrefix(u, "viking://~/memories/") {
			t.Fatalf("own user-form output not normalized: %q", u)
		}
	}

	// ③ 他人展开形态 → 400 且零上游调用（绝不代理他人形态）
	fake.resetBrowse()
	if _, err := core.ConsoleMemoryBrowse(7, svc.ID, 1001, "list", "viking://user/u-9999/memories"); err == nil {
		t.Fatalf("other user-form must be rejected")
	} else if !strings.Contains(err.Error(), "own space") {
		t.Fatalf("rejection must be identity-mismatch 400, got %v", err)
	}
	if got := fake.browseURIs(); len(got) != 0 {
		t.Fatalf("other user-form must never reach upstream, got %v", got)
	}

	// ④ read 回传闭环：以 ① 出参的 ~/ 形态读回文本内容（read 纯透传不改写）
	raw, err = core.ConsoleMemoryBrowse(7, svc.ID, 1001, "read", "viking://~/memories/plan.md")
	if err != nil {
		t.Fatalf("round-trip read: %v", err)
	}
	if got := fake.browseURIs(); len(got) != 1 || got[0] != "viking://~/memories/plan.md" {
		t.Fatalf("read upstream uri mismatch: %v", got)
	}
	var env struct {
		Status string          `json:"status"`
		Result json.RawMessage `json:"result"`
	}
	if err := json.Unmarshal(raw, &env); err != nil || env.Status != "ok" {
		t.Fatalf("read envelope: %v %s", err, raw)
	}
	var text string
	if err := json.Unmarshal(env.Result, &text); err != nil || text != "# 计划\n正文内容" {
		t.Fatalf("read result = %s (%v)", env.Result, err)
	}

	// ⑤ 白名单闸门语义不变：非 ~/ 且非 user/ 形态仍拒绝
	if _, err := core.ConsoleMemoryBrowse(7, svc.ID, 1001, "list", "viking://resources/x"); err == nil {
		t.Fatalf("non-home non-user uri must stay rejected by whitelist")
	}
}
