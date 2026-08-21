# 问题排查与踩坑总结（approval-forms）

> 本文档沉淀在「审批 Demo + HITL 确认 + 单次流改造」开发/联调中遇到的**应用层问题**。
> 核心结论：多数"模型重试/异常"表象，根因都在**应用层**（恢复上下文重建、前端生命周期、MCP 传输），
> 修复后模型一次即成功。

---

## 1. HITL 恢复执行失败：`Parameter validation failed ... argument "content" is null`

**症状**（最隐蔽、最核心）：

- 表单确认后发送「提交申请」，HITL 确认卡出现，点击「批准」后**永远不成功**；
- 框架日志 `POST_ACTING submit_application state=SUCCESS`，但 **mock MCP 从未收到 `submit_application` 请求**；
- 恢复流事件里出现：
  `Error: Parameter validation failed for tool 'submit_application': Schema validation error: argument "content" is null`；
- LLM 如实报告"工具调用异常/参数校验错误"，随后**再次调用 submit_application → 又触发 HITL → 无限循环**。

**根因**（应用层，与模型无关）：

- `ToolExecutor.validateInput(toolCall.getContent(), tool.getParameters())` 用 **`ToolUseBlock.getContent()`（String）** 做 schema 校验（不是 `getInput()`）；
- 正常路径下 `content` 是 LLM 流式累积的原始 JSON 字符串（非空）；
- 但 HITL 恢复时，框架从 `confirm_context` 表重建 `ToolUseBlock` 使用三参构造器 `new ToolUseBlock(id, name, input)`——**`content` 固定为 null**；
- 恢复执行时 SDK 用这个 `content=null` 做校验 → `"argument content is null"` → 工具从未真正执行，mock 自然无请求。

**修复**（`service/ConfirmContextStore.java` `toToolCalls()`）：

```java
// 重建时填充 content = input 的 JSON 字符串，与 SDK 流式工具调用格式一致
var contentStr = m.get("content") instanceof String s
    ? s : (input == null ? null : MAPPER.writeValueAsString(input));
result.add(new ToolUseBlock(id, name, input, contentStr, null));
```

**排查方法**：给 `AgentRuntimeService` 事件转发临时加 `TOOL_RESULT_TEXT_DELTA` 日志，直接看恢复流里工具返回的错误文本；对比 mock 访问日志（后端代理/MCP trace）确认工具是否真的被执行。

---

## 2. Channel 流程 HITL 会话 key 双轨：批准后打不进频道上下文

**症状**：

- 恢复执行时 SDK 找不到 pending 工具调用，`getPendingToolUseIds()` 为空；
- 恢复后 LLM **全新推理**（messages=2，无历史），回复"当前对话中还没有进行中的申请单"；
- DB 出现两条 `agent_state`：频道行 `(userId=peer, sessionId=gw-hash)` 与恢复行 `(userId=vendorKey, sessionId=makeThreadId)` 各自为政。

**根因**：

- Channel 流程经 `ChatUiChannel` 网关路由，真实会话 key = `(userId=peer, sessionId=gw-hash)`；其中 `gw-hash = "gw-" + SHA-256(canonicalKey) 前6字节`，canonicalKey 对 ChatUiChannel（DmScope.MAIN、默认 agentId=main）恒为 `"chatui|x:agentId=main"` → 同进程所有 peer 共享 `gw-3f20f08c5499`；
- 恢复侧原先用 `sessionId=makeThreadId(raw)`、`userId=vendorKey` → 读的是另一行空的 agent_state；
- `AgentStartEvent.getSessionId()` **不可用**（ReActAgent 构造时第一参传 null），不能靠事件捕获网关 session。

**修复**（`AgentRuntimeService`）：

- `storeConfirmContext` 用确定性推导 `channelGatewaySessionId()`（SHA-256 canonicalKey）与 rawSessionId(peer) 一并存入 `ConfirmContext`；
- `buildResumeContext`：缓存含网关 session 时用 `(sessionId=gw-hash, userId=peer)`，否则回落 `makeThreadId+vendorKey`（A2A/普通流程）。

**排查方法**：查 `agent_state` 两行内容的 size/messages 数；用 `python3` 验证 `"gw-"+sha256("chatui|x:agentId=main").hexdigest()[:12]` 是否等于 DB 中的 `session_id`。

---

## 3. MCP App 卡片偶发不可交互：confirm_application 请求发不出去

**症状**：

- 「确认表单」点击偶发无效：postMessage（iframe→父页 `tools/call`）被检测到，但父页**没有发出** `/mcp/approval/tools/confirm_application` fetch；
- mock MCP 无记录，`proxy.py` 日志无该请求；卡片显示停在各字段文本（未进入"已确认"）。

**根因**（前端生命周期 bug）：

- `chat.js` 在 `AGENT_END` 里调用 `teardownAllAppHosts()`——LLM 回合一结束就卸载全部 MCP App 宿主；
- 若用户点击「确认表单」发生在 teardown 之后，iframe 的 `tools/call` postMessage **无人处理** → 前端 `McpAppHost.handleMessage` 已移除监听 → 卡片失活；
- 时序竞争：点得快就成功，点得慢就失败（flaky）。

**修复**（`ui/js/chat.js`）：

- `AGENT_END` **不再 teardown**；改到 `AGENT_START`（新回合开始）时 `teardownAllAppHosts()`；
- 新会话/切换会话原有的清理保留。卡片在两回合之间保持可交互等待用户决策。

---

## 4. mock MCP 501：Java MCP SDK streamableHttp 初始化 GET 探测

**症状**：

- 框架启动日志：`McpTransportException: Invalid SSE response. Status code: 501 Line: <!DOCTYPE HTML>`
- 工具能注册成功（POST 路径正常），但初始化/探测阶段出现 WARN/异常，且偶发连接异常。

**根因**：`BaseHTTPRequestHandler` **默认对未实现的方法返回 501**；mock 只实现了 `do_POST`，SDK streamableHttp 传输在初始化时发的 GET 探测被 501 拒绝。

**修复**（`approval_mcp.py`）：实现 `do_GET`，返回立即结束的 SSE 空帧：

```python
def do_GET(self):
    body = b'data: {"jsonrpc":"2.0","result":{},"id":null}\n\n'
    self.send_response(200)
    self.send_header("Content-Type", "text/event-stream")
    self.send_header("Cache-Control", "no-cache")
    self.send_header("Content-Length", str(len(body)))
    self.end_headers()
    self.wfile.write(body)
```

---

## 5. mock MCP 进程"神秘死亡" → ConnectException

**症状**：

- E2E 运行中段偶发 `McpTool - Error calling MCP tool '...': java.net.ConnectException`；
- 事后 `ss -tlnp | grep 8813` 无监听、进程消失、日志只有启动行无报错。

**根因（操作层面）**：mock、框架、proxy 独立进程，**重启不同步**导致：

- 框架 JVM 内 MCP HttpClient 持有到旧 mock 的 keep-alive 连接，mock 被杀/重启后成为死连接；
- 或 mock 在 E2E 中途被清理脚本误杀（`pkill -f` 匹配到同路径进程）。

**修复习惯**（重要）：

- **mock 与框架必须同步启停**：先起 mock → 确认存活 → 再起框架 → 最后起 proxy；
- 清理时用精确 PID（存 pid 文件），避免 `pkill -f agent-framework` 误伤 python 脚本（路径含同名字段）；
- 全程不要在 E2E 运行中重启任一组件。

---

## 6. LLM 把工具成功响应误读为"重试"：恢复消息太模糊

**症状**：

- `submit_application` 真实执行成功（mock 已置 `stage=submitted`），LLM 仍说"提交遇到内部错误，重试一次"并再次调用。

**根因**（应用层信号不足）：

- `buildResumeMsg` 原实现 `textContent("user confirmed")`——模型不知道"该工具已被人工批准且执行成功，不得重复调用"；
- 配合 §1 的 content=null bug，模型看到的是错误结果，自然重试。

**修复**（`AgentRuntimeService.buildResumeMsg`）：

```java
var text = allConfirmed
    ? "人工已批准上述工具调用，工具将立即执行。如果工具执行成功，请直接向用户汇报结果并结束流程，不得再次调用同一工具。"
    : "人工拒绝了上述工具调用，请不要执行，直接向用户说明。";
```

同时 mock 侧把 `submit_application` 做成**幂等**：`stage=submitted` 再次提交返回 `SUCCESS ... Stop now`（不报错、不触发重试）。

---

## 7. 模型选择差异：工具调用能力不在同一量级

| 模型 | 表现 | 结论 |
|------|------|------|
| mimo-v2.5 | 工具调用准确、时延 1.8s~240s 波动大 | 可用；需在 E2E 中放大 LLM 等待超时（`LLM_TIMEOUT=420s`） |
| nemotron-3-nano-30b-a3b:free | **完全不调用 MCP 工具**（3 次推理全部返回纯文本 JSON） | 不适用于本 Demo，勿切换 |

> 时延只影响 E2E 等待窗口，不影响功能正确性；工具调用正确性由 §1/§2/§3 的应用层修复保障。

---

## 8. 排查工具箱（速查）

| 目标 | 命令 |
|------|------|
| 框架健康 | `curl -s localhost:8100/health` |
| MCP 服务器列表 | `curl -s localhost:8100/mcp` |
| 工具列表 | `curl -s localhost:8100/tools` |
| MCP 单发调用 | `curl -s -X POST localhost:8813/mcp -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{...}}'` |
| 手动对话（单次流） | `curl -N -X POST localhost:8100/threads/{sid}/chat -H 'Content-Type: application/json' -d '{"message":"...","userId":"debug-user"}'` |
| 恢复执行结果原文 | framework 日志临时加 `TOOL_RESULT_TEXT_DELTA` 调试日志（完成后删除） |
| 确认上下文 | `mysql -h127.0.0.1 -P3307 agent_manager_test -e "SELECT * FROM confirm_context ORDER BY created_at DESC LIMIT 5"` |
| agent_state 双行检查 | 同库 `agent_state`（`userId:sessionId` 拼接主键，比对频道行与恢复行） |
| 端口占用 | `ss -tlnp \| grep -E '8100\|8813\|8913'` |

---

## 9. 留给未来的可改进点

1. `ToolExecutor.validateInput` 用 `content` 校验是 SDK 既定行为——避免在框架层重建 `ToolUseBlock` 时丢 `content` 字段（§1 已修），若升级 SDK 需回归验证。
2. `AGENT_END` 保留卡片（§3）后，若 LLM 在回合内多次调用带 UI 的工具，`AGENT_START` 的 teardown 会清掉上一张卡——多卡并存的场景需按 `tcId` 精确清理。
3. mock 重启与框架 MCP 连接的同步（§5）本质是运维约束，可考虑把 three 组件收敛为单一 `docker-compose` 服务编排，从根上消除不一致。