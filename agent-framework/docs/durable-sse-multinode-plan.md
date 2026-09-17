# 事件总线多副本正确性与落库性能改造设计（durable-sse-multinode-plan）

> 状态：**设计稿**，待评审
> 范围：agent-framework（AgentScope Java 2.0.0 + Spring Boot 3.3.5）
> 前置：已完成 durable-sse-plan（SessionEventBus + session_event + GET /subscribe + GET /status）
> 触发：多副本部署即将开启（动机：高可用 + 吞吐）
> 目标：让事件总线在 N 副本下语义正确，同时把落库开销降到可承受范围

---

## 1. 背景与问题

### 1.1 触发条件

durable-sse-plan 交付时明确把多实例列为已知限制（§9 R5）与 P2 预留（§6），当时部署规模为单实例。当前状态：

- agent-framework 由 platform-backend 按 agent 拉起为 K8s Deployment，`replicas` 是发布时可调参数，`< 1` 兜底为 1（`backend/internal/k8s/objects.go:102-104`）
- 仓库内所有 Deployment 当前均为 `replicas: 1`（`manifests/platform.yaml:48,145`）
- Service 无 `sessionAffinity`，ingress 无 affinity 注解（`backend/internal/k8s/objects.go:205-220`）

即：**代码已经允许 replicas ≥ 2，但没有任何一层配置阻止踩坑**。谁把 replicas 调到 2 就当场激活本文的 F1/F2。

### 1.2 现状核实

以下每一条均经代码核对，非推测。

| # | 问题 | 位置 | 机制 | 实际严重性 |
|---|------|------|------|-----------|
| F1 | 跨副本实时事件丢失 | `SessionEventBus.java:91-100` | `emit` 只写本 Pod 的 sink；执行副本与订阅副本不同则实时流收不到后续事件 | 重连流回放正常（走 DB）但无后续实时事件 |
| F2 | 跨副本重连流悬挂 | `SessionEventBus.java:158` | `completionSignal = sink.asFlux().then()` 派生自**本地** sink；heartbeat 是 `Flux.interval` 无限流，`takeUntilOther` 永不触发 | **不是文档所述"5 分钟兜底"**——见下 |
| F3 | `turnStatus` 误判 | `SessionEventBus.java:195` | `sinks.containsKey()` 是 Pod 本地判据 | `/status` **未被** `leaseHeld` 救回，见下 |
| F4 | 残留 sink 使 `turnStatus` 长期误报 | `SessionEventBus.java:137` | `subscribe()` 内部调用 `ensureSink()`，会**创建** sink | 对本已结束的 turn 调一次 `GET /subscribe`，该 Pod 的 `turnStatus` 即报 WORKING |
| F5 | 多标签页接缝丢失 | `SessionEventBus.java:174,147,158` | `multicast().onBackpressureBuffer` 的实测语义见 §1.3 | 后加入的标签页在其 replay 窗口内的实时事件全部丢失，且不自愈 |
| F6 | `queryAfter` 全程持有连接 | `SessionEventStore.java:127-152` | `Flux.create` 内 `try (conn)` 包住整个推送循环；下游反压则连接被钉住 | 池默认 **10**（`AgentManagerProperties.java:214`）→ 10 个并发回放可耗尽整 Pod 连接池 |
| F7 | `/status` 单次调用 4 次独立借用 | `SessionStreamController.java:323-339` | `isHeld` + `findLatest` + `findMaxSeq` + `findPendingConfirm` | 前端若轮询 status，开销高于游标轮询 |
| F8 | 写入侧每事件 2 语句 | `SessionEventStore.java:83-102` | `SELECT MAX(seq)+1` + `INSERT`，各自 `prepareStatement`，1 次连接借用 | 2000-token turn ≈ 4200 语句 / 2100 次借用 |

**F2 更正**：`evictStaleSinks()` 的唯一调用点是 `SessionCleanupService.java:82`，位于 `@Scheduled(cron = "0 0 3 * * ?")`（`SessionCleanupService.java:68`）——**每天凌晨 3 点执行一次**。`sinksEvictionDelay` 的 5 分钟是**阈值**而非**检查周期**。因此跨副本 subscribe 后：心跳 `: hb` 照发，前端 `onerror` 不触发、不重连，ingress `proxy-read-timeout` 为 3600s（`backend/internal/k8s/objects.go:216`）也不会砍——连接悬挂至**客户端放弃 / Pod 重启 / 次日凌晨 3 点**。durable-sse-plan §9 R2 所述"Sinks TTL 5min 兜底"在实现层从未生效，这是与多副本无关的独立缺陷。

**F3 更正**：`SessionStreamController.java:331` 为 `leaseHeld || busStatus == TurnStatus.WORKING`，是 **OR**。跨副本场景靠 `leaseHeld` 救回；但**本地误报的 WORKING 会赢过 `leaseHeld=false`**。结合 F4，本 Pod 存在残留 sink 时 `/status` 持续报 working。

**F5/F8 与多副本无关**，单副本下同样存在，属于本次一并修复的对象。

### 1.3 F5 的实测记录

对 `Sinks.many().multicast().onBackpressureBuffer(256)`（reactor-core 3.6.x）实测：

```
[1] 首订阅者(emit 后订阅) 收到 = [BEFORE-1, BEFORE-2, AFTER-1]
[2] 晚订阅者(emit 后订阅)  收到 = [BEFORE-1, BEFORE-2, AFTER-1]
[3] 首订阅者=[E1, E2]      第二订阅者(中途加入)=[E2]
```

结论：**首个订阅者可获得订阅前缓冲的元素**（这是 `POST /chat` 先 subscribe 后执行之所以安全的原因）；**第二个及以后的订阅者只能获得订阅之后的元素**。

后果：多标签页场景下，标签页 B 中途打开 → `replay.concatWith(live)` 要求 replay 完成后才订阅 live → 该窗口内的实时事件全部丢失，B 的内容比 A 少一截且不会自愈。

### 1.4 目标

- N 副本下事件语义正确：不丢、不悬挂、状态判定准确
- 落库开销降至可承受范围，且**不改变实时流观感与对外契约**
- 分阶段可交付，每阶段可独立验证

### 1.5 非目标

- 不做 Redis / 粘性路由（否决理由见 §2，重新评估触发条件见 §2.3；**其中 §2.1 D1 已于 2026-09-16 被推翻**——`session_event` 存储已迁到 Redis Streams，pub/sub 广播仍未采纳，见该节标注）
- 不做 agent 执行的跨 Pod 恢复（`agent_state` 持久化已具备基础，但需先解决工具调用幂等性/副作用重放，另立课题）
- 不做 A2A 路径改造（自带 `tasks/resubscribe`）
- 不改 SSE 词表与前端现有渲染逻辑

---

## 2. 架构决策

### 2.1 D1：不采用 Redis Pub/Sub

> **⚠️ 已被推翻（2026-09-16；命中 §2.3 触发条件 #1）**
>
> 被推翻的是**本条对 Redis 的否决**，不是「不用 pub/sub 做广播」这个取向：原否决针对的是
> **用 Redis Pub/Sub 做跨副本实时广播**，而实际采纳的是另一种形态——**Redis Streams 作为
> `session_event` 的存储（store of record）**：`sess:{sid}:events`（Stream，ID = `<seq>-0`，
> 字段 `t`/`r`/`p`）+ `sess:{sid}:replies`（ZSET，member = replyId，score = 该 reply 首个 seq）。
>
> 四条理由为何不再支撑否决：
> - **理由一/二的前提变了**：原文说「状态已有归属（`session_event` + `turn_lease`），Redis 只解决扇出」。
>   现在这份存储的归属**就是 Redis**，Redis 不再是「纯增量成本、零增量正确性」的旁路。
> - **跨副本正确性不需要广播**：§3.4.1 的游标追赶原样保留（`SessionEventTailer` 轮询），只是被轮询的
>   对象从 MySQL 换成了 Redis Stream。**任何副本读同一份存储即天然跨副本正确**，无需知道执行在哪个
>   Pod——§2.4 D3「写入方本地 sink、观察者读共享存储」的分工一字未改。
> - **理由三只对 Pub/Sub 成立**：at-most-once、无 ack、消费不过来被断开即静默丢消息，这些是 pub/sub
>   的语义；Stream 是带 ID、可 `XRANGE` 重读的持久日志，没有这条失败模式，故不适用于实际采纳的形态。
> - **理由四已被现实作废**：Redis 已进入运维栈（`manifests/platform.yaml` 的 `oaf-redis`
>   Deployment/Service/PVC；应用侧有 Lettuce 客户端与 `RedisEventLog` 存储层），边际成本不再是零。
>
> **仍未采纳的部分**：跨副本**实时扇出**（`RedisSessionEventBus`）本轮未做，观察者仍走 300ms 游标
> 追赶（§2.4）——即「不引入 pub/sub 广播」这条设计取向保留。
>
> **验证状态**（均已实跑，不是「已写好」）：语义层（攒批 / seq 分配 / 游标分页 / replyId 过滤）
> 由单元测试覆盖，全量 `mvn test` **655 用例全绿**（2026-09-17 复测 = 654 + file_ready 契约用例 1 例）；
> 需要真 Redis 的两支集成测试
> （`REDIS_IT=1 REDIS_IT_URL=…` 门控）**`RedisEventLogIT` 10/10、跨副本
> `SessionEventStoreCrossReplicaIT` 6/6**。端到端真链路探针跑通：真实 turn 落 Redis →
> `/subscribe` 回放 → `/status`（Redis 停时 **503**、恢复后 200）→ 级联删除三个 key →
> history 的 reply_id 回填；配置反向验证两个方向都验过（`appendonly=no` / `allkeys-lru` 时
> 如实 ERROR，合规时「持久性自检通过」）。旧表已确认停止写入（行数与 `MAX(created_at)` 均冻结）。
>
> §5.3 用例现状：#8 由跨副本 IT 覆盖；#11 由 `SessionEventBusTest` 的 HITL 时序契约覆盖；
> #9（HITL 后另一副本 subscribe）与 #10（kill 执行副本）此前只有判定语义的单测覆盖
> （`SessionEventTailerTest` 的 `finishedWhenHitlPendingConfirm` / `tailEmitsInterruptedWhenExecutorCrashed`）；
> **真进程联调已于 2026-09-17 由 e2e 多副本专项补齐**：跨副本 subscribe 全量事件
> （802/802）到达 + done 帧正常关流、kill 执行副本 60s 后 `/status` 返回 `interrupted`——
> #8-#11 至此全部闭环，无已知缺口。

**理由一：它解决的是扇出，不是状态归属。** 本文问题的本质是"重连的 Pod 如何知道发生了什么、以及什么时候结束"，这是状态归属问题；状态已有归属（`session_event` + `turn_lease`）。Redis 回答的是"如何通知 N 个进程"，而本场景并不需要跨 Pod 通知 N 个进程——`POST /chat` 天然同 Pod。

**理由二：它不能替换 DB 路径，只能叠加。**

| 缺口 | Redis 是否解决 |
|------|----------------|
| 完成信号（`closeSession` 在 Pod A，订阅者在 Pod B） | 否，需额外广播 close 控制帧 |
| `turnStatus` 判据 | 否，仍必须改为查 `turn_lease` |
| replay→live 接缝 | 否，仍需 DB 对账兜底 |

即 DB 游标路径一行都省不掉，Redis 是"纯增量成本、零增量正确性"。

**理由三：引入新的静默失败模式。** Redis pub/sub 是 at-most-once、无 ack、无回放。Redis 对 pubsub 客户端有 `client-output-buffer-limit`，消费不过来会被**主动断开**；网络抖动、主从切换期间消息永久丢失，且订阅方**不可感知、不可检测、不可补偿**。后果是用户流中出现静默空洞——内容残缺、无 error 帧、前端无从察觉。以高可用为动机引入一个"故障时静默损坏内容"的新单点，方向相反。

**理由四：成本与现状。** 当前仓库 Redis 依赖与配置均为零（`pom.xml`、`application.yml` 无 Redis 相关项；Redis 仅出现在 4 份设计文档中作为 P2 预留）。引入需补：有状态组件部署与 HA 形态（Sentinel/Cluster）、`spring-boot-starter-data-redis` 依赖与连接生命周期、每 session 通道的 subscribe/unsubscribe 簿记、`subscribe()` 完成信号语义改造、`EnvelopedEvent` 过线序列化、测试基建（embedded-redis / testcontainers）。

**附注**：durable-sse-plan §6 所述"替换时仅需更换 Bean 实现，控制器零改动"不成立——`subscribe()` 的完成信号派生自本地 sink（`SessionEventBus.java:158`），`turnStatus()` 读本地 sinks map（`SessionEventBus.java:195`），这两个语义均需改动。因此"接口已预留"不构成低成本的证据。

### 2.2 D2：不采用会话粘性路由

- **只降低概率，不提供正确性**：cookie 是浏览器维度而非 session 维度，换设备、cookie 过期（nginx affinity 默认 1h）、扩缩容瞬间都会破。
- **吞吐动机不需要**：随机 LB 已摊开并发 session。粘性仅在"有 per-session 内存状态需要保温"时有额外收益，而这里唯一的内存状态正是要解耦掉的 sink。
- **高可用动机下反而更差**：Pod 死亡时一致性哈希把槽位转给后继 Pod，一批本运行正常的 session 被整体改派；随机 LB 下仅该 Pod 上的 turn 受影响，其余 session 无感。
- **成本**：`session_id` 在 path 中，且 ingress 被 `/agent/{short}(/|$)(.*)` rewrite（`backend/internal/k8s/objects.go:205`），按 session_id 哈希需加 `map` 正则提取 + `upstream-hash-by`，属 Go 侧改动 + 全 agent 共享的配置面。
- **明确禁止**：不要使用 Service 的 `sessionAffinity: ClientIP`——在 ingress 之后所有流量源 IP 均为 ingress Pod，会把所有会话钉死到一个副本，等于未扩容。

### 2.3 D1/D2 的重新评估触发条件

命中任意一条应重新评估：

1. Redis 已进入运维栈（此时本功能边际成本趋近于零，性价比反转）
   —— **已命中（2026-09-16）**：Redis 已随 `oaf-redis`（`manifests/platform.yaml` 的
   Deployment/Service/PVC）进入运维栈，应用侧接入了 Lettuce 客户端与 Redis Streams 存储层
   （`RedisEventLog` + `SessionEventStore`）。重新评估的结论见 §2.1 顶部标注：采纳的是
   **Streams 做事件存储**，**不是**改用 pub/sub 做广播。
2. 需要多个协作者同时低延迟观察同一 session
3. 需要跨 Pod 下发 cancel/interrupt（可先走 `turn_lease` 标志位——执行侧 `TurnLeaseGuard` 已有 20s 续约循环，天然是命令轮询点，`TurnLeaseGuard.java:62`）

### 2.4 D3：采纳 DB 游标追赶 + 写入方本地 sink

**核心规则**：

> **拥有执行的请求消费本地 sink（保证首 token 延迟）；其他所有观察者（`GET /subscribe`）走 DB 游标。**

理由：DB 本来就是 Pod 之间共享的状态，`session_event` 由执行副本写入、任何副本可读，`turn_lease` 由执行副本续约、任何副本可读。现有缺陷仅在于 `subscribe` 去读本地 sink 而非这张表。

副产品：`subscribe()` 不再调用 `ensureSink()` → **F4 从根上消失**。

### 2.5 D4：攒批采用多值 INSERT，而非合并文本

| | 合并文本（delta 拼成一条） | **多值 INSERT（采纳）** |
|---|---|---|
| 实时流粒度 | 变粗 → 打字机效果从 ~16 次/秒降至 ~3.3 次/秒，**可感知** | 不变（仍 per-token） |
| SSE `id` / seq 语义 | 一个 batch 共用一个 seq | 不变（每 token 一个 seq） |
| 重连游标语义 | 需改为 inclusive + 前端重建消息 | 不变 |
| 前端线协议 | 不变 | 不变 |
| DB 语句数（单 turn） | ~11 | ~11（**前提：全部 delta 都进缓冲**，见下） |
| DB 行数（单 turn） | ~11 | ~2100（未省） |

**前提修正（2026-09-16）**：上表「~11 语句」的前提是**所有 delta 类事件都进缓冲**。实现落地时该前提一度不成立——判定 delta 的名单被硬编码，只列了 `TEXT_BLOCK_DELTA` / `THINKING_BLOCK_DELTA` 两种，而事件词表（`AgentEventType`）里的 `*_DELTA` 共 **6** 种：另外 4 种（`DATA_BLOCK_DELTA` / `TOOL_CALL_DELTA` / `TOOL_RESULT_TEXT_DELTA` / `TOOL_RESULT_DATA_DELTA`）落进里程碑分支、**每一条都触发一次刷出**。实测 88,445 条 `TOOL_CALL_DELTA` = 88,445 条 INSERT，写放大 26 倍；这是**词表漂移**造成的静默退化，不是选型错误——**多值 INSERT 攒批本身的结论不变**。

修复已落地（2026-09-16）：改为按 **`*_DELTA` 后缀**判定，词表再增长也不会退化；误判的代价不对称（把里程碑当 delta 只影响回放可见延迟，把 delta 当里程碑则是每 token 一条写），所以宁可放宽。

打字机效果的量化依据：前端本身每 60ms `setState` 一次（`frontend/src/app/assistant/page.tsx:210-218`），**当前观感已是 ~16 次/秒**，由前端制造而非服务端 per-token 事件制造。服务端再加 300ms 攒批会降到 ~3.3 次/秒。

结论：多值 INSERT 以"行数未省"为代价，换取"实时流 / seq / 线协议零改动"，且语句数降幅相同（-99.7%）。合并文本留作后续可选项，仅当行数或回放体积成为瓶颈时再做（届时才需动游标语义与前端重建逻辑）。

---

## 3. 详细设计

### 3.1 关键不变量

| # | 不变量 | 违背后果 |
|---|--------|---------|
| I1 | DB 中 `session_event` 的 seq 始终是**连续前缀** | 重连回放读到空洞或提前判定终态 |
| I2 | 同一 session 同一时刻只有**一个 Pod** 在写（由 `turn_lease` 全局串行化保证） | seq 冲突 |
| I3 | 游标只前进；回放查询 `seq > cursor` 幂等可重放 | 重复投递无法收敛 |
| I4 | 写入方用本地 sink 供自己的 SSE；观察者只读 DB | F1/F2 回归 |

I1 的实现约束：**任何里程碑事件落库前，必须先把待刷的 delta 缓冲刷掉。** 否则若 delta 缓冲了 seq 100-299、此时 `permission_ask`（seq 300）立即落库，DB 中即出现 300 而 100-299 缺失，重连客户端回放会读到 300 并误判边界。emit 单线程顺序，实现上为 `flushPending(); insertMilestone();`。

### 3.2 阶段 1：服务端内部优化（零对外契约变更）

#### 3.2.1 seq 内存计数器（消除 F8 的 SELECT）

- 每 session 维护一个 in-memory seq 计数器，`append` 直接取号，不再 `SELECT COALESCE(MAX(seq),0)`
- **seed 时机**：必须在 `turn_lease` **获取之后**从 DB `MAX(seq)` 读种子（`SessionEventStore.findMaxSeq` 已有）。原因是 seq 为 session 维度递增，而 turn 跨副本交接（HITL 确认后是新 turn、新 replyId，可能在另一个 Pod 恢复）；若仅按 Pod 生命周期 seed，交接后新 Pod 的计数器会从 0 开始并撞掉已有 seq
- 依赖 I2：counter 的安全性建立在"同一时刻只有一个 Pod 在写该 session"之上，此前提需写入代码注释

#### 3.2.2 多值 INSERT 攒批（降低 F8 的语句数与借用次数）

```
delta 事件（TEXT_BLOCK_DELTA / THINKING_BLOCK_DELTA）→ 进缓冲
里程碑事件（AGENT_END / permission_ask / TOOL_* / file_ready / error）→ 先 flushPending() 再立即落库
flush 触发：缓冲 size 达上限 / 距上次 flush 达 T / turn 结束 / 出错
```

> **更新（2026-09-16）**：上面的 delta 名单是**示例而非全集**——按此名单硬编码实现后，4 种 `*_DELTA`（`DATA_BLOCK_DELTA` / `TOOL_CALL_DELTA` / `TOOL_RESULT_TEXT_DELTA` / `TOOL_RESULT_DATA_DELTA`）因命中下面里程碑列表里的 `TOOL_*` 而被当成里程碑逐条落库（详见 §2.5 的前提修正）。现实现改为按 **`*_DELTA` 后缀**判定，两侧重叠不再有歧义。

- 多值 INSERT 语法：`INSERT INTO session_event (...) VALUES (...),(...),...`，每行仍持有各自的 `seq` / `event_type` / `payload` / `reply_id`，`created_at` 逐行 `NOW(3)`
- **批量上限**：按 payload 估算控制（200 行 × ~100B ≈ 20KB，远低于 MySQL `max_allowed_packet` 默认值），上限可配置并需有兜底
- `AGENT_END` **必须同步落库**，不可留在缓冲——否则 turn 已结束但 DB 末条仍为 delta，`/status` 与游标终止判定全部失准
- 语句数：单 turn **~4200 → ~11（-99.7%）**，连接借用同步下降

#### 3.2.3 `evictStaleSinks` 独立调度（修 F2 的兜底）

从 `SessionCleanupService.cleanup()` 的每日 cron 中移出，改为独立 `@Scheduled(fixedDelay = 60s)` 的方法。

> 说明：这只是兜底。F2 的根治在阶段 3；但此修正与多副本无关，且是"任何原因漏掉 `closeSession` 的 sink 都会挂 24 小时"这一缺陷的唯一出口，因此提前到阶段 1。

#### 3.2.4 `turnStatus` 判据修正（修 F3/F4）

- 删除 `sinks.containsKey()` 判据（Pod 本地，跨副本错误；且会被 F4 的残留 sink 污染）
- 改为 `turnLeaseStore.isHeld(sessionId)` + `eventStore.findLatest(sessionId)` 的事件类型，作为唯一判据
- 依据：`turn_lease` 在 DB 中，天生跨副本正确

### 3.3 阶段 2：健壮性修复（独立可做，不依赖阶段 1）

#### 3.3.1 `queryAfter` 连接持有修正（修 F6）

现状（`SessionEventStore.java:127-152`）：`try (conn)` 包住整个 `while (rs.next()) sink.next(...)`，下游反压时连接被钉住。池默认 10，10 个并发回放即可耗尽整 Pod 连接池（`dbPoolConnectionTimeoutMs` 默认 30000ms）。

修正：在 try 块内**先把整页读入 `List`**，关闭连接后再从内存向外发。

同时补 `LIMIT`（当前查询无 LIMIT，一次回放 2000 行 ~1MB 全量 materialize）与分页续读。

> 优先级说明：当前前端不调用 `/subscribe`（实测见 §5.2），此路径尚非热路径；**一旦前端实现重连，它立即成为新热路径**。因此应与阶段 3 同批或更早完成。

#### 3.3.2 `/status` 合并查询（修 F7）

`findLatest` + `findMaxSeq` 合并为一条 `ORDER BY seq DESC LIMIT 1`；评估 `isHeld` 与 `findPendingConfirm` 是否可合并。目标：单次 `/status` 从 4 次借用降到 2 次以内。

### 3.4 阶段 3：跨副本正确性

#### 3.4.1 `subscribe` 改 DB 游标追赶

```
读 turn_lease → 被持有 → turn 在执行中（在哪个 Pod 无需知道）
replay:  SELECT ... WHERE session_id=? AND seq > K ORDER BY seq ASC LIMIT n
         → 推送，cursor = 已推送的最大 seq；不足页则续读
循环:
  SELECT ... WHERE session_id=? AND seq > cursor → 有则推送并推进 cursor
  判定是否可终止（见下）
  无新事件 → sleep 300ms
终止: 补 done 帧 → 关流
```

跨副本路径不再使用本地 sink；I4 由"写入方消费本地 sink、观察者读 DB"保证。

#### 3.4.2 终止判定（含 HITL 分支）

**不可简化为"lease 释放即结束"**：`ChatStreamController.java:312` 在 HITL 时会主动 `lease.release()` 让出执行权，但 turn 并未结束——`permission_ask` 不是终态。

判别依据复用 `confirm_context` 表：

| 条件 | 判定 |
|------|------|
| lease 被持有 | 执行中，继续轮询 |
| lease 已释放 且 最新事件为 `AGENT_END` / 终态 error | 正常结束，补 done 帧关流 |
| lease 已释放 且 `findPendingConfirm` 非空 | HITL turn 边界 → 补 done 帧关流（依据 §3.4.4 的决策） |
| lease 已释放 且 `findPendingConfirm` 为空 且 最新事件非终态 | 崩溃 / 被抢占 → 推 `interrupted` 终态帧后关流 |

`findPendingConfirm` 的作用是**区分"正常关流"与"以 interrupted 关流"**，而非"决定是否继续轮询"。

轮询频率建议：事件查询 300ms；lease 检查在空闲时降频至 ~2s（稳态下事件查询返回 0 行，为亚毫秒的索引区间扫）。

#### 3.4.3 `/status` 增加 `interrupted` 态

Pod 死亡后 lease 停止续约（TTL 60s，`TurnLeaseStore.java:34`），60s 后过期。当前判定链为 `leaseHeld=false` → `busStatus=IDLE` → `latestEvent` 非 `AGENT_END` → `state="idle"`。**前端显示 idle，用户看到自己发了消息、没有回复、也没有报错**——比报错更糟，用户不知该重试还是该等待。

增加 `interrupted` 态（判据同 §3.4.2 末行），前端据此提示"任务中断，可重试"。这与 §3.4.2 共用同一判据，应一起实现。

#### 3.4.4 HITL 关流语义（决策：关流）

**实现与文档不一致**：durable-sse-plan §5.4 述"`permission_ask` 后 EventBus 关闭该 turn 的 Sinks，前端流正常结束；确认后是新 turn、新 SSE 流"。但 `ChatStreamController.handleEventAndEmit` 的 `RequireUserConfirmEvent` 分支只做 `storeConfirmContext` + `lease.release()`，**未调用 `closeSession`**。当前实际语义是"流意外地一直开着"。

**决策：在 `permission_ask` 处关流**，即落实 durable-sse-plan §5.4 的原意。前端证据（均经核对）：

| 证据 | 位置 | 含义 |
|------|------|------|
| `asked` 返回值在两个调用点均未被使用 | `page.tsx:300`（丢弃）、`page.tsx:323`（未接收） | 前端不依赖流的终态来决策 |
| 确认卡片经 `onAsk` 回调在流中即时渲染 | `page.tsx:243,325-327` | 关流时卡片已渲染完毕 |
| 刷新时用 `pendingConfirm` 重建卡片 | `page.tsx:112-118` | 不依赖流持续打开 |
| 主循环 `for(;;) { reader.read() }` 直至 `done` | `page.tsx:275-288` | 关流是前端期望的正常终止方式 |

**若不关流的两项代价**：(a) 服务端 sink 长驻需靠驱逐兜底；(b) 跨副本下订阅方在整个 HITL 期间（可能数分钟）持续 300ms 轮询，而该期间不会有任何新事件，纯属浪费。

关流后 §3.4.2 中 `findPendingConfirm` 的语义相应变为"区分正常关流与 interrupted 关流"，见该节。

#### 3.4.5 前端重连（另立排期）

实测：`frontend/src/` 全量 grep `subscribe` / `EventSource` / `afterSeq` / `lastEventId` / `/status` **零命中**。`assistant/page.tsx` 使用 `fetch` + `resp.body.getReader()` 一次性消费。

现状与缺口：

- **无重连**：`fetch` 不具备 `EventSource` 的内置重连与 `Last-Event-ID`；流中断则 `reader.read()` 抛异常
- 刷新走"从 DB 重放历史消息"（可用，且 `pendingConfirm` 会重建 HITL 卡片），但**不会接回正在执行的流**
- durable-sse-plan §5.3"EventSource 内置重连 + Last-Event-ID 天然支持"在选型上不成立——前端用的是 `fetch`。该文档阶段 3（前端）未实施

服务端已就绪的部分：每帧已发 SSE `id`（`SessionEventBus.toSSE` 的 `.id(String.valueOf(e.seq()))`）；心跳 `: hb` comment 帧已在发送。

前端需补：重连机制（`fetch` 退避重连 + 游标，或改用 `EventSource` 走 `GET /subscribe` 并白拿内置重连与 `Last-Event-ID`）；游标记录（`fetch` 读原始流需自行解析 `id:` 行）；刷新后按 `GET /status` 决定是否接回；`interrupted` 态的提示。

> **排期约束**：阶段 3 上线后若前端未实现重连，则无消费方。反之，若前端先实现重连而阶段 1-3 未完成，会直接撞上 F2 的悬挂。顺序应为：**阶段 1+2 → 阶段 3 → 前端重连**。

### 3.5 阶段 4（可选项）：合并文本攒批

仅当行数或回放体积成为瓶颈时再做。需同时改动：游标语义改 inclusive（保证部分填充的 batch 可被重放）、前端重连时必须**重建**末条 assistant 消息而非追加。可参考 §2.5 的对比表。

---

## 4. 兼容性与影响面

| 项 | 影响 |
|---|------|
| SSE 词表 | 零改动 |
| 前端渲染逻辑 | 零改动（阶段 1-3） |
| `session_event` 表结构 | 零改动 |
| 对外 HTTP 契约 | 阶段 1-2 零改动；阶段 3 新增 `interrupted` 态、`subscribe` 语义不变（`afterSeq` 仍为 exclusive `seq > cursor`） |
| **HITL 流行为** | **唯一的行为变更**：`permission_ask` 时流关闭（原为保持打开）。已核对前端不依赖流持续打开，见 §3.4.4 证据表 |
| `TurnLeaseStore` | 新增 `isHeld()` 已于 durable-sse-plan 交付并存在 |
| 单副本部署 | 全部改动向后兼容，单副本下同样受益（F4/F5/F6/F7/F8 均与副本数无关） |

---

## 5. 验证方案

### 5.1 阶段 1

1. seq 计数器：多次 emit 后 seq 连续无重复；**HITL 跨 turn 交接后 seq 不重置**（模拟不同 replyId 的新 turn 在同一 session 上）
2. 多值 INSERT：单 turn 语句数从 ~4200 降至 ~10 量级（可用 SQL 计数或日志断言）
3. I1 不变量：在 delta 缓冲未满时插入 `permission_ask`，断言 DB 中 seq 无空洞
4. `evictStaleSinks`：`fixedDelay` 生效；无订阅者的 sink 在 60s 内被回收
5. `turnStatus`：构造"DB 中 lease 被持有但本 Pod 无 sink"的场景，断言返回 WORKING

### 5.2 阶段 2

6. `queryAfter`：以慢消费（`delayElements`）驱动回放，断言连接在推送期间已归还（观察池的 active 连接数）
7. `/status`：断言借用次数下降

### 5.3 阶段 3

8. 双副本模拟：Pod A 执行 + Pod B subscribe，断言实时事件到达、turn 结束时流正常关闭（对照当前行为：悬挂）
9. HITL：`permission_ask` 后（另一副本）subscribe，断言以 done 帧正常关流，且**不**被标记为 `interrupted`
10. 崩溃场景：kill 执行副本，断言 60s 后 `/status` 返回 `interrupted`、subscribe 流以 `interrupted` 帧关闭
11. HITL 关流：`permission_ask` 时 `closeSession` 被调用，订阅方收到 done 帧

---

## 6. 风险与缓解

| # | 风险 | 概率 | 影响 | 缓解 |
|---|------|------|------|------|
| R1 | 计数器 seed 时机错误导致 seq 冲突 | 中 | 回放错乱、数据覆盖 | 必须在 `turn_lease` 获取后 seed；HITL 新 turn 重新 seed；代码注释写明依赖 I2；#1 用例覆盖 |
| R2 | 攒批导致 Pod 崩溃时丢失 ≤1 个 flush 窗口的 delta | 中 | 断线回放缺尾部少量文本 | 窗口设小（300ms）；里程碑事件同步落库（I1）；`AGENT_END` 必须同步 |
| R3 | 多值 INSERT 受 `max_allowed_packet` 限制 | 低 | 落库失败 | 批量上限按 payload 估算并留足余量；上限可配置；失败时降级为逐条 |
| R4 | **阶段 3 完成前开启 replicas>1** | 高 | F1/F2 直接触发（悬挂至凌晨 3 点） | **阶段 1+2 完成不代表可开多副本**；必须等阶段 3；发布流程中显式注明 |
| R5 | 阶段 3 上线后前端未实现重连 | 高 | 改动无消费方，收益不可见 | 阶段 3 与前端排期对齐；服务端可先用集成测试验证 |
| R6 | 游标轮询增加 DB 读负载 | 低 | — | 仅重连路径触发；~3.8 qps/观察者（参照 §附录）；稳态查询返回 0 行、走 `uk_session_seq` 亚毫秒 |
| R7 | `interrupted` 判据误伤合法长 turn | 中 | 正常任务被标记中断 | 判据含 `findPendingConfirm` 分支；lease TTL 60s + 续约 20s 留有充足余量 |

---

## 7. 实施步骤

### 阶段 1：服务端内部优化（零契约变更）

1. `SessionEventStore`：seq 内存计数器 + seed 接口（turn 开始时由调用方在 lease 获取后触发）
2. `SessionEventStore`：多值 INSERT 攒批 + `flushPending()` + 里程碑同步刷（I1）
3. `SessionCleanupService` / `SessionEventBus`：`evictStaleSinks` 独立 `@Scheduled(fixedDelay = 60s)`
4. `SessionEventBus.turnStatus`：判据改为 lease + 最新事件类型
5. 单测 + 用例 #1-#5

### 阶段 2：健壮性修复

6. `SessionEventStore.queryAfter`：页内 materialize + `LIMIT` + 分页续读
7. `SessionStreamController.status`：合并查询
8. 用例 #6-#7

### 阶段 3：跨副本正确性

9. `SessionEventBus.subscribe`：DB 游标追赶（§3.4.1）
10. 终止判定（§3.4.2）+ HITL 处 `closeSession`（§3.4.4）
11. `SessionStreamController.status`：`interrupted` 态
12. `subscribe()` 移除 `ensureSink()` 调用
13. 用例 #8-#11

### 阶段 4：前端重连（另立排期）

14. 重连机制 + 游标记录 + 恢复判定 + `interrupted` 提示

---

## 8. 设计自检清单

- [x] F1-F8 均有代码位置与机制说明，非推测
- [x] F2 更正了 durable-sse-plan §9 R2 的"5 分钟兜底"（实为每日凌晨 3 点）
- [x] F3 更正了"`/status` 因 `leaseHeld` 而幸免"（`||` 语义下本地误报会赢）
- [x] Redis 与粘性路由均给出否决理由**及重新评估触发条件**（§2.3）
- [x] 采纳方案明确了"写入方消费本地 sink、观察者读 DB"的规则（I4）
- [x] 攒批方案保住了实时流观感（打字机效果）与 seq / 线协议
- [x] 关键不变量 I1 给出了实现约束（里程碑前先 flush）
- [x] 阶段划分保证每阶段可独立验证；阶段 1+2 不依赖任何未决问题
- [x] HITL 终止判定有明确判据（复用 `confirm_context`）
- [x] HITL 关流语义的实现/文档不一致已决策（关流），并附前端证据
- [x] 前端缺口经实测确认（grep 零命中），并给出排期约束
- [x] R4 显式标注"阶段 1+2 完成不代表可开多副本"

---

## 附录 A：DB 压力测算

### A.1 写侧（已存在，与本文建议无关）

一次 2000-token 回答 ≈ 2100 个事件；`append()` 每事件 1 次连接借用 + 2 语句。

| 方案 | 单 turn 语句数 | 降幅 |
|------|---------------|------|
| 现状 | ~4200 | — |
| + seq 计数器 | ~2100 | -50% |
| + 多值 INSERT 攒批 | **~11** | **-99.7%** |

摊在 ~40s 上：现状 ≈ 50 语句/秒/活跃会话。

### A.2 读侧（本文新增）

每个重连观察者：事件查询 300ms → 3.3 qps；空闲时 lease 检查 ~2s → 0.5 qps；合计 **~3.8 qps**。查询走 `uk_session_seq (session_id, seq)`（2026-09-16 由普通索引改为唯一键，前缀相同故访问路径不变；前缀匹配 + seq 范围，索引序天然满足 `ORDER BY seq`），稳态返回 0 行，亚毫秒。

100 个并发重连观察者 ≈ 380 qps，对比同规模写侧（100 活跃会话 ≈ 5000 语句/秒）为 **~8%**。

### A.3 结论

DB 压力在**写侧**，且为既有问题；读侧新增量级小一个数量级。写侧优化同时降低读侧——多值 INSERT 不改变行数，但 §3.5 的合并文本方案若实施，单 turn 回放体积可从 ~1MB / 2000 行降至几 KB / 数十行。

**另需注意**：连接池默认仅 **10**（`AgentManagerProperties.java:214`），F6 的"回放全程持有连接"是比查询量更实际的风险，已在阶段 2 处理。
