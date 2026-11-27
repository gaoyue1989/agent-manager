let ctx = null;
let mcpData = [];
let regStatus = {};

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header">
        <h2>MCP Servers</h2>
        <span class="sub">
          <span id="mcpSummary"></span>
          <button class="btn small primary" id="btnReloadAllMcp">Reload All MCP</button>
          <button class="btn small" id="btnRefreshMcp">Refresh</button>
        </span>
      </div>
      <div class="module-scroll" id="mcpBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnRefreshMcp').addEventListener('click', loadMcp);
    document.getElementById('btnReloadAllMcp').addEventListener('click', () => reloadMcpServer(null));
    loadMcp();
  },
  unmount() { ctx = null; }
};

async function reloadMcpServer(serverName) {
  try {
    const res = await ctx.api.triggerReload('mcp', serverName || undefined);
    if (!ctx) return;
    ctx.utils.toast((serverName ? 'Reloaded ' + serverName : 'Reloaded all MCP servers') +
      (res && res.error ? ' (' + res.error + ')' : ''), res && res.error ? 'warn' : 'success');
    await loadMcp();
  } catch (e) {
    if (!ctx) return;
    ctx.utils.toast('MCP reload failed: ' + e.message, 'error');
  }
}

async function loadMcp() {
  const body = document.getElementById('mcpBody');
  try {
    const [servers, reloadInfo] = await Promise.all([
      ctx.api.getMcpServers(),
      ctx.api.getReloadStatus().catch(() => ({}))
    ]);
    if (!ctx) return;
    mcpData = servers || [];
    regStatus = (reloadInfo && reloadInfo.registeredServers) || {};
  } catch (e) {
    if (!ctx) return;
    body.innerHTML = '<div class="empty error-text">Failed to load MCP servers: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  document.getElementById('mcpSummary').textContent = mcpData.length + ' server(s)';
  if (mcpData.length === 0) {
    body.innerHTML = '<div class="empty">No MCP servers</div>';
    return;
  }

  body.innerHTML = mcpData.map((m, idx) => {
    const serverName = m.server || '?';
    const live = regStatus[serverName];
    const liveBadge = live !== undefined
      ? '<span class="badge ' + (String(live).toLowerCase().includes('fail') || String(live).toLowerCase().includes('err') ? 'red' : 'green') + '">' +
        ctx.utils.esc(typeof live === 'object' ? (live.status || JSON.stringify(live)) : String(live)) + '</span>'
      : '';
    const badges = [
      liveBadge,
      m.connection_type ? '<span class="badge dim">' + ctx.utils.esc(m.connection_type) + '</span>' : '',
      m.vendor ? '<span class="badge dim">' + ctx.utils.esc(m.vendor) + '</span>' : ''
    ].join('');
    const info = [
      ['Server', m.server],
      ['Vendor', m.vendor],
      ['Type', m.connection_type],
      ['URL', m.url],
      ['Tools', m.tool_count != null ? String(m.tool_count) : '']
    ].filter(([, v]) => v != null && v !== '');
    return '<div class="panel">' +
      '<div class="panel-header"><span class="title">' + ctx.utils.esc(serverName) + '</span>' +
      '<span class="count">' + badges + '</span></div>' +
      '<div class="panel-body">' +
      info.map(([k, v]) => '<div class="info-row"><span class="label">' + k + '</span>' +
        '<span class="value" style="white-space:normal">' + ctx.utils.esc(v) + '</span></div>').join('') +
      '<div style="display:flex;gap:8px;margin-top:10px">' +
        '<button class="btn small mcp-detail-btn" data-idx="' + idx + '">View Config</button>' +
        '<button class="btn small accent mcp-reload-btn" data-server="' + ctx.utils.esc(serverName) + '">Reload Server</button>' +
      '</div>' +
      '</div></div>';
  }).join('');

  body.querySelectorAll('.mcp-detail-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const item = mcpData[Number(btn.dataset.idx)];
      ctx.utils.showJsonModal('MCP Server: ' + (item.server || ''), item);
    });
  });
  body.querySelectorAll('.mcp-reload-btn').forEach((btn) => {
    btn.addEventListener('click', () => reloadMcpServer(btn.dataset.server));
  });
}
