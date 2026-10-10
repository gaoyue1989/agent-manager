# OAF 平台 Helm 部署指南（内网镜像源 · 外部 MySQL/Redis · 复用已有 PVC）

> 适用：`charts/oaf-platform/`（PR #112 起）。设计依据
> [design/helm-offline-deploy-design.md](design/helm-offline-deploy-design.md)；
> manifests 自举（kind 开发）仍见 [deployment.md](deployment.md) §一~§五，二者并存。

---

## 〇、架构与前提总览

```
客户端 ──► 集群 ingress controller（NodePort/80/443，地址记为 <入口>）
             ├─ /                 → platform-frontend（UI）
             ├─ /api  /healthz    → platform-backend（REST）
             ├─ /mcp              → platform-backend（MCP，3600s）
             └─ /agent/{short}/   → platform-router（nginx 剥前缀）→ oaf-{short}-svc:8100
                                          业务 Pod（发布 API 动态创建，Helm 不管理）
外部依赖（chart 不部署）：MySQL（元数据 oaf_platform + 业务 checkpoint 库）、Redis、
镜像内网仓库、已有 PVC（平台数据卷：OAF 包/文件落盘）
```

**环境前提（缺一不可，安装前逐项确认）**

| # | 前提 | 说明 |
|---|---|---|
| 1 | Kubernetes **≥ 1.21**（推荐 1.24+） | `batch/v1` Job（1.21 稳定）；Ingress `networking.k8s.io/v1`（1.19+）。`synthesized` kubeconfig 依赖的显式 service-account-token Secret 在 1.24+ 需显式创建——chart 已如此渲染 |
| 2 | Helm **≥ 3.4**（推荐 3.10+） | chart 用 `deepCopy` 等 3.4+ 模板函数；`apiVersion: v2`。实测基线 Helm 3.14；无 helm 的 air-gapped 可 `helm template` 出 YAML 后 `kubectl apply` |
| 3 | ingress controller 已部署 | 任意实现（路径 `/agent` Prefix 属 Ingress 核心规范）；nginx 类可识别 chart 的超时注解 |
| 4 | **内网镜像仓库**可用，且下列镜像已推送（§一） | 节点可拉取；私有仓库需 imagePullSecret |
| 5 | **外部 MySQL** 已建库授权（§三.1） | `oaf_platform`（元数据）+ 业务 checkpoint 库 |
| 6 | **外部 Redis** 可达（§三.2） | 业务侧 session_event 存储 |
| 7 | **已有 PVC**（§四） | 默认名 `platform-data`，或任意名（需 backend ≥ 含 `PLATFORM_PVC_NAME` 的版本，PR #111） |
| 8 | 可选：OTel Collector | OTel 已决议暂不考虑；如需经 `business.deploymentOverlay` 注入（§六.4） |

---

## 一、镜像准备（内网镜像源）

chart 需要 **4 类平台镜像 + ≥1 个业务镜像**，全部走内网仓库前缀：

| values 键 | 镜像 | 说明 |
|---|---|---|
| `images.backend` | `<内网仓库>/oaf/platform-backend:<tag>` | Go 管理后端（REST+MCP） |
| `images.frontend` | `<内网仓库>/oaf/platform-frontend:<tag>` | Next.js UI |
| `images.router` | `<内网仓库>/oaf/nginx:1.27.1-alpine` | 子路径路由器（官方 nginx 即可，推入内网仓库） |
| `images.bootstrapJob` | `<内网仓库>/oaf/curl:8.8.0` | bootstrap 自举 Job（需 sh + curl） |
| `images.business[]` | `<内网仓库>/oaf/agent-framework:agentscope-<版本>-v<日期>` | 业务运行时白名单（渲染 `AVAILABLE_IMAGES`） |

**镜像导入内网仓库**（任选其一）：

```bash
# 方式 A：有外网中转机 —— 拉取官方/构建镜像后推内网
REG=harbor.internal/oaf
docker pull nginx:1.27.1-alpine && docker pull curlimages/curl:8.8.0
docker tag nginx:1.27.1-alpine $REG/nginx:1.27.1-alpine
docker tag curlimages/curl:8.8.0 $REG/curl:8.8.0
# backend/frontend/agent-framework 由 CI 构建产出后：
# docker tag platform-backend:v9 $REG/platform-backend:v9 && docker push ...
docker push $REG/nginx:1.27.1-alpine && docker push $REG/curl:8.8.0

# 方式 B：完全离线 —— tar 包导入节点（不经过仓库；节点 docker/ctr 可用即可）
docker save <镜像> | gzip > img.tar.gz
# 目标节点：docker load < img.tar.gz   （或 ctr -n k8s.io images import）
```

**私有仓库凭据**（节点无法匿名拉取时）：

```bash
kubectl -n agent-platform create secret docker-registry oaf-registry-cred \
  --docker-server=harbor.internal --docker-username=xxx --docker-password=xxx
# values: imagePullSecrets: [{name: oaf-registry-cred}]  → 透传到所有 Deployment 与 Job
```

⚠️ **业务镜像更新必须换 tag**：业务 Pod 固定 `imagePullPolicy=IfNotPresent`（backend 构造），
同 tag 覆盖推送在已缓存节点不生效。仓库 tag 惯例：`agentscope-<版本>-v<YYYYMMDD>`。

---

## 二、安装

```bash
# 0) 准备 values
cp charts/oaf-platform/values-offline.yaml.example values-offline.yaml   # 按环境修改

# 1) 一键安装
helm install oaf ./charts/oaf-platform -n agent-platform --create-namespace \
  -f values-offline.yaml

# 2) 自检
kubectl -n agent-platform rollout status deploy/oaf-platform-backend
kubectl -n agent-platform rollout status deploy/oaf-platform-router
curl -s http://<入口>/api/v1/services            # 200
curl -s http://<入口>/agent/<短名>/health        # 已发布服务 200
kubectl -n agent-platform logs job/oaf-bootstrap # 种子/发布助手执行记录
```

air-gapped 无 helm：`helm template oaf ./charts/oaf-platform -f values-offline.yaml --output-dir out/`
→ `kubectl apply -f out/`（bootstrap Job 需要 `helm.sh/hook` 语义时改手动建 Job 或容忍其作为普通 Job 执行）。

**渲染期即校验**（fail 早于装出故障）：`images.business` 为空 / `external.mysql` 缺失 /
`routing.mode=host` 缺后缀 / router 模式配 `ingressOverlay` / 分域只填一半 /
`kubeconfig.mode=provided` 缺内容 / `ingress.host` 填 IP（Ingress host 仅接受 DNS 名，
单 IP 环境留空走 catch-all）——安装即报错并给出原因。

---

## 三、外部 MySQL / Redis

chart **不部署**任何中间件；平台自身连 MySQL，业务服务连 MySQL(checkpoint)+Redis，
后者经**平台默认配置**传递（bootstrap Job 写入 → 发布向导预填）。

### 3.1 外部 MySQL（必需）

**建库授权**（一次性，DBA 执行）：

```sql
CREATE DATABASE oaf_platform  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 平台元数据（表由后端 AutoMigrate 自建）
CREATE DATABASE oaf_checkpoint DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; -- 业务 checkpoint（默认共享库）
CREATE USER IF NOT EXISTS 'oaf'@'%' IDENTIFIED BY '<密码>';
GRANT ALL ON oaf_platform.*  TO 'oaf'@'%';
GRANT ALL ON oaf_checkpoint.* TO 'oaf'@'%';
FLUSH PRIVILEGES;
```

**values**：

```yaml
external:
  mysql:
    host: mysql.internal.svc.cluster.local   # 集群内可达地址（外部实例经 Service/Endpoint 暴露）
    port: 3306
    database: oaf_platform
    username: oaf
    password: ""                             # 见下方「密码注入」
    params: "charset=utf8mb4&parseTime=True&loc=Local"
```

**密码注入三选一**（不落 Deployment 明文）：

```bash
# 方式 A（推荐）：自管 Secret，DSN 整条写入 key=mysql-dsn
kubectl -n agent-platform create secret generic oaf-secrets \
  --from-literal=mysql-dsn='oaf:<密码>@tcp(mysql.internal:3306)/oaf_platform?charset=utf8mb4&parseTime=True&loc=Local' \
  --from-literal=auth-token='<REST/MCP Bearer，生产必填>'
# values: backend.existingSecret: oaf-secrets
```

```yaml
# 方式 B：values 给 password（chart 渲染进 oaf-platform-secrets，仅示例/测试用）
external: { mysql: { password: "<明文>" } }
# 方式 C：external.mysql.dsn 直给（同样渲染进 chart Secret）
```

### 3.2 外部 Redis（业务侧必需）

平台自身不用 Redis；业务服务的 `AGENT_REDIS_URL` 经**平台默认配置**预填：

```yaml
platformDefaults:
  seed:
    enabled: true
    values:
      AGENT_REDIS_URL: "redis://redis.internal.svc.cluster.local:6379"
      CHECKPOINT_JDBC_URL: "jdbc:mysql://mysql.internal.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&serverTimezone=UTC"
      CHECKPOINT_USERNAME: "oaf"
      CHECKPOINT_PASSWORD: "<密码>"
      LLM_BASE_URL: "https://llm.internal/v1"
      LLM_MODEL_ID: "glm-4.7"
      LLM_API_KEY: "<key>"
```

**seed 键域限制**：只接受平台配置键（`backend/internal/service/platformconfig/template.go`
的 llm/mysql/redis/sandbox/protocol 分组；全集见 §六.2），未知键被平台 400 拒绝。
不启用 seed 时（`enabled: false`），装后在 UI `/settings` 页手工维护。

**多服务隔离（强制，平台不校验）**：多个业务服务共享同一 MySQL/Redis 时，每服务必须
独立 `CHECKPOINT_JDBC_URL` 库名 + 非空 `AGENT_REDIS_PREFIX`（如 `{service}:`），否则
会话串列、沙箱互锁跨服务影响。发布时在 env 里显式配置。

### 3.3 外部 Redis 语义提醒

- Redis 需 `noeviction` + 持久化（AOF）由外部实例负责——session_event 事件流被淘汰 =
  回放数据静默丢失；
- 内网 Redis 通常有密码：URL 形态 `redis://:<密码>@redis.internal:6379`（经 seed 落
  平台默认配置，发布助手等服务按敏感键路由进服务 Secret）。

---

## 四、复用已有 PVC（chart 不创建）

平台数据卷（OAF 包 / 上传与产出文件）由环境**预先提供**，chart 恒不创建：

```yaml
persistence:
  existingClaim: platform-data   # 环境已有 PVC 名
  mountPath: /data               # 后端 DATA_ROOT 与挂载点同时渲染（保持一致即可）
```

- **PVC 名约束**：默认要求 `platform-data`；**任意名**需 backend 含 `PLATFORM_PVC_NAME`
  支持（PR #111，chart 恒渲染该 env，旧镜像忽略之）——chart 不校验 PVC 是否存在，
  装后 backend Pending/报错时先查 PVC 名与 namespace；
- **容量规划**：`packages/`（OAF 包，每个数 MB）+ `files/`（上传与产出文件）；
- **访问模式**：单节点 RWO 即可（多节点集群也按单挂载设计；扩副本前先评估存储）；
- `helm uninstall` **不删除** PVC（也不删业务服务）。

---

## 五、bootstrap 自举（种子 + 发布助手）

`platformDefaults.seed.enabled` / `releaseAgent.enabled` 任一开启即渲染 post-install Job
（幂等，`helm upgrade` 重跑安全；失败用 `--atomic` 回滚或 `--no-hooks` 跳过）：

1. **①默认配置种子**：`PUT /api/v1/platform-config`（等待 backend 就绪后执行）；
2. **②发布助手自举**：上传 chart 内置 `release-agent.oaf.zip`（构建期由
   `scripts/pack-release-agent.sh` 从仓库源打包）→ `POST /api/v1/services` 发布
   `release-agent`，**env = seed.values ∪ releaseAgent.env**（后者优先）→ 服务 running
   后前端助手页即可用。同名服务已存在则跳过（`helm uninstall` 不删它——业务对象语义）。

`releaseAgent.packageSource: packageId` 时改用环境已导入的包（配 `packageId: <id>`）。

---

## 六、常用配置速查

### 6.1 路由与对外暴露

```yaml
routing: { mode: router, ingressClass: nginx, host: "<入口IP或域名>", port: 30080 }
ingress:
  className: nginx
  host: ""                       # 统一域名（形态 A）；留空 = catch-all（单 IP 测试）；不可填 IP
  # 形态 B（分域）：backend.host + frontend.host 同时填；前端 publicApiUrl 必填
  tls: { enabled: true, secretName: oaf-tls }
```

| `routing.mode` | 行为 |
|---|---|
| `router`（默认） | 部署 platform-router（2 副本）+ 共享 `/agent` Ingress；业务服务**无** per-service Ingress |
| `host` | 每服务独立域名 `host={K8sName}{hostSuffix}`；须给 `routing.hostSuffix`（`.` 开头） |

### 6.2 seed 可用键全集（platform_config）

llm：`LLM_API_KEY/LLM_BASE_URL/LLM_MODEL_ID/LLM_PROVIDER/LLM_TEMPERATURE/LLM_MAX_TOKENS/
LLM_ENABLE_THINKING/LLM_CONTEXT_LENGTH/LLM_REASONING_EFFORT/LLM_FREQUENCY_PENALTY`；
mysql：`CHECKPOINT_JDBC_URL/CHECKPOINT_USERNAME/CHECKPOINT_PASSWORD`；
redis：`AGENT_REDIS_URL/AGENT_REDIS_COMMAND_TIMEOUT_MS/AGENT_REDIS_CONNECT_TIMEOUT_MS`；
sandbox：`OPENSANDBOX_SERVER_URL/OPENSANDBOX_API_KEY/SANDBOX_IMAGE/SANDBOX_TIMEOUT_MINUTES/
SANDBOX_MEMORY_MB/SANDBOX_CPU_COUNT`；protocol：`AGENT_PROTOCOL_AUTH_TOKEN/
AGENT_REMOTE_HEADERS_JSON`。

### 6.3 KUBECONFIG（后端集群访问）

```yaml
backend:
  kubeconfig:
    mode: synthesized            # 默认：部署期合成（专用 SA + token Secret + kubeconfig）
    server: ""                   # 空 = https://kubernetes.default.svc
    # mode: provided             # 跨集群：后端在管理集群、业务发布到工作集群
    # existingSecret: oaf-remote-kubeconfig   # key: kubeconfig
rbac:
  clusterScope: false            # true = ClusterRole（跨 namespace 管理业务）
```

### 6.4 业务级配置（发布期生效）

```yaml
business:
  resources: { requestsCpu: 250m, requestsMem: 256Mi, limitsCpu: "1", limitsMem: 1Gi }  # 每个业务 Pod
  deploymentOverlay:             # DEPLOYMENT_TEMPLATE（SMP），三选一
    enabled: false
    patch: {}                    # 结构化（推荐）；如需 OTel 在此注入 OTEL_* env
    raw: ""
    existingConfigMap: ""        # 运维自管 ConfigMap
  ingressOverlay:                # INGRESS_TEMPLATE，仅 host 模式
    enabled: false
```

Overlay 仅对**新发布/重发布**生效；不得破坏保留键/envFrom/挂载/selector（发布期被
`validateDeployment` 拒）。

---

## 七、升级 / 回滚 / 卸载

```bash
helm upgrade oaf ./charts/oaf-platform -n agent-platform -f values-offline.yaml
helm history oaf -n agent-platform
helm rollback oaf <revision> -n agent-platform
helm uninstall oaf -n agent-platform
```

- `upgrade` 滚动 backend/frontend/router（SSE 长连接会断，选窗口）；**业务服务不受影响**；
- overlay/默认值变更对**存量业务服务**需 republish 才生效；
- `uninstall` 删除平台对象；**业务服务与 PVC 保留**——业务 Pod 仍在运行但入口/路由不可达，
  卸载前先下线业务（平台删除 API 或 `kubectl delete deploy oaf-*`）。

---

## 八、故障排查速查

| 现象 | 定位 |
|---|---|
| backend CrashLoop：`router mode: service .../platform-router-svc not found` | router Service 未就绪/被改名（**名固定 `platform-router-svc`**，后端常量）；查 `kubectl get svc platform-router-svc` |
| backend CrashLoop：MySQL 连接失败 | 外部 MySQL 未建库/凭据错；`kubectl exec ... -- mysql -h<host> ...` 验证 |
| bootstrap Job 失败 `unknown platform config key` | seed.values 混入服务级键（如 `SANDBOX_ENABLED`）→ 移到 `releaseAgent.env` |
| 发布服务一直 deploy_failed | Pod CrashLoop：查业务 Pod 日志；常见 = 包目录空（包 id 与库不匹配）或缺 env |
| `/agent/{short}/` 502 | 短名错误（`/agent/` 后是**去 `oaf-` 前缀的短名**）或服务不存在（DNS 10s 缓存窗口） |
| Ingress 创建失败 `host must be a DNS name` | `ingress.host` 填了 IP → 留空走 catch-all |
| UI 能开但 API 404 | 经独立域名/端口访问（形态 B）未设 `frontend.env.publicApiUrl` |
| 业务镜像拉取失败 | `imagePullSecrets` 未配或仓库凭据过期 |

---

## 九、与 manifests 自举的关系

| | `helm`（本指南） | `manifests/*.yaml` |
|---|---|---|
| 适用 | 生产/内网/离线交付；values 参数化 | kind 单机开发自举 |
| MySQL/Redis | **外部**（必填 values） | 集群内部署（含 PVC） |
| PVC | **复用已有**（不创建） | chart 同名清单创建 |
| 对外暴露 | 仅 Ingress 两形态 | NodePort 30880/30881 + Ingress |
| 平台对象名 | `{release}-*`（router svc 固定 `platform-router-svc`） | 固定名 |

混用注意：同一 namespace 二选一；从 manifests 迁移到 helm 见设计 §13.6（删旧平台对象 →
helm install，数据/业务全保留，迁移窗口业务无感）。
