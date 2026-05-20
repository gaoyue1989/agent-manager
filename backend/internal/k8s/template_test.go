package k8s

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// UT-14: 渲染 build 模式 Deployment 模板
func TestTemplateEngine_RenderDeployment_Build(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("failed to create template engine: %v", err)
	}

	params := DeployParams{
		Name:      "agent-1",
		Namespace: "default",
		Image:     "registry/agent-1:v1",
		Port:      8000,
		Replicas:  1,
		EnvYAML:   "        - name: LLM_API_KEY\n          value: \"sk-xxx\"\n",
	}

	yaml, err := engine.RenderDeployment(params)
	if err != nil {
		t.Fatalf("render deployment: %v", err)
	}

	checks := []string{
		"kind: Deployment",
		"name: agent-1",
		"image: registry/agent-1:v1",
		"containerPort: 8000",
		"replicas: 1",
		"LLM_API_KEY",
		"app: agent-1",
	}
	for _, check := range checks {
		if !strings.Contains(yaml, check) {
			t.Errorf("deployment YAML should contain %q\nGot:\n%s", check, yaml)
		}
	}
}

// UT-15: 渲染 mount 模式 Deployment 模板（含卷挂载）
func TestTemplateEngine_RenderDeployment_Mount(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("failed to create template engine: %v", err)
	}

	params := DeployParams{
		Name:         "agent-42",
		Namespace:    "production",
		Image:        "registry/agent-framework:latest",
		Port:         8100,
		Replicas:     2,
		EnvYAML:      "        - name: AGENT_CONFIG_DIR\n          value: \"/config\"\n",
		VolumeMounts: "        - name: config-volume\n          mountPath: /config\n",
		Volumes:      "        - name: config-volume\n          configMap:\n            name: agent-42-config\n",
	}

	yaml, err := engine.RenderDeployment(params)
	if err != nil {
		t.Fatalf("render deployment: %v", err)
	}

	checks := []string{
		"kind: Deployment",
		"name: agent-42",
		"namespace: production",
		"image: registry/agent-framework:latest",
		"containerPort: 8100",
		"replicas: 2",
		"config-volume",
		"mountPath: /config",
	}
	for _, check := range checks {
		if !strings.Contains(yaml, check) {
			t.Errorf("deployment YAML should contain %q\nGot:\n%s", check, yaml)
		}
	}
}

// UT-16: 渲染 Service 模板
func TestTemplateEngine_RenderService(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("failed to create template engine: %v", err)
	}

	params := ServiceParams{
		Name:      "agent-1",
		Namespace: "default",
		Port:      8000,
	}

	yaml, err := engine.RenderService(params)
	if err != nil {
		t.Fatalf("render service: %v", err)
	}

	checks := []string{
		"kind: Service",
		"name: agent-1-svc",
		"port: 8000",
		"targetPort: 8000",
		"app: agent-1",
	}
	for _, check := range checks {
		if !strings.Contains(yaml, check) {
			t.Errorf("service YAML should contain %q\nGot:\n%s", check, yaml)
		}
	}
}

// UT-17: 渲染 Ingress 模板
func TestTemplateEngine_RenderIngress(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("failed to create template engine: %v", err)
	}

	params := IngressParams{
		Name:         "agent-1",
		Namespace:    "default",
		Path:         "/agent/1",
		Port:         8000,
		IngressClass: "nginx",
	}

	yaml, err := engine.RenderIngress(params)
	if err != nil {
		t.Fatalf("render ingress: %v", err)
	}

	checks := []string{
		"kind: Ingress",
		"name: agent-1-ingress",
		"path: /agent/1(/|$)(.*)",
		"ingressClassName: nginx",
		"name: agent-1-svc",
		"rewrite-target: /$2",
	}
	for _, check := range checks {
		if !strings.Contains(yaml, check) {
			t.Errorf("ingress YAML should contain %q\nGot:\n%s", check, yaml)
		}
	}
}

// UT-18: 外部模板覆盖内置模板
func TestTemplateEngine_ExternalOverride(t *testing.T) {
	// 创建临时目录
	tmpDir := t.TempDir()

	customDeployTpl := `apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{.Name}}-custom
spec:
  template:
    spec:
      containers:
      - name: custom-agent
        image: {{.Image}}
`
	if err := os.WriteFile(filepath.Join(tmpDir, "deployment.yaml.tmpl"), []byte(customDeployTpl), 0644); err != nil {
		t.Fatalf("write custom template: %v", err)
	}

	engine, err := NewTemplateEngine(tmpDir)
	if err != nil {
		t.Fatalf("failed to create template engine with external: %v", err)
	}

	params := DeployParams{
		Name: "agent-1",
		Image: "registry/agent-1:v1",
	}

	yaml, err := engine.RenderDeployment(params)
	if err != nil {
		t.Fatalf("render deployment: %v", err)
	}

	if !strings.Contains(yaml, "agent-1-custom") {
		t.Errorf("expected custom deployment name, got:\n%s", yaml)
	}
	if !strings.Contains(yaml, "custom-agent") {
		t.Errorf("expected custom container name, got:\n%s", yaml)
	}
}

// UT-19: 模板含 resources + livenessProbe 渲染正确
func TestTemplateEngine_RenderDeployment_WithResourcesAndProbes(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("failed to create template engine: %v", err)
	}

	params := DeployParams{
		Name:          "agent-1",
		Namespace:     "default",
		Image:         "registry/agent-1:v1",
		Port:          8000,
		Replicas:      1,
		Resources:     "  limits:\n    cpu: 500m\n    memory: 512Mi\n  requests:\n    cpu: 100m\n    memory: 128Mi",
		LivenessProbe: "  httpGet:\n    path: /health\n    port: 8000\n  initialDelaySeconds: 30",
	}

	yaml, err := engine.RenderDeployment(params)
	if err != nil {
		t.Fatalf("render deployment: %v", err)
	}

	if !strings.Contains(yaml, "limits:") {
		t.Error("expected resources limits in YAML")
	}
	if !strings.Contains(yaml, "cpu: 500m") {
		t.Error("expected cpu: 500m in resources")
	}
	if !strings.Contains(yaml, "livenessProbe:") {
		t.Error("expected livenessProbe in YAML")
	}
	if !strings.Contains(yaml, "/health") {
		t.Error("expected /health path in liveness probe")
	}
}

// UT-20: 无效模板名返回错误
func TestTemplateEngine_InvalidTemplate(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	yaml, err := engine.render("nonexistent", nil)
	if err == nil {
		t.Error("expected error for non-existent template")
	}
	if yaml != "" {
		t.Error("expected empty YAML for non-existent template")
	}
}

// UT-21: embed 内置模板存在且可解析
func TestTemplateEngine_DefaultTemplatesEmbedded(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	templateNames := []string{"deployment", "service", "ingress"}
	for _, name := range templateNames {
		if _, ok := engine.templates[name]; !ok {
			t.Errorf("expected embedded template %q", name)
		}
	}
}

// TestTemplateEngine_ReplicasDefault 验证未设置 replicas 时默认为 1
func TestTemplateEngine_ReplicasDefault(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	params := DeployParams{
		Name:  "agent-1",
		Image: "registry/agent-1:v1",
	}

	yaml, err := engine.RenderDeployment(params)
	if err != nil {
		t.Fatalf("render deployment: %v", err)
	}

	if !strings.Contains(yaml, "replicas: 1") {
		t.Error("expected default replicas: 1")
	}
}

// TestTemplateEngine_EmptyNamespace 验证 namespace 为空时的默认值
func TestTemplateEngine_EmptyNamespace(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	params := DeployParams{
		Name:  "agent-1",
		Image: "registry/agent-1:v1",
	}

	yaml, err := engine.RenderDeployment(params)
	if err != nil {
		t.Fatalf("render deployment: %v", err)
	}

	if !strings.Contains(yaml, "namespace: default") {
		t.Error("expected default namespace: default")
	}
}

// TestTemplateEngine_ExternalTemplateDir_NotExists 外部目录不存在报错
func TestTemplateEngine_ExternalTemplateDir_NotExists(t *testing.T) {
	_, err := NewTemplateEngine("/nonexistent/path")
	if err == nil {
		t.Error("expected error for non-existent template directory")
	}
}

// TestTemplateEngine_ExternalTemplateDir_InvalidTemplate 外部模板语法错误
func TestTemplateEngine_ExternalTemplateDir_InvalidTemplate(t *testing.T) {
	tmpDir := t.TempDir()

	invalidTpl := `apiVersion: apps/v1
kind: Deployment
{{ end }}
`
	if err := os.WriteFile(filepath.Join(tmpDir, "deployment.yaml.tmpl"), []byte(invalidTpl), 0644); err != nil {
		t.Fatalf("write invalid template: %v", err)
	}

	_, err := NewTemplateEngine(tmpDir)
	if err == nil {
		t.Error("expected parse error for invalid template (unmatched {{ end }})")
	} else {
		t.Log("got expected error:", err)
	}
}

// TestTemplateEngine_RenderService_DifferentPorts 验证不同端口
func TestTemplateEngine_RenderService_DifferentPorts(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	params := ServiceParams{
		Name: "agent-42",
		Port: 8100,
	}

	yaml, err := engine.RenderService(params)
	if err != nil {
		t.Fatalf("render service: %v", err)
	}

	if !strings.Contains(yaml, "port: 8100") {
		t.Error("expected port: 8100 for mount mode")
	}
}

// TestTemplateEngine_RenderIngress_DifferentIngressClass 验证不同 ingress class
func TestTemplateEngine_RenderIngress_DifferentIngressClass(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	params := IngressParams{
		Name:         "agent-1",
		Path:         "/agent/1",
		Port:         8000,
		IngressClass: "alb",
	}

	yaml, err := engine.RenderIngress(params)
	if err != nil {
		t.Fatalf("render ingress: %v", err)
	}

	if !strings.Contains(yaml, "ingressClassName: alb") {
		t.Error("expected ingressClassName: alb")
	}
}

// TestTemplateEngine_RenderIngress_EmptyIngressClass 验证空 ingress class 不渲染
func TestTemplateEngine_RenderIngress_EmptyIngressClass(t *testing.T) {
	engine, err := NewTemplateEngine("")
	if err != nil {
		t.Fatalf("create engine: %v", err)
	}

	params := IngressParams{
		Name:         "agent-1",
		Path:         "/agent/1",
		Port:         8000,
		IngressClass: "",
	}

	yaml, err := engine.RenderIngress(params)
	if err != nil {
		t.Fatalf("render ingress: %v", err)
	}

	if strings.Contains(yaml, "ingressClassName:") {
		t.Error("ingressClassName should be absent when empty")
	}
}
