package handler

import (
	"net/http"
	"strconv"

	"agent-manager/backend/internal/service"

	"github.com/gin-gonic/gin"
)

type DeployHandler struct {
	svc *service.DeployService
}

func NewDeployHandler(svc *service.DeployService) *DeployHandler {
	return &DeployHandler{svc: svc}
}

func (h *DeployHandler) Register(r *gin.RouterGroup) {
	r.POST("/agents/:id/build", h.Build)
	r.POST("/agents/:id/deploy", h.Deploy)
	r.POST("/agents/:id/publish", h.Publish)
	r.POST("/agents/:id/unpublish", h.Unpublish)
	r.GET("/agents/:id/image-info", h.ImageInfo)
	r.GET("/agents/:id/pod-status", h.PodStatus)
	r.POST("/agents/:id/chat", h.Chat)
}

func parseID(c *gin.Context) uint {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	return uint(id)
}

func (h *DeployHandler) Build(c *gin.Context) {
	build, err := h.svc.BuildImage(parseID(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, build)
}

func (h *DeployHandler) Deploy(c *gin.Context) {
	dep, err := h.svc.Deploy(parseID(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, dep)
}

func (h *DeployHandler) Publish(c *gin.Context) {
	dep, err := h.svc.Publish(parseID(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, dep)
}

func (h *DeployHandler) Unpublish(c *gin.Context) {
	dep, err := h.svc.Unpublish(parseID(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, dep)
}

func (h *DeployHandler) ImageInfo(c *gin.Context) {
	info, err := h.svc.GetImageInfo(parseID(c))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, info)
}

func (h *DeployHandler) PodStatus(c *gin.Context) {
	status, err := h.svc.GetPodStatus(parseID(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, status)
}

func (h *DeployHandler) Chat(c *gin.Context) {
	var req struct {
		Message string              `json:"message" binding:"required"`
		History []map[string]string `json:"history"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.svc.ChatWithAgent(parseID(c), req.Message, req.History)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}
