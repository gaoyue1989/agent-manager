// 平台默认配置：GET/PUT /api/v1/platform-config 与 GET /platform-config/defaults。
// 默认配置仅作为「发布新服务 / 编辑 env」时的表单默认填入（R3 修订），不经 envFrom
// 运行时注入——平台配置变更不影响任何已发布服务，服务只携带自己显式配置的 env。
// DB platform_config 表为事实源；审计只记动作与键名，不记值。
// 设计见 docs/design/platform-default-config-secret-design.md。
package service

import (
	"errors"
	"fmt"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/service/platformconfig"
	"agent-manager/backend/internal/store"
)

var (
	ErrUnknownConfigKey  = errors.New("unknown platform config key")
	ErrRequiredConfigKey = errors.New("required platform config key cannot be cleared")
	ErrEmptyConfigValues = errors.New("platform config values is required")
)

// PlatformConfigFieldView 设置页字段视图：敏感值永不回明文，仅 hasValue。
type PlatformConfigFieldView struct {
	EnvKey      string    `json:"envKey"`
	Label       string    `json:"label"`
	Required    bool      `json:"required"`
	Sensitive   bool      `json:"sensitive"`
	Multiline   bool      `json:"multiline"`
	Placeholder string    `json:"placeholder,omitempty"`
	HasValue    bool      `json:"hasValue"`
	Value       string    `json:"value,omitempty"` // 仅非敏感键回填
	UpdatedAt   time.Time `json:"updatedAt"`
}

// PlatformConfigGroupView 分组视图（页面 section）。
type PlatformConfigGroupView struct {
	Name   string                    `json:"name"`
	Title  string                    `json:"title"`
	Fields []PlatformConfigFieldView `json:"fields"`
}

// PlatformConfigView GET /platform-config 响应。
type PlatformConfigView struct {
	Groups    []PlatformConfigGroupView `json:"groups"`
	UpdatedAt *time.Time                `json:"updatedAt,omitempty"`
}

// loadPlatformValues 读取平台配置事实源 → 键值 map。
func (c *Core) loadPlatformValues() (map[string]string, error) {
	var rows []store.PlatformConfigEntity
	if err := c.DB.Find(&rows).Error; err != nil {
		return nil, err
	}
	values := make(map[string]string, len(rows))
	for _, r := range rows {
		values[r.EnvKey] = r.Value
	}
	return values, nil
}

// GetPlatformConfig 设置页 schema + 掩码值。
func (c *Core) GetPlatformConfig() (*PlatformConfigView, error) {
	var rows []store.PlatformConfigEntity
	if err := c.DB.Find(&rows).Error; err != nil {
		return nil, err
	}
	values := make(map[string]string, len(rows))
	touched := map[string]time.Time{}
	var latest *time.Time
	for _, r := range rows {
		values[r.EnvKey] = r.Value
		touched[r.EnvKey] = r.UpdatedAt
		ts := r.UpdatedAt
		if latest == nil || ts.After(*latest) {
			latest = &ts
		}
	}
	view := &PlatformConfigView{Groups: []PlatformConfigGroupView{}, UpdatedAt: latest}
	for _, g := range platformconfig.Template() {
		gv := PlatformConfigGroupView{Name: g.Name, Title: g.Title, Fields: []PlatformConfigFieldView{}}
		for _, f := range g.Fields {
			fv := PlatformConfigFieldView{
				EnvKey: f.EnvKey, Label: f.Label, Required: f.Required, Sensitive: f.Sensitive,
				Multiline: f.Multiline, Placeholder: f.Placeholder,
				UpdatedAt: touched[f.EnvKey],
			}
			if v, ok := values[f.EnvKey]; ok {
				fv.HasValue = true
				if !f.Sensitive {
					fv.Value = v // 非敏感键回填真实值；敏感键永不回明文
				}
			}
			gv.Fields = append(gv.Fields, fv)
		}
		view.Groups = append(view.Groups, gv)
	}
	return view, nil
}

// GetPlatformDefaults 返回已配置默认值的平面键值表（含敏感键明文）。
// 专供发布向导 / 详情页「填入平台默认」的表单预填（R3 语义：默认值本就是供复制进服务的模板）；
// GET /platform-config 展示视图仍保持敏感掩码。
func (c *Core) GetPlatformDefaults() (map[string]string, error) {
	values, err := c.loadPlatformValues()
	if err != nil {
		return nil, err
	}
	out := make(map[string]string, len(values))
	for k, v := range values {
		if v != "" && platformconfig.KnownKeys()[k] {
			out[k] = v
		}
	}
	return out, nil
}

// UpdatePlatformConfig 部分更新：出现且非空 = 设置、出现且空串 = 删除、未出现 = 不变。
// 校验：未知键 400；清除必填键 400（必填仅防误清，不做全局完整性检查）。
// 只写 DB，不触碰集群——默认配置不影响任何已发布服务（R3 语义）。
func (c *Core) UpdatePlatformConfig(values map[string]string) (*PlatformConfigView, error) {
	if len(values) == 0 {
		return nil, ErrEmptyConfigValues
	}
	known := platformconfig.KnownKeys()
	for k, v := range values {
		if !known[k] {
			return nil, fmt.Errorf("%w: %q", ErrUnknownConfigKey, k)
		}
		if len(v) > MaxEnvValueBytes {
			return nil, fmt.Errorf("platform config value of %q exceeds %d bytes", k, MaxEnvValueBytes)
		}
		if v == "" {
			if f := platformconfig.Lookup(k); f != nil && f.Required {
				var cnt int64
				if err := c.DB.Model(&store.PlatformConfigEntity{}).Where("env_key = ?", k).Count(&cnt).Error; err != nil {
					return nil, err
				}
				if cnt > 0 {
					return nil, fmt.Errorf("%w: %s", ErrRequiredConfigKey, k)
				}
			}
		}
	}
	changed := make([]string, 0, len(values))
	err := c.DB.Transaction(func(tx *gorm.DB) error {
		for k, v := range values {
			if v == "" {
				if err := tx.Where("env_key = ?", k).Delete(&store.PlatformConfigEntity{}).Error; err != nil {
					return err
				}
			} else if err := tx.Save(&store.PlatformConfigEntity{EnvKey: k, Value: v}).Error; err != nil {
				return err
			}
			changed = append(changed, k)
		}
		return tx.Create(&store.PlatformConfigEvent{Action: "update", EnvKeys: mustJSON(changed)}).Error
	})
	if err != nil {
		return nil, err
	}
	return c.GetPlatformConfig()
}
