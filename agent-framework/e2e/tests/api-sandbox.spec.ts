/**
 * e2e-sandbox API 组：X 沙箱（mock OpenSandbox，e2e-ci-plan §5.6）。
 * 断言面 = agent 行为（TOOL_RESULT/turn 终态）+ mock /stats（create/connect/命令/文件操作流水）。
 */
import { test, expect } from '@playwright/test';
import { BASE, sessionIdFor } from '../lib/env.js';
import { chat, history, a2a } from '../lib/client.js';
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

test('X9 文件交付全链路（write→present→file_ready→下载）', async () => {
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

// ---------- X 组：沙箱档用户技能（管理面 PUT/DELETE → 容器物化 → syncBack 回写仲裁） ----------
// 覆盖缺口：api-sandbox.spec.ts 此前 grep skill 零命中——物化（管理面写入下一 turn 投影）
// 与回写（容器→KV，tombstone/admin-override 仲裁）三段链路均无行为级覆盖。
// 观测通道：mock OpenSandbox /stats fileOps 记录 /files/upload 流水，SDK 写容器走同一路由。

test('X10 管理面 PUT 用户技能 → 下一 turn 物化进容器（mock fileOps 证据）', async ({ request }) => {
  const uid = U();
  const skill = `e2e-skill-${uniq()}`; // 全程唯一，避免与 L2/.skills-cache 路径串扰
  const marker = `MAT-${uniq()}`;
  const content = `---\nname: ${skill}\ndescription: e2e materialize probe\nversion: 1.0.0\n---\n\n# ${skill}\n\n${marker}\n`;

  // 基线：新用户无 L4、无 tombstone
  const empty = await request.get(`/skills/users/${uid}`);
  expect(empty.status()).toBe(200);
  const emptyBody = await empty.json();
  expect(emptyBody.skills).toEqual([]);
  expect(emptyBody.tombstones).toEqual([]);

  // 管理面 PUT（KV 权威写 + admin-override 栅栏）
  const put = await request.put(`/skills/users/${uid}/${skill}`, { data: { content } });
  expect(put.status()).toBe(200);
  const putBody = await put.json();
  expect(putBody.action).toBe('created');
  expect(Number(putBody.version)).toBeGreaterThan(0);
  expect(String(putBody.message)).toContain('管理面写入栅栏'); // 沙箱档提示契约

  // KV 视图：source=user / adminOverride=true
  const detail = await request.get(`/skills/users/${uid}/${skill}`);
  expect(detail.status()).toBe(200);
  const detailBody = await detail.json();
  expect(detailBody.source).toBe('user');
  expect(detailBody.hasUserOverride).toBe(true);
  expect(String(detailBody.content)).toContain(marker);
  const list = await (await request.get(`/skills/users/${uid}`)).json();
  const entry = (list.skills as Array<Record<string, unknown>>).find(s => s.name === skill);
  expect(entry).toBeTruthy();
  expect(entry!.adminOverride).toBe(true);

  // 下一 turn 物化：acquire 后 SandboxUserKeyMiddleware 把 L4 投影进容器 /workspace/skills。
  // 观测通道：物化走「staging(/tmp/workspace.tar.b64) 上传 + tar 解包」管道，fileOps 只有 staging
  // 路径——改用与真实协议同源的 files/download 探针直证容器内文件（新 uid 首 turn 必产生新 create）
  const createsBefore = (await sandboxStats()).creates.length;
  const sid = sessionIdFor(`x10-${uniq()}`);
  // turn 走 A2A：metadata.userId 会透传进 RuntimeContext，沙箱 userKey=真实用户，
  // 会话开始物化（onAgent 主路径）才按用户 KV 生效（Channel 链路 userKey 退化问题
  // 已由 issue #52 修复，Channel 侧回归见 X15）
  const a2aRes = await a2a('message/send', {
    message: {
      kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
      parts: [{ kind: 'text', text: `[E2E:plain]` }],
      metadata: { userId: uid, sessionId: sid },
    },
  });
  expect(a2aRes.status).toBe(200);
  expect(a2aRes.json.error).toBeUndefined();
  const creates = (await sandboxStats()).creates;
  expect(creates.length, '新用户首 turn 未创建沙箱').toBe(createsBefore + 1);
  const probe = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${creates[createsBefore].id}/proxy/44772/files/download?path=${encodeURIComponent(`/workspace/skills/${skill}/SKILL.md`)}`);
  expect(probe.status, '容器内 /workspace/skills/{skill}/SKILL.md 不可下载（L4 未物化）').toBe(200);
  expect(await probe.text()).toContain(marker);

  // 负例（无 LLM，MOD 组风格）
  const emptyPut = await request.put(`/skills/users/${uid}/x10-neg`, { data: { content: '  ' } });
  expect(emptyPut.status()).toBe(400);
  expect((await emptyPut.json()).error).toBe('empty_content');
  const badName = await request.put(`/skills/users/${uid}/a..b`, { data: { content } });
  expect(badName.status()).toBe(400);
  expect((await badName.json()).error).toBe('invalid_name');
  const big = await request.put(`/skills/users/${uid}/x10-neg`, { data: { content: 'x'.repeat(100 * 1024 + 1) } });
  expect(big.status()).toBe(413);
  expect((await big.json()).error).toBe('content_too_large');
});

test('X11 管理面 DELETE → tombstone 防复活（同会话同代容器 syncBack 仲裁）+ 物化不再投影', async ({ request }) => {
  const uid = U();
  const skill = `e2e-skill-${uniq()}`;
  const content = `---\nname: ${skill}\ndescription: e2e tombstone probe\nversion: 1.0.0\n---\n\n# ${skill}\n`;

  const putMarker = `TOMB-${uniq()}`;
  const put = await request.put(`/skills/users/${uid}/${skill}`, { data: { content: `${content}
${putMarker}
` } });
  expect(put.status()).toBe(200);

  // turn1：物化基线（容器内出现该技能副本，经 files/download 探针直证——物化走 staging tar 管道，
  // fileOps 只记 staging 路径，见 X10 注释）
  const createsBefore = (await sandboxStats()).creates.length;
  const sidA = sessionIdFor(`x11-${uniq()}`); // turn2 复用同一 sid：同会话跨 call resume 同代容器
  // A2A turn（同 X10：metadata.userId 透传，物化主路径按真实用户生效）
  const w = await a2a('message/send', {
    message: {
      kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
      parts: [{ kind: 'text', text: `[E2E:plain]` }],
      metadata: { userId: uid, sessionId: sidA },
    },
  });
  expect(w.status).toBe(200);
  expect(w.json.error).toBeUndefined();
  const creates = (await sandboxStats()).creates;
  expect(creates.length).toBeGreaterThanOrEqual(createsBefore + 1);
  const downloadProbe = () => fetch(`${SANDBOX_MOCK}/v1/sandboxes/${creates[createsBefore].id}/proxy/44772/files/download?path=${encodeURIComponent(`/workspace/skills/${skill}/SKILL.md`)}`);
  expect((await downloadProbe()).status, 'turn1 后容器内技能副本缺失（未物化）').toBe(200);

  // 管理面 DELETE：写 tombstone
  const del = await request.delete(`/skills/users/${uid}/${skill}`);
  expect(del.status()).toBe(200);
  const delBody = await del.json();
  expect(delBody.deletedFiles).toBe(1); // PUT 只写 SKILL.md，.deleted 标记不计入（listSkillFiles 排除 . 元数据段）
  expect(delBody.tombstone.name).toBe(skill);
  expect(String(delBody.tombstone.clearHint)).toContain('sync-from-package');
  expect(String(delBody.message)).toContain('不会被回写落库');

  // KV 面立即可见：技能消失、tombstones 列出、明细 404（无包内基线）
  const names = (j: { skills: Array<Record<string, unknown>>; tombstones: Array<Record<string, unknown>> }) => ({
    skills: j.skills.map(s => String(s.name)),
    tombs: j.tombstones.map(t => String(t.name)),
  });
  const after = names(await (await request.get(`/skills/users/${uid}`)).json());
  expect(after.skills).not.toContain(skill);
  expect(after.tombs).toContain(skill);
  const gone = await request.get(`/skills/users/${uid}/${skill}`);
  expect(gone.status()).toBe(404);
  expect((await gone.json()).error).toBe('not_found');

  // turn2：复用 sidA（同会话同代容器）——materialize 跳过已删技能，stop() syncBack 面对容器内存活副本：
  // tombstone 闸门（WorkspaceSyncService syncOneSkill）是 KV 不复活的唯一防线，坏实现会在此变红
  const r = await a2a('message/send', {
    message: {
      kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
      parts: [{ kind: 'text', text: `[E2E:plain]` }],
      metadata: { userId: uid, sessionId: sidA },
    },
  });
  expect(r.status).toBe(200);
  expect(r.json.error).toBeUndefined();
  // 同代容器副本仍在（未被重物化覆盖/未删除），且 KV 不复活
  const probe2 = await downloadProbe();
  expect(probe2.status, '同代容器内存活副本丢失').toBe(200);
  expect(await probe2.text()).toContain(putMarker);
  const final = names(await (await request.get(`/skills/users/${uid}`)).json());
  expect(final.skills).not.toContain(skill);
  expect(final.tombs).toContain(skill);
});

test('X15 Channel 链路（/threads/chat）per-user 技能物化（issue #52 回归）', async ({ request }) => {
  // 修复前：Channel 链路 ctx.userId 是网关 peer（=前端 sessionId）、ctx.sessionId 是共享
  // gw-hash，SandboxUserKeyMiddleware 原样取 ctx 值 → 物化按 peer 查 KV 恒 0 条，per-user
  // 技能"静默丢失"。修复后经 session_user 反查真实用户（与 MCP McpMeta 注入同源）。
  const uid = U();
  const skill = `e2e-skill-${uniq()}`;
  const marker = `CHN-${uniq()}`;
  const content = `---\nname: ${skill}\ndescription: e2e channel materialize probe\nversion: 1.0.0\n---\n\n# ${skill}\n\n${marker}\n`;

  const put = await request.put(`/skills/users/${uid}/${skill}`, { data: { content } });
  expect(put.status()).toBe(200);

  // turn 走 Channel chat（非 A2A）：物化探针同 X10（files/download 直证容器内文件）
  const createsBefore = (await sandboxStats()).creates.length;
  const sid = sessionIdFor(`x15-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');

  const creates = (await sandboxStats()).creates;
  expect(creates.length, '新会话首 turn 未创建沙箱').toBe(createsBefore + 1);
  const probe = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${creates[createsBefore].id}/proxy/44772/files/download?path=${encodeURIComponent(`/workspace/skills/${skill}/SKILL.md`)}`);
  expect(probe.status, 'Channel 链路容器内技能未物化（userKey 仍退化为 sessionId）').toBe(200);
  expect(await probe.text()).toContain(marker);
});
