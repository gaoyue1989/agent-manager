package service

import (
	"os"
	"path/filepath"
	"testing"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/store"
)

// writeDeployOverlay 写临时 overlay 文件并返回路径。
func writeDeployOverlay(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "deploy-overlay.yaml")
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

// TestPublishWithOverlay 发布链路经 DeployBuilder：overlay 改 PVC claimName 后，
// fake 集群中落地的 Deployment 携带 overlay 形态（端到端接线验证）。
func TestPublishWithOverlay(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	b, err := k8s.NewDeploymentBuilder(writeDeployOverlay(t, `
spec:
  template:
    spec:
      nodeSelector:
        pool: agents
      volumes:
        - name: agent-files
          persistentVolumeClaim:
            claimName: my-env-pvc
`))
	if err != nil {
		t.Fatal(err)
	}
	core.Cfg.DeployBuilder = b

	pkg := uploadTestPkg(t, core, "")
	svc, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "templ"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	d, err := fk.GetDeployment("test", svc.K8sName)
	if err != nil {
		t.Fatal(err)
	}
	if d.Spec.Template.Spec.NodeSelector["pool"] != "agents" {
		t.Fatal("overlay nodeSelector missing on published deployment")
	}
	for _, v := range d.Spec.Template.Spec.Volumes {
		if v.Name == "agent-files" {
			if v.PersistentVolumeClaim == nil || v.PersistentVolumeClaim.ClaimName != "my-env-pvc" {
				t.Fatal("overlay claimName missing on published deployment")
			}
		}
	}
	// 历史行为保留：/config 只读 subPath 仍指向包目录
	found := false
	for _, vm := range d.Spec.Template.Spec.Containers[0].VolumeMounts {
		if vm.MountPath == "/config" && vm.ReadOnly && vm.SubPath == pkg.DirPath {
			found = true
		}
	}
	if !found {
		t.Fatal("/config subPath mount must stay after overlay merge")
	}
}

// TestPublishRejectsInvariantViolatingOverlay 启动探针（哑参数）恰好通过、但真实发布
// 必然违反 metadata 不变量的 overlay → 发布失败、服务转 error，非法形态不落集群。
func TestPublishRejectsInvariantViolatingOverlay(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	// 探针以 K8sName=oaf-template-probe/Namespace=default 试渲染，该 overlay 恰好
	// 通过探针；对任何真实发布 metadata.name 都对不上 → 发布期校验兜底拒绝。
	b, err := k8s.NewDeploymentBuilder(writeDeployOverlay(t, `
metadata:
  name: oaf-template-probe
  namespace: default
`))
	if err != nil {
		t.Fatalf("startup probe should pass for this overlay: %v", err)
	}
	core.Cfg.DeployBuilder = b

	pkg := uploadTestPkg(t, core, "uniq")
	if _, err := core.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest", Name: "viol"}); err == nil {
		t.Fatal("invariant-violating overlay must fail publish")
	}
	// DB 记录转 error（applyAll 失败路径 transition 后 Publish 返回 nil）
	var row store.ServiceEntity
	if err := core.DB.Where("k8s_name = ?", "oaf-viol").First(&row).Error; err != nil {
		t.Fatalf("service row should exist: %v", err)
	}
	if row.Status != store.StatusError {
		t.Fatalf("service should transition to error, got %s", row.Status)
	}
	// 非法形态不得落地集群：名字被 overlay 劫持的 Deployment 不允许出现
	if _, gerr := fk.GetDeployment("test", "oaf-template-probe"); gerr == nil {
		t.Fatal("hijacked deployment must not land in cluster")
	}
}
