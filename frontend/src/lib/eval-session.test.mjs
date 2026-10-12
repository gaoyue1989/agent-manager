import assert from "node:assert/strict";
import { test } from "node:test";
import { evalCollectorMode, evalSessionHeader } from "./eval-session.ts";

// issue #121 对策：proxy 的采集判定原写作 `process.env.EVAL_COLLECTOR_AGENT_URL !== ""`——
// 该变量在仓库中从未被任何部署/manifest 设置（实际生效的是 AGENT_INTERNAL_URL），
// 未设置时 undefined !== "" 为 true，采集注入在本地/未注入环境被误开。
// 判定改为基于实际生效的 AGENT_INTERNAL_URL 且显式排除未设置/空串。
// 注：当前语义是「AGENT_INTERNAL_URL 非空即注入」，不区分是否真指向 collector
// （collector 反代口带 /{ns} 子路径、真实业务服务不带）——该歧义登记在 #121，
// 待运维侧确认是否按子路径形态收紧后再收紧此断言。

test("AGENT_INTERNAL_URL 显式设置即视为采集目标", () => {
  assert.equal(evalCollectorMode("http://collector:18203/e2e"), true);
});

test("AGENT_INTERNAL_URL 未设置/空串不是采集模式（#121 判空语义）", () => {
  assert.equal(evalCollectorMode(undefined), false);
  assert.equal(evalCollectorMode(""), false);
});

test("采集模式且 sid 存在时注入", () => {
  assert.equal(evalSessionHeader("http://collector:18203/e2e", "webui-abc"), "webui-abc");
});

test("AGENT_INTERNAL_URL 未设置/空串不注入（#121 判空语义）", () => {
  assert.equal(evalSessionHeader(undefined, "webui-abc"), null);
  assert.equal(evalSessionHeader("", "webui-abc"), null);
});

test("采集模式但 sid 缺失时不注入（#121 现状：cookie 从未被任何一方写入）", () => {
  assert.equal(evalSessionHeader("http://collector:18203/e2e", undefined), null);
  assert.equal(evalSessionHeader("http://collector:18203/e2e", ""), null);
});
