# Agent Manager 项目执行计划

---

## 项目背景

构建一个 **Agent 管理平台**，支持通过页面/JSON/YAML 配置 Agent，自动生成 DeepAgents 代码，打包为 Docker 镜像，部署到 Kubernetes 上的 agent-sandbox 隔离环境中运行，并管理 Agent 的发布/下线生命周期。

### 核心组件版本

| 组件 | 版本/地址 | 说明 |
|------|-----------|------|
| **agent-sandbox** | `kubernetes-sigs/agent-sandbox` v0.4.3 | K8s Sandbox CRD + Operator，管理隔离容器 |
| **DeepAgents** | `langchain-ai/deepagents` v0.5.5 | Python Agent 框架，基于 LangChain + LangGraph |
| **Kubernetes** | Kind (Kubernetes in Docker) | 本地 K8s 集群 |
| **GreatSQL** | 8.0.32-27 (端口3307) | 主数据库，持久化 Agent 元数据 |
| **MinIO** | RELEASE.2025-04-08 (端口9000/9001) | 对象存储，存储代码/镜像/Dockerfile |

### 技术栈

| 层级 | 技术选型 | 理由 |
|------|---------|------|
| **前端** | React + Next.js + TypeScript | 现代化 UI，服务端渲染能力 |
| **后端** | Go + Gin + GORM | 高性能，与 K8s 生态天然匹配 |
| **代码生成** | Python 子进程 | 利用 DeepAgents Python SDK 生态 |
| **镜像构建** | Docker SDK for Go | 程序化构建 Agent 镜像 |

---

## 一、环境搭建

### 1.1 Kubernetes 部署 (Kind)

**步骤：**

1. 安装 kubectl
2. 安装 Kind (Kubernetes in Docker)
3. 创建单节点 K8s 集群
4. 验证集群状态 (`kubectl get nodes`, `kubectl get pods -A`)

**验收标准：**
- `kubectl cluster-info` 返回正常
- 所有系统 Pod 处于 Running 状态

### 1.2 agent-sandbox 部署

**步骤：**

1. 下载 agent-sandbox v0.4.3 的 manifest：
   - 核心组件：`manifest.yaml` (Sandbox CRD + Controller)
   - 扩展组件：`extensions.yaml` (SandboxTemplate, SandboxClaim, SandboxWarmPool)
2. `kubectl apply` 部署到 K8s 集群
3. 验证 CRD 安装 (`kubectl get crd | grep sandbox`)
4. 验证 Controller 运行 (`kubectl get pods -n agent-sandbox-system`)
5. 创建测试 Sandbox 实例，确认 Pod 能正常启动

**验收标准：**
- CRD `sandboxes.agents.x-k8s.io` 存在
- agent-sandbox-controller Pod Running
- 能成功创建并运行一个 Sandbox 实例

### 1.3 数据库准备

**步骤：**

1. 在 GreatSQL (端口3307) 中创建 `agent_manager` 数据库
2. 创建专用用户 `agent_manager@'%'` 并授权
3. 不修改/不影响已有的 dify 系列数据库

**SQL 参考：**
```sql
CREATE DATABASE agent_manager CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'agent_manager'@'%' IDENTIFIED BY 'Agent@Manager2026';
GRANT ALL PRIVILEGES ON agent_manager.* TO 'agent_manager'@'%';
FLUSH PRIVILEGES;
```

### 1.4 MinIO Bucket 准备

**步骤：**

1. 在现有 MinIO (minioadmin/minioadmin, 端口9000) 中创建新 Bucket
2. Bucket 名称 `agent-manager`，用于存储：
   - Agent 生成的代码文件 (.py)
   - Dockerfile
   - 构建日志
3. 不影响 Dify 已有的 `dify` bucket

### 1.5 环境部署文档

编写 `docs/deployment.md`，内容覆盖：
- 系统架构图
- 各组件安装步骤
- 配置说明
- 常见故障排查
- 端口规划表

---

## 二、具体功能验证

### 2.1 JSON → DeepAgents 代码转换

**步骤：**

1. 编写 Python 代码生成模块 (`codegen/generator.py`)
2. 定义 Agent 配置 JSON Schema：
   ```json
   {
     "name": "my-agent",
     "description": "客服助手",
     "model": "qwen3.6-plus",
     "model_endpoint": "https://dashscope.aliyuncs.com/compatible-mode/v1",
     "api_key": "sk-xxx",
     "system_prompt": "你是一个友好的客服助手",
     "tools": ["web_search", "calculator"],
     "sub_agents": [],
     "memory": true,
     "max_iterations": 50
   }
   ```
3. 代码生成器读取 JSON 配置，生成完整的 DeepAgents Python 脚本 + Dockerfile
4. 生成代码上传到 MinIO

**验收标准：**
- 输入 JSON 配置，输出可运行的 DeepAgents Python 脚本
- 生成的脚本包含：模型初始化、工具注册、system_prompt 注入、API 暴露

### 2.2 构建镜像 & agent-sandbox 部署

**步骤：**

1. Go 后端调用 Docker SDK，使用生成的 Dockerfile 构建镜像
2. 镜像 tag 规则：`agent-manager/agent-{name}:{version}`
3. 生成 Sandbox CRD YAML，apply 到 K8s
4. 等待 Pod Ready
5. 记录部署状态到 MySQL

**验收标准：**
- 镜像成功构建并存在于 Docker 中
- Sandbox CRD 创建成功，Pod Running
- 能通过 Sandbox 暴露的地址访问 Agent

### 2.3 API 测试

**步骤：**

1. 编写测试脚本，向已部署的 Agent 发送 API 请求
2. 验证 Agent 能正确响应
3. 验证流式输出
4. 验证多轮对话

**验收标准：**
- Agent API 返回 200
- 响应内容符合预期
- 多轮对话上下文保持正常

---

## 三、功能开发

### 3.1 前端页面 (React + Next.js)

**页面规划：**

| 页面 | 路由 | 功能 |
|------|------|------|
| Dashboard | `/` | 概览：Agent 总数、运行中、已下线 |
| Agent 列表 | `/agents` | 分页表格，状态筛选，发布/下线操作 |
| Agent 创建 | `/agents/create` | 表单/JSON/YAML 三种创建方式 |
| Agent 详情 | `/agents/:id` | 基本信息、代码预览、部署状态、API 调用测试 |
| Agent 编辑 | `/agents/:id/edit` | 修改配置，重新生成代码 |
| 部署历史 | `/agents/:id/deployments` | 部署版本历史 |

### 3.2 后端 API (Go + Gin)

**API 设计：**

```
# Agent CRUD
POST   /api/v1/agents                  # 创建 Agent (接受 JSON/YAML/form)
GET    /api/v1/agents                  # Agent 列表 (分页、筛选)
GET    /api/v1/agents/:id              # Agent 详情
PUT    /api/v1/agents/:id              # 更新 Agent 配置
DELETE /api/v1/agents/:id              # 删除 Agent

# 代码生成
POST   /api/v1/agents/:id/generate      # 触发代码生成
GET    /api/v1/agents/:id/code          # 获取生成的代码

# 镜像构建 & 部署
POST   /api/v1/agents/:id/build         # 触发镜像构建
POST   /api/v1/agents/:id/deploy        # 部署到 agent-sandbox

# 发布/下线
POST   /api/v1/agents/:id/publish       # 发布上线
POST   /api/v1/agents/:id/unpublish     # 下线

# 部署状态
GET    /api/v1/agents/:id/status        # Agent 运行状态
GET    /api/v1/agents/:id/deployments   # 部署历史

# Agent API 代理 (调用运行中的 Agent)
POST   /api/v1/agents/:id/invoke        # 调用 Agent (透传请求)
POST   /api/v1/agents/:id/invoke/stream # 流式调用 Agent
```

### 3.3 数据库设计 (MySQL)

```sql
-- Agent 主表
CREATE TABLE agents (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    config      JSON NOT NULL,           -- Agent 配置 (JSON)
    config_type ENUM('form', 'json', 'yaml') DEFAULT 'form',
    status      ENUM('draft', 'generated', 'built', 'deployed', 'published', 'unpublished', 'error') DEFAULT 'draft',
    version     INT DEFAULT 1,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_name (name)
);

-- 代码生成记录
CREATE TABLE code_generations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT NOT NULL,
    version     INT NOT NULL,
    code_path   VARCHAR(512),            -- MinIO 中的代码路径
    dockerfile_path VARCHAR(512),        -- MinIO 中的 Dockerfile 路径
    status      ENUM('pending', 'running', 'success', 'failed') DEFAULT 'pending',
    error_msg   TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    INDEX idx_agent_version (agent_id, version)
);

-- 镜像构建记录
CREATE TABLE image_builds (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT NOT NULL,
    version     INT NOT NULL,
    image_tag   VARCHAR(256),
    status      ENUM('pending', 'building', 'success', 'failed') DEFAULT 'pending',
    build_log   TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    INDEX idx_agent_version (agent_id, version)
);

-- 部署记录
CREATE TABLE deployments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id        BIGINT NOT NULL,
    version         INT NOT NULL,
    sandbox_name    VARCHAR(128),        -- K8s Sandbox 资源名称
    sandbox_status  VARCHAR(64),         -- K8s Sandbox 状态
    endpoint_url    VARCHAR(512),        -- Agent API 访问地址
    status          ENUM('pending', 'deploying', 'running', 'stopped', 'failed') DEFAULT 'pending',
    deployed_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    unpublished_at  DATETIME,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    INDEX idx_agent_id (agent_id),
    INDEX idx_status (status)
);
```

### 3.4 功能 1：Agent 配置生成

**参考：** Claude Managed Agents 的设计理念
- Agent 配置标准化 (JSON Schema)
- UI 表单配置 (可视化)
- JSON/YAML 导入/导出
- 配置校验
- 一键生成 DeepAgents 代码

**实现：**
1. 前端：Monaco Editor 组件用于 JSON/YAML 编辑
2. 前端：可视化表单 (form 模式) 与代码模式切换
3. 后端：JSON Schema 校验
4. 后端：调用 Python codegen 模块生成代码
5. 预览生成的代码，确认后提交

**验收标准：**
- 通过页面表单填写配置 → 生成可执行的 DeepAgents Python 代码
- 上传 JSON/YAML 配置 → 解析并生成代码
- 代码可通过 `uv run` 直接在本地验证运行

### 3.5 功能 2：Agent 发布/下线

**发布流程：**
```
draft → 生成代码 → 构建镜像 → 部署到Sandbox → 健康检查通过 → published
```

**下线流程：**
```
published → 删除 Sandbox 资源 → 停止 Pod → unpublished (保留元数据/镜像)
```

**实现：**
1. 后端调用 K8s API 管理 Sandbox 资源
2. 发布：创建/更新 Sandbox CRD
3. 下线：删除 Sandbox CRD (保留 PVC/镜像以便重新发布)
4. 状态机管理，记录所有状态变更
5. 健康检查：发布后等待 Pod Ready，探活 Agent 健康端点

**验收标准：**
- 生成 Agent 代码 → 通过 agent-sandbox 部署成功
- 通过 Agent API 端点能成功调用
- 下线后 API 不可访问
- 重新发布后恢复可用

### 3.6 功能 3：基础设施集成

**MySQL 集成：**
- 使用 GORM 连接 GreatSQL (端口3307)
- 数据库 `agent_manager`，不影响 `dify` 等已有库
- 连接池配置

**MinIO 集成：**
- 使用 minio-go SDK
- Bucket: `agent-manager`
- 存储路径规则：`agents/{agent_id}/{version}/`
- 文件：`agent.py`, `Dockerfile`, `requirements.txt`

**LLM 集成 (阿里云 DashScope)：**
- 在生成的 Agent 代码中配置 qwen3.6-plus
- 端点：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- API Key：`sk-0440b76852944f019bb142a715bc2cab`
- 兼容 OpenAI API 格式

---

## 四、项目目录结构

```
/root/agent-manager/
├── backend/                    # Go 后端
│   ├── cmd/
│   │   └── server/
│   │       └── main.go         # 入口
│   ├── internal/
│   │   ├── handler/            # HTTP Handler
│   │   ├── service/            # 业务逻辑层
│   │   ├── model/              # 数据模型 (GORM)
│   │   ├── k8s/                # K8s 客户端封装
│   │   ├── docker/             # Docker 客户端封装
│   │   ├── minio/              # MinIO 客户端封装
│   │   └── codegen/            # Python 代码生成调用
│   ├── config/
│   │   └── config.yaml         # 配置文件
│   ├── go.mod
│   └── go.sum
├── frontend/                   # React 前端 (Next.js)
│   ├── app/                    # Next.js App Router
│   ├── components/             # React 组件
│   ├── lib/                    # 工具函数/API Client
│   ├── package.json
│   └── tsconfig.json
├── codegen/                    # Python 代码生成模块
│   ├── generator.py            # 核心生成器
│   ├── templates/              # Jinja2 模板
│   ├── schema/                 # JSON Schema 定义
│   └── requirements.txt
├── sandbox/                    # agent-sandbox 部署文件
│   └── kustomization.yaml
├── docs/
│   ├── deployment.md           # 部署文档
│   ├── api.md                  # API 文档
│   └── architecture.md         # 架构文档
├── docker-compose.yml          # 本地开发环境 (可选)
└── Makefile                    # 常用命令
```

---

## 五、执行顺序 & 时间估算

| 阶段 | 任务 | 预估时间 | 依赖 |
|------|------|---------|------|
| **一.1** | K8s (Kind) 部署 | 30min | 无 |
| **一.2** | agent-sandbox 部署 | 30min | 一.1 |
| **一.3** | MySQL 数据库准备 | 10min | 无 |
| **一.4** | MinIO Bucket 准备 | 10min | 无 |
| **一.5** | 部署文档编写 | 边做边写 | - |
| **二.1** | JSON→代码生成验证 | 1h | 一.3, 一.4 |
| **二.2** | 镜像构建+部署验证 | 1h | 二.1, 一.2 |
| **二.3** | API 测试验证 | 30min | 二.2 |
| **三.1** | 前端页面开发 | 4h | 二.3 |
| **三.2** | 后端 API 开发 | 4h | 二.3 |
| **三.3** | 数据库设计实现 | 30min | 一.3 |
| **三.4** | Agent 配置功能 | 2h | 三.2 |
| **三.5** | 发布/下线功能 | 2h | 三.2, 一.2 |
| **三.6** | 基础设施集成 | 1h | 一.3, 一.4 |
| **总** | | **约 17 小时** | |

---

## 六、风险 & 注意事项

1. **MinIO 冲突**：现有 `dify-minio` 的 bucket 和数据不能受影响，所有操作限定在 `agent-manager` bucket
2. **MySQL 冲突**：不修改已有的 `dify` 系列数据库，只新建 `agent_manager` 库
3. **端口冲突**：K8s 可能需要 NodePort，注意避免与 Dify 的 80/443 端口冲突
4. **资源限制**：机器 4核/8GB，K8s + Dify + agent-sandbox 需合理分配资源
5. **API Key 安全**：LLM API Key 不应硬编码在前端，通过后端配置管理

---

## 七、下一步

请审核以上计划，确认后我将按顺序执行：
1. 先执行「一、环境搭建」全部内容
2. 再执行「二、功能验证」全部内容
3. 最后执行「三、功能开发」全部内容

审核要点：
- [ ] 技术栈选择是否 OK
- [ ] 数据库表设计是否满足需求
- [ ] API 设计是否合理
- [ ] 前端页面规划是否齐全
- [ ] 验收标准是否清晰
- [ ] 执行顺序是否需要调整
