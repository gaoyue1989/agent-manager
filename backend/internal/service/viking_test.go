package service

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"agent-manager/backend/internal/k8s/k8sfake"
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
// 响应形状），记录收到的 account 建户/轮换/删除次数、write URI 与 browse 入参 URI 供
// 断言。conflict 模拟 account 已存在（CONFLICT → EnsureAccount 轮换分支）；accounts
// 按名记账（issue #475：同名二次建户真实走 CONFLICT 轮换分支，与真机语义一致）；
// deleteErr/writeErr 注入对应端点故障；onCreate 为建户前置钩子（发布链路时序断言）。
// ensureDelay 拖长建户窗口构造并发交叉；checkWriteKey 启用 ADMIN key 轮换失效语义
// （write 校验 X-API-Key == currentKey，旧 key 401）；rotateAfterCreate 模拟建户与
// 导入之间 key 被外部接管；inFlight/overlap 记录上游调用并发度。未覆盖路径一律 404
// （测试内显式）。ls/read 条目 uri 模仿真机返回本人展开形态 viking://user/u-1001/…；
// plan.md 的 size 取 2^53+1 探测出参重序列化数字精度。
type fakeOpenViking struct {
	mu        sync.Mutex
	creates   int
	rotates   int
	deletes   int
	writeURI  []string
	browseURI []string
	conflict  bool
	deleteErr bool
	writeErr     bool
	browseStatus int
	onCreate     func()

	accounts          map[string]bool
	ensureDelay       time.Duration
	checkWriteKey     bool
	currentKey        string
	rotateAfterCreate bool
	inFlight          int
	overlap           bool
}

func (f *fakeOpenViking) enter() func() {
	f.mu.Lock()
	f.inFlight++
	if f.inFlight > 1 {
		f.overlap = true
	}
	f.mu.Unlock()
	return func() {
		f.mu.Lock()
		f.inFlight--
		f.mu.Unlock()
	}
}

func (f *fakeOpenViking) overlapped() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.overlap
}

func (f *fakeOpenViking) recordWrite(uri string) {
	f.mu.Lock()
	f.writeURI = append(f.writeURI, uri)
	f.mu.Unlock()
}
func (f *fakeOpenViking) recordBrowse(uri string) {
	f.mu.Lock()
	f.browseURI = append(f.browseURI, uri)
	f.mu.Unlock()
}
func (f *fakeOpenViking) createCount() int { f.mu.Lock(); defer f.mu.Unlock(); return f.creates }
func (f *fakeOpenViking) rotateCount() int { f.mu.Lock(); defer f.mu.Unlock(); return f.rotates }
func (f *fakeOpenViking) deleteCount() int { f.mu.Lock(); defer f.mu.Unlock(); return f.deletes }
func (f *fakeOpenViking) uris() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string{}, f.writeURI...)
}
func (f *fakeOpenViking) browseURIs() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string{}, f.browseURI...)
}
func (f *fakeOpenViking) resetBrowse() { f.mu.Lock(); f.browseURI = nil; f.mu.Unlock() }

func (f *fakeOpenViking) handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		defer f.enter()()
		switch {
		case r.URL.Path == "/api/v1/admin/accounts" && r.Method == http.MethodPost:
			var body struct {
				AccountID string `json:"account_id"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			if f.ensureDelay > 0 {
				time.Sleep(f.ensureDelay)
			}
			f.mu.Lock()
			if f.accounts == nil {
				f.accounts = map[string]bool{}
			}
			isConflict := f.conflict || f.accounts[body.AccountID]
			f.mu.Unlock()
			if isConflict {
				w.WriteHeader(http.StatusConflict)
				_ = json.NewEncoder(w).Encode(map[string]any{"status": "error",
					"error": map[string]string{"code": "CONFLICT", "message": "account exists"}})
				return
			}
			f.mu.Lock()
			f.creates++
			f.accounts[body.AccountID] = true
			key := "ak-test"
			if f.checkWriteKey {
				key = fmt.Sprintf("ak-create-%d", f.creates)
				f.currentKey = key
				if f.rotateAfterCreate {
					f.currentKey = "ak-external-rotated"
				}
			}
			hook := f.onCreate
			f.mu.Unlock()
			if hook != nil {
				hook()
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok",
				"result": map[string]string{"user_key": key}})
		case strings.HasSuffix(r.URL.Path, "/key") && r.Method == http.MethodPost:
			f.mu.Lock()
			f.rotates++
			if f.checkWriteKey {
				f.currentKey = "ak-rotated"
			}
			f.mu.Unlock()
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok",
				"result": map[string]string{"user_key": "ak-rotated"}})
		case strings.HasSuffix(r.URL.Path, "/users") && r.Method == http.MethodPost:
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok",
				"result": map[string]string{"user_key": "x"}})
		case strings.HasPrefix(r.URL.Path, "/api/v1/admin/accounts/") && r.Method == http.MethodDelete:
			f.mu.Lock()
			f.deletes++
			for id := range f.accounts {
				if strings.HasSuffix(r.URL.Path, "/"+id) {
					delete(f.accounts, id)
				}
			}
			f.mu.Unlock()
			if f.deleteErr {
				w.WriteHeader(http.StatusBadGateway)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok", "result": nil})
		case r.URL.Path == "/api/v1/content/write":
			var body struct {
				URI string `json:"uri"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			f.recordWrite(body.URI)
			f.mu.Lock()
			bad := f.writeErr
			keyOK := !f.checkWriteKey || f.currentKey == "" || r.Header.Get("X-API-Key") == f.currentKey
			f.mu.Unlock()
			if !keyOK {
				w.WriteHeader(http.StatusUnauthorized)
				_ = json.NewEncoder(w).Encode(map[string]any{"status": "error",
					"error": map[string]string{"code": "UNAUTHORIZED", "message": "invalid api key"}})
				return
			}
			if bad {
				w.WriteHeader(http.StatusBadGateway)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok",
				"result": map[string]string{}})
		case r.URL.Path == "/api/v1/fs/ls" && r.Method == http.MethodGet:
			f.recordBrowse(r.URL.Query().Get("uri"))
			if f.browseStatus >= 400 {
				w.WriteHeader(f.browseStatus)
				_, _ = fmt.Fprint(w, `{"status":"error","error":{"code":"NOT_FOUND","message":"private path viking://user/u-1001/private-memory"}}`)
				return
			}
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
	core.Viking = NewVikingAdmin(true, srv.URL, "root-key-test")
	return core, srv.URL
}

// newVikingTestCoreWithFake 同 newVikingTestCore，另返回 fake K8s（apply/restart 失败注入）。
func newVikingTestCoreWithFake(t *testing.T, h http.HandlerFunc) (*Core, *k8sfake.FakeK8s, string) {
	t.Helper()
	core, fk, cleanup := newTestCore(t)
	t.Cleanup(cleanup)
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	core.Viking = NewVikingAdmin(true, srv.URL, "root-key-test")
	return core, fk, srv.URL
}

// 总开关门控（issue #414）：OPENVIKING_ENABLED=false 时即使 URL/RootKey 齐备也不装配
// Admin 客户端——装配即默认 provider=openviking，总开关失效 = 紧急回退/灰度手段失效。
func TestNewVikingAdminEnabledGate(t *testing.T) {
	if v := NewVikingAdmin(false, "http://127.0.0.1:1", "root-key"); v != nil {
		t.Fatalf("OPENVIKING_ENABLED=false must not assemble VikingAdmin, got %v", v)
	}
	if v := NewVikingAdmin(true, "", "root-key"); v != nil {
		t.Fatalf("enabled but missing URL must stay nil")
	}
	if v := NewVikingAdmin(true, "http://127.0.0.1:1", ""); v != nil {
		t.Fatalf("enabled but missing root key must stay nil")
	}
	if v := NewVikingAdmin(true, "http://127.0.0.1:1", "root-key"); v == nil {
		t.Fatalf("enabled with full config must assemble")
	}

	// 行为面复现（repro B-02）：开关关闭后发布链路零接触——不建户、不改 provider
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler())
	core.Viking = NewVikingAdmin(false, "http://127.0.0.1:1", "root-key") // 开关关闭覆盖
	plain, secret := map[string]string{}, map[string]string{}
	if _, err := core.applyOpenVikingEnv("oaf-svc", nil, plain, secret); err != nil {
		t.Fatalf("disabled platform publish must be zero touch: %v", err)
	}
	if len(plain) != 0 || len(secret) != 0 {
		t.Fatalf("env mutated under disabled switch: %v %v", plain, secret)
	}
	if fake.createCount() != 0 {
		t.Fatalf("disabled platform must not provision OpenViking, creates=%d", fake.createCount())
	}
}

// applyOpenVikingEnv 行为矩阵（Publish/UpdateEnv/Republish 共用同一函数）。
func TestApplyOpenVikingEnvMatrix(t *testing.T) {
	t.Run("platform disabled zero touch", func(t *testing.T) {
		c := &Core{} // Viking=nil
		plain, secret := map[string]string{}, map[string]string{}
		if _, err := c.applyOpenVikingEnv("oaf-svc", nil, plain, secret); err != nil {
			t.Fatalf("disabled + no explicit provider must be zero touch: %v", err)
		}
		if len(plain) != 0 || len(secret) != 0 {
			t.Fatalf("env mutated: %v %v", plain, secret)
		}
	})

	t.Run("platform disabled explicit openviking rejected", func(t *testing.T) {
		c := &Core{}
		plain := map[string]string{AgentMemoryProviderEnv: MemoryProviderOpenViking}
		if _, err := c.applyOpenVikingEnv("oaf-svc", nil, plain, map[string]string{}); err == nil {
			t.Fatalf("explicit provider without platform config must fail")
		}
	})

	t.Run("explicit local opt-out", func(t *testing.T) {
		c := &Core{Viking: NewVikingAdmin(true, "http://unused", "k")} // 退出路径不触网
		plain := map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}
		secret := map[string]string{}
		if _, err := c.applyOpenVikingEnv("oaf-svc", nil, plain, secret); err != nil {
			t.Fatalf("explicit local: %v", err)
		}
		if len(plain) != 1 || len(secret) != 0 {
			t.Fatalf("opt-out must not inject anything: %v %v", plain, secret)
		}
	})

	t.Run("invalid provider rejected", func(t *testing.T) {
		c := &Core{Viking: NewVikingAdmin(true, "http://unused", "k")}
		plain := map[string]string{AgentMemoryProviderEnv: "redis"}
		if _, err := c.applyOpenVikingEnv("oaf-svc", nil, plain, map[string]string{}); err == nil {
			t.Fatalf("invalid provider must fail")
		}
	})

	t.Run("disabled switch legacy provision maintained", func(t *testing.T) {
		c := &Core{} // Viking=nil
		plain := map[string]string{
			AgentMemoryProviderEnv: MemoryProviderOpenViking,
			OpenVikingURLEnv:       "http://ov-legacy:8300",
			OpenVikingAccountEnv:   "oaf-svc",
		}
		secret := map[string]string{OvkAdminKeyEnv: "ak-legacy", OvkUserSeedEnv: "seed-legacy"}
		if _, err := c.applyOpenVikingEnv("oaf-svc", nil, plain, secret); err != nil {
			t.Fatalf("legacy provisioned service must stay maintainable under disabled switch: %v", err)
		}
		if plain[OpenVikingURLEnv] != "http://ov-legacy:8300" || plain[OpenVikingAccountEnv] != "oaf-svc" ||
			secret[OvkAdminKeyEnv] != "ak-legacy" || secret[OvkUserSeedEnv] != "seed-legacy" {
			t.Fatalf("legacy maintain must be zero touch: %v %v", plain, secret)
		}
	})

	t.Run("disabled switch explicit local clears derived keys", func(t *testing.T) {
		c := &Core{} // Viking=nil
		plain := map[string]string{
			AgentMemoryProviderEnv: MemoryProviderLocal,
			OpenVikingURLEnv:       "http://ov-legacy:8300",
			OpenVikingAccountEnv:   "oaf-svc",
		}
		secret := map[string]string{OvkAdminKeyEnv: "ak-legacy", OvkUserSeedEnv: "seed-legacy"}
		if _, err := c.applyOpenVikingEnv("oaf-svc", nil, plain, secret); err != nil {
			t.Fatalf("explicit local opt-out must stay available under disabled switch: %v", err)
		}
		if len(plain) != 1 || len(secret) != 0 {
			t.Fatalf("opt-out must clear all derived keys plain and secret: %v %v", plain, secret)
		}
	})
}

// 发布端到端（平台启用默认接管）：建户 → env/Secret 注入 → knowledge 导入 → 幂等 UpdateEnv。
func TestPublishWithOpenVikingProvision(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler())

	zip := buildTestZip(map[string]string{
		"AGENTS.md":             testAgentsMD,
		"knowledge/guide.md":    "# 指南\n平台发布流程说明",
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
	core, _ := newVikingTestCore(t, fake.handler())
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
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler())
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "svc"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if err := core.Delete(svc.ID); err != nil {
		t.Fatalf("delete: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for fake.deleteCount() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if fake.deleteCount() != 1 {
		t.Fatalf("delete must remove openviking account, deletes=%d", fake.deleteCount())
	}
}

// OpenViking 故障不阻断删除（best-effort 语义）。
func TestDeleteToleratesVikingOutage(t *testing.T) {
	core, _ := newVikingTestCore(t, (&fakeOpenViking{deleteErr: true}).handler())
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


// UpdateEnv 全量覆盖回显含保留键不再死锁（真机验证发现的实现缺陷）：剥离后 provider
// 切换成功、派生键重建；平台未启用时显式设置注入键仍被拒绝（恶意值进不来）。
func TestUpdateEnvSwitchProviderWithReservedEcho(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler())
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
	var upPlain, upSecret map[string]string
	_ = json.Unmarshal([]byte(up.EnvJSON), &upPlain)
	_ = json.Unmarshal([]byte(up.EnvSecretJSON), &upSecret)
	if upPlain[AgentMemoryProviderEnv] != MemoryProviderLocal {
		t.Fatalf("provider must switch to local, got %q", upPlain[AgentMemoryProviderEnv])
	}
	if _, ok := upPlain[OpenVikingAccountEnv]; ok {
		t.Fatalf("local opt-out must clear stale account key")
	}
	if upSecret[OvkAdminKeyEnv] != "" || upSecret[OvkUserSeedEnv] != "" {
		t.Fatalf("local opt-out must clear derived secret keys (issue #430): %v", upSecret)
	}

	// 切回 openviking:secret 已清空 → 重新供给（远端 account 数据保留,切回恢复供给）
	waitForVikingTerminal(t, core, svc.ID)
	echo2 := map[string]string{}
	for k, v := range upPlain {
		echo2[k] = v
	}
	echo2[AgentMemoryProviderEnv] = MemoryProviderOpenViking
	up2, err := core.UpdateEnv(svc.ID, echo2, []string{OvkAdminKeyEnv}) // 保留键走 secretKeys 侧同样被剥离
	if err != nil {
		t.Fatalf("switch back: %v", err)
	}
	var up2Plain, up2Secret map[string]string
	_ = json.Unmarshal([]byte(up2.EnvJSON), &up2Plain)
	_ = json.Unmarshal([]byte(up2.EnvSecretJSON), &up2Secret)
	if up2Plain[OpenVikingAccountEnv] != "oaf-svc" || up2Plain[OpenVikingURLEnv] != baseURL {
		t.Fatalf("derived keys must be re-derived: %v", up2Plain)
	}
	if up2Secret[OvkAdminKeyEnv] == "" || up2Secret[OvkUserSeedEnv] == "" {
		t.Fatalf("switch back must re-provision secret keys: %v", up2Secret)
	}
}

// 发布链路供给时序与失败回滚（issue #430 B-03a/B-03b）：
//   - 占位先行不变量——EnsureAccount 只允许发生在 DB 占位事务之后，并发同名发布败者在
//     唯一键冲突处失败，零建户零轮换（胜者在线 ADMIN key 不被轮换失效）
//   - 供给失败回滚——占位后任一环节失败，本次新建的 account 回滚删除、占位行与
//     ref_count 清理（DB 零残留、无外部孤儿 account）
//   - 回滚边界——EnsureAccount 走 CONFLICT 轮换分支（account 已存在，持有者在用）时
//     失败绝不删除 account
func TestPublishProvisionOrderingAndRollback(t *testing.T) {
	publishImport := func(t *testing.T, c *Core) *store.OafPackage {
		t.Helper()
		zip := buildTestZip(map[string]string{
			"AGENTS.md":          testAgentsMD,
			"knowledge/guide.md": "# 指南",
		})
		pkg, err := c.Packages.Upload("demo.zip", bytesReader(zip))
		if err != nil {
			t.Fatalf("upload pkg: %v", err)
		}
		_, err = c.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
			Name: "svc", Env: map[string]string{OvkKnowledgeImportEnv: "true"}})
		if err == nil {
			t.Fatalf("publish must fail on knowledge import error")
		}
		return pkg
	}
	assertNoRows := func(t *testing.T, c *Core, pkgID uint) {
		t.Helper()
		var cnt int64
		if err := c.DB.Model(&store.ServiceEntity{}).Where("k8s_name = ?", "oaf-svc").Count(&cnt).Error; err != nil {
			t.Fatal(err)
		}
		if cnt != 0 {
			t.Fatalf("failed publish must leave zero DB rows, got %d", cnt)
		}
		var pkgRow store.OafPackage
		if err := c.DB.First(&pkgRow, pkgID).Error; err != nil {
			t.Fatal(err)
		}
		if pkgRow.RefCount != 0 {
			t.Fatalf("ref_count must be restored after rollback, got %d", pkgRow.RefCount)
		}
	}

	t.Run("ensure account runs after DB placeholder", func(t *testing.T) {
		fake := &fakeOpenViking{}
		core, _ := newVikingTestCore(t, fake.handler())
		pkg := uploadTestPkg(t, core, "")
		invoked := false
		fake.onCreate = func() {
			var cnt int64
			if err := core.DB.Model(&store.ServiceEntity{}).Where("k8s_name = ?", "oaf-svc").Count(&cnt).Error; err != nil {
				t.Errorf("placeholder check: %v", err)
			}
			if cnt != 1 {
				t.Errorf("EnsureAccount must run after DB placeholder transaction, placeholder rows = %d", cnt)
			}
			invoked = true
		}
		svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "svc"})
		if err != nil {
			t.Fatalf("publish: %v", err)
		}
		if !invoked {
			t.Fatalf("ensure account never invoked")
		}
		waitForVikingTerminal(t, core, svc.ID)
	})

	t.Run("provision failure rolls back new account and placeholder", func(t *testing.T) {
		fake := &fakeOpenViking{writeErr: true}
		core, _ := newVikingTestCore(t, fake.handler())
		pkg := publishImport(t, core)
		assertNoRows(t, core, pkg.ID)
		if fake.deleteCount() != 1 {
			t.Fatalf("newly created account must be rolled back on provision failure, deletes=%d", fake.deleteCount())
		}
	})

	t.Run("rotated existing account never deleted on failure", func(t *testing.T) {
		fake := &fakeOpenViking{conflict: true, writeErr: true}
		core, _ := newVikingTestCore(t, fake.handler())
		pkg := publishImport(t, core)
		assertNoRows(t, core, pkg.ID)
		if fake.rotateCount() != 1 {
			t.Fatalf("conflict path must rotate key, rotates=%d", fake.rotateCount())
		}
		if fake.deleteCount() != 0 {
			t.Fatalf("pre-existing account (key rotated, holder in use) must not be deleted, deletes=%d", fake.deleteCount())
		}
	})
}

// 切 local 后签发入口按 provider 校验拒绝（issue #430 B-04b）：secret 派生键已随切换
// 清理，不再凭残留凭据签发无对应供给的 userKey（openVikingUrl 为空的悬空凭证）。
func TestSwitchLocalBlocksMemoryKeyIssue(t *testing.T) {
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler())
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", WorkspaceID: 7})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)

	if _, err := core.IssueMemoryKey(svc.ID, "u-1001"); err != nil {
		t.Fatalf("issue before switch: %v", err)
	}
	rec, err := core.Get(svc.ID)
	if err != nil {
		t.Fatal(err)
	}
	echo := map[string]string{}
	_ = json.Unmarshal([]byte(rec.EnvJSON), &echo)
	echo[AgentMemoryProviderEnv] = MemoryProviderLocal
	if _, err := core.UpdateEnv(svc.ID, echo, nil); err != nil {
		t.Fatalf("switch local: %v", err)
	}
	if _, err := core.IssueMemoryKey(svc.ID, "u-1001"); err == nil {
		t.Fatalf("issue after local switch must be rejected")
	}
	if _, err := core.ConsoleIssueMemoryKey(7, svc.ID, 1001); err == nil {
		t.Fatalf("console issue after local switch must be rejected")
	}
}

// 用户侧自助签发:userId 固定 u-{uid},workspace 归属校验(他人空间 404 语义),签发
// key 与推导公式一致且可重复。
func TestConsoleIssueMemoryKey(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler())
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
	core, baseURL := newVikingTestCore(t, fake.handler())
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

// 上游浏览错误按 HTTP 状态映射本地错误，且绝不回显上游内部路径或响应正文。
func TestMemoryBrowseMapsUpstreamErrorsWithoutLeakingDetails(t *testing.T) {
	for _, tc := range []struct {
		status int
		want   error
	}{
		{http.StatusBadRequest, ErrInvalidForm},
		{http.StatusForbidden, ErrForbidden},
		{http.StatusNotFound, ErrNotFound},
		{http.StatusInternalServerError, nil},
	} {
		t.Run(http.StatusText(tc.status), func(t *testing.T) {
			fake := &fakeOpenViking{browseStatus: tc.status}
			core, _ := newVikingTestCore(t, fake.handler())
			pkg := uploadTestPkg(t, core, "")
			svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
				Name: "svc", WorkspaceID: 7})
			if err != nil {
				t.Fatalf("publish: %v", err)
			}
			waitForVikingTerminal(t, core, svc.ID)

			_, err = core.ConsoleMemoryBrowse(7, svc.ID, 1001, "list", "viking://~/memories")
			if err == nil {
				t.Fatal("browse must return an error")
			}
			if tc.want != nil && !errors.Is(err, tc.want) {
				t.Fatalf("error = %v, want errors.Is(%v)", err, tc.want)
			}
			if strings.Contains(err.Error(), "private-memory") || strings.Contains(err.Error(), "u-1001") {
				t.Fatalf("upstream private details leaked: %v", err)
			}
		})
	}
}

// 记忆浏览代理 URI 双向归一（issue #374）：入参 ~/ 与自有 user/{uid} 两形态等价、
// 出参条目统一 ~/ 契约口径、他人形态拒绝且绝不触上游、read 回传闭环、白名单闸门不变。
func TestMemoryBrowseURINormalization(t *testing.T) {
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler())
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

// 路径穿越与逃逸字符拒绝（issue #420/#484）：`..` 段及全部编码/分隔符变体在白名单
// 闸门之前拦截，绝不代理给上游——自有 user/{uid}/../ 前缀改写后仍含穿越段，前缀闸门
// 拦不住；文件名中的点（a..b/.hidden）与 UTF-8/空格纯字面不受影响。
func TestMemoryBrowseRejectsPathTraversal(t *testing.T) {
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler())
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", WorkspaceID: 7})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)

	for _, uri := range []string{
		"viking://~/../u-9999/memories",                       // 前缀改写后的越权形态（他人空间）
		"viking://~/../../resources/acme/secret.md",           // 越出本人空间根
		"viking://user/u-1001/../u-9999/memories",             // 自有展开形态夹带穿越段
		"viking://~/memories/%2e%2e/u-9999/secrets.md",        // 百分号编码穿越段
		"viking://~/memories/%2E%2E/plan.md",                  // 大小写变体
		"viking://~/.%2e/u-9999/memories",                     // 混合点编码
		"viking://~/%2e./u-9999/memories/secret.md",           // 混合点编码（反序）
		"viking://user/u-1001/.%2e/u-9999/memories",           // 自有前缀改写产物夹带混合编码
		"viking://~/memories%2f..%2f..%2fu-9999/memories/secret.md", // 编码斜杠拼接穿越段
		"viking://~/memories%2F..%2F..%2Fu-9999/memories",     // 编码斜杠大写变体
		"viking://~/..%2fu-9999/memories",                     // 编码斜杠尾随穿越段
		"viking://~/..\\u-9999\\memories\\secret.md",          // 反斜杠分隔符
		"viking://~/..?foo=bar",                               // query 切分出穿越 path
		"viking://~/..#frag",                                  // fragment 切分出穿越 path
		"viking://~/%252e%252e/u-9999/memories/secret.md",     // 双重编码
		"viking://~/memories/\x00/u-9999/secrets.md",          // 控制字符（空字节）
		"viking://~/./u-9999/memories",                        // . 段（歧义规范化形态）
	} {
		fake.resetBrowse()
		if _, err := core.ConsoleMemoryBrowse(7, svc.ID, 1001, "list", uri); err == nil {
			t.Fatalf("traversal uri must be rejected: %s", uri)
		}
		if _, err := core.ConsoleMemoryBrowse(7, svc.ID, 1001, "read", uri); err == nil {
			t.Fatalf("traversal uri must be rejected on read too: %s", uri)
		}
		if got := fake.browseURIs(); len(got) != 0 {
			t.Fatalf("traversal uri %s must never reach upstream, got %v", uri, got)
		}
	}

	// 文件名中的点、隐藏文件、UTF-8/空格纯字面不是穿越形态：合法 URI 照常原样代理
	for _, uri := range []string{
		"viking://~/memories/a..b.md",
		"viking://~/memories/.hidden",
		"viking://~/memories/会议 记录.md",
	} {
		fake.resetBrowse()
		if _, err := core.ConsoleMemoryBrowse(7, svc.ID, 1001, "read", uri); err != nil {
			t.Fatalf("legit uri %s must not be rejected: %v", uri, err)
		}
		if got := fake.browseURIs(); len(got) != 1 || got[0] != uri {
			t.Fatalf("legit uri %s must reach upstream verbatim, got %v", uri, got)
		}
	}
}

// 总开关关闭后的存量已建户服务生命周期（issue #477，PR #472 回归三场景）：
//   - 无关 Republish（改副本/升镜像/换包）不再被 DB 事实 provider=openviking 锁死 400——
//     凭据齐备走「存量维持」（零外呼、env 原样），无凭据的新建供给仍 fail-fast 拒绝
//   - 显式切回 local 无条件清理全部派生键（plain URL/ACCOUNT + secret ADMIN/SEED 双侧），
//     总开关关闭不再跳过清理导致 OVK 密钥永久残留 DB env_secret_json 与 K8s Secret
//   - ConsoleMemoryBrowse 对齐 issueMemoryKeyFor 总开关门控：关闭后拒绝且零上游调用
//     （紧急回退止血，不再以残留凭据外呼 OpenViking）
func TestDisabledSwitchLegacyServiceLifecycle(t *testing.T) {
	fake := &fakeOpenViking{}
	core, baseURL := newVikingTestCore(t, fake.handler())
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", WorkspaceID: 7})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)
	creations := fake.createCount()
	core.Viking = NewVikingAdmin(false, baseURL, "root-key-test") // 紧急回退：总开关关闭

	// ① 记忆浏览对齐总开关门控：拒绝且绝不外呼（评审 Finding 2b）
	fake.resetBrowse()
	if _, err := core.ConsoleMemoryBrowse(7, svc.ID, 1001, "list", "viking://~/memories"); err == nil {
		t.Fatalf("browse must be gated by the platform switch when disabled")
	}
	if got := fake.browseURIs(); len(got) != 0 {
		t.Fatalf("disabled switch must stop upstream browse calls, got %v", got)
	}

	// ② 无关 Republish 走存量维持（评审 Finding 2a）：不再 400、env/Secret 原样、零外呼
	replicas := int32(2)
	up, err := core.Republish(svc.ID, RepublishOptions{Replicas: &replicas})
	if err != nil {
		t.Fatalf("unrelated republish on legacy provisioned service must pass: %v", err)
	}
	if fake.createCount() != creations {
		t.Fatalf("legacy maintain must be zero touch, creates=%d", fake.createCount())
	}
	if up.Replicas != 2 {
		t.Fatalf("replicas must update, got %d", up.Replicas)
	}
	var upPlain, upSecret map[string]string
	_ = json.Unmarshal([]byte(up.EnvJSON), &upPlain)
	_ = json.Unmarshal([]byte(up.EnvSecretJSON), &upSecret)
	if upPlain[AgentMemoryProviderEnv] != MemoryProviderOpenViking || upPlain[OpenVikingURLEnv] != baseURL ||
		upPlain[OpenVikingAccountEnv] != "oaf-svc" {
		t.Fatalf("legacy plain env must stay intact across republish: %v", upPlain)
	}
	if upSecret[OvkAdminKeyEnv] == "" || upSecret[OvkUserSeedEnv] == "" {
		t.Fatalf("legacy secret must stay intact across republish: %v", upSecret)
	}

	// ③ 显式切回 local 无条件清理派生键（评审 Finding 2c）：DB 双侧清空 + supply 不再误报
	waitForVikingTerminal(t, core, svc.ID)
	var rec store.ServiceEntity
	if err := core.DB.First(&rec, svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	echo := map[string]string{}
	_ = json.Unmarshal([]byte(rec.EnvJSON), &echo)
	echo[AgentMemoryProviderEnv] = MemoryProviderLocal
	if _, err := core.UpdateEnv(svc.ID, echo, nil); err != nil {
		t.Fatalf("switch local under disabled switch must pass: %v", err)
	}
	var fin store.ServiceEntity
	if err := core.DB.First(&fin, svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	var finPlain, finSecret map[string]string
	_ = json.Unmarshal([]byte(fin.EnvJSON), &finPlain)
	_ = json.Unmarshal([]byte(fin.EnvSecretJSON), &finSecret)
	if finPlain[AgentMemoryProviderEnv] != MemoryProviderLocal {
		t.Fatalf("provider must switch to local, got %q", finPlain[AgentMemoryProviderEnv])
	}
	if finPlain[OpenVikingURLEnv] != "" || finPlain[OpenVikingAccountEnv] != "" {
		t.Fatalf("switch local must clear plain derived keys: %v", finPlain)
	}
	if finSecret[OvkAdminKeyEnv] != "" || finSecret[OvkUserSeedEnv] != "" {
		t.Fatalf("switch local must clear secret derived keys (issue #477): %v", finSecret)
	}
	rows, err := core.ConsoleMemorySupply(7, "")
	if err != nil {
		t.Fatalf("supply: %v", err)
	}
	for _, r := range rows {
		if r.ServiceID == svc.ID && (r.Provider != MemoryProviderLocal || r.Provisioned) {
			t.Fatalf("supply must report local non-provisioned after switch: %+v", r)
		}
	}
}

// ---- 并发 Republish 与多失败路径的账户生命周期（issue #475，PR #467 回归）----

// uploadVikingImportPkg 上传带 knowledge/guide.md 的测试包（导入链路共用）。
func uploadVikingImportPkg(t *testing.T, c *Core) *store.OafPackage {
	t.Helper()
	zip := buildTestZip(map[string]string{
		"AGENTS.md":          testAgentsMD,
		"knowledge/guide.md": "# 指南",
	})
	pkg, err := c.Packages.Upload("demo.zip", bytesReader(zip))
	if err != nil {
		t.Fatalf("upload pkg: %v", err)
	}
	return pkg
}

// 并发 Republish 同一服务（双击/超时重试/脚本重放）必须按服务串行化：修复前两个供给
// 流并发进入 EnsureAccount，后到者 CONFLICT 轮换 ADMIN key 使先建户方 401 失败，其
// rollbackProvision(created=true) 误删他者已上线的在用 account；修复后锁内串行，先
// 到者完整供给成功、后到者凭据 sticky 零外呼，账户全程存活、零删除。
func TestRepublishConcurrentProvisionSerialized(t *testing.T) {
	fake := &fakeOpenViking{checkWriteKey: true, ensureDelay: 80 * time.Millisecond}
	core, baseURL := newVikingTestCore(t, fake.handler())
	pkg := uploadVikingImportPkg(t, core)
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", Env: map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)

	switchEnv := map[string]string{
		AgentMemoryProviderEnv: MemoryProviderOpenViking,
		OvkKnowledgeImportEnv:  "true",
	}
	start := make(chan struct{})
	errs := make([]error, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			_, errs[i] = core.Republish(svc.ID, RepublishOptions{Env: switchEnv})
		}(i)
	}
	close(start)
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			t.Fatalf("republish[%d] must succeed under serialization: %v", i, err)
		}
	}
	if fake.overlapped() {
		t.Fatalf("concurrent republish on one service must be serialized, upstream saw overlapping calls")
	}
	if fake.deleteCount() != 0 {
		t.Fatalf("live account must never be deleted by a losing concurrent request, deletes=%d", fake.deleteCount())
	}
	if fake.createCount() != 1 {
		t.Fatalf("loser must reuse sticky credentials, creates=%d", fake.createCount())
	}
	var got store.ServiceEntity
	if err := core.DB.First(&got, svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	var secret map[string]string
	_ = json.Unmarshal([]byte(got.EnvSecretJSON), &secret)
	var plain map[string]string
	_ = json.Unmarshal([]byte(got.EnvJSON), &plain)
	if secret[OvkAdminKeyEnv] == "" || plain[OpenVikingURLEnv] != baseURL {
		t.Fatalf("service must stay provisioned: plain=%v secret=%v", plain, secret)
	}
}

// ADMIN key 在建户与导入之间被外部轮换（运维手工轮换/他者接管）时，导入 401 失败的
// 回滚绝不能删除账户——401 语义 = key 已被他人持有轮换，账户归属不再属于本次调用
// （issue #475：修复前 rollbackProvision(created=true) 无条件 DeleteAccount）。
func TestRollbackSkipsWhenKeyRevokedExternally(t *testing.T) {
	fake := &fakeOpenViking{checkWriteKey: true, rotateAfterCreate: true}
	core, _ := newVikingTestCore(t, fake.handler())
	pkg := uploadVikingImportPkg(t, core)

	_, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", Env: map[string]string{OvkKnowledgeImportEnv: "true"}})
	if err == nil {
		t.Fatalf("publish must fail when admin key was rotated externally mid-provision")
	}
	if fake.deleteCount() != 0 {
		t.Fatalf("401 (key revoked by another holder) must never delete the account, deletes=%d", fake.deleteCount())
	}
	var cnt int64
	if err := core.DB.Model(&store.ServiceEntity{}).Where("k8s_name = ?", "oaf-svc").Count(&cnt).Error; err != nil {
		t.Fatal(err)
	}
	if cnt != 0 {
		t.Fatalf("failed publish must leave zero DB rows, got %d", cnt)
	}
	var pkgRow store.OafPackage
	if err := core.DB.First(&pkgRow, pkg.ID).Error; err != nil {
		t.Fatal(err)
	}
	if pkgRow.RefCount != 0 {
		t.Fatalf("ref_count must be restored, got %d", pkgRow.RefCount)
	}
}

// UpdateEnv 切换供给成功建户后，落库前任一环节失败（applyServiceEnvObjects /
// RestartDeployment）必须回滚本次新建账户（issue #475：修复前泄漏外部孤儿 account）。
func TestUpdateEnvFailureRollsBackNewAccount(t *testing.T) {
	t.Run("applyServiceEnvObjects failure", func(t *testing.T) {
		fake := &fakeOpenViking{}
		core, fk, _ := newVikingTestCoreWithFake(t, fake.handler())
		pkg := uploadVikingImportPkg(t, core)
		svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
			Name: "svc", Env: map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}})
		if err != nil {
			t.Fatalf("publish: %v", err)
		}
		waitForVikingTerminal(t, core, svc.ID)
		fk.FailNextEnsure(1, fmt.Errorf("k8s cm failure"))

		if _, err := core.UpdateEnv(svc.ID, map[string]string{
			AgentMemoryProviderEnv: MemoryProviderOpenViking,
			OvkKnowledgeImportEnv:  "true",
		}, nil); err == nil {
			t.Fatalf("update env must fail")
		}
		if fake.createCount() != 1 {
			t.Fatalf("provision must have created account, creates=%d", fake.createCount())
		}
		if fake.deleteCount() != 1 {
			t.Fatalf("newly created account must be rolled back on later failure, deletes=%d", fake.deleteCount())
		}
		after, err := core.Get(svc.ID)
		if err != nil {
			t.Fatal(err)
		}
		var plain map[string]string
		_ = json.Unmarshal([]byte(after.EnvJSON), &plain)
		if plain[AgentMemoryProviderEnv] != MemoryProviderLocal {
			t.Fatalf("failed update must leave env untouched, got %v", plain)
		}
	})

	t.Run("restart failure", func(t *testing.T) {
		fake := &fakeOpenViking{}
		core, fk, _ := newVikingTestCoreWithFake(t, fake.handler())
		pkg := uploadVikingImportPkg(t, core)
		svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
			Name: "svc", Env: map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}})
		if err != nil {
			t.Fatalf("publish: %v", err)
		}
		waitForVikingTerminal(t, core, svc.ID)
		fk.FailNextRestart(1, fmt.Errorf("k8s restart failure"))

		if _, err := core.UpdateEnv(svc.ID, map[string]string{
			AgentMemoryProviderEnv: MemoryProviderOpenViking,
		}, nil); err == nil {
			t.Fatalf("update env must fail")
		}
		if fake.deleteCount() != 1 {
			t.Fatalf("newly created account must be rolled back on restart failure, deletes=%d", fake.deleteCount())
		}
	})
}

// Republish 建户后 DB 落库失败（事务 abort）必须回滚本次新建账户（issue #475）。
func TestRepublishDBFailureRollsBackNewAccount(t *testing.T) {
	fake := &fakeOpenViking{}
	core, _ := newVikingTestCore(t, fake.handler())
	pkg := uploadVikingImportPkg(t, core)
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", Env: map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)
	if err := core.DB.Exec("CREATE TRIGGER block_svc_update BEFORE UPDATE ON services " +
		"BEGIN SELECT RAISE(ABORT, 'blocked for test'); END;").Error; err != nil {
		t.Fatal(err)
	}

	if _, err := core.Republish(svc.ID, RepublishOptions{Env: map[string]string{
		AgentMemoryProviderEnv: MemoryProviderOpenViking,
	}}); err == nil {
		t.Fatalf("republish must fail on db abort")
	}
	if fake.createCount() != 1 {
		t.Fatalf("provision must have created account, creates=%d", fake.createCount())
	}
	if fake.deleteCount() != 1 {
		t.Fatalf("newly created account must be rolled back when db commit fails, deletes=%d", fake.deleteCount())
	}
	after, err := core.Get(svc.ID)
	if err != nil {
		t.Fatal(err)
	}
	var plain map[string]string
	_ = json.Unmarshal([]byte(after.EnvJSON), &plain)
	if plain[AgentMemoryProviderEnv] != MemoryProviderLocal {
		t.Fatalf("failed republish must leave env untouched, got %v", plain)
	}
}

// Republish 的 K8s apply 失败发生在 DB 已提交之后：env/账户已归属服务，失败只转 error
// 状态，账户绝不回滚（下次 StartAgain/Republish 复用）——修复不得误伤该语义。
func TestRepublishApplyFailureKeepsAccount(t *testing.T) {
	fake := &fakeOpenViking{}
	core, fk, _ := newVikingTestCoreWithFake(t, fake.handler())
	pkg := uploadVikingImportPkg(t, core)
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Name: "svc", Env: map[string]string{AgentMemoryProviderEnv: MemoryProviderLocal}})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	waitForVikingTerminal(t, core, svc.ID)
	fk.FailNextEnsure(1, fmt.Errorf("k8s apply failure"))

	if _, err := core.Republish(svc.ID, RepublishOptions{Env: map[string]string{
		AgentMemoryProviderEnv: MemoryProviderOpenViking,
	}}); err == nil {
		t.Fatalf("republish must fail on apply error")
	}
	if fake.deleteCount() != 0 {
		t.Fatalf("committed account belongs to the service, must not be rolled back, deletes=%d", fake.deleteCount())
	}
	after, err := core.Get(svc.ID)
	if err != nil {
		t.Fatal(err)
	}
	var plain map[string]string
	_ = json.Unmarshal([]byte(after.EnvJSON), &plain)
	if plain[AgentMemoryProviderEnv] != MemoryProviderOpenViking {
		t.Fatalf("committed env must stay, got %v", plain)
	}
}

// rollbackPublishPlaceholder 仅在占位行删除成功（RowsAffected>0）时递减 ref_count：
// 并发 Delete 已先行删行减计数时，迟到的回滚不得再误减他人引用（issue #475）。
func TestRollbackPublishPlaceholderChecksRowsAffected(t *testing.T) {
	core, _ := newVikingTestCore(t, (&fakeOpenViking{}).handler())
	pkg := uploadTestPkg(t, core, "")
	if err := core.DB.Model(&store.OafPackage{}).Where("id = ?", pkg.ID).
		UpdateColumn("ref_count", 1).Error; err != nil {
		t.Fatal(err)
	}
	svc := &store.ServiceEntity{K8sName: "oaf-ghost", DisplayName: "ghost", PackageID: pkg.ID,
		Status: store.StatusDeploying, AgentCardJSON: "{}", SkillsJSON: "[]"}
	if err := core.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	if err := core.DB.Delete(&store.ServiceEntity{}, svc.ID).Error; err != nil {
		t.Fatal(err)
	}

	core.rollbackPublishPlaceholder(svc)

	var pkgRow store.OafPackage
	if err := core.DB.First(&pkgRow, pkg.ID).Error; err != nil {
		t.Fatal(err)
	}
	if pkgRow.RefCount != 1 {
		t.Fatalf("stale placeholder rollback must not decrement ref_count owned by others, got %d", pkgRow.RefCount)
	}
}
