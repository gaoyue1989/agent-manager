package handler

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"

	"agent-manager/backend/internal/model"
	"agent-manager/backend/internal/service"

	"github.com/gin-gonic/gin"
	"gopkg.in/yaml.v3"
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

	r.POST("/skills/upload", h.UploadSkills)
	r.GET("/skills/:agent_id", h.ListSkills)
	r.DELETE("/skills/:agent_id/:skill_name", h.DeleteSkill)
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
		configJSON, err := yamlToJSON(req.Config)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid yaml: " + err.Error()})
			return
		}
		req.Config = configJSON
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

func yamlToJSON(yamlStr string) (string, error) {
	var data interface{}
	if err := yaml.Unmarshal([]byte(yamlStr), &data); err != nil {
		return "", err
	}
	data = convertYAMLMap(data)
	jsonBytes, err := json.Marshal(data)
	if err != nil {
		return "", err
	}
	return string(jsonBytes), nil
}

func convertYAMLMap(in interface{}) interface{} {
	switch v := in.(type) {
	case map[string]interface{}:
		out := make(map[string]interface{})
		for k, val := range v {
			out[k] = convertYAMLMap(val)
		}
		return out
	case []interface{}:
		for i, val := range v {
			v[i] = convertYAMLMap(val)
		}
		return v
	default:
		return v
	}
}

type skillMetadata struct {
	Name          string            `yaml:"name" json:"name"`
	Description   string            `yaml:"description" json:"description"`
	License       string            `yaml:"license" json:"license,omitempty"`
	Compatibility string            `yaml:"compatibility" json:"compatibility,omitempty"`
	AllowedTools  string            `yaml:"allowed-tools" json:"allowed_tools"`
	Metadata      map[string]string `yaml:"metadata" json:"metadata,omitempty"`
}

func (h *AgentHandler) UploadSkills(c *gin.Context) {
	agentIDStr := c.Query("agent_id")
	if agentIDStr == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "agent_id is required"})
		return
	}
	agentID, err := strconv.ParseUint(agentIDStr, 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid agent_id"})
		return
	}

	file, header, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "file is required"})
		return
	}
	defer file.Close()

	if !strings.HasSuffix(strings.ToLower(header.Filename), ".zip") {
		c.JSON(http.StatusBadRequest, gin.H{"error": "only .zip files are supported"})
		return
	}

	zipData, err := io.ReadAll(file)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "read file failed"})
		return
	}

	reader, err := zip.NewReader(bytes.NewReader(zipData), int64(len(zipData)))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid zip file: " + err.Error()})
		return
	}

	skills := make([]map[string]interface{}, 0)
	skillFiles := make(map[string][]byte)

	for _, f := range reader.File {
		if f.FileInfo().IsDir() {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			continue
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			continue
		}

		if strings.HasSuffix(strings.ToUpper(f.Name), "SKILL.MD") {
			meta := parseSkillMarkdown(data, f.Name)
			if meta != nil {
				skills = append(skills, meta)
			}
		}

		skillFiles[f.Name] = data
	}

	if len(skills) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "no valid SKILL.md found in zip"})
		return
	}

	storedSkills, err := h.svc.SaveSkills(uint(agentID), skills, skillFiles)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save skills failed: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"skills": storedSkills})
}

func parseSkillMarkdown(data []byte, zipPath string) map[string]interface{} {
	content := string(data)
	parts := strings.SplitN(content, "---", 3)
	if len(parts) < 3 {
		return nil
	}

	var meta skillMetadata
	if err := yaml.Unmarshal([]byte(parts[1]), &meta); err != nil {
		return nil
	}

	if meta.Name == "" || meta.Description == "" {
		return nil
	}

	skillDir := filepath.Dir(zipPath)
	if strings.HasPrefix(skillDir, "./") {
		skillDir = skillDir[2:]
	}
	if skillDir == "." {
		skillDir = meta.Name
	}

	tools := []string{}
	if meta.AllowedTools != "" {
		for _, t := range strings.Fields(meta.AllowedTools) {
			t = strings.TrimSuffix(t, ",")
			if t != "" {
				tools = append(tools, t)
			}
		}
	}

	return map[string]interface{}{
		"name":          meta.Name,
		"description":   meta.Description,
		"path":          fmt.Sprintf("skills/%s", skillDir),
		"license":       meta.License,
		"compatibility": meta.Compatibility,
		"allowed_tools": tools,
		"metadata":      meta.Metadata,
	}
}

func (h *AgentHandler) ListSkills(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("agent_id"), 10, 32)
	skills, err := h.svc.ListSkills(uint(id))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"skills": skills})
}

func (h *AgentHandler) DeleteSkill(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("agent_id"), 10, 32)
	skillName := c.Param("skill_name")
	if err := h.svc.DeleteSkill(uint(id), skillName); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
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
	result, err := h.svc.DeleteWithCleanup(uint(id))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted", "cleanup": result})
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

func (h *AgentHandler) GenerateCodeWithBaseImage(c *gin.Context) {
	id, _ := strconv.ParseUint(c.Param("id"), 10, 32)
	var req struct {
		BaseImage string `json:"base_image"`
	}
	c.ShouldBindJSON(&req)
	gen, err := h.svc.GenerateCodeWithBaseImage(uint(id), req.BaseImage)
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
