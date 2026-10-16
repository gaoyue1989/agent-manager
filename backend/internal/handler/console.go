// 用户侧 console 端点（M2–M8）：概览 / Agent 列表与生命周期 / 创建与编辑 /
// 模型连接 / MCP 连接器 / 集成 Agent / API Key / 成员。
// 鉴权：JWT（router.go 的 JWTAuth 组中间件）；workspace 权限由 membership 裁决
// （resolveWorkspace 中间件 → c.Set("ws"/"wsRole")），写操作再过 requireWsRole。
package handler

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
)

// resolveWorkspace :ws 参数裁决（M2 §2.2）：JWT uid → workspace_members 查询；
// 未命中 404（不暴露 403/404 差异）；default 空间仅 admin 可解析。
func resolveWorkspace(core *service.Core) gin.HandlerFunc {
	return func(c *gin.Context) {
		uid := c.GetUint("uid")
		role := c.GetString("role")
		u, err := core.GetUser(uid)
		if err != nil {
			Fail(c, http.StatusUnauthorized, "user not found")
			c.Abort()
			return
		}
		ws, wsRole, err := core.ResolveWorkspace(u, c.Param("ws"))
		if err != nil {
			c.Set("authStatus", 404)
			Fail(c, http.StatusNotFound, "workspace_not_found")
			c.Abort()
			return
		}
		// 角色纠偏：平台 admin 在任意成员空间内按成员角色裁决写权限（owner/admin 可写）
		_ = role
		c.Set("ws", ws.ID)
		c.Set("wsSlug", ws.Slug)
		c.Set("wsRole", wsRole)
		c.Set("wsUser", u)
		c.Next()
	}
}

// wsCtx 取 resolveWorkspace 注入的上下文。
func wsCtx(c *gin.Context) (wsID uint, wsRole string, err error) {
	wsID = c.GetUint("ws")
	if wsID == 0 {
		return 0, "", errors.New("workspace context missing")
	}
	role := c.GetString("wsRole")
	return wsID, role, nil
}

// requireWsRole 写操作裁决：member 403。
func requireWsRole(c *gin.Context) bool {
	_, role, err := wsCtx(c)
	if err != nil {
		Fail(c, http.StatusInternalServerError, err.Error())
		return false
	}
	if err := service.RequireWsRole(role); err != nil {
		Fail(c, http.StatusForbidden, err.Error())
		return false
	}
	return true
}

// mapCreateError 创建/编辑链路错误映射（表单 400 / 冲突 409 / 发布失败 502）。
func mapCreateError(c *gin.Context, err error) {
	if err == nil {
		return
	}
	switch {
	case errors.Is(err, service.ErrInvalidForm), errors.Is(err, service.ErrDuplicateName),
		errors.Is(err, service.ErrImageNotAllowed):
		Fail(c, http.StatusBadRequest, err.Error())
	case errors.Is(err, service.ErrConflict):
		Fail(c, http.StatusConflict, err.Error())
	case errors.Is(err, service.ErrForbidden):
		Fail(c, http.StatusForbidden, err.Error())
	case errors.Is(err, service.ErrNotFound):
		Fail(c, http.StatusNotFound, err.Error())
	default:
		Fail(c, http.StatusBadGateway, "publish failed: "+err.Error())
	}
}

// registerConsole 挂载 console 端点（g 已是 /console 组，带 JWTAuth 中间件）。
func registerConsole(g *gin.RouterGroup, core *service.Core) {
	cg := g
	cg.Use(resolveWorkspace(core))

	// ---- M2：概览 / Agent 列表 / 模板 ----
	cg.GET("/workspaces/:ws/summary", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		view, err := core.ConsoleSummary(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	cg.GET("/workspaces/:ws/agents", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		list, err := core.ConsoleAgents(wsID, c.Query("status"), c.Query("keyword"))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	cg.GET("/workspaces/:ws/templates", func(c *gin.Context) {
		list, err := core.Packages.ListTemplates()
		if err != nil {
			mapError(c, err)
			return
		}
		type tmplView struct {
			service.PackageVersionView
		}
		out := make([]service.PackageVersionView, 0, len(list))
		for _, p := range list {
			out = append(out, service.PackageVersionView{OafPackage: p})
		}
		OK(c, out)
	})

	// ---- M3：创建 / 编辑 / preview-name ----
	cg.POST("/workspaces/:ws/agents", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil || !requireWsRole(c) {
			if err == nil {
				return
			}
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		uid := c.GetUint("uid")
		var form service.AgentForm
		if err := c.ShouldBindJSON(&form); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		svc, pkg, err := core.CreateAgent(wsID, uid, &form)
		if err != nil {
			mapCreateError(c, err)
			return
		}
		c.JSON(http.StatusAccepted, respOK(gin.H{"service": svc, "package": pkg}))
	})
	cg.GET("/workspaces/:ws/agents/preview-name", func(c *gin.Context) {
		// 轻端点：失焦实时预览派生 slug（M3 §3.1）
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		name := c.Query("name")
		agentKey := service.SlugifyAgentKey(name)
		slug := service.PlatformVendorKey() + "/" + agentKey
		var cnt int64
		_ = core.DB.Table("oaf_packages").Where("slug = ? AND workspace_id = ?", slug, wsID).Count(&cnt).Error
		k8sName, conflict := core.PreviewK8sName(name)
		OK(c, gin.H{"agentKey": agentKey, "slug": slug, "k8sName": k8sName, "conflict": conflict || cnt > 0})
	})
	cg.GET("/workspaces/:ws/agents/:id/edit-form", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		view, err := core.EditForm(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	cg.PUT("/workspaces/:ws/agents/:id", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil || !requireWsRole(c) {
			if err == nil {
				return
			}
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		uid := c.GetUint("uid")
		var form service.AgentForm
		if err := c.ShouldBindJSON(&form); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		svc, pkg, err := core.UpdateAgent(wsID, uid, parseUint(c.Param("id")), &form)
		if err != nil {
			mapCreateError(c, err)
			return
		}
		c.JSON(http.StatusAccepted, respOK(gin.H{"service": svc, "package": pkg}))
	})

	// ---- M4：详情 / 动作 / 版本 ----
	cg.GET("/workspaces/:ws/agents/:id", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		detail, err := core.ConsoleAgentDetail(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, detail)
	})
	action := func(name string, fn func(*service.Core, uint) (interface{}, error)) {
		cg.POST("/workspaces/:ws/agents/:id/"+name, func(c *gin.Context) {
			if !requireWsRole(c) {
				return
			}
			wsID, _, err := wsCtx(c)
			if err != nil {
				Fail(c, http.StatusInternalServerError, err.Error())
				return
			}
			id := parseUint(c.Param("id"))
			if _, err := core.ConsoleAgentDetail(wsID, id); err != nil { // 归属校验
				mapError(c, err)
				return
			}
			out, err := fn(core, id)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, out)
		})
	}
	action("publish", func(core *service.Core, id uint) (interface{}, error) {
		return core.StartAgain(id)
	})
	action("unpublish", func(core *service.Core, id uint) (interface{}, error) {
		return core.Unpublish(id)
	})
	action("register", func(core *service.Core, id uint) (interface{}, error) {
		return core.Reregister(id)
	})
	cg.POST("/workspaces/:ws/agents/:id/republish", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		id := parseUint(c.Param("id"))
		if _, err := core.ConsoleAgentDetail(wsID, id); err != nil {
			mapError(c, err)
			return
		}
		var opt service.RepublishOptions
		if c.Request.ContentLength > 0 {
			if err := c.ShouldBindJSON(&opt); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
		}
		out, err := core.Republish(id, opt)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, out)
	})
	cg.DELETE("/workspaces/:ws/agents/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		id := parseUint(c.Param("id"))
		if _, err := core.ConsoleAgentDetail(wsID, id); err != nil {
			mapError(c, err)
			return
		}
		if err := core.Delete(id); err != nil {
			mapError(c, err)
			return
		}
		OK(c, nil)
	})
	cg.GET("/workspaces/:ws/agents/:id/versions", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		list, err := core.ConsoleAgentVersions(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	cg.POST("/workspaces/:ws/agents/:id/versions/:pkgId/upgrade", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		id := parseUint(c.Param("id"))
		if _, err := core.ConsoleAgentDetail(wsID, id); err != nil {
			mapError(c, err)
			return
		}
		pkgID := parseUint(c.Param("pkgId"))
		if _, err := core.ConsoleAgentVersions(wsID, id); err != nil { // 版本归属校验
			mapError(c, err)
			return
		}
		out, err := core.Republish(id, service.RepublishOptions{PackageID: &pkgID})
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, out)
	})

	registerConsoleModels(cg, core)
	registerConsoleTools(cg, core)
	registerConsoleIntegrations(cg, core)
	registerConsoleApiKeys(cg, core)
	registerConsoleMembers(cg, core)
}

// registerConsoleModels M5：模型连接端点。
func registerConsoleModels(cg *gin.RouterGroup, core *service.Core) {
	mc := cg.Group("/workspaces/:ws/model-connections")
	mc.GET("", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		list, err := core.ListModelConnections(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	mc.GET("/usage", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		list, err := core.ModelConnectionUsage(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	mc.GET("/models", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		raw, err := core.AgentModels(wsID, parseUint(c.Query("serviceId")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, raw)
	})
	mc.POST("", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var in service.CreateModelConnInput
		if err := c.ShouldBindJSON(&in); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.CreateModelConnection(wsID, in)
		if err != nil {
			mapError(c, err)
			return
		}
		c.JSON(http.StatusCreated, respOK(view))
	})
	mc.PATCH("/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var in service.PatchModelConnInput
		if err := c.ShouldBindJSON(&in); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.UpdateModelConnection(wsID, parseUint(c.Param("id")), in)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	mc.POST("/:id/test", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		res, err := core.TestModelConnection(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, res)
	})
	mc.POST("/:id/deliver", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var body struct {
			ServiceID uint `json:"serviceId"`
		}
		if err := c.ShouldBindJSON(&body); err != nil || body.ServiceID == 0 {
			Fail(c, http.StatusBadRequest, "serviceId is required")
			return
		}
		d, err := core.DeliverConnectionToService(wsID, parseUint(c.Param("id")), body.ServiceID)
		if err != nil {
			if d != nil {
				// 下发失败：202 返回失败状态行（页面展示 error）
				c.JSON(http.StatusAccepted, respOK(d))
				return
			}
			mapError(c, err)
			return
		}
		c.JSON(http.StatusAccepted, respOK(d))
	})
	mc.DELETE("/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		if err := core.DeleteModelConnection(wsID, parseUint(c.Param("id"))); err != nil {
			mapError(c, err)
			return
		}
		OK(c, nil)
	})
}

// registerConsoleTools M6：MCP 连接器端点。
func registerConsoleTools(cg *gin.RouterGroup, core *service.Core) {
	tc := cg.Group("/workspaces/:ws/tools")
	tc.GET("", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		list, err := core.ListMcpConnectors(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	tc.POST("", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var in service.CreateMcpConnectorInput
		if err := c.ShouldBindJSON(&in); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.CreateMcpConnector(wsID, in)
		if err != nil {
			mapError(c, err)
			return
		}
		c.JSON(http.StatusCreated, respOK(view))
	})
	tc.PATCH("/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var in service.PatchMcpConnectorInput
		if err := c.ShouldBindJSON(&in); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.UpdateMcpConnector(wsID, parseUint(c.Param("id")), in)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	tc.POST("/:id/verify", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		res, err := core.VerifyMcpConnector(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, res)
	})
	tc.DELETE("/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		if err := core.DeleteMcpConnector(wsID, parseUint(c.Param("id"))); err != nil {
			mapError(c, err)
			return
		}
		OK(c, nil)
	})
}

// registerConsoleIntegrations M7：集成 Agent 端点。
func registerConsoleIntegrations(cg *gin.RouterGroup, core *service.Core) {
	ig := cg.Group("/workspaces/:ws/integrations")
	ig.GET("", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		list, err := core.ListIntegrations(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	ig.POST("", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var in service.CreateIntegrationInput
		if err := c.ShouldBindJSON(&in); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.CreateIntegration(wsID, in)
		if err != nil {
			mapError(c, err)
			return
		}
		c.JSON(http.StatusCreated, respOK(view))
	})
	ig.PATCH("/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var in service.PatchIntegrationInput
		if err := c.ShouldBindJSON(&in); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.UpdateIntegration(wsID, parseUint(c.Param("id")), in)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	ig.DELETE("/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		if err := core.DeleteIntegration(wsID, parseUint(c.Param("id"))); err != nil {
			mapError(c, err)
			return
		}
		OK(c, nil)
	})
}

// registerConsoleApiKeys M8：API Key + 可邀请用户。
func registerConsoleApiKeys(cg *gin.RouterGroup, core *service.Core) {
	ak := cg.Group("/workspaces/:ws/api-keys")
	ak.GET("", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		agentID := parseUint(c.Query("agentId"))
		list, err := core.ListApiKeys(wsID, agentID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	ak.POST("", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var in struct {
			Name           string `json:"name"`
			AgentID        *uint  `json:"agentId"`
			ExpiresInDays  int    `json:"expiresInDays"`
		}
		if err := c.ShouldBindJSON(&in); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		rec, plain, err := core.IssueApiKey(service.IssueApiKeyInput{
			Name: in.Name, AgentID: in.AgentID, ExpiresInDays: in.ExpiresInDays,
			WorkspaceID: wsID, UserID: c.GetUint("uid"),
		})
		if err != nil {
			mapError(c, err)
			return
		}
		c.JSON(http.StatusCreated, respOK(gin.H{
			"id": rec.ID, "name": rec.Name, "keyPrefix": rec.KeyPrefix, "plaintext": plain,
		}))
	})
	ak.DELETE("/:id", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		if err := core.RevokeApiKey(wsID, parseUint(c.Param("id"))); err != nil {
			mapError(c, err)
			return
		}
		OK(c, nil)
	})
	cg.GET("/workspaces/:ws/invitable-users", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		list, err := core.InvitableUsers(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
}

// registerConsoleMembers M8：成员管理（owner/admin 写；成员可见列表）。
func registerConsoleMembers(cg *gin.RouterGroup, core *service.Core) {
	mb := cg.Group("/workspaces/:ws/members")
	mb.GET("", func(c *gin.Context) {
		wsID, _, _ := wsCtx(c)
		list, err := core.ListMembers(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	mb.POST("", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
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
		m, err := core.AddMember(wsID, body.UserID, body.Role)
		if err != nil {
			mapError(c, err)
			return
		}
		c.JSON(http.StatusCreated, respOK(m))
	})
	mb.PATCH("/:uid", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		var body struct {
			Role string `json:"role"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		if err := core.UpdateMemberRole(wsID, parseUint(c.Param("uid")), body.Role); err != nil {
			mapError(c, err)
			return
		}
		OK(c, nil)
	})
	mb.DELETE("/:uid", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, _ := wsCtx(c)
		if err := core.RemoveMember(wsID, parseUint(c.Param("uid"))); err != nil {
			mapError(c, err)
			return
		}
		OK(c, nil)
	})
}
