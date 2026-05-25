package service

import (
	"encoding/json"
	"fmt"
	"log"
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
	db                   *gorm.DB
	storage              *minio.Storage
	builder              *docker.Builder
	sandbox              *k8s.SandboxClient
	deploymentClient     *k8s.DeploymentClient
	registry             string
	agentSvc             *AgentService
	ingressEnabled       bool
	baseImageName        string
	llmAPIKey            string
	llmModelID           string
	llmBaseUrl           string
	defaultCheckpointDSN string
	deployMethod         string
}

func NewDeployService(db *gorm.DB, storage *minio.Storage, builder *docker.Builder, sandbox *k8s.SandboxClient, registry string, agentSvc *AgentService) *DeployService {
	return &DeployService{db: db, storage: storage, builder: builder, sandbox: sandbox, registry: registry, agentSvc: agentSvc, ingressEnabled: true, baseImageName: "agent-base:latest", deployMethod: "sandbox"}
}

func NewDeployServiceWithIngress(db *gorm.DB, storage *minio.Storage, builder *docker.Builder, sandbox *k8s.SandboxClient, registry string, agentSvc *AgentService, ingressEnabled bool) *DeployService {
	return &DeployService{db: db, storage: storage, builder: builder, sandbox: sandbox, registry: registry, agentSvc: agentSvc, ingressEnabled: ingressEnabled, baseImageName: "agent-base:latest", deployMethod: "sandbox"}
}

func NewDeployServiceWithBaseImage(db *gorm.DB, storage *minio.Storage, builder *docker.Builder, sandbox *k8s.SandboxClient, registry string, agentSvc *AgentService, ingressEnabled bool, baseImageName string) *DeployService {
	return &DeployService{db: db, storage: storage, builder: builder, sandbox: sandbox, registry: registry, agentSvc: agentSvc, ingressEnabled: ingressEnabled, baseImageName: baseImageName, deployMethod: "sandbox"}
}

func NewDeployServiceWithLLM(db *gorm.DB, storage *minio.Storage, builder *docker.Builder, sandbox *k8s.SandboxClient, registry string, agentSvc *AgentService, ingressEnabled bool, baseImageName, llmAPIKey, llmModelID, llmBaseUrl, defaultCheckpointDSN string) *DeployService {
	return &DeployService{
		db:                   db,
		storage:              storage,
		builder:              builder,
		sandbox:              sandbox,
		registry:             registry,
		agentSvc:             agentSvc,
		ingressEnabled:       ingressEnabled,
		baseImageName:        baseImageName,
		llmAPIKey:            llmAPIKey,
		llmModelID:           llmModelID,
		llmBaseUrl:           llmBaseUrl,
		defaultCheckpointDSN: defaultCheckpointDSN,
		deployMethod:         "sandbox",
	}
}

func NewDeployServiceWithDeployMethod(db *gorm.DB, storage *minio.Storage, builder *docker.Builder, sandbox *k8s.SandboxClient, registry string, agentSvc *AgentService, ingressEnabled bool, baseImageName, llmAPIKey, llmModelID, llmBaseUrl, defaultCheckpointDSN, deployMethod string, deploymentClient *k8s.DeploymentClient) *DeployService {
	return &DeployService{
		db:                   db,
		storage:              storage,
		builder:              builder,
		sandbox:              sandbox,
		deploymentClient:     deploymentClient,
		registry:             registry,
		agentSvc:             agentSvc,
		ingressEnabled:       ingressEnabled,
		baseImageName:        baseImageName,
		llmAPIKey:            llmAPIKey,
		llmModelID:           llmModelID,
		llmBaseUrl:           llmBaseUrl,
		defaultCheckpointDSN: defaultCheckpointDSN,
		deployMethod:         deployMethod,
	}
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
	buildLog, err := s.builder.BuildWithBaseImage(localTag, imageTag, prefix, s.storage, s.registry)
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

func (s *DeployService) GenerateAndBuild(agentID uint) (*model.ImageBuild, error) {
	baseImage := fmt.Sprintf("%s/%s", s.registry, s.baseImageName)
	if _, err := s.agentSvc.GenerateCodeWithBaseImage(agentID, baseImage); err != nil {
		return nil, err
	}
	return s.BuildImage(agentID)
}

func (s *DeployService) Deploy(agentID uint) (*model.Deployment, error) {
	agent, err := s.agentSvc.GetByID(agentID)
	if err != nil {
		return nil, err
	}

	sandboxName := fmt.Sprintf("agent-%d", agent.ID)
	imageTag := fmt.Sprintf("%s/agent-%d:v%d", s.registry, agent.ID, agent.Version)

	llmAPIKey, llmModel, llmEndpoint := parseLLMConfig(agent.Config)

	dep := &model.Deployment{
		AgentID:     agent.ID,
		Version:     agent.Version,
		SandboxName: sandboxName,
		Status:      model.DeployDeploying,
	}
	s.db.Create(dep)

	if err := s.sandbox.CreateService(sandboxName); err != nil {
		dep.Status = model.DeployFailed
		s.db.Save(dep)
		return dep, err
	}

	now := time.Now()
	if s.deployMethod == "deployment" && s.deploymentClient != nil {
		envVars := map[string]string{
			"LLM_API_KEY":  llmAPIKey,
			"LLM_MODEL":    llmModel,
			"LLM_ENDPOINT": llmEndpoint,
		}
		if err := s.deploymentClient.CreateDeployment(sandboxName, imageTag, envVars); err != nil {
			dep.Status = model.DeployFailed
			s.db.Save(dep)
			return dep, err
		}
	} else {
		if err := s.sandbox.CreateSandbox(sandboxName, imageTag, llmAPIKey, llmModel, llmEndpoint); err != nil {
			dep.Status = model.DeployFailed
			s.db.Save(dep)
			return dep, err
		}
	}

	dep.Status = model.DeployRunning
	dep.DeployedAt = &now
	s.db.Save(dep)

	agent.Status = model.StatusDeployed
	s.db.Save(agent)

	return dep, nil
}

func (s *DeployService) DeployWithMount(agentID uint) (*model.Deployment, error) {
	agent, err := s.agentSvc.GetByID(agentID)
	if err != nil {
		return nil, err
	}

	sandboxName := fmt.Sprintf("agent-%d", agent.ID)
	configMapName := fmt.Sprintf("agent-%d-config", agent.ID)
	secretName := fmt.Sprintf("agent-%d-secret", agent.ID)

	dep := &model.Deployment{
		AgentID:     agent.ID,
		Version:     agent.Version,
		SandboxName: sandboxName,
		Status:      model.DeployDeploying,
	}
	s.db.Create(dep)

	configFiles, configMapItems, err := s.loadConfigFilesFromMinIO(agent)
	if err != nil {
		dep.Status = model.DeployFailed
		s.db.Save(dep)
		return dep, fmt.Errorf("load config files: %w", err)
	}

	if err := s.sandbox.CreateConfigMap(configMapName, configFiles); err != nil {
		dep.Status = model.DeployFailed
		s.db.Save(dep)
		return dep, fmt.Errorf("create configmap: %w", err)
	}

	secretData := map[string]string{
		"LLM_API_KEY": s.llmAPIKey,
	}
	if err := s.sandbox.CreateSecret(secretName, secretData); err != nil {
		s.sandbox.DeleteConfigMap(configMapName)
		dep.Status = model.DeployFailed
		s.db.Save(dep)
		return dep, fmt.Errorf("create secret: %w", err)
	}

	checkpointDSN := agent.CheckpointDSN
	if checkpointDSN == "" {
		checkpointDSN = s.defaultCheckpointDSN
	}

	envVars := map[string]string{
		"LLM_MODEL_ID": s.llmModelID,
		"LLM_BASE_URL": s.llmBaseUrl,
	}

	if err := s.sandbox.CreateServiceWithPort(sandboxName, 8100); err != nil {
		s.sandbox.DeleteConfigMap(configMapName)
		s.sandbox.DeleteSecret(secretName)
		dep.Status = model.DeployFailed
		s.db.Save(dep)
		return dep, fmt.Errorf("create service: %w", err)
	}

	image := agent.Image
	if agent.RuntimeMode == model.RuntimeModeMount && s.registry != "" && !strings.Contains(image, "/") {
		image = fmt.Sprintf("%s/%s", s.registry, image)
	}

	now := time.Now()
	if s.deployMethod == "deployment" && s.deploymentClient != nil {
		if err := s.deploymentClient.CreateDeploymentWithMounts(sandboxName, image, 8100, envVars, configMapName, secretName, checkpointDSN); err != nil {
			s.sandbox.DeleteConfigMap(configMapName)
			s.sandbox.DeleteSecret(secretName)
			dep.Status = model.DeployFailed
			s.db.Save(dep)
			return dep, fmt.Errorf("create deployment: %w", err)
		}
	} else {
		if err := s.sandbox.CreateSandboxWithMountsAndItems(sandboxName, image, configMapName, secretName, envVars, checkpointDSN, configMapItems); err != nil {
			s.sandbox.DeleteConfigMap(configMapName)
			s.sandbox.DeleteSecret(secretName)
			dep.Status = model.DeployFailed
			s.db.Save(dep)
			return dep, fmt.Errorf("create sandbox: %w", err)
		}
	}

	dep.Status = model.DeployRunning
	dep.DeployedAt = &now
	s.db.Save(dep)

	agent.Status = model.StatusDeployed
	s.db.Save(agent)

	return dep, nil
}

func (s *DeployService) loadConfigFilesFromMinIO(agent *model.Agent) (map[string]string, []k8s.ConfigMapItem, error) {
	files := make(map[string]string)
	var configMapItems []k8s.ConfigMapItem

	safeKey := func(path string) string {
		return strings.ReplaceAll(path, "/", ".")
	}

	var oafConfig *model.OAFConfig

	if agent.ConfigType == model.ConfigOAF {
		oaf, err := model.OAFFromJSON([]byte(agent.Config))
		if err != nil {
			return nil, nil, fmt.Errorf("parse OAF JSON: %w", err)
		}
		oafConfig = oaf
	} else {
		oaf, err := model.ParseOAF(agent.Config)
		if err != nil {
			return nil, nil, fmt.Errorf("parse OAF: %w", err)
		}
		oafConfig = oaf
	}

	oafYAML, err := oafConfig.ToYAML()
	if err != nil {
		return nil, nil, fmt.Errorf("serialize OAF: %w", err)
	}
	files["AGENTS.md"] = oafYAML
	configMapItems = append(configMapItems, k8s.ConfigMapItem{Key: "AGENTS.md", Path: "AGENTS.md"})

	prefix := fmt.Sprintf("agents/%d/skills", agent.ID)
	if s.storage.PrefixExists(prefix) {
		skillFiles, err := s.storage.ListFiles(prefix)
		if err == nil {
			for _, file := range skillFiles {
				content, err := s.storage.GetFile(file)
				if err != nil {
					continue
				}
				relPath := strings.TrimPrefix(file, prefix+"/")
				cfgPath := fmt.Sprintf("skills/%s", relPath)
				files[safeKey(cfgPath)] = content
				configMapItems = append(configMapItems, k8s.ConfigMapItem{Key: safeKey(cfgPath), Path: cfgPath})
			}
		}
	}

	for _, mcp := range oafConfig.MCPServers {
		if mcp.ConfigDir == "" {
			continue
		}
		mcpPrefix := fmt.Sprintf("agents/%d/%s", agent.ID, mcp.ConfigDir)
		if s.storage.PrefixExists(mcpPrefix) {
			mcpFiles, err := s.storage.ListFiles(mcpPrefix)
			if err == nil {
				for _, file := range mcpFiles {
					content, err := s.storage.GetFile(file)
					if err != nil {
						continue
					}
					relPath := strings.TrimPrefix(file, mcpPrefix+"/")
					cfgPath := fmt.Sprintf("%s/%s", mcp.ConfigDir, relPath)
					files[safeKey(cfgPath)] = content
					configMapItems = append(configMapItems, k8s.ConfigMapItem{Key: safeKey(cfgPath), Path: cfgPath})
				}
			}
		}
	}

	customToolsPrefix := fmt.Sprintf("agents/%d/custom-tools", agent.ID)
	if s.storage.PrefixExists(customToolsPrefix) {
		ctFiles, err := s.storage.ListFiles(customToolsPrefix)
		if err == nil {
			for _, file := range ctFiles {
				content, err := s.storage.GetFile(file)
				if err != nil {
					continue
				}
				relPath := strings.TrimPrefix(file, customToolsPrefix+"/")
				cfgPath := fmt.Sprintf("custom-tools/%s", relPath)
				files[safeKey(cfgPath)] = content
				configMapItems = append(configMapItems, k8s.ConfigMapItem{Key: safeKey(cfgPath), Path: cfgPath})
			}
		}
	}

	return files, configMapItems, nil
}

func (s *DeployService) Publish(agentID uint) (*model.Deployment, error) {
	agent, err := s.agentSvc.GetByID(agentID)
	if err != nil {
		return nil, err
	}

	var dep *model.Deployment

	if agent.RuntimeMode == model.RuntimeModeMount {
		dep, err = s.DeployWithMount(agentID)
	} else {
		if agent.Status == model.StatusDraft {
			return nil, fmt.Errorf("please generate code and build image first")
		}
		dep, err = s.Deploy(agentID)
	}

	if err != nil {
		return dep, err
	}

	sandboxName := fmt.Sprintf("agent-%d", agent.ID)

	var endpointURL string
	if s.ingressEnabled {
		port := 8000
		if agent.RuntimeMode == model.RuntimeModeMount {
			port = 8100
		}
		endpointURL, err = s.sandbox.CreateIngressWithPort(sandboxName, agent.ID, port)
		if err != nil {
			log.Println("WARNING: failed to create Ingress:", err)
		}
		dep.EndpointURL = endpointURL
	}

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

	sandboxName := dep.SandboxName

	agent, _ := s.agentSvc.GetByID(agentID)

	if s.ingressEnabled {
		if err := s.sandbox.DeleteIngress(sandboxName); err != nil {
			log.Println("WARNING: failed to delete Ingress:", err)
		}
	}

	if err := s.sandbox.DeleteService(sandboxName); err != nil {
		log.Println("WARNING: failed to delete Service:", err)
	}

	if s.deployMethod == "deployment" && s.deploymentClient != nil {
		if err := s.deploymentClient.DeleteDeployment(sandboxName); err != nil {
			return dep, err
		}
	} else {
		if err := s.sandbox.DeleteSandbox(sandboxName); err != nil {
			return dep, err
		}
	}

	if agent.RuntimeMode == model.RuntimeModeMount {
		configMapName := fmt.Sprintf("agent-%d-config", agent.ID)
		secretName := fmt.Sprintf("agent-%d-secret", agent.ID)
		s.sandbox.DeleteConfigMap(configMapName)
		s.sandbox.DeleteSecret(secretName)
	}

	now := time.Now()
	dep.UnpublishedAt = &now
	dep.Status = model.DeployStopped
	dep.EndpointURL = ""
	s.db.Save(dep)

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

type PodFileNode struct {
	Name     string         `json:"name"`
	Path     string         `json:"path"`
	Type     string         `json:"type"`
	Key      string         `json:"key,omitempty"`
	Children []*PodFileNode `json:"children,omitempty"`
}

func (s *DeployService) GetPodFiles(agentID uint) ([]*PodFileNode, error) {
	agent, err := s.agentSvc.GetByID(agentID)
	if err != nil {
		return nil, err
	}

	if agent.RuntimeMode != model.RuntimeModeMount {
		return nil, fmt.Errorf("only mount mode agents support file listing")
	}

	configMapName := fmt.Sprintf("agent-%d-config", agent.ID)
	data, err := s.sandbox.GetConfigMapData(configMapName)
	if err != nil {
		return nil, fmt.Errorf("read configmap: %w", err)
	}

	filePaths := make(map[string]string)

	if _, ok := data["AGENTS.md"]; ok {
		filePaths["AGENTS.md"] = "AGENTS.md"
	}

	prefix := fmt.Sprintf("agents/%d/", agent.ID)
	minioFiles, err := s.storage.ListFiles(prefix)
	if err == nil {
		for _, f := range minioFiles {
			if strings.HasSuffix(f, "/") {
				continue
			}
			relPath := strings.TrimPrefix(f, prefix)
			if _, dup := filePaths[relPath]; !dup {
				filePaths[relPath] = relPath
			}
		}
	}

	root := &PodFileNode{Name: "/config", Path: "/config", Type: "dir", Children: []*PodFileNode{}}
	dirMap := make(map[string]*PodFileNode)

	for _, filePath := range filePaths {
		parts := strings.Split(filePath, "/")
		current := root

		for i, part := range parts {
			isLast := i == len(parts)-1
			fullPath := strings.Join(parts[:i+1], "/")

			if isLast {
				current.Children = append(current.Children, &PodFileNode{
					Name: part, Path: fullPath, Type: "file", Key: filePath,
				})
			} else {
				if existing, ok := dirMap[fullPath]; ok {
					current = existing
				} else {
					dir := &PodFileNode{Name: part, Path: fullPath, Type: "dir", Children: []*PodFileNode{}}
					dirMap[fullPath] = dir
					current.Children = append(current.Children, dir)
					current = dir
				}
			}
		}
	}

	return root.Children, nil
}

func (s *DeployService) GetPodFileContent(agentID uint, key string) (map[string]interface{}, error) {
	agent, err := s.agentSvc.GetByID(agentID)
	if err != nil {
		return nil, err
	}

	if agent.RuntimeMode != model.RuntimeModeMount {
		return nil, fmt.Errorf("only mount mode agents support file reading")
	}

	if key == "AGENTS.md" {
		configMapName := fmt.Sprintf("agent-%d-config", agent.ID)
		data, err := s.sandbox.GetConfigMapData(configMapName)
		if err != nil {
			return nil, fmt.Errorf("read configmap: %w", err)
		}
		content, ok := data["AGENTS.md"]
		if !ok {
			return nil, fmt.Errorf("AGENTS.md not found in configmap")
		}
		return map[string]interface{}{"key": key, "content": content}, nil
	}

	objectName := fmt.Sprintf("agents/%d/%s", agent.ID, key)
	content, err := s.storage.GetFile(objectName)
	if err != nil {
		return nil, fmt.Errorf("read file from storage: %w", err)
	}

	return map[string]interface{}{
		"key":     key,
		"content": content,
	}, nil
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
		"jsonrpc": "2.0",
		"method":  "message/send",
		"id":      "1",
		"params": map[string]interface{}{
			"message": map[string]interface{}{
				"role":  "user",
				"parts": []map[string]string{{"text": message}},
			},
		},
	}
	bodyJSON, _ := json.Marshal(reqBody)

	startTime := time.Now()

	if s.ingressEnabled && dep.EndpointURL != "" {
		resp, err := chatViaHTTP(dep.EndpointURL, bodyJSON, startTime)
		if err == nil {
			return resp, nil
		}
		log.Printf("Ingress chat failed, falling back to kubectl exec: %v", err)
	}

	out, err := s.sandbox.ExecInPod(dep.SandboxName,
		"curl", "-s", "-m", "120", "-X", "POST", "http://localhost:8100/",
		"-H", "Content-Type: application/json",
		"-d", string(bodyJSON))
	latencyMs := time.Since(startTime).Milliseconds()

	if err != nil {
		return map[string]interface{}{
			"success":    false,
			"error":      fmt.Sprintf("agent unreachable: %v, output: %s", err, string(out)),
			"latency_ms": latencyMs,
		}, nil
	}

	return parseChatResponse(out, latencyMs)
}

func chatViaHTTP(endpointURL string, bodyJSON []byte, startTime time.Time) (map[string]interface{}, error) {
	cmd := exec.Command("curl", "-s", "-m", "120", "-X", "POST", endpointURL,
		"-H", "Content-Type: application/json",
		"-d", string(bodyJSON))
	out, err := cmd.CombinedOutput()
	latencyMs := time.Since(startTime).Milliseconds()

	if err != nil {
		return nil, fmt.Errorf("ingress chat failed: %w", err)
	}

	return parseChatResponse(out, latencyMs)
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func parseChatResponse(raw []byte, latencyMs int64) (map[string]interface{}, error) {
	var rpcResp struct {
		JSONRPC string                 `json:"jsonrpc"`
		ID      string                 `json:"id"`
		Error   *rpcError              `json:"error"`
		Result  map[string]interface{} `json:"result"`
	}

	if err := json.Unmarshal(raw, &rpcResp); err != nil {
		return map[string]interface{}{
			"success":    false,
			"error":      fmt.Sprintf("parse response: %v, raw: %s", err, string(raw)),
			"latency_ms": latencyMs,
		}, nil
	}

	if rpcResp.Error != nil {
		return map[string]interface{}{
			"success":    false,
			"error":      fmt.Sprintf("agent error (code %d): %s", rpcResp.Error.Code, rpcResp.Error.Message),
			"latency_ms": latencyMs,
		}, nil
	}

	responseText := extractResponseText(rpcResp.Result)
	return map[string]interface{}{
		"success":    true,
		"data":       map[string]interface{}{"response": responseText},
		"latency_ms": latencyMs,
	}, nil
}

func extractResponseText(result map[string]interface{}) string {
	artifacts, ok := result["artifacts"].([]interface{})
	if !ok || len(artifacts) == 0 {
		return ""
	}
	artifact, ok := artifacts[0].(map[string]interface{})
	if !ok {
		return ""
	}
	parts, ok := artifact["parts"].([]interface{})
	if !ok || len(parts) == 0 {
		return ""
	}
	part, ok := parts[0].(map[string]interface{})
	if !ok {
		return ""
	}
	text, _ := part["text"].(string)
	return text
}

func parseLLMConfig(configJSON string) (apiKey, model, endpoint string) {
	var cfg struct {
		APIKey       string `json:"api_key"`
		Model        string `json:"model"`
		ModelEndpoint string `json:"model_endpoint"`
	}
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return "", "qwen3.6-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1"
	}
	if cfg.Model == "" {
		cfg.Model = "qwen3.6-plus"
	}
	if cfg.ModelEndpoint == "" {
		cfg.ModelEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1"
	}
	return cfg.APIKey, cfg.Model, cfg.ModelEndpoint
}
