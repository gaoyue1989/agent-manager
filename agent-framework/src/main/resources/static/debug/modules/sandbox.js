/* ===== Sandbox 模块：沙箱配置查看（沙箱模式排查） ===== */

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
    body.innerHTML = '<div class="empty error-text">Failed to load sandbox: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  if (!sb.enabled) {
    body.innerHTML = '<div class="panel"><div class="panel-header"><span class="title">Sandbox</span></div>' +
      '<div class="panel-body"><div class="info-row"><span class="label">Status</span>' +
      '<span class="value" style="color:var(--yellow)">Disabled (RemoteFilesystemSpec 模式)</span></div>' +
      '<div class="info-row"><span class="label">启用方式</span><span class="value">设置 SANDBOX_ENABLED=true</span></div>' +
      '</div></div>';
    return;
  }

  body.innerHTML = '<div class="panel"><div class="panel-header"><span class="title">Sandbox Configuration</span>' +
    '<span class="badge" style="color:var(--green)">Enabled</span></div><div class="panel-body">' +
    row('Image', sb.image) +
    row('Timeout (min)', sb.timeout_minutes) +
    row('Memory (MiB)', sb.memory_mb) +
    row('CPU Count', sb.cpu_count) +
    row('Server URL', sb.server_url) +
    row('API Key', sb.api_key_configured ? 'Configured' : '<span style="color:var(--red)">Missing</span>') +
    '</div></div>';
}

function row(label, value) {
  return '<div class="info-row"><span class="label">' + ctx.utils.esc(label) + '</span>' +
    '<span class="value">' + (value === undefined || value === null ? '-' : value) + '</span></div>';
}
