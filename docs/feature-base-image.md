# Feature: 基础镜像构建

## 1. 需求概述

为加快 Agent 镜像构建速度，预先构建一个包含所有 pip 依赖的基础镜像。Agent 构建时基于此基础镜像，只需复制 `agent.py` 和 `skills/`，无需每次执行 `pip install`。

## 2. 现状分析

### 2.1 当前 Dockerfile

```dockerfile
FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt  # 每次构建都执行 pip install
COPY agent.py .
EXPOSE 8000
ENV PYTHONUNBUFFERED=1
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1
CMD ["python", "-u", "agent.py"]
```

### 2.2 问题

- 每次 `docker build` 都执行 `pip install`，耗时约 30-60 秒
- 依赖固定，无需每次重新安装

## 3. 设计方案

### 3.1 基础镜像

**镜像名称**: `{registry}/agent-base:latest`

**Dockerfile.base**:
```dockerfile
FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

ENV PYTHONUNBUFFERED=1
```

**requirements.txt** (预装所有可能依赖):
```
deepagents>=0.5.0
langchain>=1.0.0
langchain-openai>=1.0.0
langchain-mcp-adapters>=0.1.0
fastapi>=0.100.0
uvicorn>=0.30.0
pydantic>=2.0.0
```

### 3.2 Agent Dockerfile (生成)

**无 Skills**:
```dockerfile
FROM {registry}/agent-base:latest

WORKDIR /app

COPY agent.py .

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["python", "-u", "agent.py"]
```

**有 Skills**:
```dockerfile
FROM {registry}/agent-base:latest

WORKDIR /app

COPY agent.py .
COPY skills/ /skills/

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["python", "-u", "agent.py"]
```

### 3.3 构建流程

```
1. 启动时检查基础镜像是否存在
2. 不存在则自动构建并推送
3. Agent 构建时直接使用基础镜像
```

## 4. 实现方案

### 4.1 配置扩展 (config/config.go)

新增环境变量:
```go
type Config struct {
    // ...
    BaseImageName  string  // 基础镜像名称，默认 agent-base:latest
    BuildBaseImage bool    // 是否自动构建基础镜像，默认 true
}
```

### 4.2 Docker Builder 扩展 (docker/builder.go)

新增方法:
```go
func (b *Builder) BuildBaseImage(registry string) (string, error)
func (b *Builder) CheckImageExists(imageTag string) bool
func (b *Builder) BuildAgent(baseImage, localTag, remoteTag, prefix string, storage *minio.Storage) (string, error)
```

### 4.3 启动流程扩展 (cmd/server/main.go)

```go
// 初始化 Docker Builder 后
if cfg.BuildBaseImage {
    baseImageTag := fmt.Sprintf("%s/agent-base:latest", cfg.LocalRegistry)
    if !builder.CheckImageExists(baseImageTag) {
        log.Println("Building base image...")
        if _, err := builder.BuildBaseImage(cfg.LocalRegistry); err != nil {
            log.Fatalf("failed to build base image: %v", err)
        }
    }
}
```

### 4.4 CodeGen 扩展 (codegen/generator.py)

修改 `_render_dockerfile`:
```python
def _render_dockerfile(name: str, has_skills: bool = False, base_image: str = "") -> str:
    from_image = base_image if base_image else "python:3.12-slim"
    # ...
```

### 4.5 Service 层修改 (service/deploy.go)

```go
func (s *DeployService) BuildImage(agentID uint) (*model.ImageBuild, error) {
    // 使用基础镜像构建
    baseImage := fmt.Sprintf("%s/agent-base:latest", s.registry)
    // ...
}
```

## 5. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `backend/config/config.go` | 修改 | 新增 BaseImageName、BuildBaseImage 配置 |
| `backend/internal/docker/builder.go` | 修改 | 新增 BuildBaseImage、CheckImageExists 方法 |
| `backend/internal/docker/Dockerfile.base` | 新增 | 基础镜像 Dockerfile |
| `backend/internal/docker/requirements-base.txt` | 新增 | 基础镜像依赖文件 |
| `backend/cmd/server/main.go` | 修改 | 启动时构建基础镜像 |
| `backend/internal/service/deploy.go` | 修改 | 使用基础镜像构建 |
| `codegen/generator.py` | 修改 | Dockerfile 渲染支持基础镜像参数 |

## 6. 性能对比

| 场景 | 当前耗时 | 优化后耗时 | 提升 |
|------|---------|-----------|------|
| 首次构建 | ~60s | ~60s (构建基础镜像) | - |
| 后续构建 | ~60s | ~5s | 91% |
| 依赖更新 | ~60s | ~60s (重建基础镜像) | - |

## 7. 单元测试

### 7.1 Docker Builder 测试

| 用例 | 验证内容 |
|------|---------|
| TestBuildBaseImage | 构建基础镜像成功 |
| TestCheckImageExists_True | 镜像存在返回 true |
| TestCheckImageExists_False | 镜像不存在返回 false |
| TestBuildAgent_WithBaseImage | 使用基础镜像构建 Agent |

### 7.2 Service 测试

| 用例 | 验证内容 |
|------|---------|
| TestBuildImage_WithBaseImage | 构建使用基础镜像 |
| TestBuildImage_BaseImageNotExists | 基础镜像不存在时自动构建 |

## 8. 端到端测试

| 用例 | 验证内容 |
|------|---------|
| E6-1 | 启动时自动构建基础镜像 |
| E6-2 | Agent 构建使用基础镜像 |
| E6-3 | 构建时间 < 10s |
| E6-4 | 构建镜像运行正常 |

## 9. 维护指南

### 9.1 更新依赖

当需要添加新依赖时：
1. 更新 `backend/internal/docker/requirements-base.txt`
2. 删除旧基础镜像: `docker rmi {registry}/agent-base:latest`
3. 重启后端服务，自动重建基础镜像

### 9.2 手动构建基础镜像

```bash
cd backend/internal/docker
docker build -f Dockerfile.base -t localhost:5000/agent-base:latest .
docker push localhost:5000/agent-base:latest
```
