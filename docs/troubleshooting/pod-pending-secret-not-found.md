# Pod Pending: Secret Not Found

## 问题描述

Pod 状态一直为 `Pending`，Events 显示：

```
Warning  Failed  Error: secret "agent-secrets" not found
```

## 根因分析

Sandbox CRD 创建时引用了 K8s Secret `agent-secrets`，但集群中不存在该 Secret。

原代码 (`backend/internal/k8s/sandbox.go`):

```go
env:
- name: LLM_API_KEY
  valueFrom:
    secretKeyRef:
      name: agent-secrets
      key: llm-api-key
```

## 解决方案

修改 `CreateSandbox` 直接注入 LLM 环境变量，从 Agent.Config 解析配置：

### 1. 修改 `backend/internal/k8s/sandbox.go`

```go
func (s *SandboxClient) CreateSandbox(name, image, llmAPIKey, llmModel, llmEndpoint string) error {
    yaml := fmt.Sprintf(`...
        env:
        - name: LLM_API_KEY
          value: "%s"
        - name: LLM_MODEL
          value: "%s"
        - name: LLM_ENDPOINT
          value: "%s"
`, name, s.namespace, name, name, image, llmAPIKey, llmModel, llmEndpoint)
```

### 2. 修改 `backend/internal/service/deploy.go`

```go
func (s *DeployService) Deploy(agentID uint) (*model.Deployment, error) {
    // ...
    llmAPIKey, llmModel, llmEndpoint := parseLLMConfig(agent.Config)
    // ...
    if err := s.sandbox.CreateSandbox(sandboxName, imageTag, llmAPIKey, llmModel, llmEndpoint); err != nil {
        // ...
    }
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
```

## 验证

```bash
kubectl get pod agent-22 -o wide
# NAME       READY   STATUS    RESTARTS   AGE
# agent-22   1/1     Running   0          12s

kubectl exec agent-22 -- env | grep LLM_
# LLM_API_KEY=xxx
# LLM_MODEL=qwen3.6-plus
# LLM_ENDPOINT=https://dashscope.aliyuncs.com/compatible-mode/v1
```

## 相关文件

- `backend/internal/k8s/sandbox.go:42` — CreateSandbox 函数
- `backend/internal/service/deploy.go:86` — Deploy 函数
- `backend/internal/service/deploy.go:320` — parseLLMConfig 函数
