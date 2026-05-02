# Feature: Agent 删除功能

## 1. 需求概述

Agent 删除时需要清理所有相关资源，包括：
- MySQL 数据库记录 (Agent + CodeGeneration + ImageBuild + Deployment)
- MinIO 对象存储中的代码文件
- Kubernetes 中的 Sandbox CRD、Service、Ingress
- Docker 本地镜像和远程仓库镜像

## 2. 资源清单

### 2.1 数据库资源 (MySQL)

| 表名 | 关联字段 | 删除策略 |
|------|---------|---------|
| `agents` | `id` | 主表，直接删除 |
| `code_generations` | `agent_id` | GORM CASCADE 自动级联删除 |
| `image_builds` | `agent_id` | GORM CASCADE 自动级联删除 |
| `deployments` | `agent_id` | GORM CASCADE 自动级联删除 |

### 2.2 对象存储资源 (MinIO)

| 路径前缀 | 内容 | 删除策略 |
|---------|------|---------|
| `agents/{id}/v{version}/` | 代码文件 (agent.py, Dockerfile, requirements.txt) | 递归删除所有版本 |
| `agents/{id}/skills/` | Skill 文件 | 递归删除 |

### 2.3 Kubernetes 资源

| 资源类型 | 命名规则 | 删除策略 |
|---------|---------|---------|
| Sandbox CRD | `agent-{id}` | `kubectl delete sandbox` |
| Service | `agent-{id}-svc` | `kubectl delete service` |
| Ingress | `agent-{id}-ingress` | `kubectl delete ingress` |

### 2.4 Docker 资源

| 资源类型 | 命名规则 | 删除策略 |
|---------|---------|---------|
| 本地镜像 | `agent-manager/agent-{id}:v{version}` | `docker rmi` |
| 远程镜像 | `{registry}/agent-{id}:v{version}` | `docker rmi` (本地 tag 删除即可，远程需 registry API) |

## 3. API 设计

### 3.1 端点

```
DELETE /api/v1/agents/:id
```

### 3.2 响应

```json
{
  "message": "deleted",
  "cleanup": {
    "database": true,
    "minio": true,
    "kubernetes": true,
    "docker": {
      "local_images": 2,
      "remote_images": 2
    }
  }
}
```

### 3.3 错误处理

- Agent 不存在: 404
- 删除失败: 500，返回已清理的资源列表和失败原因

## 4. 实现方案

### 4.1 Service 层 (service/agent.go)

```go
type DeleteResult struct {
    Database      bool     `json:"database"`
    MinIO         bool     `json:"minio"`
    DockerImages  []string `json:"docker_images"`
    K8sSandbox    bool     `json:"k8s_sandbox"`
    K8sService    bool     `json:"k8s_service"`
    K8sIngress    bool     `json:"k8s_ingress"`
}

func (s *AgentService) DeleteWithCleanup(id uint) (*DeleteResult, error) {
    // 1. 查询 Agent 信息
    agent, err := s.GetByID(id)
    if err != nil {
        return nil, err
    }

    result := &DeleteResult{}
    sandboxName := fmt.Sprintf("agent-%d", agent.ID)

    // 2. 根据状态清理 K8s 资源
    if agent.Status == model.StatusDeployed || agent.Status == model.StatusPublished {
        // 检查并删除 Ingress
        if s.sandbox.IngressExists(sandboxName) {
            s.sandbox.DeleteIngress(sandboxName)
            result.K8sIngress = true
        }
        // 检查并删除 Service
        if s.sandbox.ServiceExists(sandboxName) {
            s.sandbox.DeleteService(sandboxName)
            result.K8sService = true
        }
        // 检查并删除 Sandbox
        if s.sandbox.SandboxExists(sandboxName) {
            s.sandbox.DeleteSandbox(sandboxName)
            result.K8sSandbox = true
        }
    }

    // 3. 清理 Docker 镜像 (built 及之后状态)
    if agent.Status != model.StatusDraft && agent.Status != model.StatusGenerated {
        images := s.getAgentImages(agent.ID)
        for _, img := range images {
            if s.builder.ImageExists(img) {
                s.builder.RemoveImage(img)
                result.DockerImages = append(result.DockerImages, img)
            }
        }
    }

    // 4. 清理 MinIO 文件 (generated 及之后状态)
    if agent.Status != model.StatusDraft {
        prefix := fmt.Sprintf("agents/%d", agent.ID)
        if s.storage.PrefixExists(prefix) {
            s.storage.DeleteByPrefix(prefix)
            result.MinIO = true
        }
    }

    // 5. 删除数据库记录
    s.db.Delete(&model.Agent{}, id)
    result.Database = true

    return result, nil
}
```

### 4.2 MinIO 扩展 (minio/storage.go)

新增方法:
```go
func (s *Storage) DeleteByPrefix(prefix string) error
func (s *Storage) PrefixExists(prefix string) bool
```

### 4.3 K8s 扩展 (k8s/sandbox.go)

新增方法:
```go
func (s *SandboxClient) SandboxExists(name string) bool
func (s *SandboxClient) ServiceExists(name string) bool
func (s *SandboxClient) IngressExists(name string) bool
```

### 4.4 Docker 扩展 (docker/builder.go)

新增方法:
```go
func (b *Builder) RemoveImage(imageTag string) error
func (b *Builder) ImageExists(imageTag string) bool
```

## 5. 状态与资源映射

### 5.1 各状态下的资源存在情况

| 状态 | 数据库 | MinIO | Docker 镜像 | K8s Sandbox | K8s Service | K8s Ingress |
|------|--------|-------|-------------|-------------|-------------|-------------|
| draft | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| generated | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ |
| built | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ |
| deployed | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| published | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| unpublished | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ |
| error | ✓ | ? | ? | ? | ? | ? |

### 5.2 删除前状态检查

```go
func (s *AgentService) DeleteWithCleanup(id uint) (*DeleteResult, error) {
    agent, err := s.GetByID(id)
    if err != nil {
        return nil, err
    }

    result := &DeleteResult{}

    // 根据状态决定清理策略
    switch agent.Status {
    case StatusDraft:
        // 只删除数据库
    case StatusGenerated:
        // 删除 MinIO + 数据库
    case StatusBuilt:
        // 删除 Docker + MinIO + 数据库
    case StatusDeployed, StatusPublished:
        // 删除 K8s + Docker + MinIO + 数据库
    case StatusUnpublished:
        // 删除 Docker + MinIO + 数据库
    case StatusError:
        // 尝试清理所有可能存在的资源
    }
}
```

### 5.3 资源存在性检查

每种资源删除前先检查是否存在：

```go
// K8s 资源检查
func (s *SandboxClient) SandboxExists(name string) bool
func (s *SandboxClient) ServiceExists(name string) bool
func (s *SandboxClient) IngressExists(name string) bool

// Docker 镜像检查
func (b *Builder) ImageExists(imageTag string) bool

// MinIO 文件检查
func (s *Storage) PrefixExists(prefix string) bool
```

## 6. 删除顺序

按以下顺序执行删除，确保失败时能追踪已清理资源：

1. **状态检查** - 确定 Agent 当前状态
2. **K8s 资源** - 检查并删除 Ingress → Service → Sandbox（仅 deployed/published 状态）
3. **Docker 镜像** - 检查并删除所有版本镜像（built 及之后状态）
4. **MinIO 文件** - 检查并删除代码文件（generated 及之后状态）
5. **数据库记录** - 最后删除元数据（所有状态）

## 7. 安全约束

- 删除操作不可逆，需在 Handler 层记录日志
- 失败时记录已删除资源，支持手动清理
- `error` 状态时尝试清理所有可能存在的资源
- 每种资源删除前先检查是否存在，不存在则跳过

## 8. 单元测试

### 8.1 Service 层测试

| 用例 | 验证内容 |
|------|---------|
| TestDelete_Draft | draft 状态，只删除数据库 |
| TestDelete_Generated | generated 状态，删除 MinIO + 数据库 |
| TestDelete_Built | built 状态，删除 Docker + MinIO + 数据库 |
| TestDelete_Deployed | deployed 状态，删除 K8s + Docker + MinIO + 数据库 |
| TestDelete_Published | published 状态，删除所有资源 |
| TestDelete_Unpublished | unpublished 状态，删除 Docker + MinIO + 数据库 |
| TestDelete_Error | error 状态，尝试清理所有可能资源 |
| TestDelete_NotFound | Agent 不存在，返回 404 |
| TestDelete_K8sFailed | K8s 删除失败，返回错误和已清理资源 |
| TestDelete_DockerFailed | Docker 删除失败，继续清理其他资源 |

### 8.2 MinIO 测试

| 用例 | 验证内容 |
|------|---------|
| TestDeleteByPrefix | 按前缀删除多个文件 |
| TestDeleteByPrefix_Empty | 前缀不存在，无操作 |
| TestPrefixExists_True | 前缀存在返回 true |
| TestPrefixExists_False | 前缀不存在返回 false |

### 8.3 Docker 测试

| 用例 | 验证内容 |
|------|---------|
| TestRemoveImage | 删除单个镜像 |
| TestRemoveAgentImages | 删除 Agent 所有版本镜像 |
| TestImageExists_True | 镜像存在返回 true |
| TestImageExists_False | 镜像不存在返回 false |

### 8.4 K8s 测试

| 用例 | 验证内容 |
|------|---------|
| TestSandboxExists_True | Sandbox 存在返回 true |
| TestSandboxExists_False | Sandbox 不存在返回 false |
| TestServiceExists_True | Service 存在返回 true |
| TestServiceExists_False | Service 不存在返回 false |
| TestIngressExists_True | Ingress 存在返回 true |
| TestIngressExists_False | Ingress 不存在返回 false |

## 9. 端到端测试

| 用例 | 初始状态 | 验证内容 |
|------|---------|---------|
| E5-1 | draft | 删除 draft 状态 Agent，只删除数据库 |
| E5-2 | generated | 删除 generated 状态，验证 MinIO 已清理 |
| E5-3 | built | 删除 built 状态，验证 Docker 镜像已清理 |
| E5-4 | deployed | 删除 deployed 状态，验证 K8s 资源已清理 |
| E5-5 | published | 删除 published 状态，验证所有资源已清理 |
| E5-6 | error | 删除 error 状态，尝试清理所有可能资源 |
| E5-7 | - | 删除不存在的 Agent，返回 404 |
