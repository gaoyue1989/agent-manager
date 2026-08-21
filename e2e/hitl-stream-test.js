const http = require('http');
const fs = require('fs');
const path = require('path');

const BASE = 'http://localhost:8100';
const DIR = '/tmp/opencode/e2e-hitl-' + Date.now().toString(36);
fs.mkdirSync(DIR, { recursive: true });

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function GET(u) { return new Promise((ok, no) => { http.get(u, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => ok({ s: r.statusCode, b: d })); }).on('error', no); }); }
function POST(u, body) { return new Promise((ok, no) => { const u2 = new URL(u); const d = JSON.stringify(body); const req = http.request({ hostname: u2.hostname, port: u2.port, path: u2.pathname, method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(d) } }, r => { let b = ''; r.on('data', c => b += c); r.on('end', () => ok({ s: r.statusCode, b })); }); req.on('error', no); req.write(d); req.end(); }); }
function SSE(u, body, to = 120000) { return new Promise(ok => { const u2 = new URL(u); const d = JSON.stringify(body); const frames = []; const req = http.request({ hostname: u2.hostname, port: u2.port, path: u2.pathname, method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(d) } }, r => { let b = ''; r.on('data', c => { b += c.toString(); const ls = b.split('\n'); b = ls.pop() || ''; for (const l of ls) { if (!l.startsWith('data:')) continue; const x = l.slice(5).trim(); if (!x) continue; try { frames.push(JSON.parse(x)); } catch (e) {} } }); r.on('end', () => ok({ s: r.statusCode, f: frames })); r.on('error', () => ok({ s: 0, f: frames, err: 'error' })); }); req.on('error', () => ok({ s: 0, f: frames, err: 'error' })); req.write(d); req.end(); setTimeout(() => { req.destroy(); ok({ s: 0, f: frames, err: 'timeout' }); }, to); }); }

const results = [];
function log(t, s, m, d) { console.log(`[${s}] ${t}: ${m}${d ? ' | ' + d : ''}`); results.push({ t, s, m, d }); }

async function main() {
  console.log('='.repeat(60));
  console.log('HITL 完整流程验证（触发→确认→流式恢复）');
  console.log('使用 write_file（权限=ask）触发 HITL');
  console.log(new Date().toISOString());
  console.log('='.repeat(60));

  const sid = 'e2e-hitl-' + Date.now().toString(36);

  // 步骤1: 发送触发 write_file 工具调用的消息
  console.log('\n--- 步骤1: 发送消息触发 write_file ---');
  const r1 = await SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,
    { message: '使用 write_file 工具写入文件 /tmp/test-hitl.txt 内容为 "hello hitl"', userId: 'e2e-user' }, 180000);

  const types = r1.f.map(f => f.type);
  console.log(`收到 ${r1.f.length} 帧`);

  const permissionAsk = r1.f.find(f => f.type === 'permission_ask');
  const toolCallStart = r1.f.find(f => f.type === 'TOOL_CALL_START');
  const done = r1.f.find(f => f.type === 'done');
  const agentEnd = r1.f.find(f => f.type === 'AGENT_END');

  if (permissionAsk) {
    log('步骤1-触发HITL', 'PASS', `收到 permission_ask`);
    console.log('工具调用:', JSON.stringify(permissionAsk.tool_calls, null, 2));

    // 步骤2: 检查 pendingConfirm
    console.log('\n--- 步骤2: 检查 pendingConfirm ---');
    const hist = await GET(`${BASE}/threads/${encodeURIComponent(sid)}/history`);
    const histData = JSON.parse(hist.b);
    if (histData.pendingConfirm) {
      log('步骤2-pendingConfirm', 'PASS', `reply_id=${histData.pendingConfirm.reply_id}, tools=${(histData.pendingConfirm.tools || []).length}个`);

      // 步骤3: 确认工具调用（批准）
      console.log('\n--- 步骤3: 确认工具调用（批准） ---');
      const confirmResults = (histData.pendingConfirm.tools || []).map(t => ({
        tool_call_id: t.tool_call_id, confirmed: true, accept_rule: false
      }));
      const r3 = await POST(`${BASE}/threads/${encodeURIComponent(sid)}/confirm`, { results: confirmResults });
      console.log('确认响应:', r3.s);
      if (r3.s === 200) {
        const cd = JSON.parse(r3.b);
        log('步骤3-确认成功', 'PASS', `response="${(cd.response || '').substring(0, 80)}"`);
      } else {
        log('步骤3-确认失败', 'FAIL', `HTTP ${r3.s}: ${r3.b.substring(0, 200)}`);
      }

      // 步骤4: 验证 confirm-stream 流式恢复
      console.log('\n--- 步骤4: confirm-stream 流式恢复 ---');
      const sid2 = sid + '-cs';
      const r4a = await SSE(`${BASE}/threads/${encodeURIComponent(sid2)}/chat`,
        { message: '使用 write_file 工具写入 /tmp/test-hitl-stream.txt 内容为 "stream test"', userId: 'e2e-user' }, 180000);
      const pa2 = r4a.f.find(f => f.type === 'permission_ask');
      if (pa2) {
        const h2 = await GET(`${BASE}/threads/${encodeURIComponent(sid2)}/history`);
        const pc2 = JSON.parse(h2.b).pendingConfirm;
        if (pc2) {
          const cr2 = (pc2.tools || []).map(t => ({ tool_call_id: t.tool_call_id, confirmed: true, accept_rule: false }));
          const r4b = await SSE(`${BASE}/threads/${encodeURIComponent(sid2)}/confirm-stream`, { results: cr2 }, 180000);
          const hasDone = r4b.f.some(f => f.type === 'done');
          const hasEnd = r4b.f.some(f => f.type === 'AGENT_END');
          const hasText = r4b.f.some(f => f.type === 'TEXT_BLOCK_DELTA');
          log('步骤4-confirm-stream', (hasDone || hasEnd) ? 'PASS' : 'FAIL',
            `${r4b.f.length}帧 done=${hasDone} end=${hasEnd} text=${hasText}`);
          console.log('流帧:', r4b.f.filter(f => !f.type.includes('THINKING')).map(f => f.type).join(', '));
        } else {
          log('步骤4-confirm-stream', 'FAIL', '无 pendingConfirm');
        }
      } else {
        log('步骤4-confirm-stream', 'SKIP', '未触发 HITL');
      }

      // 步骤5: 重复确认（应 409）
      console.log('\n--- 步骤5: 重复确认（应 409） ---');
      const r5 = await POST(`${BASE}/threads/${encodeURIComponent(sid)}/confirm`, { results: confirmResults });
      log('步骤5-重复确认', r5.s === 409 ? 'PASS' : 'FAIL', `HTTP ${r5.s} (期望 409)`);

    } else {
      log('步骤2-pendingConfirm', 'FAIL', '历史中无 pendingConfirm');
    }
  } else if (toolCallStart) {
    log('步骤1-触发HITL', 'FAIL', `TOOL_CALL_START 但无 permission_ask`);
    console.log('帧类型:', types.join(', '));
  } else if (done || agentEnd) {
    log('步骤1-触发HITL', 'FAIL', `直接完成，未触发 HITL`);
    console.log('帧类型:', types.join(', '));
  } else {
    log('步骤1-触发HITL', 'FAIL', `${r1.f.length}帧，无终态`);
  }

  const passed = results.filter(r => r.s === 'PASS').length;
  const failed = results.filter(r => r.s === 'FAIL').length;
  console.log('\n' + '='.repeat(60));
  console.log(`HITL 测试结果: 通过=${passed} 失败=${failed}`);
  console.log('='.repeat(60));
  fs.writeFileSync(path.join(DIR, 'hitl-report.json'), JSON.stringify({ results, passed, failed }, null, 2));
  process.exit(failed > 0 ? 1 : 0);
}

main().catch(e => { console.error('FATAL:', e); process.exit(1); });
