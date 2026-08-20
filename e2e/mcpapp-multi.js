// 多轮工具调用：验证第二轮工具调用后卡片（textEl 重写场景）仍存活
const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu'] });
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
  const snap = () => page.evaluate(() => ({
    cards: document.querySelectorAll('.mcp-apps-container').length,
    iframes: document.querySelectorAll('.mcp-apps-iframe').length,
    done: !document.getElementById('sendBtn')?.disabled,
    text: (document.querySelector('.msg.assistant')?.innerText || '').slice(0, 120)
  }));
  const ta = await page.$('#chatInput');
  await ta.type('请分两步：先调用 get_time 工具获取北京时间，说出时间；然后再次调用 get_time 获取纽约时间，两次都必须在文字中给出结果。');
  await page.evaluate(() => document.getElementById('sendBtn')?.click());
  for (let i = 0; i < 40; i++) {
    await new Promise(r => setTimeout(r, 5000));
    const s = await snap();
    if (s.cards) log('tick', JSON.stringify(s));
    if (s.done && i > 1) { log('DONE', JSON.stringify(s)); break; }
  }
  await new Promise(r => setTimeout(r, 3000));
  log('final', JSON.stringify(await snap()));
  await browser.close();
})();
