/* ===== Memory 模块：MEMORY.md + memory/ 文件（按用户分组） ===== */

let ctx = null;
let memoryData = {};

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>记忆</h2>
        <span class="sub"><button class="btn small" id="btnReloadMemory">刷新</button></span></div>
      <div class="module-scroll" id="memoryBody"><div class="empty">加载中...</div></div>
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
    if (!ctx) return; // 已切走（unmount 置空 ctx）：丢弃过期渲染，避免读 null 的 utils
    body.innerHTML = '<div class="empty error-text">加载记忆失败：' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  if (!ctx) return; // await 期间可能已 unmount，过期响应不再渲染

  const users = memoryData.users || {};
  const entries = Object.entries(users);

  if (entries.length === 0) {
    body.innerHTML = '<div class="empty">暂无记忆数据（agent_fs 中暂无 MEMORY.md / memory/ 文件）</div>';
    return;
  }

  body.innerHTML = entries.map(([ns, user]) => {
    const files = user.files || [];
    let html = '<div class="panel">' +
      '<div class="panel-header"><span class="title">' + ctx.utils.esc(ns) + '</span>' +
      '<span class="count">' + files.length + ' 个文件</span></div>' +
      '<div class="panel-body">';

    if (files.length) {
      html += '<table class="data-table"><thead><tr><th>路径</th><th>大小</th><th>更新时间</th><th>操作</th></tr></thead><tbody>';
      for (const f of files) {
        html += '<tr><td class="mono">' + ctx.utils.esc(f.path) + '</td>' +
          '<td>' + ctx.utils.formatBytes(f.size) + '</td>' +
          '<td>' + ctx.utils.esc((f.updated_at || '').substring(0, 19)) + '</td>' +
          '<td><button class="btn small memory-file-btn" data-ns="' + ctx.utils.esc(ns) + '" data-path="' + ctx.utils.esc(f.path) + '">查看</button></td></tr>';
      }
      html += '</tbody></table>';
    } else {
      html += '<div class="empty">暂无文件</div>';
    }
    html += '</div></div>';
    return html;
  }).join('');

  body.querySelectorAll('.memory-file-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const ns = btn.dataset.ns;
      const path = btn.dataset.path;
      const user = users[ns] || {};
      const file = (user.files || []).find((f) => f.path === path) || {};
      const text = (file.content != null && file.content !== '')
        ? file.content
        : '(content not loaded; path: ' + path + ')';
      ctx.utils.showTextModal('Memory: ' + ns + ' / ' + path, text);
    });
  });
}
