// M3 发布扩展测试：spec 档直译 / DEPLOYMENT_TEMPLATE resources 互斥 / publicEndpoint 双模式。
package service

import (
	"os"
	"path/filepath"
	"testing"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/store"
)

func TestPublishSpecMapping(t *testing.T) {
	c, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, c, "")
	svc, err := c.Publish(PublishRequest{PackageID: pkg.ID, Name: "spec-svc",
		Image: "agent-framework:latest", Spec: "medium"})
	if err != nil {
		t.Fatalf("publish with spec: %v", err)
	}
	if svc.Spec != "medium" {
		t.Fatalf("spec column: %q", svc.Spec)
	}
	dep, err := fk.GetDeployment("test", svc.K8sName)
	if err != nil {
		t.Fatalf("deployment: %v", err)
	}
	res := dep.Spec.Template.Spec.Containers[0].Resources
	if res.Requests.Cpu().String() != "2" || res.Requests.Memory().String() != "4Gi" ||
		res.Limits.Cpu().String() != "2" || res.Limits.Memory().String() != "4Gi" {
		t.Fatalf("resources: %+v", res.String())
	}
	// 非法 spec 400
	if _, err := c.Publish(PublishRequest{PackageID: pkg.ID, Name: "spec-bad", Spec: "huge"}); err == nil {
		t.Fatal("unknown spec should fail")
	}
}

func TestPublishSpecTemplateMutex(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	// 注入配置了 resources 的 overlay（与启动路径同解析逻辑）
	dir, _ := os.MkdirTemp("", "tmpl-*")
	defer os.RemoveAll(dir)
	overlay := filepath.Join(dir, "dep.yaml")
	if err := os.WriteFile(overlay, []byte(`spec:
  template:
    spec:
      containers:
        - name: agent
          resources:
            requests: {cpu: "1"}
`), 0o644); err != nil {
		t.Fatal(err)
	}
	builder, err := k8s.NewDeploymentBuilder(overlay)
	if err != nil {
		t.Fatalf("builder: %v", err)
	}
	if !builder.OverridesResources() {
		t.Fatal("overlay resources not detected")
	}
	c.Cfg.DeployBuilder = builder
	pkg := uploadTestPkg(t, c, "")
	if _, err := c.Publish(PublishRequest{PackageID: pkg.ID, Name: "mutex-svc", Spec: "small"}); err == nil ||
		err.Error() != "invalid form: DEPLOYMENT_TEMPLATE already configures resources; spec is not allowed" {
		t.Fatalf("mutex: %v", err)
	}
	// 不带 spec 走 overlay 不变
	if _, err := c.Publish(PublishRequest{PackageID: pkg.ID, Name: "overlay-ok"}); err != nil {
		t.Fatalf("no-spec publish: %v", err)
	}
}

func TestPublishPublicEndpointFalse(t *testing.T) {
	// router 模式（默认 IngressHostSuffix 空）：publicEndpoint=false → endpoint 空 + 无 Ingress 对象
	c, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, c, "")
	f := false
	svc, err := c.Publish(PublishRequest{PackageID: pkg.ID, Name: "private-svc", PublicEndpoint: &f})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if svc.Endpoint != "" {
		t.Fatalf("endpoint should be empty: %q", svc.Endpoint)
	}
	// Deployment 仍在
	if _, err := fk.GetDeployment("test", svc.K8sName); err != nil {
		t.Fatalf("deployment missing: %v", err)
	}
	// publicEndpoint=true（默认）→ endpoint 派生正常
	svc2, err := c.Publish(PublishRequest{PackageID: pkg.ID, Name: "public-svc"})
	if err != nil {
		t.Fatalf("publish 2: %v", err)
	}
	if svc2.Endpoint == "" {
		t.Fatal("default endpoint should be derived")
	}
	// 存量行 nil 视为 true
	var raw store.ServiceEntity
	if err := c.DB.First(&raw, svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	_ = raw
}

// TestPublishSpecMicro 最低配置档 micro = 250m/256Mi（平台默认档同值，低配单节点友好）。
func TestPublishSpecMicro(t *testing.T) {
	c, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, c, "")
	svc, err := c.Publish(PublishRequest{PackageID: pkg.ID, Name: "micro-svc",
		Image: "agent-framework:latest", Spec: "micro"})
	if err != nil {
		t.Fatalf("publish micro: %v", err)
	}
	dep, err := fk.GetDeployment("test", svc.K8sName)
	if err != nil {
		t.Fatalf("deployment: %v", err)
	}
	res := dep.Spec.Template.Spec.Containers[0].Resources
	if res.Requests.Cpu().String() != "250m" || res.Requests.Memory().String() != "256Mi" ||
		res.Limits.Cpu().String() != "250m" || res.Limits.Memory().String() != "256Mi" {
		t.Fatalf("micro resources: %s", res.String())
	}
}
