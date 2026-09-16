package service

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

const cardJSON = `{
  "name": "Demo Agent",
  "version": "1.0.0",
  "description": "demo",
  "protocolVersion": "1.0.0",
  "url": "http://x:8100/",
  "skills": [{"id": "s1", "name": "echo"}],
  "capabilities": {"streaming": true}
}`

// mockFramework 模拟 agent-framework 的 agent-card + health 端点。
func mockFramework(t *testing.T, cardStatus int) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/agent-card.json", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(cardStatus)
		_, _ = w.Write([]byte(cardJSON))
	})
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	return httptest.NewServer(mux)
}

func TestFetchCardSuccess(t *testing.T) {
	srv := mockFramework(t, http.StatusOK)
	defer srv.Close()
	card, err := fetchCard(srv.URL)
	if err != nil {
		t.Fatalf("fetchCard: %v", err)
	}
	if card.Name != "Demo Agent" || card.Version != "1.0.0" || card.ProtocolVersion != "1.0.0" {
		t.Fatalf("card parse wrong: %+v", card)
	}
}

func TestFetchCardBadStatus(t *testing.T) {
	srv := mockFramework(t, http.StatusNotFound)
	defer srv.Close()
	if _, err := fetchCard(srv.URL); err == nil {
		t.Fatal("404 must fail")
	}
}

func TestRegisterPersistsCard(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc, _ := core.Publish(PublishRequest{PackageID: pkg.ID})

	srv := mockFramework(t, http.StatusOK)
	defer srv.Close()
	// 先改库再置 Ready：异步注册在就绪后会重读最新 cluster_url
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).Update("cluster_url", srv.URL)

	_ = fk.SetReady("test", svc.K8sName, 1)
	waitForStatus(t, core, svc.ID, store.StatusRunning)
	got, _ := core.Get(svc.ID)
	if got.RegisteredName != "Demo Agent" || got.RegisteredVersion != "1.0.0" {
		t.Fatalf("registered fields wrong: %+v", got)
	}
	if got.AgentCardJSON == "" || got.RegisteredAt == nil {
		t.Fatal("card json / registeredAt missing")
	}
	var skills string
	core.DB.Model(&store.ServiceEntity{}).Select("skills_json").Where("id = ?", svc.ID).Scan(&skills)
	if skills == "" {
		t.Fatal("skills_json not saved")
	}
}

func TestReregisterRecoversToRunning(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	srv := mockFramework(t, http.StatusOK)
	defer srv.Close()
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).Update("cluster_url", srv.URL)
	svc.ClusterURL = srv.URL

	got, err := core.Reregister(svc.ID)
	if err != nil {
		t.Fatalf("reregister: %v", err)
	}
	if got.Status != store.StatusRunning {
		t.Fatalf("status after re-register: %s", got.Status)
	}
}

// /health 检查失败同样视为未就绪（agent-card 200 但 health 500）。
func TestFetchCardHealthFailure(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/agent-card.json", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(cardJSON))
	})
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	_, err := fetchCard(srv.URL)
	if err == nil || !strings.Contains(err.Error(), "health http 503") {
		t.Fatalf("expect health http 503 error, got %v", err)
	}
}

// agent-card 返回非法 JSON → 解析失败（而非把空卡片入库）。
func TestFetchCardBadJSON(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/agent-card.json", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("{not-json"))
	})
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	_, err := fetchCard(srv.URL)
	if err == nil || !strings.Contains(err.Error(), "parse agent-card") {
		t.Fatalf("expect parse error, got %v", err)
	}
}

// 手动重注册失败：状态维持 register_failed 并返回错误（不得误跳 running）。
func TestReregisterFailureKeepsRegisterFailed(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	// cluster_url 指向不可达地址 → 重试耗尽
	got, err := core.Reregister(svc.ID)
	if err == nil {
		t.Fatal("reregister against unreachable cluster url must fail")
	}
	if got.Status != store.StatusRegisterFailed {
		t.Fatalf("status=%s want register_failed", got.Status)
	}
	// 数据库中的状态也未被推进
	fresh, _ := core.Get(svc.ID)
	if fresh.Status != store.StatusRegisterFailed {
		t.Fatalf("db status=%s want register_failed", fresh.Status)
	}
}
