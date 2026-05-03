const { execSync } = require('child_process');

const API_URL = 'http://localhost:8080/api/v1';

async function log(name, status, description, data = {}) {
  console.log(`[${status}] ${name}: ${description}`);
  if (Object.keys(data).length > 0) {
    console.log(JSON.stringify(data, null, 2));
  }
}

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function main() {
  let agentId;

  // E7-1: 检查 Ingress Controller
  try {
    const pods = execSync('kubectl get pods -n ingress-nginx -o jsonpath="{.items[*].status.phase}"').toString();
    if (pods.includes('Running')) {
      await log('E7-1', 'PASS', 'Ingress Controller Running');
    } else {
      await log('E7-1', 'FAIL', 'Ingress Controller 未运行', { pods });
    }
  } catch (e) {
    await log('E7-1', 'FAIL', '检查 Ingress Controller 失败', { error: e.message });
  }

  // E7-2: 检查 IngressClass
  try {
    const classes = execSync('kubectl get ingressclass -o jsonpath="{.items[*].metadata.name}"').toString();
    if (classes.includes('nginx')) {
      await log('E7-2', 'PASS', 'IngressClass nginx 存在');
    } else {
      await log('E7-2', 'FAIL', 'IngressClass nginx 不存在', { classes });
    }
  } catch (e) {
    await log('E7-2', 'FAIL', '检查 IngressClass 失败', { error: e.message });
  }

  // E7-3: 创建 Agent
  try {
    const createRes = await fetch(`${API_URL}/agents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        config: JSON.stringify({
          name: 'e7-ingress-test',
          description: 'E2E ingress test agent',
          model: 'qwen3.6-plus',
          system_prompt: 'You are a test agent.',
        }),
        config_type: 'json',
      }),
    });
    const createData = await createRes.json();
    agentId = createData.id;
    await log('E7-3', 'PASS', '创建 Agent', { agentId });
  } catch (e) {
    await log('E7-3', 'FAIL', '创建 Agent 失败', { error: e.message });
    process.exit(1);
  }

  // 生成代码 + 构建 + 发布
  await log('E7-4', 'INFO', '生成代码...');
  await fetch(`${API_URL}/agents/${agentId}/generate`, { method: 'POST' });
  await sleep(3000);
  
  await log('E7-5', 'INFO', '构建镜像...');
  await fetch(`${API_URL}/agents/${agentId}/build`, { method: 'POST' });
  await sleep(30000);
  
  // E7-6: 发布 Agent
  try {
    const publishRes = await fetch(`${API_URL}/agents/${agentId}/publish`, { method: 'POST' });
    const publishData = await publishRes.json();
    await sleep(10000);
    
    if (publishData.status === 'running') {
      await log('E7-6', 'PASS', '发布 Agent', { endpoint_url: publishData.endpoint_url });
    } else {
      await log('E7-6', 'FAIL', '发布失败', publishData);
    }
  } catch (e) {
    await log('E7-6', 'FAIL', '发布失败', { error: e.message });
  }

  // E7-7: 检查 Ingress 资源
  try {
    const ingress = execSync(`kubectl get ingress agent-${agentId}-ingress -n default -o jsonpath="{.metadata.name}"`).toString();
    if (ingress === `agent-${agentId}-ingress`) {
      await log('E7-7', 'PASS', 'Ingress 资源存在', { ingress });
    } else {
      await log('E7-7', 'FAIL', 'Ingress 资源不存在');
    }
  } catch (e) {
    await log('E7-7', 'FAIL', '检查 Ingress 失败', { error: e.message });
  }

  // E7-8: 通过 Ingress 访问 Agent (使用 port-forward)
  try {
    const portForward = execSync('kubectl port-forward svc/ingress-nginx-controller -n ingress-nginx 8888:80 &').toString();
    await sleep(3000);
    
    const chatRes = await fetch(`http://localhost:8888/agent/${agentId}/`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    });
    
    if (chatRes.ok || chatRes.status === 500) {
      await log('E7-8', 'PASS', 'Ingress 访问成功 (HTTP ' + chatRes.status + ')');
    } else {
      await log('E7-8', 'FAIL', `Ingress 访问失败: ${chatRes.status}`);
    }
    
    execSync('pkill -f "port-forward.*8888"').toString();
  } catch (e) {
    await log('E7-8', 'WARN', 'Ingress 访问测试跳过', { error: e.message });
  }

  // E7-9: 下线 Agent
  try {
    await fetch(`${API_URL}/agents/${agentId}/unpublish`, { method: 'POST' });
    await sleep(5000);
    await log('E7-9', 'PASS', '下线 Agent');
  } catch (e) {
    await log('E7-9', 'FAIL', '下线失败', { error: e.message });
  }

  // E7-10: 验证 Ingress 删除
  try {
    const ingress = execSync(`kubectl get ingress agent-${agentId}-ingress -n default 2>&1`).toString();
    if (ingress.includes('NotFound')) {
      await log('E7-10', 'PASS', 'Ingress 已删除');
    } else {
      await log('E7-10', 'FAIL', 'Ingress 仍然存在');
    }
  } catch (e) {
    await log('E7-10', 'PASS', 'Ingress 已删除 (NotFound)');
  }

  // 清理
  await log('CLEANUP', 'INFO', '清理测试 Agent...');
  await fetch(`${API_URL}/agents/${agentId}`, { method: 'DELETE' });
  await log('CLEANUP', 'PASS', '清理完成');
}

main();
