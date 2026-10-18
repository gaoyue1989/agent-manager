// Package authtoken 平台登录 token（HS256 JWT，标准库实现，不引第三方依赖）。
// handler（签发/中间件）与 service（introspect 数据面校验）共用同一格式约定。
package authtoken

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

var ErrInvalid = errors.New("invalid session token")

const TTL = 12 * time.Hour

type Claims struct {
	Sub      string `json:"sub"`
	UID      uint   `json:"uid"`
	Role     string `json:"role"`
	Exp      int64  `json:"exp"`
	IssuedAt int64  `json:"iat"`
}

// Sign HS256 签发（header.payload.signature，base64url 无填充）。
func Sign(secret string, c Claims) string {
	if c.Exp == 0 {
		c.Exp = time.Now().Add(TTL).Unix()
	}
	if c.IssuedAt == 0 {
		c.IssuedAt = time.Now().Unix()
	}
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payload, _ := json.Marshal(c)
	body := base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(header + "." + body))
	return fmt.Sprintf("%s.%s.%s", header, body, base64.RawURLEncoding.EncodeToString(mac.Sum(nil)))
}

// Issue 按用户身份签发登录 token。
func Issue(secret string, uid uint, username, role string) string {
	return Sign(secret, Claims{Sub: username, UID: uid, Role: role})
}

// Parse 校验签名与过期并还原 claims。
func Parse(secret, token string) (Claims, error) {
	var c Claims
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return c, ErrInvalid
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(parts[0] + "." + parts[1]))
	want := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(want), []byte(parts[2])) {
		return c, ErrInvalid
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return c, ErrInvalid
	}
	if err := json.Unmarshal(payload, &c); err != nil {
		return c, ErrInvalid
	}
	if c.Exp != 0 && time.Now().Unix() >= c.Exp {
		return c, ErrInvalid
	}
	return c, nil
}
