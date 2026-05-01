package handler

import (
	"net/http"
	"strconv"

	"agent-manager/backend/internal/model"
	"agent-manager/backend/internal/service"

	"github.com/gin-gonic/gin"
)

type AgentHandler struct {
	svc *service.AgentService
}

func NewAgentHandler(svc *service.AgentService) *AgentHandler {
	return &AgentHandler{svc: svc}
}

func (h *AgentHandler) Register(r *gin.RouterGroup) {
	r.POST("/agents", h.Create)
	r.GET("/agents", h.List)
	r.GET("/agents/:id", h.Get)
	r.PUT("/agents/:id", h.Update)
	r.DELETE("/agents/:id", h.Delete)
	r.POST("/agents/:id/generate", h.GenerateCode)
	r.GET("/agents/:id/code", h.GetCode)
	r.GET("/agents/:id/deployments", h.GetDeployments)
}

func (h *AgentHandler) Create(c *gin.Context) {
	var req struct {
		Config     string `json:"config" binding:"required"`
		ConfigType string `json:"config_type"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	ct := model.ConfigJSON
	if req.ConfigType == "yaml" {
		ct = model.ConfigYAML
	} else if req.ConfigType == "form" {
		ct = model.ConfigForm
	}
	agent, err := h.svc.Create(req.Config, ct)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, agent)
}

func (h *AgentHandler) List(c *gin.Context) {
	status := c.Query("status")
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	agents, total, err := h.svc.List(status, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": agents, "total": total})
}

func (h *AgentHandler) Get(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	agent, err := h.svc.GetByID(uint(id))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "agent not found"})
		return
	}
	c.JSON(http.StatusOK, agent)
}

func (h *AgentHandler) Update(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	var req struct {
		Config string `json:"config" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	agent, err := h.svc.Update(uint(id), req.Config)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, agent)
}

func (h *AgentHandler) Delete(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	if err := h.svc.Delete(uint(id)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}

func (h *AgentHandler) GenerateCode(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	gen, err := h.svc.GenerateCode(uint(id))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gen)
}

func (h *AgentHandler) GetCode(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	gen, code, err := h.svc.GetCode(uint(id))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "code not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"generation": gen, "code": code})
}

func (h *AgentHandler) GetDeployments(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	deps, err := h.svc.GetDeployments(uint(id))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": deps})
}
