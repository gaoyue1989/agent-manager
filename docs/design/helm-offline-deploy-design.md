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
| 路由 | `routing.mode`（`router`\|`host`）、`routing.ingressClass`、`routing.host`、`routing.port`、`routing.hostSuffix` | router 模式：`host`=对外 IP、`port`=NodePort；host 模式额外 `hostSuffix` |
| 后端 | `backend.replicas`（固定 1，见原则一）、`backend.authToken`、`backend.resources`、`backend.packageDownloadBase`、`backend.templates.{deployment,ingress}` | `authToken` 为空则关闭 Bearer 校验（**生产必填**，经 Secret 注入） |
| 前端 | `frontend.env.backendInternalUrl`、`frontend.env.agentInternalUrl` | 后者默认指向 release-agent（发布助手对话） |
| 数据卷 | `persistence.existingClaim`、`persistence.mountPath`（`/data`） | **不创建**，见 §5.4 |
| 外部 MySQL | `external.mysql.{host,port,database,username,password,params}` 或 `dsn` 直给 | 平台元数据库（`oaf_platform`）与业务 checkpoint 库（`oaf_checkpoint`）见 §5.1/§5.2 |
| 外部 Redis | `external.redis.{url}`（业务侧默认值） | 平台自身不使用 Redis；仅用于种子与文档提示 |
| 外部 OTel | `otel.{enabled,exporter,endpoint,headers,serviceName}` | 经业务 Deployment overlay 注入，见 §5.3 |
| 业务 overlay | `business.deploymentOverlay.{enabled,extra}` | `extra` 为任意 SMP patch（与 `otel.*` 合并渲染） |
| 平台默认值种子 | `platformDefaults.seed.{enabled,llm.*,mysql.*,redis.*,sandbox.*,protocol.*}` | 调平台 API 写入；默认关闭 |
| 暴露 | `ingress.{enabled,className,annotations}`、`nodePort.{backend,frontend,enabled}` | NodePort 端口可配（默认 30880/30881） |
| 安全 | `imagePullSecrets[]`、`podSecurityContext`、`existingSecret`（密码类复用已有 Secret） | values 明文仅示例，生产走 `existingSecret` |

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

## 11. 实施拆分

| PR | 内容 | 依赖 |
|---|---|---|
| PR-A | chart 骨架 + 全部模板 + values + README + 离线脚本 + `helm template` 渲染断言（CI 增加 `helm lint` + `helm template --set` 组合断言） | 无 |
| PR-B（建议同批） | 后端 `PLATFORM_PVC_NAME` env（+ 测试），解 §5.4 约束 | 无 |
| PR-C（可选） | kind 上以 chart 部署的冒烟（外部 MySQL/Redis 指向 kind 内实例、PVC 沿用 `platform-data`），复用现有集群与自检命令 | PR-A |

## 12. 评审关注点（建议逐条决议）

1. **§5.4 PVC 名**：接受「必须叫 `platform-data`」，还是同批加 `PLATFORM_PVC_NAME`？（推荐后者）
2. **OTel 方案**：确认走 `DEPLOYMENT_TEMPLATE` overlay（备选「平台默认配置加 OTel 组」需后端改动）
3. **seed Job 是否纳入**：纳入则一键部署后即可直接发布；不纳入则部署后仍需 `/settings` 手工填默认值
4. **chart 目录位置**：`charts/oaf-platform`（推荐）vs `deploy/helm/...`
5. **是否保留 `manifests/` 自举**（推荐保留，二者并存）
6. **镜像清单口径**：chart 所需镜像（backend/frontend/router）与业务白名单统一由 values 渲染、
   统一内网仓库前缀（推荐）；`imagePullSecrets` 由 chart 透传到三类 Deployment
