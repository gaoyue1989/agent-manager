/* ===== Memory 模块：MEMORY.md + memory/ 文件（按用户分组） ===== */

let ctx = null;
let memoryData = {};

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Memory</h2>
        <span class="sub"><button class="btn small" id="btnReloadMemory">Reload</button></span></div>
      <div class="module-scroll" id="memoryBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnReloadMemory').addEventListener('click', loadMemory);
    loadMemory();
  },
  unmount() { ctx = null; }
};

async function loadMemory() {
  const body = document.getElementById('memoryBody');
  try {
    memoryData = (await ctx.api.getMemory()) || {};
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load memory: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  const users = memoryData.users || {};
  const entries = Object.entries(users);

  if (entries.length === 0) {
    body.innerHTML = '<div class="empty">No memory stored yet. (agent_fs 中暂无 MEMORY.md / memory/ 文件)</div>';
    return;
  }

  body.innerHTML = entries.map(([ns, user]) => {
    const files = user.files || [];
    const memoryMd = user.memory_md;
    let html = '<div class="panel">' +
      '<div class="panel-header"><span class="title">👤 ' + ctx.utils.esc(ns) + '</span>' +
      '<span class="count">' + files.length + ' file(s)</span></div>' +
      '<div class="panel-body">';

    // 文件列表
    if (files.length) {
      html += '<table class="data-table"><thead><tr><th>Path</th><th>Size</th><th>Updated</th><th></th></tr></thead><tbody>';
      for (const f of files) {
        html += '<tr><td class="mono">' + ctx.utils.esc(f.path) + '</td>' +
          '<td>' + ctx.utils.formatBytes(f.size) + '</td>' +
          '<td>' + ctx.utils.esc((f.updated_at || '').substring(0, 19)) + '</td>' +
          '<td><button class="btn small memory-file-btn" data-ns="' + ctx.utils.esc(ns) + '" data-path="' + ctx.utils.esc(f.path) + '">View</button></td></tr>';
      }
      html += '</tbody></table>';
    } else {
      html += '<div class="empty">No files</div>';
    }
    html += '</div></div>';
    return html;
  }).join('');

  body.querySelectorAll('.memory-file-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const ns = btn.dataset.ns;
      const path = btn.dataset.path;
      const user = users[ns] || {};
      const text = path === 'MEMORY.md' && user.memory_md != null
        ? user.memory_md
        : '(content not loaded; path: ' + path + ')';
      ctx.utils.showTextModal('Memory: ' + ns + ' / ' + path, text);
    });
  });
}
