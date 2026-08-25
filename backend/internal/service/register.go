package service

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"agent-manager/backend/internal/store"
)

const registerHTTPTimeout = 5 * time.Second

// AgentCard A2A agent-card.json 的宽松解析（保留原始 JSON + 提取常用字段）。
type AgentCard struct {
	Name            string          `json:"name"`
	Version         string          `json:"version"`
	Description     string          `json:"description"`
	ProtocolVersion string          `json:"protocolVersion"`
	URL             string          `json:"url"`
	Skills          json.RawMessage `json:"skills,omitempty"`
	Capabilities    json.RawMessage `json:"capabilities,omitempty"`
}

// Register 拉取并入库 agent-card（含重试）。成功返回空串，失败返回原因。
func (c *Core) Register(svc *store.ServiceEntity) string {
	var card *AgentCard
	var lastErr error
	for i := 0; i <= c.Cfg.RegisterRetry; i++ {
		if i > 0 {
			time.Sleep(backoff(i)) // 2s/4s/8s...
		}
		got, err := fetchCard(svc.ClusterURL)
		if err == nil {
			card = got
			break
		}
		lastErr = err
	}
	if card == nil {
		return fmt.Sprintf("a2a register failed after retries: %v", lastErr)
	}

	skills, _ := json.Marshal(card.Skills)
	now := time.Now()
	err := c.DB.Model(svc).Updates(map[string]interface{}{
		"agent_card_json":    mustJSON(card),
		"registered_name":    card.Name,
		"registered_version": card.Version,
		"skills_json":        string(skills),
		"registered_at":      &now,
	}).Error
	if err != nil {
		return fmt.Sprintf("save registration: %v", err)
	}
	return ""
}

// fetchCard 请求 {clusterURL}/.well-known/agent-card.json 并校验 /health。
func fetchCard(clusterURL string) (*AgentCard, error) {
	client := &http.Client{Timeout: registerHTTPTimeout}
	resp, err := client.Get(clusterURL + "/.well-known/agent-card.json")
	if err != nil {
		return nil, fmt.Errorf("fetch agent-card: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("agent-card http %d", resp.StatusCode)
	}
	var card AgentCard
	if err := json.Unmarshal(body, &card); err != nil {
		return nil, fmt.Errorf("parse agent-card: %w", err)
	}
	// 健康检查失败同样视为未就绪
	hresp, err := client.Get(clusterURL + "/health")
	if err != nil {
		return nil, fmt.Errorf("health: %w", err)
	}
	hresp.Body.Close()
	if hresp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("health http %d", hresp.StatusCode)
	}
	return &card, nil
}

// Reregister 手动重新注册（register_failed → running）。
func (c *Core) Reregister(id uint) (*store.ServiceEntity, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, err
	}
	if reason := c.Register(svc); reason != "" {
		c.transition(svc, store.StatusRegisterFailed, "manual re-register: "+reason)
		return svc, fmt.Errorf("%s", reason)
	}
	c.transition(svc, store.StatusRunning, "manual re-register ok")
	return svc, nil
}

func backoff(attempt int) time.Duration {
	d := 2 * time.Second
	for i := 1; i < attempt && i < 5; i++ {
		d *= 2
	}
	return d
}
