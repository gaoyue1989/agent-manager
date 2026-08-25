package service

import (
	"agent-manager/backend/internal/k8s"
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

// ListService 服务列表项：DB 记录 + 实时 Pod 状态。
type ListService struct {
	store.ServiceEntity
	Pods []k8s.PodInfo `json:"pods,omitempty"`
}

// List 列表（可按状态/关键字过滤），合并实时 Pod 状态。
func (c *Core) List(status, keyword string) ([]ListService, error) {
	q := c.DB.Model(&store.ServiceEntity{}).Order("id DESC")
	if status != "" {
		q = q.Where("status = ?", status)
	}
	if keyword != "" {
		q = q.Where("display_name LIKE ? OR k8s_name LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	var rows []store.ServiceEntity
	if err := q.Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]ListService, 0, len(rows))
	for _, r := range rows {
		pods, _ := c.K8s.PodStatuses(c.Cfg.Namespace, k8s.LabelKey+"="+r.K8sName)
		out = append(out, ListService{ServiceEntity: r, Pods: pods})
	}
	return out, nil
}

// Detail 详情：基础信息 + Pod 状态 + 最近事件。
type ServiceDetail struct {
	store.ServiceEntity
	Pods   []k8s.PodInfo        `json:"pods,omitempty"`
	Events []store.ServiceEvent `json:"events"`
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
	return &ServiceDetail{ServiceEntity: *svc, Pods: pods, Events: events}, nil
}

// Events 指定服务的事件列表。
func (c *Core) Events(id uint) ([]store.ServiceEvent, error) {
	var events []store.ServiceEvent
	err := c.DB.Where("service_id = ?", id).Order("created_at DESC").Limit(50).Find(&events).Error
	return events, err
}
