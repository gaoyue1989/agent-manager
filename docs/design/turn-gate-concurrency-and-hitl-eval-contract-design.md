# 并发 turn 排队根治（#87）与评测 HITL 契约对齐（#86）设计

| | |
|---|---|
| 对应 Issue | [#87](https://github.com/gaoyue1989/agent-manager/issues/87)（并发 turn 启动排队 + 无等待反馈）、[#86](https://github.com/gaoyue1989/agent-manager/issues/86)（HITL ask 段帧序契约漂移，评测假阴性） |
| 状态 | 已实施（2026-10-01，M1~M4 全部落地；mvn test 1353 例 0 失败，flywheel selftest 绿，V9 迁移真库验证通过；e2e/bench C 档待集群环境回归） |
| 编制时点 | 2026-10-01，基于 master `3e6903b`、agentscope SDK 2.0.3（源码级考古，证据内联） |
| 分期 | M1（#86 评测侧）→ M2（#87 根治）→ M3（#87 兜底与观测）→ M4（回归防线）；实施时一次落地，提交评审时仍按期拆分 |

---

## 0. 摘要

两个问题同源于 2026-10-01 评测飞轮一轮（`3e6903bf-trend-20261001-192737`），但根因完全独立：

- **#87 根因（本设计已定位到源码行级）**：`/threads/chat` 全部请求经 `ChatUiChannel.create()`（默认 `DmScope.MAIN`）进入 SDK 网关，路由层把所有 DM 折叠成**同一个 canonicalKey**（`"chatui|x:agentId=main"`，常量）；`HarnessGateway.runStream` 用该 key 做 turn 闸门（`LocalSessionTurnGate`：每 key 一把 `Semaphore(1, true)`），**整 turn 持有**（订阅时阻塞 acquire，`doFinally` 才释放）→ 全进程所有会话的 turn 在一把全局公平信号量上串行。排队时长 = 在途 turn 剩余时长（与长 LLM 流式强相关），实测 17s/47s/69s/169s 全部吻合「恰为前一路 turn 结束时刻」。根治方案：Channel 切 `PER_PEER`（peer 即平台 sessionId）→ 闸门自动变**每会话一把**；配套 `agent_state` 存量键 Flyway 重键迁移（V9）与 HITL 恢复身份推导变更。另加 waiting 心跳兜底与首帧直发，防任何未来排队点回归。
- **#86 根因（issue 已定界，本设计给实现细节）**：4db16ba（2026-09-27）有意变更 ask 段收尾（挂起点不再提前 release+关流，改由 SDK 自然收尾），主段终帧从 `permission_ask` 变为 `AGENT_END`（`permission_ask → REQUEST_STOP → AGENT_RESULT → AGENT_END`）；评测执行器以「terminal == permission_ask」判定挂起，恒为 False → `auto_confirm` 永不触发。修复：判定改为「段内出现 `permission_ask` 且被 ask 的 tool_call 在本段内无 `TOOL_RESULT_END` 配对」（新旧行为兼容），同步契约文档、用例期望与 selftest，并给趋势账本加断代注记。

---

## 1. 问题陈述

### 1.1 Issue #87：同实例并发 turn 启动排队且零反馈

- 评测 workers=2 下 `case_gen_config_01#2` 125s 整体超时且**全程零帧**（连 `session_created` 都未到达）；服务端 agent 执行完全正常，turn 实际开始时刻晚 69s——恰为同实例另一消息 turn 结束的时刻。
- 独立复现：3 路并发时第 2 路 `AGENT_START` @169s（恰为另一路 turn 结束时刻）；2 路并发时排队 17s。排队时长与在途**长 LLM 流式调用**正相关，长推理消息必现数十秒级。
- 现有 `waiting` 帧只覆盖**平台侧 per-session turn 租约**（`ChatStreamController` 的租约等待循环，`ChatStreamController.java:304-316`）；本排队发生在租约之后的 SDK 层，租约空闲 → 客户端静默。
- 后果：用户侧长时间静默 → 重复发送；评测侧排队叠加执行时间 → 120s 用例超时**误报**；横向扩容每副本有效并发 ≈ 1，扩容收益被锁死。

### 1.2 Issue #86：HITL ask 段终止帧序契约变更，评测执行器未跟进

- 4db16ba 修复 HITL 恢复收尾窗口竞态后，主段帧序实测变为：
  `..., TOOL_CALL_END, tool_call_summary, MODEL_CALL_END, permission_ask, REQUEST_STOP, AGENT_RESULT, AGENT_END`
- `bench/eval/executor/runner.py:91`：`pending_ask = view_probe["terminal"] == MAPPING["hitl"]["ask_frame"]` —— terminal 现在恒为 `AGENT_END` → `auto_confirm` 永不触发 → `case_hitl_publish_confirm_001` 2/2 假阴性，所有 `hitl_policy=auto_confirm` 用例全灭，趋势账本 09-25 之后 HITL 口径断代。
- 前端不受影响（事件驱动，收到 `permission_ask` 即渲染确认卡）；多副本 HITL 探针 9/9 全过（4db16ba 要修的问题确实修好了）。

---

## 2. Issue #87 根因分析（源码级证据链）

### 2.1 串行点定位

以下均为 agentscope SDK 2.0.3 源码（`agentscope-harness-2.0.3-sources.jar`）与平台代码的实读结论：

```
POST /threads/chat
 └─ ChatStreamController.chat（ChatStreamController.java:411-415）
     └─ chatChannelSupplier.get()                            ← ChannelConfig.java:24-29
        = runtimeService.getAgent().channel(ChatUiChannel.create())
        └─ ChatUiChannel.create()（ChatUiChannel.java:87-89）
           = ChannelConfig.of("chatui") → dmScope = DmScope.MAIN（默认，DmScope.java:38-40）
     └─ channel.sendStream(ChatUiRequest.withPeer(sessionId, messages))
        └─ buildInbound → InboundMessage.dm("chatui", peerId=sessionId)（ChatUiChannel.java:477-487）
     └─ HarnessGateway.runStream（HarnessGateway.java:256-276）
         gateKey = ctx.canonicalKey()
         └─ ChannelRouter.buildDmContext（ChannelRouter.java，类注释明示）
            “DM + DmScope.MAIN — channel field only; all DMs share one session”
            → canonicalKey = "chatui|x:agentId=main"   ← 常量！所有会话同值
         └─ withGatedStream(gateKey, ...)（HarnessGateway.java:722-748）
             Flux.defer(() -> { lease = sessionTurnGate.acquire(gateKey); ... })  // 阻塞 acquire
                 .doFinally(sig -> lease.close())                                 // 流终止才释放
                 .subscribeOn(Schedulers.boundedElastic())
         └─ LocalSessionTurnGate（LocalSessionTurnGate.java:32-36）
             gates.computeIfAbsent(key, k -> new Semaphore(1, true)); semaphore.acquire();
```

**结论：全进程所有 `/threads/chat` turn 竞争同一把 `Semaphore(1, true)`（公平、单许可），且从订阅持到流终止（整 turn）。**

平台侧旁证（平台自己的注释早已记录这一事实，只是从未把它与「并发串行」关联）：

- `AgentRuntimeService.java:623-625`：「框架 Channel 通道走 ChatUiChannel 默认配置（DmScope.MAIN、globalDefaultAgentId=main），MsgContext.canonicalKey() = `"chatui" + "|x:agentId=main"`（extra 按 key 排序）。**同进程所有 peer 共享同一会话 id**」；
- `SessionKeyResolver` javadoc：「Channel 链路下 ctx.sessionId 是全进程共享的网关 gw-hash」；
- `AgentRuntimeService.channelGatewaySessionId()`（609-636 行）复刻推导出共享会话 id `gw-3f20f08c5499`。

### 2.2 观测证据逐条对账

| 实测现象（issue #87） | 全局闸门解释 |
|---|---|
| 排队时长 = 在途 turn 剩余时长（17s/47s/69s/169s，`AGENT_START` 恰为另一路 turn 结束时刻） | 公平信号量 FIFO 出队，前持有者 `doFinally` 释放的瞬间即是排队者 `acquire` 返回、`AGENT_START` 发出的时刻 |
| 与长 LLM 流式强相关；短工具轮次不明显 | 闸门**整 turn 持有**，长推理 turn 持有 2~3 分钟 |
| 平台 turn 租约空闲、现有 waiting 帧不发 | 排队点在平台租约（`TurnLeaseStore`，per-session、`turn_lease` 表）**之后**的 SDK 层 |
| E3 观察到「排队 17s 后与 req1 真并发」 | 与严格互斥表面矛盾，判定依据是两连接均在出帧；不排除实验窗口内残留其他在途/孤儿 turn 的出队交错。属**待实证项**（§2.4 取证脚本会在排队窗口内抓线程转储定谳），不影响主根因链成立 |
| 评测 #2 整 turn 零帧（连 `session_created` 未达） | 独立二级症状，见 §2.3 |

### 2.3 二级症状（零帧）假说

`withGatedStream` 的 `subscribeOn(boundedElastic)` 使**每个排队 turn 阻塞占用一个 boundedElastic 线程**（`semaphore.acquire()` 长期 park）。而 `ChatStreamController` 的外层 `Flux.create(...)` 同样 `subscribeOn(boundedElastic)`（`ChatStreamController.java:462`）——`session_created` 是在**该 lambda 的第一行**才发出的。当 boundedElastic（默认 `10×CPU` 线程）被排队 waiter、孤儿 turn 看护、其他阻塞调用累积占满时，外层 lambda 本身被排队 → **首帧推迟到 2.6 分钟**，与评测观察吻合。此为待证假说（未定界点 2），§3.3 的「首帧直发」改造无论成因如何都消除该症状，§2.4 提供取证手段。

### 2.4 取证方案（修复前固定现场，M3 交付）

新增 `agent-framework/bench/diag/turn-queue-jstack.py`（配合 FLYWHEEL.md §2.0 本地栈专用实例）：

1. 复刻 issue #87 的复现脚本：req1 发长推理消息，+2s 后 req2 并发；
2. req2 发出后每 2s 对被测进程执行 `jcmd <pid> Thread.dump`（容器内 1 号进程），直至 req2 收到 `AGENT_START`，转储落盘；
3. **预期（缺陷形态）**：转储中出现 `boundedElastic-xxx` 线程 WAITING（park），栈形如 `Semaphore.acquire → LocalSessionTurnGate.acquire → withGatedStream lambda`；修复后该栈消失、req2 `AGENT_START` 延迟 <10s；
4. 同时输出两路 `session_created/AGENT_START/AGENT_END` 时刻表。

> 该脚本同时是 §6 验收的对照工具：修复前红、修复后绿，作为根因闭环证据归档进 issue #87。

---

## 3. Issue #87 修复设计

### 3.1 方案对比与决策

| 方案 | 做法 | 优点 | 缺点 / 风险 | 决策 |
|---|---|---|---|---|
| **A. PER_PEER 每会话闸门** | Channel 配置切 `DmScope.PER_PEER`（peer 即平台 sessionId） | 语义正确：闸门、SDK 会话 id、状态存储全部按会话隔离；消除共享 gw-hash 这个历史包袱的根源；进程内防线保留（同会话仍互斥） | canonicalKey 变化 → SDK 会话 id 变化 → `agent_state` 存量键需重键迁移；`channelGatewaySessionId()` 推导、HITL 恢复身份、若干注释/兜底路径需同步 | **采用（M2）** |
| B. 直通闸门 | 经 `DistributedStore.sessionTurnGate()`（`HarnessAgent.ensureGateway` 已有 hook，HarnessAgent.java:695-704）注入 no-op gate，串行完全交给平台 `TurnLeaseStore` | 改动最小，不动任何键 | 失去进程内防线：不走平台租约的入口（`runWakeup` 唤醒 turn、skill curator、未来入口）可与 chat turn 并发写同一 SDK 会话状态；`isSessionRunning` 语义失效 | 备选（仅当 A 的迁移演练失败时回退） |
| C. 仅缓解 | 只加 waiting 心跳，不动闸门 | 零风险 | 并发能力仍 ≈1，扩容收益锁死，评测超时误报依旧 | 不采纳为终态；心跳作为兜底保留（M3） |

选 A 的关键前提核查（已完成）：

- **跨副本串行不依赖 SDK 闸门**：`LocalSessionTurnGate` 本就是进程内的；跨副本 per-session 互斥由 `TurnLeaseStore`（`turn_lease` 表，chat 与 confirm 两侧都先抢租约）承担，切 PER_PEER 后不变；
- **workspace / agent_fs 不受影响**：KV 命名空间是 `agents/{agentName}/users/{userId}/...`（`AgentScopeConfig.java:116-118`），只按 userId（=peer）分区，与 sessionId 无关；
- **A2A / invokeStream 链路不经网关**（`AgentRuntimeService` 注释：「A2A/invoke 流程无网关路由」），不受 scope 变化影响；
- **SDK 状态表在 Flyway 管辖内**：`agent_state` 在 V1 基线（`db/migration/V1__baseline_e91d1f0.sql:15`），重键可走标准 Flyway 迁移，多副本启动由历史表锁互斥；
- `hasPendingConfirm` 不依赖闸门的 `isRunning`（读 `confirm_context` + `agent_state`，`AgentRuntimeService.java:764-773`）。

### 3.2 方案 A 详细设计

#### 3.2.1 Channel 切换（`ChannelConfig.java`）

两处构造点同步替换：

```java
// ChannelConfig.java:15-17（启动期 Bean）与 :24-29（provider）
return agent.channel(ChatUiChannel.create());
// 改为
return agent.channel(ChatUiChannel.perPeer());   // DmScope.PER_PEER，每 peer（=sessionId）独立会话键
```

切换后路由层 `buildDmContext` 令 `room = peerId`，canonicalKey 变为：

```
"chatui|r:<sessionId>|x:agentId=main"
```

（peerId 即 `ChatStreamController` 传入的 `ChatUiRequest.withPeer(finalSessionId, ...)` 的 finalSessionId，已 `PathSafe.sanitize`。）`LocalSessionTurnGate` 随之自动变为**每会话一把信号量**——跨会话零互斥，同会话仍进程内互斥（与平台租约形成双层防线）。

#### 3.2.2 会话身份推导变更（`AgentRuntimeService`）

- `channelGatewaySessionId()`（627-636 行）从常量推导改为**按会话**推导：

```java
/** 复刻 HarnessGateway 的网关会话 id 派生（PER_PEER 形态）：
 *  canonicalKey = "chatui|r:" + peerId + "|x:agentId=main" → "gw-" + SHA-256 前 6 字节 hex（12 字符） */
private String channelGatewaySessionId(String peerId) {
    var canonicalKey = "chatui|r:" + peerId + "|x:agentId=main";
    ... // 与现有 SHA-256/HexFormat 推导一致
}
```

- `storeConfirmContext`（609-618 行）改传 `rawSessionId`；
- **必须用单测钉住推导与 SDK 一致**：集成测试里用 perPeer channel 真跑一 turn，读 `agent_state` 落库行键，断言等于 `normalizeUser(sid) + ":" + channelGatewaySessionId(sid)` 复合键（防 SDK 升级时静默漂移；同时覆盖 `normalizeUser` 对 PathSafe 会话 id 是否恒等的核实）。

#### 3.2.3 存量数据迁移（Flyway `V9__per_peer_session_rekey.sql`）

`agent_state.session_id` 现为复合键 `{normalizeUser(peer)}:{gw-hash}`，peer 即平台 sessionId（前缀分量），因此**旧行自含重键所需的全部信息**，纯 SQL 可完成，无需跨表知识：

```sql
-- 仅迁移 Channel 链路共享 gw-hash 形态；A2A（{tenant}__{tid}，无 ':' 分量）与其他形态不受影响
UPDATE agent_state
SET session_id = CONCAT(
      SUBSTRING_INDEX(session_id, ':', 1), ':gw-',
      SUBSTRING(SHA2(CONCAT('chatui|r:', SUBSTRING_INDEX(session_id, ':', 1),
                           '|x:agentId=main'), 256), 1, 12))
WHERE session_id LIKE '%:gw-3f20f08c5499';
```

- MySQL `SHA2(...,256)` 返回 64 字符小写 hex，取前 12 字符即 SDK `HexFormat.of().formatHex(digest, 0, 6)` 的等价形态；
- 幂等：迁移后不再有行匹配 `LIKE '%:gw-3f20f08c5499'`，重跑无操作；
- 遵循 db-migration-flyway-design §7 纪律：新写形态（PER_PEER 键）与存量回填同版本配套；**禁止**修改已合并 V 文件；
- 回滚（升级失败需退回旧镜像时）反向执行一次（旧键同样可由新行自含推导），见 §8；
- `session_message` 归档表**不迁移**：读取侧本就是 5 形 LIKE 兜底（`SessionMessageStore.findPage`，`sid:%` 前缀形态命中新旧行）；`agent_fs`/workspace 按 userId 分区不受影响。

#### 3.2.4 HITL confirm 兼容（升级窗口在途确认）

`confirm_context.runtime_session_id` 存量行仍是共享 `gw-3f20f08c5499`。恢复读取处（`consumeConfirmContext` → `buildResumeContext`）加一层翻译：

```java
// 旧行兼容：runtime_session_id 为共享 gw-hash 时按 runtime_user_id（=peer=sid）重推导 PER_PEER 会话 id
if (LEGACY_SHARED_GW_SESSION_ID.equals(row.runtimeSessionId()) && row.runtimeUserId() != null) {
    runtimeSessionId = channelGatewaySessionId(row.runtimeUserId());
}
```

覆盖升级瞬间挂起中的确认卡（预期个位数）；新行天然携带新会话 id。

#### 3.2.5 影响面核查清单（实施时逐项确认并补测试/注释）

| 触点 | 影响 | 动作 |
|---|---|---|
| `SessionKeyResolver` / `FrameworkTracingMiddleware` / `LlmLoggingMiddleware` | ctx.sessionId 从共享 gw-hash 变为每会话 gw-hash，**userId 仍=peer**，反查逻辑不变 | 更新 javadoc 表格；行为回归靠既有单测 |
| `AgentStateReader` / `SessionMessageStore` 多形态匹配 | 5 形 LIKE 天然兼容新键 | javadoc 注明新形态；回归测试 |
| `SessionModelMiddleware.candidateKeys` | 「先 sessionId 后 userId」——Channel 链路 sessionId 仍非平台 sid，回落 peer 逻辑不变 | 既有单测回归 |
| `McpUserContextMiddleware` / `RemoteUserIdMiddleware` / `RemoteSpawnCaptureMiddleware` | 均按 peer/sid 反查，不变 | 回归 |
| e2e R5（跨副本 HITL）、e2e-multi 双副本门禁 | 恢复身份改为每会话推导后必须仍绿 | 门禁必须项 |
| Debug Console / `/threads` 列表 | 读平台自有表 + 多形态兜底，不变 | 回归 |
| 评测 frame-mapping | 帧词表无变化 | selftest 回归 |

### 3.3 兜底与体验（M3，独立于根因修复可先行）

#### 3.3.1 跨层排队 waiting 心跳（issue #87 修复方向 2）

排队点未必只有闸门一处（模型侧限流、连接池等任何未来回归都会重现静默）。在 `ChatStreamController` 增加「首事件前心跳」：

- 位置：`sendStream(...).subscribe(...)`（411-431 行）调用点；`ScheduledExecutorService`（独立单线程 daemon，**绝不占用 Reactor/boundedElastic 线程**——沿用 `OrphanTurnWatchdog` 的调度纪律）；
- 行为：订阅后每 `WAITING_FRAME_INTERVAL`（15s）向 **sink 直发**一帧 `waitingSSE()`（不进 EventBus、不落库，与现有租约等待帧完全同词表、前端零改动），直到本 turn **首个 SDK 事件**进入 `handleEventAndEmit`、或 turn 终态（endTurn/error/onCancel）即取消；
- 帧语义：`{"type":"waiting"}` 不变（复用既有合成帧，frame-mapping 不动）；不下发 queue_position（单进程内排队位次对跨副本场景无意义，YAGNI）。

#### 3.3.2 首帧直发（二级症状，无论成因一律消除）

`session_created` 的发出不再依赖 boundedElastic 可用性：

```java
var body = Flux.<ServerSentEvent<String>>create(sink -> { ...原逻辑，去掉 emitSessionCreated 段... })
    .subscribeOn(Schedulers.boundedElastic());
return emitSessionCreated
    ? Flux.just(sessionCreatedSSE(finalSessionId)).concatWith(body)   // 首帧在订阅线程同步发出
    : body;
```

`Flux.just` 首元素不经调度器排队，MVC 异步桥一订阅即写出——消除「连 session_created 都未达」的形态。

#### 3.3.3 可观测性埋点

- `handleEventAndEmit` 首事件时打点：`[chat] turn first-event after {}ms (sid={}, rid={})`，超 10s 升 WARN——排队/静默类问题从此有直接日志证据；
- （可选）Micrometer 指标：`chat.turn.first.event.delay` histogram、boundedElastic 池活线程/队列深度 gauge，接入现有 OTel 链路。

---

## 4. 回归防线与评测侧改进（#87 修复方向 3/4，M4）

### 4.1 mock-llm 长推理场景

`bench/mock-llm/server.js` 新增场景标记 `[BENCH:slow]`：命中后以流式 thinking/text delta 分片输出，总时长 `MOCK_LLM_SLOW_MS`（默认 30s，可调），再收尾；`/stats` 计数口径不变。用于构造确定性「长 LLM 流式」在途 turn。

### 4.2 bench/load 并发启动延迟档（C 档）

`bench/load/runner.js` 新增场景组 C（区别于 B 系闭环容量口径，专测**启动排队**）：

- 场景：`[BENCH:slow]` 消息 + `--session-pool = concurrency`（每路独立会话，规避同会话租约串行的合理排队）；
- 发压形态：ramp=0 一波齐发（C=2/4/8 三档，`--stage` 遍历）；
- 指标：每路 `AGENT_START` 相对请求发出时刻的延迟 `agent_start_delay_ms`，写入 `{stage}.jsonl` 与 `{stage}.summary.json`（分布：p50/p95/max）；
- 断言门：`--assert-start-delay-ms`（默认 10000）：**任一路超阈值即该档 FAIL**（本轮坏值基线 17s/47s/69s/169s，修复后预期亚秒级）；
- 入口：`run-bench.sh` 增加 C 档子命令；CI 集成为**非必需 job**（对齐「新增 job 默认不是必需检查」的仓库惯例，待数据稳定后再议转必需）。

### 4.3 评测报告「首帧/启动延迟」单列（区分环境排队与用例慢）

- `executor/sse_client.build_view` 视图增加 `first_event_ms`（首事件 t_ms，`session_created` 除外）与 `agent_start_ms`（`AGENT_START` 的 t_ms，无则 null）；
- `flywheel._summary_row` 透传两字段进 `summary.jsonl`；`report.md` 用例表增加列，`agent_start_ms > 15000` 标 `⚠️ 启动排队嫌疑`；
- `analyzer/rca.classify_failure` 新增分类「启动排队类」（timeout 且 `agent_start_ms` 缺失或超阈值 → 归环境排队而非用例失败），直接消除 #87 形态的误报判定。

---

## 5. Issue #86 修复设计（M1，纯评测侧，可独立先行）

### 5.1 判定逻辑修订（`executor/sse_client.py` + `executor/runner.py`）

**核心原则：挂起判定不依赖终帧，改为「内容配对」——对新旧帧序都成立（≤4db16ba 旧序以 `permission_ask` 终止时，被 ask 的 tool 同样本段无 `TOOL_RESULT_END`）。**

`sse_client.build_view` 增强（hitl 视图段）：

```python
# 迭代中累计：asked_ids = 各 ask 帧 tool_calls 的 tool_call_id 并集
#             result_ended_ids = 本段 TOOL_RESULT_END 的 toolCallId 集合（既有 calls_by_id 已有 result_state，等价可推）
view["hitl"]["pending"] = bool(asked_ids) and bool(asked_ids - result_ended_ids)
```

`runner.execute_once`（84-101 行）：

```python
pending_ask = view_probe["hitl"]["pending"]          # 原：terminal == ask_frame
# 终帧合法性同步放宽：ask 段合法终帧集合 = {permission_ask, AGENT_END}（旧序 / 4db16ba+ 新序）
if pending_ask and terminal not in {permission_ask, AGENT_END}:
    error_info = f"ask 段异常终帧: {terminal}"        # 记录进轨迹，供 RCA 归类
```

续段合并逻辑（confirm-stream 调用、`t_offset_ms` 衔接、`hitl_confirmed` 判定）不变。合并后终帧为续段 `AGENT_END`，`status=success` 路径不变。

### 5.2 契约文档同步（`config/frame-mapping.json` + `bench/eval/README.md`）

- `frame-mapping.json`：`version` → `2026-10-01`；`notes` 新增：

  > `"hitl_ask_terminal"`：自 4db16ba（2026-09-27）ask 段帧序为 `permission_ask → REQUEST_STOP → AGENT_RESULT → AGENT_END`（终帧 `AGENT_END`）；旧序以 `permission_ask` 终止。挂起判定不得依赖终帧，须按「ask 存在且被 ask 的 tool_call 无 `TOOL_RESULT_END` 配对」。

- `hitl` 段增加 `"ask_segment_terminal_frames": ["permission_ask", "AGENT_END"]`（消费方唯一事实源化）；
- `terminal_frames` 数组**不动**（`permission_ask` 保留以兼容旧轨迹回放判定）；
- README「关键事实」HITL 条目改写为新帧序 + 注明自 4db16ba 起 + 新旧兼容说明。

### 5.3 用例库修订（`cases/case_hitl_publish_confirm_001.json`）

新契约下主段+续段合并轨迹有**两个** `AGENT_END`（主段收尾 + 续段收尾）：

```json
"frames": { "permission_ask": 1, "tool_call_summary": ">=1", "error": 0, "AGENT_END": 2 }
```

`ground_truth` 同步措辞（「主段止于 AGENT_END（4db16ba 起）、confirm 续段恢复执行」）。

### 5.4 selftest 扩充（`flywheel.cmd_selftest`）

现有 HITL 断言（440-448 行）是旧序形态（`AGENT_END@50` 之后的 `permission_ask@70`），保留作旧序兼容用例；新增新序合成轨迹：

```python
new_seq = [ ...TOOL_CALL_END, MODEL_CALL_END,
            permission_ask@70(含 tool_call_id), REQUEST_STOP@71, AGENT_RESULT@72, AGENT_END@73 ]
# 断言：terminal == "AGENT_END"（非 permission_ask）、hitl.pending is True、asks 解析正常
# 合并续段（USER_CONFIRM_RESULT + TOOL_RESULT_END(SUCCESS) + AGENT_END）后：
#       hitl.confirmed is True、tool_result[publish_service].ok、AGENT_END 计 2
```

### 5.5 趋势账本断代注记

- `history.jsonl` 行新增可选字段 `"contract"`（如 `{"hitl_ask_seq": "v2(4db16ba+)"}`），本轮起写入；`flywheel status` 渲染；
- README「已知问题」新增条目：09-25 轮与 10-01 轮 HITL 轨迹口径不同（09-25 后至本修复前，HITL 用例数据假阴性、不可比）；
- `report.md` 头部口径注记列出 contract 字段值。

---

## 6. 测试计划与验收标准

| 层 | 内容 | 验收 |
|---|---|---|
| 单测（Java） | `channelGatewaySessionId(sid)` 与 SDK 实跑落库键一致性探针；V9 迁移（存量库 → 键重写 → 幂等重跑）；waiting 心跳（首事件即停、终态即停、不进 EventBus）；首帧直发；confirm 旧 runtime_session_id 翻译 | `mvn test` 全绿（现网基线 1026 例 0 失败不得回退） |
| e2e | 核心 / 多副本（含 R5 跨副本 HITL，15/15）/ 沙箱 / 协议多副本 / 工具插件 | 七项必需检查全绿 |
| 诊断脚本 | `turn-queue-jstack.py`：修复前转储含 `LocalSessionTurnGate.acquire` 阻塞栈、修复后消失 | 双向各跑一次，证据归档 issue #87 |
| bench C 档 | C=2/4/8 × slow 30s，`agent_start_delay_ms` | **P100 ≤ 10s**（坏基线 17~169s） |
| 评测 | 专用实例 `flywheel.py run --only case_hitl_publish_confirm` | 2/2 PASS；`--judge` 分数恢复正常区间 |
| 升级演练 | 存量库（含旧键 agent_state 行 + 在途 confirm 行）跑 V9 + 兼容翻译 | 存量会话续聊上下文保留（抽 1 会话手工核）；在途确认可恢复 |

## 7. 实施分期与交付物

| 期 | 内容 | 交付物 | 依赖 |
|---|---|---|---|
| M1 | #86 全部（§5） | `executor/{runner,sse_client}.py`、`config/frame-mapping.json`、`README.md`、`cases/case_hitl_publish_confirm_001.json`、`flywheel.py`（selftest/status/contract 字段） | 无，可立即先行 |
| M2 | #87 根治（§3.2） | `ChannelConfig.java`、`AgentRuntimeService.java`、`db/migration/V9__per_peer_session_rekey.sql` + 单测 | 建议在 M1 后（评测口径先修复，M2 验证可依赖可信的 HITL 用例） |
| M3 | 兜底与观测（§2.4/§3.3） | `ChatStreamController.java`（心跳/首帧/打点）、`bench/diag/turn-queue-jstack.py` | 可与 M2 同 PR 或独立 |
| M4 | 防线（§4） | `bench/mock-llm/server.js`、`bench/load/runner.js`、`run-bench.sh`、eval 视图/报告/RCA 字段 | 依赖 M2 合入后数据才有意义（阈值断言） |

## 8. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| PER_PEER 键推导与 SDK 实际不一致（normalizeUser 差异等） | §3.2.2 探针单测钉死；V9 WHERE 精确匹配旧共享 hash，仅迁移已证形态 | 回滚镜像 + 反向 UPDATE（新行同样自含旧键推导所需信息） |
| 迁移后个别会话丢上下文 | V9 幂等 + 升级演练（§6）；影响面限于 SDK 会话记忆，平台侧 history（session_message/EventBus）不受影响 | 反向迁移可恢复 |
| 升级瞬间在途 HITL 确认失效 | §3.2.4 兼容翻译覆盖 | — |
| SDK 升级（2.0.x+）改变 canonicalKey 派生 | 钉 2.0.3；探针单测在升级时率先红 | — |
| 心跳帧对前端/评测的干扰 | 复用既有 `waiting` 词表（前端已处理）；评测 `build_view` 对 waiting 本就不计入关键断言（仅 frame_counts） | 开关化（env 常量，默认开） |
| waiting 心调占用线程 | 独立单线程 daemon 调度器，非 Reactor 线程，遵守 2026-09-19 教训 | — |

## 9. 附录：关键代码路径索引

- 平台：`ChatStreamController.java`（chat 入口/租约等待/HITL 落库/收尾）、`ChannelConfig.java`（Channel 构造）、`AgentRuntimeService.java`（gw-hash 推导/confirm 上下文/恢复身份）、`TurnLeaseStore.java`（per-session 跨副本租约）、`SessionKeyResolver` / `AgentStateReader` / `SessionMessageStore`（键形态翻译与多形态兜底）
- SDK（agentscope 2.0.3，源码 jar）：`HarnessGateway.runStream/withGatedStream`（闸门持有窗口）、`LocalSessionTurnGate`（Semaphore(1,true) per key）、`ChannelRouter.buildDmContext`（DM+MAIN → channel-only key）、`DmScope`、`ChatUiChannel.create/perPeer`、`HarnessAgent.ensureGateway`（`DistributedStore.sessionTurnGate()` 注入点）
- 评测：`bench/eval/executor/{runner,sse_client,checks}.py`、`config/frame-mapping.json`、`cases/case_hitl_publish_confirm_001.json`、`flywheel.py`（selftest/status）、`README.md`（关键事实）
- 压测：`bench/load/runner.js`、`bench/mock-llm/server.js`、`run-bench.sh`
- 迁移：`agent-framework/src/main/resources/db/migration/`（V1 基线含 agent_state；纪律见 docs/design/db-migration-flyway-design.md）
