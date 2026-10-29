/* ===== Monitor 模块：跨用户会话与问答运营监控 =====
 *
 * 面向运营/管理视角的只读面板：
 *   - 顶部指标卡：用户数 / 会话数 / 提问数 / 回答数 / 今日会话与消息（GET /debug/monitor/overview）
 *   - 会话列表：跨全部用户，按 user / 标题关键词 / 时间范围过滤（GET /debug/monitor/sessions）
 *   - 对话日志：消息粒度浏览 + 正文关键词模糊检索（GET /debug/monitor/messages，issue #206）
 *   - 问答回放：选中会话后复用 GET /threads/{sid}/history 渲染完整问答
 *     （用户提问 + 助手回答 + 工具调用 + 每轮 OTel traceId，issue #205）
 *
 * 数据来源均为共享存储（session_user / agent_state / session_message），
 * 页面本身无本地状态依赖，多副本部署下任意副本均可正确展示。
 */

let ctx = null;
let refreshTimer = null;
let autoRefresh = true;
let mode = 'sessions';      // sessions = 会话列表；messages = 对话日志
let sessions = [];
let messages = [];
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
    loadActiveList();
    refreshTimer = setInterval(() => {
      if (!autoRefresh) return;
      loadOverview();
      loadActiveList();
    }, REFRESH_MS);
  },

  unmount() {
    if (refreshTimer) clearInterval(refreshTimer);
    if (filterDebounce) clearTimeout(filterDebounce);
    ctx = null;
    mode = 'sessions';
    sessions = [];
    messages = [];
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
      <div class="monitor-modes" role="group" aria-label="监控视图">
        <button id="monModeSessions" type="button" class="monitor-mode active">会话列表</button>
        <button id="monModeMessages" type="button" class="monitor-mode">对话日志</button>
      </div>
      <input id="monUser" class="input" type="text" spellcheck="false" placeholder="user_id（精确）">
      <input id="monKeyword" class="input" type="text" spellcheck="false" placeholder="标题 / 会话 id 关键词">
      <input id="monContent" class="input" type="text" spellcheck="false"
             placeholder="对话内容关键词（模糊匹配）" style="display:none">
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
      <label class="mon-auto" id="monProblemLabel"><input type="checkbox" id="monProblem"> 只看异常</label>
      <label class="mon-auto"><input type="checkbox" id="monAuto" checked> 自动刷新</label>
    </div>
    <div class="monitor-body">
      <div class="monitor-list" id="monitorList"><div class="empty">Loading...</div></div>
      <div class="monitor-detail" id="monitorDetail"><div class="empty">从左侧选择一个会话查看问答</div></div>
    </div>
  </div>`;
}

function bindEvents() {
  document.getElementById('monModeSessions').addEventListener('click', () => setMode('sessions'));
  document.getElementById('monModeMessages').addEventListener('click', () => setMode('messages'));
  document.getElementById('monSearch').addEventListener('click', loadActiveList);
  document.getElementById('monReset').addEventListener('click', () => {
    document.getElementById('monUser').value = '';
    document.getElementById('monKeyword').value = '';
    document.getElementById('monContent').value = '';
    document.getElementById('monFrom').value = '';
    document.getElementById('monTo').value = '';
    document.getElementById('monLimit').value = '100';
    document.getElementById('monProblem').checked = false;
    if (mode !== 'sessions') {
      setMode('sessions');
    } else {
      loadSessions();
    }
  });
  document.getElementById('monAuto').addEventListener('change', (e) => {
    autoRefresh = e.target.checked;
  });
  document.getElementById('monProblem').addEventListener('change', loadActiveList);
  // 文本过滤输入：防抖后查询（避免每键入一次触发一次全量扫描）
  for (const id of ['monUser', 'monKeyword', 'monContent']) {
    document.getElementById(id).addEventListener('input', () => {
      if (filterDebounce) clearTimeout(filterDebounce);
      filterDebounce = setTimeout(loadActiveList, 350);
    });
  }
  for (const id of ['monFrom', 'monTo']) {
    document.getElementById(id).addEventListener('change', loadActiveList);
  }
  document.getElementById('monLimit').addEventListener('change', loadActiveList);
}

/** 切换视图：会话列表 / 对话日志（issue #206） */
function setMode(next) {
  mode = next === 'messages' ? 'messages' : 'sessions';
  document.getElementById('monModeSessions').classList.toggle('active', mode === 'sessions');
  document.getElementById('monModeMessages').classList.toggle('active', mode === 'messages');
  document.getElementById('monKeyword').style.display = mode === 'sessions' ? '' : 'none';
  document.getElementById('monContent').style.display = mode === 'messages' ? '' : 'none';
  document.getElementById('monProblemLabel').style.display = mode === 'sessions' ? '' : 'none';
  loadActiveList();
}

/** 当前视图的数据加载入口（自动刷新 / 查询按钮共用） */
function loadActiveList() {
  if (mode === 'messages') {
    loadMessages();
  } else {
    loadSessions();
  }
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

/** 对话日志过滤条件（正文关键词 + user / 时间 / 条数；不含标题关键词与只看异常） */
function currentMessageFilters() {
  const user = document.getElementById('monUser').value.trim();
  const content = document.getElementById('monContent').value.trim();
  const from = document.getElementById('monFrom').value;
  const to = document.getElementById('monTo').value;
  const params = { limit: document.getElementById('monLimit').value };
  if (user) params.userId = user;
  if (content) params.keyword = content;
  if (from) params.from = from.replace('T', ' ') + (from.length === 16 ? ':00' : '');
  if (to) params.to = to.replace('T', ' ') + (to.length === 16 ? ':59' : '');
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
      + statCard('轮次总数', data.total_turns)
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
  const turns = s.turn_count != null ? s.turn_count + ' 轮' : '';
  const msgTip = s.message_count != null ? s.message_count + ' 条消息' : '';
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
    + (turns ? '<span class="ms-dot">·</span><span title="' + ctx.utils.esc(msgTip) + '">'
        + ctx.utils.esc(turns) + '</span>' : '')
    + (badges ? '<span class="ms-badges">' + badges + '</span>' : '')
    + '</div>'
    + '<div class="ms-time">' + ctx.utils.esc((s.updated_at || '').substring(0, 19).replace('T', ' ')) + '</div>'
    + '</div>';
}

function markSelected() {
  document.querySelectorAll('.monitor-session, .monitor-msg').forEach((el) => {
    el.classList.toggle('active', el.dataset.sid === selectedSid);
  });
  document.querySelectorAll('.mm-group-head').forEach((el) => {
    el.classList.toggle('active', el.dataset.sid === selectedSid);
  });
}

// ---------- 对话日志（消息粒度 + 正文检索，issue #206） ----------

async function loadMessages() {
  const listEl = document.getElementById('monitorList');
  if (!listEl) return;
  try {
    const data = await ctx.api.getMonitorMessages(currentMessageFilters());
    if (!ctx) return;
    if (data.error) {
      listEl.innerHTML = '<div class="empty error-text">对话日志加载失败：'
        + ctx.utils.esc(data.error) + '</div>';
      return;
    }
    messages = data.messages || [];
    const stamp = document.getElementById('monitorUpdated');
    if (stamp) {
      const total = data.total != null ? data.total : messages.length;
      stamp.textContent = '全部用户 · 对话日志 · 共 ' + total + ' 条'
        + (data.scan_capped ? '（扫描已达上限，请缩小时间范围）' : '')
        + (data.hasMore ? '（显示前 ' + messages.length + ' 条）' : '')
        + ' · ' + new Date().toLocaleTimeString('zh-CN', { hour12: false });
    }
    if (messages.length === 0) {
      listEl.innerHTML = '<div class="empty">没有匹配的对话消息</div>';
      return;
    }
    listEl.innerHTML = renderMessageGroups(messages);
    bindMessageRows(listEl);
    markSelected();
  } catch (e) {
    if (!ctx) return;
    listEl.innerHTML = '<div class="empty error-text">对话日志加载失败：' + ctx.utils.esc(e.message) + '</div>';
  }
}

/** 命中消息按会话分组：组头是会话（标题 + 用户 + 命中条数），组内是消息行 */
function renderMessageGroups(rows) {
  const groups = new Map();
  for (const m of rows) {
    const sid = m.session_id || '';
    if (!groups.has(sid)) {
      groups.set(sid, { sid, meta: m, items: [] });
    }
    groups.get(sid).items.push(m);
  }
  return [...groups.values()].map((g) => {
    const title = (g.meta.title || '').trim() || g.sid;
    const head = '<div class="mm-group-head" data-sid="' + ctx.utils.esc(g.sid) + '">'
      + '<span class="mm-group-title" title="' + ctx.utils.esc(title) + '">' + ctx.utils.esc(title) + '</span>'
      + '<span class="mm-group-meta">' + ctx.utils.esc(g.meta.user_id || 'unknown')
      + ' · ' + g.items.length + ' 条</span></div>';
    return '<div class="monitor-msg-group">' + head + g.items.map(messageRowHtml).join('') + '</div>';
  }).join('');
}

/** 单条对话日志行（点击跳转会话回放并定位到该消息） */
function messageRowHtml(m) {
  const isUser = m.role === 'user';
  const trace = (m.trace_id || '').trim();
  const traceShort = trace ? (trace.length > 8 ? trace.substring(0, 8) + '…' : trace) : '-';
  const keyword = document.getElementById('monContent').value.trim();
  return '<div class="monitor-msg" data-sid="' + ctx.utils.esc(m.session_id || '')
    + '" data-msg-id="' + ctx.utils.esc(m.msg_id || '') + '">'
    + '<span class="mm-role ' + (isUser ? 'user' : 'assistant') + '">' + (isUser ? '用户' : '助手') + '</span>'
    + '<span class="mm-content">' + highlightKeyword(m.content || '', keyword) + '</span>'
    + '<span class="mm-time">' + ctx.utils.esc((m.created_at || '').substring(0, 19).replace('T', ' ')) + '</span>'
    + '<span class="mm-trace" title="' + ctx.utils.esc(trace || '无 trace') + '">'
    + ctx.utils.esc(traceShort) + '</span></div>';
}

/** 关键词高亮：先按原文切段再逐段转义，避免把 HTML 注入进 mark */
function highlightKeyword(text, keyword) {
  if (!keyword) return ctx.utils.esc(text);
  const lower = String(text).toLowerCase();
  const target = keyword.toLowerCase();
  let out = '';
  let pos = 0;
  let idx;
  while ((idx = lower.indexOf(target, pos)) >= 0) {
    out += ctx.utils.esc(text.substring(pos, idx));
    out += '<mark>' + ctx.utils.esc(text.substring(idx, idx + keyword.length)) + '</mark>';
    pos = idx + keyword.length;
  }
  return out + ctx.utils.esc(text.substring(pos));
}

function bindMessageRows(root) {
  root.querySelectorAll('.monitor-msg').forEach((el) => {
    el.addEventListener('click', () => selectSession(el.dataset.sid, el.dataset.msgId));
  });
  root.querySelectorAll('.mm-group-head').forEach((el) => {
    el.addEventListener('click', () => selectSession(el.dataset.sid));
  });
}

// ---------- 问答回放 ----------

async function selectSession(sid, jumpMsgId) {
  if (!sid) return;
  selectedSid = sid;
  markSelected();
  const seq = ++detailSeq;
  const detailEl = document.getElementById('monitorDetail');
  if (!detailEl) return;
  detailEl.innerHTML = '<div class="empty">Loading conversation...</div>';
  // 归属校验（issue #158）：回放以会话归属者身份读取（监控页跨用户，取列表行 user_id）
  const session = sessions.find((x) => x.session_id === sid)
    || messages.find((x) => x.session_id === sid) || {};
  try {
    // 从对话日志跳转时放大首页条数，尽量让目标消息落在返回范围内
    const data = await ctx.api.getThreadHistory(sid, session.user_id, jumpMsgId ? { limit: 500 } : {});
    // 丢弃过期响应：等待期间用户可能已切到别的会话
    if (!ctx || seq !== detailSeq) return;
    detailEl.innerHTML = renderConversation(sid, session, data);
    highlightCode(detailEl);
    bindTraceCopy(detailEl);
    if (jumpMsgId) {
      scrollToMessage(detailEl, jumpMsgId);
    }
  } catch (e) {
    if (!ctx || seq !== detailSeq) return;
    detailEl.innerHTML = '<div class="empty error-text">会话详情加载失败：' + ctx.utils.esc(e.message) + '</div>';
  }
}

/** 从对话日志跳转：滚动到目标消息并短暂高亮 */
function scrollToMessage(root, msgId) {
  const el = [...root.querySelectorAll('[data-msg-id]')].find((x) => x.dataset.msgId === msgId);
  if (!el) {
    ctx.utils.toast('该消息不在最近 500 条历史中，请缩小时间范围后重试', 'warn');
    return;
  }
  el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  el.classList.add('msg-jump-flash');
  setTimeout(() => el.classList.remove('msg-jump-flash'), 2000);
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
  const failures = failureBanner(collectFailures(msgs));
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
  return header + failures + pending + body + files;
}

/** 工具调用失败终态（与后端 TOOL_ERROR_PREDICATE 一致） */
const ERROR_STATES = new Set(['error', 'denied', 'interrupted']);

function isErrorState(state) {
  return ERROR_STATES.has(state);
}

/** 失败态中文短标签 */
function stateLabel(state) {
  if (state === 'error') return '失败';
  if (state === 'denied') return '拒绝';
  if (state === 'interrupted') return '中断';
  return state || '';
}

/** 失败态样式类：error=红，denied/interrupted=黄 */
function stateClass(state) {
  return state === 'error' ? 'err' : 'warn';
}

/** 扫描本会话所有助手消息的工具调用，收集失败项（error/denied/interrupted） */
function collectFailures(msgs) {
  const items = [];
  for (const m of msgs) {
    for (const tc of (m.tool_calls || [])) {
      const st = tc.state || 'success';
      if (isErrorState(st)) {
        items.push({ name: tc.name || 'tool', state: st, output: tc.output != null ? String(tc.output) : '' });
      }
    }
  }
  return items;
}

/** 单行错误摘要：取首条非空行，截断 */
function previewLine(text) {
  const s = String(text == null ? '' : text).trim();
  if (!s) return '（无输出）';
  const first = s.split('\n').find((l) => l.trim()) || s;
  return truncate(first.trim(), 200);
}

/** 会话顶部失败摘要横幅（无失败返回空串） */
function failureBanner(items) {
  if (!items || items.length === 0) {
    return '';
  }
  const rows = items.map((it) =>
    '<div class="mf-row"><span class="mf-name">' + ctx.utils.esc(it.name) + '</span>'
    + '<span class="mf-state ' + stateClass(it.state) + '">' + ctx.utils.esc(stateLabel(it.state)) + '</span>'
    + '<span class="mf-msg" title="' + ctx.utils.esc(it.output ? truncate(String(it.output), 2000) : '') + '">'
    + ctx.utils.esc(previewLine(it.output)) + '</span></div>').join('');
  return '<div class="monitor-failures"><div class="mf-title">⚠ 本会话检测到 ' + items.length
    + ' 个失败的工具调用（下方已自动展开对应工具组）</div><div class="mf-list">' + rows + '</div></div>';
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
  // data-msg-id：对话日志跳转的定位锚点（issue #206）
  const msgId = m.msg_id ? ' data-msg-id="' + ctx.utils.esc(m.msg_id) + '"' : '';
  if (m.role === 'compaction') {
    return '<div class="monitor-compaction"' + msgId + '>— '
      + ctx.utils.esc(m.content || '上下文已压缩') + ' —</div>';
  }
  if (m.role === 'user') {
    return '<div class="msg user"' + msgId + '><div class="msg-bubble">' + userText(m.content || '') + '</div>'
      + traceChip(m.trace_id) + '</div>';
  }
  if (m.role === 'assistant' || m.role === 'agent') {
    return '<div class="msg assistant"' + msgId + '>' + assistantBlocks(m) + traceChip(m.trace_id) + '</div>';
  }
  // tool / 其它角色：直接展示内容
  return '<div class="msg system"' + msgId + '>' + ctx.utils.esc(m.content || '') + traceChip(m.trace_id) + '</div>';
}

/**
 * 每轮 OTel traceId 短码（issue #205）：前 8 位 + 复制按钮，完整值在 title 中；
 * 无 trace（OTEL 未开启 / state-only 消息）显示 "-"，与日志 pattern 无活跃 span 同口径。
 */
function traceChip(traceId) {
  const v = (traceId || '').trim();
  if (!v) {
    return '<div class="msg-trace"><span class="trace-label">trace</span>'
      + '<span class="trace-none">-</span></div>';
  }
  const short = v.length > 8 ? v.substring(0, 8) + '…' : v;
  return '<div class="msg-trace"><span class="trace-label">trace</span>'
    + '<span class="trace-id" title="' + ctx.utils.esc(v) + '">' + ctx.utils.esc(short) + '</span>'
    + '<button type="button" class="trace-copy" data-trace="' + ctx.utils.esc(v) + '">复制</button></div>';
}

/** 绑定 trace 复制按钮（详情渲染后调用） */
function bindTraceCopy(root) {
  root.querySelectorAll('.trace-copy').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      copyTrace(btn.dataset.trace);
    });
  });
}

/** 复制 traceId：优先 Clipboard API，非安全上下文降级为 textarea + execCommand */
async function copyTrace(text) {
  if (!text) return;
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
    } else {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
    }
    ctx.utils.toast('traceId 已复制', 'success');
  } catch (e) {
    ctx.utils.toast('复制失败：' + (e.message || e), 'error');
  }
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
  const failCount = calls.filter((c) => isErrorState(c.state)).length;
  const rows = calls.map(toolRowHtml).join('');
  const label = calls.length + ' 次工具调用' + (failCount ? ' · ' + failCount + ' 失败' : '');
  return '<div class="tool-group' + (failCount ? ' open' : '') + '">'
    + '<div class="tool-group-header' + (failCount ? ' has-error' : '')
    + '" onclick="window.App.toolGroupToggle(this)">'
    + '<span class="tg-arrow">▶</span><span>' + ctx.utils.esc(label) + '</span></div>'
    + '<div class="tool-group-body">' + rows + '</div></div>';
}

function toolRowHtml(c) {
  const err = isErrorState(c.state);
  const body = (c.argsText ? '<div class="tc-label">参数</div><pre>' + ctx.utils.esc(truncate(c.argsText, 4000)) + '</pre>' : '')
    + (c.resultText
        ? '<div class="tc-label">结果' + (c.outputTruncated ? '（已截断）' : '') + '</div><pre>'
          + ctx.utils.esc(truncate(c.resultText, 4000)) + '</pre>'
        : '');
  return '<div class="tool-call-row' + (err ? ' has-error open' : '')
    + '" onclick="window.App.toolRowToggle(this)">'
    + '<span class="tc-name">' + ctx.utils.esc(c.name || 'tool') + '</span>'
    + (err ? '<span class="tc-err-label">' + ctx.utils.esc(stateLabel(c.state)) + '</span>' : '')
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
