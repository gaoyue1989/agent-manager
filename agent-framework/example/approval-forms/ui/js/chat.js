/* 审批 Demo 对话模块：POST /chat 单次流（SSE 直吐），支持工具调用展示、MCP App 表单卡片、HITL 人工确认、历史回显 */
import { api } from './api.js';
import { McpAppHost, buildToolResult } from './mcp-app-host.js';

let currentSessionId = null;
let isStreaming = false;

let currentReply = null;         // 当前 assistant 回复构建器
const replyMap = {};             // replyId -> builder
let pendingToolCalls = {};       // tcId -> {name, argsRaw, argsText, resultRaw, state}
let pendingConfirm = null;       // {cardEl, calls}
const appHosts = {};             // tcId -> McpAppHost

const esc = (s) => String(s == null ? '' : s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const $ = (id) => document.getElementById(id);

function toast(msg, type) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = msg;
  if (type === 'error') el.style.background = '#dc2626';
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 3500);
}

function scrollBottom(force) {
  const scroller = $('chatMessages');
  if (!scroller) return;
  const dist = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
  if (force || dist < 200) scroller.scrollTop = scroller.scrollHeight;
}

// ---------- 会话管理 ----------

async function loadThreads() {
  try {
    const threads = await api.getThreads();
    const list = $('threadList');
    const sorted = (threads || []).slice().sort(
      (a, b) => String(b.updated_at || '').localeCompare(String(a.updated_at || '')));
    if (sorted.length === 0) {
      list.innerHTML = '<div class="empty">暂无会话</div>';
    } else {
      list.innerHTML = sorted.map((t) => {
        const sid = t.session_id;
        const title = t.thread_id || sid;
        return '<div class="thread-item' + (sid === currentSessionId ? ' active' : '') + '" data-sid="' +
          esc(sid) + '"><span class="tid">' + esc(title) + '</span>' +
          '<span class="meta">' + esc((t.updated_at || '').substring(5, 19).replace('T', ' ')) + '</span></div>';
      }).join('');
    }
    list.querySelectorAll('.thread-item').forEach((el) => {
      el.addEventListener('click', () => selectThread(el.dataset.sid));
    });
  } catch (e) {
    $('threadList').innerHTML = '<div class="empty">加载失败: ' + esc(e.message) + '</div>';
  }
}

function newThread() {
  const sid = 'debug-user:' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  setSession(sid);
  $('chatInner').innerHTML = '<div class="msg system"><div class="bubble">新会话已创建，请输入申请内容。</div></div>';
  teardownAllAppHosts();
  pendingToolCalls = {};
  pendingConfirm = null;
  currentReply = null;
  loadThreads();
}

function setSession(sid) {
  currentSessionId = sid;
  $('connBadge').textContent = 'Channel · ' + sid.split(':').pop();
  document.querySelectorAll('.thread-item').forEach((el) =>
    el.classList.toggle('active', el.dataset.sid === sid));
}

async function selectThread(sid) {
  setSession(sid);
  teardownAllAppHosts();
  pendingToolCalls = {};
  pendingConfirm = null;
  currentReply = null;
  $('chatInner').innerHTML = '<div class="msg system"><div class="bubble">加载历史中…</div></div>';
  await loadHistory(sid);
  loadThreads();
}

async function loadHistory(sessionId) {
  try {
    const data = await api.getThreadHistory(sessionId);
    const msgs = data.messages || [];
    const inner = $('chatInner');
    inner.innerHTML = '';
    if (msgs.length === 0) {
      inner.innerHTML = '<div class="msg system"><div class="bubble">该会话暂无消息</div></div>';
      return;
    }
    msgs.forEach((m) => {
      if (m.role === 'user' && m.content) renderUserMessage(m.content);
      else if ((m.role === 'assistant' || m.role === 'agent') && (m.content || (m.tool_calls && m.tool_calls.length))) {
        renderAssistantHistory(m.content || '', m.tool_calls || []);
      }
    });
    scrollBottom(true);
  } catch (e) {
    toast('历史加载失败: ' + e.message, 'error');
  }
}

// ---------- 静态消息渲染 ----------

function renderUserMessage(text) {
  const div = document.createElement('div');
  div.className = 'msg user';
  div.innerHTML = '<div class="role-pill">你</div><div class="bubble">' + esc(text) + '</div>';
  $('chatInner').appendChild(div);
}

function renderAssistantHistory(content, toolCalls) {
  const div = document.createElement('div');
  div.className = 'msg assistant';
  let html = '<div class="role-pill">审批助手</div>';
  if (toolCalls.length) {
    const rows = toolCalls.map((tc) => {
      const argsText = tc.input && typeof tc.input === 'object' ? JSON.stringify(tc.input, null, 2) : String(tc.input || '');
      return renderToolRowHtml({ name: tc.name, argsText, resultText: null, state: 'success' });
    }).join('');
    html += '<div class="tool-group open"><div class="tool-group-header" onclick="this.parentElement.classList.toggle(\'open\')">' +
      '<span class="tg-arrow">▶</span><span>历史工具调用</span></div><div class="tool-group-body">' + rows + '</div></div>';
  }
  if (content) html += '<div class="bubble">' + esc(content) + '</div>';
  div.innerHTML = html;
  $('chatInner').appendChild(div);
}

function renderToolRowHtml(call) {
  const stateIcon = ({ success: '✅', running: '⏳', error: '❌', denied: '🚫' })[call.state] || '•';
  const body = (call.resultText != null || call.argsText) ?
    '<div class="tool-call-body">' +
      (call.argsText ? '<div class="tc-label">参数</div><pre>' + esc(call.argsText) + '</pre>' : '') +
      (call.resultText != null ? '<div class="tc-label">结果</div><pre>' + esc(call.resultText) + '</pre>' : '') +
    '</div>' : '';
  return '<div class="tool-call-row" onclick="this.nextElementSibling.classList.toggle(\'open\');' +
    'this.classList.toggle(\'open\')">' +
    '<span class="tc-name">' + esc(call.name || 'tool') + '</span>' +
    '<span class="tc-state">' + stateIcon + '</span>' +
    (body ? '<span class="tc-toggle">▶</span>' : '') +
    '</div>' + body;
}

// ---------- 流式回复构建 ----------

function ensureReply(replyId) {
  if (currentReply && currentReply.replyId === replyId) return currentReply;
  if (replyMap[replyId]) { currentReply = replyMap[replyId]; return currentReply; }
  const div = document.createElement('div');
  div.className = 'msg assistant';
  div.innerHTML = '<div class="role-pill">审批助手</div>';
  const contentEl = document.createElement('div');
  contentEl.className = 'reply-content';
  div.appendChild(contentEl);
  $('chatInner').appendChild(div);
  const r = {
    replyId,
    text: '',
    thinking: '',
    contentEl,
    textEl: null,
    toolsGroupEl: null,
    toolsBodyEl: null,
    toolsTitleEl: null,
    toolRows: {},       // tcId -> {name,argsRaw,argsText,resultRaw,state}
    toolOrder: []
  };
  replyMap[replyId] = r;
  currentReply = r;
  return r;
}

function ensureTextEl(r) {
  if (!r.textEl) {
    const el = document.createElement('div');
    el.className = 'bubble';
    r.contentEl.appendChild(el);
    r.textEl = el;
  }
  return r.textEl;
}

function ensureToolGroup(r) {
  if (!r.toolsGroupEl) {
    const wrap = document.createElement('div');
    wrap.className = 'tool-group open';
    wrap.innerHTML = '<div class="tool-group-header" onclick="this.parentElement.classList.toggle(\'open\')">' +
      '<span class="tg-arrow">▶</span><span class="tg-title">工具调用</span></div><div class="tool-group-body"></div>';
    r.contentEl.appendChild(wrap);
    r.toolsGroupEl = wrap;
    r.toolsBodyEl = wrap.querySelector('.tool-group-body');
    r.toolsTitleEl = wrap.querySelector('.tg-title');
  }
  return r.toolsGroupEl;
}

function updateToolGroupTitle(r) {
  if (!r.toolsTitleEl) return;
  const calls = r.toolOrder.map((id) => r.toolRows[id]).filter((c) => c && c.name);
  r.toolsTitleEl.textContent = calls.length ? '调用 ' + calls.length + ' 个工具' : '工具调用';
}

function addToolRow(r, tcId, name) {
  if (r.toolRows[tcId]) return;
  const row = { tcId, name, argsRaw: '', argsText: '', resultRaw: null, state: null };
  r.toolRows[tcId] = row;
  r.toolOrder.push(tcId);
  const el = document.createElement('div');
  el.dataset.tcid = tcId;
  el.innerHTML = '<span class="tc-name">' + esc(name) + '</span>' +
    '<span class="tc-state">⏳</span><span class="tc-toggle">▶</span>';
  const body = document.createElement('div');
  body.className = 'tool-call-body';
  el.addEventListener('click', () => { el.classList.toggle('open'); body.classList.toggle('open'); });
  ensureToolGroup(r);
  r.toolsBodyEl.appendChild(el);
  r.toolsBodyEl.appendChild(body);
  updateToolGroupTitle(r);
}

function updateToolRow(r, tcId) {
  const row = r.toolRows[tcId];
  if (!row || !r.toolsBodyEl) return;
  const el = r.toolsBodyEl.querySelector('[data-tcid="' + tcId + '"]');
  const body = el && el.nextElementSibling;
  if (!el) return;
  const stateIcon = ({ success: '✅', error: '❌', denied: '🚫', cancelled: '❌' })[row.state];
  el.querySelector('.tc-state').textContent = stateIcon || '⏳';
  if (row.state === 'running' || row.state == null) el.querySelector('.tc-state').textContent = '⏳';
  if (body) {
    let html = '';
    if (row.argsText) html += '<div class="tc-label">参数</div><pre>' + esc(row.argsText) + '</pre>';
    if (row.resultRaw != null) html += '<div class="tc-label">结果</div><pre>' + esc(row.resultRaw) + '</pre>';
    body.innerHTML = html || '<span class="tc-running">执行中…</span>';
  }
}

function parseArgs(raw) {
  try { return JSON.parse(raw); } catch (e) { return {}; }
}

// ---------- MCP App 卡片 ----------

function renderMcpAppCard(r, data) {
  const tcId = data.toolCallId;
  const toolName = data.toolName || 'mcp-app';
  const ui = data.ui || {};
  if (!pendingToolCalls[tcId]) {
    pendingToolCalls[tcId] = { name: toolName, argsRaw: '', argsText: '', resultRaw: null, state: null };
  }
  const card = document.createElement('div');
  card.className = 'mcp-apps-container open';
  card.innerHTML =
    '<div class="mcp-apps-header">' +
      '<span class="app-label">MCP App</span>' +
      '<span class="app-name">' + esc(toolName) + '</span>' +
      '<span class="app-uri">' + esc(ui.resourceUri || '') + '</span>' +
      '<span class="tc-toggle">▶</span>' +
    '</div>' +
    '<div class="mcp-apps-body"><div class="mcp-apps-loading">加载中…</div></div>';
  card.querySelector('.mcp-apps-header').addEventListener('click', () => card.classList.toggle('open'));
  r.contentEl.appendChild(card);
  const bodyEl = card.querySelector('.mcp-apps-body');

  const host = new McpAppHost({
    tcId,
    server: ui.server,
    resourceUri: ui.resourceUri,
    toolName,
    callbacks: {
      needsConfirm: (call) => renderAppConfirmCard(call),
      log: (level, msg) => console.log('[mcp-app:' + tcId + '] ' + level + ':', msg)
    }
  });
  appHosts[tcId] = host;
  mountAppCard(host, bodyEl);
  scrollBottom(false);
}

async function mountAppCard(host, bodyEl) {
  try {
    await host.mount(bodyEl);
  } catch (e) {
    bodyEl.innerHTML = '<div class="mcp-apps-error">' + esc(e.message || '资源加载失败') + '</div>' +
      '<div style="text-align:center;padding-bottom:10px"><button class="btn small" data-retry>重试</button></div>';
    const retry = bodyEl.querySelector('[data-retry]');
    if (retry) retry.addEventListener('click', () => {
      bodyEl.innerHTML = '<div class="mcp-apps-loading">加载中…</div>';
      mountAppCard(host, bodyEl);
    });
  }
  scrollBottom(false);
}

function teardownAllAppHosts() {
  for (const key of Object.keys(appHosts)) {
    try { appHosts[key].teardown('reply-completed'); } catch (e) { /* ignore */ }
    delete appHosts[key];
  }
}

function renderAppConfirmCard(call) {
  return new Promise((resolve) => {
    const card = document.createElement('div');
    card.className = 'confirm-card';
    card.innerHTML =
      '<div class="confirm-card-header"><span class="confirm-title">⚠ 等待确认</span>' +
      '<span class="confirm-sub">MCP App 工具调用需人工批准</span></div>' +
      '<div class="confirm-tools"><div class="confirm-tool">' +
      '<div class="confirm-tool-name">' + esc(call.name || 'tool') + '</div>' +
      '<div class="confirm-tool-input"><pre>' + esc(JSON.stringify(call.arguments || {}, null, 2)) + '</pre></div>' +
      '</div></div>' +
      '<div class="confirm-actions">' +
      '<button class="btn danger small" data-act="reject">拒绝</button>' +
      '<button class="btn primary small" data-act="approve">批准</button>' +
      '</div>';
    const settle = (ok) => { try { card.remove(); } catch (e) { /* ignore */ } resolve(ok); };
    card.querySelector('[data-act="approve"]').addEventListener('click', () => settle(true));
    card.querySelector('[data-act="reject"]').addEventListener('click', () => settle(false));
    const anchor = currentReply ? currentReply.contentEl : $('chatInner');
    if (anchor) anchor.appendChild(card);
    scrollBottom(true);
  });
}

// ---------- HITL 人工确认（提交审批） ----------

function renderConfirmCard(r, data) {
  if (pendingConfirm && pendingConfirm.cardEl.isConnected) pendingConfirm.cardEl.remove();
  const calls = data.tool_calls || [];
  if (!calls.length) return;
  const card = document.createElement('div');
  card.className = 'confirm-card';
  const rows = calls.map((c) => {
    const input = c.input;
    let inputHtml = '';
    if (input && typeof input === 'object') inputHtml = '<pre>' + esc(JSON.stringify(input, null, 2)) + '</pre>';
    else if (input) inputHtml = '<pre>' + esc(String(input)) + '</pre>';
    return '<div class="confirm-tool"><div class="confirm-tool-name">' + esc(c.name || 'tool') + '</div>' +
      '<div class="confirm-tool-id">' + esc(c.tool_call_id || '') + '</div>' +
      (inputHtml ? '<div class="confirm-tool-input">' + inputHtml + '</div>' : '') + '</div>';
  }).join('');
  card.innerHTML =
    '<div class="confirm-card-header"><span class="confirm-title">⚠ 等待人工确认</span>' +
    '<span class="confirm-sub">工具调用需人工批准后才会执行</span></div>' +
    '<div class="confirm-tools">' + rows + '</div>' +
    '<div class="confirm-actions">' +
    '<button class="btn danger small" data-act="reject">拒绝</button>' +
    '<button class="btn primary small" data-act="approve">批准</button>' +
    '</div>';
  card.querySelector('[data-act="approve"]').addEventListener('click', () => submitConfirm(calls, true));
  card.querySelector('[data-act="reject"]').addEventListener('click', () => submitConfirm(calls, false));
  (r ? r.contentEl : $('chatInner')).appendChild(card);
  pendingConfirm = { cardEl: card, calls };
  scrollBottom(true);
}

async function submitConfirm(calls, approved) {
  if (!pendingConfirm) return;
  if (!currentSessionId) { toast('无活跃会话', 'error'); return; }
  const results = calls.map((c) => ({
    tool_call_id: c.tool_call_id,
    confirmed: approved,
    accept_rule: false
  }));
  pendingConfirm.cardEl.classList.add('processing');
  pendingConfirm.cardEl.querySelectorAll('button').forEach((b) => (b.disabled = true));
  pendingConfirm.cardEl.querySelector('.confirm-title').textContent = '处理中…';
  try {
    // 单次流：confirm-stream 的事件直接经 handleEvent 渲染（工具执行、文本、LLM 重试触发的
    // 后续 permission_ask 均在此流内到达——长连接时代靠 SessionEventBus 扇出，现已移除）
    await new Promise((resolve, reject) => {
      let settled = false;
      const finish = (err) => { if (settled) return; settled = true; err ? reject(err) : resolve(); };
      api.confirmStream(currentSessionId, results, {
        onEvent: (d) => {
          if (d.type === 'error') { finish(new Error(d.error || 'confirm-stream error')); return; }
          if (d.type === 'done') { finish(); return; }
          // 渲染恢复执行的事件；permission_ask（LLM 重试）会渲染新的确认卡，由用户再次决策
          try { handleEvent(d); } catch (e) { /* 渲染失败不阻断确认流程 */ }
        },
        onError: (e) => finish(e)
      });
      setTimeout(() => finish(), 60000);
    });
    if (pendingConfirm && pendingConfirm.cardEl.isConnected) {
      pendingConfirm.cardEl.querySelector('.confirm-title').textContent =
        approved ? '✅ 已批准，继续执行…' : '已拒绝';
      pendingConfirm = null;
    }
  } catch (e) {
    toast('确认失败: ' + e.message, 'error');
    if (pendingConfirm) {
      pendingConfirm.cardEl.classList.remove('processing');
      pendingConfirm.cardEl.querySelectorAll('button').forEach((b) => (b.disabled = false));
      pendingConfirm.cardEl.querySelector('.confirm-title').textContent = '⚠ 等待人工确认';
    }
  }
}

// ---------- SSE 事件处理 ----------

function handleEvent(data) {
  if (!data || !data.type) return;

  if (data.type === 'AGENT_START') {
    ensureReply(data.replyId || 'reply-' + Date.now());
    isStreaming = true;
    updateSendBtn();
    // 新 turn 开始才清理上一轮的 MCP App 卡片（AGENT_END 不清理——
    // 卡片需保持可交互等待用户确认/提交，否则 iframe 失活导致 tools/call 无人处理）
    teardownAllAppHosts();
    return;
  }
  if (data.type === 'AGENT_END') {
    if (currentReply) { currentReply.textEl = null; }
    currentReply = null;
    isStreaming = false;
    updateSendBtn();
    scrollBottom(true);
    loadThreads();
    return;
  }

  const r = currentReply || (data.replyId ? ensureReply(data.replyId) : null);
  if (!r) return;

  switch (data.type) {
    case 'TEXT_BLOCK_DELTA': {
      r.text += (data.delta || '');
      ensureTextEl(r).textContent = r.text + '▌';
      scrollBottom(false);
      break;
    }
    case 'THINKING_BLOCK_DELTA': {
      if (!r.thinkingEl) {
        const el = document.createElement('div');
        el.className = 'thinking-block';
        el.innerHTML = '<div class="thinking-header" onclick="this.nextElementSibling.classList.toggle(\'open\')">thinking… ▼</div>' +
          '<div class="thinking-body"><pre></pre></div>';
        r.contentEl.appendChild(el);
        r.thinkingEl = el;
      }
      r.thinking += (data.delta || '');
      r.thinkingEl.querySelector('pre').textContent = r.thinking;
      scrollBottom(false);
      break;
    }
    case 'THINKING_BLOCK_END': {
      break;
    }
    case 'TOOL_CALL_START': {
      if (data.ui && data.ui.resourceUri) renderMcpAppCard(r, data);
      else {
        if (!pendingToolCalls[data.toolCallId]) pendingToolCalls[data.toolCallId] = { name: data.toolName, argsRaw: '', argsText: '', resultRaw: null, state: null };
        r.toolRows[data.toolCallId] = pendingToolCalls[data.toolCallId];
        if (!r.toolOrder.includes(data.toolCallId)) r.toolOrder.push(data.toolCallId);
        addToolRow(r, data.toolCallId, data.toolName);
      }
      scrollBottom(false);
      break;
    }
    case 'TOOL_CALL_DELTA': {
      const tc = pendingToolCalls[data.toolCallId];
      if (tc) {
        tc.argsRaw = (tc.argsRaw || '') + (data.delta || '');
        try { tc.argsText = JSON.stringify(JSON.parse(tc.argsRaw), null, 2); }
        catch (e) { tc.argsText = tc.argsRaw; }
        updateToolRow(r, data.toolCallId);
      }
      break;
    }
    case 'TOOL_CALL_END': {
      updateToolRow(r, data.toolCallId);
      break;
    }
    case 'TOOL_RESULT_START': {
      if (!pendingToolCalls[data.toolCallId]) {
        pendingToolCalls[data.toolCallId] = { name: data.toolCallName, argsRaw: '', argsText: '', resultRaw: '', state: null };
      }
      pendingToolCalls[data.toolCallId].resultRaw = '';
      break;
    }
    case 'TOOL_RESULT_TEXT_DELTA': {
      const tc = pendingToolCalls[data.toolCallId];
      if (tc) tc.resultRaw = (tc.resultRaw || '') + (data.delta || '');
      break;
    }
    case 'TOOL_RESULT_END': {
      const tc = pendingToolCalls[data.toolCallId];
      if (tc) tc.state = data.state || 'success';
      const host = appHosts[data.toolCallId];
      if (host) {
        host.sendToolInput(parseArgs((tc && tc.argsRaw) || ''));
        host.sendToolResult(buildToolResult(tc ? tc.resultRaw : null, data.state));
      } else {
        updateToolRow(r, data.toolCallId);
      }
      scrollBottom(false);
      break;
    }
    case 'permission_ask': {
      renderConfirmCard(r, data);
      break;
    }
    case 'user_confirm_result': {
      break;
    }
    case 'error': {
      isStreaming = false;
      updateSendBtn();
      const el = document.createElement('div');
      el.className = 'msg system';
      el.innerHTML = '<div class="bubble" style="color:#dc2626">错误: ' + esc(data.error || '未知') + '</div>';
      $('chatInner').appendChild(el);
      break;
    }
    default:
      break;
  }
}

function updateSendBtn() {
  const btn = $('sendBtn');
  if (isStreaming) { btn.disabled = true; btn.textContent = '…'; btn.classList.remove('primary'); }
  else { btn.disabled = false; btn.textContent = '发送'; btn.classList.add('primary'); }
}

// ---------- 发送（POST /chat 单次流） ----------

let currentChatStream = null;

async function sendMessage() {
  if (isStreaming) { toast('正在处理中，请稍候…'); return; }
  const text = $('chatInput').value.trim();
  if (!text) return;
  if (!currentSessionId) newThread();
  $('chatInput').value = '';
  $('chatInput').style.height = 'auto';
  renderUserMessage(text);
  isStreaming = true;
  updateSendBtn();
  currentChatStream = api.triggerSessionChat(currentSessionId, text, 'debug-user', {
    onEvent: handleEvent,
    onError: (e) => {
      if (!isStreaming) return;
      isStreaming = false;
      updateSendBtn();
      toast('流式响应异常: ' + e.message, 'error');
    }
  });
}

// ---------- 弹窗 ----------

async function showSystemPrompt() {
  try {
    const data = await api.getSystemPrompt();
    const full = data.system_prompt || data.base_prompt || '';
    openModal('<h2>System Prompt</h2><pre>' + esc(full) + '</pre>');
  } catch (e) { toast('加载失败: ' + e.message, 'error'); }
}

async function showTools() {
  try {
    const data = await api.getTools();
    const tools = data.tools || data.mcp_tools || [];
    const lines = tools.map((t) => '- ' + t.name + (t.uiResourceUri ? '  [UI]' : '') + (t.appOnly ? '  [app-only]' : '') + ' — ' + sanitizeDesc(t.description)).join('\n');
    openModal('<h2>工具列表</h2><pre>' + esc(lines || '（无）') + '</pre>');
  } catch (e) { toast('加载失败: ' + e.message, 'error'); }
}

function sanitizeDesc(s) {
  if (!s) return '—';
  return String(s).length > 160 ? String(s).substring(0, 160) + '…' : s;
}

function openModal(html) {
  const backdrop = document.createElement('div');
  backdrop.className = 'modal-backdrop';
  backdrop.innerHTML = '<div class="modal"><button class="btn small close">关闭</button>' + html + '</div>';
  backdrop.addEventListener('click', (e) => { if (e.target === backdrop) backdrop.remove(); });
  backdrop.querySelector('.close').addEventListener('click', () => backdrop.remove());
  document.body.appendChild(backdrop);
}

// ---------- 事件绑定 ----------

function init() {
  window.App = { api, state: { getSessionId: () => currentSessionId } };
  $('sendBtn').addEventListener('click', () => sendMessage());
  $('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
  });
  $('chatInput').addEventListener('input', () => {
    const ta = $('chatInput');
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 120) + 'px';
  });
  $('btnNewThread').addEventListener('click', newThread);
  $('btnSysPrompt').addEventListener('click', showSystemPrompt);
  $('btnTools').addEventListener('click', showTools);
  const scroller = $('chatMessages');
  scroller.addEventListener('scroll', () => {
    const dist = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
    $('scrollDown').classList.toggle('visible', dist > 120);
  });
  $('scrollDown').addEventListener('click', () => { scroller.scrollTop = scroller.scrollHeight; });
  loadThreads();
}

init();