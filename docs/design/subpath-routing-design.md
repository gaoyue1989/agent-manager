# 统一域名子路径访问设计：agent-framework 前缀自感知（方案 A）+ 双模式路由（host 域名 / platform-router）

> 状态：**已实施（2026-10-10，实施记录与验证证据见 §10；§9.5 手工清单待 kind 真机勾选）**。
> 修订史：v2 按评审决议**删除 ingress-nginx 注解 rewrite 的 path 模式**，终态仅保留
> host 域名与 platform-router（nginx 原生 rewrite）两种模式；v3 记录 §8 评审决议并新增
> **§9 E2E 验证标准——任务完成的硬性判据**；v4 按评审补充 host 模式 e2e 验证（§9.4）；
> 破坏性升级窗口见 §4.6。
> 关联：[ingress-template-design.md](ingress-template-design.md)、[ingress-host-mode-design.md](ingress-host-mode-design.md)
>（ingress-host-mode 的 path 模式分支本期删除，host 分支全部保留）。

## 1. 背景与决策

平台原有两种业务路由形态：path 模式（默认，`rewrite-target=/$2` 等 ingress-nginx 私有
注解剥前缀）与 host 模式（`INGRESS_HOST_SUFFIX` 非空，独立域名 + 根路径）。

平台决策：**后续不再依赖 ingress controller 私有注解**（部分集群 controller 不支持，
且注解能力随 controller 版本漂移），删除 path 模式，终态只保留两种模式：

1. **host 域名模式**：`INGRESS_HOST_SUFFIX` 非空（现状代码保留，行为不变）；
2. **router 模式**：`INGRESS_HOST_SUFFIX` 空（接管原 path 模式的「统一域名 + 子路径」
   槽位），由集群内 **platform-router nginx** 以 nginx 原生 `rewrite` 承接前缀剥离，
   对 ingress controller 无任何注解依赖。

配套的**方案 A**（agent-framework 基于 `X-Forwarded-Prefix` 的对外 URL 自感知）为
router 模式补齐「服务端下发的 URL 带正确前缀」；host 模式无前缀，A 的前缀分支天然
不触发。

## 2. 总体架构与流量路径（终态两形态）

```
host 模式（INGRESS_HOST_SUFFIX 非空，现状不变）：
  client ──► per-service Ingress（host={K8sName}{后缀}，path=/，Prefix）
             ──► oaf-{short}-svc:8100        ← 无前缀，无 X-Forwarded-Prefix

router 模式（INGRESS_HOST_SUFFIX 空，接管原 path 模式槽位）：
  client ── 统一域名 ──► 共享 Ingress（manifests 自举，path=/agent，Prefix，零 rewrite 注解）
                         ──► platform-router nginx ×2 ──► oaf-{short}-svc:8100
                              （nginx 原生 rewrite 剥前缀 + proxy_set_header X-Forwarded-Prefix）

两模式的注册/健康探测/就绪探针都走集群内 svc 直连（根路径），不经入口层。
path 模式（ingress-nginx 注解 rewrite）：删除，backend 不再生成任何 rewrite 注解；
存量对象收敛见 §4.6。
```

router 模式下 agent-framework 收到的请求与原 path 模式**逐字节同构**（前缀已剥 +
`X-Forwarded-Prefix` 头存在），因此方案 A 的代码、原 path 模式下已正确的消费方
（Debug Console 302、前端 `AGENT_BASE`）在 router 模式下行为不变。

## 3. 方案 A：agent-framework 前缀自感知

### 3.1 对外 URL 契约（核心不变量）

| 下发点 | 规则 |
|---|---|
| agent-card `url`（REST 卡） | 请求含 `X-Forwarded-Prefix`（router 模式经 platform-router 设定）时填 `{scheme}://{host}{prefix}/`（带尾斜杠）；host 模式与集群内直连无此头 → 保持空串 |
| SSE `file_ready.download_url` | 有前缀时下发 `{prefix}/files/{fileId}`；无前缀维持 `/files/{fileId}` |
| `GET /` 与 `/metadata` 的 `endpoints` | 有前缀时逐项加前缀；另新增 additive 字段 `base_url`（有前缀时为 `{scheme}://{host}{prefix}`，无前缀时**整个键省略**，保证响应体逐字节不变） |
| DebugController `/debug` 302 | 已有实现（读 `X-Forwarded-Prefix` 拼 Location），不动；router 模式该头由 nginx 配置设定，行为不变 |

scheme/host 推导：`X-Forwarded-Proto` → 回落 `request.getScheme()`；`X-Forwarded-Host` →
回落 `Host` 头。不启用 `server.forward-headers-strategy`（Spring 不认识
`X-Forwarded-Prefix`，启用只会引入全局隐式行为，不如显式手读可控——与 DebugController
现有模式一致）。

### 3.2 新增工具类

`io.agentmanager.framework.util.ExternalUrlSupport`（静态工具，util 包已存在）：

```java
/** 规范化外部前缀（"/agent/foo"）；无 X-Forwarded-Prefix 头返回 null */
public static String forwardedPrefix(HttpServletRequest req);

/** 外部基址 "scheme://host/agent/foo"（无尾斜杠）；无前缀头返回 null */
public static String externalBase(HttpServletRequest req);
```

实现纪律：header 值 trim、去重复斜杠、剥尾斜杠；多值（逗号分隔）取第一个。
返回值仅用于**响应体内 URL 字符串拼接**，不参与路由、鉴权、过滤逻辑——经 router
转发时该头由 `proxy_set_header` 覆盖设定，客户端无法注入；伪造面仅存在于直连场景
（只影响伪造者自己收到的链接指向，无越权）。

### 3.3 改动点明细（agent-framework，3 个 Java 点 + 1 处内置 UI）

| 文件:位置 | 改动 |
|---|---|
| [AgentCardController.java:57](../../agent-framework/src/main/java/io/agentmanager/framework/controller/AgentCardController.java) | `agentCard()` 增加 `HttpServletRequest` 参数；`url` 按 §3.1 契约填充（`Map` 为 `LinkedHashMap`，仅前缀存在时覆盖空串默认值） |
| [ChatStreamController.java:767](../../agent-framework/src/main/java/io/agentmanager/framework/controller/ChatStreamController.java) | `download_url` 按 §3.1 契约。前缀在 `POST /threads/chat` 请求入口读一次，经闭包/参数传入 `emitFileReadyViaEventBus`——**禁止用 `RequestContextHolder`**（file_ready 在异步事件回调线程合成，请求线程局部不可靠）。`/threads/{sid}/history` 的 `files` 数组只含 `file_id` 不带 URL，不受影响 |
| [InfoController.java:104-130, 135-176](../../agent-framework/src/main/java/io/agentmanager/framework/controller/InfoController.java) | `root()` 与 `getMetadata()` 增加 `HttpServletRequest` 参数；endpoints 字典抽 helper 按前缀包装；`root()` 的 `Map.of` 改 `LinkedHashMap` 以支持条件性 `base_url`（无前缀时省略键） |
| [static/debug/modules/chat.js:1372](../../agent-framework/src/main/resources/static/debug/modules/chat.js) | `const raw = data.download_url \|\| ('/files/' + fileId); const downloadUrl = raw.startsWith(ctx.api.BASE) ? raw : ctx.api.BASE + raw;`（见 §3.4） |

**明确不改**：`A2AServerConfig` SDK 注册卡 url（集群内语义，`RemoteConfirmBridge` 消费）；
`A2aJobService` 自环地址；`RequestLoggingFilter`/`HttpTracingFilter` 等 URI 匹配（两模式下
app 均只见根路径，现状正确）；`application.yml` 任何 server.* 配置。

### 3.4 兼容性矩阵（逐消费方验证）

`download_url` 语义从「服务根绝对路径」升级为「从域名根解析的完整路径（含外部前缀）」：

| 消费方 | 行为 | 结论 |
|---|---|---|
| 前端 assistant 页 [MessageItem.tsx:126](../../frontend/src/app/assistant/components/MessageItem.tsx) | `startsWith("/files") ? AGENT_BASE + url : url`：旧值走前缀分支，新值 `/agent/release-agent/files/x` 不匹配走 else 直接用 | **双向兼容，零改动** |
| debug UI chat.js | 见 §3.3 改法：旧服务端值 `/files/x` 不以 BASE 开头 → 补前缀（旧行为）；新值以 BASE 开头 → 直接用；直连时 BASE=`''` 等价直用 | 兼容 |
| backend 注册链路 | `fetchCard` 经 ClusterURL 直连（无代理头）→ card `url` 仍空串，`agent_card_json` 落库内容不变 | 零影响 |
| 存量 e2e | e2e nginx LB 根代理不设 `X-Forwarded-Prefix` → 所有断言（含 download_url）不变 | 零回归 |
| 多副本 / e2e-multi | 前缀是请求期属性，不落共享存储 | 无跨副本一致性问题 |
| A2A SDK 客户端 | card `url` 从空串变为可达地址（router 模式经公网取卡时） | 纯增强 |

### 3.5 测试（方案 A）

- **单测**：`ExternalUrlSupportTest`（有/无头、多值、尾斜杠、空白）；`AgentCardControllerTest`
  增前缀维度；`InfoControllerTest`（endpoints 前缀 + `base_url` 存在/省略）；ChatStream
  file_ready 前缀维度（走现有流式测试基建）。
- **e2e**：现有四 job 零回归之外新增用例——e2e nginx LB 透传客户端任意头，因此 e2e
  直接在 chat 请求/取卡请求上加 `X-Forwarded-Prefix: /agent/{short}` 头，断言
  `download_url` 与 card `url` 带前缀（不改 LB 配置，不影响其他用例）。
- **手工**：kind 上 router 模式全链路 curl 验证卡片与 SSE 帧。

## 4. 双模式路由：host 保留 + router 新增 + path 删除

### 4.1 模式判定（单一配置来源，不新增开关）

| `INGRESS_HOST_SUFFIX` | 模式 | 说明 |
|---|---|---|
| 空（默认） | **router**（新） | 接管原 path 模式槽位；不引入 `INGRESS_ROUTER_ENABLED` 之类的第二开关，避免双开关一致性校验 |
| 非空 | **host**（现状） | 全部现有校验/模板机制保留 |

fail-fast（对齐 `validateIngressHostSuffix` 风格）：

- suffix 空（router 模式）且 `INGRESS_TEMPLATE` 非空 → 启动失败（模板仅对 host 模式
  有意义；原 path 模式模板从此非法）；
- router 模式启动自检 `platform-router-svc` 存在（k8s client 查 Service 对象，非就绪），
  缺失 → 启动失败并提示先 `kubectl apply -f manifests/platform-router.yaml`。

### 4.2 router nginx 配置（ConfigMap 全文）

```nginx
worker_processes auto;
events { worker_connections 10240; }
http {
  access_log /dev/stdout;
  # 变量 proxy_pass 逐请求解析必须显式指定 resolver；10s 缓存意味着服务删除后至多 10s 内残留转发（502）
  resolver kube-dns.kube-system.svc.cluster.local valid=10s ipv6=off;

  # 外层 controller 已声明 X-Forwarded-* 时透传，否则按本跳观察值兜底（保住 TLS 卸载场景的 https）
  map $http_x_forwarded_proto $fwd_proto { default $http_x_forwarded_proto; "" $scheme; }
  map $http_x_forwarded_host  $fwd_host  { default $http_x_forwarded_host;  ""  $host; }

  server {
    listen 8080;
    # 与文件上传（POST /files/upload）对齐：入口层不设更严于现状的体量限制，上限由应用层裁决
    client_max_body_size 200m;

    # [a-z0-9-] 限定字符集：$short 只出现在域名 label 位置，杜绝路径注入 upstream；
    # nginx 在 location 匹配前已做 URI 归一化（合并斜杠、消解 ../）
    location ~ ^/agent/(?<short>[a-z0-9-]+) {
      rewrite ^/agent/[a-z0-9-]+/?(.*)$ /$1 break;    # 剥前缀：nginx 原生能力，替代 ingress 注解
      proxy_pass http://oaf-$short-svc.agent-platform.svc.cluster.local:8100;
      proxy_set_header Host              $host;
      proxy_set_header X-Forwarded-Proto $fwd_proto;
      proxy_set_header X-Forwarded-Host  $fwd_host;
      proxy_set_header X-Forwarded-Prefix /agent/$short;   # 方案 A 的输入契约
      proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
      proxy_http_version 1.1;
      proxy_set_header Connection "";         # SSE 长连接
      proxy_buffering off;                    # SSE 不缓冲
      proxy_request_buffering off;            # 上传直透
      proxy_read_timeout 3600s;               # 等价原 path 模式注解三件套
      proxy_send_timeout 3600s;
    }

    location = /healthz { access_log off; return 200 "ok"; }   # router 自身存活探针
    location / { return 404; }
  }
}
```

固化进这份配置的平台约定（backend 改命名规则时须同步）：`oaf-` 前缀、`-svc` 后缀、
业务端口 8100、namespace `agent-platform`。

### 4.3 manifests（`manifests/platform-router.yaml`，一次性自举，仅 router 模式集群 apply）

| 对象 | 要点 |
|---|---|
| ConfigMap `platform-router-conf` | §4.2 nginx.conf 原文 |
| Deployment `platform-router` | 镜像 `nginx:1.27.1-alpine`（版本钉死；kind 环境需 `docker pull && kind load` 导入）；**2 副本**；`RollingUpdate maxUnavailable: 0`；resources 适度（100m/128Mi 起）；readiness/liveness 探 `GET :8080/healthz`；配置静态，**无需任何 reload 机制** |
| Service `platform-router-svc` | ClusterIP 80 → 8080 |
| Ingress `platform-agent-router` | `ingressClassName` 取集群配置；**零 rewrite 注解**，`path: /agent`、`pathType: Prefix`、backend `platform-router-svc:80`；机会性保留 `proxy-read/send-timeout=3600` 与 `ssl-redirect=false`（ingress-nginx 集群生效、其他 controller 忽略后由 20s SSE 心跳兜底，见 §6） |

### 4.4 backend 改动

**router 模式行为分支：**

| 流程 | host 模式（现状） | router 模式 |
|---|---|---|
| Publish / Republish / StartAgain 的 apply | Build Ingress → overlay 合并 → `validateIngress` → EnsureIngress | **不 Build、不创建**；且对同名 Ingress 执行「**存在即删除**」（NotFound 容忍）——升级集群上共享 `/agent` Ingress 与存量 path 模式对象路径重叠，须收敛（§4.6） |
| Endpoint 落库（[template.go:407-442](../../backend/internal/k8s/template.go) `IngressEndpoint`） | 从合并后 Ingress 对象派生 | 纯配置拼装 `http://{INGRESS_HOST}:{INGRESS_PORT}/agent/{short}/`（复用现有 `INGRESS_HOST`/`INGRESS_PORT` 与回落语义；**URL 形状与原 path 模式输出一致**，前端/DB/MCP 契约零变化） |
| Unpublish / 删除 | 删 per-service Ingress | 照旧删除（NotFound 容忍），与 host 模式同码路 |
| `asyncWaitAndRegister` / `fetchCard` / 就绪与存活探针 | ClusterURL 直连 svc 根路径 | **不变** |
| `NewIngressBuilder` 启动探针 | 按模板加载（模板必空 → 无探针） | 同左；builder 装配保留，仅 host 模式使用 |

**path 模式删除清单（现有代码）：**

| 位置 | 删除内容 | 保留内容 |
|---|---|---|
| [objects.go](../../backend/internal/k8s/objects.go) | `Ingress()` path 分支；常量 `annRewriteTarget`/`annUseRegex`/`annXForwardedPrefix`/`rewriteTargetValue`/`ingressPathSuffix` | host 分支与 `annSSLRedirect`/`annProxyReadTimeout`/`annProxySendTimeout`、`pathTypePtr`/`ingressBackend` helper、`ShortName`（router Endpoint 拼前缀用） |
| [template.go](../../backend/internal/k8s/template.go) | `validateIngress` 的 path 模式不变量组（rewrite 三项强制、path 尾缀 `(/\|$)(.*)`、共用前缀、x-forwarded-prefix 一致性）；`IngressEndpoint` 的 path 分支与 `hostWithPort`（仅 path 分支使用） | host 模式不变量与 overlay 合并机制、`IngressEndpoint` host 分支 |
| [config.go](../../backend/config/config.go) | —（path 模式无专属配置） | 新增 §4.1 两条 fail-fast |
| `backend/templates/ingress-overlay.example.yaml` | P1-P3（path 模式样例组） | H1-H3（host 模式样例组） |

**测试改写：** `objects_test` 的 path 模式逐字符回归锁 → router 分支断言（不建 Ingress、
Endpoint 形状）；`template_test` 的 P 组用例删除，H 组保留；`publish_test` 的 path 模式
端到端 → router 模式端到端（fake 集群断言 Ingress 未创建且存量同名被删 + Endpoint 落库）；
`config_test` 增「suffix 空 + template 非空拒绝启动」用例。

### 4.5 模式矩阵（终态）

| 配置 | 模式 | per-service Ingress | 对外 URL 形态 |
|---|---|---|---|
| suffix 空（默认） | router | 不创建（存在即删） | `{INGRESS_HOST}:{INGRESS_PORT}/agent/{short}/` |
| suffix 非空 | host | 创建（现状） | `{scheme}://{K8sName}{suffix}/` |

### 4.6 升级与存量收敛（破坏性变更窗口）

对现役 path 模式集群（suffix 空 + 无 router），升级顺序：

1. `kubectl apply -f manifests/platform-router.yaml`（router 双副本就绪，共享 Ingress 生效）。
   共享 Ingress 与存量 per-service Ingress 存在 `/agent/*` 路径重叠窗口，但**两条路由终点
   等价**（legacy：注解 rewrite → svc:8100 根路径；shared：router rewrite → svc:8100 根
   路径），ingress-nginx 无论裁决到哪条，请求都正确到达——重叠只造成配置漂移风险，不造成
   流量错误，尽快收敛即可；
2. 更新 backend 镜像 + `rollout restart deployment/platform-backend`（router 模式激活，
   启动自检通过）；
3. 逐服务 Republish / StartAgain：存量同名 Ingress 被「存在即删除」清理，
   `refreshEndpoint` 收敛落库（值与原 path 模式相同，多数情况无实际变化）；
4. 残留兜底（未走过重发布的服务）：按平台归属 label 批量删除业务 Ingress（selector
   以 objects.go 实际 label 为准，排除 `platform-agent-router` 自身）。

配置迁移注意：原「suffix 空 + INGRESS_TEMPLATE=path 模板」的部署在新版启动即失败，
须清空模板（host 模式集群不受影响）。

## 5. 架构强约束核对

| 约束 | 核对 |
|---|---|
| 原则一：业务 K8s 资源只允许显式发布 API 写入 | router 配置静态、发布链路零写入共享配置；共享 Ingress 属 manifests 一次性自举；无 reconcile/后台任务 ✓ |
| 原则一：存量收敛不做后台批量迁移 | Ingress 清收敛只经显式 Republish/StartAgain/Unpublish 与运维手册兜底，无后台任务 ✓ |
| 原则一：业务运行时不依赖 backend 可用性 | router 不依赖 backend 进程；入口三链路（env envFrom / PVC 只读挂载 / 流量直达 svc）不变，仅 L7 跳从 ingress-nginx 注解 rewrite 换成共享 Ingress + router——同性质共享无状态组件，双副本控制故障域 ✓ |
| 原则二：业务面无状态可横向扩展 | 不涉及（方案 A 前缀是请求期属性，不落影响正确性的状态）✓ |

## 6. 风险与已知限制

1. **破坏性配置变更**：suffix 空的部署从 path 模式翻转为 router 模式，必须与
   `platform-router.yaml` 同窗口应用（§4.6 顺序），否则 backend 启动自检失败
   （CrashLoop，报错信息指向缺 manifest——fail-fast 语义与 INGRESS_TEMPLATE 探针一致）。
2. **router 滚动升级断 SSE 长连接**：`maxUnavailable: 0` 只保护新建连接，旧 pod 终止
   仍会断其上的长流（ingress-nginx 自身升级同理），按运维窗口操作。
3. **未知/已删服务的语义**：router 返回 502（DNS 解析失败 + `valid=10s` 缓存窗口），
   原 path 模式是 404。客户端按 5xx 处理；发布完成到 DNS 可解析的秒级窗口被
   `asyncWaitAndRegister`（等 Ready 才转 running）实际覆盖。
4. **非 nginx controller 不识别任何注解**：共享 Ingress 上的 timeout/ssl-redirect 注解
   失效 → 回落 controller 默认值。SSE 由 20s 心跳保活（小于常见 60s 读超时）；**长时间
   无数据的 A2A blocking 请求可能被 controller 默认超时截断**，此类集群需在 controller
   侧调大默认值（运维项，记入 deployment.md）。
5. **命名约定耦合**：`oaf-`/`-svc`/8100/namespace 固化在 nginx.conf；backend 命名规则
   变更需同步修改 router 配置（与 `AgentPort` 常量同级的共享不变量，写入两模块 AGENTS.md）。
6. **`client_max_body_size 200m`**：显式设定避免比现状（ingress controller 默认）更严；
   应用层上传限额仍是最终裁决。
7. **大小写**：`location ~` 大小写敏感，`/Agent/foo` 404，与原 path 模式行为一致。

## 7. 实施拆分与上线顺序

| PR | 范围 | 依赖 | 兼容性 |
|---|---|---|---|
| PR-1 | agent-framework 方案 A 全部（§3.3）+ 单测 + e2e 头注入用例 | 无 | 全向后兼容，可独立合入；在升级过渡期的存量 path 模式集群上同样生效（ingress-nginx 注解也设定该头） |
| PR-2 | backend：删除 path 模式（§4.4 删除清单）+ router 模式分支 + `manifests/platform-router.yaml` + backend 单测改写 | 无（与 PR-1 并行开发） | **破坏性**：suffix 空集群须按 §4.6 与部署联动 |
| PR-3 | e2e + 文档：根 e2e 中 path 模式断言改写为 router；router e2e job；根 AGENTS.md / backend/AGENTS.md / agent-framework/AGENTS.md / docs/deployment.md 同步 | PR-1 + PR-2 | — |

文档同步清单：根 AGENTS.md（基础设施表 + 关键约定去 path 化）、backend/AGENTS.md
（双模式、router 行为分支、命名不变量）、agent-framework/AGENTS.md（X-Forwarded-Prefix
契约与对外 URL 规则）、docs/deployment.md（router 部署/启用/§4.6 升级顺序/§6.4 运维项）、
docs/design/README.md 索引、ingress-template-design.md 与 ingress-host-mode-design.md
头部补「path 模式已由本设计删除」注记（正文不改动，保留历史）。

## 8. 评审决议（2026-10-10）

| # | 议题 | 决议 |
|---|---|---|
| 1 | 模式判定语义 | ✅ `INGRESS_HOST_SUFFIX` 空 = router 默认语义（接管原 path 槽位，不新增第二开关） |
| 2 | `platform-router-svc` 启动自检缺失即 fail-fast | 未明确表态，**按 fail-fast 默认实施**（与 INGRESS_TEMPLATE 探针同语义，CrashLoop 报错指向缺 manifest）；有异议须在实施前提出版本 |
| 3 | 存量 Ingress 收敛口径 | ✅ Republish/StartAgain「存在即删除」+ 平台 label 批量删手册兜底 |
| 4 | 镜像与体量 | ✅ nginx 镜像钉死 `nginx:1.27.1-alpine`；`client_max_body_size 200m` |
| 5 | e2e 落位 | ✅ 头注入用例进**核心 job** 新增用例组（§9.2 X 组）；新增独立 job「**E2E 路由模式**」含 **router 相位（§9.3 R 组）与 host 相位（§9.4 H 组，显式验证 host 模式不受影响并补齐历史真机回归缺口）**，验收期必须在 PR run 绿，转分支保护必需检查为稳定后的管理员工动作；**任务完成以 §9 验证标准全过为准** |

## 9. E2E 验证标准（任务完成的硬性判据）

**总则**：完成 = PR-1/2/3 全部合并，且 §9.1–§9.4 自动化全绿 + §9.5 手工清单勾完
（§9.6 DoD 汇总）。任何一条未过即任务未完成，修复后重跑对应组。

### 9.1 既有门禁零回归（每个 PR 的 CI 必须）

| 项 | 通过标准 |
|---|---|
| agent-framework 单测 | `mvn test` 全绿（含新增 `ExternalUrlSupport` / AgentCard / Info / ChatStream 前缀用例） |
| backend 单测 | `go vet ./...` + `go test ./...` 全绿（router fake 端到端绿；path 模式用例删除后无残留引用与失败） |
| 四个必需 e2e job | 核心（API+UI）/ 多副本 / 沙箱 / 协议多副本全绿（无前缀 LB 环境） |
| 行为不变量 | 无 `X-Forwarded-Prefix` 时：`download_url == /files/{id}`、card `url == ""`、`GET /` 与 `/metadata` **无 `base_url` 键**、endpoints 为根路径（与变更前逐字节一致） |

### 9.2 方案 A 前缀感知 e2e（核心 job 新增用例组，必须自动化）

请求经 e2e nginx LB（透传客户端任意头），对已发布服务逐一断言：

| # | 用例 | 通过标准 |
|---|---|---|
| X1 | `POST /threads/chat` 带 `X-Forwarded-Prefix: /agent/{short}`（mock LLM 触发 present_file） | SSE `file_ready` 帧 `download_url == /agent/{short}/files/{fileId}` |
| X2 | 取卡带 `X-Forwarded-Prefix` + `X-Forwarded-Host: entry.example` | card `url == http://entry.example/agent/{short}/`；**不带头的对照组 `url == ""`** |
| X3 | `GET /` 带前缀头 | endpoints 六项全部带前缀；`base_url == http://{Host}/agent/{short}` |
| X4 | `GET /metadata?includeDetails=true` 带前缀头 | 同 X3（includeDetails 分支） |
| X5 | 前缀规范化 | 头值带尾斜杠（`/agent/{short}/`）或多值（`v1, v2`）时，输出仍为规范 `/agent/{short}` |
| X6 | UI 维度 | 经前缀路径打开 `/debug/`，文件下载卡片 href 解析为 `{前缀}/files/{id}` 且 GET 200；若现有 UI 断言机制不可承载，移入 §9.5 手工 |

### 9.3 router 相位 e2e（agent-framework-ci 新增 job「E2E 路由模式」，验收必须绿）

环境构造（job 内步骤）：复用现有 kind + ingress-nginx + 平台部署基建 →
`kubectl apply -f manifests/platform-router.yaml` 并等 router ×2 Ready → backend 以
`INGRESS_HOST_SUFFIX` 空（router 模式）启动（启动成功本身即验证 §4.1 自检通过）。
R 组跑完后**同一集群翻转 suffix，串行执行 §9.4 host 相位 H 组**（兼验模式切换收敛）。

| # | 用例 | 通过标准 |
|---|---|---|
| R1 | 部署形态 | 业务 Ingress 仅 `platform-agent-router` 一条；其注解无 rewrite-target/use-regex/x-forwarded-prefix；path=`/agent`、pathType=Prefix、backend 指向 `platform-router-svc:80` |
| R2 | 发布不留痕 | 发布 ≥2 个服务，`kubectl get ingress` 计数不变（不产生任何 per-service Ingress） |
| R3 | Endpoint 契约与注册 | `endpoint == http://{INGRESS_HOST}:{INGRESS_PORT}/agent/{short}/`；服务转 `running`（注册经 ClusterURL 直连，不经入口层） |
| R4 | 前缀主链路 | `GET {入口}/agent/{short}/`、`/agent/{short}/health`、`/agent/{short}/threads` 全 200 |
| R5 | 方案 A 端到端 | 经前缀取卡 `url == http://{入口}/agent/{short}/`；chat SSE 的 file_ready 带前缀；GET download_url 200（下载链路经 router 走通） |
| R6 | Debug Console | `/agent/{short}/debug` → 302 Location=`/agent/{short}/debug/`；`/debug/` 200；页面静态资源经前缀加载 200 |
| R7 | SSE 续传 | `subscribe` 断开重连续传成功（经前缀路径） |
| R8 | 负向语义 | `/agent/` → 404；未知服务 `/agent/no-such-svc/health` → 5xx；`/Agent/{short}/` → 404（大小写敏感） |
| R9 | 上传 | 经前缀上传常规测试文件 200；条件用例：上传 >1MB 且低于应用限额的文件 200（nginx 默认 1m 会 413，证明 `client_max_body_size 200m` 生效） |
| R10 | 存量收敛 | 预置伪造 legacy per-service Ingress（含 rewrite 注解）→ Republish → 断言该对象被删除、服务仍 `running`、Endpoint 不变 |
| R11 | router 可用性 | 删除一个 platform-router pod → 前缀访问仍 200（双副本兜底） |
| R12 | 幂等 | 连续两次 Republish 无异常，Ingress 始终仅共享一条 |

### 9.4 host 模式回归验证（同 job 第二相位 e2e H 组 + go test 门禁）

host 模式是终态两模式之一，且 path 模式删除触碰了 host 共用的代码路径
（`Ingress()` 构造 / `validateIngress` / `IngressEndpoint` / `applyAll` / `EnsureIngress`），
**必须 e2e 级验证不受影响**——同时补齐 ingress-host-mode-design.md §6 遗留的
「host 模式真机回归未覆盖」历史缺口。

相位构造（紧接 §9.3 router 相位，同一 kind 集群、同一批已发布服务）：platform-backend
置 `INGRESS_HOST_SUFFIX` 非空（如 `.e2e.test`）→ `rollout restart` → 对存量服务
Republish（一次动作同时验证 **router→host 模式切换的存量收敛路径**）。

| # | 用例 | 通过标准 |
|---|---|---|
| H1 | Ingress 形态 | per-service Ingress 创建：host==`{K8sName}{suffix}`、path==`/`、pathType==Prefix、注解恰三条（ssl-redirect=false、proxy-read/send-timeout=3600）、**无任何 rewrite 注解** |
| H2 | Endpoint 重算 | Republish 后落库 endpoint == `http://{K8sName}{suffix}/`（`refreshEndpoint` 收敛） |
| H3 | 流量主链路 | 以 Host 头 `{K8sName}{suffix}` 访问 ingress NodePort：`/`、`/health`、chat SSE 全通（根路径、无前缀） |
| H4 | 方案 A 零影响不变量 | host 模式下（无 `X-Forwarded-Prefix`）：card `url==""`、`download_url==/files/{id}`、`/` 与 `/metadata` 无 `base_url` 键 |
| H5 | 注册链路 | 服务保持/回到 `running`（ClusterURL 直连，与入口模式无关） |
| H6 | Debug Console | `/debug` → 302 相对 Location（无 X-Forwarded-Prefix 分支）→ `/debug/` 200，静态资源经 Host 头加载 200 |

go test 门禁（与本相位并行保留）：host 模式 fake 端到端既有用例全绿（Ingress 形态 /
注解恰三条 / Endpoint 派生）；「suffix 空 + `INGRESS_TEMPLATE` 非空 → 启动失败」用例绿。

### 9.5 手工清单（kind 真机，发布前一次性勾选）

1. X6 的 UI 维度（若未自动化）；
2. TLS/外层代理场景 `X-Forwarded-Proto` 透传正确（map 兜底逻辑，环境具备时验证）；
3. 长稳 SSE：单条 chat 流 >60s 持续输出不断（心跳 + 超时链路）；
4. §4.6 升级窗口演练：存量 path 集群按序升级，重叠期抽验请求正确到达（两条路由终点等价）。

### 9.6 完成定义（DoD）

PR-1/2/3 合并；§9.1 全绿；§9.2 用例组进核心 job 且绿；「E2E 路由模式」job 存在且
**router 相位（§9.3 R 组）与 host 相位（§9.4 H 组）在 PR run 均绿**；§9.4 go test 门禁绿；
§9.5 四项勾完。**缺一即任务未完成。**

## 10. 实施记录（2026-10-10）

### 10.1 与设计的两处落地偏差（已核实并按仓库 CI 现实定稿）

1. **§9.3 的「复用 kind + ingress-nginx + 平台部署基建」前提不成立**：agent-framework
   的四个 e2e job 均为本地进程形态（GitHub services MySQL/Redis + jar + docker nginx），
   仓库 CI 无 kind/平台 backend 环境。落地形态调整为——
   - **RT 组**（`agent-framework/e2e/tests/api-router.spec.ts`，CI 新增非必需 job
     「E2E 路由模式」）：`fixtures/nginx-router.conf.template` 为 manifest nginx.conf 的
     **本地语义变体**（location/rewrite/proxy_set_header 逐条同源，唯一差异：kube-dns
     变量解析 upstream 换固定地址），覆盖 R4–R9 语义（前缀路由、X-Forwarded-Prefix
     注入的方案 A 端到端、Debug 302、SSE 续传、上传、负向、endpoints/base_url）；
   - R1–R3/R10/R11（集群/Ingress 对象形态断言）与 H 组由 **backend go test**（fake
     集群：router 发布不留痕/存量删除/host 形态回归/模式切换收敛/fail-fast）+
     §9.5 手工清单覆盖；两份 nginx 配置（manifest 与 fixture）同步义务写入注释。
   - e2e 用例编号 R 组在代码中命名 **RT 组**（避免与 e2e-multi 既有 R 组撞名）。
2. **§9.4 H 组（同 job 第二相位）未落地为独立 e2e**：host 模式形态/Endpoint/模式切换
   收敛由 go test 全量覆盖（`TestPublishHostMode`/`TestRepublishRecomputesEndpointAfterHostModeSwitch`
   等，router→host 切换收敛即该测试首段），真机 H 组归并 §9.5 手工清单。

### 10.2 实施清单

| PR | 内容 |
|---|---|
| PR-1 `feat/subpath-routing-framework` | 方案 A：`util/ExternalUrlSupport` + AgentCard/Info/ChatStream 三处 + chat.js 下载卡片判别式 + 契约文档（api-frontend-sse.md §9.7 / AGENTS.md 服务端点节）；单测 4 类新增前缀维度；e2e 核心 X 组（X1–X5，LB 透传头注入）+ RT 组全套 + CI job「E2E 路由模式（RT 组）」+ 本设计文档 |
| PR-2 `feat/subpath-routing-backend` | 删 path 模式（`Ingress()` path 分支与 rewrite 五常量、`validateIngress`/`IngressEndpoint` path 分支、overlay 示例 P 组）；router 模式（`RouterEndpoint`、applyAll「存在即删除」、`deriveEndpoint`、`RequireRouter` 启动自检 + `ServiceExists` 接口、config fail-fast「suffix 空+模板非空拒绝」）；`manifests/platform-router.yaml`；测试改写（router 发布端到端/存量删除/host 回归/探针同模式）；AGENTS.md（根+backend）与 deployment.md §七之二/§七之三 |

### 10.3 验证证据（§9 DoD 对应）

| 项 | 结果 |
|---|---|
| §9.1 agent-framework 单测 | `mvn test` **1390 例 0 失败 4 跳过**（含新增 ExternalUrlSupportTest 10 例、AgentCard/Info/ChatStream 前缀维度） |
| §9.1 backend 单测 | `go vet ./...` + `go test ./...` 全绿（k8s/config/service/handler/mcpsrv/store/cmd） |
| §9.2 X 组 | 核心套件本地实跑 **X1–X5 全过**；全量 core e2e 85 passed / 0 failed（含 UI 组） |
| §9.3 RT 组 | 本地实跑 `run.sh router` **8/8 全过**（经完整 env-up→playwright→env-down 路径）；CI job 随 PR 生效 |
| §9.4 go test 门禁 | host 模式回归 + 「suffix 空+模板非空启动即拒」+ RequireRouter 用例全绿 |
| §9.5 手工清单 | **待 kind 真机勾选**（X6 UI 维度 / TLS X-Forwarded-Proto 透传 / >60s 长稳 SSE / §4.6 升级窗口演练） |

### 10.4 实施期修正（设计未预见）

- platform-router 的 `X-Forwarded-Host` 兜底从 `$host` 改 **`$http_host`**：`$host` 剥端口，
  NodePort 入口集群（`{INGRESS_HOST}:{INGRESS_PORT}` 形态）上 agent-card url 会丢
  `:30080` 派生出不可达地址；`$http_host` 保留端口，域名形态两者等价（RT2 锁定）。
- e2e RT7 上传用例 mime 需走应用白名单（`text/plain`；octet-stream 会被应用 415 拒，
  与 router 无关）。
