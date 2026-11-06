import assert from "node:assert/strict";
import { test } from "node:test";
import { classifyConfirmFailure, confirmRemainingSeconds, createConfirmCard, expireDueConfirmCards, hasEnv, isConfirmExpired, maskInput, parseInput } from "./confirm-card.ts";

test("valid tool calls produce a pending card with sorted-id identity", () => {
  const card = createConfirmCard([
    { tool_call_id: "call-b", name: "delete_service", input: { k8sName: "a" } },
    { tool_call_id: "call-a", name: "publish_service", input: {} },
  ]);
  assert.equal(card.status, "pending");
  assert.equal(card.id, JSON.stringify(["call-a", "call-b"]));
  assert.equal(card.toolCalls.length, 2);
});

test("missing tool_call_id or name downgrades to unknown (submission forbidden)", () => {
  assert.equal(createConfirmCard([{ tool_call_id: "", name: "delete_service" }]).status, "unknown");
  assert.equal(createConfirmCard([{ tool_call_id: "c1", name: 42 }]).status, "unknown");
  assert.equal(createConfirmCard([]).status, "unknown");
  assert.equal(createConfirmCard("not-an-array").status, "unknown");
  assert.equal(createConfirmCard(null).status, "unknown");
});

test("duplicate tool_call_ids downgrade to unknown", () => {
  assert.equal(createConfirmCard([
    { tool_call_id: "dup", name: "a" },
    { tool_call_id: "dup", name: "b" },
  ]).status, "unknown");
});

test("env object values are masked by default at any depth", () => {
  const input = { env: { FOO: "bar" }, replicas: 2, nested: { env: { TOKEN: "x" } } };
  const masked = maskInput(input);
  assert.equal(masked.env.FOO, "••••••（已遮掩）");
  assert.equal(masked.replicas, 2);
  assert.equal(masked.nested.env.TOKEN, "••••••（已遮掩）");
});

test("sensitive-looking keys are masked without explicit env marker", () => {
  const masked = maskInput({ api_key: "k", LLM_PASSWORD: "p", plain: "v" });
  assert.equal(masked.api_key, "••••••（已遮掩）");
  assert.equal(masked.LLM_PASSWORD, "••••••（已遮掩）");
  assert.equal(masked.plain, "v");
});

test("reveal path (hide=false on subtree) is caller-controlled and arrays recurse", () => {
  const masked = maskInput(["a", { env: { K: "v" } }], false);
  assert.equal(masked[0], "a");
  assert.equal(masked[1].env.K, "••••••（已遮掩）");
  assert.deepEqual(maskInput({ k: "v" }, true), { k: "••••••（已遮掩）" });
});

test("hasEnv detects full-overwrite env at any depth for the warning banner", () => {
  assert.equal(hasEnv({ env: {} }), true);
  assert.equal(hasEnv({ a: { b: { env: { K: "v" } } } }), true);
  assert.equal(hasEnv({ envs: {} }), false);
  assert.equal(hasEnv("env"), false);
  assert.equal(hasEnv(null), false);
});

test("parseInput decodes stringified JSON tool input, keeps raw on failure", () => {
  assert.deepEqual(parseInput('{"a":1}'), { a: 1 });
  assert.equal(parseInput("not-json"), "not-json");
  assert.deepEqual(parseInput({ a: 1 }), { a: 1 });
});

test("confirm failure classification: only 404 expired is provably unexecuted", () => {
  // 后端 404：上下文过期，HITL 未批准 → 工具未执行，可安全重新发起
  assert.equal(classifyConfirmFailure(
    "confirm_context_not_found: session 'agentmanager-release-agent__webui-x' not found or confirm context expired"),
    "expired");
  // 409 已消费：可能已执行，不得断言未执行
  assert.equal(classifyConfirmFailure(
    "confirm_already_consumed: session 'webui-x' already processed"), "unknown");
  // 网络/流中断、未知错误、非字符串 → 无法排除已执行
  assert.equal(classifyConfirmFailure("HTTP 500"), "unknown");
  assert.equal(classifyConfirmFailure("turn_in_progress: session 'x' has an active turn"), "unknown");
  assert.equal(classifyConfirmFailure(undefined), "unknown");
  assert.equal(classifyConfirmFailure({ message: "confirm_context_not_found" }), "unknown");
});

test("D16: expiresAt carried on card, absent when backend omits it", () => {
  const card = createConfirmCard([{ tool_call_id: "c1", name: "t" }], "2026-11-05T10:00:00Z");
  assert.equal(card.expiresAt, "2026-11-05T10:00:00Z");
  assert.equal(card.status, "pending");
  // 后端未启用截止（permission_ask 无 expires_at / history 无该字段）→ 不带，现状行为
  assert.equal(createConfirmCard([{ tool_call_id: "c1", name: "t" }]).expiresAt, undefined);
  assert.equal(createConfirmCard([{ tool_call_id: "c1", name: "t" }], undefined).expiresAt, undefined);
  assert.equal(createConfirmCard([{ tool_call_id: "c1", name: "t" }], 42).expiresAt, undefined);
});

test("D16: isConfirmExpired judges by absolute deadline", () => {
  const now = 1000000000000;
  assert.equal(isConfirmExpired(new Date(now + 5000).toISOString(), now), false);
  assert.equal(isConfirmExpired(new Date(now).toISOString(), now), true);
  assert.equal(isConfirmExpired(new Date(now - 1).toISOString(), now), true);
  // 无截止/非法值 → 永不超时（现状行为）
  assert.equal(isConfirmExpired(undefined, now), false);
  assert.equal(isConfirmExpired("not-a-date", now), false);
});

test("D16: confirmRemainingSeconds counts down, null without deadline", () => {
  const now = 1000000000000;
  assert.equal(confirmRemainingSeconds(new Date(now + 6500).toISOString(), now), 7);
  assert.equal(confirmRemainingSeconds(new Date(now + 1000).toISOString(), now), 1);
  assert.equal(confirmRemainingSeconds(new Date(now - 5000).toISOString(), now), 0);
  assert.equal(confirmRemainingSeconds(undefined, now), null);
  assert.equal(confirmRemainingSeconds("garbage", now), null);
});

test("D16: expireDueConfirmCards flips only due pending cards", () => {
  const due = createConfirmCard([{ tool_call_id: "a", name: "t" }], new Date(1000).toISOString());
  const fresh = createConfirmCard([{ tool_call_id: "b", name: "t" }], new Date(9999999999999).toISOString());
  const noDeadline = createConfirmCard([{ tool_call_id: "c", name: "t" }]);
  const submitted = { ...createConfirmCard([{ tool_call_id: "d", name: "t" }], new Date(1000).toISOString()), status: "resolved" };
  const msgs = [
    { role: "assistant", content: "", confirm: due },
    { role: "assistant", content: "", confirm: fresh },
    { role: "assistant", content: "", confirm: noDeadline },
    { role: "assistant", content: "", confirm: submitted },
  ];
  const next = expireDueConfirmCards(msgs, 2000);
  assert.equal(next[0].confirm.status, "expired");
  assert.equal(next[1].confirm.status, "pending");
  assert.equal(next[2].confirm.status, "pending");
  assert.equal(next[3].confirm.status, "resolved");
  // 无到期卡（now=500 < 所有 expiresAt）→ 返回原引用（React 状态零扰动）
  assert.equal(expireDueConfirmCards(msgs, 500), msgs);
});

test("D16: confirm_expired (410 hard deadline) classifies as expired", () => {
  assert.equal(classifyConfirmFailure(
    "confirm_expired: session 'webui-x' pending confirm expired at 2026-11-05T10:00:00Z"), "expired");
});
