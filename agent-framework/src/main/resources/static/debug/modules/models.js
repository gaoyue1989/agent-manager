/* ===== Models 模块：会话可切换模型管理 =====
   系统模型（LLM_* 环境变量，只读，默认模型）+ 托管模型（model_config 表 CRUD + 连接测试）。
   接口契约见 docs/session-model-switch-design.md §4.1（GET /models?all=true 为管理视图）。 */

let ctx = null;
let models = [];

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Models</h2>
        <span class="sub">
          <button class="btn small" id="btnReloadModels">Reload</button>
          <button class="btn small primary" id="btnAddModel">Add Model</button>
        </span></div>
      <div class="empty" style="text-align:left;padding:0 0 10px">
        会话可切换模型：系统模型来自 LLM_* 环境变量（只读，未显式选择的会话走它）；
        托管模型存 model_config 表，保存即生效。删除被会话引用的模型后，该会话自动回落默认模型。
      </div>
      <div class="module-scroll" id="modelsBody"><div class="empty">Loading...</div></div>
    </div>`;
    document.getElementById('btnReloadModels').addEventListener('click', loadModels);
    document.getElementById('btnAddModel').addEventListener('click', () => openForm(null));
    loadModels();
  },
  unmount() {
    ctx = null;
    models = [];
  }
};

async function loadModels() {
  const body = document.getElementById('modelsBody');
  try {
    const data = await ctx.api.getModels(true);
    models = (data && data.models) || [];
  } catch (e) {
    if (!ctx) return; // 已切走：丢弃过期渲染
    body.innerHTML = '<div class="empty error-text">Failed to load models: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  if (!ctx) return; // await 期间可能已 unmount
  renderTable(body);
}

function renderTable(body) {
  if (models.length === 0) {
    body.innerHTML = '<div class="empty">No models.</div>';
    return;
  }
  let html = '<table class="data-table"><thead><tr>'
    + '<th>Name</th><th>Model ID</th><th>Base URL</th><th>API Key</th>'
    + '<th>Sampling</th><th>Status</th><th>Actions</th></tr></thead><tbody>';
  for (const m of models) {
    const isSystem = m.source === 'system';
    html += '<tr>'
      + '<td>' + ctx.utils.esc(m.name)
        + (isSystem ? ' <span class="badge dim">system</span>' : '')
        + (m.is_default ? ' <span class="badge green">default</span>' : '') + '</td>'
      + '<td class="mono">' + ctx.utils.esc(m.model_id) + '</td>'
      + '<td class="mono">' + ctx.utils.esc(m.base_url || '—') + '</td>'
      + '<td class="mono">' + ctx.utils.esc(m.api_key_masked || (isSystem ? '（env）' : '（系统密钥）')) + '</td>'
      + '<td class="mono">' + ctx.utils.esc(samplingText(m)) + '</td>'
      + '<td>' + (m.enabled === false
          ? '<span class="badge red">disabled</span>'
          : '<span class="badge green">enabled</span>') + '</td>'
      + '<td>'
      + '<button class="btn small" data-act="test" data-id="' + ctx.utils.esc(m.id) + '">Test</button> '
      + (isSystem ? ''
          : '<button class="btn small" data-act="edit" data-id="' + ctx.utils.esc(m.id) + '">Edit</button> '
            + '<button class="btn small danger" data-act="del" data-id="' + ctx.utils.esc(m.id) + '">Delete</button>')
      + '</td></tr>';
  }
  html += '</tbody></table>';
  body.innerHTML = html;

  body.querySelectorAll('button[data-act]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const model = models.find((x) => x.id === btn.dataset.id);
      if (!model) return;
      if (btn.dataset.act === 'test') testModel(model, btn);
      else if (btn.dataset.act === 'edit') openForm(model);
      else if (btn.dataset.act === 'del') deleteModel(model);
    });
  });
}

function samplingText(m) {
  const parts = [];
  if (m.temperature != null) parts.push('T=' + m.temperature);
  if (m.max_tokens != null) parts.push('max=' + m.max_tokens);
  if (m.enable_thinking) parts.push('thinking');
  return parts.length ? parts.join(', ') : '—';
}

/** 连接测试：真实发起一次最小 completion（后端 max_tokens=16） */
async function testModel(model, btn) {
  btn.disabled = true;
  btn.textContent = 'Testing...';
  try {
    const r = await ctx.api.testModel(model.id);
    const detail = r.ok
      ? '(' + r.latency_ms + 'ms)' + (r.reply ? ' → ' + r.reply : '')
      : (r.message || 'failed');
    ctx.utils.toast((r.ok ? '✔ ' : '✘ ') + model.name + ' ' + detail, r.ok ? 'success' : 'error');
  } catch (e) {
    ctx.utils.toast('✘ ' + model.name + ' ' + e.message, 'error');
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = 'Test';
    }
  }
}

/** 删除（决策#4：允许删除，引用会话回落默认模型；后续新请求可切到其他模型） */
async function deleteModel(model) {
  if (!confirm('确定删除模型 "' + model.name + '"？\n\n引用它的会话将自动回落默认模型，后续可重新选择其他模型。')) return;
  try {
    await ctx.api.deleteModel(model.id);
    ctx.utils.toast('已删除 ' + model.name + '（引用会话将回落默认模型）', 'success');
    loadModels();
  } catch (e) {
    ctx.utils.toast('删除失败: ' + e.message, 'error');
  }
}

/** 新增/编辑弹窗（existing=null 为新增；apiKey 三态：留空=不修改、输入=覆盖、勾选清空=回落系统密钥） */
function openForm(existing) {
  const m = existing || {};
  const isEdit = !!existing;
  const field = (label, id, value, attrs) =>
    '<div style="margin-bottom:8px"><div style="font-size:11px;color:var(--text-dim);margin-bottom:3px">'
    + label + '</div><input class="input" style="width:100%" id="' + id + '" '
    + (attrs || '') + ' value="' + ctx.utils.esc(value != null ? value : '') + '"></div>';

  window.App.modal.open(
    '<h2>' + (isEdit ? 'Edit Model' : 'Add Model') + '</h2>'
    + '<p style="color:var(--text-dim);font-size:11px;margin-bottom:8px">'
    + '托管模型存 model_config 表，保存即生效（多副本 ≤30s 收敛）。未显式选择的会话走系统模型。</p>'
    + field('Name *', 'mfName', m.name, 'placeholder="展示名（唯一）"')
    + field('Provider', 'mfProvider', m.provider || 'openai', 'placeholder="openai"')
    + field('Model ID *', 'mfModelId', m.model_id, 'placeholder="推理端点模型名，如 deepseek-v3"')
    + field('Base URL *', 'mfBaseUrl', m.base_url, 'placeholder="https://api.example.com/v1"')
    + field('API Key', 'mfApiKey', '', 'type="password" autocomplete="new-password" placeholder="'
        + ctx.utils.esc(m.api_key_masked || '留空 = 回落系统 LLM_API_KEY') + '"')
    + (isEdit
        ? '<label style="display:flex;align-items:center;gap:6px;font-size:11px;color:var(--text-dim);margin:-4px 0 8px">'
          + '<input type="checkbox" id="mfClearKey"> 清空已存密钥（回落系统 LLM_API_KEY）</label>'
        : '')
    + field('Temperature', 'mfTemp', m.temperature, 'type="number" step="0.1" min="0" max="2"')
    + field('Max Tokens', 'mfMaxTokens', m.max_tokens, 'type="number" min="1"')
    + field('Timeout (s)', 'mfTimeout', m.timeout_seconds, 'type="number" min="1"')
    + field('Context Length', 'mfCtxLen', m.context_length, 'type="number" min="0" placeholder="0 = 不传给模型"')
    + '<div style="display:flex;gap:14px;font-size:12px;margin:4px 0 10px">'
    + '<label style="display:flex;align-items:center;gap:6px">'
    + '<input type="checkbox" id="mfThinking" ' + (m.enable_thinking ? 'checked' : '') + '> 深度思考</label>'
    + '<label style="display:flex;align-items:center;gap:6px">'
    + '<input type="checkbox" id="mfEnabled" ' + (m.enabled === false ? '' : 'checked') + '> 启用（会话可选）</label>'
    + '</div>'
    + '<div style="display:flex;justify-content:flex-end;gap:8px">'
    + '<button class="btn" id="mfCancel">取消</button>'
    + '<button class="btn primary" id="mfSave">' + (isEdit ? '保存' : '创建') + '</button>'
    + '</div>'
  );
  document.getElementById('mfCancel').addEventListener('click', () => window.App.modal.close());
  document.getElementById('mfSave').addEventListener('click', () => saveForm(existing));
}

async function saveForm(existing) {
  const val = (id) => {
    const el = document.getElementById(id);
    return el ? el.value.trim() : '';
  };
  const num = (id) => {
    const v = val(id);
    if (v === '') return undefined;
    const n = Number(v);
    return Number.isFinite(n) ? n : undefined;
  };
  const name = val('mfName');
  const modelId = val('mfModelId');
  const baseUrl = val('mfBaseUrl');
  if (!name || !modelId || !baseUrl) {
    ctx.utils.toast('name / modelId / baseUrl 均为必填', 'error');
    return;
  }
  const body = {
    name,
    provider: val('mfProvider') || undefined,
    modelId,
    baseUrl,
    temperature: num('mfTemp'),
    maxTokens: num('mfMaxTokens'),
    timeoutSeconds: num('mfTimeout'),
    contextLength: num('mfCtxLen'),
    enableThinking: document.getElementById('mfThinking').checked,
    enabled: document.getElementById('mfEnabled').checked
  };
  const keyInput = val('mfApiKey');
  const clearKey = document.getElementById('mfClearKey');
  if (existing) {
    // 三态：勾选清空 > 输入覆盖 > 缺省不动（后端 PATCH 语义）
    if (clearKey && clearKey.checked) body.apiKey = '';
    else if (keyInput) body.apiKey = keyInput;
  } else if (keyInput) {
    body.apiKey = keyInput;
  }

  try {
    if (existing) await ctx.api.updateModel(existing.id, body);
    else await ctx.api.createModel(body);
    ctx.utils.toast(existing ? '模型已更新' : '模型已创建', 'success');
    window.App.modal.close();
    loadModels();
  } catch (e) {
    ctx.utils.toast('保存失败: ' + e.message, 'error');
  }
}
