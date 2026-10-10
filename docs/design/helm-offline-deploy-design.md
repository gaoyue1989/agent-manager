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
3. **MySQL / Redis / OTel 全部外部化**：chart 不部署、不创建、不管理；
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
| platform-backend | ServiceAccount + Role + RoleBinding、Deployment、Service（ClusterIP + 可选 NodePort）、Ingress（`/api`、`/healthz`、`/mcp`） | 恒有（Ingress / NodePort 可关） |
| platform-frontend | Deployment、Service（ClusterIP + 可选 NodePort） | 恒有 |
| platform-router | ConfigMap（nginx.conf）、Deployment（副本数可配，默认 2）、Service、共享 Ingress（`/agent`） | `routing.mode=router`（默认） |
| 业务 Deployment overlay | ConfigMap（供后端 `DEPLOYMENT_TEMPLATE` 挂载） | `business.deploymentOverlay.enabled` |
| 平台默认配置种子 | post-install/post-upgrade Job（调平台 API） | `platformDefaults.seed.enabled` |
| **不管理** | MySQL、Redis、OpenSandbox、业务 agent（Deployment/Service/Ingress/CM/Secret）、**任何 PVC**、namespace 内既有对象 | — |

## 3. Chart 目录结构

```
charts/oaf-platform/
├── Chart.yaml                        # apiVersion v2；无 dependencies（自包含）
├── values.yaml                       # 全量默认值（kind 开发语义）
├── values-offline.yaml.example       # 离线示例（内网仓库前缀 / 外部中间件 / 已有 PVC）
├── README.md                         # 快速开始、内网仓库配置、命名同步约束、卸载语义
└── templates/
    ├── _helpers.tpl                  # 命名/labels/必填校验（required）
    ├── namespace.yaml                # 可选创建（默认跟随 Release.Namespace）
    ├── serviceaccount.yaml           # SA + Role + RoleBinding（沿用现有 RBAC 规则集）
    ├── backend-deployment.yaml
    ├── backend-service.yaml          # ClusterIP + 可选 NodePort
    ├── backend-ingress.yaml          # /api /healthz /mcp（可选，class/annotations 可配）
    ├── frontend-deployment.yaml
    ├── frontend-service.yaml
    ├── router-configmap.yaml         # nginx.conf（namespace/端口约定由 values 渲染）
    ├── router-deployment.yaml        # 默认 2 副本，maxUnavailable: 0
    ├── router-service.yaml
    ├── router-ingress.yaml           # 共享 /agent Ingress（零 rewrite 注解）
    ├── business-overlay-configmap.yaml
    ├── seed-defaults-job.yaml        # 可选：写入平台默认配置
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
| 路由 | `routing.mode`（`router`\|`host`）、`routing.ingressClass`、`routing.host`、`routing.port`、`routing.hostSuffix` | router 模式：`host`=对外 IP、`port`=NodePort；host 模式额外 `hostSuffix` |
| 后端 | `backend.serverPort`（8080，联动 Service targetPort）、`backend.replicas`（固定 1，见原则一）、`backend.authToken`、`backend.resources`、`backend.packageDownloadBase`、`backend.register.{timeoutSeconds,retry}`、`backend.kubeconfig` | `authToken` 为空则关闭 Bearer 校验（**生产必填**，经 Secret 注入）；`kubeconfig` 默认关闭（in-cluster），开启时挂载已有 Secret 供集群外开发/特殊 RBAC 场景 |
| 前端 | `frontend.env.{backendInternalUrl,agentInternalUrl,publicApiUrl,evalCollector.{mode,agentUrl}}` | `agentInternalUrl` 默认指向 release-agent；`publicApiUrl`（`NEXT_PUBLIC_API_URL`，默认空=服务端反代同源）；`evalCollector.*` 为评测录制反代模式（默认关闭，仅评测环境） |
| 数据卷 | `persistence.existingClaim`、`persistence.mountPath`（`/data`） | **不创建**，见 §5.4 |
| 外部 MySQL | `external.mysql.{host,port,database,username,password,params}` 或 `dsn` 直给 | 平台元数据库（`oaf_platform`）与业务 checkpoint 库（`oaf_checkpoint`）见 §5.1/§5.2 |
| 外部 Redis | `external.redis.{url}`（业务侧默认值） | 平台自身不使用 Redis；仅用于种子与文档提示 |
| 外部 OTel | `otel.{enabled,exporter,endpoint,headers,serviceName}` | 经业务 Deployment overlay 注入，见 §5.3 |
| 业务 overlay | `business.deploymentOverlay`（OTel 自动段 + `extra` 自备 SMP，合并渲染**单文件**——后端只认一个 `DEPLOYMENT_TEMPLATE` 路径）、`business.ingressOverlay`（自备，**仅 host 模式**） | router 模式配置 `ingressOverlay` → chart 安装即报错（后端对 suffix 空 + `INGRESS_TEMPLATE` 非空启动 fail-fast，chart 提前拦避免 CrashLoop） |
| 平台默认值种子 | `platformDefaults.seed.{enabled,llm.*,mysql.*,redis.*,sandbox.*,protocol.*}` | 调平台 API 写入；默认关闭 |
| 暴露 | `ingress.{enabled,className,annotations}`、`nodePort.{backend,frontend,enabled}` | NodePort 端口可配（默认 30880/30881） |
| 安全 | `imagePullSecrets[]`、`podSecurityContext`、`existingSecret`（密码类复用已有 Secret） | values 明文仅示例，生产走 `existingSecret` |
| 通用调度 | `backend/frontend/router` 各自的 `podAnnotations`、`nodeSelector`、`tolerations`、`affinity` | 生产常见诉求；三组件独立配置 |
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
otel: { enabled: true, exporter: otlp, endpoint: "http://otel-collector.observability:4318",
        headers: "", serviceName: "agent-framework" }
business: { deploymentOverlay: { enabled: true, extra: "" } }
platformDefaults: { seed: { enabled: false } }
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
| 21 | `KUBECONFIG` | main.go | `backend.kubeconfig`（默认关闭=in-cluster ServiceAccount；开启挂载已有 Secret） | **本次补（评审点名）** |
| F1 | `BACKEND_INTERNAL_URL` | 前端 proxy.ts | `frontend.env.backendInternalUrl` | 已有 |
| F2 | `AGENT_INTERNAL_URL` | 前端 proxy.ts | `frontend.env.agentInternalUrl` | 已有 |
| F3 | `NEXT_PUBLIC_API_URL` | 前端 | `frontend.env.publicApiUrl`（默认空=服务端反代同源） | **本次补** |
| F4-F5 | `EVAL_COLLECTOR_MODE` / `EVAL_COLLECTOR_AGENT_URL` | 前端（评测录制反代） | `frontend.env.evalCollector.*`（默认关闭，仅评测环境） | **本次补** |
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

### 5.2 业务服务 → 外部 MySQL(checkpoint) / Redis（经平台默认配置）

- 平台**不向业务 Pod 注入**这两类配置：业务 env 来自「发布时配置」，而发布向导的默认值
  来自**平台默认配置**（`platform_config` 表 → `GET /platform-config/defaults` 预填）。
- 因此 chart 通过 `platformDefaults.seed`（可选 Job）把 `external.mysql`（`CHECKPOINT_JDBC_URL`/
  `CHECKPOINT_USERNAME`/`CHECKPOINT_PASSWORD`）与 `external.redis`（`AGENT_REDIS_URL`）
  写入平台默认配置 → 「发布新服务」自动带出 → 一键语义闭环。
- 不启用种子时：运维在 `/settings` 页手工维护（chart 不阻塞部署）。
- 文档必须强调（既有约束）：**多服务共享同一 MySQL/Redis 时必须每服务独立 checkpoint 库名
  与 `AGENT_REDIS_PREFIX`**，平台当前不自动派生（已知缺口）。

### 5.3 业务服务 → 外部 OTel（经业务 Deployment overlay）

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

### 5.4 PVC 复用（含一处硬编码约束，需决议）

- 业务 Pod 与后端的 PVC 名是**编译期常量** `platform-data`（`internal/k8s/objects.go`
  `PVCName`），后端无法让「任意已有 PVC 名」生效。
- **零代码路径**：要求环境中已有 PVC 名为 `platform-data`（`persistence.existingClaim`
  默认同值；chart 渲染时校验，值不等于 `platform-data` 且未开启后端开关则**安装即报错**
  并提示 §5.4 备选方案）。
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
- chart 侧不发起任何网络访问（无子 chart、无 hook 下载）；seed Job 只访问集群内平台 Service。
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
| 原则一：无后台 reconcile | chart 无 CronJob/控制器；seed Job 为一次性 post-install（可关） |
| 原则二：业务面多副本与隔离 | chart 不注入 `sessionAffinity`；不改变 `CHECKPOINT_JDBC_URL` / `AGENT_REDIS_PREFIX` 语义（仅经平台默认值传递，文档强调每服务独立） |
| 卸载语义 | 删除平台对象与 router；**业务对象与已有 PVC 保留**（不设 ownerReference、不设 namespace 级 finalizer）；README 明示「卸载后业务 Pod 仍在运行、`/agent` 路由不可达，需先决定业务下线策略」 |

## 10. 风险与限制

1. **业务服务非 chart 管理**：升级 chart 不影响已发布服务（符合原则一），但也意味着
   overlay / 默认值变更需 **republish** 才对存量生效。
2. **OTel 经 overlay**：仅新发布/重发布生效；与路由模式正交（host 模式同样适用）。
3. **PVC 名硬编码**（§5.4）：本设计最大约束，推荐同批加 `PLATFORM_PVC_NAME`。
4. **外部 MySQL**：库/账号需预建；schema 由 `AutoMigrate`；chart 不提供备份与版本回退。
5. **外部 Redis**：`noeviction`/持久化由外部实例负责；`AGENT_REDIS_URL` 每服务独立前缀为
   文档级约束（平台不校验，既有缺口）。
6. **镜像 tag 与 `AVAILABLE_IMAGES` 必须一致**（否则发布 400）——两者由同一 values 渲染以降低错配；
   业务 Pod 的 `imagePullPolicy` 固定 `IfNotPresent`（backend 构造，非 chart 可调），因此**同 tag
   覆盖推送不会在已缓存节点生效**，业务镜像更新须使用新 tag（仓库既有实践：
   `agentscope-2.1.0-v{日期}`）。
7. **`helm upgrade` 会滚动 backend/router**（SSE 断流，同 ingress 升级语义）；业务面不受影响。
8. **seed Job** 依赖平台 API 就绪与 `AUTH_TOKEN`；失败会令 release 处于 failed（可关或
   用 `--atomic`/`--no-hooks` 控制）。
9. **`INGRESS_TEMPLATE` 与 router 模式互斥**：后端对 suffix 空 + 模板非空启动即拒
   （subpath-routing §4.1）；chart 必须在渲染期 fail（`fail "routing.mode=router 时不可配置 business.ingressOverlay"`），
   否则装出一个 CrashLoop 的 backend。

## 11. 实施拆分

| PR | 内容 | 依赖 |
|---|---|---|
| PR-A | chart 骨架 + 全部模板 + values + README + 离线脚本 + `helm template` 渲染断言（CI 增加 `helm lint` + `helm template --set` 组合断言） | 无 |
| PR-B（建议同批） | 后端 `PLATFORM_PVC_NAME` env（+ 测试），解 §5.4 约束 | 无 |
| PR-C（可选） | kind 上以 chart 部署的冒烟（外部 MySQL/Redis 指向 kind 内实例、PVC 沿用 `platform-data`），复用现有集群与自检命令 | PR-A |

## 12. 评审关注点（建议逐条决议）

1. ~~§5.4 PVC 名~~ **已决议（2026-10-10）：PVC 名需可配置** → 实施 `PLATFORM_PVC_NAME`（后端 env，默认
   `platform-data`；实现已完成待提交，随 PR-B 走），chart `persistence.existingClaim` 指向任意已有 PVC。
2. **OTel 方案**：确认走 `DEPLOYMENT_TEMPLATE` overlay（备选「平台默认配置加 OTel 组」需后端改动）
3. **seed Job 是否纳入**：纳入则一键部署后即可直接发布；不纳入则部署后仍需 `/settings` 手工填默认值
4. **chart 目录位置**：`charts/oaf-platform`（推荐）vs `deploy/helm/...`
5. **是否保留 `manifests/` 自举**（推荐保留，二者并存）
6. **镜像清单口径**：chart 所需镜像（backend/frontend/router）与业务白名单统一由 values 渲染、
   统一内网仓库前缀（推荐）；`imagePullSecrets` 由 chart 透传到三类 Deployment
7. **KUBECONFIG 是否纳入**（§4.3 #21）：默认关闭（in-cluster），还是干脆不提供（保持纯 in-cluster）？
   （推荐提供但默认关闭，供集群外开发/特殊 RBAC 排障）
8. **EVAL_COLLECTOR_* 是否纳入**（§4.3 F4-F5）：评测录制反代属评测环境专属，纳入 chart 增加面；
   推荐「提供但默认关闭」
