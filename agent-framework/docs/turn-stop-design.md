# 单轮停止（POST /threads/{sessionId}/stop）设计与实施

> 状态：**已实施**（2026-10-27）。替代 `stateless-single-stream-plan.md` R12 的「待定稿」结论：
> S2 实测语义为「cancel = 仅断订阅、turn 后台继续」，本方案新增服务端协作式中断能力。
> 相关代码：`TurnStopStore` / `TurnStopService` / `TurnStopWatcher` / `TurnControlRegistry` /
> `TurnInterruptMiddleware` / `ThreadController#stopThread` / `ChatStreamController` /
> `SessionEventTailer` / `StateDataParser`；迁移 `V13__turn_stop.sql`。

## 1. 背景与目标

### 1.1 现状（实施前）

| 通道 | 能否终止运行中的 turn |
|---|---|
| `POST /threads/chat`（平台/debug 页） | **不能**。断连明确不杀任务（durable-sse 不变量），agent 继续跑完 |
| A2A `tasks/cancel` | 名义存在，实际 `HarnessAgentRunner.stop` 用无参 `interrupt()`（只打 `defaultSessionId` 槽），且 `taskSessionMap` 是进程内 Map（跨副本/重启 no-op） |
| debug 页 Stop 按钮 | 只 `abort()` 本地 SSE，任务后台继续 |
| HITL 挂起 | 可用 `confirm {approved:false}` 干净终止（非「运行中」语义） |

`OrphanTurnWatchdog` 只是断连 180s 后强制收尾租约，不下发中断。

### 1.2 目标

- 对**运行中**的 turn 提供协作式停止：停止后续推理与新工具调度，尽快进入终态。
- 跨副本可用、无会话粘性（架构原则二）：停止请求可落任意 Pod。
- 不引入正确性本地状态；不破坏 durable SSE / 租约 / HITL 的既有不变量。

### 1.3 非目标

- **不撤销已执行工具的副作用**（stop 不是回滚）。
- **不强杀正在执行的工具/阻塞调用**（见 §5）。
- 不递归取消 `agent_spawn` 子任务与 Agent Protocol 远程任务。
- P0 不支持 A2A `tasks/cancel` / AG-UI `POST /agui/runs`（见 §8）。

## 2. 关键事实（决定方案形态）

1. **SDK 2.0.3 有按会话中断**：`HarnessAgent.interrupt(userId, sessionId)` /
   `ReActAgent.interrupt(uid, sid)`，检查点在模型流 chunk 之间与每次 reasoning/acting 前。
2. **中断标志是运行时的**：`InterruptControl` javadoc 明确「never serialized as part of the
   persisted agent state」——只有持有该 turn 的副本能生效，跨副本没有共享标志位。
3. **Channel 链路 RuntimeContext 被网关改写**：`ctx.userId` = 网关 peer（= 前端 sid）、
   `ctx.sessionId` = 网关 gw-hash（见 `SessionKeyResolver` 类注释）。不能凭前端 sid 推导
   中断键，必须取「生效后」的 ctx。
4. **断连 ≠ 取消**：`ChatStreamController.onCancel` 明确 agent 继续执行，事件照常落库。

结论：需要「共享存储投递请求 + 属主副本本地路由 + 现有终态收口」三层，缺一不可。

## 3. 架构

```
客户端 ──POST /threads/{sid}/stop──► 任意副本
                                     │ 解析目标 replyId（意图行 → 最新非终态事件）
                                     │ INSERT turn_stop(session_id PK, reply_id, ...)
                                     ▼
                              MySQL turn_stop
                                     │ 属主副本 @Scheduled 全局轮询（默认 1s）
                                     ▼
                     TurnStopWatcher（replyId 匹配本 Pod 活跃 turn）
                                     │ TurnControlRegistry.interrupt(sid)
                                     ▼
                     HarnessAgent.interrupt(effectiveUserId, effectiveSessionId)
                                     │ SDK handleInterrupt：合成 pending tool error result
                                     │ + 追加恢复消息 + saveState → 流以 AGENT_END 收尾
                                     ▼
             ChatStreamController：emitSynthetic("cancelled") → AGENT_END → endTurn
                                     │
                                     └─ unregister + 删除 turn_stop 行
```

### 3.1 共享存储：`turn_stop`（V13）

```sql
CREATE TABLE IF NOT EXISTS turn_stop (
  session_id   VARCHAR(255) NOT NULL,
  reply_id     VARCHAR(64)  NOT NULL,
  requested_by VARCHAR(255) DEFAULT NULL,
  requested_at DATETIME(3)  NOT NULL,
  PRIMARY KEY (session_id),
  KEY idx_turn_stop_reply (reply_id),
  KEY idx_turn_stop_requested (requested_at)
);
```

- PK = `session_id`：同会话只保留最新一次请求（重复点击幂等覆盖）。
- `reply_id` **栅栏**：turn 的 replyId 全局唯一，陈旧行不会误杀下一轮；消费/清理都带
  `WHERE session_id=? AND reply_id=?`。
- 读路径过滤 `requested_at > now - 1h`（崩溃遗留视为孤儿），`SessionCleanupService`
  每日 `deleteBefore(now - 1d)` 兜底。
- 写失败分型：`request` 返回 `UNAVAILABLE`（端点 503），**不谎称已停止**；读失败返回空集，
  属主下一拍重试（宁延迟不误判）。

### 3.2 目标轮次解析（`TurnStopService`）

| 优先级 | 状态 | 目标 replyId | 结果 |
|---|---|---|---|
| ① | HITL `waiting_confirm` | — | 409 `turn_waiting_confirm`（改用 confirm 拒绝） |
| ② | **执行中**（`turn_lease` 在）：最新非终态事件 | 事件 replyId | 202 + 写行 |
| ③ | 执行中但尚无事件（刚抢到锁/准备段） | 不可判定 | 409 `turn_not_ready`（retryable，客户端短重试） |
| ④ | **排队中**（无租约，`turn_intent` 存在） | 意图行 replyId | 202 + 写行（排队 loop 收流） |
| ⑤ | 以上皆无 | — | 404 `nothing_to_stop` |

> 「租约在」优先于「排队意图」：同会话 `run(A)+queue(B)` 时点 Stop 应该停 A（正在跑的），
> 而不是只取消排队中的 B。B 的停止要等 A 结束、B 真正开始后再点。

### 3.3 属主副本：为什么是「全局轮询 + 本地匹配」

每个 turn 一个轮询线程会随并发 turn 数线性放大 DB 查询；本方案每个副本只跑一个固定周期
任务，一次 `SELECT session_id, reply_id FROM turn_stop`（表内只有在途停止请求）后按
reply_id 匹配本 Pod 登记的活跃 turn。代价是**所有副本每秒各一次极小查询**，收益是查询
成本与并发 turn 数解耦。

**重复投递**：停止请求可能在 SDK 调用真正开始前到达（租约已到手、`onAgent` 尚未执行），
此时 interrupt 可能被 call 起始的 `interruptControl().reset()` 清掉。因此 turn 收尾前
每拍都重新投递（interrupt 幂等），保证进入执行段后必然命中。

**排队 turn 复用同一轮询**：排队中的 turn 也登记进 watcher（`registerQueued`，标记
queued），命中停止行时消费行并置内存标记，由 `/chat` 的排队 loop 下一拍读取后直接收流
（`cancelled`）——排队期间不额外打 DB。租约到手时 `unregisterRegistrationOnly` 只移除
排队登记、不消费行（若停止请求恰好在此刻到达，留给执行态派发）。

### 3.4 本地路由：`TurnControlRegistry` + `TurnInterruptMiddleware`

- `TurnInterruptMiddleware`（`order=3`，最外层）在 `onAgent` 阶段把网关改写后的
  `(agent 实例, userId, sessionId)` 按**规范 sessionId**（`SessionKeyResolver` 翻译）登记。
- 本地 `ConcurrentHashMap`，定位为**可丢弃缓存**：进程重启/实例驱逐后失效不影响语义
  （turn 已不在该 Pod）；有界兜底（>4096 整体清空）。
- **turn 代际栅栏**：执行态登记同时绑定当前 replyId（`bindTurn`），投递前比对
  `interrupt(sessionId, replyId)`——watcher 轮询线程若在「旧 turn 收尾、新 turn 起跑」
  的瞬间被调度延迟，旧请求会被代际校验拒绝，不会误打新 turn。收尾时 `unbindTurn` 只在
  代际仍匹配时移除。
- 陈旧条目无害：SDK 在新 call 开始时 reset 中断标志。
- 兼容路径：非 HarnessAgent/ReActAgent 实例退化为无参 `interrupt()`（仅测试/嵌入式）。

### 3.5 终态与展示

- 执行中的 turn：`AGENT_END` 之前 `emitSynthetic("cancelled", {reason:"user_stopped"})`
  → 同一事件流落库 + 广播；`cancelled` 加入 `SessionEventTailer.TERMINAL_TYPES`，
  观察者据此收流，`/status` 归一为终态 `completed`。
- 排队 turn：还没有事件流，直接向本连接发 `cancelled` 帧后 complete（不占 seq、不落库）。
- SDK 的恢复话术（"I noticed that you have interrupted me…"，`generateReason=INTERRUPTED`）：
  - 实时流：`AgentResultEvent` 被抑制，不上屏；
  - history：`StateDataParser` 将其内容替换为「（本次回复已被用户停止）」并附加
    `stopped: true`（只增字段）。
- 租约/收尾顺序不变：先 `closeSession`（刷缓冲）再 `release`（`TurnFinalizer`）。

## 4. API

```http
POST /threads/{sessionId}/stop
X-User-Id: <网关注入；归属校验同其他 /threads 端点>
```

| 状态 | body | 含义 |
|---|---|---|
| 202 | `{session_id, reply_id, state:"stopping"}` | 已受理（尚未保证已中止） |
| 404 | `{error:"nothing_to_stop"}` | 无运行中的 turn |
| 404 | `{error:"thread_not_found"}` | 归属不符（不泄露会话存在性） |
| 409 | `{error:"turn_waiting_confirm"}` | HITL 挂起中，改用 confirm 拒绝 |
| 409 | `{error:"turn_not_ready", retryable:true}` | 租约在但尚无事件，短重试 |
| 503 | `{error:"turn_stop_unavailable"}` | 能力关闭或存储不可用（fail-closed） |

开关（`application.yml` → `agent.turn-stop.*`）：

| env | 默认 | 说明 |
|---|---|---|
| `AGENT_TURN_STOP_ENABLED` | `true` | 关 = 端点 503、属主不轮询（行为回退「只能断流」） |
| `AGENT_TURN_STOP_POLL_MS` | `1000` | 属主全局轮询周期（下限 100ms） |
| `AGENT_TURN_STOP_GRACE_MS` | `15000` | 协作式停止告警阈值（仅 WARN，不做破坏性收尾） |

## 5. 语义边界（必须向使用者声明）

1. **协作式中断，不是抢占**：检查点在模型流 chunk 之间与每轮 reasoning/acting 前；
   `toolkit.callTools` 内部没有检查点——**正在执行的长工具/阻塞调用不会被强杀**，
   工具返回后才生效。这正是 `GRACE_MS` 只告警不硬停的原因。
2. **不撤销副作用**：已发出的外部请求、已写的数据照旧生效。
3. **子 agent / 远程任务不递归取消**（`agent_spawn`、Agent Protocol 远程任务）。
4. **会话仍可继续使用**：stop 只终止当前 turn，不删会话；下一轮直接发起即可
   （租约在 SDK 真正收尾后释放）。
5. **history 会出现一条「（本次回复已被用户停止）」**，与实时 `cancelled` 帧对齐。

## 6. 为什么不做「硬停」

提前释放租约会允许新 turn 开始，而旧 SDK 循环可能仍在 unwind，晚到的 `saveState` 会覆盖
新 turn 的 state（同 `stateless-single-stream-plan` R3 的已知风险族）。因此 P0 选择：

- 超时只 WARN，租约保持到 SDK 真正收尾；
- 兜底仍是既有 `TurnLeaseGuard` 10 分钟硬寿命 + 租约 TTL；
- 若未来需要强制收尾，中断侧的 replyId 代际栅栏（§3.4）已就位，但还需要**写入侧**
  代际栅栏（旧执行段的 append/save 一律丢弃）才能安全提前放锁；不能只加超时。

## 7. 风险与处置

| # | 风险 | 处置 |
|---|---|---|
| 1 | 长工具阻塞 → stop 看起来无效（最大风险） | UI 显示 `Stopping...` + 10s 提示；`GRACE_MS` WARN；文档明示；后续可对沙箱 exec 做工具级 kill |
| 2 | gw-hash 映射错误 → 误杀其他会话 | 只信 `onAgent` 捕获的生效 ctx，绝不按前端 sid 推导 |
| 3 | 陈旧停止行误杀下一轮 | reply_id 栅栏；收尾时按本 replyId 清理；1h 读窗 + 每日清理 |
| 4 | 属主崩溃 → 孤儿行 | 读窗过滤 + 清理；turn 已随租约 TTL 结束，无业务影响 |
| 5 | 存储故障 | 写 fail-closed（503）；读失败下一拍重试 |
| 6 | 恢复话术污染上下文 | 实时抑制 + history 本地化（`generateReason=INTERRUPTED`） |
| 7 | 多协议不一致 | P0 仅 `/threads/chat`；A2A/AG-UI 未支持（§8） |

## 8. 范围与后续

- **P0（本次）**：`POST /threads/{sid}/stop`、排队 turn 取消、`cancelled` 终态帧、
  `/status`/`/subscribe`/history 对齐、debug 页 Stop 按钮。
- **P1（未做）**：confirm-stream 恢复段的 stop；`waiting_confirm` 的「拒绝即停止」；
  AG-UI 面；硬停策略（依赖代际栅栏）。
- **P2（未做）**：A2A `tasks/cancel` 改走同一 `turn_stop`（顺带修 `HarnessAgentRunner.stop`
  无参 interrupt 缺陷）；子 agent / 远程任务级联取消。

## 9. 验收

单测：
- `TurnStopServiceTest`：目标解析 5 分支 + fail-closed；
- `TurnStopWatcherTest`：replyId 匹配/非匹配、重复投递、注销清理、开关关；
- `TurnControlRegistryTest`：生效键投递、未知会话、移除、空键；
- `ThreadControllerStopTest`：202/404/409（两种）/503/归属 404；
- `ChatStreamControllerTest`：cancelled 排在 AGENT_END 前、恢复话术被抑制、排队取消；
- `StateDataParserTest`：history `stopped` 标注与本地化；
- `SessionEventTailerTest`：`cancelled` 终态判定。

集成（需 `HITL_MYSQL_IT=1`）：`TurnStopStoreMySqlIT`（覆盖写、栅栏消费、全量读、过期清理）。

手工/E2E 建议（未纳入本次自动化）：
1. 双副本：A 执行长 turn（mock LLM 慢流），B 收 `POST .../stop` → A 的 SSE 收到
   `cancelled` → `AGENT_END`；`/status` 终态；`/subscribe` 回放一致；随后同会话新 turn 正常。
2. 排队 turn：先发长 turn，再发第二条（排队），对第二条 stop → 立刻 `cancelled`，不产生 error。
3. 工具阻塞：沙箱内执行 `sleep 60` 后 stop → 不立即终止；工具返回后停止，日志出现
   `turn still running ... likely blocked in a tool call`。
4. 沙箱档：stop 后 `syncBack`（记忆/用户技能）照常执行，不丢数据。
5. HITL：`waiting_confirm` 时 stop → 409 `turn_waiting_confirm`；confirm 拒绝正常终止。

## 10. 实施偏差记录

- 无硬停（§6）：仅协作式中断 + 告警，牺牲「卡死工具立即终止」换取 state 一致性。
- 全局轮询而非每 turn 轮询（§3.3）：延迟上限 = 轮询周期（默认 1s），换取查询成本与
  并发 turn 数解耦。
- 空 `reply_id` 语义未采用：目标不可判定时返回 409 retryable，避免陈旧「任意 turn」
  请求误杀下一轮（§3.2 ④）。
