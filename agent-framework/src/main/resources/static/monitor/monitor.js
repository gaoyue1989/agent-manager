/* ===== Monitor 模块：跨用户会话与问答运营监控 =====
 *
 * 面向运营/管理视角的只读面板：
 *   - 顶部指标卡：用户数 / 会话数 / 提问数 / 回答数 / 今日会话与消息（GET /debug/monitor/overview）
 *   - 会话列表：跨全部用户，按 user / 标题关键词 / 时间范围过滤（GET /debug/monitor/sessions）
 *   - 问答回放：选中会话后复用 GET /threads/{sid}/history 渲染完整问答（用户提问 + 助手回答 + 工具调用）
 *
 * 数据来源均为共享存储（session_user / agent_state / session_message），
 * 页面本身无本地状态依赖，多副本部署下任意副本均可正确展示。
 */

let ctx = null;
let refreshTimer = null;
let autoRefresh = true;
let sessions = [];
let selectedSid = null;
let detailSeq = 0;          // 详情请求序号：丢弃过期响应（快速切换会话时）
let filterDebounce = null;

const REFRESH_MS = 15000;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = render();
    bindEvents();
    loadOverview();
    loadSessions();
    refreshTimer = setInterval(() => {
      if (!autoRefresh) return;
      loadOverview();
      loadSessions();
    }, REFRESH_MS);
  },

  unmount() {
    if (refreshTimer) clearInterval(refreshTimer);
    if (filterDebounce) clearTimeout(filterDebounce);
    ctx = null;
    sessions = [];
    selectedSid = null;
  }
};

function render() {
  return `
  <div class="module-page monitor-page">
    <div class="module-header">
      <h2>Monitor</h2>
      <span class="sub" id="monitorUpdated">全部用户 · 会话与问答监控</span>
    </div>
    <div class="monitor-stats" id="monitorStats"></div>
    <div class="monitor-toolbar">
      <input id="monUser" class="input" type="text" spellcheck="false" placeholder="user_id（精确）">
      <input id="monKeyword" class="input" type="text" spellcheck="false" placeholder="标题 / 会话 id 关键词">
      <label class="mon-time">从&nbsp;<input id="monFrom" class="input" type="datetime-local"></label>
      <label class="mon-time">到&nbsp;<input id="monTo" class="input" type="datetime-local"></label>
      <select id="monLimit" class="input">
        <option value="50">50 条</option>
        <option value="100" selected>100 条</option>
        <option value="200">200 条</option>
        <option value="500">500 条</option>
      </select>
      <button id="monSearch" class="btn small">查询</button>
      <button id="monReset" class="btn small">重置</button>
      <label class="mon-auto"><input type="checkbox" id="monProblem"> 只看异常</label>
      <label class="mon-auto"><input type="checkbox" id="monAuto" checked> 自动刷新</label>
    </div>
    <div class="monitor-body">
      <div class="monitor-list" id="monitorList"><div class="empty">Loading...</div></div>
      <div class="monitor-detail" id="monitorDetail"><div class="empty">从左侧选择一个会话查看问答</div></div>
    </div>
  </div>`;
}

function bindEvents() {
  document.getElementById('monSearch').addEventListener('click', loadSessions);
  document.getElementById('monReset').addEventListener('click', () => {
    document.getElementById('monUser').value = '';
    document.getElementById('monKeyword').value = '';
    document.getElementById('monFrom').value = '';
    document.getElementById('monTo').value = '';
    document.getElementById('monLimit').value = '100';
    document.getElementById('monProblem').checked = false;
    loadSessions();
  });
  document.getElementById('monAuto').addEventListener('change', (e) => {
    autoRefresh = e.target.checked;
  });
  document.getElementById('monProblem').addEventListener('change', loadSessions);
  // 文本过滤输入：防抖后查询（避免每键入一次触发一次全量扫描）
  for (const id of ['monUser', 'monKeyword']) {
    document.getElementById(id).addEventListener('input', () => {
      if (filterDebounce) clearTimeout(filterDebounce);
      filterDebounce = setTimeout(loadSessions, 350);
    });
  }
  for (const id of ['monFrom', 'monTo']) {
    document.getElementById(id).addEventListener('change', loadSessions);
  }
  document.getElementById('monLimit').addEventListener('change', loadSessions);
}

/** 读取过滤条件（空值省略，交由后端按缺省处理） */
function currentFilters() {
  const user = document.getElementById('monUser').value.trim();
  const keyword = document.getElementById('monKeyword').value.trim();
  const from = document.getElementById('monFrom').value;
  const to = document.getElementById('monTo').value;
  const limit = document.getElementById('monLimit').value;
  const params = { limit };
  if (user) params.userId = user;
  if (keyword) params.keyword = keyword;
  if (from) params.from = from.replace('T', ' ') + (from.length === 16 ? ':00' : '');
  if (to) params.to = to.replace('T', ' ') + (to.length === 16 ? ':59' : '');
  if (document.getElementById('monProblem').checked) params.onlyProblem = 'true';
  return params;
}

// ---------- 顶部指标 ----------

async function loadOverview() {
  const box = document.getElementById('monitorStats');
  if (!box) return;
  try {
    const data = await ctx.api.getMonitorOverview();
    if (!ctx) return;
    if (data.connected === false) {
      box.innerHTML = '<div class="empty error-text">概览读取失败：' + ctx.utils.esc(data.error || '') + '</div>';
      return;
    }
    box.innerHTML = statCard('用户总数', data.total_users)
      + statCard('会话总数', data.total_sessions)
      + statCard('提问总数', data.total_questions)
      + statCard('回答总数', data.total_answers)
      + statCard('消息总数', data.total_messages)
      + statCard('今日新增会话', data.sessions_today)
      + statCard('今日活跃会话', data.active_sessions_today)
      + statCard('今日消息', data.messages_today)
      + statCard('异常会话', data.sessions_with_error, data.sessions_with_error > 0 ? 'err' : '')
      + statCard('待确认会话', data.sessions_pending_confirm, data.sessions_pending_confirm > 0 ? 'warn' : '')
      + statCard('问题会话', data.sessions_with_problem, data.sessions_with_problem > 0 ? 'err' : '');
  } catch (e) {
    if (!ctx) return;
    box.innerHTML = '<div class="empty error-text">概览读取失败：' + ctx.utils.esc(e.message) + '</div>';
  }
}

function statCard(label, value, tone) {
  const v = (value === undefined || value === null) ? '-' : value;
  return '<div class="monitor-stat' + (tone ? ' ' + tone : '') + '"><div class="ms-value">' + ctx.utils.esc(v) + '</div>'
    + '<div class="ms-label">' + ctx.utils.esc(label) + '</div></div>';
}

// ---------- 会话列表 ----------

async function loadSessions() {
  const listEl = document.getElementById('monitorList');
  if (!listEl) return;
  try {
    const data = await ctx.api.getMonitorSessions(currentFilters());
    if (!ctx) return;
    sessions = data.sessions || [];
    const stamp = document.getElementById('monitorUpdated');
    if (stamp) {
      const total = data.total != null ? data.total : sessions.length;
      stamp.textContent = '全部用户 · ' + (data.onlyProblem ? '仅异常 · ' : '') + '共 ' + total + ' 条'
        + (data.hasMore ? '（显示前 ' + sessions.length + ' 条，请缩小范围）' : '')
        + ' · ' + new Date().toLocaleTimeString('zh-CN', { hour12: false });
    }
    if (sessions.length === 0) {
      listEl.innerHTML = '<div class="empty">没有匹配的会话</div>';
      return;
    }
    listEl.innerHTML = sessions.map(sessionRow).join('');
    listEl.querySelectorAll('.monitor-session').forEach((el) => {
      el.addEventListener('click', () => selectSession(el.dataset.sid));
    });
    markSelected();
  } catch (e) {
    if (!ctx) return;
    listEl.innerHTML = '<div class="empty error-text">会话列表加载失败：' + ctx.utils.esc(e.message) + '</div>';
  }
}

function sessionRow(s) {
  const sid = s.session_id || '';
  const title = (s.title || '').trim() || sid;
  const active = sid === selectedSid ? ' active' : '';
  const count = s.message_count != null ? s.message_count + ' 条消息' : '';
  const model = s.model ? s.model : '默认模型';
  const flag = s.has_error ? ' has-error' : (s.pending_confirm ? ' has-pending' : '');
  let badges = '';
  if (s.has_error) {
    badges += '<span class="ms-badge err" title="存在失败的工具调用">✗ ' + (s.error_count || 1) + '</span>';
  }
  if (s.pending_confirm) {
    badges += '<span class="ms-badge warn" title="有未完成的 HITL 工具确认">⏳ 待确认</span>';
  }
  return '<div class="monitor-session' + active + flag + '" data-sid="' + ctx.utils.esc(sid) + '">'
    + '<div class="ms-title" title="' + ctx.utils.esc(sid) + '">' + ctx.utils.esc(title) + '</div>'
    + '<div class="ms-meta"><span class="ms-user">' + ctx.utils.esc(s.user_id || 'unknown') + '</span>'
    + '<span class="ms-dot">·</span><span>' + ctx.utils.esc(model) + '</span>'
    + (count ? '<span class="ms-dot">·</span><span>' + ctx.utils.esc(count) + '</span>' : '')
    + (badges ? '<span class="ms-badges">' + badges + '</span>' : '')
    + '</div>'
    + '<div class="ms-time">' + ctx.utils.esc((s.updated_at || '').substring(0, 19).replace('T', ' ')) + '</div>'
    + '</div>';
}

function markSelected() {
  document.querySelectorAll('.monitor-session').forEach((el) => {
    el.classList.toggle('active', el.dataset.sid === selectedSid);
  });
}

// ---------- 问答回放 ----------

async function selectSession(sid) {
  if (!sid) return;
  selectedSid = sid;
  markSelected();
  const seq = ++detailSeq;
  const detailEl = document.getElementById('monitorDetail');
  if (!detailEl) return;
  detailEl.innerHTML = '<div class="empty">Loading conversation...</div>';
  try {
    const data = await ctx.api.getThreadHistory(sid);
    // 丢弃过期响应：等待期间用户可能已切到别的会话
    if (!ctx || seq !== detailSeq) return;
    const session = sessions.find((x) => x.session_id === sid) || {};
    detailEl.innerHTML = renderConversation(sid, session, data);
    highlightCode(detailEl);
  } catch (e) {
    if (!ctx || seq !== detailSeq) return;
    detailEl.innerHTML = '<div class="empty error-text">会话详情加载失败：' + ctx.utils.esc(e.message) + '</div>';
  }
}

function renderConversation(sid, session, data) {
  const msgs = data.messages || [];
  const header = '<div class="monitor-conv-header">'
    + '<div class="mc-title">' + ctx.utils.esc((session.title || '').trim() || sid) + '</div>'
    + '<div class="mc-meta">'
    + '<span class="ms-user">' + ctx.utils.esc(session.user_id || 'unknown') + '</span>'
    + '<span class="ms-dot">·</span><span>' + ctx.utils.esc(sid) + '</span>'
    + (session.model ? '<span class="ms-dot">·</span><span>' + ctx.utils.esc(session.model) + '</span>' : '')
    + '</div></div>';
  const pending = data.pendingConfirm
    ? '<div class="monitor-pending">⏳ 该会话有未完成的工具确认（HITL）：'
      + ctx.utils.esc(pendingToolsLabel(data.pendingConfirm)) + '</div>'
    : '';
  let body;
  if (msgs.length === 0) {
    body = '<div class="empty">该会话暂无归档消息</div>';
  } else {
    body = '<div class="monitor-conv">' + msgs.map(messageHtml).join('') + '</div>';
  }
  const files = (data.files || []).length
    ? '<div class="monitor-files">产出文件：' + (data.files || []).map((f) =>
        '<span class="mf-item">' + ctx.utils.esc(f.file_name || f.file_id) + '</span>').join('') + '</div>'
    : '';
  return header + pending + body + files;
}

/** HITL 待确认工具名摘要：tools 字段可能是数组（agent_state 源）或 JSON 字符串（confirm_context 源） */
function pendingToolsLabel(pc) {
  const t = pc && pc.tools;
  let arr = null;
  if (Array.isArray(t)) arr = t;
  else if (typeof t === 'string') {
    try { const parsed = JSON.parse(t); if (Array.isArray(parsed)) arr = parsed; } catch (e) { /* 非 JSON：忽略 */ }
  }
  if (!arr) return '待确认';
  const names = arr.map((x) => x && (x.name || x.tool_name)).filter(Boolean);
  return names.length ? names.join(', ') : '待确认';
}

/** 单条消息 → HTML（role: user / assistant / agent / compaction） */
function messageHtml(m) {
  if (m.role === 'compaction') {
    return '<div class="monitor-compaction">— ' + ctx.utils.esc(m.content || '上下文已压缩') + ' —</div>';
  }
  if (m.role === 'user') {
    return '<div class="msg user"><div class="msg-bubble">' + userText(m.content || '') + '</div></div>';
  }
  if (m.role === 'assistant' || m.role === 'agent') {
    return '<div class="msg assistant">' + assistantBlocks(m) + '</div>';
  }
  // tool / 其它角色：直接展示内容
  return '<div class="msg system">' + ctx.utils.esc(m.content || '') + '</div>';
}

/** 用户消息：转义 + 高亮 @Skill 引用（与 Chat 页一致） */
function userText(content) {
  return ctx.utils.esc(content).replace(/@(\S+)/g, '<span class="skill-mention-tag">@$1</span>');
}

/**
 * 助手消息：优先按 blocks 块序渲染（文本气泡与工具组按真实发生顺序交错），
 * 无 blocks 的旧数据退回「工具组在上、文本在下」。
 */
function assistantBlocks(m) {
  const toolCalls = m.tool_calls || [];
  const blocks = m.blocks;
  const callFrom = (tc) => ({
    name: tc.name || '',
    argsText: tc.input != null && typeof tc.input === 'object'
      ? JSON.stringify(tc.input, null, 2) : String(tc.input || ''),
    resultText: tc.output != null ? String(tc.output) : null,
    outputTruncated: !!tc.output_truncated,
    state: tc.state || 'success'
  });
  let html = '';
  const appendText = (text) => { html += '<div class="msg-bubble">' + md(text) + '</div>'; };
  if (Array.isArray(blocks) && blocks.length > 0) {
    let group = null;
    const flush = () => { if (group) { html += toolGroupHtml(group); group = null; } };
    for (const b of blocks) {
      if (b && b.type === 'text' && b.text) {
        flush();
        appendText(b.text);
      } else if (b && b.type === 'tool') {
        const tc = toolCalls.find((c) => b.id && c.id === b.id) || {};
        group = group || [];
        group.push(callFrom(tc));
      }
    }
    flush();
  } else {
    if (toolCalls.length > 0) html += toolGroupHtml(toolCalls.map(callFrom));
    if (m.content) appendText(m.content);
  }
  if (!html) html = '<div class="msg-bubble"><span class="tc-running">（无文本输出）</span></div>';
  return html;
}

// ---------- 工具调用渲染（复用 Chat 页的全局 CSS 类与折叠开关） ----------

function toolGroupHtml(calls) {
  const rows = calls.map(toolRowHtml).join('');
  return '<div class="tool-group">'
    + '<div class="tool-group-header" onclick="window.App.toolGroupToggle(this)">'
    + '<span class="tg-arrow">▶</span><span>' + ctx.utils.esc(calls.length + ' 次工具调用') + '</span></div>'
    + '<div class="tool-group-body">' + rows + '</div></div>';
}

function toolRowHtml(c) {
  const body = (c.argsText ? '<div class="tc-label">参数</div><pre>' + ctx.utils.esc(truncate(c.argsText, 4000)) + '</pre>' : '')
    + (c.resultText
        ? '<div class="tc-label">结果' + (c.outputTruncated ? '（已截断）' : '') + '</div><pre>'
          + ctx.utils.esc(truncate(c.resultText, 4000)) + '</pre>'
        : '');
  return '<div class="tool-call-row" onclick="window.App.toolRowToggle(this)">'
    + '<span class="tc-name">' + ctx.utils.esc(c.name || 'tool') + '</span>'
    + '<span class="tc-state">' + ctx.utils.toolStateIcon(c.state) + '</span>'
    + (body ? '<span class="tc-toggle">▶</span>' : '')
    + '</div>'
    + (body ? '<div class="tool-call-body"><div class="tc-inner">' + body + '</div></div>' : '');
}

function truncate(s, n) {
  return s.length > n ? s.substring(0, n) + '…' : s;
}

function md(text) {
  if (text == null) return '';
  try {
    const raw = window.marked.parse(String(text), { gfm: true, breaks: true });
    return window.DOMPurify.sanitize(raw, { ADD_ATTR: ['class', 'target', 'rel'] });
  } catch (e) {
    return ctx.utils.esc(text);
  }
}

function highlightCode(root) {
  if (!window.hljs) return;
  root.querySelectorAll('pre code').forEach((c) => {
    try { window.hljs.highlightElement(c); } catch (e) { /* 语言不支持时忽略 */ }
  });
}
