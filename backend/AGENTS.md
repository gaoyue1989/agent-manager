# Backend — AGENTS.md

## 二级模块概述

Go 后端服务，基于 Gin + GORM，提供 REST API，管理 Agent 全生命周期：配置创建 → 代码生成 → 镜像构建 → K8s 部署 → 发布/下线。

## 基础规则

严格按用户需求执行，不擅自加功能、不脑补逻辑、不画蛇添足；只输出可直接运行的完整代码，拒绝伪代码。需求模糊主动提问，输出无多余闲聊，全程对齐项目现有代码风格、目录结构、命名规范。

## 开发流程

先看项目目录和现有关联代码，理清逻辑再编码；只修改指定文件与逻辑，不改动无关代码、不整文件重写。

## 代码规范

命名语义化，禁止硬编码密钥、魔法数字；网络、IO、数据库操作必做判空、边界校验和异常捕获；优先复用现有工具，不私自升级框架、乱加依赖；复杂逻辑加中文注释。

## 输出格式

代码块标语言、改文件标路径；保留配置原有缩进，不搞多余排版；完工自动清理调试日志、临时测试代码。

## 安全约束

不随意改 Git、Docker 及系统配置；禁用高危删除命令，敏感信息用占位符；不做删核心文件、清依赖等破坏性操作，环境报错先给排查方案。

---

## 目录结构

```
backend/
├── cmd/server/main.go          # 入口，路由注册，依赖注入
├── config/config.go            # 环境变量配置加载
├── go.mod / go.sum             # Go 模块依赖
└── internal/
    ├── handler/                # HTTP 层 (Gin Handler)
    │   ├── agent.go            # Agent CRUD + 代码生成端点
    │   ├── deploy.go           # 构建/部署/发布/下线端点
    │   └── deploy_test.go      # 集成测试
    ├── service/                # 业务逻辑层
    │   ├── agent.go            # Agent 生命周期管理
    │   ├── deploy.go           # 构建-部署-发布管线
    │   └── deploy_test.go      # 单元测试
    ├── model/
    │   └── models.go           # GORM 数据模型 + 状态枚举
    ├── k8s/
    │   ├── sandbox.go          # kubectl 封装的 Sandbox CRD 客户端
    │   └── sandbox_test.go     # 单元测试
    ├── docker/
    │   └── builder.go          # Shell 调用 docker CLI 构建器
    ├── minio/
    │   └── storage.go          # MinIO 对象存储客户端
    └── codegen/
        └── runner.go           # Python 代码生成子进程调用器
```

---

## 入口与启动流程

`cmd/server/main.go` 执行以下启动流程：
1. `config.Load()` 从环境变量加载所有配置
2. 连接 MySQL (GORM)，执行 `AutoMigrate` 自动建表
3. 初始化 MinIO Storage (自动创建 bucket)
4. 初始化 CodeGen Runner (Python 子进程调用)
5. 初始化 K8s SandboxClient (kubectl 封装)
6. 初始化 Docker Builder (Shell 调用 docker CLI)
7. 创建 AgentService / DeployService 并注入依赖
8. 注册 Gin 路由 (CORS 全开)，启动 HTTP 服务

## 依赖 (go.mod)

```
gin-gonic/gin v1.10.0          # HTTP 框架
gin-contrib/cors v1.7.2        # CORS 中间件
gorm.io/gorm v1.30.0           # ORM
gorm.io/driver/mysql v1.5.7    # MySQL 驱动
minio-go/v7 v7.0.73            # MinIO SDK
```

## 配置 (config/config.go)

所有配置通过环境变量注入，无配置文件依赖：

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `SERVER_PORT` | 服务端口 | `8080` |
| `MYSQL_DSN` | 数据库连接串 | `agent_manager:...@tcp(127.0.0.1:3307)/agent_manager?...` |
| `MINIO_ENDPOINT` | MinIO 地址 | `127.0.0.1:9000` |
| `MINIO_ACCESS_KEY` | MinIO 访问密钥 | `minioadmin` |
| `MINIO_SECRET_KEY` | MinIO 密钥 | `minioadmin` |
| `MINIO_BUCKET` | MinIO Bucket | `agent-manager` |
| `KUBECONFIG` | K8s 配置路径 | `~/.kube/config` |
| `LOCAL_REGISTRY` | Docker 本地仓库 | `localhost:5000` |
| `CODEGEN_SCRIPT` | 生成器脚本 | `../codegen/generator.py` |
| `CODEGEN_PYTHON` | Python 解释器 | `python3` |
| `LLM_MODEL` | 默认模型 | `qwen3.6-plus` |
| `LLM_ENDPOINT` | LLM API 端点 | DashScope 地址 |
| `LLM_API_KEY` | LLM API Key | (DashScope key) |

## 数据模型 (internal/model/models.go)

四张表，Agent 为根实体，其余三张通过 `AgentID` 外键关联，`ON DELETE CASCADE`：

```
Agent (1) ──┬── (N) CodeGeneration   # 代码生成记录
            ├── (N) ImageBuild        # 镜像构建记录
            └── (N) Deployment         # 部署记录
```

**Agent 状态机：**
```
draft → generated → built → deployed → published
                              ↑         ↓
                              └─ unpublished ←─┘
```

- `Agent.Config` 字段以 JSON string 形式存储完整配置
- `Agent.ConfigType` 枚举：`form` / `json` / `yaml`
- 所有子表通过 `(AgentID, Version)` 复合索引加速查询

## API 路由

全部挂载于 `/api/v1`，共 15 个端点：

**Agent CRUD & 代码生成 (AgentHandler: handler/agent.go):**
```
POST   /agents              # 创建 Agent
GET    /agents              # Agent 列表 (?status=&offset=&limit=)
GET    /agents/:id          # Agent 详情
PUT    /agents/:id          # 更新 Agent 配置 (version++)
DELETE /agents/:id          # 删除 Agent (级联删除)
POST   /agents/:id/generate  # 触发代码生成
GET    /agents/:id/code      # 获取生成的代码
GET    /agents/:id/deployments # 部署历史
```

**构建 & 部署 & 发布 (DeployHandler: handler/deploy.go):**
```
POST   /agents/:id/build     # 构建 Docker 镜像
POST   /agents/:id/deploy    # 部署到 K8s Sandbox
POST   /agents/:id/publish   # 发布上线 (deploy + 状态置为 published)
POST   /agents/:id/unpublish # 下线 (删除 Sandbox)
GET    /agents/:id/image-info # 镜像信息
GET    /agents/:id/pod-status # Pod 运行状态
POST   /agents/:id/chat      # 与 Agent 对话 (kubectl exec curl)
```

## 核心业务逻辑

### AgentService (service/agent.go)

| 方法 | 说明 |
|------|------|
| `Create(configJSON, configType)` | 创建 Agent 并保存到 MySQL |
| `GetByID(id)` | 按 ID 查询单个 Agent |
| `List(status, offset, limit)` | 分页列表，支持按 status 筛选 |
| `Update(id, configJSON)` | 更新配置，version 自增 |
| `Delete(id)` | 删除 Agent (级联删除子记录) |
| `GenerateCode(id)` | 调用 CodeGen Runner，生成代码存入 MinIO，写入 CodeGeneration 记录，状态 → `generated` |
| `GetCode(id)` | 从 MinIO 拉取已生成的代码内容 |
| `GetDeployments(id)` | 查询该 Agent 的全部部署记录 |
| `GetLatestDeployment(id)` | 查询最新部署记录 |

### DeployService (service/deploy.go)

| 方法 | 说明 |
|------|------|
| `BuildImage(id)` | 从 MinIO 下载代码 → Docker build → tag → push → 写入 ImageBuild，状态 → `built` |
| `Deploy(id)` | 通过 K8s SandboxClient 创建 Sandbox CRD，写入 Deployment，状态 → `deployed` |
| `Publish(id)` | 调用 Deploy + 状态 → `published` |
| `Unpublish(id)` | 删除 Sandbox，状态 → `unpublished` |
| `GetImageInfo(id)` | 查询最新镜像标签、仓库地址、构建状态 |
| `GetPodStatus(id)` | 查询 Pod 运行状态 (Ready/Status/IP/Restarts) |
| `ChatWithAgent(id, message, history)` | 通过 `kubectl exec` 向 Pod 内发送 curl 请求 |

## 基础设施客户端

### K8s (internal/k8s/sandbox.go)
- 使用 `kubectl` CLI 而非 client-go SDK
- CRD: `agents.x-k8s.io/v1alpha1` Sandbox 资源
- `CreateSandbox(name, image)` — 创建 Sandbox CRD
- `DeleteSandbox(name)` — 删除 Sandbox CRD
- `GetPodStatus(sandboxName)` — 解析 `kubectl get pod` 输出
- `GetPodStatusJSON(sandboxName)` — 返回 kubectl JSON 输出

### Docker (internal/docker/builder.go)
- Shell 调用 `docker login` / `docker build` / `docker tag` / `docker push`
- 从 MinIO 下载文件到临时目录 (`os.MkdirTemp`)，构建完成后清理
- 构建成功后返回镜像完整标签 `registry/imageName:agentID-version`

### MinIO (internal/minio/storage.go)
- `PutFile(objectName, data)` / `GetFile(objectName)` 以字节流操作
- 初始化时自动创建 bucket (若不存在)
- 存储路径规则: `{prefix}/agent.py`, `{prefix}/Dockerfile`, `{prefix}/requirements.txt`

### CodeGen (internal/codegen/runner.go)
- `Run(config)` — 将配置 JSON 通过 stdin 传入 Python 脚本，解析 stdout，返回 `map[string]string` (文件名 → 内容)
- `RunAndStore(config, prefix)` — 生成后直接存入 MinIO
