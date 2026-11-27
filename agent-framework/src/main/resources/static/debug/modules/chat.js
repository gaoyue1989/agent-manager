import { icon } from '../js/icons.js';
import { createChatSidebar } from './chat-sidebar.js';
import { createChatRenderer } from './chat-renderer.js';
import { createChatComposer } from './chat-composer.js';

let ctx = null;
let messagesEl = null;
let connBadgeEl = null;
let uidInput = null;
let modelSelect = null;
let protoSelect = null;
let toolsPopover = null;
let sidebar = null;
let renderer = null;
let composer = null;

let isStreaming = false;
let activeAbort = null;
let activeChatHandle = null;
let activeSubHandle = null;
let stopSlowTimer = null;
let resumeSyncTimer = null;
let activeSyncTimer = null;
let refreshTimer = null;
let stoppingBuffer = [];

let lastEventId = 0;
let currentReplyId = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;
const RECONNECT_BASE_DELAY = 1000;

let currentReply = null;
let usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, cached_tokens: 0, call_count: 0 };

function transport() {
  return (ctx && ctx.agui && ctx.state.getState('ui.protocol') === 'agui') ? ctx.agui : ctx.api;
}

function uid() {
  return (ctx && ctx.state.getState('ui.userId')) || 'debug-user';
}

function currentSessionId() {
  return ctx ? ctx.state.getState('threads.current') : null;
}

function currentThreadTitle() {
  const sid = currentSessionId();
  if (!sid) return '对话';
  const list = ctx.state.getState('threads.list') || [];
  const t = list.find((x) => x && x.session_id === sid);
  return (t && t.title && t.title.trim()) ? t.title.trim() : sid;
}

function renderTemplate() {
  return `
  <div class="chat-module">
    <div class="chat-sidebar">
      <div class="chat-sidebar-header">
        <span class="cs-title">会话列表</span>
        <button id="btnNewThread" class="btn small ghost" title="新建会话"></button>
      </div>
      <div class="chat-sidebar-search">
        <input id="threadSearch" class="input" type="search" placeholder="搜索会话...">
      </div>
      <div class="chat-sidebar-list" id="threadList">
        <div class="empty">加载中...</div>
      </div>
    </div>
    <div class="chat-main">
      <div class="chat-header-bar">
        <button id="btnMobileSessions" class="icon-btn chat-mobile-sessions-btn" title="会话列表"></button>
        <h2 id="chatTitle">对话</h2>
        <span class="status-text" id="connBadge"></span>
        <div class="chat-header-right">
          <input id="uidInput" class="input uid-input" style="width:96px" type="text" spellcheck="false" title="用户标识（同用户共享工作区与记忆）">
          <select id="modelSelect" class="input uid-input" title="当前会话模型"></select>
          <div class="seg">
            <button id="modeA2A">A2A</button>
            <button id="modeChannel" class="active">Channel</button>
          </div>
          <button id="btnContextToggle" class="btn small ghost" title="会话上下文注入">
            <span id="ctxIconSlot"></span>
            <span>上下文</span>
            <span class="badge accent ctx-count" style="display:none"></span>
          </button>
          <div class="chat-tools-wrap">
            <button id="btnDebugTools" class="btn small" title="更多调试工具">
              <span id="toolsIconSlot"></span>
              <span>更多</span>
            </button>
            <div id="chatToolsPopover" class="chat-tools-popover">
              <div class="popover-row">
                <span>对话协议</span>
                <select id="protoSelect" class="input">
                  <option value="native">native</option>
                  <option value="agui">AG-UI</option>
                </select>
              </div>
              <div class="popover-actions">
                <button id="btnBrowserToggle" class="btn small accent">浏览器接管</button>
                <button id="btnLlmCalls" class="btn small" disabled>LLM 调用</button>
                <button id="btnSysPrompt" class="btn small">系统提示词</button>
                <button id="btnCard" class="btn small">Agent 卡片</button>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div id="browserControlBar" class="browser-bar"></div>
      <div id="contextDrawer" class="context-drawer"></div>
      <div class="chat-messages" id="chatMessages">
        <div class="chat-messages-inner" id="chatInner"></div>
        <button id="scrollDown" class="scroll-down-btn" title="回到底部">↓</button>
      </div>
      <div class="chat-input">
        <div class="chat-composer-card">
          <div id="uploadPreview" class="upload-preview" style="display:none">
            <div class="upload-files" id="uploadFiles"></div>
          </div>
          <div class="chat-input-pill">
            <button id="uploadBtn" class="upload-btn" title="上传附件"></button>
            <input type="file" id="fileInput" multiple accept="image/*,text/plain,text/markdown,text/csv,application/pdf,.docx,.xlsx,.pptx,.doc,.xls,.ppt" style="display:none">
            <textarea id="chatInput" rows="1" placeholder="输入消息...（/ 提及技能，Enter 发送，Shift+Enter 换行）"></textarea>
            <button id="sendBtn" class="btn primary">发送</button>
            <button id="stopBtn" class="btn danger" style="display:none">停止</button>
          </div>
          <div id="skillMentionDropdown" class="skill-mention-dropdown"></div>
        </div>
      </div>
    </div>
  </div>`;
}

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = renderTemplate();
    messagesEl = document.getElementById('chatInner');
    connBadgeEl = document.getElementById('connBadge');
    uidInput = document.getElementById('uidInput');
    modelSelect = document.getElementById('modelSelect');
    protoSelect = document.getElementById('protoSelect');
    toolsPopover = document.getElementById('chatToolsPopover');

    const ctxIcon = document.getElementById('ctxIconSlot');
    if (ctxIcon) ctxIcon.innerHTML = icon('layers', 14);
    const toolsIcon = document.getElementById('toolsIconSlot');
    if (toolsIcon) toolsIcon.innerHTML = icon('sliders', 14);

    uidInput.value = uid();
    protoSelect.value = ctx.state.getState('ui.protocol') || 'native';

    sidebar = createChatSidebar({
      ctx,
      onSelectThread: (sid) => selectThread(sid),
      onNewThread: () => newThread(),
      onThreadDeleted: (sid) => {
        if (currentSessionId() === sid) newThread();
        loadThreads(true);
      },
      onThreadRenamed: (sid, title) => {
        const list = (ctx.state.getState('threads.list') || [])
          .map((t) => (t && t.session_id === sid ? { ...t, title } : t));
        ctx.state.setState('threads.list', list);
        sidebar.renderList(list);
        if (currentSessionId() === sid) updateHeaderTitle();
      },
      onBrowserRelease: (sid, onDone) => releaseBrowserAndStream(sid, onDone)
    });

    renderer = createChatRenderer({
      ctx,
      getMessagesEl: () => messagesEl,
      scrollToBottom,
      getCurrentSessionId: () => currentSessionId(),
      getUserId: () => uid(),
      onSubmitConfirm: (calls, approved) => submitConfirm(calls, approved)
    });

    composer = createChatComposer({
      ctx,
      onSend: () => sendMessage(),
      onStop: () => stop()
    });

    sidebar.mount(container);
    composer.mount(container);
    bindHeaderEvents();
    updateConnBadge();

    messagesEl.innerHTML = '<div class="chat-greeting">今天有什么可以帮您？</div>';
    loadModels();
    loadThreads();
    const restoredSid = currentSessionId();
    if (restoredSid) selectThread(restoredSid);

    refreshTimer = setInterval(() => loadThreads(), 30000);
    subscribeScroll();
  },

  unmount() {
    if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
    if (resumeSyncTimer) { clearInterval(resumeSyncTimer); resumeSyncTimer = null; }
    if (activeSyncTimer) { clearInterval(activeSyncTimer); activeSyncTimer = null; }
    clearTimeout(stopSlowTimer);
    if (activeAbort) { activeAbort.abort(); activeAbort = null; }
    if (activeChatHandle) { try { activeChatHandle.close(); } catch (e) {} activeChatHandle = null; }
    if (activeSubHandle) { try { activeSubHandle.close(); } catch (e) {} activeSubHandle = null; }
    if (renderer) renderer.resetAll();
    document.removeEventListener('click', closePopoverOutside);
    currentReply = null;
    currentReplyId = null;
    lastEventId = 0;
    stoppingBuffer = [];
    isStreaming = false;
  }
};

function closePopoverOutside(e) {
  if (!toolsPopover || !toolsPopover.classList.contains('open')) return;
  if (e.target.closest('.chat-tools-wrap')) return;
  toolsPopover.classList.remove('open');
}

function bindHeaderEvents() {
  document.getElementById('btnDebugTools').addEventListener('click', (e) => {
    e.stopPropagation();
    if (toolsPopover) toolsPopover.classList.toggle('open');
  });
  document.addEventListener('click', closePopoverOutside);

  modelSelect.addEventListener('change', onModelChange);

  protoSelect.addEventListener('change', () => {
    ctx.state.setState('ui.protocol', protoSelect.value);
    ctx.utils.toast('对话协议已切换为 ' + protoSelect.value + '（下一轮生效）', 'info');
  });

  document.getElementById('modeA2A').addEventListener('click', () => setStreamMode('a2a'));
  document.getElementById('modeChannel').addEventListener('click', () => setStreamMode('channel'));

  uidInput.addEventListener('change', () => {
    const val = (uidInput.value || '').trim() || 'debug-user';
    uidInput.value = val;
    ctx.state.setState('ui.userId', val);
    ctx.state.setState('threads.current', null);
    newThread();
    loadThreads(true);
    composer.loadAvailableSkills();
  });

  document.getElementById('btnBrowserToggle').addEventListener('click', () => {
    if (toolsPopover) toolsPopover.classList.remove('open');
    sidebar.probeBrowserState();
  });
  document.getElementById('btnLlmCalls').addEventListener('click', () => {
    if (toolsPopover) toolsPopover.classList.remove('open');
    showLlmCalls();
  });
  document.getElementById('btnSysPrompt').addEventListener('click', () => {
    if (toolsPopover) toolsPopover.classList.remove('open');
    showSystemPrompt();
  });
  document.getElementById('btnCard').addEventListener('click', () => {
    if (toolsPopover) toolsPopover.classList.remove('open');
    showAgentCard();
  });
}

function setStreamMode(mode) {
  ctx.state.setState('ui.streamMode', mode);
  document.getElementById('modeA2A').classList.toggle('active', mode === 'a2a');
  document.getElementById('modeChannel').classList.toggle('active', mode === 'channel');
  updateConnBadge();
}

function updateConnBadge() {
  if (!connBadgeEl) return;
  const mode = ctx.state.getState('ui.streamMode') || 'channel';
  connBadgeEl.textContent = mode === 'a2a' ? 'A2A' : 'Channel';
}

function updateHeaderTitle(connecting = false) {
  const el = document.getElementById('chatTitle');
  if (!el) return;
  const title = currentThreadTitle();
  el.textContent = connecting ? title + ' · 运行中' : title;
  el.title = title;
}

function subscribeScroll() {
  const scroller = document.getElementById('chatMessages');
  const btn = document.getElementById('scrollDown');
  if (!scroller || !btn) return;
  scroller.addEventListener('scroll', () => {
    const gap = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
    btn.classList.toggle('visible', gap > 120);
  });
  btn.addEventListener('click', () => scrollToBottom(true));
}

function scrollToBottom(force) {
  const scroller = document.getElementById('chatMessages');
  if (!scroller) return;
  const gap = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
  if (force || gap < 140) scroller.scrollTop = scroller.scrollHeight;
}

async function loadThreads() {
  try {
    const list = (await ctx.api.getThreads(uid())) || [];
    ctx.state.setState('threads.list', list);
    sidebar.renderList(list);
    if (currentSessionId()) {
      updateHeaderTitle(isStreaming);
      restoreModelForSession(currentSessionId());
    }
  } catch (e) {}
}

async function selectThread(sessionId) {
  if (!sessionId) return;
  if (activeSubHandle) { try { activeSubHandle.close(); } catch (e) {} activeSubHandle = null; }
  if (resumeSyncTimer) { clearInterval(resumeSyncTimer); resumeSyncTimer = null; }
  ctx.state.setState('threads.current', sessionId);
  sidebar.renderList(ctx.state.getState('threads.list') || []);
  sidebar.hideBrowserBar();
  updateHeaderTitle(false);
  restoreModelForSession(sessionId);
  const llmBtn = document.getElementById('btnLlmCalls');
  if (llmBtn) llmBtn.disabled = false;
  await loadThreadHistory(sessionId);
  await tryResumeSSE(sessionId);
}

async function tryResumeSSE(sessionId) {
  try {
    const status = await ctx.api.getStatus(sessionId, uid());
    if (!status || status.state === 'idle' || status.state === 'completed') return;
    if (status.state === 'working' || status.state === 'waiting_confirm' || status.state === 'interrupted') {
      currentReplyId = status.reply_id || null;
      enterStreamingUi();
      if (status.stopping) enterStoppingUi();
      activeSubHandle = transport().subscribe(sessionId, {
        afterSeq: 0,
        replyId: currentReplyId,
        userId: uid(),
        onEvent: (evt) => handleEvent(evt),
        onError: () => resetStreamingState(),
        onEnd: () => resetStreamingState()
      });
    }
  } catch (e) {}
}

function newThread() {
  if (activeSubHandle) { try { activeSubHandle.close(); } catch (e) {} activeSubHandle = null; }
  if (activeChatHandle) { try { activeChatHandle.close(); } catch (e) {} activeChatHandle = null; }
  resetStreamingState();
  ctx.state.setState('threads.current', null);
  renderer.resetAll();
  sidebar.hideBrowserBar();
  sidebar.setContextFromHistory([]);
  sidebar.renderList(ctx.state.getState('threads.list') || []);
  updateHeaderTitle(false);
  if (modelSelect) modelSelect.value = 'system';
  const llmBtn = document.getElementById('btnLlmCalls');
  if (llmBtn) llmBtn.disabled = true;
  messagesEl.innerHTML = '<div class="chat-greeting">今天有什么可以帮您？</div>';
  composer.focus();
}

async function loadModels() {
  if (!modelSelect) return;
  try {
    const data = await ctx.api.getModels(false);
    const items = Array.isArray(data) ? data : (data.models || []);
    const hasSystem = items.some((m) => (m.id || m.name) === 'system');
    let html = hasSystem ? '' : '<option value="system">默认模型</option>';
    for (const m of items) {
            const id = m.id || m.name;
      const label = (m.name || id) + (m.is_default ? ' ★' : '');
      html += '<option value="' + ctx.utils.esc(id) + '">' + ctx.utils.esc(label) + '</option>';
    }
    modelSelect.innerHTML = html;
    if (currentSessionId()) restoreModelForSession(currentSessionId());
  } catch (e) {
    modelSelect.innerHTML = '<option value="system">默认模型</option>';
  }
}

async function onModelChange() {
  const sid = currentSessionId();
  const target = modelSelect.value;
  if (!sid) return;
  const prev = currentSessionModel();
  const ok = await bindSessionModel(sid, target);
  if (!ok) {
    modelSelect.value = prev;
    return;
  }
  const list = (ctx.state.getState('threads.list') || [])
    .map((t) => (t && t.session_id === sid ? { ...t, model: target === 'system' ? '' : target } : t));
  ctx.state.setState('threads.list', list);
  sidebar.renderList(list);
}

function currentSessionModel() {
  const sid = currentSessionId();
  if (!sid) return 'system';
  const list = ctx.state.getState('threads.list') || [];
  const t = list.find((x) => x && x.session_id === sid);
  return (t && t.model) || 'system';
}

function restoreModelForSession(sessionId) {
  if (!modelSelect) return;
  const list = ctx.state.getState('threads.list') || [];
  const t = list.find((x) => x && x.session_id === sessionId);
  const bound = (t && t.model) || 'system';
  const exists = Array.from(modelSelect.options).some((o) => o.value === bound);
  modelSelect.value = exists ? bound : 'system';
}

async function bindSessionModel(sid, modelId) {
  try {
    await ctx.api.patchThread(sid, { model: modelId }, uid());
    return true;
  } catch (e) {
    ctx.utils.toast('模型切换失败: ' + e.message, 'error');
    return false;
  }
}

async function loadThreadHistory(sessionId) {
  messagesEl.innerHTML = '<div class="msg system">正在加载历史消息...</div>';
  renderer.resetAll();
  try {
    const data = await ctx.api.getThreadHistory(sessionId, uid());
    sidebar.setContextFromHistory(data.context || []);
    const msgs = data.messages || [];
    messagesEl.innerHTML = '';
    if (msgs.length === 0) {
      messagesEl.innerHTML = '<div class="msg system">该会话暂无历史消息</div>';
      return;
    }
    const replyToContent = new Map();
    let lastAssistantEl = null;
    let lastAssistantContentEl = null;
    for (const m of msgs) {
      if (m.role === 'compaction') renderer.addCompactionDivider(m.content || '');
      else if (m.role === 'user') {
        const userMsgEl = renderer.addMessage('user', m.content || '');
        for (const f of (m.files || [])) renderer.renderFileReadyCard({ contentEl: userMsgEl }, f);
      } else if (m.role === 'assistant' || m.role === 'agent') {
        const refs = renderer.addAssistantHistory(m.content || '', m.tool_calls || [], m.blocks);
        lastAssistantEl = refs.msgEl;
        lastAssistantContentEl = refs.contentEl;
        if (m.stopped) refs.msgEl.appendChild(renderer.renderStoppedBadge());
        if (m.reply_id) replyToContent.set(m.reply_id, refs.contentEl);
      }
    }
    for (const f of (data.files || [])) {
      const target = (f.reply_id && replyToContent.has(f.reply_id)) ? replyToContent.get(f.reply_id) : lastAssistantContentEl;
      if (target) renderer.renderFileReadyCard({ contentEl: target }, f);
    }
    const pc = data.pendingConfirm;
    if (pc && lastAssistantEl) {
      const toolCalls = pc.tool_calls || pc.tools || [];
      if (toolCalls.length > 0) {
        renderer.renderConfirmCard({ replyId: pc.reply_id, contentEl: lastAssistantContentEl }, { tool_calls: toolCalls });
      }
    }
  } catch (e) {
    messagesEl.innerHTML = '<div class="msg system">历史消息加载失败</div>';
  }
}

function isStoppingUi() {
  const stopBtn = composer && composer.getStopBtn();
  return Boolean(stopBtn && stopBtn.dataset.stopping === '1');
}

function enterStreamingUi() {
  isStreaming = true;
  const sendBtn = composer.getSendBtn();
  if (sendBtn) {
    sendBtn.disabled = true;
    sendBtn.textContent = '发送';
    sendBtn.classList.remove('danger');
  }
  showStopButton();
}

function showStopButton() {
  const stopBtn = composer.getStopBtn();
  if (!stopBtn) return;
  stopBtn.style.display = '';
  stopBtn.disabled = false;
  stopBtn.textContent = '停止';
  delete stopBtn.dataset.stopping;
}

function hideStopButton() {
  const stopBtn = composer.getStopBtn();
  if (!stopBtn) return;
  stopBtn.style.display = 'none';
  stopBtn.disabled = false;
  stopBtn.textContent = '停止';
  delete stopBtn.dataset.stopping;
}

function enterStoppingUi() {
  const stopBtn = composer.getStopBtn();
  if (!stopBtn) return;
  stopBtn.style.display = '';
  stopBtn.dataset.stopping = '1';
  stopBtn.textContent = '停止中...';
  stopBtn.disabled = true;
}

function flushStoppingBuffer() {
  if (!stoppingBuffer.length || !currentReply) return;
  const buffered = stoppingBuffer;
  stoppingBuffer = [];
  for (const item of buffered) {
    if (item.type === 'TEXT_BLOCK_DELTA') renderer.appendTextDelta(currentReply, item.delta);
    else if (item.type === 'THINKING_BLOCK_DELTA') renderer.appendThinkingDelta(currentReply, item.delta);
  }
  scrollToBottom(false);
}

function resetStreamingState() {
  isStreaming = false;
  const sendBtn = composer && composer.getSendBtn();
  if (sendBtn) {
    sendBtn.disabled = false;
    sendBtn.textContent = '发送';
    sendBtn.classList.remove('danger');
  }
  hideStopButton();
  clearTimeout(stopSlowTimer);
  if (activeSyncTimer) { clearInterval(activeSyncTimer); activeSyncTimer = null; }
  stoppingBuffer = [];
  activeChatHandle = null;
  activeSubHandle = null;
}

function handleEvent(data) {
  const isRemote = !!data.source;
  if (data.type === 'AGENT_START') {
    if (isRemote) {
      renderer.remotePanelStatus(renderer.getActiveRemotePanel(), 'Running', 'running');
      return;
    }
    currentReply = renderer.ensureReply(data.replyId || 'reply-' + Date.now());
    renderer.resetTurnRemote();
    updateHeaderTitle(true);
    if (!isStoppingUi()) enterStreamingUi();
    else { isStreaming = true; composer.getSendBtn().disabled = true; }
    usageAccumulator = { input_tokens: 0, output_tokens: 0, total_tokens: 0, cached_tokens: 0, call_count: 0 };
    return;
  }
  if (data.type === 'AGENT_END') {
    if (isRemote) {
      renderer.remotePanelStatus(renderer.getActiveRemotePanel(), 'Done', 'done');
      return;
    }
    if (currentReply) {
      if (data.usage) currentReply.serverUsage = data.usage;
      if (data.elapsed_ms != null) currentReply.serverElapsedMs = data.elapsed_ms;
    }
    renderer.finishReply(currentReply ? currentReply.replyId : null);
    currentReply = null;
    resetStreamingState();
    updateHeaderTitle(false);
    scrollToBottom(true);
    loadThreads();
    setTimeout(() => { if (currentSessionId()) loadThreads(); }, 4000);
    return;
  }

  const r = currentReply || (data.replyId ? renderer.ensureReply(data.replyId) : null);
  if (!r) return;
  if (isRemote) {
    renderer.routeRemoteEvent(r, data);
    return;
  }

  switch (data.type) {
    case 'TEXT_BLOCK_DELTA': {
      if (isStoppingUi()) {
        stoppingBuffer.push({ type: 'TEXT_BLOCK_DELTA', delta: data.delta || '' });
        break;
      }
      renderer.appendTextDelta(r, data.delta || '', {
        directReturn: !!data.directReturn,
        toolName: data.toolName || data.tool
      });
      scrollToBottom(false);
      break;
    }
    case 'TOOL_RETURN_DIRECT': {
      const txt = data.content || data.text || data.delta || '';
      if (txt) {
        renderer.appendTextDelta(r, txt, { directReturn: true, toolName: data.toolName || data.name });
        scrollToBottom(false);
      }
      break;
    }
    case 'THINKING_BLOCK_DELTA': {
      if (isStoppingUi()) {
        stoppingBuffer.push({ type: 'THINKING_BLOCK_DELTA', delta: data.delta || '' });
        break;
      }
      renderer.appendThinkingDelta(r, data.delta || '');
      break;
    }
    case 'THINKING_BLOCK_END': {
      renderer.endThinking(r);
      break;
    }
    case 'TOOL_CALL_START': {
      if (data.ui && data.ui.resourceUri) renderer.renderMcpAppCard(r, data);
      else renderer.onToolCallStart(r, data.toolCallId, data.toolName, { directReturn: !!data.directReturn });
      break;
    }
    case 'TOOL_CALL_DELTA': {
      renderer.onToolCallDelta(r, data.toolCallId, data.delta);
      break;
    }
    case 'TOOL_CALL_END': {
      renderer.onToolCallEnd(r, data.toolCallId);
      break;
    }
    case 'TOOL_RESULT_START': {
      renderer.onToolResultStart(r, data.toolCallId);
      break;
    }
    case 'TOOL_RESULT_TEXT_DELTA': {
      renderer.onToolResultDelta(r, data.toolCallId, data.delta);
      break;
    }
    case 'TOOL_RESULT_END': {
      const host = renderer.getAppHost(data.toolCallId);
      if (host) {
        const tc = renderer.getPendingToolCall(data.toolCallId) || {};
        host.sendToolInput(renderer.parseToolArgs(tc.argsRaw));
        host.sendToolResult(renderer.buildToolResult(tc.resultRaw, data.state));
      } else {
        renderer.onToolResultEnd(r, data.toolCallId, data.state, { directReturn: !!data.directReturn });
      }
      renderer.finishRemoteSpawn(data.toolCallId, data.state);
      break;
    }
    case 'tool_call_summary': {
      const owner = renderer.getRemoteToolOwner(data.toolCallId);
      if (owner) renderer.onRemoteToolSummary(owner, data.toolCallId, data.summary);
      else renderer.onToolCallSummary(r, data.toolCallId, data.summary, data.toolName);
      break;
    }
    case 'tool_result_preview': {
      const owner = renderer.getRemoteToolOwner(data.toolCallId);
      if (owner) renderer.onRemoteToolPreview(owner, data.toolCallId, data.preview);
      else {
        renderer.onToolResultPreview(r, data.toolCallId, data.preview);
        const sp = renderer.getRemotePanel(data.toolCallId);
        if (sp && data.preview) renderer.showRemoteFooter(sp, data.preview);
      }
      break;
    }
    case 'subagent_exposed': {
      renderer.finishSubagentExposed(data);
      break;
    }
    case 'MODEL_CALL_START': {
      r.modelCalls++;
      break;
    }
    case 'MODEL_CALL_END': {
      if (data.inputTokens != null || data.outputTokens != null) {
        usageAccumulator.input_tokens += data.inputTokens || 0;
        usageAccumulator.cached_tokens += data.cachedTokens || 0;
        usageAccumulator.output_tokens += data.outputTokens || 0;
        usageAccumulator.total_tokens += data.totalTokens || 0;
        usageAccumulator.call_count++;
        r.usage.input = usageAccumulator.input_tokens;
        r.usage.cached = usageAccumulator.cached_tokens;
        r.usage.output = usageAccumulator.output_tokens;
      }
      break;
    }
    case 'permission_ask': {
      renderer.renderConfirmCard(r, data);
      break;
    }
    case 'file_ready': {
      renderer.renderFileReadyCard(r, data);
      break;
    }
    case 'cancelled': {
      r.msgEl.appendChild(renderer.renderStoppedBadge());
      break;
    }
    case 'interrupted': {
      const el = document.createElement('div');
      el.className = 'msg system';
      el.textContent = data.message || 'Interrupted';
      messagesEl.appendChild(el);
      break;
    }
    case 'error': {
      const el = document.createElement('div');
      el.className = 'msg system error-text';
      el.textContent = 'Error: ' + (data.error || data.message || 'unknown error');
      messagesEl.appendChild(el);
      scrollToBottom(true);
      break;
    }
  }
}

async function sendMessage() {
  if (isStreaming) return;
  const text = composer.getValue();
  if (!text && !composer.hasPendingFiles()) return;

  let sid = currentSessionId();
  const isNew = !sid;
  if (!sid) {
    sid = 'debug-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
    ctx.state.setState('threads.current', sid);
    updateHeaderTitle(false);
    const llmBtn = document.getElementById('btnLlmCalls');
    if (llmBtn) llmBtn.disabled = false;
  }

  const selectedModel = modelSelect ? modelSelect.value : 'system';
  const mode = ctx.state.getState('ui.streamMode') || 'channel';
  if (mode === 'a2a' && selectedModel && selectedModel !== 'system') {
    const ok = await bindSessionModel(sid, selectedModel);
    if (!ok) return;
  }

  let fileIds = [];
  if (composer.hasPendingFiles()) {
    try {
      fileIds = await composer.uploadPendingFiles(sid);
    } catch (e) {
      return;
    }
  }

  composer.clearValue();
  const greeting = messagesEl.querySelector('.chat-greeting');
  if (greeting) greeting.remove();
  renderer.addMessage('user', text || '(attachments)');

  if (mode === 'a2a') {
    await sendA2AStream(text, sid);
  } else {
    await sendChannelSingleStream(text, sid, fileIds, selectedModel !== 'system' ? selectedModel : null);
  }
}

function syncLastEventIdFromHandles() {
  if (activeChatHandle && activeChatHandle.lastEventId > lastEventId) lastEventId = activeChatHandle.lastEventId;
  if (activeSubHandle && activeSubHandle.lastEventId > lastEventId) lastEventId = activeSubHandle.lastEventId;
}

async function sendChannelSingleStream(text, sid, fileIds, model) {
  enterStreamingUi();
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
    waitingEl.textContent = '排队等待中...（已等待 ' + secs + 's）';
  };
  const hideWaiting = () => { if (waitingEl) { waitingEl.remove(); waitingEl = null; } };
  const finishStream = () => {
    hideWaiting();
    if (currentReply) {
      renderer.finishReply(currentReply.replyId);
      currentReply = null;
    }
    resetStreamingState();
    updateHeaderTitle(false);
    loadThreads();
  };

  const subscribeWithReconnect = (sessionId, afterSeq, replyId) => {
    if (activeSubHandle) { try { activeSubHandle.close(); } catch (e) {} }
    activeSubHandle = transport().subscribe(sessionId, {
      afterSeq,
      replyId,
      userId: uid(),
      onEvent: (evt) => {
        reconnectAttempts = 0;
        syncLastEventIdFromHandles();
        hideWaiting();
        handleEvent(evt);
      },
      onError: () => startReconnect(),
      onEnd: () => finishStream()
    });
  };

  const startReconnect = () => {
    syncLastEventIdFromHandles();
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      handleEvent({ type: 'error', error: '连接已断开，重连次数已达上限，请刷新页面' });
      finishStream();
      return;
    }
    const delay = Math.min(RECONNECT_BASE_DELAY * Math.pow(2, reconnectAttempts), 30000);
    reconnectAttempts++;
    setTimeout(() => {
      ctx.api.getStatus(sid, uid()).then((status) => {
        if (status.state === 'working' || status.state === 'waiting_confirm' || status.state === 'interrupted') {
          currentReplyId = status.reply_id || currentReplyId;
          if (status.stopping) enterStoppingUi();
          subscribeWithReconnect(sid, lastEventId, currentReplyId);
        } else if (status.state === 'failed') {
          handleEvent({ type: 'error', error: status.error || 'turn failed' });
          finishStream();
        } else {
          finishStream();
        }
      }).catch(() => subscribeWithReconnect(sid, lastEventId, currentReplyId));
    }, delay);
  };

  reconnectAttempts = 0;
  activeChatHandle = transport().sendChat(sid, text, uid(), fileIds, {
    model,
    onWaiting: () => {
      if (!waitingSince) waitingSince = Date.now();
      showWaiting();
    },
    onEvent: (evt) => {
      reconnectAttempts = 0;
      syncLastEventIdFromHandles();
      hideWaiting();
      handleEvent(evt);
    },
    onError: (e) => {
      hideWaiting();
      handleEvent({ type: 'error', error: e.message || String(e) });
      finishStream();
    },
    onEnd: () => finishStream()
  });
}

async function sendA2AStream(text, sid) {
  enterStreamingUi();
  try {
    const reply = await ctx.api.sendA2A(text, { userId: uid(), sessionId: sid });
    renderer.addMessage('assistant', reply || '(empty response)');
  } catch (e) {
    handleEvent({ type: 'error', error: e.message || String(e) });
  } finally {
    resetStreamingState();
    loadThreads();
  }
}

function releaseBrowserAndStream(sid, onDone) {
  if (isStreaming) {
    ctx.utils.toast('请等待当前回复结束后再交还控制权', 'warn');
    return;
  }
  enterStreamingUi();
  activeChatHandle = ctx.api.releaseBrowserStream(sid, uid(), {
    onEvent: (evt) => handleEvent(evt),
    onError: (e) => {
      handleEvent({ type: 'error', error: e.message || String(e) });
      resetStreamingState();
      if (onDone) onDone();
    },
    onEnd: () => {
      if (currentReply) {
        renderer.finishReply(currentReply.replyId);
        currentReply = null;
      }
      resetStreamingState();
      updateHeaderTitle(false);
      if (onDone) onDone();
    }
  });
}

async function submitConfirm(calls, approved) {
  const sid = currentSessionId();
  if (!sid) return;
  const results = calls.map((c) => ({
    tool_call_id: c.tool_call_id || c.id,
    confirmed: !!approved,
    accept_rule: false
  }));
  enterStreamingUi();
  currentReply = renderer.ensureReply('confirm-' + Date.now());
  try {
    await new Promise((resolve, reject) => {
      let settled = false;
      const done = (err) => {
        if (settled) return;
        settled = true;
        if (err) reject(err); else resolve();
      };
      activeSubHandle = ctx.api.confirmStream(sid, results, {
        userId: uid(),
        onEvent: (evt) => {
          if (evt.type === 'done') { done(); return; }
          if (evt.type === 'error') { handleEvent(evt); done(new Error(evt.error || 'confirm error')); return; }
          handleEvent(evt);
          if (evt.type === 'AGENT_END') done();
        },
        onError: (e) => done(e)
      });
    });
  } catch (e) {
    ctx.utils.toast('确认提交失败: ' + e.message, 'error');
  } finally {
    renderer.dismissConfirmCard();
    resetStreamingState();
    loadThreads();
  }
}

function stop() {
  const sid = currentSessionId();
  const mode = ctx.state.getState('ui.streamMode');
  const protocol = ctx.state.getState('ui.protocol');
  const stopBtn = composer.getStopBtn();
  if (isStreaming && sid && mode === 'channel' && protocol !== 'agui') {
    if (!stopBtn) { localStop(); return; }
    if (stopBtn.dataset.stopping === '1') return;
    stopBtn.dataset.stopping = '1';
    stopBtn.textContent = '停止中...';
    stopBtn.disabled = true;
    requestServerStop(sid, uid()).then(() => {
      clearTimeout(stopSlowTimer);
      stopSlowTimer = setTimeout(() => {
        if (isStreaming) ctx.utils.toast('已受理停止请求，正在等待运行中的工具返回', 'warn');
      }, 10000);
    }).catch((err) => {
      if (err && err.status === 409 && !isRetryableStopError(err)) {
        showStopButton();
        flushStoppingBuffer();
        ctx.utils.toast(err.message || '当前状态无法停止，请先处理待确认项', 'warn');
        return;
      }
      delete stopBtn.dataset.stopping;
      stoppingBuffer = [];
      if (!err || err.status !== 404) {
        ctx.utils.toast('停止请求失败: ' + ((err && err.message) || '未知错误'), 'warn');
      }
      localStop();
    });
    return;
  }
  localStop();
}

function isRetryableStopError(e) {
  if (!e || e.status !== 409) return false;
  if (e.retryable === true || (e.data && e.data.retryable === true)) return true;
  return String(e.message || '').includes('turn_not_ready');
}

async function requestServerStop(sid, userId, attempt = 0) {
  try {
    await ctx.api.stopThread(sid, userId);
  } catch (e) {
    if (isRetryableStopError(e) && attempt < 2) {
      await new Promise((r) => setTimeout(r, 400));
      return requestServerStop(sid, userId, attempt + 1);
    }
    throw e;
  }
}

function localStop() {
  if (activeChatHandle) { try { activeChatHandle.close(); } catch (e) {} }
  if (activeSubHandle) { try { activeSubHandle.close(); } catch (e) {} }
  if (activeAbort) { try { activeAbort.abort(); } catch (e) {} activeAbort = null; }
  resetStreamingState();
}

async function showLlmCalls() {
  const sid = currentSessionId();
  if (!sid) return;
  try {
    const data = await ctx.api.getLlmCalls(sid, uid());
    const calls = Array.isArray(data) ? data : (data.calls || []);
    if (calls.length === 0) {
      ctx.modal.open('<h2>LLM 调用记录</h2><div class="empty">当前会话暂无 LLM 调用记录</div>');
      return;
    }
    const html = calls.map((c, idx) => {
      const u = c.usage || {};
      return '<div class="llm-call-entry" id="llm-entry-' + idx + '">' +
        '<div class="llm-call-header" onclick="window.App.toggleLlmCall(' + idx + ')">' +
          '<span class="lc-index">#' + (idx + 1) + '</span>' +
          '<span class="lc-model">' + ctx.utils.esc(c.model || '') + '</span>' +
          '<span class="lc-usage">' + (u.input_tokens || 0) + '↓ ' + (u.output_tokens || 0) + '↑</span>' +
          '<span class="lc-toggle">▾</span>' +
        '</div>' +
        '<div class="llm-call-body"><pre class="lc-pre">' + ctx.utils.esc(JSON.stringify(c, null, 2)) + '</pre></div>' +
      '</div>';
    }).join('');
    ctx.modal.open('<h2>LLM 调用记录 (' + calls.length + ')</h2>' + html);
  } catch (e) {
    ctx.utils.toast('加载 LLM 调用记录失败: ' + e.message, 'error');
  }
}

async function showSystemPrompt() {
  try {
    const data = await ctx.api.getSystemPrompt();
    const text = typeof data === 'string' ? data : (data.system_prompt || data.prompt || JSON.stringify(data, null, 2));
    ctx.utils.showTextModal('系统提示词', text);
  } catch (e) {
    ctx.utils.toast('加载系统提示词失败: ' + e.message, 'error');
  }
}

async function showAgentCard() {
  try {
    const data = await ctx.api.getAgentCard();
    ctx.utils.showJsonModal('Agent 卡片', data);
  } catch (e) {
    ctx.utils.toast('加载 Agent 卡片失败: ' + e.message, 'error');
  }
}
