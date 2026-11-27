import { icon } from '../js/icons.js';

export function createChatComposer({ ctx, onSend, onStop }) {
  let inputEl = null;
  let sendBtn = null;
  let stopBtn = null;
  let uploadBtn = null;
  let fileInput = null;
  let uploadPreview = null;
  let uploadFiles = null;
  let mentionDropdown = null;

  let mentionSkills = [];
  let mentionActiveIdx = -1;
  let mentionTriggerPos = -1;
  let mentionVisible = false;
  let pendingFiles = [];

  function mount(root) {
    inputEl = root.querySelector('#chatInput');
    sendBtn = root.querySelector('#sendBtn');
    stopBtn = root.querySelector('#stopBtn');
    uploadBtn = root.querySelector('#uploadBtn');
    fileInput = root.querySelector('#fileInput');
    uploadPreview = root.querySelector('#uploadPreview');
    uploadFiles = root.querySelector('#uploadFiles');
    mentionDropdown = root.querySelector('#skillMentionDropdown');

    if (uploadBtn) uploadBtn.innerHTML = icon('paperclip', 17);
    if (sendBtn) sendBtn.addEventListener('click', () => onSend());
    if (stopBtn) stopBtn.addEventListener('click', () => onStop());

    if (uploadBtn && fileInput) {
      uploadBtn.addEventListener('click', () => fileInput.click());
      fileInput.addEventListener('change', handleFileSelect);
    }

    if (inputEl) {
      inputEl.addEventListener('keydown', (e) => {
        if (mentionVisible) {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            const items = mentionDropdown.querySelectorAll('.skill-mention-item');
            if (items.length > 0) {
              mentionActiveIdx = (mentionActiveIdx + 1) % items.length;
              items.forEach((el, i) => el.classList.toggle('active', i === mentionActiveIdx));
            }
            return;
          }
          if (e.key === 'ArrowUp') {
            e.preventDefault();
            const items = mentionDropdown.querySelectorAll('.skill-mention-item');
            if (items.length > 0) {
              mentionActiveIdx = (mentionActiveIdx - 1 + items.length) % items.length;
              items.forEach((el, i) => el.classList.toggle('active', i === mentionActiveIdx));
            }
            return;
          }
          if (e.key === 'Enter' || e.key === 'Tab') {
            const activeItem = mentionDropdown.querySelector('.skill-mention-item.active');
            if (activeItem) {
              e.preventDefault();
              activeItem.click();
              return;
            }
          }
          if (e.key === 'Escape') {
            e.preventDefault();
            hideMentionDropdown();
            return;
          }
        }
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          onSend();
        }
      });

      inputEl.addEventListener('input', () => {
        autoGrow();
        handleMentionInput();
      });

      inputEl.addEventListener('blur', () => {
        setTimeout(() => hideMentionDropdown(), 180);
      });
    }

    loadAvailableSkills();
  }

  function autoGrow() {
    if (!inputEl) return;
    inputEl.style.height = 'auto';
    inputEl.style.height = Math.min(inputEl.scrollHeight, 150) + 'px';
  }

  async function loadAvailableSkills() {
    try {
      const uid = ctx.state.getState('ui.userId') || 'debug-user';
      const data = await ctx.api.getAvailableSkills(uid);
      mentionSkills = (data && data.skills) || [];
    } catch (e) {
      mentionSkills = [];
    }
  }

  function handleMentionInput() {
    if (!inputEl || !mentionDropdown) return;
    const val = inputEl.value;
    const cursorPos = inputEl.selectionStart;
    const beforeCursor = val.substring(0, cursorPos);
    const slashIdx = beforeCursor.lastIndexOf('/');
    if (slashIdx === -1) { hideMentionDropdown(); return; }
    if (slashIdx > 0 && /[\w:.\/]/.test(beforeCursor[slashIdx - 1])) {
      hideMentionDropdown();
      return;
    }
    const query = beforeCursor.substring(slashIdx + 1);
    if (/\s/.test(query)) { hideMentionDropdown(); return; }
    mentionTriggerPos = slashIdx;
    renderMentionDropdown(query);
  }

  function renderMentionDropdown(query) {
    if (!mentionDropdown) return;
    const q = query.toLowerCase();
    const filtered = mentionSkills.filter((s) => {
      if (!q) return true;
      return (s.name && s.name.toLowerCase().includes(q)) ||
        (s.alias && s.alias.toLowerCase().includes(q)) ||
        (s.description && s.description.toLowerCase().includes(q));
    });

    if (filtered.length === 0) {
      mentionDropdown.innerHTML = '<div class="skill-mention-empty">No matching skills</div>';
      mentionDropdown.classList.add('visible');
      mentionVisible = true;
      mentionActiveIdx = -1;
      return;
    }

    mentionActiveIdx = 0;
    mentionDropdown.innerHTML = filtered.map((s, idx) =>
      '<div class="skill-mention-item' + (idx === 0 ? ' active' : '') + '" data-idx="' + idx + '">' +
        '<span class="sm-icon">' + icon('skills', 15) + '</span>' +
        '<span class="sm-name"><span class="sm-at">/</span>' + ctx.utils.esc(s.name) + '</span>' +
        (s.alias ? '<span class="sm-alias">(' + ctx.utils.esc(s.alias) + ')</span>' : '') +
        '<span class="sm-desc">' + ctx.utils.esc(s.description || '') + '</span>' +
      '</div>'
    ).join('');

    mentionDropdown.querySelectorAll('.skill-mention-item').forEach((el, idx) => {
      el.addEventListener('mousedown', (e) => {
        e.preventDefault();
        selectMentionSkill(filtered[idx]);
      });
      el.addEventListener('click', () => selectMentionSkill(filtered[idx]));
      el.addEventListener('mouseenter', () => {
        mentionActiveIdx = idx;
        mentionDropdown.querySelectorAll('.skill-mention-item').forEach((item, i) =>
          item.classList.toggle('active', i === idx));
      });
    });

    mentionDropdown.classList.add('visible');
    mentionVisible = true;
  }

  function selectMentionSkill(skill) {
    if (!inputEl || mentionTriggerPos < 0) return;
    const val = inputEl.value;
    const cursorPos = inputEl.selectionStart;
    const before = val.substring(0, mentionTriggerPos);
    const after = val.substring(cursorPos);
    const inserted = '/' + skill.name + ' ';
    inputEl.value = before + inserted + after;
    const newPos = before.length + inserted.length;
    inputEl.setSelectionRange(newPos, newPos);
    inputEl.focus();
    hideMentionDropdown();
    autoGrow();
  }

  function hideMentionDropdown() {
    if (mentionDropdown) mentionDropdown.classList.remove('visible');
    mentionVisible = false;
    mentionActiveIdx = -1;
    mentionTriggerPos = -1;
  }

  function handleFileSelect(e) {
    const files = Array.from(e.target.files || []);
    if (files.length === 0) return;
    for (const file of files) {
      pendingFiles.push({ file, fileId: null, status: 'pending' });
    }
    renderUploadPreview();
    if (fileInput) fileInput.value = '';
  }

  function renderUploadPreview() {
    if (!uploadPreview || !uploadFiles) return;
    if (pendingFiles.length === 0) {
      uploadPreview.style.display = 'none';
      return;
    }
    uploadPreview.style.display = 'block';
    uploadFiles.innerHTML = pendingFiles.map((item, idx) =>
      '<div class="upload-file-item">' +
        '<span class="file-icon">' + icon('paperclip', 13) + '</span>' +
        '<span class="file-name" title="' + ctx.utils.esc(item.file.name) + '">' + ctx.utils.esc(item.file.name) + '</span>' +
        '<span class="file-size">' + formatFileSize(item.file.size) + '</span>' +
        '<button class="remove-file" data-idx="' + idx + '" title="Remove">✕</button>' +
      '</div>'
    ).join('');
    uploadFiles.querySelectorAll('.remove-file').forEach((btn) => {
      btn.addEventListener('click', (e) => {
        pendingFiles.splice(parseInt(e.currentTarget.dataset.idx, 10), 1);
        renderUploadPreview();
      });
    });
  }

  function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  async function uploadPendingFiles(sid) {
    if (pendingFiles.length === 0) return [];
    const uid = ctx.state.getState('ui.userId') || 'debug-user';
    const fileIds = [];
    for (const item of pendingFiles) {
      try {
        item.status = 'uploading';
        const res = await ctx.api.uploadFile(item.file, uid, sid);
        item.fileId = res.fileId;
        item.status = 'done';
        fileIds.push(res.fileId);
      } catch (e) {
        item.status = 'error';
        ctx.utils.toast('Upload failed: ' + item.file.name + ' - ' + e.message, 'error');
        throw e;
      }
    }
    pendingFiles = [];
    renderUploadPreview();
    return fileIds;
  }

  return {
    mount,
    loadAvailableSkills,
    uploadPendingFiles,
    getValue: () => (inputEl ? inputEl.value.trim() : ''),
    clearValue: () => { if (inputEl) { inputEl.value = ''; autoGrow(); } },
    hasPendingFiles: () => pendingFiles.length > 0,
    clearPendingFiles: () => { pendingFiles = []; renderUploadPreview(); },
    focus: () => { if (inputEl) inputEl.focus(); },
    getSendBtn: () => sendBtn,
    getStopBtn: () => stopBtn
  };
}
