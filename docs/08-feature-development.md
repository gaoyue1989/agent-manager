# 三阶段: 功能开发记录

**日期**: 2026-05-01
**执行者**: opencode
**提交**: `fedca21`, `f42a65a`

## 1. Go 后端

### 项目结构
```
backend/
├── cmd/server/main.go              # 入口，初始化 DB/MinIO/K8s/Docker
├── config/config.go                 # 环境变量配置
├── internal/
│   ├── handler/agent.go            # Agent CRUD + 代码生成
│   ├── handler/deploy.go           # 构建/部署/发布/下线
│   ├── service/agent.go            # Agent 业务逻辑
│   ├── service/deploy.go           # 部署业务逻辑
│   ├── model/models.go             # GORM 数据模型
│   ├── minio/storage.go            # MinIO 文件存取
│   ├── docker/builder.go           # Docker CLI 构建/推送
│   ├── k8s/sandbox.go              # kubectl Sandbox CRD 操作
│   └── codegen/runner.go           # Python 代码生成调用
├── go.mod / go.sum
```

### 技术栈
- Go 1.23 (从阿里云镜像安装)
- Gin v1.10 + CORS 中间件
- GORM v1.25 + MySQL 驱动
- MinIO Go SDK v7
- Docker/kubectl CLI 调用

### API 端点 (11 个)
| 方法 | 路径 | 功能 |
|------|------|------|
| POST | /api/v1/agents | 创建 Agent |
| GET | /api/v1/agents | Agent 列表 |
| GET | /api/v1/agents/:id | Agent 详情 |
| PUT | /api/v1/agents/:id | 更新配置 |
| DELETE | /api/v1/agents/:id | 删除 Agent |
| POST | /api/v1/agents/:id/generate | 生成代码 |
| GET | /api/v1/agents/:id/code | 获取代码 |
| GET | /api/v1/agents/:id/deployments | 部署历史 |
| POST | /api/v1/agents/:id/build | 构建镜像 |
| POST | /api/v1/agents/:id/publish | 发布上线 |
| POST | /api/v1/agents/:id/unpublish | 下线 |

## 2. React 前端

### 项目结构
```
frontend/
├── src/
│   ├── app/
│   │   ├── layout.tsx              # 全局布局 + 导航
│   │   ├── page.tsx                # Dashboard 首页
│   │   ├── agents/
│   │   │   ├── page.tsx            # Agent 列表
│   │   │   ├── create/page.tsx      # 创建 Agent (表单/JSON/YAML)
│   │   │   └── [id]/
│   │   │       ├── page.tsx         # Agent 详情 (状态流转)
│   │   │       └── edit/page.tsx    # 编辑配置
│   └── lib/api.ts                  # API 客户端
```

### 页面功能
1. **Dashboard**: 5 个统计卡片 (总数/已发布/已下线/草稿/异常)
2. **Agent 列表**: 状态标签 + 操作按钮
3. **创建 Agent**: 表单模式 / JSON 编辑器模式 / YAML 模式切换
4. **Agent 详情**: 
   - 基线信息 + 配置预览
   - 状态流转按钮: 生成代码 → 构建镜像 → 部署 → 发布上线 → 下线
   - 代码预览 (Monaco 风格暗色主题)
   - 部署历史表格
5. **编辑 Agent**: JSON 编辑器

### 技术栈
- Next.js 16 + TypeScript
- Tailwind CSS
- Fetch API (轻量无额外依赖)

## 3. Agent 状态流转

```
draft → [生成代码] → generated → [构建镜像] → built
  → [部署] → deployed → [发布上线] → published → [下线] → unpublished
```

## 4. 遇到的关键问题

| 问题 | 解决 |
|------|------|
| Docker SDK 路径变更 (moby/moby) | 改用 `docker` CLI 命令 |
| K8s client-go 需要 Go 1.26 | 改用 `kubectl` CLI 命令 |
| Go 1.26 无法下载 | 从阿里云镜像安装 Go 1.23 |
| npm 安装慢 | 配置 npmmirror 镜像 |

## 5. 启动方法

```bash
# 启动后端
cd backend
PATH=/usr/local/go1.23/bin:$PATH go run ./cmd/server/

# 启动前端开发服务器
cd frontend
npm run dev
```

## 6. Git 提交历史

```
fedca21  一阶段: 环境搭建 + 二阶段: 功能验证
f42a65a  三阶段: 功能开发 — Go 后端 + React 前端
```
