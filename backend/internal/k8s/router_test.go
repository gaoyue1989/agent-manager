// router 模式启动自检的外部测试（package k8s_test）：k8sfake 依赖 k8s 包，
// 内部测试包（package k8s）引用会成 import cycle。
package k8s_test

import (
	"context"
	"os"
	"strings"
	"testing"

	"sigs.k8s.io/yaml"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/k8s/k8sfake"
)

// TestRequireRouter router 模式启动自检：Service 存在放行，缺失报错（指向 manifest）。
func TestRequireRouter(t *testing.T) {
	f := k8sfake.New()
	if err := k8s.RequireRouter(f, "test"); err == nil {
		t.Fatal("missing router service must fail")
	}
	svc := &corev1.Service{ObjectMeta: metav1.ObjectMeta{Name: k8s.RouterServiceName, Namespace: "test"}}
	if _, err := f.CS().CoreV1().Services("test").Create(context.TODO(), svc, metav1.CreateOptions{}); err != nil {
		t.Fatal(err)
	}
	if err := k8s.RequireRouter(f, "test"); err != nil {
		t.Fatalf("existing router service must pass: %v", err)
	}
}

// TestRouterManifestInternalPathExclusion router manifest 内部路径排除守卫：
// /agent/{short}/internal、/agent/{short}/actuator（段边界）在泛化 agent location
// 之前恒 404——正则 location 按声明顺序首匹配生效，删段或挪序都会重新经业务入口
// 暴露 skills-syncd 内部端点与 actuator。manifest 是 router 模式的唯一安全配置源，
// 本测试是 host 模式排除表校验（template.go validateIngress）在 router 侧的对偶。
func TestRouterManifestInternalPathExclusion(t *testing.T) {
	raw, err := os.ReadFile("../../../manifests/platform-router.yaml")
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}
	conf := platformRouterConfTemplate(t, raw)

	const exclusionLoc = `location ~ ^/agent/[a-z0-9-]+/(internal|actuator)(/|$) {`
	const genericLoc = `location ~ ^/agent/(?<short>[a-z0-9-]+) {`

	generic := strings.Index(conf, genericLoc)
	if generic < 0 {
		t.Fatalf("generic agent location missing from template")
	}
	exclusion := strings.Index(conf, exclusionLoc)
	if exclusion < 0 {
		t.Fatalf("internal path exclusion location missing: %s", exclusionLoc)
	}
	if exclusion > generic {
		t.Fatalf("exclusion location must precede generic agent location (regex locations match in declaration order)")
	}
	block := conf[exclusion:generic]
	if !strings.Contains(block, "return 404;") {
		t.Fatalf("exclusion location must return 404, block: %s", block)
	}
	if strings.Contains(block, "proxy_pass") {
		t.Fatalf("exclusion location must not proxy, block: %s", block)
	}
}

// platformRouterConfTemplate 取 manifest 首个文档（ConfigMap）中的 nginx 模板。
func platformRouterConfTemplate(t *testing.T, raw []byte) string {
	t.Helper()
	for _, doc := range strings.Split(string(raw), "\n---\n") {
		var obj struct {
			Kind     string `json:"kind"`
			Metadata struct {
				Name string `json:"name"`
			} `json:"metadata"`
			Data map[string]string `json:"data"`
		}
		if err := yaml.Unmarshal([]byte(doc), &obj); err != nil {
			t.Fatalf("parse manifest document: %v", err)
		}
		if obj.Kind == "ConfigMap" && obj.Metadata.Name == "platform-router-conf" {
			tpl, ok := obj.Data["default.conf.template"]
			if !ok {
				t.Fatalf("default.conf.template missing from ConfigMap platform-router-conf")
			}
			return tpl
		}
	}
	t.Fatalf("ConfigMap platform-router-conf not found in manifest")
	return ""
}
