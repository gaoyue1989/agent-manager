import { icon } from './icons.js';

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
    streamMode: 'channel',
    isStreaming: false,
    theme: null,
    protocol: (() => { try { return sessionStorage.getItem('debug-proto') || 'native'; } catch (e) { return 'native'; } })(),
    userId: (() => { try { return localStorage.getItem('debug-uid') || 'debug-user'; } catch (e) { return 'debug-user'; } })()
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
  if (path === 'threads.current') {
    try { value ? localStorage.setItem('debug-sid', value) : localStorage.removeItem('debug-sid'); } catch (e) {}
  }
  if (path === 'ui.userId') {
    try { value ? localStorage.setItem('debug-uid', value) : localStorage.removeItem('debug-uid'); } catch (e) {}
  }
  if (path === 'ui.protocol') {
    try { value ? sessionStorage.setItem('debug-proto', value) : sessionStorage.removeItem('debug-proto'); } catch (e) {}
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
  } catch (e) {}
  try {
    const tools = await window.App.api.getTools();
    setState('agent.tools', tools);
  } catch (e) {}
  try {
    const skills = await window.App.api.getSkills();
    setState('agent.skills', skills || []);
  } catch (e) {}
  try {
    const mcps = await window.App.api.getMcpServers();
    setState('agent.mcpServers', mcps || []);
  } catch (e) {}
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
  try { localStorage.setItem('debug-theme', theme); } catch (e) {}
  setState('ui.theme', theme);
  syncThemeIcon();
}

function syncThemeIcon() {
  const btn = document.getElementById('themeToggle');
  if (!btn) return;
  if (currentTheme() === 'dark') {
    btn.innerHTML = icon('sun', 17);
    btn.title = '切换到浅色模式';
  } else {
    btn.innerHTML = icon('moon', 17);
    btn.title = '切换到深色模式';
  }
}
