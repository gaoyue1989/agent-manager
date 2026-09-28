// 平台默认配置：GET/PUT /api/v1/platform-config、apply-restart 与集群对象渲染
// （设计见 docs/design/platform-default-config-secret-design.md）。
// DB platform_config 表为事实源；按模板 Sensitive 拆分渲染为平台 CM（非敏感）+ Secret（敏感）。
package service

import (
	"context"
	"errors"
	"fmt"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/service/platformconfig"
	"agent-manager/backend/internal/store"
)

var (
	ErrUnknownConfigKey  = errors.New("unknown platform config key")
	ErrRequiredConfigKey = errors.New("required platform config key cannot be cleared")
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
	Value       string    `json:"value,omitempty"` // 仅非敏感键回填真实值
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

// ApplyRestartRequest POST /platform-config/apply-restart 入参；serviceIds 为空 = 全部可重启服务。
type ApplyRestartRequest struct {
	ServiceIDs []uint `json:"serviceIds"`
}

// AppliedService 已触发滚动重启的服务。
type AppliedService struct {
	ID   uint   `json:"id"`
	Name string `json:"name"`
}

// SkippedService 未触发重启的服务与原因（单个失败不阻塞其余）。
type SkippedService struct {
	ID     uint   `json:"id"`
	Name   string `json:"name"`
	Reason string `json:"reason"`
}

// ApplyRestartResult apply-restart 响应。
type ApplyRestartResult struct {
	Restarted []AppliedService `json:"restarted"`
	Skipped   []SkippedService `json:"skipped"`
}

// EnsurePlatformObjects 渲染并落集群平台默认配置对象（启动/保存/发布前三处幂等触发）。
// 空配置也创建空对象，保证业务 Deployment envFrom 引用永不 CreateContainerConfigError。
func (c *Core) EnsurePlatformObjects() error {
	values, err := c.loadPlatformValues()
	if err != nil {
		return err
	}
	plain, secret := platformconfig.Split(values, nil)
	ns := c.Cfg.Namespace
	if err := c.K8s.EnsureConfigMap(k8s.PlatformDefaultConfigMap(ns, plain)); err != nil {
		return fmt.Errorf("platform configmap: %w", err)
	}
	if err := c.K8s.EnsureSecret(k8s.PlatformDefaultSecret(ns, secret)); err != nil {
		return fmt.Errorf("platform secret: %w", err)
	}
	return nil
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

// UpdatePlatformConfig 部分更新：出现且非空 = 设置、出现且空串 = 删除、未出现 = 不变。
// 校验：未知键 400；清除必填键 400（必填仅防误清，不做全局完整性检查）。
func (c *Core) UpdatePlatformConfig(values map[string]string) (*PlatformConfigView, error) {
	if len(values) == 0 {
		return nil, errors.New("platform config values is required")
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
	if err := c.EnsurePlatformObjects(); err != nil {
		return nil, err
	}
	return c.GetPlatformConfig()
}

// ApplyRestart 对运行中/register_failed 服务重刷 Deployment spec（带四源 envFrom 与
// 最新平台默认值）并滚动重启；逐服务独立推进，单个失败不阻塞其余。
func (c *Core) ApplyRestart(req ApplyRestartRequest) (*ApplyRestartResult, error) {
	res := &ApplyRestartResult{Restarted: []AppliedService{}, Skipped: []SkippedService{}}
	var targets []store.ServiceEntity
	if len(req.ServiceIDs) > 0 {
		if err := c.DB.Where("id IN ?", req.ServiceIDs).Find(&targets).Error; err != nil {
			return nil, err
		}
		seen := map[uint]bool{}
		for i := range targets {
			seen[targets[i].ID] = true
		}
		for _, id := range req.ServiceIDs {
			if !seen[id] {
				res.Skipped = append(res.Skipped, SkippedService{ID: id, Reason: "not found"})
			}
		}
	} else {
		if err := c.DB.Where("status IN ?", []string{store.StatusRunning, store.StatusRegisterFailed}).
			Find(&targets).Error; err != nil {
			return nil, err
		}
	}
	restarted := make([]string, 0, len(targets))
	for i := range targets {
		svc := &targets[i]
		switch svc.Status {
		case store.StatusRunning, store.StatusRegisterFailed:
		default:
			res.Skipped = append(res.Skipped, SkippedService{ID: svc.ID, Name: svc.DisplayName,
				Reason: "status " + svc.Status + " not restartable"})
			continue
		}
		if err := c.reapplyAndRestart(svc); err != nil {
			res.Skipped = append(res.Skipped, SkippedService{ID: svc.ID, Name: svc.DisplayName, Reason: err.Error()})
			continue
		}
		res.Restarted = append(res.Restarted, AppliedService{ID: svc.ID, Name: svc.DisplayName})
		restarted = append(restarted, fmt.Sprintf("%d", svc.ID))
	}
	_ = c.DB.Create(&store.PlatformConfigEvent{Action: "apply_restart", EnvKeys: mustJSON(restarted)}).Error
	return res, nil
}

// reapplyAndRestart 重刷 Deployment spec + 打 restartedAt 注解滚动重启 + 重新注册。
// 仅打注解不变更 podTemplate——存量服务的单源 envFrom 永远带不上四源引用，必须重刷 spec。
func (c *Core) reapplyAndRestart(svc *store.ServiceEntity) error {
	plain, secret, err := c.partitionServiceEnv(svc)
	if err != nil {
		return err
	}
	params := c.params(svc.K8sName, svc.Image, plain, int32(svc.Replicas), c.pkgDir(svc.PackageID))
	params.EnvSecret = secret
	dep, err := c.deployBuilder().Build(params)
	if err != nil {
		return fmt.Errorf("deployment: %w", err)
	}
	if err := c.K8s.EnsureDeployment(dep); err != nil {
		return fmt.Errorf("deployment: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := c.K8s.RestartDeployment(ctx, c.Cfg.Namespace, svc.K8sName); err != nil {
		return fmt.Errorf("restart: %w", err)
	}
	c.transition(svc, store.StatusDeploying, "platform config applied, rolling restart")
	c.asyncWaitAndRegister(svc.ID)
	return nil
}
