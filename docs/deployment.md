# OAF 服务发布平台 — 部署指南（v2）

> 历史设计归档：[design/](design/)（v2 重构设计见 [design/REDESIGN.md](design/REDESIGN.md)） ｜ 模块指引：各目录 AGENTS.md

## 前置条件

- Kind 单节点集群（集群名 `agent-manager`；集群配置原文内嵌于 [design/01-k8s-deployment.md](design/01-k8s-deployment.md) §3，仓库内无独立 kind-config.yaml），ingress-nginx 已装（NodePort 30080）
- 宿主机已装 Docker、kubectl、Go 1.26+（与 backend/go.mod 一致）、Maven+JDK21
- GOPROXY 走 `https://goproxy.cn,direct`

## 一、构建镜像

> tag 必须与 `manifests/*.yaml` 中 `image:` 字段一致，否则 apply 后 Pod 拉不到镜像（ImagePullBackOff）。
> 当前值：`platform-backend:v4`（platform.yaml:267）、`172.20.0.1:5001/platform-frontend:v7`（frontend.yaml:17）、
> `172.20.0.1:5001/agent-framework:latest`（platform.yaml:275-276 的 AVAILABLE_IMAGES/DEFAULT_IMAGE）。

```bash
# 1) agent-framework（业务运行时，Java）——发布助手/业务 Agent 的业务镜像
cd agent-framework && mvn -q clean package -DskipTests
docker build -t agent-framework:latest .

# 2) platform-backend（Go 管理后端 + MCP 同进程）
cd ../backend && docker build -t platform-backend:v4 .

# 3) platform-frontend（Next.js standalone）
cd ../frontend && docker build -t 172.20.0.1:5001/platform-frontend:v7 .
```

## 二、导入镜像到 Kind 节点

> 注意：必须用 `--platform linux/amd64` 导出（官方镜像含 arm64/attestation 条目会导致 ctr 导入失败）

```bash
# platform-backend 与 agent-framework 是本地 tag（imagePullPolicy: IfNotPresent），走 ctr 导入
for img in agent-framework:latest platform-backend:v4; do
  f=/tmp/opencode/$(echo $img | tr ':/' '__').tar
  docker save --platform linux/amd64 -o $f $img
  docker cp $f agent-manager-control-plane:/var/tmp/img.tar
  docker exec agent-manager-control-plane ctr -n k8s.io images import --all-platforms /var/tmp/img.tar
done

# platform-frontend 是带 registry 前缀的 tag 且 imagePullPolicy: Always
# → 节点必须能访问 172.20.0.1:5001，先 push 到该registry（不能靠 ctr 导入）
docker push 172.20.0.1:5001/platform-frontend:v7
```

## 三、部署平台

```bash
cd manifests
kubectl apply -f platform.yaml          # ns/RBAC/PVC/MySQL8/platform-backend
kubectl apply -f platform-ingress.yaml # REST/MCP 入口
kubectl apply -f frontend.yaml         # 前端 NodePort 30881
kubectl -n agent-platform rollout status deployment --timeout=300s
```

## 四、自举发布助手

浏览器打开前端 → 上传 `release-agent/` 打包的 zip（或直接调 API）→ 发布：
镜像选 `172.20.0.1:5001/agent-framework:latest`（platform.yaml 的 DEFAULT_IMAGE/AVAILABLE_IMAGES，
即 §一 产出的业务镜像），env 填 LLM_*、CHECKPOINT_JDBC_URL 与 AGENT_REDIS_URL（见下）。

> `AGENT_REDIS_URL=redis://oaf-redis.agent-platform.svc.cluster.local:6379`（oaf-redis 已随 platform.yaml 部署）。
> 缺省值指向 `127.0.0.1`（Pod 自身），session_event 事件不落 Redis、SSE 断线回放/续传全挂——部署必配。

> **DB schema 迁移（Flyway，2026-09-28 起）**：agent-framework 启动时自动执行
> `db/migration` 版本化迁移——存量库首次启动自动基线（V1..V9 已就位，仅跑增量）、
> 全新库从 V1 完整重建，发版无需人工干预；多副本同时启动由历史表锁互斥。
> 此后表结构/数据演进只新增 V 文件（见 docs/design/db-migration-flyway-design.md），不再手工改库。

## 五、业务日志规范（agent-framework）

业务 Agent（framework 镜像）日志按《容器日志收集方案-v2》规范输出：容器云日志收集器**仅采集** `/applog/${HOST_NAME}/trace.log`，控制台日志与其他文件均不采集。

| 项 | 值 | 说明 |
|----|-----|------|
| 日志路径 | `/applog/${HOST_NAME}/trace.log` | `HOST_NAME` = Pod Name，文件名固定不可改 |
| 日志格式 | `时间 [HOST_NAME] [APP_NAME] [级别] [线程] [traceId] 类简名 - 内容` | 毫秒级时间；traceId 取自 OTel Span（无活跃 span 输出 `-`），与 Jaeger 链路打通；类简名仅类名不含包路径 |
| 滚动策略 | 单文件 200MB，归档 `trace.log1` / `trace.log2`，合计上限 600MB | 滚动由 logback `FixedWindowRollingPolicy(1..2)` 承载 |
| 编码 | UTF-8 | 镜像 ENTRYPOINT 已硬编码 `-Dfile.encoding=UTF-8`（先于可覆盖的 `JAVA_OPTS`） |

日志相关环境变量：

| 变量 | 来源 | 说明 |
|------|------|------|
| `HOST_NAME` | platform-backend 构造 Deployment 时固定注入（保留键，用户 env 撞名即 400） | 决定日志目录 `/applog/${HOST_NAME}/` 与日志内容 `[主机名]` 字段 |
| `APP_NAME` | 业务 env 可选指定（非保留键） | 日志内容 `[应用名]` 字段，用于 Kibana 按应用检索；缺省 `agent-framework` |
| `SPRING_PROFILES_ACTIVE` | 平台不注入，缺省即启用控制台 + 文件双输出 | 本地 `make dev` 固定 `dev` profile，仅控制台、不写 `/applog` |

注意事项：

1. `HOST_NAME` 注入与 `/applog` 卷（emptyDir）由 platform-backend 构造业务 Deployment 时下发，**已上线服务需重新发布（republish）后才生效**。
2. 生产容器云接入日志采集时，由运维将 `/applog` 改挂 hostPath `/var/log/mounts/${namespace}/${app-name}` 并配置 Kibana 索引模板（hostName / app-name 字段映射，按 `app-name` 检索）；平台构造逻辑不变。
3. 内容约束：单条日志 ≤ 4KB（超长会被日志云截断）、避免大量特殊字符、禁止输出密码/密钥等敏感信息；非应用日志（GC 等）不要以 `.log` 结尾（建议 `.txt`），避免与应用日志混淆。

## 关键端点

| 入口 | 地址 |
|------|------|
| 统一入口（宿主机 nginx） | http://100.66.1.5:8911 |
| 前端直连 | http://172.20.0.3:30881 |
| REST API | http://localhost:30080/api/v1 |
| MCP | http://localhost:30080/mcp |
| 发布助手 Debug 页 | http://172.20.0.3:30080/agent/release-agent/debug |

## 凭据与敏感配置

| 项 | 值 |
|----|----|
| MySQL（oaf-mysql） | 用户 `oaf` / 密码 `OafPlatform2026`（root 同） |
| LLM | `.env.secrets`（mimo-v2.5） |

Checkpoint DSN 模板：
```
jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
```

## 运维备忘

1. **更新任一镜像后**：重新 save/import 后必须 `kubectl -n agent-platform rollout restart deployment/<name>`——同名 tag 不会自动触发滚动，且 Ingress 注解由 platform-backend 下发，改注解逻辑后必须重启它再 republish。
2. 业务 Pod 的 OAF 包挂载在 `/config`（只读）+ 工作区 `/workspace`（可写）+ 日志目录 `/applog`（可写，规范日志输出，见「五、业务日志规范」）。
3. MCP server 不可达默认不阻断启动（fail-soft）；必需依赖在包内写 `startup.required: true`。
4. **发布多个业务服务时的数据隔离（强制，见 AGENTS.md「架构强约束」）**：每个服务必须配独立的 `CHECKPOINT_JDBC_URL` 库名（如 `oaf_checkpoint_{service}`，需先建库授权）与非空 `AGENT_REDIS_PREFIX`（如 `{service}:`）。平台默认值是共享 `oaf_checkpoint` 库 + 空 Redis 前缀——多服务沿用默认会互相串数据（`/threads` 会话列表跨服务可见、沙箱并发守卫跨服务互锁），平台当前不校验，靠发布时显式配置保证。
5. backend 重启/升级不影响已在跑的业务服务（数据面不经 backend）；唯一边界：发布等待期（`deploying` 状态）重启 backend 会丢后台注册推进，该服务会停在 `deploying`，用 Republish 解救，业务 Pod 不受影响。

## 六、业务 Deployment 环境模板（DEPLOYMENT_TEMPLATE，可选）

业务 Agent 的 Deployment 由 platform-backend 内置逻辑构造；部署形态（PVC 名、调度约束、镜像拉取密钥、sidecar 等）可通过可选的 **YAML overlay** 调整，不改 Go 代码。

```bash
# 1) 从示例裁剪出环境的 overlay（只保留要改的字段）
#    完整合并规则/校验不变量/可改项见 backend/templates/deployment-overlay.example.yaml 注释
kubectl -n agent-platform create configmap deployment-template \
  --from-file=overlay.yaml=backend/templates/deployment-overlay.example.yaml

# 2) 挂载给 platform-backend 并设置环境变量
kubectl -n agent-platform patch deploy platform-backend --type='json' -p='[
  {"op":"add","path":"/spec/template/spec/containers/0/volumeMounts/-","value":{"name":"deploy-tpl","mountPath":"/etc/oaf/deployment-template","readOnly":true}},
  {"op":"add","path":"/spec/template/spec/volumes/-","value":{"name":"deploy-tpl","configMap":{"name":"deployment-template"}}},
  {"op":"add","path":"/spec/template/spec/containers/0/env/-","value":{"name":"DEPLOYMENT_TEMPLATE","value":"/etc/oaf/deployment-template/overlay.yaml"}}]'

# 3) 生效与回滚
kubectl -n agent-platform rollout restart deployment/platform-backend   # 新发布/republish 即带 overlay 形态
kubectl -n agent-platform set env deploy/platform-backend DEPLOYMENT_TEMPLATE-  # 回滚=去掉环境变量
```

行为要点：

- **不设置 `DEPLOYMENT_TEMPLATE` = 纯内置构造**，行为与未上此功能前完全一致。
- overlay 按 K8s Strategic Merge Patch 语义合并：`volumes`/`containers` 按 name 子合并（换 PVC 名只写 `claimName` 一个字段），其余内置字段保留；追加容器即新增 sidecar。
- 非法 overlay **启动即失败**（backend CrashLoop，日志指明违例项）；发布期二次校验兜底，违规发布被拒、服务转 error。
- overlay 变更只影响之后 apply 的服务；**存量服务需 republish 或 rollout restart 才滚动到新形态**。

## 七、业务 Ingress 环境模板（INGRESS_TEMPLATE，可选）

每个业务服务的 Ingress 由 platform-backend 内置逻辑构造；需要自定义**域名（host）、对外前缀（path）、TLS、白名单/CORS 类注解**时，可通过可选的 YAML overlay 调整，不改 Go 代码。模式与 §六 完全一致：

```bash
# 1) 从示例裁剪出环境的 overlay
#    合并规则/占位符/校验不变量/完整 rules 抄改样例见 backend/templates/ingress-overlay.example.yaml 注释
kubectl -n agent-platform create configmap ingress-template \
  --from-file=overlay.yaml=backend/templates/ingress-overlay.example.yaml

# 2) 挂载给 platform-backend 并设置环境变量（volume/mount 与 §六 可复用同名资源则跳过）
kubectl -n agent-platform patch deploy platform-backend --type='json' -p='[
  {"op":"add","path":"/spec/template/spec/containers/0/volumeMounts/-","value":{"name":"ingress-tpl","mountPath":"/etc/oaf/ingress-template","readOnly":true}},
  {"op":"add","path":"/spec/template/spec/volumes/-","value":{"name":"ingress-tpl","configMap":{"name":"ingress-template"}}},
  {"op":"add","path":"/spec/template/spec/containers/0/env/-","value":{"name":"INGRESS_TEMPLATE","value":"/etc/oaf/ingress-template/overlay.yaml"}}]'

# 3) 生效与回滚
kubectl -n agent-platform rollout restart deployment/platform-backend   # 新发布/republish 即带 overlay 形态
kubectl -n agent-platform set env deploy/platform-backend INGRESS_TEMPLATE-  # 回滚=去掉环境变量
```

行为要点：

- **不设置 `INGRESS_TEMPLATE` = 纯内置构造**，行为与未上此功能前完全一致。
- 占位符发布期按服务替换：`{{K8S_NAME}}`（backend 指向 `{{K8S_NAME}}-svc`）、`{{SHORT_NAME}}`（path 前缀）；**不得硬编码服务名/metadata.name**（启动探针即拒绝）。
- `annotations`/`labels` 按 key 合并（只写新增项即保留内置注解）；**`rules`/`tls` 写了即整体替换**——改 host/path 必须抄完整 rules 块。
- 校验不变量（**按 Ingress 模式收放**，模式由 `INGRESS_HOST_SUFFIX` 决定，见 §七之二）：backend 必须指向本服务；每条 path 必须设置 pathType；SSE 长超时注解必须保留；**path 模式**另需 path 保留 `(/|$)(.*)` 尾缀（rewrite 依赖）、`x-forwarded-prefix` 与对外前缀一致（改 path 同步改）。
- 服务详情展示的**访问地址（Endpoint）自动跟随合并结果**：path 模式取规则的 Host（空回落 `INGRESS_HOST`）、配 TLS 按 https 拼、端口取 `INGRESS_PORT`（Host 自带端口不重复拼）；host 模式取规则 Host 形如 `http://{K8sName}{后缀}/`（不拼端口）。改 host 后 DNS/端口可达性由环境自行保证；环境未在 `INGRESS_PORT` 终止 TLS 时不要配 TLS。
- 非法 overlay 启动即失败；发布/republish 期违规直接拒绝（Publish 不落库，Republish 保持原状）。存量服务需 republish 才滚动到新形态。

## 七之二、Ingress host 模式（INGRESS_HOST_SUFFIX，可选）

默认（不设置 `INGRESS_HOST_SUFFIX`）为 **path 模式**：所有服务共享 ingress 入口 IP，靠 `/agent/{short}` 路径前缀 + rewrite 区分。独立测试集群/生产环境要求每服务独立域名时，设置域名后缀切到 **host 模式**：`host = {K8sName}{后缀}`、`path = /`、pathType `Prefix`，无需 rewrite 注解。

> ⚠️ **切换前置检查**：若已配置 `INGRESS_TEMPLATE`（§七），其内容须**先**改成 host 模式样例（`backend/templates/ingress-overlay.example.yaml` 的 H1/H2/H3）再切模式。启动探针与发布期同模式（探针带同一个 `INGRESS_HOST_SUFFIX`），path 形态的 overlay 在 host 模式下会被探针直接判违规 → **platform-backend 启动即失败、CrashLoop、整个平台 API/UI 不可用**（不是"某个服务发布被拒"）。

```bash
# 1) 切换到 host 模式（后缀必须以 "." 开头；非法格式启动即失败）
#    ⚠️ 若已配 INGRESS_TEMPLATE，须先把 overlay 内容改成 H1/H2/H3 形态，否则下方
#       rollout restart 会让 backend CrashLoop
kubectl -n agent-platform set env deploy/platform-backend \
  INGRESS_HOST_SUFFIX=.region-c86-test.test-kzx1.cncb

# 2) 生效：滚动重启（存量服务经 republish / 重新上线后收敛到新形态）
kubectl -n agent-platform rollout restart deployment/platform-backend

# 回滚=去掉环境变量，回到 path 模式
kubectl -n agent-platform set env deploy/platform-backend INGRESS_HOST_SUFFIX-
kubectl -n agent-platform rollout restart deployment/platform-backend
```

行为要点：

- **不设置 = path 模式**，行为与未上此功能前完全一致（默认向后兼容）。
- 后缀格式启动即校验（fail-fast，backend CrashLoop 日志指明违例项）：必须以 `.` 开头、去点后为合法 DNS-1123 subdomain、**各段 ≤63 字符**、叠加最长 K8sName（67 字符）后总长 ≤253（即后缀 ≤186 字符）。
- 需 `INGRESS_CLASS` 有值（默认 `nginx`）；域名后缀须在集群 DNS/证书侧可解析——平台只生成 Ingress 对象，不代管 DNS 与证书。
- 与 `INGRESS_TEMPLATE` 可叠加（§七）：host 模式下 overlay 仍可追加注解、TLS，但 **host 不可偏离** `{K8sName}{后缀}`、**path 恒为 `/`**、backend 仍须指向本服务，且 **rewrite-target / use-regex / x-forwarded-prefix 三项必须为空**。**照抄 path 模式模板（含 x-forwarded-prefix）会导致启动即失败**：探针与发布期同模式，backend CrashLoop、日志指明违例项（该注解非空时业务 Agent 的 `/debug` 还会 302 到 `{该值}/debug/` 而 404，只是根本走不到那一步）。示例文件的 P1-P3 / H1-H3 两组样例不可混抄。
- 展示地址（Endpoint）随之切换为 `http://{K8sName}{后缀}/`（配 TLS 则 https）。**存量服务不做后台批量迁移**：模式切换后经 republish / 重新上线（StartAgain）时 Ingress 覆盖为新形态、Endpoint 同步重算落库。
