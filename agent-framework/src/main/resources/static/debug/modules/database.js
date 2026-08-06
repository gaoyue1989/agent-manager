/* ===== Database 模块：连接状态 + 表统计 + 连接池 ===== */

let ctx = null;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Database</h2>
        <span class="sub"><button class="btn small" id="btnReloadDb">Reload</button></span></div>
      <div class="module-scroll" id="dbBody"><div class="empty">Loading...</div></div>
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
    body.innerHTML = '<div class="empty error-text">Failed to load database status: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  if (!data.connected) {
    body.innerHTML = '<div class="panel"><div class="panel-header"><span class="title">Connection</span></div>' +
      '<div class="panel-body"><div class="info-row"><span class="label">Status</span>' +
      '<span class="value" style="color:var(--red)">Disconnected</span></div>' +
      '<div class="info-row"><span class="label">Error</span><span class="value">' + ctx.utils.esc(data.error || '') + '</span></div>' +
      '</div></div>';
    return;
  }

  let html = '';

  // 连接信息
  html += '<div class="panel"><div class="panel-header"><span class="title">Connection</span></div><div class="panel-body">' +
    '<div class="info-row"><span class="label">Status</span><span class="value" style="color:var(--green)">Connected</span></div>' +
    '<div class="info-row"><span class="label">Database</span><span class="value">' + ctx.utils.esc(data.database || '') + '</span></div>' +
    '<div class="info-row"><span class="label">URL</span><span class="value" style="white-space:normal">' + ctx.utils.esc(data.url || '') + '</span></div>' +
    '</div></div>';

  // 表统计
  const tables = data.tables || {};
  html += '<div class="panel"><div class="panel-header"><span class="title">Tables</span></div><div class="panel-body">' +
    '<table class="data-table"><thead><tr><th>Table</th><th>Rows</th></tr></thead><tbody>';
  for (const [name, info] of Object.entries(tables)) {
    html += '<tr><td class="mono">' + ctx.utils.esc(name) + '</td>' +
      '<td>' + (info.rows != null ? info.rows : '<span class="error-text">' + ctx.utils.esc(info.error || '?') + '</span>') + '</td></tr>';
  }
  html += '</tbody></table></div></div>';

  // 连接池
  const pool = data.connection_pool || {};
  html += '<div class="panel"><div class="panel-header"><span class="title">Connection Pool (Hikari)</span></div><div class="panel-body">' +
    '<div class="info-row"><span class="label">Active</span><span class="value">' + ctx.utils.esc(String(pool.active ?? '?')) + '</span></div>' +
    '<div class="info-row"><span class="label">Idle</span><span class="value">' + ctx.utils.esc(String(pool.idle ?? '?')) + '</span></div>' +
    '<div class="info-row"><span class="label">Total</span><span class="value">' + ctx.utils.esc(String(pool.total ?? '?')) + '</span></div>' +
    '<div class="info-row"><span class="label">Max</span><span class="value">' + ctx.utils.esc(String(pool.max ?? '?')) + '</span></div>' +
    '</div></div>';

  body.innerHTML = html;
}
