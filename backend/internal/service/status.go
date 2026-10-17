package service

import (
	"encoding/json"
	"sort"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/service/platformconfig"
	"agent-manager/backend/internal/store"
)

// Get 单条记录。
func (c *Core) Get(id uint) (*store.ServiceEntity, error) {
	var svc store.ServiceEntity
	if err := c.DB.First(&svc, id).Error; err != nil {
		return nil, ErrNotFound
	}
	return &svc, nil
}

// GetByK8sName 按 K8s 名查询。
func (c *Core) GetByK8sName(name string) (*store.ServiceEntity, error) {
	var svc store.ServiceEntity
	if err := c.DB.Where("k8s_name = ?", name).First(&svc).Error; err != nil {
		return nil, ErrNotFound
	}
	return &svc, nil
}

// EnvSecretKeyView 服务级敏感键掩码视图：值永不回明文，仅键名 + hasValue。
type EnvSecretKeyView struct {
	Key      string `json:"key"`
	HasValue bool   `json:"hasValue"`
}

// envSecretKeys 汇总服务敏感键集合：env_secret_json 全部键 + 旧 env_json 中
// 尚未迁移的模板敏感键（存量数据在首次写路径前仍存于 env_json，页面据此归入敏感区掩码显示）。
func (c *Core) envSecretKeys(svc *store.ServiceEntity) []EnvSecretKeyView {
	seen := map[string]bool{}
	out := []EnvSecretKeyView{}
	secret := map[string]string{}
	if svc.EnvSecretJSON != "" {
		_ = json.Unmarshal([]byte(svc.EnvSecretJSON), &secret)
	}
	plain := map[string]string{}
	_ = json.Unmarshal([]byte(svc.EnvJSON), &plain)
	sensitive := platformconfig.SensitiveKeys()
	for k, v := range secret {
		if !seen[k] {
			seen[k] = true
			out = append(out, EnvSecretKeyView{Key: k, HasValue: v != ""})
		}
	}
	for k, v := range plain {
		if sensitive[k] && !seen[k] {
			seen[k] = true
			out = append(out, EnvSecretKeyView{Key: k, HasValue: v != ""})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out
}

// ListService 服务列表项：DB 记录 + 实时 Pod 状态。
type ListService struct {
	store.ServiceEntity
	Pods          []k8s.PodInfo      `json:"pods,omitempty"`
	EnvSecretKeys []EnvSecretKeyView `json:"envSecretKeys"`
}

// PreviewK8sName preview-name 轻端点：按 agentKey 同源派生 K8s 名并检测冲突（不落库）。
func (c *Core) PreviewK8sName(name string) (string, bool) {
	if name == "" {
		return "", false
	}
	base := k8s.DeriveK8sName(SlugifyAgentKey(name), "")
	var cnt int64
	if err := c.DB.Model(&store.ServiceEntity{}).Where("k8s_name = ?", base).Count(&cnt).Error; err != nil {
		return base, false
	}
	return base, cnt > 0
}

// List 列表（可按状态/关键字/包/工作空间过滤），合并实时 Pod 状态。
// workspaceID 0=不过滤（存量语义）；>0=仅该空间；-1=仅未归属（运维「查看待纳管」）。
func (c *Core) List(status, keyword string, packageID uint, workspaceID int64) ([]ListService, error) {
	q := c.DB.Model(&store.ServiceEntity{}).Order("id DESC")
	if status != "" {
		q = q.Where("status = ?", status)
	}
	if keyword != "" {
		q = q.Where("display_name LIKE ? OR k8s_name LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	if packageID > 0 {
		q = q.Where("package_id = ?", packageID)
	}
	switch {
	case workspaceID > 0:
		q = q.Where("workspace_id = ?", workspaceID)
	case workspaceID < 0:
		q = q.Where("workspace_id = 0")
	}
	var rows []store.ServiceEntity
	if err := q.Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]ListService, 0, len(rows))
	for _, r := range rows {
		pods, _ := c.K8s.PodStatuses(c.Cfg.Namespace, k8s.LabelKey+"="+r.K8sName)
		out = append(out, ListService{ServiceEntity: r, Pods: pods, EnvSecretKeys: c.envSecretKeys(&r)})
	}
	return out, nil
}

// Detail 详情：基础信息 + Pod 状态 + 最近事件。
type ServiceDetail struct {
	store.ServiceEntity
	Pods          []k8s.PodInfo        `json:"pods,omitempty"`
	Events        []store.ServiceEvent `json:"events"`
	EnvSecretKeys []EnvSecretKeyView   `json:"envSecretKeys"`
}

// GetDetail 详情视图。
func (c *Core) GetDetail(id uint) (*ServiceDetail, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, err
	}
	pods, _ := c.K8s.PodStatuses(c.Cfg.Namespace, k8s.LabelKey+"="+svc.K8sName)
	var events []store.ServiceEvent
	c.DB.Where("service_id = ?", id).Order("created_at DESC").Limit(50).Find(&events)
	return &ServiceDetail{ServiceEntity: *svc, Pods: pods, Events: events,
		EnvSecretKeys: c.envSecretKeys(svc)}, nil
}

// Events 指定服务的事件列表。
func (c *Core) Events(id uint) ([]store.ServiceEvent, error) {
	var events []store.ServiceEvent
	err := c.DB.Where("service_id = ?", id).Order("created_at DESC").Limit(50).Find(&events).Error
	return events, err
}
