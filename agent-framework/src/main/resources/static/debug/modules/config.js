/* ===== Config 模块：环境变量 + OAF 配置 ===== */

let ctx = null;

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Configuration</h2>
        <span class="sub"><button class="btn small" id="btnReloadConfig">Reload</button></span></div>
      <div class="module-scroll" id="configBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnReloadConfig').addEventListener('click', loadConfig);
    loadConfig();
  },
  unmount() { ctx = null; }
};

async function loadConfig() {
  const body = document.getElementById('configBody');
  let env, oaf;
  try {
    [env, oaf] = await Promise.all([ctx.api.getEnvConfig(), ctx.api.getOafConfig()]);
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load config: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  let html = '';

  // 环境变量
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
        ['LLM_TIMEOUT', env.llm.timeout]
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
      html += '<div style="margin-bottom:10px"><div class="lc-label" style="margin-bottom:4px">' + group + '</div>';
      for (const [k, v] of items) {
        html += '<div class="info-row"><span class="label" style="font-family:var(--mono);font-size:11px">' + k + '</span>' +
          '<span class="value">' + ctx.utils.esc(v != null ? String(v) : '') + '</span></div>';
      }
      html += '</div>';
    }
    html += '</div></div>';
  }

  // OAF 配置
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
    html += '<button class="btn small oaf-raw-btn" style="margin-top:8px">View Raw OAF JSON</button>';
    html += '</div></div>';
  }

  body.innerHTML = html;
  const rawBtn = body.querySelector('.oaf-raw-btn');
  if (rawBtn) {
    rawBtn.addEventListener('click', () => {
      ctx.utils.showJsonModal('OAF Configuration', oaf);
    });
  }
}
