# SSE 链路优化设计（A1–A5）

> 状态：设计定稿，待实施。本文只做设计，不含任何代码改动。
> 范围：A1 toSSE 收口 / A2 Tailer 空闲退避 / A3 emit 持久化失败不广播 / A4 controller 级 Map 兜底清理 / A5 抽 TurnFinalizer（第一步）。
> **明确不做**：A6 不在本次范围；`.gitignore` 不动（工作区存在他人未提交改动，见 §2.3）。

---

## 1. 背景与全局约束

durable SSE 链路（`docs/durable-sse-plan.md`、`docs/durable-sse-multinode-plan.md`）已上线：执行侧事件经 `SessionEventBus` 落 Redis Streams + 广播本地 sink（写入方路径，不变量 I4），观察者经 `SessionEventTailer` 只读共享存储追赶。本设计对链路做五项收口与加固，全部是内部优化，**不触碰对外契约**。

### 1.1 全局硬约束（每一项都必须满足）

| # | 约束 | 依据 |
|---|------|------|
| H1 | HTTP 路径与参数语义不变 | `/threads/chat`、`/threads/{sid}/subscribe?afterSeq&replyId`、`/threads/{sid}/confirm(-stream)`、`/threads/{sid}/status` 的路由、参数、状态码全部原样 |
| H2 | SSE 帧词表与**帧字节**不变（A1 还要求与现状逐字节一致，论证见 §3.3） | `AgentEventSseSerializer.payload` 的词表是前后端共同契约（AGENTS.md「AgentEventSseSerializer — SSE 序列化共用工具」） |
| H3 | Redis key 结构不变：`sess:{sid}:events`（Stream，ID=`<seq>-0`，字段 `t`/`r`/`p`）与 `sess:{sid}:replies`（ZSET） | `RedisEventLog.java:38-40`、`RedisEventLog.java:71-74`；绝不引入 `XADD *`/`XSETID`（`RedisEventLog.java:53-55` 硬约束） |
| H4 | 前端可观测行为不变 | 断连续传（`afterSeq` 游标）、`/status` 判态、心跳 comment 帧（`hb`）语义不动 |
| H5 | 不变量不回退 | I1（seq 连续前缀）、I2（单写者）、I3（游标只前进）、I4（写入方本地 sink / 观察者只读 DB）见 `docs/durable-sse-multinode-plan.md:189-192`；C1（租约交接期 seq 区间不得重叠写入）见 `docs/durable-sse-multinode-impl-plan.md:1908` 与 `ChatStreamController.java:440-448` javadoc |
| H6 | 不实现 A6；不动 `.gitignore` | 本次范围限定 |

### 1.2 现状关键代码索引（本设计全部行号引用的基准）

| 文件 | 关键位置 | 说明 |
|------|----------|------|
| `service/SessionEventBus.java` | `:40` MAPPER；`:92-114` emit(4 参)；`:95` append；`:101-110` sink 广播；`:104`/`:128` `seq > 0 ? seq : 0`；`:120-134` emitSynthetic；`:146-176` subscribe；`:153`/`:159` `.map(this::toSSE)`；`:182-185` ensureSink；`:194-197` beginTurn；`:204-212` closeSession；`:223-233` abandonSession；`:244-269` evictStaleSinks；`:277-295` 私有 toSSE | 写入方路径 |
| `service/SessionEventTailer.java` | `:34` TERMINAL_TYPES；`:37` PROBE_INTERVAL_MS=2000；`:44` pollInterval；`:55-72` 构造；`:86-103`/`:106-118` probe；`:131-203` tail；`:139`/`:152`/`:216` toSSE 调用点；`:166` 探测门控；`:187-190` 心跳；`:193` 轮询 sleep；`:210-219` emitRemaining；`:225-245` 私有 toSSE；`:247-255` done/interrupted 帧 | 观察者路径 |
| `service/SessionEventStore.java` | `:59-61` isDelta；`:263-292` append（失败返回 -1，`:261` javadoc）；`:301-311` finishTurn；`:328-341` abandonTurn；`:370-391` queryAfter；`:485-505` findLatest(Strict)；`:578` EnvelopedEvent record | 攒批/seq/读 |
| `service/RedisEventLog.java` | `:228-271` appendBatch；`:324-357` fail 三分类日志；`:385-392` latest；`:400-403` tailSeq | Redis 交互 |
| `controller/AgentEventSseSerializer.java` | `:34-36` payload(event)；`:48-140` payload(3 参)；`:110-123` permission_ask（`reply_id` snake_case）；`:126-133` 通用 replyId/blockId 附注；`:142-156` extractReplyId；`:166-172` jsonEsc；`:175-181` payload(Map) | 序列化共用工具 |
| `controller/ChatStreamController.java` | `:108-125` 三个桶（presentFileBuffers/writeFileInputBuffers/toolCallNames）；`:261-267` replyId + turnEnded「迟到 closeSession」教训注释；`:295-302` 订阅 EventBus；`:308-323` 执行 + error 回调；`:324-333` 回滚 catch；`:335-354` onCancel（禁阻塞教训 `:339-342`）；`:388-391` 工具名登记；`:420` emit；`:422-426` HITL closeSession；`:429-431` file_ready 合成；`:433-437` AGENT_END；`:440-461` stopIfLeaseLost；`:463-470` endTurn(3 参)；`:472-491` endTurn javadoc+实现；`:494-498` interruptedSSE；`:518-528`/`:530-563`/`:567-577`/`:579-618` 四个桶读写方法 | 对话入口 |
| `controller/ConfirmController.java` | `:69-77` payloadForEvent；`:89-129` 同步 confirm；`:143-240` confirmStream；`:178-181` turnEnded；`:187-202` 回滚 catch；`:216-231` 执行；`:234-237` onCancel；`:245-274` handleEventAndEmit；`:276-283` endTurn(3 参)；`:308-326` endTurn javadoc+实现；`:329-333` interruptedSSE | HITL 恢复 |
| `config/AgentScopeConfig.java` | `:552-560` SessionEventBus Bean；`:568-582` SessionEventTailer Bean（`:575-576` poll 默认 300ms；`:578-579` heartbeat 同源） | 装配 |
| `config/AgentManagerProperties.java` | `:246` `@DefaultValue("300") int tailPollMs`（env `AGENT_SSE_TAIL_POLL_MS`） | 配置 |
| `controller/SessionStreamController.java` | `:67-75` subscribe → `tailer.tail(...)`；`:101-146` status | 订阅入口 |

### 1.3 实施顺序与提交纪律

- **顺序：A5 → A1 → A3 → A2 → A4**。A5 是第一步（endTurn/stopIfLeaseLost 先收口成 TurnFinalizer），A4 的清桶点落在收口后的 endTurn 上，避免对同一段代码二次搬移；A1 与 A3 都改 emit 写路径，相邻提交。
- **每完成一项立即 git commit**（共享工作目录，防丢改动）。分支 `feat/sse-optimization-a1-a5`（基于最新 master），六项门禁（mvn test / go / frontend 单测 + 三个 E2E）全绿后走 PR 合并；**禁止直接 push master**（分支保护）。
- **git add 一律显式指定文件路径**：工作区当前有他人未提交的 `.gitignore` 改动（`git status --porcelain` 实测仅 ` M .gitignore`，+4 行），严禁 `git add -A` / `git add .`，严禁触碰该文件。

---

## 2. A5 抽 TurnFinalizer（第一步）

### 2.1 现状与问题

`ChatStreamController` 与 `ConfirmController` 各自持有一组**行为完全相同**的 turn 收尾成员：

| 成员 | ChatStreamController | ConfirmController | 差异 |
|------|---------------------|-------------------|------|
| `endTurn(lease, sessionId, turnEnded)` 3 参重载 | `:463-470` | `:276-283` | 无（同一段 CAS 幂等保护） |
| `endTurn(lease, sessionId)` 2 参重载 | `:472-491` | `:308-326` | 无（同为 `isLost 先求值 → abandon/close → release`） |
| `stopIfLeaseLost(lease, sessionId, sink)` | `:440-461` | `:294-306` | 仅日志前缀 `[chat]` / `[confirm]` 不同，其余逐行相同 |
| `interruptedSSE(reason)` | `:494-498` | `:329-333` | 无（同一静态帧工厂） |
| 准备段回滚骨架（catch 块：error 帧 → closeSession → release → complete） | `:324-333` | `:193-202` | 仅日志文案、error 明细拼装与 **error 帧构造**有别（chat 手工拼接恒定键序；confirm 走 `payload(Map.of(...))`，键序受 JDK SALT 非确定——故帧构造必须留在各控制器，见 §2.3） |

两份拷贝已经在演化中各自漂移过措辞（javadoc 措辞不完全一致），继续双份维护必然再漂移。而 `handleEventAndEmit` **两边存在真实差异**（chat 侧有工具名登记、write_file 截获、present_file 累积、audit、file_ready 合成、payloadForEvent 的 ui 元数据；confirm 侧是 storeConfirmContext + 先 release 后 emit 的次序），**不强行模板化**，原地保留。

### 2.2 具体做法

1. 新增 `controller/TurnFinalizer.java`（与 `AgentEventSseSerializer` 同包同风格：`final class` + 私有构造 + 静态方法；AGENTS.md 已将后者标注为「SSE 序列化共用工具」，先例成立）：
   - `static boolean endTurn(SessionEventBus eventBus, TurnLeaseGuard lease, String sessionId, AtomicBoolean turnEnded)` —— 原 3 参重载，唯一差异是**返回 compareAndSet 结果**（收尾权是否由本次调用抢到），供 ChatStreamController 抢到时追加清桶（§6.2-3）；CAS 内部语义与现状逐行相同；
   - `static void endTurn(SessionEventBus eventBus, TurnLeaseGuard lease, String sessionId)` —— 原 2 参重载；
   - `static boolean stopIfLeaseLost(SessionEventBus eventBus, TurnLeaseGuard lease, String sessionId, FluxSink<ServerSentEvent<String>> sink, String logTag)` —— `logTag` 取 `"[chat]"` / `"[confirm]"`，拼出与现状逐字节相同的日志行；
   - `static ServerSentEvent<String> interruptedSSE(String reason)`；
   - `static void abortSetup(SessionEventBus eventBus, TurnLeaseGuard lease, FluxSink<ServerSentEvent<String>> sink, String sessionId, ServerSentEvent<String> errorFrame)` —— 回滚骨架的三个副作用步骤（`closeSession` → `release` → `complete`，外加 `sink.next(errorFrame)`）；**error 帧由调用方构造好后整帧传入，帧构造留在各控制器**，理由见 §2.3 第 2 点：两侧 errorSSE 工厂字节不等价，统一到任一工厂都会使另一侧回滚帧字节偏离现状，直接违反 H2。
2. 两个控制器删除被搬移成员，改为委托 TurnFinalizer；`handleEventAndEmit` 及调用点一行不动（仅 `stopIfLeaseLost` 调用处多传 `logTag`）。
3. **实测教训注释必须原样迁移**，映射如下（原文逐字搬移，不改写、不缩写；调用点只保留一行指向注释，如「为何只收尾一次：见 TurnFinalizer#endTurn」）：

| 教训注释 | 现位置 | 迁至 |
|----------|--------|------|
| 「迟到 closeSession 会拆掉下一个 turn 刚建好的 sink（0.4s 内前后脚两轮对话，第二轮 permission_ask 被吞，approval-forms e2e 2026-09-17 复现）」 | `ChatStreamController.java:262-267`（`turnEnded` 声明处；ConfirmController `:179-181` 有同义段） | `TurnFinalizer.endTurn(3 参)` javadoc（CAS 幂等语义的家）；**原文逐字保持不变**——其本就未声称覆盖 HITL 路径，该路径既有的迟到收尾空窗属范围外观察，见 §7 末节 |
| 「isLost() 必须在 release() 之前求值，否则时间判据被短路、丢锁 turn 误走刷缓冲」+「先收尾再放锁（C1：刷缓冲必须仍持租约）」+「抢锁方按 ACQUIRE_TIMEOUT 排队等一拍」 | `ChatStreamController.java:472-482`、`ConfirmController.java:308-317` | `TurnFinalizer.endTurn(2 参)` javadoc |
| 「丢锁后继续 append 会与新 owner seq 区间重叠（C1）；终态帧只发本连接、不落库、不占 seq，不能用 emitSynthetic」 | `ChatStreamController.java:440-448`、`ConfirmController.java:285-293` | `TurnFinalizer.stopIfLeaseLost` javadoc |
| 「准备段异常必须回滚已获取的租约，否则续租线程令 session 永久锁死」 | `ChatStreamController.java:269-273`、`ConfirmController.java:183-186` | `TurnFinalizer.abortSetup` javadoc |
| 「onCancel 跑在 Reactor 取消回调线程上，绝不能阻塞（LocalSessionTurnGate 释放路径被占死）」 | `ChatStreamController.java:339-342` | **不迁移**——`sink.onCancel` 块本身留在控制器（不属于「完全相同的成员」），注释随代码原地保留 |

4. A5 提交本身**不含任何行为改动**，靠既有测试（`ChatStreamControllerTest` 24 个 @Test、`ConfirmControllerTest` 8 个 @Test）原样全绿作为「零行为变化」回归网。

### 2.3 兼容性论证

- 搬移的都是 private 成员，无对外签名变化；两控制器委托调用后，事件流上可能出现的每一种帧序列（done/interrupted/error 帧、closeSession/abandonSession/release 的先后）与现状逐一对应，见 §2.2 的成员对照表。
- **回滚 error 帧字节必须原样（abortSetup 只收现成帧的原因）**：两侧 errorSSE 工厂**字节不等价**——`ChatStreamController.java:651-654` 手工字符串拼接，恒定输出 `{"type":"error","error":...}` 键序；`ConfirmController.java:335-339` 走 `AgentEventSseSerializer.payload(Map.of("type","error","error",msg))`，JDK 9+ 不可变集合（ImmutableCollections.MapN）迭代序受每 JVM 随机 SALT 影响、键序跨进程非确定。abortSetup 若统一采用任一工厂，另一侧的回滚 error 帧字节必然偏离现状（违反 H2 与「帧序列逐一对应」），因此帧构造留在各控制器、TurnFinalizer 只接收构造好的 `ServerSentEvent`。
- 日志文本：`stopIfLeaseLost` 经 `logTag` 拼装后与现状逐字节一致；`endTurn`/`abortSetup` 无新增日志。运维 grep 行为不变。
- A5 不改 `SessionEventBus`/`SessionEventStore`，Redis 数据面零影响。

### 2.4 单测计划

- 新增 `TurnFinalizerTest`（Mockito，风格对齐 `TurnLeaseGuardTest` 的 11 用例）：
  1. 3 参 `endTurn` CAS 幂等：两次调用仅一次生效；
  2. 丢失租约 → `InOrder(abandonSession, release)`；未丢失 → `InOrder(closeSession, release)`；
  3. `isLost` 求值先于 `release`：用 `Answer` 在 `release` 时翻转内部状态，断言走 abandon 分支（钉住 §2.2 的教训注释 2）；
  4. `stopIfLeaseLost`：`tryMarkLostNotified()` true → 发 interrupted 帧 + abandon + release 且返回 true；false → 只返回 true 不重复通知；
  5. `interruptedSSE("lease_lost")` 帧字节断言：data == `{"type":"interrupted","reason":"lease_lost"}`；
  6. `abortSetup`：`InOrder(next(errorFrame), closeSession, release, complete)`，并配**帧字节用例**——chat 侧传入 `errorSSE(...)` 产物、断言等于现状手工拼接形态 `{"type":"error","error":<jsonEsc(msg)>}`；confirm 侧传入的帧断言等于测试内现算的 `AgentEventSseSerializer.payload(Map.of("type","error","error",msg))`（与生产同一构造路径，**不硬编码键序**——SALT 使键序每 JVM 非确定，硬编码字符串断言本身不可靠）；
  7. 3 参 `endTurn` 返回值接缝（§6.2-3 依赖）：CAS 首胜返回 true、重入返回 false。
- 回归网：`ChatStreamControllerTest`、`ConfirmControllerTest` **不修改任何既有用例**，全量原样通过。

### 2.5 风险与回滚

- 风险：搬移时手抖改变语句顺序（尤其 `isLost`/`release` 次序）→ 由 §2.4 用例 3 直接拦下；E2E R 组 kill 接管用例兜底。
- 回滚：单 commit revert 即可（无数据面、无契约面牵连）。

---

## 3. A1 toSSE 收口：replyId 注入前移到 emit 写路径

### 3.1 现状与问题

replyId 注入 JSON 的同一段算法存在两份拷贝：

- `SessionEventBus.toSSE`（`SessionEventBus.java:277-295`）：服务 `subscribe` 的回放（`:153`）与实时（`:159`）两条流；
- `SessionEventTailer.toSSE`（`SessionEventTailer.java:225-245`）：服务观察者的回放/追赶（`:139`/`:152`/`:216`），注释明言「与 SessionEventBus.toSSE 保持一致的 payload 形态」。

算法：`readTree(payload)` → 若 `node.isObject()` 且 `!node.has("replyId")` 且入参 replyId 非空 → `put("replyId", ...)`（追加在末尾）→ `writeValueAsString`；任何异常吞掉用原串。问题：

1. **读端对每一帧做一次 readTree +（命中时）writeValueAsString**——回放页 500 行（`SessionEventStore.java:70`）× 每个订阅者，都是重复功；
2. **落库的 `p` 字段与广播的 `data` 不是同一份字符串**：存量机制下，`AgentEventSseSerializer` 未覆盖 replyId 的事件类型落库的是「无 replyId」版本，读端现场注入——两份拷贝一旦漂移，执行副本本地帧与观察者追赶帧字节分叉，直接破坏 I4 的「前端拿到的字节一致」承诺（`SessionEventTailer.java:226-227` 注释所防）。

需要注入的事件类型（即 `extractReplyId` 未覆盖、`AgentEventSseSerializer.java:142-156` 返回 null 的）：`DATA_BLOCK_START`/`DATA_BLOCK_DELTA`、`TOOL_RESULT_DATA_DELTA`、`REQUIRE_USER_CONFIRM`（该帧只有 snake_case `reply_id`，见 `:110-123`，读端会**再**注入 camelCase `replyId`——现状即双字段，必须原样保留）、以及合成帧 `file_ready`/`error`（`ChatStreamController.java:530-563`、`ChatStreamController.java:317-319`/`ConfirmController.java:224-226`）。`waiting`/`session_created` 帧不经 EventBus（控制器直接 `sink.next`），与本项无关。

### 3.2 具体做法

1. `AgentEventSseSerializer` 新增两个静态方法（收口后的唯一实现）：
   - `static String withReplyId(String payload, String replyId)` —— **原封不动搬入**现读端注入算法（readTree → isObject → `!has("replyId")` → put → writeValueAsString；异常吞掉返回原串；replyId null/blank 直接返回原串）；
   - `static ServerSentEvent<String> toSseFrame(SessionEventStore.EnvelopedEvent e)` —— `data = AgentEventSseSerializer.withReplyId(e.payload(), e.replyId())`（**不是**直通透传：新数据 payload 已含 replyId，withReplyId 走 `has("replyId")` 短路返回原串、零重序列化；存量行 payload 无 replyId → 兜底注入，见本节第 4 点），`id = String.valueOf(e.seq())`。
2. `SessionEventBus`：
   - `emit`（`:92-114`）与 `emitSynthetic`（`:120-134`）在序列化/取 override 之后**立刻** `payload = AgentEventSseSerializer.withReplyId(payload, replyId)`，然后把**同一份字符串**传给 `eventStore.append(...)` 与 sink 广播——落库 `p` 字段与 SSE `data` 从此同源；
   - `subscribe` 两处 `.map(this::toSSE)` 改为 `.map(AgentEventSseSerializer::toSseFrame)`；删除私有 `toSSE`（`:277-295`）。
3. `SessionEventTailer`：三处 toSSE 调用点同样改 `toSseFrame`；删除私有 `toSSE`（`:225-245`）；`doneSSE`/`interruptedSSE`/`heartbeatSSE` 是 tailer 特有帧，不动。
4. 读端兜底保留：`toSseFrame` 对**存量行**（升级前落库、`p` 内无 replyId）仍走 `withReplyId`——即保留 `!node.has("replyId")` 条件判断。细节见 §3.3 第 4 点。

### 3.3 兼容性论证（SSE 帧字节一致 + 存量 Redis 混读）

1. **写路径注入 ≡ 现读端注入**：`withReplyId` 与现 `toSSE` 是同一段算法、同一个 Jackson 默认配置 ObjectMapper；对任意 `(payload, replyId)` 输入输出逐字节相同。key 顺序也一致——Jackson `ObjectNode` 保持插入序，`put` 追加在末尾，与现状「注入后 replyId 在 JSON 尾部」相同。
2. **新数据帧字节一致**：新事件落库的 `p` 已是注入后字符串；读端 `toSseFrame` 直接透传（`has("replyId")` 命中、不重序列化）→ 帧 data ≡ 现 `toSSE(payload 原串)` 的输出。`id` 字段仍为 `String.valueOf(seq)`，不变。
3. **存量数据混读一致**：升级前落库的行 `p` 无 replyId，读端兜底 `withReplyId`（同一实现）注入 → 与现状读端输出逐字节相同。新旧行混在同一个 Stream 里（同一 session 跨升级回放）时，每行各自按「有无 replyId」走对应分支，互不影响——`p` 字段自描述，不需要版本号或数据迁移。
4. **读端「零 JSON 操作」的准确边界**：新数据在读端不再做 `writeValueAsString` 重写（唯一剩下的 JSON 操作是兜底判定里的 `readTree` + `has` 检查）。**不做 `contains("replyId")` 之类的子串捷径**——delta/tool 入参里可能出现字面量 `"replyId"`（本仓库工具会写 JSON 源码），子串判定与 `!node.has` 顶层键判定在存量行上不等价，会破坏字节一致性；兜底块随 Redis TTL（`retentionDays=7`，`SessionEventStore.java:118`、`:133-136`）自然耗尽存量后成为死代码，届时可整体删除，读端即真正零 JSON 操作。此为后续独立小 commit，不在本次范围。
5. **/status 与 history 不受影响**：`/status` 只消费 EnvelopedEvent 的 seq/type/replyId 信封字段（`SessionStreamController.java:122-145`）；`ThreadController` 对 `SessionEventStore` 仅用 `deleteSession`/`findReplyIds`（`:229`/`:461`），不消费 payload 字符串——`p` 内多出的 `replyId` 键不会泄漏到任何非 SSE 面。
6. **词表不变**：注入的键名仍是 `replyId`，值仍是 turn 的 replyId；`reply_id`（snake_case，HITL 帧）原样保留，双字段现状不变。

### 3.4 单测计划

- `AgentEventSseSerializerTest`（现 2 用例）扩充：
  1. **字节一致性 oracle**：把现读端算法拷贝进测试作参照实现，对固定 fixture 表断言 `withReplyId` 输出与参照逐字节相等。fixture 至少覆盖：无 replyId 的普通 payload（注入，末尾追加）；payload 自带 replyId（原串返回，不重序列化——用 `==`/同一引用断言短路路径）；`replyId` 为 null/blank（原串）；非对象 payload（如纯文本，原串）；非法 JSON（原串）；顶层无但**值内含**字面量 `"replyId"` 的 payload（必须注入——钉住 §3.3 第 4 点的反例）；
  2. `toSseFrame`：id/字段与 `String.valueOf(seq)` 一致、data 透传。
- `SessionEventBusTest`（现 11 用例）扩充：mock `SessionEventStore.append` 捕获落库 payload，断言「落库 payload == 广播 EnvelopedEvent.payload == withReplyId 产物」（同一引用或 `assertEquals`）；emitSynthetic 同理。
- 混读用例：store 桩先返回存量形态行（无 replyId）、再返回新形态行，经 `subscribe` 断言两帧 data 均与现读端算法输出一致。
- E2E 兜底：R 组「事件对账」用例（执行副本本地帧 vs 观察者追赶帧）天然覆盖跨路径字节一致性。

### 3.5 风险与回滚

- 风险：注入前移后 `payloadOverride`（MCP Apps ui 元数据路径，`ChatStreamController.payloadForEvent`）被二次注入——不会：ui 路径仅 TOOL_CALL_START，而该类型在 `extractReplyId` 内、payload 自带 replyId，`withReplyId` 短路返回原串；单测 §3.4-1 已覆盖该 fixture。
- 风险：两处 toSSE 删除后若有第三方调用点遗漏——全仓仅 §3.1 列出的 5 个调用点（grep `toSSE` 实测），编译期即可发现。
- 回滚：单 commit revert。存量行一旦被新代码写坏（理论上不会，有 §3.4 oracle 钉住）无法修复——所以 A1 合并前必须字节一致性用例全绿，这是本项唯一的「不可回滚点」。

---

## 4. A3 emit 持久化失败不广播 seq=0

### 4.1 现状与问题

`SessionEventBus.emit`/`emitSynthetic` 在 `eventStore.append` 返回 -1（Redis 写失败三类：不可达/OOM/非单调 ID，`RedisEventLog.java:324-357`）时，仍向 sink 广播 `new EnvelopedEvent(seq > 0 ? seq : 0, ...)`（`:104`/`:128`）——产生一条 **id=0 的实时帧**。该事件永不落库，于是：

- 实时渲染了一条回放（断连续传、`/subscribe` 重放）**永远补发不出来**的事件——I3 语义下客户端游标若停在它之后，重连将跳过它；
- ~~若它是该连接首帧，前端游标落在 0~~（此条经对照前端实现后**撤销**）：assistant 页根本不解析 SSE `id:` 行、只处理 `data:`（`frontend/src/app/assistant/page.tsx:274-277`），游标完全来自 `/status` 的 `latest_event_seq`（`page.tsx:329`）；debug 页虽解析 `id:`，但按 `idVal > lastEventId` 单调推进（`static/debug/js/api.js:158`/`:223`），id=0 不可能把游标拉回 0、也不触发全量回放。因此 id=0 帧的真实危害只剩第一条（实时渲染一条回放永远补不出的帧），A3 的修复方向不变；
- 残余不确定性说明：`appendBatch` 管道非事务、**部分写入是可能的**（`RedisEventLog.java:224-226`）——极端管道中断下，被跳过广播的帧可能实际已落库，此时不广播少的是一条本可回放的实时帧。该残余不确定性与现状对称（现状广播 id=0 帧时同样无从区分是否落库），无恶化。

### 4.2 具体做法

`emit`/`emitSynthetic` 中把广播改为条件广播（与 A1 同一提交序列，落到已收口的写路径上）：

- `seq >= 1` → 组装 EnvelopedEvent 广播（行为同现状）；
- `seq < 0` → **跳过广播**，`log.debug` 记 sid/type（失败细节由 `RedisEventLog.fail` 的 ERROR/WARN 分级日志承担，`RedisEventLog.java:324-357`——这里不再打 WARN，避免 Redis 故障期每事件一条的重复噪音）；
- `touchActive` 与返回值 `seq`（-1）不变，调用方（`ChatStreamController.java:420`/`:554`/`:317`、`ConfirmController.java:263`/`:224`）均不消费返回值、不改变收尾路径（endTurn 照常 closeSession/abandonSession，流照常 complete）。

备选方案（ask 中「或至少不带 id」）：广播但 `ServerSentEvent` 不设 id——保留实时可见性、避免 id=0，但仍制造「实时有、回放无」的不一致，仅作为回滚过渡档，不作为目标态。

### 4.3 兼容性论证

- 正常路径（append 成功）逐帧不变；变化的只有「本就该被判定为持久化失败」的帧。
- 词表/帧结构零变化（只是**缺一条**帧）；done/interrupted/error 收尾帧与心跳不受影响——它们不依赖该广播（`endTurn` → `closeSession`/`abandonSession` 的 complete 信号照常触发）。
- Redis 数据面零变化；I1/I2/I3 均不受影响（失败批次本就未落库，`SessionEventStore.java:255-259` 已声明容忍空洞）。

### 4.4 单测计划

`SessionEventBusTest` 扩充（mock store）：
1. `append` 返回 -1 → `subscribe` 后 `emit`，`StepVerifier` 断言实时流**无** `next`（现有用例若断言过 id=0 帧，同步改为断言不广播）；
2. `emit` 返回值仍为 -1、`lastActiveAt` 仍被 touch（经 evictStaleSinks 行为间接断言或包私有观察点）；
3. `append` 返回合法 seq → 帧存在且 id 正确（防误伤正常路径）；
4. `emitSynthetic` 同样三例。

### 4.5 风险与回滚

- 风险：Redis 抖动期间客户端实时流出现「静默空洞」且无 error 帧——现状该帧在未落库时重连同样必丢；极端管道部分写入下它虽可能已落库，前端也无从区分（与现状对称，见 §4.1 残余不确定性说明）。本质是把不确定性显性化；前端断连续传路径不感知差异。
- 风险：某调用方未来开始依赖「emit 必广播」——javadoc（`:81`「-1 表示持久化失败（但实时广播仍会尝试）」）同步改为「失败时不广播」。
- 回滚：单行 revert（恢复 `seq > 0 ? seq : 0` 无条件广播），或临时切到 §4.2 备选档（不带 id）。

---

## 5. A2 Tailer 空闲退避

### 5.1 现状与问题

`SessionEventTailer.tail` 轮询循环（`:141-200`）每轮固定 `Thread.sleep(pollInterval)`（`:193`；`pollInterval` = `AGENT_SSE_TAIL_POLL_MS`，默认 300ms，`AgentManagerProperties.java:246`、`AgentScopeConfig.java:575-576`）。空闲会话（长工具调用、静默思考）下绝大多数轮询是空页，仍以 ~3.3 QPS/连接 打 Redis `XRANGE`（`queryAfter` → `queryPage` → `range`）；终止探测已降频至 2s（`:166` 门控，`PROBE_INTERVAL_MS`，`:37`），但**事件轮询本身**没有退避。多副本下每个空闲观察者连接都是常驻查询源。

### 5.2 具体做法

1. 循环内维护 `emptyStreak`（连续空页计数）：空页 +1，非空页归零。
2. 抽出纯函数（包私有静态，便于表驱动测试）：

   ```java
   // 连续 emptyStreak 次空页后，本轮循环应 sleep 的毫秒数。
   // 退避序列（base=300）：600 → 1200 → 2000(封顶=PROBE_INTERVAL_MS) → 2000 …
   // 结果永不低于 baseMs：tailPollMs 可配（AGENT_SSE_TAIL_POLL_MS，AgentManagerProperties.java:246），
   // 若配置超过封顶值（如 5000），先 min 再 max 的钳制保证退避不会「反而提速」（否则序列 5000→2000→2000 倒挂）。
   static long pollSleepMs(long baseMs, int emptyStreak) {
       if (emptyStreak <= 0) return baseMs;
       long backoff = baseMs << Math.min(emptyStreak, 10);   // 2^10 封顶防溢出
       return Math.max(baseMs, Math.min(backoff, PROBE_INTERVAL_MS));
   }
   ```

   `:193` 改为 `Thread.sleep(pollSleepMs(pollInterval.toMillis(), emptyStreak))`。
3. **有新事件立即恢复**：非空页将 `emptyStreak` 归零，下一轮即回到 `tailPollMs` 基频——事件一旦开始流式产出，300ms 节奏不受退避影响。
4. 终态判定节奏：探测门控（`:166` `page.isEmpty() && now - lastProbeAt >= PROBE_INTERVAL_MS`）原样保留。**稳态**（退避已达封顶）下空页轮询周期 == 探测周期 == 2s，probe 触发节奏与现状一致。**过渡期**（事件流刚静默的头部）存在一次**一次性推迟**：首轮探测立即（`:142` lastProbeAt=0 语义保持），但按 §5.2-2 公式，第 2/3 拍 sleep 600/1200ms 均不满足 2s 门控，第二次探测最早落在 t0+3800ms（现状为 t0+2100ms，300ms 轮询里首个满足门控的空页）——执行副本恰在事件流静默起点附近崩溃时，interrupted 判定一次性最多晚 ~1.7s，与 §5.3 量化的事件驱动终态延迟同量级（可接受，但论证必须显式覆盖，§5.4-4 的断言按观测窗口约定）。此后进入稳态，节奏与现状一致。
5. 首轮探测语义不变：`lastProbeAt=0`（`:142`）保证第一个空页立刻探测，退避不影响「对已结束 turn 不空等一轮」。
6. 心跳逻辑（`:187-190`，基于 `lastFrameAt`）不动；中断处理（`:194-198`）不动。

### 5.3 兼容性论证

- **终态判定延迟**：probe 驱动的 FINISHED/INTERRUPTED 路径——稳态节奏不变（§5.2-4）；**过渡期**第二次探测一次性推迟 ~1.7s（t0+2100ms → t0+3800ms），最坏情形是执行副本恰在静默起点崩溃的 interrupted 判定，与事件驱动终态延迟同量级，可接受。事件驱动路径（`sawTerminal` → done 帧，`:158-162`）延迟上界从「≤300ms + 轮询」变为「≤2s + 轮询」——**最坏多 ~1.7s**，且只发生在「长时间静默后的第一条事件恰为终态」这一情形。可接受：观察者路径本就以 2s 探测定完成判定，前端对 done 帧无 300ms 级敏感；执行副本本地路径（I4 写入方）不经 tailer，首 token 延迟零影响。
- **心跳**：`heartbeatInterval` 默认 20s ≫ 2s 轮询周期，心跳不受退避影响；入口代理读超时保护不变。
- **Redis 压力**：空闲连接查询从 ~3.3 QPS 降到 ~0.5 QPS（2s 周期），这正是本项目的收益。
- 配置面零变化：不新增 env；`PROBE_INTERVAL_MS` 仍是常量。多副本部署下各副本独立退避，无协同需求（I4：观察者只读）。

### 5.4 单测计划

`SessionEventTailerTest`（现 16 用例）扩充：
1. `pollSleepMs` 表驱动：base=300 时 `{0→300, 1→600, 2→1200, 3→2000, 5→2000}`；base=50（既有测试的注入值）时序列 `100→200→400→800→1600→2000→…`；base=5000（超过封顶的极端配置）时恒 `5000→5000→…`（不倒挂提速）；断言属性：结果**永不低于 base**、不超过 `max(PROBE_INTERVAL_MS, base)`——即 `min(退避, 封顶)` 后再 `max(·, base)` 下界钳制，缺一层都会与配置语义矛盾；
2. 退避-恢复：mock `queryAfter` 先返回 3 次空页再返回一条 AGENT_END 事件——`StepVerifier` 断言 done 帧最终到达、且事件到达后游标推进（空页 streak 归零语义经后续事件到达时间间接验证）；
3. 首轮立即探测保持：空 store + lease 未持有 + 无 pendingConfirm → `interrupted` 帧在不等待一个完整退避周期内到达（对齐 `:142` 注释语义）；
4. 探测节奏回归：probe RUNNING→FINISHED 两段场景。断言**按观测窗口约定**：以静默起点 +4s 为界的窗口内，`turnLeaseStore.isHeld` 调用次数与现状一致（过渡期推迟 ~1.7s 后进稳态，4s 窗口可同时容纳 t0+2100ms 与 t0+3800ms 两种第二次探测落点）；更短窗口允许比现状少 1 次（§5.2-4 过渡期推迟），不做不限窗口的「次数必然相等」断言；
5. 既有 16 用例若因退避出现墙钟变慢（它们注入 base=50ms，退避后空页 sleep 最长 2s），允许为「多次空页探测」类用例显式传入小 base——构造器不变（base 本就是 `Duration` 注入），必要时补一个包私有构造注入**封顶值**用于测试（生产仍走常量 2000）。

### 5.5 风险与回滚

- 风险：静默期第一条事件的投递延迟最多 +1.7s（§5.3）——量化在案，E2E R 组断连续传/事件对账用例的等待预算（秒级）覆盖之；若 CI 实测逼近超时，可将封顶与 `PROBE_INTERVAL_MS` 解耦（新 env，默认 2000），属增量改动不破坏本设计。
- 风险：`Thread.sleep` 时长改变影响既有测试墙钟——§5.4-5 已给缓释。
- 回滚：`pollSleepMs` 单点 revert（恢复固定 sleep），纯函数抽取保证回滚面就是一行调用。

---

## 6. A4 controller 级 Map 兜底清理

### 6.1 现状与问题

`ChatStreamController` 持有三个 controller 级 `ConcurrentHashMap`（`ChatStreamController.java:108-125`）：

| Map | key 语义 | 写入 | 正常移除 | 泄漏路径 |
|-----|----------|------|----------|----------|
| `toolCallNames` | toolCallId → 真实工具名（ToolCallStart 是唯一带真名的事件，delta 帧名恒为 `__fragment__`） | `:388-391` put | `:401`（write_file end） | 工具未走到对应 End 事件（流 error/丢租约/HITL 打断）→ 条目残留 |
| `presentFileBuffers` | toolCallId → 结果文本桶（64KB 上限） | `:518-528` computeIfAbsent | `:531`（file_ready 合成时 remove） | present_file 中途断掉 → 桶残留（≤64KB/个） |
| `writeFileInputBuffers` | toolCallId → 输入 JSON 片段桶（2MB 上限） | `:567-577` computeIfAbsent | `:580`（sync 时 remove） | write_file 中途断掉 → 桶残留（≤2MB/个） |

异常路径（源流 error、丢租约、onCancel 后孤儿 turn 被看护强收）下 End 事件永远不来，条目跨 turn 存活累积。有界（桶上限）但无界（桶个数），长运行实例内存缓慢爬升。

### 6.2 具体做法（按 turn 粒度登记，防误删并行 turn）

1. `chat()` 内（`turnEnded` 声明处附近，`:267`）新增每 turn 局部登记表：

   ```java
   // 本 turn 触及过的桶 key（toolCallId）；turn 收尾时统一清桶，防异常路径残留
   var turnBucketKeys = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
   ```

2. `handleEventAndEmit`（`:377-438`）在三个 put/accumulate 调用点之前登记：`turnBucketKeys.add(toolCallId)`（`:391` 的 put、`:394-403` 的 write_file 分支、`:405-409` 的 present_file 分支）。方法签名增加 `Set<String> turnBucketKeys` 参数（private 方法，唯一内部调用点 `chat:310` 同步改）。
3. 清理点收口在 **3 参 `endTurn`**（`:463-470`）：A5 后 3 参归 TurnFinalizer 且**返回 boolean**（compareAndSet 结果，§2.2-1），chat 侧薄封装按返回值清桶——`if (TurnFinalizer.endTurn(eventBus, lease, sessionId, turnEnded)) { turnBucketKeys.forEach(k -> { toolCallNames.remove(k); presentFileBuffers.remove(k); writeFileInputBuffers.remove(k); }); }`。清桶发生在终态动作（closeSession/abandonSession）**之后**：桶纯内存、不产生任何 SSE 字节，先后不影响行为。覆盖的终态：AGENT_END（`:433-437`）、源流 error（`:321`）、onCancel 看护强收（`:343-354` 也走 3 参 endTurn）。**HITL 是部分覆盖**：`RequireUserConfirmEvent` 路径在 `:424-426` 直接 `closeSession`、不经 3 参 endTurn（租约已在 `:417` release），本 turn 的桶清理推迟到源 flux complete/error 回调的 endTurn——正常必达；flux 永不 complete 且连接无 onCancel 时与现状一样不清（无恶化）。准备段回滚（`:324-333` 走 `closeSession`+release）时 replyId 尚未产出事件、无本 turn 桶可清，无需处理（注释说明）。
4. **确认控制器不涉及**：`ConfirmController` 无这三个桶（恢复流的工具调用走 `AgentRuntimeService.resumeWithConfirmEvents`，未做工具名登记/截获——`:245-274` 实测无桶操作），A4 改动不触达。
5. 私有可测性观察点：新增包私有 `int bucketEntryCount()`（三 map size 之和），仅供测试断言，不进公有 API。

### 6.3 兼容性论证（清理时机不误删并行 turn 的桶）

- **同 session 并行 turn 不存在**：turn 由 `turn_lease` 全局串行化（I2，`docs/durable-sse-multinode-plan.md:190`），同一 session 任一时刻只有一个 turn 在写事件。
- **跨 session 并行 turn 各自隔离**：toolCallId 是每次工具调用唯一的 SDK 标识，跨 session 不可能撞 key；每个 turn 只清理**自己登记过的 key**（`turnBucketKeys` 是 turn 局部变量），另一 session 并发 turn 的桶条目完全不可达于本 turn 的清理循环。
- **key 语义不变**：桶的 key 仍是 toolCallId，登记表只是「本 turn 碰过哪些 key」的索引，不改变任何读写路径；`isTool`（`:506-516`）读 `toolCallNames` 的逻辑不动。
- **清理后迟到事件**：终态后理论上仍可能有极晚事件回调 re-put（与现状相同的既有边界，桶上限 64KB/2MB 兜底），不因本项恶化——注释记录该边界。
- HTTP/SSE/Redis 面零变化；唯一可观测差异是异常 turn 后 `bucketEntryCount()` 回落（运维向，DEBUG 级）。

### 6.4 单测计划

`ChatStreamControllerTest`（现 24 用例）扩充（沿用既有 mock 装配）：
1. 快乐路径不回归：write_file/present_file 全流程后桶计数为 0（End/合成路径本就 remove，A4 不得双删报错——`remove` 天然幂等）；
2. 异常路径清理：注入 ToolCallStart（登记）后源流直接 error → 3 参 endTurn 触发 → `bucketEntryCount()==0`；
3. **并行 turn 防误删**：turn A 只走到 ToolCallStart 即丢租约（stopIfLeaseLost 收尾），随后 turn B 完整执行——断言收尾后 A 的 key 被清而 B 的在用 key 仍在（经 `bucketEntryCount()` 与定向 get 断言）；
4. onCancel 看护路径：`OrphanTurnWatchdog` 触发的强收同样清桶（用例 2 的 onCancel 变体）；
5. 空 `turnBucketKeys` 的 turn（纯文本对话）收尾零操作、无异常。

### 6.5 风险与回滚

- 风险：登记遗漏某个 put 点 → 该类残留回到现状（不会更糟）；三个 put 点已逐一列于 §6.2-2，后续新增桶时同点登记即可（注释里写明约束）。
- 风险：`handleEventAndEmit` 签名变更波及测试反射调用——该方法为 private，`ChatStreamControllerTest` 经公开入口驱动（既有 24 用例不改即证明）；若确有反射用例，同步调整属测试代码适配，不算行为变化。
- 回滚：单 commit revert（登记表 + 清理行 + 观察点，均为新增面）。

---

## 7. 总体验证与验收

| 层 | 命令/入口 | 通过标准 |
|----|-----------|----------|
| 单测全量 | `mvn test`（基线实测于 master 7466e49：`grep -rE "^\s*@Test\b" src/test/java | wc -l` = **850**，含 @Test 文件 **88** 个，裸 `@Test` 853 处；AGENTS.md 记载的 83 类/848 已因近期 PR 增补过期，实施时以分支实跑刷新） | 0 失败；新增用例全绿；既有用例除 §4.4-1 / §5.4-5 明示的适配外零修改 |
| E2E 核心/多副本/沙箱 | agent-framework-ci 三个 E2E job（`docs/e2e-ci-plan.md`） | R 组断连续传、kill 接管、事件对账全绿（A1 字节一致性与 A3 缺帧语义的最终裁决） |
| 字节一致性抽查 | 调试页（`/debug`）连跑两轮对话 + 刷新恢复，浏览器 DevTools 抓 `data:` 行与升级前抓包对比 | 逐字节一致（permission_ask 双字段帧重点核对） |
| Redis 数据面 | `redis-cli` 抽查 `sess:{sid}:events` | key 结构与字段 `t`/`r`/`p` 不变（H3）；新行 `p` 含 `replyId` 属预期 |

### 提交切分（每项一 commit，顺序即 §1.3）

1. `refactor(agent-framework): 抽 TurnFinalizer 收口双控制器 turn 收尾成员（A5，行为零变化，教训注释原样迁移）`
2. `refactor(agent-framework): replyId 注入前移 emit 写路径，toSSE 收口为 AgentEventSseSerializer 共享实现（A1，SSE 帧字节与现状一致）`
3. `fix(agent-framework): emit 持久化失败不再广播 seq=0 帧（A3）`
4. `perf(agent-framework): SessionEventTailer 空闲退避至 2s，新事件立即恢复基频（A2）`
5. `fix(agent-framework): chat 控制器三个工具桶按 turn 粒度登记并在收尾清理（A4）`

### 范围外观察与未尽事项（超出本次范围，记录在案）

- A6 不实现；
- `docs/README.md` 索引与本文件条目待文档 PR 一并补（或随 A5 实施首提补入）；
- 读端兜底块的最终删除（存量 TTL 耗尽后，见 §3.3-4）；
- **既有观察（非本设计引入，本设计不修）**：HITL 迟到收尾不受 CAS 保护——`RequireUserConfirmEvent` 在 `ChatStreamController.java:417` 先 `lease.release()`、`:425` 直接 `closeSession`，均不置 `turnEnded`；源 flux 随后 complete 时 3 参 endTurn 的 CAS 首胜，而 `isLost()` 因 `released` 参与判定、时间判据被短路（`TurnLeaseGuard.java:105-108`）返回 false → 二次 `closeSession`：若 confirm-stream 恰在此窗口 `beginTurn`，会拆掉恢复 turn 的新 sink 并提前 flush 其缓冲。A5 迁移「迟到 closeSession」教训注释时保持原文（原文本就未声称覆盖该路径）；修复此空窗属独立后续项。
