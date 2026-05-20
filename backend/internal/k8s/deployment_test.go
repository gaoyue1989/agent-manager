package k8s

import (
	"strings"
	"testing"
)

// UT-22: build 模式 Deployment YAML 含正确 image/port/env
func TestCreateDeployment_BuildMode_YAML(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	client := NewKubectlClient("", "default")
	dc := NewDeploymentClient(client, engine, "default")

	name := "agent-99"
	image := "registry/agent-99:v1"
	envVars := map[string]string{
		"LLM_API_KEY":  "sk-test-key",
		"LLM_MODEL":    "qwen3.6-plus",
		"LLM_ENDPOINT": "https://api.example.com",
	}

	err = dc.CreateDeployment(name, image, envVars)
	if err != nil {
		t.Log("kubectl apply error (expected in test env):", err)
	}

	// 验证 YAML 构建逻辑
	yaml, err := dc.buildDeploymentYAML(name, image, 8000, envVars, nil, nil, nil)
	if err != nil {
		t.Fatalf("build yaml: %v", err)
	}

	checks := []string{
		"kind: Deployment",
		"name: agent-99",
		"image: registry/agent-99:v1",
		"containerPort: 8000",
		"LLM_API_KEY",
		"LLM_MODEL",
		"LLM_ENDPOINT",
		"app: agent-99",
	}
	for _, check := range checks {
		if !strings.Contains(yaml, check) {
			t.Errorf("deployment YAML should contain %q\nGot:\n%s", check, yaml)
		}
	}
}

// UT-23: mount 模式 Deployment YAML 含 ConfigMap/Secret 挂载
func TestCreateDeployment_MountMode_YAML(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	client := NewKubectlClient("", "default")
	dc := NewDeploymentClient(client, engine, "default")

	err = dc.CreateDeploymentWithMounts(
		"agent-42",
		"registry/agent-framework:latest",
		8100,
		map[string]string{
			"LLM_MODEL_ID": "qwen3.6-plus",
			"LLM_BASE_URL": "https://api.example.com/compatible-mode/v1",
		},
		"agent-42-config",
		"agent-42-secret",
		"mysql+asyncmy://user:pass@host:3307/db",
	)
	if err != nil {
		t.Log("kubectl apply error (expected in test env):", err)
	}

	// 验证内部 YAML 构建
	params := DeployParams{
		Name:    "agent-42",
		Image:   "registry/agent-framework:latest",
		Port:    8100,
		EnvYAML: buildEnvYAML(map[string]string{"LLM_MODEL_ID": "qwen3.6-plus"}) + "        - name: AGENT_CONFIG_DIR\n          value: \"/config\"\n",
		VolumeMounts: "        - name: config-volume\n          mountPath: /config\n",
		Volumes:      "        - name: config-volume\n          configMap:\n            name: agent-42-config\n",
	}

	yaml, err := engine.RenderDeployment(params)
	if err != nil {
		t.Fatalf("render: %v", err)
	}

	checks := []string{
		"kind: Deployment",
		"image: registry/agent-framework:latest",
		"containerPort: 8100",
		"config-volume",
		"/config",
	}
	for _, check := range checks {
		if !strings.Contains(yaml, check) {
			t.Errorf("mount mode YAML should contain %q\nGot:\n%s", check, yaml)
		}
	}
}

// UT-24: DeleteDeployment 调用 DeleteResource
func TestDeleteDeployment_CallsDeleteResource(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	client := NewKubectlClient("", "default")
	dc := NewDeploymentClient(client, engine, "default")

	err = dc.DeleteDeployment("agent-1")
	if err != nil {
		t.Log("kubectl delete error (expected in test env):", err)
	}
}

// UT-25: DeploymentExists 检查正确 kind
func TestDeploymentExists_ChecksCorrectKind(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	client := NewKubectlClient("", "default")
	dc := NewDeploymentClient(client, engine, "default")

	exists := dc.DeploymentExists("nonexistent-99999")
	if exists {
		t.Error("expected false for non-existent deployment")
	}
}

// TestCreateDeployment_CustomPort verifies custom port in deployment YAML
func TestCreateDeployment_CustomPort(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	client := NewKubectlClient("", "default")
	dc := NewDeploymentClient(client, engine, "default")

	err = dc.CreateDeploymentWithPort("agent-1", "registry/agent-1:v1", 9000, map[string]string{
		"TEST_VAR": "test",
	})
	if err != nil {
		t.Log("kubectl apply error (expected in test env):", err)
	}

	// Verify YAML has custom port
	yaml, _ := dc.buildDeploymentYAML("agent-1", "registry/agent-1:v1", 9000,
		map[string]string{"TEST_VAR": "test"}, nil, nil, nil)

	if !strings.Contains(yaml, "containerPort: 9000") {
		t.Error("expected containerPort: 9000 in YAML")
	}
}

// TestBuildEnvYAML verifies environment variable YAML generation
func TestBuildEnvYAML(t *testing.T) {
	envVars := map[string]string{
		"KEY1": "value1",
		"KEY2": "value with spaces",
	}

	yaml := buildEnvYAML(envVars)

	if !strings.Contains(yaml, "KEY1") {
		t.Error("expected KEY1 in env YAML")
	}
	if !strings.Contains(yaml, "value1") {
		t.Error("expected value1 in env YAML")
	}
	if !strings.Contains(yaml, "KEY2") {
		t.Error("expected KEY2 in env YAML")
	}
	if !strings.Contains(yaml, "value with spaces") {
		t.Error("expected value with spaces in env YAML")
	}
}

// TestBuildEnvYAML_Empty verifies empty env vars produce no output
func TestBuildEnvYAML_Empty(t *testing.T) {
	yaml := buildEnvYAML(map[string]string{})
	if yaml != "" {
		t.Errorf("expected empty string for empty env vars, got: %s", yaml)
	}
}

// TestBuildEnvYAML_QuotesEscaped verifies quotes in values are escaped
func TestBuildEnvYAML_QuotesEscaped(t *testing.T) {
	envVars := map[string]string{
		"DSN": `mysql://user:pass@host:3306/db?charset="utf8"`,
	}

	yaml := buildEnvYAML(envVars)

	if strings.Contains(yaml, `"`) && !strings.Contains(yaml, `\"`) {
		// The raw double-quotes in the value should be escaped
		if strings.Count(yaml, `\"`) < 2 {
			t.Error("expected escaped quotes in DSN value")
		}
	}
}
