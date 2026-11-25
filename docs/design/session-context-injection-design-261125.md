# 会话上下文注入设计（Session Context Injection）

> 状态：**已实施（2026-11-25，`feat/session-context-injection` 分支）**。验证记录见 §12；实施中相对评审稿的修正见 §10。
> 范围：`agent-framework/`（接口、注入、持久化）+ `frontend/`（控制台展示）+ `e2e/`。platform-router、backend、K8s 清单零改动（§2-F9）。

## 1. 需求与决策

**需求**

1. 提供接口向**当前会话**注入上下文（公告、头条等已备好的参考资料）。
2. 注入过程**不调用模型**：不产生回复、不产生事件、不触发沙箱与标题生成。
3. 注入后该会话的后续轮次以注入内容为事实依据（查询、总结等）。
4. 用户打开该会话的历史时，能看到已注入的上下文，刷新不丢。

**已确认的决策**

| # | 决策 | 结论 |
|---|---|---|
| D1 | 单次注入体量 | < 5000 字符。正文内联，不做文件卸载 |
| D2 | 同一会话注入多次 | 按资料名（`key`）覆盖：同名只留最新一份，不同名并存；另提供移除 |
| D3 | 启用方式 | 不设开关。调用注入接口即对该会话生效；从未注入的会话保持原状 |
| D4 | 调用方 | 前端或可信上游以用户身份直连业务服务，控制面不改 |
| D5 | 可见性 | 用户在会话历史中能看到已注入的上下文 |

**非目标**

- 大体量正文的文件卸载与检索。
- 页面已打开时的实时推送（§13）。
- 调试页（`/debug`）与运营监控页的展示。
- 子 agent 继承会话上下文（spawn 时由父 agent 在指令中转述，现状不变）。

## 2. 已核实的代码事实

设计的约束来源，均为实施当日实读代码（AgentScope Java 2.0.4 源码 + 本仓库）。

| # | 事实 | 锚点 |
|---|---|---|
| F1 | 唯一对话入口每轮：抢 `turn_lease` → ASKING 预检 → 构造 USER `Msg` → `ChatUiChannel.dispatchStream`；SDK 整轮 `doCall` 结束才落库 | `ChatStreamController.chat`、`TurnInputArchiveMiddleware` |
| F2 | SDK **每轮从 store 重载状态**，默认 `ConflictPolicy.OVERWRITE`，并维护进程内 `stateCache`/`slotVersions`；agent 级 `getAgentState`/`saveAgentState` 是缓存语义的「管理面 get→mutate→save」 | `ReActAgent.activateSlotForContext`、`persistAgentStateCas` |
| F3 | Channel 链路运行时身份被网关改写：`userId` = peer（前端 sid）、`sessionId` = `gw-{hash(canonicalKey)}`；`agent_state` 槽位为 `{peer}:{gw-hash}`，派生函数为 SDK 私有 | `HarnessGateway.buildRuntimeContext` / `generateSessionId` |
| F4 | 压缩在**每次推理前**判定，30 条或 80k token 任一命中 → 整个会话替换为 `[摘要] + 尾部 10 条`；仅 SYSTEM 角色豁免，**没有逐消息豁免** | `CompactionMiddleware.onReasoning`、`ConversationCompactor.compactIfNeeded` |
| F5 | 压缩前的记忆 flush（默认开）把前缀对话抽进用户长期记忆；`VikingSessionCommitStateStore` 遍历 `AgentState.context` 提交到 OpenViking 会话记忆 | `HarnessAgentFactory` compaction 装配、`VikingSessionCommitStateStore.enqueueCommit` |
| F6 | 系统提示词**每轮组装一次**：`sysPrompt` → `MiddlewareBase.onSystemPrompt` 管线（带 RuntimeContext）→ PreCallEvent hooks（`appendSystemContent`）→ 定稿；系统提示词不参与压缩、不进 AgentState | `AgentBase.notifyPreCall`、`ReActAgent.applySystemPromptMiddlewares` |
| F7 | 先例：`VikingMemoryInjectionMiddleware` 用 `onSystemPrompt` + `SessionKeyResolver` 按会话注入；`ui_context` 是单槽覆盖，其 Hook 读消息 metadata，该键只有 chat 与 AGUI 两条链路写入 | 同名类 |
| F8 | 会话生命周期：`session_user` 承载归属与保留期；`SessionCleanupService` 每日清理；`ThreadController.deleteThread` 为手工枚举级联 | 同名类 |
| F9 | 网关 `^/agent/(?<short>...)` 整段透传业务 svc，`X-User-Id` 由鉴权结果注入，`client_max_body_size 200m` | `manifests/platform-router.yaml` |
| F10 | `LlmLoggingMiddleware` 记录每次模型调用的 messages（含 system），经 `GET /threads/{sid}/llm-calls` 可查 | 同名类 |
| F11 | 历史合并按**角色**驱动：归档行 → `toRoleContentList` → `postProcessMerged`（特殊行按消息 `name` 识别，不看 `kind`）→ `fillReplyIds`（只跟踪 user/assistant）。监控总览对 `session_message` **全表**按 role 计数，不看 `kind` | `ThreadController.loadMessagesMerged`、`DebugApiController` |
| F12 | `onSystemPrompt` 管线按 `order()` **降序**依次转换（高者先，低者更靠近尾部）；SDK 侧转换器（PlanMode / Skill / WorkspaceContext）均为默认 1，`VikingMemoryInjectionMiddleware` 为 2；hooks 的 `appendSystemContent` 与 Harness 追加的静态块（如 `## Subagents`）都在管线**之后** | `ReActAgent` builder 末尾 sort、`AgentBase.notifyPreCall`、真机 `llm-calls` 记录（§12） |
| F13 | history 响应里 `files`、`pendingConfirm` 是与 `messages` **平级**的兄弟字段，由前端自行组装；控制台 `loadHistory` 只处理 `user`/`assistant` 两种角色，其余条目丢弃；欢迎态判定为「全部是 system」且欢迎态下只渲染 system 通知 | `ThreadController.threadHistory`、`frontend/src/components/chat/ChatPane.tsx` |
| F14 | 会话列表读 `session_user`；侧边栏标题为空时显示会话 id；标题在首条用户消息后由模型生成，已有标题则跳过 | `ThreadController.listThreads`、`SessionTitleService.generateAsync` |
| F15 | `session_user.session_id` 的排序规则是 `utf8mb4_unicode_ci`：不同排序规则的两列做 JOIN 会报 illegal mix of collations；且比较**不区分大小写**，而 Java 侧保留命名空间判定原本区分大小写 | `V1__baseline`、`SessionKeyNamespace` |
| F16 | 共享的归属守卫 `SessionAccessGuard.ownedBy` 依赖 `SessionUserStore.findUserIdBySession`，后者把查询异常吞成 `null`，而「无映射行」在守卫里等于放行 | 同名类 |

## 3. 方案选型

| 方案 | 做法 | 结论 |
|---|---|---|
| 甲 复用 `ui_context` | 往单槽表写正文，沿用 Hook 注入 | **否决**：单槽覆盖无法容纳多份资料；无会话归属校验；Hook 依赖消息 metadata，A2A 与 HITL 恢复链路不注入 |
| 乙 对话历史追加 | 把头条作为消息写入 `agent_state.context`（直接改 store，或跑一轮跳过模型的 turn） | **否决**，硬伤见下 |
| 丙 会话参考资料 + 每轮系统提示词注入（**选定**） | 新表按会话持久化；每轮在 `onSystemPrompt` 组装期追加；history 响应平级返回供展示 | 满足全部目标，且是多副本安全的最短路径 |

**方案乙的硬伤**

1. **会被压缩摘要掉**（F4）：头条作为普通消息进入会话，约 14 轮问答或 80k token 后被替换成一段摘要，此后「查询某条新闻的细节」失去原文依据。
2. **污染用户长期记忆**（F5）：头条正文会被压缩前 flush 当作对话内容抽取，并提交进 OpenViking 会话记忆。
3. **多副本下丢更新**（F2、F3）：SDK 保存冲突策略是 OVERWRITE，在途 turn 的收尾落库会覆盖轮间写入。要写对必须走 store 级 CAS 循环 + 持 `turn_lease` + ASKING 预检；全新会话还要复刻 SDK 私有的 `gw-hash` 槽位派生。
4. **语义与统计错位**：头条显示成一条用户消息；监控口径「一条 USER 消息 = 一轮」被抬高。

**方案丙为什么对**

- 系统提示词不参与压缩与记忆 flush（F6），注入内容在会话生命周期内**每轮确定性可见**。
- 写入只是一次数据库事务，不碰 agent、不碰 SDK 状态，天然无模型调用；无进程内状态，任意副本可服务（架构原则二）。
- `onSystemPrompt` 是 SDK 公开扩展点且带 RuntimeContext，chat、A2A、AG-UI、HITL 恢复全部链路共用同一注入点（F6、F7）。
- 注入段是 `onSystemPrompt` 管线的**最后一个转换器**，排在全部 SDK 上下文块之后（F12）。需要如实说明的是：它并不在最终提示词的绝对末尾——Harness 在管线之后还会追加静态的 `## Subagents` 等块（§12 真机实测，8452 字符的提示词里注入段位于 23% 处）。会话内多轮前缀不变；跨会话的缓存前缀只覆盖到注入段为止。

### 3.1 展示不用 `session_message` 副本

评审过程中有意见建议往 `session_message` 写一条 `kind='injected_context'` 的记录供对话流展示。不采纳：

- **两个真相源**：同一份上下文在 `session_context` 与 `session_message` 各存一份，覆盖与删除都必须同步两处。
- **并非「补 1 行」**：`postProcessMerged` 按消息 `name` 识别特殊行、不看 `kind`，需要新增分支；监控总览对 `session_message` 全表计数，副本会抬高消息数（F11）。
- **前端无论如何都要改**：控制台只处理 `user`/`assistant`，其他条目直接丢弃（F13）。

选定做法：history 响应新增与 `messages` 平级的 `context` 数组（沿用 `files`/`pendingConfirm` 的既有组装式样，F13），前端渲染为会话顶部的上下文卡片。单一真相源不变，覆盖与删除自动生效，`messages` 的分页游标与 `fillReplyIds` 都不受影响。

## 4. 接口契约

身份口径与 `/threads/{sid}/*` 既有端点一致：`X-User-Id` 头，缺省回落匿名 `debug-user`（`SessionAccessGuard`）。完整字段说明见 [../agent-framework/docs/api.md](../../agent-framework/docs/api.md)。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/threads/{sid}/context` | 注入。body `{key, title?, content}`；按 `key` 覆盖，新建 `201`、覆盖 `200`，响应带 `effectiveFrom: "next_turn"` |
| GET | `/threads/{sid}/context` | 列表。默认不带正文，`?includeContent=true` 附带 |
| DELETE | `/threads/{sid}/context/{key}` | 移除；未知 key 返回 404 `context_not_found` |
| GET | `/threads/{sid}/history`、`/threads/{sid}` | 既有接口新增平级字段 `context[]`：`key` / `title` / `content` / `content_chars` / `created_at` / `updated_at`，按注入顺序 |

错误：

| 状态 | 场景 |
|---|---|
| 400 | `bad_request`：`key` / `title` / `content` 不合法（`key` 须匹配 `[A-Za-z0-9._-]{1,64}`；`title` ≤ 255 字符且必须单行；`content` 非空白） |
| 404 | `thread_not_found`：会话归属他人，或 `sessionId` 落在保留命名空间（`u-*`、`debug-user`、`gw-*`，大小写不敏感，与 chat 同规则） |
| 413 | `context_budget_exceeded`：会话合计超过 `AGENT_SESSION_CONTEXT_MAX_TOTAL_CHARS`，body 附 `contentChars` / `totalChars` / `maxTotalChars` |
| 503 | `storage_unavailable`：存储不可用（可重试）。归属查询失败时同样返回 503，不放行 |

## 5. 数据模型

`V19__session_context.sql`：

```sql
CREATE TABLE IF NOT EXISTS session_context (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  session_id    VARCHAR(255) NOT NULL,
  context_key   VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  title         VARCHAR(255) DEFAULT NULL,
  content       MEDIUMTEXT   NOT NULL,
  content_chars INT          NOT NULL,
  user_id       VARCHAR(255) NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_context (session_id, context_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- `session_id` = 规范会话 id（`session_user.session_id`），Channel 与 A2A 会话同键。
- **排序规则是两个只会在真库上暴露的坑**：孤儿清理要与 `session_user` JOIN，表级排序规则必须跟随 `session_user` 的 `utf8mb4_unicode_ci`（F15），V10 之后新表的 `0900_ai_ci` 会让 JOIN 报错；`context_key` 单独用 `utf8mb4_bin`，使唯一键的大小写敏感语义与 Java 侧 `equals` 一致。
- `content_chars` 落库即算，预算裁决只对这一列求和，不读 `content`。
- 覆盖写不改变 `id`，条目顺序与在系统提示词中的位置稳定。
- 列类型用 `MEDIUMTEXT`：合计上限 30k 字符按 utf8mb4 最坏 4 字节计可达 120KB，超出 `TEXT` 的 64KB。

## 6. 组件

| 组件 | 职责 |
|---|---|
| `controller/SessionContextController` | 三个端点、参数清洗、错误映射。请求者身份与 chat 同口径规范化（`PathSafe.sanitize`），退化的身份不得落到占位归属上 |
| `service/SessionContextStore` | 唯一写入口。`upsert` 与 `remove` 各为单事务并在事务内持锁裁决归属；`ownerOf` 供查询端点裁决归属（读取失败即失败）；`findBySession`、`deleteBySession`、`deleteOrphans`。写入结果用 sealed 类型 `UpsertOutcome` / `RemoveOutcome`，不用异常做控制流；存储失败统一抛 `StorageException` |
| `service/SessionContextInjectionMiddleware` | `onSystemPrompt`：解析规范 sid → 读条目 → 追加参考资料段。`order()` 返回 `0`，是管线中最后一个转换器（F12） |
| `ThreadController` | `putHistoryMessages` 为响应补 `context`（`/history` 与会话详情共用）；`deleteThread` 级联删除 |
| `SessionCleanupService` | 在 `session_user` 清理之后执行孤儿清理，失败只告警、不中断其后的清理步骤 |
| `config/SessionContextConfig` | `maxTotalChars` |
| `HarnessAgentFactory` | 装配中间件。启动、OAF reload、会话级 agent 三条构建路径共用 |
| `SessionAccessGuard.isOwner` | 从 `ownedBy` 抽出的归属判定谓词，写事务持锁读到 owner 后复用同一份规则 |
| `SessionKeyNamespace` | 保留命名空间判定改为大小写不敏感（本次修复的存量缺陷，chat 等全部认领路径一并收口，见 §10） |
| 控制台 `src/lib/session-context.ts` | 纯逻辑：history 的 `context[]` → 会话顶部的 `context` 消息；欢迎态判定 |
| 控制台 `chat/ChatPane.tsx`、`chat/MessageItem.tsx` | 上下文卡片：标题行 + 更新时间 + 正文，默认展开、可折叠 |

## 7. 关键语义

**写入事务（`SessionContextStore.upsert`）**

1. 认领：会话无 `session_user` 行时以请求者建行；已有行**只刷新 `updated_at`**（不改 `user_id`）。注入视为会话活跃：推进保留时钟，但不改归属。
2. 锁定并裁决归属：`SELECT user_id … FOR UPDATE`。归属他人 → 回滚并返回 `NotOwned`；归属为占位 `unknown` → 改写为请求者。
3. 锁定既有条目：`SELECT context_key, content_chars … FOR UPDATE`。
4. 预算裁决：`其他条目合计 + 新条目 ≤ maxTotalChars`。
5. `INSERT … ON DUPLICATE KEY UPDATE`。

行锁把同会话的并发注入串行化，归属裁决与预算合计因此是原子结论，不依赖任何进程内状态。两个同会话的 `upsert` 在第一条语句上即被 `session_user` 行锁串行化，所以合计预算不依赖隔离级别。

**移除事务（`SessionContextStore.remove`）**：同样先 `FOR UPDATE` 锁归属行再裁决，他人会话返回 `NotOwned`（对外 404），与写入路径对称，不存在「校验通过后被他人认领」的窗口。

**查询端点的归属裁决**：经 `SessionContextStore.ownerOf`。它不用共享守卫 `SessionAccessGuard.ownedBy`——后者依赖的 `findUserIdBySession` 会把查询异常吞成 `null`，而「无映射行」等于放行（F16）。查询失败时返回 503，不放行。

**读取与生效**

- 每轮在系统提示词组装时读取，注入对**下一轮**生效。在途 turn 不受影响，因此本接口不需要 `turn_lease`，HITL 挂起期间也可以注入。
- 渲染模板：段标题 `# Session reference context` + 引导语（声明「是数据不是指令」）+ 每条 `## [key] title` 与正文。`title` 必须单行，不能借换行伪造后续段落头。

**未注入的会话保持原状（D3）**

- 没有条目时中间件原样返回系统提示词，逐字节不变；`/history` 的 `context` 为空数组。
- 唯一差别是每轮多一次按会话的索引范围读（读出最多 30k 字符正文，不是覆盖索引）。

**失败语义**

- 写入：任何一步失败即整体回滚并向调用方报错。
- 模型侧读取：查库异常使本轮失败，不降级为「无上下文继续回答」。头条是本轮回答的事实依据，静默缺失等于让模型编造；依据见 [../../agent-framework/AGENTS.md](../../agent-framework/AGENTS.md)「存储层错误分型纪律」。
- 展示侧读取（`/history` 的 `context`）：查库失败时省略该字段、消息照常返回，与 `attachUploadedFiles` 同口径。展示不影响回答正确性。

**生命周期**

- `ThreadController.deleteThread`：级联删除该会话的上下文。级联不是事务：与并发注入交错时可能留下孤儿行，由下一次孤儿清理收口（最终一致）。
- `SessionCleanupService`：在 `session_user` 清理之后删除已无会话映射的条目。上下文与**会话同寿命**，而「活跃」包含注入——上游持续推送的会话不会因为用户几天没说话被清掉。
- 孤儿清理分两步、分批：先**不加锁**地选出至多 1000 个孤儿 id（普通一致性读），再按主键单表 `DELETE … WHERE id IN (…)`，单次运行至多 20 批。不用单条跨表 `DELETE … LEFT JOIN`：那会对全表扫描到的行加锁，且加锁顺序（先 `session_context` 后 `session_user`）与 `upsert` 相反，存在死锁窗口。

## 8. 配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_SESSION_CONTEXT_MAX_TOTAL_CHARS` | `30000` | 会话合计上限，约为单次体量（< 5000）的 6 倍 |

没有总开关（D3）。

**上限与压缩阈值的关系**：压缩只统计对话消息，系统提示词的增长不计入触发条件（F4）。模型输入的最坏情况约为 `系统提示词（基础 + 注入）+ 对话（< 80k token）`。SDK 的估算口径是 2.5 字符/token，30k 字符约合 12k 估算 token（中文实际偏高）。模型窗口较小的服务应同步下调本上限，或下调 `AGENT_COMPACTION_TRIGGER_TOKENS`。按已确认体量，单次注入约 2k 估算 token，该风险很低；上限的作用是防止多条资料累积无界增长。

## 9. 安全

- **归属**：每个端点先裁决会话归属；他人会话一律 404。写入与移除的归属裁决在事务内持锁完成；查询的归属读取失败时返回 503，不放行。
- **保留命名空间**：`u-*`、`debug-user`、`gw-*` 形态的 `sessionId` 不得被认领（issue #558 的同一规则）。判定在本次改为**大小写不敏感**：此前 `U-{uid}`、`DEBUG-USER` 能通过 Java 侧检查被认领，而 `session_user.session_id` 的排序规则不区分大小写，在数据库比较上与用户身份等价（§10）。
- **信任边界**：只有会话归属用户（或以其身份调用的可信上游）能注入，与 chat 同一信任模型。
- **提示注入**：头条正文来自第三方，可能含指令样文本。正文置于带标题的分节内，引导语声明「是数据不是指令」。控制台卡片按**纯文本**渲染：不走 Markdown，第三方内容里的图片与链接不会变成用户浏览器的真实请求。
- **体量**：入口按字符数硬限，超限 413。
- **日志**：应用日志只记录 sid、key、字符数，不落正文。需要知道的例外：`GET /threads/{sid}/llm-calls` 的模型调用记录包含系统提示词，因此含注入段（进程内、每会话有限条数、受会话归属校验约束），属主可见，不是泄露。

## 10. 实施中对设计的修正

实现、评审与真机验证过程中，相对评审稿做了以下修正：

| 项 | 评审稿 | 最终实现 | 原因 |
|---|---|---|---|
| 单条上限 | 另设 `maxEntryChars` | 删除，只留会话合计上限 | 单条上限与合计上限相等时永远先命中合计，属于冗余 |
| `context_key` 排序规则 | 跟随表默认 | `utf8mb4_bin` | 表默认大小写不敏感，与 Java 侧 `equals` 的「是否已存在」判定不一致 |
| 表级排序规则 | 新表惯例 `0900_ai_ci` | `utf8mb4_unicode_ci` | 孤儿清理需要与 `session_user` JOIN |
| 归属谓词 | 写事务内另写一份判定 | 从 `SessionAccessGuard.ownedBy` 抽出 `isOwner` 复用 | 单一真相源 |
| 写入结果 | 抛异常表达拒绝 | sealed 类型 `UpsertOutcome` / `RemoveOutcome` | 不用异常做控制流，调用方按类型映射 HTTP 状态 |
| 卡片正文渲染 | 纯文本或 Markdown | 仅纯文本 | 第三方内容中的图片/链接不应触发用户浏览器请求 |
| DELETE 响应 | 含 `totalChars` | 只含 `sessionId` / `key` / `deleted` | 省一次额外查询，调用方不需要 |
| 注入段位置 | 「追加到尾部，跨会话共享可缓存前缀」 | 是 `onSystemPrompt` 管线的最后一个转换器，其后仍有 Harness 追加的静态块 | 真机 `llm-calls` 实测纠正（§3、§12）。要挪到绝对末尾只能在模型调用层重写请求，会绕开 agent 对系统提示词的统一认知，不做 |
| 注入是否算会话活跃 | 认领已有会话时不动 `updated_at`（避免会话列表重排） | 刷新 `updated_at`，不改归属 | 否则「上游每天推送、用户隔几天才读」的会话会在第 8 天被按无对话整会话清掉。代价是会话列表按最近活动排序时，被注入的会话会前移 |
| 孤儿清理 | 单条 `DELETE c FROM session_context c LEFT JOIN session_user u … WHERE u.session_id IS NULL` | 不加锁选 id + 按主键分批单表删除；清理步骤加容错 | 原写法全表加锁、与 `upsert` 加锁顺序相反（死锁窗口），且抛错会中断其后的清理步骤（评审阻断项） |
| 查询与移除的归属裁决 | 沿用共享守卫 `ownedBy` | 查询走 `ownerOf`（失败即 503），移除在事务内持锁裁决 | 共享守卫把查询异常吞成「无映射行」而放行（F16），不宜作为新增写路径的裁决依据 |
| 保留命名空间判定 | 区分大小写（存量） | 大小写不敏感 | 真机实测 `U-777777`、`DEBUG-USER` 可被认领，`u-777777` 被拒——issue #558 的绕过变体。在 `SessionKeyNamespace` 源头修正，chat 一并收口 |
| 请求者身份 | 原值入库 | 与 chat 同口径 `PathSafe.sanitize`；退化为占位身份时回落匿名 | 超长或含特殊字符的头会在 chat 与注入两条路径上产生不同的归属值，或直接导致写入失败 |
| 存储不可用 | 500 `internal_error` | 503 `storage_unavailable` | 区分可重试的存储故障与真正的内部错误 |
| 控制台 e2e 的跳过条件 | 「注入不是 201 就跳过」 | 先正向探测（查询接口对全新会话恒 200，接口不存在才 404），通过后严格断言 | 原条件会把 400/401/500 等真实故障静默报成跳过 |

## 11. 测试

**后端单元与集成**

| 测试 | 覆盖 |
|---|---|
| `SessionContextStoreTest` | 认领与新建、同名覆盖、他人会话拒绝且回滚不写入、占位行认领、保留会话 id（含大小写变体）拒绝、合计超限回滚、入参校验（含多行标题）、存储失败回滚并恢复 autocommit、恢复 autocommit 失败时的 suppressed 处理、认领只刷新保留时钟不改归属、归属与预算在 `FOR UPDATE` 的行上裁决、移除的三种结果、`ownerOf` 的加锁与失败语义、孤儿清理的不加锁选 id / 按主键删除 / 分批 / 批数上限 |
| `SessionContextStoreMySqlIT`（真实 MySQL，`HITL_MYSQL_IT=1` 门控） | 中文与 emoji 往返、大小写不同的 key 并存、注入刷新保留时钟且不改归属、占位认领、预算、24 线程并发注入不突破预算、移除的归属与结果、孤儿清理只删已无会话映射的条目、1205 条孤儿跨批清理且与并发注入同时进行不死锁、存储不可达时抛出而不是读成「无上下文」 |
| `SessionKeyNamespaceTest`、`SessionAccessGuardTest` | 保留命名空间大小写不敏感；`isOwner` 与 `ownedBy` 的判定一致 |
| `SessionContextInjectionMiddlewareTest` | `order()=0`；多条按注入序渲染；引导语；无条目时提示词不变；缺 ctx 或缺提示词时直通；无法解析会话时不查库；查库失败向上抛出 |
| `HarnessAgentFactoryMiddlewareAssemblyTest` | 用真实 HarnessAgent 构建，断言上下文注入是系统提示词管线的**最后一个**转换器；未注入 store 时不装配 |
| `SessionContextControllerTest` | 201/200、404、413、400（经真实 store 的校验）、503、匿名请求者、身份规范化、退化身份不落占位归属、会话 id 清洗、列表默认不带正文、归属读取失败时不放行、移除 |
| `ThreadControllerSessionContextTest`、`SessionCleanupServiceSessionContextTest` | history 平级 `context`、会话详情、展示侧读失败时省略字段、删除级联、孤儿清理在 `session_user` 清理之后、孤儿清理抛错不中断其后的清理 |

**前端**：`src/lib/session-context.test.mjs`（映射、缺字段容错、欢迎态判定）。

**E2E**

| 套件 | 用例 |
|---|---|
| `agent-framework/e2e` api-core（必需检查） | CX1 注入零模型调用、下一轮系统提示词带注入正文（mock LLM 的 `systemContent` 直证）、history 平级返回、全新会话被认领进列表；CX2 他人会话全端点 404 与保留会话 id；CX3 同名覆盖与移除；CX4 合计超限 413 与非法入参 400；CX5 HITL 挂起期间注入、恢复轮可见；CX6 删除会话级联清理 |
| `agent-framework/e2e` api-multi（必需检查） | R7：A 副本注入 → B 副本对话可见 → LB 回放返回 |
| 控制台 `console-ui-e2e.js` | 回放面板与调试面板两条路径渲染卡片、默认展开、可折叠、仅有上下文的会话仍是欢迎态 |
| 控制台 `m2-console-foundation.sh` | 经统一入口注入与回放 |

控制台两处用例的业务镜像取 Docker Hub 上的最新发布件。跳过条件是正向探测：`GET /threads/{全新 sid}/context` 在接口存在时恒 200，只有返回 404（接口不存在）才跳过；探测通过后注入、回放与卡片断言一律严格执行。

## 12. 验证记录（2026-11-25）

**单元与集成测试（本机）**

| 项 | 结果 |
|---|---|
| 后端受影响的 12 个测试类（含沙箱守卫的既有测试） | 143/143 |
| `SessionContextStoreMySqlIT`（真实 MySQL，独立临时库） | 11/11 |
| 前端 `npm test` | 108/108（104 既有 + 4 新增） |
| 前端 `tsc --noEmit` / `npm run lint` | 通过；lint 0 错误，改动文件的警告数改动前后一致 |

**真机部署验证（kind 集群）**

隔离方式：一次性工作空间里新建测试 Agent 服务，只把这一个服务的 Deployment 切到验证镜像；前端用独立的 Deployment 与 NodePort 承载验证镜像。共享的 `platform-frontend`、发布助手与其他服务未被触碰。

- **镜像**：前端走标准多阶段构建。框架镜像由宿主机离线 `mvn package` 的 jar 加标准 Dockerfile 的运行阶段组成——容器内的 Maven 直连中央仓库会在依赖预下载阶段停住。
- **Flyway**：先以平台默认镜像发布（schema 18），再切到验证镜像：`Current version … 18 → Migrating … to version "19 - session context" → Successfully applied 1 migration`；再次滚动时 `Current version … 19 … is up to date`。升级与已升级两条路径都验证到。
- **接口（经控制台同源链路，真实模型 mimo-v2.6-flash）36/36**：注入 201 且不产生对话消息；history、会话详情、会话列表均可见；真实模型准确回答注入头条中的三个事实（3.2 倍 / 42 公里 / 6 摄氏度），对资料里没有的问题如实回答没有；模型实际收到的系统提示词含注入段标题与正文，且位于 SDK 上下文块之后；同名覆盖只留最新；注入推进会话保留时钟且不改归属；合计超限 413；另一个用户的注入、读取、移除全部 404 且不改动原内容；保留命名空间的 6 种形态全部 404（含 `U-*`、`DEBUG-USER`、`GW-*`）；非法 key 400；移除 200、再次移除 404、移除后系统提示词不再含注入段。
- **控制台（真实浏览器）**：回放面板中只有上下文的会话保持欢迎态并渲染卡片，标题、时间、四条头条齐全，默认展开，点击折叠、再点展开，卡片内没有任何链接或图片元素；调试面板从历史会话切入后渲染同一张卡片，提问后真实模型的回答准确引用三个事实并标注了资料名，对话态下卡片位于消息流首位。

**E2E**：本机未运行 e2e 套件，由 PR 的必需检查承载——`agent-framework` 的 e2e-core（CX1~CX6）与 e2e-multi（R7），以及平台 e2e 中控制台的两处用例。结果以 PR 检查为准。

## 13. 风险与边界

| 项 | 说明 | 处置 |
|---|---|---|
| 注入段不在最终提示词的绝对末尾 | Harness 在 `onSystemPrompt` 管线之后还会追加静态块（`## Subagents` 等），注入段位于其前 | 不影响正确性（§12 真机问答已证）。跨会话的缓存前缀只覆盖到注入段为止；会话内多轮前缀不变 |
| 页面已打开时上游注入 | 卡片在下次加载历史（刷新、切换会话）时出现 | 不做实时推送。需要即时出现可加一个 SSE 通知帧 |
| token 预算非自调节 | 注入内容不触发压缩，只占窗口 | §8 上限 + 运维须知 |
| 请求体大小 | 入口没有比平台更严的体量限制，超大的 JSON 会先被完整读入再校验预算 | 平台既有形态（chat 相同）；本端点不消耗模型成本，暴露面更小。若要收紧应在平台层面统一做 |
| 删除会话的级联不是事务 | 与并发注入交错时可能留下孤儿行 | 由下一次孤儿清理收口（最终一致） |
| 子 agent | 子 agent 有独立会话键，不继承上下文 | 现状语义不变 |
| SDK 升级 | `onSystemPrompt` 为公开扩展点；位置依赖 `order()` 约定 | `HarnessAgentFactoryMiddlewareAssemblyTest` 钉住它是管线最后一个转换器 |
| 每轮成本 | 每轮一次按会话的索引范围读（读出最多 30k 字符正文），所有会话都付 | 与同轮既有的 `session_user` 查询同量级 |
| 老前端 / 调试页 / 监控页 | 读取 history 时忽略未知的 `context` 字段 | 无影响，不展示 |

## 顺带发现（不在本设计范围）

`POST /mcp/ui-context` 只校验 sessionId 格式，未做会话归属裁决（未调用 `SessionAccessGuard`），而写入内容会进入目标会话每轮的系统提示词。属于既有的越权写入面，建议另立 issue 处理。
