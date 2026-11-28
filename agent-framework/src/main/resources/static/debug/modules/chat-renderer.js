import { McpAppHost, buildToolResult } from '../js/mcp-app-host.js';
import { icon } from '../js/icons.js';

const RENDERERS = {
  bash: 'bash', shell: 'bash', exec_command: 'bash',
  read_file: 'read', read: 'read',
  write_file: 'write', write: 'write',
  edit_file: 'edit', edit: 'edit',
  glob_files: 'glob', glob: 'glob',
  grep_files: 'grep', grep: 'grep'
};

export function createChatRenderer({ ctx, getMessagesEl, scrollToBottom, getCurrentSessionId, getUserId, onSubmitConfirm }) {
  let replyMap = {};
  let pendingToolCalls = {};
  let remotePanels = {};
  let activeRemotePanel = null;
  let lastRemotePanel = null;
  const remoteToolOwner = {};
  let pendingConfirm = null;
  const appHosts = {};

  function resetAll() {
    teardownAllAppHosts();
    for (const r of Object.values(replyMap)) {
      if (r.footerTimer) clearInterval(r.footerTimer);
    }
    replyMap = {};
    pendingToolCalls = {};
    remotePanels = {};
    activeRemotePanel = null;
    lastRemotePanel = null;
    Object.keys(remoteToolOwner).forEach((k) => delete remoteToolOwner[k]);
    pendingConfirm = null;
  }

  function resetTurnRemote() {
    remotePanels = {};
    activeRemotePanel = null;
    Object.keys(remoteToolOwner).forEach((k) => delete remoteToolOwner[k]);
    teardownAllAppHosts();
  }

  function renderMarkdown(text) {
    if (text == null) return '';
    const raw = window.marked.parse(String(text), { gfm: true, breaks: true });
    return window.DOMPurify.sanitize(raw, { ADD_ATTR: ['class', 'target', 'rel'] });
  }

  function enhanceCodeBlocks(container) {
    container.querySelectorAll('pre').forEach((pre) => {
      if (pre.querySelector('.code-copy-btn')) return;
      const code = pre.querySelector('code');
      if (code && window.hljs) {
        try { window.hljs.highlightElement(code); } catch (e) {}
      }
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'code-copy-btn';
      btn.innerHTML = icon('copy', 12) + '<span>复制</span>';
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const text = code ? code.textContent : pre.textContent;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text || '').then(() => {
            btn.innerHTML = icon('check', 12) + '<span>已复制</span>';
            setTimeout(() => { btn.innerHTML = icon('copy', 12) + '<span>复制</span>'; }, 1500);
          }).catch(() => {});
        }
      });
      pre.appendChild(btn);
    });
  }

  function writeMarkdown(el, html) {
    el.innerHTML = html;
    enhanceCodeBlocks(el);
  }

  function renderStoppedBadge() {
    const el = document.createElement('div');
    el.className = 'msg-stopped';
    el.textContent = '已停止';
    el.title = '本次回复由用户主动停止';
    return el;
  }

  function renderDirectBadge(toolName) {
    const el = document.createElement('div');
    el.className = 'msg-direct-badge';
    el.textContent = toolName ? '直返结果 · ' + toolName : '直返结果';
    return el;
  }

  function addCompactionDivider(summary) {
    const messagesEl = getMessagesEl();
    const el = document.createElement('div');
    el.className = 'msg system';
    const details = document.createElement('details');
    details.className = 'compaction-divider';
    const summaryEl = document.createElement('summary');
    summaryEl.textContent = '上下文已压缩 · 早期消息已归档（点开查看压缩摘要）';
    const body = document.createElement('div');
    body.className = 'compaction-summary';
    body.innerHTML = renderMarkdown(summary || '');
    details.appendChild(summaryEl);
    details.appendChild(body);
    el.appendChild(details);
    messagesEl.appendChild(el);
    scrollToBottom(false);
    return el;
  }

  function addMessage(role, content) {
    const messagesEl = getMessagesEl();
    const msg = document.createElement('div');
    msg.className = 'msg ' + role;
    const bubble = document.createElement('div');
    bubble.className = 'msg-bubble';
    if (role === 'user') {
      let displayContent = ctx.utils.esc(content);
      displayContent = displayContent.replace(/(?<![\w:.\/])\/([\w\u4e00-\u9fff.\-]+)/g,
        '<span class="skill-mention-tag">/$1</span>');
      bubble.innerHTML = displayContent;
    } else {
      writeMarkdown(bubble, renderMarkdown(content));
    }
    msg.appendChild(bubble);
    messagesEl.appendChild(msg);
    scrollToBottom(false);
    return msg;
  }

  function addAssistantHistory(content, toolCalls, blocks, opts = {}) {
    const messagesEl = getMessagesEl();
    const msg = document.createElement('div');
    msg.className = 'msg assistant';
    const callOf = (tc) => ({
      type: 'tool_call',
      name: tc.name,
      summary: tc.summary,
      argsText: tc.input && typeof tc.input === 'object' ? JSON.stringify(tc.input, null, 2) : String(tc.input || ''),
      resultText: null,
      state: 'success',
      directReturn: !!tc.directReturn
    });
    let directBadgeRendered = false;
    const appendBubble = (text) => {
      if (opts && opts.directReturn && !directBadgeRendered) {
        msg.appendChild(renderDirectBadge(opts.toolName));
        directBadgeRendered = true;
      }
      const bubbleEl = document.createElement('div');
      bubbleEl.className = 'msg-bubble';
      writeMarkdown(bubbleEl, renderMarkdown(text));
      msg.appendChild(bubbleEl);
    };
    const appendImage = (b) => {
      const src = b.url
        ? safeFileHref(b.url)
        : (b.data && b.media_type && /^image\/[a-z0-9.+-]+$/i.test(b.media_type)
            ? 'data:' + b.media_type + ';base64,' + b.data
            : null);
      if (!src || src === '#') return;
      if (opts && opts.directReturn && !directBadgeRendered) {
        msg.appendChild(renderDirectBadge(opts.toolName));
        directBadgeRendered = true;
      }
      const wrap = document.createElement('div');
      wrap.className = 'msg-image-block';
      const img = document.createElement('img');
      img.src = src;
      img.alt = b.alt || 'image';
      img.loading = 'lazy';
      wrap.appendChild(img);
      msg.appendChild(wrap);
    };
    if (Array.isArray(blocks) && blocks.length > 0) {
      let group = null;
      const flushGroup = () => {
        if (group && group.length > 0) msg.appendChild(renderToolGroupBlock(group));
        group = null;
      };
      for (const b of blocks) {
        if (b && b.type === 'tool') {
          if (!group) group = [];
          const tc = (toolCalls || []).find((c) => b.id && c.id === b.id) || b;
          group.push(callOf(tc));
        } else if (b && b.type === 'text' && b.text) {
          flushGroup();
          appendBubble(b.text);
        } else if (b && b.type === 'image') {
          flushGroup();
          appendImage(b);
        }
      }
      flushGroup();
    } else {
      if (toolCalls && toolCalls.length > 0) {
        msg.appendChild(renderToolGroupBlock(toolCalls.map(callOf)));
      }
      if (content) appendBubble(content);
    }
    messagesEl.appendChild(msg);
    scrollToBottom(false);
    return { msgEl: msg, contentEl: msg };
  }

  function renderToolGroupBlock(calls) {
    const group = document.createElement('div');
    group.className = 'tool-group';
    const summary = summarizeToolCalls(calls);
    group.innerHTML =
      '<div class="tool-group-header" onclick="window.App.toolGroupToggle(this)">' +
        '<span class="tg-arrow">▸</span> ' + ctx.utils.esc(summary) +
      '</div>' +
      '<div class="tool-group-body">' +
        calls.map((c) => renderToolRow(c)).join('') +
      '</div>';
    return group;
  }

  function summarizeToolCalls(calls) {
    const counts = { read: 0, write: 0, edit: 0, bash: 0, grep: 0, glob: 0, other: 0 };
    for (const c of calls) {
      const kind = RENDERERS[c.name] || 'other';
      counts[kind] = (counts[kind] || 0) + 1;
    }
    const parts = [];
    if (counts.read) parts.push('Read ' + counts.read);
    if (counts.write) parts.push('Write ' + counts.write);
    if (counts.edit) parts.push('Edit ' + counts.edit);
    if (counts.bash) parts.push('Run ' + counts.bash);
    if (counts.grep) parts.push('Search ' + counts.grep);
    if (counts.glob) parts.push('List ' + counts.glob);
    if (counts.other) parts.push(counts.other + ' tool(s)');
    return parts.join(', ') || (calls.length + ' tool(s)');
  }

  function renderToolRow(call) {
    const kind = RENDERERS[call.name] || 'other';
    const running = call.state === 'running';
    const stateCls = call.directReturn
      ? ' state-direct'
      : call.state === 'error' || call.state === 'interrupted' || call.state === 'denied'
        ? ' state-error'
        : call.state === 'success'
          ? ' state-success'
          : '';
    const iconHtml = ctx.utils.toolStateIcon(call.state);
    let titleHtml;
    if (call.summary) {
      titleHtml = '<span class="tc-name">' + ctx.utils.esc(call.summary) + '</span>';
    } else {
      let argPreview = '';
      if (kind === 'read' || kind === 'write' || kind === 'edit') argPreview = extractFilePath(call.argsText);
      else if (kind === 'bash') argPreview = extractCommand(call.argsText);
      else if (kind === 'grep' || kind === 'glob') argPreview = extractPattern(call.argsText);
      else argPreview = truncate(call.argsText, 60);
      titleHtml = '<span class="tc-name">' + ctx.utils.esc(call.name) + '</span>' +
        '<span class="tc-arg">' + ctx.utils.esc(argPreview) + '</span>';
    }
    const previewHtml = call.preview
      ? '<span class="tc-arg" style="margin-left:4px;opacity:.75">' + ctx.utils.esc(call.preview) + '</span>'
      : '';
    const directTag = call.directReturn ? '<span class="badge accent">直返</span>' : '';
    const bodyHtml = renderToolBody(kind, call);
    return '<div class="tool-call-row' + (running ? ' shimmer' : '') + stateCls + '" onclick="window.App.toolRowToggle(this)">' +
      '<span class="tc-state">' + iconHtml + '</span>' +
      titleHtml + previewHtml + directTag +
      '<span class="tc-toggle">▸</span>' +
    '</div>' +
    '<div class="tool-call-body"><div class="tc-inner">' + bodyHtml + '</div></div>';
  }

  function extractFilePath(argsText) {
    try { const o = JSON.parse(argsText); return o.file_path || o.path || o.filename || ''; } catch (e) {}
    return truncate(argsText, 50);
  }
  function extractCommand(argsText) {
    try { const o = JSON.parse(argsText); return o.command || o.cmd || ''; } catch (e) {}
    return truncate(argsText, 50);
  }
  function extractPattern(argsText) {
    try { const o = JSON.parse(argsText); return o.pattern || o.query || o.glob || ''; } catch (e) {}
    return truncate(argsText, 50);
  }
  function truncate(s, n) {
    if (!s) return '';
    return s.length > n ? s.substring(0, n) + '…' : s;
  }

  function renderToolBody(kind, call) {
    if (call.state === 'running') {
      return '<div class="tc-running">执行中...</div>' +
        (call.argsText ? '<div><div class="tc-label">Arguments</div><pre>' + ctx.utils.esc(call.argsText) + '</pre></div>' : '');
    }
    if (kind === 'bash') {
      let cmd = call.argsText;
      try { const o = JSON.parse(call.argsText); if (o.command) cmd = o.command; } catch (e) {}
      return '<div><div class="tc-label">Command</div><pre>$ ' + ctx.utils.esc(cmd) + '</pre></div>' +
        (call.resultText ? '<div><div class="tc-label">Output</div><pre class="tc-bash-output">' + ctx.utils.esc(call.resultText) + '</pre></div>' : '');
    }
    if (kind === 'edit') {
      try {
        const o = JSON.parse(call.argsText);
        if (o.old_string != null && o.new_string != null) {
          return '<div class="diff-view"><pre>' +
            (o.old_string.split('\n').map((l) => '<span class="d-del">- ' + ctx.utils.esc(l) + '</span>').join('\n')) + '\n' +
            (o.new_string.split('\n').map((l) => '<span class="d-add">+ ' + ctx.utils.esc(l) + '</span>').join('\n')) +
            '</pre></div>';
        }
      } catch (e) {}
    }
    if (kind === 'read' && call.resultText) {
      return '<div><div class="tc-label">Content</div><pre>' + ctx.utils.esc(truncate(call.resultText, 2000)) + '</pre></div>';
    }
    return (call.argsText ? '<div><div class="tc-label">Arguments</div><pre>' + ctx.utils.esc(call.argsText) + '</pre></div>' : '') +
      (call.resultText ? '<div><div class="tc-label">Result</div><pre>' + ctx.utils.esc(truncate(call.resultText, 2000)) + '</pre></div>' : '');
  }

  function ensureReply(replyId) {
    if (replyMap[replyId]) return replyMap[replyId];
    const messagesEl = getMessagesEl();
    const greeting = messagesEl.querySelector('.chat-greeting');
    if (greeting) greeting.remove();

    const msgEl = document.createElement('div');
    msgEl.className = 'msg assistant';
    msgEl.dataset.replyId = replyId;

    const contentEl = document.createElement('div');
    contentEl.className = 'msg-bubble-content';
    msgEl.appendChild(contentEl);

    const footerEl = document.createElement('div');
    footerEl.className = 'msg-footer';
    msgEl.appendChild(footerEl);

    messagesEl.appendChild(msgEl);

    const r = {
      replyId,
      msgEl,
      contentEl,
      footerEl,
      startedAt: Date.now(),
      text: '',
      textEl: null,
      directBadgeAdded: false,
      thinkingEl: null,
      thinkingContentEl: null,
      thinkingText: '',
      toolGroups: [],
      currentGroup: null,
      usage: { input: 0, output: 0, cached: 0 },
      modelCalls: 0,
      footerTimer: null,
      finished: false
    };
    replyMap[replyId] = r;
    startFooterTimer(r);
    scrollToBottom(false);
    return r;
  }

  function startFooterTimer(r) {
    updateReplyFooter(r, true);
    r.footerTimer = setInterval(() => {
      if (r.finished) { clearInterval(r.footerTimer); return; }
      updateReplyFooter(r, true);
    }, 500);
  }

  function updateReplyFooter(r, running) {
    if (!r || !r.footerEl) return;
    const elapsed = r.serverElapsedMs != null
      ? r.serverElapsedMs / 1000
      : (Date.now() - r.startedAt) / 1000;
    const inTok = r.serverUsage ? (r.serverUsage.input_tokens || 0) : r.usage.input;
    const outTok = r.serverUsage ? (r.serverUsage.output_tokens || 0) : r.usage.output;
    const tokens = inTok + outTok;
    let html = ctx.utils.msgStateIcon(running) +
      '<span class="badge dim">' + ctx.utils.formatDuration(elapsed) + '</span>';
    if (tokens > 0) {
      html += '<span class="badge dim">' + inTok + '↓ ' + outTok + '↑</span>';
    }
    r.footerEl.innerHTML = html;
  }

  function finishReply(replyId) {
    const r = replyId ? replyMap[replyId] : null;
    if (!r) return;
    endThinking(r);
    r.finished = true;
    if (r.footerTimer) { clearInterval(r.footerTimer); r.footerTimer = null; }
    if (r.textEl) {
      writeMarkdown(r.textEl, renderMarkdown(r.text));
      r.textEl = null;
    }
    updateReplyFooter(r, false);
  }

  function closeTextTail(r) {
    if (!r || !r.textEl) return;
    writeMarkdown(r.textEl, renderMarkdown(r.text));
    r.textEl = null;
    r.text = '';
    r.directBadgeAdded = false;
  }

  function ensureThinking(r) {
    if (r.thinkingEl) return r.thinkingEl;
    closeTextTail(r);
    const el = document.createElement('div');
    el.className = 'thinking-block';
    el.innerHTML =
      '<div class="thinking-header shimmer" onclick="window.App.thinkingToggle(this)">' +
        '<span class="thinking-label">思考中...</span>' +
        '<span class="thinking-toggle">▼</span>' +
      '</div>' +
      '<div class="thinking-body"><pre class="thinking-content"></pre></div>';
    r.contentEl.appendChild(el);
    r.thinkingEl = el;
    r.thinkingContentEl = el.querySelector('.thinking-content');
    r.thinkingText = '';
    return el;
  }

  function appendThinkingDelta(r, delta) {
    ensureThinking(r);
    r.thinkingText += delta;
    r.thinkingContentEl.textContent = r.thinkingText;
    scrollToBottom(false);
  }

  function endThinking(r) {
    if (!r.thinkingEl) return;
    const header = r.thinkingEl.querySelector('.thinking-header');
    const label = r.thinkingEl.querySelector('.thinking-label');
    if (header) header.classList.remove('shimmer');
    if (label) label.textContent = '思考过程（' + r.thinkingText.length + ' 字）';
    r.thinkingEl = null;
    r.thinkingContentEl = null;
  }

  function ensureTextBubble(r) {
    if (r.textEl) return r.textEl;
    r.currentGroup = null;
    const bubble = document.createElement('div');
    bubble.className = 'msg-bubble';
    r.contentEl.appendChild(bubble);
    r.textEl = bubble;
    r.text = '';
    return bubble;
  }

  function appendTextDelta(r, delta, opts = {}) {
    endThinking(r);
    const bubble = ensureTextBubble(r);
    if (opts.directReturn && !r.directBadgeAdded) {
      r.contentEl.insertBefore(renderDirectBadge(opts.toolName), bubble);
      r.directBadgeAdded = true;
    }
    r.text += delta;
    writeMarkdown(bubble, renderMarkdown(r.text) + '<span class="cursor"></span>');
  }

  function ensureToolGroupEl(r) {
    if (r.currentGroup) return r.currentGroup;
    closeTextTail(r);
    const el = document.createElement('div');
    el.className = 'tool-group';
    el.innerHTML =
      '<div class="tool-group-header shimmer" onclick="window.App.toolGroupToggle(this)">' +
        '<span class="tg-arrow">▸</span> <span class="tg-title">正在调用工具...</span>' +
      '</div>' +
      '<div class="tool-group-body"></div>';
    r.contentEl.appendChild(el);
    const g = { el, header: el.querySelector('.tool-group-header'), titleEl: el.querySelector('.tg-title'), body: el.querySelector('.tool-group-body'), calls: [] };
    r.currentGroup = g;
    r.toolGroups.push(g);
    return g;
  }

  function onToolCallStart(r, tcId, name, opts = {}) {
    endThinking(r);
    const g = ensureToolGroupEl(r);
    let tc = pendingToolCalls[tcId];
    if (!tc) {
      tc = {
        id: tcId,
        name: name || 'tool',
        argsRaw: '',
        resultRaw: '',
        state: 'running',
        summary: null,
        preview: null,
        directReturn: !!opts.directReturn
      };
      pendingToolCalls[tcId] = tc;
    } else {
      if (name && !tc.name) tc.name = name;
      if (opts.directReturn) tc.directReturn = true;
    }
    if (!g.calls.some((c) => c.id === tcId)) g.calls.push(tc);
    rebuildToolRows(r, g);
    updateToolGroupTitle(r, g);
    if (name === 'agent_spawn') {
      ensureRemotePanel(r, tcId);
    }
    scrollToBottom(false);
  }

  function onToolCallDelta(r, tcId, delta) {
    const tc = pendingToolCalls[tcId];
    if (!tc) return;
    tc.argsRaw += (delta || '');
    const sp = remotePanels[tcId];
    if (sp) syncRemotePanelArgs(sp, tc.argsRaw);
  }

  function onToolCallEnd(r, tcId) {
    const tc = pendingToolCalls[tcId];
    if (!tc) return;
    if (r.currentGroup) rebuildToolRows(r, r.currentGroup);
  }

  function onToolCallSummary(r, tcId, summary, toolName) {
    let tc = pendingToolCalls[tcId];
    const inReply = r.toolGroups && r.toolGroups.some((g) => g.calls.some((c) => c.id === tcId));
    if (!tc || !inReply) {
      onToolCallStart(r, tcId, toolName || (tc && tc.name) || 'tool');
      tc = pendingToolCalls[tcId];
    }
    if (tc) {
      tc.summary = summary;
      if (toolName) tc.name = toolName;
    }
    for (const g of r.toolGroups) {
      if (g.calls.some((c) => c.id === tcId)) {
        rebuildToolRows(r, g);
        updateToolGroupTitle(r, g);
      }
    }
  }

  function onToolResultPreview(r, tcId, preview) {
    const tc = pendingToolCalls[tcId];
    if (tc) tc.preview = preview;
    for (const g of r.toolGroups) {
      if (g.calls.some((c) => c.id === tcId)) rebuildToolRows(r, g);
    }
  }

  function onToolResultStart(r, tcId) {
    const tc = pendingToolCalls[tcId];
    if (tc && tc.resultRaw == null) tc.resultRaw = '';
  }

  function onToolResultDelta(r, tcId, delta) {
    const tc = pendingToolCalls[tcId];
    if (tc) tc.resultRaw = (tc.resultRaw || '') + (delta || '');
  }

  function onToolResultEnd(r, tcId, state, opts = {}) {
    const tc = pendingToolCalls[tcId];
    if (!tc) return;
    const norm = String(state || 'SUCCESS').toLowerCase();
    tc.state = norm === 'success' ? 'success' : (norm === 'interrupted' ? 'interrupted' : (norm === 'denied' ? 'denied' : 'error'));
    if (opts.directReturn) tc.directReturn = true;
    for (const g of r.toolGroups) {
      if (g.calls.some((c) => c.id === tcId)) {
        rebuildToolRows(r, g);
        updateToolGroupTitle(r, g);
      }
    }
  }

  function rebuildToolRows(r, g) {
    const openIndices = new Set();
    g.body.querySelectorAll('.tool-call-row').forEach((row, idx) => {
      if (row.classList.contains('open')) openIndices.add(idx);
    });
    g.body.innerHTML = g.calls.map((c) => renderToolRow({
      name: c.name,
      summary: c.summary,
      preview: c.preview,
      argsText: c.argsRaw,
      resultText: c.resultRaw,
      state: c.state,
      directReturn: c.directReturn
    })).join('');
    g.body.querySelectorAll('.tool-call-row').forEach((row, idx) => {
      if (openIndices.has(idx)) row.classList.add('open');
    });
  }

  function updateToolGroupTitle(r, g) {
    const running = g.calls.some((c) => c.state === 'running');
    g.header.classList.toggle('shimmer', running);
    g.titleEl.textContent = running ? '正在执行 ' + g.calls.length + ' 个工具...' : summarizeToolCalls(g.calls);
  }

  function ensureRemotePanel(r, spawnTcId) {
    if (remotePanels[spawnTcId]) return remotePanels[spawnTcId];
    closeTextTail(r);
    const el = document.createElement('div');
    el.className = 'remote-call open';
    el.innerHTML =
      '<div class="rc-header" onclick="window.App.remoteCallToggle(this)">' +
        '<span class="rc-icon">↳</span>' +
        '<span class="rc-agent">Sub-Agent</span>' +
        '<span class="rc-status running">Running</span>' +
        '<span class="rc-arrow">▸</span>' +
      '</div>' +
      '<div class="rc-body">' +
        '<div class="rc-task"></div>' +
        '<div class="rc-events"></div>' +
        '<div class="rc-footer" style="display:none"></div>' +
      '</div>';
    r.contentEl.appendChild(el);
    const panel = {
      el,
      agentEl: el.querySelector('.rc-agent'),
      statusEl: el.querySelector('.rc-status'),
      taskEl: el.querySelector('.rc-task'),
      eventsEl: el.querySelector('.rc-events'),
      footerEl: el.querySelector('.rc-footer'),
      textEl: null,
      text: '',
      thinkingEl: null,
      thinkingContentEl: null,
      thinkingText: '',
      toolGroups: [],
      currentGroup: null,
      done: false
    };
    remotePanels[spawnTcId] = panel;
    activeRemotePanel = panel;
    lastRemotePanel = panel;
    return panel;
  }

  function remotePanelStatus(p, text, cls) {
    if (!p || !p.statusEl) return;
    p.statusEl.textContent = text;
    p.statusEl.className = 'rc-status ' + (cls || '');
  }

  function showRemoteFooter(p, text) {
    if (!p || !p.footerEl) return;
    p.footerEl.textContent = text;
    p.footerEl.style.display = '';
  }

  function syncRemotePanelArgs(p, argsRaw) {
    try {
      const o = JSON.parse(argsRaw);
      if (o.agent_id || o.agent) p.agentEl.textContent = o.agent_id || o.agent;
      if (o.task || o.prompt || o.message) p.taskEl.textContent = o.task || o.prompt || o.message;
    } catch (e) {}
  }

  function routeRemoteEvent(r, data) {
    const p = activeRemotePanel || lastRemotePanel;
    if (!p) return;
    const fakeR = {
      contentEl: p.eventsEl,
      textEl: p.textEl,
      text: p.text,
      thinkingEl: p.thinkingEl,
      thinkingContentEl: p.thinkingContentEl,
      thinkingText: p.thinkingText,
      toolGroups: p.toolGroups,
      currentGroup: p.currentGroup
    };
    if (data.type === 'TEXT_BLOCK_DELTA') {
      appendTextDelta(fakeR, data.delta || '');
      p.textEl = fakeR.textEl;
      p.text = fakeR.text;
    } else if (data.type === 'THINKING_BLOCK_DELTA') {
      appendThinkingDelta(fakeR, data.delta || '');
      p.thinkingEl = fakeR.thinkingEl;
      p.thinkingContentEl = fakeR.thinkingContentEl;
      p.thinkingText = fakeR.thinkingText;
    } else if (data.type === 'THINKING_BLOCK_END') {
      endThinking(fakeR);
      p.thinkingEl = null;
    } else if (data.type === 'TOOL_CALL_START') {
      remoteToolOwner[data.toolCallId] = p;
      onToolCallStart(fakeR, data.toolCallId, data.toolName);
      p.currentGroup = fakeR.currentGroup;
    } else if (data.type === 'TOOL_CALL_DELTA') {
      onToolCallDelta(fakeR, data.toolCallId, data.delta);
    } else if (data.type === 'TOOL_RESULT_TEXT_DELTA') {
      onToolResultDelta(fakeR, data.toolCallId, data.delta);
    } else if (data.type === 'TOOL_RESULT_END') {
      onToolResultEnd(fakeR, data.toolCallId, data.state);
    }
    scrollToBottom(false);
  }

  function onRemoteToolSummary(p, tcId, summary) {
    onToolCallSummary({ toolGroups: p.toolGroups }, tcId, summary);
  }

  function onRemoteToolPreview(p, tcId, preview) {
    onToolResultPreview({ toolGroups: p.toolGroups }, tcId, preview);
  }

  function safeFileHref(rawUrl) {
    if (typeof rawUrl !== 'string') return '#';
    const trimmed = rawUrl.trim();
    if (!trimmed) return '#';
    const lower = trimmed.toLowerCase();
    if (lower.startsWith('http://') || lower.startsWith('https://')) {
      return trimmed;
    }
    if (trimmed.startsWith('/') && !trimmed.startsWith('//')) {
      const base = (ctx && ctx.api && ctx.api.BASE) || '';
      if (base && trimmed.startsWith('/files/') && !trimmed.startsWith(base + '/')) {
        return base + trimmed;
      }
      return trimmed;
    }
    return '#';
  }

  function renderFileReadyCard(r, data) {
    if (!r || !r.contentEl || !data) return;
    const isUpload = data.origin === 'upload' || data.source === 'user_upload' || data.kind === 'upload';
    const card = document.createElement('div');
    card.className = 'file-ready-card' + (isUpload ? ' upload-card' : '');
    const rawHref = data.download_url || data.url || (data.file_id ? '/files/' + encodeURIComponent(data.file_id) : '');
    const href = safeFileHref(rawHref);
    const name = data.file_name || data.filename || data.name || data.file_id || 'file';
    const descParts = [];
    if (data.size != null) descParts.push(ctx.utils.formatBytes(data.size));
    if (data.description) descParts.push(data.description);
    card.innerHTML =
      '<div class="file-ready-info">' +
        '<span class="file-ready-icon">' + icon('paperclip', 18) + '</span>' +
        '<div class="file-ready-meta">' +
          '<div class="file-ready-name" title="' + ctx.utils.esc(name) + '">' + ctx.utils.esc(name) + '</div>' +
          (descParts.length ? '<div class="file-ready-desc">' + ctx.utils.esc(descParts.join(' · ')) + '</div>' : '') +
        '</div>' +
      '</div>' +
      '<a class="btn small accent file-ready-download" href="' + ctx.utils.esc(href) + '" target="_blank" rel="noopener">打开</a>';
    r.contentEl.appendChild(card);
    scrollToBottom(false);
  }

  function renderConfirmCard(r, data) {
    dismissConfirmCard();
    const calls = (data && (data.tool_calls || data.tools)) || [];
    if (calls.length === 0) return;
    const card = document.createElement('div');
    card.className = 'confirm-card';
    card.innerHTML =
      '<div class="confirm-card-header">' +
        '<span class="confirm-title">需要人工确认</span>' +
        '<span class="confirm-sub">' + calls.length + ' 个工具调用等待确认</span>' +
      '</div>' +
      '<div class="confirm-tools">' +
        calls.map((c) =>
          '<div class="confirm-tool">' +
            '<div class="confirm-tool-name">' + ctx.utils.esc(c.name || 'tool') + '</div>' +
            '<div class="confirm-tool-id">' + ctx.utils.esc(c.tool_call_id || c.id || '') + '</div>' +
            '<div class="confirm-tool-input"><pre>' + ctx.utils.esc(JSON.stringify(c.input || c.arguments || {}, null, 2)) + '</pre></div>' +
          '</div>'
        ).join('') +
      '</div>' +
      '<div class="confirm-actions">' +
        '<button class="btn danger small" data-act="reject">拒绝</button>' +
        '<button class="btn primary small" data-act="approve">批准</button>' +
      '</div>';

    card.querySelector('[data-act="approve"]').addEventListener('click', () => {
      card.classList.add('processing');
      card.querySelectorAll('button').forEach((b) => { b.disabled = true; });
      onSubmitConfirm(calls, true);
    });
    card.querySelector('[data-act="reject"]').addEventListener('click', () => {
      card.classList.add('processing');
      card.querySelectorAll('button').forEach((b) => { b.disabled = true; });
      onSubmitConfirm(calls, false);
    });

    pendingConfirm = { replyId: r.replyId, calls, cardEl: card };
    (r.contentEl || getMessagesEl()).appendChild(card);
    scrollToBottom(true);
  }

  function getPendingConfirmCard() {
    return pendingConfirm ? pendingConfirm.cardEl : null;
  }

  function restoreConfirmCard(cardEl) {
    if (!cardEl) return;
    cardEl.classList.remove('processing');
    cardEl.querySelectorAll('button').forEach((b) => { b.disabled = false; });
  }

  function dismissConfirmCard(expectedCardEl) {
    if (expectedCardEl && (!pendingConfirm || pendingConfirm.cardEl !== expectedCardEl)) {
      return;
    }
    if (pendingConfirm && pendingConfirm.cardEl) {
      try { pendingConfirm.cardEl.remove(); } catch (e) {}
    }
    pendingConfirm = null;
  }

  function renderMcpAppCard(r, data) {
    const tcId = data.toolCallId || ('app-' + Date.now());
    pendingToolCalls[tcId] = { id: tcId, name: data.toolName, argsRaw: '', resultRaw: '', state: 'running' };
    const card = document.createElement('div');
    card.className = 'mcp-apps-container open';
    const uri = (data.ui && data.ui.resourceUri) || '';
    const server = (data.ui && data.ui.server) || data.server || '';
    card.innerHTML =
      '<div class="mcp-apps-header" onclick="this.parentElement.classList.toggle(\'open\')">' +
        '<span class="app-label">MCP App</span>' +
        '<span class="app-name">' + ctx.utils.esc(data.toolName || '') + '</span>' +
        '<span class="app-uri">' + ctx.utils.esc(uri) + '</span>' +
        '<span class="tc-toggle">▸</span>' +
      '</div>' +
      '<div class="mcp-apps-body"><div class="mcp-apps-loading">正在加载交互卡片...</div></div>';
    r.contentEl.appendChild(card);
    const bodyEl = card.querySelector('.mcp-apps-body');
    const host = new McpAppHost({
      tcId,
      server,
      resourceUri: uri,
      toolName: data.toolName || '',
      callbacks: {
        needsConfirm: (call) => renderAppConfirmCard(r, call)
      }
    });
    appHosts[tcId] = host;
    mountAppCard(host, bodyEl);
    scrollToBottom(false);
  }

  async function mountAppCard(host, bodyEl) {
    try {
      await host.mount(bodyEl);
    } catch (e) {
      bodyEl.innerHTML =
        '<div class="mcp-apps-error">' + ctx.utils.esc(e.message || 'Resource load failed') + '</div>' +
        '<div><button class="btn small" data-retry>重试</button></div>';
      const retry = bodyEl.querySelector('[data-retry]');
      if (retry) retry.addEventListener('click', () => {
        bodyEl.innerHTML = '<div class="mcp-apps-loading">Loading...</div>';
        mountAppCard(host, bodyEl);
      });
    }
    scrollToBottom(false);
  }

  function renderAppConfirmCard(r, call) {
    return new Promise((resolve) => {
      const card = document.createElement('div');
      card.className = 'confirm-card';
      card.innerHTML =
        '<div class="confirm-card-header">' +
          '<span class="confirm-title">需要人工确认</span>' +
          '<span class="confirm-sub">MCP App tool call</span>' +
        '</div>' +
        '<div class="confirm-tools"><div class="confirm-tool">' +
          '<div class="confirm-tool-name">' + ctx.utils.esc(call.name || 'tool') + '</div>' +
          '<div class="confirm-tool-input"><pre>' + ctx.utils.esc(JSON.stringify(call.arguments || {}, null, 2)) + '</pre></div>' +
        '</div></div>' +
        '<div class="confirm-actions">' +
          '<button class="btn danger small" data-act="reject">拒绝</button>' +
          '<button class="btn primary small" data-act="approve">批准</button>' +
        '</div>';
      const settle = (ok) => {
        try { card.remove(); } catch (e) {}
        resolve(ok);
      };
      card.querySelector('[data-act="approve"]').addEventListener('click', () => settle(true));
      card.querySelector('[data-act="reject"]').addEventListener('click', () => settle(false));
      const anchor = r ? r.contentEl : getMessagesEl();
      if (anchor) anchor.appendChild(card);
      scrollToBottom(true);
    });
  }

  function teardownAllAppHosts() {
    for (const key of Object.keys(appHosts)) {
      try { appHosts[key].teardown('reply-completed'); } catch (e) {}
      delete appHosts[key];
    }
  }

  function parseToolArgs(argsRaw) {
    if (!argsRaw) return {};
    try { return JSON.parse(argsRaw); } catch (e) { return {}; }
  }

  function finishRemoteSpawn(tcId, state) {
    const sp = remotePanels[tcId];
    if (!sp) return;
    remotePanelStatus(sp, state === 'SUCCESS' ? 'Done' : 'Error', state === 'SUCCESS' ? 'done' : 'error');
    sp.done = true;
    if (activeRemotePanel === sp) activeRemotePanel = null;
  }

  function finishSubagentExposed(data) {
    const sp = activeRemotePanel || lastRemotePanel;
    if (!sp) return;
    const who = data.agent_id || data.label || data.subagent_id || 'Sub-Agent';
    sp.done = true;
    remotePanelStatus(sp, 'Done', 'done');
    showRemoteFooter(sp, who + ' completed');
    if (activeRemotePanel === sp) activeRemotePanel = null;
  }

  return {
    resetAll,
    resetTurnRemote,
    renderMarkdown,
    writeMarkdown,
    renderStoppedBadge,
    addCompactionDivider,
    addMessage,
    addAssistantHistory,
    ensureReply,
    finishReply,
    appendThinkingDelta,
    endThinking,
    appendTextDelta,
    onToolCallStart,
    onToolCallDelta,
    onToolCallEnd,
    onToolCallSummary,
    onToolResultPreview,
    onToolResultStart,
    onToolResultDelta,
    onToolResultEnd,
    routeRemoteEvent,
    remotePanelStatus,
    onRemoteToolSummary,
    onRemoteToolPreview,
    finishRemoteSpawn,
    finishSubagentExposed,
    renderFileReadyCard,
    renderConfirmCard,
    getPendingConfirmCard,
    restoreConfirmCard,
    dismissConfirmCard,
    renderMcpAppCard,
    teardownAllAppHosts,
    getActiveRemotePanel: () => activeRemotePanel,
    getRemotePanel: (tcId) => remotePanels[tcId],
    getRemoteToolOwner: (tcId) => remoteToolOwner[tcId],
    getAppHost: (tcId) => appHosts[tcId],
    getPendingToolCall: (tcId) => pendingToolCalls[tcId],
    parseToolArgs,
    buildToolResult,
    showRemoteFooter
  };
}
