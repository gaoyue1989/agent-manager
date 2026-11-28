/* ===== Database 模块：连接状态 + 表统计 + 连接池 ===== */

let ctx = null;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>数据库</h2>
        <span class="sub"><button class="btn small" id="btnReloadDb">刷新</button></span></div>
      <div class="module-scroll" id="dbBody"><div class="empty">加载中...</div></div>
    </div>`;
    document.getElementById('btnReloadDb').addEventListener('click', loadDb);
    loadDb();
  },
  unmount() { ctx = null; }
};

async function loadDb() {
  const body = document.getElementById('dbBody');
  let data;
  try {
    data = await ctx.api.getDatabaseStatus();
  } catch (e) {
    if (!ctx) return; // 已切走（unmount 置空 ctx）：丢弃过期渲染，避免读 null 的 utils
    body.innerHTML = '<div class="empty error-text">加载数据库状态失败：' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  if (!ctx) return; // await 期间可能已 unmount，过期响应不再渲染

  if (!data.connected) {
    body.innerHTML = '<div class="panel"><div class="panel-header"><span class="title">连接状态</span></div>' +
      '<div class="panel-body"><div class="info-row"><span class="label">状态</span>' +
      '<span class="value" style="color:var(--red)">未连接</span></div>' +
      '<div class="info-row"><span class="label">错误</span><span class="value">' + ctx.utils.esc(data.error || '') + '</span></div>' +
      '</div></div>';
    return;
  }

  let html = '';

  html += '<div class="panel"><div class="panel-header"><span class="title">连接状态</span></div><div class="panel-body">' +
    '<div class="info-row"><span class="label">状态</span><span class="value" style="color:var(--green)">已连接</span></div>' +
    '<div class="info-row"><span class="label">数据库</span><span class="value">' + ctx.utils.esc(data.database || '') + '</span></div>' +
    '<div class="info-row"><span class="label">连接地址</span><span class="value" style="white-space:normal">' + ctx.utils.esc(data.url || '') + '</span></div>' +
    '</div></div>';

  const tables = data.tables || {};
  html += '<div class="panel"><div class="panel-header"><span class="title">数据表统计</span></div><div class="panel-body">' +
    '<table class="data-table"><thead><tr><th>表名</th><th>行数</th></tr></thead><tbody>';
  for (const [name, info] of Object.entries(tables)) {
    html += '<tr><td class="mono">' + ctx.utils.esc(name) + '</td>' +
      '<td>' + (info.rows != null ? info.rows : '<span class="error-text">' + ctx.utils.esc(info.error || '?') + '</span>') + '</td></tr>';
  }
  html += '</tbody></table></div></div>';

  const pool = data.connection_pool || {};
  html += '<div class="panel"><div class="panel-header"><span class="title">连接池（Hikari）</span></div><div class="panel-body">' +
    '<div class="info-row"><span class="label">活跃</span><span class="value">' + ctx.utils.esc(String(pool.active ?? '?')) + '</span></div>' +
    '<div class="info-row"><span class="label">空闲</span><span class="value">' + ctx.utils.esc(String(pool.idle ?? '?')) + '</span></div>' +
    '<div class="info-row"><span class="label">总计</span><span class="value">' + ctx.utils.esc(String(pool.total ?? '?')) + '</span></div>' +
    '<div class="info-row"><span class="label">上限</span><span class="value">' + ctx.utils.esc(String(pool.max ?? '?')) + '</span></div>' +
    '</div></div>';

  body.innerHTML = html;
}
