// 认证端点：/api/v1/auth/login|logout|me（M0 §2.4）。
package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
)

// setSessionCookie 下发 httpOnly 会话 cookie（集群 http 部署不加 Secure——
// https 入口由环境侧终结，加 Secure 反而丢 cookie；内网工具定位，SameSite=Lax 足够）。
func setSessionCookie(c *gin.Context, token string) {
	c.SetCookie(sessionCookieName, token, sessionTTLSeconds, "/", "", false, true)
}

func clearSessionCookie(c *gin.Context) {
	c.SetCookie(sessionCookieName, "", -1, "/", "", false, true)
}

// registerAuth 挂载 /auth 组（login/logout 无鉴权；me 需 JWT）。
func registerAuth(g *gin.RouterGroup, core *service.Core, jwtSecret string) {
	au := g.Group("/auth")
	au.POST("/login", func(c *gin.Context) {
		var body struct {
			Username string `json:"username"`
			Password string `json:"password"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		u, err := core.Authenticate(body.Username, body.Password)
		if err != nil {
			if err == service.ErrUserDisabled {
				Fail(c, http.StatusForbidden, err.Error())
				return
			}
			Fail(c, http.StatusUnauthorized, service.ErrInvalidCredentials.Error())
			return
		}
		token := issueToken(jwtSecret, u.ID, u.Username, u.Role)
		setSessionCookie(c, token)
		view, workspaces, err := core.Me(u)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, gin.H{"token": token, "user": view, "workspaces": workspaces})
	})
	au.POST("/logout", func(c *gin.Context) {
		clearSessionCookie(c) // JWT 无状态：语义 = 前端清 cookie（设计 §5.2 取舍）
		OK(c, nil)
	})
	au.GET("/me", JWTAuth(jwtSecret), func(c *gin.Context) {
		uid := c.GetUint("uid")
		u, err := core.GetUser(uid)
		if err != nil {
			mapError(c, err)
			return
		}
		if u.Status == "disabled" {
			clearSessionCookie(c)
			Fail(c, http.StatusForbidden, "user is disabled")
			return
		}
		view, workspaces, err := core.Me(u)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, gin.H{"user": view, "workspaces": workspaces})
	})
	// 二期 router auth_request 数据面入口（M9）：JWT 或 API Key 双通道校验，
	// 返回 X-User-Id 响应头供 auth_request_set 注入。挂在 auth 组免 JWT 中间件，
	// 由凭据本身裁决（内网 router 子请求调用）。
	au.GET("/introspect", func(c *gin.Context) {
		core.Introspect(c.Writer, c.Request)
	})
}
