# oaf-agent — OAF 业务 Agent 业务面 chart

OAF 服务发布平台**跨集群导出发布**的生产侧交付 chart（设计 [cross-cluster-export-design.md](../../docs/design/cross-cluster-export-design.md) §6，实施契约 [cross-cluster-export-contract.md](../../docs/implementation/cross-cluster-export-contract.md) §6）。与控制面 chart `charts/oaf-platform` **完全独立、自包含**：

- 不引用平台任何对象（共享 PVC、共享路由器、平台 backend、平台默认配置表，见设计 §6 自包含红线）；
- namespace 一律 `.Release.Namespace`（不硬编码，release 名与 `-n` 由部署方指定）；
- MySQL/Redis/OTel/沙箱地址、`imagePullSecrets`、路由 host 全部来自 values；
- 生产 4 件套 = **本 chart + values.yaml + agent-framework 基础镜像 + OAF 配置镜像**（后两者由导出链路构建直推，见设计 §5）。

## 模板清单

| 模板 | 产物 | 说明 |
|------|------|------|
| `deployment.yaml` | `{fullname}` | initContainer `inject-oaf`（image.oaf，`cp -a /oaf/. /config/` 注入 emptyDir）+ 主容器 agent（image.base）+ `/health:8100` 探针 + `HOST_NAME` downward API 注入 |
| `service.yaml` | `{fullname}-svc` | ClusterIP:8100；无 sessionAffinity（多副本横向扩展语义与单集群一致） |
| `ingress.yaml` | `{fullname}` | 双模式：`host`（默认）/ `router`（见下） |
| `configmap.yaml` | `{fullname}-env` | values.env（非敏感 env，空值剔除） |
| `secret.yaml` | `{fullname}-env-secret` | values.secretEnv（敏感 env，stringData） |
| `mysql-init-job.yaml` | `{fullname}-mysql-init` | `mysql.createDatabase=true` 时启用；pre-install hook 幂等建库 |
| `serviceaccount.yaml` | `{fullname}-sa` | 最小 SA，无 RBAC（业务 Pod 无 K8s API 依赖） |

envFrom 两源与平台发布链路同序：**服务 Secret `{fullname}-env-secret` 在前、CM `{fullname}-env` 在后**（后引用者覆盖同名键）。

## 路由双模式（语义对齐 `INGRESS_HOST_SUFFIX`）

- **host（默认）**：`host={fullnameOverride}{ingress.hostSuffix}`、path `/`、pathType Prefix，仅 `ssl-redirect=false` 与 `proxy-read/send-timeout=3600`，无 rewrite 注解（与平台 host 模式不变量一致）。
- **router**：子路径 `ingress.pathPrefix`（如 `/agent/travel-agent`）+ ingress-nginx `rewrite-target`/`use-regex`/`x-forwarded-prefix` 注解剥前缀——生产集群无平台共享路由器，该语义由环境自担。

## values 必填项

| 键 | 必填 | 说明 |
|----|------|------|
| `fullnameOverride` | ★ | 对象名基底（DNS-1123 小写串、≤52 字符）；导出渲染恒写入 |
| `image.base` | ★ | agent-framework 基础镜像完整引用 |
| `image.oaf` | ★ | OAF 配置镜像完整引用（busybox 基底 + tar 层挂载点 `/oaf/`） |
| `env` / `secretEnv` | 可空 map | 空时 CM/Secret 仍渲染（空对象），保 envFrom 引用不悬空；须含平台保留键 `AGENT_CONFIG_DIR=/config`、`AGENT_WORKSPACE_DIR=/workspace`、`SERVER_HOST=0.0.0.0`、`SERVER_PORT="8100"` |
| `ingress.mode` | 缺省 host | `host` 需 `ingress.hostSuffix`（以 `.` 开头）；`router` 需 `ingress.pathPrefix` |
| `mysql.createDatabase` | 缺省 false | true 时启用建库 hook Job（`mysql.jobImage` 缺省 `mysql:8.0`，离线环境改为内网引用）；建库目标从 env/secretEnv 的 `CHECKPOINT_JDBC_URL` 解析，账号取 `CHECKPOINT_USERNAME`/`CHECKPOINT_PASSWORD`（缺省 root） |
| `imagePullSecrets` / `resources` / `replicas`（缺省 2）/ `podAnnotations` | 可选 | — |

渲染期 `oaf-agent.validate` 守卫（缺值/非法值 `helm install` 直接失败）：fullname 格式与长度、镜像两必填、configInjection.mode、env.AGENT_CONFIG_DIR 与 configInjection.dir 一致、replicas ≥1、ingress 双模式参数、tls secretName、mysql 建库前提。

## 部署示例（host 模式）

```bash
# 1.（可选）mysql.createDatabase=true 时，pre-install hook Job 幂等建库
#    前提：所用账号对 `{基库}_%` 模式库有 CREATE 权限（对齐 publishisolation.go 前提）
# 2. 安装（values 可由平台导出单下载获得，或手写）
helm upgrade --install travel-agent /path/to/charts/oaf-agent \
  -n prod-agents --create-namespace \
  --set fullnameOverride=travel-agent \
  --set image.base=harbor.internal/oaf/agent-framework:agentscope-2.1.0 \
  --set image.oaf=harbor.internal/oaf/travel-agent:1.2.0-e1 \
  --set ingress.mode=host \
  --set ingress.hostSuffix=.prod.example.cn \
  --set 'env.AGENT_CONFIG_DIR=/config' \
  --set 'env.AGENT_WORKSPACE_DIR=/workspace' \
  --set 'env.SERVER_HOST=0.0.0.0' \
  --set 'env.SERVER_PORT=8100' \
  --set 'env.CHECKPOINT_JDBC_URL=jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint_travel_agent?createDatabaseIfNotExist=true' \
  --set 'secretEnv.LLM_API_KEY=sk-xxx'
# 3. 自检（NOTES.txt 有完整清单）
kubectl -n prod-agents rollout status deploy/travel-agent
kubectl -n prod-agents wait --for=condition=complete job/travel-agent-mysql-init --timeout=120s
curl -s -H 'Host: travel-agent.prod.example.cn' http://<ingress 入口>/.well-known/agent-card.json | head
```

CI 渲染全量对象用占位值见 `ci-values.yaml`（charts-ci 的 helm template 消费）。

## 运维须知

- **配置注入**：`/config` 为 emptyDir 只读副本，由 initContainer 从 image.oaf 拷入（替代单集群共享 PVC subPath 只读挂载）；Pod 重建自动重拷恢复。
- **Pod 本地卷**：`/workspace`、`/data/files`、`/applog` 为 emptyDir（可丢弃/可重建，对齐平台业务 Pod 形态；基础镜像以非 root `appuser` 运行，不能在容器根创建这些目录）；正确性状态落 MySQL checkpoint / Redis，跨副本文件不共享为 v1 已知边界。
- **建库 Job**：pre-install hook 在其余资源之前执行，此时尚无服务 Secret，故凭据内嵌于 Job env（明文）；成功后对象保留供 `kubectl wait` 自检，确认后可 `kubectl delete job` 清理。
- **imagePullPolicy=IfNotPresent**：同 tag 覆盖推送不会在已缓存节点生效——请使用新 tag。
