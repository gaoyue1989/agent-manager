// JWT 中间件：复用 internal/authtoken 的 HS256 实现（handler 签发/校验 + service introspect 共用）。
package handler

import (
	"strings"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/authtoken"
)

const sessionCookieName = "oaf_session"

// sessionTTLSeconds 登录态有效期（与 Set-Cookie Max-Age 一致）。
var sessionTTLSeconds = int(authtoken.TTL.Seconds())

// issueToken 为用户签发登录 token。
func issueToken(secret string, uid uint, username, role string) string {
	return authtoken.Issue(secret, uid, username, role)
}

// sessionFromToken 从请求（header Bearer 或 cookie，优先 header）解析身份。
func sessionFromToken(secret string, c *gin.Context) (authtoken.Claims, bool) {
	tok := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
	if tok == "" {
		if ck, err := c.Cookie(sessionCookieName); err == nil {
			tok = ck
		}
	}
	if tok == "" {
		return authtoken.Claims{}, false
	}
	claims, err := authtoken.Parse(secret, tok)
	if err != nil {
		return authtoken.Claims{}, false
	}
	return claims, true
}

// JWTAuth 控制台鉴权：JWT（Authorization Bearer 或 oaf_session cookie）通过后
// c.Set("uid"/"role"/"username")。
func JWTAuth(secret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		claims, ok := sessionFromToken(secret, c)
		if !ok || claims.UID == 0 {
			Fail(c, 401, "unauthorized: login required")
			c.Abort()
			return
		}
		c.Set("uid", claims.UID)
		c.Set("role", claims.Role)
		c.Set("username", claims.Sub)
		c.Next()
	}
}

// RequireAdmin admin 面鉴权：先试 AUTH_TOKEN Bearer（脚本/release-agent 兼容，
// 命中直接放行），否则 JWT 且 role=admin，否则 401/403。
func RequireAdmin(authToken, secret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if authToken != "" && c.GetHeader("Authorization") == "Bearer "+authToken {
			c.Set("uid", uint(0))
			c.Set("role", "admin")
			c.Set("username", "token")
			c.Next()
			return
		}
		claims, ok := sessionFromToken(secret, c)
		if !ok {
			Fail(c, 401, "unauthorized: admin token or login required")
			c.Abort()
			return
		}
		if claims.Role != "admin" {
			Fail(c, 403, "forbidden: admin role required")
			c.Abort()
			return
		}
		c.Set("uid", claims.UID)
		c.Set("role", claims.Role)
		c.Set("username", claims.Sub)
		c.Next()
	}
}
