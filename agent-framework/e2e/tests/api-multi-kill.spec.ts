/**
 * e2e-multi kill 组：R4 kill 执行副本 → 接管 interrupted。
 * 独立 project 且排在最后——R4 会 SIGKILL 副本 A，不能影响其它用例的路由。
 */
import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import { REPLICA_A, REPLICA_B, sessionIdFor } from '../lib/env.js';
import { chat, status, subscribe } from '../lib/client.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';
import { type Frame } from '../lib/sse.js';

const U = 'e2e-multi';
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;

test('R4 kill 执行副本 → 接管 interrupted', async () => {
  // hang 场景下 SDK 在模型流停滞前不产出任何事件（Redis 无记录，B 无法判定 interrupted）——
  // 改用长流 slow（总时长 ~18s > 租约 TTL 15s）：kill 后租约到期，B 侧 latest 事件非终态 → interrupted
  const runtimeDir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
  const pid = Number(fs.readFileSync(`${runtimeDir}/agent-a.pid`, 'utf8').trim());
  process.kill(pid, 0); // 进程不存在则快速失败（stale pid file）
  const sid = sessionIdFor(`r4-${uniq()}`);
  const slow = chat({ message: `[E2E:slow](x,1500,12)`, userId: U, sessionId: sid, base: REPLICA_A, timeoutMs: 30_000 });
  await pollUntil(async () => status(sid, REPLICA_B), s => s.state === 'working', 30_000);
  process.kill(pid, 'SIGKILL');
  const st = await pollUntil(async () => status(sid, REPLICA_B), s => s.state === 'interrupted', 35_000, 1000);
  expect(st.state).toBe('interrupted');
  slow.abort();
  // 不死锁：同会话可正常发起新 turn
  const next = chat({ message: `[E2E:plain]`, userId: U, sessionId: sid, base: REPLICA_B });
  await waitTerminal(next);
  expect(next.terminal?.type).toBe('done');
});
