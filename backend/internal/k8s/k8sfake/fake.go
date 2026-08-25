// Package k8sfake 提供基于 client-go/testing 的 k8s.Client 假实现（测试专用）。
package k8sfake

import (
	"context"
	"fmt"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	networkingv1 "k8s.io/api/networking/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"agent-manager/backend/internal/k8s"
)

// FakeK8s 实现 k8s.Client 接口。
type FakeK8s struct {
	cs       *fake.Clientset
	ns       string
	restarts []string // 记录 RestartDeployment 调用
}

func New() *FakeK8s { return &FakeK8s{cs: fake.NewSimpleClientset(), ns: "test"} }

func (f *FakeK8s) CS() *fake.Clientset { return f.cs }
func (f *FakeK8s) Namespace() string   { return f.ns }
func (f *FakeK8s) Restarts() []string  { return f.restarts }

func ignoreNF(err error) error {
	if apierrors.IsNotFound(err) {
		return nil
	}
	return err
}

func (f *FakeK8s) EnsureConfigMap(cm *corev1.ConfigMap) error {
	old, err := f.cs.CoreV1().ConfigMaps(cm.Namespace).Get(context.TODO(), cm.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = f.cs.CoreV1().ConfigMaps(cm.Namespace).Create(context.TODO(), cm, metav1.CreateOptions{})
		return err
	}
	if err != nil {
		return err
	}
	old.Data = cm.Data
	old.Labels = cm.Labels
	_, err = f.cs.CoreV1().ConfigMaps(cm.Namespace).Update(context.TODO(), old, metav1.UpdateOptions{})
	return err
}

func (f *FakeK8s) EnsureDeployment(d *appsv1.Deployment) error {
	old, err := f.cs.AppsV1().Deployments(d.Namespace).Get(context.TODO(), d.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = f.cs.AppsV1().Deployments(d.Namespace).Create(context.TODO(), d, metav1.CreateOptions{})
		return err
	}
	if err != nil {
		return err
	}
	d.Status = old.Status
	d.Annotations = old.Annotations
	_, err = f.cs.AppsV1().Deployments(d.Namespace).Update(context.TODO(), d, metav1.UpdateOptions{})
	return err
}

func (f *FakeK8s) RestartDeployment(_ context.Context, ns, name string) error {
	f.restarts = append(f.restarts, ns+"/"+name)
	return nil
}

func (f *FakeK8s) EnsureService(svc *corev1.Service) error {
	_, err := f.cs.CoreV1().Services(svc.Namespace).Get(context.TODO(), svc.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = f.cs.CoreV1().Services(svc.Namespace).Create(context.TODO(), svc, metav1.CreateOptions{})
		return err
	}
	return err
}

func (f *FakeK8s) EnsureIngress(ing *networkingv1.Ingress) error {
	_, err := f.cs.NetworkingV1().Ingresses(ing.Namespace).Get(context.TODO(), ing.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = f.cs.NetworkingV1().Ingresses(ing.Namespace).Create(context.TODO(), ing, metav1.CreateOptions{})
		return err
	}
	return err
}

func (f *FakeK8s) DeleteDeployment(ns, name string) error {
	return ignoreNF(f.cs.AppsV1().Deployments(ns).Delete(context.TODO(), name, metav1.DeleteOptions{}))
}
func (f *FakeK8s) DeleteService(ns, name string) error {
	return ignoreNF(f.cs.CoreV1().Services(ns).Delete(context.TODO(), name, metav1.DeleteOptions{}))
}
func (f *FakeK8s) DeleteIngress(ns, name string) error {
	return ignoreNF(f.cs.NetworkingV1().Ingresses(ns).Delete(context.TODO(), name, metav1.DeleteOptions{}))
}
func (f *FakeK8s) DeleteConfigMap(ns, name string) error {
	return ignoreNF(f.cs.CoreV1().ConfigMaps(ns).Delete(context.TODO(), name, metav1.DeleteOptions{}))
}

func (f *FakeK8s) GetDeployment(ns, name string) (*appsv1.Deployment, error) {
	return f.cs.AppsV1().Deployments(ns).Get(context.TODO(), name, metav1.GetOptions{})
}

func (f *FakeK8s) PodStatuses(ns, labelSelector string) ([]k8s.PodInfo, error) {
	pods, err := f.cs.CoreV1().Pods(ns).List(context.TODO(), metav1.ListOptions{LabelSelector: labelSelector})
	if err != nil {
		return nil, err
	}
	var out []k8s.PodInfo
	for _, p := range pods.Items {
		out = append(out, k8s.PodInfo{Name: p.Name, Phase: string(p.Status.Phase), Ready: p.Status.Phase == corev1.PodRunning})
	}
	return out, nil
}

// SetReady 将 Deployment 状态置为完整滚动更新完成。
func (f *FakeK8s) SetReady(ns, name string, replicas int32) error {
	d, err := f.cs.AppsV1().Deployments(ns).Get(context.TODO(), name, metav1.GetOptions{})
	if err != nil {
		return err
	}
	d.Status.ObservedGeneration = d.Generation
	d.Status.UpdatedReplicas = replicas
	d.Status.ReadyReplicas = replicas
	d.Status.UnavailableReplicas = 0
	_, err = f.cs.AppsV1().Deployments(ns).Update(context.TODO(), d, metav1.UpdateOptions{})
	return err
}

// MarkPodRunning 注入一个 Running Pod。
func (f *FakeK8s) MarkPodRunning(ns, name, appLabel string) {
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns, Labels: map[string]string{k8s.LabelKey: appLabel}},
		Status:     corev1.PodStatus{Phase: corev1.PodRunning},
	}
	_, _ = f.cs.CoreV1().Pods(ns).Create(context.TODO(), pod, metav1.CreateOptions{})
}

// WaitReady 轮询 fake 中的 Deployment 状态。
func (f *FakeK8s) WaitReady(ctx context.Context, ns, name string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		d, err := f.cs.AppsV1().Deployments(ns).Get(ctx, name, metav1.GetOptions{})
		if err == nil && d.Status.ReadyReplicas >= *d.Spec.Replicas {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("deployment %s/%s not ready within %s", ns, name, timeout)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

var _ k8s.Client = (*FakeK8s)(nil)
