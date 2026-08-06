/* ===== Tools 模块：内置 / 自定义 / MCP 工具分类展示 ===== */

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
    data = await ctx.api.getTools();
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load tools: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  const builtin = data.builtin || [];
  const custom = data.custom || [];
  const mcp = data.mcp || [];

  document.getElementById('toolsSummary').textContent =
    builtin.length + ' builtin / ' + custom.length + ' custom / ' + mcp.length + ' mcp';

  let html = '';

  html += renderSection('Built-in Tools', builtin.length, builtin.map((t) => ({
    name: t.name || t, badge: t.readOnly ? '<span class="badge green">readOnly</span>' : ''
  })));

  html += renderSection('Custom Tools', custom.length, custom.map((t) => ({
    name: t.name || t, badge: t.readOnly ? '<span class="badge green">readOnly</span>' : ''
  })));

  if (mcp.length > 0) {
    html += '<div class="panel"><div class="panel-header"><span class="title">MCP Tools</span><span class="count">' + mcp.length + '</span></div><div class="panel-body">';
    for (const server of mcp) {
      const tools = server.tools || server || [];
      html += '<div style="margin:6px 0"><strong style="color:var(--accent)">🔌 ' + ctx.utils.esc(server.server || 'unknown') + '</strong>' +
        '<span class="badge dim" style="margin-left:6px">' + (tools.length || 0) + ' tools</span></div>';
      if (tools.length) {
        html += '<div style="padding-left:12px">' +
          tools.map((t) => '<span class="badge">' + ctx.utils.esc(t.name || t) + '</span>').join('') +
          '</div>';
      }
    }
    html += '</div></div>';
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
