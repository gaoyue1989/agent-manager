package k8s

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 写临时 overlay 文件，返回路径。
func writeOverlay(t *testing.T, content string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "overlay.yaml")
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

// TestBuilderNilOverlay 未配置 overlay 时与内置构造完全一致（历史行为回归锁）。
func TestBuilderNilOverlay(t *testing.T) {
	b := &DeploymentBuilder{}
	p := testParams()
	got, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	want := Deployment(p)
	if got.String() != want.String() {
		t.Fatal("nil overlay must be identical to built-in construction")
	}
}

// TestBuilderOverlayMerge overlay 按 name 合并：改 PVC claimName、加 imagePullSecrets、
// nodeSelector，同时内置卷/挂载/env 全部保留。
func TestBuilderOverlayMerge(t *testing.T) {
	overlay := `
spec:
  template:
    spec:
      imagePullSecrets:
        - name: regcred
      nodeSelector:
        pool: agents
      volumes:
        - name: agent-files
          persistentVolumeClaim:
            claimName: my-env-pvc
`
	b, err := NewDeploymentBuilder(writeOverlay(t, overlay))
	if err != nil {
		t.Fatal(err)
	}
	p := testParams()
	d, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}

	// overlay 生效
	if d.Spec.Template.Spec.NodeSelector["pool"] != "agents" {
		t.Fatal("nodeSelector from overlay missing")
	}
	foundSecret := false
	for _, s := range d.Spec.Template.Spec.ImagePullSecrets {
		if s.Name == "regcred" {
			foundSecret = true
		}
	}
	if !foundSecret {
		t.Fatal("imagePullSecrets from overlay missing")
	}
	foundPVC := false
	for _, v := range d.Spec.Template.Spec.Volumes {
		if v.Name == FilesVolumeName && v.PersistentVolumeClaim != nil &&
			v.PersistentVolumeClaim.ClaimName == "my-env-pvc" {
			foundPVC = true
		}
	}
	if !foundPVC {
		t.Fatal("PVC claimName should be overridden by overlay")
	}

	// 内置构造保留（按 name 合并而非整体替换）
	foundConfigMount := false
	for _, vm := range d.Spec.Template.Spec.Containers[0].VolumeMounts {
		if vm.MountPath == "/config" && vm.SubPath == "packages/42" && vm.ReadOnly {
			foundConfigMount = true
		}
	}
	if !foundConfigMount {
		t.Fatal("/config mount from built-in construction lost")
	}
	foundFilesMount := false
	for _, vm := range d.Spec.Template.Spec.Containers[0].VolumeMounts {
		if vm.MountPath == FilesMountPath && vm.SubPath == FilesSubPath {
			foundFilesMount = true
		}
	}
	if !foundFilesMount {
		t.Fatal("/data/files mount from built-in construction lost")
	}
	if len(d.Spec.Template.Spec.Containers[0].Env) != 5 {
		t.Fatalf("fixed env should stay 5, got %d", len(d.Spec.Template.Spec.Containers[0].Env))
	}
	if d.Name != "oaf-acme-demo" || d.Namespace != "agent-platform" {
		t.Fatal("metadata must stay from built-in construction")
	}
}

// TestBuilderOverlaySidecar overlay 新增 sidecar 容器合法（主容器契约保留）。
func TestBuilderOverlaySidecar(t *testing.T) {
	overlay := `
spec:
  template:
    spec:
      containers:
        - name: log-exporter
          image: busybox:1.36
          command: ["sh", "-c", "tail -f /applog/*/trace.log"]
`
	b, err := NewDeploymentBuilder(writeOverlay(t, overlay))
	if err != nil {
		t.Fatal(err)
	}
	d, err := b.Build(testParams())
	if err != nil {
		t.Fatal(err)
	}
	if len(d.Spec.Template.Spec.Containers) != 2 {
		t.Fatalf("want 2 containers (agent + sidecar), got %d", len(d.Spec.Template.Spec.Containers))
	}
	// SMP 合并不保证容器顺序（sidecar 可能排前），只断言 agent 存在且契约保留
	foundAgent := false
	for _, c := range d.Spec.Template.Spec.Containers {
		if c.Name == "agent" {
			foundAgent = true
			if c.Image != "agent-framework:latest" {
				t.Fatal("agent image must stay")
			}
			if len(c.EnvFrom) != 1 {
				t.Fatal("agent envFrom must stay")
			}
		}
	}
	if !foundAgent {
		t.Fatal("agent container missing after merge")
	}
	foundSidecar := false
	for _, c := range d.Spec.Template.Spec.Containers {
		if c.Name == "log-exporter" && c.Image == "busybox:1.36" {
			foundSidecar = true
		}
	}
	if !foundSidecar {
		t.Fatal("sidecar missing")
	}
}

// builderWithOverlay 绕过启动探针直接构造 builder：专测 Build 期校验路径
// （探针与真实发布共用同一校验，若探针先拒绝则 Build 期断言成死代码）。
func builderWithOverlay(t *testing.T, overlay string) *DeploymentBuilder {
	t.Helper()
	return &DeploymentBuilder{overlay: []byte(overlay), hasOverlay: true}
}

// TestBuilderRejectsBadOverlay 禁改字段/破坏不变量的 overlay 必须拒绝。
func TestBuilderRejectsBadOverlay(t *testing.T) {
	cases := map[string]string{
		"rename": `
metadata:
  name: hijacked
`,
		"replicas": `
spec:
  replicas: 99
`,
		"selector": `
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: other
`,
		"selector-nil": `
spec:
  selector: null
`,
		"selector-matchExpressions": `
spec:
  selector:
    matchExpressions:
      - key: tier
        operator: In
        values: ["a"]
`,
		"drop-config-mount": `
spec:
  template:
    spec:
      containers:
        - name: agent
          volumeMounts: null
`,
		"drop-envFrom": `
spec:
  template:
    spec:
      containers:
        - name: agent
          envFrom: null
`,
		"drop-reserved-env": `
spec:
  template:
    spec:
      containers:
        - name: agent
          env: null
`,
		"drop-files-mount": `
spec:
  template:
    spec:
      containers:
        - name: agent
          volumeMounts:
            - mountPath: /data/files
              $patch: delete
`,
		"drop-applog-mount": `
spec:
  template:
    spec:
      containers:
        - name: agent
          volumeMounts:
            - mountPath: /applog
              $patch: delete
`,
		"drop-workspace-mount": `
spec:
  template:
    spec:
      containers:
        - name: agent
          volumeMounts:
            - mountPath: /workspace
              $patch: delete
`,
		"dangling-volume-mount": `
spec:
  template:
    spec:
      containers:
        - name: agent
          volumeMounts:
            - name: no-such-volume
              mountPath: /extra
`,
	}
	for name, overlay := range cases {
		t.Run(name, func(t *testing.T) {
			b := builderWithOverlay(t, overlay)
			if _, err := b.Build(testParams()); err == nil {
				t.Fatalf("overlay %q must be rejected", name)
			}
		})
	}
}

// TestBuilderSyntaxErrorFailFast 非法 YAML / 错误类型字段启动即失败。
func TestBuilderSyntaxErrorFailFast(t *testing.T) {
	if _, err := NewDeploymentBuilder(writeOverlay(t, "spec: [broken")); err == nil {
		t.Fatal("broken yaml must fail at startup")
	}
	if _, err := NewDeploymentBuilder(writeOverlay(t, "spec:\n  replicas: \"not-a-number\"\n")); err == nil {
		t.Fatal("type-mismatched yaml must fail at startup")
	}
	if _, err := NewDeploymentBuilder(filepath.Join(t.TempDir(), "missing.yaml")); err == nil {
		t.Fatal("missing file must fail at startup")
	}
}

// TestBuilderEmptyFile 空文件视为无 overlay。
func TestBuilderEmptyFile(t *testing.T) {
	b, err := NewDeploymentBuilder(writeOverlay(t, ""))
	if err != nil {
		t.Fatal(err)
	}
	p := testParams()
	d, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	if d.String() != Deployment(p).String() {
		t.Fatal("empty overlay must not change construction")
	}
}

// TestBuilderRejectsStartup 试渲染路径：Startup 阶段即拒绝破坏不变量的 overlay。
func TestBuilderRejectsStartup(t *testing.T) {
	b, err := NewDeploymentBuilder(writeOverlay(t, "metadata:\n  name: hijacked\n"))
	if err == nil {
		t.Fatal("invariant-violating overlay must fail at startup probe")
	}
	_ = b
}

// TestBuilderReplicasFollowsRequest replicas 属每服务请求级：overlay 未改时随请求变化。
func TestBuilderReplicasFollowsRequest(t *testing.T) {
	b, err := NewDeploymentBuilder(writeOverlay(t, "spec:\n  template:\n    spec:\n      nodeSelector:\n        pool: agents\n"))
	if err != nil {
		t.Fatal(err)
	}
	p := testParams()
	p.Replicas = 3
	d, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	if *d.Spec.Replicas != 3 {
		t.Fatalf("replicas should follow request, got %d", *d.Spec.Replicas)
	}
}

// TestBuilderSidecarDanglingVolumeRejected sidecar 悬空 volume 引用被 Build 期校验拒绝。
func TestBuilderSidecarDanglingVolumeRejected(t *testing.T) {
	overlay := `
spec:
  template:
    spec:
      containers:
        - name: sidecar
          image: busybox:1.36
          volumeMounts:
            - name: ghost
              mountPath: /ghost
`
	b := builderWithOverlay(t, overlay)
	_, err := b.Build(testParams())
	if err == nil || !strings.Contains(err.Error(), "ghost") {
		t.Fatalf("sidecar dangling volume must be rejected, got: %v", err)
	}
}

// TestBuilderKeepsProbesAndResources 探针与资源规格为 WaitReady/调度核心，默认构造必须携带。
func TestBuilderKeepsProbesAndResources(t *testing.T) {
	b := &DeploymentBuilder{}
	d, err := b.Build(testParams())
	if err != nil {
		t.Fatal(err)
	}
	cs := d.Spec.Template.Spec.Containers[0]
	if cs.ReadinessProbe == nil || cs.ReadinessProbe.HTTPGet.Path != "/health" {
		t.Fatal("readiness probe missing")
	}
	if cs.LivenessProbe == nil || cs.LivenessProbe.HTTPGet.Port.IntValue() != AgentPort {
		t.Fatal("liveness probe missing")
	}
	if len(cs.Resources.Requests) == 0 || len(cs.Resources.Limits) == 0 {
		t.Fatal("resources missing")
	}
}
