// 运维侧治理端点（M1）：工作空间 / 用户 / 成员 / 纳管 / 审计。
// 鉴权：JWT-admin 或 AUTH_TOKEN（router.go 的 RequireAdmin 中间件统一裁决）。
package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
)

// registerAdmin 挂载 admin 端点（g 已是 /admin 组，M0 路由分层处建好鉴权）。
func registerAdmin(g *gin.RouterGroup, core *service.Core) {
	ad := g

	ws := ad.Group("/workspaces")
	{
		ws.GET("", func(c *gin.Context) {
			list, err := core.AdminListWorkspaces()
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, list)
		})
		ws.POST("", func(c *gin.Context) {
			var in service.CreateWorkspaceInput
			if err := c.ShouldBindJSON(&in); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			w, err := core.AdminCreateWorkspace(in)
			if err != nil {
				mapError(c, err)
				return
			}
			c.JSON(http.StatusCreated, respOK(w))
		})
		ws.GET("/:id", func(c *gin.Context) {
			w, err := core.AdminGetWorkspace(parseUint(c.Param("id")))
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, w)
		})
		ws.PATCH("/:id", func(c *gin.Context) {
			var in service.PatchWorkspaceInput
			if err := c.ShouldBindJSON(&in); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			w, err := core.AdminPatchWorkspace(parseUint(c.Param("id")), in)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, w)
		})
		ws.DELETE("/:id", func(c *gin.Context) {
			if err := core.AdminDeleteWorkspace(parseUint(c.Param("id"))); err != nil {
				mapError(c, err)
				return
			}
			OK(c, nil)
		})
		ws.GET("/:id/agents", func(c *gin.Context) {
			id := parseUint(c.Param("id"))
			// 跨空间治理视图：全状态列表（含 default 空间的待纳管形态）
			list, err := core.List(c.Query("status"), c.Query("keyword"), 0, int64(id))
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, list)
		})
		ws.POST("/:id/bind", func(c *gin.Context) {
			var body struct {
				ServiceID uint `json:"serviceId"`
			}
			if err := c.ShouldBindJSON(&body); err != nil || body.ServiceID == 0 {
				Fail(c, http.StatusBadRequest, "serviceId is required")
				return
			}
			svc, err := core.BindService(parseUint(c.Param("id")), body.ServiceID)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, svc)
		})
		// 成员管理（运维侧直接维护成员，M1 §2.3）
		ws.GET("/:id/members", func(c *gin.Context) {
			list, err := core.ListMembers(parseUint(c.Param("id")))
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, list)
		})
		ws.POST("/:id/members", func(c *gin.Context) {
			var body struct {
				UserID uint   `json:"userId"`
				Role   string `json:"role"`
			}
			if err := c.ShouldBindJSON(&body); err != nil || body.UserID == 0 {
				Fail(c, http.StatusBadRequest, "userId is required")
				return
			}
			if body.Role == "" {
				body.Role = "member"
			}
			m, err := core.AddMember(parseUint(c.Param("id")), body.UserID, body.Role)
			if err != nil {
				mapError(c, err)
				return
			}
			c.JSON(http.StatusCreated, respOK(m))
		})
		ws.PATCH("/:id/members/:uid", func(c *gin.Context) {
			var body struct {
				Role string `json:"role"`
			}
			if err := c.ShouldBindJSON(&body); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			if err := core.UpdateMemberRole(parseUint(c.Param("id")), parseUint(c.Param("uid")), body.Role); err != nil {
				mapError(c, err)
				return
			}
			OK(c, nil)
		})
		ws.DELETE("/:id/members/:uid", func(c *gin.Context) {
			if err := core.RemoveMember(parseUint(c.Param("id")), parseUint(c.Param("uid"))); err != nil {
				mapError(c, err)
				return
			}
			OK(c, nil)
		})
	}

	us := ad.Group("/users")
	{
		us.GET("", func(c *gin.Context) {
			list, err := core.AdminListUsers()
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, list)
		})
		us.POST("", func(c *gin.Context) {
			var body struct {
				Username    string `json:"username"`
				Password    string `json:"password"`
				DisplayName string `json:"displayName"`
				Role        string `json:"role"`
			}
			if err := c.ShouldBindJSON(&body); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			if body.Role == "" {
				body.Role = "user"
			}
			u, err := core.AdminCreateUser(body.Username, body.Password, body.DisplayName, body.Role)
			if err != nil {
				mapError(c, err)
				return
			}
			c.JSON(http.StatusCreated, respOK(service.ViewOf(u)))
		})
		us.PATCH("/:id", func(c *gin.Context) {
			var body struct {
				Role     *string `json:"role"`
				Status   *string `json:"status"`
				Password *string `json:"password"`
			}
			if err := c.ShouldBindJSON(&body); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			u, err := core.AdminPatchUser(parseUint(c.Param("id")), body.Role, body.Status, body.Password)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, service.ViewOf(u))
		})
	}

	ad.GET("/audit-logs", func(c *gin.Context) {
		page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
		size, _ := strconv.Atoi(c.DefaultQuery("size", "50"))
		items, total, err := core.AuditLogs(page, size)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, gin.H{"items": items, "total": total})
	})
}

// respOK 201 响应体（统一包络）。
func respOK(data interface{}) map[string]interface{} {
	return map[string]interface{}{"code": 0, "message": "ok", "data": data}
}

func parseUint(s string) uint {
	v, _ := strconv.ParseUint(s, 10, 64)
	return uint(v)
}
