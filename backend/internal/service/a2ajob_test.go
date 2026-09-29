package service

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

// A2A message/send 幂等 Job 薄封装单测（travel-fulfillment §12 评审 P1-4 + CR P0-1/P1 修订）：
// 认领独占发送权（并发不双发）、确定未受理释放可重试、结果未知保留认领（409 收敛）、
// 认领租期接管、输入校验。

// fakeA2aMember 模拟业务服务 A2A JSON-RPC 端点：计数收到的 message/send 次数，
// status 非 200 或 rpcErr 非空时返回错误；hangMs>0 时阻塞后再返回。
func fakeA2aMember(t *testing.T, status int, rpcErr string, taskID string, calls *atomic.Int64, hangMs int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if hangMs > 0 {
			time.Sleep(time.Duration(hangMs) * time.Millisecond)
		}
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

// setSendTimeout 覆盖发送超时（Core 字段，测试用）
func setSendTimeout(c *Core, d time.Duration) { c.A2ASendTimeout = d }

func jobRows(t *testing.T, c *Core, serviceID uint, key string) []store.A2aJob {
	t.Helper()
	var rows []store.A2aJob
	if err := c.DB.Where("service_id = ? AND idempotency_key = ?", serviceID, key).
		Find(&rows).Error; err != nil {
		t.Fatal(err)
	}
	return rows
}

func TestSubmitJobFirstSubmitStoresTaskID(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	srv := fakeA2aMember(t, http.StatusOK, "", "task-9", &calls, 0)
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
	srv := fakeA2aMember(t, http.StatusOK, "", "task-9", &calls, 0)
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
	if first.TaskID != second.TaskID || second.TaskID != "task-9" {
		t.Fatalf("task id drifted: %q vs %q", first.TaskID, second.TaskID)
	}
	if calls.Load() != 1 {
		t.Fatalf("member calls = %d, want 1 (no resend)", calls.Load())
	}
	if _, _, err := c.SubmitJob(svc.ID, "order-2026-002", "another"); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 {
		t.Fatalf("member calls = %d, want 2 (distinct key)", calls.Load())
	}
}

// 确定未受理（JSON-RPC error）：释放预留（行数为 0），同键重试成功——
// P1-3 修订：显式断言行删除，防止"复用空行"路径掩盖释放语义回归。
func TestSubmitJobDefiniteRejectReleasesReservation(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	broken := fakeA2aMember(t, http.StatusOK, "member exploded", "", &calls, 0)
	defer broken.Close()
	svc := runningService(t, c, broken.URL)

	if _, _, err := c.SubmitJob(svc.ID, "order-2026-001", "x"); err == nil {
		t.Fatal("expected a2a error")
	}
	// 释放语义 = 认领清空（行保留、task_id 复位空串），同键重试可重新认领
	rows := jobRows(t, c, svc.ID, "order-2026-001")
	if len(rows) != 1 || rows[0].TaskID != "" {
		t.Fatalf("claim must be released (row kept, empty task_id), got %+v", rows)
	}
	healthy := fakeA2aMember(t, http.StatusOK, "", "task-retry", &calls, 0)
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

// 结果未知（超时）：保留认领 + 同键重试得 ErrJobInFlight（不双发），成员只收到一次
func TestSubmitJobUnknownOutcomeKeepsClaimAndConverges(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	// 挂起 400ms 后成功：首次发送在 200ms 超时处中断（结果未知），成员稍后完成受理
	slow := fakeA2aMember(t, http.StatusOK, "", "task-slow", &calls, 400)
	defer slow.Close()
	svc := runningService(t, c, slow.URL)
	setSendTimeout(c, 200*time.Millisecond)

	if _, _, err := c.SubmitJob(svc.ID, "order-001", "x"); !errors.Is(err, ErrJobInFlight) {
		t.Fatalf("unknown outcome should surface ErrJobInFlight, got %v", err)
	}
	// 认领保留：行存在、task_id 为 claim 形态
	rows := jobRows(t, c, svc.ID, "order-001")
	if len(rows) != 1 || !strings.HasPrefix(rows[0].TaskID, "claim:") {
		t.Fatalf("claim must be retained, rows=%+v", rows)
	}
	// 同键立即重试：409 进行中，成员不再被调用
	if _, _, err := c.SubmitJob(svc.ID, "order-001", "x"); !errors.Is(err, ErrJobInFlight) {
		t.Fatalf("retry during claim should be ErrJobInFlight, got %v", err)
	}
	if calls.Load() != 1 {
		t.Fatalf("member calls = %d, want 1 (no double send)", calls.Load())
	}
	// GetJob 脱敏：认领行 task_id 视图 = 空串
	job, err := c.GetJob(svc.ID, "order-001")
	if err != nil {
		t.Fatal(err)
	}
	if job.TaskID != "" {
		t.Fatalf("claim row must be masked to empty, got %q", job.TaskID)
	}
}

// 并发同键：认领独占发送权——成员恰好收到一次，输家 ErrJobInFlight（CR P0-1 双发探针回归）
func TestSubmitJobConcurrentSameKeySendsExactlyOnce(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	srv := fakeA2aMember(t, http.StatusOK, "", "task-conc", &calls, 150)
	defer srv.Close()
	svc := runningService(t, c, srv.URL)

	var wg sync.WaitGroup
	errs := make([]error, 2)
	wg.Add(2)
	for i := 0; i < 2; i++ {
		go func(i int) {
			defer wg.Done()
			_, _, errs[i] = c.SubmitJob(svc.ID, "conc-key", "x")
		}(i)
	}
	wg.Wait()

	if calls.Load() != 1 {
		t.Fatalf("member calls = %d, want exactly 1", calls.Load())
	}
	inFlight := 0
	for _, err := range errs {
		if err == nil {
			continue
		}
		if errors.Is(err, ErrJobInFlight) {
			inFlight++
		} else {
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if inFlight != 1 {
		t.Fatalf("want exactly 1 loser with ErrJobInFlight, got %d (errs=%v)", inFlight, errs)
	}
}

// 认领租期过期可接管：崩溃残留自愈（新认领者发送成功并回填）
func TestSubmitJobExpiredClaimIsTakenOver(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	srv := fakeA2aMember(t, http.StatusOK, "", "task-takeover", &calls, 0)
	defer srv.Close()
	svc := runningService(t, c, srv.URL)

	stale := time.Now().Add(-2 * time.Hour)
	c.DB.Create(&store.A2aJob{ServiceID: svc.ID, IdempotencyKey: "stale-key",
		TaskID: "claim:deadbeef", ReservedAt: &stale})

	job, idempotent, err := c.SubmitJob(svc.ID, "stale-key", "x")
	if err != nil {
		t.Fatal(err)
	}
	if idempotent {
		t.Fatal("expired claim takeover is a fresh submission")
	}
	if job.TaskID != "task-takeover" {
		t.Fatalf("takeover task id = %q", job.TaskID)
	}
	if calls.Load() != 1 {
		t.Fatalf("member calls = %d, want 1", calls.Load())
	}
}

func TestSubmitJobRejectsInvalidInputAndBadState(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	svc := &store.ServiceEntity{K8sName: "oaf-idle", DisplayName: "idle", Status: store.StatusDeployFailed}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.SubmitJob(svc.ID, "k1", "x"); !errors.Is(err, ErrBadState) {
		t.Fatalf("non-running service should be ErrBadState, got %v", err)
	}
	if _, _, err := c.SubmitJob(svc.ID, "", "x"); !errors.Is(err, ErrJobInvalid) {
		t.Fatalf("empty key should be ErrJobInvalid, got %v", err)
	}
	if _, _, err := c.SubmitJob(svc.ID, "bad key!", "x"); !errors.Is(err, ErrJobInvalid) {
		t.Fatalf("url-unsafe key should be ErrJobInvalid, got %v", err)
	}
	if _, _, err := c.SubmitJob(svc.ID, "k1", "  "); !errors.Is(err, ErrJobInvalid) {
		t.Fatalf("blank text should be ErrJobInvalid, got %v", err)
	}
	if _, _, err := c.SubmitJob(svc.ID, "k1", strings.Repeat("x", 33<<10)); !errors.Is(err, ErrJobInvalid) {
		t.Fatalf("oversize text should be ErrJobInvalid, got %v", err)
	}
}

func TestGetJobReturnsMapping(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	var calls atomic.Int64
	srv := fakeA2aMember(t, http.StatusOK, "", "task-7", &calls, 0)
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
	if _, err := c.GetJob(svc.ID, "missing"); !errors.Is(err, gorm.ErrRecordNotFound) {
		t.Fatalf("want ErrRecordNotFound, got %v", err)
	}
}
