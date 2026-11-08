/* ===== Sandbox 模块：沙箱配置 + 配额与绑定（sandbox-pool-opensandbox-design §4.8，PR-D）===== */

let ctx = null;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Sandbox</h2>
        <span class="sub"><button class="btn small" id="btnReloadSb">Reload</button></span></div>
      <div class="module-scroll" id="sbBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnReloadSb').addEventListener('click', loadSandbox);
    loadSandbox();
  },
  unmount() { ctx = null; }
};

async function loadSandbox() {
  const body = document.getElementById('sbBody');
  let sb;
  try {
    sb = (await ctx.api.getSandbox()) || {};
  } catch (e) {
    if (!ctx) return; // 已切走（unmount 置空 ctx）：丢弃过期渲染，避免读 null 的 utils
    body.innerHTML = '<div class="empty error-text">Failed to load sandbox: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  if (!ctx) return; // await 期间可能已 unmount，过期响应不再渲染（下方 row() 依赖 ctx.utils）

  if (!sb.enabled) {
    body.innerHTML = '<div class="panel"><div class="panel-header"><span class="title">Sandbox</span></div>' +
      '<div class="panel-body"><div class="info-row"><span class="label">Status</span>' +
      '<span class="value" style="color:var(--yellow)">Disabled (RemoteFilesystemSpec 模式)</span></div>' +
      '<div class="info-row"><span class="label">启用方式</span><span class="value">设置 SANDBOX_ENABLED=true</span></div>' +
      '</div></div>';
    return;
  }

  // 沙箱配置面板
  let html = '<div class="panel"><div class="panel-header"><span class="title">Sandbox Configuration</span>' +
    '<span class="badge" style="color:var(--green)">Enabled</span></div><div class="panel-body">' +
    row('Image', sb.image) +
    row('Timeout (min)', sb.timeout_minutes) +
    row('Memory (MiB)', sb.memory_mb) +
    row('CPU Count', sb.cpu_count) +
    row('Server URL', sb.server_url) +
    row('API Key', sb.api_key_configured ? 'Configured' : '<span style="color:var(--red)">Missing</span>') +
    '</div></div>';

  // 配额与绑定区块（§4.8，fail-soft：端点不可用只提示，不阻塞配置展示）
  html += await loadBindingsSection();
  body.innerHTML = html;
}

/** 「配额与绑定」区块：配置回显 + 绑定表（turn_running 派生）+ 注册表健康 + 事件流 */
async function loadBindingsSection() {
  if (!ctx) return '';
  let data;
  try {
    data = await ctx.api.getSandboxBindings();
  } catch (e) {
    if (!ctx) return '';
    return panel('配额与绑定',
      '<div class="empty error-text">sandbox-bindings 端点不可用（fail-soft）：' + ctx.utils.esc(e.message) + '</div>');
  }
  if (!ctx) return '';
  if (!data || !data.enabled) {
    return panel('配额与绑定',
      '<div class="info-row"><span class="label">Lifecycle</span><span class="value" style="color:var(--yellow)">Disabled</span></div>' +
      '<div class="info-row"><span class="label">启用方式</span><span class="value">' +
      ctx.utils.esc((data && data.reason) || '设置 SANDBOX_LIFECYCLE_ENABLED=true') + '</span></div>');
  }

  const cfg = data.config || {};
  let html = panel('配额与绑定 · 配置',
    row('Lifecycle', 'Enabled') +
    row('Pool Ref', cfg.pool_ref || '<span style="color:var(--yellow)">未配置（不用服务端池）</span>') +
    row('User Quota', cfg.user_quota) +
    row('TTL (min)', cfg.ttl_minutes) +
    row('Renew Interval (s)', cfg.renew_interval_seconds) +
    row('Confirm Hold (min)', cfg.confirm_hold_minutes) +
    row('Grace (s, busy 阈值)', cfg.grace_seconds));

  // 绑定表（跨副本聚合；turn_running 由 turn_lease JOIN + updated_at 新鲜度派生）
  const bindings = data.bindings || [];
  let rows;
  if (!bindings.length) {
    rows = '<div class="empty">暂无绑定行</div>';
  } else {
    rows = '<table class="data-table"><thead><tr>' +
      '<th>user_key</th><th>session_id</th><th>sandbox_id</th><th>expires_at</th><th>turn_running</th>' +
      '</tr></thead><tbody>' +
      bindings.map(b => {
        const pending = (b.sandbox_id || '').indexOf('pending:') === 0;
        const sandboxCell = pending
          ? '<span style="color:var(--yellow)">' + ctx.utils.esc(b.sandbox_id) + '</span>'
          : ctx.utils.esc(b.sandbox_id);
        return '<tr>' +
          '<td>' + ctx.utils.esc(b.user_key) + '</td>' +
          '<td>' + ctx.utils.esc(b.session_id) + '</td>' +
          '<td>' + sandboxCell + '</td>' +
          '<td>' + expiryCell(b.expires_at) + '</td>' +
          '<td>' + (b.turn_running
            ? '<span style="color:var(--green)">running</span>'
            : '<span style="color:var(--muted)">idle</span>') + '</td>' +
          '</tr>';
      }).join('') + '</tbody></table>';
  }
  html += panel('配额与绑定 · 绑定表（集群级，' + bindings.length + ' 条' +
    (data.bindings_error ? '，<span style="color:var(--red)">读取失败: ' + ctx.utils.esc(data.bindings_error) + '</span>' : '') + ')',
    rows);

  // 注册表健康（本副本）：注册数 / 最老条目年龄 / 守护线程循环滞后
  const reg = data.registry || {};
  const lagMs = Number(reg.loop_lag_ms || 0);
  html += panel('配额与绑定 · 续期注册表（本副本）',
    row('注册条目数', reg.size) +
    row('turn 注册数', reg.active_turns) +
    row('最老条目年龄', fmtDuration(reg.oldest_entry_age_ms)) +
    row('循环滞后', lagMs > 5000
      ? '<span style="color:var(--red)">' + lagMs + 'ms（busy 判定退化预警）</span>'
      : lagMs + 'ms') +
    row('最近扫描', reg.last_sweep_at || '-'));

  // 事件环形缓冲（100 条；借出/LRU 复用/懒剔除/renew 失败分类/fail-open）
  const events = data.events || [];
  const eventRows = events.length
    ? events.slice().reverse().map(ev => {
        const color = ev.type === 'renew_fail' || ev.type === 'fail_open' || ev.type === 'create_failed'
          ? 'var(--red)' : 'var(--muted)';
        return '<div class="info-row"><span class="label" style="color:' + color + '">' +
          ctx.utils.esc(ev.type) + '</span><span class="value">' +
          ctx.utils.esc((ev.sandbox_id || '') + ' ' + (ev.detail || '') + ' @' + (ev.at || '')) +
          '</span></div>';
      }).join('')
    : '<div class="empty">暂无事件</div>';
  html += panel('配额与绑定 · 事件流（环形缓冲 ' + events.length + '/100）', eventRows);
  return html;
}

/** expires_at 倒计时单元格（过期红 / 一小时内黄 / 正常灰） */
function expiryCell(expiresAt) {
  if (!expiresAt) return '-';
  const t = Date.parse((expiresAt || '').replace(' ', 'T') + 'Z');
  if (isNaN(t)) return ctx.utils.esc(expiresAt);
  const remainMin = Math.round((t - Date.now()) / 60000);
  const color = remainMin < 0 ? 'var(--red)' : remainMin < 60 ? 'var(--yellow)' : 'var(--muted)';
  const label = remainMin < 0 ? 'expired ' + (-remainMin) + 'm 前' : '剩 ' + remainMin + 'm';
  return '<span style="color:' + color + '" title="' + ctx.utils.esc(expiresAt) + '">' + label + '</span>';
}

function fmtDuration(ms) {
  const n = Number(ms || 0);
  if (n < 1000) return n + 'ms';
  if (n < 60000) return Math.round(n / 1000) + 's';
  return Math.round(n / 60000) + 'm';
}

function panel(title, bodyHtml) {
  return '<div class="panel"><div class="panel-header"><span class="title">' + title + '</span></div>' +
    '<div class="panel-body">' + bodyHtml + '</div></div>';
}

function row(label, value) {
  return '<div class="info-row"><span class="label">' + ctx.utils.esc(label) + '</span>' +
    '<span class="value">' + (value === undefined || value === null ? '-' : value) + '</span></div>';
}
