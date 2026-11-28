import { Router } from './router.js';
import { api } from './api.js';
import { agui } from './agui.js';
import { icon } from './icons.js';
import * as state from './state.js';
import * as utils from './utils.js';

import chatModule from '../modules/chat.js';
import toolsModule from '../modules/tools.js';
import skillsModule from '../modules/skills.js';
import mcpModule from '../modules/mcp.js';
import modelsModule from '../modules/models.js';
import configModule from '../modules/config.js';
import memoryModule from '../modules/memory.js';
import databaseModule from '../modules/database.js';
import workspaceModule from '../modules/workspace.js';
import sandboxModule from '../modules/sandbox.js';
import logsModule from '../modules/logs.js';

const routes = {
  '#/':          { title: '对话',      icon: 'chat',      group: '核心调试',     module: chatModule },
  '#/tools':     { title: '工具',      icon: 'tools',     group: '能力配置',     module: toolsModule },
  '#/skills':    { title: '技能',      icon: 'skills',    group: '能力配置',     module: skillsModule },
  '#/mcp':       { title: 'MCP 服务',  icon: 'mcp',       group: '能力配置',     module: mcpModule },
  '#/models':    { title: '模型',      icon: 'models',    group: '能力配置',     module: modelsModule },
  '#/config':    { title: '配置',      icon: 'config',    group: '能力配置',     module: configModule },
  '#/memory':    { title: '记忆',      icon: 'memory',    group: '运行观测',     module: memoryModule },
  '#/database':  { title: '数据库',    icon: 'database',  group: '运行观测',     module: databaseModule },
  '#/workspace': { title: '工作区',    icon: 'workspace', group: '运行观测',     module: workspaceModule },
  '#/sandbox':   { title: '沙箱',      icon: 'sandbox',   group: '运行观测',     module: sandboxModule },
  '#/logs':      { title: '日志',      icon: 'logs',      group: '运行观测',     module: logsModule }
};

const modal = {
  open(html) {
    document.getElementById('modalContent').innerHTML = html;
    document.getElementById('modalOverlay').classList.add('active');
  },
  close() {
    document.getElementById('modalOverlay').classList.remove('active');
  }
};

window.App = { api, agui, state, utils, modal };

window.App.toolGroupToggle = (el) => {
  const group = el.closest('.tool-group');
  if (group) group.classList.toggle('open');
};

window.App.toolRowToggle = (el) => {
  el.classList.toggle('open');
};

window.App.remoteCallToggle = (headerEl) => {
  const panel = headerEl.closest('.remote-call');
  if (panel) panel.classList.toggle('open');
};

window.App.thinkingToggle = (headerEl) => {
  const body = headerEl.nextElementSibling;
  const toggle = headerEl.querySelector('.thinking-toggle');
  body.classList.toggle('open');
  toggle.textContent = body.classList.contains('open') ? '▲' : '▼';
};

window.App.toggleLlmCall = (idx) => {
  const entry = document.getElementById('llm-entry-' + idx);
  if (entry) entry.classList.toggle('open');
};

document.getElementById('modalOverlay').addEventListener('click', (e) => {
  if (e.target === e.target.closest('.modal-overlay')) modal.close();
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    const overlay = document.getElementById('modalOverlay');
    if (overlay && overlay.classList.contains('active')) modal.close();
  }
});

const mobileMenuBtn = document.getElementById('mobileMenuBtn');
if (mobileMenuBtn) mobileMenuBtn.innerHTML = icon('menu', 18);
const sidebarToggle = document.getElementById('sidebarToggle');
if (sidebarToggle) sidebarToggle.innerHTML = icon('sidebarToggle', 18);
const monitorLink = document.getElementById('monitorLink');
if (monitorLink) monitorLink.innerHTML = icon('monitor', 17);

state.initTheme();
state.loadAgentInfo();
const router = new Router(routes);
router.resolve();
setInterval(() => state.loadAgentInfo(), 30000);
