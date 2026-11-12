package k8s

import (
	"testing"

	corev1 "k8s.io/api/core/v1"
	networkingv1 "k8s.io/api/networking/v1"
)

func TestSanitizeK8sName(t *testing.T) {
	cases := map[string]string{
		"acme/demo-agent": "acme-demo-agent",
		"Acme Demo":       "acme-demo",
		"--weird__name--": "weird-name",
		"":                "svc",
	}
	for in, want := range cases {
		if got := SanitizeK8sName(in); got != want {
			t.Errorf("Sanitize(%q)=%q want %q", in, got, want)
		}
	}
	long := SanitizeK8sName(string(rune('a')) + repeat("b", 100))
	if len(long) != 63 {
		t.Errorf("long name should truncate to 63, got %d", len(long))
	}
}

func repeat(s string, n int) string {
	out := ""
	for i := 0; i < n; i++ {
		out += s
	}
	return out
}

func TestDeriveAndShortName(t *testing.T) {
	n := DeriveK8sName("", "acme/demo")
	if n != "oaf-acme-demo" {
		t.Fatalf("derive: %q", n)
	}
	if ShortName(n) != "acme-demo" {
		t.Fatalf("short: %q", ShortName(n))
	}
	if DeriveK8sName("My Custom Name", "x/y") != "oaf-my-custom-name" {
		t.Fatal("custom display name should win")
	}
}

// 派生名总长必须为最长子对象名（"{name}-skillsync-token"）预留后缀预算（issue #149：
// k8s 名贴 63 时 -svc 后缀拼出 67 被 K8s API 拒绝，且发布已落库 → 残留记录）。
func TestDeriveK8sNameLengthCap(t *testing.T) {
	long := repeat("z", 100)
	got := DeriveK8sName(long, "x/y")
	// oaf-(4) + base(≤43) + "-skillsync-token"(16) ≤ 63... 实际约束：k8s 名 ≤47，子对象 ≤63
	if len(got) > 63-len(SkillsyncTokenSuffix) {
		t.Fatalf("derived name length %d exceeds %d: %q", len(got), 63-len(SkillsyncTokenSuffix), got)
	}
	if got != "oaf-"+repeat("z", 43) {
		t.Fatalf("unexpected truncation: %q (want oaf- + 43 z)", got)
	}
	if n := len(got + "-svc"); n > 63 {
		t.Fatalf("svc object name %d exceeds 63", n)
	}
	if n := len(got + "-env-secret"); n > 63 {
		t.Fatalf("secret object name %d exceeds 63", n)
	}
	if n := len(got + SkillsyncTokenSuffix); n > 63 {
		t.Fatalf("skillsync token secret name %d exceeds 63", n)
	}
	// 非 ASCII 输入 sanitize 后长度收缩，同样满足上限
	if got := DeriveK8sName(repeat("智", 100), "x/y"); len(got) > 63-len(SkillsyncTokenSuffix) {
		t.Fatalf("cjk derive: %q (%d)", got, len(got))
	}
}

// TestSkillsyncTokenNameAndSecret per-service 技能同步 token Secret（skills-syncd D-8）：
// 名派生 {k8sName}-skillsync-token、key=token、值取自服务敏感 env SKILLSYNC_TOKEN，
// 落点固定池 workload ns（消费方，跨 ns secretKeyRef 引用不成立）。
func TestSkillsyncTokenNameAndSecret(t *testing.T) {
	if got := SkillsyncTokenName("oaf-demo"); got != "oaf-demo-skillsync-token" {
		t.Fatalf("name derive: %q", got)
	}
	if EnvSecretName("oaf-demo") != "oaf-demo-env-secret" {
		t.Fatal("env secret name must stay")
	}
	p := testParams()
	p.EnvSecret = map[string]string{"SKILLSYNC_TOKEN": "tok-1"}
	sec := SkillsyncTokenSecret(p)
	if sec.Name != SkillsyncTokenName(p.K8sName) || sec.Namespace != SandboxWorkloadNamespace {
		t.Fatalf("meta wrong: %+v", sec.ObjectMeta)
	}
	if sec.Labels[LabelKey] != p.K8sName || sec.Labels[ManagedByLabel] != ManagedByValue {
		t.Fatalf("labels wrong: %+v", sec.Labels)
	}
	if string(sec.Data[SkillsyncTokenDataKey]) != "tok-1" {
		t.Fatalf("data wrong: %+v", sec.Data)
	}
}

// testParams 不设 IngressHostSuffix —— 即 path 模式，是历史行为的回归锁基准。
func testParams() ObjectParams {
	return ObjectParams{
		K8sName: "oaf-acme-demo", Namespace: "agent-platform",
		Image:        "agent-framework:latest",
		Env:          map[string]string{"LOG_LEVEL": "info"},
		SubPath:      "packages/42",
		IngressClass: "nginx", IngressHost: "1.2.3.4", IngressPort: 30080,
	}
}

// hostModeParams 叠加域名后缀 → host 模式（每服务独立域名）。
func hostModeParams() ObjectParams {
	p := testParams()
	p.IngressHostSuffix = ".region-c86-test.test-kzx1.cncb"
	return p
}

func TestDeploymentConstruction(t *testing.T) {
	d := Deployment(testParams())
	cs := d.Spec.Template.Spec.Containers[0]

	if d.Name != "oaf-acme-demo" || d.Namespace != "agent-platform" {
		t.Fatalf("meta: %v %v", d.Name, d.Namespace)
	}
	if *d.Spec.Replicas != 1 {
		t.Fatal("default replicas should be 1")
	}
	// 最小暴露面（issue #232）：业务 Pod 显式禁用 SA token 自动挂载（nil 或 true
	// 都会按 SA/kubelet 默认投影 token）
	if d.Spec.Template.Spec.AutomountServiceAccountToken == nil || *d.Spec.Template.Spec.AutomountServiceAccountToken {
		t.Fatal("business pod must set automountServiceAccountToken: false")
	}
	if cs.Image != "agent-framework:latest" || cs.ImagePullPolicy != corev1.PullIfNotPresent {
		t.Fatalf("image: %v", cs.Image)
	}
	// envFrom 两源：服务敏感 Secret 在前，服务 CM 在后可覆盖同名键
	// （平台默认配置不经 envFrom 注入，R3 修订）
	if len(cs.EnvFrom) != 2 {
		t.Fatalf("envFrom should have 2 sources, got %d: %+v", len(cs.EnvFrom), cs.EnvFrom)
	}
	wantEnvFrom := []string{
		EnvSecretName("oaf-acme-demo"), "oaf-acme-demo-env",
	}
	for i, want := range wantEnvFrom {
		src := cs.EnvFrom[i]
		var got string
		switch {
		case src.ConfigMapRef != nil:
			got = src.ConfigMapRef.Name
		case src.SecretRef != nil:
			got = src.SecretRef.Name
		}
		if got != want {
			t.Fatalf("envFrom[%d] = %q, want %q (order matters: later overrides)", i, got, want)
		}
	}
	// 固定注入保留键
	fixed := map[string]string{}
	hostNameFromField := false
	for _, e := range cs.Env {
		fixed[e.Name] = e.Value
		// HOST_NAME 经 downward API 注入 Pod Name
		if e.Name == "HOST_NAME" && e.ValueFrom != nil && e.ValueFrom.FieldRef != nil &&
			e.ValueFrom.FieldRef.FieldPath == "metadata.name" {
			hostNameFromField = true
		}
	}
	if fixed["AGENT_CONFIG_DIR"] != "/config" || fixed["SERVER_PORT"] != "8100" ||
		fixed["SERVER_HOST"] != "0.0.0.0" || fixed["AGENT_WORKSPACE_DIR"] != "/workspace" {
		t.Fatalf("fixed env missing: %+v", fixed)
	}
	if !hostNameFromField {
		t.Fatal("HOST_NAME env (fieldRef metadata.name) missing")
	}
	// 工作区独立可写卷
	foundWs := false
	for _, v := range d.Spec.Template.Spec.Containers[0].VolumeMounts {
		if v.Name == WorkspaceVolumeName && v.MountPath == "/workspace" && !v.ReadOnly {
			foundWs = true
		}
	}
	if !foundWs {
		t.Fatal("workspace volume mount missing")
	}
	// PVC subPath 只读挂载 /config（挂载于可写卷 agent-files 之上，mount 级 readOnly）
	foundConfig := false
	for _, v := range cs.VolumeMounts {
		if v.MountPath == "/config" && v.SubPath == "packages/42" && v.ReadOnly {
			foundConfig = true
		}
	}
	if !foundConfig {
		t.Fatal("config volumeMount missing")
	}
	// 文件存储可写挂载 /data/files ← platform-data subPath files/
	foundFiles := false
	for _, v := range cs.VolumeMounts {
		if v.MountPath == FilesMountPath && v.SubPath == FilesSubPath && !v.ReadOnly {
			foundFiles = true
		}
	}
	if !foundFiles {
		t.Fatal("files volume mount missing or not writable")
	}
	// 日志规范挂载 /applog
	foundApplog := false
	for _, v := range cs.VolumeMounts {
		if v.Name == ApplogVolumeName && v.MountPath == ApplogMountPath && !v.ReadOnly {
			foundApplog = true
		}
	}
	if !foundApplog {
		t.Fatal("applog volume mount missing")
	}
	// 单卷承载（可写 PVC，无 ForceReadOnly；/config 只读由 mount 级 readOnly 表达）
	foundFilesVol := false
	for _, v := range d.Spec.Template.Spec.Volumes {
		if v.Name == FilesVolumeName && v.PersistentVolumeClaim != nil &&
			v.PersistentVolumeClaim.ClaimName == PVCName && !v.PersistentVolumeClaim.ReadOnly {
			foundFilesVol = true
		}
	}
	if !foundFilesVol {
		t.Fatal("files volume (writable PVC) missing")
	}
	// 日志目录卷（emptyDir，同 workspace 模式）
	foundApplogVol := false
	for _, v := range d.Spec.Template.Spec.Volumes {
		if v.Name == ApplogVolumeName && v.EmptyDir != nil {
			foundApplogVol = true
		}
	}
	if !foundApplogVol {
		t.Fatal("applog volume (emptyDir) missing")
	}
	if len(d.Spec.Template.Spec.Volumes) != 3 {
		t.Fatalf("expected 3 volumes (files+workspace+applog), got %d", len(d.Spec.Template.Spec.Volumes))
	}
	// 探针指向 /health:8100
	if cs.ReadinessProbe.HTTPGet.Path != "/health" || cs.ReadinessProbe.HTTPGet.Port.IntValue() != AgentPort {
		t.Fatal("readiness probe wrong")
	}
	// 资源规格
	if cs.Resources.Requests.Cpu().String() != "250m" {
		t.Fatalf("requests cpu default wrong: %s", cs.Resources.Requests.Cpu().String())
	}
	if cs.Resources.Limits.Memory().String() != "1Gi" {
		t.Fatalf("limits mem default wrong: %s", cs.Resources.Limits.Memory().String())
	}
	// selector 匹配 label
	if d.Spec.Selector.MatchLabels[LabelKey] != "oaf-acme-demo" {
		t.Fatal("selector mismatch")
	}
}

// TestServiceAndRouterEndpoint router 模式（suffix 空）不构造 per-service Ingress：
// Service 形状不变，对外展示地址由 RouterEndpoint 纯配置拼装（path 模式 rewrite 注解
// 已随 subpath-routing 设计删除，Ingress 仅剩 host 模式，见 TestIngressHostModeConstruction）。
func TestServiceAndRouterEndpoint(t *testing.T) {
	p := testParams()
	svc := Service(p)
	if svc.Name != "oaf-acme-demo-svc" || svc.Spec.Ports[0].Port != AgentPort {
		t.Fatalf("service: %+v", svc)
	}
	if got := RouterEndpoint(p.IngressHost, p.IngressPort, p.K8sName); got != "http://1.2.3.4:30080/agent/acme-demo/" {
		t.Fatalf("router endpoint: %q", got)
	}
}

// TestIngressHostModeConstruction host 模式（IngressHostSuffix 非空）内置构造：
// 域名 = {K8sName}{suffix}、path 根路径（backend 本服务）+ 内部路径排除表
// （/internal、/actuator 改道 platform-router 恒 404，保证设计 §6.1「内部端点不经
// 业务 Ingress 对外暴露」）、pathType Prefix、只留 ssl-redirect 与 SSE 超时注解——
// 不得出现任何 rewrite 相关注解（无前缀可剥离）。
func TestIngressHostModeConstruction(t *testing.T) {
	ing := Ingress(hostModeParams())
	if ing.Name != "oaf-acme-demo" || ing.Namespace != "agent-platform" {
		t.Fatalf("metadata wrong: %s/%s", ing.Namespace, ing.Name)
	}
	if ing.Spec.IngressClassName == nil || *ing.Spec.IngressClassName != "nginx" {
		t.Fatalf("ingressClassName wrong: %v", ing.Spec.IngressClassName)
	}
	if len(ing.Spec.Rules) != 1 {
		t.Fatalf("want exactly one rule, got %d", len(ing.Spec.Rules))
	}
	rule := ing.Spec.Rules[0]
	if rule.Host != "oaf-acme-demo.region-c86-test.test-kzx1.cncb" {
		t.Fatalf("host wrong: %q", rule.Host)
	}
	paths := map[string]networkingv1.HTTPIngressPath{}
	for _, p := range rule.HTTP.Paths {
		paths[p.Path] = p
	}
	if len(paths) != 1+len(hostModeBlockPathList) {
		t.Fatalf("want root path + exclusion table, got %+v", rule.HTTP.Paths)
	}
	root := paths[hostModeRootPath]
	if root.PathType == nil || *root.PathType != networkingv1.PathTypePrefix {
		t.Fatalf("root pathType wrong: %v", root.PathType)
	}
	if root.Backend.Service == nil || root.Backend.Service.Name != "oaf-acme-demo-svc" ||
		root.Backend.Service.Port.Number != AgentPort {
		t.Fatalf("root backend wrong: %+v", root.Backend.Service)
	}
	for _, bp := range hostModeBlockPathList {
		p, ok := paths[bp]
		if !ok {
			t.Fatalf("host mode must exclude internal path %s, got %+v", bp, rule.HTTP.Paths)
		}
		if p.PathType == nil || *p.PathType != networkingv1.PathTypePrefix {
			t.Fatalf("path %s pathType wrong: %v", bp, p.PathType)
		}
		if p.Backend.Service == nil || p.Backend.Service.Name != RouterServiceName ||
			p.Backend.Service.Port.Number != RouterServicePort {
			t.Fatalf("path %s backend wrong (must route to router 404 sink): %+v", bp, p.Backend.Service)
		}
	}
	wantAnn := map[string]string{
		annSSLRedirect:      "false",
		annProxyReadTimeout: "3600",
		annProxySendTimeout: "3600",
	}
	if len(ing.Annotations) != len(wantAnn) {
		t.Fatalf("host mode annotations must be exactly %v, got %v", wantAnn, ing.Annotations)
	}
	for k, want := range wantAnn {
		if ing.Annotations[k] != want {
			t.Errorf("annotation %s = %q, want %q", k, ing.Annotations[k], want)
		}
	}
	// rewrite 相关注解在 host 模式一律不得出现
	for _, k := range []string{annRewriteTarget, annUseRegex, annXForwardedPrefix} {
		if _, ok := ing.Annotations[k]; ok {
			t.Errorf("host mode must not carry rewrite annotation %s", k)
		}
	}
}

// TestIngressEmptyClassOmitsField IngressClass 空 = 不写 spec.ingressClassName
// （apiserver 落到集群默认 IngressClass；写空串会被拒，必须整体省略）；
// 路由形状（host/path/backend/注解）与显式 class 构造逐项一致。
func TestIngressEmptyClassOmitsField(t *testing.T) {
	p := hostModeParams()
	p.IngressClass = ""
	ing := Ingress(p)
	if ing.Spec.IngressClassName != nil {
		t.Fatalf("empty IngressClass must omit spec.ingressClassName, got %q", *ing.Spec.IngressClassName)
	}
	if len(ing.Spec.Rules) != 1 {
		t.Fatalf("routing shape must stay, rules: %+v", ing.Spec.Rules)
	}
	if ing.Spec.Rules[0].Host != p.K8sName+p.IngressHostSuffix {
		t.Fatalf("host wrong: %q", ing.Spec.Rules[0].Host)
	}
	if ing.Annotations[annSSLRedirect] != "false" ||
		ing.Annotations[annProxyReadTimeout] != "3600" || ing.Annotations[annProxySendTimeout] != "3600" {
		t.Fatalf("annotations must stay: %v", ing.Annotations)
	}
}

func TestReservedKeys(t *testing.T) {
	for _, k := range []string{"AGENT_CONFIG_DIR", "SERVER_HOST", "SERVER_PORT", "AGENT_WORKSPACE_DIR", "HOST_NAME"} {
		if !ReservedEnvKeys[k] {
			t.Errorf("%s should be reserved", k)
		}
	}
}

// TestDeploymentPVCNameFollowsParam 业务 Pod 挂载的平台数据卷 PVC 名随参数（Helm/清单部署
// 指向环境已有 PVC）；空值回落平台默认常量（历史行为不变）。
func TestDeploymentPVCNameFollowsParam(t *testing.T) {
	claim := func(p ObjectParams) string {
		d := Deployment(p)
		for _, v := range d.Spec.Template.Spec.Volumes {
			if v.Name == FilesVolumeName && v.PersistentVolumeClaim != nil {
				return v.PersistentVolumeClaim.ClaimName
			}
		}
		return ""
	}
	if got := claim(testParams()); got != PVCName {
		t.Fatalf("default claim must fall back to %q, got %q", PVCName, got)
	}
	p := testParams()
	p.PVCName = "existing-oaf-data"
	if got := claim(p); got != "existing-oaf-data" {
		t.Fatalf("custom claim must be honored, got %q", got)
	}
}
