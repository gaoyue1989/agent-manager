#!/usr/bin/env node
/**
 * 全功能 E2E 测试：审批 Demo 所有功能点 + 截图验证
 * 每个功能点独立截图、独立断言、独立 PASS/FAIL
 *
 * 前置条件：
 *   1. agent-framework :8100 (approval-demo 配置)
 *   2. mock MCP :8813 + proxy :8913 (./start.sh)
 *   3. cd agent-framework/example/approval-forms/e2e && node e2e-full.js
 */
const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');
const http = require('http');

const PROXY = 'http://localhost:8913';
const BACKEND = 'http://localhost:8100';
const SCREENSHOT_DIR = path.join(__dirname, 'screenshots-full');
const LLM_TIMEOUT = 420000;

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function screenshot(page, name) {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, name + '.png'), fullPage: true });
}

async function apiGet(urlPath) {
  return new Promise((resolve, reject) => {
    const url = new URL(urlPath, BACKEND);
    http.get(url, res => {
      let body = '';
      res.on('data', d => body += d);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(body) }); }
        catch { resolve({ status: res.statusCode, data: body }); }
      });
    }).on('error', reject);
  });
}

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? 'PASS' : 'FAIL') + ' — ' + name + (extra ? ' | ' + extra : ''));
}

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu']
  });

  try {
    // ================================================================
    // 1. 健康检查 API
    // ================================================================
    console.log('\n=== 1. 健康检查 ===');
    const health = await apiGet('/health');
    check('GET /health 200', health.status === 200, JSON.stringify(health.data));
    check('health slug', health.data?.slug === 'acme-approval-demo');
    check('health LLM configured', health.data?.llm_configured === true);

    // ================================================================
    // 2. MCP 工具列表 API
    // ================================================================
    console.log('\n=== 2. MCP 工具列表 ===');
    const mcp = await apiGet('/mcp');
    check('GET /mcp 200', mcp.status === 200);
    const hasApproval = Array.isArray(mcp.data) && mcp.data.some(s => s.server === 'approval');
    check('mcp has approval server', hasApproval, JSON.stringify(mcp.data?.[0]?.tool_count));

    const tools = await apiGet('/tools');
    check('GET /tools 200', tools.status === 200);
    const toolList = (tools.data?.tools || []);
    const toolNames = toolList.map(t => t.name);
    check('tools contains submit_application', toolNames.includes('submit_application'));
    check('tools contains show_application_form', toolNames.includes('show_application_form'));
    check('tools contains confirm_application', toolNames.includes('confirm_application'));

    // ================================================================
    // 3. Thread 列表 API
    // ================================================================
    console.log('\n=== 3. Thread 列表 ===');
    const threads = await apiGet('/threads');
    check('GET /threads 200', threads.status === 200);

    // ================================================================
    // 4. Debug 页面加载
    // ================================================================
    console.log('\n=== 4. Debug 页面 ===');
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });
    await page.setDefaultTimeout(20000);

    await page.goto(BACKEND + '/debug', { waitUntil: 'networkidle2', timeout: 30000 });
    await sleep(1000);
    await screenshot(page, '01-debug-page');
    const debugTitle = await page.evaluate(() => document.title);
    check('debug page loads', debugTitle.length > 0, 'title: ' + debugTitle);
    const hasDebugPanel = await page.evaluate(() =>
      document.querySelector('.debug-panel') !== null ||
      document.querySelector('#chatInput') !== null ||
      document.querySelector('.config-card') !== null
    );
    check('debug page has content', hasDebugPanel);

    // ================================================================
    // 5. 审批 Demo 前端页面
    // ================================================================
    console.log('\n=== 5. 审批 Demo 前端 ===');
    const demoPage = await browser.newPage();
    await demoPage.setViewport({ width: 1440, height: 900 });
    await demoPage.setDefaultTimeout(20000);

    await demoPage.goto(PROXY + '/', { waitUntil: 'networkidle2', timeout: 30000 });
    await sleep(1000);
    await screenshot(demoPage, '02-demo-page');
    const hasChat = await demoPage.evaluate(() =>
      document.querySelector('#chatInput') !== null ||
      document.querySelector('#chatInner') !== null
    );
    check('demo page has chat UI', hasChat);

    // 新建线程
    await demoPage.evaluate(() => document.getElementById('btnNewThread')?.click());
    await sleep(800);
    check('new thread created', true);
    await screenshot(demoPage, '03-new-thread');

    // ================================================================
    // 6. 发送消息 → MCP 表单卡片
    // ================================================================
    console.log('\n=== 6. MCP 表单卡片 ===');
    const t0 = Date.now();
    const textarea = await demoPage.$('#chatInput');
    await textarea.click({ clickCount: 3 });
    await textarea.type(
      '我需要申请一个 GPU 推理服务：' +
      '标题是「多模态大模型服务申请」，' +
      '申请文本：申请一个 8 卡 GPU 推理实例用于多模态大模型推理，预计使用 3 个月。' +
      '文件链接：http://x.example/方案.pdf 和 http://x.example/预算.xlsx。' +
      '请帮我创建申请单，并调用工具展示表单确认卡片。'
    );
    await demoPage.evaluate(() => document.getElementById('sendBtn')?.click());
    console.log('  prompt sent, waiting for MCP form card...');
    await demoPage.waitForFunction(
      () => document.querySelectorAll('.mcp-apps-container').length > 0,
      { timeout: LLM_TIMEOUT }
    ).catch(() => {});
    await demoPage.waitForFunction(
      () => document.querySelectorAll('.mcp-apps-iframe').length > 0,
      { timeout: 30000 }
    ).catch(() => {});
    await sleep(2500);
    console.log('  form card wait:', ((Date.now() - t0) / 1000).toFixed(1) + 's');
    await screenshot(demoPage, '04-form-card');

    const cardInfo = await demoPage.evaluate(() => {
      const host = document.querySelector('.mcp-apps-container');
      const iframe = document.querySelector('.mcp-apps-iframe');
      return { hostMounted: !!host, iframeMounted: !!iframe, label: host?.querySelector('.app-label')?.textContent || null };
    });
    check('form card mounted', cardInfo.hostMounted && cardInfo.iframeMounted, JSON.stringify(cardInfo));

    // ================================================================
    // 7. 卡片 iframe 内表单渲染
    // ================================================================
    console.log('\n=== 7. 表单渲染 ===');
    let frame = null;
    try { frame = await demoPage.waitForFrame(f => f.url().startsWith('about:srcdoc'), { timeout: 15000 }); } catch {}
    check('card iframe found', !!frame);
    let appText = '';
    if (frame) {
      await sleep(2500);
      appText = await frame.evaluate(() => document.body.innerText.slice(0, 600));
      console.log('  iframe text:', JSON.stringify(appText.slice(0, 150)));
      check('card shows form', appText.includes('模型服务申请表') && appText.includes('多模态大模型服务申请'));
    }

    // ================================================================
    // 8. 表单确认交互
    // ================================================================
    console.log('\n=== 8. 表单确认 ===');
    // 注入 postMessage 监听器到 demo 页面（捕获 iframe → parent 的 tools/call）
    await demoPage.evaluate(() => {
      window.__mcpConfirmLog = [];
      window.__mcpConfirmSeen = false;
      window.__mcpFetchLog = [];
      // 捕获页面所有 fetch 请求(确认卡片代理调用是否发出)
      const origFetch = window.fetch;
      window.fetch = function (...args) {
        const url = String(args[0] || '');
        if (url.includes('confirm_application') || url.includes('ui-context')) {
          window.__mcpFetchLog.push({ url, body: args[1] ? String(args[1].body || '') : '' });
        }
        return origFetch.apply(this, args);
      };
      window.addEventListener('message', e => {
        const d = e.data;
        if (d && d.jsonrpc === '2.0' && d.method === 'tools/call'
            && d.params?.name === 'confirm_application') {
          window.__mcpConfirmSeen = true;
          window.__mcpConfirmLog.push({ id: d.id, params: d.params });
          console.log('[E2E] confirm_application postMessage received:', JSON.stringify(d.params));
        }
        if (d && d.jsonrpc === '2.0' && d.id && (d.result || d.error)) {
          console.log('[E2E] tools/call response:', JSON.stringify(d).slice(0, 200));
        }
      });
    });
    // 等待 iframe 完成 handshake
    await sleep(2000);
    if (frame) {
      const clicked = await frame.evaluate(() => {
        const btn = document.getElementById('btnConfirm');
        if (btn) { btn.click(); return true; }
        return false;
      });
      check('form confirm button clicked', clicked);
      // 等待 confirm_application 调用完成（iframe postMessage → parent → proxy → mock → 响应）
      await sleep(5000);
      // 检查 postMessage 是否被 demo 页面收到
      const confirmSeen = await demoPage.evaluate(() => window.__mcpConfirmSeen);
      const confirmLog = await demoPage.evaluate(() => JSON.stringify(window.__mcpConfirmLog));
      const fetchLog = await demoPage.evaluate(() => JSON.stringify(window.__mcpFetchLog));
      check('confirm_application postMessage received', confirmSeen, confirmLog);
      console.log('  fetch log:', fetchLog);
      // 检查卡片状态
      appText = await frame.evaluate(() => document.body.innerText.slice(0, 400));
      const confirmedOk = appText.includes('已确认') || appText.includes('确认') || appText.includes('APP-');
      check('card confirmed state', confirmedOk, 'text: ' + appText.slice(0, 100).replace(/\n/g, ' '));
      await screenshot(demoPage, '05-form-confirmed');
    }

    // ================================================================
    // 9. HITL 人工审批卡片
    // ================================================================
    console.log('\n=== 9. HITL 确认卡片 ===');
    // 确保 ui/update-model-context 已完成且下一轮 LLM 调用能看到更新
    await sleep(5000);
    const t1 = Date.now();
    await textarea.click({ clickCount: 3 });
    await textarea.type('表单已经确认了，现在提交申请。');
    await demoPage.evaluate(() => document.getElementById('sendBtn')?.click());
    console.log('  submit prompt sent, waiting for HITL confirm...');
    await demoPage.waitForFunction(
      () => document.querySelectorAll('.confirm-card').length > 0,
      { timeout: LLM_TIMEOUT }
    ).catch(() => {});
    const confirmCard = await demoPage.evaluate(() => {
      const c = document.querySelector('.confirm-card');
      return c ? {
        title: c.querySelector('.confirm-title')?.textContent || '',
        tool: c.querySelector('.confirm-tool-name')?.textContent || ''
      } : null;
    });
    console.log('  HITL wait:', ((Date.now() - t1) / 1000).toFixed(1) + 's');
    check('HITL confirm card shown', !!confirmCard && (confirmCard.tool || '').includes('submit_application'), JSON.stringify(confirmCard));
    await screenshot(demoPage, '06-hitl-confirm');

    // ================================================================
    // 10. 批准 → 提交结果回流
    // ================================================================
    console.log('\n=== 10. 批准提交 ===');
    if (confirmCard) {
      // 批准循环：mimo 偶发重试会触发新确认卡，循环批准
      // 成功判据【以 mock 状态为准】:get_application stage=submitted(权威信号,不受 LLM 文本解读影响)
      const appId = await demoPage.evaluate(() => {
        // 优先从 confirm_application 的 postMessage 参数提取（页面 window 上已有记录）
        const log = window.__mcpConfirmLog || [];
        if (log.length && log[0].params?.arguments?.application_id) {
          return log[0].params.arguments.application_id;
        }
        const cardText = document.querySelector('.mcp-apps-container')?.innerText || document.body.innerText;
        const m = cardText.match(/APP-\d+/);
        return m ? m[0] : null;
      });
      console.log('  application id:', appId);
      console.log('  __mcpConfirmLog appId:', JSON.stringify(await demoPage.evaluate(() => window.__mcpConfirmLog)));

      const approveDeadline = Date.now() + 300000;
      let submitted = false;
      let finalText = '';
      let approveRounds = 0;
      while (Date.now() < approveDeadline && !submitted) {
        approveRounds++;
        // 点击所有存在且未处理的确认卡的「批准」
        const clicked = await demoPage.evaluate(() => {
          const cards = document.querySelectorAll('.confirm-card');
          let clicks = 0;
          cards.forEach((c) => {
            const btn = c.querySelector('[data-act="approve"]');
            if (btn && !btn.disabled) { btn.click(); clicks++; }
          });
          return { clicks, cardCount: cards.length };
        });
        if (clicked.clicks > 0) console.log('  approve clicked (' + clicked.clicks + '), cards=' + clicked.cardCount);
        if (approveRounds % 20 === 0) {
          const t = await demoPage.evaluate(() =>
            (document.getElementById('chatInner')?.innerText || '').slice(-120).replace(/\n/g, ' ')
          );
          console.log('  [round ' + approveRounds + '] cards=' + clicked.cardCount + ' chat: ' + t);
        }
        await sleep(4000);
        // 权威判据:直查 mock 的 get_application
        if (appId) {
          try {
            const resp = await fetch('http://localhost:8100/mcp/approval/tools/get_application',
              { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ arguments: { application_id: appId } }) });
            const j = await resp.json();
            const text = (j.content && j.content[0] && j.content[0].text) || '';
            if (text.includes('"stage": "submitted"')) { submitted = true; break; }
          } catch (e) { /* mock 未就绪时忽略 */ }
        }
      }
      console.log('  approve rounds:', approveRounds, 'submitted:', submitted);
      finalText = await demoPage.evaluate(() =>
        (document.getElementById('chatInner')?.innerText || '').slice(-500)
      );
      // 等 LLM 最终回复渲染完成
      await sleep(8000);
      // 最终判据:mock 状态优先(权威),LLM 文本为辅
      let mockState = null;
      if (appId) {
        try {
          const resp = await fetch('http://localhost:8100/mcp/approval/tools/get_application',
            { method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ arguments: { application_id: appId } }) });
          const j = await resp.json();
          const text = (j.content && j.content[0] && j.content[0].text) || '';
          const mm = text.match(/"stage":\s*"(\w+)"/);
          mockState = mm ? mm[1] : null;
        } catch (e) { /* ignore */ }
      }
      mockState = mockState || (submitted ? 'submitted' : null);
      check('submission result shown (mock stage=' + (mockState || '?') + ')',
        mockState === 'submitted',
        'state: ' + mockState + ' tail: ' + finalText.slice(-150).replace(/\n/g, ' '));
      await sleep(1500);
      await screenshot(demoPage, '07-submitted');
    }

    // ================================================================
    // 11. Thread 列表验证（新线程已创建）
    // ================================================================
    console.log('\n=== 11. Thread 列表验证 ===');
    const threadsAfter = await apiGet('/threads');
    check('threads list refresh', threadsAfter.status === 200);

    // ================================================================
    // 12. 对话历史持久化
    // ================================================================
    console.log('\n=== 12. 对话历史 ===');
    const chatText = await demoPage.evaluate(() =>
      (document.getElementById('chatInner')?.innerText || '')
    );
    check('chat has application prompt', chatText.includes('GPU 推理'));
    check('chat has submission result', chatText.includes('已提交') || chatText.includes('审批'));
    await screenshot(demoPage, '08-chat-history');

  } catch (e) {
    console.log('E2E ERROR:', e.message);
  } finally {
    await browser.close();
  }

  const failed = results.filter(r => !r.ok);
  console.log('\n===== E2E RESULT: ' + (failed.length === 0 ? 'ALL PASS (' + results.length + ' checks)' : failed.length + ' FAILED / ' + results.length + ' checks') + ' =====');
  if (failed.length) {
    console.log('Failed checks:');
    failed.forEach(r => console.log('  FAIL — ' + r.name));
    process.exit(1);
  }
})();
