/**
 * e2e-multi kill 组：R4 kill 执行副本 → 接管 interrupted。
 * 独立 project 且排在最后——R4 会 SIGKILL 副本 A，不能影响其它用例的路由。
 */
import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { REPLICA_A, REPLICA_B, sessionIdFor } from '../lib/env.js';
import { chat, status } from '../lib/client.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';

const U = 'e2e-multi';
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;

const runtimeDir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
const PID_FILE = path.join(runtimeDir, 'agent-a.pid');
const START_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), '../scripts/start-agent.sh');

/** 读 pid 文件并探测进程存活；文件缺失/损坏/进程已死一律返回 null */
function livePid(): number | null {
  try {
    const pid = Number(fs.readFileSync(PID_FILE, 'utf8').trim());
    if (!Number.isInteger(pid) || pid <= 0) return null;
    process.kill(pid, 0);
    return pid;
  } catch {
    return null;
  }
}

/** 副本 A 端口：优先 E2E_REPLICA_A（CI job 注入），回落 .runtime/env.json（env-up 产物） */
function replicaAPort(): string {
  if (REPLICA_A) return new URL(REPLICA_A).port || '8101';
  try {
    const envJson = JSON.parse(fs.readFileSync(path.join(runtimeDir, 'env.json'), 'utf8')) as { replicaA?: string };
    return new URL(String(envJson.replicaA)).port || '8101';
  } catch {
    return String(Number(new URL(process.env.E2E_BASE ?? 'http://127.0.0.1:8100').port) + 1);
  }
}

/** retry 自愈：上次尝试已 SIGKILL 副本 A、pid 文件失效——按 env-up 同一启动语义重建并刷新 pid */
function restoreReplicaA(): number {
  const port = replicaAPort();
  const r = spawnSync('bash', [START_SCRIPT, 'a', port], { encoding: 'utf8', timeout: 120_000 });
  const pid = livePid();
  if (pid === null) {
    throw new Error(
      `retry 重建副本 A 失败（start-agent.sh exit=${r.status}${r.error ? `，error=${r.error.message}` : ''}）\n` +
      `stdout: ${(r.stdout ?? '').slice(-400)}\nstderr: ${(r.stderr ?? '').slice(-800)}`,
    );
  }
  return pid;
}

test('R4 kill 执行副本 → 接管 interrupted', async ({}, testInfo) => {
  // hang 场景下 SDK 在模型流停滞前不产出任何事件（Redis 无记录，B 无法判定 interrupted）——
  // 改用长流 slow（总时长 ~18s > 租约 TTL 15s）：kill 后租约到期，B 侧 latest 事件非终态 → interrupted
  let pid = livePid();
  if (pid === null) {
    if (testInfo.retry === 0) {
      throw new Error('agent-a 不存活（env 未就绪或 pid 文件损坏）；仅 retry 具备重建自愈条件，首次失败应如实暴露');
    }
    pid = restoreReplicaA();
  }
  const sid = sessionIdFor(`r4-${uniq()}`);
  const slow = chat({ message: `[E2E:slow](x,1500,12)`, userId: U, sessionId: sid, base: REPLICA_A, timeoutMs: 30_000 });
  await pollUntil(async () => status(sid, REPLICA_B), s => s.state === 'working', 30_000);
  process.kill(pid, 'SIGKILL');
  // 接管判定链：kill → 租约到期（TTL 15s/续期 5s，最坏 kill+15s）→ B 侧 tail 判定 interrupted；
  // 预算放宽到 60s 吸收慢节点（用例总超时 180s 足够），超时则带双侧证据抛出（见 catch）
  let st: Record<string, unknown>;
  try {
    st = await pollUntil(async () => status(sid, REPLICA_B), s => s.state === 'interrupted', 60_000, 1000);
  } catch (e) {
    const [aStatus, bStatus] = await Promise.all([status(sid, REPLICA_A), status(sid, REPLICA_B)]);
    throw new Error(
      `接管超时证据：pidAlive(A)=${livePid() !== null} status(A)=${JSON.stringify(aStatus)} ` +
      `status(B)=${JSON.stringify(bStatus)} 原始错误=${(e as Error).message}`,
    );
  }
  expect(st.state).toBe('interrupted');
  slow.abort();
  // 不死锁：同会话可正常发起新 turn
  const next = chat({ message: `[E2E:plain]`, userId: U, sessionId: sid, base: REPLICA_B });
  await waitTerminal(next);
  expect(next.terminal?.type).toBe('done');
});
