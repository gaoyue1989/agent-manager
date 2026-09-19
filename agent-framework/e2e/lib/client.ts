/** agent-framework API 客户端封装（黑盒：只经 HTTP/SSE） */
import { collectStream, type Collected, type Frame } from './sse.js';
import { BASE } from './env.js';

export interface ChatOpts {
  message?: string;
  sessionId?: string;
  userId?: string;
  fileIds?: string[];
  timeoutMs?: number;
  base?: string;
}

export function chat(opts: ChatOpts): Collected {
  const base = opts.base ?? BASE;
  const p = fetch(`${base}/threads/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    signal: AbortSignal.timeout(opts.timeoutMs ?? 150_000),
    body: JSON.stringify({ message: opts.message, sessionId: opts.sessionId, userId: opts.userId, fileIds: opts.fileIds }),
  });
  return collectStream(p, (opts.timeoutMs ?? 150_000));
}

export async function status(sessionId: string, base = BASE): Promise<Record<string, unknown>> {
  const res = await fetch(`${base}/threads/${encodeURIComponent(sessionId)}/status`);
  if (!res.ok) return { _http: res.status };
  return res.json() as Promise<Record<string, unknown>>;
}

/** 游标续传：afterSeq 之后的帧 + 补发终态（Tailer 语义） */
export function subscribe(sessionId: string, afterSeq = 0, base = BASE, replyId?: string): Collected {
  const q = new URLSearchParams({ afterSeq: String(afterSeq) });
  if (replyId) q.set('replyId', replyId);
  const p = fetch(`${base}/threads/${encodeURIComponent(sessionId)}/subscribe?${q}`, { signal: AbortSignal.timeout(120_000) });
  return collectStream(p, 120_000);
}

export async function history(sessionId: string, base = BASE): Promise<Record<string, unknown>> {
  const res = await fetch(`${base}/threads/${encodeURIComponent(sessionId)}/history`);
  if (!res.ok) return { _http: res.status };
  return res.json() as Promise<Record<string, unknown>>;
}

export interface ConfirmResult { tool_call_id: string; confirmed: boolean }

/** 流式确认（HITL 恢复：新执行段重新 acquire 租约） */
export function confirmStream(sessionId: string, results: ConfirmResult[], base = BASE, timeoutMs = 120_000): Collected {
  const p = fetch(`${base}/threads/${encodeURIComponent(sessionId)}/confirm-stream`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    signal: AbortSignal.timeout(timeoutMs),
    body: JSON.stringify({ results }),
  });
  return collectStream(p, timeoutMs);
}

/** 同步确认（非流式；租约保护下恢复） */
export async function confirmSync(sessionId: string, results: ConfirmResult[], base = BASE): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(`${base}/threads/${encodeURIComponent(sessionId)}/confirm`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    signal: AbortSignal.timeout(120_000),
    body: JSON.stringify({ results }),
  });
  let body: Record<string, unknown> = {};
  try { body = await res.json(); } catch { /* 空体 */ }
  return { status: res.status, body };
}

export async function threads(base = BASE): Promise<unknown[]> {
  const res = await fetch(`${base}/threads`);
  const j = await res.json();
  return Array.isArray(j) ? j : (j.threads ?? j.items ?? []);
}

export async function deleteThread(sessionId: string, base = BASE): Promise<number> {
  const res = await fetch(`${base}/threads/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
  return res.status;
}

export async function patchThread(sessionId: string, body: Record<string, unknown>, base = BASE): Promise<Response> {
  return fetch(`${base}/threads/${encodeURIComponent(sessionId)}`, {
    method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  });
}

/** A2A JSON-RPC（全量透传 SDK） */
export async function a2a(method: string, params: Record<string, unknown>, base = BASE): Promise<{ status: number; json: Record<string, unknown> }> {
  const res = await fetch(`${base}/`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  return { status: res.status, json: (await res.json()) as Record<string, unknown> };
}

/** mock LLM 的调用记录（断言侧通道） */
export async function llmStats(base = BASE): Promise<{ count: number; calls: Array<Record<string, unknown>> }> {
  const url = new URL(base);
  const mockBase = process.env.E2E_LLM_MOCK ?? `http://${url.hostname}:18081`;
  const res = await fetch(`${mockBase}/stats`);
  return res.json();
}

export async function llmReset(): Promise<void> {
  const url = new URL(BASE);
  await fetch(`${process.env.E2E_LLM_MOCK ?? `http://${url.hostname}:18081`}/reset`, { method: 'POST' });
}

/** approval MCP 状态：经卡片代理创建申请单（测试预置数据），返回 application_id */
export async function createApprovalApp(base = BASE, title = 'E2E 审批申请'): Promise<string> {
  const res = await fetch(`${base}/mcp/approval/tools/create_application`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ arguments: { title, description: 'e2e 测试申请' }, userId: 'e2e-tester' }),
  });
  if (!res.ok) throw new Error(`create_application http ${res.status}`);
  const m = JSON.stringify(await res.json()).match(/APP-[A-Za-z0-9_-]+/);
  if (!m) throw new Error('create_application 响应无 application_id');
  return m[0];
}
