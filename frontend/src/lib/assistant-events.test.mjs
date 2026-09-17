import assert from "node:assert/strict";
import { test } from "node:test";
import { completeToolCall } from "./assistant-events.ts";

const pending = () => [
  { role: "tool", content: "🔧 publish_service", toolCallId: "first", pending: true },
  { role: "tool", content: "🔧 publish_service", toolCallId: "second", pending: true },
];

test("runtime result fields complete only the matching tool call", () => {
  const result = completeToolCall(pending(), {
    toolCallId: "second", toolCallName: "publish_service", state: "SUCCESS",
  });
  assert.equal(result[0].pending, true);
  assert.equal(result[1].pending, false);
  assert.equal(result[1].content, "🔧 publish_service ✓");
});

test("a missing or unknown call id does not complete another call", () => {
  const messages = pending();
  assert.deepEqual(completeToolCall(messages, { toolCallName: "publish_service" }), messages);
  assert.deepEqual(completeToolCall(messages, { toolCallId: "unknown" }), messages);
});

test("replayed results do not append duplicate completion markers", () => {
  const event = { toolCallId: "first", state: "SUCCESS" };
  const once = completeToolCall(pending(), event);
  assert.deepEqual(completeToolCall(once, event), once);
});

test("non-success states are not displayed as successful", () => {
  const result = completeToolCall(pending(), { toolCallId: "first", state: "ERROR" });
  assert.equal(result[0].pending, false);
  assert.equal(result[0].content.includes("✓"), false);
});
