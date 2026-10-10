# OAF 平台 Helm 离线部署设计（values 可定制 · 外部 MySQL/Redis/OTel · 复用已有 PVC）

> 状态：**待评审**（2026-10-10 编制，未实施）。
> 关联：[subpath-routing-design.md](subpath-routing-design.md)（路由双模式与命名不变量）、
> [../deployment.md](../deployment.md)（现状部署流程）、`manifests/*.yaml`（现状自举清单，本设计将其产品化为 chart）。

## 1. 背景与目标

现状部署路径是 `kubectl apply -f manifests/*.yaml`：清单里**内嵌**了单实例 MySQL/Redis、
PVC 创建、镜像 tag、`INGRESS_HOST` 等环境相关值——换环境要改文件，内网/离线环境也没有
可交付的打包与参数化路径。

**目标**

1. 单一 Helm chart（**自包含、无外部依赖**）一键部署平台控制面；
2. 全部环境相关值走 `values.yaml` / `-f values-offline.yaml`，不要求改模板；
3. **MySQL / Redis 外部化**：chart 不部署、不创建、不管理（**OTel 已决议暂不考虑**，§5.3）；
4. **PVC 复用已有**：chart 不创建任何 PVC（平台数据卷由环境预先提供）；
5. **离线（内网仓库）**：镜像**统一来自环境内网镜像仓库**——chart 不提供镜像预载/搬运，
   仅参数化 registry 前缀与拉取凭据；渲染与安装不依赖公网（无子 chart、无外网访问）。

**非目标（明确不做）**

- 不部署业务 agent 服务（由平台发布 API 动态创建，**chart 永不管理**）；
- 不部署 OpenSandbox / LLM 网关（外部服务，经平台默认配置传入）；
- 不管理 MySQL 库表（GORM `AutoMigrate` 自建，chart 只要求库与账号预先存在）；
- 不引入 ingress-nginx / cert-manager 等子 chart（入口控制器为环境前提）。

## 2. 组件范围（chart 管理 vs 不管理）

| 组件 | 渲染对象 | 条件 |
|---|---|---|
| platform-backend | ServiceAccount + Role + RoleBinding（synthesized 模式下另有专用 SA/token Secret，见 §6.2）、Deployment、Service（ClusterIP）、Ingress（`/api`、`/healthz`、`/mcp`） | 恒有 |
| platform-frontend | Deployment、Service（ClusterIP）、Ingress（§6.3 形态 A/B） | 恒有 |
| platform-router | ConfigMap（nginx.conf）、Deployment（副本数可配，默认 2）、Service、共享 Ingress（`/agent`） | `routing.mode=router`（默认） |
| 业务 Deployment overlay | ConfigMap（供后端 `DEPLOYMENT_TEMPLATE` 挂载） | `business.deploymentOverlay.enabled` |
| **bootstrap Job**（平台自举钩子） | post-install/post-upgrade Job，两步可独立开关：①写入平台默认配置（§5.2）；②上传 release-agent OAF 包并经发布 API 起服务（§5.4） | `platformDefaults.seed.enabled` / `releaseAgent.enabled` |
| **不管理** | MySQL、Redis、OpenSandbox、业务 agent（Deployment/Service/Ingress/CM/Secret）、**任何 PVC**、namespace 内既有对象 | — |

## 3. Chart 目录结构

```
charts/oaf-platform/
├── Chart.yaml                        # apiVersion v2；无 dependencies（自包含）
├── values.yaml                       # 全量默认值（kind 开发语义）
├── values-offline.yaml.example       # 离线示例（内网仓库前缀 / 外部中间件 / 已有 PVC）
├── README.md                         # 快速开始、内网仓库配置、命名同步约束、卸载语义
├── resources/release-agent.oaf.zip   # 发布助手 OAF 包（构建期由 scripts/pack-release-agent.sh 从 release-agent/ 源打包）
├── scripts/pack-release-agent.sh     # 构建期执行：源 → zip（chart 打包流水线用，装期不跑）
└── templates/
    ├── _helpers.tpl                  # 命名/labels/必填校验（required）
    ├── namespace.yaml                # 可选创建（默认跟随 Release.Namespace）
    ├── serviceaccount.yaml           # SA + Role + RoleBinding（沿用现有 RBAC 规则集）
    ├── backend-deployment.yaml
    ├── backend-service.yaml          # ClusterIP（不渲染 NodePort，已决议）
    ├── backend-ingress.yaml          # /api /healthz /mcp（可选，class/annotations 可配）
    ├── frontend-deployment.yaml
    ├── frontend-service.yaml
    ├── router-configmap.yaml         # nginx.conf（namespace/端口约定由 values 渲染）
    ├── router-deployment.yaml        # 默认 2 副本，maxUnavailable: 0
    ├── router-service.yaml
    ├── router-ingress.yaml           # 共享 /agent Ingress（零 rewrite 注解）
    ├── business-overlay-configmap.yaml
    ├── bootstrap-job.yaml            # 自举钩子：默认配置种子 + 发布助手（分步开关，§5.2/§5.4）
    └── NOTES.txt                     # 装后指引：入口地址、自检命令、回滚与卸载语义
```

## 4. values 契约（分组）

### 4.1 分组与代表键

| 分组 | 键（代表） | 默认 / 说明 |
|---|---|---|
| 命名 | `namespaceOverride`、`nameOverride` | 默认 `Release.Namespace`；**须与业务资源所在 namespace 一致**（后端 `NAMESPACE`、router upstream FQDN 同一值） |
| 镜像 | `images.backend.{repository,tag,pullPolicy}`、`images.frontend.*`、`images.router.*` | **repository 含内网 registry 前缀**（如 `harbor.internal/oaf/platform-backend`）；凭据经 `imagePullSecrets` |
| 业务镜像白名单 | `images.business[]`（`{image,label}`）、`images.defaultBusiness` | 渲染 `AVAILABLE_IMAGES` / `DEFAULT_IMAGE`（**必须与实际可拉取 tag 一致**，否则发布被 400 拒） |
| 业务 Pod 资源 | `business.resources.{requestsCpu,requestsMem,limitsCpu,limitsMem}` | 渲染 `RESOURCE_*` 四元组（后端 config.go 默认 250m/256Mi/1/1Gi，作用于**每个业务 Deployment**） |
| 路由 | `routing.mode`（`router`\|`host`）、`routing.ingressClass`、`routing.host`、`routing.port`、`routing.hostSuffix` | `host`/`port` = **集群 ingress controller 的对外入口地址**（Endpoint 展示拼装用）；host 模式额外 `hostSuffix` |
| 后端 | `backend.serverPort`（8080，联动 Service targetPort）、`backend.replicas`（固定 1，见原则一）、`backend.authToken`、`backend.resources`、`backend.packageDownloadBase`、`backend.register.{timeoutSeconds,retry}`、`backend.kubeconfig.{mode,server,existingSecret,content}` | `authToken` 为空则关闭 Bearer 校验（**生产必填**，经 Secret 注入）；`kubeconfig` 两模式（§6.2，**默认 synthesized 部署期合成**） |
| 前端 | `frontend.env.{backendInternalUrl,agentInternalUrl,publicApiUrl}` | `agentInternalUrl` 按 `releaseAgent.enabled` 渲染；`publicApiUrl` 默认空=同源（形态 B 必填）；**EVAL_COLLECTOR_* 已决议不纳入**（评测环境手工设置） |
| 数据卷 | `persistence.existingClaim`、`persistence.mountPath`（`/data`） | **不创建**，见 §5.5 |
| 外部 MySQL | `external.mysql.{host,port,database,username,password,params}` 或 `dsn` 直给 | 平台元数据库（`oaf_platform`）与业务 checkpoint 库（`oaf_checkpoint`）见 §5.1/§5.2 |
| 外部 Redis | `external.redis.{url}`（业务侧默认值） | 平台自身不使用 Redis；仅用于种子与文档提示 |
| 外部 OTel | `otel.{enabled,exporter,endpoint,headers,serviceName}` | 经业务 Deployment overlay 注入，见 §5.3 |
| 业务 overlay | `business.deploymentOverlay.extra`（自备 SMP 单文件；**OTel 自动段已决议移除**，如需 OTel 经 extra 手工注入）、`business.ingressOverlay`（自备，**仅 host 模式**） | router 模式配置 `ingressOverlay` → chart 渲染期 fail（后端 fail-fast，提前拦避免 CrashLoop） |
| 平台默认值种子 | `platformDefaults.seed.{enabled,llm.*,mysql.*,redis.*,sandbox.*,protocol.*}` | 调平台 API 写入；默认关闭 |
| 暴露 | `ingress.{className,host,tls,annotations}` + `ingress.backend.host` / `ingress.frontend.host`（分域用） | 仅 Ingress（形态 A/B 见 §6.3），**不提供 NodePort**（已决议）；入口 = 集群 ingress controller |
| 安全 | `imagePullSecrets[]`、`podSecurityContext`、`existingSecret`（密码类复用已有 Secret） | values 明文仅示例，生产走 `existingSecret` |
| 通用调度 | `backend/frontend/router` 各自的 `podAnnotations`、`nodeSelector`、`tolerations`、`affinity` | 生产常见诉求；三组件独立配置 |
| 发布助手 | `releaseAgent.{enabled,name,packageSource,image}` | 默认开；`packageSource`: `bundled`（chart 内置 zip）/ `packageId`（环境已导入包 id）；详见 §5.4 |
| bootstrap 镜像 | `images.bootstrapJob.{repository,tag}` | 自举 Job 的 curl 容器，**同样来自内网仓库** |
| router 高级 | `router.nginx.{clientMaxBodySize,readTimeout,sendTimeout}` | 默认即 subpath-routing 决议值（200m / 3600s），仅超出现状需求时调整 |
| 数据卷绑定 | `persistence.mountPath`（默认 `/data`） | 渲染时**同时**设后端 `DATA_ROOT` 与挂载点（两者必须一致，chart 绑定） |

### 4.2 values.yaml 骨架（评审用，节选）

```yaml
routing: { mode: router, ingressClass: nginx, host: "172.20.0.3", port: 30080, hostSuffix: "" }
imagePullSecrets: [{ name: oaf-registry-cred }]
images:
  # repository 均为「内网仓库/项目」全路径，tag 由发布流水线产生
  backend:  { repository: harbor.internal/oaf/platform-backend, tag: "v9", pullPolicy: IfNotPresent }
  frontend: { repository: harbor.internal/oaf/platform-frontend, tag: "v7", pullPolicy: IfNotPresent }
  router:   { repository: harbor.internal/oaf/nginx, tag: "1.27.1-alpine", pullPolicy: IfNotPresent }
  business: [{ image: "harbor.internal/oaf/agent-framework:agentscope-2.1.0-v20261010", label: "Agent Framework 2.1.0" }]
  defaultBusiness: "harbor.internal/oaf/agent-framework:agentscope-2.1.0-v20261010"
persistence: { existingClaim: platform-data, mountPath: /data }
external:
  mysql: { host: oaf-mysql.agent-platform.svc.cluster.local, port: 3306, database: oaf_platform,
           username: oaf, password: "", params: "charset=utf8mb4&parseTime=True&loc=Local" }
  redis: { url: "redis://oaf-redis.agent-platform.svc.cluster.local:6379" }
# otel 组已决议移除（暂不考虑；如需可经 deploymentOverlay.extra 手工注入 OTEL_* env）
business: { deploymentOverlay: { enabled: false, extra: "" } }
platformDefaults: { seed: { enabled: true } }          # bootstrap ①：默认配置种子（已决议默认开）
releaseAgent:                                          # bootstrap ②：发布助手自举（已决议纳入）
  enabled: true
  name: release-agent
  packageSource: bundled                               # bundled（chart 内置 zip）/ packageId
  image: ""                                            # 空 = images.defaultBusiness
images.bootstrapJob: { repository: harbor.internal/oaf/curl, tag: "8.8" }
backend:
  kubeconfig: { mode: synthesized, server: "" }        # 已决议：默认部署期合成（§6.2）
ingress: { className: nginx, host: "", tls: { enabled: false, secretName: "" } }
# 注：无 nodePort 组（已决议不提供 NodePort 暴露）
```

### 4.3 后端/前端配置全集 ↔ values 映射（2026-10-10 配置面 review 补全）

> 来源：`backend/config/config.go`（envStr/envInt 全集 21 项）+ `cmd/server/main.go`（KUBECONFIG）
> + 前端 `process.env` 5 项。**「本次补」= 首版设计遗漏，review 修正**；实现时以本表为验收清单
> （chart 渲染产物逐项可对号，防再次漏配）。

| # | 配置项 | 来源 | values 键 | 状态 |
|---|---|---|---|---|
| 1 | `SERVER_PORT` | config.go | `backend.serverPort` | **本次补** |
| 2 | `NAMESPACE` | config.go | `namespaceOverride`（须=Release.Namespace，chart 校验） | 已有 |
| 3 | `DATA_ROOT` | config.go | `persistence.mountPath`（同时渲染 env 与挂载点） | **本次补绑定** |
| 4 | `MYSQL_DSN` | config.go | `external.mysql.*` / `dsn` | 已有 |
| 5-6 | `AVAILABLE_IMAGES` / `DEFAULT_IMAGE` | config.go | `images.business[]` / `images.defaultBusiness` | 已有 |
| 7-10 | `INGRESS_CLASS/HOST/PORT/HOST_SUFFIX` | config.go | `routing.*` | 已有 |
| 11-14 | `RESOURCE_REQUESTS_{CPU,MEM}` / `RESOURCE_LIMITS_{CPU,MEM}` | config.go | `business.resources.*`（业务 Pod 资源四元组） | **本次补** |
| 15-16 | `REGISTER_TIMEOUT_SEC` / `REGISTER_RETRY` | config.go | `backend.register.*` | **本次补** |
| 17 | `DEPLOYMENT_TEMPLATE` | config.go | `business.deploymentOverlay`（OTel 段 + `extra` 合并渲染单文件） | 已有→扩展 |
| 18 | `INGRESS_TEMPLATE` | config.go | `business.ingressOverlay`（仅 host 模式；router 模式配置即安装报错） | **本次补（评审点名）** |
| 19 | `AUTH_TOKEN` | config.go | `backend.authToken` | 已有 |
| 20 | `PACKAGE_DOWNLOAD_BASE` | config.go | `backend.packageDownloadBase` | 已有 |
| 21 | `KUBECONFIG` | main.go | `backend.kubeconfig`（两模式 provided / synthesized，**默认 synthesized 部署期创建**，见 §6.2） | **本次补（评审点名，已决议）** |
| F1 | `BACKEND_INTERNAL_URL` | 前端 proxy.ts | `frontend.env.backendInternalUrl` | 已有 |
| F2 | `AGENT_INTERNAL_URL` | 前端 proxy.ts | `frontend.env.agentInternalUrl` | 已有 |
| F3 | `NEXT_PUBLIC_API_URL` | 前端 | `frontend.env.publicApiUrl`（形态 A 留空=同源 `/api/v1`；**形态 B 必填** backend 公网地址，见 §6.2） | **本次补** |
| F4-F5 | `EVAL_COLLECTOR_MODE` / `EVAL_COLLECTOR_AGENT_URL` | 前端（评测录制反代） | **已决议不纳入**（评测环境手工设置） | 本次补→已决议 |
| R1-R3 | nginx `client_max_body_size` / `proxy_{read,send}_timeout` | platform-router | `router.nginx.*`（默认 200m/3600s） | **本次补** |

不映射（维持后端默认/无 env 面）：Gin 模式、业务镜像 pullPolicy（后端构造固定 `IfNotPresent`，
见 §10 风险 6）、router nginx 的 `oaf-`/`-svc`/8100/namespace 命名约定（不变量，非配置）。

## 5. 三条外部依赖接线（本设计的核心）

### 5.1 平台自身 → 外部 MySQL（必填）

- `external.mysql.*` 渲染为后端 `MYSQL_DSN`（或 `dsn` 直给覆盖）；密码经 Secret（支持
  `existingSecret`），不落 Deployment 明文。
- **前置条件（写入 README 与 NOTES）**：库 `oaf_platform` 已存在、账号具 DDL/DML 权限
  （后端启动 `AutoMigrate` 自建表）；业务侧另需 `oaf_checkpoint` 库（§5.2）。
- chart **不**创建库、不提供备份/迁移 Job。

### 5.2 业务服务 → 外部 MySQL(checkpoint) / Redis（经平台默认配置，bootstrap Job 第①步）

- 平台**不向业务 Pod 注入**这两类配置：业务 env 来自「发布时配置」，而发布向导的默认值
  来自**平台默认配置**（`platform_config` 表 → `GET /platform-config/defaults` 预填）。
- 因此 chart 通过 **bootstrap Job 第①步**（`platformDefaults.seed.enabled`，默认开）把
  `external.mysql`（`CHECKPOINT_JDBC_URL`/`CHECKPOINT_USERNAME`/`CHECKPOINT_PASSWORD`）与
  `external.redis`（`AGENT_REDIS_URL`）等写入平台默认配置 → 「发布新服务」自动带出 → 一键语义闭环。
- 不启用种子时：运维在 `/settings` 页手工维护（chart 不阻塞部署）。
- 文档必须强调（既有约束）：**多服务共享同一 MySQL/Redis 时必须每服务独立 checkpoint 库名
  与 `AGENT_REDIS_PREFIX`**，平台当前不自动派生（已知缺口）。

### 5.3 业务服务 → 外部 OTel —— **已决议：暂不考虑**（2026-10-10）

> 下列机制说明保留作后续演进参考；首版不实施、values 无 otel 组。临时需求可经
> `business.deploymentOverlay.extra` 手工注入 `OTEL_*` env 实现（机制同文）。

（原方案存档）

- **平台默认配置没有 OTel 键**（`internal/service/platformconfig/template.go` 仅 llm/mysql/
  redis/sandbox/protocol，且 `platform_config` 拒绝未知键），所以 OTel 不走默认配置。
- 采用**业务 Deployment overlay**（`DEPLOYMENT_TEMPLATE`，Strategic Merge Patch 每个业务
  Deployment）：`otel.*` 渲染出一个 overlay ConfigMap，后端 env `DEPLOYMENT_TEMPLATE` 指向它，
  于是**所有新发布/重发布的服务**自动获得：
  `OTEL_TRACES_EXPORTER=otlp`、`OTEL_EXPORTER_OTLP_ENDPOINT`、`OTEL_EXPORTER_OTLP_HEADERS`、
  `OTEL_SERVICE_NAME`（agent-framework 侧键名，见其 `application.yml` §OTel）。
- 安全性：仅**新增 env**，不动 selector/labels/envFrom/挂载/保留键 → 不触发
  `validateDeployment` 不变量拒绝（已核对该函数只校验既有约束，额外 env 放行）。
- **存量服务需 republish 才生效**（overlay 在 apply 期合并）——README 明示。
- 备选（未采纳）：给平台默认配置新增 OTel 分组 → 需后端改代码（模板 + 校验），超出本
  chart 范围，列为后续演进。

### 5.4 发布助手自举（bootstrap Job 第②步，release-agent 亦是 chart 的一部分）

发布助手（release-agent，智能发布 OAF 包）默认随 chart 一起交付，装完即可用——前端 assistant
页的 `AGENT_INTERNAL_URL` 默认就指向 `oaf-release-agent-svc`，不装助手则该页不可用。

- **交付物**：`release-agent/`（仓库源）在 **chart 构建期**由 `scripts/pack-release-agent.sh`
  打成 OAF zip 入 `resources/release-agent.oaf.zip` 随 chart 分发（装期不打包，离线无依赖）；
  `packageSource: packageId` 时改用环境已导入的包（复用在线编辑产物，灵活性）。
- **执行**（bootstrap Job 第②步，`releaseAgent.enabled` 默认开，①②独立开关）：
  1. 上传包：`POST /api/v1/packages`（multipart，bundled zip）→ 得 packageId；
  2. 发布服务：`POST /api/v1/services`（name=`release-agent`，image=`releaseAgent.image`
     默认 `images.defaultBusiness`，**env 组装自 `platformDefaults.seed.*` 同一份 values**——
     与①天然一致，助手开箱即有 LLM/MySQL/Redis 配置）。
- **顺序**：①写默认值 → ②发布助手（同一 Job 内串行）；幂等：已存在同名服务则跳过发布步骤
  （查 `GET /api/v1/services` 判重，升级重跑安全）。
- **产物语义**：助手是**经平台发布 API 创建的业务服务**（Deployment/Service/无 per-service
  Ingress，router 模式经 `/agent/release-agent` 访问）——`helm uninstall` **不删除它**（业务对象，
  §9 语义一致）；删除走平台下线/删除 API。
- 联动：`frontend.env.agentInternalUrl` 默认值由 chart 按 `releaseAgent.enabled` 渲染
  （开=指向 `oaf-release-agent-svc`，关=空并提示助手页不可用）。

### 5.5 PVC 复用（含一处硬编码约束，需决议）

- 业务 Pod 与后端的 PVC 名是**编译期常量** `platform-data`（`internal/k8s/objects.go`
  `PVCName`），后端无法让「任意已有 PVC 名」生效。
- **零代码路径**：要求环境中已有 PVC 名为 `platform-data`（`persistence.existingClaim`
  默认同值；chart 渲染时校验，值不等于 `platform-data` 且未开启后端开关则**安装即报错**
  并提示 §5.5 备选方案）。
- **建议（待评审决议，推荐同批实施）**：后端新增 `PLATFORM_PVC_NAME` env（默认
  `platform-data`，仅改常量取用处 + 一处测试）→ `persistence.existingClaim` 才真正自由。
- chart **恒不创建 PVC**：模板不提供 `persistence.create` 开关（连默认关闭的开关都不给，
  避免误用）；后端 `persistence.existingClaim` 为必填（`required`）。

## 6. 路由模式（沿用 subpath-routing-design）

| `routing.mode` | 渲染 | 后端 env |
|---|---|---|
| `router`（默认） | router ConfigMap + Deployment + Service + 共享 Ingress（`/agent`，Prefix，零 rewrite 注解） | **不设** `INGRESS_HOST_SUFFIX` → 启动自检 `platform-router-svc`（chart 同批部署天然满足） |
| `host` | 不渲染任何 router 对象 | 设 `INGRESS_HOST_SUFFIX`（后缀格式由后端 fail-fast 校验） |

- nginx.conf 模板化点：`{{ namespace }}`（Release.Namespace）、业务端口 8100、`oaf-`/`-svc`
  命名约定（**与 backend 常量同源的不变量**，chart README 写明改动须两侧同步）。
- `INGRESS_HOST`/`INGRESS_PORT`/`INGRESS_CLASS` 由 `routing.*` 渲染（Endpoint 展示与 Ingress 构造）。
- 共享 Ingress 的机会性注解（timeout/ssl-redirect）保留，语义同 subpath-routing §6.4。

### 6.2 后端集群访问（KUBECONFIG 两模式，已决议：**默认 synthesized 部署期合成**）

后端 `main.go` 读 `KUBECONFIG`：非空 → 标准 `clientcmd` 从该路径加载；空 → InClusterConfig。
chart 两模式（`backend.kubeconfig.mode`）：

| 模式 | 渲染物 | 适用 |
|---|---|---|
| `synthesized`（**默认，已决议**） | chart **部署期合成** kubeconfig：专用 ServiceAccount（chart 渲染的 Role/ClusterRole 绑定到**该 SA**）+ 显式创建 `type: kubernetes.io/service-account-token` 的 Secret（1.24+ 不自动生成，**显式创建仍会被 token controller 填充**）+ kubeconfig 文件（`tokenFile` 引用挂载的 token、CA 引用 Pod 投影的 `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`、`server` 默认 `https://kubernetes.default.svc` 可覆写）挂载并设 env | 默认形态：**独立显式身份**访问本集群（权限与审计均与 Pod 默认 SA 解耦，RBAC 绑定面清晰） |
| `provided` | 挂载运维提供的 kubeconfig（`existingSecret` 引用已有 Secret，或 `content` 注入新建 Secret）→ 设 `KUBECONFIG` | **业务面在另一集群**（backend 在管理集群、业务 agent 发布到工作集群）；本地开发 |

补充：
- `rbac.clusterScope=true` 仍独立可用（ClusterRole 语义）；synthesized 模式下 ClusterRole
  绑定到专用 SA。
- **synthesized 注意事项**（README 明示）：长生命周期 SA token（不过期、非 TokenRequest
  bound token，deprecated 机制但 1.32 仍支持）→ token Secret 按敏感凭据管理，`helm uninstall`
  随 chart 清理；`server` DNS 名依赖 apiserver 证书 SAN 覆盖 `kubernetes.default.svc`
  （kubeadm/主流托管集群默认覆盖，个别环境用 `kubeconfig.server` 覆写为 Service IP）。

### 6.3 对外暴露：backend / frontend / router 的 Ingress 形态

现网清单只有 backend 的 `/api`+`/healthz`（api）与 `/mcp`（3600 超时）两条 Ingress，
frontend 仅 NodePort。chart 补齐为**两种对外形态**（values 选择，默认 A；**已决议：不提供
NodePort 暴露**，入口一律经集群 ingress controller，`routing.host/port` 描述该入口地址供
Endpoint 展示拼装）：

**形态 A（默认推荐）：统一域名路径分流**

单一入口（`ingress.host`，空 = 无 host catch-all，适合单 IP/测试环境）按最长前缀分流：

| 路径 | backend Service | 必备注解 |
|---|---|---|
| `/` | platform-frontend | — |
| `/api`、`/healthz` | platform-backend | ssl-redirect=false |
| `/mcp` | platform-backend | **proxy-read/send-timeout=3600**（MCP 长会话，沿用现网 platform-backend-mcp） |
| `/agent` | platform-router | subpath-routing 共享 Ingress（§6），可选挂同一 host |

- 前端 env **全部默认值即可**：`NEXT_PUBLIC_API_URL` 空 = 浏览器同源调 `/api/v1`（Ingress 直达 backend）；
  assistant 页硬编码的相对路径 `/agent/release-agent`（`frontend/src/app/assistant/page.tsx:18`）
  天然命中 router——**这是形态 A 成立的关键约束**。
- 渲染物：frontend-ingress + backend-ingress（api/mcp）+ router-ingress（共享 /agent，复用 §6 模板）。

**形态 B（高级，分域）**：`ingress.backend.host=api.example.com`、`ingress.frontend.host=ui.example.com`

- `frontend.env.publicApiUrl` 必须设为 `https://api.example.com/api/v1`（浏览器直连 REST；
  **后端已有 CORS 中间件**，`internal/handler/router.go` `r.Use(CORS())`，已核实）；
- **前端域名的 Ingress 必须同时把 `/agent` 路由给 platform-router**（assistant 页相对路径约束，
  否则助手页 404）——chart 在形态 B 下自动为 frontend ingress 追加该规则；
- MCP 客户端走 backend 域名；TLS 由 `ingress.tls`（cert-manager/环境侧提供证书 Secret）。

**渲染规则**：`ingress.backend.host`/`ingress.frontend.host` 均空 → 形态 A（全部挂 `ingress.host`）；
任一非空 → 按分域渲染（两个都须非空，chart 校验）。`/mcp` 规则恒带 3600 双超时注解；
注：不渲染任何 NodePort Service（已决议）；kind/无域名环境直接用 ingress controller 的
NodePort 入口（`routing.host:port` 即该地址）。

## 7. 离线部署流程（一键）

```bash
# 0) 前置：镜像已由发布流水线推入内网仓库；节点/集群可拉取（imagePullSecrets 就绪）
# 1) 一键安装（values 指向内网仓库与外部中间件）
helm install oaf ./charts/oaf-platform -n agent-platform --create-namespace \
  -f values-offline.yaml
# 2) 装后自检（NOTES.txt 内嵌）
kubectl -n agent-platform rollout status deploy/platform-backend deploy/platform-router
curl -s http://<host>:<port>/api/v1/services           # 平台 API 200
#    发布一个服务 → running、无 per-service Ingress、经 /agent/{short}/health 200
```

- **air-gapped 无 helm** 时：`helm template oaf ./charts/oaf-platform -f values-offline.yaml --output-dir out/`
  出 YAML 后 `kubectl apply -f out/`（渲染校验也在安装前完成）。
- chart 侧不发起任何网络访问（无子 chart、无 hook 下载）；bootstrap Job 只访问集群内平台 Service
  （镜像 `images.bootstrapJob` 同样来自内网仓库）。
- 镜像来源唯一：平台镜像（backend/frontend/router）与业务镜像白名单（`AVAILABLE_IMAGES`）
  都取同一内网仓库，由 `images.*` 与 `images.business[]` 统一渲染，避免两处 tag 错配。

## 8. 与现有 manifests 的关系

- `manifests/*.yaml` **保留**为 kind 开发自举（含 MySQL/Redis/PVC 与固定 tag），不删除；
  chart 是生产/内网/离线交付路径，两者并存（README 与 deployment.md 说明适用场景）。
- **一致性约束**：平台约定（`oaf-` 前缀、`-svc` 后缀、8100 端口、namespace）同时存在于
  backend 常量与 router nginx.conf；chart 渲染必须与 backend 常量一致（subpath-routing
  设计文档的命名不变量在此生效），chart README 显式记录该同步义务。
- 过渡选项：kind 环境也可用 chart（`external.mysql/redis` 指向集群内实例、PVC 沿用
  `platform-data`），便于在开发集群验证 chart 本身。

## 9. 架构强约束核对

| 约束 | 核对 |
|---|---|
| 原则一：业务资源只由显式发布 API 写入 | chart 不渲染任何业务对象；`helm upgrade/uninstall` **不得**触碰业务 Deployment/Service/Ingress |
| 原则一：业务运行时不依赖 backend | chart 不改三条链路（envFrom / PVC 只读直挂 / Ingress→`{name}-svc`）；router 为无状态双副本 |
| 原则一：无后台 reconcile | chart 无 CronJob/控制器；bootstrap Job 为一次性 post-install（两步可关） |
| 原则二：业务面多副本与隔离 | chart 不注入 `sessionAffinity`；不改变 `CHECKPOINT_JDBC_URL` / `AGENT_REDIS_PREFIX` 语义（仅经平台默认值传递，文档强调每服务独立） |
| 卸载语义 | 删除平台对象与 router；**业务对象与已有 PVC 保留**（不设 ownerReference、不设 namespace 级 finalizer）；README 明示「卸载后业务 Pod 仍在运行、`/agent` 路由不可达，需先决定业务下线策略」 |

## 10. 风险与限制

1. **业务服务非 chart 管理**：升级 chart 不影响已发布服务（符合原则一），但也意味着
   overlay / 默认值变更需 **republish** 才对存量生效。
2. **OTel 经 overlay**：仅新发布/重发布生效；与路由模式正交（host 模式同样适用）。
3. **PVC 名硬编码**（§5.5）：已决议加 `PLATFORM_PVC_NAME`（随 PR-B）。
4. **外部 MySQL**：库/账号需预建；schema 由 `AutoMigrate`；chart 不提供备份与版本回退。
5. **外部 Redis**：`noeviction`/持久化由外部实例负责；`AGENT_REDIS_URL` 每服务独立前缀为
   文档级约束（平台不校验，既有缺口）。
6. **镜像 tag 与 `AVAILABLE_IMAGES` 必须一致**（否则发布 400）——两者由同一 values 渲染以降低错配；
   业务 Pod 的 `imagePullPolicy` 固定 `IfNotPresent`（backend 构造，非 chart 可调），因此**同 tag
   覆盖推送不会在已缓存节点生效**，业务镜像更新须使用新 tag（仓库既有实践：
   `agentscope-2.1.0-v{日期}`）。
7. **`helm upgrade` 会滚动 backend/router**（SSE 断流，同 ingress 升级语义）；业务面不受影响。
8. **bootstrap Job** 依赖平台 API 就绪与 `AUTH_TOKEN`；失败会令 release 处于 failed（两步可独立
   关闭，或用 `--atomic`/`--no-hooks` 控制）。发布助手步骤额外依赖：内网仓库有 bootstrap 镜像、
   bundled zip 与 chart 版本一致（包内容演进靠 chart 升级携带新 zip）。
9. **`INGRESS_TEMPLATE` 与 router 模式互斥**：后端对 suffix 空 + 模板非空启动即拒
   （subpath-routing §4.1）；chart 必须在渲染期 fail（`fail "routing.mode=router 时不可配置 business.ingressOverlay"`），
   否则装出一个 CrashLoop 的 backend。
10. **形态 B 的两个硬约束**：assistant 页 agent 调用是**硬编码相对路径** `/agent/release-agent`
   （前端两处文件），分域下前端域名必须同时路由 `/agent`（chart 自动追加，但改前端代码前该约束
   长期存在）；跨域 REST 依赖后端 CORS 中间件的既有行为（已核实存在），若后续收紧 CORS 需同步评估。

## 11. 实施拆分

| PR | 内容 | 依赖 |
|---|---|---|
| PR-A | chart 骨架 + 全部模板 + values + README + 离线脚本 + `helm template` 渲染断言（CI 增加 `helm lint` + `helm template --set` 组合断言） | 无 |
| PR-B（建议同批） | 后端 `PLATFORM_PVC_NAME` env（+ 测试），解 §5.5 约束 | 无 |
| PR-C（可选） | kind 上以 chart 部署的冒烟（外部 MySQL/Redis 指向 kind 内实例、PVC 沿用 `platform-data`），复用现有集群与自检命令 | PR-A |

## 12. 评审决议记录（2026-10-10 批量定案）

1. ~~PVC 名~~ **已决议：PVC 名需可配置** → 实施 `PLATFORM_PVC_NAME`（后端 env，默认
   `platform-data`；代码已完成待提交，随 PR-B），chart `persistence.existingClaim` 指向任意已有 PVC。
2. ~~OTel 方案~~ **已决议：暂不考虑**（§5.3 机制存档备将来；临时需求经 `deploymentOverlay.extra` 手工注入）。
3. ~~seed Job~~ **已决议：纳入并扩展为 bootstrap Job（两步）**——①默认配置种子（装完即可发布）；
   ②**发布助手 release-agent 亦是 chart 的一部分**（默认开，构建期打包 OAF zip 随 chart 分发，
   装后自动上传并经发布 API 起服务，env 与①同一份 values，见 §5.4）。
4. ~~chart 目录位置~~ **已决议：`charts/oaf-platform`**。
5. ~~是否保留 manifests/~~ **已决议：保留**（kind 开发自举与 chart 并存）。
6. ~~镜像清单口径~~ **已决议：统一由 values 渲染、统一内网仓库前缀**；`imagePullSecrets` 由 chart
   透传（三类 Deployment + bootstrap Job；synthesized 模式的专用 SA 同样可加 imagePullSecrets 不适用
   ——token Secret 非镜像，无需）。
7. ~~集群访问形态~~ **已决议：KUBECONFIG 两模式（§6.2）——`provided`（主动提供）与 `synthesized`
  （部署期自动创建），默认 `synthesized`**；`rbac.clusterScope` 独立可选。
8. ~~EVAL_COLLECTOR_*~~ **已决议：不纳入**（评测环境手工设置 env）。
9. ~~对外暴露形态~~ **已决议：形态 A（默认）+ 形态 B（高级）两档，不考虑 NodePort 模式**——
   入口一律经集群 ingress controller，`routing.host/port` 描述入口地址。

**九项全部定案（2026-10-10），进入实施**：PR-A（chart 本体，含 bootstrap Job/发布助手）→ PR-B（`PLATFORM_PVC_NAME`）→ PR-C（可选冒烟）。
