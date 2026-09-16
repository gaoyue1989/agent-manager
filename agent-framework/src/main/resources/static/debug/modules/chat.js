/* ===== Chat 模块：官方对齐渲染 + 单次流 SSE / A2A 双模式 ===== */

import { McpAppHost, buildToolResult } from '../js/mcp-app-host.js';

let ctx = null;
let messagesEl = null;       // msg 容器（居中列）
let inputEl = null;
let sendBtn = null;
let sidebarListEl = null;
let connBadgeEl = null;
let uploadBtn = null;
let fileInput = null;
let uploadPreview = null;
let uploadFiles = null;
let uidInput = null;

// @Skill 提及状态
let mentionDropdown = null;
let mentionSkills = [];       // 缓存的可用 Skill 列表
let mentionActiveIdx = -1;    // 当前高亮索引
let mentionTriggerPos = -1;   // @ 符号在 textarea 中的位置
let mentionVisible = false;

// 文件上传状态
let pendingFiles = [];       // 待上传的文件列表 [{file, fileId, status}]

let isStreaming = false;
let activeAbort = null;      // 单次流/A2A 的 abort 控制器
let activeChatHandle = null; // sendChat 返回的 handle（含 close/lastEventId）
let activeSubHandle = null;  // subscribe 返回的 handle（重连时使用）

// ★ durable-sse-plan：SSE 断连自动重连
let lastEventId = 0;         // 当前 session 最新收到的 seq（用于 afterSeq 游标）
let currentReplyId = null;   // 当前 turn 的 replyId（subscribe 过滤用）
let reconnectAttempts = 0;   // 重连尝试次数（指数退避）
const MAX_RECONNECT_ATTEMPTS = 10;
const RECONNECT_BASE_DELAY = 1000; // 1s

let refreshTimer = null;
let usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, call_count: 0 };

// 当前回复构建器（按 replyId 区分多 run）
let currentReply = null;
const replyMap = {};          // replyId -> builder
let pendingToolCalls = {};    // tcId -> {name, argsRaw, args, resultRaw, state}
let thinkingTimer = null;

// HITL 待确认状态：permission_ask 事件 → 渲染确认卡片，等待批量决策
let pendingConfirm = null;    // {replyId, calls: [{tool_call_id, name, input}], cardEl}

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
        <label class="uid-label" title="用户标识，同 userId 共享 workspace 和 memory">User&nbsp;<input id="uidInput" class="input uid-input" type="text" spellcheck="false"></label>
        <div class="seg">
          <button id="modeA2A" class="active">A2A</button>
          <button id="modeChannel">Channel</button>
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
        <div class="chat-input-pill" style="position:relative">
          <button id="uploadBtn" class="btn upload-btn" title="上传文件">📎</button>
          <input type="file" id="fileInput" multiple accept="image/*,text/plain,text/markdown,text/csv,application/pdf,.docx,.xlsx,.pptx,.doc,.xls,.ppt" style="display: none">
          <textarea id="chatInput" rows="1" placeholder="Type your message... (@ 提及 Skill，Enter 发送，Shift+Enter 换行)"></textarea>
          <button id="sendBtn" class="btn primary">Send</button>
          <div id="skillMentionDropdown" class="skill-mention-dropdown"></div>
        </div>
        <div id="uploadPreview" class="upload-preview" style="display: none;">
          <div class="upload-files" id="uploadFiles"></div>
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
    uploadBtn = document.getElementById('uploadBtn');
    fileInput = document.getElementById('fileInput');
    uploadPreview = document.getElementById('uploadPreview');
    uploadFiles = document.getElementById('uploadFiles');
    uidInput = document.getElementById('uidInput');
    uidInput.value = ctx.state.getState('ui.userId') || 'debug-user';
    updateConnBadge();

    // @Skill 提及
    mentionDropdown = document.getElementById('skillMentionDropdown');
    loadAvailableSkills();

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
    pendingFiles = [];
    isStreaming = false;
  }
};

function bindEvents() {
  sendBtn.addEventListener('click', () => sendMessage());
  inputEl.addEventListener('keydown', (e) => {
    // @Skill 提及：键盘导航
    if (mentionVisible) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        mentionActiveIdx = Math.min(mentionActiveIdx + 1, mentionSkills.length - 1);
        renderMentionDropdown();
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        mentionActiveIdx = Math.max(mentionActiveIdx - 1, 0);
        renderMentionDropdown();
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        if (mentionActiveIdx >= 0 && mentionActiveIdx < mentionSkills.length) {
          selectMentionSkill(mentionSkills[mentionActiveIdx]);
        }
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        hideMentionDropdown();
        return;
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
    autoGrow();
  });
  inputEl.addEventListener('input', () => {
    autoGrow();
    handleMentionInput();
  });
  // 点击外部关闭下拉
  document.addEventListener('click', (e) => {
    if (mentionVisible && !inputEl.contains(e.target) && !mentionDropdown.contains(e.target)) {
      hideMentionDropdown();
    }
  });
  document.getElementById('btnNewThread').addEventListener('click', newThread);
  document.getElementById('btnLlmCalls').addEventListener('click', showLlmCalls);
  document.getElementById('btnSysPrompt').addEventListener('click', showSystemPrompt);
  document.getElementById('btnCard').addEventListener('click', showAgentCard);

  document.getElementById('modeA2A').addEventListener('click', () => setStreamMode('a2a'));
  document.getElementById('modeChannel').addEventListener('click', () => setStreamMode('channel'));

  // 文件上传事件
  uploadBtn.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', handleFileSelect);

  // userId 输入事件：实时保存到 state + localStorage，刷新 session 列表
  uidInput.addEventListener('input', () => {
    const v = uidInput.value.trim() || 'debug-user';
    ctx.state.setState('ui.userId', v);
    updateConnBadge();
    loadThreads();
  });
  uidInput.addEventListener('blur', () => {
    // 失焦时确保非空
    if (!uidInput.value.trim()) uidInput.value = 'debug-user';
  });
}

function autoGrow() {
  inputEl.style.height = 'auto';
  inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
}

// ---------- @Skill 提及 ----------

async function loadAvailableSkills() {
  try {
    mentionSkills = (await ctx.api.getAvailableSkills()) || [];
  } catch (e) {
    mentionSkills = [];
  }
}

function handleMentionInput() {
  const val = inputEl.value;
  const cursorPos = inputEl.selectionStart;

  // 查找光标前最近的 @ 符号（同一行内）
  const textBeforeCursor = val.substring(0, cursorPos);
  const lastAtIdx = textBeforeCursor.lastIndexOf('@');
  if (lastAtIdx < 0) { hideMentionDropdown(); return; }

  // @ 前面必须是行首或空白（避免匹配 email 等）
  if (lastAtIdx > 0 && !/[\s\u00A0]/.test(val[lastAtIdx - 1])) {
    hideMentionDropdown();
    return;
  }

  // 提取 @ 后的查询文本
  const afterAt = textBeforeCursor.substring(lastAtIdx + 1);
  // 查询到空格或换行为止
  const spaceMatch = afterAt.match(/[\s\u00A0]/);
  const query = spaceMatch ? afterAt.substring(0, spaceMatch.index) : afterAt;

  // 如果已经输入了空格，则关闭下拉（用户已完成输入）
  if (spaceMatch && spaceMatch.index === 0) { hideMentionDropdown(); return; }

  mentionTriggerPos = lastAtIdx;

  // 过滤匹配的 Skill
  const lowerQuery = query.toLowerCase();
  const filtered = mentionSkills.filter(s => {
    const name = (s.name || '').toLowerCase();
    const desc = (s.description || '').toLowerCase();
    return name.includes(lowerQuery) || desc.includes(lowerQuery);
  });

  if (filtered.length === 0 && query.length > 0) {
    hideMentionDropdown();
    return;
  }

  mentionSkills = filtered.length > 0 ? filtered : mentionSkills;
  mentionActiveIdx = 0;
  renderMentionDropdown(query);
}

function renderMentionDropdown(query) {
  if (!mentionDropdown) return;
  if (mentionSkills.length === 0) {
    mentionDropdown.innerHTML = '<div class="skill-mention-empty">没有可用的 Skill</div>';
    mentionDropdown.classList.add('visible');
    mentionVisible = true;
    return;
  }

  mentionDropdown.innerHTML = mentionSkills.map((s, idx) => {
    const name = s.name || '?';
    const desc = s.description || '';
    const isActive = idx === mentionActiveIdx;
    return '<div class="skill-mention-item' + (isActive ? ' active' : '') + '" data-idx="' + idx + '">' +
      '<span class="sm-icon">📚</span>' +
      '<span class="sm-name"><span class="sm-at">@</span>' + ctx.utils.esc(name) + '</span>' +
      '<span class="sm-desc">' + ctx.utils.esc(desc.substring(0, 60)) + '</span>' +
      '</div>';
  }).join('');

  mentionDropdown.querySelectorAll('.skill-mention-item').forEach(el => {
    el.addEventListener('mousedown', (e) => {
      e.preventDefault(); // 阻止失焦
      const idx = parseInt(el.dataset.idx, 10);
      if (idx >= 0 && idx < mentionSkills.length) {
        selectMentionSkill(mentionSkills[idx]);
      }
    });
    el.addEventListener('mouseenter', () => {
      mentionActiveIdx = parseInt(el.dataset.idx, 10);
      el.parentElement.querySelectorAll('.skill-mention-item').forEach((c, i) => {
        c.classList.toggle('active', i === mentionActiveIdx);
      });
    });
  });

  mentionDropdown.classList.add('visible');
  mentionVisible = true;
}

function selectMentionSkill(skill) {
  const name = skill.name || '';
  if (!name) return;

  const val = inputEl.value;
  const cursorPos = inputEl.selectionStart;

  // 找到触发 @ 的位置
  const textBeforeCursor = val.substring(0, cursorPos);
  const atPos = textBeforeCursor.lastIndexOf('@');

  // 替换 @query 为 @SkillName + 空格
  const before = val.substring(0, atPos);
  const after = val.substring(cursorPos);
  const insertion = '@' + name + ' ';
  inputEl.value = before + insertion + after;

  // 设置光标到插入文本之后
  const newCursorPos = atPos + insertion.length;
  inputEl.setSelectionRange(newCursorPos, newCursorPos);
  inputEl.focus();

  hideMentionDropdown();
  autoGrow();
}

function hideMentionDropdown() {
  if (mentionDropdown) {
    mentionDropdown.classList.remove('visible');
  }
  mentionVisible = false;
  mentionActiveIdx = -1;
  mentionTriggerPos = -1;
}

function setStreamMode(mode) {
  ctx.state.setState('ui.streamMode', mode);
  document.getElementById('modeA2A').classList.toggle('active', mode === 'a2a');
  document.getElementById('modeChannel').classList.toggle('active', mode === 'channel');
  updateConnBadge();
}

function updateConnBadge() {
  if (!connBadgeEl) return;
  const mode = ctx.state.getState('ui.streamMode');
  const sid = currentSessionId();
  const uid = ctx.state.getState('ui.userId') || 'debug-user';
  const bits = [mode === 'a2a' ? 'A2A' : 'Channel'];
  bits.push('单次流');
  if (uid !== 'debug-user') bits.push(uid);
  if (sid) bits.push(sid.split('_').pop());
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
    const uid = ctx.state.getState('ui.userId') || 'debug-user';
    // ★ 优先使用服务端 userId 过滤（session_user 表），不再依赖 session_id 前缀匹配
    const threads = await ctx.api.getThreads(uid);
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

async function selectThread(sessionId) {
  if (!sessionId) return;
  ctx.state.setState('threads.current', sessionId);
  document.getElementById('btnLlmCalls').disabled = false;
  loadThreadHistory(sessionId);
  updateConnBadge();
  // ★ durable-sse-plan：刷新恢复——检测 turn 状态，若仍在执行则自动 subscribe 续传
  tryResumeSSE(sessionId);
}

/** ★ durable-sse-plan §4.3：页面刷新后检测 turn 状态，自动续传 */
async function tryResumeSSE(sessionId) {
  try {
    const status = await ctx.api.getStatus(sessionId);
    if (status.state === 'working' || status.state === 'waiting_confirm') {
      console.log('[durable-sse] resuming SSE for working session:', sessionId);
      isStreaming = true;
      sendBtn.disabled = true;
      sendBtn.textContent = 'Stop';
      sendBtn.classList.add('danger');
      currentReplyId = status.reply_id || null;
      lastEventId = status.latest_event_seq || 0;
      reconnectAttempts = 0;

      // 如果正在等待确认，渲染 HITL 卡片
      if (status.state === 'waiting_confirm' && status.pending_confirm) {
        const r = currentReply || ensureReply(currentReplyId || 'resume-' + Date.now());
        renderConfirmCard(r, status.pending_confirm);
      }

      // 订阅续传
      activeSubHandle = ctx.api.subscribe(sessionId, {
        afterSeq: lastEventId,
        replyId: currentReplyId,
        onEvent: (evt) => {
          reconnectAttempts = 0;
          if (evt.type === 'AGENT_START' && evt.replyId) currentReplyId = evt.replyId;
          if (evt.type === 'permission_ask' && evt.reply_id) currentReplyId = evt.reply_id;
          handleEvent(evt);
        },
        onError: (e) => {
          console.warn('[durable-sse] resume subscribe error:', e.message);
          if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            const delay = Math.min(RECONNECT_BASE_DELAY * Math.pow(2, reconnectAttempts), 30000);
            reconnectAttempts++;
            setTimeout(() => tryResumeSSE(sessionId), delay);
          }
        },
        onEnd: () => {
          clearInterval(resumeSyncId);
          resetStreamingState();
          loadThreads();
        }
      });

      // 定期同步 lastEventId
      const resumeSyncId = setInterval(() => {
        if (activeSubHandle && activeSubHandle.lastEventId > lastEventId) {
          lastEventId = activeSubHandle.lastEventId;
        }
        if (!isStreaming) clearInterval(resumeSyncId);
      }, 500);
    }
  } catch (e) {
    console.warn('[durable-sse] status check failed, skipping resume:', e.message);
  }
}

function newThread() {
  ctx.state.setState('threads.current', null);
  teardownAllAppHosts();
  pendingToolCalls = {};
  pendingConfirm = null;
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
  try {
    const data = await ctx.api.getThreadHistory(sessionId);
    const msgs = data.messages || [];
    messagesEl.innerHTML = '';
    if (msgs.length === 0) {
      messagesEl.innerHTML = '<div class="msg system">No messages recovered for this thread</div>';
      return;
    }
    // Track reply_id → contentEl mapping for file card distribution
    const replyToContent = new Map();
    let lastAssistantEl = null;
    let lastAssistantContentEl = null;
    for (const m of msgs) {
      if (m.role === 'user') addMessage('user', m.content || '');
      else if (m.role === 'assistant' || m.role === 'agent') {
        const refs = addAssistantHistory(m.content || '', m.tool_calls || []);
        lastAssistantEl = refs.msgEl;
        lastAssistantContentEl = refs.contentEl;
        if (m.reply_id) {
          replyToContent.set(m.reply_id, refs.contentEl);
        }
      }
    }
    // Restore file download cards from history — distribute by reply_id
    const files = data.files || [];
    if (files.length > 0) {
      for (const f of files) {
        let targetContentEl = null;
        if (f.reply_id && replyToContent.has(f.reply_id)) {
          targetContentEl = replyToContent.get(f.reply_id);
        } else {
          // Fallback: attach to last assistant message
          targetContentEl = lastAssistantContentEl;
        }
        if (targetContentEl) {
          renderFileReadyCard({ contentEl: targetContentEl }, f);
        }
      }
    }
    // Restore pending HITL confirm card
    const pc = data.pendingConfirm;
    if (pc && lastAssistantEl) {
      const toolCalls = pc.tool_calls || pc.tools || [];
      if (toolCalls.length > 0) {
        const fakeReply = {
          replyId: pc.reply_id,
          contentEl: lastAssistantContentEl,
          textEl: lastAssistantContentEl
        };
        renderConfirmCard(fakeReply, { tool_calls: toolCalls });
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

  // 用户消息：高亮 @Skill 引用
  if (role === 'user') {
    var displayContent = ctx.utils.esc(content);
    displayContent = displayContent.replace(/@(\S+)/g, '<span class="skill-mention-tag">@$1</span>');
    bubble.innerHTML = displayContent;
  } else {
    writeMarkdown(bubble, renderMarkdown(content));
  }

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

/** 历史 assistant 消息：工具调用（已完成✓）+ 文本气泡（Markdown 渲染）
 *  @returns {{ msgEl: HTMLElement, contentEl: HTMLElement }} 元素引用，供历史回放时追加卡片 */
function addAssistantHistory(content, toolCalls) {
  const msg = document.createElement('div');
  msg.className = 'msg assistant';
  let contentEl = null;
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
    contentEl = bubbleEl;
  }
  // 无文本内容时，用 msg 本身作为容器（文件卡片可直接追加到 msg）
  if (!contentEl) contentEl = msg;
  messagesEl.appendChild(msg);
  scrollToBottom(false);
  return { msgEl: msg, contentEl: contentEl };
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

// ---------- 文件下载卡片（file_ready） ----------

/** 渲染 Agent 产出文件下载卡片（file-upload-download-plan §8.3） */
function renderFileReadyCard(r, data) {
  const fileId = data.file_id;
  const fileName = data.file_name || 'file';
  const mimeType = data.mime_type || 'application/octet-stream';
  const size = data.size || 0;
  const downloadUrl = ctx.api.BASE + (data.download_url || ('/files/' + fileId));

  // 根据文件类型选图标
  const icon = mimeType.startsWith('image/') ? '🖼️'
    : mimeType.startsWith('text/') ? '📄'
    : mimeType === 'application/pdf' ? '📕'
    : mimeType.startsWith('application/vnd.openxmlformats-officedocument.spreadsheetml') ? '📊'
    : mimeType.startsWith('application/vnd.openxmlformats-officedocument') ? '📝'
    : mimeType === 'application/zip' ? '📦'
    : '📎';

  const card = document.createElement('div');
  card.className = 'file-ready-card';
  card.innerHTML =
    '<div class="file-ready-info">' +
      '<span class="file-ready-icon">' + icon + '</span>' +
      '<div class="file-ready-meta">' +
        '<span class="file-ready-name">' + ctx.utils.esc(fileName) + '</span>' +
        '<span class="file-ready-size">' + formatFileSize(size) + '</span>' +
      '</div>' +
    '</div>' +
    '<a class="btn small file-ready-download" href="' + ctx.utils.esc(downloadUrl) +
      '" download="' + ctx.utils.esc(fileName) + '" target="_blank" rel="noopener">⬇ 下载</a>';

  // 挂载到回复气泡内部末尾（contentEl 内），与工具组同级，保证多个文件卡片按顺序排列
  const container = r.contentEl;
  container.appendChild(card);
  scrollToBottom(true);
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
    case 'file_ready': {
      // Agent 产出文件：渲染下载卡片（file-upload-download-plan §8.3）
      // 兜底：AGENT_END 后到达或 currentReply 为 null 时，用 replyId 或占位 id 确保卡片仍渲染
      const fr = r || ensureReply(data.replyId || 'file-' + Date.now());
      renderFileReadyCard(fr, data);
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
    case 'interrupted': {
      // 执行副本崩溃/被抢占：服务端回放完已落库事件后补发的终止帧（不携带 replyId）
      isStreaming = false;
      sendBtn.disabled = false;
      sendBtn.textContent = 'Send';
      sendBtn.classList.remove('danger');
      if (r) {
        const itr = document.createElement('div');
        itr.className = 'msg system';
        itr.style.color = 'var(--red)';
        itr.textContent = '执行已中断：执行该任务的副本失去响应，本次回复可能不完整。可刷新页面查看最新状态，或重新发送消息。';
        messagesEl.appendChild(itr);
      }
      break;
    }
    default:
      break;
  }
}

// ---------- 发送 ----------

// ---------- 文件上传 ----------

function handleFileSelect(e) {
  const files = Array.from(e.target.files);
  if (files.length === 0) return;

  for (const file of files) {
    pendingFiles.push({ file, fileId: null, status: 'pending' });
  }
  renderUploadPreview();
  fileInput.value = ''; // 重置 input 以便再次选择同一文件
}

function renderUploadPreview() {
  if (pendingFiles.length === 0) {
    uploadPreview.style.display = 'none';
    return;
  }

  uploadPreview.style.display = 'block';
  uploadFiles.innerHTML = pendingFiles.map((item, index) => {
    const file = item.file;
    const statusIcon = item.status === 'uploading' ? '⏳' : 
                      item.status === 'uploaded' ? '✅' : 
                      item.status === 'error' ? '❌' : '📎';
    return `<div class="upload-file-item" data-index="${index}">
      <span class="file-icon">${statusIcon}</span>
      <span class="file-name" title="${file.name}">${file.name}</span>
      <span class="file-size">(${formatFileSize(file.size)})</span>
      <button class="btn small remove-file" data-index="${index}" title="移除">✕</button>
    </div>`;
  }).join('');

  // 绑定移除按钮事件
  uploadFiles.querySelectorAll('.remove-file').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const index = parseInt(e.target.dataset.index);
      pendingFiles.splice(index, 1);
      renderUploadPreview();
    });
  });
}

function formatFileSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

async function uploadPendingFiles() {
  const filesToUpload = pendingFiles.filter(item => item.status === 'pending');
  if (filesToUpload.length === 0) return [];

  const uploadedIds = [];
  for (const item of filesToUpload) {
    item.status = 'uploading';
    renderUploadPreview();
    try {
      const uid = ctx.state.getState('ui.userId') || 'debug-user';
      const result = await ctx.api.uploadFile(item.file, uid, currentSessionId());
      item.fileId = result.file_id;
      item.status = 'uploaded';
      uploadedIds.push(result.file_id);
    } catch (e) {
      item.status = 'error';
      // 不自动重试，标记为失败并提示用户可手动移除
      ctx.utils.toast('文件上传失败（已跳过）: ' + item.file.name + ' — ' + e.message, 'error');
    }
    renderUploadPreview();
  }

  // 清除已上传和失败的文件（失败文件不自动重试，发送后清除）
  pendingFiles = pendingFiles.filter(item => item.status === 'pending');
  renderUploadPreview();

  return uploadedIds;
}

// ---------- 发送消息 ----------

async function sendMessage() {
  if (isStreaming) { stop(); return; }
  const text = inputEl.value.trim();
  if (!text && pendingFiles.length === 0) return;

  let sid = currentSessionId();
  if (!sid) {
    const uid = ctx.state.getState('ui.userId') || 'debug-user';
    sid = uid + '_' + Date.now().toString(36);
    ctx.state.setState('threads.current', sid);
    document.getElementById('btnLlmCalls').disabled = false;
    updateConnBadge();
  }

  // 上传文件
  const fileIds = await uploadPendingFiles();

  inputEl.value = '';
  inputEl.style.height = 'auto';
  inputEl.focus();
  hideMentionDropdown();
  addMessage('user', text);

  // 异步刷新可用 Skill 列表（下次 @ 触发时使用最新数据）
  loadAvailableSkills();

  const mode = ctx.state.getState('ui.streamMode');

  if (mode === 'channel') {
    await sendChannelSingleStream(text, sid, fileIds);
  } else {
    // A2A 模式不支持文件上传，提示用户切换到 Channel 模式
    if (fileIds && fileIds.length > 0) {
      ctx.utils.toast('A2A 模式不支持文件上传，已自动切换到 Channel 模式', 'warn');
      setStreamMode('channel');
      await sendChannelSingleStream(text, sid, fileIds);
    } else {
      await sendA2AStream(text, sid);
    }
  }
}

function stop() {
  // ★ durable-sse-plan：stop 关闭所有连接
  if (activeChatHandle) { try { activeChatHandle.close(); } catch (e) { /* ignore */ } }
  if (activeSubHandle) { try { activeSubHandle.close(); } catch (e) { /* ignore */ } }
  if (activeAbort) {
    activeAbort.abort();
    return;
  }
  isStreaming = false;
  sendBtn.disabled = false;
  sendBtn.textContent = 'Send';
  sendBtn.classList.remove('danger');
}

// ★ durable-sse-plan：重置流式状态
function resetStreamingState() {
  isStreaming = false;
  sendBtn.disabled = false;
  sendBtn.textContent = 'Send';
  sendBtn.classList.remove('danger');
  activeChatHandle = null;
  activeSubHandle = null;
}

async function sendChannelSingleStream(text, sid, fileIds) {
  // durable-sse-plan 改造版：
  // 1. POST /chat → 事件经 EventBus 广播，SSE 断连不影响 agent 执行
  // 2. SSE 断连后自动通过 GET /subscribe 重连续传
  // 3. 全程心跳（EventBus），防 Nginx/CDN 超时
  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = 'Stop';
  sendBtn.classList.add('danger');

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

  // ★ 重连逻辑：SSE 断连后通过 subscribe 续传
  const startReconnect = () => {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      handleEvent({ type: 'error', error: '连接断开，重连失败已达上限，请刷新页面' });
      return;
    }
    const delay = Math.min(RECONNECT_BASE_DELAY * Math.pow(2, reconnectAttempts), 30000);
    reconnectAttempts++;
    console.log(`[durable-sse] reconnecting in ${delay}ms (attempt ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS}), afterSeq=${lastEventId}`);
    setTimeout(() => {
      // 先查状态，确认 turn 是否仍在执行
      ctx.api.getStatus(sid).then(status => {
        // ★ interrupted 也走订阅：观察者路径会先回放已落库的部分输出（用户能看到确实产出的内容），
        // 再由服务端补发 interrupted 帧收流，该帧由 handleEvent 渲染为中断提示。
        // 若落入下面的 else 分支，用户只会看到一个没有任何内容的 AGENT_END，无从得知执行已中断。
        if (status.state === 'working' || status.state === 'waiting_confirm' || status.state === 'interrupted') {
          currentReplyId = status.reply_id || currentReplyId;
          subscribeWithReconnect(sid, lastEventId, currentReplyId);
        } else if (status.state === 'completed') {
          // turn 已完成，回放剩余事件后结束
          replayAndClose(sid, lastEventId, currentReplyId);
        } else {
          // idle 或异常，直接结束
          handleEvent({ type: 'AGENT_END', replyId: currentReplyId });
          finishStream();
        }
      }).catch(() => {
        // status 查询失败也尝试重连
        subscribeWithReconnect(sid, lastEventId, currentReplyId);
      });
    }, delay);
  };

  // ★ 通过 GET /subscribe 重连续传
  const subscribeWithReconnect = (sessionId, afterSeq, replyId) => {
    if (activeSubHandle) { try { activeSubHandle.close(); } catch (e) { /* ignore */ } }
    activeSubHandle = ctx.api.subscribe(sessionId, {
      afterSeq,
      replyId,
      onEvent: (evt) => {
        reconnectAttempts = 0; // 收到事件说明连接正常，重置计数
        hideWaiting();
        handleEvent(evt);
      },
      onError: (e) => {
        console.warn('[durable-sse] subscribe error:', e.message);
        startReconnect();
      },
      onEnd: (evt) => {
        // 流正常结束（done 帧或 AGENT_END 后 EventBus close）
        finishStream();
      }
    });
  };

  // ★ 回放历史 + done 帧后关闭（turn 已完成场景）
  const replayAndClose = (sessionId, afterSeq, replyId) => {
    activeSubHandle = ctx.api.subscribe(sessionId, {
      afterSeq,
      replyId,
      onEvent: (evt) => {
        hideWaiting();
        handleEvent(evt);
      },
      onError: () => { /* 回放失败也结束 */ },
      onEnd: () => {
        finishStream();
      }
    });
  };

  // ★ 发起 POST /chat
  reconnectAttempts = 0;
  const uid = ctx.state.getState('ui.userId') || 'debug-user';
  activeChatHandle = ctx.api.sendChat(sid, text, uid, fileIds, {
    onWaiting: () => {
      if (!waitingSince) waitingSince = Date.now();
      showWaiting();
    },
    onEvent: (evt) => {
      reconnectAttempts = 0;
      hideWaiting();
      // 追踪 replyId（从 AGENT_START 或 permission_ask 中获取）
      if (evt.type === 'AGENT_START' && evt.replyId) currentReplyId = evt.replyId;
      if (evt.type === 'permission_ask' && evt.reply_id) currentReplyId = evt.reply_id;
      handleEvent(evt);
    },
    onError: (e) => {
      hideWaiting();
      // ★ 核心变化：SSE 断连不代表 agent 失败，尝试重连
      if (isStreaming) {
        console.warn('[durable-sse] chat SSE disconnected, will try reconnect:', e.message);
        startReconnect();
      } else {
        const r = currentReply || ensureReply('single');
        handleEvent({ type: 'error', error: e.message, replyId: r.replyId });
      }
    },
    onEnd: () => {
      // ★ 需要判断是自然结束还是断连
      // 自然结束：AGENT_END 事件已触发，isStreaming=false
      // 断连：isStreaming 仍为 true，需要重连
      if (isStreaming && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        console.log('[durable-sse] stream ended unexpectedly, attempting reconnect');
        startReconnect();
      } else {
        hideWaiting();
        loadThreads();
      }
    }
  });

  // 同步 lastEventId
  const syncId = setInterval(() => {
    if (activeChatHandle) lastEventId = activeChatHandle.lastEventId;
    if (activeSubHandle && activeSubHandle.lastEventId > lastEventId) {
      lastEventId = activeSubHandle.lastEventId;
    }
  }, 500);

  // 当流真正结束时的清理（替代覆写全局函数的方式）
  const finishStream = () => {
    clearInterval(syncId);
    resetStreamingState();
    loadThreads();
  };

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
        params: { message: { role: 'user', parts: [{ text }], metadata: { userId: ctx.state.getState('ui.userId') || 'debug-user', sessionId: sid } } },
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