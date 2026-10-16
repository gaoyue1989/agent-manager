// 用户侧 console 视图聚合（M2/M4）：概览统计、Agent 列表视图、详情包装、版本列表。
// 数据源 = services 按 workspace 过滤（不新建 Agent 实体，设计 §3.2 决议）。
package service

import (
	"encoding/json"
	"time"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/store"
)

// SummaryView 工作空间概览（M2 §2.3）。
type SummaryView struct {
	Agents struct {
		Total   int64 `json:"total"`
		Running int64 `json:"running"`
		Error   int64 `json:"error"`
		Stopped int64 `json:"stopped"`
	} `json:"agents"`
	RecentEvents  []SummaryEvent `json:"recentEvents"`
	TodaySessions interface{}    `json:"todaySessions"` // 一期无会话聚合，字段预留 null
}

// SummaryEvent 概览时间线条目。
type SummaryEvent struct {
	ID          uint      `json:"id"`
	Reason      string    `json:"reason"`
	ToStatus    string    `json:"toStatus"`
	DisplayName string    `json:"displayName"`
	ServiceID   uint      `json:"serviceId"`
	CreatedAt   time.Time `json:"createdAt"`
}

// ConsoleSummary 概览聚合：状态计数 + 最近事件（service_events 倒序 10 条）。
func (c *Core) ConsoleSummary(workspaceID uint) (*SummaryView, error) {
	var sv SummaryView
	type cnt struct {
		Status string
		N      int64
	}
	var counts []cnt
	if err := c.DB.Model(&store.ServiceEntity{}).
		Select("status, count(*) AS n").
		Where("workspace_id = ?", workspaceID).
		Group("status").Scan(&counts).Error; err != nil {
		return nil, err
	}
	for _, r := range counts {
		sv.Agents.Total += r.N
		switch r.Status {
		case store.StatusRunning:
			sv.Agents.Running = r.N
		case store.StatusStopped:
			sv.Agents.Stopped = r.N
		case store.StatusError, store.StatusDeployFailed, store.StatusRegisterFailed:
			sv.Agents.Error += r.N
		}
	}
	// 最近事件：本空间服务的事件（join 过滤），倒序 10 条
	var events []SummaryEvent
	err := c.DB.Table("service_events e").
		Select("e.id, e.reason, e.to_status, e.created_at, s.display_name, s.id AS service_id").
		Joins("JOIN services s ON s.id = e.service_id").
		Where("s.workspace_id = ?", workspaceID).
		Order("e.created_at DESC").Limit(10).Scan(&events).Error
	if err != nil {
		return nil, err
	}
	sv.RecentEvents = events
	return &sv, nil
}

// AgentCardSummary agent_card 摘要（不整包直出）。
type AgentCardSummary struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

// AgentListItem console Agent 列表项（= services 视图增强，M2 §2.3）。
type AgentListItem struct {
	ID          uint             `json:"id"`
	DisplayName string           `json:"displayName"`
	K8sName     string           `json:"k8sName"`
	ShortName   string           `json:"shortName"`
	Status      string           `json:"status"`
	Endpoint    string           `json:"endpoint"`
	Model       string           `json:"model"`
	Version     string           `json:"version"`
	Replicas    int              `json:"replicas"`
	CardSummary AgentCardSummary `json:"cardSummary"`
	UpdatedAt   time.Time        `json:"updatedAt"`
}

// ConsoleAgents 空间内 Agent 列表（status/keyword 过滤；default 空间仅 admin 经中间件可达）。
func (c *Core) ConsoleAgents(workspaceID uint, status, keyword string) ([]AgentListItem, error) {
	q := c.DB.Model(&store.ServiceEntity{}).Where("workspace_id = ?", workspaceID)
	if status != "" {
		q = q.Where("status = ?", status)
	}
	if keyword != "" {
		q = q.Where("display_name LIKE ? OR k8s_name LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	var rows []store.ServiceEntity
	if err := q.Order("id DESC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]AgentListItem, 0, len(rows))
	for _, r := range rows {
		out = append(out, c.agentListItem(&r))
	}
	return out, nil
}

func (c *Core) agentListItem(r *store.ServiceEntity) AgentListItem {
	item := AgentListItem{
		ID: r.ID, DisplayName: r.DisplayName, K8sName: r.K8sName, ShortName: r.ShortName,
		Status: r.Status, Endpoint: r.Endpoint, Replicas: r.Replicas, UpdatedAt: r.UpdatedAt,
	}
	// model：env 注入的 LLM_MODEL（M3 创建链路）；历史行空串
	env := map[string]string{}
	_ = json.Unmarshal([]byte(r.EnvJSON), &env)
	item.Model = env["LLM_MODEL"]
	// version：agent_card 注册版本 → 退回包版本
	item.Version = r.RegisteredVersion
	if item.Version == "" && r.PackageID > 0 {
		if pkg, err := c.Packages.Get(r.PackageID); err == nil {
			item.Version = pkg.Version
			// agent_card 缺失/损坏时摘要降级为包名+描述（不 500）
			if item.CardSummary.Name == "" {
				item.CardSummary.Name = pkg.Name
				item.CardSummary.Description = pkg.Description
			}
		}
	}
	if r.AgentCardJSON != "" && r.AgentCardJSON != "{}" {
		var card struct {
			Name        string `json:"name"`
			Description string `json:"description"`
			Version     string `json:"version"`
		}
		if err := json.Unmarshal([]byte(r.AgentCardJSON), &card); err == nil {
			item.CardSummary = AgentCardSummary{Name: card.Name, Description: card.Description}
			if card.Version != "" {
				item.Version = card.Version
			}
		}
	}
	return item
}

// ConsoleAgentDetail 详情包装：core.GetDetail + workspace 归属校验（非本空间 404）。
func (c *Core) ConsoleAgentDetail(workspaceID, id uint) (*ServiceDetail, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound // 不暴露存在性
	}
	return c.GetDetail(id)
}

// PackageVersionView 版本 Tab 条目。
type PackageVersionView struct {
	store.OafPackage
	Current bool `json:"current"` // 当前运行版本
}

// ConsoleAgentVersions 同 slug 包版本链（含其他空间不可见的过滤由查询保证：本空间包 + 公共包）。
func (c *Core) ConsoleAgentVersions(workspaceID, serviceID uint) ([]PackageVersionView, error) {
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	pkg, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, err
	}
	var rows []store.OafPackage
	if err := c.DB.Where("slug = ? AND (workspace_id = 0 OR workspace_id = ?)",
		pkg.Slug, workspaceID).Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]PackageVersionView, 0, len(rows))
	for _, r := range rows {
		out = append(out, PackageVersionView{OafPackage: r, Current: r.ID == svc.PackageID})
	}
	return out, nil
}

// ConsoleAgentPods 实时 Pod 表（详情 Tab1；5s 轮询由前端驱动）。
func (c *Core) ConsoleAgentPods(workspaceID, id uint) ([]k8s.PodInfo, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	pods, _ := c.K8s.PodStatuses(c.Cfg.Namespace, k8s.LabelKey+"="+svc.K8sName)
	return pods, nil
}
