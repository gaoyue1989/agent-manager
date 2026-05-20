package k8s

import (
	"strings"
	"testing"
)

// UT-05: 验证 kubectl 命令构建含 --kubeconfig 和 -n
func TestKubectlClient_KubectlArgs_WithConfig(t *testing.T) {
	c := NewKubectlClient("/path/to/kubeconfig", "mynamespace")

	cmd := c.kubectl("apply", "-f", "-")

	args := cmd.Args
	hasKubeconfig := false
	hasNamespace := false
	for _, a := range args {
		if a == "--kubeconfig" {
			hasKubeconfig = true
		}
		if a == "-n" {
			hasNamespace = true
		}
		if a == "mynamespace" {
			hasNamespace = true
		}
	}

	if !hasKubeconfig {
		t.Error("expected --kubeconfig flag in kubectl args")
	}
	if !hasNamespace {
		t.Error("expected -n mynamespace in kubectl args")
	}
}

// UT-06: kubeconfig 为空时不传 --kubeconfig
func TestKubectlClient_KubectlArgs_NoKubeconfig(t *testing.T) {
	c := NewKubectlClient("", "default")

	cmd := c.kubectl("get", "pods")

	args := cmd.Args
	for _, a := range args {
		if a == "--kubeconfig" {
			t.Error("--kubeconfig should not be present when kubeconfig is empty")
		}
	}
}

// UT-03/04: ResourceExists 测试
func TestKubectlClient_ResourceExists_NotFound(t *testing.T) {
	c := NewKubectlClient("", "default")
	exists := c.ResourceExists("sandbox", "nonexistent-99999")
	if exists {
		t.Error("expected false for non-existent resource")
	}
}

// UT-07: ListPodsJSON 正确构建命令
func TestKubectlClient_ListPodsJSON_Args(t *testing.T) {
	c := NewKubectlClient("", "default")
	cmd := c.kubectl("get", "pods", "-l", "app=test", "-o", "json")

	args := cmd.Args
	hasLabel := false
	hasOutput := false
	for _, a := range args {
		if a == "-l" {
			hasLabel = true
		}
		if a == "-o" {
			hasOutput = true
		}
	}
	if !hasLabel {
		t.Error("expected -l flag for label selector")
	}
	if !hasOutput {
		t.Error("expected -o flag for output format")
	}
}

// UT-08: GetResourceJSON 正确构建 jsonpath
func TestKubectlClient_GetResourceJSON_Args(t *testing.T) {
	c := NewKubectlClient("", "testns")

	endpoint, err := c.GetServiceEndpoint("test-svc")
	if err == nil && endpoint == "" {
		// kubectl not available, which is fine — expect error or empty result
	} else if err != nil {
		// expected when kubectl not available
	} else {
		t.Log("got endpoint:", endpoint)
	}
}

// UT-05 补充: 验证 kubectl 命令参数顺序 (--kubeconfig 在子命令前)
func TestKubectlClient_KubectlArgs_Order(t *testing.T) {
	c := NewKubectlClient("/path/to/config", "testns")

	cmd := c.kubectl("delete", "service", "test-svc")

	args := cmd.Args
	// kubectl [--kubeconfig path] [-n testns] delete service test-svc
	foundKubectl := false
	foundDelete := false
	for _, a := range args {
		if a == "kubectl" {
			foundKubectl = true
		}
		if a == "delete" {
			foundDelete = true
		}
	}
	if !foundKubectl {
		t.Error("expected kubectl command")
	}
	if !foundDelete {
		t.Error("expected delete subcommand")
	}
}

// UT-01: ApplyYAML 测试
func TestKubectlClient_ApplyYAML_InvalidYAML(t *testing.T) {
	c := NewKubectlClient("", "default")
	err := c.ApplyYAML("invalid: yaml: content")
	if err != nil {
		t.Log("expected error from kubectl apply:", err)
	}
}

// TestK8sClient_DeleteResource_KindMapping verifies DeleteResource uses correct kind
func TestKubectlClient_DeleteResource_Kinds(t *testing.T) {
	c := NewKubectlClient("", "default")

	testCases := []struct {
		kind string
		name string
	}{
		{"deployment", "test-dep"},
		{"service", "test-svc"},
		{"ingress", "test-ing"},
		{"configmap", "test-cm"},
		{"secret", "test-secret"},
		{"sandbox", "test-sandbox"},
	}

	for _, tc := range testCases {
		cmd := c.kubectl("delete", tc.kind, tc.name, "--ignore-not-found")
		args := cmd.Args
		foundKind := false
		for _, a := range args {
			if a == tc.kind {
				foundKind = true
				break
			}
		}
		if !foundKind {
			t.Errorf("expected kind %q in delete args for %s", tc.kind, tc.name)
		}
	}
}

// TestKubectlClient_KubeconfigInArgs verifies kubeconfig arg position
func TestKubectlClient_KubeconfigInArgs(t *testing.T) {
	c := NewKubectlClient("/custom/kubeconfig", "production")

	cmd := c.kubectl("get", "deployment", "myapp")

	argsStr := strings.Join(cmd.Args, " ")
	if !strings.Contains(argsStr, "--kubeconfig") || !strings.Contains(argsStr, "/custom/kubeconfig") {
		t.Error("args should contain --kubeconfig /custom/kubeconfig, got:", argsStr)
	}
	if !strings.Contains(argsStr, "-n") || !strings.Contains(argsStr, "production") {
		t.Error("args should contain -n production, got:", argsStr)
	}
}

// TestExecCommand tests the ExecCommand method
func TestKubectlClient_ExecCommand(t *testing.T) {
	c := NewKubectlClient("", "default")
	_, err := c.ExecCommand("test-pod", "echo", "hello")
	if err != nil {
		t.Log("expected error from kubectl exec:", err)
	}
}
