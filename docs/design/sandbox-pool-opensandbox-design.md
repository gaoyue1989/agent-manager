# 沙箱池化下沉 OpenSandbox 设计（服务端池 + 单用户配额与生命周期）

- 日期：2026-11-07（v6：v2 两轮子代理评审修订 + v3 用户评审补充 + v4 验证与门禁细化 + v5/v6 实施前终审三条 P0 修订，见 §13）
- 状态：**已实施（2026-11-08，PR #277 回滚 + #278 文档 + #279 平台 + #280 框架 合并）**（§11 已回填 V1~V8：V1~V5 全过、V6 部分通过=单节点 kind 内存限制、V7/V8 PASS；唯余缺口 = V6⑧ 换代提示帧的 live SSE 发出面，已由 mock e2e 4/4 实证；遗留收敛见 §13 评审记录与 PR 序列）
- 前置：整体回滚 PR #276（agent-framework 自研 Redis 沙箱池，merge `9c81437`；其设计文档 `agent-framework/docs/sandbox-pool-design.md` 随回滚移除，git 历史可考）
- 上游基线：OpenSandbox `release-1.1.0`（umbrella release，全部组件 1.1.0）
- 范围：backend / frontend / agent-framework / kind 集群 OpenSandbox 部署

---

## 1. 背景与动机

### 1.1 PR #276 的问题：预热做在了客户端

PR #276 在 agent-framework 内自研 Redis 共享沙箱池（`SandboxPoolManager` 1695 行，D1~D19 决议）：通用 idle 池预热、借出/归还、creating 占位、水位弹性伸缩、五点租约续约、粘滞归还……复杂度根因是**把「预热」做在了业务框架里**——框架被迫同时维护「容器真相镜像」（Redis 账本）与「容器真相本体」（OpenSandbox Server），两者的每一种竞态（副本崩溃、容器被 GC、长命令在途、确认等待）都要在账本侧打补丁。

### 1.2 上游承接确认

现已确认上游 OpenSandbox BatchSandbox **服务端池**（Pool CRD + `extensions.poolRef`）可承接通用 idle 池的预热职责：

- Pool CRD 以 `capacitySpec.bufferMin/Max` 维持 warm Pod 水位（调度 + 镜像拉取提前完成）；
- `extensions.poolRef` 创建沙箱 = **常数时间认领 warm Pod**，per-sandbox 的 entrypoint/env 经 `BatchSandbox.spec.taskTemplate` 事后下发（task-executor :5758），execd 在认领后 bootstrap；
- Pool 全生命周期（预热/扩缩/回收/容量）经 `/v1/pools` REST API 管理。

需如实说明的语义差异：poolRef 认领的是**warm Pod 本体**（调度 + 镜像拉取提前完成），不等价于自研池「现成容器零启动认领」——设计期保守估计认领后仍有容器启动 + execd boot（秒级~十几秒）。**V2 口径对账实测（2026-11-07，§11）钉死**：认领返回即 state=Running（1.07s，对照组直建 2.16s 且为全新 Pod）、代理执行的 hostname 返回的就是 warm Pod 名、首命令 2ms——与本文「常数时间认领 warm Pod」表述一致，2026-11-05 旧核对结论（git `e11cadd`「仍是全新容器，只省调度/拉镜像」）在 release-1.1.0 下不成立；「秒级~十几秒 boot」在本环境未观测到（池模板主容器预跑 execd bootstrap 常驻，上游 e2e 同形状），warm 池收益可按「认领即用」评估——该结论依赖池模板形状把 execd 预跑起来（业务镜像主容器的模板未单独测量），冷镜像场景的预热收益量化未做（实测为空载 + 镜像全缓存的乐观值）。本设计接受该残余语义差，换取框架侧池复杂度整体清零——预热是基础设施职责，应下沉。

### 1.3 回滚决议（用户已拍板）

- **整体回滚** PR #276 全部 45 文件（`git revert -m 1 9c81437`，master 在 merge 后无新提交，可干净回滚）；其中三块可分离内容：
  - ① 池化本体（`SandboxPoolManager` + 配置/调试页/e2e/CI job）——本设计取代；
  - ② D16 确认硬截止（`ConfirmDeadlineService` + 前端 `expires_at` 倒计时，设计上独立于池化）——随回滚移除，如仍需要**后续单独 PR 重新落地**；
  - ③ D19 `resourceRequests` 透传（约 4 行）——同②，需要时单独捡回。
- 回滚后框架基线行为 = 现状：每会话首次使用新建容器、会话内 resume 复用、容器滞留至 Server 侧 `timeout`（默认 60min）被 GC、**无配额上限**。

### 1.4 新职责划分（本设计的核心一句话）

> **OpenSandbox 负责预热、池、TTL 真相与 GC；agent-framework 只做单用户配额与生命周期触发；backend/frontend 做管理面。**

---

## 2. 上游能力核对（release-1.1.0）

来源：上游仓库 `opensandbox-group/OpenSandbox`（文档站 open-sandbox.ai、`specs/sandbox-lifecycle.yml`、`server/opensandbox_server/api/pool.py`、`kubernetes/apis/sandbox/v1alpha1/pool_types.go`、HELM-DEPLOYMENT.md）。标注：**[确认]** = 文档/源码逐字核实；**[推测]** = 实施时以 kind 验证为准。

### 2.1 池管理 API（`/v1/pools`）**[确认]**

仅 `runtime.type = "kubernetes"` 可用，否则全部池端点 501 `K8S_POOL_NOT_SUPPORTED`。Pool API **非租户隔离**（官方 multi-tenancy 指南：池管理路由工作在 server 配置的默认 namespace）→ 池是平台共享资源，统一由平台管理面治理（§5）。

| 方法 | 路径 | 语义 |
|---|---|---|
| POST | `/v1/pools` | 创建 Pool CRD（name + Pod 模板 + capacitySpec）；409 = 同名已存在 |
| GET | `/v1/pools` | 列出 namespace 内全部池 |
| GET | `/v1/pools/{name}` | 单池详情（含运行状态） |
| PUT | `/v1/pools/{name}` | **仅可改 capacitySpec**（bufferMax/bufferMin/poolMax/poolMin）；改模板须删除重建 |
| DELETE | `/v1/pools/{name}` | 204；池管理的 warm Pod 由 controller 终止 |

POST 请求体外层字段名（`name/template/capacitySpec`）**[推测]**；`capacitySpec` 四键名**[确认]**。

Pool CRD 关键字段 **[确认]**：

- `spec.template`：完整 PodTemplateSpec（schemaless 透传；initContainers 装 execd/task-executor，主容器跑 task-executor 常驻）；
- `spec.capacitySpec`：`bufferMin/bufferMax`（warm 就绪带）、`poolMin/poolMax`（总量界），int32 全必填；
- `spec.recycleStrategy.type`：`Delete`（默认，归还换新 Pod）/ `Restart`（原地重启）/ `Noop`（原样复用最快但状态泄漏）；
- `status`：`revision/total/allocated/available/updated`——管理面水位展示的数据源。

### 2.2 poolRef 池化创建语义 **[确认]**

- create 请求体 `extensions.poolRef = <池名>`：沙箱从池认领 warm Pod；此时 `image/entrypoint/resourceLimits` 均可省（由 Pool 模板定义）；`snapshotId` 与 poolRef 互斥；`volumes`/`networkPolicy` 不能与 poolRef 组合（须写进 Pool 模板）；
- 分配 = 常数时间认领已运行 Pod（allocation annotations 持久化）；请求的 entrypoint/env 记入 `BatchSandbox.spec.taskTemplate`，由 controller 下发到认领 Pod 的 task-executor（:5758），execd 认领后启动；
- 池耗尽：controller 置 `PoolAllocationPending (reason=PoolCapacityExhausted)` 每 5s 复查，server 可能返回**可重试 429**；
- 未设置 poolRef 时另有 pool 自动指派（predicate + 打分插件），本设计不依赖；
- 已知问题：变更 poolRef 到另一池有杀在用 Pod 的 bug（上游 issue #1433）——框架只在 create 时带 poolRef、从不改绑，规避。

### 2.3 TTL 与续期 **[确认]**

- create `timeout`（秒，≥60）→ 响应 `expiresAt` 绝对时间，到期 `reason=ttl_expiry` 由 server GC——**TTL 真相在服务端**；
- `POST /v1/sandboxes/{id}/renew-expiration`，body `{"expiresAt": RFC3339}`，必须晚于当前 expiresAt；Java SDK 封装 `sandbox.renew(Duration)`（相对时长→绝对时间）；
- 观测：`GET /v1/sandboxes?state=&metadata=` 支持状态与 metadata 过滤（分页参数语法 **[推测]**，kind 验证核实）。

### 2.4 SDK 现状（`com.alibaba.opensandbox:sandbox:1.0.18`，无需升级）

- `Sandbox.builder().extensions(Map)` 原生透传（2026-11-05 已核实，见被回滚的 sandbox-pool-design.md §5.1）；
- `sandbox.renew(Duration)` 续期可用；`connector().sandboxId().connect()` 重连语义不变（resume 端点仅对 Paused 容器有效，Running 重连走本地句柄 + 惰性 getInfo，现行代码注释已钉死）。

### 2.5 部署形态 **[确认]**

- Helm 分组件 charts（上游仓库 `manifests/charts/{base, controller, server, ingress-gateway, node-agent, fast-sandbox, opensandbox(umbrella)}`；路径存在另一说法 `k8s/helm/`，**实施时以 release tag 实际目录为准**）；
- 安装顺序：base（CRD：batchsandboxes / pools / sandboxsnapshots + RBAC）→ controller → ingress-gateway（K8s 上必需：沙箱 Pod ClusterIP-only，流量走网关）→ server；上游 CI 的 `helm-release-test.yml` 本身就在 **Kind** 里装 umbrella chart 冒烟——kind 验证可行性有直接证据；
- server 配置：`runtime.type=kubernetes` + `[kubernetes]`（namespace、`workload_provider=batchsandbox`、`sandbox_create_timeout_seconds` 默认 60 等）；鉴权 `[server] api_key` → 请求带 `OPEN-SANDBOX-API-KEY` header（`/health`、`/docs` 除外）；无 API key 拒绝启动；
- 持久化默认 SQLite，可选 PostgreSQL；**无 Redis 依赖**；
- 镜像（池化最小集，tag 以 release-1.1.0 BOM `docs/releases/1.1.0.yaml` 为准）：`opensandbox/{server,controller,ingress}` + 池 Pod 三件套 `opensandbox/{code-interpreter,execd,task-executor}`。

---

## 3. 目标架构

```
┌─ frontend ──────────────────────────────────────────────────────┐
│ 运维侧 /admin/sandboxes：池水位卡+容量调整/创建/删除、沙箱表格+销毁 │
│ 用户侧 console 资产组「沙箱实例」：本工作空间沙箱只读视图            │
└────────────┬───────────────────────────────────────────────────┘
             │ /api/v1/admin/sandboxes*（RequireAdmin）
             │ /api/v1/console/workspaces/:ws/sandboxes（JWT）
┌─ backend（控制面）──────────────────────────────────────────────┐
│ OpenSandboxProxy：只读拉取 + 显式管理操作转发                      │
│ 配置事实源 = platform_config（OPENSANDBOX_SERVER_URL / API_KEY）   │
│ 无新表、无后台 ticker（控制面红线）                                │
└────────────┬───────────────────────────────────────────────────┘
             │ OPEN-SANDBOX-API-KEY（集群内 svc）
┌─ opensandbox（业务基础设施，kind 内 helm 部署，ns opensandbox-system）┐
│ Pool CRD bufferMin/Max 预热 warm Pod；extensions.poolRef 常数时间认领 │
│ expiresAt TTL 真相 + renew-expiration + 到期 GC（ttl_expiry）        │
└────────────▲─────────────────────────────────────────────────────┘
             │ Java SDK 1.0.18（extensions.poolRef / renew）
┌─ agent-framework（业务面，多副本无状态）─────────────────────────┐
│ sandbox_user / sandbox_binding 表（MySQL checkpoint 库，Flyway）    │
│ 借出决策：配额内 create ｜ 满→空闲 LRU 复用+renew ｜ 全忙→异常      │
│ TTL 触发点：create / turn 收尾 / doExec 在途定时                    │
└──────────────────────────────────────────────────────────────────┘
```

职责边界核对：

| 关注点 | 归属 | 说明 |
|---|---|---|
| 预热（warm Pod 水位） | opensandbox Pool CRD | bufferMin/Max 驱动，controller reconcile |
| 沙箱 TTL / GC 真相 | opensandbox server | expiresAt 权威，框架侧 expires_at 仅 LRU 排序缓存 |
| 单用户配额仲裁 | agent-framework | sandbox_user 锚行事务串行化 |
| session↔sandbox 绑定 | agent-framework | sandbox_binding 表 |
| 「执行中 turn」判定 | agent-framework（既有） | turn_lease 表 isHeldStrict，事实源不变 |
| 池/沙箱的观测与治理 | backend+frontend | 代理转发，不复制状态 |

---

## 4. agent-framework 设计

### 4.1 数据模型（Flyway `V15__sandbox_binding.sql`）

新表落 per-service checkpoint 库（`{原库}_{k8s名}`），配额天然按服务隔离——同一用户在两个业务服务各占各自配额，符合「服务间数据天然隔离」原则（架构原则二）。

```sql
-- 用户配额计数表：占额 CAS 锚点（设计意义见下）
-- 时间列一律 DATETIME(3)（毫秒）：⑤c 改绑的 updated_at 乐观校验依赖毫秒精度，
-- 同秒内 touch 也要能推进值（实现 V15 与本 DDL 一致）
CREATE TABLE IF NOT EXISTS sandbox_user (
  user_key       VARCHAR(255) NOT NULL,
  binding_count  INT NOT NULL DEFAULT 0,  -- 该用户当前绑定数；与 COUNT(sandbox_binding) 事务内同增同减
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 绑定表：user/session ↔ sandbox 簿记
CREATE TABLE IF NOT EXISTS sandbox_binding (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  user_key   VARCHAR(255) NOT NULL,
  session_id VARCHAR(255) NOT NULL,
  sandbox_id VARCHAR(128) NOT NULL,
  expires_at DATETIME(3) NOT NULL,               -- LRU 排序缓存，真相在服务端 expiresAt
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sandbox (sandbox_id),          -- 一个沙箱至多一条绑定
  UNIQUE KEY uk_session (session_id),          -- 一个会话至多一条绑定（复用=UPDATE 改归属）
  KEY idx_user (user_key, expires_at),         -- 配额校验 + LRU 取最小 expires_at
  KEY idx_expiry (expires_at)                  -- 定时清理扫描（§4.1.2）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

幂等守卫遵循 Flyway 纪律（新表 `CREATE TABLE IF NOT EXISTS`，见 db-migration-flyway-design.md FW3）。

**sandbox_user 的设计意义——占额 CAS**：配额仲裁的本质是「检查计数 + 占位」必须原子。仅靠绑定表做不到：用户 0 绑定行时 `SELECT ... FOR UPDATE` 锁不住空集，两个并发会话各自 count=4 < 5 都去 create，突破配额。sandbox_user 把计数物化为一行，用条件 UPDATE 做原子占额：

```sql
UPDATE sandbox_user SET binding_count = binding_count + 1
 WHERE user_key = ? AND binding_count < :quota;   -- affected = 1 即占额成功
```

同时它消除了「HTTP 调用夹在锁内」的问题：占额（短事务）→ create（事务外 HTTP）→ 落绑定（短事务），任何锁持有都是毫秒级；create 失败走补偿事务递减计数。该行在配额满路径还兼任串行化锚点（FOR UPDATE，§4.2 ⑤）。

**不变量「计数 == 行数」——PENDING 占额行**（评审修订，防幽灵占额）：占额与绑定落库是两个事务，若 ② 提交后 Pod 崩溃（create 前或落绑定前），`binding_count +1` 却无对应绑定行——幽灵占额会让 ⑤ 的重判死循环或误抛配额满，最长持续到次日定时重算。修复：**② 占额事务同时 INSERT 一条 PENDING 绑定行**（`sandbox_id = 'pending:{uuid}'`，session_id 即本会话），③ create 成功后短事务 `UPDATE ... SET sandbox_id = 真实 id, expires_at = 服务端响应 expiresAt`；create 失败或唯一键冲突 → 删除 PENDING 行 + 计数递减。此后**任意时刻 binding_count == COUNT(binding 行)**，⑤ 的重算/懒剔除在锚行锁下恒安全；崩溃残留的 PENDING 行由懒剔除按行龄回收（`sandbox_id LIKE 'pending:%' AND created_at < NOW() - 10min`，§4.2 ⑤a、§4.1.2）。① 的查找忽略 PENDING 行。

**为何 MySQL 而非 Redis 账本**：被回滚的 Redis 池复杂度大半来自「租约 TTL = 失活判定唯一真相」的账本技巧；新方案 liveness 真相已上移服务端（expiresAt + getInfo），框架只剩簿记 + 配额仲裁——MySQL 事务（条件 UPDATE CAS + 锚行 `FOR UPDATE`）即可安全串行化，且与 turn_lease 同库可一致判定「执行中 turn」，可调试、可审计，符合「多副本协调只靠共享存储原子操作」纪律。既有 `sbx:guard:{scope}:{value}`（Redis，同 slot 沙箱获取串行化）**原样保留**，与本表职责正交。

#### 4.1.1 「沙箱是否正在使用」的可见性——派生而非冗余字段

绑定表**不加 busy/turn_running 冗余字段**。「执行中 turn」的事实源是 turn_lease（跨副本、崩溃安全：Pod 死则租约到期自动释放）；任何镜像字段都是第二事实源，在 turn 开始/结束/挂起/崩溃各窗口必然漂移——漂移的 busy 标记会导致误复用（执行中的沙箱被抢）或误锁死（空闲沙箱占着配额），被回滚 Redis 池的核心教训就是「账本镜像真相」。所有需要展示/判定「正在使用」的位置一律**派生**：

- 仲裁（§4.2 ⑤）：`turn_lease.isHeldStrict(binding.session_id)`，配额上限内 ≤5 次主键点查；
- debug 端点 / 管理面展示：`sandbox_binding` 与 `turn_lease` 一条 JOIN 查询派生 `turn_running` 列（展示含租约刚到期的宽限语义，同 §4.2 ⑤）。

#### 4.1.2 历史数据增长与定时清理

绑定行不会在会话结束时删除（框架无会话结束信号），沙箱到期后行仍在；仅靠 acquire 懒剔除只覆盖「该用户还会再来」，历史行单调增长。清理**并入既有 `SessionCleanupService` 定时任务**（同 `AGENT_CLEANUP_*` 配置体系，不引入新调度框架）：

- 绑定行：`DELETE ... WHERE expires_at < NOW() - INTERVAL :retention DAY`（保留窗口默认 7 天，env `AGENT_CLEANUP_SANDBOX_BINDING_RETENTION_DAYS`）——过期超过保留窗口的沙箱必然已被服务端 GC，删除前无需 probe；
- 计数一致性：受影响用户的 `binding_count` 同任务分组重算（`binding_count := COUNT(存活绑定)`）；
- 锚表防膨胀：清理后已无绑定行且 `updated_at` 超保留窗口的 `sandbox_user` 行一并删除；
- 与懒剔除分工：懒剔除保**正确性**（配额判定前清死绑定），定时清理保**容量**（再也不来的用户留下的行）。

### 4.2 借出决策（acquire，首次 exec 惰性绑定）

入口不变：SDK acquire 链路 P1~P3（externalSandbox / slot 持久化 state → resume）维持现状；P4（全 miss → create）与 P3 resume 校验失败处改为走本决策。用户身份沿用 `SessionKeyResolver.realUserId`（session_user 反查真实 userId，miss 回退 sessionId，与 KV 隔离语义一致）；`pendingSessionId/pendingUserKey` 捕获点结论继承被回滚代码的验证：在守卫 `tryEnter` 同线程注入（`OpenSandboxFilesystemSpec` ThreadLocal），控制器侧注入会被 defer 挪线程失效。

```
acquire(sid, userKey):
  ① b = bindingRepo.findBySessionId(sid)（忽略 PENDING 行）
     若存在 → touch 先行（终审 d2：在 probe/connect 之前占住 updated_at，使他副本 ⑤c
       的乐观校验能感知本会话「在途复用」，否则 probe/connect 的 HTTP 间隙内本会话
       会被 stale updated_at 误判 idle 遭 LRU 改绑，见下「两道防线」）:
       touch: UPDATE binding SET updated_at/expires_at
               WHERE id = :b.id AND session_id = :sid
       affected = 1 → probeAlive(b.sandboxId)（getInfo 200）→ connect 复用（resume 语义，现状行为）
         ——把「正在使用」立刻写下去，封掉「A2A 链路 acquire 后、守护线程首拍续期前」的
         grace 空窗（busy 判定的第二事实源从此刻起算）
       affected = 0 → 绑定已被他副本改绑或懒剔除 → 放弃复用（不 connect），视同 miss 走 ②
     probeAlive 404/410 → 本会话行懒剔除（DELETE WHERE id=:b.id AND session_id=:sid
       + binding_count-1；行归属自己，无并发争用）→ 走 ② 重建（§4.4 到期重建语义）
  ② 占额（短事务：CAS + PENDING 行，见 §4.1 不变量）:
     UPDATE sandbox_user SET binding_count = binding_count + 1
      WHERE user_key = ? AND binding_count < :quota
     affected = 1 → INSERT binding(user_key, sid, sandbox_id='pending:{uuid}') → 走 ③
     affected = 0 → 配额满（或计数过时）→ 走 ⑤（首见用户先 INSERT IGNORE 锚行再重试一次）
  ③ create（事务外 HTTP，不持任何锁）:
     sb = client.create(
            extensions = {poolRef: SANDBOX_POOL_REF}        -- 配置为空则不传
            timeout    = SANDBOX_TIMEOUT_MINUTES * 60       -- TTL 起点
            metadata   = {service: 服务名, session_id: sid}  -- 供平台侧过滤（§5.3）
            + 既有 image/env/entrypoint/resourceLimits)
     成功 → 短事务 UPDATE PENDING 行 SET sandbox_id = sb.id, expires_at = 服务端响应 expiresAt
     失败 → 补偿短事务 DELETE PENDING 行 + binding_count - 1，按 §4.6 异常面抛出
  ⑤ 配额满路径（先锁外 probe，再锚行 SELECT ... FOR UPDATE 短事务串行化 per-user 决策）:
     a. 懒剔除（全部守卫式 DELETE，防并发误删；每删一行同事务 binding_count - 1）:
        - 过期行: DELETE WHERE id=? AND expires_at < NOW()           -- expires_at 为服务端绝对时间
        - 陈旧 PENDING: DELETE WHERE sandbox_id LIKE 'pending:%' AND created_at < NOW() - 10min
        - probeAlive 404/410 的行（probe 在锚锁外做）: DELETE WHERE id=? AND session_id 未变 AND updated_at 未变
        - probeAlive 网络异常：保守视为活，不删
     b. 锚行锁内 binding_count := COUNT(存活行)（不变量成立，见 §4.1）→ 提交 → 回 ② 重试 CAS
     c. 重读 live（该 user 存活绑定）→ busy/idle 判定（双事实源，见下）:
        idle = live 中「无执行中迹象」者，busy := turn_lease.isHeldStrict(session_id)
                 OR updated_at > NOW() - :grace        -- grace = max(3×RENEW_INTERVAL, 120s)
        - idle 非空:
            victim = idle 中 expires_at 最小者    -- LRU = 距过期最近（执行中持续续期，
                                                 --    expires_at 最小 ≈ 最久未使用）
            原子改绑（锚锁内，updated_at 快照乐观校验——终审 d2）:
              UPDATE sandbox_binding SET session_id = :sid
               WHERE id = :victim.id AND session_id = :victim.session_id
                 AND updated_at = :victim.updated_at   -- busy 判定时读到的该行快照值
              -- 无论 busy 判定的读发生在锁外还是锚行锁内一致性读，改绑（当前读）都复验：
              -- 受害在途 ① touch 一旦先提交，updated_at 已变 → affected = 0
            affected = 1 → connect(victim.sandbox_id) + renew(TTL) + expires_at = 服务端响应值
                        （binding_count 不变：同 user 内转移）→ return
            affected = 0 → 他副本抢先，或受害会话在途 ① touch 先行提交（乐观校验拦截）
                        → 整个 ⑤ 重试（≤ 2 次）
        - idle 空（全部有执行中迹象）→ throw UserQuotaExhaustedException(live)  -- → §4.6
  （受害会话槽位不在本流程置墓碑——SessionSandboxStateStore 底层自管连接无法并入锚行事务，
    终审 d1/cold-R1-1 改为 P3 resume 归属校验+占位（条件写），见下「两道防线」第 2 条）
```

**busy 判定为什么是双事实源（评审 P0 修订）**：`turn_lease` 是自限 10 分钟的互斥原语（TurnLeaseGuard 硬寿命到期物理删行、执行线程不感知继续跑）——一个 12min 的 turn 从第 10min 起租约消失，仅靠 isHeldStrict 会把执行中的沙箱判成 idle 被抢占互踩；A2A 链路则**从不持有 turn_lease**。第二事实源用 `binding.updated_at` 新鲜度：T3 续期守护线程每拍 UPDATE expires_at/updated_at，「最近被续期过」是**跨副本可观测的观测事实**（非镜像猜测），且与 turn_lease 生命周期解耦，同时补上 >10min 与 A2A 两个盲区。阈值 `grace = max(3×RENEW_INTERVAL, 120s)` 必须 **> 续期周期**（60s 宽限与 60s 续期间隔同值，拍间必有陈旧窗口边界抖动）；时间比较全部下推 SQL（`NOW(3)` 同用 DB 时钟，无副本偏移）。代价：turn 结束终刷后沙箱多「忙」一个 grace（LRU 复用延迟 ~2min），可接受。该判定同样应用于 §4.3 预检与 §4.8 debug 派生列。守护线程自身需健康监控（循环滞后指标，线程死 = 退回误判窗口）。

**受害会话失效 = 两道防线（终审 d1/d2/cold-R1-1 修订，取代 v4「改绑同事务给受害会话槽位置墓碑」）**：LRU 改绑后必须保证受害会话不会继续使用已易主沙箱，且**任何路径**的 acquire 成功都必须立即占位（封 grace 空窗）。v4 原方案「同事务置槽位墓碑」经实施前终审认定不可实施：`SessionSandboxStateStore` 底层 `SandboxAwareMysqlAgentStateStore` → `MysqlAgentStateStore`（agentscope-extensions-mysql 2.0.3）**自管连接**（构造器收裸 `DataSource`、`save/delete` 方法体内部自调 `DataSource.getConnection()`，反汇编实证见 §13 终审 d1），无法并入 `SandboxBindingStore` 的锚行事务；且 slot 键格式 `sandbox/user/{agentId}/{userId}` 为 harness 私有（`SandboxLocator` 明令不手拼）。照原文实施则墓碑与改绑退化为两个独立事务，改绑提交后、墓碑前，受害会话经 P3 connector（`skipHealthCheck(true)`，`OpenSandboxClient.java:233`）直连易主沙箱——恰是墓碑要防的互踩。改为：

1. **在途互斥（d2）**：① touch 先行（probe/connect 之前占住 updated_at）+ ⑤c 改绑 `AND updated_at = :快照值` 乐观校验。touch（`WHERE session_id=:sid`）与改绑（`SET session_id=:新, WHERE updated_at=:快照`）作用于同一行，InnoDB 行锁串行化下任一提交序**恰有一方 affected=1**：touch 先提交 → 改绑 updated_at 不匹配；改绑先提交 → touch 按 session_id 定位失败 → 受害放弃复用重走决策。任何时序下不存在「改绑成功且受害 touch 成功」的提交，在途双用窗口闭合。
2. **P3 归属校验+占位（d1 取选项 c；v6 经 cold-R1-1 由读时校验升级为「校验+占位」合一条件写）**：不写墓碑——P3 `client.resume` connect 成功后（connect 仅建立句柄，`skipHealthCheck(true)` 下无探活也无副作用）、句柄返回供 exec **之前**，执行「校验+占位」合一的条件写：

   ```sql
   UPDATE sandbox_binding SET updated_at = NOW(3)
    WHERE sandbox_id = :state.sandboxId AND session_id = :sid   -- uk_sandbox 唯一，至多一行
   ```

   affected = 1 → 归属有效且 updated_at 已占位（**acquire 即占位**，与 ① touch 同一语义：封「acquire/直连成功后、守护线程首拍续期前」的 grace 空窗）→ 放行；affected = 0 → 无行（fail-open 期直建未记账、恢复后首个 P3；或已被懒剔除），或 binding 已改绑他会话，或 slot↔binding 漂移（binding 指向另一 sandbox）→ close 句柄 + 抛降级异常 → 路由本决策重新记账/决策（先例：被回滚 PR #276 `reconcileResume` 即「resume 后校验归属 → close + 降级」模式）；binding 表为归属权威，漂移时降级后 ① 命中权威行，stale slot 待 harness acquire 收尾覆写。`SandboxLocator.locate`（env-update 链路，同样 `skipHealthCheck(true)` 直连，`SandboxLocator.java:97`）同规则同写。
   **为何必须写而不止读（cold-R1-1）**：存量会话每次 acquire 走 P3 直连（基线 `OpenSandboxClient.java:151`，回滚后行号；工作区 :233）——A2A 链路无 turn_lease（A2AController 0 处 TurnLease 引用，实核见 §13 终审），闲置 > grace 的 A2A 会话重入时 [P3 resume 通过, 首拍续期] ≈ ≤60s+滞后 窗口内 binding.updated_at 仍陈旧，同用户并发 ⑤c 判 idle 改绑 affected=1（读时校验无 touch 可拦截）→ 受害在途 exec 与新会话双用——与 ① 路径同款 grace 空窗（① 由 touch 封堵，见第 1 条），P3 路径必须同样占位；且该条件写就是 ⑤c 快照校验的互斥对手方：占位先提交 → ⑤c `updated_at` 快照不匹配 affected=0；⑤c 改绑先提交 → 占位 `WHERE session_id=:sid` 0 行 → 降级——任一提交序恰有一方 affected=1，与第 1 条证明同构。**校验写异常（MySQL 不可达）→ 放行 + 事件流 WARN**（与 §4.2 fail-open 同纪律：互踩窗口仅存在于存储故障期，不因校验失败制造沙箱重建风暴）；只写 updated_at 不本地推算 expires_at（服务端绝对值由首拍续期/T2 终刷回填，见要点末条纪律）。代价：每次 P3 resume/locate 一次单行条件 UPDATE（uk_sandbox 定位，量级同 ① touch）；受害降级路径多一次 connect+close（未执行任何命令，无实害）。

选 c 而非 b（固定两步写序「先墓碑后改绑」）的理由：b 的两事务间隙仍留受害在途 connect 窗口（须再靠第 1 条收口），且引入「墓碑未落不改绑」跨事务写序纪律、墓碑孤儿态处理与 slot 键合成耦合（`SandboxIsolationKey.resolve` 私有算法 + USER 作用域双候选删除）；c 与 §4.9 既有 resume 校验路由决议同构，且把归属真相收敛到 binding 表**单点**（校验与占位同写一行，v6 经 cold-R1-1 落定为条件写，无 slot 侧第二真相），符合 §4.1.1「不造第二真相」纪律。详见 §13 终审 d1 与 cold-R1-1。

要点：

- **抢占后的旧会话自愈**：绑定被新会话 LRU 抢走后，旧会话后续 acquire 经 P3 resume **归属校验+占位 0 行**（binding 已归新会话）→ close + 降级走本决策（再抢或新建），最终一致且**绝不 exec 已易主沙箱**（两道防线，见上）；
- **不变量：一个沙箱同一时刻至多服务一个「执行中迹象」的会话**——turn 必经 acquire，②⑤ 的 CAS/条件 UPDATE（含 ⑤c updated_at 快照乐观校验）保证归属改写原子；**任何路径 acquire 成功必先占位**（决策复用路径 ① touch、P3 直连路径校验+占位条件写，均前置）→ 在途复用与改绑 affected 互斥；P3 校验+占位 0 行降级保证旧持有方失联（不再重连易主沙箱）；
- **竞态兜底**：chat 预检（§4.3）放行后、exec 前其他会话占满配额——exec 时二次裁决兜底，异常路径同 ⑤；
- **fail-open**：MySQL 不可达 → 跳过 ②③⑤ 直接 create 不记账（60s 限流 WARN）——注意 fail-open 期① 也读不到：**已有活绑定的会话同样直建新沙箱**（旧沙箱 TTL 内并存、无绑定无续期、自然 GC；槽位状态同库也读不到，P4 必然触发，行为自洽），存储恢复后由懒剔除/定时清理收敛；
- probe 策略：仅配额满路径（⑤a）需对 ≤quota 条绑定逐条 probe（**锁外**执行，锁内只做守卫式 DELETE/改绑），普通路径 ① 单次 probe；
- `expires_at` 一律回填**服务端响应的绝对 expiresAt**（create/renew 响应均携带），不用本地 `now+TTL` 推算——消除多副本/跨机时钟偏移导致的误删/延迟删。

### 4.3 chat 入口预检（满足「新建 session 时告知前端」）

`/threads/chat` 入口（`ChatStreamController`，在 `session_user.upsert` 之后取 userKey）：当该服务 `SANDBOX_ENABLED=true` 且请求 session 无存活绑定时，执行 ②⑤ 的只读版本（无锚行写、无 create、无 probe）：

- 计数侧**行龄过滤**：`expires_at < NOW()` 的过期行与陈旧 PENDING 行不计入「满」判定（读侧过滤，不写）——否则死绑定会触发假 409，而 exec 时 ⑤a 懒剔除后本可成功；
- busy 判定与 ⑤c 同源（双事实源，读侧）；若配额满且全部绑定有执行中迹象，直接返回：

```
HTTP 409
{ "code": "user_quota_exhausted", "message": "沙箱配额已满（5/5）且均不可复用",
  "quota": 5, "bindings": [ {"session_id": "...", "expires_at": "...",
                             "state": "executing | confirm_holding"}, ... ] }
```

（`state` 区分「执行中」与「确认等待中（保留窗内，含剩余保留时间）」，前端配额错误卡据此提示不同的等待/操作建议。）

放行（配额未满或存在空闲）则正常建流；绑定仍惰性（首次 exec 才真正 create/复用），exec 时的 ②⑤ 全量裁决是最终裁决。预检本身 fail-open（存储异常放行）。**边界明示**：A2A / Agent Protocol 链路不经 `/threads/chat`（JSON-RPC 独立入口），预检天然失位——member 撞配额只能在 exec 时以 `user_quota_exhausted` SSE/错误面抛出（§4.6），与基线「无预检」行为一致，非回归。

### 4.4 TTL 生命周期（默认 30 分钟）

复用既有键 `SANDBOX_TIMEOUT_MINUTES`（基线默认 60 → **新默认 30**），语义从「创建时一次性超时」升级为「生命周期 TTL，随 turn 刷新」：

| 触发点 | 时机 | 动作 |
|---|---|---|
| T1 创建 | 借出决策 ③ create | `timeout = TTL×60` 秒（上游下限 60s，`TTL_MINUTES ≥ 1` 启动校验） |
| T2 turn 收尾 | turn 终态（TurnFinalizer：正常收尾/出错/被拒）注销 turn 注册并终刷；per-call `stop()`（KV/记忆回写）仅 refcount-1 | `sandbox.renew(TTL)` + `UPDATE binding.expires_at`（服务端响应值） |
| T3 在途续期 | **turn 执行期全程**（不止 doExec），注册式异步续期，见下 | 单线程守护线程每 `SANDBOX_RENEW_INTERVAL_SECONDS`（默认 60s）扫注册表 renew |
| T4 挂起保留（confirm hold） | HITL 挂起期（ask 后未决），`SANDBOX_CONFIRM_HOLD_MINUTES`（默认 20min）窗口内 | 挂起项带 `holdUntil` 留在注册表，守护线程**继续续期**（同沙箱恢复的保障，见下）；窗口过后停续注销 |

**T3 注册式异步续期（覆盖 LLM-only 长轮次）**：续期不能只挂在 doExec 上——一个 turn 的前段可能是大量 LLM 请求而尚未执行任何命令（长推理/多轮工具规划），若只在 doExec 续期，沙箱可能在「LLM 循环」阶段就到期，首个命令落地即失败。改为**注册表驱动、双信号生命周期**（评审修订：chat 多 call 轮次与 A2A 链路统一覆盖，防提前注销与注册泄漏两个方向的坑）：

- **注册（两个信号，任一存在即续期）**：
  - **call 引用计数**：acquire 成功（守卫进入同线程）refcount+1，`OpenSandbox.stop()`（SDK per-call 通用收尾路径，chat/A2A/AG-UI/background spawn 全链路必经）refcount-1 并顺手终刷——覆盖 A2A 等**不经 TurnFinalizer** 的链路，以及 turn 内多 call 轮次的间隙（LLM 循环期 refcount 可能短暂归零，靠 turn 信号兜住）；
  - **turn 注册**：chat/AG-UI 链路 turn 开始时注册，turn 终态（`TurnFinalizer.endTurn`，正常/出错/被拒）注销并终刷（= T2）——覆盖同一 turn 内多次 call 之间的 LLM 循环间隙；**注销只挂 turn 终态，不挂 per-call stop()**（一个 turn 可能多 call，per-call 注销会提前停续期）；
- **续期**：单线程守护线程每 `SANDBOX_RENEW_INTERVAL_SECONDS` 扫注册表，对「refcount>0 或 turn 注册中」的条目 `sandbox.renew(TTL)` + `UPDATE binding.expires_at/updated_at`（服务端响应绝对值）——该 UPDATE 即 §4.2 busy 判定的第二事实源；守护线程暴露循环滞后指标（滞后 = 全集群 busy 判定退化的前置告警）；
- **超龄剔除（防挂死泄漏）**：注册项带 bornAt，超过 `max(TTL×2, 2h)`（可配）强制剔除并 ERROR 告警——SSE 取消后 SDK 不推进、终态永不来（TurnLeaseGuard 硬寿命的 D7 动机场景）时，注册项不得无限续期把沙箱变成「永不过期 + 永不可抢占」；同时暴露注册表尺寸/最老条目年龄指标；
- **HITL 挂起 = 保留窗保护，confirm 恢复必须同沙箱**（用户评审修订，取代「挂起即注销、无保留保证」）：挂起恢复若落到别的沙箱，turn 中已生成的非托管工作区文件（生成物/pip install/clone）全部丢失——比多等一个空闲沙箱严重得多。机制：
  - **状态可判定**：待 confirm = 存在未决 `confirm_context` 行（V1 基线表，V7 起 `(session_id, confirm_key)` 多行 FIFO，`created_at` 即挂起时刻）——确定性 SQL 查询，无需推断；
  - **保留窗**：confirm ask 挂起时注册项**不注销**，改标 `holdUntil = now + SANDBOX_CONFIRM_HOLD_MINUTES`（默认 20min）；守护线程对 hold 未到期的项**继续每拍续期**——续期即保护：`updated_at` 持续新鲜 → §4.2 busy 判定自然视为「不可调度」，LRU 绝不选它，且沙箱不会在等待中到期；
  - **过期可调度**：holdUntil 过后停续注销 → `updated_at` 超过 grace 变 idle → 可被同 user 新会话 LRU 复用，或 TTL 到期自然 GC——等待无上限泄漏被窗口封死；
  - **恢复路径**：窗口内 confirm → ① 命中（绑定与沙箱原样）→ **同沙箱续跑，文件无损**；窗口外 confirm → 卡片仍可点（无硬拒），恢复时沙箱可能已换代 → ① miss/resume 归属校验拦截 → 重建 + 换代提示帧（「沙箱已重建，未保存的工作区文件可能丢失」）；同一 turn 连续多次 ask，每次挂起重新计窗；
  - **代价明示**：保留窗内该沙箱占用配额且不可复用——同 user 5 个会话全部挂起等 confirm 时，新会话 409（bindings 明细分 `state: executing | confirm_holding`，前端卡片可区分「执行中/确认等待中」）；20min 窗口默认值在「文件保留收益」与「配额锁定时长」间取衡，可 env 调整。hold 项同样受 bornAt 超龄剔除保护（20min ≪ max(TTL×2, 2h)，无冲突）。
- **崩溃语义**：Pod 死 → 注册表随进程消失 → 续期停止 → 沙箱 TTL 到期服务端 GC——注册表是**可丢弃的本地缓存**（架构原则二），无重建依赖；
- **renew 失败分类**（评审修订：不能只分「网络异常」）：
  - **404/410**（沙箱已被 ttl_expiry GC 或运维删除）→ 从注册表剔除 + 懒剔除绑定行（提前释放配额，不等下次 acquire）；turn 内下条命令失败报错，**重建发生在下一 turn**；
  - **非严格更晚 / 409**（同秒幂等）→ 忽略；
  - **网络异常** → WARN + 下拍重试；连续 N 次（默认 3）失败 → 事件告警，且该绑定**暂不参与 LRU victim 选取**（保守：续期失败≠沙箱已死，误抢引发真实互踩），直到恢复或自然到期。

- **到期即服务端 GC**（ttl_expiry）→ 该会话下次 acquire 走 ① probe 404 → 懒剔除 → 重建（配额内）——等价于被回滚代码的 P4 降级语义，无需后台扫描任务；
- **执行中 turn 永不过期的保障** = T3：长程任务（LLM-only 长轮次、30min+ 的构建命令、A2A 远程子 agent 长任务）由注册式续期持续后移 expiresAt。

### 4.5 复用与隔离

- LRU 复用**仅限同 user**（跨 user 绝不复用——userKey 来自 session_user 反查，与 KV 隔离同源）；
- 同 user 跨 session 复用继承容器（含工作区文件残留），残留范围限同一用户，接受并在此明示（与被回滚 D17 粘滞继承同语义）；
- `SANDBOX_POOL_REF` 为空（默认）→ create 不带 extensions.poolRef，走普通创建；**配额与 TTL 逻辑不依赖池**，池只是加速——未部署池的平台/服务行为完整可用（兼容存量与跨集群导出场景）。

### 4.6 异常面

| 场景 | 形态 |
|---|---|
| exec 时配额满且全忙 | SSE `error` 事件帧：`{code: "user_quota_exhausted", quota, bindings: [{session_id, expires_at, turn_running}]}`（对齐被回滚 e2e P6 的可感知口径） |
| chat 预检同场景 | HTTP 409，响应体同构（§4.3） |
| 池耗尽（poolRef 指向的池满） | create 超时/429 → turn 报错，错误信息透出上游 `PoolCapacityExhausted`；**不做静默降级直建**（静默降级会掩盖池容量配置错误；运维侧靠 §6 水位告警扩容）——决议可在评审时复议 |
| MySQL 故障 | fail-open 直建（§4.2），60s 限流 WARN |
| 沙箱到期/被外部销毁 | 下次 acquire 404 → 懒剔除重建，对 turn 透明 |

### 4.7 配置（.env.example / application.yml / AGENTS.md 增量）

| env | 默认 | 说明 |
|---|---|---|
| `SANDBOX_LIFECYCLE_ENABLED` | `true` | 总开关；`false` = 配额/绑定/续期全部旁路，完全回到回滚后基线行为（一键回退） |
| `SANDBOX_POOL_REF` | 空 | OpenSandbox Pool 名；空 = 不用服务端池（create 不带 poolRef） |
| `SANDBOX_USER_QUOTA` | `5` | 单用户沙箱配额（per-service）；`0` = 不限（仅关配额，TTL 行为保留） |
| `SANDBOX_TIMEOUT_MINUTES` | `30`（基线 60） | 语义升级为生命周期 TTL（T1~T3 刷新） |
| `SANDBOX_RENEW_INTERVAL_SECONDS` | `60` | T3 在途续期间隔；约束 `< TTL×60 / 2` 启动校验 |
| `SANDBOX_CONFIRM_HOLD_MINUTES` | `20` | HITL 挂起保留窗（§4.4 T4）：窗口内挂起沙箱持续续期、不可被 LRU 调度，confirm 恢复同沙箱；过期停续 → 可调度/自然到期 |

绑定链沿用被回滚代码验证过的模式：`application.yml` `agent.sandbox.lifecycle.*` 占位符段直传 env（`SystemEnvironmentPropertyMapper` 只映射顶层键，占位符段是 env 直传唯一通路）+ `@ConstructorBinding` null 归一。**默认开启**（新语义即平台默认行为）；存量已发布服务 env 固化不含新键 → 取框架默认值，自动获得配额与 TTL 行为，显式 env 覆盖优先。

### 4.8 debug 页

被回滚的池视图（水位条/账本明细）随回滚消失；`static/debug/modules/sandbox.js` 新增「配额与绑定」区块（fail-soft）：

- 配置回显：lifecycle enabled / poolRef / quota / TTL / renew 间隔；
- 绑定表（集群级，读 sandbox_binding）：user_key（脱敏）/ session_id / sandbox_id / expires_at 倒计时 / `turn_running`（§4.2 双事实源派生：turn_lease JOIN + updated_at 新鲜度）；
- 续期注册表健康（本副本）：注册数 / 最老条目年龄 / 守护线程循环滞后——「沙箱到期重建类」问题的第一排障入口；
- 事件环形缓冲（100 条，进程内可丢弃缓存，带 sandbox_id 便于跨副本对账）：借出 / LRU 复用 / 懒剔除 / **renew 失败（分类）** / **MySQL fail-open 触发**——后两者是评审指出的排障盲区，必须可观测（只 WARN 不进事件流会让「续期失败→到期重建」无法归因）；
- debug 端点不可用时 fail-soft 只提示（沿用被回滚页面的纪律）。

后端：`DebugApiController` 增 `GET /debug/sandbox-bindings`（跨副本聚合绑定表 + 本副本注册表健康与事件）。

### 4.9 改动文件清单（相对回滚后基线）

| 类型 | 文件 | 内容 |
|---|---|---|
| 新增 | `db/migration/V15__sandbox_binding.sql` | §4.1 两表 |
| 新增 | `sandbox/opensandbox/SandboxBindingStore.java` | 占额 CAS/绑定 CRUD/懒剔除（条件 DELETE 守卫）/LRU 选取（事务模板，改绑含 updated_at 快照乐观校验，§4.2 ⑤c） |
| 新增 | `sandbox/opensandbox/SandboxRenewalRegistry.java` | 进程内续期注册表（双信号：call refcount + turn 注册 + 挂起 holdUntil 保留窗）+ 守护续期/超龄剔除/滞后指标（§4.4 T3/T4） |
| 新增 | `sandbox/opensandbox/SandboxLifecycleManager.java` | acquire 决策编排、事件环形缓冲（体量预估 < 400 行，对比被回滚的 1695 行） |
| 新增 | `sandbox/opensandbox/UserQuotaExhaustedException.java` | 异常 + SSE/409 载荷组装 |
| 修改 | `OpenSandboxClient.java` | create 透传 extensions.poolRef / metadata / timeout；**resume binding 归属校验+占位**（connect 后条件写 `UPDATE … SET updated_at=NOW(3) WHERE sandbox_id AND session_id`，affected=0 → close + 降级本决策，§4.2 两道防线第 2 条/cold-R1-1）；P4 与校验占位 0 行路由到 LifecycleManager |
| 修改 | `OpenSandbox.java` | stop() 收尾 refcount-1 + 终刷（per-call 通用路径，chat/A2A 全覆盖）；doExec 不再需要专属续期调度（T3 由注册表覆盖） |
| 修改 | `OpenSandboxFilesystemSpec.java` / `SandboxUserKeyMiddleware.java` | pendingSessionId/pendingUserKey 捕获（沿用被回滚验证的注入点） |
| 修改 | `AgentRuntimeService.java` / turn 终态链路（TurnFinalizer） | turn 开始/终态/挂起/恢复 挂钩注册表（注册/注销/重注册；注销只挂终态不挂 per-call） |
| 修改 | `SandboxLocator.java` | locate 直连（`skipHealthCheck(true)`，env-update 链路）后同规则 binding 归属校验+占位（条件写，§4.2 两道防线第 2 条；终审 d1：取代原「槽位墓碑」方案——`SessionSandboxStateStore` 底层 `MysqlAgentStateStore` 自管连接无法并入锚行事务、slot 键为 harness 私有格式，见 §13 终审） |
| 修改 | `ChatStreamController.java` | chat 入口预检（§4.3） |
| 修改 | `SessionCleanupService.java` | §4.1.2 绑定/锚表定时清理 + 计数重算 |
| 修改 | `SandboxConfig.java` / `AgentScopeConfig.java` / `application.yml` / `.env.example` / `AGENTS.md` | §4.7 配置与装配 |
| 修改 | `DebugApiController.java` + `static/debug/modules/sandbox.js` | §4.8（绑定表 turn_running 由 JOIN turn_lease 派生） |

### 4.10 回退方案

`SANDBOX_LIFECYCLE_ENABLED=false` 即逐分支回到回滚后基线（env 开关先行，如需物理移除再出清理 commit——与被回滚 PR #276 §9 回退策略同构）。绑定表不回删（无副作用，Flyway 纪律禁改已合并 V 文件）。

---

## 5. backend 设计

### 5.1 OpenSandboxProxy service

- 新文件 `backend/internal/service/opensandboxproxy.go`：HTTP client 复用 `modelconn.go` 的 `httpGetJSON/httpJSON` 模式 + `OPEN-SANDBOX-API-KEY` header 注入 + `httpclient.go` 的超时/限读纪律（GET 5s、管理操作 10s）；
- 配置事实源 = `platform_config`（`GetPlatformDefaults()` 读 `OPENSANDBOX_SERVER_URL / OPENSANDBOX_API_KEY` 明文，`platformconfig.go`）；任一缺失 → `ErrSandboxNotConfigured` → 503，message 提示先在 `/settings` 配置；
- **无状态代理**：不做缓存、不做后台同步、无 ticker（控制面红线：启动路径与后台任务不碰业务基础设施的写操作；管理操作全部由显式 API 触发）；
- 上游错误映射：501 `K8S_POOL_NOT_SUPPORTED` → 503（message 说明 opensandbox 需 kubernetes runtime 部署）；401 `INVALID_API_KEY` → 502（配置错误，指向 /settings）；429/5xx 原样透传。

### 5.2 admin API 契约（`/api/v1/admin/sandboxes*`，RequireAdmin）

| 方法 | 路径 | 请求 | 上游转发 |
|---|---|---|---|
| GET | `/api/v1/admin/sandboxes/summary` | — | `GET /v1/pools` + `GET /v1/sandboxes?limit=` 聚合 |
| GET | `/api/v1/admin/sandboxes/pools` | — | `GET /v1/pools` |
| POST | `/api/v1/admin/sandboxes/pools` | `{name, image?, capacitySpec{bufferMin,bufferMax,poolMin,poolMax}}`（`recycleStrategy` 收而**不转发**：上游 CreatePoolRequest 仅 name/template/capacitySpec（schema.py 实证），入口仅做 Delete/Restart/Noop 白名单校验、池行为取 CRD 默认 Delete） | `POST /v1/pools` |
| GET | `/api/v1/admin/sandboxes/pools/:name` | — | `GET /v1/pools/{name}` |
| PUT | `/api/v1/admin/sandboxes/pools/:name` | `{capacitySpec{...}}` | `PUT /v1/pools/{name}`（仅容量） |
| DELETE | `/api/v1/admin/sandboxes/pools/:name` | — | `DELETE /v1/pools/{name}` |
| GET | `/api/v1/admin/sandboxes` | `?state=&limit=&offset=` | `GET /v1/sandboxes` |
| DELETE | `/api/v1/admin/sandboxes/:id` | — | `DELETE /v1/sandboxes/{id}` |

- 创建池的 **Pod 模板由后端预设常量生成**（从上游官方 Pool sample 派生：initContainers 装 execd/task-executor + 主容器 code-interpreter），`image` 缺省取平台默认 `SANDBOX_IMAGE`（platform_config），不接收自由 PodTemplateSpec JSON（避免任意 Pod spec 注入面）；
- 参数校验：name 合法 DNS label、capacitySpec 四值 0 ≤ poolMin ≤ bufferMin ≤ bufferMax ≤ poolMax、recycleStrategy ∈ {Delete, Restart, Noop}（收而不转发，见上表）；
- 单测：httptest 模拟上游（含 501/401/429 分支）。

### 5.3 console 只读（用户侧）

`GET /api/v1/console/workspaces/:ws/sandboxes`（JWT + resolveWorkspace）：

- 取该 ws 全部服务的 k8s 名（既有 workspace service 聚合），转发 `GET /v1/sandboxes` 后按 `metadata.service ∈ {服务名集合}` 过滤（框架 create 时注入的 metadata，§4.2 ③）；
- 只读：id / state / metadata.service / metadata.session_id / expiresAt / createdAt；无任何操作端点。

### 5.4 合规声明

无新表（AutoMigrate 清单不变）、无后台任务、无对业务 K8s 资源的写操作；对 opensandbox 的写仅来自显式 admin API。架构强约束原则一/二逐条核对通过。

---

## 6. frontend 设计

### 6.1 运维侧 `/admin/sandboxes`

- 导航：`(admin)/layout.tsx` LINKS 增「沙箱管理」；
- 页面 `src/app/(admin)/admin/sandboxes/page.tsx`（样板：`admin/page.tsx` 轮询 + `admin/settings/page.tsx` 对话框 + `console/assets/sandbox/page.tsx` 表格）：
  - 顶部 StatCard：池总数 / warm available / allocated / Running 沙箱数（`/summary`）；
  - **池卡片区**：每池 `capacitySpec` vs `status` 水位条（`available` < `bufferMin` 标黄 = 预热不足，`allocated` = `poolMax` 标红 = 池满）+ 操作：调整容量（PUT，四值表单）、删除（确认对话，提示会终止 warm Pod）、创建池（名称/镜像（预填平台 SANDBOX_IMAGE）/容量四值；recycleStrategy 不暴露——非上游 API 字段，见 §5.2）；
  - **沙箱表格**：id / state / metadata.service / metadata.session_id / expiresAt 倒计时 / createdAt / 操作：销毁（确认对话）；
  - 5s 轮询 + 手动刷新；
- API client：`frontend/src/lib/api.ts` 增 `api.admin.sandboxes.{summary,listPools,createPool,getPool,updatePool,deletePool,listSandboxes,deleteSandbox}`。

### 6.2 用户侧 console「沙箱实例」

- 导航：`(console)/layout.tsx` NAV「资产管理」组增「沙箱实例」；
- 页面 `src/app/(console)/console/assets/sandbox-instances/page.tsx`：本工作空间各服务沙箱只读表（state / 所属服务 / session / expiresAt 倒计时），`useConsoleWs()` + `api.console.sandboxes.list`；
- 不提供任何操作（用户侧只读，治理动作走运维侧/资源自身生命周期）。

### 6.3 ChatPane 配额异常配套（评审 P0 修订：无此则「配额满」体验净变差）

「配额满且全忙」是基线不存在的新失败面，必须以等量 UX 补偿。现状缺口：`ChatPane.tsx` 对非 2xx 直接 `throw new Error(\`HTTP ${resp.status}\`)` **丢弃 409 响应体**，error 帧只读 `ev.error` 字符串——`{code, quota, bindings}` 结构化载荷无法呈现。改动（PR-D）：

- **409 预检响应**：渲染专属错误卡（非通用报错）——列出该用户全部占用会话（会话标识 / expires_at 倒计时 / `state`：执行中或确认等待中（含保留窗剩余时间），等待中的会话提示「等确认到期自动释放，或停止该会话」），提供「停止该会话释放配额」按钮（复用既有 `POST /threads/{sid}/stop`，仅限本人会话）；**用户输入保留在输入框**（409 时消息未建流，不得丢字）；
- **SSE error 帧**（exec 时撞配额，A2A/竞态兜底路径同样到达）：`code=user_quota_exhausted` 时复用同一张卡渲染 `bindings` 明细；
- 会话标识展示用既有 thread 标题解析（`/threads` 列表同源），不可见会话（已被清理）只显示时间与状态。

---

## 7. platformconfig 模板增量

`backend/internal/service/platformconfig/template.go` sandbox 组新增 3 键（非敏感、发布预填语义，R3：不经 envFrom 注入、不影响已发布服务）：

| 键 | 预填默认 | 说明 |
|---|---|---|
| `SANDBOX_POOL_REF` | 空 | 发布向导/编辑 env 表单预填 |
| `SANDBOX_USER_QUOTA` | `5` | 同上 |
| `SANDBOX_RENEW_INTERVAL_SECONDS` | `60` | 同上 |

（`SANDBOX_TIMEOUT_MINUTES` 已在模板中，默认值含义随 §4.7 升级：**placeholder「60」同步改「30」并更新说明**（`template.go`），否则表单预填误导；`SANDBOX_ENABLED` 仍刻意排除——保护 OAF 包 frontmatter 三层裁决。）同步 `service/workspaceruntime.go` 的 `runtimeDefaultKeys`（导出侧第二事实源，见 cross-cluster-export-design）——**注意 `workspaceruntime_test.go` 锁定了键数量（32→35），实施时必须同一 PR 修改该测试**。

---

## 8. kind 部署（scripts/deploy-opensandbox.sh + docs/deployment.md 章节）

脚本 `scripts/deploy-opensandbox.sh`（幂等可重跑）：

1. **镜像**：走本地代理（`http://127.0.0.1:7890`）`docker pull` 最小集（server/controller/ingress + 池 Pod 三件套，tag 从 release BOM `docs/releases/1.1.0.yaml` 解析）→ `kind load docker-image`；
2. **charts**：`git clone --depth 1 --branch release-1.1.0` 上游仓库（走代理）→ 按序 `helm install`：base → controller → ingress-gateway → server，ns `opensandbox-system`（workload ns `opensandbox`）；
3. **server 配置**：`runtime.type=kubernetes`、workload namespace、`sandbox_create_timeout_seconds`、API key（`kubectl create secret generic opensandbox-api-key`，值取平台约定占位/随机生成并回填 platform_config 提示）；SQLite 持久化（POC 规模足够，PG 留升级项）；
4. **自检**：`kubectl wait` 全部 Deployment Available + `curl /health`；
5. 输出「业务服务接入指引」：`OPENSANDBOX_SERVER_URL=<server svc>:<port>` + API key 回填 /settings。

`docs/deployment.md` 增「OpenSandbox 部署与池初始化」章节（脚本用法 + 创建首个池的 curl/页面步骤 + 业务服务 env 示例）。

### 8.1 五项验收（kind 实测，结果回填 §11）

| # | 验收项 | 通过标准 |
|---|---|---|
| V1 | 预热 | `POST /v1/pools`（bufferMin=1）→ 轮询 `GET /v1/pools/{name}` 至 `status.available ≥ 1`（warm Pod Running/Ready） |
| V2 | 认领（**升格为口径对账项**） | `POST /v1/sandboxes` + `extensions.poolRef` → Running；`endpoints/44772` 代理执行命令成功；**记录池认领 vs 无池直建两组耗时**。⚠️ 本仓 2026-11-05 的上游核对（git `e11cadd`，sandbox-pool-design.md §5.1）结论是「poolRef 创建的仍是全新容器，只省调度/拉镜像」，与 release-1.1.0 文档「常数时间认领 warm Pod」表述存在口径差——V2 必须实测钉死认领语义（认领后是否仍有完整容器启动+execd boot），若与旧结论一致则回改 §1.2/§2.2 表述并下调 warm 池收益预期（预热收益=省调度+拉镜像，首命令延迟仍为秒级~十几秒）。**实测结论（2026-11-07，§11 V2）**：与 §1.2/§2.2 一致（认领 warm Pod 本体、认领即 Running），无需回改口径、无需下调收益预期；「秒级~十几秒 boot」在 execd 常驻模板下未观测到（接近零 boot），冷镜像预热收益未量化 |
| V3 | 续期 | `POST /v1/sandboxes/{id}/renew-expiration` → `GET` 中 expiresAt 后移 30min |
| V4 | 回收 | `DELETE` 沙箱 → Pod 回收、池 `available` 回补至 buffer 带（recycleStrategy=Delete 换新 warm Pod） |
| V5 | 池满 | poolMax 压小后并发创建超额沙箱 → `PoolCapacityExhausted`/429 可观测，扩容（PUT 容量）后自动恢复 |

### 8.2 kind 联调全场景（V6~V8，PR-E 验收）

> **V6⑧ 换代提示帧 live 编排配方（第 2 轮后钉死）**：帧的确定性触达路径 = 「绑定行已删 +
> 沙箱已 GC」的 confirm 恢复（P3 resume connect 404 → markSandboxRebuilt 登记 → 降级 create
> → 流内 emit）。编排步骤：① 会话 A 执行一轮并记 sandboxId S；② 触发 S 的 GC——`/admin/destroy`
> 可用（Destroy 后 P3 resume 会走 connect 404 同路径），或等 TTL 过期 + renew-404 sweep
> 懒剔除（轮询 /debug/sandbox-bindings 直到 bindingOf(A) 消失）；③ A 发起 confirm-stream（或普通
> chat 续轮）→ P3 connect 404 → 降级 create → 流首事件前 emit `sandbox_rebuilt` 帧；④ 观测：SSE
> 帧直接断言，或轮询 /debug/sandbox-bindings events 看 `sandbox_rebuilt_registered`（登记先行事件，
> 第 2 轮新增——帧在流内一次性消费、时序敏感不可轮询，登记事件让 live 编排可先确认「已登记」再看流）。
> 不应选用的路径：「行在但归属他会话」需精确的双会话抢跑时序，不可靠；「confirm 期间配额满」会让
> confirm 直接 error（⑤ 重建失败，无帧可言——先释放配额再 confirm）。

前置：§8.1 上游五项全过。发布 demo agent 两套参数：标准（`QUOTA=5/TTL=30min/HOLD=20min`）与加速变体（`QUOTA=3/TTL=5min/HOLD=2min`，缩短等待类步骤）。

| # | 场景 | 步骤 | 通过标准 |
|---|---|---|---|
| V6 | 全链路功能（加速变体） | ① chat 建会话 A 触发 exec → 首沙箱来自池（记录认领耗时，对比直建）；② 同 user 并行 3 会话各触发 exec；③ 第 4 会话 chat → 409 预检 + 前端错误卡（bindings 3 条、state 正确）；④ 卡片「停止会话」→ 配额即时释放；⑤ 第 4 会话重试 → LRU 复用（debug 端点核对 sandboxId 继承）；⑥ 长命令 >TTL 执行全程无到期中断（`GET /v1/sandboxes/{id}` expiresAt 持续后移）；⑦ 会话 ask 挂起 → 保留窗内 confirm → **同 sandboxId 且工作区生成文件在**；⑧ 保留窗过期 → 新会话抢占 → 过期 confirm → 新 sandboxId + 换代提示帧；⑨ 全程 debug 绑定表/事件流 + admin 管理页 + console 只读页三方数据一致 | 每步断言全过 |
| V7 | 回退开关 | demo agent 设 `SANDBOX_LIFECYCLE_ENABLED=false` republish → 超配额并发不 409、TTL 不随 turn 刷新 | 行为 = 回滚后基线 |
| V8 | 存量影响冒烟 | 集群内既有已发布服务（无新 env）对话一轮正常；显式 `SANDBOX_TIMEOUT_MINUTES=60` 的服务核对 TTL 语义变化并出公告 | 服务全部可用、公告已发 |

证据形式：命令输出/HTTP 响应摘录/页面截图，回填 §11。

> **实施注记（2026-11-08，m12 承载口径）**：m12-sandbox-admin.sh 为 **API 级断言**（本节步骤
> 1~6 的载荷/状态码/审计），「水位条渲染/页面错误提示/引导文案」等页面级断言不在 m12 承载
> （console-ui-e2e.js 不访问 /admin/sandboxes）——由部署验证的黑盒走查（puppeteer 截图）与
> 人工巡检覆盖；后续若纳入 UI e2e 再扩展。

---

## 9. 验证与门禁（全流程全分支覆盖）

### 9.1 单测矩阵（分支枚举即 DoD——每个枚举分支至少 1 用例，新增类行覆盖 ≥ 90%）

**U1 `SandboxBindingStoreTest`（占额 CAS + PENDING 不变量 + 懒剔除/改绑/清理）**

| 分支 | 用例 |
|---|---|
| ② CAS | `count=quota-1` 占额成功且同事务落 PENDING 行；`count=quota` 失败；首见用户（无锚行）INSERT IGNORE 后重试成功 |
| ③ 转正/补偿 | create 成功 → PENDING 行 UPDATE 真实 sandbox_id + 服务端 expiresAt；create 失败 → DELETE PENDING + count-1；转正撞 `uk_sandbox` → 走补偿 |
| ⑤a 懒剔除 | 过期行删；probe 404 行删（守卫 `session_id/updated_at 未变` 命中）；守卫不命中（已被他副本改绑）→ affected=0 跳过；probe 网络异常 → 保守不删；陈旧 PENDING（>10min）回收、新 PENDING 不回收 |
| ⑤b 重算 | 锚行锁内 `count := COUNT(存活行)`，在途 PENDING 计入（不吞额） |
| ⑤c victim | 取 `expires_at` 最小者；排除 busy（注入 `isHeldStrict=true` / `updated_at` 新鲜 / 恰好 grace 边界三态）；改绑条件 UPDATE（含 updated_at 快照乐观校验）affected=1/0 两分支；乐观校验拦截场景：快照读取后、改绑前受害行 updated_at 被并发 touch（① 或 P3 占位写）刷新 → affected=0 → ⑤ 重试（终审 d2）；**不验证 SlotStore 交互**（终审 d1：无槽位墓碑写入，归属失效改由 resume 归属校验+占位条件写，见 U4） |
| ① touch | 复用命中时 UPDATE updated_at/expires_at（先于 probe/connect）；affected=0（已被改绑/懒剔除）→ 放弃复用、视同 miss 走 ②；probe 404 → 删自己行 + count-1（终审 d2） |
| §4.1.2 清理 | 保留窗外行删 + count 分组重算 + 空锚行删；重跑幂等 |

**U2 `SandboxRenewalRegistryTest`（双信号 + hold + 超龄 + 失败分类）**

| 分支 | 用例 |
|---|---|
| call refcount | acquire++ / stop()-- 归零；归零但 turn 注册仍在 → 续期不中断（chat 多 call 间隙） |
| turn 生命周期 | 注册 / 终态注销+终刷（正常、出错、被拒三入口）；挂起设 `holdUntil`；hold 内续期、过期停续注销；confirm 恢复重注册；同 turn 多次 ask 重新计窗 |
| 超龄剔除 | bornAt > max(TTL×2, 2h)（时钟注入）→ 剔除 + ERROR；指标（尺寸/最老年龄/循环滞后）可读 |
| renew 失败分类 | 404/410 → 剔注册 + 绑定懒剔除；409/同秒 → 幂等忽略；网络异常 → WARN 下拍重试；连续 3 次 → 标记暂不参与 victim + 告警 |
| 崩溃 | 进程重启（新建实例）→ 注册表空 → 不再续期 |

**U3 `SandboxLifecycleManagerTest`（决策编排全分支）**

| 分支 | 用例 |
|---|---|
| acquire 主干 | ① 命中 touch（先于 probe/connect）→ probe 200 复用；① touch affected=0 → 放弃复用重走决策（不 connect）；① miss + 配额内 → create（断言 extensions.poolRef/metadata/timeout=TTL×60）+ 绑定落库；① 命中但 probe 404 → 懒剔除 → 重建 |
| 配额满 | idle 存在 → LRU 改绑（updated_at 快照乐观校验）+ renew + 服务端 expiresAt；全忙 → `UserQuotaExhaustedException` 载荷（quota/bindings/state 三字段）；busy 双事实源四态（lease 持有 / updated_at 新鲜 / A2A 无 lease 靠 updated_at / 双假） |
| 并发/重试 | 改绑 affected=0（他副本抢先 **或受害在途 ① touch 先行提交**）→ ⑤ 重试 ≤2 次后仍失败按全忙处理；touch 与改绑 affected 互斥（并发序：touch 先提交 → 改绑 affected=0；改绑先提交 → touch affected=0，任一序至多一方成功，终审 d2） |
| fail-open | store 抛 DataAccessException → 直建不记账 + 60s 限流（窗口内第二次不重复 WARN） |
| 预检 | 满+全忙 → 409；死绑定行龄过滤（过期行不计入 → 放行，假 409 修正）；fail-open 放行；userKey 在 `session_user.upsert` 之后解析（顺序 verify） |
| 池耗尽 | create 429/`PoolCapacityExhausted` → 异常面透出 + 占额补偿 |
| 启动校验 | `TTL_MINUTES < 1`、`RENEW_INTERVAL ≥ TTL×60/2` → fail-fast |

**U4 `OpenSandboxClientTest`（扩展既有）**：poolRef 传/不传两分支、metadata 注入、timeout 换算；**P3 resume 归属校验+占位三分支（终审 d1，v6 cold-R1-1 升级）**：归属匹配 → 条件写 affected=1（**P3 通过即 touch**：verify UPDATE 载荷 `SET updated_at=NOW(3) WHERE sandbox_id AND session_id`）→ 放行；无行/归他会话/漂移 → 条件写 affected=0 → close 句柄 + 降级异常 → 路由 LifecycleManager（verify close 被调）；校验写异常（DataAccessException）→ 放行 + WARN（fail-open）；P4 → 路由 LifecycleManager（verify）；新配置绑定链（env → application.yml 占位符 → 构造器 null 归一）。`SandboxLocatorTest` 同规则三分支（env-update 链路）。

**U5 backend 单测（httptest 模拟上游）**：proxy 七端点 happy path（方法/路径/body 透传断言）；错误映射矩阵（501→503 提示、401→502 指向 /settings、429/5xx 透传、上游超时）；URL/APIKey 缺失 → 503；createPool 校验（name DNS label、`0≤poolMin≤bufferMin≤bufferMax≤poolMax`、recycleStrategy 枚举、非法值 400）；console 只读过滤（`metadata.service ∈ ws 服务集合`）；预设模板生成（image 缺省取平台 `SANDBOX_IMAGE`）；管理操作写审计行。

**U6 frontend 组件测试**：配额错误卡（409 body / SSE `code` 帧两入口；`state=executing|confirm_holding` 两态文案；停止会话按钮调用 `/threads/{sid}/stop`；输入保留）；池容量表单校验与水位条黄/红分支。

### 9.2 mock 扩展与故障注入（`agent-framework/e2e/mock/sandbox-server.mjs`）

- `/v1/pools` 内存实现：CRUD + `status{total,allocated,available}`（allocated 随 create(poolRef)/delete 变动）；
- `/v1/sandboxes/{id}/renew-expiration`：更新内存 expiresAt，计入 `/stats`（续期断言源）；
- create 记录请求体（poolRef/metadata 断言源，沿用 `/stats.creates` 模式）；
- **故障注入开关**（`/admin/fault` 扩展，服务 e2e 分支）：`renew404`（对已删沙箱 renew 返回 404）、`poolExhausted`（create(poolRef) 返回 429 `PoolCapacityExhausted`）、`slowCommand`/`slowLLM`（配合 mock-llm 延时标记）；
- 既有 `/admin/destroy/{id}` 保留（到期/外部 GC 模拟）。

### 9.3 e2e 场景矩阵：`e2e/tests/api-sandbox-lifecycle.spec.ts`（E2E 组 **lifecycle**，playwright project `api-sandbox-lifecycle`）

| # | 场景 | 断言要点 |
|---|---|---|
| L1 | 配额上限 | USER_QUOTA=2，同 user 第 3 会话（前两个 turn 在途）→ SSE `user_quota_exhausted` 可感知 |
| L2 | chat 预检 | 同场景直发 `/threads/chat` → 409 + bindings 明细 |
| L3 | LRU 复用 | 占满后其中一个 turn 收尾（空闲）→ 新会话继承同一 sandboxId + renew 计数 +1 |
| L4 | T2 刷新 | turn 正常结束 → mock renew-expiration 调用断言 |
| L5 | T3 在途续期（两形态） | a) **LLM-only 长轮次**：mock LLM 延时 > 2×RENEW_INTERVAL、无任何工具调用 → renew ≥ 1 次；b) 长 exec 命令（mock sleep）→ 同样 renew ≥ 1 次 |
| L6 | poolRef 透传 | create 请求体 `extensions.poolRef` = 配置值 |
| L7 | metadata 注入 | create 请求体 `metadata.service` = 服务名 |
| L8 | 总开关回退 | `SANDBOX_LIFECYCLE_ENABLED=false` → 超配额不抛异常（行为=基线） |
| L9 | 到期重建 | `/admin/destroy/{id}` 模拟 GC → 下 turn 重建 + 新绑定（debug 端点可见） |
| L10 | debug 端点 | `/debug/sandbox-bindings` 绑定表 + turn_running（派生）正确 |
| L11 | 定时清理 | 短保留窗口 + 触发清理任务（测试钩子）→ 过期绑定行删除、`binding_count` 重算、空锚行删除 |
| L12 | 租约硬释放不误判（P0 修复回归） | JVM 参数 `-Dagent.cleanup.turn-lease-max-lifetime-minutes=1` + TTL=1min：长命令 >1min 执行中（turn_lease 已消失、靠 updated_at 判 busy）→ 同 user 新会话触发 ⑤ **不发生改绑** |
| L13 | 幽灵占额修正（PENDING 不变量） | 测试钩子注入 `binding_count` 偏大 + 陈旧 PENDING 行 → 新会话 acquire 必须成功（⑤b 重算 + PENDING 回收生效） |
| L14 | 被抢会话不重连易主沙箱 | L3 之后**原会话**再发 turn / confirm 恢复 → 断言不重连旧 sandboxId（resume 归属校验拦截 → close + 降级，终审 d1）、走新决策 |
| L15 | A2A 长任务不被抢 | A2A `message/send` 长任务执行中（无 turn_lease，靠续期 updated_at 判 busy）→ 同 user 新会话触发 ⑤ 不改绑；A2A turn 结束后 expires_at 停止后移（注册表 refcount 归零，防泄漏） |
| L16 | 注册表超龄剔除 | SSE 断连 + mock SDK 不发终态（挂死）→ 注册项超龄剔除、沙箱最终到期（不被无限续期） |
| L17 | renew 404 剔除 | mock renew-expiration 对已删沙箱返回 404 → 注册表剔除 + 绑定行懒剔除（配额即时释放） |
| L18 | 挂起保留窗（confirm 同沙箱） | a) 会话 A ask 挂起 → 保留窗内同 user 会话 B 触发 ⑤ → **不改绑**（updated_at 持续新鲜）；A confirm 恢复 → **同一 sandboxId**、工作区文件在；b) 保留窗过期后 → B 可改绑，A 过期 confirm → 新 sandboxId + 换代提示帧 |
| L19 | 同 user 继承残留（契约钉死） | LRU 复用后新会话 `ls` 可见旧会话工作区残留文件（§4.5 明示接受的行为写进断言） |
| L20 | 假 409 修正 | 预检时存在 `expires_at` 已过期死绑定（`/admin/destroy` + 短 TTL）→ 预检**放行**、exec 懒剔除后成功（§4.3 行龄过滤回归） |
| L21 | A2A 闲置重入 grace 空窗（cold-R1-1 回归） | A2A 会话 A 闲置 > grace（updated_at 陈旧、无 turn_lease）后重入（P3 直连）→ 与同用户新会话 B 触发 ⑤ 并发：或 B 改绑 affected=0（A 的 P3 占位写在先，updated_at 已新鲜）、或 A 占位 affected=0 降级重走决策（B 改绑在先）——断言沙箱 X 至多一方 exec、debug 绑定表终态一致（A 要么保有 X 要么换新）。**实施注记（2026-11-08）**：单副本变体未落地，当前仅 e2e-multi M2 真实并发变体覆盖（必需门禁）；单副本用例为后续补充项 |

执行分组：**A 借出与配额**（L1/L2/L6/L7/L8/L13/L20）→ **B TTL 与续期**（L4/L5/L15/L16/L17）→ **C 复用与抢占**（L3/L12/L14/L19/L21）→ **D HITL**（L18）→ **E 可观测与清理**（L9/L10/L11），单 spec 内顺序执行（共享 mock 状态，前组产物供后组复用）。

环境参数（`e2e/scripts/env-up.sh` 增 lifecycle 组）：`QUOTA=2 / TTL=1min / RENEW_INTERVAL=3s / POOL_REF=pool-e2e / CONFIRM_HOLD=1min`；JVM 注入 `-Dagent.cleanup.turn-lease-max-lifetime-minutes=1`（L12）与 grace 下限压低系统属性（生产下限 120s，测试压至 ~6s 加速 idle 判定，先例同 turn-lease lifetime）。

多副本并发（他副本抢先→重试、双副本并发 acquire 同 user 改绑竞态、**受害会话在途 ① 复用 vs 他副本 ⑤c 改绑的乐观校验互斥**——任一提交序至多一方 affected=1，双用不发生，终审 d2；**受害会话 P3 闲置重入占位 vs 他副本 ⑤c 改绑同款互斥**（L21 多副本变体：A2A 闲置 > grace 重入，cold-R1-1））进 **`e2e-multi` 组**追加用例（该组为必需门禁，双副本拓扑现成）；fail-open 收敛（mock MySQL 断连窗口 create 透传 + 恢复后懒剔除）以单测覆盖（U3）。

### 9.4 平台 console e2e（platform-e2e-ci 内追加场景 m12-sandbox-admin）

platform e2e 已是必需检查，场景在其内部追加（无需改分支保护）。上游用 mock：e2e 环境起 `sandbox-server.mjs`（已含 `/v1/pools`），`platform_config` 的 `OPENSANDBOX_SERVER_URL/API_KEY` 指向它。步骤与断言：

1. `/settings` 配置沙箱组（URL/APIKey/新 3 键默认值预填断言）→ 保存落 `platform_config`；
2. 运维侧 `/admin/sandboxes`：summary 聚合数、创建池（预设模板+容量）→ mock `/v1/pools` 收到正确载荷、列表出现、水位条渲染；容量调整（PUT 仅 capacitySpec）；删除池；
3. 沙箱列表（state 过滤）+ 销毁 → mock DELETE 断言；管理操作各产生一条审计记录；
4. 上游故障注入：mock 返回 501/401 → 页面错误提示（部署形态/配置错误两类文案）；
5. console 用户侧「沙箱实例」只读页：按 ws 服务过滤（mock metadata.service 造数）、无操作按钮；
6. 未配置 URL（清空 settings）→ admin API 503 + 页面引导文案。

### 9.5 CI 门禁修改

| 项 | 变更 | 说明 |
|---|---|---|
| agent-framework-ci.yml | 重建 job **`e2e-sandbox-lifecycle`**（E2E_GROUP=**lifecycle**，run.sh 据此映射 `--project=api-sandbox-lifecycle`；结构同被回滚的 e2e-sandbox-pool：`needs: [changes]` 目录过滤、mysql+redis services、mvn package、run.sh、artifact 上传） | PR-D 引入；`e2e-multi` 组内追加双副本并发用例（该 job 已是必需检查，无结构变更） |
| **分支保护（master）** | **PR-D 合并后，管理员将 `E2E 沙箱生命周期（L 组）` 加入必需状态检查**（`gh api -X PATCH repos/:owner/:repo/branches/master/protection/required_status_checks`，strict/enforce_admins 维持）——必需检查由九项变**十项**；根目录 AGENTS.md「分支保护」章节与 `agent-framework/docs/e2e-ci-plan.md` 同步更新 | 与原 e2e-sandbox-pool「初期非必需」先例不同：本设计配额/TTL **默认开启**影响所有服务，L 组必须挡合并；转必需前先以非必需跑 ≥3 个 PR 观察稳定性 |
| platform-e2e-ci | m12-sandbox-admin 场景纳入（该工作流已是必需检查） | §9.4 |
| backend-ci / frontend-ci | 无结构变更 | U5/U6 用例进入既有 `go test` / `lint+test+build` 必需门禁 |
| 单测门禁 | `mvn test`（U1~U4）/ `go vet + go test`（U5）/ `npm run lint+test+build`（U6）失败即红 | 既有 |

### 9.6 完成定义（DoD）

1. U1~U6 分支清单全绿（每个枚举分支 ≥1 用例），新增类行覆盖 ≥ 90%；
2. L1~L21 全绿 + e2e-multi 追加用例绿；
3. m12-sandbox-admin 绿；
4. kind：§8.1 V1~V5 + §8.2 V6~V8 全过并回填 §11；
5. 门禁：新 job 稳定 3 个 PR 后转必需，根 AGENTS.md 分支保护描述同步（九项→十项）。

---

## 10. PR 序列、上线与存量影响

| 序 | PR | 内容 | 依赖 |
|---|---|---|---|
| A | `revert/pr-276-sandbox-pool` | `git revert -m 1 9c81437` 整体回滚 | 无（master 在 merge 后无新提交，可干净回滚） |
| — | 集群操作 | kind 部署 opensandbox + §8.1 五项验收（记录回填本文档 §11） | A（基线干净） |
| B | docs | 本设计文档 + README 索引 + §11 回填 | 验收完成 |
| C | 平台沙箱管理 | backend 代理 API+单测（U5）、frontend 两页+配额错误卡（U6）、platformconfig 模板键（含 placeholder 60→30 与 `workspaceruntime_test` 35 键同步）、m12-sandbox-admin 场景（§9.4）、deploy 脚本+deployment.md | B 评审通过 |
| D | agent-framework | §4 全量（含 §6.3）+ 单测 U1~U4 + mock 故障注入（§9.2）+ L1~L21/e2e-multi 追加 + `e2e-sandbox-lifecycle` job（§9.5） | B 评审通过（与 C 可并行） |
| E | 联调（无 PR） | §8.2 V6~V8 全场景（demo agent 带 `SANDBOX_POOL_REF`）+ §8.1 上游五项 | C+D |
| — | 门禁转必需 | PR-D 合并且新 job 稳定 3 个 PR 后，管理员加必需检查（九项→十项）+ 根 AGENTS.md 同步（§9.5/§9.6） | E 验证过 |
| F（可选，另行立项） | D16 确认硬截止捡回 | 从 git 历史恢复 ConfirmDeadlineService/expires_at 前端倒计时，并按 §4.4 T4 补 confirm touch 续期与 `TTL ≥ AGENT_CONFIRM_DEADLINE_MINUTES` 不变量——解决「确认卡无限期可点 vs 沙箱 30min 换代」的语义缝合 | E 后按需 |

存量影响与风险：

- PR-D 合并后**框架默认即新语义**（lifecycle on / quota 5 / TTL 30min）：存量服务 env 固化不含新键 → 取框架默认自动获得配额与 TTL；不希望生效的服务显式设 `SANDBOX_LIFECYCLE_ENABLED=false`；
- **显式固化 `SANDBOX_TIMEOUT_MINUTES=60` 的存量服务语义突变（须公告）**：从「创建时一次性 60min 超时」变为「60min TTL 随 turn 持续刷新」——活跃沙箱最长存活从 60min 变为无限，资源占用模型改变，运维容量规划需知情；希望保持旧行为可设 `SANDBOX_LIFECYCLE_ENABLED=false`；
- 未配 `SANDBOX_POOL_REF` 的服务行为 = 配额 + TTL 治理、无池加速（完整可用，兼容跨集群导出）；
- 池耗尽 → turn 报错可重试（不静默降级，§4.6，可在评审复议）；
- 同 user 跨 session 复用继承容器（工作区残留限同 user，§4.5 明示，e2e L19 钉进契约）；
- admin 破坏性操作（删池/销毁沙箱）接入平台审计（`PlatformConfigEvent` 同款审计先例）；池水位越界目前依赖页面标黄/红人工巡检，告警通道为后续项。

### 存量影响公告（草稿）

> 状态：草稿要点（2026-11-07/08 V8 核对产出，随 PR-D 回填本文档）；正式公告按流程由人工审定发布。

- **适用对象**：显式固化 `SANDBOX_TIMEOUT_MINUTES=60` 的已发布服务——PR-D 合并后框架默认即新语义（lifecycle on / TTL 默认 30min），该类服务语义突变；
- **行为变化**：沙箱超时从「创建时一次性 60min 超时」变为「60min TTL 随 turn 持续刷新」（T1 create 起点 / T2 turn 收尾终刷 / T3 在途续期三点刷新）——活跃沙箱只要持续有 turn 即不断续期，**最长存活从 60min 变为无限**；空闲沙箱仍按 TTL 到期回收；
- **运维影响**：活跃沙箱资源占用模型改变，运维容量规划需知情；
- **保持旧行为**：显式设置 `SANDBOX_LIFECYCLE_ENABLED=false`（env 编辑后 republish 生效，回退路径 §11 V7 实证）；
- **现状核对（2026-11-07/08）**：集群内存量四服务 ConfigMap 核查均未显式设置 `SANDBOX_TIMEOUT_MINUTES=60` → 当前无实际受影响服务；语义变化核对基于框架配置面（`application.yml:89-91` 语义升级注释）与 V7 实测 TTL 刷新行为对照。

---

## 11. kind 验证记录（2026-11-07~08 回填，V1~V8 实测）

**部署记录**：`scripts/deploy-opensandbox.sh` 幂等执行 exit=0（完整重跑亦 exit=0：镜像/节点命中「已存在/已有」分支、helm upgrade R+1 未触发滚动，opensandbox-system pods age 不变、池 warm Pod 不受扰动）；`helm -n opensandbox-system list`：opensandbox-base(R2) / opensandbox-controller / opensandbox-ingress-gateway / opensandbox-server 四个 release chart 版本均 1.1.0；pods 全 1/1 Running（controller-manager、ingress-gateway×2、server）；CRD batchsandboxes / pools / sandboxsnapshots.sandbox.opensandbox.io 已装。自检：`curl http://127.0.0.1:18600/health` → `{"status":"healthy"}`；鉴权：无 key `GET /v1/pools` → HTTP 401，带 `OPEN-SANDBOX-API-KEY`（自 .env.secrets 读入 shell 变量，未回显）→ HTTP 200 `{"items":[]}`。已载入 `task-executor:release-1.1.0`（§2.5 最小集预防性载入，实测池路径只消费 execd，未被实际使用）。验收沙箱已全部删除；池 `oaf-pool-v1`（bufferMin=1）作为部署产物保留在 ns opensandbox，供 PR-C/PR-E 使用。

| # | 验收项 | 结果 | 证据（命令输出摘要/时间戳/截图） |
|---|---|---|---|
| V1 | 预热 | ✅ 过 | `POST /v1/pools`（body：`name=oaf-pool-v1` + template（PodTemplateSpec：主容器 `opensandbox/execd:release-1.1.0` + command `[/bootstrap.sh sleep 3600]` + env `EXECD_INIT=1`/`EXECD=/execd`，形状取自上游 e2e `tests/python/tests/support/server-pool.yaml`）+ capacitySpec `{bufferMin:1, bufferMax:2, poolMin:1, poolMax:3}`）→ HTTP **201**；首次轮询 `GET /v1/pools/oaf-pool-v1` 即返回 `status={total:1, allocated:0, available:1, revision:cf59e6628f698075}`；`kubectl -n opensandbox get pods`：`oaf-pool-v1-m7l69` 1/1 Running（warm Pod Ready） |
| V2 | 认领（含口径对账） | ✅ 过（与 §1.2/§2.2 一致，无需回改） | **poolRef 路径**：`POST /v1/sandboxes {extensions:{poolRef:oaf-pool-v1}, timeout:1800}` → HTTP 202、响应即 state=Running（调用+确认共 1.07s/1.17s）；代理执行（`POST /v1/sandboxes/{id}/proxy/44772/command {"command":"echo poolref-exec-ok && hostname"}`）SSE 返回 stdout=`poolref-exec-ok`、hostname=`oaf-pool-v1-m7l69`（= warm Pod 本体主机名）、execution_time=2ms；BatchSandbox CR `ac7b8a45…` spec.poolRef=`oaf-pool-v1`。**直建对照**：`POST /v1/sandboxes {image:{uri:opensandbox/code-interpreter:v1.1.0}, entrypoint:[tail -f /dev/null], resourceLimits:{cpu:1, memory:1Gi}, timeout:1800}` → create→Running 2.16s，hostname=`2ea59d70-5273-43fc-8db8-a8ebe5621ef0-0`（全新 Pod，kubectl 可见新建）；直建前置校验实测：缺 entrypoint → 422 `Entrypoint is required when image is provided`，缺 resourceLimits → 422 `resourceLimits is required when poolRef is not provided`（poolRef 路径两者均可省）。**结论**：认领=常数时间认领 warm Pod 本体、认领即 Running，「认领后秒级~十几秒 boot」未观测到（池模板=execd 主容器 bootstrap 常驻）。**局限**：两组耗时为空载集群+镜像全缓存的乐观值，冷镜像预热收益未量化（无冷启动对照）；业务镜像主容器模板的认领开销未单独测量 |
| V3 | 续期 | ✅ 过 | renew-expiration 前 expiresAt=`2026-11-07T14:48:37Z` → `POST /v1/sandboxes/{id}/renew-expiration {"expiresAt":"2026-11-07T15:18:37Z"}`（=当前值+30min）→ HTTP 200 → GET 确认 expiresAt=`2026-11-07T15:18:37Z`；脚本断言输出 `moved forward exactly 30 minutes -> PASS(+30min)` |
| V4 | 回收 | ✅ 过 | `DELETE /v1/sandboxes/ac7b8a45…`（认领自池、宿主 Pod `oaf-pool-v1-m7l69`）→ HTTP **204**，GET 沙箱 → 404；宿主 Pod ~3s 内消失（watch 输出 `pod oaf-pool-v1-m7l69 gone after ~3s`）；池状态 before=`{total:2, allocated:1, available:1}` → after=`{total:1, allocated:0, available:1}`，available 维持 bufferMin=1 水位（认领时 controller 已补位新 warm Pod `fvqqk`，即 recycleStrategy=Delete 的归还换新行为）。备注：recycleStrategy 非 REST 字段（`CreatePoolRequest` 仅 name/template/capacitySpec，schema.py:1041-1069），实测取 CRD 默认 Delete（`kubernetes/apis/sandbox/v1alpha1/pool_types.go:63-66` kubebuilder default）——满足 V4 语义但非显式设置 |
| V5 | 池满 | ✅ 过 | **收缩**：`PUT /v1/pools/oaf-pool-v1 {capacitySpec:{bufferMin:1, bufferMax:1, poolMin:1, poolMax:1}}` → 200；create A 认领后池=`{total:1, allocated:1, available:0}`；超额 create B → HTTP **429** `{"code":"KUBERNETES::POOL_CAPACITY_EXHAUSTED","message":"No Pool capacity became available before the acquisition timeout."}`。**扩容恢复**：PUT 扩回 `{bufferMin:1, bufferMax:2, poolMin:1, poolMax:3}` → 200；create C → HTTP 202、1s 内 Running；池自动扩 out=`{total:3, allocated:2, available:0}`（fvqqk=A、5n24t=C、c25rg=补位 creating→Running）。**列表过滤/分页（§12-4 验收项）**：`GET /v1/sandboxes?state=Running` → 3 条全 Running；`?state=Running&metadata=case%3Dv5` → 仅 A/C 两条（metadata 过滤生效）；`?state=Running&page=2&pageSize=1` → 返回第 2 条，`pagination={page:2, pageSize:1, totalItems:3, totalPages:3, hasNextPage:true}`；`?metadata=broken`（非法编码）→ HTTP 400。备注：server API 错误码为 `KUBERNETES::POOL_CAPACITY_EXHAUSTED`（§2.2/§8.1 写的 controller reason 名 `PoolCapacityExhausted` 是同一信号的两种表达）；超额请求被 server 429 直接快速失败，未在集群内观测到停留在 PoolAllocationPending 的 BatchSandbox CR（acquisition timeout 内无容量即拒） |
| V6 | 全链路功能（①~⑨ 步） | ⚠️ **部分通过**（原因=单节点 kind 内存限制，⑨三方一致/V7/V8 已 PASS；①~⑦、⑨ 全部实测过，⑧ 抢占/换代 live 实证、唯「换代提示帧 live SSE 发出面」未观测） | **环境**：三服务 oaf-v6-demo-{fast,direct,std}（pkgId 72/73/74，镜像 `172.20.0.1:5001/agent-framework:sandbox-pool-pipeline` 含 PR-D 生命周期代码；fast=QUOTA 3/TTL 5min/HOLD 2min/RENEW 10s/POOL_REF=pool-e2e/`JAVA_TOOL_OPTIONS=-Dagent.sandbox.lifecycle.grace-floor-seconds=30`；direct=同加速参数无 POOL_REF；std=QUOTA 5/TTL 30min/HOLD 20min/POOL_REF=pool-e2e；另 ws 服务发布入 workspace id=8 供 ⑨）+ 池 pool-e2e（`POST /api/v1/admin/sandboxes/pools` capacitySpec{1,2,1,3} → 201，首轮询 available=1、warm Pod pool-e2e-czc2l 1/1 Running）+ approval MCP 夹具（集群内辅助 Pod `v6-approval-mcp:8813`，service DNS tools/list 返回 5 工具）。**① 池认领 vs 直建**：chat 触发 exec → 0.5s 轮询 metadata=session_id → chat 起 4.1s 沙箱 Running（含 ~3.5s LLM 首 tool 延迟），服务端 create→Running 同秒（createdAt=lastTransitionAt=19:50:29Z）；debug 事件 `acquire_create "sid=… pool=pool-e2e"`、binding expires=created+5min、池 allocated=1/available=1（认领后补位）；直建对照（direct 无池）已实测对照。**②③ 并行配额**：同 user 3 会话并行 exec（sleep 35）→ 3 行全 turn_running=True 真实 sandboxId；第 4 会话 chat → HTTP 409 `{"code":"user_quota_exhausted","message":"沙箱配额已满（3/3）且均不可复用","quota":3,"bindings":[3×{session_id,expires_at,state:"executing"}]}`（与 frontend/src/lib/sandbox-quota.ts QuotaPayload 契约一致，state=executing → 卡片出停止按钮；时序注：首次 +7s 发第 4 会话得 200——一行尚 PENDING、listLive 读侧过滤不计满额，复测 3 行 live 后 409）。**④⑤ stop + LRU 复用**：运行中 turn `POST /threads/{sid}/stop` → 202 {state:stopping}，异步终止后 turn_running=False（续期停止）；LRU 事件链 `lru_reuse`×5（c1/c2/c3 依次抢 b1/b2/b3 空闲行、d4 抢 expires 最小者 c2 行）→ d4 绑定继承 sandboxId 382a84a3（原 b2/c2 沙箱）=「sandboxId 继承」实证。**⑥ 长命令 >TTL**：execute_shell_command sleep 330（>TTL 300s）执行中 9 次采样（每 25s）expires_at 持续后移（20:10:51→…→20:14:12，每次 ~+20s=renew 间隔）且 turn_running=True 全程，turn 完成（total 315s）、history 含 long-done 输出 → T3 在途续期有效、无到期中断；首测因池 poolMax=3 容量耗尽 create SandboxTimeoutException（事件 create_failed）→ `PUT /admin/sandboxes/pools/pool-e2e` 扩容（poolMax 3→6→14，PUT 路径复验 200）后复测通过（容量约束可观测=有效证据）。**⑦ HITL 保留窗**：chat 令 LLM 先 exec 写 /workspace/data/v7.txt 再调 submit_application → 终帧 permission_ask{tool_calls:[{tool_call_id, name:submit_application, input:{application_id:APP-0001}}]}；OpenSandbox files/download 探针读到 `v7-hold-marker-1794082503`；保留窗（2min）内 `POST /threads/{sid}/confirm-stream` {results:[{tool_call_id,confirmed:true}]} → 终态 AGENT_RESULT 无 error、同沙箱工作区文件在（无损实证）。**⑧ 抢占与换代**：抢占 live 4 次独立实证——hold(120s)+grace(30s) 过后新会话 acquire 抢走挂起会话行（事件 `lru_reuse victim_sid=v6-k1`；另 3 轮轮询「行被抢 YES」、抢后 bindingOf(挂起会话) 消失）；过期 confirm 换代 live 1 次实证——p1 原沙箱 85a471d8 已被 TTL GC、行被 renew_404 sweep 懒剔除，confirm-stream → 事件 `acquire_create 233c810f` sid=v6-p1（降级重建）→ AGENT_RESULT 完成、新绑定 233c810f ≠ 原 85a471d8；**唯余缺口**：换代提示帧 sandbox_rebuilt 未在 live SSE 观测到（8 次编排尝试失败根因已记录：renew-404 懒剔除路径不经归属拦截、⑤ 重建窗口撞配额满、真实 LLM 时序竞态/未调审批工具）——帧机制已由设计自带 mock e2e 实测（`npx playwright test --project=api-sandbox-lifecycle`，--grep 正则匹配 L3、L14、L18 → 4/4 PASS，含 L14/L18b sandbox_rebuilt 帧断言），⑧ 三子行为（抢占/换代/帧）均已实测、唯「live SSE 发出面」未覆盖。**⑨ 三方一致 PASS（数据级）**：framework debug 绑定行（sandbox 4e1bd83e-449c…、session v6-ws1-1794082840、expires 2026-11-07T20:50:52.411）= `GET /api/v1/admin/sandboxes`（同 id、state Running、metadata {service:oaf-v6-demo-ws, session_id 同值}）= `GET /api/v1/console/workspaces/8/sandboxes`（同 id、sessionId 同值、expiresAt 20:50:52.411439 逐字一致）；前端页面浏览器 UI 走查未在主轮执行（配额卡 UI 由黑盒复验轮补做，见下）。**黑盒复验轮**（platform-backend:sandbox-pool-pipeline 重部署、healthz OK 后）：① recycleStrategy 枚举校验 `POST /admin/sandboxes/pools` recycleStrategy:"Bogus" → HTTP 400「非法值（合法：Delete / Restart / Noop）」（上轮 201 缺陷已修）+ 同请求改 "Delete" → 201 正向链路不破坏，验证池随即 DELETE 200；② 配额满 409 真实浏览器 UI 卡 DOM 断言（puppeteer-core + chrome-headless-shell）：testid=quota-error-card-1、标题「沙箱配额已满（3/3）且均不可复用」、3 行「执行中」会话明细、3 个「停止会话释放配额」按钮（data-testid^=quota-stop-）、输入保留 INPUT_KEPT=true；③ 新用户正常 SSE 回归 200（AGENT_START→tool_call_summary→tool_result_preview，exec 输出 Exit code 0），debug binding_count=4 正常入账不受修复影响；④ 终态清理：沙箱/池/服务 unpublish+DELETE/工作空间/派生库 DROP/Redis 前缀键全 200、沙箱与 Pod 残留 0、平台配置零改动 |
| V7 | 回退开关 | ✅ 过（回退=回滚后基线行为：debug enabled:false、4 并发 0 个配额帧、TTL 不随 turn 刷新） | `PATCH /api/v1/services/62/env`（全量覆盖语义，env=原 17 键 + `SANDBOX_LIFECYCLE_ENABLED=false`）→ code 0；ConfigMap `oaf-v6-demo-fast-env` 实证键已注入；`/debug/sandbox-bindings` → `{enabled:false, reason:"SANDBOX_LIFECYCLE_ENABLED=false 或沙箱未启用"}`。超配额：user v7-user-1 四会话并行 exec（quota=3）→ 4 turn 中 **0 个 409/`user_quota_exhausted` 帧**（a/b 正常完成；c/d 报 sandbox create Pending 基建错误——节点过载恢复期，非配额语义，如实记录；「超配额不 409」断言依据 4 并发 0 配额帧成立）。TTL 不随 turn 刷新实测符合基线。备注：本轮大规模沙箱堆积曾致单节点 kind 内存过载（load 138/swap 满、全 pod restart 一次），经删除卡死沙箱恢复、无数据损坏——V6 判「部分通过」的环境限制即指此类；单节点 kind 做大规模生命周期实验需预控并发水位 |
| V8 | 存量影响冒烟 | ✅ 过（既有服务对话正常、服务全部可用；存量影响公告以草稿随本文档 §10 回填，正式公告待人工发布） | 集群内既有已发布服务（无新 env）对话一轮正常；存量四服务 CM 核查均未显式设置 `SANDBOX_TIMEOUT_MINUTES=60` → **当前无实际受影响存量服务**；显式 TIMEOUT=60 的语义变化核对基于框架配置面（`application.yml:89-91` 语义升级注释：60→30、一次性超时→随 turn 刷新的 TTL）+ V7 实测 TTL 刷新行为对照；「存量影响公告（草稿）」已回填 §10（要点：一次性超时→随 turn 刷新 TTL、活跃沙箱最长存活 60min→无限、运维容量规划需知情、可设 `SANDBOX_LIFECYCLE_ENABLED=false` 保持旧行为），正式公告按任务定义由人工发布 |

---

## 12. 开放问题（评审关注点）

1. **`POST /v1/pools` 请求体外层字段名**：**已落定（2026-11-07 源码逐字核实 + kind 实测，§11 V1）——推测正确**：`name`（pattern `^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`，≤253）/ `template`（Dict，语义=K8s PodTemplateSpec，schemaless 透传）/ `capacitySpec`（alias camelCase；四键 bufferMin/bufferMax/poolMin/poolMax int32 全必填，`populate_by_name` 故 snake_case 亦可）。证据：`server/opensandbox_server/api/schema.py:1041-1069`（CreatePoolRequest）、`:1000-1039`（PoolCapacitySpec）、`:1071-1081`（PUT /v1/pools/{name} 仅收 capacitySpec）、`:1098-1124`（PoolResponse）；实测 POST `name+template+capacitySpec`（camelCase 四键）→ HTTP 201，首轮询 `status.available=1`。`kubectl apply` Pool YAML 等价替代路径无需启用；
2. **池耗尽是否自动降级普通 create**：当前决议不降级（§4.6），理由是静默降级掩盖容量配置错误；若业务上更看重可用性可改为「降级 + WARN 告警」，待评审定；
3. **D16 确认硬截止 / D19 resourceRequests 捡回**：D16 已列入 PR-F 可选时间表（§10，解决确认卡与沙箱 TTL 的语义缝合）；D19（resourceRequests 透传）与 poolRef 模式的资源语义（由 Pool 模板定义）需一并评估后再定；
4. **上游 chart 目录**（`manifests/charts` vs `k8s/helm`）：**已落定——release-1.1.0 实际目录即 `manifests/charts`**（§11 部署记录：deploy 脚本 `CHARTS_DIR="$SRC_DIR/manifests/charts"`，base→controller→ingress-gateway→server 四 release 安装成功，脚本随 PR-C 入库）；**`/v1/sandboxes` 列表分页/metadata 过滤语法已实测落定（§11 V5）**：`state=` 状态过滤、`metadata=<key>%3D<value>` 相等过滤、`page/pageSize` 分页（响应 `pagination={page, pageSize, totalItems, totalPages, hasNextPage}`）均生效，非法 metadata 编码 → HTTP 400。console 只读页（§5.3）按服务集合过滤仍在转发后内存过滤（多值 metadata 组合未实测），state 过滤与分页语法可直接依赖。

---

## 13. 评审记录

### 2026-11-07 两轮子代理评审（本文档 v2 修订依据）

**轮一：生命周期正确性走查**（覆盖 create→绑定→turn（LLM-only/exec/挂起/恢复）→LRU 复用→到期→清理 全流程，含多副本/崩溃窗口）。采纳：

| 级别 | 发现 | 修订落点 |
|---|---|---|
| P0 | turn_lease 硬寿命 10min 到期物理删行（执行线程不感知）→ >10min turn 被误判 idle 遭 LRU 抢占互踩；A2A 链路从不持有 turn_lease 同理 | busy 判定改双事实源（isHeldStrict OR updated_at 新鲜度，grace=max(3×RENEW_INTERVAL,120s)），应用于 ⑤/预检/debug（§4.2/§4.3/§4.8） |
| P1 | 占额 CAS 与落绑定两事务间的崩溃窗口 → 幽灵占额死循环/误抛配额满（最长 ~24h） | PENDING 占额行不变量「计数==行数」（§4.1/§4.2 ②③⑤a） |
| P1 | 被改绑沙箱仍可经受害会话 P3 槽位 connector 重连（skipHealthCheck 不前置探活）→ 互踩 | 改绑同事务给受害槽位置墓碑（§4.2 ⑤/§4.9） |
| P1 | 注册表无泄漏兜底：挂死 turn（SSE 取消无终态）→ 无限续期 → 永不过期+永不可抢占 | bornAt 超龄剔除 max(TTL×2,2h)+ERROR 告警+尺寸/年龄指标（§4.4） |
| P1 | 定时清理计数重算与 ② CAS 并发未定义（低计→配额突破） | 锚行锁内守卫式 DELETE + 重算，依赖 PENDING 不变量（§4.1.2/§4.2 ⑤b） |
| P1 | chat 多 call 轮次 vs A2A 无 TurnFinalizer 的注销挂钩矛盾 | 注册表双信号：call refcount（stop()）+ turn 注册（终态注销）（§4.4） |
| P2 | renew 失败未分类（404 应剔除而非每拍重试）；本地 now+TTL 时钟偏移；预检假 409；锁内 probe；fail-open 同会话重复建未明示 | §4.4 失败分类 / expires_at 回填服务端绝对值 / §4.3 行龄过滤 / §4.2 锁外 probe+fail-open 如实描述 |
| P2 | e2e 覆盖缺口 | L12~L19 + e2e-multi 并发组 + fail-open 单测（§9.2） |

**轮二：三体验（用户/开发/AI agent）与回归核对**。结论：开发体验明确变好（env 14→5、核心代码 ~2400→<700 行、测试去时序化）；AI agent 体验结构变好（注册式续期是被回滚 g1 补丁的正解、member 侧同框架生效）；**用户体验原不达标**（前端零配套），回归面无已发布行为回退（PR #276 未进任何 tag，revert 干净）。采纳：

| 级别 | 发现 | 修订落点 |
|---|---|---|
| P0 | 前端丢弃 409 body（`HTTP 409` 一笔带过）、用户无释放路径 → 配额满体验净变差 | §6.3 ChatPane 配额错误卡（bindings 明细 + 停止会话释放 + 保留输入） |
| P0 | poolRef「常数时间认领」与本仓 2026-11-05 上游核对结论（`e11cadd`：仍是全新容器）矛盾 | V2 升格为口径对账项（§8.1），实测后回改 §1.2/§2.2 口径 |
| P1 | A2A 宽限 60s=续期间隔同值必抖动；renew 连续失败期可被误抢 | 并入双事实源 grace 阈值；连续失败暂不参与 victim 选取（§4.2/§4.4） |
| P1 | `workspaceruntime_test.go` 锁 32 键必红、`template.go` placeholder 60 误导、存量显式 TIMEOUT=60 语义突变未公告 | §7 点名测试同步 / placeholder 30 / §10 公告项 |
| P1 | 续期失败/fail-open 无可观测（排障盲区） | §4.8 事件缓冲 + fail-open 计数 + 注册表健康指标 |
| P2 | admin 破坏性操作无审计；分页语法依赖；confirm 换代无提示；D16 语义缝合 | §10 审计与告警备注 / §12-4 / §4.4 换代提示帧 / PR-F 时间表 |

未采纳/留开放：池耗尽自动降级（维持不降级，§12-2）；`GET /v1/sandboxes` 大规模分页优化（POC 无虞，§12-4）。

### 2026-11-07 用户评审补充（v3/v4 修订）

| 修订 | 内容 |
|---|---|
| HITL 挂起保留窗（取代 v2「挂起即注销、无保留保证」） | 待 confirm 状态可确定性判定（`confirm_context` 未决行，V1 基线表/V7 多行 FIFO）；ask 挂起改标 `holdUntil`（`SANDBOX_CONFIRM_HOLD_MINUTES` 默认 20min）留注册表**继续续期**——续期即保护（updated_at 持续新鲜 → busy → 不可 LRU 调度、不会中途到期），confirm 恢复**同沙箱**、turn 生成文件无损；过期停续注销 → 可调度/自然 GC。动机：跨沙箱恢复丢失非托管工作区文件，代价（配额锁定 ≤20min、全挂起时 409）已明示（§4.4 T4） |
| ① 复用 touch | acquire ① 命中时顺手 UPDATE updated_at/expires_at，封「A2A 无租约链路 acquire 后、首拍续期前」的 grace 空窗（§4.2 ①） |
| v4 验证与门禁细化 | §8.2 kind 联调全场景 V6~V8；§9 重写为验证与门禁章——9.1 单测矩阵 U1~U6（分支枚举即 DoD，行覆盖 ≥90%）、9.2 mock 故障注入（renew404/poolExhausted/slowCommand/slowLLM）、9.3 e2e L1~L20 分组矩阵（+L20 假 409 修正；grace 下限测试可压低先例同 turn-lease）、9.4 平台 m12-sandbox-admin、9.5 门禁修改（`e2e-sandbox-lifecycle` **转必需**，九项→十项 + 根 AGENTS.md 同步）、9.6 DoD |

### 2026-11-07 实施前终审（独立评审 P0 逐条修订，v5/v6）

独立评审针对 v4 定稿、以回滚后基线代码逐条核对，先后提出三条 P0：d1、d2（终审一轮，v5）；cold-R1-1（终审二轮，针对 v5 修订稿复核，v6）。**均核实成立（非误报）**，逐条修订如下；轮一 P1「改绑同事务给受害槽位置墓碑」的修订落点据此调整为 resume 归属校验+占位（历史记录保留不改）。

**d1（P0：方案不可实施）——「改绑同事务给受害会话槽位置墓碑」不可实施，改为 P3 resume binding 归属读时校验（三选一取 c）**

核实证据（本会话实测）：

- `SessionSandboxStateStore`（agentscope-harness 2.0.3）底层数据面为 `SandboxAwareMysqlAgentStateStore` → `MysqlAgentStateStore`（agentscope-extensions-mysql 2.0.3）。反汇编实证（`javap -p -c` 对解包 class）：构造器收裸 `javax.sql.DataSource`（三个重载均无 `Connection`/事务入参）；`save(...)` 方法体内部自调 `DataSource.getConnection()`；`setAutoCommit` 自管事务边界——`save/delete` 是**独立自管事务，无法并入** `SandboxBindingStore` 的锚行事务，「同事务置墓碑」落不了地；
- slot 键格式 `sandbox/user/{agentId}/{userId}` 为 harness 私有（`SandboxAwareMysqlAgentStateStore.java:10-13` javadoc 自证；`SandboxLocator.java:25-27` 明令「不手拼 `sandbox/user/...` 字符串」，须经 `SandboxIsolationKey.resolve` 私有算法合成；`SessionSandboxStateStore.slotSessionId` 为 private）。且 `MysqlAgentStateStore` 含 hash/version 内部机制（`HASH_KEY_SUFFIX`/`saveIfVersion`/`getStoredHash`，反汇编可见）——评审选项 a（同 DataSource 直写 agent_state 复刻 slot 格式）须同步复刻 hash/version 语义，SDK 升级即碎，**排除**；
- 若墓碑与改绑保持两个独立事务（无论何序），改绑提交后、墓碑前（或墓碑写失败后），受害会话经 P3 connector `skipHealthCheck(true)`（`OpenSandboxClient.java:233`；`SandboxLocator.java:97` 同模式）直连易主沙箱重连成功——恰是墓碑要防的互踩。

决议：**不写墓碑，读时归属校验（选项 c）**——P3 `client.resume` connect 成功后查 `sandbox_binding`（`sandbox_id` 无行或 session_id ≠ 本会话 → close 句柄 + 降级走本决策），`SandboxLocator.locate` 同规则（env-update 链路是回滚后基线仅有的第二条直连路径；`SandboxPoolManager`/`AgentScopeConfig` 内两处同模式代码属 PR-A 回滚对象不在基线）；校验查询异常放行 + WARN（fail-open 同纪律）。选 c 而非 b（固定两步写序「先墓碑后改绑」）的理由：b 的两事务间隙仍留受害在途 connect 窗口（须再靠 d2 收口），且引入跨事务写序纪律、墓碑孤儿态与 slot 键合成耦合；c 与 §4.9 既有「resume 校验失败路由 LifecycleManager」决议同构延伸，有被回滚 PR #276 `reconcileResume`（resume 后账本归属校验 → close + 降级）实证先例，且把归属真相收敛到 binding 表单点，符合 §4.1.1「不造第二真相」纪律。代价：每次 P3 resume/locate 多一次主键点查（毫秒级）。修订落点：§4.2 两道防线段 + 要点两条、§4.4 T4 换代提示、§4.9 文件清单（`SandboxLocator.java` 行取代原槽位墓碑行）、U1/U4 用例、L14 断言。（v6 补充：校验形式经下述 cold-R1-1 升级为「校验+占位」条件写，见第三条。）

**d2（P0：正确性错误）——⑤c 抢占与 ① 在途复用的竞态窗口未闭合，touch 前置 + updated_at 快照乐观校验**

核实证据（设计文本自证）：v4 ① 的 touch 排在 probe/connect 之后（原文「connect 复用 + touch」）；⑤c 改绑条件仅 `session_id` 无 updated_at 校验；A2A 链路从不持有 turn_lease（本文 §4.2 双事实源段自述）。受害会话在途 acquire 处于 probe/connect 的 HTTP 间隙、touch 尚未提交时，他副本 ⑤c 读到 stale updated_at → 判 idle → 改绑 `WHERE session_id` 仅条件命中 affected=1 → 受害 connect 完成后与新归属并发使用同一沙箱——违反本节自述不变量（爆炸半径限同 user 两会话，LRU 仅同 user 复用）。与轮一自评 P0「busy 误判互踩」同类，属其未闭合的残余窗口。

决议：评审三项建议**全采纳**，组合闭环——① 改「先 touch 占住 updated_at 再 probe/connect」（affected=0 → 放弃复用走 ②）；⑤c 改绑条件追加 `AND updated_at = :busy判定快照值`；① touch affected=0（已被改绑/懒剔除）→ 放弃复用重走决策。闭环证明：touch（`WHERE session_id=:sid`）与改绑（`SET session_id=:新 AND updated_at=:快照`）作用于同一行，InnoDB 行锁串行化下任一提交序**恰有一方 affected=1**——touch 先提交 → 改绑 updated_at 不匹配；改绑先提交 → touch 按 session_id 定位失败。无论 busy 判定的读发生在锁外还是锚行锁内一致性读（MVCC 快照），改绑当前读均复验，任何时序下不存在「改绑成功且受害 touch 成功」的提交。修订落点：§4.2 ①/⑤c 伪代码与两道防线段、U1（① touch/⑤c 用例）、U3（touch affected=0 分支 + affected 互斥）、e2e-multi 并发用例描述。

**cold-R1-1（P0：正确性错误，终审二轮针对 v5 修订稿）——P3 直连路径的 grace 空窗未封：归属校验为只读 SELECT、无 binding 写，d2 的在途互斥只覆盖了 LifecycleManager ① 路径**

核实证据（本会话实测）：

- 存量会话每次 acquire 走 P3 直连：基线（回滚后）`OpenSandboxClient.java:151` 即 `resume()` 内 `.skipHealthCheck(true)`（`git show 9c81437^:<path>` 实核，工作区 :233）；`SandboxLocator.java:97` 同模式。P3 成功路径不经过 LifecycleManager ①（§4.2 入口决议「P1~P3 维持现状」），因此 d2 的 touch 前置对它不生效；
- A2A 链路无 turn_lease：`grep -c "TurnLease" A2AController.java` = **0**（controller 目录含 TurnLease 引用者为 TurnFinalizer/SessionStreamController/AguiRunController/ChatStreamController/ConfirmController，A2A 不在其中）；
- 窗口由 v5 文本自证：两道防线第 2 条归属校验为只读 `SELECT`、无任何 binding 写；busy 第二事实源仅 turn_lease + binding.updated_at（§4.2，续期注册表是进程内态、跨副本不可观测）；① 路径同款空窗由 touch 封堵系设计自述（§4.2 ①「封掉 acquire 后、首拍续期前的 grace 空窗」）。于是：A2A 会话闲置 > grace 后重入，[P3 resume 通过, 首拍续期] ≈ ≤60s+滞后 窗口内 binding.updated_at 仍陈旧，同用户并发 ⑤c 判 idle → 改绑乐观校验无 touch 可拦截（affected=1）→ 受害在途 exec 与新会话双用同一沙箱——系统性违反 §4.2 自述不变量，与 d2 同类（chat 会话在 turn_lease 持有期内重入不受此窗口影响，缺口集中在 A2A/无租约链路，v5 只读校验仅在「改绑先于 resume」序下有效，本序恰相反）。

决议：**归属校验升级为「校验+占位」合一条件写（评审建议采纳）**——`UPDATE sandbox_binding SET updated_at = NOW(3) WHERE sandbox_id = :state.sandboxId AND session_id = :sid`，affected=0 即归属失效（无行/已改绑/漂移）→ close + 降级走本决策。理由：与 ⑤c 快照校验构成与 ① 完全相同的行锁互斥（占位先提交 → ⑤c 快照不匹配；改绑先提交 → 占位 `WHERE session_id` 0 行），在途双用窗口对 P3 路径同样闭合；顺带省一次点查；并统一不变量为「**任何路径 acquire 成功必先占位**」（① touch / P3 校验+占位）。占位写只动 updated_at（NOW(3) 同 DB 时钟，grace ≥120s > 60s 续期间隔 → 新鲜度覆盖到首拍续期落地）；fail-open/漂移/fail-open 恢复各分支语义见 §4.2 两道防线第 2 条。修订落点：§4.2 两道防线第 2 条重写 + 两道防线标题 + 选 c 段 + 要点两条 + 伪代码尾注、§4.9 `OpenSandboxClient.java`/`SandboxLocator.java` 两行、U4（P3 通过即 touch/affected=0 降级/异常 fail-open 三分支）、e2e 新增 L21（A2A 闲置重入 grace 空窗，多副本变体进 e2e-multi）、DoD 与 PR-D 行 L1~L20 → L1~L21。
