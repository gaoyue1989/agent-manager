/* ===== Chat 模块：A2A / Channel 双模式对话 ===== */

let ctx = null;
let messagesEl = null;
let inputEl = null;
let sendBtn = null;
let isStreaming = false;
let abortController = null;
let streamingDiv = null;
let fullText = '';
let pendingToolCalls = {};
let mcpAppsRegistry = {};
let refreshTimer = null;
let thinkingDiv = null;
let thinkingText = '';
let toolResultBuffers = {};

function render() {
  return `
  <div class="chat-module">
    <div class="module-header">
      <h2>Chat</h2>
      <select id="threadSelect" class="input" style="max-width:320px">
        <option value="">-- new thread --</option>
      </select>
      <button id="btnNewThread" class="btn small">+ New</button>
      <button id="btnRefreshThreads" class="btn small">Refresh</button>
      <div class="seg" style="margin-left:auto">
        <button id="modeA2A" class="active">A2A</button>
        <button id="modeChannel">Channel</button>
      </div>
      <button id="btnLlmCalls" class="btn small" disabled>LLM Calls</button>
      <button id="btnSysPrompt" class="btn small">System Prompt</button>
      <button id="btnCard" class="btn small">Card</button>
    </div>
    <div class="chat-messages" id="chatMessages">
      <div class="msg system">Agent Debug Console — A2A / Channel</div>
    </div>
    <div class="chat-input">
      <textarea id="chatInput" placeholder="Type your message... (Enter to send, Shift+Enter for newline)"></textarea>
      <button id="sendBtn" class="btn primary">Send</button>
    </div>
  </div>`;
}

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = render();
    messagesEl = document.getElementById('chatMessages');
    inputEl = document.getElementById('chatInput');
    sendBtn = document.getElementById('sendBtn');

    bindEvents();
    loadThreads();
    refreshTimer = setInterval(loadThreads, 30000);
  },

  unmount() {
    if (refreshTimer) clearInterval(refreshTimer);
    if (abortController) abortController.abort();
    isStreaming = false;
  }
};

function bindEvents() {
  document.getElementById('sendBtn').addEventListener('click', () => sendMessage());
  document.getElementById('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });
  document.getElementById('btnNewThread').addEventListener('click', newThread);
  document.getElementById('btnRefreshThreads').addEventListener('click', () => loadThreads(true));
  document.getElementById('threadSelect').addEventListener('change', (e) => selectThread(e.target.value));
  document.getElementById('btnLlmCalls').addEventListener('click', showLlmCalls);
  document.getElementById('btnSysPrompt').addEventListener('click', showSystemPrompt);
  document.getElementById('btnCard').addEventListener('click', showAgentCard);

  document.getElementById('modeA2A').addEventListener('click', () => setStreamMode('a2a'));
  document.getElementById('modeChannel').addEventListener('click', () => setStreamMode('channel'));
}

function setStreamMode(mode) {
  ctx.state.setState('ui.streamMode', mode);
  const a2aBtn = document.getElementById('modeA2A');
  const chBtn = document.getElementById('modeChannel');
  a2aBtn.classList.toggle('active', mode === 'a2a');
  chBtn.classList.toggle('active', mode === 'channel');
}

function currentSessionId() {
  return ctx.state.getState('threads.current');
}

// ---------- Threads ----------

async function loadThreads(force) {
  try {
    const threads = await ctx.api.getThreads();
    ctx.state.setState('threads.list', threads);
    const select = document.getElementById('threadSelect');
    const current = currentSessionId();
    select.innerHTML = '<option value="">-- new thread --</option>' +
      threads.map((t) =>
        '<option value="' + ctx.utils.esc(t.session_id) + '"' + (t.session_id === current ? ' selected' : '') + '>' +
        ctx.utils.esc(t.thread_id) + (t.updated_at ? ' (' + t.updated_at.substring(0, 16) + ')' : '') +
        '</option>').join('');
    const btn = document.getElementById('btnLlmCalls');
    if (btn) btn.disabled = !current;
  } catch (e) {
    if (force) ctx.utils.toast('Failed to load threads: ' + e.message, 'error');
  }
}

function selectThread(sessionId) {
  if (!sessionId) return;
  ctx.state.setState('threads.current', sessionId);
  document.getElementById('btnLlmCalls').disabled = false;
  loadThreadHistory(sessionId);
}

function newThread() {
  ctx.state.setState('threads.current', null);
  pendingToolCalls = {};
  mcpAppsRegistry = {};
  messagesEl.innerHTML = '<div class="msg system">New thread started</div>';
  document.getElementById('threadSelect').value = '';
  document.getElementById('btnLlmCalls').disabled = true;
}

async function loadThreadHistory(sessionId) {
  try {
    const data = await ctx.api.getThreadHistory(sessionId);
    messagesEl.innerHTML = '';
    pendingToolCalls = {};
    const msgs = data.messages || [];
    if (msgs.length === 0) {
      messagesEl.innerHTML = '<div class="msg system">No messages recovered for this thread (state format unknown)</div>';
      return;
    }
    for (const m of msgs) {
      if (m.role === 'user') addMessage('user', m.content || '');
      else if (m.role === 'assistant' || m.role === 'agent') addMessage('assistant', m.content || '');
    }
  } catch (e) {
    messagesEl.innerHTML = '<div class="msg system">Failed to load history</div>';
  }
}

// ---------- 消息渲染 ----------

function addMessage(role, content) {
  const div = document.createElement('div');
  div.className = 'msg ' + role;
  div.innerHTML = ctx.utils.formatMarkdown(content);
  messagesEl.appendChild(div);
  ctx.utils.scrollBottom(messagesEl);
  return div;
}

function addSystemMsg(text) {
  const div = document.createElement('div');
  div.className = 'msg system';
  div.textContent = text;
  messagesEl.appendChild(div);
  ctx.utils.scrollBottom(messagesEl);
}

function createStreamingMsg() {
  const div = document.createElement('div');
  div.className = 'msg assistant';
  div.innerHTML = '<div class="typing-indicator"><span></span><span></span><span></span></div>';
  messagesEl.appendChild(div);
  ctx.utils.scrollBottom(messagesEl);
  return div;
}

function updateStreamContent(text) {
  if (!streamingDiv) return;
  let html = ctx.utils.formatMarkdown(text);
  html += '<span class="cursor"></span>';
  streamingDiv.innerHTML = html;
  ctx.utils.scrollBottom(messagesEl);
}

function renderToolCallBlock(tcName, tcArgs, tcResult, tcId, collapsed, uiMeta) {
  const argsStr = tcArgs ? JSON.stringify(tcArgs, null, 2) : '';
  const resultStr = tcResult || '';
  const bodyClass = collapsed ? '' : 'open';
  const toggleClass = collapsed ? '' : 'open';
  const arrow = collapsed ? '▼' : '▲';
  const uiBadge = uiMeta
    ? '<span class="badge green" style="margin-left:8px">MCP App</span>' : '';
  return '<div class="tool-call-block" data-tcid="' + ctx.utils.esc(tcId || '') + '">' +
    '<div class="tool-call-header" onclick="window.App.toolToggle(this)">' +
    '<span class="tc-icon">🔧</span><span class="tc-name">' + ctx.utils.esc(tcName) + '</span>' +
    uiBadge +
    '<span class="tc-toggle ' + toggleClass + '">' + arrow + '</span></div>' +
    '<div class="tool-call-body ' + bodyClass + '">' +
    (argsStr ? '<div class="tc-args"><div class="tc-label">Arguments</div><pre>' + ctx.utils.esc(argsStr) + '</pre></div>' : '') +
    (resultStr ? '<div class="tc-result"><div class="tc-label" style="margin-top:8px">Result</div><pre>' + ctx.utils.esc(resultStr) + '</pre></div>' : '') +
    '</div></div>';
}

function toggleToolCall(headerEl) {
  const body = headerEl.nextElementSibling;
  const toggle = headerEl.querySelector('.tc-toggle');
  body.classList.toggle('open');
  toggle.classList.toggle('open');
  toggle.textContent = body.classList.contains('open') ? '▲' : '▼';
}

function addToolCallMessage(name, args, content, tcId, collapsed, uiMeta) {
  const div = document.createElement('div');
  div.className = 'msg assistant';
  div.innerHTML = renderToolCallBlock(name, args, null, tcId, collapsed, uiMeta);
  messagesEl.appendChild(div);
  ctx.utils.scrollBottom(messagesEl);
  return div;
}

function addToolResultMessage(name, result, tcId, collapsed) {
  const block = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(tcId || '') + '"]');
  if (block) {
    const body = block.querySelector('.tool-call-body');
    if (body && !body.querySelector('.tc-result')) {
      body.innerHTML += '<div class="tc-label" style="margin-top:8px">Result</div><pre>' + ctx.utils.esc(result || '') + '</pre>';
      body.classList.add('open');
      const toggle = block.querySelector('.tc-toggle');
      toggle.classList.add('open');
      toggle.textContent = '▲';
    }
  } else {
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.innerHTML = '<div class="tool-result-inline"><div class="tr-label">Tool Result: ' + ctx.utils.esc(name) + '</div><pre>' + ctx.utils.esc(result || '') + '</pre></div>';
    messagesEl.appendChild(div);
  }
  ctx.utils.scrollBottom(messagesEl);
}

function addUsageStats(usage, elapsed) {
  const div = document.createElement('div');
  div.className = 'usage-stats';
  let html = '';
  if (usage) {
    const inputTokens = usage.input_tokens || 0;
    const outputTokens = usage.output_tokens || 0;
    const totalTokens = usage.total_tokens || (inputTokens + outputTokens);
    html += '<div class="stat-item"><span class="stat-label">输入:</span><span class="stat-value tokens-in">' + inputTokens + '</span></div>';
    html += '<div class="stat-item"><span class="stat-label">输出:</span><span class="stat-value tokens-out">' + outputTokens + '</span></div>';
    html += '<div class="stat-item"><span class="stat-label">总计:</span><span class="stat-value">' + totalTokens + '</span></div>';
  }
  if (elapsed) {
    html += '<div class="stat-item"><span class="stat-label">耗时:</span><span class="stat-value time">' + elapsed + 's</span></div>';
  }
  div.innerHTML = html;
  messagesEl.appendChild(div);
  ctx.utils.scrollBottom(messagesEl);
}

// ---------- 思维链渲染 ----------

function createThinkingBlock() {
  const div = document.createElement('div');
  div.className = 'msg thinking';
  div.innerHTML = '<div class="thinking-header" onclick="window.App.thinkingToggle(this)">' +
    '<span class="thinking-icon">🧠</span>' +
    '<span class="thinking-label">Thinking...</span>' +
    '<span class="thinking-toggle">▼</span></div>' +
    '<div class="thinking-body open"><pre class="thinking-content"></pre></div>';
  messagesEl.appendChild(div);
  ctx.utils.scrollBottom(messagesEl);
  return div;
}

function appendThinkingDelta(delta) {
  if (!thinkingDiv) {
    thinkingDiv = createThinkingBlock();
    thinkingText = '';
  }
  thinkingText += delta;
  const content = thinkingDiv.querySelector('.thinking-content');
  if (content) content.textContent = thinkingText;
  ctx.utils.scrollBottom(messagesEl);
}

function finishThinkingBlock() {
  if (thinkingDiv) {
    const label = thinkingDiv.querySelector('.thinking-label');
    if (label) label.textContent = 'Thinking (' + thinkingText.length + ' chars)';
    const body = thinkingDiv.querySelector('.thinking-body');
    const toggle = thinkingDiv.querySelector('.thinking-toggle');
    if (body) body.classList.remove('open');
    if (toggle) { toggle.classList.remove('open'); toggle.textContent = '▼'; }
  }
  thinkingDiv = null;
  thinkingText = '';
}

// ---------- 工具参数渐进渲染 ----------

function handleToolCallDelta(toolCallId, delta) {
  const block = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(toolCallId || '') + '"]');
  if (!block) return;
  if (!pendingToolCalls[toolCallId]) pendingToolCalls[toolCallId] = { argsRaw: '' };
  if (!pendingToolCalls[toolCallId].argsRaw) pendingToolCalls[toolCallId].argsRaw = '';
  pendingToolCalls[toolCallId].argsRaw += delta;
  const argsPre = block.querySelector('.tc-args pre');
  if (argsPre) {
    try {
      const parsed = JSON.parse(pendingToolCalls[toolCallId].argsRaw);
      argsPre.textContent = JSON.stringify(parsed, null, 2);
    } catch {
      argsPre.textContent = pendingToolCalls[toolCallId].argsRaw;
    }
  }
}

function handleToolCallEnd(toolCallId) {
  const tc = pendingToolCalls[toolCallId];
  if (tc && tc.argsRaw) {
    try {
      tc.args = JSON.parse(tc.argsRaw);
    } catch { /* 保留原始文本 */ }
  }
}

// ---------- 工具结果渐进渲染 ----------

function handleToolResultStart(toolCallId, toolCallName) {
  toolResultBuffers[toolCallId] = { name: toolCallName, text: '' };
  const block = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(toolCallId || '') + '"]');
  if (block) {
    const body = block.querySelector('.tool-call-body');
    if (body && !body.querySelector('.tc-result')) {
      body.innerHTML += '<div class="tc-result"><div class="tc-label" style="margin-top:8px">Result</div>' +
        '<pre class="tc-result-content"></pre></div>';
      body.classList.add('open');
      const toggle = block.querySelector('.tc-toggle');
      if (toggle) { toggle.classList.add('open'); toggle.textContent = '▲'; }
    }
  }
}

function handleToolResultTextDelta(toolCallId, delta) {
  const buf = toolResultBuffers[toolCallId];
  if (buf) buf.text += delta;
  const block = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(toolCallId || '') + '"]');
  if (block) {
    const resultPre = block.querySelector('.tc-result-content');
    if (resultPre) resultPre.textContent = buf ? buf.text : delta;
  }
  ctx.utils.scrollBottom(messagesEl);
}

function cleanupToolResultBuffer(toolCallId) {
  delete toolResultBuffers[toolCallId];
}

// ---------- 发送 ----------

async function sendMessage() {
  if (isStreaming) {
    if (abortController) abortController.abort();
    return;
  }
  const text = inputEl.value.trim();
  if (!text) return;
  inputEl.value = '';
  inputEl.focus();
  addMessage('user', text);

  isStreaming = true;
  sendBtn.disabled = true;
  sendBtn.textContent = 'Stop';
  sendBtn.classList.add('danger');

  // 新会话（未选择历史）发送消息时自动生成 sessionId，使 LLM Calls 可通过 metadata.sessionId 关联
  if (!currentSessionId()) {
    const sid = 'debug-user:' + Date.now().toString(36);
    ctx.state.setState('threads.current', sid);
    document.getElementById('btnLlmCalls').disabled = false;
  }

  streamingDiv = createStreamingMsg();
  abortController = new AbortController();
  fullText = '';
  pendingToolCalls = {};
  thinkingDiv = null;
  thinkingText = '';
  toolResultBuffers = {};

  const mode = ctx.state.getState('ui.streamMode');
  try {
    if (mode === 'channel') await sendChannelSSE(text);
    else await sendA2AStream(text);
  } finally {
    isStreaming = false;
    sendBtn.disabled = false;
    sendBtn.textContent = 'Send';
    sendBtn.classList.remove('danger');
    streamingDiv = null;
    abortController = null;
    loadThreads();
  }
}

async function sendChannelSSE(text) {
  try {
    const params = new URLSearchParams({ message: text, userId: 'debug-user' });
    const sid = currentSessionId();
    if (sid) params.set('sessionId', sid);
    const resp = await fetch(ctx.api.BASE + '/chat/stream?' + params.toString(), { signal: abortController.signal });
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
          const data = JSON.parse(dataStr);
          if (data.type === 'error') {
            if (streamingDiv) {
              streamingDiv.innerHTML += '<span style="color:var(--red)">Error: ' + ctx.utils.esc(data.error || 'Unknown error') + '</span>';
            }
            return;
          }
          if (data.type === 'TEXT_BLOCK_DELTA' && data.delta && streamingDiv) {
            fullText += data.delta;
            updateStreamContent(fullText);
          } else if (data.type === 'THINKING_BLOCK_DELTA' && data.delta) {
            appendThinkingDelta(data.delta);
          } else if (data.type === 'THINKING_BLOCK_END') {
            finishThinkingBlock();
          } else if (data.type === 'TOOL_CALL_START') {
            handleToolCallEvent({
              type: 'tool_call',
              name: data.toolName,
              tool_call_id: data.toolCallId
            });
          } else if (data.type === 'TOOL_CALL_DELTA' && data.delta) {
            handleToolCallDelta(data.toolCallId, data.delta);
          } else if (data.type === 'TOOL_CALL_END') {
            handleToolCallEnd(data.toolCallId);
          } else if (data.type === 'TOOL_RESULT_START') {
            handleToolResultStart(data.toolCallId, data.toolCallName);
          } else if (data.type === 'TOOL_RESULT_TEXT_DELTA' && data.delta) {
            handleToolResultTextDelta(data.toolCallId, data.delta);
          } else if (data.type === 'TOOL_RESULT_END') {
            handleToolResultEvent({
              type: 'tool_result',
              tool_call_id: data.toolCallId,
              state: data.state
            });
          } else if (data.type === 'MODEL_CALL_END') {
            addUsageStats({
              input_tokens: data.inputTokens,
              output_tokens: data.outputTokens,
              total_tokens: data.totalTokens
            }, null);
          }
        } catch (e) { /* 忽略解析错误 */ }
      }
    }
    if (streamingDiv && fullText) updateStreamContent(fullText);
  } catch (e) {
    if (e.name !== 'AbortError' && streamingDiv) {
      streamingDiv.innerHTML += '<span style="color:var(--red)">Error: ' + ctx.utils.esc(e.message) + '</span>';
    }
  }
}

async function sendA2AStream(text) {
  const metadata = {};
  const sid = currentSessionId();
  if (sid) metadata.sessionId = sid;
  metadata.userId = 'debug-user';
  try {
    const resp = await fetch(ctx.api.BASE + '/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        method: 'message/stream',
        params: { message: { role: 'user', parts: [{ text }], metadata } },
        id: 'stream-' + Date.now()
      }),
      signal: abortController.signal
    });
    if (!resp.ok || !resp.body) throw new Error('HTTP ' + resp.status);
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let lastArtifactText = '';   // 累积当前 artifact 的文本（append 语义）
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      for (const line of lines) {
        if (line.startsWith('event:')) continue;
        if (!line.startsWith('data:')) continue;
        const dataStr = line.slice(5);
        if (!dataStr || dataStr === '[DONE]') continue;
        try {
          const frame = JSON.parse(dataStr);
          const result = frame.result;
          if (!result) {
            if (frame.error) {
              if (streamingDiv) {
                streamingDiv.innerHTML += '<span style="color:var(--red)">Error: ' + ctx.utils.esc(frame.error.message || 'A2A error') + '</span>';
              }
              return;
            }
            continue;
          }
          const kind = result.kind;

          // artifact-update: 流式增量（thinking/text 块 + 工具调用）
          if (kind === 'artifact-update') {
            const artifact = result.artifact || {};
            const parts = artifact.parts || [];
            for (const p of parts) {
              const blockType = (p.metadata && p.metadata._agentscope_block_type) || 'text';
              // A2A data part：工具调用（metadata._agentscope_tool_name / _agentscope_tool_call_id）
              if (p.kind === 'data') {
                const toolName = (p.metadata && p.metadata._agentscope_tool_name) || '';
                const tcId = (p.metadata && p.metadata._agentscope_tool_call_id) || '';
                if (toolName && toolName !== '__fragment__' && tcId && !pendingToolCalls[tcId]) {
                  pendingToolCalls[tcId] = { name: toolName, args: {}, uiMeta: null };
                  if (streamingDiv) {
                    const cursor = streamingDiv.querySelector('.cursor');
                    if (cursor) cursor.remove();
                    if (fullText) updateStreamContent(fullText);
                  }
                  addToolCallMessage(toolName, {}, null, tcId, false, null);
                  streamingDiv = null;
                  // A2A 流不携带工具参数（SDK 限制），从会话历史按 tool_call_id 匹配填充
                  fillToolArgsFromHistory(tcId, sid);
                }
                continue;
              }
              if (p.kind !== 'text') continue;
              const text = p.text || '';
              // append=false = 新块开始（thinking 或 text），重置累积器
              if (result.append === false) {
                lastArtifactText = '';
                fullText = '';
              }
              lastArtifactText += text;
              if (blockType === 'thinking') {
                appendThinkingDelta(text);
              } else if (streamingDiv) {
                // 直接追加增量（append=true 时 text 是增量）
                fullText += text;
                updateStreamContent(fullText);
              }
            }
          }
          // status-update: 状态变化，final=true 表示完成
          else if (kind === 'status-update') {
            const state = result.state;
            if (result.metadata && result.metadata.thread_id && !currentSessionId()) {
              ctx.state.setState('threads.current', result.metadata.thread_id);
              document.getElementById('btnLlmCalls').disabled = false;
            }
            if (result.final === true || state === 'completed') {
              finishThinkingBlock();
              if (streamingDiv) {
                const cursor = streamingDiv.querySelector('.cursor');
                if (cursor) cursor.remove();
                if (fullText) updateStreamContent(fullText);
              }
              return;
            }
          }
          // message: 最终完整消息（final 兜底）
          else if (kind === 'message') {
            const parts = result.parts || [];
            const texts = parts
              .filter((p) => p.kind === 'text' && !(p.metadata && p.metadata._agentscope_block_type === 'thinking'))
              .map((p) => p.text || '');
            if (texts.length) {
              fullText = texts.join('');
              if (streamingDiv) updateStreamContent(fullText);
            }
            if (result.metadata) {
              const usage = result.metadata[Object.keys(result.metadata)[0]];
              if (usage && usage._chat_usage) {
                addUsageStats({
                  input_tokens: usage._chat_usage.inputTokens,
                  output_tokens: usage._chat_usage.outputTokens,
                  total_tokens: usage._chat_usage.totalTokens
                }, null);
              }
            }
            finishThinkingBlock();
            if (streamingDiv) {
              const cursor = streamingDiv.querySelector('.cursor');
              if (cursor) cursor.remove();
            }
            return;
          }
        } catch (e) { /* 忽略解析错误 */ }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError' && streamingDiv) {
      streamingDiv.innerHTML = '<span style="color:var(--red)">Error: ' + ctx.utils.esc(e.message) + '</span>';
    }
  }
}

/**
 * A2A 流不携带工具参数（SDK 限制），从会话历史（agent_state）按 tool_call_id 匹配填充。
 * agent_state 在 A2A 流结束后落库，因此延迟重试几次。
 */
async function fillToolArgsFromHistory(tcId, sessionId) {
  if (!tcId || !sessionId) return;
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const data = await ctx.api.getThreadHistory(sessionId);
      const msgs = data.messages || [];
      for (const m of msgs) {
        const calls = m.tool_calls || [];
        for (const c of calls) {
          if (c.id === tcId) {
            const existing = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(tcId) + '"]');
            if (existing) {
              const argsPre = existing.querySelector('.tc-args pre');
              if (argsPre) argsPre.textContent = JSON.stringify(c.input, null, 2);
            }
            if (pendingToolCalls[tcId]) pendingToolCalls[tcId].args = c.input;
            return;
          }
        }
      }
    } catch (e) { /* 历史未就绪时忽略 */ }
    await new Promise(r => setTimeout(r, 1000));
  }
}

function handleToolCallEvent(data) {
  const uiMeta = data._meta?.ui || data.ui_meta || null;
  const existing = messagesEl.querySelector('.tool-call-block[data-tcid="' + ctx.utils.esc(tcId) + '"]');
  if (existing) {
    pendingToolCalls[tcId] = { name: data.name || (pendingToolCalls[tcId] && pendingToolCalls[tcId].name), args: data.args, uiMeta };
    const argsPre = existing.querySelector('.tc-args pre');
    if (argsPre) argsPre.textContent = JSON.stringify(data.args, null, 2);
  } else {
    pendingToolCalls[tcId] = { name: data.name, args: data.args, uiMeta };
    if (streamingDiv) {
      const cursor = streamingDiv.querySelector('.cursor');
      if (cursor) cursor.remove();
      if (fullText) updateStreamContent(fullText);
    }
    addToolCallMessage(data.name, data.args, null, tcId, false, uiMeta);
    streamingDiv = null;
    if (uiMeta && uiMeta.resourceUri) {
      mcpAppsRegistry[tcId] = { uri: uiMeta.resourceUri, name: data.name, args: data.args };
    }
  }
}

async function handleToolResultEvent(data) {
  const tc = pendingToolCalls[data.tool_call_id] || { name: data.name, args: {}, uiMeta: null };
  // 优先使用流式累积的工具结果文本（tool_result_text_delta），无增量时回退 data.result
  const buffered = toolResultBuffers[data.tool_call_id];
  let resultText = (buffered && buffered.text) ? buffered.text : data.result;
  if (Array.isArray(resultText)) {
    resultText = resultText.map((r) => r.text || '').join('\n');
  } else if (resultText && typeof resultText === 'object') {
    resultText = JSON.stringify(resultText, null, 2);
  }
  cleanupToolResultBuffer(data.tool_call_id);
  const appInfo = mcpAppsRegistry[data.tool_call_id];
  if (appInfo && appInfo.uri) {
    try {
      const argsData = JSON.parse(resultText);
      await renderMcpAppFrame(appInfo.uri, tc.name, argsData, data.tool_call_id);
    } catch (e) {
      addToolResultMessage(tc.name, resultText, data.tool_call_id, false);
    }
  } else {
    addToolResultMessage(tc.name, resultText, data.tool_call_id, false);
  }
  if (!streamingDiv) {
    streamingDiv = createStreamingMsg();
    fullText = '';
  }
}

// ---------- MCP App iframe 渲染 ----------

async function fetchMcpResource(server, uri) {
  try {
    const data = await ctx.api.readMcpResource(server, uri);
    if (data.contents && data.contents[0]) return data.contents[0];
    return null;
  } catch (e) {
    return null;
  }
}

async function renderMcpAppFrame(uiUri, toolName, toolArgs, tcId) {
  const parts = uiUri.split('/');
  const server = parts[2] || 'weather';
  const resource = await fetchMcpResource(server, uiUri);
  if (!resource || !resource.text) return null;
  const html = resource.text;
  const containerId = 'mcp-app-' + tcId;
  const container = document.createElement('div');
  container.className = 'msg assistant';
  container.innerHTML = '<div class="mcp-apps-container"><div class="mcp-apps-header">' +
    '<span class="app-icon">🎨</span><span class="app-label">' + ctx.utils.esc(toolName) + '</span>' +
    '<span class="app-uri">' + ctx.utils.esc(uiUri) + '</span></div>' +
    '<iframe id="' + containerId + '" class="mcp-apps-iframe" sandbox="allow-scripts"></iframe></div>';
  messagesEl.appendChild(container);
  ctx.utils.scrollBottom(messagesEl);
  const iframe = document.getElementById(containerId);
  iframe.onload = () => {
    iframe.contentWindow.postMessage(
      { jsonrpc: '2.0', method: 'ui/notifications/tool-input', params: { arguments: toolArgs } },
      '*');
  };
  iframe.srcdoc = html;
  return container;
}

// ---------- 弹窗功能 ----------

function formatDuration(ms) {
  if (ms < 1000) return ms + 'ms';
  const s = (ms / 1000).toFixed(2);
  return s + 's';
}

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
        html += '<span class="lc-usage">⏱ ' + formatDuration(c.response.duration_ms) + '</span>';
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
