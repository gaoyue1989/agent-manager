package service

import (
	"fmt"
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
