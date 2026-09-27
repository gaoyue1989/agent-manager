# 压缩后会话历史可查设计（session_message 消息轨 + 归档装饰器 + history 双源合并）

> 状态：**已实施**（2026-09-27，实施记录见 §15；索引在 docs/README.md ② 档）。
> API 契约同步见 [api.md](api.md) history 节；实施与设计的偏差以 §15 为准。
>
> 现状核对：2026-09-27 编制，基于 commit `3dad588`、`io.agentscope:agentscope-*:2.0.3`。
> 文中所有 SDK 行为结论均经 2.0.3 jar 字节码（javap/strings）或本仓源码逐条核实，证据见 §13。
>
> 调研基线（外部机制对比）：agentscope-java `main@857e3e6c`、bytedance/deer-flow `@e080c19`、
> agentscope-ai/QwenPaw `@3822ec7`（2026-09-27 克隆分析）。

## 1. 背景与问题

### 1.1 问题陈述

会话触发上下文压缩（CompactionConfig，本项目 30 条触发 / 保留 10 条）后，
`GET /threads/{sid}/history` **查不到压缩点之前的会话历史**——平台 REST/调试页只剩
「1 条摘要 + 最近 10 条」，压缩前的消息原文不可见。

### 1.2 根因（agentscope-java 2.0.3 行为，字节码核实）

压缩是**破坏性**的（就存储层而言）：

1. `CompactionMiddleware.applyToContext` 执行 `contextMutable().clear(); addAll([摘要]+尾部)`；
2. 随后 `ReActAgent.saveStateToSession` 把整个 `AgentState` **单 JSON 快照整体覆盖**写回
   `agent_state` 表——压缩前的消息从该表物理消失；
3. `agent_state.version` 列只是 CAS 并发锁，**不保留历史版本**，无法回滚。

另外两个正在发生的流失点（本次调研新确认）：

- **历次压缩摘要本身也会丢**：`ConversationCompactor.filterSummaryMessages` 在再次压缩时把
  旧摘要消息从上下文过滤掉，随下一快照覆盖消失——连「压缩过什么」的记录都留不下；
- **大工具结果驱逐**（ToolResultEvictionMiddleware）把 >80k 字符的结果替换为占位符、原文只存
  `agent_fs` 的 `large_tool_results` 文件，占位符版本随快照落库。

### 1.3 现状的两道兜底（本方案的原料）

| 兜底 | 现状 | 局限 |
|------|------|------|
| harness 会话转录（`sessions/*.jsonl` + `.log.jsonl`，append-only，`CompactionEntry` 标记压缩、"Entries are never deleted"）随 `agent_fs` 落库；压缩前 `offloadBeforeCompact=true`（默认）先把全量消息写转录 | **已启用**（HarnessAgentFactory 未调 disableTranscript） | ① 工具入参 >500 / 出参 >1000 字符只存预览（`SessionTranscriptWriter.truncateInput/Output` 硬编码）；② 平台 REST 无读取端点；③ JSONL 格式属 SDK 内部实现 |
| Agent 侧内置工具 `session_search` / `session_history` / `session_list`（搜转录） | **已注册**，Agent 自查压缩前历史可用 | 只服务模型，不服务 UI/REST |

即：**数据没有全丢，缺的是无损、可分页、平台可查的消息级历史链路**。

### 1.4 外部机制调研结论（设计参考）

| 项目 | 机制 | 对「压缩后历史可查」的答案 |
|------|------|--------------------------|
| agentscope-java | `agent_state` 快照（可压缩、覆盖写）+ SessionTree 转录（append-only）双层 | 转录层可查但**有损**（工具 I/O 截断）；上游 admin REST 读活 state，同样查不到 |
| bytedance/deer-flow | **双轨**：LangGraph checkpoint（状态轨，只追加）+ `run_events` 消息轨（`(thread_id,seq)` 唯一，用户可见历史权威） | 压缩只动状态轨；消息轨永不因压缩改动，用户可翻到最早消息 |
| QwenPaw | write-through `history.db`（seq 全局地址）+ 驱逐进索引（带 seq span）+ 摘要仅作「状态缓存」 | "The summary is a state cache, never a replacement for raw history"；`recall_history` 按 seq 精确重读 |

**共同结论：压缩只动模型上下文，用户可见历史必须走独立 append-only 消息轨。** 本方案照此落地。

## 2. 目标 / 非目标

**目标**
1. 压缩后，压缩点之前的会话历史在 REST + 调试页可查、可分页；
2. 归档尽量无损（文本全量、状态最新），优于现状 8000 字符截断视图的信息量；
3. 不影响 A2A / HITL / 多副本 / 对话主链路（归档 fail-soft）。

**非目标**
1. 不改压缩算法与触发参数；
2. 不做「从历史恢复上下文重灌模型」（三个参考项目也都没做）；
3. 不改转录（agent_fs JSONL）行为，不迁移/升级 SDK。

## 3. 设计原则

1. **双轨分离**（deer-flow）：`agent_state` = 模型上下文轨（可压缩、覆盖写）；`session_message` = 用户消息轨（append-only，压缩只新增行、从不删改内容）。
2. **write-through，不做事后补救**（QwenPaw）：消息在 save 时同步归档，压缩发生时原文已在库里。
3. **归档是无损源**：文本取各版本最长形态，状态取最新形态（§6.4）。
4. **fail-soft**：归档任何异常只告警，绝不影响对话 turn 与 agent_state 落库。
5. **同键同谓词**：`session_message.session_id` 与 `agent_state.session_id` 存同一复合键，查询复用既有 LIKE 谓词，不要求新旧会话做数据迁移。

## 4. 总体方案

```
用户消息 ──► HarnessAgent ──► AgentState.contextMutable()
                 │                    │ 每 acting 边界 / call 结束 / 失败 / 停机
                 │                    ▼
                 │        AgentStateStore 写链（装饰器链）：
                 │        AskingContentBackfillStateStore          ← ASKING content 回填（既有）
                 │          └─► SessionMessageArchiveStateStore    ← 【新增】write-through 归档
                 │                └─► SandboxAwareMysqlAgentStateStore → agent_state 表
                 │
                 │  CompactionMiddleware（不变）：clear+addAll([摘要]+尾部)
                 │        └─ 摘要消息随下一次 save 进归档（kind=compaction_summary）
                 ▼
GET /threads/{sid}/history（改造）：
   归档全序列（session_message，压缩前原文在此） ⨝ 当前上下文（agent_state，尾部权威）
   → 合并时间线 + 压缩分隔条 + 游标分页
```

## 5. 数据模型

新增一张表，与 `agent_state` 同库（`oaf_checkpoint`，沿用 CHECKPOINT_* 数据源），服务侧初始化建表
（同 turn_lease / confirm_context 等 8 张自建表惯例）：

```sql
CREATE TABLE IF NOT EXISTS session_message (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,      -- 归档序（≈消息时间序），游标分页键
  session_id VARCHAR(255) NOT NULL,                  -- slot 复合键，与 agent_state.session_id 同值
  msg_id     VARCHAR(128) NOT NULL,                  -- Msg.getId()，幂等键
  kind       VARCHAR(32)  NOT NULL DEFAULT 'message',-- message | compaction_summary
  role       VARCHAR(32)  NULL,                      -- MsgRole 名（USER/ASSISTANT/TOOL...）
  reply_id   VARCHAR(64)  NULL,                      -- 归档时盖 AgentState.getReplyId() 戳
  msg_data   LONGTEXT     NOT NULL,                  -- Msg JSON（形态与 agent_state.context[] 元素同构）
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_session_msg (session_id, msg_id),
  KEY idx_session_id (session_id, id)
);
```

语义说明：

- **session_id**：存 `MysqlAgentStateStore` 的 slot 复合键，格式
  `"{normalizeUser(userId)}:{sessionId}"`（ThreadController.java:117-119 注释与
  `MysqlAgentStateStore.slotId` 字节码双重确认；normalizeUser 空值归一 `__anon__`）。
  归档装饰器按同一规则生成（实现时以集成测试锁定与 `agent_state.session_id` 同值）。
- **msg_id**：`Msg.getId()`（SDK 构建时自动 `generateId()`，非空 UUID 形态）。
- **msg_data**：单条 Msg 的 JSON。序列化用 Jackson 直写（`State` 是空标记接口，
  `Msg` 无 `toJson()`），形态须与 `agent_state.state_data` 的 `context[]` 元素一致
  （content 块带 `type` 判别字段），由单测 round-trip 锁定（§10）。
- **kind=compaction_summary**：`Msg.name == "__compaction_summary__"` 的摘要消息
  （`ConversationCompactor.buildSummaryMessage` 字节码确认该常量），渲染为分隔条而非普通消息。

## 6. 写入链路：SessionMessageArchiveStateStore

### 6.1 装配位置

`AgentScopeConfig.distributedStore()` 调整为**最内层**插入：

```java
new AskingContentBackfillStateStore(          // 既有：ASKING content 回填（原位 patch 后下传）
    new SessionMessageArchiveStateStore(      // 新增：归档
        new SandboxAwareMysqlAgentStateStore(...)))
```

放最内层的原因（评审修正点，见 §13-R2）：Backfill 是**先原位 patch 再下传**，
内层装饰器看到的永远是回填后的最终形态——归档的 ASKING 块天然携带完整参数。

### 6.2 拦截点

实现 `AgentStateStore` 全接口（覆写形状照抄 `AskingContentBackfillStateStore`），仅对
`stateKey.equals("agent_state")` 的写入归档，其余透传：

| 方法 | 动作 |
|------|------|
| `save(userId, sessionId, stateKey, State)` | 归档该 AgentState.context → 透传 |
| `save(userId, sessionId, stateKey, List<? extends State>)` | 逐元素归档（幂等去重天然安全）→ 透传 |
| `saveIfVersion(userId, sessionId, stateKey, State, long)` | **必须归档**：2.0.3 MySQL 版本化存储的主写路径（ReActAgent `persistAgentStateCas` 走此口；接口 default 会绕过 delegate，既有装饰器注释已警示）→ 透传返回值 |
| get / getList / getVersioned / supportsVersioning / exists / delete / listSessionIds / close | 原样透传 |

> 参数语义序（评审修正点 §13-R1）：`save(String userId, String sessionId, String stateKey, ...)`。
> 既有 `AskingContentBackfillStateStore` 形参名（sessionId, userId）与 SDK 语义互换，仅为命名
> 问题不影响行为；新增类按 SDK 语义命名，避免误导。

### 6.3 归档时机与顺序

对每次拦截到的写入，**先归档、再 delegate**（先 delegate 失败会丢捕获；归档自身 try/catch
全包，异常仅 `log.warn`）：

1. 取 `AgentState.getContext()`（防御拷贝）；
2. 逐 Msg 生成候选行：`msg_id=getId()`、`role=getRole()`、
   `kind`（`"__compaction_summary__".equals(getName()) ? compaction_summary : message`）、
   `reply_id = state.getReplyId()`（评审修正点 §13-R6：turn 级盖戳，取代 Redis 顺序猜测）；
3. 与库中同 `(session_id, msg_id)` 行做**块级合并**（§6.4）；
4. 批量 upsert（一条 `INSERT ... ON DUPLICATE KEY UPDATE`）。

同会话写入本被 turn_lease 串行化，多副本无竞态；即使极端并发，唯一键 + 合并语义仍幂等。

### 6.4 合并规则：文本取最长，状态取最新

直接「整体更长才覆盖」会丢工具状态迁移（PENDING→FINISHED），「后者覆盖」会丢被
truncateArgs/pruneToolResults 改短的原文——两者冲突，故按 content 块合并（块 id 对齐）：

| 维度 | 规则 | 理由 |
|------|------|------|
| 文本载荷（text/thinking 块 text、tool_use.input、tool_result.output） | 两版**取长** | 流式扩展/回填是增长（覆盖为新）；裁剪/截断是收缩（保留原文） |
| 状态类字段（ToolCallState / ToolResultState）、metadata、reply_id | 两版**取新**（reply_id 取首个非空） | ASKING→FINISHED 等迁移必须呈现终态；reply_id 保持首轮盖戳 |
| 新块（旧版无此块 id） | 直接并入 | 新增 tool_result / 内容块 |

写路径「读-合并-写」：一次 `SELECT ... WHERE (session_id,msg_id) IN (...)` 取旧行 → Java 内
合并 → upsert。结果：**归档行 ≥ 任一单版本的信息量**（文本最长 + 状态最新），成为无损源。

### 6.5 kind=compaction_summary 的处理

摘要消息同样归档（普通 upsert，同合并规则）。即使 `filterSummaryMessages` 在下次压缩时把它
从上下文剔除，归档行仍在——「压缩过什么」可回溯（§1.2 流失点 2 由此闭环）。

## 7. 查询链路：history 双源合并

### 7.1 数据获取

- **归档侧**：`SELECT ... FROM session_message WHERE <session 谓词> ORDER BY id`。
  谓词复用 `AgentStateReader.loadFragments` 的 **5 形 LIKE**（`= ? / LIKE '?:%' / LIKE '?__%' /
  LIKE '%:?' / LIKE '%__?'`，覆盖 `makeThreadId` 的 `{tenantPrefix}__{threadId}` 与
  slotId 的 `{user}:{key}` 两种形态）——评审修正点 §13-R8：勿用 ThreadController 的 2 形谓词。
- **状态侧**：现有 `AgentStateReader.loadStateData` + `StateDataParser`（不变）。

### 7.2 合并算法

1. 归档序列按 `id` 升序为时间线基底；
2. 当前 `agent_state.context` 中的 msg_id 若已存在归档行：**以归档合并行渲染**
   （§6.4 保证其信息量 ≥ state 版本；执行状态两者一致，因状态取新的合并规则）；
   state 中存在而归档缺失的（本功能上线前的存量会话尾部）：以 state 版本插入基底末尾；
3. `kind=compaction_summary` 行渲染为合成项：
   `{role:"compaction", type:"compaction_summary", content:<摘要文本>, msg_id, created_at}`
   ——位置即分隔条位置（压缩发生在该行 id 与后续行之间）；
4. `pendingConfirm`、文件卡片补齐、`reply_id` 兜底回填（Redis ZSET 顺序分配）仅对**无 reply_id
   戳**的 assistant 消息执行（存量会话兼容）；
5. 工具输出展示截断沿用 `AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS`（默认 8000，作用于展示层）。

### 7.3 API 变化（增量兼容）

`GET /threads/{sid}/history?limit=200&beforeId=&includeArchived=true`

```jsonc
{
  "session_id": "...",
  "messages": [
    { "role": "user", "content": "...", "msg_id": "…", "origin": "archive", "reply_id": "…" },
    { "role": "assistant", "content": "...", "origin": "archive", "reply_id": "…",
      "tool_calls": [ { "id": "…", "name": "…", "input": {…}, "state": "finished",
                        "output": "…", "output_truncated": false } ] },
    { "role": "compaction", "type": "compaction_summary", "content": "…压缩摘要…",
      "msg_id": "…", "created_at": "…" },
    { "role": "user", "content": "…（压缩后尾部，origin 缺省 = state）", "reply_id": "…" }
  ],
  "hasMore": true,
  "nextBeforeId": 123
}
```

- 旧字段全部保留；新增 `origin`（archive|state，state 可缺省）、compaction 合成项、
  `hasMore`/`nextBeforeId` 分页字段；
- `includeArchived=false` 退回现状行为（仅 state），便于对账与回退；
- 前端对未知 `role=compaction` 忽略即可优雅降级；调试页 chat 模块渲染压缩分隔条（折叠卡）。

## 8. 生命周期与配置

- **清理**：`SessionCleanupService` 将 `session_message` 加入 `CLEANUP_TABLES` 白名单并新增
  `deleteBefore("session_message", cutoff)`——与 agent_state 相同的 `updated_at < 7 天前` 语义，
  历史生命周期与会话一致（现状保留期语义不变）。
- **配置**：`agent.history.archive-enabled`（env `AGENT_HISTORY_ARCHIVE_ENABLED`，默认 `true`）。
  关闭时装配跳过装饰器、history 查询自动只走 state（即现状）。
- **删除会话**：`DELETE /threads/{sid}` 级联清理增加 `session_message` 一并删除（既有级联清单
  已含 agent_state/agent_fs/Redis/session_user/confirm_context/turn_lease/file_asset）。

## 9. 兼容性与影响面

| 面 | 影响 |
|----|------|
| 对话主链路 | 无感知（归档 fail-soft，异常不外抛） |
| A2A `tasks/get` / `message/*` | 不变（MySqlTaskStore 仍读 agent_state） |
| HITL | 不变；且归档经 Backfill 内层，ASKING 块参数完整 |
| 多副本 | 无新增争用（turn_lease 已串行同会话写入；upsert 幂等） |
| 压缩/记忆/转录 | 全部不动 |
| 存量会话（上线前） | history 尾部照常显示、无压缩前归档（与现状相同，不回填；P2 可从转录补录） |
| 性能 | 每次 state save 增 1 次批量 SELECT + 1 次批量 upsert（行数 ≈ context 大小 ≤ 30）；量级远低于 save 本身 |

## 10. 测试计划

单测（新增）：
1. **装饰器**：agent_state 键归档 / 其他键跳过；`saveIfVersion` 同样归档且返回值透传；
   幂等（同 msg_id 重放不重复）；合并规则四例——流式增长覆盖、状态迁移（ASKING→FINISHED
   状态新/文本不缩）、裁剪收缩保留原文、新块并入；reply_id 首戳保持；fail-soft（底层抛异常时
   save 仍成功、归档静默降级）；
2. **序列化 round-trip**：Msg → msg_data JSON → `StateDataParser.toRoleContentList` 可解析，
   形态与 `context[]` 元素一致（锁 Jackson 形态）；
3. **合并查询**：归档+state 合并顺序、compaction 分隔条位置、`origin` 标注、分页游标、
   `includeArchived=false` 回退、存量会话（无归档行）行为与现状一致；
4. **清理**：`CLEANUP_TABLES` 含 session_message、deleteBefore 生效。

回归：现有 1026 用例全绿（重点：AgentScopeConfig 装配、history 相关既有用例）。

E2E（可选，建议做）：mock LLM fixtures 增加 30+ 轮场景触发压缩，断言 history 返回压缩前
消息与压缩分隔条（可挂 e2e-core 的 S 组或独立脚本）。

## 11. 分期

**P1（本方案，解决本问题）**：建表 + 归档装饰器 + history 合并 + 清理/删除级联 + 开关 + 单测 + 文档。

**P2（候选，另行立项）**：
- 转录取证端点 `GET /threads/{sid}/transcript`（读 agent_fs `sessions/*.log.jsonl`，含
  CompactionEntry 时间线；反正转录已在落库）；
- 既有会话归档回填（从转录补录，受 500/1000 截断限制）；
- `session_message` 全文检索（对齐 agent 侧 session_search 能力给到 REST）；
- `large_tool_results` 驱逐文件与 history 的关联展示。

## 12. 风险与已知边界

1. **大工具结果**：结果若在首次 save 前已被驱逐为占位符（80k 阈值），归档即占位符版本，
   原文在 `agent_fs` 驱逐文件（与现状一致，P2 关联展示）；
2. **msg_data 形态漂移**：SDK 升级若改 Msg JSON 形态，round-trip 单测第一时间暴露；
   解析侧（StateDataParser BFS）本身容忍未知字段；
3. **多 fragment 会话**：agent_state 多 item_index 时 state 侧仍只取第一个 context
   （docs/history-agentstate-design.md §8-9 既有限制）；归档侧按 msg_id 去重天然跨 fragment，
   合并视图反而比现状更完整；
4. **极端时序窗口**：消息在首次 save 前即被压缩移出（理论可达：压缩保留最新尾部 + save 覆盖
   每个 acting 边界，实际不可达）；若实测发现，捕获点前移至自定义 Middleware.onReasoning
   （压缩中间件之前），已在设计中留此后手；
5. **保留期 7 天**：`SESSION_RETENTION_DAYS` 为硬编码（SessionCleanupService.java:137，
   既有问题不在本方案范围），超期历史与会话一同清理。

## 13. 评审核对记录（2026-09-27 review）

| # | 发现 | 处置 |
|---|------|------|
| R1 | `AgentStateStore` 参数语义序是 `(userId, sessionId, stateKey)`（`slotId` 字节码：`normalizeUser(arg1)+":"+arg2`；ThreadController.java:117-119 注释同证），既有装饰器形参名互换仅命名问题 | 新类按 SDK 语义命名；session_id 复合键格式写入 §5，集成测试锁定同值 |
| R2 | Backfill 是先原位 patch 再下传——归档装饰器若放外层将看到 patch 前形态 | 装配位置定为**最内层**（§6.1） |
| R3 | `State` 是空标记接口，`Msg` 无 `toJson()`，序列化无官方出口 | msg_data 用 Jackson 直写，形态由 round-trip 单测锁定（§5、§10-2） |
| R4 | 「整体更长才覆盖」会丢状态迁移，「后者覆盖」会丢裁剪前原文 | 升级为**按块合并**：文本取长、状态取新（§6.4） |
| R5 | `filterSummaryMessages` 会过滤历次摘要——摘要本身也是流失对象 | 摘要按 `kind=compaction_summary` 归档，压缩历史可回溯（§6.5） |
| R6 | reply_id 现靠 Redis ZSET 顺序猜测分配，合并长序列会错位、Redis 过期后失联 | 归档时盖 `AgentState.getReplyId()` 戳；Redis 回填仅兜底无戳消息（§6.3、§7.2-4） |
| R7 | 清理是白名单制（`CLEANUP_TABLES`），不加白名单新表永不清理 | 加入白名单 + deleteBefore（§8） |
| R8 | session 匹配谓词有 2 形（ThreadController）与 5 形（AgentStateReader，含 `__` 形态）两种 | 归档查询统一用 5 形谓词（§7.1） |
| R9 | `saveIfVersion` 是版本化存储主写路径，仅拦 save 会漏归档 | 三写路径全拦（§6.2） |

## 14. 改动清单

| 动作 | 文件 |
|------|------|
| 新增 | `service/SessionMessageStore.java`（session_message 表访问：建表/批量读合并/分页查询/删除） |
| 新增 | `service/SessionMessageArchiveStateStore.java`（归档装饰器，§6） |
| 修改 | `config/AgentScopeConfig.java`（distributedStore 装配加一层） |
| 修改 | `controller/ThreadController.java`（history 双源合并、分页、includeArchived） |
| 修改 | `service/SessionCleanupService.java`（CLEANUP_TABLES + deleteBefore） |
| 修改 | `service/StateDataParser.java`（单消息解析复用：compaction 合成项） |
| 修改 | `src/main/resources/application.yml` + `config/HistoryConfig.java`（archive-enabled 开关） |
| 修改 | `src/main/resources/static/debug/modules/chat.js`（压缩分隔条渲染） |
| 文档 | 本文档 + docs/README.md 状态列 + AGENTS.md 端点/配置表 + api.md history 契约节 |

## 15. 实施记录（2026-09-27）

**回归结果**：`mvn test` 1070 用例 / 0 失败 / 0 错误 / 4 跳过（既有 OpenSandbox IT），BUILD SUCCESS。

**新增文件**
- `service/SessionMessageStore.java`：建表（SessionUserStore 惯例 DATETIME(3)/NOW(3)）、
  `archiveBatch`（读-合并-写 + 批量 upsert）、`findPage`（5 形谓词 + 游标，DESC 取数翻转升序）、
  `deleteBySession`、`deleteBefore`、`mergeMsgJson`（静态，供单测）。
- `service/SessionMessageArchiveStateStore.java`：三条写路径全拦（save×2 + saveIfVersion）、
  fail-soft、`slotKey` 复合键、`toRecord`（Msg → Jackson JSON + kind/reply_id）。
- 测试：`SessionMessageStoreTest`（合并规则 6 例 + SQL 形态 5 例）、
  `SessionMessageArchiveStateStoreTest`（触发面 6 例 + 序列化 round-trip）、
  `ThreadControllerHistoryMergeTest`（合并/分页/回退 6 例）。

**修改文件**
- `config/HistoryConfig.java`：新增 `archiveEnabled`（`AGENT_HISTORY_ARCHIVE_ENABLED`，默认 true）
  ——本清单原写 AgentManagerProperties，实际落在独立 HistoryConfig（避免波及其 16 处位置构造，
  该类本就是 history 配置的家）；`AgentManagerProperties` 未动。
- `config/AgentScopeConfig.java`：装饰链 `Backfill(Archive(SandboxAwareMysql))`，开关关闭时跳过 Archive。
- `controller/ThreadController.java`：构造器链重排（既有 6 参/8 参构造保持签名不变、归档关闭）；
  history 端点新增 includeArchived/limit/beforeId 与 hasMore/nextBeforeId；
  `loadMessagesMerged` + `postProcessMerged`；reply_id 顺序回填重构为 `fillReplyIds`（两视图共用）；
  deleteThread 级联 session_message。既有测试调用点批量补 3 参数。
- `service/SessionCleanupService.java`：CLEANUP_TABLES + session_message 清理。
- `service/StateDataParser.java`：toRoleContentList 条件透传 `msg_id`/`name`（StateDataParserTest
  两处精确断言同步更新）；compaction 合成项在 ThreadController 构造（比设计预期更收敛）。
- `application.yml`：新增 `agent.history.archive-enabled` 显式节；`static/debug/modules/chat.js`：
  `role=compaction` 分隔条（details 折叠卡，无新增 CSS）。

**实施期发现（对设计的修正）**
1. `ToolResultBlock.getOutput()` 返回**嵌套 ContentBlock 数组**而非字符串——payloadLength 需递归
   穿透计数，否则裁剪收缩检测失效（`mergeShouldHandleToolResultOutputArrayShape` 锁定）。
2. DTO 的 tool_calls `state` 语义「结果状态优先」：配对到 tool_result 时显示结果态而非
   tool_use 态（round-trip 测试按此断言）。
3. 会话删除级联未并入 `ThreadController.deleteBySessionId`（其谓词仅 2 形 LIKE，覆盖不了
   复合键的 userId 分量查询），改走 `SessionMessageStore.deleteBySession`（5 形谓词，更宽）。
4. `getThread` 详情接口同步切换到合并视图首页（固定 limit=200）。

**真实 LLM 端到端验证与两处设计修正**（OpenRouter `https://openrouter.ai/api/v1` +
`stealth/space-bunny-alpha`，e2e 库 13306 + Redis 16379，低阈值 trigger=6/keep=2 实跑两个会话
12 轮，3 次压缩触发；`session_message` 建表 DDL 亦经真实 MySQL 验证）：

5. **设计 §6.3/R6 的「reply_id 盖戳」假设被实测推翻**：`AgentState.getReplyId()` 在 ChatUI
   链路是**会话级**而非逐轮值（16 条消息全部同一 id）——盖戳会让前端按 reply_id 关联 turn
   失真。修正：归档不再盖戳（列保留、恒 NULL），reply_id 唯一来源回到查询侧 Redis 顺序回填
   （fillReplyIds 对 assistant 无条件按序分配，实测逐轮 id 各不相同）；合并视图仅 assistant
   携带 reply_id。
6. **分页首页 state 节点重复合入**：state 独有消息的过滤原本只对照「当前页归档集」，归档行
   在其他页的消息会在首页重复出现（实测 limit=3 首页 4 条，且摘要分隔条跨页重复）。修正：
   新增 `SessionMessageStore.findAllMsgIds`（全会话归档集，首页专用），state 节点凡已归档
   一律跳过；修正后首页恰好 limit 条、跨页零重复、游标正常推进。

**P2 未动**（另行立项）：转录取证端点、存量会话归档回填、session_message 全文检索、
large_tool_results 驱逐文件关联展示。
