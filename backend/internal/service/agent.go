package service

import (
	"encoding/json"
	"fmt"
	"strings"

	"agent-manager/backend/internal/model"
	"agent-manager/backend/internal/minio"
	"agent-manager/backend/internal/codegen"
	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/docker"

	"gorm.io/gorm"
)

type DeleteResult struct {
	Database      bool     `json:"database"`
	MinIO         bool     `json:"minio"`
	DockerImages  []string `json:"docker_images"`
	K8sSandbox    bool     `json:"k8s_sandbox"`
	K8sDeployment bool     `json:"k8s_deployment"`
	K8sService    bool     `json:"k8s_service"`
	K8sIngress    bool     `json:"k8s_ingress"`
}

type AgentService struct {
	db               *gorm.DB
	storage          *minio.Storage
	codegen          *codegen.Runner
	sandbox          *k8s.SandboxClient
	deploymentClient *k8s.DeploymentClient
	builder          *docker.Builder
	registry         string
	deployMethod     string
}

func NewAgentService(db *gorm.DB, storage *minio.Storage, cg *codegen.Runner) *AgentService {
	return &AgentService{db: db, storage: storage, codegen: cg}
}

func NewAgentServiceWithDeps(db *gorm.DB, storage *minio.Storage, cg *codegen.Runner, sandbox *k8s.SandboxClient, builder *docker.Builder, registry string) *AgentService {
	return &AgentService{db: db, storage: storage, codegen: cg, sandbox: sandbox, builder: builder, registry: registry, deployMethod: "sandbox"}
}

func NewAgentServiceWithDeployMethod(db *gorm.DB, storage *minio.Storage, cg *codegen.Runner, sandbox *k8s.SandboxClient, builder *docker.Builder, registry string, deployMethod string, deploymentClient *k8s.DeploymentClient) *AgentService {
	return &AgentService{db: db, storage: storage, codegen: cg, sandbox: sandbox, builder: builder, registry: registry, deployMethod: deployMethod, deploymentClient: deploymentClient}
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

func (s *AgentService) CreateWithRuntimeMode(configJSON string, configType model.ConfigType, runtimeMode model.RuntimeMode, image, checkpointDSN string) (*model.Agent, error) {
	agent := &model.Agent{
		Name:          extractName(configJSON),
		Config:        configJSON,
		ConfigType:    configType,
		RuntimeMode:   runtimeMode,
		Image:         image,
		CheckpointDSN: checkpointDSN,
		Status:        model.StatusDraft,
		Version:       1,
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

func (s *AgentService) UpdateWithRuntimeMode(id uint, configJSON string, runtimeMode model.RuntimeMode, image, checkpointDSN string) (*model.Agent, error) {
	agent, err := s.GetByID(id)
	if err != nil {
		return nil, err
	}
	agent.Config = configJSON
	agent.RuntimeMode = runtimeMode
	agent.Image = image
	agent.CheckpointDSN = checkpointDSN
	agent.Version++
	if err := s.db.Save(agent).Error; err != nil {
		return nil, err
	}
	return agent, nil
}

func (s *AgentService) Delete(id uint) error {
	return s.db.Delete(&model.Agent{}, id).Error
}

func (s *AgentService) DeleteWithCleanup(id uint) (*DeleteResult, error) {
	agent, err := s.GetByID(id)
	if err != nil {
		return nil, err
	}

	result := &DeleteResult{DockerImages: []string{}}
	sandboxName := fmt.Sprintf("agent-%d", agent.ID)

	if s.sandbox != nil && s.builder != nil {
		if agent.Status == model.StatusDeployed || agent.Status == model.StatusPublished {
			if s.sandbox.IngressExists(sandboxName) {
				if err := s.sandbox.DeleteIngress(sandboxName); err == nil {
					result.K8sIngress = true
				}
			}
			if s.sandbox.ServiceExists(sandboxName) {
				if err := s.sandbox.DeleteService(sandboxName); err == nil {
					result.K8sService = true
				}
			}
			// 根据部署方式清理 Sandbox 或 Deployment
			if s.deployMethod == "deployment" && s.deploymentClient != nil {
				if s.deploymentClient.DeploymentExists(sandboxName) {
					if err := s.deploymentClient.DeleteDeployment(sandboxName); err == nil {
						result.K8sDeployment = true
					}
				}
			} else {
				if s.sandbox.SandboxExists(sandboxName) {
					if err := s.sandbox.DeleteSandbox(sandboxName); err == nil {
						result.K8sSandbox = true
					}
				}
			}
		}

		if agent.RuntimeMode == model.RuntimeModeMount {
			configMapName := fmt.Sprintf("agent-%d-config", agent.ID)
			secretName := fmt.Sprintf("agent-%d-secret", agent.ID)
			s.sandbox.DeleteConfigMap(configMapName)
			s.sandbox.DeleteSecret(secretName)
		}

		if agent.Status != model.StatusDraft && agent.Status != model.StatusGenerated {
			var builds []model.ImageBuild
			s.db.Where("agent_id = ?", agent.ID).Find(&builds)
			for _, b := range builds {
				if b.ImageTag != "" && s.builder.ImageExists(b.ImageTag) {
					if err := s.builder.RemoveImage(b.ImageTag); err == nil {
						result.DockerImages = append(result.DockerImages, b.ImageTag)
					}
				}
				localTag := fmt.Sprintf("agent-manager/agent-%d:v%d", agent.ID, b.Version)
				if s.builder.ImageExists(localTag) {
					s.builder.RemoveImage(localTag)
				}
			}
		}
	}

	if agent.Status != model.StatusDraft {
		prefix := fmt.Sprintf("agents/%d", agent.ID)
		if s.storage != nil && s.storage.PrefixExists(prefix) {
			if err := s.storage.DeleteByPrefix(prefix); err == nil {
				result.MinIO = true
			}
		}
	}

	if err := s.db.Delete(&model.Agent{}, id).Error; err == nil {
		result.Database = true
	}

	return result, nil
}

func (s *AgentService) GenerateCode(id uint) (*model.CodeGeneration, error) {
	return s.GenerateCodeWithBaseImage(id, "")
}

func (s *AgentService) GenerateCodeWithBaseImage(id uint, baseImage string) (*model.CodeGeneration, error) {
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

	prefix := fmt.Sprintf("agents/%d/v%d", agent.ID, agent.Version)

	var files map[string]string

	if agent.ConfigType == model.ConfigOAF {
		oafConfig, err := model.ParseOAF(agent.Config)
		if err != nil {
			gen.Status = model.GenFailed
			gen.ErrorMsg = err.Error()
			s.db.Save(gen)
			return gen, err
		}

		if err := oafConfig.Validate(); err != nil {
			gen.Status = model.GenFailed
			gen.ErrorMsg = err.Error()
			s.db.Save(gen)
			return gen, err
		}

		files, err = s.codegen.RunWithOAF(oafConfig, prefix)
		if err != nil {
			gen.Status = model.GenFailed
			gen.ErrorMsg = err.Error()
			s.db.Save(gen)
			return gen, err
		}
	} else {
		var cfg map[string]interface{}
		if err := json.Unmarshal([]byte(agent.Config), &cfg); err != nil {
			gen.Status = model.GenFailed
			gen.ErrorMsg = err.Error()
			s.db.Save(gen)
			return gen, err
		}

		files, err = s.codegen.RunAndStoreWithBaseImage(cfg, prefix, baseImage)
		if err != nil {
			gen.Status = model.GenFailed
			gen.ErrorMsg = err.Error()
			s.db.Save(gen)
			return gen, err
		}
	}

	if path, ok := files["main.py"]; ok {
		gen.CodePath = path
	} else if path, ok := files["agent.py"]; ok {
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
	if strings.HasPrefix(strings.TrimSpace(configJSON), "---") {
		oaf, err := model.ParseOAF(configJSON)
		if err == nil {
			return oaf.Name
		}
	}

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

const sharedSkillsPrefix = "shared-skills"

func (s *AgentService) SaveSharedSkills(skillsMeta []map[string]interface{}, skillFiles map[string][]byte, skillDirs map[string]string) ([]map[string]interface{}, error) {
	nameToDir := make(map[string]string)
	for name, dir := range skillDirs {
		nameToDir[name] = dir
	}

	for zipPath, data := range skillFiles {
		cleanedPath := strings.TrimPrefix(zipPath, "./")
		if cleanedPath == "" {
			continue
		}

		skillName := ""
		relativePath := cleanedPath
		for name, dir := range nameToDir {
			if dir == "" {
				skillName = name
				relativePath = cleanedPath
				break
			} else if strings.HasPrefix(cleanedPath, dir+"/") {
				skillName = name
				relativePath = strings.TrimPrefix(cleanedPath, dir+"/")
				break
			}
		}
		if skillName == "" {
			if len(nameToDir) == 1 {
				for name, _ := range nameToDir {
					skillName = name
					relativePath = cleanedPath
					break
				}
			}
		}

		var objName string
		if skillName != "" {
			objName = fmt.Sprintf("%s/%s/%s", sharedSkillsPrefix, skillName, relativePath)
		} else {
			objName = fmt.Sprintf("%s/%s", sharedSkillsPrefix, cleanedPath)
		}
		if _, err := s.storage.PutFileString(objName, string(data)); err != nil {
			return nil, fmt.Errorf("store shared skill file %s: %w", zipPath, err)
		}
	}

	for i := range skillsMeta {
		skillsMeta[i]["storage_prefix"] = sharedSkillsPrefix
	}

	metaKey := fmt.Sprintf("%s/.metadata.json", sharedSkillsPrefix)
	existingData, err := s.storage.GetFile(metaKey)
	var allSkills []map[string]interface{}
	if err == nil && existingData != "" {
		json.Unmarshal([]byte(existingData), &allSkills)
	}

	nameSet := make(map[string]bool)
	for _, s := range skillsMeta {
		if name, ok := s["name"].(string); ok {
			nameSet[name] = true
		}
	}

	filtered := make([]map[string]interface{}, 0)
	for _, s := range allSkills {
		if name, ok := s["name"].(string); ok && nameSet[name] {
			continue
		}
		filtered = append(filtered, s)
	}
	allSkills = append(filtered, skillsMeta...)

	metaJSON, _ := json.Marshal(allSkills)
	s.storage.PutFileString(metaKey, string(metaJSON))

	return skillsMeta, nil
}

func (s *AgentService) ListSharedSkills() ([]map[string]interface{}, error) {
	metaKey := fmt.Sprintf("%s/.metadata.json", sharedSkillsPrefix)
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

func (s *AgentService) DeleteSharedSkill(skillName string) error {
	prefix := fmt.Sprintf("%s/%s", sharedSkillsPrefix, skillName)
	if err := s.storage.DeleteByPrefix(prefix); err != nil {
		return err
	}

	metaKey := fmt.Sprintf("%s/.metadata.json", sharedSkillsPrefix)
	data, err := s.storage.GetFile(metaKey)
	if err != nil {
		return nil
	}

	var skills []map[string]interface{}
	if err := json.Unmarshal([]byte(data), &skills); err != nil {
		return nil
	}

	filtered := make([]map[string]interface{}, 0)
	for _, s := range skills {
		if name, ok := s["name"].(string); ok && name == skillName {
			continue
		}
		filtered = append(filtered, s)
	}

	metaJSON, _ := json.Marshal(filtered)
	s.storage.PutFileString(metaKey, string(metaJSON))

	return nil
}

func (s *AgentService) CopySkillsToAgent(agentID uint, skillNames []string) ([]map[string]interface{}, error) {
	agentPrefix := fmt.Sprintf("agents/%d/skills", agentID)
	var allAgentSkills []map[string]interface{}

	// read existing agent skills metadata
	existingMetaKey := fmt.Sprintf("%s/.metadata.json", agentPrefix)
	existingData, err := s.storage.GetFile(existingMetaKey)
	if err == nil && existingData != "" {
		json.Unmarshal([]byte(existingData), &allAgentSkills)
	}

	sharedMetaKey := fmt.Sprintf("%s/.metadata.json", sharedSkillsPrefix)
	sharedData, err := s.storage.GetFile(sharedMetaKey)
	if err != nil {
		return nil, fmt.Errorf("no shared skills available")
	}

	var sharedSkills []map[string]interface{}
	if err := json.Unmarshal([]byte(sharedData), &sharedSkills); err != nil {
		return nil, fmt.Errorf("invalid shared skills metadata")
	}

	sharedByName := make(map[string]map[string]interface{})
	for _, sk := range sharedSkills {
		if name, ok := sk["name"].(string); ok {
			sharedByName[name] = sk
		}
	}

	copied := make([]map[string]interface{}, 0)
	for _, name := range skillNames {
		skillMeta, ok := sharedByName[name]
		if !ok {
			continue
		}

		srcPrefix := fmt.Sprintf("%s/%s", sharedSkillsPrefix, name)
		files, err := s.storage.ListFiles(srcPrefix)
		if err != nil {
			continue
		}

		for _, file := range files {
			srcContent, err := s.storage.GetFile(file)
			if err != nil {
				continue
			}
			relPath := strings.TrimPrefix(file, srcPrefix+"/")
			if relPath == file {
				relPath = strings.TrimPrefix(file, srcPrefix)
			}
			dstPath := fmt.Sprintf("%s/%s/%s", agentPrefix, name, relPath)
			if _, err := s.storage.PutFileString(dstPath, srcContent); err != nil {
				return nil, fmt.Errorf("copy file %s: %w", file, err)
			}
		}

		copyMeta := make(map[string]interface{})
		for k, v := range skillMeta {
			copyMeta[k] = v
		}
		copyMeta["storage_prefix"] = agentPrefix
		copied = append(copied, copyMeta)
	}

	allAgentSkills = append(allAgentSkills, copied...)

	metaJSON, _ := json.Marshal(allAgentSkills)
	s.storage.PutFileString(existingMetaKey, string(metaJSON))

	return copied, nil
}
