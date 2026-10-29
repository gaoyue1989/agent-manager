// 工作空间运行时默认配置（跨集群导出 L1 层）：runtime-defaults 读写与 config 组骨架端点的业务层。
// 设计见 docs/design/cross-cluster-export-design.md §8/§10；文件级契约见
// docs/implementation/cross-cluster-export-contract.md §1.4/§2.1。
// 键空间（契约 §1.4）：24 模板键（platformconfig.KnownKeys − CHECKPOINT_JDBC_URL）+ 8 导出键，
// L3 派生键与平台保留键结构性排除——L1 不可能与其撞键，撞键裁决只存在于导出链路 L2 对 L1/L3。
// 三态语义对齐 UpdatePlatformConfig（platformconfig.go:120-165）：非空=设置、空串=删除、缺失=不变。
package service

import (
	"errors"
	"fmt"
	"strings"

	"gorm.io/gorm"
	"k8s.io/apimachinery/pkg/util/validation"

	"agent-manager/backend/internal/service/platformconfig"
	"agent-manager/backend/internal/store"
)

// runtimeExportKeys 导出专用键（模板之外，v1 固定 8 键，全部非敏感；契约 §1.4 表）。
var runtimeExportKeys = map[string]string{
	"CHECKPOINT_JDBC_BASE":        "mysql",    // checkpoint 基库 URL，L3 按 {基库}_{short} 派生实际库
	"OTEL_EXPORTER_OTLP_ENDPOINT": "otel",     // OTLP 上报地址
	"REGISTRY_ADDRESS":            "registry", // OAF 镜像直推目标仓库地址（如 172.20.0.1:5001）
	"REGISTRY_NAMESPACE":          "registry", // 仓库内命名空间段（可空）
	"IMAGE_PULL_SECRETS":          "registry", // 逗号分隔 imagePullSecrets 名
	"ROUTING_MODE":                "routing",  // host | router（缺省 host）
	"ROUTING_HOST_SUFFIX":         "routing",  // host 模式域名后缀（以 . 开头，校验同 config.go:150-172）
	"BASE_IMAGE_WHITELIST":        "runtime",  // 基础镜像白名单，格式同 AVAILABLE_IMAGES（config.go:174-200 解析）
}

// l3DerivedKeys L3 导出渲染派生键：L1 不允许持有（契约 §1.4/§5.4）——CHECKPOINT_JDBC_URL
// 由 CHECKPOINT_JDBC_BASE 派生（publishisolation.go deriveCheckpointURL）、AGENT_REDIS_PREFIX
// 由服务短名派生（redisPrefixFor）；若 L1 合法持有会与渲染产物撞键，最坏把源集群
// JDBC URL（可能内嵌凭据）明文导出进生产 values。模板键空间因此剔除 CHECKPOINT_JDBC_URL。
var l3DerivedKeys = map[string]bool{
	"CHECKPOINT_JDBC_URL": true,
	"AGENT_REDIS_PREFIX":  true,
}

// runtimeKeyMeta 键空间元数据（分类与敏感标记的事实源）。
type runtimeKeyMeta struct {
	Sensitive bool
	Category  string
}

// buildRuntimeDefaultKeys 运行时默认键空间：模板全键（剔除 L3 派生键）+ 导出专用键。
// 有效规模 = 24 模板键 + 8 导出键 = 32（单测锁定）。
func buildRuntimeDefaultKeys() map[string]runtimeKeyMeta {
	out := map[string]runtimeKeyMeta{}
	for _, g := range platformconfig.Template() {
		for _, f := range g.Fields {
			if l3DerivedKeys[f.EnvKey] {
				continue
			}
			out[f.EnvKey] = runtimeKeyMeta{Sensitive: f.Sensitive, Category: g.Name}
		}
	}
	for k, cat := range runtimeExportKeys {
		out[k] = runtimeKeyMeta{Category: cat} // 导出键全部非敏感
	}
	return out
}

// runtimeDefaultKeys 键空间（包级一次性构建；platformconfig.Template 为只读静态模板）。
var runtimeDefaultKeys = buildRuntimeDefaultKeys()

// RuntimeDefaultEntry 单键视图：敏感键永不回明文（掩码惯例 = 仅 hasValue 不回值，
// 先例 PlatformConfigFieldView platformconfig.go:26）。
type RuntimeDefaultEntry struct {
	Value     string `json:"value,omitempty"` // 仅非敏感键回填真实值
	HasValue  bool   `json:"hasValue"`
	Sensitive bool   `json:"sensitive"`
	Category  string `json:"category"`
}

// loadRuntimeDefaultValues 读取本空间运行时默认事实源 → 键值 map。
func (c *Core) loadRuntimeDefaultValues(wsID uint) (map[string]string, error) {
	var rows []store.WorkspaceRuntimeDefaultEntity
	if err := c.DB.Where("workspace_id = ?", wsID).Find(&rows).Error; err != nil {
		return nil, err
	}
	values := make(map[string]string, len(rows))
	for _, r := range rows {
		values[r.EnvKey] = r.Value
	}
	return values, nil
}

// GetRuntimeDefaults 运行时默认配置视图：全键白名单（未配置键也在，供配置页表单 schema），
// 敏感键永不回明文。
func (c *Core) GetRuntimeDefaults(wsID uint) (map[string]RuntimeDefaultEntry, error) {
	values, err := c.loadRuntimeDefaultValues(wsID)
	if err != nil {
		return nil, err
	}
	out := make(map[string]RuntimeDefaultEntry, len(runtimeDefaultKeys))
	for k, meta := range runtimeDefaultKeys {
		entry := RuntimeDefaultEntry{Sensitive: meta.Sensitive, Category: meta.Category}
		if v, ok := values[k]; ok {
			entry.HasValue = true
			if !meta.Sensitive {
				entry.Value = v
			}
		}
		out[k] = entry
	}
	return out, nil
}

// rejectRuntimeKey 键空间拒绝文案：L3 派生键 / 平台保留键 / 未知键，均点名键名（契约 §1.4）。
func rejectRuntimeKey(k string) error {
	if l3DerivedKeys[k] {
		return fmt.Errorf("%w: %q is derived at export time (L3) and cannot be a runtime default",
			ErrUnknownConfigKey, k)
	}
	if reservedKeys[k] {
		return fmt.Errorf("%w: %q is reserved by platform", ErrUnknownConfigKey, k)
	}
	return fmt.Errorf("%w: %q", ErrUnknownConfigKey, k)
}

// validateRoutingHostSuffix ROUTING_HOST_SUFFIX 校验口径同 config.validateIngressHostSuffix
// （config.go:150-172，未导出故按同规则复刻）：以 "." 开头、去点后合法 DNS-1123 subdomain
// 且逐段合法 label（各段 ≤63），叠加最长 K8sName（67，objects.go DeriveK8sName 上界）后
// 总长 ≤253。
const runtimeMaxK8sNameLen = 67

func validateRoutingHostSuffix(suffix string) error {
	if !strings.HasPrefix(suffix, ".") {
		return fmt.Errorf("%w: ROUTING_HOST_SUFFIX must start with '.': %q", ErrInvalidForm, suffix)
	}
	trimmed := strings.TrimPrefix(suffix, ".")
	if errs := validation.IsDNS1123Subdomain(trimmed); len(errs) > 0 {
		return fmt.Errorf("%w: invalid ROUTING_HOST_SUFFIX %q: %s", ErrInvalidForm, suffix, strings.Join(errs, "; "))
	}
	for _, seg := range strings.Split(trimmed, ".") {
		if errs := validation.IsDNS1123Label(seg); len(errs) > 0 {
			return fmt.Errorf("%w: invalid ROUTING_HOST_SUFFIX %q: segment %q: %s",
				ErrInvalidForm, suffix, seg, strings.Join(errs, "; "))
		}
	}
	if len(suffix)+runtimeMaxK8sNameLen > validation.DNS1123SubdomainMaxLength {
		return fmt.Errorf("%w: ROUTING_HOST_SUFFIX %q is too long: %d + %d > %d",
			ErrInvalidForm, suffix, len(suffix), runtimeMaxK8sNameLen, validation.DNS1123SubdomainMaxLength)
	}
	return nil
}

// validateBaseImageWhitelist BASE_IMAGE_WHITELIST 解析口径同 config.parseImages
// （config.go:174-200）："ref|label,ref|label" 逗号分隔，逐段镜像引用不得为空。
func validateBaseImageWhitelist(raw string) error {
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		img := part
		if i := strings.Index(part, "|"); i >= 0 {
			img = strings.TrimSpace(part[:i])
		}
		if img == "" {
			return fmt.Errorf("%w: invalid BASE_IMAGE_WHITELIST entry: %q", ErrInvalidForm, part)
		}
	}
	return nil
}

// validateRuntimeKeyValue 特定键的值格式校验（空串=删除动作，放行）。
func validateRuntimeKeyValue(k, v string) error {
	if v == "" {
		return nil
	}
	switch k {
	case "ROUTING_MODE":
		if v != "host" && v != "router" {
			return fmt.Errorf("%w: ROUTING_MODE must be host or router, got %q", ErrInvalidForm, v)
		}
	case "ROUTING_HOST_SUFFIX":
		return validateRoutingHostSuffix(v)
	case "BASE_IMAGE_WHITELIST":
		return validateBaseImageWhitelist(v)
	}
	return nil
}

// UpdateRuntimeDefaults 三态部分更新：非空=设置、空串=删除、缺失=不变；未知键 400（点名键名）。
// 权限（owner/admin）由 handler 层 requireWsRole 裁决；username 记审计（UpdatedBy 列）。
func (c *Core) UpdateRuntimeDefaults(wsID uint, username string, values map[string]string) (map[string]RuntimeDefaultEntry, error) {
	if len(values) == 0 {
		return nil, ErrEmptyConfigValues
	}
	for k, v := range values {
		if _, ok := runtimeDefaultKeys[k]; !ok {
			return nil, rejectRuntimeKey(k)
		}
		if len(v) > MaxEnvValueBytes {
			return nil, fmt.Errorf("%w: runtime default value of %q exceeds %d bytes",
				ErrInvalidForm, k, MaxEnvValueBytes)
		}
		if err := validateRuntimeKeyValue(k, v); err != nil {
			return nil, err
		}
	}
	err := c.DB.Transaction(func(tx *gorm.DB) error {
		for k, v := range values {
			if v == "" {
				if err := tx.Where("workspace_id = ? AND env_key = ?", wsID, k).
					Delete(&store.WorkspaceRuntimeDefaultEntity{}).Error; err != nil {
					return err
				}
				continue
			}
			meta := runtimeDefaultKeys[k]
			var row store.WorkspaceRuntimeDefaultEntity
			err := tx.Where("workspace_id = ? AND env_key = ?", wsID, k).First(&row).Error
			if errors.Is(err, gorm.ErrRecordNotFound) {
				row = store.WorkspaceRuntimeDefaultEntity{WorkspaceID: wsID, EnvKey: k}
			} else if err != nil {
				return err
			}
			row.Value = v
			row.Sensitive = meta.Sensitive
			row.Category = meta.Category
			row.UpdatedBy = username
			if err := tx.Save(&row).Error; err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return c.GetRuntimeDefaults(wsID)
}

// ---- config 组骨架端点（设计 §9 config/templates/:group、config/registry、config/mcp-connectors；
// 契约 §9-D4：v1 键空间经 runtime-defaults 单端点承载，本组端点为其分组视图/受限写入口）----

// runtimeTemplateGroups 模板组骨架允许的分组（设计 §9：模型 / 沙箱参数模板，llm | sandbox）。
// mysql/redis/protocol 组不经本端点（runtime-defaults 主端点已覆盖），保持设计文本口径。
var runtimeTemplateGroups = map[string]bool{"llm": true, "sandbox": true}

// registryConfigCategories registry 组覆盖的分类（设计 §9：仓库 / 拉取凭证 / 路由 / 镜像白名单；
// 对应契约 §1.4 导出键分类 registry|routing|runtime）。
var registryConfigCategories = map[string]bool{"registry": true, "routing": true, "runtime": true}

// RuntimeGroupFieldView 模板组骨架字段视图：platformconfig 字段 schema + 运行时默认值（敏感掩码）。
type RuntimeGroupFieldView struct {
	EnvKey      string `json:"envKey"`
	Label       string `json:"label"`
	Required    bool   `json:"required"`
	Sensitive   bool   `json:"sensitive"`
	Multiline   bool   `json:"multiline"`
	Placeholder string `json:"placeholder,omitempty"`
	HasValue    bool   `json:"hasValue"`
	Value       string `json:"value,omitempty"` // 仅非敏感键回填真实值；敏感键永不回明文
}

// templateGroupFields 取分组字段定义（group 须已通过 runtimeTemplateGroups 校验）。
func templateGroupFields(group string) []platformconfig.Field {
	for _, g := range platformconfig.Template() {
		if g.Name == group {
			return g.Fields
		}
	}
	return nil
}

// GetRuntimeTemplateGroup 模板组读骨架：模板驱动字段 schema + 当前运行时默认值（敏感掩码）。
func (c *Core) GetRuntimeTemplateGroup(wsID uint, group string) ([]RuntimeGroupFieldView, error) {
	if !runtimeTemplateGroups[group] {
		return nil, ErrNotFound
	}
	values, err := c.loadRuntimeDefaultValues(wsID)
	if err != nil {
		return nil, err
	}
	fields := templateGroupFields(group)
	out := make([]RuntimeGroupFieldView, 0, len(fields))
	for _, f := range fields {
		fv := RuntimeGroupFieldView{
			EnvKey: f.EnvKey, Label: f.Label, Required: f.Required, Sensitive: f.Sensitive,
			Multiline: f.Multiline, Placeholder: f.Placeholder,
		}
		if v, ok := values[f.EnvKey]; ok {
			fv.HasValue = true
			if !f.Sensitive {
				fv.Value = v
			}
		}
		out = append(out, fv)
	}
	return out, nil
}

// UpdateRuntimeTemplateGroup 模板组写骨架：键必须属于该组模板，落库走 runtime-defaults
// 同一三态与校验（键空间经 runtime-defaults 统一承载，契约 §9-D4）。
func (c *Core) UpdateRuntimeTemplateGroup(wsID uint, username, group string, values map[string]string) (map[string]RuntimeDefaultEntry, error) {
	if !runtimeTemplateGroups[group] {
		return nil, ErrNotFound
	}
	groupKeys := map[string]bool{}
	for _, f := range templateGroupFields(group) {
		groupKeys[f.EnvKey] = true
	}
	for k := range values {
		if !groupKeys[k] {
			return nil, fmt.Errorf("%w: %q not in template group %q", ErrUnknownConfigKey, k, group)
		}
	}
	return c.UpdateRuntimeDefaults(wsID, username, values)
}

// GetRuntimeRegistryConfig registry 组读骨架：registry/routing/runtime 分类键的掩码视图。
func (c *Core) GetRuntimeRegistryConfig(wsID uint) (map[string]RuntimeDefaultEntry, error) {
	all, err := c.GetRuntimeDefaults(wsID)
	if err != nil {
		return nil, err
	}
	out := make(map[string]RuntimeDefaultEntry, len(runtimeExportKeys))
	for k, meta := range runtimeDefaultKeys {
		if registryConfigCategories[meta.Category] {
			out[k] = all[k]
		}
	}
	return out, nil
}

// UpdateRuntimeRegistryConfig registry 组写骨架：键空间限 registry/routing/runtime 分类。
func (c *Core) UpdateRuntimeRegistryConfig(wsID uint, username string, values map[string]string) (map[string]RuntimeDefaultEntry, error) {
	for k := range values {
		meta, ok := runtimeDefaultKeys[k]
		if !ok {
			return nil, rejectRuntimeKey(k)
		}
		if !registryConfigCategories[meta.Category] {
			return nil, fmt.Errorf("%w: %q not in registry config group", ErrUnknownConfigKey, k)
		}
	}
	return c.UpdateRuntimeDefaults(wsID, username, values)
}
