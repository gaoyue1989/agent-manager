/* ===== Agent Monitor 独立页入口 =====
 *
 * Monitor 从调试控制台独立出来的只读运营页：复用同一套 API 客户端 / 工具函数 /
 * 渲染样式（../debug/*），但仅挂载监控模块，不暴露 Chat / Config / Database 等调试功能。
 */

import { api } from '../debug/js/api.js';
import * as state from '../debug/js/state.js';
import * as utils from '../debug/js/utils.js';
import monitorModule from './monitor.js';

// 供模块内联 onclick 使用的全局句柄（工具分组 / 工具行折叠，与调试页同签名）
window.App = { api, state, utils };
window.App.toolGroupToggle = (el) => {
  const group = el.closest('.tool-group');
  if (group) group.classList.toggle('open');
};
window.App.toolRowToggle = (el) => {
  el.classList.toggle('open');
};

state.initTheme();

const container = document.getElementById('module-content');
monitorModule.mount(container, { api, state, utils });

/** 顶栏健康状态（仅 /health + /，不拉取其余调试数据） */
async function refreshHeader() {
  const dot = document.getElementById('statusDot');
  const txt = document.getElementById('statusText');
  try {
    const health = await api.getHealth();
    const ok = health.status === 'healthy';
    if (dot) dot.className = 'status-dot ' + (ok ? 'ok' : 'error');
    if (txt) txt.textContent = health.status || 'unknown';
    const cp = document.getElementById('checkpointStatus');
    if (cp) {
      cp.textContent = health.llm_configured ? 'LLM ✓' : 'LLM ✗';
      cp.style.color = health.llm_configured ? 'var(--green)' : 'var(--yellow)';
    }
  } catch (e) {
    if (dot) dot.className = 'status-dot error';
    if (txt) txt.textContent = 'disconnected';
  }
}

async function loadAgentName() {
  try {
    const info = await api.getInfo();
    const el = document.getElementById('agentName');
    if (el) el.textContent = info.agent || '';
  } catch (e) { /* 忽略 */ }
}

refreshHeader();
loadAgentName();
setInterval(refreshHeader, 30000);
