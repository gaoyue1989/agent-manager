// 服务层通用小工具（agentgen / governance 共用）。
package service

import (
	"crypto/rand"
	"encoding/json"

	"agent-manager/backend/internal/oaf"
)

func cryptoRandRead(b []byte) (int, error) { return rand.Read(b) }

// jsonMarshal 稳定 JSON 编码（err 恒吞：调用方均为可控 map）。
func jsonMarshal(v interface{}) ([]byte, error) { return json.Marshal(v) }

// jsonUnmarshalStrict 宽松 JSON 解析（容错空串）。
func jsonUnmarshalStrict(raw string, v interface{}) error {
	if raw == "" {
		return nil
	}
	return json.Unmarshal([]byte(raw), v)
}

// parseOAFConfig 解析 AGENTS.md（编辑预填用）。
func parseOAFConfig(content string) (*oaf.Config, error) { return oaf.ParseOAF(content) }

// userWsRole 查询用户在空间内的成员角色（无记录 → ErrWorkspaceNotFound）。
func (c *Core) userWsRole(userID, workspaceID uint) (string, error) {
	var m struct {
		Role string
	}
	if err := c.DB.Table("workspace_members").
		Select("role").Where("workspace_id = ? AND user_id = ?", workspaceID, userID).
		Scan(&m).Error; err != nil {
		return "", err
	}
	if m.Role == "" {
		return "", ErrWorkspaceNotFound
	}
	return m.Role, nil
}
