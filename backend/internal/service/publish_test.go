package service

import (
	"errors"
	"fmt"
	"strings"
	"testing"

	"agent-manager/backend/internal/k8s/k8sfake"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"agent-manager/backend/internal/store"
)

// publishToRegisterFailed 走完「发布→Ready→注册失败(无真实集群URL)」确定性链路。
func publishToRegisterFailed(t *testing.T, core *Core, fk *k8sfake.FakeK8s, pkgID uint, name string) *store.ServiceEntity {
	t.Helper()
	svc, err := core.Publish(PublishRequest{PackageID: pkgID, Image: "agent-framework:latest", Name: name})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	// 注册地址改写为 127.0.0.1:1（连接拒绝即时失败）：默认的 *.svc.cluster.local 在
	// macOS 上因 .local 后缀走 mDNS 解析需数秒，会超出 waitForStatus 的 3s 窗口
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).Update("cluster_url", "http://127.0.0.1:1")
	_ = fk.SetReady("test", svc.K8sName, 1)
	got := waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
	return &got
}

// 回归锁：多服务引用同一包时，删除其中一个不得清掉仍被引用的包目录。
// 旧实现的读-改-写竞态会误判归零触发 RemovePackage，业务 Pod /config 变空
// （AGENTS.md not found CrashLoop）。修复后 refCount 原子递减 + 事务外按最终值判定。
func TestDeleteKeepsPackageWhenRefCountPositive(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svcA := publishToRegisterFailed(t, core, fk, pkg.ID, "alpha")
	svcB := publishToRegisterFailed(t, core, fk, pkg.ID, "beta")

	if got, _ := core.Packages.Get(pkg.ID); got.RefCount != 2 {
		t.Fatalf("refCount=%d want 2 after two publishes", got.RefCount)
	}

	if err := core.Delete(svcA.ID); err != nil {
		t.Fatal(err)
	}
	got, err := core.Packages.Get(pkg.ID)
	if err != nil {
		t.Fatal("package row should still exist")
	}
	if got.RefCount != 1 {
		t.Fatalf("refCount=%d want 1 after one delete", got.RefCount)
	}
	// 包目录必须完好：AGENTS.md 仍可读（svcB 的 /config 依赖它）
	if _, err := core.FS.Tree(got.DirPath); err != nil {
		t.Fatalf("package dir must survive while refCount>0: %v", err)
	}
	if _, err := core.Get(svcB.ID); err != nil {
		t.Fatalf("svcB should remain: %v", err)
	}

	// 最后一个引用释放：refCount=0 → 目录清理
	if err := core.Delete(svcB.ID); err != nil {
		t.Fatal(err)
	}
	got, err = core.Packages.Get(pkg.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.RefCount != 0 {
		t.Fatalf("refCount=%d want 0", got.RefCount)
	}
	if _, err := core.FS.Tree(got.DirPath); err == nil {
		t.Fatal("package dir should be removed after last ref released")
	}
}

func TestPublishHappyPath(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")

	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Env: map[string]string{"LOG_LEVEL": "debug"}})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if svc.K8sName != "oaf-acme-demo" || svc.Status != store.StatusDeploying {
		t.Fatalf("unexpected svc: %+v", svc)
	}
	if _, err := fk.CS().AppsV1().Deployments("test").Get(t.Context(), "oaf-acme-demo", metav1.GetOptions{}); err != nil {
		t.Fatal("deployment not created")
	}
	if _, err := fk.CS().CoreV1().Services("test").Get(t.Context(), "oaf-acme-demo-svc", metav1.GetOptions{}); err != nil {
		t.Fatal("service not created")
	}
	if _, err := fk.CS().NetworkingV1().Ingresses("test").Get(t.Context(), "oaf-acme-demo", metav1.GetOptions{}); err != nil {
		t.Fatal("ingress not created")
	}
	cm, err := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{})
	if err != nil || cm.Data["LOG_LEVEL"] != "debug" {
		t.Fatalf("configmap wrong: %+v err=%v", cm, err)
	}
	got, _ := core.Packages.Get(pkg.ID)
	if got.RefCount != 1 {
		t.Fatalf("refCount=%d", got.RefCount)
	}
	d, _ := fk.GetDeployment("test", "oaf-acme-demo")
	cs := d.Spec.Template.Spec.Containers[0]
	if cs.VolumeMounts[0].SubPath != pkg.DirPath || !cs.VolumeMounts[0].ReadOnly {
		t.Fatalf("subPath mount wrong: %+v", cs.VolumeMounts[0])
	}

	// 同 publishToRegisterFailed：改写注册地址避免 macOS mDNS 解析拖慢失败路径
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).Update("cluster_url", "http://127.0.0.1:1")
	_ = fk.SetReady("test", "oaf-acme-demo", 1)
	final := waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
	events, _ := core.Events(svc.ID)
	if len(events) == 0 {
		t.Fatal("events should be recorded")
	}
	if final.Endpoint != "http://1.2.3.4:30080/agent/acme-demo/" {
		t.Fatalf("endpoint: %q", final.Endpoint)
	}
}

func TestPublishImageNotAllowed(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	_, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "evil:tag"})
	if err != ErrImageNotAllowed {
		t.Fatalf("expected ErrImageNotAllowed, got %v", err)
	}
}

func TestPublishReservedEnvKeyRejected(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	for k := range ReservedKeySamples() {
		_, err := core.Publish(PublishRequest{PackageID: pkg.ID,
			Image: "agent-framework:latest", Env: map[string]string{k: "hack"}})
		if err == nil {
			t.Errorf("reserved key %s must fail publish", k)
		}
	}
}

func TestPublishNameConflictSuffix(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	s1, err := core.Publish(PublishRequest{PackageID: pkg.ID})
	if err != nil {
		t.Fatal(err)
	}
	pkg2 := uploadTestPkg(t, core, "")
	s2, err := core.Publish(PublishRequest{PackageID: pkg2.ID})
	if err != nil {
		t.Fatal(err)
	}
	if s1.K8sName == s2.K8sName {
		t.Fatalf("name collision unresolved: %s", s2.K8sName)
	}
	if s2.K8sName != "oaf-acme-demo-2" {
		t.Fatalf("expect suffix -2, got %q", s2.K8sName)
	}
}

func TestUpdateEnvFlow(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	updated, err := core.UpdateEnv(svc.ID, map[string]string{"LOG_LEVEL": "warn"})
	if err != nil {
		t.Fatalf("update env: %v", err)
	}
	cm, _ := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{})
	if cm.Data["LOG_LEVEL"] != "warn" {
		t.Fatalf("cm not updated: %+v", cm.Data)
	}
	if len(fk.Restarts()) != 1 {
		t.Fatalf("restart not called: %v", fk.Restarts())
	}
	waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
	_ = updated
}

// env 为全量覆盖语义：新集合必须整体替换 CM 内容，旧键不得残留
// （若实现退化为 merge，旧环境变量会继续注入业务 Pod）。
func TestUpdateEnvFullOverwriteSemantics(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	if _, err := core.UpdateEnv(svc.ID, map[string]string{"KEY_A": "1", "KEY_B": "2"}); err != nil {
		t.Fatal(err)
	}
	waitForStatus(t, core, svc.ID, store.StatusRegisterFailed) // 回到稳态再改第二次
	updated, err := core.UpdateEnv(svc.ID, map[string]string{"KEY_C": "3"})
	if err != nil {
		t.Fatal(err)
	}
	cm, _ := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{})
	if len(cm.Data) != 1 || cm.Data["KEY_C"] != "3" {
		t.Fatalf("cm should only contain KEY_C, got %+v", cm.Data)
	}
	if updated.EnvJSON != `{"KEY_C":"3"}` {
		t.Fatalf("env_json should be fully replaced, got %s", updated.EnvJSON)
	}
}

// deploying 状态禁止改 env（滚动进行中重启会互相踩踏）。
func TestUpdateEnvDeployingRejected(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID})
	if err != nil {
		t.Fatal(err)
	}
	// Publish 同步落库为 deploying，异步注册在 RegisterTimeout 后才推进
	if _, err := core.UpdateEnv(svc.ID, map[string]string{"A": "B"}); !errors.Is(err, ErrBadState) {
		t.Fatalf("expect ErrBadState while deploying, got %v", err)
	}
}

// 重启失败时 env 变更不得落库（CM 与 DB 保持一致语义由重试保证）。
func TestUpdateEnvRestartFailure(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	fk.FailNextRestart(1, errors.New("patch conflict"))
	if _, err := core.UpdateEnv(svc.ID, map[string]string{"A": "B"}); err == nil {
		t.Fatal("restart failure must propagate")
	}
	got, _ := core.Get(svc.ID)
	if got.EnvJSON != svc.EnvJSON {
		t.Fatalf("env_json must stay unchanged on restart failure: %s", got.EnvJSON)
	}
	if got.Status != store.StatusRegisterFailed {
		t.Fatalf("status must be untouched, got %s", got.Status)
	}
}

// apply 失败 → 服务落 error 状态并记录事件，而不是停留在 deploying。
func TestPublishApplyFailureMarksError(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	fk.FailNextEnsure(1, errors.New("cm quota exceeded"))

	// apply 失败时 Publish 返回 nil 记录，状态从库中断言
	if _, err := core.Publish(PublishRequest{PackageID: pkg.ID}); err == nil {
		t.Fatal("publish must fail on apply error")
	}
	var got store.ServiceEntity
	if err := core.DB.First(&got).Error; err != nil {
		t.Fatal(err)
	}
	if got.Status != store.StatusError {
		t.Fatalf("db status=%s want error", got.Status)
	}
	events, _ := core.Events(got.ID)
	if len(events) == 0 || !strings.Contains(events[0].Reason, "apply failed") {
		t.Fatalf("apply failure event missing: %+v", events)
	}

	// error 状态允许 StartAgain 恢复
	fk.FailNextEnsure(0, nil)
	if _, err := core.StartAgain(got.ID); err != nil {
		t.Fatalf("start again from error: %v", err)
	}
	// 同 publishToRegisterFailed：改写注册地址避免 macOS mDNS 解析拖慢失败路径
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", got.ID).Update("cluster_url", "http://127.0.0.1:1")
	_ = fk.SetReady("test", got.K8sName, 1)
	waitForStatus(t, core, got.ID, store.StatusRegisterFailed)
}

// republish apply 失败同样落 error（引用计数已在事务内迁移，状态必须可见）。
func TestRepublishApplyFailureMarksError(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	fk.FailNextEnsure(1, errors.New("ingress api error"))
	if _, err := core.Republish(svc.ID, RepublishOptions{}); err == nil {
		t.Fatal("republish must fail on apply error")
	}
	got, _ := core.Get(svc.ID)
	if got.Status != store.StatusError {
		t.Fatalf("status=%s want error", got.Status)
	}
}

// deploying 状态禁止 StartAgain（与 stopped/error/deploy_failed/register_failed 白名单互补）。
func TestStartAgainFromDeployingRejected(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := core.StartAgain(svc.ID); !errors.Is(err, ErrBadState) {
		t.Fatalf("expect ErrBadState while deploying, got %v", err)
	}
}

// uniqName 冲突后缀耗尽（>20 次）必须报错，不得无限循环。
func TestUniqNameCollisionLimit(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	base := "oaf-acme-demo"
	names := []string{base}
	for i := 2; i <= 20; i++ {
		names = append(names, fmt.Sprintf("%s-%d", base, i))
	}
	for _, n := range names {
		if err := core.DB.Create(&store.ServiceEntity{K8sName: n}).Error; err != nil {
			t.Fatal(err)
		}
	}
	if _, err := core.uniqName(base); err == nil || !strings.Contains(err.Error(), "too many name collisions") {
		t.Fatalf("expect collision limit error, got %v", err)
	}
}

// 冲突后缀导致名字超过 63 字符时必须报错（K8s 名长度硬限制）。
func TestUniqNameTooLong(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	long := "oaf-" + strings.Repeat("a", 59) // 恰好 63 字符
	if err := core.DB.Create(&store.ServiceEntity{K8sName: long}).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := core.uniqName(long); err == nil || !strings.Contains(err.Error(), "63") {
		t.Fatalf("expect 63-char limit error, got %v", err)
	}
}

func TestUpdateEnvStoppedRejected(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")
	if _, err := core.Unpublish(svc.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := core.UpdateEnv(svc.ID, map[string]string{"A": "B"}); err == nil {
		t.Fatal("env update on stopped service must fail")
	}
}

func TestRepublishSwitchPackage(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg1 := uploadTestPkg(t, core, "")
	pkg2 := uploadTestPkg(t, core, "2")
	svc := publishToRegisterFailed(t, core, fk, pkg1.ID, "")

	newID := pkg2.ID
	updated, err := core.Republish(svc.ID, RepublishOptions{PackageID: &newID})
	if err != nil {
		t.Fatalf("republish: %v", err)
	}
	if updated.PackageID != pkg2.ID {
		t.Fatal("packageId not switched")
	}
	g1, _ := core.Packages.Get(pkg1.ID)
	g2, _ := core.Packages.Get(pkg2.ID)
	if g1.RefCount != 0 || g2.RefCount != 1 {
		t.Fatalf("refcounts wrong: %d %d", g1.RefCount, g2.RefCount)
	}
	waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
}

func TestRepublishImageNotAllowed(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")
	bad := "evil:tag"
	if _, err := core.Republish(svc.ID, RepublishOptions{Image: &bad}); err != ErrImageNotAllowed {
		t.Fatalf("expect ErrImageNotAllowed, got %v", err)
	}
}

func TestUnpublishKeepsConfigMapAndData(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	if _, err := core.Unpublish(svc.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := fk.CS().AppsV1().Deployments("test").Get(t.Context(), "oaf-acme-demo", metav1.GetOptions{}); err == nil {
		t.Fatal("deployment should be deleted")
	}
	if _, err := fk.CS().CoreV1().Services("test").Get(t.Context(), "oaf-acme-demo-svc", metav1.GetOptions{}); err == nil {
		t.Fatal("service should be deleted")
	}
	// CM 保留
	if _, err := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{}); err != nil {
		t.Fatal("configmap must be kept on unpublish")
	}
	waitForStatus(t, core, svc.ID, store.StatusStopped)

	// publish again → 资源恢复
	if _, err := core.StartAgain(svc.ID); err != nil {
		t.Fatalf("start again: %v", err)
	}
	if _, err := fk.CS().AppsV1().Deployments("test").Get(t.Context(), "oaf-acme-demo", metav1.GetOptions{}); err != nil {
		t.Fatal("deployment should be recreated")
	}
	_ = fk.SetReady("test", "oaf-acme-demo", 1) // 重建后需重新置 Ready
	waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
}

func TestDeleteCleansEverything(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	if err := core.Delete(svc.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{}); err == nil {
		t.Fatal("configmap should be deleted")
	}
	got, err := core.Packages.Get(pkg.ID)
	if err != nil {
		t.Fatal("package row should still exist (refCount=0)")
	}
	if got.RefCount != 0 {
		t.Fatalf("refCount=%d want 0", got.RefCount)
	}
	if _, err := core.FS.Tree(got.DirPath); err == nil {
		t.Fatal("package dir should be removed after last ref released")
	}
	if _, err := core.Get(svc.ID); err != ErrNotFound {
		t.Fatalf("service row should be gone, got %v", err)
	}
	var events int64
	core.DB.Model(&store.ServiceEvent{}).Where("service_id = ?", svc.ID).Count(&events)
	if events != 0 {
		t.Fatal("events should cascade delete")
	}
}

func TestListMergesPodStatus(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svcPublished, _ := core.Publish(PublishRequest{PackageID: pkg.ID})
	_ = svcPublished
	fk.MarkPodRunning("test", "pod-1", "oaf-acme-demo")

	list, err := core.List("", "")
	if err != nil || len(list) != 1 {
		t.Fatalf("list: %v n=%d", err, len(list))
	}
	if len(list[0].Pods) != 1 || !list[0].Pods[0].Ready {
		t.Fatalf("pods merge failed: %+v", list[0].Pods)
	}
	filtered, _ := core.List(store.StatusRunning, "")
	if len(filtered) != 0 {
		t.Fatal("status filter should exclude deploying svc")
	}
}
