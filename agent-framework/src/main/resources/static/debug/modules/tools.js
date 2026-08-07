/* ===== Tools 模块：MCP / 内置工具分类展示 ===== */

let ctx = null;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Tools</h2><span class="sub" id="toolsSummary"></span></div>
      <div class="module-scroll" id="toolsBody"><div class="empty">Loading...</div></div>
    </div>`;
    loadTools();
  },
  unmount() { ctx = null; }
};

async function loadTools() {
  const body = document.getElementById('toolsBody');
  let data;
  try {
    data = await ctx.api.getTools(true);
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load tools: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  const tools = data.tools || [];
  const mcpTools = tools.filter((t) => t.category === 'mcp');
  const internalTools = tools.filter((t) => t.category === 'internal');

  document.getElementById('toolsSummary').textContent =
    tools.length + ' total (' + internalTools.length + ' internal / ' + mcpTools.length + ' mcp)';

  let html = '';

  // MCP 工具（按 server 分组）
  if (mcpTools.length > 0) {
    const grouped = {};
    mcpTools.forEach((t) => {
      const server = t.server || 'unknown';
      if (!grouped[server]) grouped[server] = [];
      grouped[server].push(t);
    });

    html += '<div class="panel"><div class="panel-header"><span class="title">MCP Tools</span><span class="count">' + mcpTools.length + '</span></div><div class="panel-body">';
    for (const [server, serverTools] of Object.entries(grouped)) {
      html += '<div style="margin:6px 0"><strong style="color:var(--accent)">🔌 ' + ctx.utils.esc(server) + '</strong>' +
        '<span class="badge dim" style="margin-left:6px">' + serverTools.length + ' tools</span></div>';
      html += '<div style="padding-left:12px">' +
        serverTools.map((t) => '<span class="badge" title="' + ctx.utils.esc(t.description || '') + '">' + ctx.utils.esc(t.name) + '</span>').join('') +
        '</div>';
    }
    html += '</div></div>';
  }

  // 内置工具
  if (internalTools.length > 0) {
    html += renderSection('Built-in Tools', internalTools.length, internalTools.map((t) => ({
      name: t.name, badge: ''
    })));
  }

  body.innerHTML = html || '<div class="empty">No tools</div>';
}

function renderSection(title, count, items) {
  return '<div class="panel"><div class="panel-header"><span class="title">' + title + '</span>' +
    '<span class="count">' + count + '</span></div><div class="panel-body">' +
    (items.length
      ? items.map((i) => '<div class="list-item"><span class="name">' + ctx.utils.esc(i.name) + '</span>' +
        (i.badge || '') + '<span class="meta"></span></div>').join('')
      : '<div class="empty">None</div>') +
    '</div></div>';
}
