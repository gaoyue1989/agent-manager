// Package config 加载平台运行配置（全部来自环境变量，敏感项无默认值）。
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"k8s.io/apimachinery/pkg/util/validation"
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
	// IngressHostSuffix 业务 Ingress 域名后缀（如 .region-c86-test.test-kzx1.cncb）：
	// 空 = path 模式（无 host，靠 /agent/{short} 前缀 + rewrite 区分服务，即历史行为）；
	// 非空 = host 模式（host={K8sName}{suffix}、根路径直出、无 rewrite 注解）。
	IngressHostSuffix string

	ResourceRequestsCPU string
	ResourceRequestsMem string
	ResourceLimitsCPU   string
	ResourceLimitsMem   string

	RegisterTimeout time.Duration // 等待 Deployment Ready 超时
	RegisterRetry   int           // agent-card 拉取重试次数

	DeploymentTemplate string // 业务 Deployment overlay 文件路径（Strategic Merge Patch），空=纯内置构造
	IngressTemplate    string // 业务 Ingress overlay 文件路径（Strategic Merge Patch），空=纯内置构造

	AuthToken string // 非空时启用 Bearer 校验

	PackageDownloadBase string // create_oaf_zip 返回 download_url 的基地址（集群内可达即可，代理方为业务 Agent Pod）
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
		IngressHostSuffix:   envStr("INGRESS_HOST_SUFFIX", ""),
		ResourceRequestsCPU: envStr("RESOURCE_REQUESTS_CPU", "250m"),
		ResourceRequestsMem: envStr("RESOURCE_REQUESTS_MEM", "256Mi"),
		ResourceLimitsCPU:   envStr("RESOURCE_LIMITS_CPU", "1"),
		ResourceLimitsMem:   envStr("RESOURCE_LIMITS_MEM", "1Gi"),
		RegisterTimeout:     time.Duration(envInt("REGISTER_TIMEOUT_SEC", 120)) * time.Second,
		RegisterRetry:       envInt("REGISTER_RETRY", 5),
		DeploymentTemplate:  envStr("DEPLOYMENT_TEMPLATE", ""),
		IngressTemplate:     envStr("INGRESS_TEMPLATE", ""),
		AuthToken:           envStr("AUTH_TOKEN", ""),
		PackageDownloadBase: envStr("PACKAGE_DOWNLOAD_BASE", "http://platform-backend.agent-platform.svc.cluster.local:8080"),
	}
	if c.MySQLDSN == "" {
		return nil, errors.New("MYSQL_DSN is required")
	}
	// 后缀格式非法时直接启动失败：域名形态一经生效会影响全部服务的 Ingress 与展示地址，
	// 与 MYSQL_DSN 缺失同策略（fail-fast），不在发布期逐个报错。
	if err := validateIngressHostSuffix(c.IngressHostSuffix); err != nil {
		return nil, err
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

// INGRESS_HOST_SUFFIX 校验相关常量。K8sName 上界取 67（不是 63）：DeriveK8sName =
// Prefix + SanitizeK8sName(base)，而 SanitizeK8sName 内部先截断到 63 再前置 "oaf-"
// （internal/k8s/objects.go），叠加域名后仍须满足 DNS-1123 subdomain 上界 253。
const (
	maxK8sNameLen      = 67
	dnsMaxSubdomainLen = validation.DNS1123SubdomainMaxLength
)

// validateIngressHostSuffix 校验域名后缀：空值放行（path 模式）；非空时必须以 "." 开头、
// 去点后为合法 DNS-1123 subdomain 且逐段为合法 label（各段 ≤63），
// 且叠加最长 K8sName 后总长不超过 253（否则 apiserver 会拒收 Ingress）。
//
// 两道校验不可互相替代：IsDNS1123Subdomain 只做点分正则 + 整体 253 上界，其
// dns1123SubdomainFmt 正则不含每段长度约束（每段 ≤63 是 IsDNS1123Label 的职责），
// 只跑前者会让 64 字符的段直接放行。
//
// 拼接后的首段（{K8sName}{后缀第一段}）可能因 K8sName 最长 67 字符而超过 63：此处
// 不额外拒绝——K8s 对 rules[].host 只强校验 253 总量（issue §5 的不变量亦只写 253），
// 且平台本就在别处产出超 63 的对象名（{K8sName}-env-secret 等），按更严标准 fail-fast
// 只会拒掉 apiserver 接受、现实中可解析的配置。证书签发对首段长度的限制属环境侧责任。
func validateIngressHostSuffix(suffix string) error {
	if suffix == "" {
		return nil
	}
	if !strings.HasPrefix(suffix, ".") {
		return fmt.Errorf("INGRESS_HOST_SUFFIX must start with '.': %q", suffix)
	}
	trimmed := strings.TrimPrefix(suffix, ".")
	if errs := validation.IsDNS1123Subdomain(trimmed); len(errs) > 0 {
		return fmt.Errorf("invalid INGRESS_HOST_SUFFIX %q: %s", suffix, strings.Join(errs, "; "))
	}
	for _, seg := range strings.Split(trimmed, ".") {
		if errs := validation.IsDNS1123Label(seg); len(errs) > 0 {
			return fmt.Errorf("invalid INGRESS_HOST_SUFFIX %q: segment %q: %s",
				suffix, seg, strings.Join(errs, "; "))
		}
	}
	if len(suffix)+maxK8sNameLen > dnsMaxSubdomainLen {
		return fmt.Errorf("INGRESS_HOST_SUFFIX %q is too long: %d + %d > %d (host = k8sName + suffix must stay a valid DNS subdomain)",
			suffix, len(suffix), maxK8sNameLen, dnsMaxSubdomainLen)
	}
	return nil
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
