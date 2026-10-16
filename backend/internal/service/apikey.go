// API Key 签发/校验（M8 签发链路 + M9 数据面 introspect）。
// 明文仅签发时返回一次：ak-{8 hex}-{24 hex}；sha256 落库，列表只见前缀。
// 校验/生效的数据面入口是 introspect（router auth_request）。
package service

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"agent-manager/backend/internal/store"
)

// IssueApiKeyInput 签发入参。
type IssueApiKeyInput struct {
	Name           string `json:"name"`
	AgentID        *uint  `json:"agentId"` // nil=空间内全部 Agent
	ExpiresInDays  int    `json:"expiresInDays"`
	WorkspaceID    uint
	UserID         uint
}

// ApiKeyView 列表视图（无 hash、无明文）。
type ApiKeyView struct {
	ID          uint       `json:"id"`
	Name        string     `json:"name"`
	KeyPrefix   string     `json:"keyPrefix"`
	AgentID     *uint      `json:"agentId"`
	AgentName   string     `json:"agentName,omitempty"`
	Status      string     `json:"status"` // active | revoked | expired（过期查询时惰性判定）
	LastUsedAt  *time.Time `json:"lastUsedAt"`
	ExpiresAt   *time.Time `json:"expiresAt"`
	CreatedAt   time.Time  `json:"createdAt"`
}

// IssueApiKey 签发并返回一次性明文。
func (c *Core) IssueApiKey(in IssueApiKeyInput) (*store.ApiKey, string, error) {
	if in.Name == "" {
		return nil, "", fmt.Errorf("%w: name is required", ErrInvalidForm)
	}
	suffix := make([]byte, 12)
	if _, err := rand.Read(suffix); err != nil {
		return nil, "", err
	}
	prefixBytes := make([]byte, 4)
	if _, err := rand.Read(prefixBytes); err != nil {
		return nil, "", err
	}
	plain := fmt.Sprintf("ak-%s-%s", hex.EncodeToString(prefixBytes), hex.EncodeToString(suffix))
	sum := sha256.Sum256([]byte(plain))
	rec := &store.ApiKey{
		WorkspaceID: in.WorkspaceID, AgentID: in.AgentID, UserID: in.UserID,
		Name: in.Name, KeyPrefix: plain[:8], // ak- 前 8 位（列表识别）
		KeyHash: hex.EncodeToString(sum[:]), Status: "active",
	}
	if in.ExpiresInDays > 0 {
		exp := time.Now().AddDate(0, 0, in.ExpiresInDays)
		rec.ExpiresAt = &exp
	}
	if err := c.DB.Create(rec).Error; err != nil {
		return nil, "", err
	}
	c.recordAudit("api_key.issue", rec.KeyPrefix, map[string]interface{}{
		"kind": "api_key.issue", "actor": in.UserID, "name": in.Name,
	})
	return rec, plain, nil
}

// ListApiKeys 空间内 Key 列表（agentID>0 时按作用域过滤）。
func (c *Core) ListApiKeys(workspaceID uint, agentID uint) ([]ApiKeyView, error) {
	var rows []store.ApiKey
	q := c.DB.Where("workspace_id = ?", workspaceID).Order("id DESC")
	if agentID > 0 {
		q = q.Where("agent_id = ? OR agent_id IS NULL", agentID)
	}
	if err := q.Find(&rows).Error; err != nil {
		return nil, err
	}
	now := time.Now()
	out := make([]ApiKeyView, 0, len(rows))
	for _, r := range rows {
		status := r.Status
		if status == "active" && r.ExpiresAt != nil && now.After(*r.ExpiresAt) {
			status = "expired" // 惰性判定，不做后台任务（红线：无后台写）
		}
		v := ApiKeyView{ID: r.ID, Name: r.Name, KeyPrefix: r.KeyPrefix,
			AgentID: r.AgentID, Status: status, LastUsedAt: r.LastUsedAt,
			ExpiresAt: r.ExpiresAt, CreatedAt: r.CreatedAt}
		if r.AgentID != nil {
			if svc, err := c.Get(*r.AgentID); err == nil {
				v.AgentName = svc.DisplayName
			}
		}
		out = append(out, v)
	}
	return out, nil
}

// RevokeApiKey 吊销（行保留审计）。
func (c *Core) RevokeApiKey(workspaceID, id uint) error {
	res := c.DB.Model(&store.ApiKey{}).
		Where("id = ? AND workspace_id = ?", id, workspaceID).
		Update("status", "revoked")
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return ErrNotFound
	}
	c.recordAudit("api_key.revoke", fmt.Sprintf("key-%d", id), map[string]interface{}{
		"kind": "api_key.revoke", "workspace": workspaceID,
	})
	return nil
}

// ---- introspect（M9，router auth_request 数据面）----

// introspectThrottle last_used_at 节流写（热路径防写放大；内存态，多副本各自节流无害）。
var introspectThrottle = struct {
	sync.Mutex
	last map[uint]time.Time
}{last: map[uint]time.Time{}}

// Introspect 处理 GET /api/v1/auth/introspect：
//   - Authorization: Bearer <JWT> → 校验签名/过期（不验空间，M9 回填决议）；
//   - Authorization: Bearer ak-… → sha256 查 api_keys（active+未过期）；
//     agentId 作用域经 X-Original-URI 的 /agent/{short}/ 前缀裁决。
//
// 200 回写 X-User-Id 响应头（nginx auth_request_set 取用）；401/403 fail-closed。
func (c *Core) Introspect(w http.ResponseWriter, r *http.Request) {
	setHeader := func(uid uint) {
		w.Header().Set("X-User-Id", fmt.Sprintf("u-%d", uid))
	}
	token := ""
	if auth := r.Header.Get("Authorization"); len(auth) > 7 && auth[:7] == "Bearer " {
		token = auth[7:]
	}
	if token == "" {
		http.Error(w, `{"code":401,"message":"invalid credential"}`, http.StatusUnauthorized)
		return
	}
	// API Key 通道（sha256 hex 前 8 位无法区分，直接全量哈希比对）
	if len(token) > 3 && token[:3] == "ak-" {
		sum := sha256.Sum256([]byte(token))
		var key store.ApiKey
		if err := c.DB.Where("key_hash = ?", hex.EncodeToString(sum[:])).First(&key).Error; err != nil {
			http.Error(w, `{"code":401,"message":"invalid credential"}`, http.StatusUnauthorized)
			return
		}
		if key.Status != "active" {
			http.Error(w, `{"code":403,"message":"key revoked"}`, http.StatusForbidden)
			return
		}
		if key.ExpiresAt != nil && time.Now().After(*key.ExpiresAt) {
			http.Error(w, `{"code":403,"message":"key expired"}`, http.StatusForbidden)
			return
		}
		// 作用域裁决：绑定单 Agent 的 key 仅放行该 Agent 路由（X-Original-URI 由 nginx 附带）
		if key.AgentID != nil {
			svc, err := c.Get(*key.AgentID)
			if err != nil {
				http.Error(w, `{"code":403,"message":"agent not found"}`, http.StatusForbidden)
				return
			}
			prefix := "/agent/" + svc.ShortName + "/"
			uri := r.Header.Get("X-Original-URI")
			if uri != "" && uri != prefix && len(uri) >= len(prefix) && uri[:len(prefix)] != prefix &&
				uri != "/agent/"+svc.ShortName {
				http.Error(w, `{"code":403,"message":"key scope mismatch"}`, http.StatusForbidden)
				return
			}
		}
		c.touchApiKey(key.ID)
		setHeader(key.UserID)
		writeIntrospectOK(w, key.WorkspaceID, key.AgentID)
		return
	}
	// JWT 通道：复用 handler 层同款 HS256 校验（独立实现避免 handler→service 反向依赖，
	// 密钥与格式约定见 docs/design/console-user-workspace-split-design.md §5）
	claims, err := c.ParseSessionJWT(token)
	if err != nil || claims.UID == 0 {
		http.Error(w, `{"code":401,"message":"invalid credential"}`, http.StatusUnauthorized)
		return
	}
	setHeader(claims.UID)
	writeIntrospectOK(w, 0, nil)
}

func writeIntrospectOK(w http.ResponseWriter, wsID uint, agentID *uint) {
	w.Header().Set("Content-Type", "application/json")
	scope := map[string]interface{}{"workspaceId": wsID, "agentId": agentID}
	b, _ := json.Marshal(map[string]interface{}{"userId": w.Header().Get("X-User-Id"), "scope": scope})
	_, _ = w.Write(b)
}

// touchApiKey 更新 last_used_at（1s 节流；失败静默——记账字段不阻塞数据面）。
func (c *Core) touchApiKey(id uint) {
	introspectThrottle.Lock()
	last, ok := introspectThrottle.last[id]
	now := time.Now()
	if ok && now.Sub(last) < time.Second {
		introspectThrottle.Unlock()
		return
	}
	introspectThrottle.last[id] = now
	introspectThrottle.Unlock()
	go func() {
		_ = c.DB.Model(&store.ApiKey{}).Where("id = ?", id).Update("last_used_at", now).Error
	}()
}
