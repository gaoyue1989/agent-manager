package k8s

import (
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
)

// K8sClient 统一 K8s 资源操作接口，屏蔽 kubectl CLI 与 client-go SDK 差异
type K8sClient interface {
	// ApplyYAML 通过 kubectl apply -f - 或 dynamic client Create/Update 应用 YAML
	ApplyYAML(yamlStr string) error
	// DeleteResource 按 kind + name 删除资源
	DeleteResource(kind, name string) error
	// ResourceExists 检查资源是否存在
	ResourceExists(kind, name string) bool
	// GetResourceJSON 通过 jsonpath 查询资源属性
	GetResourceJSON(kind, name, jsonpath string) (string, error)
	// ListPodsJSON 按 label 查询 pods，返回 kubectl JSON 输出
	ListPodsJSON(labelSelector string) (string, error)
	// ExecCommand 在指定 pod 内执行命令
	ExecCommand(podName string, command ...string) ([]byte, error)
	// GetServiceEndpoint 查询 Service ClusterIP:Port
	GetServiceEndpoint(name string) (string, error)
}

// NewK8sClient 工厂函数，根据 clientMode 创建 kubectl 或 API 客户端
// 当前默认使用 kubectl 客户端（无需额外依赖）
// 若需启用 client-go SDK 模式，设置 K8S_CLIENT_MODE=api 并参见 api_client.go
func NewK8sClient(clientMode, kubeconfig, namespace string) (K8sClient, error) {
	return NewKubectlClient(kubeconfig, namespace), nil
}

// ============================================================
// KubectlClient — 基于 kubectl CLI 的实现
// ============================================================

type KubectlClient struct {
	kubeconfig string
	namespace  string
}

func NewKubectlClient(kubeconfig, namespace string) *KubectlClient {
	return &KubectlClient{kubeconfig: kubeconfig, namespace: namespace}
}

func (c *KubectlClient) kubectl(args ...string) *exec.Cmd {
	base := []string{}
	if c.kubeconfig != "" {
		base = append(base, "--kubeconfig", c.kubeconfig)
	}
	if c.namespace != "" {
		base = append(base, "-n", c.namespace)
	}
	return exec.Command("kubectl", append(base, args...)...)
}

func (c *KubectlClient) ApplyYAML(yamlStr string) error {
	cmd := c.kubectl("apply", "-f", "-")
	cmd.Stdin = strings.NewReader(yamlStr)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl apply: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (c *KubectlClient) DeleteResource(kind, name string) error {
	cmd := c.kubectl("delete", kind, name, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl delete %s %s: %s\n%s", kind, name, err.Error(), string(out))
	}
	return nil
}

func (c *KubectlClient) ResourceExists(kind, name string) bool {
	cmd := c.kubectl("get", kind, name, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(out)) != ""
}

func (c *KubectlClient) GetResourceJSON(kind, name, jsonpath string) (string, error) {
	cmd := c.kubectl("get", kind, name, "-o", jsonpath)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("kubectl get %s %s: %s\n%s", kind, name, err.Error(), string(out))
	}
	return strings.TrimSpace(string(out)), nil
}

func (c *KubectlClient) ListPodsJSON(labelSelector string) (string, error) {
	cmd := c.kubectl("get", "pods", "-l", labelSelector, "-o", "json")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("kubectl get pods: %s\n%s", err.Error(), string(out))
	}
	return string(out), nil
}

func (c *KubectlClient) ExecCommand(podName string, command ...string) ([]byte, error) {
	args := []string{"exec", podName, "--"}
	args = append(args, command...)
	cmd := c.kubectl(args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("kubectl exec: %s\n%s", err.Error(), string(out))
	}
	return out, nil
}

func (c *KubectlClient) GetServiceEndpoint(name string) (string, error) {
	return c.GetResourceJSON("service", name,
		"jsonpath={.spec.clusterIP}:{.spec.ports[0].port}")
}

// parsePodStatusJSON 从 kubectl get pods -o json 输出解析 PodStatusInfo
func parsePodStatusJSON(raw string, sandboxName string) (string, error) {
	var result map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		return "", fmt.Errorf("parse pod json: %w", err)
	}

	items, ok := result["items"].([]interface{})
	if !ok || len(items) == 0 {
		return "", fmt.Errorf("no pods found for %s", sandboxName)
	}

	pod := items[0].(map[string]interface{})
	podMeta := pod["metadata"].(map[string]interface{})
	podName := podMeta["name"].(string)
	podStatus := pod["status"].(map[string]interface{})

	phase := podStatus["phase"].(string)
	podIP := ""
	if ip, ok := podStatus["podIP"].(string); ok {
		podIP = ip
	}

	ready := "false"
	restarts := float64(0)
	containerStatuses, ok := podStatus["containerStatuses"].([]interface{})
	if ok && len(containerStatuses) > 0 {
		cs := containerStatuses[0].(map[string]interface{})
		if r, ok := cs["ready"].(bool); ok && r {
			ready = "true"
		}
		if rc, ok := cs["restartCount"].(float64); ok {
			restarts = rc
		}
	}

	resultJSON, _ := json.Marshal(map[string]interface{}{
		"pod_name": podName,
		"status":   phase,
		"ready":    ready,
		"restarts": int(restarts),
		"pod_ip":   podIP,
	})

	return string(resultJSON), nil
}
