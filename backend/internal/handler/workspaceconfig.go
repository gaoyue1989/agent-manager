// 工作空间配置管理端点（跨集群导出设计 §9/§10，2026-10）：runtime-defaults 读写 +
// config 组骨架（templates/:group、registry、mcp-connectors）。
// 鉴权分层：cg 组已带 JWTAuth + resolveWorkspace（console.go 注入 ws/wsRole）；
// GET = workspace 成员，PUT = owner/admin（requireWsRole，契约 §4.1）。
// 文件级契约见 docs/implementation/cross-cluster-export-contract.md §2.1。
package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
)

// registerWorkspaceConfig 挂载 workspace 配置管理端点（registerConsole 函数体末尾装配，契约 §2.4）。
func registerWorkspaceConfig(cg *gin.RouterGroup, core *service.Core) {
	cc := cg.Group("/workspaces/:ws/config")

	// ---- 运行时默认配置（设计 §9：分类键值，敏感掩码；导出页 L1 锁定来源）----
	cc.GET("/runtime-defaults", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		view, err := core.GetRuntimeDefaults(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	cc.PUT("/runtime-defaults", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		var body struct {
			Values map[string]string `json:"values"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		// UpdatedBy 记 JWT claims username（jwt.go 注入，契约 §1.1 CreatedBy 同口径）
		view, err := core.UpdateRuntimeDefaults(wsID, c.GetString("username"), body.Values)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})

	// ---- config 组骨架（契约 §9-D4：v1 键空间经 runtime-defaults 单端点承载，
	//      本组端点为其分组视图/受限写入口）----

	// 模型 / 沙箱参数模板（group ∈ llm|sandbox，复用 platformconfig/template.go 驱动）
	cc.GET("/templates/:group", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		fields, err := core.GetRuntimeTemplateGroup(wsID, c.Param("group"))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, fields)
	})
	cc.PUT("/templates/:group", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		var body struct {
			Values map[string]string `json:"values"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.UpdateRuntimeTemplateGroup(wsID, c.GetString("username"), c.Param("group"), body.Values)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})

	// 仓库 / 拉取凭证 / 路由 / 镜像白名单（registry|routing|runtime 分类键）
	cc.GET("/registry", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		view, err := core.GetRuntimeRegistryConfig(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	cc.PUT("/registry", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		var body struct {
			Values map[string]string `json:"values"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		view, err := core.UpdateRuntimeRegistryConfig(wsID, c.GetString("username"), body.Values)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})

	// MCP 连接器模板：读 = 既有连接器模板列表（tools CRUD 同源；auth_token 列 json:"-" 不直出）；
	// 写 v1 未实现（契约 §9-D4：连接器模板无 v1 键空间，仍经 /workspaces/:ws/tools 管理，v2 再拆端点）
	cc.GET("/mcp-connectors", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		list, err := core.ListMcpConnectors(wsID)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	cc.PUT("/mcp-connectors", func(c *gin.Context) {
		if !requireWsRole(c) {
			return
		}
		Fail(c, http.StatusNotImplemented,
			"mcp-connectors template write is not implemented in v1; manage connectors via /workspaces/:ws/tools")
	})
}
