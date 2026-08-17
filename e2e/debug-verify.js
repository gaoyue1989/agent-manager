const puppeteer = require('puppeteer');

const BASE_URL = 'http://localhost:8100';

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  const results = [];

  const check = async (label, fn) => {
    try {
      const v = await fn();
      results.push({ label, ok: !!v, value: String(v).substring(0, 200) });
    } catch (e) {
      results.push({ label, ok: false, value: e.message });
    }
  };

  // 1. Debug page loads
  await page.goto(BASE_URL + '/debug', { waitUntil: 'networkidle2', timeout: 15000 });
  await new Promise(r => setTimeout(r, 2000));

  await check('1. 页面标题', () => page.title());
  await check('2. Agent 名称', () => page.$eval('#agentName', el => el.textContent));
  await check('3. 状态指示', () => page.$eval('#statusDot', el => el.className));
  await check('4. 主题切换按钮', () => page.$('#themeToggle'));
  await check('5. 窄侧栏图标', () => page.$$eval('.sidebar .nav-item', els => els.length));
  await check('6. Header 浅色背景', () => page.$eval('.header', el => getComputedStyle(el).backgroundColor));

  // 2. Chat 空状态
  await page.goto(BASE_URL + '/debug#/', { waitUntil: 'networkidle2', timeout: 10000 });
  await new Promise(r => setTimeout(r, 1500));
  await check('7. Chat greeting', () => page.$eval('.chat-greeting', el => el.textContent).catch(() => ''));
  await check('8. 会话侧栏', () => page.$eval('.chat-sidebar', el => el.offsetHeight > 0));
  await check('9. 输入 pill', () => page.$eval('.chat-input-pill', el => el.offsetHeight > 0));
  await check('10. 消息区域居中列', () => page.$eval('.chat-messages-inner', el => el.style.maxWidth || getComputedStyle(el).maxWidth));

  // 3. 主题切换
  await page.click('#themeToggle');
  await new Promise(r => setTimeout(r, 500));
  const htmlClass = await page.$eval('html', el => el.className);
  await check('11. 深色主题 html.dark', () => htmlClass.includes('dark'));

  await page.click('#themeToggle');
  await new Promise(r => setTimeout(r, 500));
  const htmlClass2 = await page.$eval('html', el => el.className);
  await check('12. 浅色主题 html.light', () => htmlClass2.includes('light'));

  // 4. Channel 发送消息
  await page.click('#modeChannel');
  await new Promise(r => setTimeout(r, 300));
  await page.type('#chatInput', 'hi', { delay: 20 });
  await page.click('#sendBtn');
  await new Promise(r => setTimeout(r, 40000));

  await check('13. 用户消息气泡', () => page.$$eval('.msg.user', els => els.length));
  await check('14. 助手消息气泡', () => page.$$eval('.msg.assistant', els => els.length));
  await check('15. 思维链块', () => page.$$eval('.thinking-block', els => els.length));
  await check('16. 工具组', () => page.$$eval('.tool-group', els => els.length));
  await check('17. 页脚 badge', () => page.$$eval('.msg-footer', els => els.length));
  await check('18. 会话列表有数据', () => page.$$eval('.thread-item', els => els.length));

  // 5. 非 Chat 模块
  await page.goto(BASE_URL + '/debug#/tools', { waitUntil: 'networkidle2', timeout: 10000 });
  await new Promise(r => setTimeout(r, 1500));
  await check('19. Tools 面板', () => page.$$eval('.panel', els => els.length));

  await page.goto(BASE_URL + '/debug#/config', { waitUntil: 'networkidle2', timeout: 10000 });
  await new Promise(r => setTimeout(r, 1500));
  await check('20. Config 面板', () => page.$$eval('.panel', els => els.length));

  await page.goto(BASE_URL + '/debug#/database', { waitUntil: 'networkidle2', timeout: 10000 });
  await new Promise(r => setTimeout(r, 1500));
  await check('21. Database 面板', () => page.$$eval('.panel', els => els.length));

  await page.goto(BASE_URL + '/debug#/logs', { waitUntil: 'networkidle2', timeout: 10000 });
  await new Promise(r => setTimeout(r, 1500));
  await check('22. Logs 内容', () => page.$$eval('.log-line', els => els.length));

  // 汇总
  console.log('\n=== 验证结果 ===');
  let pass = 0, fail = 0;
  for (const r of results) {
    const icon = r.ok ? '✓' : '✗';
    console.log(icon + ' ' + r.label + ': ' + r.value.substring(0, 120));
    if (r.ok) pass++; else fail++;
  }
  console.log('\n通过: ' + pass + '/' + results.length + (fail > 0 ? '  失败: ' + fail : ''));

  await browser.close();
})();
