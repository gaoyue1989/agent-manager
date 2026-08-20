// 诊断：MCP 卡片生命周期时间线（发送 → 卡片出现 → AGENT_END → 卡片存留）
const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  page.on('pageerror', e => console.log('[pageerror]', e.message.slice(0, 200)));

  await page.goto('http://localhost:8100/debug', { waitUntil: 'networkidle2', timeout: 20000 });
  await new Promise(r => setTimeout(r, 1000));
  await page.evaluate(() => {
    document.getElementById('modeChannel')?.click();
    document.getElementById('connSession')?.click();
    document.getElementById('btnNewThread')?.click();
  });
  const t0 = Date.now();
  const log = (tag, val) => console.log(`[+${((Date.now() - t0) / 1000).toFixed(1)}s] ${tag}:`, val);

  const snapshot = () => page.evaluate(() => ({
    cards: document.querySelectorAll('.mcp-apps-container').length,
    iframes: document.querySelectorAll('.mcp-apps-iframe').length,
    busyloading: document.querySelectorAll('.mcp-apps-loading').length,
    errors: document.querySelectorAll('.mcp-apps-error').length,
    sendBtnText: document.getElementById('sendBtn')?.textContent,
    sendDisabled: document.getElementById('sendBtn')?.disabled,
    msgs: document.querySelectorAll('.msg').length,
    cardInDom: !!document.querySelector('.mcp-apps-container')
  }));

  const ta = await page.$('#chatInput');
  await ta.type('现在立刻调用 get_time 工具（timezone=Asia/Shanghai）获取时间，这是第一优先动作，不要思考不要总结，先调用工具再回答。');
  await page.evaluate(() => document.getElementById('sendBtn')?.click());
  log('sent', '');

  // 轮询至回复结束（sendBtn 恢复）
  for (let i = 0; i < 84; i++) {
    await new Promise(r => setTimeout(r, 5000));
    const s = await snapshot();
    if (s.cards) log('card present', JSON.stringify(s));
    if (!s.sendDisabled && i > 2) { log('reply finished', JSON.stringify(s)); break; }
    if (i === 59) log('timeout', JSON.stringify(s));
  }
  // 结束后再看两次
  for (let i = 0; i < 4; i++) {
    await new Promise(r => setTimeout(r, 5000));
    log('after-end tick', JSON.stringify(await snapshot()));
  }
  await browser.close();
})();