// 模型连接（M5）：工作空间级配置 CRUD + 连通性测试 + 下发至 Agent /models（幂等）+
// 使用情况聚合。下发的业务面契约 = agent-framework ModelController（已实施，零改动）：
//   GET  /models → {default_model, models:[{id,name,model_id,base_url,...}]}
//   POST /models {name,provider,modelId,baseUrl,apiKey,temperature,maxTokens,timeoutSeconds,enabled}
//   PATCH /models/{id}（字段缺省=不变；重试命中 duplicate_name 时转 PATCH）
package service

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

// CreateModelConnInput 新建连接入参。
type CreateModelConnInput struct {
	Name        string  `json:"name"`
	Provider    string  `json:"provider"`
	ModelID     string  `json:"modelId"`
	BaseURL     string  `json:"baseUrl"`
	APIKey      string  `json:"apiKey"`
	Temperature float64 `json:"temperature"`
	MaxTokens   int     `json:"maxTokens"`
	TimeoutSec  int     `json:"timeoutSeconds"`
}

// ModelConnView 连接视图（apiKey 一律掩码回显，明文永不直出）。
type ModelConnView struct {
	store.ModelConnection
	APIKeyMasked  string `json:"apiKeyMasked"`
	DeliveryCount int64  `json:"deliveryCount"`
}

func maskKey(k string) string {
	if k == "" {
		return ""
	}
	if len(k) <= 6 {
		return "***"
	}
	return k[:3] + "***" + k[len(k)-4:]
}

func connView(c *Core, m store.ModelConnection) ModelConnView {
	v := ModelConnView{ModelConnection: m, APIKeyMasked: maskKey(m.APIKey)}
	c.DB.Model(&store.ModelDelivery{}).Where("connection_id = ? AND status = ?", m.ID, "synced").
		Count(&v.DeliveryCount)
	return v
}

// GetModelConnection 按 id 查询。
func (c *Core) GetModelConnection(id uint) (*store.ModelConnection, error) {
	var m store.ModelConnection
	if err := c.DB.First(&m, id).Error; err != nil {
		return nil, ErrNotFound
	}
	return &m, nil
}

// GetModelConnectionInWorkspace 空间内查询（console 勾选源裁决）。
func (c *Core) GetModelConnectionInWorkspace(workspaceID, id uint) (*store.ModelConnection, error) {
	var m store.ModelConnection
	if err := c.DB.Where("id = ? AND workspace_id = ?", id, workspaceID).First(&m).Error; err != nil {
		return nil, ErrNotFound
	}
	return &m, nil
}

// ListModelConnections 空间内连接列表。
func (c *Core) ListModelConnections(workspaceID uint) ([]ModelConnView, error) {
	var rows []store.ModelConnection
	if err := c.DB.Where("workspace_id = ?", workspaceID).Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]ModelConnView, 0, len(rows))
	for _, r := range rows {
		out = append(out, connView(c, r))
	}
	return out, nil
}

// CreateModelConnection 新建（空间内 name 唯一；baseUrl 须 http(s)）。
func (c *Core) CreateModelConnection(workspaceID uint, in CreateModelConnInput) (*ModelConnView, error) {
	if strings.TrimSpace(in.Name) == "" || strings.TrimSpace(in.ModelID) == "" {
		return nil, fmt.Errorf("%w: name and modelId are required", ErrInvalidForm)
	}
	if !strings.HasPrefix(in.BaseURL, "http://") && !strings.HasPrefix(in.BaseURL, "https://") {
		return nil, fmt.Errorf("%w: baseUrl must be http(s)", ErrInvalidForm)
	}
	var cnt int64
	if err := c.DB.Model(&store.ModelConnection{}).
		Where("workspace_id = ? AND name = ?", workspaceID, in.Name).Count(&cnt).Error; err != nil {
		return nil, err
	}
	if cnt > 0 {
		return nil, fmt.Errorf("%w: duplicate_name", ErrDuplicateName)
	}
	if in.Provider == "" {
		in.Provider = "openai"
	}
	m := store.ModelConnection{WorkspaceID: workspaceID, Name: in.Name,
		Provider: in.Provider, ModelID: in.ModelID, BaseURL: in.BaseURL, APIKey: in.APIKey,
		Temperature: in.Temperature, MaxTokens: in.MaxTokens, TimeoutSec: in.TimeoutSec,
		Status: "unverified"}
	if m.Temperature == 0 {
		m.Temperature = 0.3
	}
	if m.MaxTokens == 0 {
		m.MaxTokens = 16384
	}
	if m.TimeoutSec == 0 {
		m.TimeoutSec = 120
	}
	if err := c.DB.Create(&m).Error; err != nil {
		return nil, err
	}
	v := connView(c, m)
	return &v, nil
}

// PatchModelConnInput 更新入参（apiKey 传 "" = 清除；改字段后 delivery 全部置 pending）。
type PatchModelConnInput struct {
	Name        *string  `json:"name"`
	ModelID     *string  `json:"modelId"`
	BaseURL     *string  `json:"baseUrl"`
	APIKey      *string  `json:"apiKey"`
	Temperature *float64 `json:"temperature"`
	MaxTokens   *int     `json:"maxTokens"`
	TimeoutSec  *int     `json:"timeoutSeconds"`
}

// UpdateModelConnection 局部更新；配置变更 → 全部 delivery 置 pending（页面重新下发）。
func (c *Core) UpdateModelConnection(workspaceID, id uint, in PatchModelConnInput) (*ModelConnView, error) {
	var m store.ModelConnection
	if err := c.DB.Where("id = ? AND workspace_id = ?", id, workspaceID).First(&m).Error; err != nil {
		return nil, ErrNotFound
	}
	updates := map[string]interface{}{}
	if in.Name != nil && *in.Name != "" {
		updates["name"] = *in.Name
	}
	if in.ModelID != nil && *in.ModelID != "" {
		updates["model_id"] = *in.ModelID
	}
	if in.BaseURL != nil && *in.BaseURL != "" {
		updates["base_url"] = *in.BaseURL
	}
	if in.APIKey != nil {
		updates["api_key"] = *in.APIKey // 传 "" = 清除（回落系统默认）
	}
	if in.Temperature != nil {
		updates["temperature"] = *in.Temperature
	}
	if in.MaxTokens != nil {
		updates["max_tokens"] = *in.MaxTokens
	}
	if in.TimeoutSec != nil {
		updates["timeout_sec"] = *in.TimeoutSec
	}
	if len(updates) > 0 {
		if err := c.DB.Model(&m).Updates(updates).Error; err != nil {
			return nil, err
		}
		// 配置漂移 → 全部 delivery 置 pending（无后台任务，页面手动/创建链路触发重发）
		if err := c.DB.Model(&store.ModelDelivery{}).
			Where("connection_id = ?", id).
			Updates(map[string]interface{}{"status": "pending", "error": ""}).Error; err != nil {
			return nil, err
		}
	}
	v := connView(c, m)
	return &v, nil
}

// DeleteModelConnection 删除保护：仍为某 Agent 默认连接（env LLM_* 匹配）→ 409；
// 仅下发连接可直接删，同时删 delivery 行。Agent 侧 model_config 不自动删（保守）。
func (c *Core) DeleteModelConnection(workspaceID, id uint) error {
	m, err := c.GetModelConnectionInWorkspace(workspaceID, id)
	if err != nil {
		return err
	}
	var svcs []store.ServiceEntity
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&svcs).Error; err != nil {
		return err
	}
	for _, s := range svcs {
		env := map[string]string{}
		_ = json.Unmarshal([]byte(s.EnvJSON), &env)
		if env["LLM_MODEL"] == m.ModelID && env["LLM_BASE_URL"] == m.BaseURL {
			return fmt.Errorf("%w: default_connection_in_use by agent %s", ErrConflict, s.K8sName)
		}
	}
	return c.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("connection_id = ?", id).Delete(&store.ModelDelivery{}).Error; err != nil {
			return err
		}
		return tx.Delete(m).Error
	})
}

// TestResult 连通性测试结果。
type TestResult struct {
	OK        bool   `json:"ok"`
	LatencyMs int64  `json:"latencyMs"`
	Error     string `json:"error,omitempty"`
}

// TestModelConnection backend 直连 baseUrl 最小 completion（max_tokens≈16）。
// 成功 verified / 失败 failed（错误摘要落 status 展示）。
func (c *Core) TestModelConnection(workspaceID, id uint) (*TestResult, error) {
	m, err := c.GetModelConnectionInWorkspace(workspaceID, id)
	if err != nil {
		return nil, err
	}
	res := testOpenAIEndpoint(m.BaseURL, m.APIKey, m.ModelID, m.TimeoutSec)
	now := time.Now()
	status := "failed"
	if res.OK {
		status = "verified"
	}
	if err := c.DB.Model(m).Updates(map[string]interface{}{
		"status": status, "last_tested_at": &now,
	}).Error; err != nil {
		return nil, err
	}
	return res, nil
}

// testOpenAIEndpoint 最小 completion 探测（POST {base}/chat/completions）。
func testOpenAIEndpoint(baseURL, apiKey, modelID string, timeoutSec int) *TestResult {
	if timeoutSec <= 0 {
		timeoutSec = 30
	}
	body, _ := json.Marshal(map[string]interface{}{
		"model": modelID, "max_tokens": 16,
		"messages": []map[string]string{{"role": "user", "content": "ping"}},
	})
	client := &http.Client{Timeout: time.Duration(timeoutSec) * time.Second}
	start := time.Now()
	req, err := http.NewRequest(http.MethodPost, strings.TrimRight(baseURL, "/")+"/chat/completions",
		bytes.NewReader(body))
	if err != nil {
		return &TestResult{OK: false, Error: err.Error()}
	}
	req.Header.Set("Content-Type", "application/json")
	if apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	resp, err := client.Do(req)
	if err != nil {
		return &TestResult{OK: false, LatencyMs: time.Since(start).Milliseconds(), Error: err.Error()}
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &TestResult{OK: false, LatencyMs: time.Since(start).Milliseconds(),
			Error: fmt.Sprintf("HTTP %d", resp.StatusCode)}
	}
	return &TestResult{OK: true, LatencyMs: time.Since(start).Milliseconds()}
}

// ---- 下发（deliver）----

// registerDeliveries 创建链路：勾选连接 → pending 行（幂等：已有行不重复建）。
func (c *Core) registerDeliveries(conns []*store.ModelConnection, serviceID uint) error {
	for _, conn := range conns {
		var cnt int64
		if err := c.DB.Model(&store.ModelDelivery{}).
			Where("connection_id = ? AND service_id = ?", conn.ID, serviceID).Count(&cnt).Error; err != nil {
			return err
		}
		if cnt > 0 {
			continue
		}
		if err := c.DB.Create(&store.ModelDelivery{ConnectionID: conn.ID,
			ServiceID: serviceID, Status: "pending"}).Error; err != nil {
			return err
		}
	}
	return nil
}

// syncDeliveries 编辑链路：勾选集增量重建（新增 pending；移除的行删除——远端 model_config
// 保留，usage 不再展示）。
func (c *Core) syncDeliveries(conns []*store.ModelConnection, serviceID uint) error {
	keep := map[uint]bool{}
	for _, conn := range conns {
		keep[conn.ID] = true
		var cnt int64
		if err := c.DB.Model(&store.ModelDelivery{}).
			Where("connection_id = ? AND service_id = ?", conn.ID, serviceID).Count(&cnt).Error; err != nil {
			return err
		}
		if cnt == 0 {
			if err := c.DB.Create(&store.ModelDelivery{ConnectionID: conn.ID,
				ServiceID: serviceID, Status: "pending"}).Error; err != nil {
				return err
			}
		}
	}
	var rows []store.ModelDelivery
	if err := c.DB.Where("service_id = ?", serviceID).Find(&rows).Error; err != nil {
		return err
	}
	for _, r := range rows {
		if !keep[r.ConnectionID] {
			if err := c.DB.Delete(&r).Error; err != nil {
				return err
			}
		}
	}
	return nil
}

// ListServiceDeliveries 某服务的全部下发行。
func (c *Core) ListServiceDeliveries(serviceID uint) ([]store.ModelDelivery, error) {
	var rows []store.ModelDelivery
	err := c.DB.Where("service_id = ?", serviceID).Order("id ASC").Find(&rows).Error
	return rows, err
}

// DeliverOne 单条下发（幂等）：GET /models 查重 → POST（不存在）或 PATCH（漂移）。
func (c *Core) DeliverOne(d *store.ModelDelivery) error {
	conn, err := c.GetModelConnection(d.ConnectionID)
	if err != nil {
		return err
	}
	svc, err := c.Get(d.ServiceID)
	if err != nil {
		return err
	}
	fail := func(format string, args ...interface{}) error {
		msg := fmt.Sprintf(format, args...)
		now := time.Now()
		_ = c.DB.Model(d).Updates(map[string]interface{}{"status": "failed", "error": msg[:min(len(msg), 250)], "synced_at": &now}).Error
		return fmt.Errorf("%s", msg)
	}
	if svc.Status != store.StatusRunning {
		return fail("agent_not_ready: status=%s", svc.Status)
	}
	base := svc.ClusterURL
	client := &http.Client{Timeout: time.Duration(conn.TimeoutSec) * time.Second}

	// GET /models 查重（按 model_id + base_url 匹配）
	type remoteModel struct {
		ID      string `json:"id"`
		Name    string `json:"name"`
		ModelID string `json:"model_id"`
		BaseURL string `json:"base_url"`
	}
	var remote struct {
		DefaultModel string        `json:"default_model"`
		Models       []remoteModel `json:"models"`
	}
	resp, err := httpGetJSON(client, base+"/models")
	if err != nil {
		return fail("GET /models: %v", err)
	}
	if err := json.Unmarshal(resp, &remote); err != nil {
		return fail("GET /models decode: %v", err)
	}
	var existing *remoteModel
	for i := range remote.Models {
		m := &remote.Models[i]
		if m.ModelID == conn.ModelID && m.BaseURL == conn.BaseURL {
			existing = m
			break
		}
	}
	if existing == nil {
		// 同名连接视为同一逻辑模型（baseUrl 变更 = 配置漂移 → PATCH 更新远端值）
		for i := range remote.Models {
			m := &remote.Models[i]
			if m.Name == conn.Name {
				existing = m
				break
			}
		}
	}
	payload := map[string]interface{}{
		"name": conn.Name, "provider": conn.Provider, "modelId": conn.ModelID,
		"baseUrl": conn.BaseURL, "temperature": conn.Temperature,
		"maxTokens": conn.MaxTokens, "timeoutSeconds": conn.TimeoutSec, "enabled": true,
	}
	if conn.APIKey != "" {
		payload["apiKey"] = conn.APIKey
	}
	if existing != nil {
		// 幂等跳过 or 配置漂移 → PATCH（payload 全字段覆盖即可对齐漂移字段）
		if b, err := json.Marshal(payload); err == nil {
			if _, err := httpJSON(client, http.MethodPatch, base+"/models/"+existing.ID, b); err != nil {
				return fail("PATCH /models/%s: %v", existing.ID, err)
			}
		}
		now := time.Now()
		return c.DB.Model(d).Updates(map[string]interface{}{
			"status": "synced", "remote_model_id": existing.ID, "error": "", "synced_at": &now,
		}).Error
	}
	b, _ := json.Marshal(payload)
	respBody, err := httpJSON(client, http.MethodPost, base+"/models", b)
	if err != nil {
		// POST 撞 name 唯一约束（duplicate_name）→ 兜底 PATCH：按 name 重查
		if strings.Contains(err.Error(), "duplicate_name") {
			if r2, e2 := httpGetJSON(client, base+"/models?all=true"); e2 == nil {
				var remote2 struct {
					Models []remoteModel `json:"models"`
				}
				if json.Unmarshal(r2, &remote2) == nil {
					for _, m := range remote2.Models {
						if m.Name == conn.Name {
							respBody, err = httpJSON(client, http.MethodPatch, base+"/models/"+m.ID, b)
							break
						}
					}
				}
			}
		}
		if err != nil {
			return fail("POST /models: %v", err)
		}
	}
	var created remoteModel
	remoteID := ""
	if json.Unmarshal(respBody, &created) == nil {
		remoteID = created.ID
	}
	now := time.Now()
	return c.DB.Model(d).Updates(map[string]interface{}{
		"status": "synced", "remote_model_id": remoteID, "error": "", "synced_at": &now,
	}).Error
}

// DeliverPendingForService 服务 running 回调（OnServiceRunning 挂载）：逐条下发 pending 行。
// 失败置 failed 不重试（无后台任务，页面手动重发——守红线）。
func (c *Core) DeliverPendingForService(serviceID uint) {
	var rows []store.ModelDelivery
	if err := c.DB.Where("service_id = ? AND status = ?", serviceID, "pending").
		Find(&rows).Error; err != nil {
		return
	}
	for i := range rows {
		_ = c.DeliverOne(&rows[i])
	}
}

// DeliverConnectionToService 手动触发（usage 页「下发/重新下发」按钮）。
func (c *Core) DeliverConnectionToService(workspaceID, connectionID, serviceID uint) (*store.ModelDelivery, error) {
	if _, err := c.GetModelConnectionInWorkspace(workspaceID, connectionID); err != nil {
		return nil, err
	}
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, err
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	var d store.ModelDelivery
	err = c.DB.Where("connection_id = ? AND service_id = ?", connectionID, serviceID).First(&d).Error
	if err != nil {
		if err != gorm.ErrRecordNotFound {
			return nil, err
		}
		d = store.ModelDelivery{ConnectionID: connectionID, ServiceID: serviceID, Status: "pending"}
		if err := c.DB.Create(&d).Error; err != nil {
			return nil, err
		}
	} else if d.Status == "synced" {
		// 幂等：已 synced 则跳过（重复下发不产生 Agent 侧重复模型）
		return &d, nil
	} else {
		if err := c.DB.Model(&d).Update("status", "pending").Error; err != nil {
			return nil, err
		}
	}
	if err := c.DeliverOne(&d); err != nil {
		return &d, err
	}
	return &d, nil
}

// ---- usage / agent models ----

// UsageConnRow usage 行内连接状态。
type UsageConnRow struct {
	ConnectionID  uint       `json:"connectionId"`
	Name          string     `json:"name"`
	Status        string     `json:"status"` // synced | pending | failed | removed
	RemoteModelID string     `json:"remoteModelId"`
	Error         string     `json:"error"`
	SyncedAt      *time.Time `json:"syncedAt"`
}

// UsageRow usage 行（每 Agent：默认连接 + 已勾选连接状态组）。
type UsageRow struct {
	ServiceID             uint           `json:"serviceId"`
	DisplayName           string         `json:"displayName"`
	DefaultConnectionName string         `json:"defaultConnectionName"`
	Connections           []UsageConnRow `json:"connections"`
}

// ModelConnectionUsage Agent 使用情况聚合（M5 §2.2）。
func (c *Core) ModelConnectionUsage(workspaceID uint) ([]UsageRow, error) {
	var svcs []store.ServiceEntity
	if err := c.DB.Where("workspace_id = ?", workspaceID).Order("id DESC").Find(&svcs).Error; err != nil {
		return nil, err
	}
	out := make([]UsageRow, 0, len(svcs))
	for _, s := range svcs {
		env := map[string]string{}
		_ = json.Unmarshal([]byte(s.EnvJSON), &env)
		row := UsageRow{ServiceID: s.ID, DisplayName: s.DisplayName}
		var conns []store.ModelConnection
		_ = c.DB.Where("workspace_id = ?", workspaceID).Find(&conns).Error
		connNames := map[uint]string{}
		for _, mc := range conns {
			connNames[mc.ID] = mc.Name
			if env["LLM_MODEL"] == mc.ModelID && env["LLM_BASE_URL"] == mc.BaseURL {
				row.DefaultConnectionName = mc.Name
			}
		}
		var deliveries []store.ModelDelivery
		if err := c.DB.Where("service_id = ?", s.ID).Order("id ASC").Find(&deliveries).Error; err != nil {
			return nil, err
		}
		for _, d := range deliveries {
			status := d.Status
			name := connNames[d.ConnectionID]
			if name == "" {
				status = "removed" // 连接已删：Agent 侧不自动删（保守），usage 标记
				name = fmt.Sprintf("#%d", d.ConnectionID)
			}
			row.Connections = append(row.Connections, UsageConnRow{
				ConnectionID: d.ConnectionID, Name: name, Status: status,
				RemoteModelID: d.RemoteModelID, Error: d.Error, SyncedAt: d.SyncedAt,
			})
		}
		out = append(out, row)
	}
	return out, nil
}

// AgentModels 调试下拉数据源：透传目标 Agent GET /models（含下发连接 + 系统默认）。
func (c *Core) AgentModels(workspaceID, serviceID uint) (json.RawMessage, error) {
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := httpGetJSON(client, svc.ClusterURL+"/models")
	if err != nil {
		return nil, fmt.Errorf("agent models: %w", err)
	}
	return json.RawMessage(resp), nil
}

// ---- HTTP 小工具 ----

func httpGetJSON(client *http.Client, url string) ([]byte, error) {
	resp, err := client.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncateBytes(body, 200))
	}
	return body, nil
}

func httpJSON(client *http.Client, method, url string, body []byte) ([]byte, error) {
	req, err := http.NewRequest(method, url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncateBytes(respBody, 300))
	}
	return respBody, nil
}

func truncateBytes(b []byte, n int) string {
	if len(b) <= n {
		return string(b)
	}
	return string(b[:n]) + "…"
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
