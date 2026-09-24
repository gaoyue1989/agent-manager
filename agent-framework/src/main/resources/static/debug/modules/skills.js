/* ===== Skills 模块：技能列表 + 管理（上传/删除/启停/修改） ===== */

let ctx = null;
let skillData = [];
let editingSkill = null; // 当前正在编辑的 skill name
let userSkillState = { userId: '', skills: [] }; // 用户技能（L4）区块状态
let userSkillSeq = 0;    // 用户技能加载序号：并发多次「加载」时只认最后一次响应
let sandboxEnabled = false; // 沙箱档（SANDBOX_ENABLED=true）：管理面写 L4 不会注入会话容器
let userIndexTruncated = false; // 用户索引触顶截断（truncated=true）：下拉只含前 5000 个用户

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
      <div class="module-scroll">
        <div id="skillsBody"><div class="empty">Loading...</div></div>
        <div id="userSkillsPanel"></div>
      </div>
    </div>`;

    // 事件绑定
    document.getElementById('skillZipInput').addEventListener('change', handleUpload);
    document.getElementById('skillRefreshBtn').addEventListener('click', handleRefreshAll);

    renderUserSkillsPanel();
    loadSkills();
    loadSandboxState();
  },
  unmount() {
    ctx = null;
    editingSkill = null;
    userSkillState = { userId: '', skills: [] };
    userSkillSeq++;  // 作废在途请求的渲染
    var input = document.getElementById('skillZipInput');
    if (input) input.removeEventListener('change', handleUpload);
    var btn = document.getElementById('skillRefreshBtn');
    if (btn) btn.removeEventListener('click', handleRefreshAll);
  }
};

function handleRefreshAll() {
  loadSkills();
  loadUserSkills();
}

async function loadSkills() {
  const body = document.getElementById('skillsBody');
  const summary = document.getElementById('skillsSummary');
  try {
    skillData = (await ctx.api.getSkillsManage()) || [];
  } catch (e) {
    if (!ctx) return; // 已切走（unmount 置空 ctx）：丢弃过期渲染，避免读 null 的 utils
    body.innerHTML = '<div class="empty error-text">Failed to load skills: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  if (!ctx) return; // await 期间可能已 unmount，过期响应不再渲染

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

/* ===== 用户技能（L4 个人覆盖）=====
   存储是 agent_fs KV 的 agents/{agent}/users/{userId}/skills（与 SDK workspace-writable
   仓库同一命名空间），与上面的 L2（/config/skills 包内目录，PVC 文件）是两层不同存储：
   非沙箱档同名时 L4 覆盖 L2，删除 L4 即回落包内基线；沙箱档（SANDBOX_ENABLED=true）会话读的是
   容器内 /workspace/skills 副本，管理面写入不会注入容器（提示文案见 userSkillHintText()）。
   写入不对包内文件产生任何影响。 */

function renderUserSkillsPanel() {
  var panel = document.getElementById('userSkillsPanel');
  if (!panel) return;
  panel.innerHTML =
    '<div class="panel">' +
      '<div class="panel-header">' +
        '<span class="title">👤 用户技能（个人覆盖 L4）</span>' +
        '<span class="count" id="userSkillSummary"></span>' +
      '</div>' +
      '<div class="panel-body">' +
        '<p id="userSkillHint" style="color:var(--text-dim);font-size:11px">' +
          ctx.utils.esc(userSkillHintText()) +
        '</p>' +
        '<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:8px 0">' +
          '<input id="userSkillUserInput" class="input" type="text" list="userSkillUserList" ' +
            'placeholder="userId" style="width:180px" spellcheck="false">' +
          '<datalist id="userSkillUserList"></datalist>' +
          '<button class="btn small" id="userSkillLoadBtn">🔎 加载</button>' +
          '<input id="userSkillNameInput" class="input" type="text" placeholder="技能名" ' +
            'style="width:140px" spellcheck="false">' +
          '<button class="btn small primary" id="userSkillWriteBtn" ' +
            'title="新建或覆盖该用户的 SKILL.md">✏ 写入/覆盖</button>' +
          '<button class="btn small" id="userSkillSyncBtn" ' +
            'title="把包内同名技能整目录下发为该用户个人版本">⬇ 从包内下发</button>' +
        '</div>' +
        '<div id="userSkillList"><div class="empty">选择或输入 userId 后点「加载」。</div></div>' +
      '</div>' +
    '</div>';

  document.getElementById('userSkillLoadBtn').addEventListener('click', loadUserSkills);
  document.getElementById('userSkillUserInput').addEventListener('keydown', function(e) {
    if (e.key === 'Enter') loadUserSkills();
  });
  document.getElementById('userSkillWriteBtn').addEventListener('click', handleUserSkillWrite);
  document.getElementById('userSkillSyncBtn').addEventListener('click', handleUserSkillSync);

  loadUserSkillUsers();
}

/** 沙箱档查询：写入 L4 是否回注会话容器（决定提示文案；取不到按非沙箱档口径） */
async function loadSandboxState() {
  try {
    var sb = (await ctx.api.getSandbox()) || {};
    sandboxEnabled = !!sb.enabled;
  } catch (e) {
    sandboxEnabled = false;
  }
  var hint = document.getElementById('userSkillHint');
  if (hint) hint.textContent = userSkillHintText();
}

/** 生效范围提示文案：沙箱档会话读容器内 /workspace/skills 副本，管理面 L4 不会注入容器 */
function userSkillHintText() {
  if (sandboxEnabled) {
    return '写入 agent_fs（agents/{agent}/users/{userId}/skills）；当前 SANDBOX_ENABLED=true：' +
      '会话读的是容器内 /workspace/skills 副本，管理面写入不会注入会话容器，需容器换代或' +
      '“会话开始物化 L4”能力才对会话生效。写入/删除会在 KV 置仲裁标记（.admin-override / .deleted），' +
      '回写（每次 call 结束）命中即跳过同名技能——管理面内容不会被同代容器内旧副本改回，' +
      '代价是该技能在容器内的 skill_manage 修改在标记生效期间不落库（重新写入/从包内下发/删除可清除标记）。' +
      '其他用户不受影响。';
  }
  return '写入 agent_fs（agents/{agent}/users/{userId}/skills），该用户下一轮会话生效；' +
    '删除个人覆盖后回落包内（L2）同名技能基线。其他用户不受影响。';
}

/** 用户索引（存在个人覆盖的 userId）：下拉候选 + 概览计数；索引失败不阻塞手填 */
async function loadUserSkillUsers() {
  var list = document.getElementById('userSkillUserList');
  if (!list) return;
  var summary = document.getElementById('userSkillSummary');
  try {
    var data = await ctx.api.getUserSkillUsers();
    if (!ctx) return; // await 期间可能已 unmount，过期响应不再渲染
    var users = (data && data.users) || [];
    userIndexTruncated = !!(data && data.truncated);
    list.innerHTML = users.map(function(u) {
      return '<option value="' + ctx.utils.esc(u.userId) + '">' +
        ctx.utils.esc((u.skillCount || 0) + ' skill(s)') + '</option>';
    }).join('');
    list.title = userIndexTruncated
      ? '索引触顶截断：下拉只含前 ' + users.length + ' 个用户，其他用户请手工输入 userId'
      : '';
    if (summary && !userSkillState.userId) {
      // truncated=true 必须显式提示（后端契约），否则用户数 >5000 时下拉不全却看不出
      summary.textContent = userIndexTruncated
        ? '≥' + users.length + ' user(s)（索引触顶截断，仅前 ' + users.length
          + ' 个，其他用户请手工输入 userId）'
        : users.length + ' user(s)';
      summary.style.color = userIndexTruncated ? 'var(--yellow)' : '';
      summary.title = userIndexTruncated
        ? '索引触顶截断（用户数 >5000 或行数 >100000）：下拉列表不完整' : '';
    }
  } catch (e) {
    // 索引读取失败（500：索引存储不可用）不得静默显示 0 user(s)：给醒目提示，手填仍可用
    userIndexTruncated = false;
    list.innerHTML = '';
    if (summary && !userSkillState.userId) {
      summary.textContent = 'user index unavailable: ' + e.message;
      summary.style.color = 'var(--red)';
      summary.title = '索引接口失败（如 DB 不可用），可手工输入 userId 后点「加载」查看该用户';
    }
  }
}

async function loadUserSkills() {
  var input = document.getElementById('userSkillUserInput');
  var userId = input ? input.value.trim() : '';
  var body = document.getElementById('userSkillList');
  var summary = document.getElementById('userSkillSummary');
  if (!body) return;
  // 并发加载防串号：只认最后一次请求的响应，且“行 → 用户”在渲染时固化，
  // 避免先发的 A 后返回时渲染 A 的技能行、状态却已是 B（删除/覆盖会落到另一个用户名下）
  var seq = ++userSkillSeq;
  if (!userId) {
    userSkillState = { userId: '', skills: [] };
    body.innerHTML = '<div class="empty">请输入 userId。</div>';
    return;
  }
  body.innerHTML = '<div class="empty">Loading...</div>';
  var skills, tombstones;
  try {
    var data = await ctx.api.getUserSkills(userId);
    skills = (data && data.skills) || [];
    // 已删除但标记仍在的技能（不在 L4 列表里，必须单独提示，否则“重建了却不落库”无从解释）
    tombstones = (data && data.tombstones) || [];
  } catch (e) {
    if (seq !== userSkillSeq || !ctx) return; // 过期响应或已切走（unmount 置空 ctx）：丢弃
    body.innerHTML = '<div class="empty error-text">加载失败: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  if (seq !== userSkillSeq || !ctx) return; // 过期响应：丢弃，不覆盖更新的加载结果；已切走时同样不再渲染
  userSkillState = { userId: userId, skills: skills };
  if (summary) {
    summary.textContent = userId + ' · ' + skills.length + ' 个人技能'
      + (tombstones.length ? ' + ' + tombstones.length + ' 个删除标记' : '');
    summary.style.color = '';
    summary.title = '';
  }
  if (skills.length === 0 && tombstones.length === 0) {
    body.innerHTML = '<div class="empty">该用户暂无个人覆盖（点「写入/覆盖」或「从包内下发」即可创建）。</div>';
    return;
  }

  var rows = skills.map(function(s) {
    var baseline = s.hasPackageBaseline
      ? '<span class="badge dim" title="删除后回落该包内技能">有包内基线</span>'
      : '<span class="badge yellow" title="包内无同名技能，删除后技能消失">无基线</span>';
    // 管理面写入栅栏：回写会跳过该技能 → 管理面内容不会被容器内旧副本改回，
    // 但该技能在容器内的 skill_manage 修改也不会落库（删除可清除栅栏）
    var fence = s.adminOverride
      ? '<span class="badge yellow" title="管理面写入栅栏：回写跳过该技能（管理面内容优先），' +
        '容器内 skill_manage 的修改在清除前不落库">管理面栅栏</span>'
      : '';
    return '<tr>' +
      '<td class="mono">' + ctx.utils.esc(s.name) + ' <span class="badge green">个人覆盖</span> ' + fence + '</td>' +
      '<td>' + ((s.files || []).length) + '</td>' +
      '<td>' + ctx.utils.formatBytes(s.bytes || 0) + '</td>' +
      '<td>v' + ctx.utils.esc(s.version) + '</td>' +
      '<td>' + baseline + '</td>' +
      '<td style="white-space:nowrap">' +
        '<button class="btn small us-view-btn" data-name="' + ctx.utils.esc(s.name) + '">👁 查看</button> ' +
        '<button class="btn small us-edit-btn" data-name="' + ctx.utils.esc(s.name) + '">✏ 编辑</button> ' +
        '<button class="btn small danger us-del-btn" data-name="' + ctx.utils.esc(s.name) + '">🗑 删除</button>' +
      '</td></tr>';
  }).join('');

  body.innerHTML = (skills.length
    ? '<table class="data-table"><thead><tr>' +
        '<th>Skill</th><th>文件</th><th>大小</th><th>版本</th><th>删除后</th><th></th>' +
      '</tr></thead><tbody>' + rows + '</tbody></table>'
    : '<div class="empty">该用户暂无个人覆盖（下方为已删除技能的标记）。</div>')
    + tombstonesHtml(tombstones);

  // 行操作把本次响应对应的 userId 固化进闭包（不再读可变状态）
  body.querySelectorAll('.us-view-btn').forEach(function(btn) {
    btn.addEventListener('click', function() { handleUserSkillView(userId, btn.dataset.name); });
  });
  body.querySelectorAll('.us-edit-btn').forEach(function(btn) {
    btn.addEventListener('click', function() { handleUserSkillEdit(userId, btn.dataset.name); });
  });
  body.querySelectorAll('.us-del-btn').forEach(function(btn) {
    btn.addEventListener('click', function() { handleUserSkillDelete(userId, btn.dataset.name); });
  });
}

/**
 * 删除标记（tombstone）提示块：标记无 TTL，生效期间该用户在同代容器内用 skill_manage
 * 重建同名技能会被回写侧跳过（KV 不落库、容器换代即丢），必须显式提示后果与清除方式。
 */
function tombstonesHtml(tombstones) {
  if (!tombstones || tombstones.length === 0) return '';
  var items = tombstones.map(function(t) {
    return '<li><span class="mono">' + ctx.utils.esc(t.name) + '</span>' +
      (t.deletedAt ? ' <span class="badge dim">删除于 ' + ctx.utils.esc(t.deletedAt) + '</span>' : '') +
      '</li>';
  }).join('');
  return '<div class="panel" style="margin-top:8px;border-color:var(--yellow-border)">' +
    '<div class="panel-header">' +
      '<span class="title">🗑 已删除（保留删除标记 tombstone）</span>' +
      '<span class="count">' + tombstones.length + '</span>' +
    '</div>' +
    '<div class="panel-body">' +
      '<p style="color:var(--yellow);font-size:12px;margin:0 0 4px">' +
        '标记生效期间，该用户在同代（及后续）容器内用 skill_manage 重建同名技能<b>不会被回写落库</b>' +
        '（容器换代即丢）；需管理面重新写入或从包内下发才会清除标记并恢复落库。' +
      '</p>' +
      '<ul style="margin:0 0 0 16px;font-size:12px;color:var(--text-dim)">' + items + '</ul>' +
    '</div></div>';
}

async function handleUserSkillView(userId, name) {
  if (!userId) return;
  try {
    var data = await ctx.api.getUserSkill(userId, name);
    var src = data.source === 'user' ? '个人覆盖（L4）' : '包内基线（L2）';
    ctx.utils.showTextModal('用户技能: ' + userId + ' / ' + name + ' [' + src + ']',
      data.content || '');
  } catch (err) {
    ctx.utils.toast('读取失败: ' + err.message, 'error');
  }
}

async function handleUserSkillEdit(userId, name) {
  if (!userId) return;
  try {
    // 已是个人覆盖时回填当前内容；只有包内基线时回填基线内容（保存即生成个人覆盖）
    var data = await ctx.api.getUserSkill(userId, name);
    showUserSkillEditModal(userId, name, data.content || '', data.source);
  } catch (err) {
    ctx.utils.toast('加载失败: ' + err.message, 'error');
  }
}

/** 新建/覆盖个人技能：技能名可编辑（不存在则创建） */
async function handleUserSkillWrite() {
  var userId = userStateId();
  var nameInput = document.getElementById('userSkillNameInput');
  var name = nameInput ? nameInput.value.trim() : '';
  if (!userId) { ctx.utils.toast('请先填写 userId', 'error'); return; }
  if (!name) { ctx.utils.toast('请先填写技能名', 'error'); return; }
  var content = '';
  var source = 'new';
  try {
    var data = await ctx.api.getUserSkill(userId, name);
    content = data.content || '';
    source = data.source || 'user';
  } catch (e) {
    // 只有“两侧都不存在”（404）才回落空内容新建；5xx/网络错误必须中止，
    // 否则空白起编会把该用户已有的个人覆盖整份覆盖掉
    if (e.status !== 404) {
      ctx.utils.toast('读取失败: ' + e.message, 'error');
      return;
    }
  }
  showUserSkillEditModal(userId, name, content, source);
}

function showUserSkillEditModal(userId, name, content, source) {
  var modal = window.App.modal;
  var hint = source === 'package'
    ? '当前显示包内基线内容；保存后生成该用户的个人覆盖（不动包内文件）。'
    : '保存写入 agent_fs 个人覆盖。';
  hint += sandboxEnabled
    ? '当前 SANDBOX_ENABLED=true：仅写管理面 KV，不会注入沙箱会话容器。'
    : '该用户下一轮会话生效。';
  modal.open(
    '<h2>✏ 用户技能: ' + ctx.utils.esc(userId) + ' / ' + ctx.utils.esc(name) + '</h2>' +
    '<p style="color:var(--text-dim);font-size:11px;margin-bottom:8px">' + hint + '</p>' +
    '<textarea id="skillEditArea" class="skill-edit-area" spellcheck="false">' +
    ctx.utils.esc(content) +
    '</textarea>' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:10px">' +
    '<button class="btn" id="userSkillEditCancel">取消</button>' +
    '<button class="btn primary" id="userSkillEditSave">💾 保存</button>' +
    '</div>'
  );

  document.getElementById('userSkillEditCancel').addEventListener('click', function() {
    modal.close();
  });

  document.getElementById('userSkillEditSave').addEventListener('click', async function() {
    var newContent = document.getElementById('skillEditArea').value;
    var saveBtn = document.getElementById('userSkillEditSave');
    saveBtn.disabled = true;
    saveBtn.textContent = '保存中...';
    try {
      var result = await ctx.api.putUserSkill(userId, name, newContent);
      ctx.utils.toast(result.message || '已保存', 'success');
      modal.close();
      loadUserSkills();
      loadUserSkillUsers();
    } catch (err) {
      ctx.utils.toast('保存失败: ' + err.message, 'error');
      saveBtn.disabled = false;
      saveBtn.textContent = '💾 保存';
    }
  });
}

async function handleUserSkillDelete(userId, name) {
  if (!userId) return;
  if (!confirm('删除用户 "' + userId + '" 的技能 "' + name + '" 个人覆盖？\n' +
      '删除后该用户回落包内同名技能基线（包内无同名技能则该技能消失），不可恢复。')) return;
  try {
    var result = await ctx.api.deleteUserSkill(userId, name);
    ctx.utils.toast(result.message || '已删除', 'success');
    loadUserSkills();
    loadUserSkillUsers();
  } catch (err) {
    ctx.utils.toast('删除失败: ' + err.message, 'error');
  }
}

async function handleUserSkillSync() {
  var userId = userStateId();
  var nameInput = document.getElementById('userSkillNameInput');
  var name = nameInput ? nameInput.value.trim() : '';
  if (!userId) { ctx.utils.toast('请先填写 userId', 'error'); return; }
  if (!name) { ctx.utils.toast('请先填写技能名', 'error'); return; }
  if (!confirm('把包内技能 "' + name + '" 整目录下发为用户 "' + userId + '" 的个人版本？')) return;
  try {
    var result = await ctx.api.syncUserSkillFromPackage(userId, name);
    ctx.utils.toast(result.message || ('已下发 ' + ((result.files || []).length) + ' 个文件'), 'success');
    loadUserSkills();
    loadUserSkillUsers();
  } catch (err) {
    ctx.utils.toast('下发失败: ' + err.message, 'error');
  }
}

/** 当前 userId（以输入框为准，编辑/删除时避免用到旧状态） */
function userStateId() {
  var input = document.getElementById('userSkillUserInput');
  return input ? input.value.trim() : userSkillState.userId;
}
