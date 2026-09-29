package service

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

// A2A message/send 幂等 Job 薄封装单测（travel-fulfillment §12 评审 P1-4）：
// 同键稳定 taskId 锚点、预留释放可重试、非运行态拒绝、JSON-RPC 解析。

// fakeA2aMember 模拟业务服务 A2A JSON-RPC 端点：计数收到的 message/send 次数。
func fakeA2aMember(t *testing.T, status int, rpcErr string, taskID string, calls *atomic.Int64) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		if rpcErr != "" {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"jsonrpc": "2.0", "id": 1,
				"error": map[string]any{"code": -32000, "message": rpcErr},
			})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"jsonrpc": "2.0", "id": 1,
			"result": map[string]any{"id": taskID, "kind": "task",
				"status": map[string]any{"state": "submitted"}},
		})
	}))
}

// runningService 直置 running 状态（跳过异步发布管线）并指向 fake 成员端点。
func runningService(t *testing.T, c *Core, clusterURL string) store.ServiceEntity {
	t.Helper()
	svc := &store.ServiceEntity{
		K8sName: "oaf-jobtest", DisplayName: "jobtest", Status: store.StatusRunning,
		ClusterURL: clusterURL,
	}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	return *svc
}

func TestSubmitJobFirstSubmitStoresTaskID(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	srv := fakeA2aMember(t, http.StatusOK, "", "task-9", &calls)
	defer srv.Close()
	svc := runningService(t, c, srv.URL)

	job, idempotent, err := c.SubmitJob(svc.ID, "order-2026-001", "create an order")
	if err != nil {
		t.Fatal(err)
	}
	if idempotent {
		t.Fatal("first submit must not be idempotent")
	}
	if job.TaskID != "task-9" {
		t.Fatalf("task id = %q, want task-9", job.TaskID)
	}
	if calls.Load() != 1 {
		t.Fatalf("member calls = %d, want 1", calls.Load())
	}
}

func TestSubmitJobSameKeyReturnsSameTaskWithoutResend(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	srv := fakeA2aMember(t, http.StatusOK, "", "task-9", &calls)
	defer srv.Close()
	svc := runningService(t, c, srv.URL)

	first, _, err := c.SubmitJob(svc.ID, "order-2026-001", "create an order")
	if err != nil {
		t.Fatal(err)
	}
	second, idempotent, err := c.SubmitJob(svc.ID, "order-2026-001", "create an order again")
	if err != nil {
		t.Fatal(err)
	}
	if !idempotent {
		t.Fatal("repeat submit should be idempotent")
	}
	if first.TaskID != second.TaskID {
		t.Fatalf("task id drifted: %q vs %q", first.TaskID, second.TaskID)
	}
	if calls.Load() != 1 {
		t.Fatalf("member calls = %d, want 1 (no resend)", calls.Load())
	}
	// 不同键 = 新任务
	if _, _, err := c.SubmitJob(svc.ID, "order-2026-002", "another"); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 {
		t.Fatalf("member calls = %d, want 2 (distinct key)", calls.Load())
	}
}

func TestSubmitJobSendFailureReleasesReservationForRetry(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	broken := fakeA2aMember(t, http.StatusOK, "member exploded", "", &calls)
	defer broken.Close()
	svc := runningService(t, c, broken.URL)

	if _, _, err := c.SubmitJob(svc.ID, "order-2026-001", "x"); err == nil {
		t.Fatal("expected a2a error")
	}
	// 预留已释放：同键重试指向健康成员即可成功
	healthy := fakeA2aMember(t, http.StatusOK, "", "task-retry", &calls)
	defer healthy.Close()
	c.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).Update("cluster_url", healthy.URL)

	job, _, err := c.SubmitJob(svc.ID, "order-2026-001", "x")
	if err != nil {
		t.Fatal(err)
	}
	if job.TaskID != "task-retry" {
		t.Fatalf("retry task id = %q", job.TaskID)
	}
}

func TestSubmitJobRejectsNonRunningAndBadInput(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	svc := &store.ServiceEntity{K8sName: "oaf-idle", DisplayName: "idle", Status: store.StatusDeployFailed}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.SubmitJob(svc.ID, "k1", "x"); err == nil {
		t.Fatal("non-running service must be rejected")
	}
	if _, _, err := c.SubmitJob(svc.ID, "", "x"); err == nil {
		t.Fatal("empty key must be rejected")
	}
	if _, _, err := c.SubmitJob(svc.ID, "k1", "  "); err == nil {
		t.Fatal("blank text must be rejected")
	}
}

func TestGetJobReturnsMapping(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	srv := fakeA2aMember(t, http.StatusOK, "", "task-7", &calls)
	defer srv.Close()
	svc := runningService(t, c, srv.URL)

	if _, _, err := c.SubmitJob(svc.ID, "k-get", "x"); err != nil {
		t.Fatal(err)
	}
	job, err := c.GetJob(svc.ID, "k-get")
	if err != nil {
		t.Fatal(err)
	}
	if job.TaskID != "task-7" {
		t.Fatalf("task id = %q", job.TaskID)
	}
	if _, err := c.GetJob(svc.ID, "missing"); err == nil {
		t.Fatal("missing key should not be found")
	} else if err != gorm.ErrRecordNotFound {
		t.Fatalf("want ErrRecordNotFound, got %v", err)
	}
}
