/* ===== Logs 模块：系统日志（内存 Appender） ===== */

let ctx = null;
let refreshTimer = null;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Logs</h2>
        <select id="logLevel" class="input">
          <option value="all">All</option>
          <option value="DEBUG">DEBUG</option>
          <option value="INFO">INFO</option>
          <option value="WARN">WARN</option>
          <option value="ERROR">ERROR</option>
        </select>
        <span class="sub"><button class="btn small" id="btnReloadLogs">Refresh</button></span></div>
      <div class="module-scroll" id="logsBody" style="padding:8px 0"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnReloadLogs').addEventListener('click', loadLogs);
    document.getElementById('logLevel').addEventListener('change', loadLogs);
    loadLogs();
    refreshTimer = setInterval(loadLogs, 5000);
  },
  unmount() {
    if (refreshTimer) clearInterval(refreshTimer);
    ctx = null;
  }
};

async function loadLogs() {
  const body = document.getElementById('logsBody');
  if (!body) return;
  const level = document.getElementById('logLevel').value;
  try {
    const data = await ctx.api.getLogs(level, 200);
    const logs = data.logs || [];
    if (logs.length === 0) {
      body.innerHTML = '<div class="empty">No logs</div>';
      return;
    }
    body.innerHTML = logs.map((line) => {
      const m = line.match(/\s(DEBUG|INFO|WARN|ERROR)\s/);
      const lv = m ? m[1] : '';
      return '<div class="log-line"><span class="lv-' + lv + '">' + ctx.utils.esc(line) + '</span></div>';
    }).join('');
    body.scrollTop = body.scrollHeight;
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load logs: ' + ctx.utils.esc(e.message) + '</div>';
  }
}
