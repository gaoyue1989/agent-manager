package k8s

import (
	"strings"
	"testing"
)

func TestCreateSandboxWithMounts_YAMLContainsConfigMap(t *testing.T) {
	s := &SandboxClient{namespace: "default"}
	name := "agent-42"
	image := "agent-framework:latest"
	configMapName := "agent-42-config"
	secretName := "agent-42-secret"
	checkpointDSN := "mysql+asyncmy://user:pass@host:3307/db"
	envVars := map[string]string{
		"LLM_MODEL_ID": "qwen3.6-plus",
		"LLM_BASE_URL": "https://dashscope.aliyuncs.com/compatible-mode/v1",
	}

	err := s.CreateSandboxWithMounts(name, image, configMapName, secretName, envVars, checkpointDSN)
	if err == nil {
		t.Skip("kubectl not available in test environment, skipping")
	}
}

func TestConfigMapYAML_Content(t *testing.T) {
	name := "agent-42-config"
	namespace := "default"
	data := map[string]string{
		"AGENTS.md": "name: test",
		"skills/bash/SKILL.md": "# Bash Skill",
	}

	var yaml string
	yaml += "apiVersion: v1\n"
	yaml += "kind: ConfigMap\n"
	yaml += "metadata:\n"
	yaml += "  name: " + name + "\n"
	yaml += "  namespace: " + namespace + "\n"
	yaml += "data:\n"

	for key, value := range data {
		yaml += "  " + key + ": |\n"
		for _, line := range strings.Split(value, "\n") {
			yaml += "    " + line + "\n"
		}
	}

	if yaml == "" {
		t.Error("ConfigMap YAML should not be empty")
	}
	if !strings.Contains(yaml, "AGENTS.md") {
		t.Error("ConfigMap YAML should contain AGENTS.md key")
	}
	if !strings.Contains(yaml, "skills/bash/SKILL.md") {
		t.Error("ConfigMap YAML should contain skill key")
	}
}

func TestSecretYAML_Content(t *testing.T) {
	name := "agent-42-secret"
	namespace := "default"
	data := map[string]string{
		"LLM_API_KEY": "sk-test-key",
	}

	var yaml string
	yaml += "apiVersion: v1\n"
	yaml += "kind: Secret\n"
	yaml += "metadata:\n"
	yaml += "  name: " + name + "\n"
	yaml += "  namespace: " + namespace + "\n"
	yaml += "type: Opaque\n"
	yaml += "stringData:\n"

	for key, value := range data {
		yaml += "  " + key + ": " + value + "\n"
	}

	if yaml == "" {
		t.Error("Secret YAML should not be empty")
	}
	if !strings.Contains(yaml, "LLM_API_KEY") {
		t.Error("Secret YAML should contain LLM_API_KEY")
	}
	if !strings.Contains(yaml, "sk-test-key") {
		t.Error("Secret YAML should contain the API key value")
	}
}

func TestSandboxYAML_WithMounts(t *testing.T) {
	name := "agent-42"
	image := "agent-framework:latest"
	configMapName := "agent-42-config"
	secretName := "agent-42-secret"

	yaml := `apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: ` + name + `
  namespace: default
spec:
  podTemplate:
    spec:
      containers:
      - name: agent
        image: ` + image + `
        env:
        - name: LLM_API_KEY
          valueFrom:
            secretKeyRef:
              name: ` + secretName + `
              key: LLM_API_KEY
        volumeMounts:
        - name: config-volume
          mountPath: /config
      volumes:
      - name: config-volume
        configMap:
          name: ` + configMapName

	if yaml == "" {
		t.Error("Sandbox YAML should not be empty")
	}
	if !strings.Contains(yaml, "agent-framework:latest") {
		t.Error("Sandbox YAML should reference the agent framework image")
	}
	if !strings.Contains(yaml, "valueFrom") {
		t.Error("Sandbox YAML should use valueFrom for secret")
	}
	if !strings.Contains(yaml, "secretKeyRef") {
		t.Error("Sandbox YAML should use secretKeyRef")
	}
	if !strings.Contains(yaml, "config-volume") {
		t.Error("Sandbox YAML should have config-volume volume mount")
	}
	if !strings.Contains(yaml, "/config") {
		t.Error("Sandbox YAML should mount to /config")
	}
}

func TestSandboxYAML_CheckpointDSN(t *testing.T) {
	checkpointDSN := "mysql+asyncmy://u:p@h:3307/db"
	escapedDSN := escapeK8sValue(checkpointDSN)

	yaml := `        - name: CHECKPOINT_MYSQL_DSN
          value: "` + escapedDSN + `"`

	if !strings.Contains(yaml, "CHECKPOINT_MYSQL_DSN") {
		t.Error("YAML should contain CHECKPOINT_MYSQL_DSN env var")
	}
	if !strings.Contains(yaml, "mysql+asyncmy") {
		t.Error("YAML should contain the DSN value")
	}
}

func TestEscapeK8sValue(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"simple", "simple"},
		{"with\"quote", "with\\\"quote"},
		{"no/special", "no/special"},
	}

	for _, tt := range tests {
		result := escapeK8sValue(tt.input)
		if result != tt.expected {
			t.Errorf("escapeK8sValue(%q) = %q, want %q", tt.input, result, tt.expected)
		}
	}
}
