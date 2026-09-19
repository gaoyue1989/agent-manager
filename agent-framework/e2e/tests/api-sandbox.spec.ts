/**
 * e2e-sandbox API 组：X 沙箱（mock OpenSandbox，e2e-ci-plan §5.6）。
 * 断言面 = agent 行为（TOOL_RESULT/turn 终态）+ mock /stats（create/connect/命令/文件操作流水）。
 */
import { test, expect } from '@playwright/test';
import { BASE, sessionIdFor } from '../lib/env.js';
import { chat, history } from '../lib/client.js';
import { waitTerminal, textOf, toolNames, toolResults } from '../lib/matchers.js';
import { upload, textBytes } from '../lib/files.js';

const U = () => `e2e-sbx-${Math.random().toString(36).slice(2, 8)}`;
const SANDBOX_MOCK = process.env.E2E_SANDBOX_MOCK ?? 'http://127.0.0.1:8090';
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;

interface SandboxStats {
  creates: Array<{ id: string }>;
  connects: Array<Record<string, unknown>>;
  commands: Array<{ sandboxId: string; cmd: string; exitCode?: number; whitelisted?: boolean }>;
  fileOps: Array<Record<string, unknown>>;
  errors: Array<Record<string, unknown>>;
}

async function sandboxStats(): Promise<SandboxStats> {
  const r = await fetch(`${SANDBOX_MOCK}/stats`);
  return r.json() as Promise<SandboxStats>;
}

test('X1 沙箱模式启动与首个 create', async () => {
  const sid = sessionIdFor(`x1-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: U(), sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done'); // 非沙箱功能不回归
  const stats = await sandboxStats();
  expect(stats.creates.length).toBeGreaterThanOrEqual(1); // 沙箱已按需创建
});

test('X2 Shell 执行（受控白名单）', async () => {
  const uid = U();
  const sid = sessionIdFor(`x2-${uniq()}`);
  const stream = chat({ message: `[E2E:execute]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(toolNames(stream.frames)).toContain('execute');
  expect(textOf(stream.frames)).toContain('hello-e2a');
  const results = toolResults(stream.frames);
  expect(String((results[0] as Record<string, unknown>).state ?? '')).toBe('SUCCESS');
  const stats = await sandboxStats();
  const cmd = stats.commands.filter(c => c.cmd.includes('echo'));
  expect(cmd.some(c => c.exitCode === 0)).toBe(true);
});

// 框架语义缺陷（与 F5/X9 同类）：回放的 write_file tool_call 不触发沙箱文件写入
// （无 files/upload 流量、无 TOOL_RESULT 帧），跨会话读随之失败。真实沙箱档由集群 e2e 覆盖。
test.fixme('X3 沙箱文件写读 + 跨会话（USER 复用）', async () => {
  const uid = U();
  const sidA = sessionIdFor(`x3a-${uniq()}`);
  const w = chat({ message: `[E2E:tool:write:sb]`, userId: uid, sessionId: sidA });
  await waitTerminal(w);
  expect(w.terminal?.type).toBe('done');
  // KV 回写是 turn 后异步动作，新会话 hydrate 可能竞态——轮询最多 3 个新会话
  let found = '';
  for (let i = 0; i < 3 && !found; i++) {
    await new Promise(r => setTimeout(r, 2000)); // KV 回写竞态缓冲
    const sidB = sessionIdFor(`x3b-${uniq()}`);
    const r = chat({ message: `[E2E:tool:read:sb]`, userId: uid, sessionId: sidB });
    await waitTerminal(r);
    if (r.terminal?.type !== 'done') continue; // 错误 turn（排队/竞态）不计入
    const text = textOf(r.frames);
    if (text.includes('沙箱落盘验证')) found = text;
  }
  expect(found, '新会话未能读回写入内容（工作区回灌失效）').toContain('沙箱落盘验证');
});

test('X4 USER 级工作区持久化与跨用户隔离', async () => {
  // 该 SDK 版本沙箱按会话创建（与早期设计的 USER 级复用不同），
  // 真正的 USER 语义落在 KV 工作区持久化：同 user 跨会话可见（X3 已验），跨 user 不可见（此处验证）
  const uidA = U();
  const uidB = U();
  const sa = sessionIdFor(`x4a-${uniq()}`);
  const w = chat({ message: `[E2E:tool:write:sb]`, userId: uidA, sessionId: sa });
  await waitTerminal(w);
  expect(w.terminal?.type).toBe('done');
  // user B（独立用户）读 user A 写的文件 → 不可见
  const sb = sessionIdFor(`x4b-${uniq()}`);
  const r = chat({ message: `[E2E:tool:read:sb]`, userId: uidB, sessionId: sb });
  await waitTerminal(r);
  expect(textOf(r.frames)).not.toContain('沙箱落盘验证');
  expect(r.terminal?.type).toBe('done');
});

test('X6 容器 GC → connect 404 降级 create', async () => {
  const uid = U();
  const sidA = sessionIdFor(`x6a-${uniq()}`);
  const w = chat({ message: `[E2E:tool:write:sb]`, userId: uid, sessionId: sidA });
  await waitTerminal(w);
  const stats1 = await sandboxStats();
  const lastCreate = stats1.creates[stats1.creates.length - 1];
  // 模拟容器 GC
  const destroy = await fetch(`${SANDBOX_MOCK}/admin/destroy/${lastCreate.id}`, { method: 'POST' });
  expect(destroy.status).toBe(200);
  // 同 USER 再对话：resume 404 → 框架降级 create 新沙箱，turn 正常完成
  const sidB = sessionIdFor(`x6b-${uniq()}`);
  const r = chat({ message: `[E2E:tool:read:sb]`, userId: uid, sessionId: sidB });
  await waitTerminal(r);
  expect(r.terminal?.type).toBe('done');
  const stats2 = await sandboxStats();
  expect(stats2.creates.length).toBeGreaterThan(stats1.creates.length);
  expect(stats2.creates[stats2.creates.length - 1].id).not.toBe(lastCreate.id);
});

test('X8 pending 上限与用户隔离（沙箱挂账态专属）', async ({ request }) => {
  // 沙箱模式上传 status=pending 挂账，countPending 限流（FILE_UPLOAD_MAX_PENDING=20）才生效
  const uidA = `e2e-sbx-pend-a-${uniq()}`;
  const uidB = `e2e-sbx-pend-b-${uniq()}`;
  for (let i = 0; i < 20; i++) {
    const r = await upload(BASE, textBytes('x'), `a${i}.txt`, 'text/plain', { userId: uidA });
    expect(r.status, `第 ${i + 1} 个`).toBe(200);
  }
  const full = await upload(BASE, textBytes('x'), 'a20.txt', 'text/plain', { userId: uidA });
  expect(full.status).toBe(429);
  expect((full.body as Record<string, string>).error).toBe('too_many_pending_files');
  // user B 不受 A 的 pending 影响（userKey 维度隔离）
  const ok = await upload(BASE, textBytes('x'), 'b.txt', 'text/plain', { userId: uidB });
  expect(ok.status).toBe(200);
  // X-User-Id header 优先于 body userId：A 已满 + header=B → 按 B 放行
  const fd = new FormData();
  fd.append('file', new Blob([new Uint8Array(textBytes('x'))], { type: 'text/plain' }), 'h.txt');
  fd.append('userId', uidA);
  const res = await fetch(`${BASE}/files/upload`, { method: 'POST', body: fd, headers: { 'X-User-Id': uidB } });
  expect(res.status).toBe(200);
});

// 与 F5 同类：回放的 write_file 与 KV/工作区持久化状态冲突（already exists），
// present_file 需要的文件语义在回放世界不稳定——待框架侧明确 write/present 幂等语义后启用
test.fixme('X9 文件交付全链路（write→present→file_ready→下载）', async () => {
  const uid = U();
  const sid = sessionIdFor(`x9-${uniq()}`);
  const stream = chat({ message: `[E2E:file:deliver](report.md)`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const ready = stream.frames.find(f => f.type === 'file_ready') as Record<string, unknown> | undefined;
  expect(ready, '缺少 file_ready 帧').toBeTruthy();
  expect(ready!.file_name).toBe('report.md');
  const dl = await fetch(`${BASE}${ready!.download_url}`);
  expect(dl.status).toBe(200);
  const bytes = Buffer.from(await dl.arrayBuffer());
  expect(bytes.length).toBeGreaterThan(0);
  expect(dl.headers.get('content-disposition')).toContain('attachment');
});

test('X7 命令失败传播（exit code 经文本返回）', async () => {
  const sid = sessionIdFor(`x7-${uniq()}`);
  const stream = chat({ message: `[E2E:execute:fail]`, userId: U(), sessionId: sid });
  await waitTerminal(stream);
  expect(toolNames(stream.frames)).toContain('execute');
  const results = toolResults(stream.frames);
  expect(String((results[0] as Record<string, unknown>).state ?? '')).toBe('SUCCESS'); // 工具执行完成 ≠ 命令成功
  expect(textOf(stream.frames)).toContain('nonexistent-e2a-dir'); // stderr 可见
  expect(stream.terminal?.type).toBe('done'); // 单命令失败不炸流
  const h = await history(sid);
  const tcs = (h.messages as Array<Record<string, unknown>>).flatMap(m => (m.tool_calls ?? []) as Array<Record<string, unknown>>);
  const exe = tcs.find(t => t.name === 'execute');
  expect(String(exe?.output)).toContain('Exit code: 2');
});
