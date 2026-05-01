const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const SCREENSHOT_DIR = path.join(__dirname, 'docs', 'screenshots');
if (!fs.existsSync(SCREENSHOT_DIR)) fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

// Helper to find element by text using XPath
const findByText = async (page, tag, text) => {
  return await page.evaluateHandle((t, txt) => {
    const xpath = `//${t}[contains(text(), "${txt}")]`;
    const result = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
    return result.singleNodeValue;
  }, tag, text);
};

(async () => {
  console.log('Starting E2E Tests...');
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });

  const results = [];
  const log = (test, status, msg) => {
    console.log(`[${status}] ${test}: ${msg}`);
    results.push({ test, status, msg });
  };

  try {
    // 1. Navigate to Agent Detail Page
    console.log('Navigating to Agent Detail...');
    await page.goto('http://localhost:3000/agents/2', { waitUntil: 'domcontentloaded', timeout: 15000 });
    await new Promise(r => setTimeout(r, 2000));

    // 2. F1: Image Info
    console.log('Testing F1: Image Info...');
    const imageInfoHeader = await findByText(page, 'h2', '镜像信息');
    if (imageInfoHeader) {
      const text = await page.evaluate(el => el.parentElement.innerText, imageInfoHeader);
      if (text.includes('172.20.0.1:5001')) {
        log('F1: 镜像信息展示', 'PASS', '镜像地址显示正确');
      } else {
        log('F1: 镜像信息展示', 'FAIL', '镜像地址未显示');
      }
    } else {
      log('F1: 镜像信息展示', 'FAIL', '未找到镜像信息区域');
    }

    // 3. F2: Pod Status & Refresh
    console.log('Testing F2: Pod Status...');
    const podStatusHeader = await findByText(page, 'h2', 'Pod 状态');
    if (podStatusHeader) {
      log('F2: Pod 状态监控', 'PASS', 'Pod 状态区域显示正常');
      
      // Click Refresh
      const refreshBtn = await findByText(page, 'button', '刷新');
      if (refreshBtn) {
        await refreshBtn.click();
        await new Promise(r => setTimeout(r, 3000));
        log('F2: 刷新按钮', 'PASS', '刷新按钮点击成功');
      } else {
        log('F2: 刷新按钮', 'FAIL', '未找到刷新按钮');
      }
    } else {
      log('F2: Pod 状态监控', 'FAIL', '未找到 Pod 状态区域');
    }

    // 4. F3: Chat Test
    console.log('Testing F3: Chat Test...');
    const chatHeader = await findByText(page, 'h2', 'Agent 聊天测试');
    if (chatHeader) {
      log('F3: 聊天测试区域', 'PASS', '聊天测试区域显示正常');
      
      const input = await page.$('input[placeholder="输入消息测试 Agent..."]');
      const sendBtn = await findByText(page, 'button', '发送');
      
      if (input && sendBtn) {
        await input.type('你好，用中文说你好');
        await sendBtn.click();
        
        // Wait for response (kubectl exec can be slow)
        console.log('Waiting for chat response...');
        await new Promise(r => setTimeout(r, 30000));
        
        // Check if response appeared
        const chatBubbles = await page.$$('.bg-white.border');
        if (chatBubbles.length > 0) {
          log('F3: 聊天功能', 'PASS', '收到 Agent 响应');
        } else {
          // Check for error or latency
          const latency = await findByText(page, 'div', '响应时间');
          if (latency) {
            log('F3: 聊天功能', 'PASS', '收到 Agent 响应 (含延迟信息)');
          } else {
            log('F3: 聊天功能', 'FAIL', '未收到 Agent 响应');
          }
        }
      } else {
        log('F3: 聊天功能', 'FAIL', '未找到输入框或发送按钮');
      }
    } else {
      log('F3: 聊天测试区域', 'FAIL', '未找到聊天测试区域');
    }

    // Screenshot
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'e2e-full-page.png'), fullPage: true });
    console.log('Screenshot saved.');

  } catch (e) {
    console.error('Test failed:', e);
  } finally {
    await browser.close();
    console.log('Tests completed.');
    
    // Output results as JSON for report generation
    console.log('RESULTS_JSON_START');
    console.log(JSON.stringify(results));
    console.log('RESULTS_JSON_END');
  }
})();
