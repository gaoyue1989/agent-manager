/* ===== Chat 模块：官方对齐渲染 + 单次流 SSE / A2A 双模式 ===== */

import { McpAppHost, buildToolResult } from '../js/mcp-app-host.js';

let ctx = null;
let messagesEl = null;       // msg 容器（居中列）
let inputEl = null;
let sendBtn = null;
let sidebarListEl = null;
let connBadgeEl = null;

let isStreaming = false;
let activeAbort = null;      // 单次流/A2A 的 abort 控制器

let refreshTimer = null;
let usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, call_count: 0 };

// 当前回复构建器（按 replyId 区分多 run）
let currentReply = null;
const replyMap = {};          // replyId -> builder
let pendingToolCalls = {};    // tcId -> {name, argsRaw, args, resultRaw, state}
let thinkingTimer = null;

// HITL 待确认状态：permission_ask 事件 → 渲染确认卡片，等待批量决策
let pendingConfirm = null;    // {replyId, calls: [{tool_call_id, name, input}], cardEl}
// AG-UI HITL：RUN_FINISHED.outcome.interrupts → 挂起待 resume（interruptId 维度）
let pendingAguiInterrupts = null;  // {replyId, interrupts: [Interrupt], cardEl}

// MCP Apps 卡片宿主单例：tcId -> McpAppHost（AGENT_END 时 teardown，iframe 保留静态渲染）
const appHosts = {};          // tcId -> McpAppHost

// 渲染器映射表（内置工具名 → 官方渲染样式）
const RENDERERS = {
  bash: 'bash', shell: 'bash', exec_command: 'bash',
  read_file: 'read', read: 'read',
  write_file: 'write', write: 'write',
  edit_file: 'edit', edit: 'edit',
  glob_files: 'glob', glob: 'glob',
  grep_files: 'grep', grep: 'grep'
};

function render() {
  return `
  <div class="chat-module">
    <div class="chat-sidebar">
      <div class="chat-sidebar-header">
        <span class="cs-title">Sessions</span>
        <button id="btnNewThread" class="btn small" title="New thread">＋</button>
      </div>
      <div class="chat-sidebar-list" id="threadList">
        <div class="empty">Loading...</div>
      </div>
    </div>
    <div class="chat-main">
      <div class="module-header">
        <h2 id="chatTitle">Chat</h2>
        <span class="sub" id="connBadge"></span>
        <div style="margin-left:auto"></div>
        <div class="seg">
          <button id="modeA2A">A2A</button>
          <button id="modeChannel">Channel</button>
          <button id="modeAgui" class="active">AG-UI</button>
        </div>
        <button id="btnLlmCalls" class="btn small" disabled>LLM Calls</button>
        <button id="btnSysPrompt" class="btn small">System Prompt</button>
        <button id="btnCard" class="btn small">Card</button>
      </div>
      <div class="chat-messages" id="chatMessages">
        <div class="chat-messages-inner" id="chatInner"></div>
        <button id="scrollDown" class="scroll-down-btn" title="回到底部">↓</button>
      </div>
      <div class="chat-input">
        <div class="chat-input-pill">
          <textarea id="chatInput" rows="1" placeholder="Type your message... (Enter to send, Shift+Enter for newline)"></textarea>
          <button id="sendBtn" class="btn primary">Send</button>
        </div>
      </div>
    </div>
  </div>`;
}

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = render();
    messagesEl = document.getElementById('chatInner');
    inputEl = document.getElementById('chatInput');
    sendBtn = document.getElementById('sendBtn');
    sidebarListEl = document.getElementById('threadList');
    connBadgeEl = document.getElementById('connBadge');
    updateConnBadge();

    bindEvents();
    messagesEl.innerHTML = '<div class="chat-greeting">How can I help you today?</div>';
    loadThreads();
    refreshTimer = setInterval(loadThreads, 30000);
    subscribeScroll();
  },

  unmount() {
    if (refreshTimer) clearInterval(refreshTimer);
    if (thinkingTimer) clearInterval(thinkingTimer);
    if (activeAbort) activeAbort.abort();
    teardownAllAppHosts();
    pendingConfirm = null;
    pendingAguiInterrupts = null;
    isStreaming = false;
  }
};

function bindEvents() {
  sendBtn.addEventListener('click', () => sendMessage());
  inputEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
    autoGrow();
  });
  inputEl.addEventListener('input', autoGrow);
  document.getElementById('btnNewThread').addEventListener('click', newThread);
  document.getElementById('btnLlmCalls').addEventListener('click', showLlmCalls);
  document.getElementById('btnSysPrompt').addEventListener('click', showSystemPrompt);
  document.getElementById('btnCard').addEventListener('click', showAgentCard);

  document.getElementById('modeA2A').addEventListener('click', () => setStreamMode('a2a'));
  document.getElementById('modeChannel').addEventListener('click', () => setStreamMode('channel'));
  document.getElementById('modeAgui').addEventListener('click', () => setStreamMode('agui'));
}

function autoGrow() {
  inputEl.style.height = 'auto';
  inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
}

function setStreamMode(mode) {
  ctx.state.setState('ui.streamMode', mode);
  document.getElementById('modeA2A').classList.toggle('active', mode === 'a2a');
  document.getElementById('modeChannel').classList.toggle('active', mode === 'channel');
  document.getElementById('modeAgui').classList.toggle('active', mode === 'agui');
  // 切模式清空视图（不同模式的会话 key 语义不同，避免混淆）
  newThread();
  loadThreads();
}

function updateConnBadge() {
  if (!connBadgeEl) return;
  const mode = ctx.state.getState('ui.streamMode');
  const sid = currentSessionId();
  const bits = [mode === 'a2a' ? 'A2A' : mode === 'agui' ? 'AG-UI' : 'Channel'];
  bits.push('单次流');
  if (sid) bits.push(sid.split(':').pop());
  connBadgeEl.textContent = bits.join(' · ');
}

function currentSessionId() {
  return ctx.state.getState('threads.current');
}

function setConnecting(elId, on) {
  const el = document.getElementById(elId);
  if (el) el.classList.toggle('shimmer', on);
}

// ---------- 消息滚动 ----------

function subscribeScroll() {
  const scroller = document.getElementById('chatMessages');
  const btn = document.getElementById('scrollDown');
  if (!scroller || !btn) return;
  scroller.addEventListener('scroll', () => {
    const dist = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
    btn.classList.toggle('visible', dist > 120);
  });
  btn.addEventListener('click', () => scrollToBottom(true));
}

function scrollToBottom(force) {
  const scroller = document.getElementById('chatMessages');
  if (!scroller) return;
  if (force) {
    ctx.utils.scrollBottom(scroller);
    return;
  }
  const dist = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
  if (dist < 200) ctx.utils.scrollBottom(scroller);
}

// ---------- Threads ----------

async function loadThreads(force) {
  try {
    const mode = ctx.state.getState('ui.streamMode');
    let threads;
    if (mode === 'agui') {
      // AG-UI 契约（R7 spike 定稿）：{threads:[{id,updatedAt,metadata:{userId,archived}}]}
      const data = await ctx.api.aguiThreads();
      threads = (data.threads || []).map((t) => ({
        session_id: t.id,
        thread_id: t.id,
        updated_at: String(t.updatedAt || '').replace('T', ' ').slice(0, 19),
        archived: !!(t.metadata && t.metadata.archived)
      }));
    } else {
      threads = await ctx.api.getThreads();
    }
    ctx.state.setState('threads.list', threads);
    const sorted = (threads || []).slice().sort((a, b) =>
      String(b.updated_at || '').localeCompare(String(a.updated_at || '')));
    if (!sidebarListEl) return;
    if (sorted.length === 0) {
      sidebarListEl.innerHTML = '<div class="empty">No sessions yet</div>';
    } else {
      sidebarListEl.innerHTML = sorted.map((t) => {
        const cur = currentSessionId();
        const tid = t.session_id;
        const title = t.thread_id || tid;
        return '<div class="thread-item' + (tid === cur ? ' active' : '') + '" data-sid="' +
          ctx.utils.esc(tid) + '"><span class="tid">' + ctx.utils.esc(title) +
          '</span><span class="meta">' + ctx.utils.esc((t.updated_at || '').substring(5, 16).replace('T', ' ')) +
          '</span></div>';
      }).join('');
    }
    sidebarListEl.querySelectorAll('.thread-item').forEach((el) => {
      el.addEventListener('click', () => selectThread(el.dataset.sid));
    });
  } catch (e) {
    if (force) ctx.utils.toast('Failed to load threads: ' + e.message, 'error');
  }
}

function selectThread(sessionId) {
  if (!sessionId) return;
  ctx.state.setState('threads.current', sessionId);
  dismissConfirmCard();
  document.getElementById('btnLlmCalls').disabled = false;
  loadThreadHistory(sessionId);
  updateConnBadge();
}

function newThread() {
  ctx.state.setState('threads.current', null);
  teardownAllAppHosts();
  pendingToolCalls = {};
  pendingConfirm = null;
  pendingAguiInterrupts = null;
  currentReply = null;
  messagesEl.innerHTML = '<div class="msg system">New thread started</div>';
  document.getElementById('btnLlmCalls').disabled = true;
  updateConnBadge();
}

// ---------- 历史加载 ----------

async function loadThreadHistory(sessionId) {
  messagesEl.innerHTML = '<div class="msg system">Loading history...</div>';
  teardownAllAppHosts();
  pendingToolCalls = {};
  const mode = ctx.state.getState('ui.streamMode');
  if (mode === 'agui') {
    try {
      const data = await ctx.api.aguiMessages(sessionId);
      const msgs = data.messages || [];
      messagesEl.innerHTML = '';
      if (msgs.length === 0) {
        messagesEl.innerHTML = '<div class="msg system">No messages recovered for this thread</div>';
      }
      for (const m of msgs) {
        if (m.role === 'user') addMessage('user', m.content || '');
        else if (m.role === 'assistant' && m.content) addAssistantHistory(m.content, []);
      }
      // R11：挂起 interrupt → 自渲染确认卡片（resume[] 手工闭环，与 assistant 页同源方案）
      if (Array.isArray(data.pendingInterrupts) && data.pendingInterrupts.length > 0) {
        const rr = ensureReply('history-' + Date.now());
        renderAguiConfirmCard(rr, data.pendingInterrupts);
      }
    } catch (e) {
      messagesEl.innerHTML = '<div class="msg system">Failed to load history</div>';
    }
    return;
  }
  try {
    const data = await ctx.api.getThreadHistory(sessionId);
    const msgs = data.messages || [];
    messagesEl.innerHTML = '';
    if (msgs.length === 0) {
      messagesEl.innerHTML = '<div class="msg system">No messages recovered for this thread</div>';
      return;
    }
    for (const m of msgs) {
      if (m.role === 'user') addMessage('user', m.content || '');
      else if (m.role === 'assistant' || m.role === 'agent') {
        addAssistantHistory(m.content || '', m.tool_calls || []);
      }
    }
  } catch (e) {
    messagesEl.innerHTML = '<div class="msg system">Failed to load history</div>';
  }
}

// ---------- 消息渲染（官方对齐） ----------

function addMessage(role, content) {
  const msg = document.createElement('div');
  msg.className = 'msg ' + role;
  const bubble = document.createElement('div');
  bubble.className = 'msg-bubble';
  writeMarkdown(bubble, renderMarkdown(content));
  msg.appendChild(bubble);
  messagesEl.appendChild(msg);
  scrollToBottom(false);
  return msg;
}

// Markdown 渲染：marked 解析 GFM（表格/任务列表/删除线/链接识别），DOMPurify 净化（禁 <script>/event handler）
// 围栏代码块 ```lang…``` 输出 <pre><code class="language-lang hljs"> 由 hljs 高亮
function renderMarkdown(text) {
  if (text == null) return '';
  const raw = window.marked.parse(String(text), { gfm: true, breaks: true });
  return window.DOMPurify.sanitize(raw, { ADD_ATTR: ['class', 'target', 'rel'] });
}

/** 流式时把 markdown HTML 写入 el，并对每个 <pre><code> 块增量跑一次 hljs（自动跳过未闭合块） */
function writeMarkdown(el, html) {
  el.innerHTML = html;
  if (window.hljs) {
    el.querySelectorAll('pre code').forEach((c) => {
      try { window.hljs.highlightElement(c); } catch (e) { /* 语言不支持时忽略 */ }
    });
  }
}

/** 历史 assistant 消息：工具调用（已完成✓）+ 文本气泡（Markdown 渲染） */
function addAssistantHistory(content, toolCalls) {
  const msg = document.createElement('div');
  msg.className = 'msg assistant';
  if ((toolCalls || []).length > 0) {
    msg.innerHTML = renderToolGroupBlock(toolCalls.map((tc) => ({
      type: 'tool_call',
      name: tc.name,
      argsText: tc.input && typeof tc.input === 'object' ? JSON.stringify(tc.input, null, 2) : String(tc.input || ''),
      resultText: null,
      state: 'success'
    })));
  }
  if (content) {
    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'msg-bubble';
    writeMarkdown(bubbleEl, renderMarkdown(content));
    msg.appendChild(bubbleEl);
  }
  messagesEl.appendChild(msg);
  scrollToBottom(false);
}

/** 工具分组块（默认折叠） */
function renderToolGroupBlock(calls) {
  const summary = summarizeToolCalls(calls);
  let rowsHtml = calls.map((c) => renderToolRow(c)).join('');
  return '<div class="tool-group">' +
    '<div class="tool-group-header" onclick="window.App.toolGroupToggle(this)">' +
    '<span class="tg-arrow">▶</span><span>' + ctx.utils.esc(summary.title) + '</span>' +
    (summary.ins || summary.del ? '<span class="diff-stats"><span class="ins">+' + summary.ins + '</span><span class="del">-' + summary.del + '</span></span>' : '') +
    '</div>' +
    '<div class="tool-group-body">' + rowsHtml + '</div>' +
    '</div>';
}

function summarizeToolCalls(calls) {
  const cats = { bash: 0, read: 0, edit: 0, search: 0, todo: 0, mcp: 0 };
  let ins = 0, del = 0;
  for (const c of calls) {
    const name = c.name || '';
    if (/^(bash|sh|shell)/.test(name)) cats.bash++;
    else if (/^(read|read_file)/.test(name)) cats.read++;
    else if (/^(write|write_file|edit|edit_file)/.test(name)) cats.edit++;
    else if (/^(grep|glob)/.test(name)) cats.search++;
    else if (/^(task|plan)/.test(name)) cats.todo++;
    else if (/^mcp__/.test(name)) cats.mcp++;
  }
  const parts = [];
  if (cats.bash) parts.push(cats.bash + ' Bash');
  if (cats.read) parts.push(cats.read + ' Read');
  if (cats.edit) parts.push(cats.edit + ' Edit');
  if (cats.search) parts.push(cats.search + ' Search');
  if (cats.todo) parts.push(cats.todo + ' Todo');
  if (cats.mcp) parts.push(cats.mcp + ' MCP');
  const title = parts.length > 0 ? parts.join(' · ') : 'Call ' + calls.length + ' tools';
  return { title, ins, del };
}

/** 工具行（专用渲染器 + 状态图标 + chevron） */
function renderToolRow(call) {
  const r = RENDERERS[String(call.name || '').split('__').pop()] || 'default';
  let headerLabel = call.name || 'tool';
  let headerArg = '';
  if (r === 'read' || r === 'write' || r === 'edit') {
    headerArg = extractFilePath(call.argsText) || '';
  } else if (r === 'bash') {
    headerLabel = 'Bash';
    headerArg = extractCommand(call.argsText) || '';
  } else if (r === 'glob' || r === 'grep') {
    headerLabel = r === 'glob' ? 'Glob' : 'Grep';
    headerArg = extractPattern(call.argsText) || '';
  }
  const stateIcon = ctx.utils.toolStateIcon(call.state);
  const body = renderToolBody(r, call);
  return '<div class="tool-call-row" onclick="window.App.toolRowToggle(this)">' +
    '<span class="tc-name">' + ctx.utils.esc(headerLabel) + '</span>' +
    (headerArg ? '<span class="tc-arg">' + ctx.utils.esc(headerArg) + '</span>' : '') +
    '<span class="tc-state">' + stateIcon + '</span>' +
    (body ? '<span class="tc-toggle">▶</span>' : '') +
    '</div>' +
    (body ? '<div class="tool-call-body"><div class="tc-inner">' + body + '</div></div>' : '');
}

function extractFilePath(argsText) {
  try {
    const o = JSON.parse(argsText || '{}');
    return o.file_path || o.path || o.file || '';
  } catch { return ''; }
}
function extractCommand(argsText) {
  try {
    const o = JSON.parse(argsText || '{}');
    return o.cmd || o.command || o.script || '';
  } catch { return argsText || ''; }
}
function extractPattern(argsText) {
  try {
    const o = JSON.parse(argsText || '{}');
    return o.pattern || o.patterns || o.glob || '';
  } catch { return argsText || ''; }
}

function renderToolBody(kind, call) {
  switch (kind) {
    case 'bash':
      if (call.resultText) return '<div class="tc-label">Output</div><div class="tc-bash-output">' + ctx.utils.esc(truncate(call.resultText, 4000)) + '</div>';
      return call.resultText === null ? '<span class="tc-running">Running…</span>' : '';
    case 'read': {
      let html = '';
      if (call.argsText) html += '<div class="tc-label">Arguments</div><pre>' + ctx.utils.esc(call.argsText) + '</pre>';
      if (call.resultText) html += '<div class="tc-label">Content</div><pre>' + ctx.utils.esc(truncate(call.resultText, 4000)) + '</pre>';
      return html;
    }
    case 'edit':
    case 'write':
      if (call.argsText) {
        let args = call.argsText;
        const file = extractFilePath(args);
        const content = (() => { try { return JSON.parse(args || '{}').content || ''; } catch { return ''; } })();
        if (file || content) {
          return '<div class="tc-label">' + (kind === 'edit' ? 'Edit' : 'Write') + '</div>' +
            (file ? '<pre>' + ctx.utils.esc(file) + '</pre>' : '') +
            (content ? '<pre class="diff-view"><span class="d-add">+' + ctx.utils.esc(content.split('\n')[0] || '') + '</span></pre>' : '') +
            (call.resultText ? '<div class="tc-label">Result</div><pre>' + ctx.utils.esc(truncate(call.resultText, 2000)) + '</pre>' : '');
        }
      }
      return defaultToolBody(call);
    case 'glob':
    case 'grep':
      return defaultToolBody(call);
    default:
      return defaultToolBody(call);
  }
}

function defaultToolBody(call) {
  let html = '';
  if (call.argsText) html += '<div class="tc-label">Arguments</div><pre>' + ctx.utils.esc(call.argsText) + '</pre>';
  if (call.resultText) html += '<div class="tc-label">Result</div><pre>' + ctx.utils.esc(truncate(call.resultText, 4000)) + '</pre>';
  if (!html && call.resultText === null) html = '<span class="tc-running">Running…</span>';
  return html;
}

function truncate(s, n) {
  return s.length > n ? s.substring(0, n) + '…' : s;
}

// ---------- 流式回复构建（当前回复） ----------

function ensureReply(replyId) {
  if (currentReply && currentReply.replyId === replyId) return currentReply;
  if (replyMap[replyId]) { currentReply = replyMap[replyId]; return currentReply; }
  // 清理旧回复的 footer 计时
  const div = document.createElement('div');
  div.className = 'msg assistant';
  div.innerHTML = '<div class="msg-bubble-content"></div><div class="msg-footer"><span class="badge" data-footer></span></div>';
  messagesEl.appendChild(div);
  const r = {
    replyId,
    text: '',
    thinking: '',
    thinkingActive: false,
    toolCalls: {},     // tcId -> call
    toolOrder: [],
    elapsedStart: Date.now(),
    usage: { input: 0, output: 0 },
    modelCalls: 0,
    activeThinkingEl: null,
    footerBadge: div.querySelector('[data-footer]'),
    contentEl: div.querySelector('.msg-bubble-content')
  };
  replyMap[replyId] = r;
  currentReply = r;
  startFooterTimer(r);
  return r;
}

function startFooterTimer(r) {
  if (thinkingTimer) clearInterval(thinkingTimer);
  thinkingTimer = setInterval(() => {
    // 仅 tick 当前活跃回复
    if (currentReply && currentReply.replyId === r.replyId) updateReplyFooter(r, true);
  }, 1000);
}

function updateReplyFooter(r, running) {
  if (!r.footerBadge) return;
  const secs = (Date.now() - r.elapsedStart) / 1000;
  r.footerBadge.innerHTML =
    ctx.utils.msgStateIcon(running) +
    ' <span>' + ctx.utils.formatDuration(secs) + '</span>' +
    (r.usage.input || r.usage.output
      ? ' <span>↑' + r.usage.input + ' ↓' + r.usage.output + '</span>' : '') +
    (r.modelCalls > 0 ? ' · ' + r.modelCalls + ' calls' : '');
}

function finishReply(replyId) {
  const r = replyId ? (replyMap[replyId] || null) : currentReply;
  if (r) {
    r.thinkingActive = false;
    updateReplyFooter(r, false);
  }
  if (thinkingTimer) { clearInterval(thinkingTimer); thinkingTimer = null; }
}

// ---------- 思维链 ----------

function ensureThinking(r) {
  if (!r.activeThinkingEl) {
    const el = document.createElement('div');
    el.className = 'thinking-block';
    el.innerHTML = '<div class="thinking-header shimmer" onclick="window.App.thinkingToggle(this)">' +
      '<span class="thinking-label">thinking…</span><span class="thinking-toggle">▼</span></div>' +
      '<div class="thinking-body"><pre class="thinking-content"></pre></div>';
    r.contentEl.appendChild(el);
    r.activeThinkingEl = el;
    (function timer(div, start) {
      const lbl = div.querySelector('.thinking-label');
      const iv = setInterval(() => {
        if (div.isConnected) lbl.textContent = 'thinking for ' + ctx.utils.formatDuration((Date.now() - start) / 1000);
        else clearInterval(iv);
      }, 1000);
    })(el, Date.now());
  }
  return r.activeThinkingEl;
}

function appendThinkingDelta(r, delta) {
  r.thinking += delta;
  ensureThinking(r);
  const pre = r.activeThinkingEl.querySelector('.thinking-content');
  if (pre) pre.textContent = r.thinking;
  scrollToBottom(false);
}

function endThinking(r) {
  r.thinkingActive = false;
  if (r.activeThinkingEl) {
    const lbl = r.activeThinkingEl.querySelector('.thinking-label');
    if (lbl) lbl.textContent = 'thinking (' + r.thinking.length + ' chars)';
    r.activeThinkingEl.querySelector('.thinking-body').classList.remove('open');
    r.activeThinkingEl = null;
  }
}

// ---------- 工具流式渲染 ----------

function ensureToolTextEl(r) {
  if (!r.textEl) {
    const el = document.createElement('div');
    el.className = 'msg-bubble';
    r.contentEl.parentNode.insertBefore(el, r.contentEl.nextSibling);
    r.textEl = el;
  }
  return r.textEl;
}

function ensureToolGroupEl(r) {
  if (!r.toolsGroupEl) {
    const wrap = document.createElement('div');
    wrap.className = 'tool-group';
    wrap.innerHTML = '<div class="tool-group-header" onclick="window.App.toolGroupToggle(this)">' +
      '<span class="tg-arrow">▶</span><span class="tg-title"></span></div>' +
      '<div class="tool-group-body"></div>';
    r.contentEl.appendChild(wrap);
    r.toolsGroupEl = wrap;
    r.toolsBodyEl = wrap.querySelector('.tool-group-body');
    r.toolsTitleEl = wrap.querySelector('.tg-title');
  }
  return r.toolsGroupEl;
}

function onToolCallStart(r, tcId, name) {
  if (!pendingToolCalls[tcId]) pendingToolCalls[tcId] = { name, argsRaw: '', argsText: '', resultRaw: null, state: null };
  if (r.toolCalls[tcId]) return; // 已存在
  r.toolCalls[tcId] = pendingToolCalls[tcId];
  r.toolOrder.push(tcId);
  const rowEl = document.createElement('div');
  rowEl.className = 'tool-call-row shimmer';
  rowEl.dataset.tcid = tcId;
  rowEl.innerHTML = '<span class="tc-name">' + ctx.utils.esc(name) + '</span>' +
    '<span class="tc-state">' + ctx.utils.toolStateIcon('running') + '</span>' +
    '<span class="tc-toggle">▶</span>';
  rowEl.addEventListener('click', () => window.App.toolRowToggle(rowEl));
  ensureToolGroupEl(r);
  r.toolsBodyEl.appendChild(rowEl);
  updateToolGroupTitle(r);
  scrollToBottom(false);
}

function onToolCallDelta(r, tcId, delta) {
  const tc = pendingToolCalls[tcId];
  if (!tc) return;
  tc.argsRaw = (tc.argsRaw || '') + delta;
  try { tc.argsText = JSON.stringify(JSON.parse(tc.argsRaw), null, 2); }
  catch { tc.argsText = tc.argsRaw; }
  const rowEl = r.toolsBodyEl ? r.toolsBodyEl.querySelector('[data-tcid="' + ctx.utils.esc(tcId) + '"]') : null;
  if (rowEl) rowEl.classList.remove('shimmer');
  updateToolGroupTitle(r);
}

function onToolCallEnd(r, tcId) {
  const rowEl = r.toolsBodyEl ? r.toolsBodyEl.querySelector('[data-tcid="' + ctx.utils.esc(tcId) + '"]') : null;
  if (rowEl) rowEl.classList.remove('shimmer');
  updateToolGroupTitle(r);
}

function onToolResultStart(r, tcId) {
  const tc = pendingToolCalls[tcId] || (pendingToolCalls[tcId] = { name: '', argsRaw: '', argsText: '', resultRaw: null, state: null });
  tc.resultRaw = '';
}

function onToolResultDelta(r, tcId, delta) {
  const tc = pendingToolCalls[tcId];
  if (tc) tc.resultRaw = (tc.resultRaw || '') + delta;
}

function onToolResultEnd(r, tcId, state) {
  const tc = pendingToolCalls[tcId];
  if (tc) tc.state = state;
  rebuildToolRows(r);
  updateToolGroupTitle(r);
}

function rebuildToolRows(r) {
  if (!r.toolsBodyEl) return;
  r.toolsBodyEl.innerHTML = r.toolOrder.map((tcId) => {
    const tc = r.toolCalls[tcId] || {};
    return renderToolRow({
      name: tc.name,
      argsText: tc.argsText,
      resultText: tc.resultRaw == null ? (tc.state == null ? null : '') : tc.resultRaw,
      state: tc.state || 'running'
    });
  }).join('');
  r.toolsBodyEl.querySelectorAll('.tool-call-row').forEach((el) => {
    el.addEventListener('click', () => window.App.toolRowToggle(el));
  });
  scrollToBottom(false);
}

function updateToolGroupTitle(r) {
  if (!r.toolsTitleEl) return;
  const calls = r.toolOrder.map((id) => r.toolCalls[id] || {}).filter((c) => c.name);
  if (calls.length === 0) return;
  const summary = summarizeToolCalls(calls);
  r.toolsTitleEl.textContent = summary.title;
}

// ---------- HITL 确认卡片 ----------

/** 渲染权限确认卡片：批量展示待确认工具（名称 + 参数），Approve 全部 / Reject 全部 */
function renderConfirmCard(r, data) {
  if (pendingConfirm) dismissConfirmCard(); // 新 ASK 覆盖旧卡片（同 session 新 ASK 场景）
  const calls = data.tool_calls || [];
  if (calls.length === 0) return;

  const card = document.createElement('div');
  card.className = 'confirm-card';
  const rows = calls.map((c) => {
    let inputHtml = '';
    const input = c.input;
    if (input && typeof input === 'object') {
      inputHtml = '<pre>' + ctx.utils.esc(JSON.stringify(input, null, 2)) + '</pre>';
    } else if (input) {
      inputHtml = '<pre>' + ctx.utils.esc(String(input)) + '</pre>';
    }
    return '<div class="confirm-tool">' +
      '<div class="confirm-tool-name">' + ctx.utils.esc(c.name || 'tool') + '</div>' +
      '<div class="confirm-tool-id">' + ctx.utils.esc(c.tool_call_id || '') + '</div>' +
      (inputHtml ? '<div class="confirm-tool-input">' + inputHtml + '</div>' : '') +
      '</div>';
  }).join('');

  card.innerHTML =
    '<div class="confirm-card-header">' +
      '<span class="confirm-title">⚠ 等待确认</span>' +
      '<span class="confirm-sub">工具调用需人工批准</span>' +
    '</div>' +
    '<div class="confirm-tools">' + rows + '</div>' +
    '<div class="confirm-actions">' +
      '<button class="btn danger small" data-act="reject">Reject all</button>' +
      '<button class="btn primary small" data-act="approve">Approve all</button>' +
    '</div>';

  card.querySelector('[data-act="approve"]').addEventListener('click', () =>
    submitConfirm(calls, true));
  card.querySelector('[data-act="reject"]').addEventListener('click', () =>
    submitConfirm(calls, false));

  // 卡片插到回复气泡之后（等同工具组位置）
  const anchor = r.textEl || r.contentEl;
  anchor.parentNode.insertBefore(card, anchor.nextSibling);
  pendingConfirm = { replyId: r.replyId, calls, cardEl: card };
  scrollToBottom(true);
}

/** 移除当前确认卡片（确认已提交 / 新 ASK 覆盖） */
function dismissConfirmCard() {
  if (pendingConfirm && pendingConfirm.cardEl && pendingConfirm.cardEl.isConnected) {
    pendingConfirm.cardEl.remove();
  }
  pendingConfirm = null;
}

/** 批量提交确认决策：results = [{tool_call_id, confirmed, accept_rule:false}] */
async function submitConfirm(calls, approved) {
  if (!pendingConfirm) return;
  const results = calls.map((c) => ({
    tool_call_id: c.tool_call_id,
    confirmed: approved,
    accept_rule: false
  }));
  const sid = currentSessionId();
  if (!sid) {
    ctx.utils.toast('No active session, cannot confirm', 'error');
    return;
  }

  // 标记卡片为处理中（防重复点击）
  pendingConfirm.cardEl.classList.add('processing');
  pendingConfirm.cardEl.querySelectorAll('button').forEach((b) => (b.disabled = true));
  pendingConfirm.cardEl.querySelector('.confirm-title').textContent = '处理中…';

  // 保存当前卡片引用：confirm-stream 可能返回新 permission_ask 创建新卡片，
  // finally 只应移除本次确认的卡片，不误清新卡片
  const myCard = pendingConfirm.cardEl;

  try {
    // 单次流：原流已无后续事件（恢复是新调用），关闭原连接改走 confirm-stream 渲染
    if (activeAbort) { try { activeAbort.abort(); } catch (e) { /* ignore */ } }
    isStreaming = true;
    await consumeConfirmStream(sid, results, true);
    isStreaming = false;
    loadThreads();
  } catch (e) {
    ctx.utils.toast('Confirm failed: ' + e.message, 'error');
    if (pendingConfirm && pendingConfirm.cardEl === myCard) {
      myCard.querySelectorAll('button').forEach((b) => (b.disabled = false));
      myCard.querySelector('.confirm-title').textContent = '⚠ 等待确认';
    }
  } finally {
    // 只移除本次确认的卡片（confirm-stream 返回新 permission_ask 时已创建新卡片）
    if (pendingConfirm && pendingConfirm.cardEl === myCard) {
      dismissConfirmCard();
    }
  }
}

/** 消费确认后事件流（render=true 时事件直接渲染；false 时仅触发恢复，事件经总线回流） */
function consumeConfirmStream(sid, results, render) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (err) => {
      if (settled) return;
      settled = true;
      if (err) reject(err); else resolve();
    };

    // 确保有回复上下文：confirm-stream 事件可能没有 replyId，
    // 需要预先创建回复容器以接收 TEXT_BLOCK_DELTA 等事件
    if (render) {
      // 始终创建新回复：confirm-stream 是新的执行段，不应追加到旧回复
      const replyId = 'confirm-' + Date.now();
      currentReply = null; // 清除旧引用，确保 ensureReply 创建新回复
      ensureReply(replyId);
    }

    ctx.api.confirmStream(sid, results, {
      onEvent: (data) => {
        if (render) handleEvent(data);
        if (data.type === 'error') finish(new Error(data.error || 'confirm-stream error'));
        if (data.type === 'done') finish();
      },
      onError: (e) => finish(e)
    });
    // 兜底超时（正常流以 done 帧收尾）
    setTimeout(() => finish(), 30000);
  });
}

// ---------- MCP Apps 卡片（阶段二，见 mcp-apps-extension-plan.md §5.1） ----------

/** 渲染 MCP App 交互式卡片：占位卡片 → 异步拉资源 → 沙箱 iframe 挂载 */
function renderMcpAppCard(r, data) {
  const tcId = data.toolCallId;
  const toolName = data.toolName || 'mcp-app';
  const ui = data.ui || {};   // {resourceUri, server}
  if (!pendingToolCalls[tcId]) {
    pendingToolCalls[tcId] = { name: toolName, argsRaw: '', argsText: '', resultRaw: null, state: null };
  }

  const card = document.createElement('div');
  card.className = 'mcp-apps-container open';
  card.innerHTML =
    '<div class="mcp-apps-header">' +
      '<span class="app-label">MCP App</span>' +
      '<span class="app-name">' + ctx.utils.esc(toolName) + '</span>' +
      '<span class="app-uri">' + ctx.utils.esc(ui.resourceUri || '') + '</span>' +
      '<span class="tc-toggle">▶</span>' +
    '</div>' +
    '<div class="mcp-apps-body"><div class="mcp-apps-loading">加载中…</div></div>';
  card.querySelector('.mcp-apps-header').addEventListener('click', () => card.classList.toggle('open'));
  // 挂载到回复内容容器（textEl 会被 TEXT_BLOCK_DELTA 的 innerHTML 整体重写，
  // 若工具调用发生在文本输出之后，卡片会被误清，故固定挂 contentEl，与工具行一致）
  const anchor = r.contentEl;
  anchor.appendChild(card);
  const bodyEl = card.querySelector('.mcp-apps-body');

  const host = new McpAppHost({
    tcId,
    server: ui.server,
    resourceUri: ui.resourceUri,
    toolName,
    callbacks: {
      // 卡片 tools/call 触发 ask 工具：403 needsConfirm → 卡片外确认卡片，Approve 后重试（5.1.3）
      needsConfirm: (call) => renderAppConfirmCard(call),
      log: (level, msg) => console.log('[mcp-app:' + tcId + '] ' + level + ':', msg)
    }
  });
  appHosts[tcId] = host;
  mountAppCard(host, bodyEl);
  scrollToBottom(false);
}

/** 异步挂载：拉取资源 → 沙箱 iframe；失败显示错误 + 重试 */
async function mountAppCard(host, bodyEl) {
  try {
    await host.mount(bodyEl);
  } catch (e) {
    bodyEl.innerHTML =
      '<div class="mcp-apps-error">' + ctx.utils.esc(e.message || '资源加载失败') + '</div>' +
      '<div><button class="btn small" data-retry>重试</button></div>';
    const retry = bodyEl.querySelector('[data-retry]');
    if (retry) retry.addEventListener('click', () => {
      bodyEl.innerHTML = '<div class="mcp-apps-loading">加载中…</div>';
      mountAppCard(host, bodyEl);
    });
  }
  scrollToBottom(false);
}

/** 卡片 tools/call 确认流：Approve/Reject 单工具决策（复用 .confirm-card 样式，上传确认重试下载） */
function renderAppConfirmCard(call) {
  return new Promise((resolve) => {
    const card = document.createElement('div');
    card.className = 'confirm-card';
    card.innerHTML =
      '<div class="confirm-card-header">' +
        '<span class="confirm-title">⚠ 等待确认</span>' +
        '<span class="confirm-sub">MCP App 工具调用需人工批准</span>' +
      '</div>' +
      '<div class="confirm-tools"><div class="confirm-tool">' +
        '<div class="confirm-tool-name">' + ctx.utils.esc(call.name || 'tool') + '</div>' +
        '<div class="confirm-tool-input"><pre>' + ctx.utils.esc(JSON.stringify(call.arguments || {}, null, 2)) + '</pre></div>' +
      '</div></div>' +
      '<div class="confirm-actions">' +
        '<button class="btn danger small" data-act="reject">Reject</button>' +
        '<button class="btn primary small" data-act="approve">Approve</button>' +
      '</div>';
    const settle = (ok) => {
      try { card.remove(); } catch (e) { /* ignore */ }
      resolve(ok);
    };
    card.querySelector('[data-act="approve"]').addEventListener('click', () => settle(true));
    card.querySelector('[data-act="reject"]').addEventListener('click', () => settle(false));
    const anchor = currentReply ? (currentReply.textEl || currentReply.contentEl) : messagesEl;
    if (anchor && anchor.parentNode) {
      anchor.parentNode.insertBefore(card, anchor.nextSibling);
    } else if (anchor) {
      anchor.appendChild(card);
    }
    scrollToBottom(true);
  });
}

/** 回复结束 / 线程切换：通知各卡片 teardown（iframe 保留静态渲染），清理单例 */
function teardownAllAppHosts() {
  for (const key of Object.keys(appHosts)) {
    try { appHosts[key].teardown('reply-completed'); } catch (e) { /* ignore */ }
    delete appHosts[key];
  }
}

/** 工具参数解析：SSE 增量拼接的原始 JSON → 对象（供 tool-input 下发） */
function parseToolArgs(argsRaw) {
  if (!argsRaw) return {};
  try { return JSON.parse(argsRaw); } catch (e) { return {}; }
}

// ---------- 事件处理（统一入口） ----------
// SDK 语义：AGENT_START/END 是 agent 级 replyId，内部 block 事件（TEXT/THINKING/TOOL/MODEL）
// 是 model 调用级 replyId（不同 id）。因此以 AGENT_START/END 为消息生命周期，
// block 事件统一归入当前 agent 回复，不按 block replyId 另建气泡。

function handleEvent(data) {
  // agent 生命周期事件：切换/收尾当前回复
  if (data.type === 'AGENT_START') {
    const rr = ensureReply(data.replyId || 'reply-' + Date.now());
    setConnecting('chatTitle', true);
    isStreaming = true;
    sendBtn.disabled = true;
    sendBtn.textContent = 'Stop';
    sendBtn.classList.add('danger');
    usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, call_count: 0 };
    // MCP Apps：新回合开始 → 清理上一回合卡片（保留当前回合可交互）
    teardownAllAppHosts();
    return;
  }
  if (data.type === 'AGENT_END') {
    finishReply(currentReply ? currentReply.replyId : null);
    currentReply = null;
    isStreaming = false;
    sendBtn.disabled = false;
    sendBtn.textContent = 'Send';
    sendBtn.classList.remove('danger');
    setConnecting('chatTitle', false);
    scrollToBottom(true);
    loadThreads();
    return;
  }

  // block 事件：归入当前 agent 回复（无活跃回复时用自身 replyId 兜底占位）
  const r = currentReply || (data.replyId ? ensureReply(data.replyId) : null);
  if (!r) return;

  switch (data.type) {
    case 'TEXT_BLOCK_DELTA': {
      if (r) {
        r.text += (data.delta || '');
        endThinking(r);
        // Markdown 写入后追加光标节点：cursor span 在 sanitize 之外，不参与解析，避免被吞
        const el = ensureToolTextEl(r);
        writeMarkdown(el, renderMarkdown(r.text));
        const cur = document.createElement('span');
        cur.className = 'cursor';
        el.appendChild(cur);
      }
      scrollToBottom(false);
      break;
    }
    case 'THINKING_BLOCK_DELTA': {
      if (r) appendThinkingDelta(r, data.delta || '');
      break;
    }
    case 'THINKING_BLOCK_END': {
      if (r) endThinking(r);
      break;
    }
    case 'TOOL_CALL_START': {
      if (r) {
        // MCP Apps：工具带 ui 元数据 → 渲染交互式卡片（不走普通工具行）
        if (data.ui && data.ui.resourceUri) {
          renderMcpAppCard(r, data);
        } else {
          onToolCallStart(r, data.toolCallId, data.toolName);
        }
      }
      break;
    }
    case 'TOOL_CALL_DELTA': {
      if (r) onToolCallDelta(r, data.toolCallId, data.delta);
      break;
    }
    case 'TOOL_CALL_END': {
      if (r) onToolCallEnd(r, data.toolCallId);
      break;
    }
    case 'TOOL_RESULT_START': {
      if (r) onToolResultStart(r, data.toolCallId);
      break;
    }
    case 'TOOL_RESULT_TEXT_DELTA': {
      if (r) onToolResultDelta(r, data.toolCallId, data.delta);
      break;
    }
    case 'TOOL_RESULT_END': {
      if (r) {
        // MCP Apps：结果到达 → 通知卡片（tool-input 完整参数 → tool-result），按规范顺序下发
        const host = appHosts[data.toolCallId];
        if (host) {
          const tc = pendingToolCalls[data.toolCallId] || {};
          host.sendToolInput(parseToolArgs(tc.argsRaw));
          host.sendToolResult(buildToolResult(tc.resultRaw, data.state));
        } else {
          onToolResultEnd(r, data.toolCallId, data.state);
        }
      }
      break;
    }
    case 'MODEL_CALL_START': {
      if (r) r.modelCalls++;
      break;
    }
    case 'MODEL_CALL_END': {
      if (data.inputTokens != null || data.outputTokens != null) {
        usageAccumulator.input_tokens += data.inputTokens || 0;
        usageAccumulator.output_tokens += data.outputTokens || 0;
        usageAccumulator.total_tokens += data.totalTokens || 0;
        usageAccumulator.call_count++;
        if (r) {
          r.usage.input = usageAccumulator.input_tokens;
          r.usage.output = usageAccumulator.output_tokens;
        }
      }
      break;
    }
    case 'AGENT_END': {
      // 已在函数顶部统一处理（agent 级生命周期）
      break;
    }
    case 'permission_ask': {
      // HITL：工具调用被 ASK 拦截，渲染确认卡片（批量决策，见 hitl-permission-plan.md 8.3）
      // permission_ask 通常跟随 AGENT_START 同 replyId；异常时并入当前回复兜底
      const rr = r || ensureReply(data.replyId || 'perm-' + Date.now());
      renderConfirmCard(rr, data);
      break;
    }
    case 'user_confirm_result': {
      // P2：恢复事件标记卡片已处理（当前确认后由提交逻辑直接移除卡片）
      break;
    }
    case 'error': {
      isStreaming = false;
      sendBtn.disabled = false;
      sendBtn.textContent = 'Send';
      sendBtn.classList.remove('danger');
      if (r) {
        const err = document.createElement('div');
        err.className = 'msg system';
        err.style.color = 'var(--red)';
        err.textContent = 'Error: ' + (data.error || 'Unknown');
        messagesEl.appendChild(err);
      }
      break;
    }
    default:
      break;
  }
}


// ---------- AG-UI 模式（agui-migration-plan Phase 2.7：词表 RUN_*/TEXT_MESSAGE_*/TOOL_CALL_*/CUSTOM） ----------

/** AG-UI 主流：POST /agui/run（threadId 直接作会话 key，D5）→ 事件映射到既有渲染器 */
async function sendAguiStream(text, threadId) {
  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = 'Stop';
  sendBtn.classList.add('danger');
  const abortController = new AbortController();
  activeAbort = abortController;

  ctx.api.sendAgui({
    threadId,
    runId: 'run-' + Date.now().toString(36),
    messages: [{ id: 'm-' + Date.now().toString(36), role: 'user', content: text }],
    forwardedProps: { userId: 'debug-user' }
  }, {
    onEvent: (ev) => handleAguiEvent(ev, threadId),
    onError: (e) => {
      handleAguiError(threadId, e.message);
    },
    onEnd: () => {
      // 流关闭兜底收尾（RUN_FINISHED 已复位；此处幂等处理异常断流场景）
      if (isStreaming) {
        isStreaming = false;
        sendBtn.disabled = false;
        sendBtn.textContent = 'Send';
        sendBtn.classList.remove('danger');
        setConnecting('chatTitle', false);
        finishReply(currentReply ? currentReply.replyId : null);
        currentReply = null;
        loadThreads();
      }
    }
  });

  // 消费由 sendAgui 内部异步驱动；stop 经 activeAbort.abort() + stop 端点
  try { await new Promise((resolve) => setTimeout(resolve, 0)); } catch (e) { /* ignore */ }
}

/** resume run：挂起 interrupts 批量决策后重开执行段（服务端覆盖率校验 + CAS） */
async function sendAguiResume(threadId, interrupts, approved) {
  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = 'Stop';
  sendBtn.classList.add('danger');
  const abortController = new AbortController();
  activeAbort = abortController;

  await new Promise((resolve) => {
    let settled = false;
    const finish = () => { if (!settled) { settled = true; resolve(); } };
    ctx.api.sendAgui({
      threadId,
      runId: 'resume-' + Date.now().toString(36),
      messages: [],
      resume: interrupts.map((i) => ({
        interruptId: i.id,
        status: approved ? 'resolved' : 'cancelled',
        payload: { approved }
      }))
    }, {
      onEvent: (ev) => handleAguiEvent(ev, threadId),
      onError: (e) => { ctx.utils.toast('Resume failed: ' + e.message, 'error'); finish(); },
      onEnd: () => finish()
    });
    // 兜底：SSE 异常悬挂时超时收尾
    setTimeout(finish, 120000);
  });
  isStreaming = false;
  sendBtn.disabled = false;
  sendBtn.textContent = 'Send';
  sendBtn.classList.remove('danger');
  activeAbort = null;
  loadThreads();
}

/** AG-UI 事件处理：映射到既有渲染函数（工具行/思维链/usage/MCP Apps 卡片） */
function handleAguiEvent(ev, threadId) {
  switch (ev.type) {
    case 'RUN_STARTED': {
      const rr = ensureReply(ev.runId || 'agui-' + Date.now());
      setConnecting('chatTitle', true);
      isStreaming = true;
      sendBtn.disabled = true;
      sendBtn.textContent = 'Stop';
      sendBtn.classList.add('danger');
      usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, call_count: 0 };
      teardownAllAppHosts();
      return;
    }
    case 'RUN_FINISHED': {
      // HITL：outcome.type=interrupt → 挂起卡片（resume[] 闭环）；否则正常收尾
      if (ev.outcome && ev.outcome.type === 'interrupt' && (ev.outcome.interrupts || []).length > 0) {
        const rr = currentReply || ensureReply(ev.runId || 'agui-int');
        // 执行段结束：状态复位（恢复是新 run，需重新抢租约）
        isStreaming = false;
        sendBtn.disabled = false;
        sendBtn.textContent = 'Send';
        sendBtn.classList.remove('danger');
        setConnecting('chatTitle', false);
        finishReply(rr.replyId);
        renderAguiConfirmCard(rr, ev.outcome.interrupts);
        return;
      }
      finishReply(currentReply ? currentReply.replyId : null);
      currentReply = null;
      isStreaming = false;
      sendBtn.disabled = false;
      sendBtn.textContent = 'Send';
      sendBtn.classList.remove('danger');
      setConnecting('chatTitle', false);
      scrollToBottom(true);
      loadThreads();
      return;
    }
    case 'RUN_ERROR': {
      handleAguiError(ev.threadId || threadId, ev.message || 'Run error');
      return;
    }
  }

  // block 事件归入当前回复
  const r = currentReply || (ev.runId ? ensureReply(ev.runId) : null);
  if (!r) return;

  switch (ev.type) {
    case 'TEXT_MESSAGE_CONTENT':
      r.text += (ev.delta || '');
      endThinking(r);
      writeMarkdown(ensureToolTextEl(r), renderMarkdown(r.text));
      scrollToBottom(false);
      break;
    case 'REASONING_MESSAGE_CONTENT':
      appendThinkingDelta(r, ev.delta || '');
      break;
    case 'REASONING_MESSAGE_END':
      endThinking(r);
      break;
    case 'TOOL_CALL_START':
      onToolCallStart(r, ev.toolCallId, ev.toolCallName);
      break;
    case 'TOOL_CALL_ARGS':
      onToolCallDelta(r, ev.toolCallId, ev.delta);
      break;
    case 'TOOL_CALL_END':
      onToolCallEnd(r, ev.toolCallId);
      break;
    case 'TOOL_CALL_RESULT': {
      // 一次性全量结果：MCP Apps 卡片优先（tool-input/result 按规范下发），否则工具行收尾
      const host = appHosts[ev.toolCallId];
      if (host) {
        const tc = pendingToolCalls[ev.toolCallId] || {};
        host.sendToolInput(parseToolArgs(tc.argsRaw));
        host.sendToolResult(buildToolResult(ev.content, 'success'));
      } else {
        onToolResultStart(r, ev.toolCallId);
        onToolResultDelta(r, ev.toolCallId, ev.content || '');
        onToolResultEnd(r, ev.toolCallId, 'success');
      }
      break;
    }
    case 'CUSTOM': {
      if (ev.name === 'token_usage' && ev.value && ev.value.cumulative) {
        // token 统计：cumulative 累计口径（inputTokens/outputTokens/totalTokens）
        usageAccumulator.input_tokens = ev.value.cumulative.inputTokens || 0;
        usageAccumulator.output_tokens = ev.value.cumulative.outputTokens || 0;
        usageAccumulator.total_tokens = ev.value.cumulative.totalTokens || 0;
        usageAccumulator.call_count++;
        r.usage.input = usageAccumulator.input_tokens;
        r.usage.output = usageAccumulator.output_tokens;
      } else if (ev.name === 'oaf.mcp_ui' && ev.value) {
        // MCP Apps：卡片锚点（toolCallId + ui 元数据）
        renderMcpAppCard(r, {
          toolCallId: ev.value.toolCallId,
          toolName: (pendingToolCalls[ev.value.toolCallId] || {}).name || 'mcp-app',
          ui: { resourceUri: ev.value.resourceUri, server: ev.value.server }
        });
      } else if (ev.name === 'oaf.file_ready' && ev.value) {
        renderFileCard(r, ev.value);
      } else if (ev.name === 'oaf.tool_image' && ev.value) {
        renderFileCard(r, {
          file_name: 'tool-image',
          mime_type: ev.value.media_type || 'image/png',
          data: ev.value.data,
          url: ev.value.url
        });
      }
      break;
    }
    default:
      break;
  }
}

function handleAguiError(threadId, message) {
  isStreaming = false;
  sendBtn.disabled = false;
  sendBtn.textContent = 'Send';
  sendBtn.classList.remove('danger');
  setConnecting('chatTitle', false);
  const r = currentReply || ensureReply('agui-err-' + Date.now());
  const err = document.createElement('div');
  err.className = 'msg system';
  err.style.color = 'var(--red)';
  err.textContent = 'Error: ' + (message || 'Unknown');
  messagesEl.appendChild(err);
  scrollToBottom(false);
  finishReply(r.replyId);
}

/** 产出文件卡片（oaf.file_ready / oaf.tool_image） */
function renderFileCard(r, v) {
  const isImage = (v.mime_type || v.media_type || '').startsWith('image/');
  const src = v.data ? 'data:' + (v.media_type || 'image/png') + ';base64,' + v.data : null;
  const href = src || v.download_url || v.url;
  const card = document.createElement('div');
  card.className = 'file-ready-card';
  card.innerHTML =
    '<span class="fr-icon">' + (isImage ? '🖼️' : '📄') + '</span>' +
    '<div class="fr-body"><div class="fr-name">' + ctx.utils.esc(v.file_name || 'file') + '</div>' +
    '<div class="fr-meta">' + ctx.utils.esc(v.mime_type || v.media_type || '') +
    (v.size ? ' · ' + (v.size / 1024).toFixed(1) + ' KB' : '') + '</div></div>' +
    (href ? '<a class="fr-download" href="' + ctx.utils.esc(href) + '" download="' + ctx.utils.esc(v.file_name || 'file') + '">下载</a>' : '');
  const anchor = r.textEl || r.contentEl;
  anchor.parentNode.insertBefore(card, anchor.nextSibling);
  scrollToBottom(false);
}

/** AG-UI HITL 确认卡片：interrupts（interruptId:toolCallId 维度）→ Approve/Reject all → resume[] 新 run */
function renderAguiConfirmCard(r, interrupts) {
  if (pendingAguiInterrupts) dismissAguiConfirmCard();
  if (!interrupts || interrupts.length === 0) return;

  const card = document.createElement('div');
  card.className = 'confirm-card';
  const rows = interrupts.map((i) => {
    const meta = i.metadata || {};
    let inputHtml = '';
    const input = meta.toolInput;
    if (input && typeof input === 'object') {
      inputHtml = '<pre>' + ctx.utils.esc(JSON.stringify(input, null, 2)) + '</pre>';
    } else if (input) {
      inputHtml = '<pre>' + ctx.utils.esc(String(input)) + '</pre>';
    } else if (meta.toolContent) {
      try { inputHtml = '<pre>' + ctx.utils.esc(JSON.stringify(JSON.parse(meta.toolContent), null, 2)) + '</pre>'; }
      catch (e) { inputHtml = '<pre>' + ctx.utils.esc(String(meta.toolContent)) + '</pre>'; }
    }
    return '<div class="confirm-tool">' +
      '<div class="confirm-tool-name">' + ctx.utils.esc(meta.toolName || 'tool') + '</div>' +
      '<div class="confirm-tool-id">' + ctx.utils.esc(i.toolCallId || i.id) + '</div>' +
      (inputHtml ? '<div class="confirm-tool-input">' + inputHtml + '</div>' : '') +
      '</div>';
  }).join('');

  card.innerHTML =
    '<div class="confirm-card-header">' +
      '<span class="confirm-title">⚠ 等待确认</span>' +
      '<span class="confirm-sub">工具调用需人工批准（AG-UI interrupts）</span>' +
    '</div>' +
    '<div class="confirm-tools">' + rows + '</div>' +
    '<div class="confirm-actions">' +
      '<button class="btn danger small" data-act="reject">Reject all</button>' +
      '<button class="btn primary small" data-act="approve">Approve all</button>' +
    '</div>';

  const threadId = currentSessionId();
  card.querySelector('[data-act="approve"]').addEventListener('click', () => decide(true));
  card.querySelector('[data-act="reject"]').addEventListener('click', () => decide(false));
  async function decide(approved) {
    if (!threadId) {
      ctx.utils.toast('No active thread, cannot confirm', 'error');
      return;
    }
    card.classList.add('processing');
    card.querySelectorAll('button').forEach((b) => (b.disabled = true));
    card.querySelector('.confirm-title').textContent = '处理中…';
    dismissAguiConfirmCard();
    try {
      await sendAguiResume(threadId, interrupts, approved);
    } catch (e) {
      ctx.utils.toast('Confirm failed: ' + e.message, 'error');
    }
  }

  const anchor = r.textEl || r.contentEl;
  anchor.parentNode.insertBefore(card, anchor.nextSibling);
  pendingAguiInterrupts = { replyId: r.replyId, interrupts, cardEl: card };
  scrollToBottom(true);
}

/** 移除 AG-UI 确认卡片 */
function dismissAguiConfirmCard() {
  if (pendingAguiInterrupts && pendingAguiInterrupts.cardEl && pendingAguiInterrupts.cardEl.isConnected) {
    pendingAguiInterrupts.cardEl.remove();
  }
  pendingAguiInterrupts = null;
}

// ---------- 发送 ----------

async function sendMessage() {
  if (isStreaming) { stop(); return; }
  const text = inputEl.value.trim();
  if (!text) return;

  let sid = currentSessionId();
  if (!sid) {
    sid = 'debug-user:' + Date.now().toString(36);
    ctx.state.setState('threads.current', sid);
    document.getElementById('btnLlmCalls').disabled = false;
    updateConnBadge();
  }

  inputEl.value = '';
  inputEl.style.height = 'auto';
  inputEl.focus();
  addMessage('user', text);

  const mode = ctx.state.getState('ui.streamMode');

  if (mode === 'channel') {
    await sendChannelSingleStream(text, sid);
  } else if (mode === 'agui') {
    await sendAguiStream(text, sid);
  } else {
    await sendA2AStream(text, sid);
  }
}

function stop() {
  if (activeAbort) {
    // AG-UI 模式：先断流再调 stop 端点中断 agent 执行段（断连 onCancel 亦会 interrupt，双保险幂等）
    const mode = ctx.state.getState('ui.streamMode');
    const sid = currentSessionId();
    activeAbort.abort();
    if (mode === 'agui' && sid) {
      postJson('/agui/run/agent/release-agent/stop/' + encodeURIComponent(sid), {})
        .catch(() => {});
    }
    return;
  }
}

/** 非 JSON 容错的 POST（stop 端点，204/JSON 均可） */
async function postJson(path, body) {
  const resp = await fetch(ctx.api.BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {})
  });
  return resp.ok ? (resp.status === 204 ? null : resp.json()) : null;
}

async function sendChannelSingleStream(text, sid) {
  // 单次流（无状态架构）：POST /threads/{sid}/chat 事件直吐，执行完即关闭
  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = 'Stop';
  sendBtn.classList.add('danger');
  const abortController = new AbortController();
  activeAbort = abortController;

  let waitingSince = null;
  let waitingEl = null;
  const showWaiting = () => {
    if (!waitingEl) {
      waitingEl = document.createElement('div');
      waitingEl.className = 'msg system';
      waitingEl.style.color = 'var(--yellow)';
      messagesEl.appendChild(waitingEl);
      scrollToBottom(false);
    }
    const secs = Math.round((Date.now() - waitingSince) / 1000);
    waitingEl.textContent = '排队等待中... (已等待 ' + secs + 's，其他任务执行完毕后将自动开始)';
  };
  const hideWaiting = () => { if (waitingEl) { waitingEl.remove(); waitingEl = null; } };

  ctx.api.sendChat(sid, text, 'debug-user', {
    onWaiting: () => {
      if (!waitingSince) waitingSince = Date.now();
      showWaiting();
    },
    onEvent: (evt) => {
      hideWaiting();
      handleEvent(evt);
    },
    onError: (e) => {
      hideWaiting();
      const r = currentReply || ensureReply('single');
      handleEvent({ type: 'error', error: e.message, replyId: r.replyId });
    },
    onEnd: () => {
      hideWaiting();
      loadThreads();
    }
  });

  try { await new Promise((resolve) => setTimeout(resolve, 0)); }
  catch (e) { /* ignore */ }
}
// A2A message/stream（标准帧，工具仅工具名，无回填）
async function sendA2AStream(text, sid) {
  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = 'Stop';
  sendBtn.classList.add('danger');
  const abortController = new AbortController();
  activeAbort = abortController;

  // 新建一条 A2A 回复（标准帧不携带 replyId，用占位 id）
  const replyId = 'a2a-' + Date.now().toString(36);
  const r = ensureReply(replyId);
  usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, call_count: 0 };

  try {
    const resp = await fetch(ctx.api.BASE + '/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        method: 'message/stream',
        params: { message: { role: 'user', parts: [{ text }], metadata: { userId: 'debug-user', sessionId: sid } } },
        id: 'stream-' + Date.now()
      }),
      signal: abortController.signal
    });
    if (!resp.ok || !resp.body) throw new Error('HTTP ' + resp.status);
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith('data:')) continue;
        const dataStr = line.slice(5).trim();
        if (!dataStr || dataStr === '[DONE]') continue;
        try {
          handleFrame(JSON.parse(dataStr), replyId);
        } catch (e) { /* ignore */ }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      handleEvent({ type: 'error', error: e.message, replyId });
    }
  } finally {
    isStreaming = false;
    sendBtn.disabled = false;
    sendBtn.textContent = 'Send';
    sendBtn.classList.remove('danger');
    activeAbort = null;
    finishReply(replyId);
    loadThreads();
  }
}

/** A2A 帧 → 事件（标准帧忠实呈现：工具仅工具名 + 状态） */
function handleFrame(frame, replyId) {
  const result = frame.result;
  if (!result) {
    if (frame.error) handleEvent({ type: 'error', error: frame.error.message || 'A2A error', replyId });
    return;
  }
  const kind = result.kind;
  if (kind === 'artifact-update') {
    const artifact = result.artifact || {};
    const parts = artifact.parts || [];
    for (const p of parts) {
      const blockType = (p.metadata && p.metadata._agentscope_block_type) || 'text';
      if (p.kind === 'data') {
        const toolName = (p.metadata && p.metadata._agentscope_tool_name) || '';
        const tcId = (p.metadata && p.metadata._agentscope_tool_call_id) || '';
        if (toolName && toolName !== '__fragment__' && tcId) {
          // 标准帧：仅工具名（无参数/结果）
          handleEvent({ type: 'TOOL_CALL_START', toolCallId: tcId, toolName, replyId });
          handleEvent({ type: 'TOOL_CALL_END', toolCallId: tcId, toolName, replyId });
          handleEvent({ type: 'TOOL_RESULT_END', toolCallId: tcId, toolName: toolName, state: 'success', replyId });
        }
        continue;
      }
      if (p.kind !== 'text') continue;
      const text = p.text || '';
      if (blockType === 'thinking') {
        handleEvent({ type: 'THINKING_BLOCK_DELTA', delta: text, replyId });
      } else {
        handleEvent({ type: 'TEXT_BLOCK_DELTA', delta: text, replyId });
      }
    }
  } else if (kind === 'status-update') {
    if (result.final === true || result.state === 'completed') {
      handleEvent({ type: 'AGENT_END', replyId });
    }
  } else if (kind === 'message') {
    const parts = result.parts || [];
    const texts = parts.filter((p) => p.kind === 'text').map((p) => p.text || '');
    handleEvent({ type: 'TEXT_BLOCK_DELTA', delta: texts.join(''), replyId });
    if (result.metadata) {
      const usage = result.metadata[Object.keys(result.metadata)[0]];
      if (usage && usage._chat_usage) {
        handleEvent({
          type: 'MODEL_CALL_END',
          inputTokens: usage._chat_usage.inputTokens,
          outputTokens: usage._chat_usage.outputTokens,
          totalTokens: usage._chat_usage.totalTokens,
          replyId
        });
      }
    }
    handleEvent({ type: 'AGENT_END', replyId });
  }
}

// ---------- 弹窗功能 ----------

async function showLlmCalls() {
  const sid = currentSessionId();
  if (!sid) return;
  try {
    const data = await ctx.api.getLlmCalls(sid);
    const calls = data.calls || [];
    let html = '<h2>LLM Calls — ' + ctx.utils.esc(sid) + '</h2>';
    if (calls.length === 0) {
      html += '<p class="empty">No LLM calls recorded for this thread yet.</p>';
    } else {
      html += '<p style="color:var(--text-dim);font-size:11px;margin-bottom:10px">' + calls.length + ' call(s)</p>';
    }
    for (let i = 0; i < calls.length; i++) {
      const c = calls[i];
      const req = c.request || {};
      const res = c.response || {};
      const msgs = req.messages || [];
      const model = req.model || 'unknown';
      const usage = res.usage || {};
      const content = res.content || '';
      const toolCalls = res.tool_calls || [];
      html += '<div class="llm-call-entry" id="llm-entry-' + i + '">' +
        '<div class="llm-call-header" onclick="window.App.toggleLlmCall(' + i + ')">' +
        '<span class="lc-index">#' + (i + 1) + '</span><span class="lc-model">' + ctx.utils.esc(model) + '</span>';
      if (usage.total_tokens) html += '<span class="lc-usage">' + usage.total_tokens + ' tokens</span>';
      if (c.response && c.response.duration_ms != null) {
        html += '<span class="lc-usage">⏱ ' + ctx.utils.formatDuration(c.response.duration_ms / 1000) + '</span>';
      }
      html += '<span class="lc-toggle">▼</span></div><div class="llm-call-body">';
      html += '<div class="lc-section"><div class="lc-label">Request (' + msgs.length + ' messages)</div>';
      for (const m of msgs) {
        const role = m.role || 'unknown';
        const ctext = m.content || '';
        const display = ctext.length > 500 ? ctext.substring(0, 500) + '...' : ctext;
        html += '<div class="lc-msg ' + ctx.utils.esc(role) + '"><span class="lc-role">' + ctx.utils.esc(role.toUpperCase()) + '</span>' +
          '<span class="lc-content">' + ctx.utils.esc(display) + '</span></div>';
      }
      html += '</div>';
      if (content || toolCalls.length > 0) {
        html += '<div class="lc-section"><div class="lc-label">Response</div>';
        if (content) html += '<div class="lc-pre">' + ctx.utils.esc(content) + '</div>';
        for (const tc of toolCalls) {
          html += '<div class="lc-pre">Tool: ' + ctx.utils.esc(tc.name || '') + '\nArgs: ' +
            ctx.utils.esc(JSON.stringify(tc.args || {}, null, 2)) + '</div>';
        }
        html += '</div>';
      }
      html += '</div></div>';
    }
    ctx.modal.open(html);
  } catch (e) {
    ctx.utils.toast('Failed to load LLM calls: ' + e.message, 'error');
  }
}

async function showSystemPrompt() {
  try {
    const data = await ctx.api.getSystemPrompt();
    const base = data.base_prompt || '';
    const full = data.system_prompt || '';
    const extra = full.substring(base.length);
    let html = '<h2>System Prompt</h2>';
    if (extra) {
      html += '<div style="margin-bottom:10px"><div class="lc-label" style="margin-bottom:4px">Base (from AGENTS.md)</div>' +
        '<pre style="max-height:200px;overflow-y:auto">' + ctx.utils.esc(base) + '</pre></div>';
      html += '<div><div class="lc-label" style="margin-bottom:4px">Auto-generated (Skills & MCP Context)</div>' +
        '<pre style="max-height:200px;overflow-y:auto">' + ctx.utils.esc(extra) + '</pre></div>';
    } else {
      html += '<pre style="max-height:60vh;overflow-y:auto">' + ctx.utils.esc(full) + '</pre>';
    }
    ctx.modal.open(html);
  } catch (e) {
    ctx.utils.toast('Failed to load system prompt: ' + e.message, 'error');
  }
}

async function showAgentCard() {
  try {
    const card = await ctx.api.getAgentCard();
    ctx.utils.showJsonModal(card.name || 'Agent Card', card);
  } catch (e) {
    ctx.utils.toast('Failed: ' + e.message, 'error');
  }
}