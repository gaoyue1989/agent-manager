/* ===== 应用入口：注册模块 + 初始化全局对象 ===== */

import { Router } from './router.js';
import { api } from './api.js';
import * as state from './state.js';
import * as utils from './utils.js';

import chatModule from '../modules/chat.js';
import toolsModule from '../modules/tools.js';
import skillsModule from '../modules/skills.js';
import mcpModule from '../modules/mcp.js';
import configModule from '../modules/config.js';
import memoryModule from '../modules/memory.js';
import databaseModule from '../modules/database.js';
import workspaceModule from '../modules/workspace.js';
import sandboxModule from '../modules/sandbox.js';
import logsModule from '../modules/logs.js';

const routes = {
  '#/':          { title: 'Chat',      icon: '💬', module: chatModule },
  '#/tools':     { title: 'Tools',     icon: '🔧', module: toolsModule },
  '#/skills':    { title: 'Skills',    icon: '📚', module: skillsModule },
  '#/mcp':       { title: 'MCP',       icon: '🔌', module: mcpModule },
  '#/config':    { title: 'Config',    icon: '⚙️', module: configModule },
  '#/memory':    { title: 'Memory',    icon: '🧠', module: memoryModule },
  '#/database':  { title: 'Database',  icon: '🗄️', module: databaseModule },
  '#/workspace': { title: 'Workspace', icon: '📁', module: workspaceModule },
  '#/sandbox':   { title: 'Sandbox',   icon: '📦', module: sandboxModule },
  '#/logs':      { title: 'Logs',      icon: '📋', module: logsModule }
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

// 全局暴露（供内联 onclick 使用）
window.App = { api, state, utils, modal };

// 工具调用块折叠切换（供模块内联 onclick 使用）
window.App.toolToggle = (headerEl) => {
  const body = headerEl.nextElementSibling;
  const toggle = headerEl.querySelector('.tc-toggle');
  body.classList.toggle('open');
  toggle.classList.toggle('open');
  toggle.textContent = body.classList.contains('open') ? '▲' : '▼';
};

// 思维链块折叠切换（供模块内联 onclick 使用）
window.App.thinkingToggle = (headerEl) => {
  const body = headerEl.nextElementSibling;
  const toggle = headerEl.querySelector('.thinking-toggle');
  body.classList.toggle('open');
  toggle.classList.toggle('open');
  toggle.textContent = body.classList.contains('open') ? '▲' : '▼';
};

// LLM Call 条目折叠切换（供模块内联 onclick 使用）
window.App.toggleLlmCall = (idx) => {
  const entry = document.getElementById('llm-entry-' + idx);
  if (entry) entry.classList.toggle('open');
};

// 弹窗遮罩点击关闭
document.getElementById('modalOverlay').addEventListener('click', (e) => {
  if (e.target === e.target.closest('.modal-overlay')) modal.close();
});

// 初始化：加载基础信息 + 启动路由
state.loadAgentInfo();
const router = new Router(routes);
router.resolve();
setInterval(() => state.loadAgentInfo(), 30000);
