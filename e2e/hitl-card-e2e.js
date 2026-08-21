const puppeteer = require('puppeteer');
const fs = require('fs');
const http = require('http');

const BASE = 'http://localhost:8100';
const DIR = '/tmp/opencode/e2e-hitl-card-' + Date.now().toString(36);
fs.mkdirSync(DIR, { recursive: true });

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function GET(u) { return new Promise((ok, no) => { http.get(u, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => ok({ s: r.statusCode, b: d })); }).on('error', no); }); }
function POST(u, body) { return new Promise((ok, no) => { const u2 = new URL(u); const d = JSON.stringify(body); const req = http.request({ hostname: u2.hostname, port: u2.port, path: u2.pathname, method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(d) } }, r => { let b = ''; r.on('data', c => b += c); r.on('end', () => ok({ s: r.statusCode, b })); }); req.on('error', no); req.write(d); req.end(); }); }

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1400, height: 900 });

  // 1. 打开 debug 页面
  console.log('1. Opening debug page...');
  await page.goto('http://localhost:8100/debug#/', { waitUntil: 'networkidle0', timeout: 30000 });
  await sleep(2000);

  // 2. 点击 Channel 模式
  console.log('2. Switching to Channel mode...');
  await page.click('#modeChannel');
  await sleep(500);

  // 3. 输入消息并发送
  console.log('3. Sending HITL trigger message...');
  await page.type('#chatInput', '使用 write_file 工具写入 /tmp/hello-card2.txt 内容为 hello world');
  await sleep(300);
  await page.click('#sendBtn');

  // 4. 等待确认卡片出现
  console.log('4. Waiting for confirmation card...');
  let cardFound = false;
  for (let i = 0; i < 60; i++) {
    await sleep(2000);
    const card = await page.$('.confirm-card');
    if (card) {
      cardFound = true;
      console.log(`   Card found after ${(i+1)*2}s`);
      await sleep(500);
      break;
    }
  }

  // 5. 截图确认卡片
  await page.screenshot({ path: `${DIR}/03-confirm-card.png` });
  console.log('5. Screenshot with confirmation card');

  // 6. 点击 Approve
  if (cardFound) {
    console.log('6. Clicking Approve...');
    const approveBtn = await page.$('.confirm-card [data-act="approve"]');
    if (approveBtn) {
      await approveBtn.click();
      console.log('   Approve clicked');

      // 7. 等待 confirm-stream 完成（轮询等待 done 帧，最多 120s）
      console.log('7. Waiting for confirm-stream to complete...');
      for (let i = 0; i < 60; i++) {
        await sleep(2000);
        // 检查是否有文本响应出现
        const textEls = await page.$$('.msg.assistant .msg-bubble');
        const hasText = textEls.length > 0;
        const thinking = await page.$('.thinking-indicator');
        const stopBtn = await page.$('#sendBtn.danger');
        if (hasText && !thinking && !stopBtn) {
          console.log(`   Text response appeared after ${(i+1)*2}s`);
          await sleep(1000);
          break;
        }
        if (i % 5 === 0) {
          console.log(`   Still processing... (${(i+1)*2}s)`);
        }
      }

      // 8. 截图最终结果
      await page.screenshot({ path: `${DIR}/04-after-confirm.png` });
      console.log('8. Screenshot after confirm');

      // 检查是否有新的确认卡片
      const newCard = await page.$('.confirm-card');
      if (newCard) {
        console.log('   NEW confirmation card appeared');
        await page.screenshot({ path: `${DIR}/05-new-card.png` });
      } else {
        console.log('   No new confirmation card');
      }
    }
  }

  await browser.close();
  console.log(`\nScreenshots: ${DIR}/`);
})();
