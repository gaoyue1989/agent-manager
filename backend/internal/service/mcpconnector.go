// MCP 连接器（M6）：工作空间级配置 CRUD + 验证（最小 MCP 客户端：initialize +
// tools/list 两步 JSON-RPC over HTTP）+ 引用计数。注入链路在 agentgen（M3 消费）。
package service

import (
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf8"
	"time"

	"agent-manager/backend/internal/store"
)

// CreateMcpConnectorInput 新建连接器入参。
type CreateMcpConnectorInput struct {
	Name        string          `json:"name"`
	Transport   string          `json:"transport"`
	Endpoint    string          `json:"endpoint"`
	AuthType    string          `json:"authType"`
	AuthToken   string          `json:"authToken"`
	TimeoutSec  int             `json:"timeoutSec"`
	ToolScope   json.RawMessage `json:"toolScope"`
	Permissions json.RawMessage `json:"permissions"`
}

// McpToolInfo verify 返回的工具快照。
type McpToolInfo struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

// McpConnectorView 连接器视图（token 永不直出）。
type McpConnectorView struct {
	store.McpConnector
	RefCount int64 `json:"refCount"`
}

// GetMcpConnector 按 id 查询。
func (c *Core) GetMcpConnector(id uint) (*store.McpConnector, error) {
	var m store.McpConnector
	if err := c.DB.First(&m, id).Error; err != nil {
		return nil, ErrNotFound
	}
	return &m, nil
}

// ListMcpConnectors 空间内连接器列表（refCount = 生成包 mcpServers 声明命中本连接器名的
// 空间内服务数，粗统计）。
func (c *Core) ListMcpConnectors(workspaceID uint) ([]McpConnectorView, error) {
	var rows []store.McpConnector
	if err := c.DB.Where("workspace_id = ?", workspaceID).Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]McpConnectorView, 0, len(rows))
	for _, r := range rows {
		out = append(out, McpConnectorView{McpConnector: r, RefCount: c.connectorRefCount(workspaceID, r.Name)})
	}
	return out, nil
}

// connectorRefCount 引用粗统计：空间内服务的运行包 frontmatter mcpServers[].server 命中。
func (c *Core) connectorRefCount(workspaceID uint, name string) int64 {
	var svcs []store.ServiceEntity
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&svcs).Error; err != nil {
		return 0
	}
	var n int64
	for _, s := range svcs {
		pkg, err := c.Packages.Get(s.PackageID)
		if err != nil || pkg.ManifestJSON == "" {
			continue
		}
		var cfg struct {
			MCPServers []struct {
				Server string `json:"server"`
			} `json:"mcpServers"`
		}
		if json.Unmarshal([]byte(pkg.ManifestJSON), &cfg) == nil {
			for _, m := range cfg.MCPServers {
				if m.Server == name {
					n++
					break
				}
			}
		}
	}
	return n
}

// CreateMcpConnector 新建（空间内 name 唯一；endpoint 须 http(s)）。
func (c *Core) CreateMcpConnector(workspaceID uint, in CreateMcpConnectorInput) (*McpConnectorView, error) {
	if strings.TrimSpace(in.Name) == "" || strings.TrimSpace(in.Endpoint) == "" {
		return nil, fmt.Errorf("%w: name and endpoint are required", ErrInvalidForm)
	}
	// mcp_connectors.name 列 varchar(64)（issue #149：超长输入曾以 500 Data too long 直出）
	if utf8.RuneCountInString(in.Name) > 64 {
		return nil, fmt.Errorf("%w: name too long (max 64)", ErrInvalidForm)
	}
	if in.Transport != "sse" && in.Transport != "streamable_http" && in.Transport != "" {
		return nil, fmt.Errorf("%w: invalid transport", ErrInvalidForm)
	}
	if in.Transport == "" {
		in.Transport = "streamable_http"
	}
	if !strings.HasPrefix(in.Endpoint, "http://") && !strings.HasPrefix(in.Endpoint, "https://") {
		return nil, fmt.Errorf("%w: endpoint must be http(s)", ErrInvalidForm)
	}
	var cnt int64
	if err := c.DB.Model(&store.McpConnector{}).
		Where("workspace_id = ? AND name = ?", workspaceID, in.Name).Count(&cnt).Error; err != nil {
		return nil, err
	}
	if cnt > 0 {
		return nil, fmt.Errorf("%w: duplicate_name", ErrDuplicateName)
	}
	m := store.McpConnector{WorkspaceID: workspaceID, Name: in.Name,
		Transport: in.Transport, Endpoint: in.Endpoint,
		AuthType: in.AuthType, AuthToken: in.AuthToken, TimeoutSec: in.TimeoutSec,
		Status: "unverified"}
	if m.AuthType == "" {
		m.AuthType = "none"
	}
	if m.TimeoutSec <= 0 {
		m.TimeoutSec = 120
	}
	if len(in.ToolScope) > 0 {
		m.ToolScope = string(in.ToolScope)
	} else {
		m.ToolScope = `{"mode":"all"}`
	}
	if len(in.Permissions) > 0 {
		m.Permissions = string(in.Permissions)
	} else {
		m.Permissions = "{}"
	}
	if err := c.DB.Create(&m).Error; err != nil {
		return nil, err
	}
	return &McpConnectorView{McpConnector: m}, nil
}

// PatchMcpConnectorInput 局部更新（authToken 传 "" = 清除；改 endpoint/token 后状态回 unverified）。
type PatchMcpConnectorInput struct {
	Name       *string          `json:"name"`
	Transport  *string          `json:"transport"`
	Endpoint   *string          `json:"endpoint"`
	AuthType   *string          `json:"authType"`
	AuthToken  *string          `json:"authToken"`
	TimeoutSec *int             `json:"timeoutSec"`
	ToolScope  *json.RawMessage `json:"toolScope"`
	// Permissions 三态默认策略（tools.map）；nil=不变
	Permissions *json.RawMessage `json:"permissions"`
}

// UpdateMcpConnector 局部更新。
func (c *Core) UpdateMcpConnector(workspaceID, id uint, in PatchMcpConnectorInput) (*McpConnectorView, error) {
	var m store.McpConnector
	if err := c.DB.Where("id = ? AND workspace_id = ?", id, workspaceID).First(&m).Error; err != nil {
		return nil, ErrNotFound
	}
	updates := map[string]interface{}{}
	if in.Name != nil && *in.Name != "" {
		updates["name"] = *in.Name
	}
	if in.Transport != nil && *in.Transport != "" {
		updates["transport"] = *in.Transport
	}
	if in.Endpoint != nil && *in.Endpoint != "" {
		updates["endpoint"] = *in.Endpoint
		updates["status"] = "unverified" // 目标变更需重新验证
	}
	if in.AuthType != nil && *in.AuthType != "" {
		updates["auth_type"] = *in.AuthType
	}
	if in.AuthToken != nil {
		updates["auth_token"] = *in.AuthToken
	}
	if in.TimeoutSec != nil && *in.TimeoutSec > 0 {
		updates["timeout_sec"] = *in.TimeoutSec
	}
	if in.ToolScope != nil {
		updates["tool_scope"] = string(*in.ToolScope)
	}
	if in.Permissions != nil {
		updates["permissions"] = string(*in.Permissions)
	}
	if len(updates) > 0 {
		if err := c.DB.Model(&m).Updates(updates).Error; err != nil {
			return nil, err
		}
	}
	return &McpConnectorView{McpConnector: m}, nil
}

// DeleteMcpConnector 被引用（refCount>0）→ 409。
func (c *Core) DeleteMcpConnector(workspaceID, id uint) error {
	m, err := c.GetMcpConnector(id)
	if err != nil {
		return err
	}
	if m.WorkspaceID != workspaceID {
		return ErrNotFound
	}
	if c.connectorRefCount(workspaceID, m.Name) > 0 {
		return fmt.Errorf("%w: connector_in_use", ErrConflict)
	}
	return c.DB.Delete(m).Error
}

// VerifyMcpConnector 连通性探测：MCP initialize → notifications/initialized → tools/list。
// 成功回写 verified + 工具快照进 toolScope（subset 选项源）；失败 failed。
func (c *Core) VerifyMcpConnector(workspaceID, id uint) (map[string]interface{}, error) {
	m, err := c.GetMcpConnector(id)
	if err != nil {
		return nil, err
	}
	if m.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	timeout := m.TimeoutSec
	if timeout <= 0 {
		timeout = 30
	}
	tools, latency, err := mcpListTools(m.Endpoint, m.AuthToken, timeout)
	now := time.Now()
	if err != nil {
		_ = c.DB.Model(m).Updates(map[string]interface{}{
			"status": "failed", "last_verified_at": &now,
		}).Error
		return map[string]interface{}{"ok": false, "error": err.Error(), "latencyMs": latency}, nil
	}
	_ = c.DB.Model(m).Updates(map[string]interface{}{
		"status": "verified", "last_verified_at": &now,
	}).Error
	// 工具快照写入 toolScope（subset 模式的选项源；mode 保持不变）
	var scope struct {
		Mode  string   `json:"mode"`
		Tools []string `json:"tools"`
	}
	_ = json.Unmarshal([]byte(m.ToolScope), &scope)
	if scope.Mode == "" {
		scope.Mode = "all"
	}
	names := make([]string, 0, len(tools))
	for _, t := range tools {
		names = append(names, t.Name)
	}
	scope.Tools = names
	b, _ := json.Marshal(scope)
	_ = c.DB.Model(m).Update("tool_scope", string(b)).Error
	return map[string]interface{}{"ok": true, "toolCount": len(tools), "tools": tools, "latencyMs": latency}, nil
}

// ---- 最小 MCP 客户端（streamable HTTP：initialize → initialized 通知 → tools/list）----

type mcpRPCRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

func mcpSessionHeaders(authToken string) [][2]string {
	h := [][2]string{
		{"Content-Type", "application/json"},
		{"Accept", "application/json, text/event-stream"},
	}
	if authToken != "" {
		h = append(h, [2]string{"Authorization", "Bearer " + authToken})
	}
	return h
}

// mcpListTools 两步探测（initialize → tools/list），返回工具快照与耗时。
func mcpListTools(endpoint, authToken string, timeoutSec int) ([]McpToolInfo, int64, error) {
	client := httpTimeoutClient(timeoutSec)
	start := time.Now()
	// 会话头在 initialize 后回填；tools/list 必须复用同一 Mcp-Session-Id
	//（go-sdk 服务端按会话校验状态，缺头会报 "invalid during session initialization"——部署实测）
	sessionHeaders := mcpSessionHeaders(authToken)
	do := func(req mcpRPCRequest) (json.RawMessage, [][2]string, error) {
		body, err := json.Marshal(req)
		if err != nil {
			return nil, nil, err
		}
		respBody, headers, err := client.Post(endpoint, body, sessionHeaders)
		if err != nil {
			return nil, nil, err
		}
		// streamable HTTP 响应可能为 application/json 或 SSE（data: 帧内 JSON）
		payload := extractMCPJSON(respBody)
		var rpc struct {
			Result json.RawMessage `json:"result"`
			Error  *struct {
				Code    int    `json:"code"`
				Message string `json:"message"`
			} `json:"error"`
		}
		if err := json.Unmarshal(payload, &rpc); err != nil {
			return nil, headers, fmt.Errorf("decode rpc response: %w", err)
		}
		if rpc.Error != nil {
			return nil, headers, fmt.Errorf("rpc error %d: %s", rpc.Error.Code, rpc.Error.Message)
		}
		return rpc.Result, headers, nil
	}

	initResult, headers, err := do(mcpRPCRequest{JSONRPC: "2.0",
		ID: json.RawMessage("1"), Method: "initialize",
		Params: json.RawMessage(`{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"oaf-platform","version":"1.0.0"}}`)})
	if err != nil {
		return nil, time.Since(start).Milliseconds(), err
	}
	_ = initResult
	// 会话头回传（Mcp-Session-Id 等）——回填进共享 sessionHeaders，供后续通知与 tools/list 复用
	for _, k := range []string{"Mcp-Session-Id", "MCP-Session-Id", "mcp-session-id"} {
		if v := headerGet(headers, k); v != "" {
			sessionHeaders = append(sessionHeaders, [2]string{"Mcp-Session-Id", v})
			break
		}
	}
	// initialized 通知（无响应）
	notif, _ := json.Marshal(mcpRPCRequest{JSONRPC: "2.0", Method: "notifications/initialized"})
	_, _, _ = client.Post(endpoint, notif, sessionHeaders)

	result, _, err := do(mcpRPCRequest{JSONRPC: "2.0",
		ID: json.RawMessage("2"), Method: "tools/list", Params: json.RawMessage("{}")})
	if err != nil {
		return nil, time.Since(start).Milliseconds(), err
	}
	var tools struct {
		Tools []McpToolInfo `json:"tools"`
	}
	if err := json.Unmarshal(result, &tools); err != nil {
		return nil, time.Since(start).Milliseconds(), err
	}
	return tools.Tools, time.Since(start).Milliseconds(), nil
}

func headerGet(headers [][2]string, key string) string {
	for _, h := range headers {
		if strings.EqualFold(h[0], key) {
			return h[1]
		}
	}
	return ""
}

// extractMCPJSON 从响应体提取首个 JSON-RPC 响应（application/json 直返；
// SSE 取首个 data: 帧的 JSON 对象）。
func extractMCPJSON(body []byte) json.RawMessage {
	trimmed := strings.TrimSpace(string(body))
	if trimmed == "" {
		return json.RawMessage(`{}`)
	}
	if strings.HasPrefix(trimmed, "{") {
		return json.RawMessage(trimmed)
	}
	// SSE 形态
	for _, line := range strings.Split(trimmed, "\n") {
		line = strings.TrimRight(line, "\r")
		if strings.HasPrefix(line, "data:") {
			data := strings.TrimSpace(line[5:])
			if strings.HasPrefix(data, "{") {
				return json.RawMessage(data)
			}
		}
	}
	return json.RawMessage(`{}`)
}
