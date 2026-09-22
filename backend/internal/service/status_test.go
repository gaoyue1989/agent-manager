package service

import (
	"testing"
	"time"
)

// 列表关键字过滤命中 displayName 与 k8s_name。
func TestListKeywordFilter(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	if _, err := core.Publish(PublishRequest{PackageID: pkg.ID, Name: "billing-bot"}); err != nil {
		t.Fatal(err)
	}

	byK8s, err := core.List("", "oaf-billing", 0)
	if err != nil || len(byK8s) != 1 {
		t.Fatalf("filter by k8s_name: %v n=%d", err, len(byK8s))
	}
	byDisplay, err := core.List("", "billing-bot", 0)
	if err != nil || len(byDisplay) != 1 {
		t.Fatalf("filter by display_name: %v n=%d", err, len(byDisplay))
	}
	none, err := core.List("", "no-such", 0)
	if err != nil || len(none) != 0 {
		t.Fatalf("filter miss: %v n=%d", err, len(none))
	}
}

// 纯中文 displayName 清洗后为空 → 回退通用名 oaf-svc，重名由 uniqName 加后缀解决。
func TestListChineseNameFallback(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Name: "订单助手"})
	if err != nil {
		t.Fatal(err)
	}
	if svc.K8sName != "oaf-svc" {
		t.Fatalf("k8sName=%q want oaf-svc fallback", svc.K8sName)
	}
	pkg2 := uploadTestPkg(t, core, "")
	svc2, err := core.Publish(PublishRequest{PackageID: pkg2.ID, Name: "工单机器人"})
	if err != nil {
		t.Fatal(err)
	}
	if svc2.K8sName != "oaf-svc-2" {
		t.Fatalf("k8sName=%q want oaf-svc-2", svc2.K8sName)
	}
}

func TestGetByK8sName(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	if _, err := core.Publish(PublishRequest{PackageID: pkg.ID}); err != nil {
		t.Fatal(err)
	}
	got, err := core.GetByK8sName("oaf-acme-demo")
	if err != nil || got.K8sName != "oaf-acme-demo" {
		t.Fatalf("GetByK8sName: %v %+v", err, got)
	}
	if _, err := core.GetByK8sName("no-such"); err != ErrNotFound {
		t.Fatalf("expect ErrNotFound, got %v", err)
	}
}

// backoff 曲线：2s 起步指数退避，第 5 次后封顶 32s。
func TestBackoffCurve(t *testing.T) {
	cases := map[int]time.Duration{
		1: 2 * time.Second,
		2: 4 * time.Second,
		3: 8 * time.Second,
		4: 16 * time.Second,
		5: 32 * time.Second,
		9: 32 * time.Second, // 封顶不再翻倍
	}
	for attempt, want := range cases {
		if got := backoff(attempt); got != want {
			t.Errorf("backoff(%d)=%v want %v", attempt, got, want)
		}
	}
}
