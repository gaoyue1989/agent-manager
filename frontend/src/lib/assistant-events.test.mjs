import assert from "node:assert/strict";
import { test } from "node:test";
import { completeToolCall, markAwaitingConfirm, markDeniedWithoutResult, settlePendingToolCalls, toolStatus } from "./assistant-events.ts";

const pending = () => [
  { role: "tool", content: "publish_service", toolCallId: "first", pending: true },
  { role: "tool", content: "publish_service", toolCallId: "second", pending: true },
];

test("runtime result fields complete only the matching tool call", () => {
  const result = completeToolCall(pending(), {
    toolCallId: "second", toolCallName: "publish_service", state: "SUCCESS",
  });
  assert.equal(result[0].pending, true);
  assert.equal(result[1].pending, false);
  assert.equal(result[1].state, "SUCCESS");
  assert.equal(toolStatus(result[1]), "success");
});

test("a missing or unknown call id does not complete another call", () => {
  const messages = pending();
  assert.deepEqual(completeToolCall(messages, { toolCallName: "publish_service" }), messages);
  assert.deepEqual(completeToolCall(messages, { toolCallId: "unknown" }), messages);
});

test("replayed results do not overwrite the first terminal state", () => {
  const once = completeToolCall(pending(), { toolCallId: "first", state: "SUCCESS" });
  const replayed = completeToolCall(once, { toolCallId: "first", state: "ERROR" });
  assert.equal(replayed[0].state, "SUCCESS");
});

test("RUNNING is not a terminal state and keeps the step in progress", () => {
  const result = completeToolCall(pending(), { toolCallId: "first", state: "RUNNING" });
  assert.equal(result[0].pending, true);
  assert.equal(toolStatus(result[0]), "running");
});

test("non-success terminal states are never rendered as success", () => {
  for (const state of ["ERROR", "DENIED", "INTERRUPTED"]) {
    const result = completeToolCall(pending(), { toolCallId: "first", state });
    assert.equal(toolStatus(result[0]), state.toLowerCase(), `${state} 应映射为 ${state.toLowerCase()}`);
    assert.notEqual(toolStatus(result[0]), "success");
  }
});

test("awaiting-confirm steps stay claimable by the later real result", () => {
  // permission_ask 暂停 → 批准恢复后仍能写入真实终态（清 pending 不阻断认领）
  const awaiting = markAwaitingConfirm(pending(), ["first"]);
  assert.equal(toolStatus(awaiting[0]), "awaiting");
  assert.equal(toolStatus(awaiting[1]), "running");
  const settled = completeToolCall(awaiting, { toolCallId: "first", state: "SUCCESS" });
  assert.equal(toolStatus(settled[0]), "success");
});

test("awaiting-confirm does not overwrite an existing terminal state", () => {
  const done = completeToolCall(pending(), { toolCallId: "first", state: "SUCCESS" });
  const after = markAwaitingConfirm(done, ["first", "second"]);
  assert.equal(toolStatus(after[0]), "success");
  assert.equal(toolStatus(after[1]), "awaiting");
});

test("stream close settles still-spinning steps without claiming success", () => {
  const settled = settlePendingToolCalls(pending());
  assert.equal(settled[0].pending, false);
  assert.equal(toolStatus(settled[0]), "unknown");
  assert.notEqual(toolStatus(settled[0]), "success");
});

test("rejected tools are marked denied instead of staying awaiting", () => {
  const awaiting = markAwaitingConfirm(pending(), ["first", "second"]);
  const after = markDeniedWithoutResult(awaiting, ["first"]);
  assert.equal(toolStatus(after[0]), "denied");
  assert.equal(toolStatus(after[1]), "awaiting");
});

test("historical replay without state renders neutral, never success", () => {
  const replayed = [{ role: "tool", content: "get_package", pending: false }];
  assert.equal(toolStatus(replayed[0]), "unknown");
});

test("official ASKING state from AgentState maps to awaiting, not unknown", () => {
  // 历史回放：tool_use.state 为官方 ToolCallState.ASKING（后端从 AgentState 读出，小写 asking）
  assert.equal(toolStatus({ role: "tool", content: "publish_service", pending: false, state: "ASKING" }), "awaiting");
  assert.equal(toolStatus({ role: "tool", content: "publish_service", pending: false, state: "asking" }), "awaiting");
});

test("official ToolResultState values render their real outcome", () => {
  // 后端现在把 tool_result.state 直接透传（小写），前端需按终态语义渲染
  assert.equal(toolStatus({ role: "tool", content: "t", pending: false, state: "success" }), "success");
  assert.equal(toolStatus({ role: "tool", content: "t", pending: false, state: "denied" }), "denied");
  assert.equal(toolStatus({ role: "tool", content: "t", pending: false, state: "interrupted" }), "interrupted");
  assert.equal(toolStatus({ role: "tool", content: "t", pending: false, state: "error" }), "error");
});

test("partial tool call with only ToolCallState allowed is not claimed as success", () => {
  // tool_use.state=allowed 表示已放行但尚无结果：不得显示成功（该轮可能仍在执行/已中断）
  const step = { role: "tool", content: "publish_service", pending: false, state: "allowed" };
  assert.equal(toolStatus(step), "unknown");
  assert.notEqual(toolStatus(step), "success");
});

test("awaiting steps are not overwritten into success by settle fallback", () => {
  const awaiting = [{ role: "tool", content: "publish_service", pending: false, state: "ASKING" }];
  const settled = settlePendingToolCalls(awaiting);
  assert.equal(toolStatus(settled[0]), "awaiting", "流结束兜底不应把待确认改成中性");
});

test("completeToolCall settles an awaiting step with the real result", () => {
  // 批准后真实结果到达：待确认 → 成功
  const awaiting = [{ role: "tool", content: "publish_service", toolCallId: "c1", pending: false, state: "ASKING" }];
  const done = completeToolCall(awaiting, { toolCallId: "c1", state: "SUCCESS" });
  assert.equal(toolStatus(done[0]), "success");
});

test("RUNNING snapshot keeps the step in progress instead of neutral", () => {
  // 历史快照时工具仍在执行（官方 ToolResultState.RUNNING，大小写不敏感）：显示执行中
  assert.equal(toolStatus({ role: "tool", content: "t", pending: false, state: "running" }), "running");
  assert.equal(toolStatus({ role: "tool", content: "t", pending: false, state: "RUNNING" }), "running");
});
