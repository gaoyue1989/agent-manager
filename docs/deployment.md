# OAF 服务发布平台 — 部署指南（v2）

> 历史设计归档：[design/](design/)（v2 重构设计见 [design/REDESIGN.md](design/REDESIGN.md)） ｜ 模块指引：各目录 AGENTS.md

## 前置条件

- Kind 单节点集群（集群名 `agent-manager`；集群配置原文内嵌于 [design/01-k8s-deployment.md](design/01-k8s-deployment.md) §3，仓库内无独立 kind-config.yaml），ingress-nginx 已装（NodePort 30080）
- 宿主机已装 Docker、kubectl、Go 1.26+（与 backend/go.mod 一致）、Maven+JDK21
- GOPROXY 走 `https://goproxy.cn,direct`

## 一、构建镜像

> tag 必须与 `manifests/*.yaml` 中 `image:` 字段一致，否则 apply 后 Pod 拉不到镜像（ImagePullBackOff）。
> 当前值：`platform-backend:v4`（platform.yaml:270）、`172.20.0.1:5001/platform-frontend:v7`（frontend.yaml:17）、
> `172.20.0.1:5001/agent-framework:latest`（platform.yaml:278-279 的 AVAILABLE_IMAGES/DEFAULT_IMAGE）。

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

# platform-frontend 现为 IfNotPresent（manifests/frontend.yaml:25）：ctr 导入/kind load 导入
# 节点后 rollout restart 即换代；IfNotPresent 不比 registry digest，仅 push 到 172.20.0.1:5001
# 而未导入节点时换代静默失效（Pod 仍复用旧镜像）——push 只是可选路径之一，导入节点才是必要动作
docker push 172.20.0.1:5001/platform-frontend:v7

# 导出功能基底镜像预置（EXPORT_BASE_IMAGE，issue #218）：导出单异步构建以 busybox 为
# base 直推镜像仓库，backend 缺省 busybox:1.36 会拉 Docker Hub——离线/内网环境必须预置到
# 本地 registry（platform.yaml 的 EXPORT_BASE_IMAGE 已固定指向 172.20.0.1:5001/busybox:1.36，
# 不预置则创建导出单后构建失败入 failed，报 pull base image i/o timeout）
docker pull busybox:1.36
docker tag busybox:1.36 172.20.0.1:5001/busybox:1.36
docker push 172.20.0.1:5001/busybox:1.36
```

## 三、部署平台

```bash
cd manifests
kubectl apply -f platform.yaml          # ns/RBAC/PVC/MySQL8/platform-backend（含 JWT_SECRET/ADMIN_INIT_*，生产先覆盖占位值）
kubectl apply -f platform-ingress.yaml # REST/MCP 入口
kubectl apply -f platform-router.yaml  # 业务子路径路由器（ROUTER_AUTH_MODE 默认 off）
kubectl apply -f frontend.yaml         # 前端 NodePort 30881
kubectl -n agent-platform rollout status deployment --timeout=300s
```

> **控制台拆分（2026-10 起）**：frontend 升级后浏览器访问任意页面先跳 `/login`（首个 admin 由
> `ADMIN_INIT_USER/ADMIN_INIT_PASSWORD` 种子创建）。数据面网关鉴权分三档灰度
> （platform-router `ROUTER_AUTH_MODE=off|report|enforce`，默认 off 行为与历史一致；
> 转档 = `kubectl -n agent-platform set env deploy/platform-router ROUTER_AUTH_MODE=enforce`
> 后 rollout restart，回滚同路径改回 off；enforce 前须先在控制台签发 API Key 或以 JWT 调用）。
>
> **Helm 部署路径（charts/oaf-platform）等价配置**：`backend.jwtSecret`（登录签发密钥，**渲染期必填**，
> 经 Secret 注入；复用已有 Secret 时 keys 契约 `mysql-dsn/auth-token/jwt-secret/admin-init-password`）、
> `backend.adminInitUser/adminInitPassword`（首个 admin 种子，生产必须覆盖）、
> `routing.authMode`（off|report|enforce，默认 off）。完整 values 参考见
> [design/helm-offline-deploy-design.md](design/helm-offline-deploy-design.md) §4.1/§4.3；改这些值 = `helm upgrade`。
>
> **存量升级提示（router 模式）**：集群里若残留历史 per-service Ingress（旧的 path/host 模式
> `oaf-<name>` 资源），其 `/agent/<name>` 更具体路径会**绕过 platform-router**（鉴权与路由都不生效）。
> 迁移到 router 模式 / 转 enforce 前应删除这类遗留 Ingress（`kubectl -n agent-platform get ingress`
> 逐一核对：只应保留 `*-backend-api`、`*-backend-mcp`、`*-frontend`、`*-agent-router`）。

### 控制台拆分按功能 e2e（e2e/console-split/）

部署后一键回归（M0–M12 十三模块；M9=技能中心、M10=沙箱配置、M11=空间配置/跨集群导出、M12=沙箱管理面；enforce 三态需独立网关环境，不在本套件）：

```bash
BASE=http://localhost:30080/api/v1 FRONTEND_BASE=http://localhost:8911 bash e2e/console-split/run-all.sh   # 全跑；或传模块号 m0 m3 ... 选择性跑
```

可选在命令前追加合法赋值前缀（不带方括号）`LLM_API_KEY=.. LLM_BASE_URL=.. LLM_MODEL=..` 显式指定模型凭据；缺省自动从 release-agent 部署的 env Secret/ConfigMap 读取（e2e/console-split/lib.sh `resolve_llm_env`），不可得时 M3/M5 跳过。`BASE`/`FRONTEND_BASE` 不传时脚本内缺省值与上文相同。

实装截图画廊：[docs/screenshots/console-split/README.md](screenshots/console-split/README.md)。

## 四、自举发布助手

浏览器打开前端 → 上传 `release-agent/` 打包的 zip（或直接调 API）→ 发布：
镜像选 `172.20.0.1:5001/agent-framework:latest`（platform.yaml 的 DEFAULT_IMAGE/AVAILABLE_IMAGES，
即 §一 产出的业务镜像），env 填 LLM_*、CHECKPOINT_JDBC_URL 与 AGENT_REDIS_URL（见下）。

> `AGENT_REDIS_URL=redis://oaf-redis.agent-platform.svc.cluster.local:6379`（oaf-redis 已随 platform.yaml 部署）。
> 缺省值指向 `127.0.0.1`（Pod 自身），session_event 事件不落 Redis、SSE 断线回放/续传全挂——部署必配。

> **DB schema 迁移（Flyway，2026-09-28 起）**：agent-framework 启动时自动执行
> `db/migration` 版本化迁移——存量库首次启动按 flyway_schema_history 自动基线、仅跑增量、
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

## 五之二、OpenSandbox 部署与池初始化（沙箱池化，可选）

业务 Agent 的 OpenSandbox 沙箱池化（docs/design/sandbox-pool-opensandbox-design.md）依赖集群内 OpenSandbox
release-1.1.0（base CRD/RBAC + controller + ingress-gateway + server；workload ns `opensandbox`）。
幂等部署脚本一步到位（kind；可任意重跑，实测完整重跑 exit=0，不扰动已建池的 warm Pod）：

```bash
./scripts/deploy-opensandbox.sh
# 产出: ns opensandbox-system 四个 release + ns opensandbox（workload）
#       集群内地址 http://opensandbox-server.opensandbox-system.svc:80
#       本机自检 http://127.0.0.1:18600/health（port-forward 保持后台；端口冲突换 PF_PORT 重跑）
```

前置：docker/kind/kubectl 已装（helm 未装时脚本会经 127.0.0.1:7890 代理自动安装）；
`.env.secrets` 含 `OPENSANDBOX_API_KEY`（脚本读入 shell 变量注入 secret，不回显不落盘）。

### 1. 平台接入（运维侧 /settings）

| 键 | 值 |
|----|----|
| `OPENSANDBOX_SERVER_URL` | `opensandbox-server.opensandbox-system.svc:80`（backend 代理的事实源，改默认配置不影响已发布服务） |
| `OPENSANDBOX_API_KEY` | 同 `.env.secrets` 的 `OPENSANDBOX_API_KEY` |
| `SANDBOX_IMAGE` | 池主容器镜像缺省（创建池「镜像」留空时使用），如 `opensandbox/code-interpreter:v1.1.0` |
| `SANDBOX_TIMEOUT_MINUTES` | 生命周期 TTL（新默认 30，随 turn 续期刷新） |
| `SANDBOX_POOL_REF` / `SANDBOX_USER_QUOTA` / `SANDBOX_RENEW_INTERVAL_SECONDS` | 发布表单预填默认（空 / 5 / 60） |

错误对照（沙箱管理页 / admin API）：**503** = URL/APIKey 未配置（先来 /settings）；
**502** = API Key 校验失败（/settings 修正）**或上游不可达/超时**（提示语带 "opensandbox 不可达"
——核对 `OPENSANDBOX_SERVER_URL` 与 opensandbox server 存活：server 未部署 / port-forward 断开
等联调常见故障同样返回 502）；**503 + "kubernetes runtime"** = 上游 runtime 非
kubernetes（脚本部署形态不会出现）；**429/5xx** = 上游原样透传。

### 2. 创建首个池

页面路径：运维侧 → **沙箱管理**（/admin/sandboxes）→ 创建池。Pod 模板由平台预设（官方 sample
形状：initContainers 装 execd/task-executor + 主容器跑 task-executor 常驻，镜像参数只替换主容器），
只需填池名与容量四值（校验链 `0 ≤ poolMin ≤ bufferMin ≤ bufferMax ≤ poolMax`）。

curl 等价（走平台管理代理，admin JWT 或 AUTH_TOKEN）：

```bash
TOKEN=<admin JWT>
curl -X POST http://localhost:30080/api/v1/admin/sandboxes/pools \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"oaf-pool-v1","capacitySpec":{"bufferMin":1,"bufferMax":2,"poolMin":1,"poolMax":3}}'
# 轮询预热就绪（kind V1 实测：创建后首轮询 status.available ≥ 1，warm Pod Running/Ready）：
curl -H "Authorization: Bearer $TOKEN" http://localhost:30080/api/v1/admin/sandboxes/pools/oaf-pool-v1
```

直连上游等价路径（绕过平台代理）：`POST http://127.0.0.1:18600/v1/pools`，header
`OPEN-SANDBOX-API-KEY`，模板逐字段取上游 sample `kubernetes/config/samples/sandbox_v1alpha1_pool.yaml`
（release-1.1.0 源码）。日常治理（水位调整/删除池/销毁沙箱）在沙箱管理页完成，均记入审计日志。

### 3. 业务服务接入（env 示例）

发布向导 / 编辑 env 表单填入（/settings 已配则自动预填）：

```dotenv
SANDBOX_ENABLED=true
SANDBOX_POOL_REF=oaf-pool-v1          # 空 = 不用池（配额与 TTL 行为不受影响，兼容未部署池的集群）
SANDBOX_USER_QUOTA=5                  # 单用户沙箱配额（0 = 不限）
SANDBOX_TIMEOUT_MINUTES=30            # 生命周期 TTL（随 turn 续期刷新）
SANDBOX_RENEW_INTERVAL_SECONDS=60     # 在途续期间隔
```

注意事项：

1. **语义升级公告**：显式固化 `SANDBOX_TIMEOUT_MINUTES=60` 的存量服务，从「创建时一次性 60min 超时」
   变为「60min TTL 随 turn 持续刷新」（活跃沙箱最长存活不再有界）；保持旧行为设 `SANDBOX_LIFECYCLE_ENABLED=false`。
2. **池耗尽**：poolMax 占满后 create 报 429（PoolCapacityExhausted），不做静默降级直建——在沙箱管理页
   「调整容量」扩 buffer/pool 后自动恢复。
3. **kind 联调容量偏紧**（宿主 15G 内存平台栈已占约 10G）：`bufferMin ≤ 1~2`，勿按生产水位配池；
   联调前可 `docker image prune` 清理。
4. 池/沙箱的 TTL 与 GC 真相在 opensandbox 服务端（expiresAt / ttl_expiry），平台侧仅代理转发与簿记；
   backend 重启不影响任何沙箱与池。

## 关键端点

对外入口：

| 入口 | 地址 |
|------|------|
| 统一入口（宿主机 nginx） | http://100.66.1.5:8911 |
| 前端直连 | http://172.20.0.3:30881 |
| REST API | http://localhost:30080/api/v1 |
| MCP | http://localhost:30080/mcp |
| 发布助手 Debug 页 | http://172.20.0.3:30080/agent/release-agent/debug |

集群内组件：

| 组件 | 地址 | 用途 |
|------|------|------|
| platform-backend | svc:8080 / NodePort 30880 | REST + MCP |
| platform-frontend | NodePort 30881 | Web UI |
| ingress-nginx | NodePort 30080 | 业务 Agent 入口（router 模式：共享 Ingress /agent → platform-router；host 模式：per-service 域名） |
| platform-router | svc:80（nginx 1.27.1 ×2） | router 模式子路径路由器（`/agent/{short}` 前缀剥离 + X-Forwarded-Prefix 注入，静态泛化配置、发布链路零写入；manifests/platform-router.yaml） |
| oaf-mysql | svc:3306 | 平台元数据 + checkpoint |
| oaf-redis | svc:6379 | session_event 事件流存储（Redis Streams，支撑 durable SSE 多副本续传） |
| 共享 PVC | platform-data (10Gi) | OAF 包存储：`packages/{id}` subPath 只读挂 /config |
| namespace | agent-platform | 全部平台与业务资源 |

## 凭据与敏感配置

| 项 | 值 |
|----|----|
| MySQL（oaf-mysql） | 用户 `oaf` / 密码 `OafPlatform2026`（root 同） |
| LLM | `.env.secrets`（mimo-v2.5） |
| JWT_SECRET（控制台登录签发密钥，2026-10 起） | platform.yaml 默认占位 `oaf-console-jwt-secret-CHANGE-ME`——**生产必须覆盖**（缺失启动 fail-fast；更换即全员重新登录） |
| ADMIN_INIT_USER / ADMIN_INIT_PASSWORD（首个 admin 种子） | 默认 `admin` / `OafConsole2026`——仅 users 表为空时生效；生产环境首登后立即改密（运维侧 → 用户管理 → 重置密码） |

Checkpoint DSN 模板（新发布服务由平台自动派生 per-service 库名，见「运维备忘 4」）：
```
jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
```

评测飞轮外链（可选，前端构建时注入；未配置则控制台隐藏入口）：
```
# frontend 构建参数：NEXT_PUBLIC_EVAL_STUDIO_URL=http://{部署主机}:18400
```

## 运维备忘

1. **更新任一镜像后**：重新 save/import 后必须 `kubectl -n agent-platform rollout restart deployment/<name>`——同名 tag 不会自动触发滚动，且 Ingress 注解由 platform-backend 下发，改注解逻辑后必须重启它再 republish。
2. 业务 Pod 的 OAF 包挂载在 `/config`（只读）+ 工作区 `/workspace`（可写）+ 日志目录 `/applog`（可写，规范日志输出，见「五、业务日志规范」）。
3. MCP server 不可达默认不阻断启动（fail-soft）；必需依赖在包内写 `startup.required: true`。
4. **发布多个业务服务时的数据隔离（issue #142 起新发布自动派生）**：Publish 新建服务时自动派生独立 checkpoint 库（`CHECKPOINT_JDBC_URL` 改写为 `{原库}_{k8s 名}` + `createDatabaseIfNotExist=true` 首连自动建库）与 Redis key 前缀（`AGENT_REDIS_PREFIX={k8s 名}:`，仅平台已配置共享 Redis 时）——用户显式配置的值优先于派生；**存量服务不受影响**（env 已固化），收敛需显式改 env 后 republish。**前置授权**：MySQL 账号须能建 `{原库}_%` 模式库——fresh 集群 init.sql 已含 `GRANT ALL ON \`oaf_checkpoint\_%\`.*`；存量集群手工执行一次：
   ```sql
   GRANT ALL ON `oaf_checkpoint\_%`.* TO 'oaf'@'%'; FLUSH PRIVILEGES;
   ```
   未派生的存量默认值（共享 `oaf_checkpoint` 库 + 空 Redis 前缀）多服务沿用仍会互相串数据。
5. backend 重启/升级不影响已在跑的业务服务（数据面不经 backend）；唯一边界：发布等待期（`deploying` 状态）重启 backend 会丢后台注册推进，该服务会停在 `deploying`，用 Republish 解救，业务 Pod 不受影响。
6. **共享 PVC 名可配**：`PLATFORM_PVC_NAME`（默认 `platform-data`）决定业务 Pod 与 backend 共用的平台数据卷名——环境已有 PVC 用别的名字时设它（Helm 部署即 `persistence.existingClaim`）；变更后需 republish 才刷到业务 Deployment。

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

- **不设置 `INGRESS_TEMPLATE` = 纯内置构造**。**模板仅 host 模式可用**（`INGRESS_HOST_SUFFIX` 非空；router 模式无 per-service Ingress，suffix 空 + 模板非空启动即拒）。
- 占位符发布期按服务替换：`{{K8S_NAME}}`（backend 指向 `{{K8S_NAME}}-svc`）；**不得硬编码服务名/metadata.name**（启动探针即拒绝）。
- `annotations`/`labels` 按 key 合并（只写新增项即保留内置注解）；**`rules`/`tls` 写了即整体替换**——写 rules 必须抄完整块（host/path 均为不变量，见 §七之三）。
- 校验不变量（host 模式）：backend 必须指向本服务；每条 path 必须设置 pathType 且恒为 `/`；rule host 恒为 `{K8sName}{后缀}`；SSE 长超时注解必须保留；rewrite-target / use-regex / x-forwarded-prefix 三项必须为空。
- 服务详情展示的**访问地址（Endpoint）自动跟随合并结果**：host 模式取规则 Host 形如 `http(s)://{K8sName}{后缀}/`（不拼端口）。改 host 后 DNS/端口可达性由环境自行保证；环境未终止 TLS 时不要配 TLS。
- 非法 overlay 启动即失败；发布/republish 期违规直接拒绝（Publish 不落库，Republish 保持原状）。存量服务需 republish 才滚动到新形态。

## 七之二、业务路由双模式（INGRESS_HOST_SUFFIX：router / host）

`INGRESS_HOST_SUFFIX` 是业务路由模式的**唯一开关**（subpath-routing-design v2 起，历史 ingress-nginx 注解 rewrite 的 path 模式已删除）：

| `INGRESS_HOST_SUFFIX` | 模式 | 形态 |
|---|---|---|
| 空（默认） | **router** | 不构造 per-service Ingress；统一域名 `/agent/{short}` 子路径由集群内 **platform-router** nginx 承接（§七之三部署）；Endpoint = `http://{INGRESS_HOST}:{INGRESS_PORT}/agent/{short}/` |
| 非空 | **host** | per-service Ingress：`host={K8sName}{后缀}`、`path=/`、pathType Prefix，只保留 ssl-redirect 与 proxy 超时注解 |

> ⚠️ **router 模式启动自检**：suffix 空时 backend 启动即检查 `platform-router-svc` 存在，缺失则 CrashLoop（报错指向 §七之三的 manifest）——先 apply platform-router 再升级 backend。**suffix 空 + `INGRESS_TEMPLATE` 非空同样启动即拒**（router 无 per-service Ingress，模板无处生效）。

host 模式切换（后缀必须以 `.` 开头、各段 ≤63、叠加最长 K8sName 后总长 ≤253）：

```bash
# ⚠️ 若已配 INGRESS_TEMPLATE，须先把 overlay 内容改成 H1/H2/H3 形态（host 模式样例）
kubectl -n agent-platform set env deploy/platform-backend \
  INGRESS_HOST_SUFFIX=.region-c86-test.test-kzx1.cncb
kubectl -n agent-platform rollout restart deployment/platform-backend
# 回滚：清空后缀并重启；router 模式恢复需 platform-router 已部署（§七之三）
kubectl -n agent-platform set env deploy/platform-backend INGRESS_HOST_SUFFIX-
```

行为要点：

- host 模式的 `INGRESS_CLASS` 留空 = 业务 Ingress 不写 `ingressClassName`，落到集群默认 IngressClass（default-class 标注）；显式给值（默认 `nginx`）则固定。域名后缀须在集群 DNS/证书侧可解析——平台只生成 Ingress 对象，不代管 DNS 与证书。
- **存量服务不做后台批量迁移**：模式切换后经 republish / 重新上线（StartAgain）收敛——host 模式刷 Ingress 形态；router 模式对同名 Ingress「存在即删除」（清理 path 时代残留）——Endpoint 均同步重算落库。残留兜底：`kubectl -n agent-platform delete ingress -l app.kubernetes.io/managed-by=oaf-platform`（排除 `platform-agent-router` 自身）。
- 展示地址（Endpoint）随模式切换：router = `http://{INGRESS_HOST}:{INGRESS_PORT}/agent/{short}/`（与历史 path 模式同形状，前端零改动）；host = `http(s)://{K8sName}{后缀}/`。

## 七之三、platform-router 部署（router 模式，默认）

router 模式的集群内子路径路由器（静态泛化 nginx：正则提服务名 + kube-dns 动态解析 + 原生 rewrite 剥前缀 + `X-Forwarded-Prefix` 注入；配置静态，发布链路零写入、无需 reload）：

```bash
# kind 环境先导入镜像
docker pull nginx:1.27.1-alpine && kind load docker-image nginx:1.27.1-alpine --name <集群名>

# 部署 router（ConfigMap + Deployment×2 + Service + 共享 Ingress /agent）
kubectl apply -f manifests/platform-router.yaml
kubectl -n agent-platform rollout status deployment/platform-router --timeout=120s

# 升级窗口（存量 path 模式集群，subpath-routing §4.6）：
# 1) apply 本 manifest（router 就绪；共享 Ingress 与存量业务 Ingress 的 /agent/* 路径
#    重叠期内两条路由终点等价——都到 svc:8100 根路径，无流量错误，仅尽快收敛）
# 2) 更新 platform-backend 镜像 + rollout restart（router 模式激活，启动自检通过）
# 3) 逐服务 republish / startagain：存量 Ingress 被清理、Endpoint 收敛
# 4) 残留兜底：按平台归属 label 批量删业务 Ingress（排除 platform-agent-router）
```

运维注意：

- **已知限制（非 nginx controller 集群）**：共享 Ingress 上的 timeout/ssl-redirect 注解会失效（回落 controller 默认值）——SSE 由 agent-framework 20s 心跳保活（小于常见 60s 读超时）；**长时间无数据的 A2A blocking 请求可能被 controller 默认超时截断**，此类集群需在 controller 侧调大默认超时。
- **命名不变量**：nginx.conf 固化 `oaf-` 前缀、`-svc` 后缀、端口 8100、namespace `agent-platform`——backend 命名规则变更须同步 manifest。
- **路径段为短名**：`/agent/{short}` 的 short 是去 `oaf-` 前缀的短名（与服务详情 Endpoint 落库值一致，如 `oaf-order-agent` → `/agent/order-agent/`）；误用 K8sName（`/agent/oaf-order-agent/`）会解析到不存在的 `oaf-oaf-order-agent-svc` → 502（未知服务语义，非缺陷）。
- router 滚动升级会断其上的 SSE 长连接（`maxUnavailable: 0` 只保新建连接），按运维窗口操作。
- 未知/已删服务返回 502（DNS 解析失败 + 10s 缓存窗口），与历史 ingress 404 语义不同。
