/**
 * e2e-sandbox-lifecycle API 组（sandbox-pool-opensandbox-design §9.3 L1~L20，PR-D）：
 * 沙箱生命周期开启（SANDBOX_LIFECYCLE_ENABLED=true + 加速参数 QUOTA=2 / TTL=1min /
 * RENEW_INTERVAL=3s / CONFIRM_HOLD=1min / POOL_REF=pool-e2e / grace 下限 6s / 注册表超龄 2min /
 * turn_lease 硬寿命 1min）+ mock OpenSandbox（池/renew-expiration/故障注入，§9.2）。
 *
 * 断言面 = agent 行为（turn 终态/结构化帧）+ mock /stats（creates[带请求体]/renews）
 * + /debug/sandbox-bindings（跨副本绑定表/注册表健康/事件流）+ 直连 MySQL（L13/L20 测试钩子，
 * PENDING 不变量与死绑定的注入面）+ lifecycle-off 基线对照实例（L8，E2E_LIFECYCLE_OFF_BASE）。
 *
 * 分组顺序执行（共享 mock 状态）：A 借出与配额（L1/L2/L6/L8/L13/L20）→ B TTL 与续期
 * （L4/L5/L15/L16/L17）。C/D/E（L3/L9/L10/L11/L12/L14/L18/L19/L21）见
 * api-sandbox-lifecycle-reuse.spec.ts——独立实例与 mock 沙箱，与本文件分属两个 CI job 并行。
 */
import { test, expect } from '@playwright/test';
import { BASE, sessionIdFor } from '../lib/env.js';
import { chat } from '../lib/client.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';
import { SANDBOX_MOCK, U, uniq, sleep, mockStats, mockReset, mockFault, createPool, destroySandbox, lifecycleDebug, bindingOf, renewCount, withSql, rawChat, collectQuotaFrame } from '../lib/sandbox-lifecycle.js';

const OFF_BASE = process.env.E2E_LIFECYCLE_OFF_BASE ?? 'http://127.0.0.1:8101';

test.beforeAll(async () => {
  await mockReset();
  // 建池（L6 断言 create 载荷 poolRef 透传 + 池耗尽面共用）；幂等：409 视为已存在
  const res = await createPool('pool-e2e');
  expect([201, 409]).toContain(res.status);
});

// ==================== A 组：借出与配额 ====================

test('L6 create 透传 extensions.poolRef + L7 metadata.session_id 注入', async () => {
  const sid = sessionIdFor(`l6-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: U(), sessionId: sid, timeoutMs: 120_000 });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const creates = (await mockStats()).creates;
  const mine = creates.find(c => c.body?.metadata?.session_id === sid);
  expect(mine, 'create 请求体未记录（§9.2 /stats.creates 扩展）').toBeTruthy();
  expect(mine!.body.extensions?.poolRef, 'poolRef 未透传（§4.2 ③）').toBe('pool-e2e');
  expect(mine!.body.metadata?.session_id).toBe(sid);
  expect(mine!.body.timeout, 'timeout 未按 TTL 换算（1min → 60s）').toBe(60);
  // poolRef 路径抑制 resourceLimits（评审 d17 部分保留）；image 受 SDK 客户端
  // 「image XOR snapshotId」强约束必传（上游对组合实测忽略，镜像以 Pool 模板为准）
  expect(mine!.body.image).toBeTruthy();
  expect(mine!.body.resource).toBeFalsy();
  // 池账本变动：认领后 allocated=1、available 维持 bufferMin=1（补位）
  const pool = await (await fetch(`${SANDBOX_MOCK}/v1/pools/pool-e2e`)).json() as { status: { allocated: number; available: number } };
  expect(pool.status.allocated).toBeGreaterThanOrEqual(1);
  expect(pool.status.available).toBeGreaterThanOrEqual(1);
});

test('L1 配额上限：同 user 第 3 会话 user_quota_exhausted 可感知（SSE 帧或预检 409 同构载荷）', async () => {
  const uid = U();
  const s1 = sessionIdFor(`l1a-${uniq()}`);
  const s2 = sessionIdFor(`l1b-${uniq()}`);
  // 两个在途 turn（sleep 15s，重叠期 ≥ 10s）
  const t1 = chat({ message: `[E2E:execute:sleep](15)`, userId: uid, sessionId: s1, timeoutMs: 120_000 });
  const t2 = chat({ message: `[E2E:execute:sleep](15)`, userId: uid, sessionId: s2, timeoutMs: 120_000 });
  await sleep(6_000);   // 等 s1/s2 完成 acquire + 续期占位（updated_at 新鲜）
  // 第 3 会话：预检 409（HTTP，结构化载荷）或 exec 时 SSE error 帧（code=user_quota_exhausted）
  const res = await rawChat({ message: `[E2E:plain]`, userId: uid, sessionId: sessionIdFor(`l1c-${uniq()}`) });
  let sensed = false;
  if (res.status === 409) {
    const body = await res.json() as Record<string, unknown>;
    expect(body.code).toBe('user_quota_exhausted');
    sensed = true;
  } else {
    const frame = await collectQuotaFrame(res);
    if (frame) sensed = true;
  }
  expect(sensed, '配额满未在任何面可感知（§4.3/§4.6）').toBe(true);
  await Promise.all([waitTerminal(t1), waitTerminal(t2)]);
});

test('L2 chat 预检 409 + bindings 明细（state 字段区分执行中）', async () => {
  const uid = U();
  const s1 = sessionIdFor(`l2a-${uniq()}`);
  const s2 = sessionIdFor(`l2b-${uniq()}`);
  const t1 = chat({ message: `[E2E:execute:sleep](12)`, userId: uid, sessionId: s1, timeoutMs: 120_000 });
  const t2 = chat({ message: `[E2E:execute:sleep](12)`, userId: uid, sessionId: s2, timeoutMs: 120_000 });
  await sleep(6_000);
  const res = await rawChat({ message: `[E2E:plain]`, userId: uid, sessionId: sessionIdFor(`l2c-${uniq()}`) });
  expect(res.status, '预检未按 §4.3 返回 409').toBe(409);
  const body = await res.json() as {
    code: string; quota: number;
    bindings: Array<{ session_id: string; expires_at: string; state: string }>;
  };
  expect(body.code).toBe('user_quota_exhausted');
  expect(body.quota).toBe(2);
  expect(body.bindings.length).toBe(2);
  for (const b of body.bindings) {
    expect(b.session_id).toBeTruthy();
    expect(b.expires_at).toBeTruthy();
    expect(['executing', 'confirm_holding']).toContain(b.state);
  }
  // 黑盒第 2 轮唯一 5xx 回归钉：真实浏览器 chat 发 Accept: text/event-stream（ChatPane.tsx:366）——
  // handler Map body 走 Jackson（仅 application/json）协商失败 → 500；String body 修复后必须仍 409
  const sse = await fetch(`${BASE}/threads/chat`, {
    method: 'POST', headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
    signal: AbortSignal.timeout(120_000),
    body: JSON.stringify({ message: `[E2E:plain]`, userId: uid, sessionId: sessionIdFor(`l2d-${uniq()}`) }),
  });
  expect(sse.status, 'Accept: text/event-stream 下预检 409 退化（内容协商回归）').toBe(409);
  const sseBody = await sse.json();
  expect(sseBody.code).toBe('user_quota_exhausted');
  await Promise.all([waitTerminal(t1), waitTerminal(t2)]);
});

test('L13 幽灵占额修正（PENDING 不变量）：计数偏大 + 陈旧 PENDING 注入 → 新会话 acquire 必须成功', async () => {
  const uid = U();
  const sid = sessionIdFor(`l13a-${uniq()}`);
  const t1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, timeoutMs: 120_000 });
  await waitTerminal(t1);
  expect(t1.terminal?.type).toBe('done');
  // 测试钩子：注入幽灵占额（count=9）+ 陈旧 PENDING 行（模拟占额后崩溃残留）
  await withSql(async q => {
    await q('UPDATE sandbox_user SET binding_count = 9 WHERE user_key = ?', [uid]);
    await q(`INSERT INTO sandbox_binding (user_key, session_id, sandbox_id, expires_at, created_at)
             VALUES (?, 'l13-pending-stale', 'pending:l13test', DATE_SUB(NOW(3), INTERVAL 20 MINUTE),
                     DATE_SUB(NOW(3), INTERVAL 11 MINUTE))`, [uid]);
    const cnt = Number(((await q('SELECT binding_count FROM sandbox_user WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).binding_count);
    expect(cnt).toBe(9);
  });
  // 新会话 acquire：⑤b 重算 + 陈旧 PENDING 回收生效 → 必须成功（L13 通过标准）
  const sid2 = sessionIdFor(`l13b-${uniq()}`);
  const t2 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid2, timeoutMs: 120_000 });
  await waitTerminal(t2);
  expect(t2.terminal?.type, `幽灵占额未修正: ${JSON.stringify(t2.terminal)}`).toBe('done');
  await withSql(async q => {
    const cnt = Number(((await q('SELECT binding_count FROM sandbox_user WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).binding_count);
    const rowsN = Number(((await q(`SELECT COUNT(*) AS c FROM sandbox_binding WHERE user_key = ? AND sandbox_id NOT LIKE 'pending:%'`, [uid])).rows[0] as Record<string, unknown>).c);
    expect(cnt, '计数未回归==行数').toBe(rowsN);
    expect(rowsN).toBe(2);
    const stale = Number(((await q(`SELECT COUNT(*) AS c FROM sandbox_binding WHERE session_id = 'l13-pending-stale'`)).rows[0] as Record<string, unknown>).c);
    expect(stale, '陈旧 PENDING 行未被回收').toBe(0);
  });
});

test('L20 假 409 修正：过期死绑定不计入预检 → 放行 + exec 懒剔除后成功', async () => {
  const uid = U();
  const sid = sessionIdFor(`l20a-${uniq()}`);
  const t1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, timeoutMs: 120_000 });
  await waitTerminal(t1);
  const sbx = (await bindingOf(sid))?.sandbox_id;
  expect(sbx).toBeTruthy();
  await destroySandbox(sbx!);   // 模拟服务端 GC
  // SQL 钩子：绑定行 expires_at 拨到过去（死绑定行龄）
  await withSql(async q => {
    await q(`UPDATE sandbox_binding SET expires_at = DATE_SUB(NOW(3), INTERVAL 5 MINUTE)
             WHERE session_id = ?`, [sid]);
  });
  // 预检：live 过滤后为空 → 放行（HTTP 200 流开始，非 409）
  const res = await rawChat({ message: `[E2E:plain]`, userId: uid, sessionId: sessionIdFor(`l20b-${uniq()}`) });
  expect(res.status, '死绑定触发假 409（§4.3 行龄过滤回归）').toBe(200);
  await res.body?.cancel().catch(() => undefined);
  // exec：① probe 404 → 懒剔除 → 重建
  const sid2 = sessionIdFor(`l20c-${uniq()}`);
  const t2 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid2, timeoutMs: 120_000 });
  await waitTerminal(t2);
  expect(t2.terminal?.type).toBe('done');
  const nb = await bindingOf(sid2);
  expect(nb?.sandbox_id, '重建绑定缺失').toBeTruthy();
  expect(nb!.sandbox_id).not.toBe(sbx);
  const old = await bindingOf(sid);
  expect(old, '死绑定行未被懒剔除').toBeUndefined();
});

test('L8 总开关回退：SANDBOX_LIFECYCLE_ENABLED=false → 超配额不抛异常、TTL 不刷新（行为=基线）', async () => {
  const uid = U();
  // 同 user 3 个会话依次完整对话（配额 2 下 lifecycle 开时第 3 个必 409/异常）——全过
  for (let i = 0; i < 3; i++) {
    const sid = sessionIdFor(`l8-${i}-${uniq()}`);
    const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, base: OFF_BASE, timeoutMs: 120_000 });
    await waitTerminal(stream);
    expect(stream.terminal?.type, `OFF 实例第 ${i + 1} 会话失败（lifecycle 开关未旁路）`).toBe('done');
  }
  // TTL 不随 turn 刷新：OFF 实例的沙箱无任何 renew 调用（T1~T3 全旁路）
  const creates = (await mockStats()).creates;
  const offCreate = creates[creates.length - 1];   // 上面第 3 会话的 create
  const renews = (await mockStats()).renews.filter(x => x.sandboxId === offCreate.id);
  expect(renews.length, 'OFF 实例出现续期调用（开关未旁路 TTL）').toBe(0);
});

// ==================== B 组：TTL 与续期 ====================

test('L4 T2 turn 收尾刷新：renew-expiration 被调用（T2/stop 终刷）', async () => {
  const uid = U();
  const sid = sessionIdFor(`l4-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, timeoutMs: 120_000 });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const sbx = (await bindingOf(sid))?.sandbox_id;
  expect(sbx).toBeTruthy();
  const renews = (await mockStats()).renews.filter(x => x.sandboxId === sbx);
  expect(renews.length, 'turn 收尾后 renew-expiration 零调用（T2/stop 终刷缺失）').toBeGreaterThanOrEqual(1);
});

test('L5a T3 LLM-only 长轮次续期：无工具调用的长 turn 内 renew ≥ 1（turn 信号兜住 call 间隙）', async () => {
  const uid = U();
  const sid1 = sessionIdFor(`l5a1-${uniq()}`);
  // 第一 turn 建 sandbox + 注册表条目（bornAt），完成后 call 归零、条目保留
  const t1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid1, timeoutMs: 120_000 });
  await waitTerminal(t1);
  expect(t1.terminal?.type).toBe('done');
  const sbx = (await bindingOf(sid1))?.sandbox_id;
  expect(sbx).toBeTruthy();
  const base = await renewCount(sbx!);
  // 第二 turn：纯 LLM 慢流（4 chunk × 4s ≈ 12-16s，无任何工具调用）——turn 注册信号兜住续期
  const sid2 = sessionIdFor(`l5a2-${uniq()}`);
  const t2 = chat({ message: `[E2E:slow](x,4000,4)`, userId: uid, sessionId: sid2, timeoutMs: 120_000 });
  await sleep(8_000);   // 轮询窗口：turn 进行中（> 2×RENEW_INTERVAL=6s）
  const during = await renewCount(sbx!);
  expect(during, 'LLM-only 长轮次期间无在途续期（turn 注册信号失效）').toBeGreaterThan(base);
  await waitTerminal(t2);
  expect(t2.terminal?.type).toBe('done');
});

test('L5b T3 长 exec 命令续期：sleep 12s（> 2×RENEW）执行全程 renew ≥ 1', async () => {
  const uid = U();
  const sid = sessionIdFor(`l5b-${uniq()}`);
  const stream = chat({ message: `[E2E:execute:sleep](12)`, userId: uid, sessionId: sid, timeoutMs: 120_000 });
  await sleep(7_000);   // 命令执行中
  const sbx = (await bindingOf(sid))?.sandbox_id;
  expect(sbx, 'acquire 未完成（sleep turn 应已建沙箱）').toBeTruthy();
  const during = await renewCount(sbx!);
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const after = await renewCount(sbx!);
  expect(after, '长命令执行期间续期次数未增加（T3 在途续期缺失）').toBeGreaterThan(0);
  expect(after).toBeGreaterThanOrEqual(during);
});

test('L15 A2A 长任务不被抢 + 结束后停止续期（A2A 无 turn_lease 靠 updated_at 判 busy）', async () => {
  const uid = U();
  // S2 空闲绑定占满配额（2）：其 turn 完成后进入 grace
  const sidFill = sessionIdFor(`l15f-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidFill, timeoutMs: 120_000 }));
  await sleep(12_000);   // grace(9s) 过后 idle
  // A2A 长任务（blocking，metadata.userId = 同一真实用户；binding.session_id = uid）
  const a2aPromise = fetch(`${BASE}/`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'message/send',
      params: { message: { kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
        parts: [{ kind: 'text', text: `[E2E:execute:sleep](15)` }], metadata: { userId: uid, sessionId: sessionIdFor(`l15a2a-${uniq()}`) } } },
    }),
    signal: AbortSignal.timeout(120_000),
  });
  await sleep(4_000);   // A2A 任务完成 acquire（binding.session_id = uid）
  const a2aBinding = (await lifecycleDebug()).bindings.find(b => b.session_id === uid);
  expect(a2aBinding, 'A2A 任务未落绑定（session_id=真实用户语义）').toBeTruthy();
  const a2aSandbox = a2aBinding!.sandbox_id;
  // 同 user 新 chat 会话触发 ⑤：A2A busy（updated_at 新鲜）→ 不改绑 A2A，victim = S2
  const sidNew = sessionIdFor(`l15n-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidNew, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  const after = (await lifecycleDebug()).bindings.find(b => b.session_id === uid);
  expect(after?.sandbox_id, 'A2A 长任务执行中被改绑（互踩）').toBe(a2aSandbox);
  // A2A 完成后 expires_at 停止后移（refcount 归零、无 turn 注册 → 防泄漏）
  await a2aPromise.then(r => r.json()).catch(() => undefined);
  await sleep(13_000);   // grace(6s) + 2×RENEW(3s) 缓冲
  const renewsBefore = await renewCount(a2aSandbox);
  await sleep(7_000);
  const renewsAfter = await renewCount(a2aSandbox);
  expect(renewsAfter, 'A2A 结束后仍被续期（注册表泄漏）').toBe(renewsBefore);
});

test('L16 注册表超龄剔除：挂死 turn（SSE 断连）→ bornAt 超龄强制剔除 + 续期停止', async () => {
  const uid = U();
  const sid = sessionIdFor(`l16-${uniq()}`);
  // 长 exec（140s > 超龄下限 2min 的窗口内保持 turn 注册 + refcount>0）
  const ac = new AbortController();
  const res = await fetch(`${BASE}/threads/chat`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    signal: ac.signal,
    body: JSON.stringify({ message: `[E2E:execute:sleep](140)`, userId: uid, sessionId: sid }),
  });
  expect(res.status).toBe(200);
  await sleep(8_000);   // acquire 完成（bornAt 起算）
  const sbx = (await bindingOf(sid))?.sandbox_id;
  expect(sbx, '长 turn 未建沙箱').toBeTruthy();
  const renewsAtStart = await renewCount(sbx!);
  expect(renewsAtStart).toBeGreaterThanOrEqual(1);
  ac.abort();   // SSE 断连：agent 继续跑（durable SSE），SDK 无终态推进
  // 轮询注册表：bornAt+2min（超龄下限已压低）→ 强制剔除 + ERROR 事件
  await pollUntil(async () => {
    const dbg = await lifecycleDebug();
    const overage = dbg.events.some(e => e.type === 'registry_overage' && e.sandbox_id === sbx);
    return dbg.registry.size === 0 && overage;
  }, v => v === true, 170_000, 3_000).catch(() => undefined);
  const dbg = await lifecycleDebug();
  expect(dbg.events.some(e => e.type === 'registry_overage' && e.sandbox_id === sbx),
    `超龄剔除事件缺失: ${JSON.stringify(dbg.events.slice(-5))}`).toBe(true);
  // 剔除后续期停止（沙箱不被无限续期——最终由服务端 TTL GC）
  const renewsAtEvict = (await mockStats()).renews.filter(x => x.sandboxId === sbx).length;
  await sleep(7_000);
  const renewsFinal = (await mockStats()).renews.filter(x => x.sandboxId === sbx).length;
  expect(renewsFinal, '超龄剔除后仍被续期').toBe(renewsAtEvict);
  expect(renewsAtEvict).toBeGreaterThan(renewsAtStart);
});

test('L17 renew 404 剔除：mock 故障注入 → 注册表剔除 + 绑定懒剔除（配额即时释放）', async () => {
  const uid = U();
  const sid = sessionIdFor(`l17-${uniq()}`);
  const stream = chat({ message: `[E2E:execute:sleep](30)`, userId: uid, sessionId: sid, timeoutMs: 120_000 });
  await sleep(6_000);   // acquire 完成 + 首拍续期落地
  const sbx = (await bindingOf(sid))?.sandbox_id;
  expect(sbx).toBeTruthy();
  await mockFault({ renew404: true });
  try {
    // 下拍续期 404 → 剔注册 + 懒剔除绑定（提前释放配额，不等下次 acquire）
    await pollUntil(async () => {
      const dbg = await lifecycleDebug();
      return dbg.events.some(e => e.type === 'renew_404' && e.sandbox_id === sbx)
        && !dbg.bindings.some(b => b.session_id === sid);
    }, v => v === true, 20_000, 1_000).catch(() => undefined);
    const dbg = await lifecycleDebug();
    expect(dbg.events.some(e => e.type === 'renew_404' && e.sandbox_id === sbx),
      `renew_404 事件缺失: ${JSON.stringify(dbg.events.slice(-5))}`).toBe(true);
    expect(dbg.bindings.some(b => b.session_id === sid),
      'renew 404 后绑定行未懒剔除').toBe(false);
    // 配额即时释放：同 user 新会话直接 create 成功
    const sid2 = sessionIdFor(`l17b-${uniq()}`);
    const t2 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid2, timeoutMs: 120_000 });
    await waitTerminal(t2);
    expect(t2.terminal?.type, `配额未即时释放: ${JSON.stringify(t2.terminal)}`).toBe('done');
  } finally {
    await mockFault({});
  }
  await waitTerminal(stream).catch(() => undefined);   // 原 turn 后续命令报错属预期（沙箱已失簿记）
});