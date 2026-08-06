/* ===== Skills 模块：技能列表 + 详情 ===== */

let ctx = null;
let skillData = [];

export default {
  mount(container, c) {
    ctx = c;
    container.innerHTML = `
    <div class="module-page">
      <div class="module-header"><h2>Skills</h2><span class="sub" id="skillsSummary"></span></div>
      <div class="module-scroll" id="skillsBody"><div class="empty">Loading...</div></div>
    </div>`;
    loadSkills();
  },
  unmount() { ctx = null; }
};

async function loadSkills() {
  const body = document.getElementById('skillsBody');
  try {
    skillData = (await ctx.api.getSkills()) || [];
  } catch (e) {
    body.innerHTML = '<div class="empty error-text">Failed to load skills: ' + ctx.utils.esc(e.message) + '</div>';
    return;
  }
  document.getElementById('skillsSummary').textContent = skillData.length + ' skill(s)';

  if (skillData.length === 0) {
    body.innerHTML = '<div class="empty">No skills</div>';
    return;
  }

  body.innerHTML = '<div class="info-grid">' + skillData.map((s, idx) => {
    const badges = [];
    if (s.source) badges.push('<span class="badge dim">' + ctx.utils.esc(s.source) + '</span>');
    if (s.version) badges.push('<span class="badge dim">v' + ctx.utils.esc(s.version) + '</span>');
    return '<div class="panel">' +
      '<div class="panel-header"><span class="title">📚 ' + ctx.utils.esc(s.name || '?') + '</span>' +
      '<span class="count">' + badges.join(' ') + '</span></div>' +
      '<div class="panel-body">' +
      '<p style="color:var(--text-dim);font-size:11px">' + ctx.utils.esc(s.description || 'No description') + '</p>' +
      '<button class="btn small skill-detail-btn" data-idx="' + idx + '" style="margin-top:8px">View Detail</button>' +
      '</div></div>';
  }).join('') + '</div>';

  body.querySelectorAll('.skill-detail-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const skill = skillData[Number(btn.dataset.idx)];
      ctx.utils.showJsonModal('Skill: ' + (skill.name || ''), skill);
    });
  });
}
