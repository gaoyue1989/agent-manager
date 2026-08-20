// MCP Apps 阶段二 E2E：Debug 页 MCP 卡片渲染 + 交互
// 前置：agent-framework :8100 + fake_mcp :8812（ui://get-time/mcp-app.html 可交互版）
const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const BASE_URL = 'http://localhost:8100';
const DIR = path.join(__dirname, 'agent-framework-screenshots');
const SESSION = 'acme-test-agent:debug-user:e2e-mcpapps';

async function screenshot(page, name) {
  const filePath = path.join(DIR, name + '.png');
  await page.screenshot({ path: filePath, fullPage: true });
  console.log('  saved ' + name + '.png');
}

(async () => {
  if (!fs.existsSync(DIR)) fs.mkdirSync(DIR, { recursive: true });
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  await page.setDefaultTimeout(20000);

  try {
    await page.goto(BASE_URL + '/debug', { waitUntil: 'networkidle2', timeout: 20000 });
    await new Promise(r => setTimeout(r, 1500));

    // 0) 切换到 Channel + Session（长连接 SSE）模式 + 新建线程
    await page.evaluate(() => {
      document.getElementById('modeChannel')?.click();
      document.getElementById('connSession')?.click();
      document.getElementById('btnNewThread')?.click();
    });
    await new Promise(r => setTimeout(r, 1500));

    // 1) 发送触发 get_time 工具的消息（真实 LLM 需主动调用 MCP 工具）
    const textarea = await page.$('#chatInput');
    if (!textarea) throw new Error('chat textarea #chatInput not found');
    await textarea.type('你必须调用 get_time 这个工具（参数 timezone 用 Asia/Shanghai）来获取时间。绝对不能假设或编造结果。调用完成后告诉我结果。');
    await page.evaluate(() => document.getElementById('sendBtn')?.click());
    console.log('message sent, waiting for MCP card (LLM call may take 120-240s)...');
    const t0 = Date.now();
    await page.waitForFunction(
      () => document.querySelectorAll('.mcp-apps-container').length > 0,
      { timeout: 240000 }
    ).catch(() => {});
    console.log('card wait:', ((Date.now() - t0) / 1000).toFixed(1) + 's');
    // 等 iframe 挂载完成（mount 异步拉资源）
    await page.waitForFunction(
      () => document.querySelectorAll('.mcp-apps-iframe').length > 0,
      { timeout: 20000 }
    ).catch(() => {});
    await new Promise(r => setTimeout(r, 2000));

    // 2) 截图：MCP 卡片应渲染在消息流中
    await screenshot(page, 'mcpapps-01-card-rendered');

    // 3) 断言卡片元素
    const cardInfo = await page.evaluate(() => {
      const card = document.querySelector('.mcp-apps-container');
      if (!card) return null;
      return {
        open: card.classList.contains('open'),
        label: card.querySelector('.app-label')?.textContent || null,
        name: card.querySelector('.app-name')?.textContent || null,
        uri: card.querySelector('.app-uri')?.textContent || null,
        iframe: !!card.querySelector('.mcp-apps-iframe'),
        loading: !!card.querySelector('.mcp-apps-loading'),
        error: !!card.querySelector('.mcp-apps-error')
      };
    });
    console.log('card info:', JSON.stringify(cardInfo));

    // 4) iframe 内 app 状态（sandbox 无 allow-same-origin，contentDocument 跨域不可读，用 frame.evaluate）
    const frame = page.frames().find(f => f.url().startsWith('about:srcdoc'));
    const iframeState = frame ? await frame.evaluate(() => document.body.innerText.slice(0, 200)) : 'NO FRAME';
    console.log('iframe content:', JSON.stringify(iframeState));
    await screenshot(page, 'mcpapps-02-card-after');

    // 5) 触发卡片按钮（ui/tools/call get_time）验证 host 转发 + 结果回流
    if (frame) {
      await page.evaluate(() => {
        window.__hostMsgs = [];
        window.addEventListener('message', (e) => { if (e.data && e.data.jsonrpc) window.__hostMsgs.push(e.data); });
      });
      await frame.evaluate(() => document.getElementById('btn')?.click());
    }
    await new Promise(r => setTimeout(r, 5000));
    const hostMsgs = await page.evaluate(() => window.__hostMsgs || []);
    console.log('postMessage frames:', JSON.stringify(hostMsgs).slice(0, 500));
    const iframeAfterClick = frame ? await frame.evaluate(() => document.body.innerText.slice(0, 300)) : 'NO FRAME';
    console.log('iframe after button click:', JSON.stringify(iframeAfterClick));
    await screenshot(page, 'mcpapps-03-after-tool-call');

    // 6) ui/update-model-context：卡片静默更新模型上下文（4.7）→ 后端持久化
    const lastSessionId = await page.evaluate(() => window.App.state.getState('threads.current') || '');
    console.log('active session:', lastSessionId);
    if (frame) {
      await frame.evaluate(() => document.getElementById('btnCtx')?.click());
    }
    await new Promise(r => setTimeout(r, 3000));
    const ctxMsgs = await page.evaluate(() => (window.__ctxMsgs || (window.__ctxMsgs = []), window.__ctxMsgs));
    console.log('ctx note: 检查 ui_context 表是否写入 session=' + lastSessionId);

    console.log('DONE');
  } catch (e) {
    console.error('E2E FAILED:', e.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
