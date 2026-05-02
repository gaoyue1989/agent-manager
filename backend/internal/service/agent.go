package service

import (
	"encoding/json"
	"fmt"
	"strings"

	"agent-manager/backend/internal/model"
	"agent-manager/backend/internal/minio"
	"agent-manager/backend/internal/codegen"

	"gorm.io/gorm"
)

type AgentService struct {
	db      *gorm.DB
	storage *minio.Storage
	codegen *codegen.Runner
}

func NewAgentService(db *gorm.DB, storage *minio.Storage, cg *codegen.Runner) *AgentService {
	return &AgentService{db: db, storage: storage, codegen: cg}
}

func (s *AgentService) Create(configJSON string, configType model.ConfigType) (*model.Agent, error) {
	agent := &model.Agent{
		Name:       extractName(configJSON),
		Config:     configJSON,
		ConfigType: configType,
		Status:     model.StatusDraft,
		Version:    1,
	}
	if err := s.db.Create(agent).Error; err != nil {
		return nil, err
	}
	return agent, nil
}

func (s *AgentService) GetByID(id uint) (*model.Agent, error) {
	var agent model.Agent
	if err := s.db.First(&agent, id).Error; err != nil {
		return nil, err
	}
	return &agent, nil
}

func (s *AgentService) List(status string, offset, limit int) ([]model.Agent, int64, error) {
	var agents []model.Agent
	var total int64
	q := s.db.Model(&model.Agent{})
	if status != "" {
		q = q.Where("status = ?", status)
	}
	q.Count(&total)
	if err := q.Order("updated_at DESC").Offset(offset).Limit(limit).Find(&agents).Error; err != nil {
		return nil, 0, err
	}
	return agents, total, nil
}

func (s *AgentService) Update(id uint, configJSON string) (*model.Agent, error) {
	agent, err := s.GetByID(id)
	if err != nil {
		return nil, err
	}
	agent.Config = configJSON
	agent.Version++
	if err := s.db.Save(agent).Error; err != nil {
		return nil, err
	}
	return agent, nil
}

func (s *AgentService) Delete(id uint) error {
	return s.db.Delete(&model.Agent{}, id).Error
}

func (s *AgentService) GenerateCode(id uint) (*model.CodeGeneration, error) {
	agent, err := s.GetByID(id)
	if err != nil {
		return nil, err
	}

	gen := &model.CodeGeneration{
		AgentID: agent.ID,
		Version: agent.Version,
		Status:  model.GenRunning,
	}
	s.db.Create(gen)

	var cfg map[string]interface{}
	if err := json.Unmarshal([]byte(agent.Config), &cfg); err != nil {
		gen.Status = model.GenFailed
		gen.ErrorMsg = err.Error()
		s.db.Save(gen)
		return gen, err
	}

	prefix := fmt.Sprintf("agents/%d/v%d", agent.ID, agent.Version)
	files, err := s.codegen.RunAndStore(cfg, prefix)
	if err != nil {
		gen.Status = model.GenFailed
		gen.ErrorMsg = err.Error()
		s.db.Save(gen)
		return gen, err
	}

	if path, ok := files["agent.py"]; ok {
		gen.CodePath = path
	}
	if path, ok := files["Dockerfile"]; ok {
		gen.DockerfilePath = path
	}

	gen.Status = model.GenSuccess
	s.db.Save(gen)

	agent.Status = model.StatusGenerated
	s.db.Save(agent)

	return gen, nil
}

func (s *AgentService) GetCode(id uint) (*model.CodeGeneration, string, error) {
	gen, err := s.getLatestGen(id)
	if err != nil {
		return nil, "", err
	}
	code, err := s.storage.GetFile(gen.CodePath)
	return gen, code, err
}

func (s *AgentService) GetDeployments(id uint) ([]model.Deployment, error) {
	var deps []model.Deployment
	err := s.db.Where("agent_id = ?", id).Order("created_at DESC").Find(&deps).Error
	return deps, err
}

func (s *AgentService) GetLatestDeployment(id uint) (*model.Deployment, error) {
	var dep model.Deployment
	err := s.db.Where("agent_id = ?", id).Order("created_at DESC").First(&dep).Error
	return &dep, err
}

func (s *AgentService) getLatestGen(id uint) (*model.CodeGeneration, error) {
	var gen model.CodeGeneration
	err := s.db.Where("agent_id = ?", id).Order("version DESC").First(&gen).Error
	return &gen, err
}

func (s *AgentService) SaveDeployment(dep *model.Deployment) error {
	return s.db.Save(dep).Error
}

func (s *AgentService) CreateDeployment(dep *model.Deployment) error {
	return s.db.Create(dep).Error
}

func extractName(configJSON string) string {
	var cfg map[string]interface{}
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return "unknown"
	}
	if name, ok := cfg["name"].(string); ok {
		return name
	}
	return "unknown"
}

func (s *AgentService) SaveSkills(agentID uint, skillsMeta []map[string]interface{}, skillFiles map[string][]byte) ([]map[string]interface{}, error) {
	prefix := fmt.Sprintf("agents/%d/skills", agentID)

	for zipPath, data := range skillFiles {
		cleanedPath := strings.TrimPrefix(zipPath, "./")
		if cleanedPath == "" {
			continue
		}
		objName := fmt.Sprintf("%s/%s", prefix, cleanedPath)
		if _, err := s.storage.PutFileString(objName, string(data)); err != nil {
			return nil, fmt.Errorf("store skill file %s: %w", zipPath, err)
		}
	}

	for i := range skillsMeta {
		skillsMeta[i]["storage_prefix"] = prefix
	}

	metaJSON, _ := json.Marshal(skillsMeta)
	metaKey := fmt.Sprintf("%s/.metadata.json", prefix)
	s.storage.PutFileString(metaKey, string(metaJSON))

	return skillsMeta, nil
}

func (s *AgentService) ListSkills(agentID uint) ([]map[string]interface{}, error) {
	metaKey := fmt.Sprintf("agents/%d/skills/.metadata.json", agentID)
	data, err := s.storage.GetFile(metaKey)
	if err != nil {
		return []map[string]interface{}{}, nil
	}

	var skills []map[string]interface{}
	if err := json.Unmarshal([]byte(data), &skills); err != nil {
		return []map[string]interface{}{}, nil
	}
	return skills, nil
}

func (s *AgentService) DeleteSkill(agentID uint, skillName string) error {
	prefix := fmt.Sprintf("agents/%d/skills/%s", agentID, skillName)
	files, err := s.storage.ListFiles(prefix)
	if err != nil {
		return err
	}

	for _, file := range files {
		s.storage.DeleteFile(file)
	}

	return nil
}
