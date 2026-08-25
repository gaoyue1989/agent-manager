package service

import (
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
	_ = fk.SetReady("test", svc.K8sName, 1)
	got := waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
	return &got
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
