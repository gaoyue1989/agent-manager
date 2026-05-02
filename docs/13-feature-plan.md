# 新增功能实施方案 (修订版)

> 本文档涵盖 6 项新增功能的详细设计、文件变更清单和实现方案。
> 确认后执行代码修改，修改完成后需通过单元测试再进行端到端测试。

---

## 1. 配置页面: 默认 Tool 选择 + MCP 配置 + Skill 配置 + 示例

### 当前状态
- 前端创建页面有表单/JSON/YAML 三种模式，但表单模式只有 name/description/model/endpoint/api_key/system_prompt 六个字段
- codegen 的 `tools` 字段只是字符串数组，`sub_agents` 数组未真正接入 `create_deep_agent()`
- 无 MCP 配置字段，无 Skill 配置字段
- 前端 JSON/YAML 模式无示例模板

### 1.1 DeepAgents 内置默认工具

DeepAgents v0.5.5 的 `create_deep_agent()` 内置以下 9 个默认工具：

| 工具名 | 来源中间件 | 功能说明 |
|--------|----------|---------|
| `write_todos` | TodoListMiddleware | 管理任务清单 |
| `ls` | FilesystemMiddleware | 列出目录内容 |
| `read_file` | FilesystemMiddleware | 读取文件 |
| `write_file` | FilesystemMiddleware | 写入/创建文件 |
| `edit_file` | FilesystemMiddleware | 编辑文件（查找替换） |
| `glob` | FilesystemMiddleware | 按模式匹配文件 |
| `grep` | FilesystemMiddleware | 搜索文件内容 |
| `execute` | FilesystemMiddleware | 执行 Shell 命令（需 Sandbox 后端） |
| `task` | SubAgentMiddleware | 调用子 Agent |

用户在前端可选择启用/禁用其中任意工具。禁用的工具通过 `create_deep_agent()` 的 `excluded_tools` 机制排除，或在生成代码时使用 middleware 的 `excluded_tools` 参数。

### 1.2 Skill 包上传与解析

Skill 遵循 [Agent Skills 规范](https://agentskills.io/specification)，目录结构：

```
skills/
├── web-research/
│   ├── SKILL.md          # 必填: YAML frontmatter + Markdown 指令
│   └── helper.py         # 可选: 辅助脚本
└── code-review/
    ├── SKILL.md
    └── review_checklist.md
```

`SKILL.md` 格式：
```markdown
---
name: web-research
description: 结构化网页研究技能，用于进行深入的网络调研
license: MIT
compatibility: 需要网络连接
allowed-tools: grep glob read_file write_file
---

# Web Research Skill

## 何时使用
- 用户要求研究某个话题...
```

**上传流程**：
1. 前端提供 `.zip` 上传组件
2. 后端解压 zip → 遍历目录找 `SKILL.md` → 解析 YAML frontmatter → 提取元数据 (name, description, license, compatibility, allowed_tools)
3. 将 Skill 文件存入 MinIO，路径：`skills/{agent_id}/{skill_name}/`
4. 返回解析后的元数据给前端展示
5. 代码生成时，将 Skills 文件包含进 Docker 镜像的 `/skills/` 目录
6. 生成的 `agent.py` 使用 `FilesystemBackend(root_dir="/skills")` + `skills=["/skills/"]` 调用 `create_deep_agent()`

### 1.3 MCP 配置

MCP（Model Context Protocol）配置作为可选外部工具源：

```json
{
  "mcp_config": {
    "url": "http://mcp-server:8001/sse",
    "transport": "sse",
    "headers": {}
  }
}
```

代码生成时，MCP 工具通过 `langchain_mcp_adapters` 加载并注入到 agent。

### 1.4 前端实现

#### 表单模式新增区域：
- **默认工具选择**：9 个 DeepAgents 内置工具的 checkbox 多选组，附带每个工具的功能提示
- **MCP 配置**：可折叠区域，含 URL、Transport（SSE/STDIO 下拉）、Headers（键值对编辑器）
- **Skill 上传**：
  - `.zip` 文件上传按钮 + 拖拽区域
  - 上传后自动展示解析的 Skill 元数据列表（名称、描述、标签）
  - 支持删除已上传的 Skill
- 每个配置区域顶部提供"填入示例"按钮

#### JSON/YAML 模式新增：
- textarea 上方提供"完整示例"、"最小示例"两个按钮，点击填入对应模板

### 1.5 前端示例模板

**JSON 完整示例**:
```json
{
  "name": "my-agent",
  "description": "一个支持 MCP 和多技能的 Agent",
  "model": "qwen3.6-plus",
  "model_endpoint": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "api_key": "sk-****",
  "system_prompt": "你是一个有用的 AI 助手。",
  "enabled_tools": ["write_todos", "ls", "read_file", "write_file", "edit_file", "glob", "grep", "execute", "task"],
  "excluded_tools": [],
  "mcp_config": {
    "url": "http://localhost:8001/sse",
    "transport": "sse",
    "headers": {}
  },
  "memory": true,
  "max_iterations": 50
}
```

**JSON 最小示例**:
```json
{
  "name": "simple-agent",
  "description": "最简 Agent 配置",
  "model": "qwen3.6-plus",
  "system_prompt": "你是一个有用的 AI 助手。",
  "enabled_tools": []
}
```

**YAML 完整示例**:
```yaml
name: my-agent
description: 一个支持 MCP 和多技能的 Agent
model: qwen3.6-plus
model_endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1
api_key: sk-xxx
system_prompt: 你是一个有用的 AI 助手。
enabled_tools:
  - write_todos
  - ls
  - read_file
  - write_file
  - edit_file
  - glob
  - grep
  - execute
  - task
excluded_tools: []
mcp_config:
  url: http://localhost:8001/sse
  transport: sse
  headers: {}
memory: true
max_iterations: 50
```

**YAML 最小示例**:
```yaml
name: simple-agent
description: 最简 Agent 配置
model: qwen3.6-plus
system_prompt: 你是一个有用的 AI 助手。
```

### 1.6 后端接口新增

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/skills/upload` | 上传 .zip Skill 包，解析并返回元数据 |
| GET | `/api/v1/skills/:agent_id` | 获取已上传的 Skill 元数据列表 |
| DELETE | `/api/v1/skills/:agent_id/:skill_name` | 删除指定 Skill |

### 涉及文件
| 文件 | 变更 |
|------|------|
| `codegen/schema/agent_config.json` | 新增 enabled_tools, excluded_tools, mcp_config 属性 |
| `codegen/generator.py` | 读取新字段，生成 excluded_tools 配置、MCP 客户端代码、Skills 集成 |
| `frontend/src/app/agents/create/page.tsx` | 新增工具多选/MCP/Skill 上传表单区 + 示例按钮 |
| `frontend/src/app/agents/[id]/page.tsx` | 详情页展示新字段 |
| `frontend/src/lib/api.ts` | 新增 skills.upload / skills.list / skills.delete |
| `backend/internal/handler/agent.go` | 新增 Skill 上传/查询/删除端点 |
| `backend/internal/service/agent.go` | 新增 Skill zip 解析、MinIO 存储逻辑 |
| `backend/go.mod` | 添加 yaml.v3 (YAML 解析) |

---

## 2. K8s Ingress: Agent 对外暴露 + 注册/删除

### 当前状态
- Agent 仅通过 `kubectl exec curl` 通信，无外部 HTTP 访问
- Sandbox CRD 创建 Pod 后无 Service/Ingress
- 发布/下线时只操作 Sandbox CRD

### 实现方案

#### 2.1 扩展 K8s Client（backend/internal/k8s/sandbox.go）
新增方法：
- `CreateService(name, agentID uint)` — 创建 Service 指向 Sandbox Pod
- `DeleteService(name)` — 删除 Service
- `CreateIngress(name, agentID uint)` — 创建 Ingress，path = `/agent/{id}`
- `DeleteIngress(name)` — 删除 Ingress

Sandbox Pod 的 label 是 `agents.x-k8s.io/sandbox-name-hash`（hash 值为 pod 名），由于 Sandbox CRD 创建的 Pod 标签由 controller 管理，我们改用直接匹配 sandbox name 的方式：通过 `kubectl get pod` 找到 Pod 并获取其 labels，然后创建匹配的 Service。

或者更简单的方案：使用 Sandbox CRD 直接创建的 Service。

Service 模板 (ClusterIP)：
```yaml
apiVersion: v1
kind: Service
metadata:
  name: agent-{id}-svc
  namespace: default
spec:
  selector:
    app: agent-{id}
  ports:
  - port: 8000
    targetPort: 8000
```

Ingress 模板：
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: agent-{id}-ingress
  namespace: default
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  ingressClassName: nginx
  rules:
  - http:
      paths:
      - path: /agent/{id}
        pathType: Prefix
        backend:
          service:
            name: agent-{id}-svc
            port:
              number: 8000
```

#### 2.2 修改 DeployService
- `Deploy()`: Sandbox CRD 创建时额外添加 label `app: agent-{id}` 到 podTemplate
- `Publish()`: 创建 Service → 创建 Ingress → 记录 `endpoint_url = http://{INGRESS_HOST}/agent/{id}`
- `Unpublish()`: 删除 Ingress → 删除 Service → 删除 Sandbox

#### 2.3 Handler Chat 端点
- `Chat()`: 优先通过 endpoint_url (Ingress) HTTP 请求，fallback 到 `kubectl exec`

#### 2.4 配置新增
| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `INGRESS_HOST` | Ingress 访问地址 | `localhost` |
| `INGRESS_ENABLED` | 是否创建 Ingress | `true` |

### 涉及文件
| 文件 | 变更 |
|------|------|
| `backend/internal/k8s/sandbox.go` | 新增 Service/Ingress CRUD + Pod label 查询 |
| `backend/internal/service/deploy.go` | Publish/Unpublish 集成 Ingress 生命周期 |
| `backend/config/config.go` | 新增 INGRESS_HOST, INGRESS_ENABLED |
| `backend/internal/handler/deploy.go` | Chat 端点改用 Ingress URL |
| `backend/internal/model/models.go` | endpoint_url 字段长度扩展至 1024 |

---

## 3. Nginx 反向代理: 统一端口 8911

需要支持两种模式：

### 3.1 模式 A: Dev 本地开发 — 修改本地 Nginx

若开发机上已安装 Nginx（如通过 `apt install nginx` 或宿主自带），在本地 Nginx 配置目录添加站点配置文件。如果本地未安装 Nginx，`make dev` 可提示安装或跳过。

**配置路径**: `docker/nginx/agent-manager.conf`（用于本地 Nginx 软链接或 include）

```nginx
# Agent Manager — 本地开发反向代理 (8911)
# 用法: sudo ln -s $(pwd)/docker/nginx/agent-manager.conf /etc/nginx/sites-enabled/
#       sudo nginx -t && sudo nginx -s reload

server {
    listen 8911;

    # 前端 (Next.js dev server)
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 后端 API (Go dev server)
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }

    # Agent Ingress 请求转发到 K8s Ingress Controller
    # 若 K8s Ingress NodePort 为 30080，则转发到: http://127.0.0.1:30080
    location /agent/ {
        proxy_pass http://127.0.0.1:30080;
        proxy_set_header Host $host;
        proxy_http_version 1.1;
    }
}
```

**Makefile dev 命令**:
```makefile
dev-nginx-setup:
	@echo "本地 Nginx 配置: docker/nginx/agent-manager.conf"
	@echo "手动执行: sudo ln -s $$(pwd)/docker/nginx/agent-manager.conf /etc/nginx/sites-enabled/"
	@echo "       && sudo nginx -t && sudo nginx -s reload"
```

### 3.2 模式 B: Docker Compose — 自动创建 Nginx 容器

Docker Compose 中新增独立的 `nginx` 服务容器，使用 `nginx:alpine` 镜像，挂载反向代理配置文件。

**配置路径**: `docker/nginx/default.conf`（Docker 容器内的 Nginx 配置）

```nginx
server {
    listen 8911;

    # 前端 (容器内部通信)
    location / {
        proxy_pass http://frontend:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 后端 API
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
    }

    # Agent 请求转发到 K8s Ingress (通过宿主机网络)
    # 使用 host.docker.internal 或宿主机 IP 访问 K8s NodePort
    location /agent/ {
        proxy_pass http://host.docker.internal:30080;
        proxy_set_header Host $host;
        proxy_http_version 1.1;
    }
}
```

**Docker Compose 中的 nginx 服务**:
```yaml
nginx:
  image: nginx:alpine
  ports:
    - "8911:8911"
  volumes:
    - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
  depends_on:
    - frontend
    - backend
  networks:
    - agent-net
  extra_hosts:
    - "host.docker.internal:host-gateway"   # 访问宿主机 K8s NodePort
```

### 3.3 两套配置差异对比

| 差异点 | 模式 A (Dev 本地) | 模式 B (Docker) |
|--------|-------------------|-----------------|
| 配置文件 | `docker/nginx/agent-manager.conf` | `docker/nginx/default.conf` |
| proxy_pass 目标 | `http://127.0.0.1:3000/8080` | `http://frontend:3000/backend:8080` |
| K8s Ingress 代理 | `http://127.0.0.1:30080` | `http://host.docker.internal:30080` |
| Nginx 运行方式 | 宿主 Nginx 进程 | Docker 容器 |
| 启动方式 | 手动 `ln -s` + `nginx -s reload` | `docker compose up -d` 自动 |

### 3.4 前端 API 地址统一
- `NEXT_PUBLIC_API_URL` 默认改为 `/api/v1`（两个模式统一通过 Nginx 代理）
- `next.config.ts` 添加 `output: 'standalone'` 用于 Docker 部署

### 涉及文件
| 文件 | 变更 |
|------|------|
| `docker/nginx/agent-manager.conf` | **新增**: Dev 本地 Nginx 配置 |
| `docker/nginx/default.conf` | **新增**: Docker 容器 Nginx 配置 (与 6. Docker Compose 共用) |
| `frontend/src/lib/api.ts` | 修改: BASE URL 改为 `/api/v1` |
| `frontend/next.config.ts` | 修改: 添加 output: 'standalone' |
| `Makefile` | 修改: 新增 `dev-nginx-setup` target |
| `docker/docker-compose.yml` | 修改: 添加 nginx 服务 + extra_hosts |

---

## 4. Dockerfile: 前后端镜像构建

### 4.1 后端 Dockerfile（backend/Dockerfile）
多阶段构建（Go 编译 + Alpine 运行），包含 kubectl 和 Python codegen 环境：
```dockerfile
FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o server ./cmd/server

FROM alpine:3.19
RUN apk add --no-cache ca-certificates curl kubectl python3 py3-pip && \
    python3 -m venv /opt/codegen-venv && \
    /opt/codegen-venv/bin/pip install --no-cache-dir \
      deepagents>=0.5.0 langchain langchain-openai fastapi uvicorn pydantic pyyaml
COPY --from=builder /app/server /usr/local/bin/server
ENV CODEGEN_PYTHON=/opt/codegen-venv/bin/python3
EXPOSE 8080
CMD ["server"]
```

### 4.2 前端 Dockerfile（frontend/Dockerfile）
```dockerfile
FROM node:22-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
```

### 涉及文件
| 文件 | 变更 |
|------|------|
| `backend/Dockerfile` | 新增 |
| `frontend/Dockerfile` | 新增 |
| `frontend/next.config.ts` | 添加 output: 'standalone' |

---

## 5. Makefile: 开发/构建/部署命令

```makefile
.PHONY: dev dev-backend dev-frontend dev-nginx-setup
.PHONY: build build-backend build-frontend
.PHONY: docker-build docker-up docker-down docker-logs
.PHONY: test test-backend test-e2e
.PHONY: lint lint-backend lint-frontend
.PHONY: clean

# === 开发 ===
dev: dev-backend dev-frontend

dev-backend:
	cd backend && go run ./cmd/server

dev-frontend:
	cd frontend && npm run dev

dev-nginx-setup:
	@echo "=== 本地开发 Nginx 反向代理配置 ==="
	@echo "配置文件: docker/nginx/agent-manager.conf"
	@echo ""
	@echo "手动执行以下命令启用:"
	@echo "  sudo ln -s $$(pwd)/docker/nginx/agent-manager.conf /etc/nginx/conf.d/agent-manager.conf"
	@echo "  sudo nginx -t && sudo nginx -s reload"
	@echo ""
	@echo "然后通过 http://localhost:8911 访问"

# === 构建 ===
build: build-backend build-frontend

build-backend:
	cd backend && CGO_ENABLED=0 go build -o bin/server ./cmd/server

build-frontend:
	cd frontend && npm run build

# === Docker 镜像 ===
docker-build-backend:
	docker build -t agent-manager-backend:latest ./backend

docker-build-frontend:
	docker build -t agent-manager-frontend:latest ./frontend

docker-build: docker-build-backend docker-build-frontend

# === Docker Compose ===
docker-up:
	docker compose -f docker/docker-compose.yml up -d --build

docker-down:
	docker compose -f docker/docker-compose.yml down

docker-logs:
	docker compose -f docker/docker-compose.yml logs -f

# === 测试 ===
test: test-backend test-e2e

test-backend:
	cd backend && go test ./internal/... -v

test-e2e:
	node e2e/e2e-test.js

# === lint ===
lint: lint-backend lint-frontend

lint-backend:
	cd backend && go vet ./...

lint-frontend:
	cd frontend && npm run lint

# === 清理 ===
clean:
	cd backend && rm -rf bin/
	docker compose -f docker/docker-compose.yml down -v
```

### 涉及文件
| 文件 | 变更 |
|------|------|
| `Makefile` | 新增 |

---

## 6. Docker Compose: 容器化部署

### 目录结构
```
docker/
├── docker-compose.yml
├── nginx/
│   └── default.conf
└── mysql/
    └── init.sql
```

### docker-compose.yml
```yaml
version: '3.8'
services:
  nginx:
    image: nginx:alpine
    ports:
      - "8911:8911"
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on:
      - frontend
      - backend
    networks:
      - agent-net
    extra_hosts:
      - "host.docker.internal:host-gateway"

  frontend:
    build:
      context: ../frontend
      dockerfile: Dockerfile
    environment:
      - NODE_ENV=production
      - NEXT_PUBLIC_API_URL=/api/v1
    depends_on:
      - backend
    networks:
      - agent-net

  backend:
    build:
      context: ../backend
      dockerfile: Dockerfile
    environment:
      - SERVER_PORT=8080
      - MYSQL_DSN=agent_manager:Agent@Manager2026@tcp(mysql:3306)/agent_manager?charset=utf8mb4&parseTime=True&loc=Local
      - MINIO_ENDPOINT=minio:9000
      - MINIO_ACCESS_KEY=minioadmin
      - MINIO_SECRET_KEY=minioadmin
      - INGRESS_HOST=localhost
      - INGRESS_ENABLED=true
      - CODEGEN_PYTHON=/opt/codegen-venv/bin/python3
    depends_on:
      mysql:
        condition: service_healthy
      minio:
        condition: service_started
    networks:
      - agent-net

  mysql:
    image: greatsql/greatsql:8.0.32-25
    ports:
      - "3307:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=root123
      - MYSQL_DATABASE=agent_manager
      - MYSQL_USER=agent_manager
      - MYSQL_PASSWORD=Agent@Manager2026
    volumes:
      - mysql_data:/var/lib/mysql
      - ./mysql/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - agent-net

  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      - MINIO_ROOT_USER=minioadmin
      - MINIO_ROOT_PASSWORD=minioadmin
    command: server /data --console-address ":9001"
    volumes:
      - minio_data:/data
    networks:
      - agent-net

volumes:
  mysql_data:
  minio_data:

networks:
  agent-net:
    driver: bridge
```

### 涉及文件
| 文件 | 变更 |
|------|------|
| `docker/docker-compose.yml` | 新增 |
| `docker/nginx/default.conf` | 新增 |
| `docker/mysql/init.sql` | 新增 |

---

## 端到端测试方案

### 测试用例

#### E1: 配置页面工具/MCP/Skill 创建
1. 导航到 `/agents/create`
2. 切换到表单模式
3. 在工具多选区勾选 `write_todos`、`read_file`、`write_file`
4. 展开 MCP 配置，填入 URL 和 Transport
5. 上传 Skill .zip 包
6. **验证**: Skill 解析后元数据正确展示（name, description）
7. 点击"完整示例"按钮
8. **验证**: JSON/YAML textarea 填入完整示例模板
9. 提交创建
10. **验证**: Agent 创建成功，详情页显示启用工具列表、MCP 配置、Skill 元数据

#### E2: Agent 发布后 Ingress 外部访问
1. 生成代码 → 构建镜像 → 发布 Agent（通过 UI 按钮序列）
2. 获取 endpoint_url
3. **验证**: endpoint_url 格式为 `http://{host}/agent/{id}`
4. 通过 Ingress URL 直接 POST 调用 agent chat
5. **验证**: 获得正常 Agent 响应

#### E3: Agent 下线后 Ingress 删除
1. 对已发布 Agent 执行下线
2. **验证**: 下线成功，再次访问 Ingress URL 返回 502/503
3. 查询 pod_status
4. **验证**: deployment status 为 `stopped`

#### E4: Nginx 8911 统一端口
1. 通过 `http://localhost:8911` 访问首页
2. **验证**: 页面正常渲染
3. 通过 `http://localhost:8911/api/v1/agents` 获取 Agent 列表
4. **验证**: API 返回正常 JSON 数据

#### E5: Docker Compose 全栈启动
1. `docker compose -f docker/docker-compose.yml up -d`
2. `docker compose ps`
3. **验证**: 5 个服务全部 Running
4. `curl http://localhost:8911/api/v1/agents`
5. **验证**: 返回 200 + JSON

### E2E 脚本变更
- 端口从 3000 改为 8911
- 新增 E1 测试（创建流程）
- 保留当前 F1-F3 测试（镜像信息、Pod 状态、聊天功能）

---

## 变更文件总结

| 序号 | 文件 | 类型 | 归属功能 |
|:---:|------|:---:|:---:|
| 1 | `codegen/schema/agent_config.json` | 修改 | 1. 工具/MCP/Skill schema |
| 2 | `codegen/generator.py` | 修改 | 1. 新字段代码生成 |
| 3 | `frontend/src/app/agents/create/page.tsx` | 修改 | 1. 表单+示例 |
| 4 | `frontend/src/app/agents/[id]/page.tsx` | 修改 | 1. 详情展示 |
| 5 | `frontend/src/lib/api.ts` | 修改 | 1+3. skills API + 端口 |
| 6 | `frontend/next.config.ts` | 修改 | 3+4. standalone |
| 7 | `backend/internal/k8s/sandbox.go` | 修改 | 2. Service/Ingress |
| 8 | `backend/internal/service/deploy.go` | 修改 | 2. Ingress 生命周期 |
| 9 | `backend/internal/handler/deploy.go` | 修改 | 2. Chat 改用 Ingress |
| 10 | `backend/internal/model/models.go` | 修改 | 2. endpoint_url |
| 11 | `backend/config/config.go` | 修改 | 2. Ingress 配置 |
| 12 | `backend/internal/service/agent.go` | 修改 | 1. Skill 上传解析 |
| 13 | `backend/internal/handler/agent.go` | 修改 | 1. Skill 端点 |
| 14 | `backend/go.mod` | 修改 | 1. yaml.v3 |
| 15 | `backend/Dockerfile` | 新增 | 4. 后端镜像 |
| 16 | `frontend/Dockerfile` | 新增 | 4. 前端镜像 |
| 17 | `Makefile` | 新增 | 5. 构建脚本 |
| 18 | `docker/docker-compose.yml` | 新增 | 6. 容器编排 (含 nginx) |
| 19 | `docker/nginx/agent-manager.conf` | 新增 | 3. Dev 本地 Nginx 配置 |
| 20 | `docker/nginx/default.conf` | 新增 | 3+6. Docker Nginx 配置 |
| 21 | `docker/mysql/init.sql` | 新增 | 6. 数据库初始化 |
| 22 | `e2e/e2e-test.js` | 修改 | E2E 测试 |
