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
