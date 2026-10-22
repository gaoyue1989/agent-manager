import assert from "node:assert/strict";
import { test } from "node:test";
import { isValidAgentShortName } from "./agent-short.ts";

// 回归：short 名白名单曾写成 /^[a-z0-9](-[a-z0-9])*$/（只匹配单字符连段），
// 正常 short 名全部被判非法 → /agent/{short}/* 数据面反代 404。
test("多字符短名与连段名均合法（K8s DNS label 允许连续连字符）", () => {
  for (const name of ["a", "abc", "a1", "a--b", "release-agent", "cs-ticket-stress", "a-1-b2"]) {
    assert.equal(isValidAgentShortName(name), true, name);
  }
});

test("首尾连字符/大写/下划线/斜杠/空串均非法", () => {
  for (const name of ["", "-abc", "abc-", "Abc", "a_b", "a/b", "a.b", "a b"]) {
    assert.equal(isValidAgentShortName(name), false, name);
  }
});

test("超过 K8s DNS label 上限（63）非法", () => {
  assert.equal(isValidAgentShortName("a".repeat(63)), true);
  assert.equal(isValidAgentShortName("a".repeat(64)), false);
});
