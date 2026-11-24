import { expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { BASE } from './env.js';

export const SANDBOX_MOCK = process.env.E2E_SANDBOX_MOCK ?? 'http://127.0.0.1:8090';
export const U = () => `e2e-lc-${Math.random().toString(36).slice(2, 8)}`;
let uniqueSeq = 0;
export const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;
export const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

// ---- mock 观测/管理通道（§9.2 扩展面）----

export interface CreateRecord {
  id: string;
  body: {
    extensions: { poolRef?: string } | null;
    metadata: Record<string, string> | null;
    timeout: number | null;
    image: unknown;
    resource: unknown;
  };
}
export interface MockStats {
  creates: CreateRecord[];
  renews: Array<{ sandboxId: string; expiresAt: string; at: number }>;
  commands: Array<{ sandboxId: string; cmd: string; exitCode?: number }>;
  faultHits: number;
}

export async function mockStats(): Promise<MockStats> {
  return (await (await fetch(`${SANDBOX_MOCK}/stats`)).json()) as MockStats;
}

export async function mockReset(): Promise<void> {
  await fetch(`${SANDBOX_MOCK}/reset`, { method: 'POST' });
}

export async function mockFault(faults: Record<string, unknown>): Promise<void> {
  await fetch(`${SANDBOX_MOCK}/admin/fault`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(faults) });
}

export async function createPool(name: string): Promise<Response> {
  return fetch(`${SANDBOX_MOCK}/v1/pools`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name, capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3 } }),
  });
}

export async function destroySandbox(id: string): Promise<void> {
  await fetch(`${SANDBOX_MOCK}/admin/destroy/${id}`, { method: 'POST' });
}

/** 向指定沙箱工作区植入文件（黑盒走 mock 的 execd files/upload 通道，L19/L18 用） */
export async function plantFile(sandboxId: string, path: string, content: string): Promise<void> {
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
export async function probeFile(sandboxId: string, path: string): Promise<{ status: number; text: string }> {
  const res = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${sandboxId}/proxy/44772/files/download?path=${encodeURIComponent(path)}`);
  return { status: res.status, text: await res.text() };
}

// ---- 调试端点与 DB 钩子 ----

export interface BindingRow { session_id: string; sandbox_id: string; turn_running: boolean }
export interface LifecycleDebug {
  enabled: boolean;
  config: Record<string, unknown>;
  bindings: BindingRow[];
  registry: { size: number; entries: Array<Record<string, unknown>> };
  events: Array<{ type: string; sandbox_id: string; detail: string }>;
}

export async function lifecycleDebug(base = BASE): Promise<LifecycleDebug> {
  return (await (await fetch(`${base}/debug/sandbox-bindings`)).json()) as LifecycleDebug;
}

export const bindingOf = async (sid: string, base = BASE): Promise<BindingRow | undefined> =>
  (await lifecycleDebug(base)).bindings.find(b => b.session_id === sid);

/** renews 计数（按沙箱过滤） */
export const renewCount = async (sandboxId: string): Promise<number> =>
  (await mockStats()).renews.filter(x => x.sandboxId === sandboxId).length;

/** MySQL 直连（L13/L20/L11 测试钩子：PENDING 不变量/死绑定/清理的注入与对账面） */
export async function withSql<T>(fn: (q: (sql: string, params?: unknown[]) => Promise<{ rows: unknown[]; affected: number }>) => Promise<T>): Promise<T> {
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
export async function rawChat(body: Record<string, unknown>, timeoutMs = 120_000): Promise<Response> {
  return fetch(`${BASE}/threads/chat`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    signal: AbortSignal.timeout(timeoutMs),
    body: JSON.stringify(body),
  });
}

/** 采集流中的 user_quota_exhausted 结构化帧（§4.6 SSE 面） */
export async function collectQuotaFrame(res: Response): Promise<Record<string, unknown> | null> {
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