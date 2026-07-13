//go:build api

// api_client.go — 基于 client-go SDK 的 K8s API 客户端实现
// 启用方式：
//   1. 确保网络可访问 Go 代理
//   2. 运行: go get k8s.io/client-go@latest && go mod tidy
//   3. 构建: go build -tags api ./cmd/server
//   4. 设置环境变量: K8S_CLIENT_MODE=api

package k8s

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"

	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/util/yaml"
	"k8s.io/client-go/discovery"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	//
	// 以下为 "k8s.io/client-go/kubernetes" 子包的显式导入，供 go mod tidy 发现：
	// _ "k8s.io/client-go/kubernetes/typed/apps/v1"
	// _ "k8s.io/client-go/kubernetes/typed/core/v1"
	// _ "k8s.io/client-go/kubernetes/typed/networking/v1"
)

// K8sAPIClient 基于 client-go dynamic client 的 K8s API 客户端
type K8sAPIClient struct {
	dynamicClient   dynamic.Interface
	typedClient     kubernetes.Interface
	discoveryClient discovery.DiscoveryInterface
	namespace       string
	gvrCache        map[string]schema.GroupVersionResource
	mu              sync.RWMutex
}

func newK8sAPIClient(kubeconfigPath, namespace string) (K8sClient, error) {
	loadingRules := clientcmd.NewDefaultClientConfigLoadingRules()
	if kubeconfigPath != "" {
		loadingRules.ExplicitPath = kubeconfigPath
	}
	configOverrides := &clientcmd.ConfigOverrides{}
	kubeConfig := clientcmd.NewNonInteractiveDeferredLoadingClientConfig(loadingRules, configOverrides)

	restConfig, err := kubeConfig.ClientConfig()
	if err != nil {
		return nil, fmt.Errorf("build rest config: %w", err)
	}

	dynamicClient, err := dynamic.NewForConfig(restConfig)
	if err != nil {
		return nil, fmt.Errorf("create dynamic client: %w", err)
	}

	typedClient, err := kubernetes.NewForConfig(restConfig)
	if err != nil {
		return nil, fmt.Errorf("create typed client: %w", err)
	}

	discoveryClient, err := discovery.NewDiscoveryClientForConfig(restConfig)
	if err != nil {
		return nil, fmt.Errorf("create discovery client: %w", err)
	}

	return &K8sAPIClient{
		dynamicClient:   dynamicClient,
		typedClient:     typedClient,
		discoveryClient: discoveryClient,
		namespace:       namespace,
		gvrCache:        make(map[string]schema.GroupVersionResource),
	}, nil
}

func (c *K8sAPIClient) ApplyYAML(yamlStr string) error {
	decoder := yaml.NewYAMLToJSONDecoder(strings.NewReader(yamlStr))

	var obj unstructured.Unstructured
	if err := decoder.Decode(&obj); err != nil {
		return fmt.Errorf("decode yaml: %w", err)
	}

	gvr, err := c.resolveGVR(obj.GetAPIVersion(), obj.GetKind())
	if err != nil {
		return fmt.Errorf("resolve gvr for %s/%s: %w", obj.GetAPIVersion(), obj.GetKind(), err)
	}

	ctx := context.Background()
	ns := c.namespace
	if obj.GetNamespace() != "" {
		ns = obj.GetNamespace()
	}

	existing, err := c.dynamicClient.Resource(gvr).Namespace(ns).Get(ctx, obj.GetName(), metav1.GetOptions{})
	if err != nil {
		if errors.IsNotFound(err) {
			_, err = c.dynamicClient.Resource(gvr).Namespace(ns).Create(ctx, &obj, metav1.CreateOptions{})
			return err
		}
		return fmt.Errorf("get resource: %w", err)
	}

	obj.SetResourceVersion(existing.GetResourceVersion())
	_, err = c.dynamicClient.Resource(gvr).Namespace(ns).Update(ctx, &obj, metav1.UpdateOptions{})
	return err
}

func (c *K8sAPIClient) DeleteResource(kind, name string) error {
	gvr, err := c.resolveGVRKind(kind)
	if err != nil {
		return err
	}
	ctx := context.Background()
	return c.dynamicClient.Resource(gvr).Namespace(c.namespace).Delete(ctx, name, metav1.DeleteOptions{})
}

func (c *K8sAPIClient) ResourceExists(kind, name string) bool {
	gvr, err := c.resolveGVRKind(kind)
	if err != nil {
		return false
	}
	ctx := context.Background()
	_, err = c.dynamicClient.Resource(gvr).Namespace(c.namespace).Get(ctx, name, metav1.GetOptions{})
	return err == nil
}

func (c *K8sAPIClient) GetResourceJSON(kind, name, jsonpath string) (string, error) {
	gvr, err := c.resolveGVRKind(kind)
	if err != nil {
		return "", err
	}
	ctx := context.Background()
	obj, err := c.dynamicClient.Resource(gvr).Namespace(c.namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return "", err
	}
	raw, _ := json.Marshal(obj)
	return string(raw), nil
}

func (c *K8sAPIClient) ListPodsJSON(labelSelector string) (string, error) {
	ctx := context.Background()
	list, err := c.typedClient.CoreV1().Pods(c.namespace).List(ctx, metav1.ListOptions{
		LabelSelector: labelSelector,
	})
	if err != nil {
		return "", err
	}
	raw, _ := json.Marshal(list)
	return string(raw), nil
}

func (c *K8sAPIClient) ExecCommand(podName string, command ...string) ([]byte, error) {
	// ExecCommand 需要 SPDY 连接，当前通过 kubectl 回退实现
	// 生产环境可通过 client-go remotecommand 包实现
	return nil, fmt.Errorf("exec not supported via API client, use kubectl mode")
}

func (c *K8sAPIClient) GetServiceEndpoint(name string) (string, error) {
	ctx := context.Background()
	svc, err := c.typedClient.CoreV1().Services(c.namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return "", err
	}
	if len(svc.Spec.Ports) == 0 {
		return "", fmt.Errorf("service %s has no ports", name)
	}
	return fmt.Sprintf("%s:%d", svc.Spec.ClusterIP, svc.Spec.Ports[0].Port), nil
}

func (c *K8sAPIClient) GetConfigMap(name string) (map[string]string, error) {
	ctx := context.Background()
	cm, err := c.typedClient.CoreV1().ConfigMaps(c.namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return nil, err
	}
	result := make(map[string]string)
	for k, v := range cm.Data {
		result[k] = v
	}
	for k, v := range cm.BinaryData {
		result[k] = string(v)
	}
	return result, nil
}

var knownGVRKinds = map[string]schema.GroupVersionResource{
	"deployment":  {Group: "apps", Version: "v1", Resource: "deployments"},
	"service":     {Group: "", Version: "v1", Resource: "services"},
	"ingress":     {Group: "networking.k8s.io", Version: "v1", Resource: "ingresses"},
	"configmap":   {Group: "", Version: "v1", Resource: "configmaps"},
	"secret":      {Group: "", Version: "v1", Resource: "secrets"},
	"sandbox":     {Group: "agents.x-k8s.io", Version: "v1alpha1", Resource: "sandboxes"},
	"pod":         {Group: "", Version: "v1", Resource: "pods"},
	"namespace":   {Group: "", Version: "v1", Resource: "namespaces"},
}

func (c *K8sAPIClient) resolveGVRKind(kind string) (schema.GroupVersionResource, error) {
	lowerKind := strings.ToLower(kind)
	if gvr, ok := knownGVRKinds[lowerKind]; ok {
		return gvr, nil
	}
	return schema.GroupVersionResource{}, fmt.Errorf("unknown resource kind: %s", kind)
}

func (c *K8sAPIClient) resolveGVR(apiVersion, kind string) (schema.GroupVersionResource, error) {
	cacheKey := apiVersion + "/" + kind
	c.mu.RLock()
	if gvr, ok := c.gvrCache[cacheKey]; ok {
		c.mu.RUnlock()
		return gvr, nil
	}
	c.mu.RUnlock()

	gv, err := schema.ParseGroupVersion(apiVersion)
	if err != nil {
		return schema.GroupVersionResource{}, fmt.Errorf("parse apiVersion %q: %w", apiVersion, err)
	}

	ctx := context.Background()
	resources, err := c.discoveryClient.ServerResourcesForGroupVersion(apiVersion)
	if err != nil {
		// 回退：用已知映射
		c.mu.RLock()
		if gvr, ok := c.gvrCache[cacheKey]; ok {
			c.mu.RUnlock()
			return gvr, nil
		}
		c.mu.RUnlock()
		return schema.GroupVersionResource{}, fmt.Errorf("discover %s: %w", apiVersion, err)
	}

	for _, r := range resources.APIResources {
		if strings.EqualFold(r.Kind, kind) {
			gvr := schema.GroupVersionResource{
				Group:    gv.Group,
				Version:  gv.Version,
				Resource: r.Name,
			}
			c.mu.Lock()
			c.gvrCache[cacheKey] = gvr
			c.mu.Unlock()
			return gvr, nil
		}
	}

	return schema.GroupVersionResource{}, fmt.Errorf("kind %q not found in %s", kind, apiVersion)
}
