let ctx = null;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>沙箱管理</h2>
        <span class="sub"><button class="btn small" id="btnReloadSb">刷新</button></span></div>
      <div class="module-scroll" id="sbBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnReloadSb').addEventListener('click', loadSandbox);
    loadSandbox();
  },
  unmount() { ctx = null; }
};

function parseEnvText(text) {
  const env = {};
  for (const rawLine of String(text || '').split('\n')) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    const val = line.slice(eq + 1);
    if (key) env[key] = val;
  }
  return env;
}

function renderEnvInjectPanel() {
  const defaultUid = (ctx && ctx.state.getState('ui.userId')) || 'debug-user';
  const defaultSid = (ctx && ctx.state.getState('threads.current')) || '';
  return '<div class="panel collapsible-panel" id="sbEnvPanel">' +
    '<div class="panel-header collapsible-head" id="sbEnvToggle">' +
      '<span class="title">注入沙箱环境变量</span>' +
      '<span class="count"><span class="badge dim">PUT /sandbox/env</span></span>' +
    '</div>' +
    '<div class="panel-body collapsible-body">' +
      '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:8px">' +
        '<input id="sbEnvUser" class="input" type="text" placeholder="userId" value="' + ctx.utils.esc(defaultUid) + '">' +
        '<input id="sbEnvSid" class="input" type="text" placeholder="sessionId" value="' + ctx.utils.esc(defaultSid) + '">' +
      '</div>' +
      '<textarea id="sbEnvPairs" class="input" rows="4" style="width:100%;font-family:var(--mono);font-size:12px;resize:vertical" placeholder="每行一个 KEY=VALUE（提交后自动清空，不保留敏感值）"></textarea>' +
      '<div style="display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:8px">' +
        '<span id="sbEnvResult" class="status-text"></span>' +
        '<button class="btn small primary" id="sbEnvSubmit">应用环境变量</button>' +
      '</div>' +
    '</div>' +
  '</div>';
}

function bindEnvInjectEvents() {
  const panel = document.getElementById('sbEnvPanel');
  const toggle = document.getElementById('sbEnvToggle');
  if (panel && toggle) {
    toggle.addEventListener('click', () => panel.classList.toggle('open'));
  }
  const submit = document.getElementById('sbEnvSubmit');
  if (!submit) return;
  submit.addEventListener('click', async () => {
    const userId = (document.getElementById('sbEnvUser').value || '').trim();
    const sessionId = (document.getElementById('sbEnvSid').value || '').trim();
    const pairsEl = document.getElementById('sbEnvPairs');
    const resultEl = document.getElementById('sbEnvResult');
    const env = parseEnvText(pairsEl.value);
    if (!userId || !sessionId) {
      ctx.utils.toast('请填写 userId 与 sessionId', 'warn');
      return;
    }
    if (Object.keys(env).length === 0) {
      ctx.utils.toast('请至少输入一行 KEY=VALUE', 'warn');
      return;
    }
    submit.disabled = true;
    try {
      const res = await ctx.api.updateSandboxEnv(userId, sessionId, env);
      pairsEl.value = '';
      const keys = (res.updatedKeys || []).join(', ');
      if (resultEl) {
        resultEl.style.color = 'var(--green)';
        resultEl.textContent = 'Updated [' + keys + '] on ' + (res.sandboxId || 'sandbox') + ' (' + (res.applied || 'next_exec') + ')';
      }
      ctx.utils.toast('已更新沙箱环境变量: ' + keys, 'success');
    } catch (e) {
      if (resultEl) {
        resultEl.style.color = 'var(--red)';
        resultEl.textContent = e.message;
      }
      ctx.utils.toast('更新沙箱环境变量失败: ' + e.message, 'error');
    } finally {
      submit.disabled = false;
    }
  });
}

async function loadSandbox() {
  const body = document.getElementById('sbBody');
  let sb;
  try {
    sb = (await ctx.api.getSandbox()) || {};
  } catch (e) {
    if (!ctx) return;
    body.innerHTML = '<div class="empty error-text">Failed to load sandbox: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  if (!ctx) return;

  if (!sb.enabled) {
    body.innerHTML = '<div class="panel"><div class="panel-header"><span class="title">Sandbox</span></div>' +
      '<div class="panel-body"><div class="info-row"><span class="label">Status</span>' +
      '<span class="value" style="color:var(--yellow)">Disabled (RemoteFilesystemSpec)</span></div>' +
      '<div class="info-row"><span class="label">Enable</span><span class="value">Set SANDBOX_ENABLED=true</span></div>' +
      '</div></div>';
    return;
  }

  let html = '<div class="panel"><div class="panel-header"><span class="title">沙箱配置</span>' +
    '<span class="count"><span class="badge green">Enabled</span></span></div><div class="panel-body">' +
    row('Image', sb.image) +
    row('Timeout (min)', sb.timeout_minutes) +
    row('Memory (MiB)', sb.memory_mb) +
    row('CPU Count', sb.cpu_count) +
    row('Server URL', sb.server_url) +
    row('API Key', sb.api_key_configured ? 'Configured' : '<span style="color:var(--red)">Missing</span>') +
    '</div></div>';

  html += renderEnvInjectPanel();
  html += await loadBindingsSection();
  body.innerHTML = html;
  bindEnvInjectEvents();
}

async function loadBindingsSection() {
  if (!ctx) return '';
  let data;
  try {
    data = await ctx.api.getSandboxBindings();
  } catch (e) {
    if (!ctx) return '';
    return panel('配额与绑定',
      '<div class="empty error-text">sandbox-bindings unavailable: ' + ctx.utils.esc(e.message) + '</div>');
  }
  if (!ctx) return '';
  if (!data || !data.enabled) {
    return panel('配额与绑定',
      '<div class="info-row"><span class="label">Lifecycle</span><span class="value" style="color:var(--yellow)">Disabled</span></div>' +
      '<div class="info-row"><span class="label">Enable</span><span class="value">' +
      ctx.utils.esc((data && data.reason) || 'Set SANDBOX_LIFECYCLE_ENABLED=true') + '</span></div>');
  }

  const cfg = data.config || {};
  let html = panel('配额与绑定 · Config',
    row('Lifecycle', 'Enabled') +
    row('Pool Ref', cfg.pool_ref || '<span style="color:var(--yellow)">Not configured</span>') +
    row('User Quota', cfg.user_quota) +
    row('TTL (min)', cfg.ttl_minutes) +
    row('Renew Interval (s)', cfg.renew_interval_seconds) +
    row('Confirm Hold (min)', cfg.confirm_hold_minutes) +
    row('Grace (s)', cfg.grace_seconds));

  const bindings = data.bindings || [];
  let rows;
  if (!bindings.length) {
    rows = '<div class="empty">No active bindings</div>';
  } else {
    rows = '<table class="data-table"><thead><tr>' +
      '<th>user_key</th><th>session_id</th><th>sandbox_id</th><th>expires_at</th><th>turn_running</th>' +
      '</tr></thead><tbody>' +
      bindings.map((b) => {
        const pending = (b.sandbox_id || '').indexOf('pending:') === 0;
        const sandboxCell = pending
          ? '<span style="color:var(--yellow)">' + ctx.utils.esc(b.sandbox_id) + '</span>'
          : ctx.utils.esc(b.sandbox_id);
        return '<tr>' +
          '<td class="mono">' + ctx.utils.esc(b.user_key) + '</td>' +
          '<td class="mono">' + ctx.utils.esc(b.session_id) + '</td>' +
          '<td class="mono">' + sandboxCell + '</td>' +
          '<td>' + expiryCell(b.expires_at) + '</td>' +
          '<td>' + (b.turn_running
            ? '<span class="badge green">running</span>'
            : '<span class="badge dim">idle</span>') + '</td>' +
          '</tr>';
      }).join('') + '</tbody></table>';
  }
  html += panel('配额与绑定 · Table (' + bindings.length + ')', rows);

  const reg = data.registry || {};
  const lagMs = Number(reg.loop_lag_ms || 0);
  html += panel('配额与绑定 · Renew Registry',
    row('Registered', reg.size) +
    row('Active Turns', reg.active_turns) +
    row('Oldest Age', fmtDuration(reg.oldest_entry_age_ms)) +
    row('Loop Lag', lagMs > 5000 ? '<span style="color:var(--red)">' + lagMs + 'ms</span>' : lagMs + 'ms') +
    row('Last Sweep', reg.last_sweep_at || '-'));

  const events = data.events || [];
  const eventRows = events.length
    ? events.slice().reverse().map((ev) => {
        const color = ev.type === 'renew_fail' || ev.type === 'fail_open' || ev.type === 'create_failed'
          ? 'var(--red)' : 'var(--text-dim)';
        return '<div class="info-row"><span class="label" style="color:' + color + '">' +
          ctx.utils.esc(ev.type) + '</span><span class="value">' +
          ctx.utils.esc((ev.sandbox_id || '') + ' ' + (ev.detail || '') + ' @' + (ev.at || '')) +
          '</span></div>';
      }).join('')
    : '<div class="empty">No events</div>';
  html += panel('配额与绑定 · Events (' + events.length + '/100)', eventRows);
  return html;
}

function expiryCell(expiresAt) {
  if (!expiresAt) return '-';
  const t = Date.parse((expiresAt || '').replace(' ', 'T') + 'Z');
  if (isNaN(t)) return ctx.utils.esc(expiresAt);
  const remainMin = Math.round((t - Date.now()) / 60000);
  const color = remainMin < 0 ? 'var(--red)' : remainMin < 60 ? 'var(--yellow)' : 'var(--text-dim)';
  const label = remainMin < 0 ? 'expired ' + (-remainMin) + 'm ago' : remainMin + 'm left';
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
