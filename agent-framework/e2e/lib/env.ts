/** 运行环境与用例隔离 ID（e2e-ci-plan §6.3） */
export const BASE = process.env.E2E_BASE ?? 'http://127.0.0.1:8100';
/** 多副本：精确副本寻址（LB 与副本并存时使用） */
export const REPLICA_A = process.env.E2E_REPLICA_A ?? '';
export const REPLICA_B = process.env.E2E_REPLICA_B ?? '';

const RUN_ID = process.env.E2E_RUN_ID
  ?? `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

/** 运行级唯一 ID：e2e-<runId>-<case>（防 HITL 暂停态/会话串场） */
export const ids = (c: string) => `e2e-${RUN_ID}-${c}`;
export const runId = RUN_ID;

/** sessionId：'tenant_thread' 形态（UiContextInjectionHook 接受 tenant:thread / tenant_thread；
 *  下划线经 PathSafe sanitize 不变形，/threads 列表与会话键保持一致） */
export const sessionIdFor = (c: string) => `e2e_${ids(c)}`;

let tenantPrefixCache: string | null = null;
/** 从 /health 取 tenant_prefix（debug 页同源信息），供需要真实租户前缀的断言 */
export async function tenantPrefix(fetchImpl: typeof fetch): Promise<string> {
  if (tenantPrefixCache) return tenantPrefixCache;
  const h = await (await fetchImpl(`${BASE}/health`)).json();
  tenantPrefixCache = String(h.tenant_prefix ?? 'e2e');
  return tenantPrefixCache;
}
