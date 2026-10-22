import assert from "node:assert/strict";
import { test } from "node:test";
import { isReleaseAgentPath } from "./agent-route.ts";

test("发布助手根路径与子路径命中", () => {
  for (const p of ["/agent/release-agent", "/agent/release-agent/", "/agent/release-agent/threads/chat"]) {
    assert.equal(isReleaseAgentPath(p), true, p);
  }
});

test("release-agent* 正常业务 short 名不得被发布助手前缀吞掉", () => {
  for (const p of [
    "/agent/release-agentfoo",
    "/agent/release-agentfoo/threads",
    "/agent/release-agents",
    "/agent/release-agentx/threads/chat",
  ]) {
    assert.equal(isReleaseAgentPath(p), false, p);
  }
});

test("普通业务路径不命中", () => {
  for (const p of ["/agent/cs-ticket-stress/threads", "/agent/release/threads", "/api/v1/healthz"]) {
    assert.equal(isReleaseAgentPath(p), false, p);
  }
});
