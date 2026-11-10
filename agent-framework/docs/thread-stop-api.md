# 单轮停止接口文档

> 端点：`POST /threads/{sessionId}/stop`
> 状态：**已实施**（2026-10-27）｜设计与机制见 [turn-stop-design.md](turn-stop-design.md)
> 相关实现：`ThreadController#stopThread` / `TurnStopService` / `TurnStopStore` / `TurnStopWatcher`

## 1. 概述

停止会话中**当前运行中**的 turn（一次用户消息触发的完整执行段）。采用**协作式中断**：

- 请求经共享存储 `turn_stop` 投递，由持有该 turn 的副本调用 SDK 按会话 `interrupt`；
- 可在**任意副本**发起，无会话粘性（满足业务面无状态多副本约束）；
- 只终止当前 turn，**不删除会话**，下一轮可直接继续对话；
- **不撤销**已执行工具的副作用，**不强杀**正在阻塞中的工具调用。

## 2. 请求

```http
POST /threads/{sessionId}/stop
X-User-Id: <网关注入的用户身份>
```

| 项 | 说明 |
|---|---|
| Path 参数 `sessionId` | 会话 id（如 `acme-test-agent:thread-1`），服务端经 `PathSafe.sanitize` 规范化 |
| 请求头 `X-User-Id` | 当前用户身份，用于归属校验；非归属用户统一 404，不泄露会话存在性 |
| 请求体 | **无**（空 body） |
| 幂等性 | 幂等：同会话重复点击覆盖同一条停止请求（`turn_stop` 主键为 `session_id`） |

## 3. 响应

| 状态码 | 响应体 | 含义 |
|---|---|---|
| `202` | `{"session_id":"...","reply_id":"...","state":"stopping"}` | **已受理**，尚未保证已中止；等待事件流 `cancelled` 终态帧 |
| `404` | `{"error":"nothing_to_stop","message":"..."}` | 会话没有运行中的 turn（无租约、无排队意图，或已终态） |
| `404` | `{"error":"thread_not_found","message":"..."}` | 归属不符（不区分「不存在」与「无权限」） |
| `409` | `{"error":"turn_waiting_confirm","message":"..."}` | HITL 挂起中——不是「运行中」，应改用 `confirm` 拒绝 |
| `409` | `{"error":"turn_not_ready","message":"...","retryable":true}` | 租约已获取但本轮尚未产出事件，目标暂不可判定，客户端短重试 |
| `503` | `{"error":"turn_stop_unavailable","message":"..."}` | 停止能力未启用（`AGENT_TURN_STOP_ENABLED=false`）或存储不可用（fail-closed，不谎称已停止） |

### 3.1 成功响应

```json
{
  "session_id": "acme-test-agent:thread-1",
  "reply_id": "reply-7f3a...",
  "state": "stopping"
}
```

`reply_id` 是本次目标 turn 的信封 id，可用于 `/threads/{sid}/subscribe?replyId=` 只跟踪该 turn。

### 3.2 错误响应

```json
{ "error": "nothing_to_stop", "message": "Session 'acme-test-agent:thread-1' has no running turn" }
```

```json
{ "error": "turn_not_ready", "message": "Turn is starting but has not produced any event yet; retry shortly", "retryable": true }
```

## 4. 受理后的行为

### 4.1 执行中的 turn

1. 属主副本按 `AGENT_TURN_STOP_POLL_MS`（默认 1s）轮询消费请求，调用 `HarnessAgent.interrupt(effectiveUserId, effectiveSessionId)`；
2. SDK 在下一个检查点中止（模型流 chunk 之间 / 每轮 reasoning、acting 前）；
3. 事件流依次输出：

```text
{"type":"cancelled","reason":"user_stopped"}
{"type":"AGENT_END"}
```

4. `cancelled` 属于终态类型，`/subscribe` 观察者据此收流；
5. `/threads/{sid}/status` **不新增状态位**，归为终态 `completed`；
6. `/threads/{sid}/history` 中对应 assistant 消息内容替换为「（本次回复已被用户停止）」，
   并附带 `"stopped": true`（只增字段，旧字段不变）。

### 4.2 排队中的 turn（尚未拿到租约）

- 直接向该连接发送 `cancelled` 帧后结束，不占事件 seq、不落事件库；
- 不会产生 `error` 帧，也不影响正在执行的 turn（同会话 `run(A) + queue(B)` 时，
  stop 优先停正在执行的 A；停 B 需等 B 真正开始后再调用）。

### 4.3 归属与落点

- 停止请求可发往任意副本，由 `turn_stop` 表跨副本传递，无需粘性路由；
- 会话保持可用，下一次 `POST /threads/chat` 正常发起新 turn（租约在 SDK 真正收尾后释放）。

## 5. 客户端调用建议

| 收到 | 建议动作 |
|---|---|
| `202` | 展示「正在停止…」，通过 `/subscribe` 等待 `cancelled`/`AGENT_END`；不要立即重发对话 |
| `404 nothing_to_stop` | 视为已完成/未在运行，走 `/status` 或 history 刷新；不要重试 |
| `404 thread_not_found` | 按会话不存在处理，不要重试 |
| `409 turn_waiting_confirm` | 改为 `POST /threads/{sid}/confirm-stream` 提交拒绝（`approved:false`） |
| `409 turn_not_ready` | 短延迟重试（建议 200–500ms，2–3 次） |
| `503 turn_stop_unavailable` | 能力关闭或存储故障；稍后重试，期间可仅断开 SSE（turn 会继续在后台执行） |

> 停止是**协作式**的：若工具正在长时间阻塞执行，`202` 之后可能延迟生效。
> 超过 `AGENT_TURN_STOP_GRACE_MS`（默认 15s）仍无终态时服务端仅打 WARN，
> 不会强杀工具、也不会提前释放租约。

## 6. 语义边界（必须向使用者声明）

1. **协作式中断，不是抢占**：检查点在模型流 chunk 之间与每轮推理/行动前；工具调用内部无检查点，
   正在执行的长工具/阻塞调用不会被强杀，工具返回后才生效。
2. **不撤销副作用**：已发出的外部请求、已写入的数据照旧生效；stop 不是回滚。
3. **不递归取消子任务**：`agent_spawn` 子 agent、Agent Protocol 远程任务不会被级联取消。
4. **不删除会话**：只终止当前 turn，会话历史与后续对话不受影响。
5. **current protocol 范围**：P0 仅 `/threads/chat` 通道；A2A `tasks/cancel`、AG-UI 面尚未复用本机制。

## 7. 配置开关

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_TURN_STOP_ENABLED` | `true` | 关 = 端点返回 503、属主不轮询（回退「只能断流」） |
| `AGENT_TURN_STOP_POLL_MS` | `1000` | 属主副本全局轮询周期（下限 100ms） |
| `AGENT_TURN_STOP_GRACE_MS` | `15000` | 协作式停止告警阈值（仅 WARN，不做破坏性收尾） |

## 8. 调用示例

### 8.1 curl

```bash
curl -X POST "http://localhost:8100/threads/acme-test-agent:thread-1/stop" \
  -H 'X-User-Id: alice'
```

```json
{"session_id":"acme-test-agent:thread-1","reply_id":"reply-7f3a","state":"stopping"}
```

### 8.2 JavaScript（发起停止并等待终态）

```js
const BASE = ''; // 直连网关时填写前缀，如 '/agent/demo'

async function stopTurn(sessionId, replyId, afterSeq) {
  const res = await fetch(`${BASE}/threads/${encodeURIComponent(sessionId)}/stop`, {
    method: 'POST',
    headers: { 'X-User-Id': currentUserId },
  });
  const body = await res.json();

  if (res.status === 409 && body.retryable) {
    // 本轮尚无事件，短重试
    return new Promise((r) => setTimeout(r, 300)).then(() => stopTurn(sessionId, replyId, afterSeq));
  }
  if (res.status !== 202) {
    throw new Error(`${res.status} ${body.error || 'stop_failed'}`);
  }

  // 等待 cancelled / AGENT_END：复用既有续传端点
  return subscribe(`${BASE}/threads/${encodeURIComponent(sessionId)}/subscribe?afterSeq=${afterSeq}`, {
    onEvent(evt) {
      if (evt.type === 'cancelled') showStopped();
      if (evt.type === 'AGENT_END') closeStream();
    },
  });
}
```

## 9. 关联端点

| 端点 | 关系 |
|---|---|
| `POST /threads/chat` | 发起 turn；`cancelled` 帧格式同其他 SSE 帧 |
| `GET /threads/{sid}/subscribe` | 断连续传，回放 `cancelled` 终态；可用 `replyId` 只跟目标 turn |
| `GET /threads/{sid}/status` | 停止后归为 `completed`（不新增状态位） |
| `GET /threads/{sid}/history` | 停止的回复带 `stopped: true` 与本地化提示 |
| `POST /threads/{sid}/confirm-stream` | HITL 挂起时的正确终止方式（拒绝确认） |
