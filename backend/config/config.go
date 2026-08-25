// Package config 加载平台运行配置（全部来自环境变量，敏感项无默认值）。
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// ImageOption 可选镜像项。
type ImageOption struct {
	Image string `json:"image"`
	Label string `json:"label"`
}

type Config struct {
	ServerPort int
	Namespace  string
	DataRoot   string // 共享 PVC 挂载点

	MySQLDSN string // 必填，无默认值

	AvailableImages []ImageOption
	DefaultImage    string

	IngressClass string
	IngressHost  string // 对外展示地址，如 172.20.0.2:30080
	IngressPort  int    // ingress http NodePort，仅用于拼接展示 URL

	ResourceRequestsCPU string
	ResourceRequestsMem string
	ResourceLimitsCPU   string
	ResourceLimitsMem   string

	RegisterTimeout time.Duration // 等待 Deployment Ready 超时
	RegisterRetry   int           // agent-card 拉取重试次数

	AuthToken string // 非空时启用 Bearer 校验
}

func Load() (*Config, error) {
	c := &Config{
		ServerPort:          envInt("SERVER_PORT", 8080),
		Namespace:           envStr("NAMESPACE", "agent-platform"),
		DataRoot:            envStr("DATA_ROOT", "/data"),
		MySQLDSN:            envStr("MYSQL_DSN", ""),
		IngressClass:        envStr("INGRESS_CLASS", "nginx"),
		IngressHost:         envStr("INGRESS_HOST", "localhost"),
		IngressPort:         envInt("INGRESS_PORT", 30080),
		ResourceRequestsCPU: envStr("RESOURCE_REQUESTS_CPU", "250m"),
		ResourceRequestsMem: envStr("RESOURCE_REQUESTS_MEM", "256Mi"),
		ResourceLimitsCPU:   envStr("RESOURCE_LIMITS_CPU", "1"),
		ResourceLimitsMem:   envStr("RESOURCE_LIMITS_MEM", "1Gi"),
		RegisterTimeout:     time.Duration(envInt("REGISTER_TIMEOUT_SEC", 120)) * time.Second,
		RegisterRetry:       envInt("REGISTER_RETRY", 5),
		AuthToken:           envStr("AUTH_TOKEN", ""),
	}
	if c.MySQLDSN == "" {
		return nil, errors.New("MYSQL_DSN is required")
	}
	images, def, err := parseImages(envStr("AVAILABLE_IMAGES", ""), envStr("DEFAULT_IMAGE", ""))
	if err != nil {
		return nil, err
	}
	if len(images) == 0 {
		return nil, errors.New("AVAILABLE_IMAGES is required (e.g. 'agent-framework:latest|Agent Framework latest')")
	}
	if def == "" {
		def = images[0].Image
	}
	c.AvailableImages = images
	c.DefaultImage = def
	return c, nil
}

// parseImages 解析 "img1|Label1,img2|Label2" 格式；DEFAULT_IMAGE 为空取第一项。
func parseImages(raw, def string) ([]ImageOption, string, error) {
	var out []ImageOption
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		img, label := part, ""
		if i := strings.Index(part, "|"); i >= 0 {
			img, label = strings.TrimSpace(part[:i]), strings.TrimSpace(part[i+1:])
		}
		if img == "" {
			return nil, "", fmt.Errorf("invalid AVAILABLE_IMAGES entry: %q", part)
		}
		out = append(out, ImageOption{Image: img, Label: label})
	}
	for _, o := range out {
		if o.Image == def {
			return out, def, nil
		}
	}
	if len(out) > 0 && def == "" {
		return out, out[0].Image, nil
	}
	return out, def, nil
}

func envStr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func envInt(k string, d int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return d
}

// IsAllowedImage 校验镜像是否在可选列表内。
func (c *Config) IsAllowedImage(img string) bool {
	for _, o := range c.AvailableImages {
		if o.Image == img {
			return true
		}
	}
	return false
}
