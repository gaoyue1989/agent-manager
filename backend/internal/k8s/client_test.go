package k8s

import (
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func rolloutFixture() *appsv1.Deployment {
	replicas := int32(2)
	return &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: "d", Generation: 2},
		Spec:       appsv1.DeploymentSpec{Replicas: &replicas},
		Status: appsv1.DeploymentStatus{
			ObservedGeneration: 2, UpdatedReplicas: 2, ReadyReplicas: 2, UnavailableReplicas: 0,
		},
	}
}

// 回归锁：滚动更新完成判定必须四条件齐备（世代对齐 + 新版本副本达标 + 就绪达标 + 无不可用）。
// 仅判 ReadyReplicas 会在滚动更新期间误判（旧 Pod 仍在服务），导致注册打到旧实例。
func TestRolloutComplete(t *testing.T) {
	t.Run("all conditions met", func(t *testing.T) {
		if !rolloutComplete(rolloutFixture()) {
			t.Fatal("expected complete")
		}
	})
	t.Run("generation not observed", func(t *testing.T) {
		d := rolloutFixture()
		d.Status.ObservedGeneration = 1
		if rolloutComplete(d) {
			t.Fatal("generation behind must not be complete")
		}
	})
	t.Run("updated replicas short", func(t *testing.T) {
		d := rolloutFixture()
		d.Status.UpdatedReplicas = 1 // 旧版本 Pod 仍在服务的滚动中间态
		if rolloutComplete(d) {
			t.Fatal("old-pods-serving must not be complete")
		}
	})
	t.Run("ready replicas short", func(t *testing.T) {
		d := rolloutFixture()
		d.Status.ReadyReplicas = 1
		if rolloutComplete(d) {
			t.Fatal("not enough ready replicas must not be complete")
		}
	})
	t.Run("unavailable present", func(t *testing.T) {
		d := rolloutFixture()
		d.Status.UnavailableReplicas = 1
		if rolloutComplete(d) {
			t.Fatal("unavailable replicas must not be complete")
		}
	})
	t.Run("nil replicas defaults to one", func(t *testing.T) {
		d := rolloutFixture()
		d.Spec.Replicas = nil
		d.Status.UpdatedReplicas, d.Status.ReadyReplicas = 1, 1
		if !rolloutComplete(d) {
			t.Fatal("default replicas=1 should be complete")
		}
		d.Status.ReadyReplicas = 0
		if rolloutComplete(d) {
			t.Fatal("zero ready with default replicas must not be complete")
		}
	})
}
