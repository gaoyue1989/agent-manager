let ctx = null;
let lastReloadResult = null;

export default {
  mount(container, c) {
    ctx = c;
    lastReloadResult = null;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header">
        <h2>系统配置</h2>
        <span class="sub">
          <select id="reloadScope" class="input" style="padding:4px 8px;font-size:12px">
            <option value="auto">热重载：自动</option>
            <option value="mcp">热重载：仅 MCP</option>
            <option value="agent">热重载：重建 Agent</option>
          </select>
          <button class="btn small primary" id="btnHotReload">触发热重载</button>
          <button class="btn small" id="btnRefreshConfig">刷新</button>
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
  if (scope === 'agent' && !window.confirm('确认触发 scope=agent 整包热重载？这将重建 Agent 并清空内存会话缓存。')) {
    return;
  }
  if (btn) { btn.disabled = true; btn.textContent = '重载中...'; }
  try {
    const res = await ctx.api.triggerReload(scope);
    if (!ctx) return;
    const failedMcp = Array.isArray(res && res.mcp_servers)
      ? res.mcp_servers.filter((item) => item && item.ok === false)
      : [];
    const hasErr = Boolean(res && res.error);
    lastReloadResult = { ok: !hasErr && failedMcp.length === 0, data: res, error: res && res.error ? res.error : null };
    if (hasErr) {
      ctx.utils.toast('热重载（' + scope + '）异常: ' + res.error, res.scope === 'noop' ? 'warn' : 'error');
    } else if (failedMcp.length > 0) {
      ctx.utils.toast('热重载部分 MCP 失败: ' + failedMcp.map((x) => x.server).join(', '), 'warn');
    } else {
      ctx.utils.toast('热重载（' + scope + '）成功', 'success');
    }
    await loadConfig();
  } catch (e) {
    if (!ctx) return;
    lastReloadResult = { ok: false, error: e.message };
    ctx.utils.toast('热重载失败: ' + e.message, 'error');
    await loadConfig();
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = '触发热重载'; }
  }
}

function renderReloadBanner() {
  if (!lastReloadResult) return '';
  if (!lastReloadResult.ok && !lastReloadResult.data) {
    return '<div class="reload-result-banner error"><strong>热重载失败:</strong> ' +
      ctx.utils.esc(lastReloadResult.error) + '</div>';
  }
  const d = lastReloadResult.data || {};
  const mcpList = Array.isArray(d.mcp_servers) ? d.mcp_servers : [];
  const mcpBadges = mcpList.map((m) =>
    '<span class="badge ' + (m && m.ok === false ? 'red' : 'green') + '">' +
    ctx.utils.esc((m.server || '?') + ': ' + (m.action || (m.ok ? 'ok' : 'failed'))) + '</span>'
  ).join('');
  return '<div class="reload-result-banner' + (lastReloadResult.ok ? '' : ' error') + '">' +
    '<strong>热重载结果</strong>' +
    '<span class="badge accent">scope: ' + ctx.utils.esc(d.scope || 'auto') + '</span>' +
    '<span class="badge ' + (d.fingerprint_changed ? 'green' : 'dim') + '">fingerprint_changed: ' + String(!!d.fingerprint_changed) + '</span>' +
    '<span class="badge ' + (d.agent_rebuilt ? 'green' : 'dim') + '">agent_rebuilt: ' + String(!!d.agent_rebuilt) + '</span>' +
    (d.error ? '<span class="badge red">error: ' + ctx.utils.esc(d.error) + '</span>' : '') +
    mcpBadges +
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
    html += '<div class="panel"><div class="panel-header"><span class="title">环境变量</span></div><div class="panel-body">';
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
    html += '<div class="panel"><div class="panel-header"><span class="title">OAF 配置（AGENTS.md）</span></div><div class="panel-body">';
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
    html += '<button class="btn small oaf-raw-btn" style="margin-top:10px">查看原始 JSON</button>';
    html += '</div></div>';
  }

  body.innerHTML = html;
  const rawBtn = body.querySelector('.oaf-raw-btn');
  if (rawBtn) {
    rawBtn.addEventListener('click', () => ctx.utils.showJsonModal('OAF Configuration', oaf));
  }
}
