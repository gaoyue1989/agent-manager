# History 回放权威化与 HITL 确认恢复设计（AgentState 为消息级事实权威来源）

> 状态：已实施（2026-09-18）。本文记录设计决策、契约变更与已知限制；
> 评审发现的遗留问题见 §8，修复前请先读该节。

## 1. 背景与问题

原 `GET /threads/{sid}/history` 的消息级事实存在三处割裂：

1. `StateDataParser.extractContentText()` 只取 `text` 块，把 `tool_result` 块整个丢弃——
   工具的执行状态（success/error/denied）与结果文本在 history 里不可见，历史回放曾把
   一切工具显示为成功（`✓` 硬编码），误导使用者。
2. HITL 确认卡依赖 `confirm_context` 表（TTL 默认 30 分钟），超时后确认卡必然 404
   `confirm_context_not_found`——线上实际发生过（2026-09-18，会话 `webui-mu6fppj5-5iuu5n`）。
3. 曾尝试从 Redis 事件流（`TOOL_RESULT_END`）与 `tool_audit_log` 回填状态，两路都被否决：
   Redis 是 7 天 TTL 的高频通道不该承担持久读；`tool_audit_log` 不记录 confirm 恢复段
   （`ConfirmController` 不写审计），恰好缺最关键的数据。

## 2. 设计原则（已与产品确认）

1. **已完成轮次的消息级事实是持久状态，落在 DB**：工具调用与结果（含状态、输出）、
   人工确认的终态（批准→执行结果 / 拒绝→denied）合并为一条记录保存，语义与实时事件流
   一致，页面因此可保持一致；且能通过接口触发后续请求（确认卡仍可操作）。
2. **运行中轮次的高频刷新走 Redis 事件流**：`/subscribe?afterSeq=N` 承担断线续传与
   实时渲染，DB 不参与高频读。

### 官方依据（agentscope-java 2.0.3）

- `AgentState.getContext()` 由 SDK 自动持久化（本工程落 `agent_state` 表，MySQL）。
- `ToolUseBlock.state`：`PENDING / ASKING / ALLOWED / SUBMITTED / FINISHED`（ToolCallState）。
- `ToolResultBlock.state`：`SUCCESS / ERROR / INTERRUPTED / DENIED / RUNNING`，且带 `output`。
- HITL 恢复：`ReActAgent.askingToolCalls()` **从持久化 state 的最后一条 assistant 消息**
  读 ASKING 工具；恢复只需提交 `ConfirmResult`（按 id 匹配）。2.0.3 起回复关联 id 由
  `persistPendingRequestReplyId` 写入该消息 metadata（`agentscope_confirm_request_reply_id`），
  2.0.0 无此能力——这是升级 SDK 的动因。

线上实测佐证：`webui-mu5l3x49-tf03wm` 14 组 tool_use/tool_result 全配对（含批准执行的
`publish_service=success`）；`webui-mu6fppj5-5iuu5n` 的 `publish_service` 为 `asking`
且无 result（HITL 拦截语义天然成立，无需外部表推断）。

## 3. 数据源分工

| 数据 | 来源 | 寿命 | 用途 |
|------|------|------|------|
| 消息文本 / 工具状态 / 结果输出 | `agent_state`（MySQL，SDK 写入） | 会话保留期（默认 7 天） | history 回放（权威） |
| 确认卡挂起项（ASKING + replyId） | `agent_state` 最后一条 assistant 消息 | 同上 | `pendingConfirm` 重建、恢复执行 |
| 运行时身份（gw-hash sessionId / peer userId） | `agent_state` 根对象 `session_id`/`user_id` | 同上 | 恢复所需 RuntimeContext |
| token 级增量 / 轮内事件顺序 | Redis `sess:{sid}:events` | TTL 7 天 | `/subscribe` 续传、实时渲染 |
| `confirm_context` 表 | 平台写入 | TTL 30 分钟 | **兼容兜底**（老会话；state 无 ASKING 时回落） |
| 产出文件 | `file_asset` | 7 天 | history 文件卡片（不变） |

## 4. 核心变更

### 4.1 StateDataParser（解析层）

- `toRoleContentList(arr, maxChars)`：先扫全量 `tool_result` 建索引（按 tool_use id），
  再与 `tool_use` 配对合并为一条：`{id, name, input, state, output, output_truncated,
  output_full_length}`。结果状态优先于 tool_use 自身状态（有结果看结果，无结果看挂起态）。
- `extractAskingToolCalls(arr)`：只读**最后一条 assistant** 中 `state=asking` 的
  tool_use（与 SDK `askingToolCalls()` 同口径），带 metadata 里的 replyId。
- `extractRuntimeIdentity(stateData)`：BFS 取根对象 `session_id`/`user_id`。
- `extractOutputText(output)`：字符串直取；ContentBlock 数组拼接 text 块；无 text 块回落
  紧凑 JSON（图片等二进制不内联）。

### 4.2 AgentStateReader（新增服务）

`agent_state` 读取的唯一出口（五种 key 形态 LIKE 匹配：裸 peer、`peer:…`、`tenant__peer`
及后缀变体），供 ThreadController（history）与 AgentRuntimeService（确认恢复）共用，
避免两处拼 SQL 口径漂移。读失败 fail-soft（warn + 空结果）。

### 4.3 ThreadController（history）

- state 只读一次，消息解析与确认卡重建共用同一快照（同请求内无重复查库）。
- `pendingConfirmPayload`：优先 state 的 ASKING（`source=agent_state`，无 TTL），
  回落 `confirm_context`（`source=confirm_context`，兼容）。
- 截断上限注入：`AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS`（`HistoryConfig`，默认 8000，≤0 关闭）。

### 4.4 AgentRuntimeService（HITL 恢复）

- `resolveConfirmContext`：优先 `loadConfirmContextFromState`（ASKING 的 ToolUseBlock 按
  id+name+input 重建，state=ASKING；RuntimeContext 用 state 根对象的 gw-hash/peer），
  回落 `consumeConfirmContext`（原 CAS 路径）。
- `checkConfirmAvailable`：state 有 ASKING 即通过；否则查 `confirm_context`
  （保留原 404/409 语义）。并发双击由 Turn 租约串行（既有机制）。

### 4.5 SDK 2.0.0 → 2.0.3

唯一破坏性变更：`AgentRunner.stream()` → `streamEvents()`，事件类型
`Flux<Event>` → `Flux<AgentEvent>`（`HarnessAgentRunner` 已适配，A2A 通道与平台链路
事件词表就此统一）。其余为纯增量。全量回归 670 用例通过。

### 4.6 前端

- 工具步骤按真实状态渲染：`success/error/denied/interrupted/awaiting/running/unknown`，
  状态比较大小写归一（实时流大写 `SUCCESS`，历史回放小写 `success`——两链路既有事实）。
- 步骤可展开查看 output（截断时提示完整长度）。
- `confirm_context_not_found` 归类为 `expired` 卡片态（文案与现状有矛盾，见 §8-5）。

## 5. API 契约变更（GET /threads/{sid}/history）

```jsonc
{
  "pendingConfirm": {            // 无挂起时 null
    "reply_id": "…",             // state metadata 的关联 id；空则恢复时 SDK 兜底生成
    "source": "agent_state",     // 或 "confirm_context"（兜底路径）
    "tools": [{ "tool_call_id": "…", "name": "…", "input": {…}, "reply_id": "…" }]
  },
  "messages": [
    { "role": "assistant", "content": "…",
      "tool_calls": [
        { "id": "…", "name": "…", "input": {…},
          "state": "success",            // tool_result 优先；无结果时为 tool_use 状态（如 asking）
          "output": "…",                  // 截断后的结果文本
          "output_truncated": true,       // 可选
          "output_full_length": 50123 } ] // 可选
    }
  ]
}
```

## 6. HITL 恢复时序（新路径）

```
确认卡提交 → POST /threads/{sid}/confirm-stream
  ├─ checkConfirmAvailable：state 有 ASKING？通过 ：查 confirm_context（404/409 语义不变）
  ├─ Turn 租约 acquire（并发双击在此串行）
  ├─ resolveConfirmContext：
  │    state 路径：ASKING 工具按 id 重建 ToolUseBlock；RuntimeContext=(gw-hash, peer)【均出自 state】
  │    回落路径：consume confirm_context（CAS，30 分钟内）
  ├─ buildResumeMsg：ConfirmResult(boolean, ToolUseBlock) → METADATA_CONFIRM_RESULTS
  └─ agent.streamEvents(...)   // SDK: askingToolCalls() 校验 id → applyConfirmResults
         批准 → ALLOWED + 执行 + tool_result(success/error) 落 state
         拒绝 → tool_result(DENIED, "Permission denied by user") 落 state
```

## 7. 安全与截断

- output 截断：`AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS`（默认 8000），防止大输出撑爆响应。
- **已知暴露面（评审确认，见 §8-3）**：工具 output 可能回显敏感值（实测
  `get_service_status` 的 output 含 `LLM_API_KEY` 明文）。确认卡对 input 有 maskInput，
  output 无对称遮掩——实时流本就明文推送（既有），history 使其持久化。修复前请勿在
  敏感环境把 output 面板视为安全边界。

## 8. 已知限制与遗留问题（评审结论 → 修复状态 2026-09-18）

1. ~~【性能】confirm-stream 的 state 查询冗余~~ **已修复**：新增
   `AgentStateReader.loadAskingSnapshot(fullThreadId, peer)`，state 按候选顺序**只读一份**，
   asking/identity/replyId 从同一份 JSON 派生；`loadConfirmContextFromState` 与
   `checkConfirmAvailable` 复用该快照，消除逐项重查与必落空的 fullThreadId 查询。
2. ~~【逻辑】replyId 在恢复路径恒为空~~ **已修复**：replyId 随快照同源取出
   （含新 ASK 会话 SDK 持久化的非空值）；缺失时仍由 SDK 兜底生成。
3. ~~【安全】output 明文回显敏感值~~ **已修复**：`StateDataParser.maskSensitiveText`
   在截断**之前**应用（密钥键值对 / `tp-` token / Bearer 凭据；纯数字用量统计不遮），
   history 的 `tool_result.output` 不再携带明文密钥。注意：实时流
   `TOOL_RESULT_TEXT_DELTA` 仍为明文（既有通道，未在本轮改动范围）。
4. ~~【状态机】state 路径确认后 confirm_context 残留未消费~~ **已修复**：
   `resolveConfirmContext` 在 state 命中时顺手 consume 残留表行（失败仅 debug 日志），
   二次提交得到语义准确的 409。
5. ~~【文案矛盾】expired 文案失真~~ **已修复**：改为"该确认已失效：当前没有待确认的
   挂起操作（可能已被处理或会话已更新），本次未执行任何变更"，徽标改"已失效"。
6. ~~【行为矛盾】发送新消息清空历史工具步骤~~ **已修复**：`send()` 以
   `settlePendingToolCalls(prev)` 替代 `filter(role!=="tool")`，历史轮次（含
   state/output）保留在界面。
7. ~~【语义】RUNNING 渲染~~ **已修复**：`toolStatus` 对 `RUNNING`（大小写不敏感）返回
   `running`；`allowed/submitted` 无结果仍显示中性"无执行状态"（语义保守，可接受）。
8. ~~【工程】HistoryConfig 魔法数字 / AgentManagerProperties 残留空行~~ **已修复**：
   `defaults()` 引用 `StateDataParser.DEFAULT_TOOL_OUTPUT_MAX_CHARS`；空行清理。
9. 【既有·未修】多 fragment（多 item_index）state 的 `findMessagesArray` 只取第一个
   `context`——长会话历史只显示第一段。非本次引入；线上单会话 state 为单行（实测），
   影响有限，建议后续为 `loadFragments` 增加逐段解析合并。

## 9. 测试与部署记录（修复轮追加）

- 后端 `mvn test`：674 通过（新增 6：遮掩键值对/平台 token/Bearer、用量数字不遮、
  遮掩先于截断、正则边界）。
- 前端 `node:test`：25 通过（新增 RUNNING 语义）。
- 线上验证（2026-09-18，修复轮）：history 的 `get_service_status` output 遮掩生效
  （修复前实测命中 `tp-c9dgn7tl…` 明文 → 修复后 0 残留、遮掩标记 5 处）；
  confirm-stream 状态路径恢复正常（错误仅出现于提交已消费的旧 tool_call_id，属预期）；
  恢复后拒绝/校验失败/新 ASK 全部正确落 state，新 ASK 的 replyId 非空（P2-1 修复生效）。
- 部署（修复轮）：`agent-framework` registry `latest`（digest `b75f583…`）导入节点后
  rollout restart 生效——**注意**：`imagePullPolicy: IfNotPresent` + 同 tag 覆盖时，
  republish 不会触发滚动（spec 无变化），必须先 `ctr import` 新镜像再
  `kubectl rollout restart`；`platform-frontend` 为 `review-fix-20260918`。
  回退：前端 `history-authoritative-20260918`；后端回推旧 tag 后 restart。

- 后端 `mvn test`：670 通过（新增 StateDataParser 11 例：配对/截断/asking 判定/
  replyId/身份提取；SDK 升级适配 3 处）。
- 前端 `node:test`：24 通过（新增：ASKING 映射 awaiting、大小写归一、allowed 不冒充
  成功、兜底不改写待确认）。
- 线上验证（2026-09-18）：历史回放 state/output 全量正确；曾 404 的会话从 state 恢复
  执行成功；拒绝后落库 `denied + "Permission denied by user"`；新 ASK 的 replyId 非空。
- 部署：`agent-framework`（registry `latest`，经平台 republish）、`platform-frontend`
  （`history-authoritative-20260918`）。回退：前端 `state-fix-20260918`；后端需回推
  旧 tag 后 republish。

## 10. 多副本部署审计（2026-09-18）

### 结论：本轮变更不引入新的多副本风险

| 变更 | 审计结果 |
|------|---------|
| `AgentStateReader` | 无进程内状态、无缓存，每次实时 DB 读；连接 try-with-resources 归还 ✓ |
| `resolveConfirmContext` 的残留行清理 | `ConfirmContextStore.consume` 是 DB CAS（`WHERE consumed=0`），跨 Pod 原子 ✓ |
| `StateDataParser` 新函数 | 纯静态无状态；`Pattern` static final 线程安全 ✓ |
| 前端 | 仅 localStorage 存 sessionId（客户端本地），接口全走无状态 REST/SSE ✓ |

### SDK 2.0.0 → 2.0.3 顺带改善的既有缺陷

- **2.0.0 无任何 state 写入并发保护**（无 `persistAgentStateCas`/`slotVersions`/
  `stateConflictCount`）——多副本并发写同一会话是静默覆盖。
- **2.0.3 新增 CAS 乐观锁**，且 `MysqlAgentStateStore.supportsVersioning()` 返回 true
  （本工程的 store 实际启用版本校验）；表结构 `namespace_path/item_key/version` 两版一致，
  无需迁移。
- **限制（已核实）**：`HarnessAgent.Builder` 不透传 `conflictPolicy`（其 `build()` 只转发
  name/maxIters/hook/middleware/stateStore 等），`ReActAgent.conflictPolicy` 为
  `private final` 且无 setter ⇒ 当前无法配置 `FAIL`/`APPEND_MERGE`，实际语义仍是默认
  `OVERWRITE`（后写者胜，但会记 WARN `agent_state CAS conflict — OVERWRITE applied`
  并累加 `getStateConflictCount()`，相对 2.0.0 已有可观测性）。
  如需强一致需改用 `HarnessAgent.Builder.fromAgent(ReActAgent)` 手工复刻装配，成本与风险高。

### 修复：同步 confirm 补 Turn 租约（本轮）

`POST /threads/{sid}/confirm`（同步版）此前**无** `turn_lease` 保护，而
`confirm-stream`/`chat` 都有——多副本下同一确认打到两个 Pod 会恢复执行两次。
已补齐：抢租约失败返回 409 `turn_in_progress`（不恢复执行），`finally` 释放租约
（避免锁到 TTL 过期）。新增 2 个单测覆盖互斥与释放。

### replicas=2 实测（2026-09-18）

| 场景 | 结果 |
|------|------|
| 并发 2 个同步 confirm（同 tool_call_id，命中不同 Pod） | 一个 409 `turn_in_progress`、一个正常执行 ✓ 日志确认拒绝记录在另一 Pod |
| 执行后租约释放 | 再请求得业务性 404（非 `turn_in_progress`），锁未残留 ✓ |
| 跨 Pod state 一致性 | 连续 3 次 history 读取：消息 16 / 工具 14，完全一致 ✓ |
| 多副本对话 | `chat` 可用，回复正常 ✓ |
| 同会话并发 chat | 未抢到租约者先发 `waiting` 帧排队，随后正常执行 ✓ |

验证后已恢复 `replicas=1`（平台默认）。镜像 digest `6166705…`；回退：回推旧 tag 后
`ctr import` + `rollout restart`。
