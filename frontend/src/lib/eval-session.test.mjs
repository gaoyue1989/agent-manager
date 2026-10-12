import assert from "node:assert/strict";
import { test } from "node:test";
import { evalCollectorMode, evalCollectorTarget } from "./eval-session.ts";

// issue #121 对策：采集模式判定早期基于一个「未设置时恒真」的判空表达式
// （EVAL_COLLECTOR_AGENT_URL !== ""），本地/未注入环境被误开。现语义钉死为
// 「显式开关 EVAL_COLLECTOR_MODE=1 + 目标必达」，本组守住两层契约。

test("EVAL_COLLECTOR_MODE=1 才算采集模式", () => {
  assert.equal(evalCollectorMode("1"), true);
});

test("未设置/空串/其它取值都不是采集模式（#121 判空语义）", () => {
  assert.equal(evalCollectorMode(undefined), false);
  assert.equal(evalCollectorMode(""), false);
  assert.equal(evalCollectorMode("0"), false);
  assert.equal(evalCollectorMode("true"), false);
});

test("开关开启且目标已设置时返回 collector 目标", () => {
  assert.equal(evalCollectorTarget("1", "http://collector:18203/e2e"), "http://collector:18203/e2e");
});

test("开关未开时不取目标（防止误注入）", () => {
  assert.equal(evalCollectorTarget(undefined, "http://collector:18203/e2e"), null);
  assert.equal(evalCollectorTarget("0", "http://collector:18203/e2e"), null);
});

test("开关已开但目标未设置时返回 null（proxy 侧告警并退回普通代理）", () => {
  assert.equal(evalCollectorTarget("1", undefined), null);
  assert.equal(evalCollectorTarget("1", ""), null);
});
