/**
 * e2e 沙箱生命周期·多副本组（sandbox-pool-opensandbox-design §9.3「多副本并发」小节，
 * e2e-multi job 追加项目；双副本共享 MySQL/Redis、nginx 轮询随机路由——L21 多副本变体）。
 *
 * 断言面 = 两副本各自直连地址（REPLICA_A/REPLICA_B，绕过 LB 消除随机性）+ mock /stats
 * + /debug/sandbox-bindings（跨副本聚合，binding 表即跨副本真相）。
 * 覆盖：双副本并发 acquire 同 user（② CAS 串行化，计数==行数）、受害会话 P3 闲置重入
 * 占位 vs 他副本 ⑤c 改绑的同款互斥（cold-R1-1/L21 变体）、双副本并发 ⑤c 只有一方改绑
 * 成功（updated_at 快照乐观校验，终审 d2）。
 *
 * 前置：multi 组 env-up 起双副本（SANDBOX_ENABLED=true + 生命周期开 + QUOTA=2 +
 * RENEW_INTERVAL=3s + grace-floor=6s）；沙箱 mock 同进程池不建（本组不依赖 poolRef）。
 */
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { sessionIdFor } from '../lib/env.js';
import { REPLICA_A, REPLICA_B } from '../lib/env.js';
import { chat } from '../lib/client.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';

const BASE_A = REPLICA_A;
const BASE_B = REPLICA_B;
const SANDBOX_MOCK = process.env.E2E_SANDBOX_MOCK ?? 'http://127.0.0.1:8090';
const U = () => `e2e-lcm-${Math.random().toString(36).slice(2, 8)}`;
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;

interface BindingRow {
  session_id: string;
  sandbox_id: string;
  turn_running: boolean;
}

async function bindings(base: string): Promise<BindingRow[]> {
  const r = await fetch(`${base}/debug/sandbox-bindings`);
  const j = (await r.json()) as { bindings?: BindingRow[] };
  return j.bindings ?? [];
}

/** mysql 连接（L13 式测试钩子的多副本版：直接对账 binding 表不变量） */
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

test('M1 双副本并发 acquire 同 user：② CAS 串行化 + 计数==行数', async () => {
  const uid = U();
  const sidA = sessionIdFor(`m1a-${uniq()}`);
  const sidB = sessionIdFor(`m1b-${uniq()}`);
  // 两副本同时发起（各自直连，绕过 LB）；同 user 不同 session，配额 2 内各自 create
  const [ra, rb] = await Promise.all([
    chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA, base: BASE_A, timeoutMs: 120_000 }),
    chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidB, base: BASE_B, timeoutMs: 120_000 }),
  ]);
  await waitTerminal(ra);
  await waitTerminal(rb);
  expect(ra.terminal?.type).toBe('done');
  expect(rb.terminal?.type).toBe('done');

  // 跨副本对账（binding 表 = 共享真相）：两会话各持独立沙箱，计数==行数==2
  await pollUntil(async () => {
    const rows = await withSql(q => q(
      'SELECT binding_count FROM sandbox_user WHERE user_key = ?', [uid]));
    const count = Number((rows.rows[0] as Record<string, unknown> | undefined)?.binding_count ?? 0);
    const rowCount = await withSql(q => q(
      'SELECT COUNT(*) AS c FROM sandbox_binding WHERE user_key = ?', [uid]));
    const rowsN = Number((rowCount.rows[0] as Record<string, unknown>).c);
    return count === 2 && rowsN === 2;
  }, v => v === true, 30_000, 1_000);
  const all = [...await bindings(BASE_A), ...await bindings(BASE_B)];
  const sa = all.find(b => b.session_id === sidA)?.sandbox_id;
  const sb = all.find(b => b.session_id === sidB)?.sandbox_id;
  expect(sa, 'A 绑定缺失（跨副本不可见）').toBeTruthy();
  expect(sb, 'B 绑定缺失（跨副本不可见）').toBeTruthy();
  expect(sa).not.toBe(sb);   // 并发 acquire 各自 create，无共享归属
});

test('M2 L21 多副本变体：他副本 ⑤c 改绑后，受害会话 P3 闲置重入被归属校验拦截（降级重建，不重连易主沙箱）', async () => {
  const uid = U();
  const sidVictim = sessionIdFor(`m2v-${uniq()}`);
  const sidFill = sessionIdFor(`m2f-${uniq()}`);
  const sidStealer = sessionIdFor(`m2s-${uniq()}`);

  // 1) 受害会话在副本 A 建沙箱（turn 完成 → P3 slot 指向该沙箱、updated_at 随 grace 过期变陈旧）
  const v1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidVictim, base: BASE_A, timeoutMs: 120_000 });
  await waitTerminal(v1);
  expect(v1.terminal?.type).toBe('done');
  // 1.5) 记录 victim 沙箱（改绑后该行 session_id 改写为 stealer，按 victim sid 查将落空——
  // 这正是 steal 生效的签名，必须先取沙箱 id 再触发 ⑤）
  const victimSbx = (await bindings(BASE_A)).find(b => b.session_id === sidVictim)?.sandbox_id;
  expect(victimSbx, 'victim 首轮绑定缺失').toBeTruthy();
  // 2) 第二会话占满配额（QUOTA=2），其 turn 完成后进入空闲
  const f1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidFill, base: BASE_B, timeoutMs: 120_000 });
  await waitTerminal(f1);
  // 3) 等 grace(=3×RENEW=9s) + 缓冲：victim 的 updated_at 陈旧 → 他副本 ⑤c 判 idle 可选
  await new Promise(r => setTimeout(r, 12_000));
  // 4) 副本 B 新会话触发 ⑤ → 改绑 victim 的沙箱（他副本抢先；⑤c 选中 expires 最小的 idle 行=victim）
  const st1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidStealer, base: BASE_B, timeoutMs: 120_000 });
  await waitTerminal(st1);
  expect(st1.terminal?.type).toBe('done');
  const stealerSbx = (await bindings(BASE_A)).find(b => b.session_id === sidStealer)?.sandbox_id;
  expect(stealerSbx, 'stealer 绑定缺失').toBeTruthy();
  // 改绑（stealer 持 victim 沙箱）或新建都合法（victim updated_at 若已被他副本 touch 则 stealer 走 ②）
  const didSteal = stealerSbx === victimSbx;

  // 5) 受害会话在本副本重入（P3 直连易主沙箱）→ 归属校验+占位 0 行 → close + 降级 →
  //    断言绝不 exec 已易主沙箱（终态正常 + 归属换新）
  const v2 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidVictim, base: BASE_A, timeoutMs: 120_000 });
  await waitTerminal(v2);
  expect(v2.terminal?.type, `victim 重入终态异常: ${JSON.stringify(v2.terminal)}`).toBe('done');
  const after = (await bindings(BASE_A)).find(b => b.session_id === sidVictim)?.sandbox_id;
  expect(after, 'victim 重入后绑定缺失（降级 create 脱簿记）').toBeTruthy();
  if (didSteal) {
    expect(after, '受害会话重连易主沙箱（两道防线失效）').not.toBe(victimSbx);
    // 换代提示帧（§4.4 T4 恢复路径）：归属校验拦截 → 降级重建 → 首个事件前提示
    expect(v2.frames.some(f => f.type === 'sandbox_rebuilt'), '缺换代提示帧').toBe(true);
  }
  // 6) 终态一致性：binding 行数 == 计数（同 user 内转移不增减）
  await withSql(async q => {
    const cnt = Number(((await q('SELECT binding_count FROM sandbox_user WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).binding_count);
    const rowsN = Number(((await q('SELECT COUNT(*) AS c FROM sandbox_binding WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).c);
    expect(cnt).toBe(rowsN);
  });
});

test('M3 双副本并发 ⑤c：同 victim 至多一方改绑成功（updated_at 快照乐观校验，终审 d2）', async () => {
  const uid = U();
  // 两个空闲绑定（配额满），updated_at 同步陈旧化 → 两副本各自的 ⑤c 看到同一 victim 集
  const sidA = sessionIdFor(`m3a-${uniq()}`);
  const sidB = sessionIdFor(`m3b-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA, base: BASE_A, timeoutMs: 120_000 }));
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidB, base: BASE_B, timeoutMs: 120_000 }));
  await new Promise(r => setTimeout(r, 12_000));   // grace 过后双双 idle

  const before = new Map((await bindings(BASE_A))
    .filter(b => [sidA, sidB].includes(b.session_id)).map(b => [b.session_id, b.sandbox_id]));
  const beforeSandboxes = new Set(before.values());
  expect(beforeSandboxes.size).toBe(2);

  // 两副本同时触发 ⑤（各自新会话、同 user）：victim 集相同 → 至多一方抢到同一行
  const [r1, r2] = await Promise.all([
    chat({ message: `[E2E:plain]`, userId: uid, sessionId: sessionIdFor(`m3c-${uniq()}`), base: BASE_A, timeoutMs: 120_000 }),
    chat({ message: `[E2E:plain]`, userId: uid, sessionId: sessionIdFor(`m3d-${uniq()}`), base: BASE_B, timeoutMs: 120_000 }),
  ]);
  await waitTerminal(r1);
  await waitTerminal(r2);
  expect(r1.terminal?.type).toBe('done');
  expect(r2.terminal?.type).toBe('done');

  // 终态一致性：uk_sandbox/uk_session 不变量 + 计数==行数 + 无沙箱被两会话同时持有
  const rows = (await bindings(BASE_A)).filter(b =>
    [sidA, sidB].includes(b.session_id) || b.sandbox_id && beforeSandboxes.has(b.sandbox_id));
  const sbxOwners = new Map<string, string[]>();
  for (const b of (await bindings(BASE_A))) {
    sbxOwners.set(b.sandbox_id, [...(sbxOwners.get(b.sandbox_id) ?? []), b.session_id]);
  }
  for (const [sbx, owners] of sbxOwners) {
    expect(owners.length, `沙箱 ${sbx} 被多会话持有: ${owners}`).toBe(1);
  }
  await withSql(async q => {
    const cnt = Number(((await q('SELECT binding_count FROM sandbox_user WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).binding_count);
    const rowsN = Number(((await q('SELECT COUNT(*) AS c FROM sandbox_binding WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).c);
    expect(cnt).toBe(rowsN);
    expect(cnt).toBe(2);   // 两行簿记：⑤c 改绑为同 user 内转移，计数/行数不变
  });
  void rows;
});
