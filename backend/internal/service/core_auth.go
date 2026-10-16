// Core 与身份相关的横切字段与小工具（JWT 密钥、用户查询、审计写入）。
package service

import (
	"encoding/json"
	"fmt"

	"gorm.io/gorm"

	"agent-manager/backend/internal/authtoken"
	"agent-manager/backend/internal/store"
)

// ParseSessionJWT 校验平台登录 token（introspect 数据面复用）。
func (c *Core) ParseSessionJWT(token string) (authtoken.Claims, error) {
	return authtoken.Parse(c.JWTSecret, token)
}

// GetUser 按用户 id 查询。
func (c *Core) GetUser(id uint) (*store.User, error) {
	var u store.User
	if err := c.DB.First(&u, id).Error; err != nil {
		return nil, ErrNotFound
	}
	return &u, nil
}

// recordAudit 治理/凭证类审计事件：写 service_events 同表（service_id=0，reason 编码
// kind/actor/detail JSON），避免一期建独立 audit_logs 表（M1 §2.4 设计决议）。
// 非事务上下文调用（治理动作为单行写，失败仅记日志不回滚主操作）。
func (c *Core) recordAudit(action, object string, detail map[string]interface{}) {
	if detail["kind"] == nil {
		detail["kind"] = action
	}
	detail["object"] = object
	b, _ := json.Marshal(detail)
	if len(b) > 512 {
		b = b[:512]
	}
	if err := c.DB.Create(&store.ServiceEvent{ServiceID: 0, Reason: string(b)}).Error; err != nil {
		fmt.Printf("[audit] write %s failed: %v\n", action, err)
	}
}

// recordAuditTx 事务版审计写入（与主写同事务保证一致）。
func recordAuditTx(tx *gorm.DB, action, object string, detail map[string]interface{}) error {
	detail["kind"] = action
	detail["object"] = object
	b, _ := json.Marshal(detail)
	if len(b) > 512 {
		b = b[:512]
	}
	return tx.Create(&store.ServiceEvent{ServiceID: 0, Reason: string(b)}).Error
}
