package k8s

import (
	"fmt"
	"os/exec"
	"strings"
)

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
