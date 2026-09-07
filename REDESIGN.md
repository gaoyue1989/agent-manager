# Agent Manager 平台重构设计 —— OAF 服务发布平台（v2）

> 版本：v1.0（2026-08-25）
> 定位：独立重构设计文档，不依赖任何历史设计文档。基于对当前代码库（master @ 4c2b2e6）的实际调研编写。
> 范围：`backend/`、`frontend/` 重构精简 + 新增 MCP 服务 + 基于 `agent-framework/` 的智能发布 Agent。`agent-framework/` 本身仅做必要的适配性小改，不在重构范围。

---

## 1. 目标与非目标

### 1.1 目标（新架构只做这一条链路）

```
上传符合规范的 OAF 配置包 (zip)
        │
        ▼
发布（页面选择镜像 + 填写环境变量）
        │
        ▼
K8s 原生 YAML 拉起：Deployment + Service + Ingress
  · OAF 配置包经共享 PVC 以 subPath 只读挂载到 /config
  · 环境变量经 per-service ConfigMap 以 envFrom 注入
        │
        ▼
服务就绪后自动注册：调用 A2A 接口 (.well-known/agent-card.json) 采集服务信息入库
        │
        ▼
服务列表 / 状态展示 / 重新发布 / 下线 / 删除
```

在此之上：

1. **核心功能以 MCP 服务暴露**（streamableHttp，与 Go 后端同进程），供 AI Agent 编排调用。
2. **智能发布 Agent**：基于 `agent-framework` 构建 OAF Agent，其 `mcp-configs` 指向平台 MCP 服务，通过自然语言对话完成「上传包 → 发布 → 查状态 → 重新发布」全流程。

### 1.2 非目标（明确删除）

| 删除项 | 说明 |
|--------|------|
| 构建模式（codegen + Docker 镜像构建） | 平台不再生成代码、不再构建业务镜像，只消费预构建镜像 |
| agent-sandbox CRD 部署轨道 | 全部改用 K8s 原生 Deployment/Service/Ingress |
| MinIO 对象存储 | 配置包直接落共享 PVC，去掉该中间件 |
| Skills 共享库 / 技能上传管理（前后端） | 技能属于 OAF 包内容，随包分发 |
| 聊天测试、Pod 文件树、代码预览、Debug Console 外链 | 辅助调试功能全部移除 |
| 表单式 Agent 创建器（OAF YAML/表单编辑器） | 创建入口收敛为「上传 zip 包」 |
| 双 K8s 客户端（kubectl CLI 封装） | 后端进集群后统一走 client-go InClusterConfig |

### 1.3 已确认的前提决策

| 决策点 | 结论 |
|--------|------|
| 管理后端部署形态 | **容器化部署进 K8s 集群**（宿主机进程无法写 PVC），与管理面/业务服务同 namespace |
| OAF 包存储 | **单一共享 PVC**，按 `packages/{packageId}/` 目录隔离；每个服务容器以 subPath 只读挂载自己的包目录 |
| 业务镜像来源 | **平台提供可选镜像列表**（环境变量 `AVAILABLE_IMAGES`），发布时必选其一，默认取 `DEFAULT_IMAGE` |
| MCP 形态 | **与 Go 后端同进程**：同一 HTTP Server 同时暴露 REST(`/api/v1`) 与 MCP(streamableHttp `/mcp`)，业务层协议无关复用 |
| 智能发布 Agent 部署 | **部署进 K8s**，作为平台第一个自举发布的 OAF 服务，端到端验证新架构 |
| 元数据存储 | **保留 MySQL/GreatSQL(:3307)**；从 Pod 内经 `172.20.0.1:3307` 访问（Kind Docker 网关地址，已被现有 checkpoint DSN 实践验证可行） |
| 数据库实例 | backend 全部新表共用**新建 database `oaf_platform`**，不做旧数据迁移 |
| 代码组织 | **原地重构** `backend/`、`frontend/`；开工前打 `v1-archive` tag 归档旧实现，Makefile 同步更新 |
| 包校验严格度 | **宽松模式**：强制项仅 AGENTS.md 必填字段与格式（沿用现有 Validate）；skills/mcp-configs 引用缺失仅警告不阻断发布 |
| 资源配额 | 暂不设 namespace ResourceQuota，靠 Deployment 默认 limits 兜底 |
| release-agent LLM 配置 | **mimo-v2.5**（2026-08-20 验证有效：工具调用稳定），自举发布时经 env 注入 |
| 认证模型 | 内网信任，暂不做登录/多租户；预留 `AUTH_TOKEN` 扩展位 |

### 1.4 环境事实（已核实）

| 项 | 值 |
|----|----|
| 集群 | Kind 单节点 `agent-manager-control-plane`，v1.32.2 |
| StorageClass | `standard`（rancher.io/local-path，WaitForFirstConsumer） |
| Ingress Controller | ingress-nginx，NodePort 30080/30443 |
| 本地镜像仓库 | `kind-registry` 宿主机映射 `:5002`；集群内经 `172.20.0.1:5002` 可达（HTTP 明文仓库，containerd 需配置 insecure registry） |
| MySQL | 宿主机 GreatSQL :3307 |
| agent-framework 能力 | Spring Boot :8100：自带 `GET /.well-known/agent-card.json`、`GET /health`、A2A JSON-RPC(`POST /`)；MCP 客户端（mcp-configs 支持 `sse`/`streamableHttp`/`stdio`，auth.token 支持 `${ENV_VAR}` 注入）；OAF AGENTS.md frontmatter 解析 |

---

## 2. 总体架构

```
                     浏览器 :8911（宿主机 Nginx，保留现有统一入口）
                       │                          │
              / (前端)  │                         │ /api/*、/mcp
                       ▼                          ▼
              ┌──────────────┐          NodePort :30880
              │   frontend   │                 │
              │ (Next.js容器) │                ▼
              └──────────────┘   ┌─────────────────────────────────┐
                                 │ platform-backend (Go, 进集群)     │
                                 │ ├─ REST      /api/v1            │
                                 │ ├─ MCP       /mcp (streamable)  │
                                 │ ├─ client-go (InClusterConfig)  │
                                 │ └─ GORM ──► MySQL(172.20.0.1:3307)
                                 └───────────┬─────────────────────┘
                                             │ apply/poll（RBAC 仅限本 ns）
                    ┌────────────────────────┼────────────────────────┐
                    ▼                        ▼                        ▼
             Deployment oaf-{name}    Service oaf-{name}-svc   Ingress oaf-{name}
             envFrom: cm/oaf-{name}-env  ClusterIP :8100       path /agent/{name}
             volumes: pvc/platform-data
                      subPath: packages/{pkgId} → /config (ro)
                    │
                    ▼
             ┌────────────────────────────┐
             │ 业务 Pod（agent-framework  │  就绪后由 backend 主动注册：
             │ 或其他兼容镜像）             │  GET http://oaf-{name}-svc.{ns}.svc:8100
             │ AGENT_CONFIG_DIR=/config   │  /.well-known/agent-card.json → 入库
             └────────────────────────────┘

             ┌────────────────────────────┐
             │ release-agent Pod           │  智能发布 Agent（本身也是 oaf-* 服务之一，
             │ (agent-framework 镜像)      │  通过平台自举发布）
             │  mcp-configs/platform →     │
             │  http://platform-backend:8080/mcp (集群内 DNS) │
             └────────────────────────────┘
```

Namespace：全部资源收敛至 `agent-platform`（管理面 Deployment/PVC/RBAC + 业务服务）。

---

## 3. 组件设计：管理后端（Go + Gin）

### 3.1 目录结构（重构后）

```
backend/
├── cmd/server/main.go            # 入口：装配 REST + MCP + K8s + DB
├── config/config.go              # 精简后的环境变量（见 3.7）
├── internal/
│   ├── handler/                  # Gin handlers（REST 门面，薄层）
│   │   ├── package.go            # OAF 包上传/列表/详情/删除
│   │   ├── service.go            # 发布/列表/详情/重发布/下线/删除/env 更新/手动注册
│   │   ├── image.go              # 可选镜像列表
│   │   └── middleware.go         # CORS / 可选 AUTH_TOKEN 校验
│   ├── mcpsrv/                   # MCP 门面（同进程）
│   │   └── server.go             # 工具注册 → 转发 service 层；http.Handler 挂 /mcp
│   ├── service/                  # 业务层（协议无关，REST 与 MCP 共用）
│   │   ├── package.go            # zip 安全校验/解包/落 PVC/落库
│   │   ├── publish.go            # K8s 对象构造与 apply、状态机流转、重新发布
│   │   ├── register.go           # A2A 注册（agent-card + health 拉取解析）
│   │   └── status.go             # 列表/详情聚合（DB 记录 + 实时 Pod 状态合并）
│   ├── k8s/
│   │   ├── client.go             # InClusterConfig + typed Clientset 封装
│   │   ├── objects.go            # Deployment/Service/Ingress/CM/PVC 构造器
│   │   └── ready.go              # Deployment Ready 轮询（2s 间隔 / 120s 超时）
│   ├── store/
│   │   ├── db.go                 # GORM 初始化 / AutoMigrate
│   │   ├── model.go              # Package / Service 两张主表（见 §6）
│   │   └── fs.go                 # DATA_ROOT 目录读写（解包、删除、容量统计）
│   └── oaf/
│       └── oaf.go                # ← 平移自 internal/model/oaf_config.go（ParseOAF/Validate 不改语义）
```

### 3.2 K8s 客户端

- `k8s.io/client-go`：`rest.InClusterConfig()`；本地开发环境回退读 `KUBECONFIG`。
- typed clients（core/v1、apps/v1、networking/v1），不再使用 kubectl shell 封装与 dynamic client 双轨。
- RBAC 最小化（namespace `agent-platform`）：ServiceAccount + Role + RoleBinding，资源限定 deployments/services/ingresses/configmaps/pods/persistentvolumeclaims 的 get/list/watch/create/update/patch/delete。
- apply 幂等语义：Get→不存在 Create / 存在 Update（ConfigMap、Deployment 用 Server-Side Apply 或 Patch）。

### 3.3 OAF 包处理链路

1. 接收 multipart zip。限制：包体 ≤ 20MB、条目 ≤ 2000、解压后总量 ≤ 100MB（防 zip bomb）。
2. 安全校验：拒绝 zip slip（每个条目 `filepath.Clean` 后必须仍在目标根内）、拒绝符号链接/设备文件条目。
3. 必须含根级 `AGENTS.md`；frontmatter 解析复用现有 `ParseOAF` + `Validate()`（identity 五字段 + metadata 四字段必填、kebab-case、semver 等校验规则不变）。**宽松模式**：`mcpServers[].configDir` 指向目录缺失、skills 引用无法解析等仅产生 warnings 随包记录入库并在详情/发布确认页展示，不阻断上传与发布。
4. 解包落盘共享 PVC：`{DATA_ROOT}/packages/{packageId}/`，保留目录结构（AGENTS.md、skills/**、mcp-configs/** 等）。
5. 入库 `packages` 表（manifest 关键字段摘要 + sha256 checksum）。同 slug+version 重复上传允许并存为新 packageId（服务发布时绑定具体 packageId）；被任一 service 引用中的包禁止删除。

### 3.4 发布流程（Publish）

输入：`package_id`、`name`（可选，缺省由 slug 派生）、`image`（必须 ∈ AVAILABLE_IMAGES）、`env map`、`replicas`（默认 1）。

1. 命名规范化：slug `vendorKey/agentKey` → `oaf-{vendorKey}-{agentKey}`，转 DNS-1123（小写字母数字 `-`，≤63 字符）；同名冲突查库加 `-2/-3` 后缀。
2. ConfigMap `oaf-{name}-env`：data = env 键值对（接口层限 64 键 × 单值 ≤ 32KB）。
3. Deployment `oaf-{name}`：
   - `envFrom: configMapRef: oaf-{name}-env`
   - 固定注入 `AGENT_CONFIG_DIR=/config`、`SERVER_HOST=0.0.0.0`、`SERVER_PORT=8100`
   - volumes：PVC `platform-data`，`subPath: packages/{packageId}` → `/config`（readOnly）
   - readiness/livenessProbe：`GET :8100/health`
   - resources 默认 requests 256Mi/250m、limits 1Gi/1（可经 env 覆盖）；`imagePullPolicy: IfNotPresent`
4. Service `oaf-{name}-svc`（ClusterIP，8100→8100）。
5. Ingress `oaf-{name}`（ingress class nginx）：path `/agent/{name}(/|$)(.*)` + rewrite-target `/$2`，对外地址 `http://{INGRESS_HOST}:30080/agent/{name}/`。
6. 状态置 `deploying`，异步轮询 Deployment Ready（2s 间隔 / 120s 超时）。
7. **自动注册**：Ready 后请求集群内 `http://oaf-{name}-svc.{ns}.svc.cluster.local:8100/.well-known/agent-card.json` 与 `/health`（直连 Service DNS，不经 Ingress）。指数退避重试 5 次（2/4/8/16/30s）：
   - 成功：提取 Agent Card（name/version/description/skills/capabilities/urls）连同原始 JSON 落库，状态 → `running`；
   - 失败：状态 → `register_failed`（Pod 仍在运行，支持手动触发重新注册）。

> 说明：注册信息以 agent-framework 的 Agent Card 为准（A2A v1.0 规范字段）；其他兼容镜像只需提供同路径的 agent-card.json 即可完成注册。

### 3.5 其余动作定义

| 动作 | 行为 |
|------|------|
| 更新 env（PATCH） | 更新 ConfigMap data → rollout restart Deployment（`kubectl.rollout` 语义：patch template annotation `restartedAt`）→ 状态回到 deploying → 重新注册 |
| 重新发布 republish | 幂等重建全套资源（CM/Deployment/Svc/Ingress）+ 若绑定了新 packageId 则换 subPath + rollout restart → 重新注册。用于「配置包更新后刷新服务」 |
| 下线 unpublish | 删除 Ingress/Service/Deployment（保留 ConfigMap、PVC 数据、DB 记录），状态 → `stopped` |
| 上线 publish(again) | stopped/error 状态重新执行 §3.4 |
| 删除 delete | 先 unpublish，再删 ConfigMap、PVC 中 `packages/{packageId}` 目录（仅当无其他 service 引用）、DB 记录 |
| 手动注册 re-register | 重跑 §3.4 第 7 步 |

### 3.6 REST API 契约（/api/v1）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/packages` | multipart 上传 OAF zip，返回 Package 记录 |
| GET | `/packages?keyword=` | 包列表（含被引用计数） |
| GET | `/packages/:id` | 包详情（manifest 摘要 + 文件树） |
| DELETE | `/packages/:id` | 删除未被引用的包 |
| GET | `/images` | 可选镜像列表（来自 AVAILABLE_IMAGES） |
| POST | `/services` | 发布服务 `{packageId, name?, image, env{}, replicas?}` |
| GET | `/services?status=&keyword=` | 服务列表（合并实时 Pod 状态） |
| GET | `/services/:id` | 详情：基础信息 + 实时状态 + Agent Card + 最近事件 |
| PATCH | `/services/:id/env` | `{env{}}` 更新并滚动重启 |
| POST | `/services/:id/republish` | 重新发布 |
| POST | `/services/:id/publish` | stopped/error → 重新上线 |
| POST | `/services/:id/unpublish` | 下线 |
| POST | `/services/:id/register` | 手动重新注册 |
| DELETE | `/services/:id` | 删除（级联清理 K8s + PVC 目录 + DB） |
| GET | `/healthz` | 后端自检（DB/K8s 连通性） |

统一响应沿用现有 `{code, message, data}` 包装风格；错误码沿用 HTTP 状态码 + message。

### 3.7 配置项（config.go 重构后）

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| SERVER_PORT | 8080 | REST+MCP 同端口 |
| MYSQL_DSN | 无默认，必填 | 如 `user:pass@tcp(172.20.0.1:3307)/oaf_platform?charset=utf8mb4&parseTime=True` |
| NAMESPACE | agent-platform | 工作命名空间 |
| DATA_ROOT | /data | 共享 PVC 挂载点（OAF 包根目录） |
| AVAILABLE_IMAGES | — | `\|` 分隔：`registry:5002/agent-framework:latest\|Agent Framework latest` |
| DEFAULT_IMAGE | 第一项 | 默认选中镜像 |
| INGRESS_CLASS | nginx | |
| INGRESS_HOST | <node-ip> | 对外展示地址拼接用 |
| RESOURCE_REQUESTS/LIMITS | 256Mi/250m, 1Gi/1 | 业务 Pod 默认资源规格 |
| REGISTER_TIMEOUT / RETRY | 120s / 5 | 就绪等待与注册重试参数 |
| AUTH_TOKEN | 空 | 非空时 REST/MCP 均要求 Bearer（预留位） |

**删除的旧配置**：MINIO_*、LOCAL_REGISTRY（并入镜像全名）、CODEGEN_*、BASE_IMAGE*、BUILD_BASE_IMAGE、DEPLOY_METHOD、DEPLOY_TEMPLATE_DIR、K8S_CLIENT_MODE、DOCKER_USERNAME/PASSWORD、CHECKPOINT_*、LLM_*（LLM 配置属于业务 Pod 的 env，由用户在发布时填写，不再由平台注入）。


### 3.8 MCP 服务设计（同进程 /mcp）

- 实现：官方 Go SDK `github.com/modelcontextprotocol/go-sdk`（streamable HTTP transport），其 `http.Handler` 直接挂载到 Gin 的 `/mcp` 路径（与 REST 同一监听端口）。若 SDK 稳定性不达预期，备选 `mark3labs/mcp-go`（M4 里程碑做选型验证，接口层已隔离在 mcpsrv 包内可替换）。
- 业务逻辑全部走 §3.1 的 service 层，MCP 工具只是第二个门面；工具返回结构化 JSON 文本（便于 LLM 阅读）。

**工具清单（9 个）**：

| 工具名 | 入参 | 出参 | 对应 REST |
|--------|------|------|-----------|
| `upload_package` | `filename`, `content_base64` | packageId, name, slug, version, 文件数 | POST /packages（MCP 通道限 10MB） |
| `list_packages` | `keyword?` | 包数组（id/name/slug/version/引用计数/时间） | GET /packages |
| `get_package_detail` | `packageId` | manifest 摘要 + 文件树 + AGENTS.md 正文 | GET /packages/:id |
| `publish_service` | `packageId`, `name?`, `image?`(缺省默认镜像), `env?{}`, `replicas?` | serviceId, k8sName, endpoint, 初始状态 | POST /services |
| `list_services` | `status?, keyword?` | 服务数组（状态/endpoint/注册信息摘要） | GET /services |
| `get_service_status` | `serviceId` 或 `name` | 详情：实时 Pod 状态、Agent Card、最近事件 | GET /services/:id |
| `update_service_env` | `serviceId`, `env{}`（全量覆盖） | 触发的滚动重启状态 | PATCH /services/:id/env |
| `republish_service` | `serviceId` | 新状态 | POST /services/:id/republish |
| `unpublish_service` / `delete_service` | `serviceId` | 结果 | 对应 DELETE/POST |

> 发布是长操作（拉起+就绪等待最长 ~2min）：`publish_service` 采用「立即返回 deploying + 后台推进」模式，Agent 通过轮询 `get_service_status` 跟踪进度。该约定写入工具 description。

### 3.9 服务状态机

```
            publish                    ready + register ok
 created ────────────► deploying ────────────────────────► running
    │                     │    │                              │
    │        timeout/fail │    │ register fail                │ unpublish
    ▼                     ▼    ▼                              ▼
 error ◄───────────── deploy_failed   register_failed      stopped
    ▲                        │              │               │
    │      republish/publish │◄─────────────┤ republish     │ publish(again)
    └────────────────────────┴──────────────┴───────────────┘
```

- `register_failed`：Pod 正常但 agent-card 拉取失败（如非 agent-framework 镜像且未实现该端点）；不阻塞服务访问。
- 所有状态变迁写 `service_events` 表（含原因/时间），详情页展示最近 N 条。

---

## 4. 组件设计：前端（Next.js）

### 4.1 页面清单（6 页 → 3 页）

| 路由 | 功能 |
|------|------|
| `/` | **服务列表**：表格列 = 名称/包(slug@version)/镜像/状态 badge/Endpoint/A2A 注册时间/更新时间；行操作 = 详情、重新发布、下线/上线、删除（confirm）；顶部入口「发布新服务」 |
| `/publish` | **发布向导**：① 选择已有 OAF 包或上传新 zip（拖拽上传，上传即回显解析出的 AGENTS.md 摘要供确认）② 镜像下拉（AVAILABLE_IMAGES）③ 环境变量键值对编辑器（增删行，值支持多行文本）④ 副本数 ⑤ 提交 → 跳转详情页看部署进度 |
| `/services/[id]` | **服务详情**：基本信息卡、状态卡（实时轮询 5s：K8s 条件/Pod phase）、Agent Card 展示（A2A 注册信息：name/version/skills/capabilities/url）、env 编辑卡（保存=滚动重启）、操作按钮区（按状态机渲染：重新发布/下线/上线/重新注册/删除）、事件时间线 |

删除页面：`/agents/*` 全部四页（列表/create/[id]/edit）、`/skills`。

### 4.2 实现要点

- API 客户端 `src/lib/api.ts` 重写为上述 REST 契约的薄封装（fetch + 统一错误处理），方法数 25 → 15。
- 无路由中间件/无状态库维持现状（useState+useEffect），不为精简版引入新依赖；移除未使用的 axios。
- 复用现有 Tailwind 表格/badge 样式风格；组件保持单文件内联（项目惯例）。
- `output: 'standalone'` 保持，容器化构建直接利用。

---

## 5. 组件设计：智能发布 Agent（基于 agent-framework）

### 5.1 形态

一个标准 OAF 配置包 `release-agent/`，由平台自身发布为集群内服务（自举，验证全链路）：

```
release-agent/
├── AGENTS.md                      # OAF frontmatter + 发布助手角色指令
└── mcp-configs/
    └── platform/
        ├── config.yaml            # streamableHttp → 平台 MCP
        └── ActiveMCP.json         # （可选）工具子集
```

`mcp-configs/platform/config.yaml`：

```yaml
server: platform-publisher
vendor: agentmanager
version: "1.0.0"

connection:
  type: streamableHttp
  url: http://platform-backend.agent-platform.svc.cluster.local:8080/mcp
  timeout: 300          # 发布链路长操作，放宽

permissions:
  read_only: false       # 发布 Agent 需要写操作
```

AGENTS.md 要点：
- identity：`vendorKey: agentmanager`、`agentKey: release-agent`、slug `agentmanager/release-agent`
- 角色指令：你是 OAF 服务发布助手；能力 = 上传/查询配置包、发布/重发布/下线服务、查询服务状态与环境变量更新；发布后必须用 get_service_status 确认到 running/register_failed 再向用户汇报；对删除类操作必须先向用户复述目标并获得明确确认（对应 OAF `config.require_confirmation` 场景）。
- tools 声明与 mcpServers 一致。

### 5.2 运行时依赖

- 镜像：复用 `agent-framework` 现有 Dockerfile 产物（`registry:5002/agent-framework:latest`），零代码改动即可接入 MCP 工具（McpToolRegistrar 已原生支持 streamableHttp + `${ENV_VAR}` 注入）。
- env（经 ConfigMap 注入）：`LLM_API_KEY`、`LLM_MODEL_ID`、`LLM_BASE_URL`、`AGENT_CONFIG_DIR=/config`（固定注入）等。
- 状态存储：沿用 agent-framework 的 MySQL checkpoint（env 注入 CHECKPOINT_* DSN，指向宿主机 GreatSQL）。

#### 环境变量配置规则（自由可配，支持任意增加）

**原则：平台不限定业务镜像的环境变量集合。** 除少量平台保留键外，用户可在发布时及运行后（PATCH env）**任意新增、修改、删除任意数量的键值对**，经 ConfigMap `envFrom` 全量注入容器。agent-framework 的下述契约仅作为发布向导的**参考模板**（用于预填与占位提示），不是硬约束。

保留键（平台固定注入，用户 env 中出现同名键则报错拒绝）：

| 变量 | 值 |
|------|----|
| `AGENT_CONFIG_DIR` | `/config`（PVC subPath 挂载点） |
| `SERVER_HOST` / `SERVER_PORT` | `0.0.0.0` / `8100` |

agent-framework 参考契约（仅作向导预填模板；其他镜像可完全自定义）：

| 分类 | 变量 | 说明 |
|------|------|------|
| 通常必填 | `LLM_API_KEY`、`LLM_MODEL_ID`、`LLM_BASE_URL` | LLM 接入三要素 |
| 可选 | `LLM_PROVIDER`、`LLM_TEMPERATURE`、`LLM_MAX_TOKENS` | 提供商与生成参数 |
| 可选 | `CHECKPOINT_MYSQL_DSN` | 会话持久化；主机须用集群可达地址（如 `172.20.0.1:3307`），不能写 127.0.0.1 |
| 可选 | `JAVA_OPTS` | JVM 参数（镜像内置默认 `-XX:MaxRAMPercentage=75`） |
| 进阶 | `SANDBOX_*` 系列、`OTEL_EXPORTER_OTLP_ENDPOINT` 等 | 不在表单展示，用户可经 env 编辑器自行添加 |

实现要点：
- REST `POST /services` 与 `PATCH /services/:id/env` 的 `env` 为开放 map[string]string，服务端仅校验：键名合法（`[A-Za-z_][A-Za-z0-9_]*`）、非保留键、数量 ≤ 64、单值 ≤ 32KB。
- 发布向导 env 编辑器为通用键值对表格（增删行）；选择 agent-framework 类镜像时可一键套用参考模板预填。
- 敏感值（API Key 等）本期仍走 ConfigMap 明文；预留升级 Secret 方案（标记敏感键自动分流），不在本期范围。

### 5.3 使用方式

- 经 Ingress 访问其 Debug 页 `/agent/release-agent/`（agent-framework 自带）对话；
- 典型对话：「把 /tmp 下这个 oaf 包发一下，环境变量 KEY1=V1」「现在线上跑着哪些服务？xxx 什么状态」「把 xxx 的 MAX_TOKENS 改成 8192 并重启」「下线 demo-service」（触发确认）。


---

## 6. 数据模型（MySQL，GORM）

### 6.1 packages 表（OAF 配置包）

| 字段 | 类型 | 说明 |
|------|------|------|
| ID | uint PK AI | packageId |
| Name | varchar(128) | AGENTS.md name |
| Slug | varchar(200) idx | `vendorKey/agentKey` |
| Version | varchar(32) | semver |
| Description | text | |
| ManifestJSON | json | frontmatter 原文（含 skills/mcpServers/tools 等） |
| DirPath | varchar(256) | PVC 内相对路径 `packages/{id}` |
| FileCount / TotalSize | int / bigint | 解包统计 |
| Checksum | char(64) | zip sha256 |
| RefCount | int | 引用中的 service 数（发布时 +1） |
| CreatedAt | datetime | |

### 6.2 services 表（服务实例）

| 字段 | 类型 | 说明 |
|------|------|------|
| ID | uint PK AI | serviceId |
| K8sName | varchar(63) uniq | `oaf-{name}`（Deployment/Service/Ingress 同名前缀） |
| DisplayName | varchar(128) | 用户可读名 |
| PackageID | uint FK→packages | 绑定的配置包版本 |
| Image | varchar(256) | 运行镜像（∈ AVAILABLE_IMAGES） |
| EnvJSON | json | 环境变量键值对（ConfigMap 数据源） |
| Replicas | int default 1 | |
| Status | enum(created,deploying,running,register_failed,deploy_failed,stopped,error) idx | 见 §3.9 |
| Endpoint | varchar(256) | `http://{INGRESS_HOST}:30080/agent/{k8sNameShort}/` |
| ClusterURL | varchar(256) | `http://{k8sName}-svc.{ns}.svc:8100`（注册/内网调用用） |
| AgentCardJSON | json | A2A 注册原始 card |
| RegisteredName/Version/Description/SkillsJSON | 冗余提取列 | 列表页免解析大 JSON |
| RegisteredAt | datetime | 最近注册时间 |
| CreatedAt/UpdatedAt | datetime | |

### 6.3 service_events 表（状态变迁历史）

| 字段 | 类型 |
|------|------|
| ID | uint PK AI |
| ServiceID | uint FK idx |
| FromStatus / ToStatus | varchar(24) |
| Reason | varchar(512) |
| CreatedAt | datetime idx |

旧表（agents/code_generations/image_builds/deployments/skills 相关）全部废弃；新库建议新建 database（如 `oaf_platform`），不做旧数据迁移。

---

## 7. K8s 部署拓扑与自举

### 7.1 平台自身清单（manifests/platform/，一次性 kubectl apply）

```yaml
# namespace + 共享 PVC + RBAC（摘要）
namespace: agent-platform
persistentVolumeClaim:
  name: platform-data            # 10Gi, storageClass standard, accessMode RWO*
serviceAccount: platform-backend # + Role/RoleBinding（见 3.2）
```

> *local-path 仅支持 RWO；单节点 Kind 下多个 Pod 挂同一 RWO 卷合法（RWO 限制的是节点维度）。若未来多节点需更换支持 RWX 的 StorageClass。

### 7.2 platform-backend Deployment

- 镜像：多阶段构建 `golang:1.23 → gcr.io/distroless/static`（或 alpine），推 `registry:5002/platform-backend:v1`
- 挂载：PVC `platform-data` → `/data`（rw）
- env：MYSQL_DSN、NAMESPACE、AVAILABLE_IMAGES、INGRESS_HOST 等
- Service `platform-backend`（ClusterIP 8080）+ NodePort 30880（供宿主机 nginx 反代 /api 与 /mcp）

### 7.3 frontend Deployment

- 镜像：standalone 构建 → `registry:5002/platform-frontend:v1`
- Service NodePort 30881；宿主机 Nginx :8911 上游改指 `localhost:30881`（前端）与 `localhost:30880`（/api、/mcp）

### 7.4 自举顺序

```
1. docker build 三镜像并推 registry:5002（backend/frontend/agent-framework）
2. kubectl apply manifests/platform/（ns+pvc+rbac+backend+frontend）
3. 浏览器 :8911 → 上传 release-agent OAF 包 → 发布（选 agent-framework 镜像，
   env 填 LLM_* + CHECKPOINT_*）→ 等待 running（第一个被新平台管理的服务）
4. 打开 /agent/release-agent/ 对话验证 MCP 工具链路
5. E2E：经 release-agent 对话完成第二个示例服务的完整发布
```

### 7.5 开发态降级

后端保留 `KUBECONFIG` 回退（本机 go run 直连 kind），DATA_ROOT 可指本地目录——便于不上集群的快速迭代；容器内自动走 InClusterConfig。

---

## 8. 旧代码删除清单

### backend/（删除）

| 目标 | 文件 |
|------|------|
| Docker 构建轨道 | internal/docker/*、service/deploy.go 中 BuildImage/GenerateAndBuild、handler Build 路由、config BASE_IMAGE*/DOCKER_* |
| codegen | internal/codegen/*、POST /generate、GET /code、CodeGeneration 表 |
| sandbox CRD 轨道 | internal/k8s/sandbox.go*、DEPLOY_METHOD 分支、SandboxClient 全部引用 |
| MinIO | internal/minio/* 及全部调用 |
| kubectl CLI 客户端 | internal/k8s/client.go KubectlClient、api_client.go+stub（统一重写为 client-go typed） |
| Skills 管理 | handler/service 中 skills/shared-skills/copy 相关端点与逻辑 |
| 聊天测试/Pod 文件 | ChatWithAgent、GetPodFiles/GetPodFileContent、pod-files 路由 |
| 旧表 | agents/code_generations/image_builds/deployments 四表模型（新模型见 §6） |

**保留迁移**：internal/model/oaf_config.go（→ internal/oaf/）、CORS/响应包装风格、GORM/Makefile 工程骨架。

### frontend/（删除）

| 目标 | 文件 |
|------|------|
| 页面 | src/app/agents/**（4 页）、src/app/skills/page.tsx |
| API 封装 | api.ts 中 generate/getCode/build/chat/podFiles/podFile/imageInfo/skills 全组 |
| 死代码 | axios 依赖、src/lib/__tests__/oaf-parser.test.ts（无 runner 孤儿文件） |

**保留迁移**：layout.tsx 导航骨架（改为 3 项）、Tailwind 样式风格、standalone 构建方式。

### 仓库级（可选清理）

codegen/ 整目录、docker/、Dockerfile.offline-builder、sandbox/、e2e 旧脚本（新增 e2e 覆盖新链路）、PLAN.md 归档。

---

## 9. 里程碑

| 阶段 | 内容 | 验收标准（含测试门禁） |
|------|------|---------|
| M1 backend 核心 | 新模型/K8s client/objects、包上传校验解包、publish/unpublish/republish/delete、A2A 自动注册、REST 全量 | 单测全绿（§10.1）+ REST E2E 场景 A/B 全绿（§10.2） |
| M2 前端 | 3 页面重构 + api.ts 重写 | 页面完成上传→发布→列表→详情→重新发布全操作；UI E2E 场景 D 全绿 |
| M3 MCP 门面 | mcpsrv 接入 go-sdk，9 工具对齐 REST | MCP 工具单测全绿 + E2E 场景 C 全绿（MCP client 走完整发布链路） |
| M4 发布 Agent | release-agent OAF 包制作 + 经平台自举发布 + 对话验收 | 自然语言完成一次第三方服务发布并确认 running；E2E 场景 E 全绿 |
| M5 清理收尾 | §8 删除清单执行、镜像化平台自身、宿主机 nginx 切换 | 平台全部运行于集群、仓库无死代码；**A~F 全场景回归通过** |

依赖关系：M1→M2/M3 可并行；M4 依赖 M1+M3；M5 收尾。

---

## 10. 测试策略（单测与 E2E 均须覆盖全流程）

> 硬性要求：单元测试与 E2E 不是抽样验证，必须覆盖 §3.4/§3.5 定义的**全部动作**与状态机全部迁移路径；每个里程碑的验收以对应测试全绿为准，不允许"先实现后补测"。

### 10.1 单元测试（Go `go test`，随代码同 PR 交付）

| 测试对象 | 必须覆盖的用例 | 方法/基建 |
|----------|----------------|-----------|
| `internal/oaf` | 迁移并保留现有 ParseOAF/Validate 用例；新增宽松模式：configDir 缺失 → warnings 非错误 | 表驱动测试 |
| `internal/store` | 两张主表 CRUD、RefCount 引用计数增减、被引用包删除拒绝、service_events 写入 | GORM + sqlite 内存库或 sqlmock |
| `internal/service/package` | 正常 zip 解包；zip slip 条目拒绝；符号链接拒绝；超限（20MB/条目数/解压总量）；无 AGENTS.md 拒绝；重复 slug+version 并存 | 临时目录模拟 DATA_ROOT |
| `internal/service/publish` | 名称规范化（slug→DNS-1123、超长截断、冲突加后缀）；env 校验（键名格式/保留键拒绝/64 键/32KB 上限）；K8s 对象构造快照断言（Deployment 挂载 subPath/probe/resources/envFrom）；幂等 apply 二次调用不报错 | objects.go 纯函数 + client-go fake clientset |
| `internal/service/register` | agent-card 成功解析入库；HTTP 失败重试 5 次后置 register_failed；手动 re-register 恢复 running | httptest mock agent-card 服务 |
| `internal/k8s/ready` | Ready 轮询成功/120s 超时两条路径 | fake clientset |
| `internal/handler` | 全部 REST 端点的参数校验、错误码、响应包装 | gin httptest |
| `internal/mcpsrv` | 9 工具入参出参映射、长操作「立即返回+轮询」语义、保留键校验与 REST 行为一致 | SDK in-memory transport |

### 10.2 E2E（真实 Kind 集群 + 真实镜像 + mimo-v2.5，脚本放 `e2e/`）

统一前置：集群可用、registry:5002 有 platform-backend/frontend/agent-framework 三镜像、`.env.secrets` 注 LLM 配置。每个场景结束断言 **DB 记录 / K8s 资源 / PVC 目录三方一致**。

| 场景 | 步骤 | 断言要点 |
|------|------|---------|
| A. REST 发布主链路 | 上传示例 OAF zip → POST /services → 轮询至 running → GET 详情 | Deployment/Svc/Ingress 存在且命名正确；agent_card_json 已入库且字段齐全；endpoint 可访问 agent-card.json |
| B. 全动作矩阵 + 异常路径 | PATCH env→滚动重启；republish（换新 packageId）；unpublish→stopped→publish again；delete；异常组：非 zip 包/缺 AGENTS.md/保留键 env/镜像不在列表 → 均 4xx 且无资源残留；register 失败注入（错误端口镜像）→ register_failed → 手动 re-register | 状态机每步 DB+K8s 一致；delete 后零残留（kubectl 全查 + PVC 目录清空 + DB 记录删除） |
| C. MCP 工具全流程 | MCP client（go-sdk client）按序调 list/upload/publish/get_status/update_env/republish/unpublish/delete 共 9 工具 | 与场景 A 同等断言；工具返回 JSON 结构稳定 |
| D. 前端 UI 流程 | Puppeteer：发布向导三步走完（含 env 编辑器增删行）→ 列表出现 → 详情页轮询至 running → 重新发布按钮 → 下线/删除 | 关键 DOM 断言 + 后端状态核对（沿用现有 e2e/puppeteer 设施） |
| E. 发布 Agent 自举 | release-agent 经平台发布为集群服务 → A2A message/send 自然语言指令：「上传 xx 包并发到 running」「查 xx 状态」「下线它」（触发确认流） | 第三方服务真实 running；确认流生效；全程仅经 MCP 工具落库落集群 |
| F. M5 回归 | A~E 全量重跑 + 平台自身（backend/frontend）经 Ingress 可用 | 全绿作为最终验收 |

执行方式：`make e2e`（串行跑 A→C→D→E，LLM 相关场景超时窗口 ≥300s）；CI 不强制，本机 Kind 为准。

---

## 11. 风险与开放问题

| # | 风险/问题 | 应对 |
|---|-----------|------|
| 1 | local-path StorageClass 不支持 RWX | 单节点 Kind 下 RWO 多 Pod 挂载合法；文档注明多节点需换 SC |
| 2 | Pod 访问宿主机 GreatSQL 依赖 172.20.0.1 网关 | 已有 checkpoint DSN 先例；MYSQL_DSN 做成必填配置不硬编码 |
| 3 | registry:5002 为 HTTP 明文仓库 | Kind containerd 需配置 insecure registry（现有集群已具备同类先例）；部署清单中记录配置步骤 |
| 4 | ConfigMap env 有 1MB/键 32KB 限制 | 接口层限制 64 键 × 32KB 并前置报错；超大敏感配置后续升级 Secret 方案（预留） |
| 5 | MCP Go SDK 成熟度 | mcpsrv 包隔离实现，备选 mark3labs/mcp-go 一日内可切换 |
| 6 | 非 agent-framework 镜像无 agent-card 端点 | register_failed 不阻塞运行；手动 re-register 兜底 |
| 7 | 发布为长操作，HTTP 超时 | 异步状态机 + 前端轮询 + MCP 工具「立即返回+轮询」约定 |
| 8 | JVM 业务 Pod 内存占用（agent-framework ~1GB） | 默认 limits 1Gi 可配；AVAILABLE_IMAGES 元数据可携带建议规格 |
| 9 | 平台后端自身故障导致业务服务失联 | 业务服务不依赖平台运行时（仅管理面）；平台重启后以 DB+集群实况对账恢复状态展示 |
| 10 | 旧数据是否迁移 | 默认不迁移（新库 oaf_platform）；如需保留旧 agent 列表另行导出脚本（不在范围） |

---

## 附录 A：发布产物 YAML 示例（单个服务全套）

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: oaf-demo-agent-env, namespace: agent-platform }
data: { LOG_LEVEL: "info" }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: oaf-demo-agent, namespace: agent-platform, labels: { app: oaf-demo-agent } }
spec:
  replicas: 1
  selector: { matchLabels: { app: oaf-demo-agent } }
  template:
    metadata: { labels: { app: oaf-demo-agent } }
    spec:
      containers:
        - name: agent
          image: 172.20.0.1:5002/agent-framework:latest
          ports: [{ containerPort: 8100 }]
          envFrom: [{ configMapRef: { name: oaf-demo-agent-env } }]
          env:
            - { name: AGENT_CONFIG_DIR, value: /config }
            - { name: SERVER_HOST, value: 0.0.0.0 }
            - { name: SERVER_PORT, value: "8100" }
          volumeMounts:
            - { name: oaf-config, mountPath: /config, subPath: packages/{packageId}, readOnly: true }
          readinessProbe: { httpGet: { path: /health, port: 8100 }, initialDelaySeconds: 15, periodSeconds: 5 }
          livenessProbe:  { httpGet: { path: /health, port: 8100 }, initialDelaySeconds: 60, periodSeconds: 15 }
          resources:
            requests: { memory: 256Mi, cpu: 250m }
            limits:   { memory: 1Gi,  cpu: "1" }
      volumes:
        - name: oaf-config
          persistentVolumeClaim: { claimName: platform-data }
---
apiVersion: v1
kind: Service
metadata: { name: oaf-demo-agent-svc, namespace: agent-platform }
spec:
  selector: { app: oaf-demo-agent }
  ports: [{ port: 8100, targetPort: 8100 }]
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: oaf-demo-agent
  namespace: agent-platform
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2
    nginx.ingress.kubernetes.io/use-regex: "true"
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
spec:
  ingressClassName: nginx
  rules:
    - http:
        paths:
          - path: /agent/demo-agent(/|$)(.*)
            pathType: ImplementationSpecific
            backend: { service: { name: oaf-demo-agent-svc, port: { number: 8100 } } }
```

---

## 附录 B：实施记录（2026-08-25 完工）

### B.1 与设计的偏差（已验证的更优解）

| 设计原案 | 实际落地 | 原因 |
|----------|---------|------|
| 元数据用宿主机 GreatSQL(:3307) | **集群内自建 MySQL 8.0**（oaf-mysql，含 oaf_platform/oaf_checkpoint 两库） | GreatSQL root 密钥不可得；集群内自建彻底解除宿主机依赖 |
| 镜像经 registry:5002 拉取 | **docker save + ctr import 进节点**（`--platform linux/amd64`） | kind load 对 OCI index 含 arm64/attestation 的镜像报 digest 缺失 |
| MCP SDK 版本跟随最新 | modelcontextprotocol/**go-sdk v1.3.1** | v1.4+ 要求 go1.25，本地工具链 1.23 |
| 业务 Pod 仅挂包卷 | 包卷(ro /config) + **独立 emptyDir 工作区(/workspace)** 双挂载 | agent-framework 启动需在 configDir 下写 .agentscope/workspace，只读卷会启动失败 |
| — | agent-framework 新增 `AGENT_WORKSPACE_DIR` 环境变量 | 框架必要小改：工作区基目录可配置 |

### B.2 实施期发现的关键机制

- **zip 条目权限**：Python zipfile 默认 0600，业务 Pod 非 root 用户读不了 → 解包时强制最低 0644。
- **MySQL JSON 列**：空字符串非法，JSON 字段入库前须初始化 `{}`/`[]`。
- **agent-framework 内置 HITL**：SDK 权限引擎对非 readOnly 的 MCP 写工具默认 ASK（与 permissionContext 是否装配无关）。发布助手类可信内部组件需在 mcp-configs `permissions.read_only: true` + frontmatter `config.permission.mode: bypass`（且至少声明一条 `permissions.tools.*` 以激活上下文）。
- **A2A blocking 超时**：阻塞式 message/send 全程可超 60s，业务 Ingress 必须注入 `proxy-read/send-timeout=3600`。
- **会话暂停态持久化**：HITL ASKING 状态经 MysqlDistributedStore 跨 Pod 复活；E2E 须用唯一 userId 隔离会话。
- **Debug Console 子路径部署适配**：静态页相对资源 + 后端对无尾斜杠 `/debug` 302 重定向（Location 由 ingress `x-forwarded-prefix` 注解还原外部前缀，浏览器保留原始 host:port）。平台生成的业务 Ingress 统一注入该注解。
- **MCP 启动容错（框架层新增，官方无此能力）**：agentscope-java SDK 的 `Toolkit.ToolRegistration.apply()` 对 MCP 初始化失败直接 block() 抛异常导致应用无法启动；官方 issue #2563（mcp 初始化支持异步）仍 open。已在 McpToolRegistrar 实现 fail-soft：默认连接失败仅 WARN 并跳过该 server（工具不可用但服务正常启动）；config.yaml `startup.required: true` 声明严格模式。已验证 approval-demo（指向不可达的 127.0.0.1:8813 mock）由 deploy_failed → running。

### B.3 测试结果（M5 回归口径）

| 场景 | 用例数 | 结果 |
|------|--------|------|
| 单元测试（go test ./...，6 包） | 全部 | ✅ 绿 |
| A REST 发布主链路 + B 动作矩阵/异常 | 42 | ✅ 全绿 |
| C MCP client 全链路 | 19 | ✅ 全绿 |
| D Puppeteer UI 流程 | 10 | ✅ 全绿 |
| E 发布 Agent 自举 + 自然语言驱动 | 6 | ✅ 全绿 |

### B.4 入口与对话集成（2026-08-25 补充完工）

- **宿主机 nginx :8911 已切换**：上游指向集群（前端→NodePort 30881、API→30880、/agent/→ingress 30080、/mcp 同后端），A2A/SSE 路径均注入 proxy-read/send-timeout=3600。原 v1 配置备份于 /tmp/opencode/agent-manager.conf.bak。
- **前端集成发布助手对话**（页面 /assistant，无状态单次流方案）：
  - `POST {agent}/threads/{sessionId}/chat` SSE 增量渲染（TEXT_BLOCK_DELTA 拼接、TOOL_CALL 状态行、permission_ask 确认卡片）
  - HITL 恢复走 `POST /threads/{sessionId}/confirm-stream`（results=[{tool_call_id,confirmed}]）
  - Next rewrites 将 `/agent/release-agent/*` 反代到 release-agent Service，任意入口同源可用
  - sessionId 存 localStorage（会话跨刷新延续）；HTTP 非安全上下文无 crypto.randomUUID，用时间戳+随机串兜底

### B.5 遗留待办

- 敏感 env 升级 Secret 方案（当前 ConfigMap 明文，内网信任模型）。
- 对话 UI E2E：e2e/chat-ui-e2e.js（4 用例，经 :8911 主入口真实对话验证）。

### B.6 文件上传下载 + OAF 部署包自动生成（2026-09-06 完工）

文件上传下载（图片/文档上传 → 沙箱注入/工作区直读 → present_file 回传 → SSE file_ready → 前端下载卡片）
与"按描述自动生成 OAF 部署 zip 包"两条链路已完工并全量验证（设计详见
`agent-framework/docs/file-upload-download-plan.md` §1~§18）。

**测试基线**：单测 439 全绿（+56 新用例）；backend go test 全绿；E2E 非沙箱 21/21、
沙箱 29/29（含 S-S8 生成包、S-S9 发布全链路）、UI 沙箱/非沙箱各 12/12（U11~U13 生成包对话）。

**关键机制（复用价值高）**：
1. 文件存储双后端（local/S3）按 Dify UploadFile 模式：DB 只存元数据（file_asset 表），内容存 FileStorage；
   OAF 包 zip 与产物文件统一走 origin=generated 登记 → SSE file_ready 合成帧 → 前端下载卡片。
2. 沙箱注入三兜底：create/resume 后注入 + SandboxUserKeyMiddleware.onAgent 兜底 + doExec 兜底；
   reset 按 session 双维度 + spec 记录 userKey→sandboxId 防多实例循环注入。
3. 沙箱生成包**绕开沙箱**：`create_oaf_zip` 工具 JVM 侧组装 zip（LLM 传 AGENTS.md 文本即可），
   前置 `check_oaf_package` 强制校验（与平台 backend/internal/oaf 规则对齐）；实测 LLM 用沙箱
   write_file/edit_file 写文件易失败且浪费迭代。
4. `present_file` 沙箱模式支持 file_path 直读（OpenSandbox 经 execd files API readByteArray；
   userKey 不匹配拒绝防串沙箱），LLM 无需复述大段 base64。
5. `HarnessAgent.maxIters(20)`：SDK 默认 10 轮不足支撑生成包长流程（10 轮实测 EXCEED_MAX_ITERS）。
6. **No active sandbox 尾部收尾错误（SDK 缺陷）**：agent 调用结束后 SDK 收尾路径偶发访问已释放沙箱
   文件系统 → 流以 error 终止导致前端误判失败；`SessionStreamController.isTrailingSandboxTeardownError`
   识别（异常链消息含 "No active sandbox"）→ 忽略、正常 complete。根因在 SDK 未消除，属应用层缓解。

**遗留待办（详见文档 §17.3 与 §18）**：
- **沙箱复用已确认正常**（2026-09-06 深查）：同 userId 连续 chat 走 `Priority 3: resuming from persisted
  state` 复用容器（2 次 chat 仅 1 个容器）；旧观察"每 turn create"源于当时 chat 失败周期
  （release 阶段异常中断 → persistState 未执行 → 下次无 state 降级 create）。容器增长主要来自
  多 userId 会话（每个新用户 create）+ 失败会话重建 + Server 侧 3600s TTL 回收，长会话需定期清理
- **默认镜像陈旧坑（已修）**：发布服务默认 `agent-framework:latest` 解析到 kind 节点
  docker.io/library 标签的旧镜像（2026-08 版，LocalFileStorage 无默认构造器 → CrashLoop → deploy_failed）。
  修复：新镜像 `ctr images rm` 旧 tag 后 `docker save | ctr images import` 覆盖；发布链路 E2E S-S9
  （LLM 生成+上传 → REST 发布 running → 删除清理）29/29 全绿。**更新 agent-framework 镜像后必须同步
  更新节点的 docker.io/library/agent-framework:latest tag**，否则新发布服务全部 CrashLoop
- 平台 upload_package 仅收 base64（zip 大时 LLM 复述有 token 压力），可评估增加 file_id 引用上传
- S3 存储档 E2E 未实测；非沙箱档 S-S8 已确认 21/21 全绿（2026-09-06）
