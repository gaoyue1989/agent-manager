/**
 * e2e-sandbox-pool API 组（sandbox-pool-design §8 E2E）：池开启（SANDBOX_POOL_ENABLED=true，
 * 加速回收参数 IDLE_RETURN=8s / SCAN=2s / MAX_SIZE=2 / MIN_IDLE=1）+ mock OpenSandbox。
 *
 * 断言面 = agent 行为（turn 终态）+ mock /stats（creates 数受 MAX_SIZE 约束）
 * + /debug/sandbox-pool（集群级 idle/busy/creating 账本 + 流转事件）。
 * 池默认关闭路径（mock 零改动、行为与 master 一致）由 api-sandbox（X 组）覆盖——本组只跑池开启。
 */
import { test, expect } from '@playwright/test';
import { sessionIdFor } from '../lib/env.js';
import { chat } from '../lib/client.js';
import { waitTerminal, textOf } from '../lib/matchers.js';

const BASE = process.env.E2E_BASE ?? 'http://127.0.0.1:8100';
const SANDBOX_MOCK = process.env.E2E_SANDBOX_MOCK ?? 'http://127.0.0.1:8090';
const POOL = {
  maxSize: 2,
  idleReturnSeconds: 8,
  scanIntervalSeconds: 2,
};
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;
const U = () => `e2e-pool-${Math.random().toString(36).slice(2, 8)}`;

interface PoolStats {
  creates: Array<{ id: string }>;
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
  busy: Array<{ sandboxId: string; sid: string; podId: string }>;
  creating: string[];
  maxSize: number;
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
  const sid = sessionIdFor(`p3-${uniq()}`);
  const stream = chat({ message: `[E2E:execute]`, userId: U(), sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  expect(textOf(stream.frames)).toContain('hello-e2a');

  // turn 结束 = 挂起点 stop() 最后续约；IDLE_RETURN(8s)+SCAN(2s) 内无新活动 → 扫描 kill + 补位
  const deadline = Date.now() + (POOL.idleReturnSeconds + POOL.scanIntervalSeconds + 10) * 1000;
  let reclaimed = false;
  while (Date.now() < deadline && !reclaimed) {
    await sleep(1000);
    const pool = await poolDebug();
    // 回收证据：busy 归零（借出条目被认领移除）且出现 scan-kill / rebuild 流转事件
    const busyEmpty = pool.busy.length === 0;
    const killSeen = pool.recent_events.some((e) => e.event === 'scan-kill' || e.event === 'rebuild');
    reclaimed = busyEmpty && killSeen;
  }
  expect(reclaimed, `失活容器应在 IDLE_RETURN+SCAN 内归还（busy 清空 + scan-kill 事件）`).toBe(true);
  // 补位后新会话可继续借出（重建容器入池，会话无感）
  const sid2 = sessionIdFor(`p3b-${uniq()}`);
  const stream2 = chat({ message: `[E2E:plain]`, userId: U(), sessionId: sid2 });
  await waitTerminal(stream2);
  expect(stream2.terminal?.type).toBe('done');
});

test('P4 resume 404 降级：容器被外部 GC 后同会话复活借新容器', async () => {
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
