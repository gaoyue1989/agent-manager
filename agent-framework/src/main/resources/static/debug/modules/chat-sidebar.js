import { icon } from '../js/icons.js';

export function createChatSidebar({ ctx, onSelectThread, onNewThread, onThreadDeleted, onThreadRenamed, onBrowserRelease }) {
  let threadListEl = null;
  let searchInputEl = null;
  let sidebarEl = null;
  let contextDrawerEl = null;
  let contextBtnEl = null;
  let browserBarEl = null;
  let searchQuery = '';
  let contextOpen = false;
  let browserVisible = false;
  let browserState = null;

  function uid() {
    return ctx.state.getState('ui.userId') || 'debug-user';
  }

  function currentSid() {
    return ctx.state.getState('threads.current');
  }

  function shortId(sid) {
    if (!sid) return '';
    return sid.length > 18 ? sid.slice(0, 8) + '…' + sid.slice(-6) : sid;
  }

  function mount(root) {
    sidebarEl = root.querySelector('.chat-sidebar');
    threadListEl = root.querySelector('#threadList');
    searchInputEl = root.querySelector('#threadSearch');
    contextDrawerEl = root.querySelector('#contextDrawer');
    contextBtnEl = root.querySelector('#btnContextToggle');
    browserBarEl = root.querySelector('#browserControlBar');

    const newBtn = root.querySelector('#btnNewThread');
    if (newBtn) {
      newBtn.innerHTML = icon('plus', 15);
      newBtn.addEventListener('click', () => {
        closeMobile();
        onNewThread();
      });
    }

    if (searchInputEl) {
      searchInputEl.addEventListener('input', () => {
        searchQuery = (searchInputEl.value || '').trim().toLowerCase();
        renderList(ctx.state.getState('threads.list') || []);
      });
    }

    const mobileSessBtn = root.querySelector('#btnMobileSessions');
    if (mobileSessBtn) {
      mobileSessBtn.innerHTML = icon('sessions', 16);
      mobileSessBtn.addEventListener('click', () => {
        if (sidebarEl) sidebarEl.classList.toggle('mobile-open');
      });
    }

    if (contextBtnEl) {
      contextBtnEl.addEventListener('click', () => toggleContextDrawer());
    }
  }

  function closeMobile() {
    if (sidebarEl) sidebarEl.classList.remove('mobile-open');
  }

  function renderList(threads) {
    if (!threadListEl) return;
    const activeSid = currentSid();
    const filtered = (threads || []).filter((t) => {
      if (!searchQuery) return true;
      const hay = ((t.title || '') + ' ' + (t.session_id || '') + ' ' + (t.thread_id || '')).toLowerCase();
      return hay.includes(searchQuery);
    });

    if (filtered.length === 0) {
      threadListEl.innerHTML = '<div class="empty">' + (searchQuery ? '无匹配会话' : '暂无会话') + '</div>';
      return;
    }

    threadListEl.innerHTML = filtered.map((t) => {
      const sid = t.session_id;
      const hasTitle = Boolean(t.title && t.title.trim());
      const label = hasTitle ? t.title.trim() : shortId(sid);
      const active = sid === activeSid ? ' active' : '';
      const time = t.updated_at ? String(t.updated_at).substring(5, 16) : '';
      const subParts = [];
      if (hasTitle) subParts.push(shortId(sid));
      if (t.model) subParts.push(t.model);
      if (time) subParts.push(time);
      return '<div class="thread-item' + active + '" data-sid="' + ctx.utils.esc(sid) + '">' +
        '<div class="thread-main">' +
          '<div class="tid' + (hasTitle ? '' : ' mono') + '" title="' + ctx.utils.esc(t.title || sid) + '">' + ctx.utils.esc(label) + '</div>' +
          '<div class="meta">' + ctx.utils.esc(subParts.join(' · ') || sid) + '</div>' +
        '</div>' +
        '<div class="thread-actions">' +
          '<button class="thread-action-btn" data-act="rename" title="重命名">' + icon('edit', 13) + '</button>' +
          '<button class="thread-action-btn danger" data-act="delete" title="删除">' + icon('trash', 13) + '</button>' +
        '</div>' +
      '</div>';
    }).join('');

    threadListEl.querySelectorAll('.thread-item').forEach((el) => {
      const sid = el.dataset.sid;
      el.addEventListener('click', (e) => {
        if (e.target.closest('.thread-actions')) return;
        closeMobile();
        onSelectThread(sid);
      });
      const renameBtn = el.querySelector('[data-act="rename"]');
      if (renameBtn) {
        renameBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          openRenameModal(sid);
        });
      }
      const deleteBtn = el.querySelector('[data-act="delete"]');
      if (deleteBtn) {
        deleteBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          openDeleteModal(sid);
        });
      }
    });
  }

  function openRenameModal(sid) {
    const list = ctx.state.getState('threads.list') || [];
    const cur = list.find((t) => t.session_id === sid);
    const currentTitle = (cur && cur.title) || '';
    ctx.modal.open(
      '<h2>重命名会话</h2>' +
      '<div style="display:flex;flex-direction:column;gap:12px">' +
        '<input id="renameSessionInput" class="input" type="text" value="' + ctx.utils.esc(currentTitle) + '" placeholder="请输入会话标题">' +
        '<div style="display:flex;justify-content:flex-end;gap:8px">' +
          '<button class="btn" id="renameCancelBtn">取消</button>' +
          '<button class="btn primary" id="renameSaveBtn">保存</button>' +
        '</div>' +
      '</div>'
    );
    const input = document.getElementById('renameSessionInput');
    if (input) { input.focus(); input.select(); }
    document.getElementById('renameCancelBtn').addEventListener('click', () => ctx.modal.close());
    const save = async () => {
      const title = (input.value || '').trim();
      if (!title) { ctx.utils.toast('标题不能为空', 'warn'); return; }
      try {
        await ctx.api.patchThread(sid, { title }, uid());
        ctx.modal.close();
        ctx.utils.toast('会话已重命名', 'success');
        onThreadRenamed(sid, title);
      } catch (e) {
        ctx.utils.toast('重命名失败: ' + e.message, 'error');
      }
    };
    document.getElementById('renameSaveBtn').addEventListener('click', save);
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); });
  }

  function openDeleteModal(sid) {
    const list = ctx.state.getState('threads.list') || [];
    const cur = list.find((t) => t.session_id === sid);
    const label = (cur && cur.title) || sid;
    ctx.modal.open(
      '<h2>删除会话</h2>' +
      '<p style="font-size:13px;color:var(--text);margin-bottom:14px">确认删除会话 <strong>' + ctx.utils.esc(label) + '</strong> 及其关联的消息、上下文和文件？</p>' +
      '<div style="display:flex;justify-content:flex-end;gap:8px">' +
        '<button class="btn" id="delCancelBtn">取消</button>' +
        '<button class="btn danger" id="delConfirmBtn">确认删除</button>' +
      '</div>'
    );
    document.getElementById('delCancelBtn').addEventListener('click', () => ctx.modal.close());
    document.getElementById('delConfirmBtn').addEventListener('click', async () => {
      try {
        await ctx.api.deleteThread(sid, uid());
        ctx.modal.close();
        ctx.utils.toast('会话已删除', 'success');
        onThreadDeleted(sid);
      } catch (e) {
        ctx.utils.toast('删除失败: ' + e.message, 'error');
      }
    });
  }

  function updateContextBadge(count) {
    if (!contextBtnEl) return;
    const badge = contextBtnEl.querySelector('.ctx-count');
    if (badge) {
      badge.textContent = count > 0 ? String(count) : '';
      badge.style.display = count > 0 ? 'inline-flex' : 'none';
    }
  }

  function setContextFromHistory(contextList) {
    const count = Array.isArray(contextList) ? contextList.length : 0;
    updateContextBadge(count);
    if (contextOpen) loadContextDrawer();
  }

  function toggleContextDrawer(forceOpen) {
    contextOpen = forceOpen !== undefined ? forceOpen : !contextOpen;
    if (contextDrawerEl) contextDrawerEl.classList.toggle('open', contextOpen);
    if (contextOpen) loadContextDrawer();
  }

  async function loadContextDrawer() {
    if (!contextDrawerEl) return;
    const sid = currentSid();
    if (!sid) {
      contextDrawerEl.innerHTML = '<div class="empty">请先选择或发送一个会话以管理上下文资料。</div>';
      return;
    }
    contextDrawerEl.innerHTML = '<div class="empty">正在加载会话上下文...</div>';
    try {
      const data = await ctx.api.getThreadContext(sid, uid(), true);
      renderContextDrawer(sid, data);
    } catch (e) {
      if (e && e.status === 404) {
        renderContextDrawer(sid, { entries: [], totalChars: 0, maxTotalChars: 0, unpersisted: true });
        return;
      }
      contextDrawerEl.innerHTML = '<div class="empty error-text">加载会话上下文失败: ' + ctx.utils.esc(e.message) + '</div>';
    }
  }

  function renderContextDrawer(sid, data) {
    const entries = data.entries || [];
    const total = data.totalChars || 0;
    const max = data.maxTotalChars || 0;
    const pct = max > 0 ? Math.min(100, Math.round((total / max) * 100)) : 0;
    const barCls = pct >= 100 ? ' over' : pct >= 80 ? ' warn' : '';
    updateContextBadge(entries.length);

    let html = '<div class="context-drawer-head">' +
      '<strong style="font-size:13px">会话上下文</strong>' +
      '<span class="badge accent">' + entries.length + ' 项资料</span>' +
      (max > 0
        ? '<div class="context-budget-bar"><div class="context-budget-fill' + barCls + '" style="width:' + pct + '%"></div></div>' +
          '<span class="status-text" style="font-family:var(--mono)">' + total + ' / ' + max + ' 字符</span>'
        : '') +
      '<button class="btn ghost small" id="ctxCloseBtn" style="margin-left:auto">关闭</button>' +
    '</div>';

    if (entries.length > 0) {
      html += '<div class="context-entry-list">' + entries.map((e, idx) =>
        '<div class="context-entry">' +
          '<span class="ce-key">' + ctx.utils.esc(e.key) + '</span>' +
          '<span class="ce-title" title="' + ctx.utils.esc(e.title || '') + '">' + ctx.utils.esc(e.title || '') + '</span>' +
          '<span class="ce-chars">' + (e.contentChars || 0) + ' 字符</span>' +
          '<button class="btn small ghost" data-ctx-view="' + idx + '">查看</button>' +
          '<button class="btn small ghost" style="color:var(--red)" data-ctx-del="' + ctx.utils.esc(e.key) + '">移除</button>' +
        '</div>'
      ).join('') + '</div>';
    } else {
      html += '<div class="status-text">当前会话暂未注入上下文资料。</div>';
    }

    html += '<div class="context-form">' +
      '<input id="ctxKeyInput" class="input" type="text" placeholder="标识 key（如 spec-doc）">' +
      '<input id="ctxTitleInput" class="input" type="text" placeholder="标题（可选）">' +
      '<textarea id="ctxContentInput" class="input" placeholder="输入资料内容（将在下一轮对话生效）..."></textarea>' +
      '<div style="grid-column:1/-1;display:flex;justify-content:flex-end;gap:8px">' +
        '<button class="btn primary small" id="ctxInjectBtn">注入上下文</button>' +
      '</div>' +
    '</div>';

    contextDrawerEl.innerHTML = html;

    document.getElementById('ctxCloseBtn').addEventListener('click', () => toggleContextDrawer(false));
    contextDrawerEl.querySelectorAll('[data-ctx-view]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const item = entries[Number(btn.dataset.ctxView)];
        if (item) ctx.utils.showTextModal('Context: ' + item.key + (item.title ? ' — ' + item.title : ''), item.content || '');
      });
    });
    contextDrawerEl.querySelectorAll('[data-ctx-del]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const key = btn.dataset.ctxDel;
        try {
          await ctx.api.deleteThreadContext(sid, key, uid());
          ctx.utils.toast('已移除上下文: ' + key, 'success');
          loadContextDrawer();
        } catch (e) {
          ctx.utils.toast('移除失败: ' + e.message, 'error');
        }
      });
    });
    document.getElementById('ctxInjectBtn').addEventListener('click', async () => {
      const key = (document.getElementById('ctxKeyInput').value || '').trim();
      const title = (document.getElementById('ctxTitleInput').value || '').trim();
      const content = document.getElementById('ctxContentInput').value || '';
      if (!key || !content.trim()) {
        ctx.utils.toast('请填写标识 key 与资料内容', 'warn');
        return;
      }
      try {
        const res = await ctx.api.injectThreadContext(sid, { key, title, content }, uid());
        ctx.utils.toast('已注入 ' + res.key + '（下一轮生效）', 'success');
        loadContextDrawer();
      } catch (e) {
        if (e && e.status === 413) {
          const d = e.data || {};
          ctx.utils.toast('超出上下文字符预算（' + (d.totalChars || '?') + ' / ' + (d.maxTotalChars || '?') + ' 字符）', 'error');
        } else {
          ctx.utils.toast('注入失败: ' + e.message, 'error');
        }
      }
    });
  }

  function hideBrowserBar() {
    browserVisible = false;
    browserState = null;
    if (browserBarEl) {
      browserBarEl.classList.remove('visible', 'human');
      browserBarEl.innerHTML = '';
    }
  }

  async function probeBrowserState() {
    const sid = currentSid();
    if (!sid) {
      ctx.utils.toast('请先选择一个会话', 'warn');
      return;
    }
    try {
      const data = await ctx.api.getBrowserState(sid, uid());
      browserState = data;
      browserVisible = true;
      renderBrowserBar(sid, data);
    } catch (e) {
      hideBrowserBar();
      if (e && e.status === 404) {
        ctx.utils.toast('当前服务未启用浏览器接管或会话尚未落库', 'info');
      } else {
        ctx.utils.toast('获取浏览器状态失败: ' + e.message, 'error');
      }
    }
  }

  function renderBrowserBar(sid, data) {
    if (!browserBarEl || !browserVisible) return;
    const ctrl = (data && data.control) || {};
    const sb = (data && data.sandbox) || {};
    const isHuman = ctrl.owner === 'human';
    browserBarEl.classList.add('visible');
    browserBarEl.classList.toggle('human', isHuman);

    const ownerBadge = isHuman
      ? '<span class="badge yellow">人工接管中</span>'
      : '<span class="badge green">Agent 控制中</span>';
    const metaParts = [];
    if (sb.sandbox_id) metaParts.push('sandbox=' + sb.sandbox_id);
    if (ctrl.reason) metaParts.push(ctrl.reason);
    if (ctrl.expires_in_seconds != null) metaParts.push('ttl=' + ctrl.expires_in_seconds + 's');

    browserBarEl.innerHTML =
      '<span class="bb-status">' + icon('globe', 15) + ' 浏览器 ' + ownerBadge + '</span>' +
      (metaParts.length ? '<span class="bb-meta">' + ctx.utils.esc(metaParts.join(' · ')) + '</span>' : '') +
      '<div class="bb-actions">' +
        (!isHuman
          ? '<button class="btn small primary" id="bbTakeoverBtn">接管控制权</button>'
          : '<button class="btn small primary" id="bbReleaseBtn">交还并继续</button>') +
        '<button class="btn small ghost" id="bbRefreshBtn">' + icon('refresh', 13) + '</button>' +
        '<button class="btn small ghost" id="bbCloseBtn">隐藏</button>' +
      '</div>';

    const takeoverBtn = document.getElementById('bbTakeoverBtn');
    if (takeoverBtn) {
      takeoverBtn.addEventListener('click', async () => {
        try {
          await ctx.api.takeoverBrowser(sid, 'manual debug takeover', uid());
          ctx.utils.toast('已接管浏览器控制权', 'success');
          probeBrowserState();
        } catch (e) {
          ctx.utils.toast('接管失败: ' + e.message, 'error');
        }
      });
    }
    const releaseBtn = document.getElementById('bbReleaseBtn');
    if (releaseBtn) {
      releaseBtn.addEventListener('click', () => {
        onBrowserRelease(sid, () => probeBrowserState());
      });
    }
    const refreshBtn = document.getElementById('bbRefreshBtn');
    if (refreshBtn) refreshBtn.addEventListener('click', () => probeBrowserState());
    const closeBtn = document.getElementById('bbCloseBtn');
    if (closeBtn) closeBtn.addEventListener('click', () => hideBrowserBar());
  }

  return {
    mount,
    renderList,
    setContextFromHistory,
    toggleContextDrawer,
    probeBrowserState,
    hideBrowserBar
  };
}
