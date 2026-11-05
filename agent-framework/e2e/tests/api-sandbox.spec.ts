/**
 * e2e-sandbox API 组：X 沙箱（mock OpenSandbox，e2e-ci-plan §5.6）。
 * 断言面 = agent 行为（TOOL_RESULT/turn 终态）+ mock /stats（create/connect/命令/文件操作流水）。
 */
import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { BASE, sessionIdFor } from '../lib/env.js';
import { chat, history, a2a, status, stop } from '../lib/client.js';
import { waitTerminal, textOf, toolNames, toolResults, pollUntil } from '../lib/matchers.js';
import { upload, textBytes } from '../lib/files.js';
import type { UploadResult } from '../lib/files.js';

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
  faultHits?: number;
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
  const uid = U();
  const sid = sessionIdFor(`x7-${uniq()}`);
  const stream = chat({ message: `[E2E:execute:fail]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(toolNames(stream.frames)).toContain('execute');
  const results = toolResults(stream.frames);
  expect(String((results[0] as Record<string, unknown>).state ?? '')).toBe('SUCCESS'); // 工具执行完成 ≠ 命令成功
  expect(textOf(stream.frames)).toContain('nonexistent-e2a-dir'); // stderr 可见
  expect(stream.terminal?.type).toBe('done'); // 单命令失败不炸流
  const h = await history(sid, BASE, uid);
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
  const empty = await request.get('/skills/users', { headers: { 'X-User-Id': uid } });
  expect(empty.status()).toBe(200);
  const emptyBody = await empty.json();
  expect(emptyBody.skills).toEqual([]);
  expect(emptyBody.tombstones).toEqual([]);

  // 管理面 PUT（KV 权威写 + admin-override 栅栏）
  const put = await request.put(`/skills/users/${skill}`, { headers: { 'X-User-Id': uid }, data: { content } });
  expect(put.status()).toBe(200);
  const putBody = await put.json();
  expect(putBody.action).toBe('created');
  expect(Number(putBody.version)).toBeGreaterThan(0);
  expect(String(putBody.message)).toContain('管理面写入栅栏'); // 沙箱档提示契约

  // KV 视图：source=user / adminOverride=true
  const detail = await request.get(`/skills/users/${skill}`, { headers: { 'X-User-Id': uid } });
  expect(detail.status()).toBe(200);
  const detailBody = await detail.json();
  expect(detailBody.source).toBe('user');
  expect(detailBody.hasUserOverride).toBe(true);
  expect(String(detailBody.content)).toContain(marker);
  const list = await (await request.get('/skills/users', { headers: { 'X-User-Id': uid } })).json();
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
  const emptyPut = await request.put('/skills/users/x10-neg', { headers: { 'X-User-Id': uid }, data: { content: '  ' } });
  expect(emptyPut.status()).toBe(400);
  expect((await emptyPut.json()).error).toBe('empty_content');
  const badName = await request.put('/skills/users/a..b', { headers: { 'X-User-Id': uid }, data: { content } });
  expect(badName.status()).toBe(400);
  expect((await badName.json()).error).toBe('invalid_name');
  const big = await request.put('/skills/users/x10-neg', { headers: { 'X-User-Id': uid }, data: { content: 'x'.repeat(1024 * 1024 + 1) } });
  expect(big.status()).toBe(413);
  expect((await big.json()).error).toBe('content_too_large');
});

test('X11 管理面 DELETE → tombstone 防复活（同会话同代容器 syncBack 仲裁）+ 物化不再投影', async ({ request }) => {
  const uid = U();
  const skill = `e2e-skill-${uniq()}`;
  const content = `---\nname: ${skill}\ndescription: e2e tombstone probe\nversion: 1.0.0\n---\n\n# ${skill}\n`;

  const putMarker = `TOMB-${uniq()}`;
  const put = await request.put(`/skills/users/${skill}`, { headers: { 'X-User-Id': uid }, data: { content: `${content}
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
  const del = await request.delete(`/skills/users/${skill}`, { headers: { 'X-User-Id': uid } });
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
  const after = names(await (await request.get('/skills/users', { headers: { 'X-User-Id': uid } })).json());
  expect(after.skills).not.toContain(skill);
  expect(after.tombs).toContain(skill);
  const gone = await request.get(`/skills/users/${skill}`, { headers: { 'X-User-Id': uid } });
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
  const final = names(await (await request.get('/skills/users', { headers: { 'X-User-Id': uid } })).json());
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

  const put = await request.put(`/skills/users/${skill}`, { headers: { 'X-User-Id': uid }, data: { content } });
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

// ---------- X16/X17：上传注入语义（execd files API 原样落盘 + 同名唯一化） ----------
// 回归背景：旧实现把上传字节 base64 后以 String 传 execd files API——execd 按提交字节
// 原样落盘（byte[] → octet-stream，String → text/plain 原文），不做 base64 解码，
// 沙箱内二进制文件（docx/xlsx/图片）全部变成 base64 文本（真实沙箱实测钉死）；
// 且沙箱路径未做同名唯一化，同名多文件静默互相覆盖。观测通道同 X10/X15：
// 新用户首 turn 必产生新 create，经 files/download 探针直证容器内字节。

/** 最小 docx（PK zip 头 + 非文本字节段：任何 base64/文本化转换都会破坏字节一致性） */
const DOCX_BYTES = Buffer.from([0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0xFE, 0xFF, 0x7F, 0x80, 0x55, 0xAA]);
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

/** 新用户上传 → 带 fileIds 的 chat（[E2E:plain] 触发注入）→ 返回本轮新建的全部沙箱 id */
async function injectViaChat(
  uid: string,
  tag: string,
  files: Array<{ bytes: Buffer; name: string; mime: string }>,
): Promise<string[]> {
  const fileIds: string[] = [];
  for (const f of files) {
    const up = await upload(BASE, f.bytes, f.name, f.mime, { userId: uid });
    expect(up.status, `上传 ${f.name}`).toBe(200);
    fileIds.push((up.body as UploadResult).file_id);
  }
  const createsBefore = (await sandboxStats()).creates.length;
  const sid = sessionIdFor(`${tag}-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, fileIds });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const creates = (await sandboxStats()).creates;
  expect(creates.length, '新用户首 turn 未创建沙箱').toBeGreaterThan(createsBefore);
  return creates.slice(createsBefore).map(c => c.id);
}

const downloadFromSandbox = (sandboxId: string, path: string) =>
  fetch(`${SANDBOX_MOCK}/v1/sandboxes/${sandboxId}/proxy/44772/files/download?path=${encodeURIComponent(path)}`);

/**
 * 注入产物探针（按新沙箱代从新到旧扫描）：返回 {sandboxId, res} 或 null。
 * 系统注入是 fail-soft（失败保持 pending，下一沙箱代重试），单次单代探测会误报
 * 慢环境/重试窗口下的瞬时未注入——这正是 CI 假红的一课（PR #126 首跑）。
 */
async function probeAcrossGenerations(
  ids: string[],
  paths: string[],
): Promise<{ sandboxId: string; res: Array<Response> } | null> {
  for (const id of [...ids].reverse()) {
    const res = await Promise.all(paths.map(p => downloadFromSandbox(id, p)));
    if (res.every(r => r.ok)) return { sandboxId: id, res };
  }
  return null;
}

test('X16 二进制上传注入后字节逐位一致（execd 原样落盘，无 base64 损坏）', async () => {
  const uid = U();
  const name = `sample-${uniq()}.docx`;
  const path = `/workspace/uploads/${name}`;
  let ids = await injectViaChat(uid, 'x16', [{ bytes: DOCX_BYTES, name, mime: DOCX_MIME }]);

  // fail-soft 恢复：首代未注入（时序/慢窗口）→ 补一个 turn 触发新沙箱代重新注入
  let hit = await probeAcrossGenerations(ids, [path]);
  if (!hit) {
    ids = ids.concat(await injectViaChat(uid, 'x16-recover', []));
    hit = await probeAcrossGenerations(ids, [path]);
  }
  expect(hit, '上传文件未注入沙箱（含恢复 turn 重试）').toBeTruthy();

  // 旧实现的故障形态：uploads/{name} 是 base64 文本（长度 +33%、内容非 PK 头）——逐字节比对
  const got = Buffer.from(await hit!.res[0].arrayBuffer());
  expect(got.equals(DOCX_BYTES), `字节不一致：得到 ${got.length} 字节（旧实现会是 base64 文本约 ${Math.round(DOCX_BYTES.length * 4 / 3)} 字节）`).toBe(true);

  // 探针通道有效性自检：不存在的路径必须 404（防 mock 返回 200 假绿）
  const neg = await downloadFromSandbox(hit!.sandboxId, `/workspace/uploads/no-such-${uniq()}.bin`);
  expect(neg.status).toBe(404);
});

test('X17 同名上传唯一化注入（_1 后缀，两份内容互不覆盖）', async () => {
  const uid = U();
  const name = `report-${uniq()}.zip`; // zip 在默认 MIME 白名单内（octet-stream 不在）
  const bytesA = Buffer.from([0x50, 0x4B, 0x03, 0x04, 0xAA, 0x01]);
  const bytesB = Buffer.from([0x50, 0x4B, 0x03, 0x04, 0xBB, 0x02]);
  const stem = name.slice(0, -'.zip'.length);
  const paths = [`/workspace/uploads/${name}`, `/workspace/uploads/${stem}_1.zip`];
  let ids = await injectViaChat(uid, 'x17', [
    { bytes: bytesA, name, mime: 'application/zip' },
    { bytes: bytesB, name, mime: 'application/zip' },
  ]);

  // fail-soft 恢复（同 X16）：两份必须在同一沙箱代内同时可见且内容各自完好
  let hit = await probeAcrossGenerations(ids, paths);
  if (!hit) {
    ids = ids.concat(await injectViaChat(uid, 'x17-recover', []));
    hit = await probeAcrossGenerations(ids, paths);
  }
  expect(hit, '同名文件未唯一化注入（_1 后缀缺失或互相覆盖；含恢复 turn 重试）').toBeTruthy();

  const [first, second] = hit!.res;
  expect(Buffer.from(await first.arrayBuffer()).equals(bytesA), '第一份内容被覆盖或损坏').toBe(true);
  expect(Buffer.from(await second.arrayBuffer()).equals(bytesB), '第二份内容不等于其上传字节').toBe(true);
});

// ---------- X18：工作区投影新鲜度（stage 晚于 start 的技能目录补水合，E-SKILL-4） ----------
// 回归背景：技能目录（.skills-cache/{scope}/oaf-package/...）由 HarnessSkillMiddleware 在
// 每轮推理 onSystemPrompt 时物化到平台本地工作区，而 SDK 的投影 hydrate 只在 sandbox start
// 时跑一次——晚于 start 的目录永远到不了容器 /workspace，load_skill_through_path 渲染的
// 容器路径在 shell 里 ls 不到（debug 会话实录：gorden-ppt-skill 文件列表 shell 不可见）。
// 修复：doExec 首次命令前重算投影 hash，变化即补水合（OpenSandbox.refreshProjectionIfStale）。
// 观测通道同 X10：mock 沙箱真实 bash 执行 tar/base64 管道（白名单内），files/download 探针直证。

test('X18 投影新鲜度：start 后新增的技能目录在下一 turn 补水合进容器（E-SKILL-4）', async () => {
  const uid = U();
  const sid = sessionIdFor(`x18-${uniq()}`);
  const marker = `PROJ-${uniq()}`;
  // 探针目录按 MarketplaceStager 真实布局构造：.skills-cache/{scope}/oaf-package/{skill}/
  // （scopeKeyFor 默认 USER 隔离档 → ctx.userId 优先；Channel 链路 /threads/chat 经 ChatUiChannel
  //  以 peer 作 ctx.userId，而本框架 peer = 前端 sessionId，故 scope = sid，
  //  见 ChatStreamController.sendStream(ChatUiRequest.withPeer(finalSessionId, ...))）
  const scope = sid;
  const hostSkillDir = path.join(
    process.env.E2E_AGENT_WS_ROOT ?? '.runtime/agent-config/.agentscope/workspace',
    '.skills-cache', scope, 'oaf-package', `probe-skill-${uniq()}`);
  const hostSkillFile = path.join(hostSkillDir, 'SKILL.md');

  // turn1：新用户首 turn 建沙箱 + start 投影基线（此刻宿主还没有探针目录）
  const stream1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
  await waitTerminal(stream1);
  expect(stream1.terminal?.type).toBe('done');
  const stats1 = await sandboxStats();
  const sbxId = stats1.creates[stats1.creates.length - 1].id;

  // turn 间隙：模拟 onSystemPrompt staging——往宿主 .skills-cache 写入新技能目录
  // （与 MarketplaceStager 同位同构；测试进程直写 = 不依赖 LLM 回放层模拟 stage 时序）
  fs.mkdirSync(hostSkillDir, { recursive: true });
  fs.writeFileSync(hostSkillFile, `---\nname: probe-skill\ndescription: x18 projection probe\n---\n\n${marker}\n`);

  try {
    // turn2：同会话 resume + 首次 exec 触发 refreshProjectionIfStale → 补水合进容器
    const stream2 = chat({ message: `[E2E:execute]`, userId: uid, sessionId: sid });
    await waitTerminal(stream2);
    expect(stream2.terminal?.type).toBe('done');

    // 探针：容器内 /workspace/.skills-cache/{scope}/oaf-package/{skill}/SKILL.md 可读且内容一致
    const containerPath = `/workspace/${path.relative(
      process.env.E2E_AGENT_WS_ROOT ?? '.runtime/agent-config/.agentscope/workspace', hostSkillFile)}`;
    const probe = await downloadFromSandbox(sbxId, containerPath);
    expect(probe.status, `容器内 ${containerPath} 不可读（start 后新增技能目录未补水合）`).toBe(200);
    expect(await probe.text()).toContain(marker);
  } finally {
    fs.rmSync(path.join(hostSkillDir), { recursive: true, force: true }); // 清场：不留宿主脏目录
  }
});

// ---------- X19：Channel 双会话并发沙箱 turn（上游 #2849 单槽互踩回归） ----------
// 回归背景：SDK SandboxBackedFilesystem 每 agent 单槽（volatile Sandbox），并行 turn 互相
// 覆盖/清空（B 的 releaseForCall 清槽 → A 撞 "No active sandbox"）；guard 键在 Channel 链路
// 曾退化为前端 sessionId（每会话一把锁），防不住跨会话并发。修复组合：①guard 键经
// session_user 反查真实 userId（同用户串行）；②SANDBOX_SESSION_AGENT_ISOLATION per-session
// agent（跨会话各持独立文件系统，默认关，CI 沙箱 job 已开启——见 scripts/env-up.sh）。
// 断言面：两会话并发跑沙箱 turn，双方都必须正常终态且工具结果正确——单槽互踩下 B 会把
// A 的 turn 打成 error（实测复现：B 探针收尾与 A 第 2 轮推理同毫秒撞窗口）。

test('X19 双会话并发沙箱 turn 互不干扰（#2849 单槽互踩回归）', async () => {
  const uidA = U();
  const uidB = U();
  const sidA = sessionIdFor(`x19a-${uniq()}`);
  const sidB = sessionIdFor(`x19b-${uniq()}`);
  // 同发并发（Promise.all 保证同 tick 发起）：A/B 各自首 turn 创建沙箱 + 执行命令
  const [ra, rb] = await Promise.all([
    (async () => { const s = chat({ message: `[E2E:execute]`, userId: uidA, sessionId: sidA, timeoutMs: 120_000 }); await waitTerminal(s); return s; })(),
    (async () => { const s = chat({ message: `[E2E:execute]`, userId: uidB, sessionId: sidB, timeoutMs: 120_000 }); await waitTerminal(s); return s; })(),
  ]);
  // 双方终态：必须是 done 而非 error（单槽互踩的典型症状是另一方 "No active sandbox" error）
  expect(ra.terminal?.type, `会话 A 终态异常: ${JSON.stringify(ra.terminal)}`).toBe('done');
  expect(rb.terminal?.type, `会话 B 终态异常: ${JSON.stringify(rb.terminal)}`).toBe('done');
  // 双方工具结果正确（execute 夹具固定 echo hello-e2a）
  expect(textOf(ra.frames)).toContain('hello-e2a');
  expect(textOf(rb.frames)).toContain('hello-e2a');
  // 双方各自创建了沙箱（跨会话隔离：两个 create 流水，非共享同一沙箱代）
  const stats = await sandboxStats();
  expect(stats.creates.length).toBeGreaterThanOrEqual(2);
});

// 同用户两会话并发长任务（用户反馈回归：2026-10-21 守卫锁粒度修复）。
// per-session agent 隔离（SANDBOX_SESSION_AGENT_ISOLATION=true）下 slot 按前端 sid 分片，
// 守卫锁粒度跟随 slot（sessionGranularLocks）→ 同用户两会话的沙箱执行窗口必须重叠；
// 修复前锁反查真实用户（每用户一把），B 会话整回合排队到 A 回合结束。
test('X20 同用户两会话并发沙箱执行（锁粒度回归）', async () => {
  const uid = U();
  const sidA = sessionIdFor(`x20a-${uniq()}`);
  const sidB = sessionIdFor(`x20b-${uniq()}`);
  const secs = 10;
  const t0 = Date.now();
  // A 先发起并进入沙箱执行（sleep 10），3s 后同用户 B 会话发起同样的长任务
  const streamA = chat({ message: `[E2E:execute:sleep](${secs})`, userId: uid, sessionId: sidA, timeoutMs: 120_000 });
  await new Promise(r => setTimeout(r, 3_000));
  const streamB = chat({ message: `[E2E:execute:sleep](${secs})`, userId: uid, sessionId: sidB, timeoutMs: 120_000 });
  await Promise.all([waitTerminal(streamA), waitTerminal(streamB)]);
  const wall = Date.now() - t0;

  expect(streamA.terminal?.type, `A 终态异常: ${JSON.stringify(streamA.terminal)}`).toBe('done');
  expect(streamB.terminal?.type, `B 终态异常: ${JSON.stringify(streamB.terminal)}`).toBe('done');

  // 从各自 TOOL_RESULT 的 echo 时间戳还原执行窗口（mock 沙箱真实 bash 执行）
  // 工具输出在 TOOL_RESULT_TEXT_DELTA.delta（textOf 只拼 LLM 文本帧，不适用）
  const windowOf = (frames: Array<Record<string, unknown> & { type: string }>) => {
    const text = frames.filter(f => f.type === 'TOOL_RESULT_TEXT_DELTA')
      .map(f => String(f.delta ?? '')).join('');
    const s = /conc-start-(\d{13})/.exec(text)?.[1];
    const e = /conc-end-(\d{13})/.exec(text)?.[1];
    return s && e ? { start: Number(s), end: Number(e) } : null;
  };
  const wa = windowOf(streamA.frames);
  const wb = windowOf(streamB.frames);
  expect(wa, 'A 执行窗口缺失').toBeTruthy();
  expect(wb, 'B 执行窗口缺失').toBeTruthy();
  // 并行判据：执行窗口重叠（串行时 B 的 start 必然晚于 A 的 end）
  expect(wb!.start, `B 开始(${wb!.start}) 应早于 A 结束(${wa!.end})——出现串行排队`).toBeLessThan(wa!.end);
  expect(wa!.start, `A 开始(${wa!.start}) 应早于 B 结束(${wb!.end})`).toBeLessThan(wb!.end);
  // 粗判据：总耗时显著小于串行值（3s 错峰 + 2×10s ≈ 23s）
  expect(wall, `总耗时 ${wall}ms 接近串行水位，疑似未并发`).toBeLessThan(22_000);

  // 结构判据：两会话各自持有独立沙箱（create 流水 ≥ 2 且命令落在不同 sandboxId）
  const stats = await sandboxStats();
  const sleepSandboxes = new Set(
    stats.commands.filter(c => c.cmd.includes('sleep')).map(c => c.sandboxId));
  expect(sleepSandboxes.size).toBeGreaterThanOrEqual(2);
});

// ==================== X-ENV 组：沙箱环境变量接口（docs/sandbox-env-update-design.md） ====================
// 生效机制 = execd EXECD_ENVS 文件：接口按 userId/sessionId 定位沙箱后直接写文件，
// execd 每条命令自动重读；断言面 = TOOL_RESULT_TEXT_DELTA（真实 bash stdout）+ 文件内容。

const putSandboxEnv = async (userId: string, sessionId: string, env: Record<string, string>) => {
  const r = await fetch(`${BASE}/sandbox/env`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ userId, sessionId, env }),
  });
  return { status: r.status, body: (await r.json().catch(() => ({}))) as Record<string, unknown> };
};

/** 首轮 plain 对话：创建沙箱并让 harness 持久化沙箱状态（接口定位依赖） */
async function bootSandbox(uid: string, sid: string) {
  const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
}

/** PUT 重试：harness 在轮次结束后才持久化沙箱状态，吸收 CI 时序抖动 */
async function putEnvWithRetry(userId: string, sessionId: string, env: Record<string, string>) {
  let last = { status: 0, body: {} as Record<string, unknown> };
  for (let i = 0; i < 5; i++) {
    last = await putSandboxEnv(userId, sessionId, env);
    if (last.status !== 404) return last;
    await new Promise(r => setTimeout(r, 1_000));
  }
  return last;
}

/**
 * 拼接某轮工具结果文本（TOOL_RESULT_TEXT_DELTA = 真实 bash stdout，textOf 只拼 LLM 文本帧）。
 * execute 工具结果按 JSON 字符串编码（delta 形如 `"Exit code: 0\n\n<stdout>"`），此处解码。
 */
const toolOutputOf = (frames: Array<Record<string, unknown> & { type: string }>) => {
  const raw = frames.filter(f => f.type === 'TOOL_RESULT_TEXT_DELTA')
    .map(f => String(f.delta ?? '')).join('');
  try {
    const decoded = JSON.parse(raw);
    return typeof decoded === 'string' ? decoded : raw;
  } catch {
    return raw;
  }
};

/** 工具结果去掉 execute 包装的 "Exit code: N" 前缀，得到沙箱命令 stdout */
const toolStdoutOf = (frames: Array<Record<string, unknown> & { type: string }>) =>
  toolOutputOf(frames).replace(/^Exit code: -?\d+\n\n?/, '').trim();

test('X-ENV-1 接口写入 → 后续命令自动加载（execd 每条命令重读）', async () => {
  const uid = U();
  const sid = sessionIdFor(`xenv1-${uniq()}`);
  await bootSandbox(uid, sid);

  const put = await putEnvWithRetry(uid, sid, { SANDBOX_E2E_TOKEN: 'one', SANDBOX_E2E_KEEP: 'keep' });
  expect(put.status, `PUT 失败: ${JSON.stringify(put.body)}`).toBe(200);
  expect(put.body.applied).toBe('next_exec');
  expect(put.body.sandboxId).toBeTruthy();

  const stream = chat({ message: `[E2E:sbx-env]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  // 字节级精确断言：旧版 execd 不剥引号，若接口写 KEY='one' 这里会是 "'one' 'keep'"
  const envText = toolStdoutOf(stream.frames);
  expect(envText).toBe('one keep');
  const preview = stream.frames.find(f => f.type === 'tool_result_preview') as Record<string, unknown> | undefined;
  expect(String(preview?.preview ?? '')).toContain('one');
});

test('X-ENV-2 合并更新：未修改的键保持生效', async () => {
  const uid = U();
  const sid = sessionIdFor(`xenv2-${uniq()}`);
  await bootSandbox(uid, sid);
  expect((await putEnvWithRetry(uid, sid, { SANDBOX_E2E_TOKEN: 'one', SANDBOX_E2E_KEEP: 'keep' })).status).toBe(200);
  expect((await putEnvWithRetry(uid, sid, { SANDBOX_E2E_TOKEN: 'two' })).status).toBe(200);

  const stream = chat({ message: `[E2E:sbx-env]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const envText = toolStdoutOf(stream.frames);
  expect(envText).toBe('two keep');   // 更新的键生效（追加行覆盖旧行）、未修改键保持生效
});

test('X-ENV-3 镜像脚本写入的 PATH 行保留（不整文件覆盖）', async () => {
  const uid = U();
  const sid = sessionIdFor(`xenv3-${uniq()}`);
  await bootSandbox(uid, sid);
  expect((await putEnvWithRetry(uid, sid, { SANDBOX_E2E_TOKEN: 'one' })).status).toBe(200);

  const stats = await sandboxStats();
  const id = stats.creates[stats.creates.length - 1].id;
  const dl = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${id}/proxy/44772/files/download?path=${encodeURIComponent('/opt/opensandbox/.env')}`);
  const envFileText = await dl.text();
  expect(envFileText).toContain('PATH=/usr/local/bin:/usr/bin:/bin'); // 镜像脚本启动追加行
  expect(envFileText).toContain('SANDBOX_E2E_TOKEN=one');             // 接口追加行（裸值，跨 execd 版本兼容）
});

test('X-ENV-4 值含单引号（非首字符）→ 允许且字面保留', async () => {
  const uid = U();
  const sid = sessionIdFor(`xenv4-${uniq()}`);
  await bootSandbox(uid, sid);
  expect((await putEnvWithRetry(uid, sid, { SANDBOX_E2E_TOKEN: "it's" })).status).toBe(200);

  const stream = chat({ message: `[E2E:sbx-env]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  expect(toolStdoutOf(stream.frames)).toBe("it's");  // 裸值不剥引号也不加引号
});

test('X-ENV-6 旧/新版 execd 均无法无损表达的值 → 400', async () => {
  const uid = U();
  const sid = sessionIdFor(`xenv6-${uniq()}`);
  await bootSandbox(uid, sid);
  // $ 会被 os.ExpandEnv 展开（无转义语法）；换行会截断行式格式；首字符引号在新版会被当引号值
  const cases: Array<[Record<string, string>, string]> = [
    [{ SANDBOX_E2E_TOKEN: 'sk-$HOME' }, 'unsupported_env_value'],
    [{ SANDBOX_E2E_TOKEN: 'a\nb' }, 'invalid_env_value'],
    [{ SANDBOX_E2E_TOKEN: "'quoted'" }, 'unsupported_env_value'],
  ];
  for (const [env, code] of cases) {
    // 沙箱状态在 turn 收尾异步落库：404 重试（400 立即返回），与其余 X-ENV 用例一致
    const put = await putEnvWithRetry(uid, sid, env);
    expect(put.status, JSON.stringify(env)).toBe(400);
    expect(put.body.error).toBe(code);
  }
});

test('X-ENV-5 沙箱不存在 → 404（不做兜底）', async () => {
  const put = await putSandboxEnv(`nobody-${uniq()}`, sessionIdFor(`xenv5-${uniq()}`), { A: '1' });
  expect(put.status).toBe(404);
  expect(put.body.error).toBe('sandbox_not_found');
});

// 发布失败收敛（issue #230 写序修复）：mock 故障注入让发布临时文件写入 500，
// 验证 publish 失败 → 502 → sidecar 未推进 → 调用方重试重新检出变化并收敛。
test('X-ENV-8 发布失败 → 502 → sidecar 未推进 → 调用方重试收敛', async () => {
  const uid = U();
  const sid = sessionIdFor(`xenv8-${uniq()}`);
  await bootSandbox(uid, sid);
  // 故障注入：发布用临时文件（path 含 .tmp.）的 files/upload 返回 500（mock /admin/fault）
  const inject = await fetch(`${SANDBOX_MOCK}/admin/fault`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ mvFail: true }),
  });
  expect(inject.status).toBe(200);
  try {
    const failed = await putEnvWithRetry(uid, sid, { SANDBOX_E2E_TOKEN: 'eight' });
    expect(failed.status, JSON.stringify(failed.body)).toBe(502);
    expect(failed.body.error).toBe('sandbox_unavailable');
    const stats = await sandboxStats();
    expect(stats.faultHits ?? 0, '故障注入未命中（PUT 失败与注入无关）').toBeGreaterThanOrEqual(1);
  } finally {
    // 清除注入（失败也要清，防污染后续用例；/reset 亦会清）
    await fetch(`${SANDBOX_MOCK}/admin/fault`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({}),
    });
  }
  // 重试：sidecar 未推进（写序修复）→ 变更重新检出 → 收敛；
  // 修复前 sidecar 已先行写入 → 重试静默 no-op（updatedKeys 空）→ 命令读不到值
  const retried = await putEnvWithRetry(uid, sid, { SANDBOX_E2E_TOKEN: 'eight' });
  expect(retried.status, JSON.stringify(retried.body)).toBe(200);
  expect(retried.body.updatedKeys, '重试必须重新检出变更（非静默 no-op）').toContain('SANDBOX_E2E_TOKEN');

  const stream = chat({ message: `[E2E:sbx-env]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  expect(toolStdoutOf(stream.frames)).toBe('eight'); // KEEP 未设置 → 空展开被 word-split 去除
});

// ---------- STP 组：单轮停止全链路（turn-stop-design §9 建议 3，PR #203） ----------
// 此前整条真实链路（stop 写表 → 属主副本轮询 → registry 派发 → SDK interrupt →
// cancelled 帧落 SSE → /status 归 completed → history 标注 stopped）只有互 mock 的
// 单测、零 e2e 黑盒。语义注意（design §5.1）：协作式中断不杀阻塞中的工具——stop 后
// cancelled 要等 sleep 返回、到达下一中断检查点才出现，故不断言 stop→cancelled 立即性，
// 只断言顺序（cancelled 先于 AGENT_END/done）与终态收敛。
test('STP1 运行中 turn 停止全链路：202 受理 → cancelled 终态 → status/history 归档', async () => {
  const uid = U();
  const sid = sessionIdFor(`stp1-${uniq()}`);
  // sleep 20s 给 stop 留窗口（工具阻塞中，中断在工具返回后的检查点生效）
  const stream = chat({ message: `[E2E:execute:sleep](20)`, userId: uid, sessionId: sid, timeoutMs: 120_000 });

  // 工具开始后（turn 已产出事件、租约在持）投递停止
  await pollUntil(async () => stream.frames, fs => toolNames(fs).includes('execute'), 30_000, 100);
  const stopped = await stop(sid, BASE, uid);
  expect(stopped.status, JSON.stringify(stopped.body)).toBe(202);
  expect(stopped.body.state).toBe('stopping');
  expect(String(stopped.body.reply_id ?? '')).toBeTruthy();

  // 流收敛：cancelled（reason=user_stopped）出现且先于 AGENT_END / done 终态
  await waitTerminal(stream);
  const cancelledIdx = stream.frames.findIndex(f => f.type === 'cancelled');
  expect(cancelledIdx, `未收到 cancelled 帧，实际帧序 [${stream.frames.map(f => f.type).join(',')}]`).toBeGreaterThanOrEqual(0);
  expect((stream.frames[cancelledIdx] as Record<string, unknown>).reason).toBe('user_stopped');
  const agentEndIdx = stream.frames.findIndex(f => f.type === 'AGENT_END');
  expect(agentEndIdx, 'AGENT_END 缺失（停止轮未正常收尾）').toBeGreaterThan(cancelledIdx);
  expect(stream.terminal?.type).toBe('done');

  // SDK 恢复话术不上屏（ChatStreamController 抑制 INTERRUPTED 恢复消息）
  expect(textOf(stream.frames)).not.toContain('I noticed that you have interrupted');

  // /status：cancelled 属终态（SessionEventTailer.TERMINAL_TYPES）→ 归 completed
  await pollUntil(async () => status(sid, BASE, uid), s => s.state === 'completed', 30_000);

  // history：末轮助手消息换成本地化文案 + stopped 标注（StateDataParser INTERRUPTED 分支）
  const h = await history(sid, BASE, uid);
  const msgs = h.messages as Array<Record<string, unknown>>;
  const lastAssistant = [...msgs].reverse().find(m => m.role === 'assistant');
  expect(lastAssistant, 'history 缺少末轮助手消息').toBeTruthy();
  expect(lastAssistant!.stopped).toBe(true);
  expect(String(lastAssistant!.content)).toBe('（本次回复已被用户停止）');
});
