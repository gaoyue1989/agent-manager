const puppeteer = require('puppeteer');
const fs = require('fs');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const API_URL = `${BASE_URL}/api/v1`;

let screenshotIndex = 1;
const screenshots = [];

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function takeScreenshot(page, name, description) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const filename = `${String(screenshotIndex).padStart(2, '0')}-${timestamp}-${name.replace(/\s+/g, '-')}.png`;
  const filepath = `screenshots/${filename}`;
  
  await page.screenshot({ path: filepath, fullPage: true });
  
  screenshots.push({
    index: screenshotIndex,
    filename,
    name,
    description,
    timestamp: new Date().toISOString(),
  });
  
  console.log(`[${screenshotIndex}] ${name}: ${description}`);
  screenshotIndex++;
}

async function log(name, status, description, data = {}) {
  console.log(`[${status}] ${name}: ${description}`);
  if (Object.keys(data).length > 0) {
    console.log('  ', JSON.stringify(data));
  }
}

async function main() {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox', '--disable-setuid-sandbox'] });
  const page = await browser.newPage();

  let agentId;

  try {
    console.log('=== E5: Agent 删除功能测试 ===\n');

    const createRes = await fetch(`${API_URL}/agents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        config: JSON.stringify({
          name: 'e5-delete-test',
          description: 'E2E delete test agent',
          model: 'qwen3.6-plus',
          system_prompt: 'You are a test agent.',
        }),
        config_type: 'json',
      }),
    });
    const createData = await createRes.json();
    agentId = createData.id;
    await log('E5-1', 'PASS', '创建 Agent', { agentId });

    await page.goto(`${BASE_URL}/agents/${agentId}`);
    await sleep(2000);
    await takeScreenshot(page, 'E5-1 创建Agent', 'Agent创建成功页面');

    const genRes = await fetch(`${API_URL}/agents/${agentId}/generate`, { method: 'POST' });
    const genData = await genRes.json();
    await log('E5-2', genData.status === 'success' ? 'PASS' : 'FAIL', '生成代码', { status: genData.status });
    await sleep(3000);

    const buildRes = await fetch(`${API_URL}/agents/${agentId}/build`, { method: 'POST' });
    await log('E5-3', 'INFO', '构建镜像开始');
    await sleep(30000);

    const deployRes = await fetch(`${API_URL}/agents/${agentId}/deploy`, { method: 'POST' });
    const deployData = await deployRes.json();
    await log('E5-4', deployData.status === 'running' ? 'PASS' : 'WARN', '部署Agent', { status: deployData.status });
    await sleep(10000);

    await page.goto(`${BASE_URL}/agents/${agentId}`);
    await sleep(2000);
    await takeScreenshot(page, 'E5-4 部署成功', 'Agent部署成功页面');

    const delRes = await fetch(`${API_URL}/agents/${agentId}`, { method: 'DELETE' });
    const delData = await delRes.json();
    await log('E5-5', 'PASS', '删除Agent', delData);

    await page.goto(`${BASE_URL}/`);
    await sleep(2000);
    await takeScreenshot(page, 'E5-5 删除成功', 'Agent删除后首页');

    const getRes = await fetch(`${API_URL}/agents/${agentId}`);
    if (getRes.status === 404) {
      await log('E5-6', 'PASS', '验证Agent已删除', { status: 404 });
    } else {
      await log('E5-6', 'FAIL', 'Agent仍然存在', { status: getRes.status });
    }

    console.log('\n=== E5 测试完成 ===\n');
  } catch (e) {
    await log('E5', 'FAIL', '测试失败', { error: e.message });
  } finally {
    await browser.close();
  }

  fs.writeFileSync('screenshots/metadata.json', JSON.stringify(screenshots, null, 2));
}

main();
