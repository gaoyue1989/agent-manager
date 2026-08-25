package k8s

import (
	"context"
	"fmt"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	networkingv1 "k8s.io/api/networking/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/util/retry"
)

// Client 集群操作接口（service 层依赖此抽象，测试注入 fake）。
type Client interface {
	Namespace() string
	EnsureConfigMap(cm *corev1.ConfigMap) error
	EnsureDeployment(d *appsv1.Deployment) error
	RestartDeployment(ctx context.Context, ns, name string) error // rollout restart
	EnsureService(svc *corev1.Service) error
	EnsureIngress(ing *networkingv1.Ingress) error

	DeleteDeployment(ns, name string) error
	DeleteService(ns, name string) error
	DeleteIngress(ns, name string) error
	DeleteConfigMap(ns, name string) error

	GetDeployment(ns, name string) (*appsv1.Deployment, error)
	PodStatuses(ns, labelSelector string) ([]PodInfo, error)
	WaitReady(ctx context.Context, ns, name string, timeout time.Duration) error
}

// PodInfo 实时 Pod 状态摘要。
type PodInfo struct {
	Name     string `json:"name"`
	Phase    string `json:"phase"`
	Ready    bool   `json:"ready"`
	Restarts int32  `json:"restarts"`
}

// RealClient 基于 client-go typed clientset 的实现。
type RealClient struct {
	cs *kubernetes.Clientset
	ns string
}

// NewInCluster 构造集群内客户端；kubeconfigPath 非空时回退本地开发模式。
func NewInCluster(kubeconfigPath string) (*RealClient, error) {
	var (
		cfg *rest.Config
		err error
	)
	if kubeconfigPath != "" {
		cfg, err = loadKubeconfig(kubeconfigPath)
	} else {
		cfg, err = rest.InClusterConfig()
	}
	if err != nil {
		return nil, fmt.Errorf("load k8s config: %w", err)
	}
	cs, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return nil, err
	}
	return &RealClient{cs: cs}, nil
}

func (c *RealClient) WithNamespace(ns string) *RealClient {
	c.ns = ns
	return c
}

func (c *RealClient) Namespace() string { return c.ns }

func (c *RealClient) EnsureConfigMap(cm *corev1.ConfigMap) error {
	existing, err := c.cs.CoreV1().ConfigMaps(cm.Namespace).Get(context.TODO(), cm.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = c.cs.CoreV1().ConfigMaps(cm.Namespace).Create(context.TODO(), cm, metav1.CreateOptions{})
		return err
	}
	if err != nil {
		return err
	}
	existing.Data = cm.Data
	existing.Labels = cm.Labels
	_, err = c.cs.CoreV1().ConfigMaps(cm.Namespace).Update(context.TODO(), existing, metav1.UpdateOptions{})
	return err
}

func (c *RealClient) EnsureDeployment(d *appsv1.Deployment) error {
	existing, err := c.cs.AppsV1().Deployments(d.Namespace).Get(context.TODO(), d.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = c.cs.AppsV1().Deployments(d.Namespace).Create(context.TODO(), d, metav1.CreateOptions{})
		return err
	}
	if err != nil {
		return err
	}
	d.Annotations = existing.Annotations // 保留 restartedAt 等注解
	existing.Spec = d.Spec
	existing.Labels = d.Labels
	_, err = c.cs.AppsV1().Deployments(d.Namespace).Update(context.TODO(), existing, metav1.UpdateOptions{})
	return err
}

// RestartDeployment patch template annotation 触发滚动重启。
func (c *RealClient) RestartDeployment(ctx context.Context, ns, name string) error {
	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		d, err := c.cs.AppsV1().Deployments(ns).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return err
		}
		if d.Spec.Template.ObjectMeta.Annotations == nil {
			d.Spec.Template.ObjectMeta.Annotations = map[string]string{}
		}
		d.Spec.Template.ObjectMeta.Annotations["kubectl.kubernetes.io/restartedAt"] = time.Now().Format(time.RFC3339)
		_, err = c.cs.AppsV1().Deployments(ns).Update(ctx, d, metav1.UpdateOptions{})
		return err
	})
}

func (c *RealClient) EnsureService(svc *corev1.Service) error {
	existing, err := c.cs.CoreV1().Services(svc.Namespace).Get(context.TODO(), svc.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = c.cs.CoreV1().Services(svc.Namespace).Create(context.TODO(), svc, metav1.CreateOptions{})
		return err
	}
	if err != nil {
		return err
	}
	existing.Spec.Selector = svc.Spec.Selector
	existing.Spec.Ports = svc.Spec.Ports
	existing.Labels = svc.Labels
	_, err = c.cs.CoreV1().Services(svc.Namespace).Update(context.TODO(), existing, metav1.UpdateOptions{})
	return err
}

func (c *RealClient) EnsureIngress(ing *networkingv1.Ingress) error {
	existing, err := c.cs.NetworkingV1().Ingresses(ing.Namespace).Get(context.TODO(), ing.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = c.cs.NetworkingV1().Ingresses(ing.Namespace).Create(context.TODO(), ing, metav1.CreateOptions{})
		return err
	}
	if err != nil {
		return err
	}
	existing.Spec = ing.Spec
	existing.Annotations = ing.Annotations
	existing.Labels = ing.Labels
	_, err = c.cs.NetworkingV1().Ingresses(ing.Namespace).Update(context.TODO(), existing, metav1.UpdateOptions{})
	return err
}

func (c *RealClient) DeleteDeployment(ns, name string) error {
	err := c.cs.AppsV1().Deployments(ns).Delete(context.TODO(), name, metav1.DeleteOptions{})
	return ignoreNotFound(err)
}

func (c *RealClient) DeleteService(ns, name string) error {
	err := c.cs.CoreV1().Services(ns).Delete(context.TODO(), name, metav1.DeleteOptions{})
	return ignoreNotFound(err)
}

func (c *RealClient) DeleteIngress(ns, name string) error {
	err := c.cs.NetworkingV1().Ingresses(ns).Delete(context.TODO(), name, metav1.DeleteOptions{})
	return ignoreNotFound(err)
}

func (c *RealClient) DeleteConfigMap(ns, name string) error {
	err := c.cs.CoreV1().ConfigMaps(ns).Delete(context.TODO(), name, metav1.DeleteOptions{})
	return ignoreNotFound(err)
}

func (c *RealClient) GetDeployment(ns, name string) (*appsv1.Deployment, error) {
	return c.cs.AppsV1().Deployments(ns).Get(context.TODO(), name, metav1.GetOptions{})
}

func (c *RealClient) PodStatuses(ns, labelSelector string) ([]PodInfo, error) {
	pods, err := c.cs.CoreV1().Pods(ns).List(context.TODO(), metav1.ListOptions{LabelSelector: labelSelector})
	if err != nil {
		return nil, err
	}
	var out []PodInfo
	for _, p := range pods.Items {
		info := PodInfo{Name: p.Name, Phase: string(p.Status.Phase)}
		for _, cs := range p.Status.ContainerStatuses {
			info.Restarts += cs.RestartCount
			if cs.Ready {
				info.Ready = true
			}
		}
		out = append(out, info)
	}
	return out, nil
}

// WaitReady 轮询 Deployment 完成滚动更新：世代对齐、新版本副本全部就绪且无不可用副本。
// 仅判 readyReplicas 会在滚动更新期间误判（旧 Pod 仍在服务），导致注册打到旧实例。
func (c *RealClient) WaitReady(ctx context.Context, ns, name string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		d, err := c.GetDeployment(ns, name)
		if err == nil && rolloutComplete(d) {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("deployment %s/%s not ready within %s", ns, name, timeout)
		}
		time.Sleep(2 * time.Second)
	}
}

// rolloutComplete 判定 Deployment 是否完成完整滚动更新。
func rolloutComplete(d *appsv1.Deployment) bool {
	want := int32(1)
	if d.Spec.Replicas != nil {
		want = *d.Spec.Replicas
	}
	return d.Generation <= d.Status.ObservedGeneration &&
		d.Status.UpdatedReplicas >= want &&
		d.Status.ReadyReplicas >= want &&
		d.Status.UnavailableReplicas == 0
}

func ignoreNotFound(err error) error {
	if apierrors.IsNotFound(err) {
		return nil
	}
	return err
}
