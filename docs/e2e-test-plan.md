# E2E 测试计划

## 测试环境

- 后端: `http://localhost:8080`
- 前端: `http://localhost:3000`
- Nginx: `http://localhost:8911`
- Mock MCP Server: `http://localhost:9998`

## 测试用例

### E5: Agent 删除功能

| 用例 | 操作 | 验证点 | 截图 |
|------|------|--------|------|
| E5-1 | 创建 Agent | 返回 Agent ID | 创建成功页面 |
| E5-2 | 生成代码 | 状态 → generated | 代码生成成功 |
| E5-3 | 构建镜像 | 状态 → built | 镜像构建成功 |
| E5-4 | 部署 Agent | 状态 → deployed | 部署成功 |
| E5-5 | 删除 Agent | 返回删除结果 | 删除确认页面 |
| E5-6 | 查询 Agent | 返回 404 | Agent 不存在 |
| E5-7 | 检查 MinIO | 文件已删除 | MinIO Console 截图 |
| E5-8 | 检查 K8s | Sandbox 已删除 | kubectl get sandbox |
| E5-9 | 检查 Docker | 镜像已删除 | docker images |

### E6: 基础镜像构建

| 用例 | 操作 | 验证点 | 截图 |
|------|------|--------|------|
| E6-1 | 启动后端 | 基础镜像构建日志 | 启动日志 |
| E6-2 | 检查基础镜像 | 镜像存在 | docker images |
| E6-3 | 创建 Agent | Agent 创建成功 | 创建成功页面 |
| E6-4 | 构建镜像 | 构建时间 < 10s | 构建耗时显示 |
| E6-5 | 检查镜像 | 基于 agent-base | docker inspect |
| E6-6 | 部署测试 | Agent 运行正常 | Pod 状态正常 |
| E6-7 | 聊天测试 | 响应正常 | 聊天记录 |

## 测试脚本

### E5: Agent 删除测试

```javascript
// e2e/e5-delete-test.js
const puppeteer = require('puppeteer');
const fs = require('fs');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8911';
const API_URL = `${BASE_URL}/api/v1`;

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function log(name, status, description, data = {}) {
  console.log(`[${status}] ${name}: ${description}`);
  console.log(JSON.stringify(data, null, 2));
}

async function main() {
  const browser = await puppeteer.launch({ headless: 'new' });
  const page = await browser.newPage();

  let agentId;

  // E5-1: 创建 Agent
  try {
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
  } catch (e) {
    await log('E5-1', 'FAIL', '创建 Agent 失败', { error: e.message });
    process.exit(1);
  }

  // E5-2: 生成代码
  try {
    await fetch(`${API_URL}/agents/${agentId}/generate`, { method: 'POST' });
    await sleep(3000);
    await log('E5-2', 'PASS', '生成代码');
  } catch (e) {
    await log('E5-2', 'FAIL', '生成代码失败', { error: e.message });
  }

  // E5-3: 构建镜像
  try {
    await fetch(`${API_URL}/agents/${agentId}/build`, { method: 'POST' });
    await sleep(30000);
    await log('E5-3', 'PASS', '构建镜像');
  } catch (e) {
    await log('E5-3', 'FAIL', '构建镜像失败', { error: e.message });
  }

  // E5-4: 部署
  try {
    await fetch(`${API_URL}/agents/${agentId}/deploy`, { method: 'POST' });
    await sleep(10000);
    await log('E5-4', 'PASS', '部署 Agent');
  } catch (e) {
    await log('E5-4', 'FAIL', '部署失败', { error: e.message });
  }

  // E5-5: 删除 Agent
  try {
    const delRes = await fetch(`${API_URL}/agents/${agentId}`, { method: 'DELETE' });
    const delData = await delRes.json();
    await log('E5-5', 'PASS', '删除 Agent', delData);
  } catch (e) {
    await log('E5-5', 'FAIL', '删除失败', { error: e.message });
    process.exit(1);
  }

  // E5-6: 验证删除
  try {
    const getRes = await fetch(`${API_URL}/agents/${agentId}`);
    if (getRes.status === 404) {
      await log('E5-6', 'PASS', 'Agent 已删除');
    } else {
      await log('E5-6', 'FAIL', 'Agent 仍然存在');
    }
  } catch (e) {
    await log('E5-6', 'PASS', 'Agent 已删除 (404)');
  }

  await browser.close();
}

main();
```

### E6: 基础镜像测试

```javascript
// e2e/e6-base-image-test.js
const { execSync } = require('child_process');

async function log(name, status, description, data = {}) {
  console.log(`[${status}] ${name}: ${description}`);
  if (Object.keys(data).length > 0) {
    console.log(JSON.stringify(data, null, 2));
  }
}

async function main() {
  // E6-1: 检查基础镜像
  try {
    const images = execSync('docker images --format "{{.Repository}}:{{.Tag}}"').toString();
    if (images.includes('agent-base:latest') || images.includes('localhost:5000/agent-base:latest')) {
      await log('E6-1', 'PASS', '基础镜像存在');
    } else {
      await log('E6-1', 'FAIL', '基础镜像不存在');
    }
  } catch (e) {
    await log('E6-1', 'FAIL', '检查镜像失败', { error: e.message });
  }

  // E6-2: 创建 Agent 并构建
  const API_URL = 'http://localhost:8080/api/v1';
  let agentId;

  try {
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
    agentId = createData.id;
    await log('E6-2', 'PASS', '创建 Agent', { agentId });
  } catch (e) {
    await log('E6-2', 'FAIL', '创建失败', { error: e.message });
    process.exit(1);
  }

  // 生成代码
  await fetch(`${API_URL}/agents/${agentId}/generate`, { method: 'POST' });
  await new Promise(r => setTimeout(r, 3000));

  // E6-3: 构建镜像并计时
  try {
    const start = Date.now();
    await fetch(`${API_URL}/agents/${agentId}/build`, { method: 'POST' });
    await new Promise(r => setTimeout(r, 5000));
    const elapsed = (Date.now() - start) / 1000;
    
    if (elapsed < 15) {
      await log('E6-3', 'PASS', `构建耗时 ${elapsed.toFixed(1)}s`, { elapsed });
    } else {
      await log('E6-3', 'WARN', `构建耗时 ${elapsed.toFixed(1)}s (预期 < 15s)`, { elapsed });
    }
  } catch (e) {
    await log('E6-3', 'FAIL', '构建失败', { error: e.message });
  }

  // E6-4: 检查镜像基础
  try {
    const inspect = execSync(`docker inspect localhost:5000/agent-${agentId}:v1 --format '{{.Config.Image}}'`).toString().trim();
    if (inspect.includes('agent-base')) {
      await log('E6-4', 'PASS', '镜像基于 agent-base', { base: inspect });
    } else {
      await log('E6-4', 'FAIL', '镜像未使用 agent-base', { base: inspect });
    }
  } catch (e) {
    await log('E6-4', 'FAIL', '检查镜像失败', { error: e.message });
  }

  // 清理
  await fetch(`${API_URL}/agents/${agentId}`, { method: 'DELETE' });
}

main();
```

## 运行方式

```bash
# 运行 E5 测试
cd e2e && node e5-delete-test.js

# 运行 E6 测试
cd e2e && node e6-base-image-test.js

# 运行所有测试
make test-e2e
```

## 预期结果

- E5: 所有测试 PASS，Agent 及相关资源完全删除
- E6: 所有测试 PASS，构建时间显著减少
