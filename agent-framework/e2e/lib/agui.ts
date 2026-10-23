/** AG-UI 面客户端封装（agui-native-parity-design §12.4 UA 组 API 级载体） */
import { collectStream, type Collected } from './sse.js';
import { BASE } from './env.js';
import { userHeaders } from './client.js';

/** AG-UI run 请求体（官方 RunAgentInput 投影） */
export interface AguiRunOpts {
  threadId?: string;
  runId?: string;
  message?: string;
  resume?: Array<{ interruptId: string; status: string; payload?: Record<string, unknown> }>;
  userId?: string;
  timeoutMs?: number;
  base?: string;
}

/** POST /agui/runs → SSE AguiEvent 流（id: 行 = 信封 seq） */
export function aguiRun(opts: AguiRunOpts): Collected {
  const base = opts.base ?? BASE;
  const p = fetch(`${base}/agui/runs`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...userHeaders(opts.userId) },
    signal: AbortSignal.timeout(opts.timeoutMs ?? 60_000),
    body: JSON.stringify({
      threadId: opts.threadId,
      runId: opts.runId,
      messages: opts.message != null
        ? [{ id: 'm-e2e', role: 'user', content: opts.message }]
        : [],
      resume: opts.resume,
    }),
  });
  return collectStream(p, opts.timeoutMs ?? 60_000);
}

/** GET /agui/threads/{sid}/events?afterSeq= → 平台扩展续传端点（pa 拆帧 + 降级） */
export function aguiEvents(sessionId: string, afterSeq = 0, runId?: string, userId?: string, base = BASE): Collected {
  const q = new URLSearchParams({ afterSeq: String(afterSeq) });
  if (runId) q.set('runId', runId);
  const p = fetch(`${base}/agui/threads/${encodeURIComponent(sessionId)}/events?${q}`,
    { signal: AbortSignal.timeout(120_000), headers: userHeaders(userId) });
  return collectStream(p, 120_000);
}

/** RUN_FINISHED outcome.interrupts 提取 */
export function interruptsOf(frame: Record<string, unknown> | undefined): Array<Record<string, unknown>> {
  if (!frame) return [];
  const outcome = frame.outcome as Record<string, unknown> | undefined;
  return (outcome?.interrupts as Array<Record<string, unknown>>) ?? [];
}

/** AG-UI 流终态：RUN_FINISHED / RUN_ERROR / permission_ask（共享 waitTerminal 的
 * TERMINAL_TYPES 不含 RUN_*，AG 面用本助手；流关闭后按帧类型判定） */
export async function waitAguiTerminal(stream: Collected): Promise<Record<string, unknown> | undefined> {
  await stream.closed;
  const t = stream.frames.find(f => ['RUN_FINISHED', 'RUN_ERROR', 'permission_ask'].includes(f.type));
  if (!t) throw new Error('AG-UI 流未收敛到终态（RUN_FINISHED/RUN_ERROR）');
  return t;
}
