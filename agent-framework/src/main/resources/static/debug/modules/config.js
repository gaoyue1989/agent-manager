let ctx = null;
let lastReloadResult = null;

export default {
  mount(container, c) {
    ctx = c;
    lastReloadResult = null;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header">
        <h2>Configuration</h2>
        <span class="sub">
          <select id="reloadScope" class="input" style="padding:4px 8px;font-size:12px">
            <option value="auto">Reload: Auto</option>
            <option value="mcp">Reload: MCP</option>
            <option value="agent">Reload: Agent</option>
          </select>
          <button class="btn small primary" id="btnHotReload">Hot Reload</button>
          <button class="btn small" id="btnRefreshConfig">Refresh</button>
        </span>
      </div>
      <div class="module-scroll" id="configBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnRefreshConfig').addEventListener('click', loadConfig);
    document.getElementById('btnHotReload').addEventListener('click', triggerHotReload);
    loadConfig();
  },
  unmount() { ctx = null; }
};

async function triggerHotReload() {
  const btn = document.getElementById('btnHotReload');
  const scopeEl = document.getElementById('reloadScope');
  const scope = scopeEl ? scopeEl.value : 'auto';
  if (btn) { btn.disabled = true; btn.textContent = 'Reloading...'; }
  try {
    const res = await ctx.api.triggerReload(scope);
    if (!ctx) return;
    lastReloadResult = { ok: true, data: res };
    ctx.utils.toast('Reload (' + scope + ') succeeded', 'success');
    await loadConfig();
  } catch (e) {
    if (!ctx) return;
    lastReloadResult = { ok: false, error: e.message };
    ctx.utils.toast('Reload failed: ' + e.message, 'error');
    await loadConfig();
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = 'Hot Reload'; }
  }
}

function renderReloadBanner() {
  if (!lastReloadResult) return '';
  if (!lastReloadResult.ok) {
    return '<div class="reload-result-banner error"><strong>Hot Reload Failed:</strong> ' +
      ctx.utils.esc(lastReloadResult.error) + '</div>';
  }
  const d = lastReloadResult.data || {};
  return '<div class="reload-result-banner">' +
    '<strong>Hot Reload Result</strong>' +
    '<span class="badge accent">scope: ' + ctx.utils.esc(d.scope || 'auto') + '</span>' +
    '<span class="badge ' + (d.fingerprint_changed ? 'green' : 'dim') + '">fingerprint_changed: ' + String(!!d.fingerprint_changed) + '</span>' +
    '<span class="badge ' + (d.agent_rebuilt ? 'green' : 'dim') + '">agent_rebuilt: ' + String(!!d.agent_rebuilt) + '</span>' +
  '</div>';
}

async function loadConfig() {
  const body = document.getElementById('configBody');
  let env, oaf;
  try {
    [env, oaf] = await Promise.all([ctx.api.getEnvConfig(), ctx.api.getOafConfig()]);
  } catch (e) {
    if (!ctx) return;
    body.innerHTML = '<div class="empty error-text">Failed to load config: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  if (!ctx) return;

  let html = renderReloadBanner();

  if (env) {
    html += '<div class="panel"><div class="panel-header"><span class="title">Environment Variables</span></div><div class="panel-body">';
    const groups = [
      ['LLM', env.llm && [
        ['LLM_API_KEY', env.llm.api_key],
        ['LLM_MODEL_ID', env.llm.model_id],
        ['LLM_BASE_URL', env.llm.base_url],
        ['LLM_PROVIDER', env.llm.provider],
        ['LLM_TEMPERATURE', env.llm.temperature],
        ['LLM_MAX_TOKENS', env.llm.max_tokens],
        ['LLM_TIMEOUT', env.llm.timeout],
        ['LLM_FALLBACK_MODEL_ID', env.llm.fallback_model_id || '(disabled)']
      ]],
      ['Server', env.server && [
        ['SERVER_HOST', env.server.host],
        ['SERVER_PORT', env.server.port]
      ]],
      ['Checkpoint', env.checkpoint && [
        ['CHECKPOINT_JDBC_URL', env.checkpoint.jdbc_url],
        ['CHECKPOINT_USERNAME', env.checkpoint.username],
        ['CHECKPOINT_PASSWORD', env.checkpoint.password]
      ]],
      ['Other', [['AGENT_CONFIG_DIR', env.config_dir]]]
    ];
    for (const [group, items] of groups) {
      if (!items) continue;
      html += '<div style="margin-bottom:12px"><div class="lc-label" style="margin-bottom:4px;font-weight:600;color:var(--text-dim);font-size:11px;text-transform:uppercase">' + group + '</div>';
      for (const [k, v] of items) {
        html += '<div class="info-row"><span class="label" style="font-family:var(--mono)">' + k + '</span>' +
          '<span class="value">' + ctx.utils.esc(v != null ? String(v) : '') + '</span></div>';
      }
      html += '</div>';
    }
    html += '</div></div>';
  }

  if (oaf) {
    html += '<div class="panel"><div class="panel-header"><span class="title">OAF Configuration (AGENTS.md frontmatter)</span></div><div class="panel-body">';
    const fields = [
      ['Name', oaf.name],
      ['Slug', oaf.slug],
      ['Version', oaf.version],
      ['Description', oaf.description],
      ['Author', oaf.author],
      ['License', oaf.license],
      ['Tools', oaf.tools && oaf.tools.join(', ')],
      ['Denied Tools', oaf.deniedTools && oaf.deniedTools.length ? oaf.deniedTools.join(', ') : '(none)'],
      ['Skills', oaf.skills && oaf.skills.map((s) => s.name + (s.source === 'local' ? ' (local)' : '')).join(', ')],
      ['MCP Servers', oaf.mcpServers && oaf.mcpServers.map((m) => m.server).join(', ')],
      ['Sub Agents', oaf.subAgents && oaf.subAgents.map((a) => a.agent).join(', ')],
      ['Model', oaf.model ? (oaf.model.provider + ' / ' + oaf.model.name) : ''],
      ['Tags', oaf.tags && oaf.tags.join(', ')]
    ];
    for (const [k, v] of fields) {
      if (v == null || v === '') continue;
      html += '<div class="info-row"><span class="label">' + k + '</span>' +
        '<span class="value" style="white-space:normal">' + ctx.utils.esc(String(v)) + '</span></div>';
    }
    html += '<button class="btn small oaf-raw-btn" style="margin-top:10px">View Raw OAF JSON</button>';
    html += '</div></div>';
  }

  body.innerHTML = html;
  const rawBtn = body.querySelector('.oaf-raw-btn');
  if (rawBtn) {
    rawBtn.addEventListener('click', () => ctx.utils.showJsonModal('OAF Configuration', oaf));
  }
}
