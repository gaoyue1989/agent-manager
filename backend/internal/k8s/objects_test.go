package k8s

import (
	"testing"

	corev1 "k8s.io/api/core/v1"
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

func testParams() ObjectParams {
	return ObjectParams{
		K8sName: "oaf-acme-demo", Namespace: "agent-platform",
		Image:        "agent-framework:latest",
		Env:          map[string]string{"LOG_LEVEL": "info"},
		SubPath:      "packages/42",
		IngressClass: "nginx", IngressHost: "1.2.3.4", IngressPort: 30080,
	}
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
	if cs.Image != "agent-framework:latest" || cs.ImagePullPolicy != corev1.PullIfNotPresent {
		t.Fatalf("image: %v", cs.Image)
	}
	// envFrom ConfigMap
	if len(cs.EnvFrom) != 1 || cs.EnvFrom[0].ConfigMapRef.Name != "oaf-acme-demo-env" {
		t.Fatalf("envFrom: %+v", cs.EnvFrom)
	}
	// 固定注入保留键
	fixed := map[string]string{}
	for _, e := range cs.Env {
		fixed[e.Name] = e.Value
	}
	if fixed["AGENT_CONFIG_DIR"] != "/config" || fixed["SERVER_PORT"] != "8100" ||
		fixed["SERVER_HOST"] != "0.0.0.0" || fixed["AGENT_WORKSPACE_DIR"] != "/workspace" {
		t.Fatalf("fixed env missing: %+v", fixed)
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
	if len(d.Spec.Template.Spec.Volumes) != 2 {
		t.Fatalf("expected 2 volumes (files+workspace), got %d", len(d.Spec.Template.Spec.Volumes))
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

func TestServiceAndIngress(t *testing.T) {
	p := testParams()
	svc := Service(p)
	if svc.Name != "oaf-acme-demo-svc" || svc.Spec.Ports[0].Port != AgentPort {
		t.Fatalf("service: %+v", svc)
	}
	ing := Ingress(p)
	path := ing.Spec.Rules[0].HTTP.Paths[0].Path
	if path != "/agent/acme-demo(/|$)(.*)" {
		t.Fatalf("ingress path: %q", path)
	}
	if ing.Annotations["nginx.ingress.kubernetes.io/rewrite-target"] != "/$2" {
		t.Fatal("rewrite annotation missing")
	}
	if ing.Annotations["nginx.ingress.kubernetes.io/proxy-read-timeout"] != "3600" {
		t.Fatal("long proxy timeout annotation missing")
	}
	if ing.Annotations["nginx.ingress.kubernetes.io/x-forwarded-prefix"] != "/agent/acme-demo" {
		t.Fatalf("x-forwarded-prefix wrong: %v", ing.Annotations["nginx.ingress.kubernetes.io/x-forwarded-prefix"])
	}
	if ing.Spec.Rules[0].HTTP.Paths[0].Backend.Service.Name != "oaf-acme-demo-svc" {
		t.Fatal("ingress backend wrong")
	}
}

func TestReservedKeys(t *testing.T) {
	for _, k := range []string{"AGENT_CONFIG_DIR", "SERVER_HOST", "SERVER_PORT"} {
		if !ReservedEnvKeys[k] {
			t.Errorf("%s should be reserved", k)
		}
	}
}
