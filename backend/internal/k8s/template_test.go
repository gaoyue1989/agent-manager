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
	p := hostModeParams()
	got, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	if got.String() != Ingress(p).String() {
		t.Fatal("nil overlay must be identical to built-in construction")
	}
	if ep := IngressEndpoint(got, p.IngressHost); ep != "http://oaf-acme-demo.region-c86-test.test-kzx1.cncb/" {
		t.Fatalf("endpoint must match host-mode formula, got %q", ep)
	}
	// router 模式（suffix 空）没有 per-service Ingress：Build 一律拒绝（发布流不会走到，
	// 此处锁住编程错误面）
	if _, err := b.Build(testParams()); err == nil {
		t.Fatal("router mode (empty suffix) must reject per-service ingress build")
	}
}

// TestIngressBuilderTLS 允许配置 TLS（无不变量限制），Endpoint 按 https 派生；
// rules 未写 → 整体替换不发生，内置 host 模式规则保留。
func TestIngressBuilderTLS(t *testing.T) {
	overlay := `
spec:
  tls:
    - hosts: [oaf-acme-demo.region-c86-test.test-kzx1.cncb]
      secretName: demo-tls
`
	b := ingressWithOverlay(t, overlay)
	p := hostModeParams()
	ing, err := b.Build(p)
	if err != nil {
		t.Fatal(err)
	}
	if len(ing.Spec.TLS) != 1 || ing.Spec.TLS[0].SecretName != "demo-tls" {
		t.Fatal("tls section missing")
	}
	if ing.Spec.Rules[0].Host != "oaf-acme-demo.region-c86-test.test-kzx1.cncb" ||
		ing.Spec.Rules[0].HTTP.Paths[0].Path != "/" {
		t.Fatal("built-in host-mode routing must stay when overlay omits rules")
	}
	if ep := IngressEndpoint(ing, p.IngressHost); ep != "https://oaf-acme-demo.region-c86-test.test-kzx1.cncb/" {
		t.Fatalf("endpoint must switch to https with tls, got %q", ep)
	}
}

// TestIngressBuilderTLSCoverage TLS 覆盖校验三态（issue #97）：spec.tls 存在时其 hosts
// 必须覆盖全部非空 rule host——展示 Endpoint 按「有 TLS 即 https」派生，不覆盖的域名
// 会得到 https 展示地址 + 无证书死链；path 模式空 rule host（共享入口形态）豁免。
func TestIngressBuilderTLSCoverage(t *testing.T) {
	// 1) TLS 缺 hosts（只配 secretName，hosts 为空列表）→ 拒（host 模式 rule host 非空）
	if _, err := ingressWithOverlay(t, `
spec:
  tls:
    - secretName: agents-wildcard-tls
`).Build(hostModeParams()); err == nil {
		t.Fatal("tls without hosts must be rejected when rule host exists")
	}
	// 2) TLS hosts 不覆盖 rule host（{{HOST_SUFFIX}} 拼错/漏后缀）→ 拒
	if _, err := ingressWithOverlay(t, `
spec:
  tls:
    - hosts: ["{{K8S_NAME}}{{HOST_SUFFIX}}.cn"]
      secretName: agents-wildcard-tls
`).Build(hostModeParams()); err == nil {
		t.Fatal("tls hosts must cover the rule host")
	}
	// 3) TLS hosts 正确覆盖（{{HOST_SUFFIX}} 与 INGRESS_HOST_SUFFIX 联动）→ 通过，
	//    Endpoint 按 https + 域名派生
	p := hostModeParams()
	ing, err := ingressWithOverlay(t, `
spec:
  tls:
    - hosts: ["{{K8S_NAME}}{{HOST_SUFFIX}}"]
      secretName: agents-wildcard-tls
`).Build(p)
	if err != nil {
		t.Fatalf("covering tls overlay must pass: %v", err)
	}
	want := "https://oaf-acme-demo.region-c86-test.test-kzx1.cncb/"
	if ep := IngressEndpoint(ing, p.IngressHost); ep != want {
		t.Fatalf("endpoint must be https domain, got %q want %q", ep, want)
	}
}

// TestIngressBuilderRouterModeRejected router 模式（suffix 空）不构造 per-service
// Ingress：无论 overlay 内容如何，Build 一律拒绝（subpath-routing-design §4.4；
// 「禁改字段/破坏不变量的 overlay 拒绝」的 host 模式覆盖见
// TestIngressBuilderHostModeRejectsBadOverlay）。
func TestIngressBuilderRouterModeRejected(t *testing.T) {
	for name, overlay := range map[string]string{
		"plain":        "",
		"with-overlay": "metadata:\n  annotations:\n    nginx.ingress.kubernetes.io/enable-cors: \"true\"\n",
	} {
		t.Run(name, func(t *testing.T) {
			b := ingressWithOverlay(t, overlay)
			if _, err := b.Build(testParams()); err == nil {
				t.Fatalf("router mode must reject per-service ingress build (%s)", name)
			}
		})
	}
}

// TestIngressBuilderSyntaxErrorFailFast 非法 YAML / 缺文件 / 试渲染违规（硬编码
// 服务名或 metadata）启动即失败。探针与 host 模式同参（suffix 非空）。
func TestIngressBuilderSyntaxErrorFailFast(t *testing.T) {
	const suffix = ".region-c86-test.test-kzx1.cncb"
	if _, err := NewIngressBuilder(writeOverlay(t, "spec: [broken"), "nginx", suffix); err == nil {
		t.Fatal("broken yaml must fail at startup")
	}
	if _, err := NewIngressBuilder(filepath.Join(t.TempDir(), "missing.yaml"), "nginx", suffix); err == nil {
		t.Fatal("missing file must fail at startup")
	}
	if _, err := NewIngressBuilder(writeOverlay(t, "metadata:\n  name: hijacked\n"), "nginx", suffix); err == nil {
		t.Fatal("invariant-violating overlay must fail at startup probe")
	}
	// 硬编码具体服务 backend：探针（哑参数 oaf-template-probe）即拒绝，逼用占位符
	if _, err := NewIngressBuilder(writeOverlay(t, fullIngressRules("other-svc")), "nginx", suffix); err == nil {
		t.Fatal("hardcoded service backend must fail at startup probe")
	}
}

// TestIngressBuilderHostModeAnnotationsMerge host 模式 overlay 追加注解（map 按 key
// 合并）：内置 host/path/backend 全保留，且校验不要求 rewrite 注解。
func TestIngressBuilderHostModeAnnotationsMerge(t *testing.T) {
	overlay := `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/whitelist-source-range: 10.0.0.0/8
`
	b := ingressWithOverlay(t, overlay)
	ing, err := b.Build(hostModeParams())
	if err != nil {
		t.Fatal(err)
	}
	if ing.Annotations["nginx.ingress.kubernetes.io/whitelist-source-range"] != "10.0.0.0/8" {
		t.Fatal("whitelist annotation from overlay missing")
	}
	if ing.Annotations[annProxyReadTimeout] != "3600" || ing.Annotations[annSSLRedirect] != "false" {
		t.Fatalf("built-in annotations lost after merge: %v", ing.Annotations)
	}
	if ing.Spec.Rules[0].Host != "oaf-acme-demo.region-c86-test.test-kzx1.cncb" {
		t.Fatalf("built-in host lost after merge: %q", ing.Spec.Rules[0].Host)
	}
	if ing.Spec.Rules[0].HTTP.Paths[0].Path != "/" {
		t.Fatal("built-in root path lost after merge")
	}
	if ing.Spec.Rules[0].HTTP.Paths[0].Backend.Service.Name != "oaf-acme-demo-svc" {
		t.Fatal("built-in backend lost after merge")
	}
	// host 模式：rewrite 三项必须为空，但显式写空串放行（"不存在"与"存在但为空"等价）
	if _, err := ingressWithOverlay(t, `
metadata:
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: ""
    nginx.ingress.kubernetes.io/use-regex: ""
    nginx.ingress.kubernetes.io/x-forwarded-prefix: ""
`).Build(hostModeParams()); err != nil {
		t.Fatalf("host mode must accept empty rewrite annotations: %v", err)
	}
}

// TestIngressBuilderHostModeRejectsBadOverlay host 模式违规 overlay：改 host、改
// backend、丢 SSE 超时、丢 pathType、加 defaultBackend 一律拒绝（发布不落库）。
func TestIngressBuilderHostModeRejectsBadOverlay(t *testing.T) {
	hostRules := func(host, backend string) string {
		return fmt.Sprintf(`
spec:
  rules:
    - host: %s
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: %q
                port:
                  number: 8100
`, host, backend)
	}
	cases := map[string]string{
		"host-hijacked":     hostRules("demo.example.com", "oaf-acme-demo-svc"),
		"host-empty":        hostRules("", "oaf-acme-demo-svc"),
		"wrong-backend":     hostRules("oaf-acme-demo.region-c86-test.test-kzx1.cncb", "other-svc"),
		"timeout-dropped":   "metadata:\n  annotations:\n    nginx.ingress.kubernetes.io/proxy-read-timeout: null\n",
		"default-backend":   "spec:\n  defaultBackend:\n    service:\n      name: other-svc\n      port:\n        number: 80\n",
		"path-type-missing": hostModeRulesNoPathType("oaf-acme-demo.region-c86-test.test-kzx1.cncb", "oaf-acme-demo-svc"),
		// path 恒为根：放开会让 Ingress 只服务 /foo，而落库 Endpoint 仍指根 → 404
		"path-not-root": hostModeRulesWithPath("oaf-acme-demo.region-c86-test.test-kzx1.cncb", "oaf-acme-demo-svc", "/foo"),
		"rules-dropped": "spec:\n  rules: null\n",
		// rewrite 三项在 host 模式必须为空：x-forwarded-prefix 非空会让 DebugController
		// 302 到 {该值}/debug/ 直接 404（最常见来源=照抄 path 模式模板）
		"rewrite-target-set":     hostModeAnn("nginx.ingress.kubernetes.io/rewrite-target", "/$2"),
		"use-regex-set":          hostModeAnn("nginx.ingress.kubernetes.io/use-regex", `"false"`),
		"x-forwarded-prefix-set": hostModeAnn("nginx.ingress.kubernetes.io/x-forwarded-prefix", "/agent/{{SHORT_NAME}}"),
	}
	for name, overlay := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := ingressWithOverlay(t, overlay).Build(hostModeParams()); err == nil {
				t.Fatalf("host-mode overlay %q must be rejected", name)
			}
		})
	}
}

// TestIngressBuilderHostModeProbe 启动探针须与发布期同模式：host 模式 overlay 经探针
// 通过；overlay 硬编码探针域名（oaf-template-probe{suffix}）只能骗过探针，真实
// 发布期因 host 偏离被拒——与 path 模式"硬编码服务名"同构。
func TestIngressBuilderHostModeProbe(t *testing.T) {
	const suffix = ".region-c86-test.test-kzx1.cncb"
	// 只追加注解、不写 rules：内置 host 保持，探针与发布期均通过
	if _, err := NewIngressBuilder(writeOverlay(t, "metadata:\n  annotations:\n    nginx.ingress.kubernetes.io/enable-cors: \"true\"\n"),
		"nginx", suffix); err != nil {
		t.Fatalf("host-mode overlay must pass startup probe: %v", err)
	}
	// 偏离域名：探针即拒（省得等到发布期才发现）
	if _, err := NewIngressBuilder(writeOverlay(t, hostModeRules("demo.example.com", "{{K8S_NAME}}-svc")), "nginx", suffix); err == nil {
		t.Fatal("host-mode overlay with wrong host must fail at startup probe")
	}
	// 硬编码探针域名：探针放行、真实发布拒绝
	b, err := NewIngressBuilder(writeOverlay(t, hostModeRules("oaf-template-probe"+suffix, "oaf-template-probe-svc")), "nginx", suffix)
	if err != nil {
		t.Fatalf("probe-domain overlay should pass probe: %v", err)
	}
	if _, err := b.Build(hostModeParams()); err == nil {
		t.Fatal("hardcoded probe host must be rejected at publish time")
	}
}

// hostModeRules host 模式的完整 rules overlay（path 根路径、pathType Prefix）。
func hostModeRules(host, backend string) string {
	return fmt.Sprintf(`
spec:
  rules:
    - host: %s
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: %q
                port:
                  number: 8100
`, host, backend)
}

// hostModeRulesNoPathType 漏写 pathType 的 host 模式 rules（apiserver 会拒，提前拦）。
func hostModeRulesNoPathType(host, backend string) string {
	return fmt.Sprintf(`
spec:
  rules:
    - host: %s
      http:
        paths:
          - path: /
            backend:
              service:
                name: %q
                port:
                  number: 8100
`, host, backend)
}

// hostModeAnn 单条注解 overlay（host 模式下 rewrite 三项非空应被拒）。
func hostModeAnn(key, value string) string {
	return fmt.Sprintf("metadata:\n  annotations:\n    %s: %s\n", key, value)
}

// hostModeRulesWithPath 指定非根 path 的 host 模式 rules（应被拒：path 是不变量）。
func hostModeRulesWithPath(host, backend, path string) string {
	return fmt.Sprintf(`
spec:
  rules:
    - host: %s
      http:
        paths:
          - path: %s
            pathType: Prefix
            backend:
              service:
                name: %q
                port:
                  number: 8100
`, host, path, backend)
}

// fullIngressRules 合法形状的完整 rules overlay（backend 名硬编码，host 钉死探针域名
// 后缀），供探针用例复用——backend 非 {probe}-svc 即被不变量拒绝。
func fullIngressRules(backend string) string {
	return fmt.Sprintf(`
spec:
  rules:
    - host: oaf-template-probe.region-c86-test.test-kzx1.cncb
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: %q
                port:
                  number: 8100
`, backend)
}

// TestIngressEndpointTable Endpoint 派生（host 模式）：规则 host 优先 / 防御回落
// INGRESS_HOST / TLS 切 https（不拼 INGRESS_PORT，80/443 由域名承载）。
// router 模式展示地址不走 Ingress 对象，见 TestRouterEndpoint。
func TestIngressEndpointTable(t *testing.T) {
	hostBase := Ingress(hostModeParams())
	cases := []struct {
		name string
		ing  *networkingv1.Ingress
		host string
		want string
	}{
		{
			name: "host-mode-domain", ing: hostBase, host: "1.2.3.4",
			want: "http://oaf-acme-demo.region-c86-test.test-kzx1.cncb/",
		},
		{
			name: "host-mode-tls",
			ing: func() *networkingv1.Ingress {
				ing := hostBase.DeepCopy()
				ing.Spec.TLS = []networkingv1.IngressTLS{{Hosts: []string{"oaf-acme-demo.region-c86-test.test-kzx1.cncb"}}}
				return ing
			}(), host: "1.2.3.4",
			want: "https://oaf-acme-demo.region-c86-test.test-kzx1.cncb/",
		},
		{
			// 防御分支：host 模式下规则无 host（不可能过 validateIngress）时回落 INGRESS_HOST
			name: "host-mode-no-rule-host",
			ing: func() *networkingv1.Ingress {
				ing := hostBase.DeepCopy()
				ing.Spec.Rules[0].Host = ""
				return ing
			}(), host: "1.2.3.4",
			want: "http://1.2.3.4/",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := IngressEndpoint(tc.ing, tc.host); got != tc.want {
				t.Fatalf("want %q, got %q", tc.want, got)
			}
		})
	}
}

// TestRouterEndpoint router 模式展示地址：统一入口 + /agent/{short}/ 前缀，形状与原
// path 模式输出一致（前端/DB/MCP 契约零变化）；host 自带端口不重复拼接。
func TestRouterEndpoint(t *testing.T) {
	cases := []struct {
		host    string
		port    int
		k8sName string
		want    string
	}{
		{"1.2.3.4", 30080, "oaf-acme-demo", "http://1.2.3.4:30080/agent/acme-demo/"},
		{"172.20.0.2:30080", 30080, "oaf-demo", "http://172.20.0.2:30080/agent/demo/"},
		{"entry.example.com", 80, "oaf-x", "http://entry.example.com:80/agent/x/"},
	}
	for _, tc := range cases {
		if got := RouterEndpoint(tc.host, tc.port, tc.k8sName); got != tc.want {
			t.Errorf("RouterEndpoint(%q,%d,%q) = %q, want %q", tc.host, tc.port, tc.k8sName, got, tc.want)
		}
	}
}
