/** 运行环境与用例隔离 ID（e2e-ci-plan §6.3） */
import fs from 'node:fs';
import path from 'node:path';

export const BASE = process.env.E2E_BASE ?? 'http://127.0.0.1:8100';

/**
 * 多副本：精确副本寻址（LB 与副本并存时使用）。
 * 优先 CI 注入的 E2E_REPLICA_A/B；本地 `run.sh multi` 未注入时回落 env-up 产物
 * env.json（env-up 先于 playwright 执行，导入时必然存在）。回落失败保持空串
 * （单副本组不读这两个值；多副本用例会以可读的 fetch 错误暴露，而非静默错路由）。
 * 回落缺失曾致空串地址进 fetch：R2 报"最后观测=[]"（collectStream 吞掉连接异常）、
 * R4 报 "Failed to parse URL from /threads/..."（2026-09-24 本地 multi 轮实录）。
 */
function replicaUrl(envKey: 'E2E_REPLICA_A' | 'E2E_REPLICA_B', runtimeKey: 'replicaA' | 'replicaB'): string {
  const injected = process.env[envKey];
  if (injected) return injected;
  try {
    const dir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
    const envJson = JSON.parse(fs.readFileSync(path.join(dir, 'env.json'), 'utf8')) as Record<string, string>;
    return String(envJson[runtimeKey] ?? '');
  } catch {
    return '';
  }
}
export const REPLICA_A = replicaUrl('E2E_REPLICA_A', 'replicaA');
export const REPLICA_B = replicaUrl('E2E_REPLICA_B', 'replicaB');

/** bench mock MCP（端口随 env-up.sh BENCH_MCP_PORT；用于观测 tools/call 的 header/_meta） */
export const BENCH_MCP = process.env.E2E_BENCH_MCP ?? 'http://127.0.0.1:18082';

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
