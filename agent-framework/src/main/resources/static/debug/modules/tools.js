let ctx = null;
let lastData = null;
let searchQuery = '';

export default {
  mount(container, c) {
    ctx = c;
    searchQuery = '';
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header">
        <h2>工具列表</h2>
        <div class="tool-filter-bar">
          <input id="toolsSearch" class="input" type="search" placeholder="按名称、服务或标签筛选工具..." style="width:240px">
        </div>
        <span class="sub" id="toolsSummary"></span>
      </div>
      <div class="module-scroll" id="toolsBody"><div class="empty">加载中...</div></div>
    </div>`;
    document.getElementById('toolsSearch').addEventListener('input', (e) => {
      searchQuery = (e.target.value || '').trim().toLowerCase();
      renderTools();
    });
    loadTools();
  },
  unmount() {
    ctx = null;
    lastData = null;
  }
};

async function loadTools() {
  const body = document.getElementById('toolsBody');
  try {
    lastData = await ctx.api.getTools(true);
  } catch (e) {
    if (!ctx) return;
    body.innerHTML = '<div class="empty error-text">加载工具列表失败: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  if (!ctx) return;
  renderTools();
}

function matchesFilter(tool) {
  if (!searchQuery) return true;
  const hay = [
    tool.name,
    tool.fullName,
    tool.server,
    tool.description,
    tool.directReturn ? 'direct return 直返' : '',
    tool.uiResourceUri ? 'mcp app ui mcp 界面' : '',
    tool.appOnly ? 'app only 仅卡片可调' : '',
    (tool.sessionControllable === false && !tool.appOnly) ? 'always on 始终启用' : ''
  ].filter(Boolean).join(' ').toLowerCase();
  return hay.includes(searchQuery);
}

function renderBadges(t) {
  const badges = [];
  if (t.directReturn) badges.push('<span class="badge accent">直返</span>');
  if (t.uiResourceUri) badges.push('<span class="badge green" title="' + ctx.utils.esc(t.uiResourceUri) + '">MCP 界面</span>');
  if (t.appOnly) badges.push('<span class="badge yellow">仅卡片可调</span>');
  if (t.sessionControllable === false && !t.appOnly) badges.push('<span class="badge dim">始终启用</span>');
  return badges.join('');
}

function renderToolCard(t) {
  return '<div class="tool-item-card">' +
    '<div class="tool-item-head">' +
      '<span class="tool-item-name" title="' + ctx.utils.esc(t.fullName || t.name) + '">' + ctx.utils.esc(t.name) + '</span>' +
      renderBadges(t) +
    '</div>' +
    (t.description ? '<div class="tool-item-desc">' + ctx.utils.esc(t.description) + '</div>' : '') +
  '</div>';
}

function renderTools() {
  const body = document.getElementById('toolsBody');
  if (!body || !lastData) return;

  const tools = lastData.tools || [];
  const sdkInternal = lastData.sdkInternal || [];
  const mcpTools = tools.filter((t) => t.category === 'mcp' && matchesFilter(t));
  const internalTools = tools.filter((t) => t.category !== 'mcp' && matchesFilter(t));
  const filteredSdk = sdkInternal.filter(matchesFilter);

  const totalAll = tools.length + sdkInternal.length;
  document.getElementById('toolsSummary').textContent =
    totalAll + ' total (' + mcpTools.length + ' mcp · ' + internalTools.length + ' custom · ' + filteredSdk.length + ' sdk)';

  let html = '';

  if (mcpTools.length > 0) {
    const grouped = {};
    mcpTools.forEach((t) => {
      const server = t.server || 'unknown';
      if (!grouped[server]) grouped[server] = [];
      grouped[server].push(t);
    });
    html += '<div class="panel"><div class="panel-header"><span class="title">MCP 业务工具</span><span class="count">' + mcpTools.length + '</span></div><div class="panel-body">';
    for (const [server, serverTools] of Object.entries(grouped)) {
      html += '<div style="margin:8px 0 6px;display:flex;align-items:center;gap:8px">' +
        '<strong style="color:var(--accent);font-family:var(--mono)">' + ctx.utils.esc(server) + '</strong>' +
        '<span class="badge dim">' + serverTools.length + '</span></div>' +
        '<div class="tool-item-grid" style="margin-bottom:12px">' + serverTools.map(renderToolCard).join('') + '</div>';
    }
    html += '</div></div>';
  }

  if (internalTools.length > 0) {
    html += '<div class="panel"><div class="panel-header"><span class="title">自定义内置工具</span><span class="count">' + internalTools.length + '</span></div>' +
      '<div class="panel-body"><div class="tool-item-grid">' + internalTools.map(renderToolCard).join('') + '</div></div></div>';
  }

  if (filteredSdk.length > 0) {
    html += '<div class="panel"><div class="panel-header"><span class="title">SDK 内置工具</span><span class="count">' + filteredSdk.length + '</span></div>' +
      '<div class="panel-body"><div class="tool-item-grid">' + filteredSdk.map(renderToolCard).join('') + '</div></div></div>';
  }

  body.innerHTML = html || '<div class="empty">' + (searchQuery ? '无匹配工具' : '暂无工具') + '</div>';
}
