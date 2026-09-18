import assert from "node:assert/strict";
import { test } from "node:test";
import { createConfirmCard, hasEnv, maskInput, parseInput } from "./confirm-card.ts";

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
