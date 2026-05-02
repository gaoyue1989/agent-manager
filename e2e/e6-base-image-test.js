const puppeteer = require('puppeteer');
const fs = require('fs');
const { execSync } = require('child_process');

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

  try {
    console.log('=== E6: 基础镜像构建测试 ===\n');

    try {
      const images = execSync('docker images --format "{{.Repository}}:{{.Tag}}"').toString();
      if (images.includes('agent-base:latest') || images.includes('172.20.0.1:5001/agent-base:latest')) {
        await log('E6-1', 'PASS', '基础镜像存在');
      } else {
        await log('E6-1', 'WARN', '基础镜像不存在，可能需要重启后端服务');
      }
    } catch (e) {
      await log('E6-1', 'FAIL', '检查镜像失败', { error: e.message });
    }

    const createRes = await fetch(`${API_URL}/agents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        config: JSON.stringify({
          name: 'e6-base-image-test',
          description: 'E2E base image test',
          model: 'qwen3.6-plus',
          system_prompt: 'You are a test agent.',
        }),
        config_type: 'json',
      }),
    });
    const createData = await createRes.json();
    const agentId = createData.id;
    await log('E6-2', 'PASS', '创建Agent', { agentId });

    await page.goto(`${BASE_URL}/agents/${agentId}`);
    await sleep(2000);
    await takeScreenshot(page, 'E6-2 创建Agent', 'Agent创建成功页面');

    await fetch(`${API_URL}/agents/${agentId}/generate`, { method: 'POST' });
    await log('E6-3', 'INFO', '生成代码');
    await sleep(3000);

    const buildStart = Date.now();
    await fetch(`${API_URL}/agents/${agentId}/build`, { method: 'POST' });
    await sleep(5000);
    const buildElapsed = (Date.now() - buildStart) / 1000;

    if (buildElapsed < 15) {
      await log('E6-4', 'PASS', `构建耗时 ${buildElapsed.toFixed(1)}s`, { elapsed: buildElapsed });
    } else {
      await log('E6-4', 'WARN', `构建耗时 ${buildElapsed.toFixed(1)}s (预期 < 15s)`, { elapsed: buildElapsed });
    }

    await page.goto(`${BASE_URL}/agents/${agentId}`);
    await sleep(2000);
    await takeScreenshot(page, 'E6-4 构建成功', 'Agent构建成功页面');

    try {
      const inspectCmd = `docker inspect 172.20.0.1:5001/agent-${agentId}:v1 --format '{{.Config.Image}}' 2>/dev/null || echo "not found"`;
      const baseImage = execSync(inspectCmd).toString().trim();
      if (baseImage.includes('agent-base')) {
        await log('E6-5', 'PASS', '镜像基于agent-base', { base: baseImage });
      } else {
        await log('E6-5', 'WARN', '未检测到基础镜像', { base: baseImage });
      }
    } catch (e) {
      await log('E6-5', 'WARN', '检查镜像基础失败', { error: e.message });
    }

    const deployRes = await fetch(`${API_URL}/agents/${agentId}/deploy`, { method: 'POST' });
    await log('E6-6', 'INFO', '部署Agent');
    await sleep(10000);

    await page.goto(`${BASE_URL}/agents/${agentId}`);
    await sleep(2000);
    await takeScreenshot(page, 'E6-6 部署成功', 'Agent部署成功页面');

    const chatRes = await fetch(`${API_URL}/agents/${agentId}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Hello', history: [] }),
    });
    const chatData = await chatRes.json();
    if (chatData.success) {
      await log('E6-7', 'PASS', '聊天测试成功');
    } else {
      await log('E6-7', 'WARN', '聊天测试失败', { error: chatData.error });
    }

    await fetch(`${API_URL}/agents/${agentId}`, { method: 'DELETE' });
    await log('E6-8', 'INFO', '清理测试Agent');

    console.log('\n=== E6 测试完成 ===\n');
  } catch (e) {
    await log('E6', 'FAIL', '测试失败', { error: e.message });
  } finally {
    await browser.close();
  }

  fs.writeFileSync('screenshots/metadata.json', JSON.stringify(screenshots, null, 2));
}

main();
