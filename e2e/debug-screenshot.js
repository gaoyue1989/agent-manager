const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const BASE_URL = 'http://localhost:8100';
const DIR = path.join(__dirname, 'agent-framework-screenshots');

async function screenshot(page, name) {
  const filePath = path.join(DIR, name + '.png');
  await page.screenshot({ path: filePath, fullPage: true });
  console.log('  ' + name + '.png');
  return filePath;
}

(async () => {
  if (!fs.existsSync(DIR)) fs.mkdirSync(DIR, { recursive: true });

  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });

  try {
    // 1. Debug page 初始加载
    await page.goto(BASE_URL + '/debug', { waitUntil: 'networkidle2', timeout: 15000 });
    await new Promise(r => setTimeout(r, 2000));
    await screenshot(page, '01-debug-page-initial');

    // 2. Chat 页面（默认路由）
    await page.goto(BASE_URL + '/debug#/', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '02-chat-empty-greeting');

    // 3. 主题切换 - 深色
    const themeBtn = await page.$('#themeToggle');
    if (themeBtn) {
      await themeBtn.click();
      await new Promise(r => setTimeout(r, 500));
      await screenshot(page, '03-chat-dark-theme');
      await themeBtn.click(); // 切回浅色
      await new Promise(r => setTimeout(r, 500));
    }

    // 4. 发送消息（Channel 长连接）
    await page.click('#modeChannel');
    await new Promise(r => setTimeout(r, 300));
    await page.type('#chatInput', '1+1=? 只回答数字', { delay: 30 });
    await page.click('#sendBtn');
    await new Promise(r => setTimeout(r, 35000)); // 等 LLM 响应
    await screenshot(page, '04-chat-channel-message');

    // 5. 会话列表应该出现了
    await screenshot(page, '05-chat-session-list');

    // 6. 切换到 A2A 模式
    await page.click('#modeA2A');
    await new Promise(r => setTimeout(r, 300));
    await page.type('#chatInput', '2+2=? 只回答数字', { delay: 30 });
    await page.click('#sendBtn');
    await new Promise(r => setTimeout(r, 35000));
    await screenshot(page, '06-chat-a2a-message');

    // 7. Tools 页
    await page.goto(BASE_URL + '/debug#/tools', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '07-tools-page');

    // 8. Skills 页
    await page.goto(BASE_URL + '/debug#/skills', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '08-skills-page');

    // 9. MCP 页
    await page.goto(BASE_URL + '/debug#/mcp', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '09-mcp-page');

    // 10. Config 页
    await page.goto(BASE_URL + '/debug#/config', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '10-config-page');

    // 11. Database 页
    await page.goto(BASE_URL + '/debug#/database', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '11-database-page');

    // 12. Memory 页
    await page.goto(BASE_URL + '/debug#/memory', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '12-memory-page');

    // 13. Workspace 页
    await page.goto(BASE_URL + '/debug#/workspace', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '13-workspace-page');

    // 14. Sandbox 页
    await page.goto(BASE_URL + '/debug#/sandbox', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '14-sandbox-page');

    // 15. Logs 页
    await page.goto(BASE_URL + '/debug#/logs', { waitUntil: 'networkidle2', timeout: 10000 });
    await new Promise(r => setTimeout(r, 1500));
    await screenshot(page, '15-logs-page');

    console.log('\nAll screenshots saved to: ' + DIR);

  } catch (err) {
    console.error('Error:', err.message);
    await screenshot(page, 'error-state');
  } finally {
    await browser.close();
  }
})();
