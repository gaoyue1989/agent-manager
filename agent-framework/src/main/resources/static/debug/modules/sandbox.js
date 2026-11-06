/* ===== Sandbox 模块：沙箱配置查看（沙箱模式排查）+ 沙箱池状态（池化配置 / D18 水位） ===== */

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
    loadPool();
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
  loadPool();
}

// ---------------------------------------------------------------- 沙箱池面板

/**
 * 沙箱池状态（GET /debug/sandbox-pool）：池化配置 9 项 + 当前水位（D18 弹性水位 T）
 * + 集群级 idle/busy/creating/waiters 明细。fail-soft：端点不可用（旧版本运行时/缓存页）
 * 只提示不阻塞上方沙箱配置展示。
 */
async function loadPool() {
  const body = document.getElementById('sbBody');
  if (!body || !ctx) return;
  const holder = document.createElement('div');
  holder.id = 'sbPool';
  holder.innerHTML = '<div class="empty">Loading pool...</div>';
  body.appendChild(holder);

  let pool;
  try {
    pool = await ctx.api.getSandboxPool();
  } catch (e) {
    if (!ctx) return;
    const el = document.getElementById('sbPool');
    if (el) el.innerHTML = '<div class="empty">Sandbox Pool 状态不可用：' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  if (!ctx) return; // await 期间可能已 unmount / Reload 重建 DOM
  const el = document.getElementById('sbPool');
  if (!el) return;
  el.outerHTML = pool && pool.enabled ? poolPanel(pool) : poolOff(pool);
}

/** 池关闭视图：enabled=false（含 reason——池关闭默认分支 / sandbox 整体关闭） */
function poolOff(pool) {
  const reason = pool && pool.reason ? pool.reason : 'SANDBOX_POOL_ENABLED=false（默认，逐会话容器）';
  return '<div class="panel"><div class="panel-header"><span class="title">Sandbox Pool（沙箱池）</span>' +
    '<span class="badge dim">Disabled</span></div><div class="panel-body">' +
    row('状态', '<span style="color:var(--yellow)">未启用（逐会话容器模式）</span>') +
    row('原因', ctx.utils.esc(reason)) +
    row('启用方式', 'SANDBOX_ENABLED=true + SANDBOX_POOL_ENABLED=true') +
    '</div></div>';
}

/** 池开启视图：水位条（idle+creating 对比 target，80% 抬升线）+ 池明细 + 池化配置 */
function poolPanel(pool) {
  const cfg = pool.config || {};
  const idle = (pool.idle || []).length;
  const sticky = (pool.stickyIdle || []).length;
  const busy = (pool.busy || []).length;
  const creating = (pool.creating || []).length;
  const total = idle + busy + creating; // 账本合计（e2e P2 同口径）
  const maxSize = numOf(cfg.maxSize);
  const target = numOf(pool.target);
  const level = idle + creating; // D18 通用水位：通用 idle + creating 对比水位 T
  const pct = target > 0 ? Math.min(100, Math.round(level / target * 100)) : 0;
  const full = maxSize > 0 && total >= maxSize; // 池满：账本合计顶到 MAX_SIZE
  const fillCls = full ? ' full' : (pct >= 80 ? ' warn' : '');

  return '<div class="panel"><div class="panel-header"><span class="title">Sandbox Pool（沙箱池）</span>' +
    '<span class="badge green">Enabled</span></div><div class="panel-body">' +
    '<div class="pool-section-title">当前水位（D18 弹性水位）</div>' +
    '<div class="pool-level-head"><span class="label">通用水位（通用 idle + creating / target）</span>' +
    '<span class="value" style="font-family:var(--mono)">' + level + ' / ' + dashOf(target) +
    '（' + pct + '%）</span></div>' +
    '<div class="pool-meter"><div class="pool-meter-fill' + fillCls + '" style="width:' + pct + '%"></div>' +
    '<div class="pool-meter-mark" style="left:80%" title="80% 抬升线"></div></div>' +
    '<div class="pool-level-note">≥ 80%×target 抬升（步进 GROW_STEP，上限 MAX_SIZE）；' +
    '≤ 50%×target 连续 3 周期 + 冷却后回缩（下限 MIN_IDLE，只杀通用 idle）</div>' +
    '<div class="pool-section-title">池明细（Redis 单 ledger，集群级）</div>' +
    '<div class="info-grid">' +
    row('通用 idle', idle) +
    row('粘滞 idle（D17 归还转粘滞）', sticky) +
    row('busy（借出中，含跨副本）', busy) +
    row('creating（补位/借出按需建中）', creating) +
    row('借出等待 waiters', waitersView(pool.waiters)) +
    row('集群占用（idle+busy+creating / MAX_SIZE）', total + ' / ' + dashOf(maxSize)) +
    row('当前副本 podId', pool.podId) +
    row('回缩计数 fullCycles', numOf(pool.fullCycles)) +
    '</div>' +
    '<div class="pool-section-title">池化配置（SANDBOX_POOL_*）</div>' +
    '<div class="info-grid">' +
    row('MIN_IDLE · 最小空闲（水位下限）', cfg.minIdle) +
    row('MAX_SIZE · 集群容量上限', cfg.maxSize) +
    row('MAX_WAIT_SECONDS · 借出等待上限（秒）', cfg.maxWaitSeconds) +
    row('IDLE_RETURN_SECONDS · 空闲租约（秒）', cfg.idleReturnSeconds) +
    row('SCAN_INTERVAL_SECONDS · 回收扫描间隔（秒）', cfg.scanIntervalSeconds) +
    row('REBUILD_CONCURRENCY · 重建并发', cfg.rebuildConcurrency) +
    row('USER_MAX_CONTAINERS · 单用户容器配额（D17）', cfg.userMaxContainers) +
    row('STICKY_TTL_SECONDS · 粘滞空闲 TTL（秒，D17）', cfg.stickyTtlSeconds) +
    row('GROW_STEP · 水位抬升步进（D18）', cfg.growStep) +
    '</div>' +
    '</div></div>';
}

// ---------- 池面板工具 ----------

/** info-row（label 转 esc，value 由调用方保证安全——数字/后端固定串） */
function row(label, value) {
  return '<div class="info-row"><span class="label">' + ctx.utils.esc(label) + '</span>' +
    '<span class="value">' + (value === undefined || value === null || value === '' ? '-' : value) + '</span></div>';
}

/** 数值兜底：非有限数字返回 null（展示层转 '-'） */
function numOf(v) {
  return (typeof v === 'number' && isFinite(v)) ? v : null;
}

function dashOf(v) {
  return v === null ? '-' : String(v);
}

/** waiters > 0 标黄（有会话在等容器：池满压力可感知） */
function waitersView(v) {
  const n = numOf(v);
  if (n === null) return null;
  return n > 0 ? '<span style="color:var(--yellow)">' + n + '</span>' : String(n);
}
