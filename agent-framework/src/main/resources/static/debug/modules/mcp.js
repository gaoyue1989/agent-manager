/* ===== MCP 模块：服务器列表 + 配置详情 ===== */

let ctx = null;
let mcpData = [];

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>MCP Servers</h2><span class="sub" id="mcpSummary"></span></div>
      <div class="module-scroll" id="mcpBody"><div class="empty">Loading...</div></div>
    </div>`;
    loadMcp();
  },
  unmount() { ctx = null; }
};

async function loadMcp() {
  const body = document.getElementById('mcpBody');
  try {
    mcpData = (await ctx.api.getMcpServers()) || [];
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load MCP servers: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  document.getElementById('mcpSummary').textContent = mcpData.length + ' server(s)';

  if (mcpData.length === 0) {
    body.innerHTML = '<div class="empty">No MCP servers</div>';
    return;
  }

  body.innerHTML = mcpData.map((m, idx) => {
    const badges = [
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
      '<div class="panel-header"><span class="title">🔌 ' + ctx.utils.esc(m.server || '?') + '</span>' +
      '<span class="count">' + badges + '</span></div>' +
      '<div class="panel-body">' +
      info.map(([k, v]) => '<div class="info-row"><span class="label">' + k + '</span>' +
        '<span class="value" style="white-space:normal">' + ctx.utils.esc(v) + '</span></div>').join('') +
      '<button class="btn small mcp-detail-btn" data-idx="' + idx + '" style="margin-top:8px">View Config</button>' +
      '</div></div>';
  }).join('');

  body.querySelectorAll('.mcp-detail-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      ctx.utils.showJsonModal('MCP Server: ' + (mcpData[Number(btn.dataset.idx)].server || ''), mcpData[Number(btn.dataset.idx)]);
    });
  });
}
