/* ===== Workspace 模块：工作区文件浏览 ===== */

let ctx = null;
let wsData = {};

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Workspace</h2>
        <span class="sub"><button class="btn small" id="btnReloadWs">Reload</button></span></div>
      <div class="module-scroll" id="wsBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnReloadWs').addEventListener('click', loadWorkspace);
    loadWorkspace();
  },
  unmount() { ctx = null; }
};

function sandboxNotice() {
  if (!wsData.sandbox_mode) return '';
  return '<div class="panel"><div class="panel-header"><span class="title" style="color:var(--yellow)">Sandbox Mode</span></div>' +
    '<div class="panel-body">' +
    '<div class="info-row"><span class="label">说明</span><span class="value">本目录为<strong>静态模板层</strong>（投影源），非沙箱内实时文件</span></div>' +
    '<div class="info-row"><span class="label">运行时文件</span><span class="value">MEMORY.md / memory/ 在 KV 中，用 <strong>Memory</strong> 页查看</span></div>' +
    '<div class="info-row"><span class="label">沙箱状态</span><span class="value">经 OpenSandbox Server 管理（见 Sandbox 页）</span></div>' +
    '</div></div>';
}

async function loadWorkspace() {
  const body = document.getElementById('wsBody');
  try {
    wsData = (await ctx.api.getWorkspace()) || {};
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load workspace: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  if (!wsData.exists) {
    body.innerHTML = sandboxNotice() + '<div class="panel"><div class="panel-header"><span class="title">Workspace</span></div>' +
      '<div class="panel-body"><div class="info-row"><span class="label">Path</span>' +
      '<span class="value">' + ctx.utils.esc(wsData.path || '') + '</span></div>' +
      '<div class="info-row"><span class="label">Status</span><span class="value" style="color:var(--yellow)">Not initialized</span></div>' +
      '</div></div>';
    return;
  }

  const files = wsData.files || [];
  let html = sandboxNotice() + '<div class="panel"><div class="panel-header"><span class="title">Workspace Files</span>' +
    '<span class="count">' + files.length + ' file(s)</span></div><div class="panel-body">' +
    '<div class="info-row"><span class="label">Path</span><span class="value" style="white-space:normal">' + ctx.utils.esc(wsData.path) + '</span></div>';

  if (files.length === 0) {
    html += '<div class="empty">No files in workspace</div>';
  } else {
    html += '<table class="data-table"><thead><tr><th>Path</th><th>Size</th><th>Modified</th></tr></thead><tbody>';
    for (const f of files) {
      html += '<tr><td class="mono">' + ctx.utils.esc(f.path) + '</td>' +
        '<td>' + ctx.utils.formatBytes(f.size) + '</td>' +
        '<td>' + ctx.utils.esc((f.modified || '').substring(0, 19)) + '</td></tr>';
    }
    html += '</tbody></table>';
  }
  html += '</div></div>';

  body.innerHTML = html;
}
