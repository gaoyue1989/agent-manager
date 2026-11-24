#!/usr/bin/env node
import { readFileSync } from 'node:fs';

const AGENT = process.env.AGENT_URL ?? 'http://127.0.0.1:30080/agent/browser-takeover';
const OSB = process.env.OSB_URL ?? 'http://127.0.0.1:18600';
const USER = process.env.DEMO_USER ?? 'demo-user';
const PASSWORD = process.env.DEMO_PASSWORD ?? 'HumanOnly-2026';
const SESSION = process.env.SESSION_ID ?? `takeover-${Date.now().toString(36)}`;
const TURN_TIMEOUT_MS = Number(process.env.TURN_TIMEOUT_MS ?? 300000);

function secretsKey() {
  if (process.env.OPENSANDBOX_API_KEY) return process.env.OPENSANDBOX_API_KEY;
  const file = process.env.SECRETS_FILE
    ? new URL(`file://${process.env.SECRETS_FILE}`)
    : new URL('../../../.env.secrets', import.meta.url);
  try {
    const line = readFileSync(file, 'utf8')
      .split('\n')
      .find((item) => item.startsWith('OPENSANDBOX_API_KEY='));
    return line ? line.slice('OPENSANDBOX_API_KEY='.length).replaceAll('"', '') : '';
  } catch {
    return '';
  }
}

const API_KEY = secretsKey();

let passed = 0;
let failed = 0;

function check(name, ok, detail = '') {
  if (ok) {
    passed += 1;
    console.log(`  PASS  ${name}`);
  } else {
    failed += 1;
    console.log(`  FAIL  ${name}${detail ? `  (${detail})` : ''}`);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function* sse(url, init) {
  const response = await fetch(url, init);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${await response.text().catch(() => '')}`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let index = buffer.indexOf('\n\n');
    while (index >= 0) {
      const chunk = buffer.slice(0, index);
      buffer = buffer.slice(index + 2);
      const data = chunk
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('\n');
      if (data) {
        try {
          yield JSON.parse(data);
        } catch {
          /* ignore */
        }
      }
      index = buffer.indexOf('\n\n');
    }
  }
}

const headers = () => ({ 'Content-Type': 'application/json', 'X-User-Id': USER });

async function chat(text, timeoutMs = TURN_TIMEOUT_MS) {
  const frames = [];
  for await (const frame of sse(`${AGENT}/threads/chat`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ sessionId: SESSION, message: text, userId: USER }),
    signal: AbortSignal.timeout(timeoutMs),
  })) {
    frames.push(frame);
  }
  return frames;
}

async function release() {
  const frames = [];
  for await (const frame of sse(`${AGENT}/threads/${encodeURIComponent(SESSION)}/browser/release`, {
    method: 'POST',
    headers: headers(),
    signal: AbortSignal.timeout(TURN_TIMEOUT_MS),
  })) {
    frames.push(frame);
  }
  return frames;
}

async function browserState() {
  const response = await fetch(`${AGENT}/threads/${encodeURIComponent(SESSION)}/browser`, {
    headers: { 'X-User-Id': USER },
  });
  if (!response.ok) throw new Error(`state HTTP ${response.status}`);
  return response.json();
}

async function takeover(reason) {
  const response = await fetch(`${AGENT}/threads/${encodeURIComponent(SESSION)}/browser/takeover`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(`takeover HTTP ${response.status}`);
  return response.json();
}

async function runInSandbox(sandboxId, command) {
  const response = await fetch(`${OSB}/v1/sandboxes/${sandboxId}/proxy/44772/command`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'OPEN-SANDBOX-API-KEY': API_KEY,
    },
    body: JSON.stringify({ command, timeout: 180000 }),
    signal: AbortSignal.timeout(200000),
  });
  if (!response.ok) {
    throw new Error(`execd HTTP ${response.status}: ${await response.text().catch(() => '')}`);
  }
  const text = await response.text();
  const stdout = [];
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('{')) continue;
    try {
      const event = JSON.parse(trimmed);
      if (event.type === 'stdout') stdout.push(event.text);
      if (event.type === 'error') throw new Error(`sandbox command failed: ${JSON.stringify(event.error)}`);
    } catch (error) {
      if (error instanceof SyntaxError) continue;
      throw error;
    }
  }
  return stdout.join('\n');
}

function toolCalls(frames) {
  const calls = new Map();
  for (const frame of frames) {
    if (frame.type === 'TOOL_CALL_START') {
      calls.set(frame.toolCallId, { id: frame.toolCallId, name: frame.toolName, args: '', state: '' });
    } else if (frame.type === 'TOOL_CALL_DELTA') {
      const call = calls.get(frame.toolCallId);
      if (call) call.args += frame.delta ?? '';
    } else if (frame.type === 'TOOL_RESULT_END') {
      const call = calls.get(frame.toolCallId);
      if (call) call.state = frame.state;
    }
  }
  return [...calls.values()];
}

function assistantText(frames) {
  return frames
    .filter((frame) => frame.type === 'TEXT_BLOCK_DELTA')
    .map((frame) => frame.delta ?? '')
    .join('');
}

function executeCalls(frames) {
  return toolCalls(frames).filter((call) => call.name === 'execute');
}

async function main() {
  console.log(`agent=${AGENT}\nsession=${SESSION}\nuser=${USER}\n`);
  if (!API_KEY) {
    throw new Error('缺少 OPENSANDBOX_API_KEY（SECRETS_FILE / .env.secrets / 环境变量）');
  }

  console.log('T0 预热：确认浏览器与 CLI 可用');
  const warm = await chat('先确认环境：执行 chrome-devtools list_pages，把当前页面的标题和 URL 告诉我。');
  const warmText = assistantText(warm);
  check('T0 调用 execute', executeCalls(warm).length > 0);
  check('T0 返回页面信息', /1088|登录|page/i.test(warmText), warmText.slice(0, 120));

  console.log('T1 被动触发：登录需要密码 → Agent 应请求人工接管');
  const loginTask = await chat(
    '打开演示站点登录页 http://127.0.0.1:8088/login ，用账号 demo 登录。密码我不知道，需要输入密码时请请求人工接管。',
  );
  check('T1 调用 request_browser_takeover',
    toolCalls(loginTask).some((call) => call.name === 'request_browser_takeover'));
  const afterTrigger = await browserState();
  check('T1 控制权为 HUMAN', afterTrigger.control.owner === 'HUMAN');
  check('T1 沙箱已创建', Boolean(afterTrigger.sandbox?.sandbox_id));

  console.log('T2 接管期间闸门：Agent 的沙箱命令应被拒绝');
  const duringTakeover = await chat('继续执行：把账号填进登录表单。');
  const takeoverExec = executeCalls(duringTakeover);
  const succeeded = takeoverExec.filter((call) => call.state === 'SUCCESS');
  const denied = takeoverExec.filter((call) => call.state === 'DENIED');
  check('T2 接管期间无成功执行的命令', succeeded.length === 0,
    succeeded.map((c) => c.args).join(' | ').slice(0, 120));
  check('T2 若尝试执行则被 DENIED',
    takeoverExec.length === 0 || takeoverExec.every((call) => call.state === 'DENIED'),
    takeoverExec.map((c) => `${c.state}:${c.args.slice(0, 60)}`).join(' | '));
  check('T2 控制权仍为 HUMAN', (await browserState()).control.owner === 'HUMAN');

  console.log('T3 模拟人工：在沙箱里用 CLI 完成登录');
  const sandboxId = afterTrigger.sandbox.sandbox_id;
  const snapshot = await runInSandbox(sandboxId, 'cd /root && chrome-devtools take_snapshot 1');
  const userUid = snapshot.match(/uid=(\S+) textbox "账号"/)?.[1];
  const passUid = snapshot.match(/uid=(\S+) textbox "密码"/)?.[1];
  const submitUid = snapshot.match(/uid=(\S+) button "登录"/)?.[1];
  check('T3 快照含登录表单', Boolean(userUid && passUid && submitUid), snapshot.slice(0, 160));
  if (userUid && passUid && submitUid) {
    await runInSandbox(sandboxId,
      `cd /root && chrome-devtools fill 1 "${userUid}" "demo" && chrome-devtools fill 1 "${passUid}" "${PASSWORD}" && chrome-devtools click 1 "${submitUid}" && sleep 1 && chrome-devtools list_pages`);
    const pages = await runInSandbox(sandboxId, 'cd /root && chrome-devtools list_pages');
    check('T3 人工登录成功（已到订单页）', pages.includes('/orders'), pages.slice(0, 160));
  }

  console.log('T4 交还控制权：Agent 应先重新采样再继续');
  const releaseFrames = await release();
  const afterRelease = await browserState();
  check('T4 控制权回到 AGENT', afterRelease.control.owner === 'AGENT');
  const resumeExec = executeCalls(releaseFrames);
  const firstResume = resumeExec[0];
  check('T4 续跑调用 execute', Boolean(firstResume));
  check('T4 首个命令是重采样',
    Boolean(firstResume && /list_pages|take_snapshot/.test(firstResume.args)),
    firstResume ? firstResume.args.slice(0, 160) : '');
  const summary = assistantText(releaseFrames);
  check('T4 汇总订单', /订单/.test(summary) && /(未发货|已发货|合计|总金额)/.test(summary), summary.slice(0, 200));

  console.log('T5 主动接管：执行中人工接管应停止当前轮');
  const running = chat('把订单页面的表格内容逐行读出来，并截图保存到 /tmp/orders.png。');
  await sleep(4000);
  const takeResult = await takeover('e2e 主动接管');
  check('T5 主动接管成功', takeResult.control?.owner === 'HUMAN');
  const runningFrames = await running;
  check('T5 当前轮被停止', runningFrames.some((frame) => frame.type === 'cancelled'));
  const stateAfterActive = await browserState();
  check('T5 控制权为 HUMAN', stateAfterActive.control.owner === 'HUMAN');

  console.log('T6 交还后继续');
  const secondRelease = await release();
  const stateFinal = await browserState();
  check('T6 控制权回到 AGENT', stateFinal.control.owner === 'AGENT');
  check('T6 续跑成功', executeCalls(secondRelease).some((call) => call.state === 'SUCCESS'));

  console.log(`\n结果：PASS ${passed} / FAIL ${failed}`);
  if (failed > 0) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error('e2e 执行失败:', error);
  process.exitCode = 1;
});
