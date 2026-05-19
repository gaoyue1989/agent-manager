package k8s

import (
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
)

type PodStatusInfo struct {
	PodName  string `json:"pod_name"`
	Status   string `json:"status"`
	Ready    string `json:"ready"`
	Restarts string `json:"restarts"`
	Age      string `json:"age"`
	IP       string `json:"pod_ip"`
	Node     string `json:"node"`
}

type SandboxClient struct {
	namespace    string
	ingressHost  string
	ingressEnabled bool
}

func NewSandboxClient() (*SandboxClient, error) {
	return &SandboxClient{
		namespace:      "default",
		ingressHost:    "localhost",
		ingressEnabled: true,
	}, nil
}

func NewSandboxClientWithIngress(ingressHost string, ingressEnabled bool) (*SandboxClient, error) {
	return &SandboxClient{
		namespace:      "default",
		ingressHost:    ingressHost,
		ingressEnabled: ingressEnabled,
	}, nil
}

func (s *SandboxClient) CreateSandbox(name, image, llmAPIKey, llmModel, llmEndpoint string) error {
	yaml := fmt.Sprintf(`apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: %s
  namespace: %s
  labels:
    app: %s
spec:
  podTemplate:
    metadata:
      labels:
        app: %s
    spec:
      containers:
      - name: agent
        image: %s
        ports:
        - containerPort: 8000
        env:
        - name: LLM_API_KEY
          value: "%s"
        - name: LLM_MODEL
          value: "%s"
        - name: LLM_ENDPOINT
          value: "%s"
        - name: HTTP_PROXY
          value: "http://172.20.0.1:7890"
        - name: HTTPS_PROXY
          value: "http://172.20.0.1:7890"
`, name, s.namespace, name, name, image, llmAPIKey, llmModel, llmEndpoint)

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

func (s *SandboxClient) CreateService(name string) error {
	return s.CreateServiceWithPort(name, 8000)
}

func (s *SandboxClient) CreateServiceWithPort(name string, port int) error {
	svcYaml := fmt.Sprintf(`apiVersion: v1
kind: Service
metadata:
  name: %s-svc
  namespace: %s
spec:
  selector:
    app: %s
  ports:
  - port: %d
    targetPort: %d
`, name, s.namespace, name, port, port)

	cmd := exec.Command("kubectl", "apply", "-f", "-")
	cmd.Stdin = strings.NewReader(svcYaml)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl apply service: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) DeleteService(name string) error {
	svcName := name + "-svc"
	cmd := exec.Command("kubectl", "delete", "service", svcName, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl delete service: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) CreateIngress(name string, agentID uint) (string, error) {
	return s.CreateIngressWithPort(name, agentID, 8000)
}

func (s *SandboxClient) CreateIngressWithPort(name string, agentID uint, port int) (string, error) {
	if !s.ingressEnabled {
		return "", nil
	}

	ingressName := name + "-ingress"
	svcName := name + "-svc"
	path := fmt.Sprintf("/agent/%d", agentID)
	endpointURL := fmt.Sprintf("http://%s%s", s.ingressHost, path)

	ingressYaml := fmt.Sprintf(`apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: %s
  namespace: %s
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
spec:
  ingressClassName: nginx
  rules:
  - http:
      paths:
      - path: %s(/|$)(.*)
        pathType: Prefix
        backend:
          service:
            name: %s
            port:
              number: %d
`, ingressName, s.namespace, path, svcName, port)

	cmd := exec.Command("kubectl", "apply", "-f", "-")
	cmd.Stdin = strings.NewReader(ingressYaml)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("kubectl apply ingress: %s\n%s", err.Error(), string(out))
	}
	return endpointURL, nil
}

func (s *SandboxClient) DeleteIngress(name string) error {
	if !s.ingressEnabled {
		return nil
	}

	ingressName := name + "-ingress"
	cmd := exec.Command("kubectl", "delete", "ingress", ingressName, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl delete ingress: %s\n%s", err.Error(), string(out))
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
	cmd := exec.Command("kubectl", "get", "pods", "-n", s.namespace, "-l", fmt.Sprintf("app=%s", sandboxName),
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
		fmt.Sprintf("app=%s", sandboxName), "-o", "json")
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

func (s *SandboxClient) GetServiceEndpoint(name string) (string, error) {
	svcName := name + "-svc"
	cmd := exec.Command("kubectl", "get", "service", svcName, "-n", s.namespace,
		"-o", "jsonpath={.spec.clusterIP}:{.spec.ports[0].port}")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("kubectl get service: %s\n%s", err.Error(), string(out))
	}
	return strings.TrimSpace(string(out)), nil
}

func (s *SandboxClient) SandboxExists(name string) bool {
	cmd := exec.Command("kubectl", "get", "sandbox", name, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(out)) != ""
}

func (s *SandboxClient) ServiceExists(name string) bool {
	svcName := name + "-svc"
	cmd := exec.Command("kubectl", "get", "service", svcName, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(out)) != ""
}

func (s *SandboxClient) IngressExists(name string) bool {
	ingressName := name + "-ingress"
	cmd := exec.Command("kubectl", "get", "ingress", ingressName, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(out)) != ""
}

func (s *SandboxClient) CreateConfigMap(name string, data map[string]string) error {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf(`apiVersion: v1
kind: ConfigMap
metadata:
  name: %s
  namespace: %s
data:
`, name, s.namespace))

	for key, value := range data {
		sb.WriteString(fmt.Sprintf("  %s: |\n", key))
		for _, line := range strings.Split(value, "\n") {
			sb.WriteString(fmt.Sprintf("    %s\n", line))
		}
	}

	cmd := exec.Command("kubectl", "apply", "-f", "-")
	cmd.Stdin = strings.NewReader(sb.String())
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl apply configmap: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) DeleteConfigMap(name string) error {
	cmd := exec.Command("kubectl", "delete", "configmap", name, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl delete configmap: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) ConfigMapExists(name string) bool {
	cmd := exec.Command("kubectl", "get", "configmap", name, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(out)) != ""
}

func (s *SandboxClient) CreateSecret(name string, stringData map[string]string) error {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf(`apiVersion: v1
kind: Secret
metadata:
  name: %s
  namespace: %s
type: Opaque
stringData:
`, name, s.namespace))

	for key, value := range stringData {
		sb.WriteString(fmt.Sprintf("  %s: %s\n", key, value))
	}

	cmd := exec.Command("kubectl", "apply", "-f", "-")
	cmd.Stdin = strings.NewReader(sb.String())
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl apply secret: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) DeleteSecret(name string) error {
	cmd := exec.Command("kubectl", "delete", "secret", name, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl delete secret: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (s *SandboxClient) SecretExists(name string) bool {
	cmd := exec.Command("kubectl", "get", "secret", name, "-n", s.namespace, "--ignore-not-found")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(out)) != ""
}

func (s *SandboxClient) CreateSandboxWithMounts(name, image, configMapName, secretName string, envVars map[string]string, checkpointDSN string) error {
	envLines := ""
	for key, value := range envVars {
		if key == "LLM_MODEL_ID" || key == "LLM_BASE_URL" {
			continue
		}
		envLines += fmt.Sprintf("        - name: %s\n          value: \"%s\"\n", key, escapeK8sValue(value))
	}

	yaml := fmt.Sprintf(`apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: %s
  namespace: %s
  labels:
    app: %s
spec:
  podTemplate:
    metadata:
      labels:
        app: %s
    spec:
      containers:
      - name: agent
        image: %s
        ports:
        - containerPort: 8100
        env:
        - name: AGENT_CONFIG_DIR
          value: "/config"
        - name: LLM_API_KEY
          valueFrom:
            secretKeyRef:
              name: %s
              key: LLM_API_KEY
        - name: LLM_MODEL_ID
          value: "%s"
        - name: LLM_BASE_URL
          value: "%s"
        - name: LLM_PROVIDER
          value: "openai"
        - name: CHECKPOINT_MYSQL_DSN
          value: "%s"
        - name: SERVER_HOST
          value: "0.0.0.0"
        - name: SERVER_PORT
          value: "8100"
%s
        volumeMounts:
        - name: config-volume
          mountPath: /config
      volumes:
      - name: config-volume
        configMap:
          name: %s
`, name, s.namespace, name, name, image, secretName,
		envVars["LLM_MODEL_ID"], envVars["LLM_BASE_URL"], escapeK8sValue(checkpointDSN),
		envLines, configMapName)

	cmd := exec.Command("kubectl", "apply", "-f", "-")
	cmd.Stdin = strings.NewReader(yaml)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl apply: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func escapeK8sValue(value string) string {
	return strings.ReplaceAll(value, "\"", "\\\"")
}
