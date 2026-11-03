/* ===== Models 模块：会话可切换模型管理 =====
   系统模型（LLM_* 环境变量，只读）+ 托管模型（model_config 表 CRUD + 连接测试）；
   支持设置全局默认模型与备用模型（DB 配置优先于 env）。
   接口契约见 docs/session-model-switch-design.md §4.1 与 docs/model-fallback-design.md
   （GET /models?all=true 为管理视图）。 */

let ctx = null;
let models = [];
let fallbackId = '';
let fallbackSource = 'none';

/** Provider 方言值域（与 ModelController.PROVIDER_DIALECTS 对齐）：决定思考开关/推理强度的下发位置 */
const PROVIDER_DIALECTS = ['openai', 'vllm', 'sglang', 'glm', 'deepseek'];

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
        会话可切换模型：系统模型来自 LLM_* 环境变量（只读）；托管模型存 model_config 表，保存即生效。
        未显式选择模型的会话走「默认模型」——点任意模型行的 Set Default 设置，点系统模型行可回落系统默认。
        删除被会话引用的模型后，该会话自动回落默认模型。
        备用模型点托管模型行的 Set Fallback 设置（主模型重试耗尽后自动切换）；数据库未设置时回退 LLM_FALLBACK_MODEL_ID。
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
    fallbackId = '';
    fallbackSource = 'none';
  }
};

async function loadModels() {
  const body = document.getElementById('modelsBody');
  try {
    const data = await ctx.api.getModels(true);
    models = (data && data.models) || [];
    fallbackId = (data && data.fallback_model) || '';
    fallbackSource = (data && data.fallback_source) || 'none';
    // 列表项是轻量视图（不含采样参数），管理页并行拉详情补全（失败保持列表项，不阻塞渲染）
    await Promise.all(models.map(async (m) => {
      if (!ctx) return;
      try {
        const detail = await ctx.api.getModel(m.id);
        if (detail && detail.id === m.id) Object.assign(m, detail);
      } catch (e) {
        // 详情拉取失败不影响列表展示；编辑时会再次拉取
      }
    }));
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
  let html = '';
  // 备用模型提示：区分数据库配置与部署级 env；DB 配置可清除后回落 env
  html += '<div class="empty" style="text-align:left;padding:0 0 8px">备用模型：';
  if (!fallbackId) {
    html += '未启用（点托管模型行的 Set Fallback 设置）';
  } else {
    html += '<span class="mono">' + ctx.utils.esc(fallbackId) + '</span>'
      + (fallbackSource === 'env'
          ? '（来自 LLM_FALLBACK_MODEL_ID；设置备用可覆盖）'
          : '（来源 db） <button class="btn small" data-act="clear-fallback">Clear</button>');
  }
  html += '</div>';
  html += '<table class="data-table"><thead><tr>'
    + '<th>Name</th><th>Provider</th><th>Model ID</th><th>Base URL</th><th>API Key</th>'
    + '<th>Sampling</th><th>Status</th><th>Actions</th></tr></thead><tbody>';
  for (const m of models) {
    const isSystem = m.source === 'system';
    html += '<tr>'
      + '<td>' + ctx.utils.esc(m.name)
        + (isSystem ? ' <span class="badge dim">system</span>' : '')
        + (m.is_default ? ' <span class="badge green">default</span>' : '')
        + (m.is_fallback ? ' <span class="badge yellow">fallback</span>' : '') + '</td>'
      + '<td><span class="badge dim">' + ctx.utils.esc(m.provider || 'openai') + '</span></td>'
      + '<td class="mono">' + ctx.utils.esc(m.model_id) + '</td>'
      + '<td class="mono">' + ctx.utils.esc(m.base_url || '—') + '</td>'
      + '<td class="mono">' + ctx.utils.esc(m.api_key_masked || (isSystem ? '（env）' : '（系统密钥）')) + '</td>'
      + '<td class="mono">' + ctx.utils.esc(samplingText(m)) + '</td>'
      + '<td>' + (m.enabled === false
          ? '<span class="badge red">disabled</span>'
          : '<span class="badge green">enabled</span>') + '</td>'
      + '<td>'
      + '<button class="btn small" data-act="test" data-id="' + ctx.utils.esc(m.id) + '">Test</button> '
      + (!m.is_default && (isSystem || m.enabled !== false)
          ? '<button class="btn small primary" data-act="default" data-id="' + ctx.utils.esc(m.id) + '">Set Default</button> '
          : '')
      + (!isSystem && !m.is_fallback && m.enabled !== false
          ? '<button class="btn small" data-act="fallback" data-id="' + ctx.utils.esc(m.id) + '">Set Fallback</button> '
          : '')
      + (isSystem ? ''
          : '<button class="btn small" data-act="edit" data-id="' + ctx.utils.esc(m.id) + '">Edit</button> '
            + '<button class="btn small danger" data-act="del" data-id="' + ctx.utils.esc(m.id) + '">Delete</button>')
      + '</td></tr>';
  }
  html += '</tbody></table>';
  body.innerHTML = html;

  body.querySelectorAll('button[data-act]').forEach((btn) => {
    btn.addEventListener('click', () => {
      if (btn.dataset.act === 'clear-fallback') {
        clearFallback();
        return;
      }
      const model = models.find((x) => x.id === btn.dataset.id);
      if (!model) return;
      if (btn.dataset.act === 'test') testModel(model, btn);
      else if (btn.dataset.act === 'default') setDefaultModel(model);
      else if (btn.dataset.act === 'fallback') setFallbackModel(model);
      else if (btn.dataset.act === 'edit') openForm(model);
      else if (btn.dataset.act === 'del') deleteModel(model);
    });
  });
}

/** 设为默认对话模型（系统模型行 = 清除托管默认，回落 LLM_* 系统模型） */
async function setDefaultModel(model) {
  const isSystem = model.source === 'system';
  try {
    const r = await ctx.api.setDefaultModel(model.id);
    const name = isSystem ? '系统模型' : model.name;
    ctx.utils.toast('已将 ' + name + ' 设为默认模型'
      + (r && r.default_model ? '（' + r.default_model + '）' : ''), 'success');
    loadModels();
  } catch (e) {
    ctx.utils.toast('设置默认模型失败: ' + e.message, 'error');
  }
}

/** 设为备用模型（DB 配置优先于 LLM_FALLBACK_MODEL_ID） */
async function setFallbackModel(model) {
  try {
    const r = await ctx.api.setFallbackModel(model.id);
    ctx.utils.toast('已将 ' + model.name + ' 设为备用模型'
      + (r && r.fallback_model ? '（' + r.fallback_model + '）' : ''), 'success');
    loadModels();
  } catch (e) {
    ctx.utils.toast('设置备用模型失败: ' + e.message, 'error');
  }
}

/** 清除数据库备用覆盖；若部署配置了 LLM_FALLBACK_MODEL_ID 则自动回落该模型 */
async function clearFallback() {
  try {
    const r = await ctx.api.setFallbackModel('system');
    const msg = (r && r.fallback_model)
      ? '已清除 DB 备用，当前回落 ' + r.fallback_model + '（来源 ' + r.fallback_source + '）'
      : '已清除备用模型';
    ctx.utils.toast(msg, 'success');
    loadModels();
  } catch (e) {
    ctx.utils.toast('清除备用模型失败: ' + e.message, 'error');
  }
}

function samplingText(m) {
  const parts = [];
  if (m.temperature != null) parts.push('T=' + m.temperature);
  if (m.max_tokens != null) parts.push('max=' + m.max_tokens);
  if (m.frequency_penalty != null) parts.push('freq=' + m.frequency_penalty);
  if (m.reasoning_effort) parts.push('effort=' + m.reasoning_effort);
  if (m.enable_thinking) parts.push('thinking');
  if (m.timeout_seconds != null) parts.push('timeout=' + m.timeout_seconds + 's');
  if (m.context_length != null) parts.push('ctx=' + m.context_length);
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
async function openForm(existing) {
  if (!ctx) return;
  // 列表项是轻量视图：编辑前拉详情拿到完整采样参数（失败回落列表项）
  let m = existing || {};
  if (existing) {
    try {
      const detail = await ctx.api.getModel(existing.id);
      if (detail && detail.id === existing.id) m = detail;
    } catch (e) {
      // 详情失败不阻塞编辑，用列表项已有字段
    }
    if (!ctx) return; // await 期间可能已 unmount
  }
  const isEdit = !!existing;
  const field = (label, id, value, attrs) =>
    '<div style="margin-bottom:8px"><div style="font-size:11px;color:var(--text-dim);margin-bottom:3px">'
    + label + '</div><input class="input" style="width:100%" id="' + id + '" '
    + (attrs || '') + ' value="' + ctx.utils.esc(value != null ? value : '') + '"></div>';
  const selectField = (label, id, options, value) =>
    '<div style="margin-bottom:8px"><div style="font-size:11px;color:var(--text-dim);margin-bottom:3px">'
    + label + '</div><select class="input" style="width:100%" id="' + id + '">'
    + options.map((o) => '<option value="' + ctx.utils.esc(o) + '"'
        + (o === value ? ' selected' : '') + '>' + ctx.utils.esc(o) + '</option>').join('')
    + '</select></div>';
  // 两列并排（采样参数字段较多，减少弹窗高度）
  const row = (a, b) => '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">' + a + b + '</div>';
  const section = (title) => '<div style="font-size:11px;font-weight:600;color:var(--text-h);'
    + 'margin:10px 0 6px;padding-top:8px;border-top:1px solid var(--border-subtle)">' + title + '</div>';
  // 新增时预填后端默认值（与 ModelController createModel 的 defaultIfNull 一致）；编辑时原样回填，不用默认值兜底
  const prefill = (v, d) => (isEdit ? v : (v != null && v !== '' ? v : d));
  // 存量未知 provider 值保留为可选项，避免编辑时被静默改成 openai
  const provider = m.provider || 'openai';
  const providerOptions = PROVIDER_DIALECTS.includes(provider)
    ? PROVIDER_DIALECTS : PROVIDER_DIALECTS.concat([provider]);

  window.App.modal.open(
    '<h2>' + (isEdit ? 'Edit Model' : 'Add Model') + '</h2>'
    + '<p style="color:var(--text-dim);font-size:11px;margin-bottom:8px">'
    + '托管模型存 model_config 表，保存即生效（多副本 ≤30s 收敛）。未显式选择的会话走列表中的默认模型。</p>'
    + section('连接')
    + field('Name *', 'mfName', m.name, 'placeholder="展示名（唯一）"')
    + selectField('Provider（推理引擎方言）', 'mfProvider', providerOptions, provider)
    + field('Model ID *', 'mfModelId', m.model_id, 'placeholder="推理端点模型名，如 deepseek-v3"')
    + field('Base URL *', 'mfBaseUrl', m.base_url, 'placeholder="https://api.example.com/v1"')
    + field('API Key', 'mfApiKey', '', 'type="password" autocomplete="new-password" placeholder="'
        + ctx.utils.esc(m.api_key_masked || '留空 = 回落系统 LLM_API_KEY') + '"')
    + (isEdit
        ? '<label style="display:flex;align-items:center;gap:6px;font-size:11px;color:var(--text-dim);margin:-4px 0 8px">'
          + '<input type="checkbox" id="mfClearKey"> 清空已存密钥（回落系统 LLM_API_KEY）</label>'
        : '')
    + section('采样参数')
    + row(field('Temperature', 'mfTemp', prefill(m.temperature, 0.3), 'type="number" step="0.1" min="0" max="2"'),
          field('Max Tokens', 'mfMaxTokens', prefill(m.max_tokens, 16384), 'type="number" min="1"'))
    + row(field('Reasoning Effort（推理强度）', 'mfReasoningEffort', m.reasoning_effort,
            'placeholder="如 low / medium / high；留空 = 不下发（清空即清除）"'),
          field('Frequency Penalty', 'mfFreqPenalty', m.frequency_penalty,
            'type="number" step="0.1" min="-2" max="2" placeholder="[-2, 2]；留空 = 不下发（已设置只能改值，0 与不传等价）"'))
    + row(field('Timeout (s)', 'mfTimeout', prefill(m.timeout_seconds, 120), 'type="number" min="1"'),
          field('Context Length', 'mfCtxLen', prefill(m.context_length, 0), 'type="number" min="0" placeholder="0 = 不传给模型"'))
    + section('开关')
    + '<div style="display:flex;gap:14px;font-size:12px;margin:4px 0 6px">'
    + '<label style="display:flex;align-items:center;gap:6px">'
    + '<input type="checkbox" id="mfThinking" ' + (m.enable_thinking ? 'checked' : '') + '> 深度思考</label>'
    + '<label style="display:flex;align-items:center;gap:6px">'
    + '<input type="checkbox" id="mfEnabled" ' + (m.enabled === false ? '' : 'checked') + '> 启用（会话可选）</label>'
    + '</div>'
    + '<div style="color:var(--text-dim);font-size:11px;margin:0 0 10px;line-height:1.5">'
    + '思考开关与推理强度的下发位置由 Provider 决定：vllm/sglang → chat_template_kwargs；'
    + 'glm → thinking.type（仅关闭时下发）；openai → 顶层 reasoning_effort；deepseek → 均不下发。'
    + '深度思考勾选（true）表示走端点默认行为。</div>'
    + (isEdit
        ? '<div style="color:var(--text-dim);font-size:11px;margin:0 0 10px;line-height:1.6;font-family:var(--mono)">'
          + 'ID: ' + ctx.utils.esc(m.id || '—')
          + (m.is_default ? ' · default' : '') + (m.is_fallback ? ' · fallback' : '') + '<br>'
          + 'Created: ' + ctx.utils.esc(m.created_at || '—') + '<br>'
          + 'Updated: ' + ctx.utils.esc(m.updated_at || '—') + '</div>'
        : '')
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
  // reasoningEffort：格式与后端一致（^[a-z0-9_]{1,16}$）；编辑时留空 = 清除（PATCH 空串语义）
  const reasoningEffort = val('mfReasoningEffort').toLowerCase();
  if (reasoningEffort && !/^[a-z0-9_]{1,16}$/.test(reasoningEffort)) {
    ctx.utils.toast('Reasoning Effort 仅支持小写字母/数字/下划线，最长 16 位', 'error');
    return;
  }
  const frequencyPenalty = num('mfFreqPenalty');
  if (frequencyPenalty != null && (frequencyPenalty < -2 || frequencyPenalty > 2)) {
    ctx.utils.toast('Frequency Penalty 必须在 [-2, 2] 区间', 'error');
    return;
  }
  const body = {
    name,
    provider: val('mfProvider') || undefined,
    modelId,
    baseUrl,
    temperature: num('mfTemp'),
    maxTokens: num('mfMaxTokens'),
    reasoningEffort,
    frequencyPenalty,
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
