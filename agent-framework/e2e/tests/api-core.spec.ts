/**
 * e2e-core API 组：S 基础 / F 文件 / H HITL / M MCP Apps / A A2A / SK 技能管理 / HA 归档历史 /
 * FW /threads 槽位双形态匹配与 Flyway / RD Redis 前缀隔离（e2e-ci-plan §5，FW 为 §5.10、RD 为 §5.11）。
 * 协议权威：docs/api-thread-spec.md v1.0。
 */
import { test, expect, type APIRequestContext, type APIResponse } from '@playwright/test';
import { BASE, BENCH_MCP, ids, sessionIdFor } from '../lib/env.js';
import { chat, status, subscribe, history, threads, deleteThread, patchThread, a2a, llmStats, llmReset, createApprovalApp, confirmStream, confirmSync } from '../lib/client.js';
import { waitTerminal, textOf, toolNames, toolResults, pollUntil } from '../lib/matchers.js';
import { seqMonotonic } from '../lib/sse.js';
import { upload, download, textBytes, pngBytes } from '../lib/files.js';
import { seedCompactionArchive } from '../lib/archive-seed.js';
import mysql from 'mysql2/promise';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const U = 'e2e-tester';
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;

test.beforeEach(async () => { await llmReset(); });

// ---------- S 组：基础 API ----------

test('S1 元数据端点全量暴露', async ({ request }) => {
  for (const path of ['/', '/health', '/metadata', '/system-prompt', '/.well-known/agent-card.json', '/tools', '/mcp', '/skills']) {
    const res = await request.get(path);
    expect(res.status(), path).toBe(200);
  }
  const tools = await (await request.get('/tools')).json();
  const toolStr = JSON.stringify(tools);
  // 默认视图仅列 MCP 工具；内置/自定义工具需显式 includeInternal=true（issue #28）
  expect(toolStr).toContain('bench_echo');           // bench MCP（read_only）
  expect(toolStr).toContain('show_application_form'); // approval MCP（ui.tools）
  expect(toolStr).toContain('confirm_application');
  expect(toolStr).toContain('appOnly');
  expect(tools.internalCount).toBe(0);               // 默认不暴露内部工具
  // includeInternal：运行时注册集以 @Tool bean 为准（非 frontmatter tools 声明视图），
  // declared 标注声明意图。插件化自定义工具的注册/剔除见 e2e-plugin job（plugin-smoke.sh）。
  const internal = await (await request.get('/tools?includeInternal=true')).json();
  expect(internal.internalCount).toBeGreaterThan(0);
  const internalStr = JSON.stringify(internal);
  for (const name of ['echo', 'present_file', 'present_url']) {
    expect(internalStr, name).toContain(`"name":"${name}"`);
  }
  // 本 fixture 未声明 tools: → 运行时集全部 declared=false
  expect(internalStr).toContain('"declared":false');
  const mcps = await (await request.get('/mcp')).json();
  const serverNames = JSON.stringify(mcps);
  for (const s of ['bench', 'approval', 'denied', 'cards']) expect(serverNames).toContain(s);
  const skills = await (await request.get('/skills')).json();
  expect(JSON.stringify(skills)).toContain('demo-skill');
  const card = await (await request.get('/.well-known/agent-card.json')).json();
  expect(JSON.stringify(card)).toContain('A2A');
});

test('S2 单次流基础帧序', async () => {
  // 不传 sessionId：服务端自动生成并首发 session_created（api-thread-spec §二）
  const stream = chat({ message: `[E2E:plain]`, userId: U });
  await waitTerminal(stream);
  seqMonotonic(stream.frames);
  const types = stream.frames.map(f => f.type);
  expect(types[0]).toBe('session_created');
  const sid = String((stream.frames[0] as Record<string, unknown>).session_id);
  expect(sid.length).toBeGreaterThan(8);
  expect(types).toContain('AGENT_START');
  expect(types).toContain('MODEL_CALL_START');
  expect(types).toContain('TEXT_BLOCK_START');
  expect(types).toContain('TEXT_BLOCK_DELTA');
  expect(types).toContain('TEXT_BLOCK_END');
  expect(types).toContain('AGENT_END');
  expect(stream.terminal?.type).toBe('done');
  expect(textOf(stream.frames).length).toBeGreaterThan(2);
  // 返回的 session_id 可用于续接（/history 一致）
  const h = await history(sid);
  expect(h._http === undefined || h._http === 200).toBe(true);
});

test('S3 续会话上下文累积', async () => {
  const sid = sessionIdFor(`s3-${uniq()}`);
  const s1 = chat({ message: `[E2E:remember](琥珀-${uniq()})`, userId: U, sessionId: sid });
  await waitTerminal(s1);
  const s2 = chat({ message: `[E2E:recall]`, userId: U, sessionId: sid });
  await waitTerminal(s2);
  const stats = await llmStats();
  const calls = stats.calls.filter(c => c.scenario === 'recall');
  const last = calls[calls.length - 1];
  expect(Number(last.msgCount)).toBeGreaterThan(2); // 首轮 user+assistant 已入上下文
  expect((last.roles as string[])).toContain('assistant');
});

test('S4 内置工具调用', async () => {
  const sid = sessionIdFor(`s4-${uniq()}`);
  const stream = chat({ message: `[E2E:tool:echo](e2a-echo-${uniq()})`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  expect(toolNames(stream.frames)).toContain('echo');
  const results = toolResults(stream.frames);
  expect(results.length).toBeGreaterThan(0);
  expect(String((results[0] as Record<string, unknown>).state ?? '')).toBe('SUCCESS');
  const callSummary = stream.frames.find(f => f.type === 'tool_call_summary' && f.toolName === 'echo') as Record<string, unknown> | undefined;
  expect(callSummary, '缺少 tool_call_summary 合成帧').toBeTruthy();
  expect(String(callSummary!.summary)).toContain('echo 端到端回显内容');
  expect(String(callSummary!.replyId ?? '')).not.toBe('');
  const resultPreview = stream.frames.find(f => f.type === 'tool_result_preview' && f.toolName === 'echo') as Record<string, unknown> | undefined;
  expect(resultPreview, '缺少 tool_result_preview 合成帧').toBeTruthy();
  expect(String(resultPreview!.preview)).toContain('echo:');
  const rawToolEnd = stream.frames.findIndex(f => f.type === 'TOOL_RESULT_END' && f.toolCallId === callSummary!.toolCallId);
  const previewIndex = stream.frames.indexOf(resultPreview!);
  expect(rawToolEnd).toBeGreaterThanOrEqual(0);
  expect(previewIndex).toBeGreaterThan(rawToolEnd);
  expect(stream.terminal?.type).toBe('done');
  // history 侧 state 为小写 success
  const h = await history(sid);
  const tcs = (h.messages as Array<Record<string, unknown>>).flatMap(m => (m.tool_calls ?? []) as Array<Record<string, unknown>>);
  expect(tcs.some(t => t.name === 'echo' && t.state === 'success')).toBe(true);
});

test('S5 MCP 只读工具 bench_echo + 用户级 header/_meta 注入', async () => {
  const sid = sessionIdFor(`s5-${uniq()}`);
  const arg = `mcp-${uniq()}`;
  const stream = chat({ message: `[E2E:tool:mcp_echo](${arg})`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  expect(toolNames(stream.frames)).toContain('bench_echo');
  expect(stream.terminal?.type).toBe('done');
  const results = toolResults(stream.frames);
  expect(String((results[0] as Record<string, unknown>).state ?? '')).toBe('SUCCESS');

  // 用户级注入：config.yaml userHeaders 映射 X-User-Id ← userId（缺值 deny fail-closed）；
  // 校验 mock 侧实收：HTTP header 为真实 userId（Channel 链路经 session_user 反查），
  // 协议 _meta 携带同一 userId（McpMeta 双通道）
  type LastCall = { xUserId?: string | null; meta?: Record<string, unknown> | null };
  const last = await pollUntil<LastCall>(
    async () => (await (await fetch(`${BENCH_MCP}/last-call`)).json()) as LastCall,
    v => v.xUserId === U,
  );
  expect(last.xUserId).toBe(U);
  expect(last.meta?.userId).toBe(U);
});

test('S6 参数校验负例', async ({ request }) => {
  const res = await request.post('/threads/chat', { data: { userId: U } });
  // 无 message/fileIds：SSE error 帧（200 流内 error）或 400，按实现固化
  if (res.status() === 200) {
    const txt = await res.text();
    expect(txt).toContain('error');
  } else {
    expect(res.status()).toBe(400);
  }
  const miss = await request.get(`/threads/e2e-nonexistent-${uniq()}/history`);
  expect([200, 404]).toContain(miss.status());
  const st = await request.get(`/threads/e2e-nonexistent-${uniq()}/status`);
  expect([200, 404]).toContain(st.status());
});

test('S7 线程生命周期管理', async ({ request }) => {
  const sid = sessionIdFor(`s7-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  const list = await threads();
  expect(JSON.stringify(list)).toContain(sid);
  const one = await request.get(`/threads/${sid}`);
  expect(one.status()).toBe(200);
  const manualTitle = `e2e-title-${uniq()}`;
  const patch = await patchThread(sid, { title: manualTitle });
  expect([200, 204]).toContain(patch.status);
  const titled = await pollUntil(async () => {
    const res = await request.get(`/threads?userId=${encodeURIComponent(U)}`);
    const rows = await res.json() as Array<Record<string, unknown>>;
    return rows.find(row => row.session_id === sid);
  }, row => row?.title === manualTitle);
  expect(titled?.title).toBe(manualTitle);
  const llmCalls = await request.get(`/threads/${sid}/llm-calls`);
  expect(llmCalls.status()).toBe(200);
  // 记录键必须与查询键一致：Channel 链路 ctx.sessionId 是全进程共享的网关 gw-hash，
  // 按它落记录会让本查询恒为空、且各会话记录串进同一个桶（issue #44）。
  // 此前只断 200 正是 CI 看不见该缺陷的原因，这里必须断到非空。
  const callsBody = await pollUntil(
    async () => (await request.get(`/threads/${sid}/llm-calls`)).json() as { calls: unknown[] },
    body => (body.calls?.length ?? 0) > 0,
  );
  expect(callsBody.calls.length).toBeGreaterThan(0);
  expect(await deleteThread(sid)).toBeLessThan(300);
  // 实测：删除后 GET 详情仍 200（空壳），权威信号是列表移除
  await pollUntil(async () => threads(), list => !JSON.stringify(list).includes(sid), 15_000);
});

test('S8 history 回放工具配对', async () => {
  const sid = sessionIdFor(`s8-${uniq()}`);
  const stream = chat({ message: `[E2E:tool:time]`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  const h = await history(sid);
  expect(h.pendingConfirm ?? null).toBeNull();
  const msgs = h.messages as Array<Record<string, unknown>>;
  const tc = msgs.flatMap(m => (m.tool_calls ?? []) as Array<Record<string, unknown>>).find(t => t.name === 'get_current_time');
  expect(tc).toBeTruthy();
  expect(tc!.state).toBe('success');
  expect(String(tc!.output ?? '').length).toBeGreaterThan(0);
  expect(tc!.id).toBeTruthy();
});

test('S9 首轮消息自动生成标题，手工标题后续不被覆盖', async ({ request }) => {
  const sid = sessionIdFor(`s9-${uniq()}`);
  const first = chat({ message: '[E2E:plain]请总结本次会话主题', userId: U, sessionId: sid });
  await waitTerminal(first);

  const generated = await pollUntil(async () => {
    const res = await request.get(`/threads?userId=${encodeURIComponent(U)}`);
    const rows = await res.json() as Array<Record<string, unknown>>;
    return rows.find(row => row.session_id === sid);
  }, row => typeof row?.title === 'string' && row.title.length > 0);
  expect(generated?.title).toBe('端到端链路畅通，测试正常');

  const manualTitle = `手工标题-${uniq()}`;
  const patched = await patchThread(sid, { title: manualTitle });
  expect(patched.status).toBe(200);
  const second = chat({ message: '[E2E:plain]继续对话', userId: U, sessionId: sid });
  await waitTerminal(second);
  await new Promise(resolve => setTimeout(resolve, 500));

  const res = await request.get(`/threads?userId=${encodeURIComponent(U)}`);
  const rows = await res.json() as Array<Record<string, unknown>>;
  expect(rows.find(row => row.session_id === sid)?.title).toBe(manualTitle);
});

// ---------- F 组：文件上传下载 ----------

test('F1 文档上传→工作区注入→读文件', async () => {
  const uid = ids(`f1-${uniq()}`);
  const sid = sessionIdFor(`f1-${uniq()}`);
  const content = 'e2a-note-content-v1'; // 与 tool-read 录制件固定耦合（夹具即契约）
  const up = await upload(BASE, textBytes(content), 'note.txt', 'text/plain', { userId: uid, sessionId: sid });
  expect(up.status).toBe(200);
  const fileId = (up.body as Record<string, string>).file_id;
  expect(fileId).toBeTruthy();
  const stream = chat({ message: `[E2E:tool:read](uploads/note.txt)`, userId: uid, sessionId: sid, fileIds: [fileId] });
  await waitTerminal(stream);
  expect(toolNames(stream.frames)).toContain('read_file');
  expect(textOf(stream.frames)).toContain(content); // read_file 读回上传内容（注入生效）
  const stats = await llmStats();
  // 按 scenario 取本测试自己的调用：mock 的后台合成调用（scenario=background-synth）
  // 与本测试共用 stats 通道，盲取最后一条会偶发取到后台帧（S3 同款过滤模式）
  const call = stats.calls.filter(c => c.scenario === 'tool-read').pop();
  expect(call).toBeTruthy();
  expect(JSON.stringify(call!.roles)).toContain('assistant');
});

test('F2 图片上传→视觉内联', async () => {
  const uid = ids(`f2-${uniq()}`);
  const sid = sessionIdFor(`f2-${uniq()}`);
  const up = await upload(BASE, pngBytes(2), `img-${uniq()}.png`, 'image/png', { userId: uid });
  expect(up.status).toBe(200);
  const fileId = (up.body as Record<string, string>).file_id;
  const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, fileIds: [fileId] });
  await waitTerminal(stream);
  const stats = await llmStats();
  // 按 scenario 取本测试自己的调用（本窗口内唯一带 plain 标记的调用）：
  // 后台合成调用共用 stats 通道且可能落在主调用之后，盲取最后一条会偶发翻车
  const call = stats.calls.filter(c => c.scenario === 'plain').pop();
  expect(call).toBeTruthy();
  expect(call!.hasImageBlock).toBe(true); // ImageBlock → OpenAI image_url 内联
});

test('F4 同名重复上传唯一化（冒烟）', async () => {
  const uid = ids(`f4-${uniq()}`);
  const sid = sessionIdFor(`f4-${uniq()}`);
  const content = 'e2a-note-content-v1';
  const u1 = await upload(BASE, textBytes(content), 'note.txt', 'text/plain', { userId: uid, sessionId: sid });
  const u2 = await upload(BASE, textBytes(content), 'note.txt', 'text/plain', { userId: uid, sessionId: sid });
  expect(u1.status).toBe(200);
  expect(u2.status).toBe(200);
  const idsArr = [(u1.body as Record<string, string>).file_id, (u2.body as Record<string, string>).file_id];
  // 唯一化（note_1.txt 后缀）由 uniqueWorkspacePath 单测覆盖；此处验证两次携文件对话均正常完成
  for (const fid of idsArr) {
    const s = chat({ message: `[E2E:tool:read](uploads/note.txt)`, userId: uid, sessionId: sid, fileIds: [fid] });
    await waitTerminal(s);
    expect(s.terminal?.type).toBe('done');
    expect(toolNames(s.frames)).toContain('read_file');
  }
});

// D8（SDK 缺陷，裸栈定位）：夹具含 edit_file 调用时，SDK 的 FilesystemUtils.countOccurrences
// 对空/相同内容做替换会死循环（indexOf("", i) 恒返回 i，游标不前进 → 单线程 CPU 100% 挂死），
// 并连带卡住 HarnessGateway 会话闸门释放，使实例后续所有 turn 阻塞。
// file_ready 链路本身已由 D3 修复验证（见 X9 与手动探针）；本用例待 SDK 修复后转正。
test.fixme('F5 present_file 交付与下载（file_ready 帧 + 下载内容）', async () => {
  const sid = sessionIdFor(`f5-${uniq()}`);
  const stream = chat({ message: `[E2E:file:deliver](report.md)`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  const ready = stream.frames.find(f => f.type === 'file_ready') as Record<string, unknown> | undefined;
  expect(ready, '缺少 file_ready 帧（D3 修复后应产出）').toBeTruthy();
  expect(ready!.file_name).toBe('report.md');
  const dl = await download(BASE, String(ready!.download_url ?? ready!.file_id));
  expect(dl.status).toBe(200);
  expect(dl.bytes!.length).toBeGreaterThan(0);
  expect(dl.disposition).toContain('attachment');
});

// F12（2026-09-24 发布助手无法下载 OAF 包回归门禁）：OAF 打包工具迁移至平台 MCP 后链路为
// MCP create_oaf_zip（平台 mock 返回 packageId/download_url）→ present_url 登记交付
// （外部交付物，下载经 /files/{id} 服务端代理回源）。不变式保持门禁：file_ready 帧合成、
// file_asset.session_id 落业务会话（不得是网关恒定 gw-hash，否则历史回放查不到卡片）。
test('F12 OAF 打包交付（MCP create_oaf_zip + present_url + 代理下载 + 历史回放会话绑定）', async () => {
  const sid = sessionIdFor(`f9-${uniq()}`);
  const stream = chat({ message: `[E2E:oaf:package]`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  expect(toolNames(stream.frames)).toContain('create_oaf_zip');
  expect(toolNames(stream.frames)).toContain('present_url');
  const callSummaries = stream.frames.filter(f => f.type === 'tool_call_summary');
  expect(callSummaries.some(f => f.toolName === 'create_oaf_zip')).toBe(true);
  expect(callSummaries.some(f => f.toolName === 'present_url')).toBe(true);
  const resultPreviews = stream.frames.filter(f => f.type === 'tool_result_preview');
  expect(resultPreviews.some(f => f.toolName === 'present_url')).toBe(true);
  // 实时 file_ready 帧：download_url 固定 /files/{id} 相对路径（前端拼 AGENT_BASE）
  const ready = stream.frames.find(f => f.type === 'file_ready') as Record<string, unknown> | undefined;
  expect(ready, '缺少 file_ready 帧（create_oaf_zip 应与 present_file 同链路合成）').toBeTruthy();
  expect(ready!.file_name).toBe('e2e-oaf-agent.zip');
  expect(String(ready!.download_url)).toMatch(/^\/files\/[0-9a-f-]{36}$/);
  // 下载内容为合法 zip（PK 魔数）
  const dl = await download(BASE, String(ready!.file_id));
  expect(dl.status).toBe(200);
  expect(dl.bytes!.length).toBeGreaterThan(0);
  expect(dl.bytes!.subarray(0, 2).toString('latin1')).toBe('PK');
  // 历史回放按业务会话可查（session_id 绑定断裂即在此红）
  const h = await history(sid);
  const files = (h.files ?? []) as Array<Record<string, unknown>>;
  const hit = files.find(f => f.file_id === ready!.file_id);
  expect(hit, '历史回放未按会话返回产出文件（file_asset.session_id 绑定断裂）').toBeTruthy();
  expect(hit!.file_name).toBe('e2e-oaf-agent.zip');
});

test('F6 inline 预览规则', async ({ request }) => {
  const uid = ids(`f6-${uniq()}`);
  const png = await upload(BASE, pngBytes(1), `pic-${uniq()}.png`, 'image/png', { userId: uid });
  const txt = await upload(BASE, textBytes('e2a'), `doc-${uniq()}.txt`, 'text/plain', { userId: uid });
  expect(png.status).toBe(200);
  expect(txt.status).toBe(200);
  const pngId = (png.body as Record<string, string>).file_id;
  const txtId = (txt.body as Record<string, string>).file_id;
  const r1 = await request.get(`/files/${pngId}?inline=1`);
  expect(r1.headers()['content-disposition']).toContain('inline');
  const r2 = await request.get(`/files/${txtId}?inline=1`);
  expect(r2.headers()['content-disposition']).toContain('inline');
  const r3 = await request.get(`/files/${txtId}`);
  expect(r3.headers()['content-disposition']).toContain('attachment');
  // 中文文件名 RFC5987
  const zh = await upload(BASE, textBytes('e2a'), `报告-${uniq()}.md`, 'text/markdown', { userId: uid });
  expect(zh.status).toBe(200);
  const zhId = (zh.body as Record<string, string>).file_id;
  const r4 = await request.get(`/files/${zhId}`);
  expect(r4.headers()['content-disposition']).toContain('filename*=UTF-8');
});

test('F7 下载负例', async ({ request }) => {
  expect((await request.get('/files/not-a-uuid')).status()).toBe(400);
  expect((await request.get(`/files/${crypto.randomUUID()}`)).status()).toBe(404);
});

test('F8 上传校验矩阵', async ({ request }) => {
  const uid = ids(`f8-${uniq()}`);
  const cases: Array<[string, Buffer, string, string, number, string]> = [
    ['空文件', Buffer.alloc(0), 'empty.txt', 'text/plain', 400, 'no_file_uploaded'],
    ['危险扩展名伪装白名单 MIME', textBytes('x'), 'evil.sh', 'text/plain', 400, 'extension_mime_mismatch'],
    ['非白名单 MIME', textBytes('x'), 'app.bin', 'application/x-sh', 415, 'unsupported_file_type'],
    ['扩展名 MIME 错配', pngBytes(1), 'mismatch.png', 'text/plain', 400, 'extension_mime_mismatch'],
    ['超限', pngBytes(21 * 1024), 'big.png', 'image/png', 413, 'file_too_large'],
  ];
  for (const [label, bytes, name, mime, expectStatus, expectErr] of cases) {
    const r = await upload(BASE, bytes, name, mime, { userId: uid });
    expect(r.status, label).toBe(expectStatus);
    expect((r.body as Record<string, string>).error, label).toBe(expectErr);
  }
  // pending 上限（429）仅在沙箱模式存在（pending 态由沙箱注入挂账产生）——见 api-sandbox X8
});

// F9（pending 用户隔离与 X-User-Id 优先级）依赖 pending 态——沙箱模式专属，见 api-sandbox X8

// ---------- H 组：HITL ----------

/** HITL 前置：approval MCP 要求表单确认后才能 submit（show→confirm 流程）；经 UI 代理预置状态 */
async function createConfirmedApp(base = BASE): Promise<string> {
  const app = await createApprovalApp(base);
  const res = await fetch(`${base}/mcp/approval/tools/confirm_application`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ arguments: { application_id: app, action: 'confirm' }, confirmed: true, userId: 'e2e-tester' }),
  });
  if (!res.ok) throw new Error(`confirm_application http ${res.status}`);
  return app;
}

test('H1 ask 挂起与状态', async ({ request }) => {
  const app = await createConfirmedApp();
  const sid = sessionIdFor(`h1-${uniq()}`);
  const stream = chat({ message: `[E2E:hitl:submit](${app})`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  const ask = stream.terminal;
  expect(ask?.type).toBe('permission_ask');
  const toolCalls = (ask as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>;
  expect(toolCalls[0].name).toBe('submit_application');
  const st = await status(sid);
  expect(st.state).toBe('waiting_confirm');
  const h = await pollUntil(
    async () => history(sid),
    value => (value.pendingConfirm as Record<string, unknown> | null)?.source === 'agent_state',
    10_000,
    200,
  );
  const pc = h.pendingConfirm as Record<string, unknown>;
  expect(pc).toBeTruthy();
  expect(((pc.tools as Array<Record<string, unknown>>)[0]).tool_call_id).toBe(toolCalls[0].tool_call_id);
  expect(pc.source).toBe('agent_state');
});

test('H2 批准（confirm-stream）', async () => {
  const app = await createConfirmedApp();
  const sid = sessionIdFor(`h2-${uniq()}`);
  const ask1 = chat({ message: `[E2E:hitl:submit](${app})`, userId: U, sessionId: sid });
  await waitTerminal(ask1);
  const tcid = ((ask1.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  await new Promise(r => setTimeout(r, 800)); // 等 ASK 段租约释放（confirm 抢锁竞态）
  const rec = confirmStream(sid, [{ tool_call_id: tcid, confirmed: true }]);
  await waitTerminal(rec);
  expect(rec.terminal?.type).toBe('done');
  const results = toolResults(rec.frames);
  expect(String((results[0] as Record<string, unknown>).state ?? '')).toBe('SUCCESS');
  // HITL 恢复流没有 TOOL_CALL_* 重放：RESULT_END 后必须补发调用摘要，再发结果预览
  const summary = rec.frames.find(f => f.type === 'tool_call_summary' && f.toolName === 'submit_application') as Record<string, unknown> | undefined;
  expect(summary, 'HITL 恢复流缺少 tool_call_summary 兜底帧').toBeTruthy();
  expect(String(summary!.summary)).toBe('执行 submit_application');
  expect(String(summary!.replyId ?? '')).not.toBe('');
  const preview = rec.frames.find(f => f.type === 'tool_result_preview' && f.toolName === 'submit_application') as Record<string, unknown> | undefined;
  expect(preview, 'HITL 恢复流缺少 tool_result_preview').toBeTruthy();
  expect(rec.frames.indexOf(summary!)).toBeLessThan(rec.frames.indexOf(preview!));
  // 兜底摘要走 emitSynthetic 落库：断连续传/刷新回放必须同样可见
  const replay = subscribe(sid, 0);
  await waitTerminal(replay);
  expect(replay.frames.some(f => f.type === 'tool_call_summary' && f.toolName === 'submit_application')).toBe(true);
  expect(replay.frames.some(f => f.type === 'tool_result_preview' && f.toolName === 'submit_application')).toBe(true);
  const h = await history(sid);
  expect(h.pendingConfirm ?? null).toBeNull();
  const tcs = (h.messages as Array<Record<string, unknown>>).flatMap(m => (m.tool_calls ?? []) as Array<Record<string, unknown>>);
  expect(tcs.some(t => t.name === 'submit_application' && t.state === 'success')).toBe(true);
});

test('H3 拒绝', async () => {
  const app = await createApprovalApp();
  const sid = sessionIdFor(`h3-${uniq()}`);
  const ask1 = chat({ message: `[E2E:hitl:submit](${app})`, userId: U, sessionId: sid });
  await waitTerminal(ask1);
  const tcid = ((ask1.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  await new Promise(r => setTimeout(r, 800)); // 等 ASK 段租约释放（confirm 抢锁竞态）
  const rec = confirmStream(sid, [{ tool_call_id: tcid, confirmed: false }]);
  await waitTerminal(rec);
  expect(rec.terminal?.type).toBe('done');
  const h = await history(sid);
  const tcs = (h.messages as Array<Record<string, unknown>>).flatMap(m => (m.tool_calls ?? []) as Array<Record<string, unknown>>);
  expect(tcs.some(t => t.name === 'submit_application' && t.state === 'denied')).toBe(true);
});

test('H4 重复确认 409', async () => {
  const app = await createConfirmedApp();
  const sid = sessionIdFor(`h4-${uniq()}`);
  const ask1 = chat({ message: `[E2E:hitl:submit](${app})`, userId: U, sessionId: sid });
  await waitTerminal(ask1);
  const tcid = ((ask1.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  await new Promise(r => setTimeout(r, 800)); // 等 ASK 段租约释放（confirm 抢锁竞态）
  const rec = confirmStream(sid, [{ tool_call_id: tcid, confirmed: true }]);
  await waitTerminal(rec);
  const again = await confirmSync(sid, [{ tool_call_id: tcid, confirmed: true }]);
  expect([409, 404]).toContain(again.status); // confirm_already_consumed / confirm_context_not_found
});

test('H5 同步 confirm 批准', async () => {
  const app = await createConfirmedApp();
  const sid = sessionIdFor(`h5-${uniq()}`);
  const ask1 = chat({ message: `[E2E:hitl:submit](${app})`, userId: U, sessionId: sid });
  await waitTerminal(ask1);
  const tcid = ((ask1.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  await new Promise(r => setTimeout(r, 800)); // 等 ASK 段租约释放（confirm 抢锁竞态）
const r = await confirmSync(sid, [{ tool_call_id: tcid, confirmed: true }]);
  expect(r.status).toBe(200);
  await pollUntil(async () => history(sid), (h) => {
    const tcs = (h.messages as Array<Record<string, unknown>>).flatMap(m => (m.tool_calls ?? []) as Array<Record<string, unknown>>);
    return tcs.some(t => t.name === 'submit_application' && t.state === 'success');
  }, 60_000);
});

test('H6 挂起期间新消息的行为（ASKING 态拒绝新 turn）', async () => {
  const app = await createConfirmedApp();
  const sid = sessionIdFor(`h6-${uniq()}`);
  const ask1 = chat({ message: `[E2E:hitl:submit](${app})`, userId: U, sessionId: sid });
  await waitTerminal(ask1);
  expect(ask1.terminal?.type).toBe('permission_ask');
  // 实测行为：ASKING 态下新 turn 被拒（error 帧说明需先审批），租约已让出但 agent 拒绝推进
  const next = chat({ message: `[E2E:plain]`, userId: U, sessionId: sid });
  await waitTerminal(next);
  expect(next.terminal?.type).toBe('error');
  expect(String((next.terminal as Record<string, unknown>).error)).toContain('ASKING');
  const st = await status(sid);
  expect(st.state).toBe('waiting_confirm'); // 挂起项保持
});

test('H8 UI 代理 ask 拦截', async ({ request }) => {
  const app = await createApprovalApp();
  const res = await request.post('/mcp/approval/tools/submit_application', {
    data: { arguments: { application_id: app }, userId: U },
  });
  expect(res.status()).toBe(403);
  const body = await res.json();
  expect(body.needsConfirm).toBe(true);
  expect((body.toolCalls as Array<Record<string, unknown>>)[0].name).toBe('submit_application');
});

// ---------- M 组：MCP Apps ----------

test('M1 TOOL_CALL_START 携带 ui 元数据', async () => {
  const app = await createApprovalApp();
  const sid = sessionIdFor(`m1-${uniq()}`);
  const stream = chat({ message: `[E2E:mcpapp:form](${app})`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  const tc = stream.frames.find(f => f.type === 'TOOL_CALL_START' && f.toolName === 'show_application_form') as Record<string, unknown> | undefined;
  expect(tc, '未收到 show_application_form TOOL_CALL_START').toBeTruthy();
  const ui = tc!.ui as Record<string, string>;
  expect(ui.resourceUri).toBe('ui://approval/application-form.html');
  expect(ui.server).toBe('approval');
  expect(stream.terminal?.type).toBe('done');
});

test('M2/M3 UI 资源拉取与列表', async ({ request }) => {
  const ui = await request.get('/mcp/approval/resources/ui?uri=ui://approval/application-form.html');
  expect(ui.status()).toBe(200);
  const body = await ui.json();
  expect(String(body.html)).toContain('<');
  expect(body.mimeType).toContain('mcp-app');
  const list = await request.get('/mcp/approval/resources');
  expect(list.status()).toBe(200);
  expect(JSON.stringify(await list.json())).toContain('ui://approval/application-form.html');
});

test('M4 卡片工具代理（app_only 经代理可调）', async ({ request }) => {
  const app = await createApprovalApp();
  const res = await request.post('/mcp/approval/tools/confirm_application', {
    data: { arguments: { application_id: app, action: 'confirm' }, confirmed: true, userId: U },
  });
  expect([200, 201]).toContain(res.status());
});

test('M5 ui_context 静默注入系统提示', async () => {
  const sid = sessionIdFor(`m5-${uniq()}`);
  const mark = `UICTX-MARK-${uniq()}`;
  const app = await createApprovalApp();
  const form = await fetch(`${BASE}/mcp/ui-context`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sessionId: sid, content: mark }),
  });
  expect([200, 201, 204]).toContain(form.status);
  const stream = chat({ message: `[E2E:mcpapp:form](${app})`, userId: U, sessionId: sid });
  await waitTerminal(stream);
  const stats = await llmStats();
  // 按 scenario 取本测试自己的调用（同 F1/F2：后台合成调用共用 stats 通道）
  const call = stats.calls.filter(c => c.scenario === 'mcpapp-form').pop();
  expect(call).toBeTruthy();
  expect(String(call!.systemContent)).toContain(mark); // UiContextInjectionHook 注入直证
});

test('M6 cards server appOnly 标记', async ({ request }) => {
  const tools = await (await request.get('/tools')).json();
  const str = JSON.stringify(tools);
  expect(str).toContain('"appOnly":true');
});

// ---------- A 组：A2A ----------

test('A1 A2A message/send', async () => {
  const r = await a2a('message/send', {
    message: {
      kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
      parts: [{ kind: 'text', text: `[E2E:plain]` }],
      metadata: { userId: U, sessionId: sessionIdFor(`a1-${uniq()}`) },
    },
  });
  expect(r.status).toBe(200);
  expect(r.json.result ?? r.json.error).toBeTruthy();
  expect(r.json.error).toBeUndefined();
});

test('A2 A2A message/stream', async ({ request }) => {
  const res = await request.post('/', {
    data: {
      jsonrpc: '2.0', id: 2, method: 'message/stream',
      params: {
        message: {
          kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
          parts: [{ kind: 'text', text: `[E2E:plain]` }],
          metadata: { userId: U, sessionId: sessionIdFor(`a2-${uniq()}`) },
        },
      },
    },
  });
  expect(res.status()).toBe(200);
  const text = await res.text();
  expect(text.length).toBeGreaterThan(10);
});

test('A3/A4 tasks/get 路由与 A2A 限制声明', async () => {
  const r = await a2a('tasks/get', { id: 'nonexistent-task' });
  expect(r.status).toBe(200);
  expect(r.json.error === undefined || r.json.error?.code !== -32601).toBe(true); // 方法已路由（非 Method not found）
});

// A5（e5d6ff3 回归门禁·A2A 面）：S7 只钉了 Channel 链路的 llm-calls 反查，
// A2A 链路此前零断言——SessionKeyResolver 在 A2A 下应把记录落在调用方 metadata.sessionId
//（SessionKeyResolver.java 链路表；单测 LlmLoggingMiddlewareTest#shouldPreferSessionIdOnA2ALink），
// 解析回归（记录落共享桶/别的键 → 按 sid 查询恒空）在门禁不可见。复用 plain 夹具，无 mock 扩展。
test('A5 A2A message/stream 后 llm-calls 按 metadata sessionId 反查非空', async ({ request }) => {
  const sid = sessionIdFor(`a5-${uniq()}`);
  const res = await request.post('/', {
    data: {
      jsonrpc: '2.0', id: 5, method: 'message/stream',
      params: {
        message: {
          kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
          parts: [{ kind: 'text', text: `[E2E:plain]` }],
          metadata: { userId: U, sessionId: sid },
        },
      },
    },
  });
  expect(res.status()).toBe(200);
  const text = await res.text(); // blocking：流读完即任务终态（A2 同款完成 barrier）
  expect(text).toContain('data:'); // SSE 形态（A2AController.convertToSse → ServerSentEvent）

  type LlmCallsBody = {
    session_id: string;
    calls: Array<{
      call_id: string; timestamp: number;
      request: { messages: Array<{ role: string; content: string }> };
      response: { usage: Record<string, number> };
    }>;
  };
  const body = await pollUntil(
    async () => (await request.get(`/threads/${sid}/llm-calls`)).json() as LlmCallsBody,
    b => (b.calls?.length ?? 0) > 0,
  );
  // 回显查询键：记录键口径 = 规范会话 id（docs/api.md llm-calls 节）
  expect(body.session_id).toBe(sid);
  const call = body.calls[0];
  expect(String(call.call_id)).toContain('call-');
  expect(Number(call.timestamp)).toBeGreaterThan(0);
  // 记录的是模型输入消息：A2A 文本 part 原样进 user 消息（role 为 SDK 枚举大写 'USER'）
  expect(call.request.messages.some(m => m.role === 'USER' && m.content.includes('[E2E:plain]'))).toBe(true);
  // usage 契约（LlmLoggingMiddleware：三键恒在，数值可为 0）
  for (const k of ['input_tokens', 'output_tokens', 'total_tokens']) {
    expect(Number(call.response.usage[k]), `usage.${k}`).toBeGreaterThanOrEqual(0);
  }
});

// A6（e5d6ff3 串桶回归·A2A 面，数据正确性）：两个 A2A 会话（message/send，A1 同款面）各带
// run 内唯一 token，互查双方记录。两道判据对应两类回归形态：
// ① 记录退化到共享桶（gw-hash/"global"）或互换/错路由到对方会话键——按 sid 精确查询与
//    末段归一回退（LLMLogger.java:43-49）均未命中，GET 恒返回 []，红在 callsOf 的
//    pollUntil 超时（超时消息携带最后观测 calls:[]）；
// ② 串写进仍可按 sid 反查到的桶（双写/归一末段碰撞/回退命中对方桶）——非空收敛通过，
//    红在交叉 token 断言（not.toContain 对方 token）。
// 两 sid 末段（a6a-/a6b- + uniq）互异（LLMLogger.java:54-60 归一规则）：既保证①不发生
// 回退误命中，也保证②的负向断言不被回退误伤。
test('A6 双 A2A 会话 llm-calls 互查不串桶', async ({ request }) => {
  const sidA = sessionIdFor(`a6a-${uniq()}`);
  const sidB = sessionIdFor(`a6b-${uniq()}`);
  const tokenA = `A6A-${uniq()}`;
  const tokenB = `A6B-${uniq()}`;
  const send = (sid: string, token: string) => a2a('message/send', {
    message: {
      kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
      parts: [{ kind: 'text', text: `[E2E:plain] ${token}` }],
      metadata: { userId: U, sessionId: sid },
    },
  });
  for (const [sid, token] of [[sidA, tokenA], [sidB, tokenB]] as const) {
    const r = await send(sid, token);
    expect(r.status).toBe(200);
    expect(r.json.error).toBeUndefined();
  }
  const callsOf = (sid: string) => pollUntil(
    async () => (await request.get(`/threads/${sid}/llm-calls`)).json() as { session_id: string; calls: unknown[] },
    b => (b.calls?.length ?? 0) > 0,
  );
  const bodyA = await callsOf(sidA); // 判据①：共享桶/错键形态在此超时红
  const bodyB = await callsOf(sidB);
  expect(bodyA.session_id).toBe(sidA);
  expect(bodyB.session_id).toBe(sidB);
  expect(JSON.stringify(bodyA)).toContain(tokenA);
  expect(JSON.stringify(bodyA), 'B 的内容落进了 A 的桶').not.toContain(tokenB); // 判据②
  expect(JSON.stringify(bodyB)).toContain(tokenB);
  expect(JSON.stringify(bodyB), 'A 的内容落进了 B 的桶').not.toContain(tokenA); // 判据②
});

// A7（查询契约负例·无 LLM，MOD 组风格）：未运行过的会话查 llm-calls 恒空——为 A5/A6 的
// "非空"锚定意义：端点不得对任意 sid 全局倾倒记录（LLMLogger.getCalls 退化为全量返回时在此红）。
// ThreadController 无会话存在性校验，未知 sid 固定 200 + 空数组（ThreadController.java:354）。
test('A7 llm-calls 未知会话恒空（查询契约）', async ({ request }) => {
  const ghost = sessionIdFor(`a7-${uniq()}`);
  const res = await request.get(`/threads/${ghost}/llm-calls`);
  expect(res.status()).toBe(200);
  const body = await res.json() as { session_id: string; calls: unknown[] };
  expect(body.session_id).toBe(ghost);
  expect(body.calls).toEqual([]);
});

// ---------- FW 组：/threads slot 双形态匹配 + Flyway（76e0e74/512a6b3，e2e-ci-plan §5.10） ----------
// 契约权威：ThreadController#AGENT_STATE_JOIN（SUBSTRING_INDEX 冒号前/后段双向匹配，
// ThreadController.java:58-60）与 db/migration/V6__backfill_session_user_from_agent_state.sql。
// 此前 S7 只钉可见性与列表移除（详情仅断 status===200、删除只断列表移除），对 updated_at
// 溯源与删除孤儿零断言：回退旧 LIKE 前缀实现 → FW1（规范臂）红；删冒号前段臂 → FW2 红；
// baseline-version 抬高/V6 守卫回归 → FW3 红。DB 直查走 mysql2（archive-seed.ts 同款连接
// 方式与地址解析顺序），两臂 COUNT 与被测 SQL 同口径，不引入新的黑盒面。

/** DB 直连（archive-seed.ts 同款：显式 env > .runtime/env.json > 内置默认，解析失败抛错） */
async function openDb(): Promise<mysql.Connection> {
  let jdbc = process.env.MYSQL_URL ?? '';
  if (!jdbc) {
    try {
      const envJson = JSON.parse(
        fs.readFileSync(path.join(process.env.E2E_RUNTIME_DIR ?? '.runtime', 'env.json'), 'utf8')) as { mysqlUrl?: string };
      jdbc = envJson.mysqlUrl ?? '';
    } catch { /* env.json 缺失/坏 JSON：回落内置默认 */ }
  }
  if (!jdbc) jdbc = 'jdbc:mysql://127.0.0.1:3306/agent_framework_e2e';
  const m = /jdbc:mysql:\/\/([^:/]+):(\d+)\/([^?]+)/.exec(jdbc);
  if (!m) throw new Error(`MYSQL_URL 解析失败: ${jdbc}`);
  return mysql.createConnection({
    host: m[1], port: Number(m[2]), database: m[3],
    user: process.env.MYSQL_USER ?? 'e2e', password: process.env.MYSQL_PASS ?? 'e2e-pass',
    multipleStatements: true,
  });
}

/** agent_state 中冒号前/后段任一命中 key 的行数（与 ThreadController SUBSTRING_INDEX 双臂同口径） */
async function slotRowCount(key: string): Promise<number> {
  const conn = await openDb();
  try {
    const [rows] = await conn.query(
      `SELECT COUNT(*) c FROM agent_state
       WHERE SUBSTRING_INDEX(session_id, ':', 1) = ? OR SUBSTRING_INDEX(session_id, ':', -1) = ?`, [key, key]);
    return Number((rows as Array<{ c: number }>)[0].c);
  } finally { await conn.end(); }
}

test('FW1 A2A 规范槽位（{userId}:{sid}）列表/详情可见、删除无孤儿行', async () => {
  const sid = sessionIdFor(`fw1-${uniq()}`);
  const uid = ids(`fw1-u-${uniq()}`);
  const r = await a2a('message/send', {
    message: {
      kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
      parts: [{ kind: 'text', text: `[E2E:plain](fw1-${uniq()})` }],
      metadata: { userId: uid, sessionId: sid },
    },
  });
  expect(r.status).toBe(200);
  expect(r.json.error).toBeUndefined();

  // 槽位形态实证：A2A 链路 agent_state 落规范形态 {uid}:{sid}（SessionKeyResolver 链路表原值透传）；
  // 旧 LIKE 前缀匹配对它恒落空 → 详情 updated_at 恒 ''、删除留孤儿（76e0e74 修复的两症状，
  // 旧实现回退只被本用例钉：老形态 slot {peer}:{gw-hash} 恰在 LIKE 前缀臂命中范围内，FW2 钉不住该回归）
  const slots = await pollUntil(async () => {
    const conn = await openDb();
    try {
      const [rows] = await conn.query(
        `SELECT session_id FROM agent_state WHERE SUBSTRING_INDEX(session_id, ':', -1) = ?`, [sid]);
      return rows as Array<{ session_id: string }>;
    } finally { await conn.end(); }
  }, rows => rows.length > 0, 30_000);
  expect(slots.some(x => x.session_id === `${uid}:${sid}`), 'A2A 应写规范形态槽位 {uid}:{sid}').toBe(true);

  // 列表可见（session_user 由 A2AController 会话开始前 upsert）
  await pollUntil(async () => threads(), list => JSON.stringify(list).includes(sid), 30_000);

  // 详情 updated_at 非空：规范槽位经 SUBSTRING_INDEX 末段臂命中（旧实现恒 ''，匹配回归即在此红）
  const one = await (await fetch(`${BASE}/threads/${encodeURIComponent(sid)}`)).json() as Record<string, unknown>;
  expect(String(one.updated_at ?? ''), '详情 updated_at 应取自 agent_state（末段臂命中）').not.toBe('');
  expect(one.user_id).toBe(uid);

  // 删除：规范槽位行级联清理，不留孤儿（旧实现漏删 → updated_at 残留非空、DB 计数>0）
  expect(await deleteThread(sid)).toBeLessThan(300);
  await pollUntil(async () => threads(), list => !JSON.stringify(list).includes(sid), 15_000); // S7 同款权威信号
  const oneAfter = await (await fetch(`${BASE}/threads/${encodeURIComponent(sid)}`)).json() as Record<string, unknown>;
  expect(String(oneAfter.updated_at ?? ''), '删除后 updated_at 应为空（孤儿残留即在此红）').toBe('');
  expect(await slotRowCount(sid), 'agent_state 不得残留 sid 孤儿行').toBe(0);
});

test('FW2 老 Channel 形态槽位种子：列表/详情 updated_at 取自 agent_state、删除无孤儿', async () => {
  // 老 Channel 形态 {peer}:{gw-hash} 只在存量/网关链路出现，e2e 对话写不出+固定时间戳
  // 组合——直插种子做值级断言（HA3/archive-seed 同模式）。两轨时间戳刻意错开：
  // 本用例钉的是"冒号前段臂被删"类回归（退化 exact-only/后段-only）：此时 LEFT JOIN 不命中
  // → SELECT 列 MAX(a.updated_at)=NULL → updated_at 落空串路径（ThreadController.java:179），
  // toContain(STATE_TS) 红；回退旧 LIKE 前缀实现钉不住本用例（LIKE 前缀恰命中老形态，归 FW1）。
  // ORDER BY 的 COALESCE(created_at) 只影响排序不进断言；session_user 轨时间是
  // NOT NULL 列的哨兵值，与 agent_state 轨错开仅为来源可区分。
  const uid = ids(`fw2-u-${uniq()}`);
  const peer = `fw2-peer-${uniq()}`;
  const slot = `${peer}:gwk-${uniq()}`;
  const STATE_TS = '2026-08-08 08:08:08';   // agent_state 轨（JOIN 命中时的期望值）
  const SU_TS = '2026-01-02 03:04:05.000';  // session_user 轨（NOT NULL 哨兵值，非断言期望）
  const conn = await openDb();
  try {
    // state_data 不含 $.session_id/$.user_id：任何后续重启的 V6 回填都因形状条件（V6:47-48）跳过该行
    await conn.query(
      `INSERT INTO session_user (session_id, user_id, remark, created_at, updated_at) VALUES (?, ?, ?, ?, ?);
       INSERT INTO agent_state (session_id, state_key, item_index, state_data, created_at, updated_at)
       VALUES (?, 'agent_state', 0, '{"messages":[]}', ?, ?)`,
      [peer, uid, `FW2种子-${peer}`, SU_TS, SU_TS, slot, '2026-01-02 03:04:05', STATE_TS]);
  } finally { await conn.end(); }

  // 列表：updated_at 必须取自 agent_state（冒号前段臂命中）；臂被删 → NULL → 空串 → 红
  const row = await pollUntil(async () => {
    const res = await fetch(`${BASE}/threads?userId=${encodeURIComponent(uid)}`);
    const rows = await res.json() as Array<Record<string, unknown>>;
    return rows.find(x => x.session_id === peer);
  }, x => !!x, 15_000);
  expect(String(row!.updated_at ?? ''), '列表 updated_at 应取自 agent_state（冒号前段臂）').toContain(STATE_TS);

  // 详情：同一冒号前段臂（getThread 元信息 SQL）；Timestamp.toString() 尾部 ".0" 用 toContain 吸收
  const one = await (await fetch(`${BASE}/threads/${encodeURIComponent(peer)}`)).json() as Record<string, unknown>;
  expect(String(one.updated_at ?? ''), '详情 updated_at 应取自 agent_state（冒号前段臂）').toContain(STATE_TS);
  expect(one.user_id).toBe(uid);

  // 删除：老形态槽位行一并清理（冒号前段臂），列表/详情/DB 三面无残留
  expect(await deleteThread(peer)).toBeLessThan(300);
  await pollUntil(async () => threads(), list => !JSON.stringify(list).includes(peer), 15_000);
  const oneAfter = await (await fetch(`${BASE}/threads/${encodeURIComponent(peer)}`)).json() as Record<string, unknown>;
  expect(String(oneAfter.updated_at ?? ''), '删除后老形态槽位行必须消失').toBe('');
  expect(await slotRowCount(peer), 'agent_state 不得残留老形态孤儿行').toBe(0);
  const conn2 = await openDb();
  try {
    const [su] = await conn2.query('SELECT COUNT(*) c FROM session_user WHERE session_id = ?', [peer]);
    expect(Number((su as Array<{ c: number }>)[0].c), 'session_user 行必须已删').toBe(0);
  } finally { await conn2.end(); }
});

test.describe('FW3 Flyway V6 存量回填（baseline 升级路径，MEM 组同款第二实例）', () => {
  const runtimeDir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
  const FW_PORT = String(Number(new URL(BASE).port) + 12); // 避开 MEM(+10)/multi(+1/+2)
  const FW_BASE = `http://127.0.0.1:${FW_PORT}`;
  const START_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), '../scripts/start-agent.sh');

  const uidPos = `fw3-pos-u-${uniq()}`;
  const sidPos = `fw3-pos-sid-${uniq()}`;
  // sid 侧守卫种子（钉 V6:50 c.sid<>'unknown'）：slot 尾段必须就是 'unknown'（V6:13 注释的
  // "无主残留"形态）——否则在形状条件 V6:47 就被过滤、永远到不了 :50，守卫没被行使
  const uidNoSid = `fw3-sg-u-${uniq()}`;
  // uid 侧守卫种子（钉 V6:49 c.uid<>'unknown'）：slot 首段=unknown、JSON user_id='unknown'（对称形态）
  const sidNoUid = `fw3-ug-sid-${uniq()}`;
  const uidClaimA = `fw3-cla-u-${uniq()}`;
  const uidClaimB = `fw3-clb-u-${uniq()}`;
  const sidShared = `fw3-shared-sid-${uniq()}`; // 同 sid 两个 uid 认领 = 共享 gw-hash 桶形态（V6:51 守卫）
  const STATE_TS = '2026-08-08 08:08:08';

  test.beforeAll(async () => {
    // 1) 种子（模拟升级前存量：只在 agent_state、session_user 不登记）+ 清 Flyway 历史表。
    // DB/Redis 地址由 start-agent.sh 经继承 env / env.json 解析（与主实例同库，MEM 同款）。
    const conn = await openDb();
    try {
      await conn.query(
        `INSERT INTO agent_state (session_id, state_key, item_index, state_data, created_at, updated_at) VALUES
         (?, 'agent_state', 0, ?, '2026-01-02 03:04:05', ?),
         (?, 'agent_state', 0, ?, '2026-01-02 03:04:05', ?),
         (?, 'agent_state', 0, ?, '2026-01-02 03:04:05', ?),
         (?, 'agent_state', 0, ?, '2026-01-02 03:04:05', ?),
         (?, 'agent_state', 0, ?, '2026-01-02 03:04:05', ?);
         DROP TABLE IF EXISTS flyway_schema_history;`,
        [`${uidPos}:${sidPos}`, JSON.stringify({ session_id: sidPos, user_id: uidPos, messages: [] }), STATE_TS,
         `${uidNoSid}:unknown`, JSON.stringify({ session_id: 'unknown', user_id: uidNoSid, messages: [] }), STATE_TS,
         `unknown:${sidNoUid}`, JSON.stringify({ session_id: sidNoUid, user_id: 'unknown', messages: [] }), STATE_TS,
         `${uidClaimA}:${sidShared}`, JSON.stringify({ session_id: sidShared, user_id: uidClaimA, messages: [] }), STATE_TS,
         `${uidClaimB}:${sidShared}`, JSON.stringify({ session_id: sidShared, user_id: uidClaimB, messages: [] }), STATE_TS]);
    } finally { await conn.end(); }
    // 2) 第二实例：schema 非空 + 历史表缺失 → baseline(5) + 仅跑 V6（现网升级同路径，
    // application.yml flyway.baseline-on-migrate/baseline-version=5）
    const r = spawnSync('bash', [START_SCRIPT, 'fwv6', FW_PORT], { encoding: 'utf8', timeout: 150_000 });
    if (r.status !== 0) {
      throw new Error(`fwv6 实例启动失败 exit=${r.status}\nstdout: ${(r.stdout ?? '').slice(-400)}\nstderr: ${(r.stderr ?? '').slice(-800)}`);
    }
    // 显式等就绪：wait-ready 通过后仍有瞬断窗口（冷启动竞态），MEM 组同款
    const deadline = Date.now() + 60_000;
    for (;;) {
      try { if ((await fetch(`${FW_BASE}/health`)).status === 200) break; } catch { /* 未就绪 */ }
      if (Date.now() > deadline) throw new Error(`fwv6 实例 60s 内未就绪：${FW_BASE}/health`);
      await new Promise(r2 => setTimeout(r2, 500));
    }
  });

  test.afterAll(async () => {
    try {
      const pid = Number(fs.readFileSync(path.join(runtimeDir, 'agent-fwv6.pid'), 'utf8').trim());
      if (Number.isInteger(pid) && pid > 0) { try { process.kill(pid); } catch { /* 已退出 */ } }
    } catch { /* pid 文件缺失交由 env-down 的 agent-*.pid 通配兜底 */ }
    // 清理种子/回填行（回填产生的 session_user 行按业务 sid 删；守卫回归态的 'unknown' 行定点删；
    // try/catch 包裹，下轮 env-up reset-data 全量 DROP 兜底）
    try {
      const conn = await openDb();
      try {
        await conn.query(
          `DELETE FROM agent_state WHERE session_id IN (?, ?, ?, ?, ?);
           DELETE FROM session_user WHERE session_id IN (?, ?, ?) OR (session_id = 'unknown' AND user_id = ?);`,
          [`${uidPos}:${sidPos}`, `${uidNoSid}:unknown`, `unknown:${sidNoUid}`, `${uidClaimA}:${sidShared}`, `${uidClaimB}:${sidShared}`,
           sidPos, sidShared, sidNoUid, uidNoSid]);
      } finally { await conn.end(); }
    } catch { /* 清理失败交由下轮 reset-data 兜底 */ }
  });

  test('FW3 V6 回填：自洽规范槽位入列且 updated_at 溯源 agent_state；三道守卫行均不入列', async () => {
    // 正例：回填后列表可见，updated_at = 种子 agent_state 行的 MAX(updated_at)（末段臂 JOIN 命中）
    const row = await pollUntil(async () => {
      const res = await fetch(`${FW_BASE}/threads?userId=${encodeURIComponent(uidPos)}`);
      const rows = await res.json() as Array<Record<string, unknown>>;
      return rows.find(x => x.session_id === sidPos);
    }, x => !!x, 15_000);
    expect(String(row!.updated_at ?? ''), '回填会话列表 updated_at 应取自 agent_state').toContain(STATE_TS);
    // 守卫1（V6:50 c.sid<>'unknown'）：无主残留不回填——若守卫被删，该行会以
    // session_id='unknown'、user_id=uidNoSid 入列，在 uidNoSid 视图可见
    const noSid = await (await fetch(`${FW_BASE}/threads?userId=${encodeURIComponent(uidNoSid)}`)).json() as Array<Record<string, unknown>>;
    expect(noSid.find(x => x.session_id === 'unknown'), 'sid=unknown 无主残留不得回填（V6:50）').toBeUndefined();
    // 守卫2（V6:49 c.uid<>'unknown'）：无主行不认领——若守卫被删，(sidNoUid, 'unknown') 入列，
    // 在字面 'unknown' 用户视图可见
    const noUid = await (await fetch(`${FW_BASE}/threads?userId=unknown`)).json() as Array<Record<string, unknown>>;
    expect(noUid.find(x => x.session_id === sidNoUid), `uid=unknown 无主行不得回填（V6:49，sid=${sidNoUid}）`).toBeUndefined();
    // 守卫3（V6:51 claimants=1）：同 sid 双 uid 认领 = 共享 gw-hash 桶，两个视图都不得出现
    for (const uid of [uidClaimA, uidClaimB]) {
      const rows = await (await fetch(`${FW_BASE}/threads?userId=${encodeURIComponent(uid)}`)).json() as Array<Record<string, unknown>>;
      expect(rows.find(x => x.session_id === sidShared), `共享桶 ${uid} 不得回填（V6:51）`).toBeUndefined();
    }
    // 升级路径契约钉：历史表恰为 baseline(5)+V6——baseline-version 被抬高会在此红（V6 静默跳过形态）
    const conn = await openDb();
    try {
      const [hist] = await conn.query('SELECT version, success FROM flyway_schema_history ORDER BY installed_rank');
      const rows = hist as Array<{ version: string; success: number }>;
      expect(rows.map(x => x.version)).toEqual(['5', '6']);
      expect(rows.every(x => x.success === 1), '迁移历史必须全 success').toBe(true);
    } finally { await conn.end(); }
  });
});

// ---------- SK 组：用户技能（L4）管理面探针 ----------
// 说明：完整管理面场景（PUT/GET/DELETE/sync-from-package + A2A 生效性）见仓库根 e2e/user-skill-admin-e2e.sh
//（手工脚本，需平台已发布带 skills 的自建服务）；此处只钉住端点路由与响应契约，防路由写错静默合入。

test('SK1 用户技能索引与明细端点契约', async ({ request }) => {
  // 调试页数据源：与 /skills/users 同源，字段为 count/users
  const debugIdx = await request.get('/debug/user-skills');
  expect(debugIdx.status()).toBe(200);
  const idx = await debugIdx.json();
  expect(idx).toHaveProperty('count');
  expect(Array.isArray(idx.users)).toBe(true);
  expect(idx.count).toBe(idx.users.length);

  // REST 侧入口（同源）：/skills/users 不是 /skills/{name}/content 的歧义牺牲品
  const usersRes = await request.get('/skills/users');
  expect(usersRes.status()).toBe(200);
  const usersBody = await usersRes.json();
  expect(usersBody).toHaveProperty('users');

  // 明细：L4 无覆盖 + 包内无同名技能 → 404 not_found（而不是 500/200 空体）
  const ghost = await request.get(`/skills/users/${U}/ghost-skill-${uniq()}`);
  expect(ghost.status()).toBe(404);
  expect((await ghost.json()).error).toBe('not_found');

  // 参数校验：非法 userId → 400
  const bad = await request.get('/skills/users/.hidden/ghost');
  expect(bad.status()).toBe(400);
  expect((await bad.json()).error).toBe('invalid_user_id');
});

// ---------- SK 组：/skills/available 与 parse-refs 按用户合并 L4（d0c3eaa） ----------
// 合并语义（同名 L4 覆盖描述/独有补入/X-User-Id 头优先/删除回落）此前只有单测覆盖，
// e2e HTTP 黑盒面零断言（grep skills/available|parse-refs 于 tests/ 零命中）。

/** 写入用户 L4 技能主文件：description 承载 /available 合并视图的覆盖描述（l4Description 解析 frontmatter） */
async function putL4(request: APIRequestContext, uid: string, name: string, description: string): Promise<APIResponse> {
  const content = `---\nname: ${name}\ndescription: ${description}\n---\n\n# ${name}\n`;
  return request.put(`/skills/users/${encodeURIComponent(uid)}/${encodeURIComponent(name)}`, { data: { content } });
}

/** /available 返回顺序（全局目录序 + L4 追加）不参与契约，深比较前按 name 归一 */
const nameSorted = (arr: unknown) =>
  (arr as Array<Record<string, string>>).slice().sort((a, b) => (a.name < b.name ? -1 : 1));

test('SK2 /skills/available 按用户合并 L4：同名覆盖、独有补入、Header 优先、删除回落', async ({ request }) => {
  // 旧行为基线（不传 userId）：全局启用目录的 name+description 精简视图（SkillCatalogService.availableSkills()）
  const baseline = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
  expect(Array.isArray(baseline)).toBe(true);
  for (const e of baseline) expect(Object.keys(e).sort()).toEqual(['description', 'name']);
  const baseDemo = baseline.find(e => e.name === 'demo-skill');
  expect(baseDemo, 'fixture 目录应含 demo-skill（fixtures/agent-config/skills）').toBeTruthy();
  // 空串 userId 等价不传（effectiveUserId blank → 全局目录）
  expect(nameSorted(await (await request.get('/skills/available?userId=')).json()))
    .toEqual(nameSorted(baseline));

  const uidA = ids(`sk2-a-${uniq()}`);
  const uidB = ids(`sk2-b-${uniq()}`);
  // 无任何 L4 的用户：合并视图与全局目录全等（合并不得让 @ 候选缩水/变形）。
  // 注：KV 读取失败严格分支黑盒不可注入（WorkspaceReader 把枚举失败按空处理），此处钉其可达降级面
  expect(nameSorted(await (await request.get(`/skills/available?userId=${encodeURIComponent(uidB)}`)).json()))
    .toEqual(nameSorted(baseline));

  const l4Only = `e2e-l4-${uniq()}`;
  const markerA = `L4A覆盖-${uniq()}`;
  const onlyMarker = `L4独有-${uniq()}`;
  const markerB = `L4B覆盖-${uniq()}`;
  const putDemo = await putL4(request, uidA, 'demo-skill', markerA);
  expect(putDemo.status()).toBe(200);
  const putBody = await putDemo.json() as Record<string, unknown>;
  expect(putBody.action).toBe('created');
  expect(Number(putBody.version)).toBeGreaterThan(0);
  expect(((await (await putL4(request, uidA, l4Only, onlyMarker)).json()) as Record<string, unknown>).action).toBe('created');
  // uidB 写入必须显式断言成功：否则下方「X-User-Id 优先」的负向断言（hdr 不含 markerB）
  // 在 PUT 失败时自然成立，头部优先级实际未被行使（假绿窗口，SK1 注释所防的静默合入形态）
  const putB = await putL4(request, uidB, 'demo-skill', markerB);
  expect(putB.status()).toBe(200);
  expect(((await putB.json()) as Record<string, unknown>).action).toBe('created');

  // 合并视图：同名 L4 描述覆盖全局 + L4 独有技能补入
  const merged = await (await request.get(`/skills/available?userId=${encodeURIComponent(uidA)}`)).json() as Array<Record<string, string>>;
  expect(merged.find(e => e.name === 'demo-skill')?.description).toBe(markerA);
  expect(merged.find(e => e.name === l4Only)?.description).toBe(onlyMarker);

  // X-User-Id 头优先于 ?userId=（网关注入登录态优先）：返回 A 的视图且不混入 B 的 L4
  const hdr = await (await request.get('/skills/available', { headers: { 'X-User-Id': uidA }, params: { userId: uidB } })).json() as Array<Record<string, string>>;
  expect(hdr.find(e => e.name === 'demo-skill')?.description).toBe(markerA);
  expect(hdr.find(e => e.name === l4Only)?.description).toBe(onlyMarker);
  expect(JSON.stringify(hdr)).not.toContain(markerB);

  // 不传 userId 始终是全局目录：写入后公共视图仍不泄漏个人技能
  const globalAfter = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
  expect(globalAfter.find(e => e.name === 'demo-skill')?.description).toBe(baseDemo!.description);
  expect(JSON.stringify(globalAfter)).not.toContain(l4Only);

  // 删除回落：demo-skill 删除（有包内基线）→ 合并视图回落全局描述
  const del = await request.delete(`/skills/users/${encodeURIComponent(uidA)}/demo-skill`);
  expect(del.status()).toBe(200);
  expect(((await del.json()) as Record<string, unknown>).hasPackageBaseline).toBe(true);
  await pollUntil(async () => {
    const rows = await (await request.get(`/skills/available?userId=${encodeURIComponent(uidA)}`)).json() as Array<Record<string, string>>;
    return rows.find(e => e.name === 'demo-skill');
  }, e => e?.description === baseDemo!.description, 15_000);
  // 独有技能删除 → 从合并视图消失（无包内基线，该技能对该用户已不可见）
  expect((await request.delete(`/skills/users/${encodeURIComponent(uidA)}/${encodeURIComponent(l4Only)}`)).status()).toBe(200);
  await pollUntil(async () => {
    const rows = await (await request.get(`/skills/available?userId=${encodeURIComponent(uidA)}`)).json() as Array<Record<string, string>>;
    return rows.some(e => e.name === l4Only);
  }, gone => !gone, 15_000);
});

test('SK3 /skills/parse-refs 按用户合并解析 @Skill 引用', async ({ request }) => {
  const uid = ids(`sk3-${uniq()}`);
  const l4Name = `e2e-l4-${uniq()}`;
  expect(((await (await putL4(request, uid, l4Name, `L4引用-${uniq()}`)).json()) as Record<string, unknown>).action).toBe('created');

  const refs = async (message: string, userId?: string) => {
    const q = new URLSearchParams({ message });
    if (userId) q.set('userId', userId);
    const res = await request.get(`/skills/parse-refs?${q}`);
    expect(res.status()).toBe(200);
    return await res.json() as { skills: string[]; count: number };
  };

  // 全局技能 + L4 技能合并命中；重复引用去重保序；count 与 skills 一致
  const both = await refs(`@demo-skill 和 @${l4Name} 再 @demo-skill`, uid);
  expect(both.skills).toEqual(['demo-skill', l4Name]);
  expect(both.count).toBe(both.skills.length);

  // 不传 userId：L4 个人技能不可见（@ 注入链路的合并依赖生效 userId）
  expect(await refs(`@${l4Name}`)).toEqual({ skills: [], count: 0 });
  // 邮箱形态不误匹配（(?<![\w]) lookbehind）；目录外名称被 enabledSkillNames 过滤
  expect(await refs('联系 user@example.com', uid)).toEqual({ skills: [], count: 0 });
  expect(await refs('@no-such-skill-x', uid)).toEqual({ skills: [], count: 0 });
  // 中文紧邻 @ 合法（技能名收尾于串尾，避免中文字符类把后续中文吞进名字）
  expect((await refs(`用@${l4Name}`, uid)).skills).toEqual([l4Name]);
});

test('SK4 禁用的包内技能不进 /available 与 parse-refs（恢复原状）', async ({ request }) => {
  // 前置自愈：上轮异常残留禁用态则先拨回（toggle 对称，.skill-states.json 实时生效）
  const st0 = (await (await request.get('/skills/manage')).json() as Array<Record<string, unknown>>)
    .find(s => s.name === 'demo-skill');
  expect(st0, 'fixture 目录应含 demo-skill').toBeTruthy();
  if (st0!.enabled === false) await request.put('/skills/demo-skill/toggle');

  const uid = ids(`sk4-${uniq()}`); // 纯全局视图用户（无 L4 覆盖）
  expect((((await (await request.put('/skills/demo-skill/toggle')).json()) as Record<string, unknown>)).enabled).toBe(false);
  try {
    // 无 userId 视图剔除
    let rows = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
    expect(rows.some(e => e.name === 'demo-skill')).toBe(false);
    // 带 userId 合并视图同样剔除（该用户无同名 L4 → 不得复活）
    rows = await (await request.get(`/skills/available?userId=${encodeURIComponent(uid)}`)).json() as Array<Record<string, string>>;
    expect(rows.some(e => e.name === 'demo-skill')).toBe(false);
    // @ 引用解析同源（enabledSkillNames）：禁用技能不再命中
    const refs = await (await request.get(`/skills/parse-refs?${new URLSearchParams({ message: '@demo-skill 演示一下', userId: uid })}`)).json() as { skills: string[]; count: number };
    expect(refs).toEqual({ skills: [], count: 0 });
  } finally {
    await request.put('/skills/demo-skill/toggle'); // 恢复启用，不污染后续用例/project
  }
  const st1 = (await (await request.get('/skills/manage')).json() as Array<Record<string, unknown>>)
    .find(s => s.name === 'demo-skill');
  expect(st1!.enabled).toBe(true);
  const rows = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
  expect(rows.some(e => e.name === 'demo-skill')).toBe(true);
});

// ---------- HA 组：压缩归档历史（#45，docs/session-history-archive-design.md） ----------
// 契约权威：ThreadController#threadHistory（includeArchived/limit/beforeId → hasMore/nextBeforeId、
// origin 双源标记、role=compaction 合成项）。e2e 实例归档默认开启（application.yml
// AGENT_HISTORY_ARCHIVE_ENABLED:true，start-agent.sh 未覆盖）——对话 write-through 归档，
// 合并视图是真默认路径，此前用例对它零断言。

type HistoryMsg = Record<string, unknown>;

/** 带 query 的 history 查询（lib/client.js 的 history 不带参） */
async function historyQ(sid: string, query = ''): Promise<Record<string, unknown>> {
  const res = await fetch(`${BASE}/threads/${encodeURIComponent(sid)}/history${query ? `?${query}` : ''}`);
  expect(res.status, 'history HTTP 状态').toBe(200);
  return res.json() as Promise<Record<string, unknown>>;
}

const userTexts = (h: Record<string, unknown>) =>
  ((h.messages ?? []) as HistoryMsg[]).filter(m => m.role === 'user').map(m => String(m.content));

test('HA1 未压缩会话 history 合并视图契约与双源一致', async () => {
  const sid = sessionIdFor(`ha1-${uniq()}`);
  const first = `ha1-first-${uniq()}`;
  const second = `ha1-second-${uniq()}`;
  for (const arg of [first, second]) {
    const s = chat({ message: `[E2E:plain](${arg})`, userId: U, sessionId: sid });
    await waitTerminal(s);
    expect(s.terminal?.type).toBe('done');
  }
  // 合并视图（includeArchived 默认 true）：等两轮 user 消息都可见（吸收落库时序）
  const merged = await pollUntil(async () => historyQ(sid), h => userTexts(h).length >= 2);
  expect(merged.hasMore).toBe(false);
  expect(merged.nextBeforeId ?? null).toBeNull();
  const msgs = merged.messages as HistoryMsg[];
  expect(msgs.length).toBeGreaterThan(0);
  const seen = new Set<string>();
  for (const m of msgs) {
    expect(String(m.msg_id ?? '')).not.toBe('');
    expect(['archive', 'state'], JSON.stringify(m)).toContain(m.origin);
    seen.add(String(m.msg_id));
  }
  expect(seen.size, '合并视图 msg_id 不得重复（双源去重回归即在此红）').toBe(msgs.length);
  for (const m of msgs) expect(m.origin, '未压缩会话应全部 origin=archive').toBe('archive');
  // 内容双源一致：带标记的 user 消息在两视图同序同文
  const markerOf = (ts: string[]) => ts.filter(t => t.includes('ha1-'));
  expect(markerOf(userTexts(merged))).toEqual([`[E2E:plain](${first})`, `[E2E:plain](${second})`]);
  const stateOnly = await historyQ(sid, 'includeArchived=false');
  expect(stateOnly.hasMore).toBe(false);
  expect(stateOnly.nextBeforeId ?? null).toBeNull();
  expect(markerOf(userTexts(stateOnly))).toEqual(markerOf(userTexts(merged)));
  // origin 是合并视图专属键：state-only 视图不得携带
  for (const m of (stateOnly.messages ?? []) as HistoryMsg[]) expect('origin' in m).toBe(false);
});

test('HA2 beforeId 游标全量遍历：不丢页、不重复、末页收敛', async () => {
  const sid = sessionIdFor(`ha2-${uniq()}`);
  for (const arg of [`ha2-a-${uniq()}`, `ha2-b-${uniq()}`]) {
    const s = chat({ message: `[E2E:plain](${arg})`, userId: U, sessionId: sid });
    await waitTerminal(s);
    expect(s.terminal?.type).toBe('done');
  }
  const full = await pollUntil(async () => historyQ(sid), h => userTexts(h).length >= 2);
  expect(full.hasMore).toBe(false); // 小会话在默认 200 条内，首页即全量
  const fullIds = ((full.messages ?? []) as HistoryMsg[]).map(m => String(m.msg_id));

  // limit=1 逐页向后翻（游标 = 本页最旧归档行 id），直到末页
  const pageIds: string[] = [];
  let cursor: number | null = null;
  for (let i = 0; i < 50; i++) {
    const page = await historyQ(sid, cursor === null ? 'limit=1' : `limit=1&beforeId=${cursor}`);
    const msgs = (page.messages ?? []) as HistoryMsg[];
    expect(msgs.length, `第 ${i + 1} 页不得为空`).toBeGreaterThanOrEqual(1);
    for (const m of msgs) pageIds.push(String(m.msg_id));
    if (!page.hasMore) {
      expect(page.nextBeforeId ?? null, '末页不得再给游标').toBeNull();
      break;
    }
    expect(typeof page.nextBeforeId).toBe('number');
    const next = Number(page.nextBeforeId);
    if (cursor !== null) expect(next, '游标必须严格递减').toBeLessThan(cursor);
    cursor = next;
  }
  // 全量遍历恰好覆盖首页视图：不丢页（游标跳行）也不重复（跨页去重失效）
  expect(pageIds.length).toBe(fullIds.length);
  expect(new Set(pageIds)).toEqual(new Set(fullIds));
  // 分页游标是合并视图专属：state-only 视图忽略 limit 恒 hasMore=false
  const statePaged = await historyQ(sid, 'includeArchived=false&limit=1');
  expect(statePaged.hasMore).toBe(false);
});

test('HA3 压缩摘要分隔条 + 未归档尾部双源合并 + 深翻页只走归档行', async () => {
  const sid = sessionIdFor(`ha3-${uniq()}`);
  const mark = uniq();
  const tailArg = `ha3-tail-${mark}`;
  // 1) 真实一轮对话充当"未归档尾部"（write-through 也会归档它，下一步删其归档行）
  const s = chat({ message: `[E2E:plain](${tailArg})`, userId: U, sessionId: sid });
  await waitTerminal(s);
  expect(s.terminal?.type).toBe('done');
  // 2) 种子：清本会话归档行 → 直插压缩前历史 + __compaction_summary__
  const seed = await seedCompactionArchive(`${U}:${sid}`, sid, mark, U);

  // 3) 首页合并视图：归档基底升序 + 摘要分隔条 + state 尾部合入基底末尾
  const full = await historyQ(sid);
  const msgs = (full.messages ?? []) as HistoryMsg[];
  const m1i = msgs.findIndex(m => m.msg_id === seed.m1);
  const sum = msgs.find(m => m.msg_id === seed.summaryId);
  const m3i = msgs.findIndex(m => m.msg_id === seed.m3);
  expect(m1i, '压缩前用户消息(归档)缺失').toBeGreaterThanOrEqual(0);
  expect(sum, '压缩摘要分隔条合成项缺失').toBeTruthy();
  expect(m3i, '压缩前助手消息(归档)缺失').toBeGreaterThanOrEqual(0);
  expect(m1i).toBeLessThan(msgs.indexOf(sum!));
  expect(msgs.indexOf(sum!)).toBeLessThan(m3i); // 归档行按 id 升序为时间线基底
  expect(sum!.role).toBe('compaction');
  expect(sum!.type).toBe('compaction_summary');
  expect(String(sum!.content)).toContain(`压缩摘要-${mark}`);
  expect(String(sum!.created_at ?? '')).toContain('2026-09-27 10:00');
  expect('origin' in sum!, '分隔条合成项不标 origin').toBe(false);
  expect(msgs.find(m => m.msg_id === seed.m1)!.origin).toBe('archive');
  expect(msgs.find(m => m.msg_id === seed.m3)!.origin).toBe('archive');
  const tail = msgs.find(m => m.origin === 'state' && String(m.content).includes(tailArg));
  expect(tail, '未归档尾部必须以 origin=state 合入基底末尾').toBeTruthy();
  expect(msgs.indexOf(tail!)).toBeGreaterThan(m3i);
  const idsAll = msgs.map(m => String(m.msg_id));
  expect(new Set(idsAll).size, '全视图 msg_id 不得重复').toBe(idsAll.length);
  expect(full.hasMore).toBe(false);
  expect(full.nextBeforeId ?? null).toBeNull();

  // 4) 翻页（limit=2）：首页 = 最新 2 条归档 + state 尾部；游标指向本页最旧归档行
  const page1 = await historyQ(sid, 'limit=2');
  const p1 = (page1.messages ?? []) as HistoryMsg[];
  expect(page1.hasMore).toBe(true);
  expect(typeof page1.nextBeforeId).toBe('number');
  expect(p1.some(m => m.msg_id === seed.summaryId), '分隔条在中间页同样渲染').toBe(true);
  expect(p1.some(m => m.origin === 'state' && String(m.content).includes(tailArg)),
    '首页(beforeId=null)才合入 state 尾部').toBe(true);
  expect(p1.some(m => m.msg_id === seed.m1), 'limit=2 首页不得包含更早归档行').toBe(false);

  // 5) 深翻页只走归档行：尾部不得再现，游标恰落在 [m1]
  const page2 = await historyQ(sid, `limit=2&beforeId=${Number(page1.nextBeforeId)}`);
  const p2 = (page2.messages ?? []) as HistoryMsg[];
  expect(p2.map(m => String(m.msg_id))).toEqual([seed.m1]);
  expect(p2[0].origin).toBe('archive');
  expect(p2.some(m => 'origin' in m && m.origin === 'state'), '深翻页不得合入 state 消息').toBe(false);
  expect(p2.some(m => String(m.content).includes(tailArg)), '深翻页不得再现尾部').toBe(false);
  expect(page2.hasMore).toBe(false);
  expect(page2.nextBeforeId ?? null).toBeNull();

  await deleteThread(sid).catch(() => undefined); // 级联清理种子归档行（S7 同款）
});

// ---------- MEM 组：记忆总开关关断分支（AGENT_MEMORY_ENABLED=false） ----------
// 覆盖缺口：e2e tests/scripts/mock/config 对 AGENT_MEMORY_ENABLED 零命中（grep exit=1）。
// 整组包进 describe：文件级 beforeAll 会在 S1 之前拉起第二 JVM 且 spawn 失败炸全文件——
// describe 级 hooks（workers=1 串行）恰在 HA 组之后、MEM1 之前触发，爆炸半径限于本组。
test.describe('MEM 记忆关断', () => {
  const runtimeDir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
  const MEM_PORT = process.env.E2E_MEMOFF_PORT ?? String(Number(new URL(BASE).port) + 10);
  const MEM_BASE = `http://127.0.0.1:${MEM_PORT}`;
  const START_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), '../scripts/start-agent.sh');
  const MEMORY_TOOLS = ['memory_search', 'memory_get', 'memory_save']; // HarnessAgentFactory.java:52

  let memoffPid: number | null = null;

  test.beforeAll(async () => {
    // start-agent.sh 内联 env 白名单（LLM_BASE_URL 等）之外原样透传子进程：
    // AGENT_MEMORY_ENABLED=false 经 spawnSync env 命中 application.yml:111 relaxed binding
    const r = spawnSync('bash', [START_SCRIPT, 'memoff', MEM_PORT], {
      encoding: 'utf8', timeout: 150_000,
      env: { ...process.env, AGENT_MEMORY_ENABLED: 'false' },
    });
    if (r.status !== 0) {
      throw new Error(`memory-off 实例启动失败 exit=${r.status}\nstdout: ${(r.stdout ?? '').slice(-400)}\nstderr: ${(r.stderr ?? '').slice(-800)}`);
    }
    try {
      const pid = Number(fs.readFileSync(path.join(runtimeDir, 'agent-memoff.pid'), 'utf8').trim());
      if (Number.isInteger(pid) && pid > 0) memoffPid = pid;
    } catch { /* pid 文件缺失交由用例内请求失败暴露 */ }
    // 显式等就绪：wait-ready 通过后仍有瞬断窗口（冷启动竞态），beforeAll 自证 /health 200
    const deadline = Date.now() + 60_000;
    for (;;) {
      try {
        if ((await fetch(`${MEM_BASE}/health`)).status === 200) break;
      } catch { /* 未就绪，继续轮询 */ }
      if (Date.now() > deadline) throw new Error(`memory-off 实例 60s 内未就绪：${MEM_BASE}/health`);
      await new Promise(r => setTimeout(r, 500));
    }
  });

  test.afterAll(async () => {
    if (memoffPid !== null) { try { process.kill(memoffPid); } catch { /* 已退出 */ } }
  });

  test('MEM1 记忆关断实例 /tools 契约：sdkInternal 无 memory_*，默认实例对照组在', async ({ request }) => {
    const off = await (await fetch(`${MEM_BASE}/tools?includeInternal=true`)).json() as {
      sdkInternal?: Array<{ name: string }>;
    };
    // fail-soft 容错：sdkInternal 枚举异常时返回空列表（InternalToolRegistry fail-soft）——
    // 先断言在字段，避免 TypeError 掩盖『端点异常』与『工具仍注册』两种故障的区分
    expect(Array.isArray(off.sdkInternal), `关断实例 /tools sdkInternal 段异常：${JSON.stringify(off).slice(0, 200)}`).toBe(true);
    const offSdk = (off.sdkInternal ?? []).map(t => t.name);
    for (const name of MEMORY_TOOLS) {
      expect(offSdk, `${name} 在关断实例仍注册（disableMemoryTools 未生效/false 分支装配回归）`).not.toContain(name);
    }
    expect(offSdk, '关记忆不得误伤文件系统内置工具').toContain('read_file');
    const plainView = await (await fetch(`${MEM_BASE}/tools`)).json();
    expect(JSON.stringify(plainView), '默认视图 MCP 工具不受记忆开关影响').toContain('bench_echo');

    // 对照组：默认实例（AGENT_MEMORY_ENABLED 缺省 true）必须暴露 memory_* ——防两向回归：
    // 对照缺失=默认被误读为关；关断实例出现=false 分支未生效
    const base = await request.get('/tools?includeInternal=true');
    expect(base.status()).toBe(200);
    const baseBody = await base.json() as { sdkInternal?: Array<{ name: string }> };
    expect(Array.isArray(baseBody.sdkInternal), '默认实例 /tools sdkInternal 段异常（fail-soft 空列表）').toBe(true);
    const baseSdk = (baseBody.sdkInternal ?? []).map(t => t.name);
    for (const name of MEMORY_TOOLS) {
      expect(baseSdk, `默认实例缺少 ${name}（对照组失效：默认值或 sdkInternal 暴露通道回归）`).toContain(name);
    }
  });

  test('MEM2 关断实例 [E2E:plain] 对话正常且无记忆提取后台 LLM 调用（卫生级）', async () => {
    const sid = sessionIdFor(`mem2-${uniq()}`);
    const stream = chat({ message: `[E2E:plain]`, userId: 'e2e-memoff', sessionId: sid, base: MEM_BASE });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');
    // 冷实例首帧可能是 AGENT_START（session_created 非首帧契约），只断对话链路完整可用
    expect(textOf(stream.frames).length).toBeGreaterThan(2);
    const h = await history(sid, MEM_BASE);
    expect(h._http === undefined || h._http === 200).toBe(true);

    // 文件级 beforeEach llmReset 给出干净窗口；mock 为两实例共享、全局窗口
    const stats = await llmStats();
    expect(stats.calls.some(c => c.scenario === 'plain'), '主对话未到达 mock（实例/路由错配）').toBe(true);
    // 记忆提取判据（llm-server.mjs）：无 system 或 system 含 'memory extraction assistant'
    // → background-synth。标题生成自带 system（SessionTitleService）归入 plain，不误伤。
    // ⚠卫生级信号：flush 走 throttled 触发（默认 10 分钟节流），即便 false 分支回归，
    // 秒级窗口内大概率也不产生 background-synth（空转通过）；确定性拦截在 MEM1（工具注册）
    // 与 MEM3（分支日志）。共享窗口无来源实例字段，BASE 异步 flush 理论可落窗误红（概率低）。
    const bg = stats.calls.filter(c => c.scenario === 'background-synth');
    expect(bg, `窗口内出现 background-synth（若源于本实例即 hooks 未关，亦可能为共享窗口污染，见注释）：${JSON.stringify(bg).slice(0, 300)}`).toHaveLength(0);
  });

  test('MEM3 关断分支装配日志直证（disableMemoryHooks/disableMemoryTools 分支执行）', async () => {
    const health = await fetch(`${MEM_BASE}/health`);
    expect(health.status).toBe(200);
    // 该日志行是 memoryEnabled=false else 分支的唯一可观测出口（HarnessAgentFactory），
    // agent @Bean 启动即装配，/health 就绪时必已落盘；
    // 日志经 start-agent.sh 重定向 + env-up.sh 渲染的控制台 logback 落盘
    const log = fs.readFileSync(path.join(runtimeDir, 'logs', 'agent-memoff.log'), 'utf8');
    expect(log).toContain('Memory fully disabled');
  });
});

// ---------- RD 组：Redis 前缀隔离（AGENT_REDIS_PREFIX，d31cd93，e2e-ci-plan §5.11） ----------
// 覆盖缺口：e2e tests/scripts/mock/lib 对 AGENT_REDIS_* 零命中（grep exit=1）。
// 多 Agent 共用 oaf-redis 时前缀切分 sess:*/sbx:guard:* key（docs/redis-cluster-prefix-design.md §1）；
// 断言面 = 同 Redis 双实例（默认无前缀 vs e2e-isolated 前缀）同 sessionId+userId 的
// /status 与 /subscribe 互不可见（前缀只切 Redis key：共享 MySQL 的 history/threads 列表面
// 【不】隔离是设计内语义，刻意不断言）。describe 包裹 + spawnSync 仿 MEM 组
//（workers=1 组内串行，爆炸半径限于本组）。
test.describe('RD Redis 前缀隔离', () => {
  const runtimeDir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
  const RD_PORT = process.env.E2E_RD_PREFIX_PORT ?? String(Number(new URL(BASE).port) + 11);
  const RD_BASE = `http://127.0.0.1:${RD_PORT}`;
  const RD_PREFIX = 'e2e-isolated'; // 不含 { }（hash tag 语法被 AgentRedisProperties 校验拒绝）
  const START_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), '../scripts/start-agent.sh');

  let rdpfxPid: number | null = null;

  test.beforeAll(async () => {
    // start-agent.sh 内联 env（VAR=val nohup java）不清空父环境：AGENT_REDIS_PREFIX 原样透传
    // 命中 application.yml 的 AGENT_REDIS_PREFIX 占位符（MEM 组 AGENT_MEMORY_ENABLED 同一透传路径）
    const r = spawnSync('bash', [START_SCRIPT, 'rdpfx', RD_PORT], {
      encoding: 'utf8', timeout: 150_000,
      env: { ...process.env, AGENT_REDIS_PREFIX: RD_PREFIX },
    });
    if (r.status !== 0) {
      throw new Error(`prefixed 实例启动失败 exit=${r.status}\nstdout: ${(r.stdout ?? '').slice(-400)}\nstderr: ${(r.stderr ?? '').slice(-800)}`);
    }
    try {
      const pid = Number(fs.readFileSync(path.join(runtimeDir, 'agent-rdpfx.pid'), 'utf8').trim());
      if (Number.isInteger(pid) && pid > 0) rdpfxPid = pid;
    } catch { /* pid 文件缺失交由用例内请求失败暴露 */ }
    // 显式等就绪：wait-ready 通过后仍有瞬断窗口（冷启动竞态），MEM 组同款
    const deadline = Date.now() + 60_000;
    for (;;) {
      try { if ((await fetch(`${RD_BASE}/health`)).status === 200) break; } catch { /* 未就绪 */ }
      if (Date.now() > deadline) throw new Error(`prefixed 实例 60s 内未就绪：${RD_BASE}/health`);
      await new Promise(r => setTimeout(r, 500));
    }
  });

  test.afterAll(async () => {
    if (rdpfxPid !== null) { try { process.kill(rdpfxPid); } catch { /* 已退出 */ } }
  });

  /** 回放业务帧的最大 seq（done 为 Tailer 补发合成帧，不计） */
  const maxSeqOf = (frames: Array<Record<string, unknown> & { type: string }>) =>
    Math.max(0, ...frames.filter(f => f.type !== 'done').map(f => Number((f as { seq?: number }).seq ?? 0)));

  test('RD1 同 Redis 双实例前缀隔离：事件流与断线续传互不可见', async () => {
    const sid = sessionIdFor(`rd1-${uniq()}`);
    // turn1 落默认实例（无前缀）：写 sess:{sid}:events（seq 在 append 时分配，里程碑
    // AGENT_END 触发整批同管道刷出——waitTerminal 后 latest_event_seq 即终局值，可精确对账）
    const a = chat({ message: `[E2E:plain]`, userId: 'e2e-rd', sessionId: sid });
    await waitTerminal(a);
    expect(a.terminal?.type).toBe('done');
    const aSeq = Number((await status(sid)).latest_event_seq ?? 0);
    expect(aSeq, '默认实例事件流为空（主实例自身回归）').toBeGreaterThan(0);

    // 前缀实例同名会话此刻必须全空：前缀失效时此处读到 A 的流即红（决定性断言）
    const before = await status(sid, RD_BASE);
    expect(before.state).toBe('idle');
    expect(Number(before.latest_event_seq ?? -1)).toBe(0);
    // 前缀实例断线续传空关流（tailer 首轮立即探测空流即补 done），A 的帧一条读不到
    const emptySub = subscribe(sid, 0, RD_BASE);
    await emptySub.closed;
    expect(emptySub.frames.filter(f => f.type !== 'done'), '前缀实例续传读到 A 的帧（隔离失效）').toHaveLength(0);

    // turn2 同 sessionId+userId 落前缀实例：写 e2e-isolated:sess:{sid}:events，seq 独立发号
    const b = chat({ message: `[E2E:plain]`, userId: 'e2e-rd', sessionId: sid, base: RD_BASE });
    await waitTerminal(b);
    expect(b.terminal?.type).toBe('done');
    const bSeq = Number((await status(sid, RD_BASE)).latest_event_seq ?? 0);
    expect(bSeq).toBeGreaterThan(0);

    // 双向续传回放恰好只含各自的流：max seq 精确相等（串流即溢出）
    const subA = subscribe(sid, 0);
    await subA.closed;
    seqMonotonic(subA.frames);
    expect(subA.frames[subA.frames.length - 1].type).toBe('done');
    expect(maxSeqOf(subA.frames), 'A 的回放混入 B 的帧').toBe(aSeq);
    const subB = subscribe(sid, 0, RD_BASE);
    await subB.closed;
    seqMonotonic(subB.frames);
    expect(subB.frames[subB.frames.length - 1].type).toBe('done');
    expect(maxSeqOf(subB.frames), 'B 的回放混入 A 的帧').toBe(bSeq);
    // A 的流不因 B 的 turn 变化
    expect(Number((await status(sid)).latest_event_seq ?? -1)).toBe(aSeq);
  });

  test('RD2 删除会话前缀互不影响：DELETE 只清本实例命名空间', async () => {
    const sid = sessionIdFor(`rd2-${uniq()}`);
    const a = chat({ message: `[E2E:plain]`, userId: 'e2e-rd', sessionId: sid });
    await waitTerminal(a);
    expect(a.terminal?.type).toBe('done');
    const aSeq = Number((await status(sid)).latest_event_seq ?? 0);
    const b = chat({ message: `[E2E:plain]`, userId: 'e2e-rd', sessionId: sid, base: RD_BASE });
    await waitTerminal(b);
    expect(b.terminal?.type).toBe('done');
    const bSeq = Number((await status(sid, RD_BASE)).latest_event_seq ?? 0);
    expect(aSeq).toBeGreaterThan(0);
    expect(bSeq).toBeGreaterThan(0);

    // 前缀实例删会话：只清 e2e-isolated:sess:{sid}:* 两个 key（RedisEventLog.deleteSession
    // 经 facade.key 前缀）+ 共享 MySQL 行（前缀只切 Redis key，两实例都会删——设计内语义）
    expect(await deleteThread(sid, RD_BASE)).toBe(200);
    expect(Number((await status(sid, RD_BASE)).latest_event_seq ?? -1)).toBe(0); // 自己的流已清
    expect(Number((await status(sid)).latest_event_seq ?? -1),
      '前缀实例 DELETE 波及默认实例事件流（deleteSession 未走前缀）').toBe(aSeq);
    // 默认实例断线续传不受对方删除破坏
    const subA = subscribe(sid, 0);
    await subA.closed;
    expect(subA.frames[subA.frames.length - 1].type).toBe('done');
    expect(maxSeqOf(subA.frames)).toBe(aSeq);
    // 反向：默认实例删除只清自己（双向语义闭环）
    expect(await deleteThread(sid)).toBe(200);
    expect(Number((await status(sid)).latest_event_seq ?? -1)).toBe(0);
  });

  test('RD3 前缀配置生效直证：门面启动日志 prefix 字段（含冒号规范化）', () => {
    // RedisConnectionFacade.create 的 standalone 摘要日志是前缀落地的唯一进程级出口，
    // /health 就绪时 bean 已装配、日志必已落盘（MEM3 同款日志直证手法）
    const pfxLog = fs.readFileSync(path.join(runtimeDir, 'logs', 'agent-rdpfx.log'), 'utf8');
    expect(pfxLog, '前缀实例日志缺 RedisClient configured 行').toContain('RedisClient configured');
    expect(pfxLog).toContain('mode=standalone');
    expect(pfxLog).toContain(`prefix="${RD_PREFIX}:"`); // normalizedPrefix 自动补冒号
    // 对照组：默认实例（env-up 未注入前缀）必须 prefix=""——防前缀 env 泄漏进主实例
    const baseLog = fs.readFileSync(path.join(runtimeDir, 'logs', 'agent-a.log'), 'utf8');
    expect(baseLog).toContain('prefix=""');
  });
});
