/**
 * e2e-core API 组：S 基础 / F 文件 / H HITL / M MCP Apps / A A2A（e2e-ci-plan §5）。
 * 协议权威：docs/api-thread-spec.md v1.0。
 */
import { test, expect } from '@playwright/test';
import { BASE, BENCH_MCP, ids, sessionIdFor } from '../lib/env.js';
import { chat, status, subscribe, history, threads, deleteThread, patchThread, a2a, llmStats, llmReset, createApprovalApp, confirmStream, confirmSync } from '../lib/client.js';
import { waitTerminal, textOf, toolNames, toolResults, pollUntil } from '../lib/matchers.js';
import { seqMonotonic } from '../lib/sse.js';
import { upload, download, textBytes, pngBytes } from '../lib/files.js';

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
  // /tools 仅列 MCP 工具（内置工具不经此端点暴露）
  expect(toolStr).toContain('bench_echo');           // bench MCP（read_only）
  expect(toolStr).toContain('show_application_form'); // approval MCP（ui.tools）
  expect(toolStr).toContain('confirm_application');
  expect(toolStr).toContain('appOnly');
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
  const patch = await patchThread(sid, { title: `e2e-title-${uniq()}` });
  expect([200, 204]).toContain(patch.status);
  const llmCalls = await request.get(`/threads/${sid}/llm-calls`);
  expect(llmCalls.status()).toBe(200);
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
  const call = stats.calls[stats.calls.length - 1];
  expect(JSON.stringify(call.roles)).toContain('assistant');
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
  const call = stats.calls[stats.calls.length - 1];
  expect(call.hasImageBlock).toBe(true); // ImageBlock → OpenAI image_url 内联
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
  const h = await history(sid);
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
  const call = stats.calls[stats.calls.length - 1];
  expect(String(call.systemContent)).toContain(mark); // UiContextInjectionHook 注入直证
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
