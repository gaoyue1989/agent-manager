# oaf-platform Helm Chart

OAF 服务发布平台控制面的一键部署 chart（**自包含**，无子 chart）。

> **完整部署文档（内网镜像源 / 外部 MySQL·Redis / 复用已有 PVC / 排查速查）：
> [docs/helm-deployment.md](../../docs/helm-deployment.md)**

设计依据 [docs/design/helm-offline-deploy-design.md](../../docs/design/helm-offline-deploy-design.md)。

部署内容：`platform-backend`（REST + MCP）、`platform-frontend`（UI）、`platform-router`
（router 模式子路径路由器）、RBAC、对外 Ingress、可选的平台自举钩子（bootstrap Job）。

**版本要求**：Helm **≥ 3.4**（chart 使用 `deepCopy` 等模板函数；`apiVersion: v2`）；Kubernetes
**≥ 1.21**（`batch/v1` Job；Ingress `networking.k8s.io/v1` 需 1.19+）。实测基线：Helm 3.14 + K8s 1.32。
air-gapped 无 helm 时 `helm template --output-dir` 出 YAML 后 `kubectl apply`（渲染与校验在安装期外完成）。

**不部署 / 不创建**：MySQL、Redis、OpenSandbox、**任何 PVC**、业务 agent 服务
（业务服务由平台发布 API 动态创建，**Helm 永不管理**）。

## 快速开始

```bash
# 1) 准备：内网镜像已推送、外部 MySQL 建库授权、已有 PVC、values-offline.yaml
cp charts/oaf-platform/values-offline.yaml.example values-offline.yaml   # 按环境修改

# 2) 安装
helm install oaf ./charts/oaf-platform -n agent-platform --create-namespace -f values-offline.yaml

# 3) 自检（详见 helm 输出的 NOTES）
kubectl -n agent-platform rollout status deploy/oaf-platform-backend deploy/oaf-platform-router
curl -s http://<ingress-host>/api/v1/services
```

air-gapped 无 helm 时：`helm template oaf ./charts/oaf-platform -f values-offline.yaml --output-dir out/`
→ `kubectl apply -f out/`（渲染校验在安装前完成）。

## 关键 values

| 分组 | 说明 |
|---|---|
| `images.*` | 三类平台镜像 + `bootstrapJob`（curl 容器）+ `business[]` 业务镜像白名单；**统一内网仓库前缀**，`imagePullSecrets` 透传到各 Deployment 与 Job |
| `routing.mode` | `router`（默认，部署 platform-router + 共享 `/agent` Ingress）或 `host`（每服务独立域名，须给 `hostSuffix`） |
| `ingress.*` | 形态 A（`host`，统一域名路径分流：`/`→前端、`/api`+`/healthz`+`/mcp`→后端、`/agent`→router）；形态 B（`backend.host`+`frontend.host` 分域，前端域自动追加 `/agent` 路由）。**不提供 NodePort 暴露** |
| `persistence.existingClaim` | **必填**，指向环境已有 PVC（渲染 `PLATFORM_PVC_NAME`；chart 不创建） |
| `external.mysql.*` | 平台元数据库（外部）。DSN 经 Secret 注入，不落 Deployment 明文 |
| `external.redis.url` | 业务侧 Redis（用于默认配置种子；平台自身不用 Redis） |
| `backend.kubeconfig.mode` | `synthesized`（默认，部署期合成：专用 SA + token Secret + kubeconfig）或 `provided`（跨集群，给 `existingSecret`/`content`） |
| `backend.authToken` | REST/MCP Bearer（生产必填；建议 `backend.existingSecret`，key `auth-token`） |
| `business.resources` | 业务 Pod 资源四元组（渲染 `RESOURCE_*`，影响每个发布的服务） |
| `business.deploymentOverlay` | `DEPLOYMENT_TEMPLATE`（SMP，作用于每个业务 Deployment）：三选一 `patch`（结构化，推荐）/ `raw`（原文）/ `existingConfigMap`（运维自管）。OTel 暂不考虑，如需经此注入 `OTEL_*` |
| `business.ingressOverlay` | `INGRESS_TEMPLATE`，**仅 host 模式**（router 模式配置即渲染期报错） |
| `platformDefaults.seed` | bootstrap 第①步：写平台默认配置（**只接受平台配置键**：llm/mysql/redis/sandbox/protocol 分组，键名同 `backend/internal/service/platformconfig/template.go`；未知键被平台 400 拒绝），使发布向导自动预填 |
| `releaseAgent.env` | 发布助手的**业务 env**（服务级键如 `SANDBOX_ENABLED`/`FILE_EXTERNAL_URL_PREFIXES`/`AGENT_REDIS_PREFIX`）；与 `platformDefaults.seed.values` 合并（后者优先） |
| `releaseAgent.*` | bootstrap 第②步：**发布助手自举**（默认开）。`packageSource: bundled` 用 chart 内置包，`packageId` 用环境已导入包 |

## 平台自举钩子（bootstrap Job）

`platformDefaults.seed.enabled` / `releaseAgent.enabled` 任一开启即渲染 post-install/post-upgrade Job：

1. 等 backend `/healthz` 就绪；
2. ① `PUT /api/v1/platform-config` 写默认值（发布向导预填，一键闭环）；
3. ② 幂等判重后：上传 OAF 包（bundled）→ `POST /api/v1/services` 发布 `release-agent`
   （env 与①同一份 values）。

失败处理：`--atomic` 回滚或 `--no-hooks` 跳过；步骤可独立关闭。

## 敏感值管理

values 中的明文（MySQL 密码、`authToken`、LLM key 等）仅作示例。生产建议：

```bash
kubectl -n agent-platform create secret generic oaf-secrets \
  --from-literal=mysql-dsn='oaf:***@tcp(mysql:3306)/oaf_platform?charset=utf8mb4&parseTime=True' \
  --from-literal=auth-token='***'
# values: backend.existingSecret=oaf-secrets
```

## 命名不变量（与 backend 同源，改动须两侧同步）

router 模式的 nginx.conf 固化：`oaf-` 前缀 / `-svc` 后缀 / 业务端口 8100 /
namespace（= 本 chart 的 namespace）；**router Service 名固定 `platform-router-svc`**
（后端常量 `k8s.RouterServiceName`，router 模式启动自检按此查找，改名即 CrashLoop）。`/agent/{short}` 的 `{short}` 为**去 `oaf-` 前缀的短名**
（与服务详情 Endpoint 一致）；误用 k8sName 会得到 502（未知服务语义）。

## 卸载语义

`helm uninstall` 删除平台对象与 router；**业务服务与已有 PVC 保留**（不设 ownerReference）。
业务 Pod 卸载后仍在运行，但入口/路由随平台对象删除而不可达——卸载前请先决定业务下线策略
（平台下线/删除 API）。

## 构建期：内置发布助手包

`resources/release-agent.oaf.zip` 由 `scripts/pack-release-agent.sh` 从仓库 `release-agent/`
源打包（AGENTS.md 必须在 zip 根）。**chart 打包流水线须在 `helm package` 前执行该脚本**，
否则 bundled 模式缺包（渲染期会因 `.Files.Get` 失败而报错）。
