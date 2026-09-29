package k8s

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	networkingv1 "k8s.io/api/networking/v1"
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
			if len(c.EnvFrom) != 2 {
				t.Fatal("agent envFrom must stay (2 sources)")
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

// ---- IngressBuilder ----

// ingressWithOverlay 绕过启动探针直接构造 builder：专测 Build 期校验路径。
func ingressWithOverlay(t *testing.T, overlay string) *IngressBuilder {
	t.Helper()
	return &IngressBuilder{overlay: []byte(overlay), hasOverlay: true}
}

// TestIngressBuilderNilOverlay 未配置 overlay 时与内置构造完全一致，Endpoint 派生
// 与旧公式逐字符一致（历史行为回归锁）。
func TestIngressBuilderNilOverlay(t *testing.T) {
	b := &IngressBuilder{}
	p := testParams()
	got, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	if got.String() != Ingress(p).String() {
		t.Fatal("nil overlay must be identical to built-in construction")
	}
	if ep := IngressEndpoint(got, p.IngressHost, p.IngressPort); ep != "http://1.2.3.4:30080/agent/acme-demo/" {
		t.Fatalf("endpoint must match legacy formula, got %q", ep)
	}
}

// TestIngressBuilderAnnotationsMerge annotations 按 key 合并：追加白名单注解，
// 内置注解与路由规则全保留。
func TestIngressBuilderAnnotationsMerge(t *testing.T) {
	overlay := `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/whitelist-source-range: 10.0.0.0/8
`
	b, err := NewIngressBuilder(writeOverlay(t, overlay), "nginx")
	if err != nil {
		t.Fatal(err)
	}
	p := testParams()
	ing, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	if ing.Annotations["nginx.ingress.kubernetes.io/whitelist-source-range"] != "10.0.0.0/8" {
		t.Fatal("whitelist annotation from overlay missing")
	}
	if ing.Annotations[annRewriteTarget] != rewriteTargetValue ||
		ing.Annotations[annUseRegex] != "true" ||
		ing.Annotations[annXForwardedPrefix] != "/agent/acme-demo" ||
		ing.Annotations[annProxyReadTimeout] == "" {
		t.Fatal("built-in annotations lost after merge")
	}
	if ing.Spec.Rules[0].HTTP.Paths[0].Path != "/agent/acme-demo(/|$)(.*)" {
		t.Fatal("built-in routing lost after merge")
	}
}

// TestIngressBuilderHostAndPath rules 整体替换改 host/path：占位符按服务替换，
// x-forwarded-prefix 同步改写，Endpoint 派生跟随合并结果。
func TestIngressBuilderHostAndPath(t *testing.T) {
	overlay := `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/x-forwarded-prefix: /my-agent
spec:
  rules:
    - host: demo.example.com
      http:
        paths:
          - path: /my-agent(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: "{{K8S_NAME}}-svc"
                port:
                  number: 8100
`
	b, err := NewIngressBuilder(writeOverlay(t, overlay), "nginx")
	if err != nil {
		t.Fatal(err)
	}
	p := testParams()
	ing, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	rule := ing.Spec.Rules[0]
	if rule.Host != "demo.example.com" {
		t.Fatalf("host from overlay missing: %q", rule.Host)
	}
	path := rule.HTTP.Paths[0]
	if path.Path != "/my-agent(/|$)(.*)" {
		t.Fatalf("path from overlay missing: %q", path.Path)
	}
	if path.Backend.Service.Name != "oaf-acme-demo-svc" {
		t.Fatalf("placeholder must be substituted per service, got %q", path.Backend.Service.Name)
	}
	if ing.Annotations[annXForwardedPrefix] != "/my-agent" {
		t.Fatal("x-forwarded-prefix from overlay missing")
	}
	if ep := IngressEndpoint(ing, p.IngressHost, p.IngressPort); ep != "http://demo.example.com:30080/my-agent/" {
		t.Fatalf("endpoint must follow merged ingress, got %q", ep)
	}
}

// TestIngressBuilderTLS 允许配置 TLS（无不变量限制），Endpoint 按 https 派生。
func TestIngressBuilderTLS(t *testing.T) {
	overlay := `
spec:
  tls:
    - hosts: [demo.example.com]
      secretName: demo-tls
`
	b := ingressWithOverlay(t, overlay)
	p := testParams()
	ing, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	if len(ing.Spec.TLS) != 1 || ing.Spec.TLS[0].SecretName != "demo-tls" {
		t.Fatal("tls section missing")
	}
	// rules 未写 → 整体替换不发生，内置规则保留
	if ing.Spec.Rules[0].HTTP.Paths[0].Path != "/agent/acme-demo(/|$)(.*)" {
		t.Fatal("built-in routing must stay when overlay omits rules")
	}
	if ep := IngressEndpoint(ing, p.IngressHost, p.IngressPort); ep != "https://1.2.3.4:30080/agent/acme-demo/" {
		t.Fatalf("endpoint must switch to https with tls, got %q", ep)
	}
}

// TestIngressBuilderRejectsBadOverlay 禁改字段/破坏不变量的 overlay 必须拒绝。
func TestIngressBuilderRejectsBadOverlay(t *testing.T) {
	fullRules := func(host, path string) string {
		return fmt.Sprintf(`
metadata:
  annotations:
    nginx.ingress.kubernetes.io/x-forwarded-prefix: /agent/{{SHORT_NAME}}
spec:
  rules:
    - host: %s
      http:
        paths:
          - path: %s
            pathType: ImplementationSpecific
            backend:
              service:
                name: "{{K8S_NAME}}-svc"
                port:
                  number: 8100
`, host, path)
	}
	cases := map[string]string{
		"rename": `
metadata:
  name: hijacked
`,
		"class-changed": `
spec:
  ingressClassName: traefik
`,
		"default-backend": `
spec:
  defaultBackend:
    service:
      name: other-svc
      port:
        number: 80
`,
		"path-type-missing": `
spec:
  rules:
    - http:
        paths:
          - path: /agent/{{SHORT_NAME}}(/|$)(.*)
            backend:
              service:
                name: "{{K8S_NAME}}-svc"
                port:
                  number: 8100
`,
		"wrong-backend": `
spec:
  rules:
    - http:
        paths:
          - path: /agent/{{SHORT_NAME}}(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: other-svc
                port:
                  number: 8100
`,
		"path-no-suffix": fullRules("", "/agent/{{SHORT_NAME}}"),
		"rewrite-changed": `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$3
`,
		"use-regex-off": `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/use-regex: "false"
`,
		"prefix-mismatch": `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/x-forwarded-prefix: /other
`,
		"timeout-dropped": `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/proxy-read-timeout: null
`,
		"rules-dropped": `
spec:
  rules: null
`,
		"multi-prefix": `
spec:
  rules:
    - http:
        paths:
          - path: /a(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: "{{K8S_NAME}}-svc"
                port:
                  number: 8100
          - path: /b(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: "{{K8S_NAME}}-svc"
                port:
                  number: 8100
`,
	}
	for name, overlay := range cases {
		t.Run(name, func(t *testing.T) {
			b := ingressWithOverlay(t, overlay)
			if _, err := b.Build(testParams()); err == nil {
				t.Fatalf("overlay %q must be rejected", name)
			}
		})
	}
}

// TestIngressBuilderSyntaxErrorFailFast 非法 YAML / 缺文件 / 试渲染违规（硬编码
// 服务名或 metadata）启动即失败。
func TestIngressBuilderSyntaxErrorFailFast(t *testing.T) {
	if _, err := NewIngressBuilder(writeOverlay(t, "spec: [broken"), "nginx"); err == nil {
		t.Fatal("broken yaml must fail at startup")
	}
	if _, err := NewIngressBuilder(filepath.Join(t.TempDir(), "missing.yaml"), "nginx"); err == nil {
		t.Fatal("missing file must fail at startup")
	}
	if _, err := NewIngressBuilder(writeOverlay(t, "metadata:\n  name: hijacked\n"), "nginx"); err == nil {
		t.Fatal("invariant-violating overlay must fail at startup probe")
	}
	// 硬编码具体服务 backend：探针（哑参数 oaf-template-probe）即拒绝，逼用占位符
	if _, err := NewIngressBuilder(writeOverlay(t, fullIngressRules("other-svc", "/x(/|$)(.*)")), "nginx"); err == nil {
		t.Fatal("hardcoded service backend must fail at startup probe")
	}
}

// fullIngressRules 合法形状的完整 rules overlay（backend 名硬编码），供探针用例复用。
func fullIngressRules(backend, path string) string {
	return fmt.Sprintf(`
spec:
  rules:
    - http:
        paths:
          - path: %s
            pathType: ImplementationSpecific
            backend:
              service:
                name: %q
                port:
                  number: 8100
`, path, backend)
}

// TestIngressEndpointTable Endpoint 派生：host 回落/规则 host/自带端口/TLS/根前缀/空规则。
func TestIngressEndpointTable(t *testing.T) {
	base := Ingress(testParams())
	cases := []struct {
		name string
		ing  *networkingv1.Ingress
		host string
		port int
		want string
	}{
		{
			name: "builtin-legacy-formula", ing: base, host: "1.2.3.4", port: 30080,
			want: "http://1.2.3.4:30080/agent/acme-demo/",
		},
		{
			name: "rule-host-overrides-fallback",
			ing: func() *networkingv1.Ingress {
				ing := base.DeepCopy()
				ing.Spec.Rules[0].Host = "demo.example.com"
				return ing
			}(), host: "1.2.3.4", port: 30080,
			want: "http://demo.example.com:30080/agent/acme-demo/",
		},
		{
			name: "fallback-host-with-port", ing: base, host: "172.20.0.2:30080", port: 30080,
			want: "http://172.20.0.2:30080/agent/acme-demo/",
		},
		{
			name: "root-prefix",
			ing: func() *networkingv1.Ingress {
				ing := base.DeepCopy()
				ing.Spec.Rules[0].HTTP.Paths[0].Path = "(/|$)(.*)"
				return ing
			}(), host: "1.2.3.4", port: 30080,
			want: "http://1.2.3.4:30080/",
		},
		{
			name: "no-rules-fallback",
			ing: func() *networkingv1.Ingress {
				ing := base.DeepCopy()
				ing.Spec.Rules = nil
				return ing
			}(), host: "1.2.3.4", port: 30080,
			want: "http://1.2.3.4:30080/",
		},
		{
			name: "tls-scheme",
			ing: func() *networkingv1.Ingress {
				ing := base.DeepCopy()
				ing.Spec.TLS = []networkingv1.IngressTLS{{Hosts: []string{"demo.example.com"}}}
				return ing
			}(), host: "1.2.3.4", port: 30080,
			want: "https://1.2.3.4:30080/agent/acme-demo/",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := IngressEndpoint(tc.ing, tc.host, tc.port); got != tc.want {
				t.Fatalf("want %q, got %q", tc.want, got)
			}
		})
	}
}
