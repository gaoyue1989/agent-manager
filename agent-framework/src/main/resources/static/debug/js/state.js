/* ===== 全局状态管理 ===== */

const state = {
  agent: {
    info: null,
    health: null,
    tools: { builtin: [], custom: [], mcp: [] },
    skills: [],
    mcpServers: []
  },
  threads: {
    list: [],
    current: null // 当前选中的 session_id
  },
  ui: {
    streamMode: 'a2a', // a2a | channel
    isStreaming: false
  }
};

const listeners = {};

export function getState(path) {
  return path.split('.').reduce((obj, key) => (obj == null ? undefined : obj[key]), state);
}

export function setState(path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  const target = keys.reduce((obj, key) => {
    if (obj[key] == null) obj[key] = {};
    return obj[key];
  }, state);
  target[last] = value;
  notify(path, value);
}

export function subscribe(path, callback) {
  if (!listeners[path]) listeners[path] = [];
  listeners[path].push(callback);
  return () => {
    listeners[path] = (listeners[path] || []).filter((cb) => cb !== callback);
  };
}

function notify(path, value) {
  (listeners[path] || []).forEach((cb) => cb(value));
}

/** 初始化时从后端加载基础信息（健康、Agent 名、工具、技能、MCP） */
export async function loadAgentInfo() {
  try {
    const health = await window.App.api.getHealth();
    setState('agent.health', health);
    updateHeaderStatus(health);
  } catch (e) {
    setHeaderStatus('disconnected', 'error');
  }
  try {
    const info = await window.App.api.getInfo();
    setState('agent.info', info);
    const nameEl = document.getElementById('agentName');
    if (nameEl) nameEl.textContent = info.agent || '';
  } catch (e) { /* 忽略 */ }
  try {
    const tools = await window.App.api.getTools();
    setState('agent.tools', tools);
  } catch (e) { /* 忽略 */ }
  try {
    const skills = await window.App.api.getSkills();
    setState('agent.skills', skills || []);
  } catch (e) { /* 忽略 */ }
  try {
    const mcps = await window.App.api.getMcpServers();
    setState('agent.mcpServers', mcps || []);
  } catch (e) { /* 忽略 */ }
}

function updateHeaderStatus(health) {
  if (!health) return;
  const ok = health.status === 'healthy';
  setHeaderStatus(ok ? health.status : (health.status || 'unknown'), ok ? 'ok' : 'error');
  const cpEl = document.getElementById('checkpointStatus');
  if (cpEl) {
    cpEl.textContent = health.llm_configured ? 'LLM ✓' : 'LLM ✗';
    cpEl.style.color = health.llm_configured ? 'var(--green)' : 'var(--yellow)';
  }
}

function setHeaderStatus(text, cls) {
  const dot = document.getElementById('statusDot');
  const txt = document.getElementById('statusText');
  if (dot) dot.className = 'status-dot ' + (cls || '');
  if (txt) txt.textContent = text;
}
