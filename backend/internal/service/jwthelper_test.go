package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"
)

// testIssueToken 测试用 HS256 JWT 签发（与 internal/authtoken 同格式；service 层
// ParseSessionJWT 校验走 Core.JWTSecret="test-jwt-secret"）。
func testIssueToken(uid uint, username, role string) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payload, _ := json.Marshal(map[string]interface{}{
		"sub": username, "uid": uid, "role": role,
		"exp": time.Now().Add(time.Hour).Unix(), "iat": time.Now().Unix(),
	})
	body := base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, []byte("test-jwt-secret"))
	mac.Write([]byte(header + "." + body))
	return fmt.Sprintf("%s.%s.%s", header, body, base64.RawURLEncoding.EncodeToString(mac.Sum(nil)))
}
