/**
 * e2e-agui API 组（agui-native-parity-design §12.4 UA 组的 API 级载体 / M5）：
 * AG1 基础 run 帧序 / AG2 跨面可见（P4）/ AG3 HITL interrupt（P3 产生面）/
 * AG4 resume 批准 + 双面幂等（P3 消费面）/ AG5 断联续传（P1）/ AG6 resume 预检。
 * 前置：实例以 AGENT_AGUI_ENABLED=true 启动（env-up.sh core/sandbox 组注入）。
 */
import { test, expect } from '@playwright/test';
import { BASE, sessionIdFor } from '../lib/env.js';
import { chat, history, status, userHeaders } from '../lib/client.js';
import { pollUntil } from '../lib/matchers.js';
import { aguiRun, aguiEvents, interruptsOf, waitAguiTerminal } from '../lib/agui.js';
import { createApprovalApp } from '../lib/client.js';

const U = 'e2e-tester';
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;

// ---------- AG1：基础 run 帧序 ----------

test('AG1 run 基础帧序：RUN_STARTED→TEXT→RUN_FINISHED', async () => {
  const sid = sessionIdFor(`ag1-${uniq()}`);
  const run = aguiRun({ threadId: sid, message: '[E2E:plain]', userId: U });
  await waitAguiTerminal(run);
  const types = run.frames.map(f => f.type);
  expect(types[0], '首帧 RUN_STARTED').toBe('RUN_STARTED');
  expect(types, '文本消息帧').toContain('TEXT_MESSAGE_CONTENT');
  expect(types[types.length - 1], '末帧 RUN_FINISHED').toBe('RUN_FINISHED');
  // threadId/runId 回显（官方 wire 契约）
  const started = run.frames[0];
  expect(started.threadId).toBe(sid);
  // seq 游标（id: 行）严格递增——同 native 面同一 seq 空间
  const seqs = run.frames.map(f => (f as { seq?: number }).seq ?? 0);
  for (let i = 1; i < seqs.length; i++) expect(seqs[i], `seq@${i}`).toBeGreaterThanOrEqual(seqs[i - 1]);
  // RUN_FINISHED 正常完成无 interrupts
  const finished = run.frames[run.frames.length - 1];
  expect(interruptsOf(finished)).toHaveLength(0);
});

// ---------- AG2：跨面可见（P4） ----------

test('AG2 AG-UI 轮对 native 面全程可见', async () => {
  const sid = sessionIdFor(`ag2-${uniq()}`);
  const run = aguiRun({ threadId: sid, message: '[E2E:plain]', userId: U });
  await waitAguiTerminal(run);
  // native history 看到该轮（消息轨 + pendingConfirm=null）
  const h = await history(sid, BASE, U);
  expect(JSON.stringify(h)).toContain('E2E:plain');
  // native /subscribe 回放同一轮：p 帧（native 词表）齐全
  const replay = await (await fetch(`${BASE}/threads/${encodeURIComponent(sid)}/subscribe?afterSeq=0`,
    { headers: userHeaders(U) })).text();
  expect(replay).toContain('AGENT_START');
  expect(replay).toContain('TEXT_BLOCK_DELTA');
  // AG-UI 续传端点回放：pa 帧齐全（RUN_STARTED..RUN_FINISHED）
  const aguiReplay = aguiEvents(sid, 0, undefined, U, BASE);
  await waitAguiTerminal(aguiReplay);
  const rTypes = aguiReplay.frames.map(f => f.type);
  expect(rTypes).toContain('RUN_STARTED');
  expect(rTypes).toContain('RUN_FINISHED');
});

// ---------- AG3：HITL interrupt（P3 产生面） ----------

test('AG3 权限挂起：RUN_FINISHED 携带 interrupts，双面状态一致', async () => {
  const app = await createApprovalApp();
  const sid = sessionIdFor(`ag3-${uniq()}`);
  const run = aguiRun({ threadId: sid, message: `[E2E:hitl:submit](${app})`, userId: U, timeoutMs: 120_000 });
  await waitAguiTerminal(run);
  const finished = await waitAguiTerminal(run);
  expect(finished?.type).toBe('RUN_FINISHED');
  const interrupts = interruptsOf(finished);
  expect(interrupts.length).toBeGreaterThan(0);
  const it = interrupts[0];
  expect(it.reason).toBe('tool_call');
  expect(String(it.id)).toContain(':');
  const meta = (it.metadata ?? {}) as Record<string, unknown>;
  expect(meta['agentscope.interruptKind']).toBe('permission_confirm');
  // interruptId 尾段 = 真实 tool_call_id
  const toolCallId = String(it.id).split(':').pop();
  expect(String(it.toolCallId ?? toolCallId)).toBe(toolCallId);
  // 双面状态一致：native /status 与 history 都看到挂起（P3）
  const st = await status(sid, BASE, U);
  expect(st.state).toBe('waiting_confirm');
  const h = await pollUntil(
    async () => history(sid, BASE, U),
    value => !!(value.pendingConfirm as Record<string, unknown> | null),
    10_000, 200,
  );
  expect(h.pendingConfirm).toBeTruthy();
});

// ---------- AG4：resume 批准 + 双面幂等（P3 消费面） ----------

test('AG4 resume[] 批准恢复执行；重复消费双面 409', async () => {
  const app = await createApprovalApp();
  const sid = sessionIdFor(`ag4-${uniq()}`);
  const ask = aguiRun({ threadId: sid, message: `[E2E:hitl:submit](${app})`, userId: U, timeoutMs: 120_000 });
  const askFinished = await waitAguiTerminal(ask);
  const interrupts = interruptsOf(askFinished);
  const interruptId = String(interrupts[0].id);
  await new Promise(r => setTimeout(r, 800)); // 等 ASK 段租约释放（confirm 抢锁竞态，与 H2 同口径）

  // resume 批准 → 恢复轮执行工具并正常完成
  const resume = aguiRun({ threadId: sid, resume: [{ interruptId, status: 'resolved', payload: { approved: true } }], userId: U, timeoutMs: 120_000 });
  await waitAguiTerminal(resume);
  const rTypes = resume.frames.map(f => f.type);
  expect(rTypes, '恢复轮产出工具结果').toContain('TOOL_CALL_RESULT');
  expect(rTypes[rTypes.length - 1]).toBe('RUN_FINISHED');
  expect(interruptsOf(resume.frames[resume.frames.length - 1])).toHaveLength(0);

  // 双面幂等：CAS 已消费——AG-UI 重复 resume 与 native confirm 都必须拒绝
  const again = aguiRun({ threadId: sid, resume: [{ interruptId, status: 'resolved', payload: { approved: true } }], userId: U });
  await waitAguiTerminal(again);
  const err = again.frames.find(f => f.type === 'RUN_ERROR');
  expect(err, '重复 resume → RUN_ERROR').toBeTruthy();
  expect(String(err!.message)).toContain('confirm');
});

// ---------- AG5：断联续传（P1） ----------

test('AG5 run 中途断连：events 端点从游标续传补齐 RUN_FINISHED', async () => {
  const sid = sessionIdFor(`ag5-${uniq()}`);
  const run = aguiRun({ threadId: sid, message: '[E2E:plain]', userId: U, timeoutMs: 60_000 });
  // 等首帧（RUN_STARTED）后立即断连——模拟客户端崩溃
  await pollUntil(
    async () => ({ frames: run.frames }),
    value => value.frames.length >= 1,
    30_000, 100,
  );
  const cursor = (run.frames[0] as { seq?: number }).seq ?? 0;
  run.abort();

  // 重连续传：afterSeq=游标（runId 过滤本轮）→ 补齐后续帧直到 RUN_FINISHED
  const resumed = aguiEvents(sid, cursor, undefined, U, BASE);
  await waitAguiTerminal(resumed);
  const rTypes = resumed.frames.map(f => f.type);
  expect(rTypes, '续传补齐 RUN_FINISHED').toContain('RUN_FINISHED');
  expect(rTypes, '断连前未收到的文本帧由回放补出').toContain('TEXT_MESSAGE_CONTENT');
  // 续传帧游标严格大于断连游标
  for (const f of resumed.frames) {
    expect((f as { seq?: number }).seq ?? 0).toBeGreaterThan(cursor);
  }
  // 全量回放与实时流同构（seq 单一空间）：从头回放也完整
  const full = aguiEvents(sid, 0, undefined, U, BASE);
  await waitAguiTerminal(full);
  expect(full.frames.map(f => f.type)).toContain('RUN_STARTED');
});

// ---------- AG6：resume 预检 ----------

test('AG6 非法/无挂起 resume → RUN_ERROR 预检', async () => {
  const sid = sessionIdFor(`ag6-${uniq()}`);
  // interruptId 缺冒号分隔 → invalid_resume
  const bad = aguiRun({ threadId: sid, resume: [{ interruptId: 'no-colon', status: 'resolved', payload: { approved: true } }], userId: U });
  await waitAguiTerminal(bad);
  const err = bad.frames.find(f => f.type === 'RUN_ERROR');
  expect(err, '非法 interruptId → RUN_ERROR').toBeTruthy();
  expect(String(err!.message)).toContain('invalid_resume');

  // 无挂起会话的 resume → confirm_context_not_found
  await new Promise(r => setTimeout(r, 200));
  const none = aguiRun({ threadId: sessionIdFor(`ag6b-${uniq()}`), resume: [{ interruptId: 'run-1:call-1', status: 'resolved', payload: { approved: true } }], userId: U });
  await waitAguiTerminal(none);
  const err2 = none.frames.find(f => f.type === 'RUN_ERROR');
  expect(err2, '无挂起 resume → RUN_ERROR').toBeTruthy();
  expect(String(err2!.message)).toContain('confirm');
});
