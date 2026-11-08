// U6（sandbox-pool-opensandbox-design §9.1）：配额错误卡与池水位纯逻辑单测（node:test，
// 模式同 confirm-card.test.mjs；渲染层 QuotaErrorCard/页面消费本模块的判定函数）。
// 覆盖：两入口（409 body / SSE error code 帧）、两态文案与停止按钮资格（评审 d5）、
// 停止端点 URL、来源分流（precheck=输入保留+气泡回滚 / exec=保留气泡）、
// 容量表单校验链、水位条黄/红/未知/正常档。
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  bindingAdvice, capacityFromForm, confirmHoldText, deriveBindingState, isQuotaError, makeQuotaError,
  parseQuota409, poolLevel, quotaFromSseError, quotaHeadline, stopThreadUrl,
  validateCapacityForm, validatePoolName,
} from "./sandbox-quota.ts";

// ---- 入口一：chat 预检 409 响应体（§4.3）----

test("409 body with user_quota_exhausted parses to payload (precheck entry)", () => {
  const body = {
    code: "user_quota_exhausted",
    message: "沙箱配额已满（5/5）且均不可复用",
    quota: 5,
    bindings: [
      { session_id: "s1", expires_at: "2026-11-07T15:00:00Z", state: "executing" },
      { session_id: "s2", expires_at: "2026-11-07T15:10:00Z", state: "confirm_holding",
        confirm_hold_expires_at: "2026-11-07T15:08:00Z" },
    ],
  };
  const p = parseQuota409(409, body);
  assert.ok(p);
  assert.equal(p.quota, 5);
  assert.equal(p.bindings.length, 2);
  // 来源分流：precheck = 未建流 → ChatPane 回滚乐观气泡并保留输入
  const err = makeQuotaError(p, "precheck");
  assert.ok(isQuotaError(err));
  assert.equal(err.source, "precheck");
});

test("non-409 or non-quota body falls back to generic error (null)", () => {
  assert.equal(parseQuota409(500, { code: "user_quota_exhausted" }), null);
  assert.equal(parseQuota409(409, { code: "turn_waiting_confirm" }), null);
  assert.equal(parseQuota409(409, null), null);
  assert.equal(parseQuota409(409, "garbage"), null);
});

// ---- 入口二：SSE error 帧 code=user_quota_exhausted（§4.6，A2A/竞态兜底路径同样到达）----

test("SSE error frame with code=user_quota_exhausted parses (exec entry)", () => {
  const ev = {
    code: "user_quota_exhausted",
    quota: 2,
    bindings: [{ session_id: "a1", expires_at: "2026-11-07T15:00:00Z", turn_running: true }],
  };
  const p = quotaFromSseError(ev);
  assert.ok(p);
  assert.equal(p.quota, 2);
  const err = makeQuotaError(p, "exec");
  assert.equal(err.source, "exec"); // exec = 已建流 → 保留气泡，不回滚输入
});

test("other SSE error frames are not quota errors", () => {
  assert.equal(quotaFromSseError({ code: "internal", error: "boom" }), null);
  assert.equal(quotaFromSseError(null), null);
  assert.equal(quotaFromSseError("quota exhausted"), null);
});

// ---- 两态文案与停止按钮资格（评审 d5）----

test("binding state derivation: state field wins, turn_running/unknown fall back to executing", () => {
  assert.equal(deriveBindingState({ state: "confirm_holding" }), "confirm_holding");
  assert.equal(deriveBindingState({ state: "executing" }), "executing");
  assert.equal(deriveBindingState({ turn_running: true }), "executing");
  assert.equal(deriveBindingState({}), "executing");
  assert.equal(deriveBindingState(null), "executing");
});

test("executing binding: stop offered, grace-after-stop disclosure (d5)", () => {
  const adv = bindingAdvice({ state: "executing" });
  assert.equal(adv.canStop, true);
  assert.equal(adv.label, "执行中");
  assert.match(adv.advice, /停止该会话/);
  assert.match(adv.advice, /宽限/); // 明示「grace 后可被复用」，不得承诺即时释放
});

test("confirm_holding binding: no stop button, guide to reject/hold-window (d5)", () => {
  const adv = bindingAdvice({ state: "confirm_holding" });
  assert.equal(adv.canStop, false); // 后端 WAITING_CONFIRM 停止必 409 —— 不得提供停止按钮
  assert.equal(adv.label, "确认等待中");
  assert.match(adv.advice, /保留窗|拒绝该确认/);
});

test("confirm_holding hold window deadline surfaces (confirm_hold_expires_at, §4.3/§6.3)", () => {
  // 预检 409 携带保留窗截止（payload409 透出）→ 展示「保留窗至 …」
  assert.equal(confirmHoldText({ state: "confirm_holding", confirm_hold_expires_at: "2026-11-07T15:10:00Z" }),
    "保留窗至 2026-11-07 15:10");
  // SSE 帧无该字段 / 缺失 / 空载 → 空串不渲染
  assert.equal(confirmHoldText({ state: "confirm_holding" }), "");
  assert.equal(confirmHoldText({ turn_running: true }), "");
  assert.equal(confirmHoldText(null), "");
});

test("stop endpoint URL reuses POST /threads/{sid}/stop", () => {
  assert.equal(stopThreadUrl("/agent/demo", "s 1"), "/agent/demo/threads/s%201/stop");
});

// ---- 标题/载荷兜底 ----

test("headline uses quota and bindings count; missing quota degrades to message", () => {
  assert.equal(
    quotaHeadline({ code: "user_quota_exhausted", quota: 5, bindings: [{}, {}, {}, {}, {}] }),
    "沙箱配额已满（5/5）且均不可复用");
  assert.equal(
    quotaHeadline({ code: "user_quota_exhausted", quota: 0, message: "配额异常" }),
    "配额异常");
  assert.equal(quotaHeadline(null), "沙箱配额已满且均不可复用");
});

test("malformed bindings degrade to empty list with quota fallback", () => {
  const p = parseQuota409(409, { code: "user_quota_exhausted", quota: 3, bindings: "oops" });
  assert.ok(p);
  assert.deepEqual(p.bindings, []);
  assert.equal(p.quota, 3);
});

// ---- 池容量表单校验（§6.1，与后端同链）----

test("capacity form: valid chain passes and converts to numbers", () => {
  const f = { poolMin: "1", bufferMin: "2", bufferMax: "3", poolMax: "5" };
  assert.equal(validateCapacityForm(f), null);
  assert.deepEqual(capacityFromForm(f), { poolMin: 1, bufferMin: 2, bufferMax: 3, poolMax: 5 });
});

test("capacity form: chain violations and non-numeric input rejected", () => {
  assert.match(validateCapacityForm({ poolMin: "2", bufferMin: "1", bufferMax: "3", poolMax: "5" }) ?? "", /poolMin/);
  assert.match(validateCapacityForm({ poolMin: "1", bufferMin: "3", bufferMax: "2", poolMax: "5" }) ?? "", /bufferMax/);
  assert.match(validateCapacityForm({ poolMin: "1", bufferMin: "2", bufferMax: "3", poolMax: "2" }) ?? "", /poolMax/);
  assert.match(validateCapacityForm({ poolMin: "-1", bufferMin: "1", bufferMax: "2", poolMax: "3" }) ?? "", /非负整数/);
  assert.match(validateCapacityForm({ poolMin: "1", bufferMin: "x", bufferMax: "2", poolMax: "3" }) ?? "", /非负整数/);
  assert.match(validateCapacityForm({ poolMin: "", bufferMin: "1", bufferMax: "2", poolMax: "3" }) ?? "", /非负整数/);
});

test("pool name precheck mirrors DNS-1123 label rule", () => {
  assert.equal(validatePoolName("oaf-pool-v1"), null);
  assert.notEqual(validatePoolName("Bad_Name"), null);
  assert.notEqual(validatePoolName("-lead"), null);
  assert.notEqual(validatePoolName("a".repeat(64)), null);
});

// ---- 水位条黄红规则（§6.1）----

test("pool level: red when allocated reaches poolMax (priority over yellow)", () => {
  const pool = { capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3 }, status: { available: 0, allocated: 3 } };
  assert.equal(poolLevel(pool), "red");
});

test("pool level: yellow when available < bufferMin (预热不足)", () => {
  const pool = { capacitySpec: { bufferMin: 2, bufferMax: 3, poolMin: 1, poolMax: 5 }, status: { available: 1, allocated: 1 } };
  assert.equal(poolLevel(pool), "yellow");
});

test("pool level: normal when buffer band satisfied", () => {
  const pool = { capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3 }, status: { available: 1, allocated: 1 } };
  assert.equal(poolLevel(pool), "normal");
});

test("pool level: unknown when status not reconciled (d19: status may be absent)", () => {
  assert.equal(poolLevel({ capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3 }, status: null }), "unknown");
  assert.equal(poolLevel({ capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3 } }), "unknown");
  assert.equal(
    poolLevel({ capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3 }, status: { allocated: 1 } }),
    "unknown");
});
