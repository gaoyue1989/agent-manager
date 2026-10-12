// router 模式启动自检的外部测试（package k8s_test）：k8sfake 依赖 k8s 包，
// 内部测试包（package k8s）引用会成 import cycle。
package k8s_test

import (
	"context"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/k8s/k8sfake"
)

// TestRequireRouter router 模式启动自检：Service 存在放行，缺失报错（指向 manifest）。
func TestRequireRouter(t *testing.T) {
	f := k8sfake.New()
	if err := k8s.RequireRouter(f, "test"); err == nil {
		t.Fatal("missing router service must fail")
	}
	svc := &corev1.Service{ObjectMeta: metav1.ObjectMeta{Name: k8s.RouterServiceName, Namespace: "test"}}
	if _, err := f.CS().CoreV1().Services("test").Create(context.TODO(), svc, metav1.CreateOptions{}); err != nil {
		t.Fatal(err)
	}
	if err := k8s.RequireRouter(f, "test"); err != nil {
		t.Fatalf("existing router service must pass: %v", err)
	}
}
