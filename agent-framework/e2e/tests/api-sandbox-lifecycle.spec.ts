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
 * 分组顺序执行（共享 mock 状态，前组产物供后组复用）：A 借出与配额（L1/L2/L6/L7/L8/L13/L20）
 * → B TTL 与续期（L4/L5/L15/L16/L17）→ C 复用与抢占（L3/L12/L14/L19）→ D HITL（L18）→
 * E 可观测与清理（L9/L10/L11）。
 */
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { BASE, sessionIdFor } from '../lib/env.js';
import { chat, confirmStream, createApprovalApp } from '../lib/client.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';

const SANDBOX_MOCK = process.env.E2E_SANDBOX_MOCK ?? 'http://127.0.0.1:8090';
const OFF_BASE = process.env.E2E_LIFECYCLE_OFF_BASE ?? 'http://127.0.0.1:8101';
const U = () => `e2e-lc-${Math.random().toString(36).slice(2, 8)}`;
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

// ---- mock 观测/管理通道（§9.2 扩展面）----

interface CreateRecord {
  id: string;
  body: {
    extensions: { poolRef?: string } | null;
    metadata: Record<string, string> | null;
    timeout: number | null;
    image: unknown;
    resource: unknown;
  };
}
interface MockStats {
  creates: CreateRecord[];
  renews: Array<{ sandboxId: string; expiresAt: string; at: number }>;
  commands: Array<{ sandboxId: string; cmd: string; exitCode?: number }>;
  faultHits: number;
}

async function mockStats(): Promise<MockStats> {
  return (await (await fetch(`${SANDBOX_MOCK}/stats`)).json()) as MockStats;
}

async function mockReset(): Promise<void> {
  await fetch(`${SANDBOX_MOCK}/reset`, { method: 'POST' });
}

async function mockFault(faults: Record<string, unknown>): Promise<void> {
  await fetch(`${SANDBOX_MOCK}/admin/fault`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(faults) });
}

async function createPool(name: string): Promise<Response> {
  return fetch(`${SANDBOX_MOCK}/v1/pools`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name, capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3 } }),
  });
}

async function destroySandbox(id: string): Promise<void> {
  await fetch(`${SANDBOX_MOCK}/admin/destroy/${id}`, { method: 'POST' });
}

/** 向指定沙箱工作区植入文件（黑盒走 mock 的 execd files/upload 通道，L19/L18 用） */
async function plantFile(sandboxId: string, path: string, content: string): Promise<void> {
  const boundary = '----e2elc' + Date.now();
  const meta = Buffer.from(JSON.stringify({ path }));
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\ncontent-disposition: form-data; name="metadata"\r\ncontent-type: application/json\r\n\r\n`),
    meta, Buffer.from(`\r\n--${boundary}\r\ncontent-disposition: form-data; name="file"; filename="f"\r\ncontent-type: application/octet-stream\r\n\r\n`),
    Buffer.from(content), Buffer.from(`\r\n--${boundary}--\r\n`),
  ]);
  const res = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${sandboxId}/proxy/44772/files/upload`, {
    method: 'POST', headers: { 'content-type': `multipart/form-data; boundary=${boundary}` }, body,
  });
  expect(res.status, `植入文件失败 ${path}`).toBe(200);
}

/** 探针：读指定沙箱工作区文件（对齐 X10 的 files/download 通道） */
async function probeFile(sandboxId: string, path: string): Promise<{ status: number; text: string }> {
  const res = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${sandboxId}/proxy/44772/files/download?path=${encodeURIComponent(path)}`);
  return { status: res.status, text: await res.text() };
}

// ---- 调试端点与 DB 钩子 ----

interface BindingRow { session_id: string; sandbox_id: string; turn_running: boolean }
interface LifecycleDebug {
  enabled: boolean;
  config: Record<string, unknown>;
  bindings: BindingRow[];
  registry: { size: number; entries: Array<Record<string, unknown>> };
  events: Array<{ type: string; sandbox_id: string; detail: string }>;
}

async function lifecycleDebug(base = BASE): Promise<LifecycleDebug> {
  return (await (await fetch(`${base}/debug/sandbox-bindings`)).json()) as LifecycleDebug;
}

const bindingOf = async (sid: string, base = BASE): Promise<BindingRow | undefined> =>
  (await lifecycleDebug(base)).bindings.find(b => b.session_id === sid);

/** renews 计数（按沙箱过滤） */
const renewCount = async (sandboxId: string): Promise<number> =>
  (await mockStats()).renews.filter(x => x.sandboxId === sandboxId).length;

/** MySQL 直连（L13/L20/L11 测试钩子：PENDING 不变量/死绑定/清理的注入与对账面） */
async function withSql<T>(fn: (q: (sql: string, params?: unknown[]) => Promise<{ rows: unknown[]; affected: number }>) => Promise<T>): Promise<T> {
  const mysql = (await import('mysql2/promise')).default;
  // CI 经 job env 注入 MYSQL_URL；本地两段式运行（env-up 与 playwright 分离）回退 env.json
  // （lib/env.ts replicaUrl 同款回退纪律）
  const runtimeMysql = (() => {
    try {
      const dir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
      return String(JSON.parse(readFileSync(`${dir}/env.json`, 'utf8')).mysqlUrl ?? '');
    } catch { return ''; }
  })();
  const jdbcUrl = process.env.MYSQL_URL || runtimeMysql || 'jdbc:mysql://127.0.0.1:3306/agent_framework_e2e';
  const m = /jdbc:mysql:\/\/([^:/]+):(\d+)\/([^?]+)/.exec(jdbcUrl);
  const conn = await mysql.createConnection({
    host: m?.[1] ?? '127.0.0.1', port: Number(m?.[2] ?? 3306), database: m?.[3] ?? 'agent_framework_e2e',
    user: process.env.MYSQL_USER ?? 'e2e', password: process.env.MYSQL_PASS ?? 'e2e-pass',
  });
  try {
    return await fn(async (sql, params) => {
      const [result] = await conn.query(sql, params ?? []);
      // @ts-expect-error mysql2 ResultSetHeader/RowDataPacket 联合形态
      return { rows: Array.isArray(result) ? result as unknown[] : [result], affected: (result as { affectedRows?: number }).affectedRows ?? 0 };
    });
  } finally {
    await conn.end();
  }
}

/** 原始 chat（读预检 409 body / 自控 abort）：collectStream 对非 2xx 静默吞帧，L1/L2 需直读 */
async function rawChat(body: Record<string, unknown>, timeoutMs = 120_000): Promise<Response> {
  return fetch(`${BASE}/threads/chat`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    signal: AbortSignal.timeout(timeoutMs),
    body: JSON.stringify(body),
  });
}

/** 采集流中的 user_quota_exhausted 结构化帧（§4.6 SSE 面） */
async function collectQuotaFrame(res: Response): Promise<Record<string, unknown> | null> {
  if (!res.ok || !res.body) return null;
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      for (const line of buf.split('\n')) {
        if (!line.startsWith('data:')) continue;
        try {
          const f = JSON.parse(line.slice(5).trim()) as Record<string, unknown>;
          if (f.code === 'user_quota_exhausted') return f;
        } catch { /* 非 JSON 行 */ }
      }
      buf = buf.slice(buf.lastIndexOf('\n') + 1);
    }
  } catch { /* 中断 */ }
  return null;
}

// L 组共享状态（分组顺序执行的产物传递）
const shared: {
  poolCreated: boolean;
  l3Uid?: string;
  l3VictimSid?: string; l3VictimSandbox?: string;
} = { poolCreated: false };

test.beforeAll(async () => {
  await mockReset();
  // 建池（L6 断言 create 载荷 poolRef 透传 + 池耗尽面共用）；幂等：409 视为已存在
  const res = await createPool('pool-e2e');
  expect([201, 409]).toContain(res.status);
  shared.poolCreated = true;
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

// ==================== C 组：复用与抢占 ====================

test('L3 LRU 复用：占满后空闲行被继承（同 sandboxId + renew 计数 +1）', async () => {
  const uid = U();
  const sidA = sessionIdFor(`l3a-${uniq()}`);
  const sidB = sessionIdFor(`l3b-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA, timeoutMs: 120_000 }));
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidB, timeoutMs: 120_000 }));
  const bindA = await bindingOf(sidA);
  const bindB = await bindingOf(sidB);
  expect(bindA && bindB).toBeTruthy();
  await sleep(12_000);   // grace(=3×RENEW=9s) 过后双双 idle（LRU 可选）
  // victim = expires_at 较小者（A 先建先收尾）——预读两条的 renew 基线
  const renewsA = await renewCount(bindA!.sandbox_id);
  const renewsB = await renewCount(bindB!.sandbox_id);
  const sidC = sessionIdFor(`l3c-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidC, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  const bindC = await bindingOf(sidC);
  expect(bindC?.sandbox_id, '新会话未继承既有沙箱（LRU 失效或走了 create）').toBeTruthy();
  expect([bindA!.sandbox_id, bindB!.sandbox_id]).toContain(bindC!.sandbox_id);
  // LRU 复用伴随 renew 顶 TTL（⑤c renew）：victim 沙箱的 renew 计数必须增加
  const victimRenewsBefore = bindC!.sandbox_id === bindA!.sandbox_id ? renewsA : renewsB;
  expect(await renewCount(bindC!.sandbox_id), 'LRU 复用未伴随 renew（⑤c 应 renew 顶 TTL）')
    .toBeGreaterThan(victimRenewsBefore);
  // 记录现场供 L14（受害会话=被夺走行的原 session，含其归属 uid）
  shared.l3Uid = uid;
  shared.l3VictimSid = bindC!.sandbox_id === bindA!.sandbox_id ? sidA : sidB;
  shared.l3VictimSandbox = bindC!.sandbox_id;
});

test('L14 被抢会话不重连易主沙箱：归属校验拦截 → 降级重建 + 换代提示帧', async () => {
  const victimSid = shared.l3VictimSid;
  const stolenSandbox = shared.l3VictimSandbox;
  const uid = shared.l3Uid;
  test.skip(!victimSid || !stolenSandbox || !uid, 'L3 未产出受害现场');
  // 受害会话再发 turn（归属 uid 不变）：P3 resume 指向易主沙箱 → 归属校验+占位 0 行 → close+降级 → 新决策
  const stream = chat({ message: `[E2E:plain]`, userId: uid!, sessionId: victimSid!, timeoutMs: 120_000 });
  await waitTerminal(stream);
  expect(stream.terminal?.type, `受害会话重入终态异常: ${JSON.stringify(stream.terminal)}`).toBe('done');
  const now_ = (await lifecycleDebug()).bindings.find(b => b.session_id === victimSid);
  expect(now_?.sandbox_id, '受害会话重建后无绑定（降级 create 脱簿记——身份归还缺失）').toBeTruthy();
  expect(now_!.sandbox_id, '受害会话仍持易主沙箱（两道防线失效）').not.toBe(stolenSandbox);
  // 换代提示帧（§4.4 T4 恢复路径）：首个事件前一次性提示
  expect(stream.frames.some(f => f.type === 'sandbox_rebuilt'), '缺换代提示帧').toBe(true);
});

test('L12 租约硬释放不误判：>1min 长命令执行中（turn_lease 已消失）⑤ 不改绑该会话', async () => {
  const uid = U();
  const sidFill = sessionIdFor(`l12f-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidFill, timeoutMs: 120_000 }));
  await sleep(8_000);   // S2 idle
  const sidLong = sessionIdFor(`l12a-${uniq()}`);
  const long = chat({ message: `[E2E:execute:sleep](70)`, userId: uid, sessionId: sidLong, timeoutMs: 180_000 });
  await sleep(66_000);   // turn_lease 硬寿命 1min 到期物理删行（执行线程不感知，靠 updated_at 判 busy）
  const longSandbox = (await bindingOf(sidLong))?.sandbox_id;
  expect(longSandbox, '长命令 turn 未建沙箱').toBeTruthy();
  // 同 user 新会话触发 ⑤：长命令会话 busy（在途续期保持 updated_at 新鲜）→ 不被改绑
  const sidNew = sessionIdFor(`l12n-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidNew, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  expect((await bindingOf(sidLong))?.sandbox_id, '长命令会话在 turn_lease 消失后被改绑（L12 回归）')
    .toBe(longSandbox);
  await waitTerminal(long);
  expect(long.terminal?.type, '长命令 turn 未正常完成').toBe('done');
});

test('L19 同 user 继承残留（契约钉死）：LRU 复用后新会话可见旧会话工作区文件', async () => {
  const uid = U();
  // 1) sidA 建 sandbox（写入代际）； grace 过后 idle
  const sidA = sessionIdFor(`l19a-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA, timeoutMs: 120_000 }));
  const bindA = await bindingOf(sidA);
  expect(bindA?.sandbox_id).toBeTruthy();
  await plantFile(bindA!.sandbox_id, 'data/out.txt', `l19-residue-${uniq()}`);
  await sleep(12_000);   // A idle（grace=9s 过后）
  // 2) busy 会话占第 2 个配额槽（create 自有沙箱，在途续期保持 busy）
  const busy = chat({ message: `[E2E:execute:sleep](14)`, userId: uid, sessionId: sessionIdFor(`l19busy-${uniq()}`), timeoutMs: 120_000 });
  await sleep(5_000);   // busy 完成 acquire
  // 3) 新会话 ⑤：idle 唯一 = sidA 行 → 必然继承 sidA 沙箱（victim 确定）
  const sidC = sessionIdFor(`l19c-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidC, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  const bindC = await bindingOf(sidC);
  expect(bindC?.sandbox_id, '⑤ 未产出绑定').toBeTruthy();
  expect(bindC!.sandbox_id, '⑤ 未继承 sidA 沙箱（busy 行不可选未生效或走了 create）').toBe(bindA!.sandbox_id);
  // 4) 继承的沙箱内可见残留文件（同 user 残留为 §4.5 明示接受的行为，写进契约断言）
  const probe = await probeFile(bindC!.sandbox_id, '/workspace/data/out.txt');
  expect(probe.status, '继承沙箱内旧会话文件丢失').toBe(200);
  expect(probe.text).toContain('l19-residue-');
  await waitTerminal(busy);
});

// ==================== D 组：HITL 保留窗 ====================

test('L18a 挂起保留窗：ask 挂起 → 保留窗内持续续期不可被 ⑤ 调度 → confirm 恢复同沙箱', async () => {
  const uid = U();
  const sidFill = sessionIdFor(`l18f-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidFill, timeoutMs: 120_000 }));
  const sidAsk = sessionIdFor(`l18a-${uniq()}`);
  const app = await createApprovalApp(BASE, 'E2E L18a 审批');
  const ask = chat({ message: `[E2E:hitl:submit](${app})`, userId: uid, sessionId: sidAsk, timeoutMs: 120_000 });
  await waitTerminal(ask);
  expect(ask.terminal?.type).toBe('permission_ask');
  const askSandbox = (await bindingOf(sidAsk))?.sandbox_id;
  expect(askSandbox, 'ask 会话未建沙箱（acquire 先于 ask）').toBeTruthy();
  // 向 ask 沙箱植入文件（保留窗恢复同沙箱 → 文件仍在）
  const marker = `l18-file-${uniq()}`;
  await plantFile(askSandbox!, 'data/l18.txt', marker);
  await sleep(12_000);   // ask 段租约释放 + grace(9s) 过后 fill 行 idle（ask 行由 hold 续期保持 busy）
  // 保留窗内同 user 新会话触发 ⑤：ask 行 busy（hold 续期）→ 不改绑，victim = fill 行
  const sidB = sessionIdFor(`l18b-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidB, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  expect((await bindingOf(sidAsk))?.sandbox_id, '保留窗内挂起会话被 ⑤ 改绑（T4 失效）').toBe(askSandbox);
  // confirm 恢复：同沙箱续跑（文件无损）
  const tcid = ((ask.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  await sleep(800);
  const rec = confirmStream(sidAsk, [{ tool_call_id: tcid, confirmed: true }], BASE, 120_000, uid);
  await waitTerminal(rec);
  expect(rec.terminal?.type, `confirm 恢复终态异常: ${JSON.stringify(rec.terminal)}`).toBe('done');
  expect((await bindingOf(sidAsk))?.sandbox_id, 'confirm 恢复未落同沙箱（文件丢失面）').toBe(askSandbox);
  const probe = await probeFile(askSandbox!, '/workspace/data/l18.txt');
  expect(probe.status, '保留窗恢复后工作区文件丢失').toBe(200);
  expect(probe.text).toContain(marker);
});

test('L18b 保留窗过期：保留窗后 A 行可被 ⑤ 改绑，A 过期 confirm → 新 sandboxId + 换代提示帧', async () => {
  const uid = U();
  const sidAsk = sessionIdFor(`l18oa-${uniq()}`);
  const app = await createApprovalApp(BASE, 'E2E L18b 审批');
  const ask = chat({ message: `[E2E:hitl:submit](${app})`, userId: uid, sessionId: sidAsk, timeoutMs: 120_000 });
  await waitTerminal(ask);
  expect(ask.terminal?.type).toBe('permission_ask');
  const askSandbox = (await bindingOf(sidAsk))?.sandbox_id;
  expect(askSandbox).toBeTruthy();
  // t0≈ask。A 行 expires_at ≈ t0+120（hold 续期到窗尾）；后建行 expires 恒更晚 → 欲让 ⑤ 选中 A 行，
  // 须令其它行 busy 遮蔽。t0+50 放 fill（sleep 15，t0+65 结束，T2 renew → expires ≈ t0+128 > A）；
  // t0+70（A 的 grace=9s 于 t0+69 刚过、fill 仍 busy）放 stealer → victim 唯一 = A 行。
  await sleep(50_000);
  const fill = chat({ message: `[E2E:execute:sleep](15)`, userId: uid, sessionId: sessionIdFor(`l18of-${uniq()}`), timeoutMs: 120_000 });
  await sleep(20_000);   // t0+70
  const sidSteal = sessionIdFor(`l18os-${uniq()}`);
  const steal = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidSteal, timeoutMs: 120_000 });
  await waitTerminal(steal);
  expect(steal.terminal?.type).toBe('done');
  // A 行已被改绑（session_id 归 stealer）：bindingOf(sidAsk) 落空即 steal 生效的签名
  const stolen = (await bindingOf(sidAsk)) === undefined;
  // 等全部行 freshness 窗口（fill 至 t0+77、steal 行至 ≈t0+83）过去 → 全 idle
  await sleep(16_000);   // ≈t0+86
  // A 过期 confirm：卡片仍可点（无硬拒）；恢复时归属校验拦截 → 降级 → ⑤（全 idle，victim=expires 最小行）
  // → A 换新归属（≠ 原 askSandbox）+ sandbox_rebuilt 换代提示帧
  const tcid = ((ask.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  const rec = confirmStream(sidAsk, [{ tool_call_id: tcid, confirmed: true }], BASE, 150_000, uid);
  await waitTerminal(rec);
  expect(rec.terminal?.type, `过期 confirm 终态异常: ${JSON.stringify(rec.terminal)}`).toBe('done');
  const newSandbox = (await bindingOf(sidAsk))?.sandbox_id;
  expect(newSandbox, '过期 confirm 后未换代（降级 create 脱簿记或恢复未走沙箱）').toBeTruthy();
  expect(newSandbox, '过期 confirm 仍落旧沙箱（保留窗过期未失效）').not.toBe(askSandbox);
  expect(rec.frames.some(f => f.type === 'sandbox_rebuilt'), '过期 confirm 缺换代提示帧').toBe(true);
  if (!stolen) {
    // steal 未发生（victim 选中其他行）时恢复可能落回原沙箱——此时换代断言应放宽为「confirm 正常完成」
    expect(newSandbox).toBeTruthy();
  }
  await waitTerminal(fill);
});

// ==================== E 组：可观测与清理 ====================

test('L9 到期重建：/admin/destroy 模拟 GC → 下 turn 重建 + 新绑定（对 turn 透明）', async () => {
  const uid = U();
  const sid = sessionIdFor(`l9a-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, timeoutMs: 120_000 }));
  const old = (await bindingOf(sid))?.sandbox_id;
  expect(old).toBeTruthy();
  await destroySandbox(old!);   // 模拟服务端 TTL GC
  // destroy 与在途续期（sweep/stop flush）存在竞态：下一拍 renew 404 → 框架按 §4.4 剔注册 +
  // 懒删绑定（提前释放配额）——等绑定行消失（≤ RENew 3s），区分不出「① 懒剔除」还是
  // 「renew_404 剔除」属预期（两条路径都收敛到行删 + 配额释放）
  await pollUntil(async () => (await bindingOf(sid)) === undefined, v => v === true, 15_000, 1_000)
    .catch(() => undefined);
  // 同会话下 turn：P3 resume connect 404（沙箱已 GC）→ 降级 create 重建（对 turn 透明）；
  // 换代提示帧 sandbox_rebuilt（§4.4 T4 / V6⑧ live 修复回归：行被懒剔除+沙箱 GC 的重建
  // 此前漏发帧——connect 404 登记路径已补）
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  expect(t.frames.some(f => f.type === 'sandbox_rebuilt'),
    `缺换代提示帧（connect-404 登记路径）: ${JSON.stringify(t.frames.slice(0, 3))}`).toBe(true);
  const nb = await bindingOf(sid);
  expect(nb?.sandbox_id, '重建后无绑定').toBeTruthy();
  expect(nb!.sandbox_id, '重建复用了已销毁沙箱').not.toBe(old);
});

test('L10 debug 端点：绑定表 + turn_running（双事实源派生）正确', async () => {
  const uid = U();
  const sidBusy = sessionIdFor(`l10a-${uniq()}`);
  const busy = chat({ message: `[E2E:execute:sleep](10)`, userId: uid, sessionId: sidBusy, timeoutMs: 120_000 });
  await sleep(6_000);   // acquire + 首拍续期
  let dbg = await lifecycleDebug();
  expect(dbg.enabled).toBe(true);
  expect(dbg.config).toMatchObject({ user_quota: 2, ttl_minutes: 1, renew_interval_seconds: 3, confirm_hold_minutes: 1 });
  const busyRow = dbg.bindings.find(b => b.session_id === sidBusy);
  expect(busyRow, 'busy 会话绑定行缺失').toBeTruthy();
  expect(busyRow!.turn_running, '执行中 turn_running 应为 true（turn_lease OR updated_at 新鲜）').toBe(true);
  expect(Array.isArray(dbg.events)).toBe(true);
  expect(dbg.registry).toHaveProperty('size');
  await waitTerminal(busy);
  // 终态 + grace(9s) 过后 → idle（双事实源双假）
  await sleep(12_000);
  dbg = await lifecycleDebug();
  const idleRow = dbg.bindings.find(b => b.session_id === sidBusy);
  expect(idleRow!.turn_running, 'turn 结束 + grace 过后仍 busy（grace 判定回归）').toBe(false);
});

test('L11 定时清理：短保留窗口 + 测试钩子触发 → 过期行删 + count 重算 + 空锚行删（重跑幂等）', async () => {
  const uid = U();
  // 注入：1 条过期超保留窗的绑定行（user_key=uid）+ 计数虚高 + 老化锚行
  await withSql(async q => {
    await q(`INSERT INTO sandbox_binding (user_key, session_id, sandbox_id, expires_at, created_at)
             VALUES (?, 'l11-expired', 'sbx-l11', DATE_SUB(NOW(3), INTERVAL 9 DAY), DATE_SUB(NOW(3), INTERVAL 9 DAY))`, [uid]);
    await q('INSERT IGNORE INTO sandbox_user (user_key) VALUES (?)', [uid]);
    await q('UPDATE sandbox_user SET binding_count = 5, updated_at = DATE_SUB(NOW(3), INTERVAL 9 DAY) WHERE user_key = ?', [uid]);
  });
  // 测试钩子：POST /debug/sandbox-cleanup?retentionDays=7（与 SessionCleanupService 同语义；
  // 钩子仅在 lifecycle 组 SANDBOX_LIFECYCLE_E2E_HOOKS=true 时注册——状态码必须先于 json() 断言，
  // 否则 404/400 空体或错误体会被静默当成功解析）
  const call = async () => {
    const res = await fetch(`${BASE}/debug/sandbox-cleanup?retentionDays=7`, { method: 'POST' });
    expect(res.status, 'sandbox-cleanup 测试钩子应已注册（SANDBOX_LIFECYCLE_E2E_HOOKS=true）且返回 200').toBe(200);
    return (await res.json()) as { enabled: boolean; bindings_deleted: number; anchors_deleted: number };
  };
  const body = await call();
  expect(body.enabled).toBe(true);
  expect(body.bindings_deleted, '过期绑定行未删').toBeGreaterThanOrEqual(1);
  await withSql(async q => {
    const rowsN = Number(((await q('SELECT COUNT(*) AS c FROM sandbox_binding WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).c);
    const cnt = Number(((await q('SELECT binding_count FROM sandbox_user WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).binding_count);
    expect(rowsN, '过期行仍在').toBe(0);
    expect(cnt, '计数未重算').toBe(0);
  });
  // 空锚行删除：第一轮的重算 UPDATE 会刷新锚行 updated_at（ON UPDATE）→ 回拨后二次清理验删
  // （与真库 IT cleanupExpiredRecomputesAndPrunesEmptyAnchorsIdempotently 同款时序）
  await withSql(async q => {
    await q(`UPDATE sandbox_user SET updated_at = DATE_SUB(NOW(3), INTERVAL 9 DAY) WHERE user_key = ?`, [uid]);
  });
  const body2 = await call();
  expect(body2.anchors_deleted, '空且老化的锚行未删').toBeGreaterThanOrEqual(1);
  // 重跑幂等（第三轮删 0）
  const body3 = await call();
  expect(body3.bindings_deleted).toBe(0);
  expect(body3.anchors_deleted).toBe(0);
});
