package service

import (
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
	"time"

	"agent-manager/backend/internal/docker"
	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/minio"
	"agent-manager/backend/internal/model"

	"gorm.io/gorm"
)

type DeployService struct {
	db       *gorm.DB
	storage  *minio.Storage
	builder  *docker.Builder
	sandbox  *k8s.SandboxClient
	registry string
	agentSvc *AgentService
}

func NewDeployService(db *gorm.DB, storage *minio.Storage, builder *docker.Builder, sandbox *k8s.SandboxClient, registry string, agentSvc *AgentService) *DeployService {
	return &DeployService{db: db, storage: storage, builder: builder, sandbox: sandbox, registry: registry, agentSvc: agentSvc}
}

func (s *DeployService) BuildImage(agentID uint) (*model.ImageBuild, error) {
	agent, err := s.agentSvc.GetByID(agentID)
	if err != nil {
		return nil, err
	}

	build := &model.ImageBuild{
		AgentID: agent.ID,
		Version: agent.Version,
		Status:  model.BuildBuilding,
	}
	s.db.Create(build)

	imageTag := fmt.Sprintf("%s/agent-%d:v%d", s.registry, agent.ID, agent.Version)
	localTag := fmt.Sprintf("agent-manager/agent-%d:v%d", agent.ID, agent.Version)

	prefix := fmt.Sprintf("agents/%d/v%d", agent.ID, agent.Version)
	buildLog, err := s.builder.Build(localTag, imageTag, prefix, s.storage)
	if err != nil {
		build.Status = model.BuildFailed
		build.BuildLog = buildLog + "\n" + err.Error()
		s.db.Save(build)
		return build, err
	}

	build.ImageTag = imageTag
	build.Status = model.BuildSuccess
	build.BuildLog = buildLog
	s.db.Save(build)

	agent.Status = model.StatusBuilt
	s.db.Save(agent)

	return build, nil
}

func (s *DeployService) Deploy(agentID uint) (*model.Deployment, error) {
	agent, err := s.agentSvc.GetByID(agentID)
	if err != nil {
		return nil, err
	}

	sandboxName := fmt.Sprintf("agent-%d", agent.ID)
	imageTag := fmt.Sprintf("%s/agent-%d:v%d", s.registry, agent.ID, agent.Version)

	dep := &model.Deployment{
		AgentID:     agent.ID,
		Version:     agent.Version,
		SandboxName: sandboxName,
		Status:      model.DeployDeploying,
	}
	s.db.Create(dep)

	now := time.Now()
	if err := s.sandbox.CreateSandbox(sandboxName, imageTag); err != nil {
		dep.Status = model.DeployFailed
		s.db.Save(dep)
		return dep, err
	}

	dep.Status = model.DeployRunning
	dep.DeployedAt = &now
	s.db.Save(dep)

	agent.Status = model.StatusDeployed
	s.db.Save(agent)

	return dep, nil
}

func (s *DeployService) Publish(agentID uint) (*model.Deployment, error) {
	dep, err := s.Deploy(agentID)
	if err != nil {
		return dep, err
	}

	agent, _ := s.agentSvc.GetByID(agentID)
	agent.Status = model.StatusPublished
	s.db.Save(agent)

	dep.Status = model.DeployRunning
	s.db.Save(dep)

	return dep, nil
}

func (s *DeployService) Unpublish(agentID uint) (*model.Deployment, error) {
	dep, err := s.agentSvc.GetLatestDeployment(agentID)
	if err != nil {
		return nil, err
	}

	if err := s.sandbox.DeleteSandbox(dep.SandboxName); err != nil {
		return dep, err
	}

	now := time.Now()
	dep.UnpublishedAt = &now
	dep.Status = model.DeployStopped
	s.db.Save(dep)

	agent, _ := s.agentSvc.GetByID(agentID)
	agent.Status = model.StatusUnpublished
	s.db.Save(agent)

	return dep, nil
}

func (s *DeployService) GetImageInfo(agentID uint) (map[string]interface{}, error) {
	var build model.ImageBuild
	err := s.db.Where("agent_id = ?", agentID).Order("created_at DESC").First(&build).Error
	if err != nil {
		return nil, fmt.Errorf("no build found for agent %d", agentID)
	}

	imageName := ""
	version := ""
	if build.ImageTag != "" {
		parts := strings.Split(build.ImageTag, ":")
		if len(parts) == 2 {
			imageName = parts[0]
			version = parts[1]
		}
	}

	return map[string]interface{}{
		"image_tag":    build.ImageTag,
		"image_name":   imageName,
		"registry":     s.registry,
		"version":      version,
		"build_status": string(build.Status),
		"build_time":   build.CreatedAt,
	}, nil
}

func (s *DeployService) GetPodStatus(agentID uint) (map[string]interface{}, error) {
	dep, err := s.agentSvc.GetLatestDeployment(agentID)
	if err != nil {
		return nil, fmt.Errorf("no deployment found for agent %d", agentID)
	}

	if dep.Status != model.DeployRunning {
		return map[string]interface{}{
			"sandbox_name": dep.SandboxName,
			"pod_status":   "not_running",
			"ready":        false,
			"message":      fmt.Sprintf("deployment status: %s", dep.Status),
		}, nil
	}

	jsonStr, err := s.sandbox.GetPodStatusJSON(dep.SandboxName)
	if err != nil {
		return map[string]interface{}{
			"sandbox_name": dep.SandboxName,
			"pod_status":   "error",
			"ready":        false,
			"error":        err.Error(),
		}, nil
	}

	var podData map[string]interface{}
	json.Unmarshal([]byte(jsonStr), &podData)
	podData["sandbox_name"] = dep.SandboxName
	return podData, nil
}

func (s *DeployService) ChatWithAgent(agentID uint, message string, history []map[string]string) (map[string]interface{}, error) {
	dep, err := s.agentSvc.GetLatestDeployment(agentID)
	if err != nil {
		return nil, fmt.Errorf("no deployment found for agent %d", agentID)
	}

	if dep.Status != model.DeployRunning {
		return nil, fmt.Errorf("agent is not running (status: %s)", dep.Status)
	}

	reqBody := map[string]interface{}{
		"message": message,
		"history":  history,
	}
	bodyJSON, _ := json.Marshal(reqBody)

	startTime := time.Now()
	cmd := exec.Command("kubectl", "exec", "-n", "default", dep.SandboxName, "--",
		"curl", "-s", "-m", "120", "-X", "POST", "http://localhost:8000/chat",
		"-H", "Content-Type: application/json",
		"-d", string(bodyJSON))
	out, err := cmd.CombinedOutput()
	latencyMs := time.Since(startTime).Milliseconds()

	if err != nil {
		return map[string]interface{}{
			"success":    false,
			"error":      fmt.Sprintf("agent unreachable: %s, output: %s", err.Error(), string(out)),
			"latency_ms": latencyMs,
		}, nil
	}

	var agentResp map[string]interface{}
	if err2 := json.Unmarshal(out, &agentResp); err2 != nil {
		return map[string]interface{}{
			"success":    false,
			"error":      fmt.Sprintf("parse agent response: %s, raw: %s", err2.Error(), string(out)),
			"latency_ms": latencyMs,
		}, nil
	}

	agentResp["latency_ms"] = latencyMs
	return agentResp, nil
}
