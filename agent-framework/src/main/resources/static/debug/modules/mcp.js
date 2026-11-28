let ctx = null;
let mcpData = [];
let regStatus = {};

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header">
        <h2>MCP 服务</h2>
        <span class="sub">
          <span id="mcpSummary"></span>
          <button class="btn small primary" id="btnReloadAllMcp">重载全部 MCP</button>
          <button class="btn small" id="btnRefreshMcp">刷新</button>
        </span>
      </div>
      <div class="module-scroll" id="mcpBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnRefreshMcp').addEventListener('click', loadMcp);
    document.getElementById('btnReloadAllMcp').addEventListener('click', (e) => reloadMcpServer(null, e.currentTarget));
    loadMcp();
  },
  unmount() { ctx = null; }
};

async function reloadMcpServer(serverName, btnEl) {
  if (!serverName && !window.confirm('确认重载全部 MCP 服务？重连期间相关工具调用可能短暂受影响。')) {
    return;
  }
  if (btnEl) btnEl.disabled = true;
  try {
    const res = await ctx.api.triggerReload('mcp', serverName || undefined);
    if (!ctx) return;
    const failedServers = Array.isArray(res && res.mcp_servers)
      ? res.mcp_servers.filter((item) => item && item.ok === false)
      : [];
    if (res && res.error) {
      ctx.utils.toast((serverName ? '重载 ' + serverName : '重载 MCP') + ' 异常: ' + res.error, 'error');
    } else if (failedServers.length > 0) {
      const names = failedServers.map((item) => (item.server || '?') + '(' + (item.action || 'failed') + ')').join(', ');
      ctx.utils.toast('部分 MCP 重载失败: ' + names, 'warn');
    } else {
      ctx.utils.toast(serverName ? '已重载 ' + serverName : '已重载全部 MCP 服务', 'success');
    }
    await loadMcp();
  } catch (e) {
    if (!ctx) return;
    ctx.utils.toast('MCP 重载失败: ' + e.message, 'error');
  } finally {
    if (btnEl) btnEl.disabled = false;
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
    const rawReg = reloadInfo && reloadInfo.registeredServers;
    if (Array.isArray(rawReg)) {
      regStatus = {};
      for (const item of rawReg) {
        if (item && item.server) regStatus[item.server] = item;
      }
    } else if (rawReg && typeof rawReg === 'object') {
      regStatus = rawReg;
    } else {
      regStatus = {};
    }
  } catch (e) {
    if (!ctx) return;
    body.innerHTML = '<div class="empty error-text">Failed to load MCP servers: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  document.getElementById('mcpSummary').textContent = mcpData.length + ' server(s)';
  if (mcpData.length === 0) {
    body.innerHTML = '<div class="empty">暂无 MCP 服务</div>';
    return;
  }

  body.innerHTML = mcpData.map((m, idx) => {
    const serverName = m.server || '?';
    const live = Object.prototype.hasOwnProperty.call(regStatus, serverName) ? regStatus[serverName] : undefined;
    let liveBadge = '';
    if (live !== undefined) {
      if (live && typeof live === 'object' && typeof live.connected === 'boolean') {
        const cls = live.connected ? 'green' : 'red';
        const label = live.connected
          ? ('已连接' + (live.tool_count != null ? ' · ' + live.tool_count + ' 工具' : ''))
          : '未连接';
        liveBadge = '<span class="badge ' + cls + '">' + ctx.utils.esc(label) + '</span>';
      } else {
        const text = typeof live === 'object' ? (live.status || JSON.stringify(live)) : String(live);
        const bad = text.toLowerCase().includes('fail') || text.toLowerCase().includes('err') || text.toLowerCase().includes('false');
        liveBadge = '<span class="badge ' + (bad ? 'red' : 'green') + '">' + ctx.utils.esc(text) + '</span>';
      }
    }
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
        '<button class="btn small mcp-detail-btn" data-idx="' + idx + '">查看配置</button>' +
        '<button class="btn small accent mcp-reload-btn" data-server="' + ctx.utils.esc(serverName) + '">重载此服务</button>' +
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
    btn.addEventListener('click', () => reloadMcpServer(btn.dataset.server, btn));
  });
}
