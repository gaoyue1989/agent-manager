// 跨集群导出端点（设计 §9 → 实际挂载，契约 §4）：workspace 作用域导出链路 +
// 导出单状态/交付物下载/人工推进。
// 鉴权分层：registerWorkspaceExport 挂 cg 组（JWTAuth + resolveWorkspace，成员可读写——
// 导出编辑不限 owner/admin，契约 §4.1）；registerExportOrders 挂 /console/exports 组
// （仅 JWTAuth，无 :ws 段故不挂 resolveWorkspace——挂了会因 c.Param("ws") 为空恒 404），
// 归属与推进权限在 service 层按 order.workspace_id + 成员关系裁决（契约 §4.2）。
package handler

import (
	"bytes"
	"net/http"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
)

// registerWorkspaceExport 挂载 workspace 作用域导出端点（registerConsole 函数体末尾装配，
// 在 registerWorkspaceConfig 之后，契约 §2.2/§2.4 串行分段）。
func registerWorkspaceExport(cg *gin.RouterGroup, core *service.Core) {
	ex := cg.Group("/workspaces/:ws/agents/:id/export")

	// 导出包编辑器数据：源包 + 草稿叠加视图（AGENTS.md/skills 只读标注）
	ex.GET("/package", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		view, err := core.ExportPackagePreview(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	// 保存单文件文本修改（写编辑草稿，不冻结；成员可写）
	ex.PUT("/package/file", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		var body struct {
			Path    string `json:"path"`
			Content string `json:"content"`
		}
		if err := c.ShouldBindJSON(&body); err != nil || body.Path == "" {
			Fail(c, http.StatusBadRequest, "path is required")
			return
		}
		view, err := core.SaveExportPackageFile(wsID, parseUint(c.Param("id")), c.GetString("username"), body.Path, body.Content)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	// 打发布标记：冻结当前快照（POST 无必填 body，沿 republish 可选 body 语义）
	ex.POST("/package/mark", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		view, err := core.FreezeExportMark(wsID, parseUint(c.Param("id")), c.GetString("username"))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	// 发布标记列表
	ex.GET("/package/marks", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		list, err := core.ListExportMarks(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, list)
	})
	// 预填：源包/现 env（掩码）/空间默认值（掩码）/镜像白名单/派生键预览
	ex.GET("/preview", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		view, err := core.ExportPreview(wsID, parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, view)
	})
	// 创建导出单（异步构建）
	ex.POST("", func(c *gin.Context) {
		wsID, _, err := wsCtx(c)
		if err != nil {
			Fail(c, http.StatusInternalServerError, err.Error())
			return
		}
		var req service.ExportOrderRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		order, err := core.CreateExportOrder(c.GetString("username"), wsID, parseUint(c.Param("id")), &req)
		if err != nil {
			mapError(c, err)
			return
		}
		c.JSON(http.StatusCreated, respOK(order))
	})
}

// registerExportOrders 挂载导出单端点（eg = /console/exports，仅 JWTAuth；router.go 装配，
// 契约 §2.4）。归属校验与推进权限（owner/admin）由 service 层裁决。
func registerExportOrders(eg *gin.RouterGroup, core *service.Core) {
	eg.GET("/:id", func(c *gin.Context) {
		order, err := core.GetExportOrder(c.GetUint("uid"), parseUint(c.Param("id")))
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, order)
	})
	// values.yaml 下载：ValuesYAML 不进任何 JSON 响应（模型 json:"-"），仅此文件端点交付；
	// 下载写审计日志 + manifest audit.downloadedBy（契约 §5.8）
	eg.GET("/:id/values.yaml", func(c *gin.Context) {
		order, err := core.ExportOrderDownload(c.GetUint("uid"), parseUint(c.Param("id")), c.GetString("username"), "values.yaml")
		if err != nil {
			mapError(c, err)
			return
		}
		c.Header("Content-Disposition", `attachment; filename="values.yaml"`)
		c.DataFromReader(http.StatusOK, int64(len(order.ValuesYAML)), "application/octet-stream",
			bytes.NewReader([]byte(order.ValuesYAML)), nil)
	})
	eg.GET("/:id/manifest.json", func(c *gin.Context) {
		order, err := core.ExportOrderDownload(c.GetUint("uid"), parseUint(c.Param("id")), c.GetString("username"), "manifest.json")
		if err != nil {
			mapError(c, err)
			return
		}
		c.Header("Content-Disposition", `attachment; filename="manifest.json"`)
		c.DataFromReader(http.StatusOK, int64(len(order.ManifestJSON)), "application/json",
			bytes.NewReader([]byte(order.ManifestJSON)), nil)
	})
	// 人工推进状态（v1：built→approving→approved→deploying→deployed + 非终态→failed/cancelled）；
	// owner/admin 在 service 层裁决（eg 组无 resolveWorkspace 上下文）
	eg.POST("/:id/status", func(c *gin.Context) {
		var in service.ExportAdvanceInput
		if err := c.ShouldBindJSON(&in); err != nil || in.Status == "" {
			Fail(c, http.StatusBadRequest, "status is required")
			return
		}
		order, err := core.AdvanceExportOrder(c.GetUint("uid"), parseUint(c.Param("id")), c.GetString("username"), &in)
		if err != nil {
			mapError(c, err)
			return
		}
		OK(c, order)
	})
}
