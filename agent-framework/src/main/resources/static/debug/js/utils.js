/* ===== 工具函数 ===== */

export function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function formatMarkdown(text) {
  let html = esc(text);
  html = html.replace(/```(\w*)\n([\s\S]*?)\n```/g, (_, lang, code) =>
    '<pre><code>' + esc(code) + '</code></pre>');
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  return html;
}

export function scrollBottom(el) {
  if (el) el.scrollTop = el.scrollHeight;
}

export function formatBytes(bytes) {
  if (bytes == null) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

export function formatTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  return d.toLocaleString('zh-CN', { hour12: false });
}

export function toast(message, type = 'info') {
  const container = document.getElementById('toastContainer');
  if (!container) return;
  const el = document.createElement('div');
  el.className = 'toast ' + type;
  el.textContent = message;
  container.appendChild(el);
  setTimeout(() => el.remove(), 4000);
}

/** 渲染 JSON 详情弹窗 */
export function showJsonModal(title, obj, maxHeight) {
  const modal = window.App.modal;
  modal.open(
    '<h2>' + esc(title) + '</h2>' +
    '<pre style="max-height:' + (maxHeight || '60vh') + ';overflow-y:auto">' +
    esc(JSON.stringify(obj, null, 2)) + '</pre>'
  );
}

export function showTextModal(title, text, maxHeight) {
  const modal = window.App.modal;
  modal.open(
    '<h2>' + esc(title) + '</h2>' +
    '<pre style="max-height:' + (maxHeight || '60vh') + ';overflow-y:auto;white-space:pre-wrap">' +
    esc(text) + '</pre>'
  );
}

export function emptyHtml(text) {
  return '<div class="empty">' + esc(text) + '</div>';
}
