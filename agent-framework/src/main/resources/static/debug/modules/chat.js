/* ===== Chat 模块：官方对齐渲染 + 长连接 SSE / A2A / 单次流 三模式 ===== */

let ctx = null;
let messagesEl = null;       // msg 容器（居中列）
let inputEl = null;
let sendBtn = null;
let sidebarListEl = null;
let connBadgeEl = null;

let isStreaming = false;
let subscription = null;     // 长连接订阅句柄
let activeAbort = null;      // 单次流/A2A 的 abort 控制器

let refreshTimer = null;
let usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, call_count: 0 };

// 当前回复构建器（按 replyId 区分多 run）
let currentReply = null;
const replyMap = {};          // replyId -> builder
let pendingToolCalls = {};    // tcId -> {name, argsRaw, args, resultRaw, state}
let thinkingTimer = null;

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
          <button id="modeA2A" class="active">A2A</button>
          <button id="modeChannel">Channel</button>
        </div>
        <div class="seg" title="连接模型">
          <button id="connSession" class="active">长连接</button>
          <button id="connSingle" title="单次流调试（旧 /chat/stream）">单次流</button>
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
    closeSubscription();
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
  document.getElementById('connSession').addEventListener('click', () => setConnModel('session'));
  document.getElementById('connSingle').addEventListener('click', () => setConnModel('single'));
}

function autoGrow() {
  inputEl.style.height = 'auto';
  inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
}

function setStreamMode(mode) {
  ctx.state.setState('ui.streamMode', mode);
  document.getElementById('modeA2A').classList.toggle('active', mode === 'a2a');
  document.getElementById('modeChannel').classList.toggle('active', mode === 'channel');
  // A2A/Channel 切换：重建会话订阅（Channel 链走长连接，A2A 走标准帧）
  setupSessionWatch();
  updateConnBadge();
}

function setConnModel(model) {
  ctx.state.setState('ui.connModel', model);
  document.getElementById('connSession').classList.toggle('active', model === 'session');
  document.getElementById('connSingle').classList.toggle('active', model === 'single');
  setupSessionWatch();
  updateConnBadge();
}

function updateConnBadge() {
  if (!connBadgeEl) return;
  const mode = ctx.state.getState('ui.streamMode');
  const conn = ctx.state.getState('ui.connModel');
  const sid = currentSessionId();
  const bits = [mode === 'a2a' ? 'A2A' : 'Channel'];
  if (conn === 'session') bits.push('长连接' + (subscription ? '✓' : ''));
  else bits.push('单次流');
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
    const threads = await ctx.api.getThreads();
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
  document.getElementById('btnLlmCalls').disabled = false;
  loadThreadHistory(sessionId);
  setupSessionWatch();
  updateConnBadge();
}

function newThread() {
  ctx.state.setState('threads.current', null);
  closeSubscription();
  pendingToolCalls = {};
  currentReply = null;
  messagesEl.innerHTML = '<div class="msg system">New thread started</div>';
  document.getElementById('btnLlmCalls').disabled = true;
  updateConnBadge();
}

// ---------- 历史加载 ----------

async function loadThreadHistory(sessionId) {
  messagesEl.innerHTML = '<div class="msg system">Loading history...</div>';
  pendingToolCalls = {};
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
  msg.innerHTML = '<div class="msg-bubble">' + renderMarkdown(content) + '</div>';
  messagesEl.appendChild(msg);
  scrollToBottom(false);
  return msg;
}

function renderMarkdown(text) {
  let html = ctx.utils.esc(text);
  html = html.replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) =>
    '<pre>' + (lang ? '<code class="' + ctx.utils.esc(lang) + '">' : '') + ctx.utils.esc(code) + '</pre>');
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  return html;
}

/** 历史 assistant 消息：文本 + 工具调用（工具行，状态已完成✓，无 result 则不展示结果） */
function addAssistantHistory(content, toolCalls) {
  const msg = document.createElement('div');
  msg.className = 'msg assistant';
  let bubble = '';
  if ((toolCalls || []).length > 0) {
    bubble += renderToolGroupBlock(toolCalls.map((tc) => ({
      type: 'tool_call',
      name: tc.name,
      argsText: tc.input && typeof tc.input === 'object' ? JSON.stringify(tc.input, null, 2) : String(tc.input || ''),
      resultText: null,
      state: 'success'
    })));
  }
  if (content) {
    bubble += '<div class="msg-bubble">' + renderMarkdown(content) + '</div>';
  }
  msg.innerHTML = bubble;
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
        ensureToolTextEl(r).innerHTML = renderMarkdown(r.text) + '<span class="cursor"></span>';
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
      if (r) onToolCallStart(r, data.toolCallId, data.toolName);
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
      if (r) onToolResultEnd(r, data.toolCallId, data.state);
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

// ---------- 会话长连接订阅 ----------

function setupSessionWatch() {
  closeSubscription(false);
  const sid = currentSessionId();
  if (!sid) return;
  const mode = ctx.state.getState('ui.streamMode');
  const conn = ctx.state.getState('ui.connModel');
  // 仅 Channel + 长连接 走会话事件总线；A2A / 单次流不订阅
  if (mode !== 'channel' || conn !== 'session') return;
  subscription = ctx.api.subscribeSession(sid, {
    onEvent: handleEvent,
    onError: () => {}
  });
}

function closeSubscription(update) {
  if (subscription) {
    try { subscription.close(); } catch (e) { /* ignore */ }
    subscription = null;
  }
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
  const conn = ctx.state.getState('ui.connModel');

  if (mode === 'channel') {
    if (conn === 'session') {
      // 长连接模式：确保订阅已开并就绪后再触发（避免事件丢失）
      if (!subscription) setupSessionWatch();
      if (subscription && subscription.ready) {
        await subscription.ready;
        // 等待 reader.read() 循环启动（fetch 返回 ≠ reader 就绪）
        await new Promise(r => setTimeout(r, 100));
      }
      await sendChannelTrigger(text, sid);
    } else {
      await sendChannelSingleStream(text, sid);
    }
  } else {
    await sendA2AStream(text, sid);
  }
}

function stop() {
  if (activeAbort) {
    activeAbort.abort();
    return;
  }
  // 长连接模式：事件总线模型无 abort 语义，提示
  ctx.utils.toast('长连接模式不支持中断（后端无 cancel 语义）；可切「单次流」调试', 'info');
}

async function sendChannelTrigger(text, sid) {
  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = '…';
  try {
    await ctx.api.triggerSessionChat(sid, text, 'debug-user');
  } catch (e) {
    ctx.utils.toast('Trigger failed: ' + e.message, 'error');
    isStreaming = false;
    sendBtn.disabled = false;
    sendBtn.textContent = 'Send';
  }
}

async function sendChannelSingleStream(text, sid) {
  // 单次流调试：走旧 /chat/stream
  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = 'Stop';
  sendBtn.classList.add('danger');
  const abortController = new AbortController();
  activeAbort = abortController;
  const params = new URLSearchParams({ message: text, userId: 'debug-user', sessionId: sid });
  const eventBus = ctx.api;
  const fakeSubscribe = { onEvent: handleEvent };
  try {
    const resp = await fetch(eventBus.BASE + '/chat/stream?' + params.toString(), { signal: abortController.signal });
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
        if (!dataStr) continue;
        try {
          fakeSubscribe.onEvent(JSON.parse(dataStr));
        } catch (e) { /* ignore */ }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      const r = currentReply || ensureReply('single');
      handleEvent({ type: 'error', error: e.message, replyId: r.replyId });
    }
  } finally {
    isStreaming = false;
    sendBtn.disabled = false;
    sendBtn.textContent = 'Send';
    sendBtn.classList.remove('danger');
    activeAbort = null;
    loadThreads();
  }
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