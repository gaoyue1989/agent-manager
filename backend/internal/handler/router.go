package handler

import (
	"bytes"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"path"
	"strconv"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
)

// Register 挂载全部 REST 路由。路由分层（设计 §5.3，M0 §2.5）：
//
//	/api/v1/auth/*      无鉴权（login/logout/introspect）；me 需 JWT
//	/api/v1/console/*   JWT（任意角色），workspace 权限由 membership 裁决
//	/api/v1/admin/*     JWT-admin 或 AUTH_TOKEN（脚本/release-agent 兼容）
//	/api/v1/{services,packages,images,platform-config}  存量运维 API（AUTH_TOKEN 可选，现状不动）
//	/mcp                AUTH_TOKEN（main.go 挂载）
//
// gin 语义：auth/console/admin 三个子组必须在 legacy `g.Use(Auth)` 之前创建，
// 否则会继承该中间件（分层单测锁住：设 AUTH_TOKEN 时 /auth/login 可匿名访问、
// 带 JWT 的 /console/* 不被 AUTH_TOKEN 拦截）。
func Register(r *gin.Engine, core *service.Core, images []struct{ Image, Label string }, authToken, jwtSecret string) {
	r.Use(CORS())
	g := r.Group("/api/v1")

	// 1) 公开/认证端点（不继承 legacy Auth）
	registerAuth(g, core, jwtSecret)

	// 2) console / admin 新面（先建组，后挂 legacy 中间件不影响它们）
	cg := g.Group("/console", JWTAuth(jwtSecret))
	registerConsole(cg, core, images)
	ag := g.Group("/admin", RequireAdmin(authToken, jwtSecret))
	registerAdmin(ag, core)

	// 3) legacy 存量（行为与今天完全一致；AUTH_TOKEN 可选语义不变）
	if authToken != "" {
		g.Use(Auth(authToken))
	}

	pk := g.Group("/packages")
	{
		pk.POST("", func(c *gin.Context) {
			fh, err := c.FormFile("file")
			if err != nil {
				Fail(c, http.StatusBadRequest, "multipart field 'file' is required: "+err.Error())
				return
			}
			f, err := fh.Open()
			if err != nil {
				mapError(c, err)
				return
			}
			defer f.Close()
			rec, err := core.Packages.Upload(fh.Filename, f)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, rec)
		})
		pk.GET("", func(c *gin.Context) {
			list, err := core.Packages.List(c.Query("keyword"), c.Query("slug"))
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, list)
		})
		pk.GET("/:id", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			rec, err := core.Packages.Get(uint(id))
			if err != nil {
				mapError(c, err)
				return
			}
			tree, err := core.Packages.Tree(rec)
			if err == nil {
				c.Set("tree", tree)
			}
			agentsMD, _ := core.Packages.ReadFile(rec, "AGENTS.md")
			OK(c, gin.H{"package": rec, "tree": tree, "agentsMd": string(agentsMD)})
		})
		pk.GET("/:id/files", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			rec, err := core.Packages.Get(uint(id))
			if err != nil {
				mapError(c, err)
				return
			}
			sub := c.Query("path")
			if sub == "" {
				Fail(c, http.StatusBadRequest, "query param 'path' is required")
				return
			}
			fc, err := core.Packages.FileContent(rec, sub)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, fc)
		})
		pk.GET("/:id/files/download", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			rec, err := core.Packages.Get(uint(id))
			if err != nil {
				mapError(c, err)
				return
			}
			sub := c.Query("path")
			if sub == "" {
				Fail(c, http.StatusBadRequest, "query param 'path' is required")
				return
			}
			data, err := core.Packages.ReadFile(rec, sub)
			if err != nil {
				if errors.Is(err, fs.ErrNotExist) {
					err = service.ErrNotFound
				}
				mapError(c, err)
				return
			}
			c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=%q", path.Base(sub)))
			c.DataFromReader(http.StatusOK, int64(len(data)), "application/octet-stream",
				bytes.NewReader(data), nil)
		})
		pk.GET("/:id/download", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			rec, err := core.Packages.Get(uint(id))
			if err != nil {
				mapError(c, err)
				return
			}
			data, err := core.Packages.Zip(rec)
			if err != nil {
				mapError(c, err)
				return
			}
			c.Header("Content-Disposition",
				fmt.Sprintf("attachment; filename=%q", fmt.Sprintf("%s-%s.zip", rec.Slug, rec.Version)))
			c.DataFromReader(http.StatusOK, int64(len(data)), "application/zip",
				bytes.NewReader(data), nil)
		})
		pk.POST("/:id/versions", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			base, err := core.Packages.Get(uint(id))
			if err != nil {
				mapError(c, err)
				return
			}
			var req service.VersionRequest
			if err := c.ShouldBindJSON(&req); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			rec, warnings, err := core.Packages.CreateVersion(base, req)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, gin.H{"package": rec, "warnings": warnings})
		})
		// 模板标记开关（M2 §2.3：用户侧模板页数据源维护入口）
		pk.PATCH("/:id/template", func(c *gin.Context) {
			var body struct {
				IsTemplate *bool `json:"isTemplate"`
			}
			if err := c.ShouldBindJSON(&body); err != nil || body.IsTemplate == nil {
				Fail(c, http.StatusBadRequest, "isTemplate is required")
				return
			}
			if err := core.Packages.SetTemplate(parseUint(c.Param("id")), *body.IsTemplate); err != nil {
				mapError(c, err)
				return
			}
			OK(c, nil)
		})
		pk.DELETE("/:id", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			if err := core.Packages.Delete(uint(id)); err != nil {
				mapError(c, err)
				return
			}
			OK(c, nil)
		})
	}

	sv := g.Group("/services")
	{
		sv.POST("", func(c *gin.Context) {
			var req service.PublishRequest
			if err := c.ShouldBindJSON(&req); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			svc, err := core.Publish(req)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, svc)
		})
		sv.GET("", func(c *gin.Context) {
			pid, _ := strconv.ParseUint(c.Query("packageId"), 10, 64)
			wid, _ := strconv.ParseInt(c.Query("workspaceId"), 10, 64)
			list, err := core.List(c.Query("status"), c.Query("keyword"), uint(pid), wid)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, list)
		})
		sv.GET("/:id", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			detail, err := core.GetDetail(uint(id))
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, detail)
		})
		sv.PATCH("/:id/env", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			var body struct {
				Env        map[string]string `json:"env"`
				SecretKeys []string          `json:"secretKeys"`
			}
			if err := c.ShouldBindJSON(&body); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			svc, err := core.UpdateEnv(uint(id), body.Env, body.SecretKeys)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, svc)
		})
		sv.POST("/:id/republish", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			var opt service.RepublishOptions
			if c.Request.ContentLength > 0 {
				if err := c.ShouldBindJSON(&opt); err != nil {
					Fail(c, http.StatusBadRequest, err.Error())
					return
				}
			}
			out, err := core.Republish(uint(id), opt)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, out)
		})
		action := func(name string, fn func(*service.Core, uint) (interface{}, error)) {
			sv.POST("/:id/"+name, func(c *gin.Context) {
				id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
				out, err := fn(core, uint(id))
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
		sv.DELETE("/:id", func(c *gin.Context) {
			id, _ := strconv.ParseUint(c.Param("id"), 10, 64)
			if err := core.Delete(uint(id)); err != nil {
				mapError(c, err)
				return
			}
			OK(c, nil)
		})
	}

	g.GET("/images", func(c *gin.Context) {
		type img struct{ Image, Label string }
		OK(c, images)
	})

	// 平台默认配置：设置页读写 + 表单默认填入数据源。
	// R3 语义：默认配置仅作为发布/编辑 env 时的表单预填，不经 envFrom 注入，
	// 不影响任何已发布服务（docs/design/platform-default-config-secret-design.md）
	pc := g.Group("/platform-config")
	{
		pc.GET("", func(c *gin.Context) {
			view, err := core.GetPlatformConfig()
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, view)
		})
		pc.GET("/defaults", func(c *gin.Context) {
			values, err := core.GetPlatformDefaults()
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, gin.H{"values": values})
		})
		pc.PUT("", func(c *gin.Context) {
			var body struct {
				Values map[string]string `json:"values"`
			}
			if err := c.ShouldBindJSON(&body); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			view, err := core.UpdatePlatformConfig(body.Values)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, view)
		})
	}

	r.GET("/healthz", func(c *gin.Context) {
		OK(c, gin.H{"status": "up"})
	})
}
