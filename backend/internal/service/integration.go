// 集成 Agent（M7）：Agent Protocol 远程子 agent 集成关系声明管理。
// 运行时编排零改动（agent_spawn / 子事件回流是 agent-framework 既有能力），
// 本模块只管理声明数据（endpoint / ask 策略 / 认证头键）与候选探测。
package service

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"agent-manager/backend/internal/store"
)

// IntegrationView 已建立集成关系视图。
type IntegrationView struct {
	store.AgentIntegration
	ReferencedBy []uint `json:"referencedBy"` // 生成包 agents[].endpoint 命中的 lead 服务（粗统计）
}

// IntegrationList 集成页双栏数据。
type IntegrationList struct {
	Relations  []IntegrationView  `json:"relations"`
	Candidates []IntegrationCand  `json:"candidates"`
}

// IntegrationCand 可注册候选（本空间服务）。
type IntegrationCand struct {
	ServiceID       uint   `json:"serviceId"`
	DisplayName     string `json:"displayName"`
	ShortName       string `json:"shortName"`
	K8sName         string `json:"k8sName"`
	ProtocolEnabled bool   `json:"protocolEnabled"` // env AGENT_PROTOCOL_ENABLED=true
	Probe           string `json:"probe"`           // online | unreachable | skipped
	EndpointURL     string `json:"endpointUrl"`
}

// ListIntegrations 集成列表 + 候选探测（对端 agent-card 拉取复用 A2A 同通道）。
func (c *Core) ListIntegrations(workspaceID uint) (*IntegrationList, error) {
	var rows []store.AgentIntegration
	if err := c.DB.Where("workspace_id = ?", workspaceID).Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	// 引用方粗统计：空间内服务运行包 frontmatter agents[].endpoint 命中
	refs := map[string][]uint{}
	var svcs []store.ServiceEntity
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&svcs).Error; err != nil {
		return nil, err
	}
	for _, s := range svcs {
		pkg, err := c.Packages.Get(s.PackageID)
		if err != nil || pkg.ManifestJSON == "" {
			continue
		}
		var cfg struct {
			Agents []struct {
				Endpoint string `json:"endpoint"`
			} `json:"agents"`
		}
		if json.Unmarshal([]byte(pkg.ManifestJSON), &cfg) == nil {
			for _, a := range cfg.Agents {
				if a.Endpoint != "" {
					refs[a.Endpoint] = append(refs[a.Endpoint], s.ID)
				}
			}
		}
	}
	relations := make([]IntegrationView, 0, len(rows))
	for _, r := range rows {
		refd := refs[r.EndpointURL]
		if refd == nil {
			refd = []uint{} // 空引用序列化 []（nil → null 前端崩）
		}
		v := IntegrationView{AgentIntegration: r, ReferencedBy: refd}
		if v.AskPolicy == "" {
			v.AskPolicy = "PROPAGATE"
		}
		relations = append(relations, v)
	}
	// 候选 = 本空间服务（自身已集成过的标记排除在 candidates 由前端渲染）
	candidates := make([]IntegrationCand, 0, len(svcs))
	client := &http.Client{Timeout: 5 * time.Second}
	for _, s := range svcs {
		enabled := false
		env := map[string]string{}
		_ = json.Unmarshal([]byte(s.EnvJSON), &env)
		if strings.EqualFold(env["AGENT_PROTOCOL_ENABLED"], "true") {
			enabled = true
		}
		probe := "skipped"
		if enabled {
			if probeAgentCard(client, s.ClusterURL) {
				probe = "online"
			} else {
				probe = "unreachable"
			}
		}
		candidates = append(candidates, IntegrationCand{
			ServiceID: s.ID, DisplayName: s.DisplayName, ShortName: s.ShortName,
			K8sName: s.K8sName, ProtocolEnabled: enabled, Probe: probe,
			EndpointURL: fmt.Sprintf("http://%s-svc:8100", s.K8sName),
		})
	}
	return &IntegrationList{Relations: relations, Candidates: candidates}, nil
}

// probeAgentCard 候选探测：GET {svc}:8100/agent-card.json（protocolEnabled=false 不探测）。
func probeAgentCard(client *http.Client, clusterURL string) bool {
	resp, err := client.Get(strings.TrimRight(clusterURL, "/") + "/agent-card.json")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4<<10))
	return resp.StatusCode >= 200 && resp.StatusCode < 300
}

// CreateIntegrationInput 登记入参（endpoint 由 member 服务派生）。
type CreateIntegrationInput struct {
	MemberServiceID uint   `json:"memberServiceId"`
	Name            string `json:"name"`
	AskPolicy       string `json:"askPolicy"`
	AuthHeaderKey   string `json:"authHeaderKey"`
	Enabled         *bool  `json:"enabled"`
}

// CreateIntegration 登记集成关系（重复集成同一 member → 409）。
func (c *Core) CreateIntegration(workspaceID uint, in CreateIntegrationInput) (*store.AgentIntegration, error) {
	svc, err := c.Get(in.MemberServiceID)
	if err != nil {
		return nil, fmt.Errorf("%w: member service not found", ErrInvalidForm)
	}
	if svc.WorkspaceID != workspaceID {
		return nil, fmt.Errorf("%w: member service not in workspace", ErrInvalidForm)
	}
	var cnt int64
	if err := c.DB.Model(&store.AgentIntegration{}).
		Where("workspace_id = ? AND member_service_id = ?", workspaceID, in.MemberServiceID).
		Count(&cnt).Error; err != nil {
		return nil, err
	}
	if cnt > 0 {
		return nil, fmt.Errorf("%w: duplicate_integration", ErrConflict)
	}
	ask := in.AskPolicy
	if ask != "DENY" {
		ask = "PROPAGATE"
	}
	name := in.Name
	if name == "" {
		name = svc.DisplayName
	}
	it := &store.AgentIntegration{
		WorkspaceID: workspaceID, MemberServiceID: svc.ID, Name: name,
		EndpointURL: fmt.Sprintf("http://%s-svc:8100", svc.K8sName),
		AskPolicy: ask, RemoteStreaming: true, AuthHeaderKey: in.AuthHeaderKey,
		Enabled: in.Enabled == nil || *in.Enabled,
	}
	if err := c.DB.Create(it).Error; err != nil {
		return nil, err
	}
	return it, nil
}

// PatchIntegrationInput 更新入参。
type PatchIntegrationInput struct {
	Name          *string `json:"name"`
	AskPolicy     *string `json:"askPolicy"`
	AuthHeaderKey *string `json:"authHeaderKey"`
	Enabled       *bool   `json:"enabled"`
}

// UpdateIntegration 更新集成关系。
func (c *Core) UpdateIntegration(workspaceID, id uint, in PatchIntegrationInput) (*store.AgentIntegration, error) {
	var it store.AgentIntegration
	if err := c.DB.Where("id = ? AND workspace_id = ?", id, workspaceID).First(&it).Error; err != nil {
		return nil, ErrNotFound
	}
	updates := map[string]interface{}{}
	if in.Name != nil && *in.Name != "" {
		updates["name"] = *in.Name
	}
	if in.AskPolicy != nil {
		if *in.AskPolicy != "DENY" && *in.AskPolicy != "PROPAGATE" {
			return nil, fmt.Errorf("%w: invalid askPolicy", ErrInvalidForm)
		}
		updates["ask_policy"] = *in.AskPolicy
	}
	if in.AuthHeaderKey != nil {
		updates["auth_header_key"] = *in.AuthHeaderKey
	}
	if in.Enabled != nil {
		updates["enabled"] = *in.Enabled
	}
	if len(updates) > 0 {
		if err := c.DB.Model(&it).Updates(updates).Error; err != nil {
			return nil, err
		}
	}
	return &it, nil
}

// DeleteIntegration 解除集成（被生成包引用中 → 409，提示先在 Agent 版本中移除 subAgent 再升级）。
func (c *Core) DeleteIntegration(workspaceID, id uint) error {
	var it store.AgentIntegration
	if err := c.DB.Where("id = ? AND workspace_id = ?", id, workspaceID).First(&it).Error; err != nil {
		return ErrNotFound
	}
	if len(c.integrationReferencing(workspaceID, it.EndpointURL)) > 0 {
		return fmt.Errorf("%w: integration_in_use (remove subAgent from agent packages first)", ErrConflict)
	}
	return c.DB.Delete(&it).Error
}

// integrationReferencing 命中 endpoint 的空间内服务。
func (c *Core) integrationReferencing(workspaceID uint, endpoint string) []uint {
	var svcs []store.ServiceEntity
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&svcs).Error; err != nil {
		return nil
	}
	var ids []uint
	for _, s := range svcs {
		pkg, err := c.Packages.Get(s.PackageID)
		if err != nil || pkg.ManifestJSON == "" {
			continue
		}
		var cfg struct {
			Agents []struct {
				Endpoint string `json:"endpoint"`
			} `json:"agents"`
		}
		if json.Unmarshal([]byte(pkg.ManifestJSON), &cfg) == nil {
			for _, a := range cfg.Agents {
				if a.Endpoint == endpoint {
					ids = append(ids, s.ID)
					break
				}
			}
		}
	}
	return ids
}
