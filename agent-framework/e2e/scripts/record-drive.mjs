#!/usr/bin/env node
/**
 * LLM 录件驱动器（e2e-ci-plan §4.1.1）：对运行中的 agent-framework 逐场景发起对话，
 * 经录制代理聚合真实 LLM 回复为 mock/fixtures/llm/<scenario>.json。
 *
 * 用法：
 *   node scripts/record-drive.mjs --base http://127.0.0.1:8200 --recorder http://127.0.0.1:18091 \
 *        --model mimo-v2.5 --scenarios plain,tool-echo,hitl-submit
 *
 * 校验失败（未得到期望工具调用/终态帧）即退出非零 —— 宁可重录，不留坏夹具。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const arg = (name, dflt) => { const i = process.argv.indexOf('--' + name); return i > 0 ? process.argv[i + 1] : dflt; };
const BASE = arg('base', 'http://127.0.0.1:8200');
const RECORDER = arg('recorder', 'http://127.0.0.1:18091');
const MODEL = arg('model', '');
const ONLY = (arg('scenarios', '') || '').split(',').map(s => s.trim()).filter(Boolean);
const FIXTURE_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'mock', 'fixtures', 'llm');

/** 场景注册表：录制与回放共用语义。fixture 参数即测试侧固定输入（e2e-ci-plan §4.1.3）。 */
export const SCENARIOS = {
  plain:        { turns: [{ message: '请一字不差地原样重复这句话：端到端链路畅通，测试正常。' }] },
  remember:     { turns: [{ message: '请记住我们的暗号：琥珀计划。只需回复：已记住。' }] },
  recall:       { turns: [{ message: '我们约定的暗号是什么？只回复暗号本身，不要说别的。' }], sameSessionAs: 'remember' },
  'tool-echo':  { turns: [{ message: '请调用 echo 工具，参数 text 为：端到端回显内容。', tool: 'echo' }] },
  'tool-time':  { turns: [{ message: '请调用 get_current_time 工具，参数 timezone 为 Asia/Shanghai，并把结果告诉我。', tool: 'get_current_time' }] },
  'tool-write': { turns: [{ message: '请调用 write_file 工具创建文件 e2e-notes/record.txt，内容为：录制写入测试。完成后简短确认。', tool: 'write_file' }] },
  'tool-read':  { turns: [{ message: '请调用 read_file 工具读取 uploads/note.txt 的完整内容并原样复述。', tool: 'read_file', upload: { name: 'note.txt', content: 'e2a-note-content-v1', expectInReply: 'e2a-note-content-v1' } }] },
  'file-deliver': { turns: [{ message: '请先用 write_file 创建 report.md（内容为：# E2E 交付报告），然后用 present_file 工具把 report.md 交付给我。', tools: ['write_file', 'present_file'] }] },
  'tool-mcp-echo': { turns: [{ message: '请调用 bench_echo 工具（MCP），参数 text 为：mcp连通性检查。完成后告诉我结果。', tool: 'bench_echo' }] },
  'mcpapp-form': { turns: [{ message: '请调用 show_application_form 工具展示申请表单，参数 application_id 为：{{APP_ID}}。', tool: 'show_application_form', appId: true }] },
  // 批准后的收尾文本单独录制（录制环境模型对 submit_application 附传 null 参数，agent 侧硬校验无法即时批准；
  // 回放侧参数已在录制时剥除，CI 中批准可正常执行）。夹具拼接：hitl-submit.calls=[ask, closing], variants.denied=拒绝收尾
  'hitl-closing': { turns: [{ message: '用户的申请单已经成功提交进入人工审批环节。请用一句话告知用户：申请已成功提交，等待审批结果即可。' }] },
  'hitl-submit': { turns: [{ message: '把申请单 {{APP_ID}} 提交进入人工审批流程：调用 submit_application 工具，只传 application_id 这一个参数，不要传任何其他参数。', tool: 'submit_application', terminal: 'permission_ask', appId: true }], hitl: true },
  execute:      { turns: [{ message: '请在沙箱中执行命令：echo hello-e2a，并告诉我完整输出。', tool: 'execute' }] },
  'execute-fail': { turns: [{ message: '请在沙箱中执行命令：ls /nonexistent-e2a-dir，并告诉我执行结果（失败也要告诉我）。', tool: 'execute' }] },
  'sandbox-write': { turns: [{ message: '请调用 write_file 工具创建文件 data/out.txt，内容为：沙箱落盘验证。完成后简短确认。', tool: 'write_file' }] },
  'sandbox-read':  { turns: [{ message: '请调用 read_file 工具读取 data/out.txt 的完整内容并原样复述。', tool: 'read_file' }] },
};

// ---------- SSE 客户端（POST /threads/chat）----------
async function chat({ message, sessionId, userId, fileIds, timeoutMs = 180000 }) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  const frames = [];
  try {
    const res = await fetch(`${BASE}/threads/chat`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, signal: ac.signal,
      body: JSON.stringify({ message, userId, sessionId, fileIds }),
    });
    if (!res.ok) throw new Error(`chat http ${res.status}`);
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        let line = buf.slice(0, idx); buf = buf.slice(idx + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        if (line.startsWith('data:')) { try { frames.push(JSON.parse(line.slice(5).trim())); } catch { /* 心跳等 */ } }
      }
    }
  } finally { clearTimeout(timer); }
  return frames;
}

async function confirm(sessionId, toolCallId, confirmed) {
  const res = await fetch(`${BASE}/threads/${encodeURIComponent(sessionId)}/confirm`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ results: [{ tool_call_id: toolCallId, confirmed }] }),
  });
  if (!res.ok && res.status !== 409) throw new Error(`confirm http ${res.status}`);
  return res.status;
}
const textOf = frames => frames.filter(f => f.type === 'TEXT_BLOCK_DELTA').map(f => f.delta).join('');
// /threads/chat 正常结束 = AGENT_END 后流关闭（无显式 done 帧）；done 由 /subscribe Tailer 补发。
const terminal = frames => {
  const explicit = frames.find(f => ['done', 'error', 'permission_ask'].includes(f.type));
  if (explicit) return explicit;
  const last = frames[frames.length - 1];
  return last?.type === 'AGENT_END' ? { type: 'done' } : undefined;
};
const toolNames = frames => frames.filter(f => f.type === 'TOOL_CALL_START').map(f => f.toolName);
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function recorderCtl(op, body) {
  const res = await fetch(`${RECORDER}/${op}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body ?? {}) });
  if (!res.ok) throw new Error(`recorder ${op} http ${res.status}`);
}

const failures = [];
async function createApprovalApp() {
  const res = await fetch(`${BASE}/mcp/approval/tools/create_application`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ arguments: { title: 'E2E 审批申请', description: 'e2e 录制用申请' }, userId: 'e2e-recorder' }),
  });
  if (!res.ok) throw new Error(`create_application http ${res.status}`);
  const m = JSON.stringify(await res.json()).match(/APP-[A-Za-z0-9_-]+/);
  if (!m) throw new Error('create_application 响应中未找到 application_id');
  return m[0];
}

async function recordScenario(name, spec, sessions) {
  const sid = `rec-${name}-${Date.now()}`;
  const sessionId = spec.sameSessionAs && sessions[spec.sameSessionAs] ? sessions[spec.sameSessionAs] : sid;
  sessions[name] = sessionId;
  // 需要 application_id 的场景：预创建申请，录制件中该 id 被改写为 {{appId}} 占位符
  let appId = null, rewrite = [];
  if (spec.turns.some(t => t.appId)) {
    appId = await createApprovalApp();
    rewrite = [[appId, '{{appId}}']];
  }
  // mimo 模型对 approval MCP 工具会附传 "content": null，MCP schema 校验拒绝——录制件中剥除
  rewrite.push([', \\"content\\": null', ''], [', \\"content\\":null', '']);
  await recorderCtl('begin', { scenario: name, model: MODEL, rewrite });
  const allFrames = [];
  for (const turn of spec.turns) {
    const message = (turn.message ?? '').replace('{{APP_ID}}', appId ?? '');
    // 需要前置上传的场景：先经 /files/upload 注入工作区，再携 fileIds 对话（读到的才是成功流）
    let fileIds;
    if (turn.upload) {
      const fd = new FormData();
      fd.append('file', new Blob([turn.upload.content], { type: 'text/plain' }), turn.upload.name);
      fd.append('userId', 'e2e-recorder');
      fd.append('sessionId', sessionId);
      const up = await (await fetch(`${BASE}/files/upload`, { method: 'POST', body: fd })).json();
      if (!up.file_id) throw new Error(`[${name}] 前置上传失败: ${JSON.stringify(up)}`);
      fileIds = [up.file_id];
    }
    const frames = await chat({ message, sessionId, userId: 'e2e-recorder', fileIds });
    allFrames.push(...frames);
    const t = terminal(frames);
    const wantT = turn.terminal ?? 'done';
    if (!t || t.type !== wantT) throw new Error(`[${name}] 终态=${t?.type ?? '无'} 期望=${wantT} 帧=${JSON.stringify(frames.slice(-4))}`);
    const called = toolNames(frames);
    const wantTools = turn.tools ?? (turn.tool ? [turn.tool] : []);
    for (const wt of wantTools) if (!called.includes(wt)) throw new Error(`[${name}] 未调用工具 ${wt}，实际=${called}`);
    if (turn.upload?.expectInReply) {
      const text = frames.filter(f => f.type === 'TEXT_BLOCK_DELTA').map(f => f.delta).join('');
      if (!text.includes(turn.upload.expectInReply)) throw new Error(`[${name}] 回复未包含期望文件内容「${turn.upload.expectInReply}」，实际=${text.slice(0, 120)}`);
    }
    if (textOf(frames).length < 2 && wantTools.length === 0) throw new Error(`[${name}] 文本过短`);
  }
  if (spec.hitl) {
    // ask 段已随上方 flush 捕获（content:null 已剥除）；不再即时批准——拼接批准收尾 + 补录拒绝变体
    await recordHitlVariants(name);
  }
  console.log(`[drive] ${name} ✔`);
}

/** HITL 夹具拼装：calls=[ask(call0), 批准收尾(hitl-closing)]，variants.denied=拒绝收尾（完整录制）。 */
async function recordHitlVariants(name) {
  const sid = `rec-${name}-deny-${Date.now()}`;
  const appId = await createApprovalApp();
  await recorderCtl('begin', { scenario: `${name}-deny`, model: MODEL, rewrite: [[appId, '{{appId}}'], [', \\"content\\": null', ''], [', \\"content\\":null', '']] });
  const frames = await chat({ message: SCENARIOS[name].turns[0].message.replace('{{APP_ID}}', appId), sessionId: sid, userId: 'e2e-recorder' });
  const ask = frames.find(f => f.type === 'permission_ask');
  if (!ask) throw new Error(`[${name}-deny] 未得到 permission_ask`);
  const tcid = ask.tool_calls?.[0]?.tool_call_id;
  if (!tcid) throw new Error(`[${name}-deny] permission_ask 缺 tool_call_id`);
  await sleep(300);
  await confirm(sid, tcid, false);
  for (let i = 0; i < 30; i++) {
    await sleep(1000);
    const h = await (await fetch(`${BASE}/threads/${encodeURIComponent(sid)}/history`)).json();
    const tools = (h.messages ?? []).flatMap(m => m.tool_calls ?? []);
    if (tools.some(t => t.state === 'denied')) break;
    if (i === 29) throw new Error(`[${name}-deny] history 未体现 denied`);
  }
  await recorderCtl('flush');
  await sleep(1500); // 场景间缓冲：迟到流落定，避免污染下一场景段
  // 拼装：calls=[ask（首个含真实 tool_call 的调用）, 批准收尾(hitl-closing)]，variants.denied=拒绝收尾
  const hasRealToolCall = c => (c.chunks ?? []).some(ch => ch.includes('"tool_calls": [') || ch.includes('"tool_calls":{'));
  const main = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, `${name}.json`), 'utf8'));
  const closing = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, 'hitl-closing.json'), 'utf8'));
  const deny = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, `${name}-deny.json`), 'utf8'));
  const askCall = main.calls.find(hasRealToolCall) ?? main.calls[0];
  main.calls = [askCall, closing.calls[closing.calls.length - 1]];
  main.variants = { denied: deny.calls[deny.calls.length - 1] };
  fs.writeFileSync(path.join(FIXTURE_DIR, `${name}.json`), JSON.stringify(main, null, 1));
  fs.unlinkSync(path.join(FIXTURE_DIR, `${name}-deny.json`));
}

const sessions = {};
for (const [name, spec] of Object.entries(SCENARIOS)) {
  if (ONLY.length && !ONLY.includes(name)) continue;
  try { await recordScenario(name, spec, sessions); } catch (e) { failures.push(name); console.error(`[drive] ${name} ✘ ${e.message}`); }
}
// 不 /stop：录制器生命周期归编排脚本管（多次分批录制复用同一实例）
if (failures.length) { console.error(`[drive] 失败场景：${failures.join(', ')}`); process.exit(1); }
console.log('[drive] 全部完成');
