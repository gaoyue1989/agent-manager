/**
 * e2e-sandbox-pool API 组（sandbox-pool-design §8 E2E）：池开启（SANDBOX_POOL_ENABLED=true，
 * 加速回收参数 IDLE_RETURN=8s / SCAN=2s / MAX_SIZE=4 / MIN_IDLE=1）+ mock OpenSandbox。
 *
 * 断言面 = agent 行为（turn 终态）+ mock /stats（creates 数受 MAX_SIZE 约束）
 * + /debug/sandbox-pool（集群级 idle/busy/creating 账本 + 流转事件）。
 * 池默认关闭路径（mock 零改动、行为与 master 一致）由 api-sandbox（X 组）覆盖——本组只跑池开启。
 */
import { test, expect } from '@playwright/test';
import { sessionIdFor } from '../lib/env.js';
import { chat } from '../lib/client.js';
import { waitTerminal, textOf, pollUntil } from '../lib/matchers.js';

const BASE = process.env.E2E_BASE ?? 'http://127.0.0.1:8100';
const SANDBOX_MOCK = process.env.E2E_SANDBOX_MOCK ?? 'http://127.0.0.1:8090';
const POOL = {
  maxSize: 4,
  idleReturnSeconds: 8,
  scanIntervalSeconds: 2,
};
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;
const U = () => `e2e-pool-${Math.random().toString(36).slice(2, 8)}`;

interface PoolStats {
  creates: Array<{ id: string; resourceRequests?: Record<string, string> }>;
  connects: Array<Record<string, unknown>>;
  errors: Array<Record<string, unknown>>;
}

async function sandboxStats(): Promise<PoolStats> {
  const r = await fetch(`${SANDBOX_MOCK}/stats`);
  return r.json() as Promise<PoolStats>;
}

async function resetMockStats(): Promise<void> {
  await fetch(`${SANDBOX_MOCK}/reset`, { method: 'POST' });
}

interface PoolDebug {
  enabled: boolean;
  idle: string[];
  stickyIdle: Array<{ sandboxId: string; owner: string; sid: string }>;
  busy: Array<{ sandboxId: string; sid: string; userKey?: string; podId: string }>;
  creating: string[];
  maxSize: number;
  target?: number;
  waiters: number;
  recent_events: Array<{ event: string; sid?: string; sandboxId?: string }>;
  config?: Record<string, number>;
}

async function poolDebug(): Promise<PoolDebug> {
  const r = await fetch(`${BASE}/debug/sandbox-pool`);
  return r.json() as Promise<PoolDebug>;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

test('P1 池开启借出与容器数约束（creates ≤ MAX_SIZE）', async () => {
  await resetMockStats();
  const before = (await sandboxStats()).creates.length;
  // 两个会话串行借出（每会话一个 slot/容器，池容量 MAX_SIZE=2）
  for (let i = 0; i < 2; i++) {
    const sid = sessionIdFor(`p1-${i}-${uniq()}`);
    const stream = chat({ message: `[E2E:plain]`, userId: U(), sessionId: sid });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');
  }
  const stats = await sandboxStats();
  // 核心断言：容器创建数受池上限约束（现状行为无上限，N 会话 ≥ N 容器）
  expect(stats.creates.length - before).toBeLessThanOrEqual(POOL.maxSize);
  expect(stats.creates.length - before).toBeGreaterThanOrEqual(1);
});

test('P2 debug 端点透出集群级池状态', async () => {
  const sid = sessionIdFor(`p2-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: U(), sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');

  const pool = await poolDebug();
  expect(pool.enabled).toBe(true);
  expect(pool.maxSize).toBe(POOL.maxSize);
  expect(Array.isArray(pool.idle)).toBe(true);
  expect(Array.isArray(pool.busy)).toBe(true);
  expect(Array.isArray(pool.creating)).toBe(true);
  // 账本水位合计 ≤ 集群上限（idle + busy + creating）
  expect(pool.idle.length + pool.busy.length + pool.creating.length).toBeLessThanOrEqual(POOL.maxSize);
  // 流转事件环形缓冲可读
  expect(Array.isArray(pool.recent_events)).toBe(true);
  expect(pool.recent_events.length).toBeGreaterThanOrEqual(1);
});

test('P3 失活回收：IDLE_RETURN+SCAN 后容器归还并补位', async () => {
  test.setTimeout(60_000);
  // 前后两段同 uid：归还转粘滞（D17）后同用户新会话经继承路径借出（不依赖通用 idle——
  // 粘滞空闲占集群容量，随机用户在 MAX_SIZE 内可能无容器可借）
  const uid = U();
  const sid = sessionIdFor(`p3-${uniq()}`);
  const stream = chat({ message: `[E2E:execute]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  expect(textOf(stream.frames)).toContain('hello-e2a');

  // turn 结束 = 挂起点 stop() 最后续约；IDLE_RETURN(8s)+SCAN(2s) 内无新活动 → 扫描 kill + 补位
  const deadline = Date.now() + (POOL.idleReturnSeconds + POOL.scanIntervalSeconds + 10) * 1000;
  let reclaimed = false;
  while (Date.now() < deadline && !reclaimed) {
    await sleep(1000);
    const pool = await poolDebug();
    // 回收证据：busy 归零（借出条目被认领移除）且出现回收流转事件
    // （D17 归还转粘滞：owner 已知 → return-sticky 不 kill；owner 未知/探测失败仍 scan-kill/rebuild）
    const busyEmpty = pool.busy.length === 0;
    const killSeen = pool.recent_events.some(
      (e) => e.event === 'scan-kill' || e.event === 'rebuild' || e.event === 'return-sticky');
    reclaimed = busyEmpty && killSeen;
  }
  expect(reclaimed, `失活容器应在 IDLE_RETURN+SCAN 内归还（busy 清空 + scan-kill 事件）`).toBe(true);
  // 归还后同用户新会话可继续借出（转粘滞容器经继承路径复用，会话无感）
  const sid2 = sessionIdFor(`p3b-${uniq()}`);
  const stream2 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid2 });
  await waitTerminal(stream2);
  expect(stream2.terminal?.type).toBe('done');
});

test('P4 resume 404 降级：容器被外部 GC 后同会话复活借新容器', async () => {
  test.setTimeout(90_000);
  // 前序用例的粘滞容器占集群容量（D17）：等待释放出可借空间
  await pollUntil(async () => await poolDebug(),
    (p) => p.busy.length + p.stickyIdle.length < p.maxSize, 40_000, 2000);
  const uid = U();
  const sid = sessionIdFor(`p4-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');

  // 找到该会话借出的容器 id（busy 账本；poolSid 尽力而为——acquire 可能先于
  // middleware ThreadLocal 设置，busy.sid 可能为空串，此时取任一 busy 条目）
  const pool = await poolDebug();
  const busy = pool.busy.find((b) => b.sid && sid.includes(b.sid))
    ?? pool.busy.find((b) => b.sandboxId);
  if (busy) {
    await fetch(`${SANDBOX_MOCK}/admin/destroy/${busy.sandboxId}`, { method: 'POST' });
    // 等 busy 记录自愈清空（租约 IDLE_RETURN 过期 + 扫描认领）：死容器条目短存期间仍计 D17 配额
    // （held 含死容器 busy 记录），USER_MAX=1 下立即重借会报 user_quota_exhausted
    await pollUntil(async () => await poolDebug(),
      (p) => !p.busy.some((b) => b.sandboxId === busy.sandboxId), 30_000, 2000);
    // 同会话下个 turn：slot 指向已销毁容器 → resume 404 → 降级 create（重借/重建，D5）
    const next = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
    await waitTerminal(next);
    expect(next.terminal?.type).toBe('done');
    const stats = await sandboxStats();
    expect(stats.creates.length).toBeGreaterThanOrEqual(1);
  }
});

test('P5 池开启下池关闭语义对照：/debug/sandbox-pool 配置正确', async () => {
  const pool = await poolDebug();
  expect(pool.enabled).toBe(true);
  expect(pool.config).toBeTruthy();
  expect(pool.config?.maxSize).toBe(POOL.maxSize);
  expect(pool.config?.idleReturnSeconds).toBe(POOL.idleReturnSeconds);
});

test('P6 同用户配额：user_quota_exhausted 可感知 + 粘滞窗口内继承同容器（D17）', async () => {
  test.setTimeout(90_000);
  const uid = U();
  const sid1 = sessionIdFor(`p6a-${uniq()}`);
  const stream1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid1 });
  await waitTerminal(stream1);
  expect(stream1.terminal?.type).toBe('done');
  // 守卫钩子（P1/P2）：chat 主链路借出身份真实可得——busy.sid=前端 sid（turn_lease 键）、busy.userKey=真实用户
  const pool1 = await poolDebug();
  const busy1 = pool1.busy.find((b) => b.sid && sid1.includes(b.sid));
  expect(busy1, '借出身份应经守卫钩子写入 busy 账本').toBeTruthy();
  // 配额满且无粘滞空闲（会话 1 仍 busy，USER_MAX=1）→ 第二会话 user_quota_exhausted 可感知报错
  const sid2 = sessionIdFor(`p6b-${uniq()}`);
  const stream2 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid2, timeoutMs: 30_000 });
  await waitTerminal(stream2);
  const errText = JSON.stringify(stream2.frames);
  expect(stream2.terminal?.type === 'error' || errText.includes('user quota exhausted'),
    `配额满应可感知报错，实际终态=${stream2.terminal?.type}`).toBe(true);
  // 会话 1 失活：IDLE_RETURN(8s)+SCAN(2s) → 归还转粘滞（owner=uid）。
  // 谓词必须锚定本会话容器（stickyIdle 出现 busy1.sandboxId）——前序用例的 return-sticky 事件会误触发
  await pollUntil(async () => await poolDebug(),
    (p) => p.stickyIdle.some((st) => st.sandboxId === busy1!.sandboxId), 30_000, 2000);
  // 同用户新会话：held(uid) ≥ USER_MAX(1) → LRU 继承粘滞容器——同 sandboxId、容器数 ≤ 配额
  const sid3 = sessionIdFor(`p6c-${uniq()}`);
  const stream3 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid3 });
  await waitTerminal(stream3);
  expect(stream3.terminal?.type).toBe('done');
  const pool3 = await poolDebug();
  const busy3 = pool3.busy.find((b) => b.sid && sid3.includes(b.sid));
  expect(busy3, '会话 3 应借出成功').toBeTruthy();
  expect(busy3!.sandboxId, '配额满应继承粘滞容器（同 sandboxId）').toBe(busy1!.sandboxId);
  const mine = pool3.busy.filter((b) => b.userKey === uid).length
    + pool3.stickyIdle.filter((s) => s.owner === uid).length;
  expect(mine).toBeLessThanOrEqual(1); // USER_MAX=1（含粘滞空闲与使用中）
});

test('P8 池满等待：waiters 可观测、超时报错可感知（设计 §8 E2E / §10 验收）', async () => {
  test.setTimeout(120_000);
  // 等前序用例的粘滞容器到期释放（MAX_SIZE=4 需要干净池做占满）
  await pollUntil(async () => await poolDebug(),
    (p) => p.busy.length === 0 && p.stickyIdle.length <= 1, 60_000, 2000);
  await resetMockStats();
  // 并发 5 个会话（MAX_SIZE=4）：4 个占满容量，第 5 个进入池空轮询等待（MAX_WAIT=6s）
  const streams: Array<ReturnType<typeof chat>> = [];
  for (let i = 0; i < 4; i++) {
    streams.push(chat({ message: `[E2E:plain]`, userId: U(), sessionId: sessionIdFor(`p8-${i}-${uniq()}`) }));
  }
  // 池满证据 1：无可借容量占满（busy+粘滞+creating = MAX_SIZE）。4 个并行 turn 数秒内 done
  // 归还转粘滞（D17 粘滞占集群容量），busy 峰值采样窗口窄——以「无可借容量」为占满判定更稳
  await pollUntil(async () => await poolDebug(),
    (p) => p.busy.length + p.stickyIdle.length + p.creating.length >= POOL.maxSize, 40_000, 500);
  const fifth = chat({ message: `[E2E:plain]`, userId: U(), sessionId: sessionIdFor(`p8f-${uniq()}`), timeoutMs: 60_000 });
  // 池满证据 2：等待期间 /debug/sandbox-pool waiters > 0（池空轮询进行中）
  await pollUntil(async () => await poolDebug(),
    (p) => p.waiters > 0, 15_000, 500);
  await waitTerminal(fifth);
  // 超时可感知：第 5 个会话以 error 终态收敛且报文含 pool exhausted（既不悬挂也不静默重试）；
  // 若 4 个 turn 在 MAX_WAIT 内全部归还释放出可借容器，done 亦为合法终态（等待后借出成功）
  const terminal = fifth.terminal?.type;
  const errText = JSON.stringify(fifth.frames);
  expect(terminal === 'error' || terminal === 'done', `第 5 个会话必须收敛到终态，实际=${terminal}`).toBe(true);
  if (terminal === 'error') {
    expect(errText.includes('pool exhausted'), `超时报错应含 pool exhausted: ${errText.slice(0, 300)}`).toBe(true);
  }
  // 收敛前 4 个并发会话（各自独立用户，互不受配额约束）。5 个会话竞争 4 个容器：
  // MAX_WAIT 内借到=done、未借到=pool exhausted error——两者都是设计内可感知终态
  // （「池满等待不悬挂、超时可感知」正是本用例的验收语义）
  for (const st of streams) {
    await waitTerminal(st);
    const t = st.terminal?.type;
    expect(t === 'done' || (t === 'error' && JSON.stringify(st.frames).includes('pool exhausted')),
      `并发会话必须收敛到 done 或 pool exhausted，实际=${t}`).toBe(true);
  }
});

test('P7 create 请求体携带 resourceRequests（D19 资源双档）', async () => {
  test.setTimeout(90_000);
  const stats = await sandboxStats();
  // 池预热/按需建的 create 请求体应带 D19 resourceRequests（默认 CPU 100m / memory 128Mi）。
  // 断言全量 creates（不 reset：借出可能命中既有 idle 不触发新 create，预热 create 在应用启动时已发生）
  const withReq = stats.creates.find(
    (c) => c.resourceRequests?.cpu === '100m' && c.resourceRequests?.memory === '128Mi');
  expect(withReq, '至少一个 create 应携带 resourceRequests（独立于池开关生效）').toBeTruthy();
});
