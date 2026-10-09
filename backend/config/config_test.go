package config

import (
	"strings"
	"testing"
	"time"
)

func TestParseImages(t *testing.T) {
	t.Run("label and bare entries", func(t *testing.T) {
		imgs, def, err := parseImages("img1|Label A, img2|Label B , img3", "")
		if err != nil {
			t.Fatal(err)
		}
		if len(imgs) != 3 || imgs[0].Label != "Label A" || imgs[2].Label != "" || imgs[2].Image != "img3" {
			t.Fatalf("images parsed wrong: %+v", imgs)
		}
		if def != "img1" {
			t.Fatalf("default should fallback to first entry, got %q", def)
		}
	})
	t.Run("empty parts skipped", func(t *testing.T) {
		imgs, _, err := parseImages("a,,b,", "")
		if err != nil || len(imgs) != 2 {
			t.Fatalf("empty parts must be skipped: %+v %v", imgs, err)
		}
	})
	t.Run("entry without image rejected", func(t *testing.T) {
		if _, _, err := parseImages("img1,|NoImage", ""); err == nil ||
			!strings.Contains(err.Error(), "invalid AVAILABLE_IMAGES entry") {
			t.Fatalf("expect invalid entry error, got %v", err)
		}
	})
	t.Run("empty raw", func(t *testing.T) {
		imgs, def, err := parseImages("", "")
		if err != nil || imgs != nil || def != "" {
			t.Fatalf("empty raw should yield nil/nil, got %+v %q %v", imgs, def, err)
		}
	})
	// 现行为钉扎：DEFAULT_IMAGE 不在列表内时 Load 不报错，
	// 但 IsAllowedImage 只认列表 → 该默认镜像实际发布必然被拒。改动此行为需同步调整部署文档。
	t.Run("default outside list returned as-is", func(t *testing.T) {
		imgs, def, err := parseImages("img1|A", "other:latest")
		if err != nil || def != "other:latest" || len(imgs) != 1 {
			t.Fatalf("unexpected: %+v %q %v", imgs, def, err)
		}
	})
}

func TestLoadRequiresMySQLDSN(t *testing.T) {
	t.Setenv("MYSQL_DSN", "")
	t.Setenv("AVAILABLE_IMAGES", "img1|A")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "MYSQL_DSN is required") {
		t.Fatalf("expect MYSQL_DSN required error, got %v", err)
	}
}

func TestLoadParsesImagesAndAllowedCheck(t *testing.T) {
	t.Setenv("MYSQL_DSN", "u:p@tcp(localhost:3306)/oaf_platform")
	t.Setenv("AVAILABLE_IMAGES", "agent-framework:latest|Agent Framework, other:v1|Other")
	t.Setenv("DEFAULT_IMAGE", "")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(c.AvailableImages) != 2 || c.DefaultImage != "agent-framework:latest" {
		t.Fatalf("images wrong: %+v default=%q", c.AvailableImages, c.DefaultImage)
	}
	if !c.IsAllowedImage("agent-framework:latest") || c.IsAllowedImage("evil:tag") {
		t.Fatal("IsAllowedImage inconsistent with list")
	}
	if c.RegisterTimeout != 120*time.Second {
		t.Fatalf("register timeout default wrong: %v", c.RegisterTimeout)
	}
}

func TestEnvIntInvalidFallsBack(t *testing.T) {
	t.Setenv("TEST_PORT", "abc")
	if got := envInt("TEST_PORT", 8080); got != 8080 {
		t.Fatalf("invalid int must fall back to default, got %d", got)
	}
	t.Setenv("TEST_PORT", "9090")
	if got := envInt("TEST_PORT", 8080); got != 9090 {
		t.Fatalf("valid int must be used, got %d", got)
	}
}

// TestLoadIngressHostSuffix INGRESS_HOST_SUFFIX 格式校验：空值放行（path 模式，
// 历史行为）；非空必须以 "." 开头、去点后为合法 DNS-1123 subdomain、叠加最长
// K8sName（67 字符）后总长 ≤253。非法即 Load 报错（启动 fail-fast）。
func TestLoadIngressHostSuffix(t *testing.T) {
	t.Setenv("MYSQL_DSN", "u:p@tcp(localhost:3306)/oaf_platform")
	t.Setenv("AVAILABLE_IMAGES", "img1|A")
	t.Setenv("DEFAULT_IMAGE", "")

	// 默认空值 = path 模式，行为与未上此功能前一致
	t.Setenv("INGRESS_HOST_SUFFIX", "")
	c, err := Load()
	if err != nil {
		t.Fatalf("empty suffix must pass: %v", err)
	}
	if c.IngressHostSuffix != "" {
		t.Fatalf("default suffix must stay empty, got %q", c.IngressHostSuffix)
	}

	// 叠加后总长上界 = 253 - 67 = 186。too-long 用例必须**形态合法**（各段 ≤63、
	// 无首尾空段），否则会被 subdomain 正则/段长校验先拒，根本走不到 253 这条规则，
	// 「后缀 ≤186」就成了没有测试隔离的不变量。
	bad := map[string]struct{ suffix, wantErr string }{
		"no-leading-dot":   {"region.example.com", "must start with '.'"},
		"uppercase":        {".Region.example.com", "invalid INGRESS_HOST_SUFFIX"},
		"illegal-char":     {".region_c86.example.com", "invalid INGRESS_HOST_SUFFIX"},
		"empty-label":      {".region..example.com", "invalid INGRESS_HOST_SUFFIX"},
		"segment-too-long": {"." + strings.Repeat("a", 64) + ".example.com", "segment"},
		// 191 字符 = 63+63+62 三段（各段合法），191+67=258 > 253 → 只有总长规则能命中
		"too-long": {"." + strings.Repeat("a", 63) + "." + strings.Repeat("b", 63) +
			"." + strings.Repeat("c", 62), "is too long"},
	}
	for name, tc := range bad {
		t.Run(name, func(t *testing.T) {
			t.Setenv("INGRESS_HOST_SUFFIX", tc.suffix)
			_, err := Load()
			if err == nil {
				t.Fatalf("suffix %q must be rejected by Load", tc.suffix)
			}
			// 断言命中的是预期规则而非前面的某条，避免"换个非法输入也能过"的假绿
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("suffix %q must be rejected by the %q rule, got %v", tc.suffix, tc.wantErr, err)
			}
		})
	}

	// 合法：issue 示例后缀，以及恰好压线（186 字符）的后缀
	for _, suffix := range []string{
		".region-c86-test.test-kzx1.cncb",
		"." + strings.Repeat("a", 63) + "." + strings.Repeat("b", 63) + "." + strings.Repeat("c", 57), // 共 186 = 253-67
	} {
		t.Setenv("INGRESS_HOST_SUFFIX", suffix)
		got, err := Load()
		if err != nil {
			t.Fatalf("suffix %q must be accepted: %v", suffix, err)
		}
		if got.IngressHostSuffix != suffix {
			t.Fatalf("suffix not read back: %q", got.IngressHostSuffix)
		}
	}
}
