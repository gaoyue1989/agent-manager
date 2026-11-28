let ctx = null;
let refreshTimer = null;
let hasLoadedOnce = false;

export default {
  mount(container, c) {
    ctx = c;
    hasLoadedOnce = false;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>日志</h2>
        <select id="logLevel" class="input">
          <option value="all">全部</option>
          <option value="DEBUG">DEBUG</option>
          <option value="INFO">INFO</option>
          <option value="WARN">WARN</option>
          <option value="ERROR">ERROR</option>
        </select>
        <span class="sub"><button class="btn small" id="btnReloadLogs">刷新</button></span></div>
      <div class="module-scroll" id="logsBody" style="padding:8px 0"><div class="empty">加载中...</div></div>
    </div>`;
    document.getElementById('btnReloadLogs').addEventListener('click', () => loadLogs(true));
    document.getElementById('logLevel').addEventListener('change', () => loadLogs(true));
    loadLogs(true);
    refreshTimer = setInterval(() => loadLogs(false), 5000);
  },
  unmount() {
    if (refreshTimer) {
      clearInterval(refreshTimer);
      refreshTimer = null;
    }
    hasLoadedOnce = false;
    ctx = null;
  }
};

async function loadLogs(forceScroll) {
  const body = document.getElementById('logsBody');
  if (!body) return;
  const level = document.getElementById('logLevel').value;
  const prevScrollTop = body.scrollTop;
  const wasNearBottom = forceScroll || !hasLoadedOnce
    || (body.scrollHeight - body.scrollTop - body.clientHeight < 48);
  try {
    const data = await ctx.api.getLogs(level, 200);
    if (!ctx) return;
    const logs = data.logs || [];
    if (logs.length === 0) {
      body.innerHTML = '<div class="empty">暂无日志</div>';
      hasLoadedOnce = true;
      return;
    }
    const ordered = logs.slice().reverse();
    body.innerHTML = ordered.map((line) => {
      const m = line.match(/\s(DEBUG|INFO|WARN|ERROR)\s/);
      const lv = m ? m[1] : '';
      return '<div class="log-line"><span class="lv-' + lv + '">' + ctx.utils.esc(line) + '</span></div>';
    }).join('');
    hasLoadedOnce = true;
    if (wasNearBottom) {
      body.scrollTop = body.scrollHeight;
    } else {
      body.scrollTop = prevScrollTop;
    }
  } catch (e) {
    if (!ctx) return;
    body.innerHTML = '<div class="empty error-text">加载日志失败：' + ctx.utils.esc(e.message) + '</div>';
  }
}
