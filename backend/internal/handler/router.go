package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/internal/service"
)

// Register 挂载全部 REST 路由（/api/v1）。
func Register(r *gin.Engine, core *service.Core, images []struct{ Image, Label string }, authToken string) {
	r.Use(CORS())
	g := r.Group("/api/v1")
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
			list, err := core.Packages.List(c.Query("keyword"))
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
			list, err := core.List(c.Query("status"), c.Query("keyword"))
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
				Env map[string]string `json:"env"`
			}
			if err := c.ShouldBindJSON(&body); err != nil {
				Fail(c, http.StatusBadRequest, err.Error())
				return
			}
			svc, err := core.UpdateEnv(uint(id), body.Env)
			if err != nil {
				mapError(c, err)
				return
			}
			OK(c, svc)
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

	r.GET("/healthz", func(c *gin.Context) {
		OK(c, gin.H{"status": "up"})
	})
}
