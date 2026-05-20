package k8s

import (
	"encoding/json"
	"fmt"
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
	client         K8sClient
	namespace      string
	ingressHost    string
	ingressEnabled bool
	ingressClass   string
}

// NewSandboxClient 创建 Sandbox 客户端（向后兼容）
func NewSandboxClient() (*SandboxClient, error) {
	return NewSandboxClientWithConfig(NewKubectlClient("", "default"), "default", "localhost", true, "nginx")
}

// NewSandboxClientWithIngress 带 Ingress 配置（向后兼容）
func NewSandboxClientWithIngress(ingressHost string, ingressEnabled bool) (*SandboxClient, error) {
	return NewSandboxClientWithConfig(NewKubectlClient("", "default"), "default", ingressHost, ingressEnabled, "nginx")
}

// NewSandboxClientWithConfig 完整配置构造函数
func NewSandboxClientWithConfig(client K8sClient, namespace, ingressHost string, ingressEnabled bool, ingressClass string) (*SandboxClient, error) {
	return &SandboxClient{
		client:         client,
		namespace:      namespace,
		ingressHost:    ingressHost,
		ingressEnabled: ingressEnabled,
		ingressClass:   ingressClass,
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

	return s.client.ApplyYAML(yaml)
}

func (s *SandboxClient) DeleteSandbox(name string) error {
	return s.client.DeleteResource("sandbox", name)
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

	return s.client.ApplyYAML(svcYaml)
}

func (s *SandboxClient) DeleteService(name string) error {
	svcName := name + "-svc"
	return s.client.DeleteResource("service", svcName)
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
  ingressClassName: %s
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
`, ingressName, s.namespace, s.ingressClass, path, svcName, port)

	if err := s.client.ApplyYAML(ingressYaml); err != nil {
		return "", fmt.Errorf("kubectl apply ingress: %w", err)
	}
	return endpointURL, nil
}

func (s *SandboxClient) DeleteIngress(name string) error {
	if !s.ingressEnabled {
		return nil
	}
	ingressName := name + "-ingress"
	return s.client.DeleteResource("ingress", ingressName)
}

func (s *SandboxClient) GetSandboxStatus(name string) (string, error) {
	return s.client.GetResourceJSON("sandbox", name,
		"jsonpath={.status.conditions[0].message}")
}

func (s *SandboxClient) GetPodStatus(sandboxName string) (*PodStatusInfo, error) {
	raw, err := s.client.ListPodsJSON(fmt.Sprintf("app=%s", sandboxName))
	if err != nil {
		return nil, err
	}
	return s.parsePodStatus(raw, sandboxName)
}

func (s *SandboxClient) parsePodStatus(raw string, sandboxName string) (*PodStatusInfo, error) {
	jsonStr, err := parsePodStatusJSON(raw, sandboxName)
	if err != nil {
		return nil, err
	}

	var result map[string]interface{}
	if err := json.Unmarshal([]byte(jsonStr), &result); err != nil {
		return nil, fmt.Errorf("parse pod status: %w", err)
	}

	return &PodStatusInfo{
		PodName:  result["pod_name"].(string),
		Status:   result["status"].(string),
		Ready:    result["ready"].(string),
		Restarts: fmt.Sprintf("%d", int(result["restarts"].(float64))),
	}, nil
}

func (s *SandboxClient) GetPodStatusJSON(sandboxName string) (string, error) {
	raw, err := s.client.ListPodsJSON(fmt.Sprintf("app=%s", sandboxName))
	if err != nil {
		return "", err
	}
	return parsePodStatusJSON(raw, sandboxName)
}

func (s *SandboxClient) GetServiceEndpoint(name string) (string, error) {
	return s.client.GetServiceEndpoint(name + "-svc")
}

func (s *SandboxClient) SandboxExists(name string) bool {
	return s.client.ResourceExists("sandbox", name)
}

func (s *SandboxClient) ServiceExists(name string) bool {
	svcName := name + "-svc"
	return s.client.ResourceExists("service", svcName)
}

func (s *SandboxClient) IngressExists(name string) bool {
	ingressName := name + "-ingress"
	return s.client.ResourceExists("ingress", ingressName)
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

	return s.client.ApplyYAML(sb.String())
}

func (s *SandboxClient) DeleteConfigMap(name string) error {
	return s.client.DeleteResource("configmap", name)
}

func (s *SandboxClient) ConfigMapExists(name string) bool {
	return s.client.ResourceExists("configmap", name)
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

	return s.client.ApplyYAML(sb.String())
}

func (s *SandboxClient) DeleteSecret(name string) error {
	return s.client.DeleteResource("secret", name)
}

func (s *SandboxClient) SecretExists(name string) bool {
	return s.client.ResourceExists("secret", name)
}

func (s *SandboxClient) ExecInPod(podName string, command ...string) ([]byte, error) {
	return s.client.ExecCommand(podName, command...)
}

func (s *SandboxClient) CreateSandboxWithMounts(name, image, configMapName, secretName string, envVars map[string]string, checkpointDSN string) error {
	envLines := ""
	for key, value := range envVars {
		if key == "LLM_MODEL_ID" || key == "LLM_BASE_URL" {
			continue
		}
			envLines += fmt.Sprintf("        - name: %s\n          value: \"%s\"\n", key, escapeK8sValue(value))
	}

	checkpointEnv := ""
	if checkpointDSN != "" {
		checkpointEnv = fmt.Sprintf("        - name: CHECKPOINT_MYSQL_DSN\n          value: \"%s\"\n", escapeK8sValue(checkpointDSN))
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
%s
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
		envVars["LLM_MODEL_ID"], envVars["LLM_BASE_URL"], checkpointEnv,
		envLines, configMapName)

	return s.client.ApplyYAML(yaml)
}
