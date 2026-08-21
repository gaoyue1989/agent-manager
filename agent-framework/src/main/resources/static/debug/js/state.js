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
    current: (() => { try { return localStorage.getItem('debug-sid'); } catch (e) { return null; } })()
  },
  ui: {
    streamMode: 'a2a', // a2a | channel
    isStreaming: false,
    theme: null // 'light' | 'dark' | null(跟随系统)
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
  // 会话 ID 持久化到 localStorage（刷新后恢复当前 session）
  if (path === 'threads.current') {
    try { value ? localStorage.setItem('debug-sid', value) : localStorage.removeItem('debug-sid'); } catch (e) { /* ignore */ }
  }
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

/* ---------- 主题切换（手动 > 系统自动） ---------- */

export function initTheme() {
  const btn = document.getElementById('themeToggle');
  if (!btn) return;
  syncThemeIcon();
  btn.addEventListener('click', () => {
    const next = currentTheme() === 'dark' ? 'light' : 'dark';
    setTheme(next);
  });
}

function currentTheme() {
  const root = document.documentElement;
  if (root.classList.contains('dark')) return 'dark';
  if (root.classList.contains('light')) return 'light';
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function getTheme() {
  return currentTheme();
}

function setTheme(theme) {
  document.documentElement.classList.remove('light', 'dark');
  if (theme === 'dark') document.documentElement.classList.add('dark');
  else if (theme === 'light') document.documentElement.classList.add('light');
  try { localStorage.setItem('debug-theme', theme); } catch (e) { /* ignore */ }
  setState('ui.theme', theme);
  syncThemeIcon();
}

function syncThemeIcon() {
  const btn = document.getElementById('themeToggle');
  if (!btn) return;
  if (currentTheme() === 'dark') {
    btn.textContent = '☀';
    btn.title = '切换到浅色';
  } else {
    btn.textContent = '☾';
    btn.title = '切换到深色';
  }
}