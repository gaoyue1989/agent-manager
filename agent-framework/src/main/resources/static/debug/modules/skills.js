/* ===== Skills 模块：技能列表 + 管理（上传/删除/启停/修改） ===== */

let ctx = null;
let skillData = [];
let editingSkill = null; // 当前正在编辑的 skill name

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header">
        <h2>📚 Skills</h2>
        <span class="sub" id="skillsSummary"></span>
        <div style="margin-left:auto;display:flex;gap:8px;align-items:center;">
          <label class="btn small accent upload-label" for="skillZipInput" title="上传 zip 格式的 Skill 包">
            ⬆ 上传 Skill
          </label>
          <input type="file" id="skillZipInput" accept=".zip" style="display:none" />
          <button class="btn small" id="skillRefreshBtn" title="刷新列表">🔄</button>
        </div>
      </div>
      <div class="module-scroll" id="skillsBody"><div class="empty">Loading...</div></div>
    </div>`;

    // 事件绑定
    document.getElementById('skillZipInput').addEventListener('change', handleUpload);
    document.getElementById('skillRefreshBtn').addEventListener('click', loadSkills);

    loadSkills();
  },
  unmount() {
    ctx = null;
    editingSkill = null;
    var input = document.getElementById('skillZipInput');
    if (input) input.removeEventListener('change', handleUpload);
    var btn = document.getElementById('skillRefreshBtn');
    if (btn) btn.removeEventListener('click', loadSkills);
  }
};

async function loadSkills() {
  const body = document.getElementById('skillsBody');
  const summary = document.getElementById('skillsSummary');
  try {
    skillData = (await ctx.api.getSkillsManage()) || [];
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load skills: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }

  var enabled = skillData.filter(function(s) { return s.enabled !== false; }).length;
  summary.textContent = skillData.length + ' skill(s), ' + enabled + ' enabled';

  if (skillData.length === 0) {
    body.innerHTML = '<div class="empty">No skills. Click "⬆ 上传 Skill" to add one.</div>';
    return;
  }

  body.innerHTML = '<div class="info-grid">' + skillData.map(function(s, idx) {
    var badges = [];
    if (s.source) badges.push('<span class="badge dim">' + ctx.utils.esc(s.source) + '</span>');
    if (s.version) badges.push('<span class="badge dim">v' + ctx.utils.esc(s.version) + '</span>');
    if (s.dynamic) badges.push('<span class="badge green">dynamic</span>');
    if (s.required) badges.push('<span class="badge yellow">required</span>');
    if (s.declaredButMissing) badges.push('<span class="badge red">missing</span>');

    var enabledBadge = s.enabled !== false
      ? '<span class="badge green">启用</span>'
      : '<span class="badge red">禁用</span>';

    return '<div class="panel skill-card' + (s.enabled === false ? ' disabled' : '') + '">' +
      '<div class="panel-header">' +
        '<span class="title">📚 ' + ctx.utils.esc(s.name || '?') + '</span>' +
        '<span class="count">' + enabledBadge + ' ' + badges.join(' ') + '</span>' +
      '</div>' +
      '<div class="panel-body">' +
        '<p style="color:var(--text-dim);font-size:11px">' + ctx.utils.esc(s.description || 'No description') + '</p>' +
        '<div class="skill-actions">' +
          '<button class="btn small skill-toggle-btn" data-idx="' + idx + '">' +
            (s.enabled !== false ? '⏸ 禁用' : '▶ 启用') +
          '</button>' +
          '<button class="btn small skill-detail-btn" data-idx="' + idx + '">👁 详情</button>' +
          '<button class="btn small skill-edit-btn" data-idx="' + idx + '">✏ 编辑</button>' +
          (s.source === 'local-dynamic' || s.dynamic
            ? '<button class="btn small danger skill-delete-btn" data-idx="' + idx + '">🗑 删除</button>'
            : '') +
        '</div>' +
      '</div></div>';
  }).join('') + '</div>';

  // 绑定事件
  body.querySelectorAll('.skill-toggle-btn').forEach(function(btn) {
    btn.addEventListener('click', function() { handleToggle(Number(btn.dataset.idx)); });
  });
  body.querySelectorAll('.skill-detail-btn').forEach(function(btn) {
    btn.addEventListener('click', function() { handleDetail(Number(btn.dataset.idx)); });
  });
  body.querySelectorAll('.skill-edit-btn').forEach(function(btn) {
    btn.addEventListener('click', function() { handleEdit(Number(btn.dataset.idx)); });
  });
  body.querySelectorAll('.skill-delete-btn').forEach(function(btn) {
    btn.addEventListener('click', function() { handleDelete(Number(btn.dataset.idx)); });
  });
}

async function handleUpload(e) {
  var file = e.target.files && e.target.files[0];
  if (!file) return;
  // 重置 input 以允许重复上传同一文件
  e.target.value = '';
  ctx.utils.toast('正在上传 ' + file.name + '...', 'info');
  try {
    var result = await ctx.api.uploadSkill(file);
    ctx.utils.toast(result.message || '上传成功', 'success');
    loadSkills();
  } catch (err) {
    ctx.utils.toast('上传失败: ' + err.message, 'error');
  }
}

async function handleToggle(idx) {
  var skill = skillData[idx];
  if (!skill) return;
  try {
    var result = await ctx.api.toggleSkill(skill.name);
    ctx.utils.toast(result.message || '操作成功', 'success');
    loadSkills();
  } catch (err) {
    ctx.utils.toast('操作失败: ' + err.message, 'error');
  }
}

async function handleDelete(idx) {
  var skill = skillData[idx];
  if (!skill) return;
  if (!confirm('确定删除 Skill "' + skill.name + '"？此操作不可恢复。')) return;
  try {
    var result = await ctx.api.deleteSkill(skill.name);
    ctx.utils.toast(result.message || '删除成功', 'success');
    loadSkills();
  } catch (err) {
    ctx.utils.toast('删除失败: ' + err.message, 'error');
  }
}

function handleDetail(idx) {
  var skill = skillData[idx];
  if (!skill) return;
  ctx.utils.showJsonModal('Skill: ' + (skill.name || ''), skill);
}

async function handleEdit(idx) {
  var skill = skillData[idx];
  if (!skill) return;
  editingSkill = skill.name;

  // 先获取 SKILL.md 内容
  ctx.utils.toast('正在加载 Skill 内容...', 'info');
  try {
    var data = await ctx.api.getSkillContent(skill.name);
    var content = data.content || '';
    showEditModal(skill.name, content);
  } catch (err) {
    ctx.utils.toast('加载失败: ' + err.message, 'error');
  }
}

function showEditModal(name, content) {
  var modal = window.App.modal;
  modal.open(
    '<h2>✏ 编辑 Skill: ' + ctx.utils.esc(name) + '</h2>' +
    '<p style="color:var(--text-dim);font-size:11px;margin-bottom:8px">修改 SKILL.md 内容。保存后下轮推理自动生效。</p>' +
    '<textarea id="skillEditArea" class="skill-edit-area" spellcheck="false">' +
    ctx.utils.esc(content) +
    '</textarea>' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:10px">' +
    '<button class="btn" id="skillEditCancel">取消</button>' +
    '<button class="btn primary" id="skillEditSave">💾 保存</button>' +
    '</div>'
  );

  document.getElementById('skillEditCancel').addEventListener('click', function() {
    modal.close();
    editingSkill = null;
  });

  document.getElementById('skillEditSave').addEventListener('click', async function() {
    var newContent = document.getElementById('skillEditArea').value;
    var saveBtn = document.getElementById('skillEditSave');
    saveBtn.disabled = true;
    saveBtn.textContent = '保存中...';
    try {
      var result = await ctx.api.updateSkillContent(name, newContent);
      ctx.utils.toast(result.message || '保存成功', 'success');
      modal.close();
      editingSkill = null;
      loadSkills();
    } catch (err) {
      ctx.utils.toast('保存失败: ' + err.message, 'error');
      saveBtn.disabled = false;
      saveBtn.textContent = '💾 保存';
    }
  });
}
