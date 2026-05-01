package k8s

import (
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
)

type PodStatusInfo struct {
	PodName   string `json:"pod_name"`
	Status    string `json:"status"`
	Ready     string `json:"ready"`
	Restarts  string `json:"restarts"`
	Age       string `json:"age"`
	IP        string `json:"pod_ip"`
	Node      string `json:"node"`
}

type SandboxClient struct {
	namespace string
}

func NewSandboxClient() (*SandboxClient, error) {
	return &SandboxClient{namespace: "default"}, nil
}

func (s *SandboxClient) CreateSandbox(name, image string) error {
	yaml := fmt.Sprintf(`apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: %s
  namespace: %s
spec:
  podTemplate:
    spec:
      containers:
      - name: agent
        image: %s
        ports:
        - containerPort: 8000
        env:
        - name: LLM_API_KEY
          value: "sk-0440b76852944f019bb142a715bc2cab"
        - name: HTTP_PROXY
          value: "http://172.20.0.1:7890"
        - name: HTTPS_PROXY
          value: "http://172.20.0.1:7890"
`, name, s.namespace, image)

	cmd := exec.Command("kubectl", "apply", "-f", "-")
	cmd.Stdin = strings.NewReader(yaml)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl apply: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) DeleteSandbox(name string) error {
	cmd := exec.Command("kubectl", "delete", "sandbox", name, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl delete: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) GetSandboxStatus(name string) (string, error) {
	cmd := exec.Command("kubectl", "get", "sandbox", name, "-n", s.namespace, "-o", "jsonpath={.status.conditions[0].message}")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("kubectl get: %s\n%s", err.Error(), string(out))
	}
	return strings.TrimSpace(string(out)), nil
}

func (s *SandboxClient) GetPodStatus(sandboxName string) (*PodStatusInfo, error) {
	jsonpath := `{range .items[*]}{.metadata.name}{"|"}{.status.phase}{"|"}{.status.containerStatuses[0].ready}{"|"}{.status.containerStatuses[0].restartCount}{"|"}{.metadata.creationTimestamp}{"|"}{.status.podIP}{"|"}{.spec.nodeName}{end}`
	cmd := exec.Command("kubectl", "get", "pods", "-n", s.namespace, "-l", fmt.Sprintf("agents.x-k8s.io/sandbox-name-hash"),
		"-o", fmt.Sprintf("jsonpath=%s", jsonpath))
	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("kubectl get pods: %s\n%s", err.Error(), string(out))
	}
	output := strings.TrimSpace(string(out))
	if output == "" {
		return nil, fmt.Errorf("pod not found for sandbox %s", sandboxName)
	}

	parts := strings.SplitN(output, "|", 7)
	if len(parts) < 6 {
		return nil, fmt.Errorf("unexpected pod info format: %s", output)
	}

	return &PodStatusInfo{
		PodName:  parts[0],
		Status:   parts[1],
		Ready:    parts[2],
		Restarts: parts[3],
		Age:      parts[4],
		IP:       parts[5],
		Node:     "",
	}, nil
}

func (s *SandboxClient) GetPodStatusJSON(sandboxName string) (string, error) {
	cmd := exec.Command("kubectl", "get", "pods", "-n", s.namespace, "-l",
		fmt.Sprintf("agents.x-k8s.io/sandbox-name-hash"), "-o", "json")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("kubectl get pods json: %s\n%s", err.Error(), string(out))
	}

	var result map[string]interface{}
	if err := json.Unmarshal(out, &result); err != nil {
		return "", fmt.Errorf("parse pod json: %w", err)
	}

	items, ok := result["items"].([]interface{})
	if !ok || len(items) == 0 {
		return "", fmt.Errorf("no pods found for sandbox %s", sandboxName)
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
