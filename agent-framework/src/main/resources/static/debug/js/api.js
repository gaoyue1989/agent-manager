const BASE = window.location.pathname.replace(/\/(?:debug|monitor)\/?.*$/, '') || '';

async function httpError(resp, path) {
  let msg = 'HTTP ' + resp.status + ' ' + path;
  let data = null;
  try {
    data = await resp.json();
    if (data && data.message) msg = data.message;
    else if (data && data.error) msg = data.note ? (data.error + ' (' + data.note + ')') : data.error;
  } catch (e) {}
  const err = new Error(msg);
  err.status = resp.status;
  err.code = data && data.error ? data.error : null;
  err.data = data;
  return err;
}

async function get(path, headers) {
  const resp = await fetch(BASE + path, { headers: headers || {} });
  if (!resp.ok) throw await httpError(resp, path);
  return resp.json();
}

async function post(path, body, headers) {
  const resp = await fetch(BASE + path, {
    method: 'POST',
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
    body: JSON.stringify(body)
  });
  if (!resp.ok) throw await httpError(resp, path);
  return resp.json();
}

async function del(path, headers) {
  const resp = await fetch(BASE + path, { method: 'DELETE', headers: headers || {} });
  if (!resp.ok) throw await httpError(resp, path);
  return resp.json();
}

async function put(path, body, headers) {
  const resp = await fetch(BASE + path, {
    method: 'PUT',
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
    body: JSON.stringify(body)
  });
  if (!resp.ok) throw await httpError(resp, path);
  return resp.json();
}

async function patch(path, body, headers) {
  const resp = await fetch(BASE + path, {
    method: 'PATCH',
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
    body: JSON.stringify(body)
  });
  if (!resp.ok) throw await httpError(resp, path);
  return resp.json();
}

function userHeader(userId) {
  return userId ? { 'X-User-Id': userId } : {};
}

function consumeSsePost(path, body, headers, { onEvent, onWaiting, onError, onEnd } = {}) {
  const controller = new AbortController();
  let lastEventId = 0;
  const connect = async () => {
    let resp;
    try {
      resp = await fetch(BASE + path, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: controller.signal
      });
    } catch (e) {
      if (controller.signal.aborted) return;
      if (onError) onError(e);
      return;
    }
    if (!resp.ok || !resp.body) {
      let msg = 'HTTP ' + resp.status;
      try {
        const j = await resp.json();
        if (j && (j.message || j.error)) msg = j.message || j.error;
      } catch (e) {}
      if (onError) onError(new Error(msg));
      return;
    }
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let active = true;
    try {
      while (active) {
        const { done, value } = await reader.read();
        if (done) break;
        if (controller.signal.aborted) { active = false; break; }
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (line.startsWith('id:')) {
            const idVal = parseInt(line.slice(3).trim(), 10);
            if (!isNaN(idVal) && idVal > lastEventId) lastEventId = idVal;
            continue;
          }
          if (!line.startsWith('data:')) continue;
          const dataStr = line.slice(5).trim();
          if (!dataStr) continue;
          let evt;
          try { evt = JSON.parse(dataStr); } catch (e) { continue; }
          if (evt.type === 'done') { if (onEnd) onEnd(evt); continue; }
          if (evt.type === 'error') { if (onError) onError(new Error(evt.error)); continue; }
          if (evt.type === 'waiting') { if (onWaiting) onWaiting(evt); continue; }
          if (onEvent) onEvent(evt);
        }
      }
    } catch (e) {
      if (!controller.signal.aborted && onError) onError(e);
    } finally {
      if (active && onEnd) onEnd();
    }
  };
  connect();
  return {
    close: () => controller.abort(),
    get lastEventId() { return lastEventId; }
  };
}

export const api = {
  BASE,

  getInfo: () => get('/'),
  getHealth: () => get('/health'),
  getAgentCard: () => get('/.well-known/agent-card.json'),
  getSystemPrompt: () => get('/system-prompt'),

  getTools: (includeInternal = false) => get('/tools?includeInternal=' + includeInternal),
  getSkills: () => get('/skills'),
  getSkillsManage: () => get('/skills/manage'),
  getAvailableSkills: (userId) => get('/skills/available' + (userId ? '?userId=' + encodeURIComponent(userId) : '')),
  parseSkillRefs: (message, userId) => get('/skills/parse-refs?message=' + encodeURIComponent(message)
    + (userId ? '&userId=' + encodeURIComponent(userId) : '')),
  uploadSkill: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const resp = await fetch(BASE + '/skills/upload', { method: 'POST', body: formData });
    if (!resp.ok) throw await httpError(resp, '/skills/upload');
    return resp.json();
  },
  deleteSkill: (name) => del('/skills/' + encodeURIComponent(name)),
  toggleSkill: (name) => put('/skills/' + encodeURIComponent(name) + '/toggle', {}),
  getSkillContent: (name) => get('/skills/' + encodeURIComponent(name) + '/content'),
  updateSkillContent: (name, content) => put('/skills/' + encodeURIComponent(name) + '/content', { content }),
  downloadSkillZip: async (name) => {
    const resp = await fetch(BASE + '/skills/' + encodeURIComponent(name) + '/download');
    if (!resp.ok) throw await httpError(resp, '/skills/' + name + '/download');
    return resp.blob();
  },
  getMcpServers: () => get('/mcp'),
  getMetadata: (includeDetails = false) => get('/metadata?includeDetails=' + includeDetails),

  getModels: (all = false) => get('/models' + (all ? '?all=true' : '')),
  getModel: (id) => get('/models/' + encodeURIComponent(id)),
  createModel: (body) => post('/models', body),
  updateModel: (id, body) => patch('/models/' + encodeURIComponent(id), body),
  deleteModel: (id) => del('/models/' + encodeURIComponent(id)),
  setDefaultModel: (id) => put('/models/' + encodeURIComponent(id) + '/default', {}),
  setFallbackModel: (id) => put('/models/' + encodeURIComponent(id) + '/fallback', {}),
  testModel: (id) => post('/models/' + encodeURIComponent(id) + '/test', {}),

  getUserSkillUsers: () => get('/debug/user-skills'),
  getUserSkills: (userId) => get('/skills/users', userHeader(userId)),
  getUserSkill: (userId, name, file) =>
    get('/skills/users/' + encodeURIComponent(name) + (file ? '?file=' + encodeURIComponent(file) : ''), userHeader(userId)),
  putUserSkill: (userId, name, content) =>
    put('/skills/users/' + encodeURIComponent(name), { content }, userHeader(userId)),
  uploadUserSkillZip: async (userId, file) => {
    const formData = new FormData();
    formData.append('file', file);
    const resp = await fetch(BASE + '/skills/users/upload', {
      method: 'POST',
      headers: userHeader(userId),
      body: formData
    });
    if (!resp.ok) throw await httpError(resp, '/skills/users/upload');
    return resp.json();
  },
  downloadUserSkillZip: async (userId, name) => {
    const resp = await fetch(BASE + '/skills/users/' + encodeURIComponent(name) + '/download', { headers: userHeader(userId) });
    if (!resp.ok) throw await httpError(resp, '/skills/users/' + name + '/download');
    return resp.blob();
  },
  deleteUserSkill: (userId, name) => del('/skills/users/' + encodeURIComponent(name), userHeader(userId)),
  toggleUserSkill: (userId, name) => put('/skills/users/' + encodeURIComponent(name) + '/toggle', {}, userHeader(userId)),
  syncUserSkillFromPackage: (userId, name) =>
    post('/skills/users/' + encodeURIComponent(name) + '/sync-from-package', {}, userHeader(userId)),

  getEnvConfig: () => get('/debug/config/env'),
  getOafConfig: () => get('/debug/config/oaf'),
  getReloadStatus: () => get('/admin/reload'),
  triggerReload: (scope = 'auto', server) => {
    let url = '/admin/reload?scope=' + encodeURIComponent(scope);
    if (server) url += '&server=' + encodeURIComponent(server);
    return post(url, {});
  },
  getDatabaseStatus: () => get('/debug/database/status'),
  getMemory: () => get('/debug/memory'),
  getWorkspace: () => get('/debug/workspace'),
  getSandbox: () => get('/debug/sandbox'),
  getSandboxBindings: () => get('/debug/sandbox-bindings'),
  updateSandboxEnv: (userId, sessionId, env) => put('/sandbox/env', { userId, sessionId, env }),
  getLogs: (level = 'all', limit = 100) => get('/debug/logs?level=' + level + '&limit=' + limit),

  getThreads: (userId) => get('/threads' + (userId ? '?userId=' + encodeURIComponent(userId) : ''), userHeader(userId)),
  patchThread: (sessionId, body, userId) => patch('/threads/' + encodeURIComponent(sessionId), body, userHeader(userId)),
  deleteThread: (sessionId, userId) => del('/threads/' + encodeURIComponent(sessionId), userHeader(userId)),
  getThreadHistory: (sessionId, userId, opts = {}) => {
    const qs = opts && opts.limit ? '?limit=' + encodeURIComponent(opts.limit) : '';
    return get('/threads/' + encodeURIComponent(sessionId) + '/history' + qs, userHeader(userId));
  },
  getLlmCalls: (sessionId, userId) => get('/threads/' + encodeURIComponent(sessionId) + '/llm-calls', userHeader(userId)),
  stopThread: (sessionId, userId) => post('/threads/' + encodeURIComponent(sessionId) + '/stop', {}, userHeader(userId)),

  getThreadContext: (sessionId, userId, includeContent = true) =>
    get('/threads/' + encodeURIComponent(sessionId) + '/context?includeContent=' + (includeContent ? 'true' : 'false'), userHeader(userId)),
  injectThreadContext: (sessionId, body, userId) =>
    post('/threads/' + encodeURIComponent(sessionId) + '/context', body, userHeader(userId)),
  deleteThreadContext: (sessionId, key, userId) =>
    del('/threads/' + encodeURIComponent(sessionId) + '/context/' + encodeURIComponent(key), userHeader(userId)),

  getBrowserState: (sessionId, userId) => get('/threads/' + encodeURIComponent(sessionId) + '/browser', userHeader(userId)),
  takeoverBrowser: (sessionId, reason, userId) =>
    post('/threads/' + encodeURIComponent(sessionId) + '/browser/takeover', reason ? { reason } : {}, userHeader(userId)),
  releaseBrowserStream: (sessionId, userId, handlers = {}) =>
    consumeSsePost('/threads/' + encodeURIComponent(sessionId) + '/browser/release', {}, userHeader(userId), handlers),

  getMonitorOverview: () => get('/debug/monitor/overview'),
  getMonitorTools: (limit = 50) => get('/debug/monitor/tools?limit=' + limit),
  getMonitorSessions: (params = {}) => {
    const qs = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
      .join('&');
    return get('/debug/monitor/sessions' + (qs ? '?' + qs : ''));
  },
  getMonitorMessages: (params = {}) => {
    const qs = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
      .join('&');
    return get('/debug/monitor/messages' + (qs ? '?' + qs : ''));
  },

  sendChat: (sessionId, message, userId, fileIds, { model, onEvent, onWaiting, onError, onEnd } = {}) => {
    const body = { message, userId: userId || 'debug-user', sessionId };
    if (model) body.model = model;
    if (fileIds && fileIds.length > 0) body.fileIds = fileIds;
    return consumeSsePost('/threads/chat', body, {}, { onEvent, onWaiting, onError, onEnd });
  },

  subscribe: (sessionId, { afterSeq, replyId, userId, onEvent, onError, onEnd } = {}) => {
    const path = '/threads/' + encodeURIComponent(sessionId) + '/subscribe';
    const controller = new AbortController();
    let lastEventId = afterSeq || 0;
    const connect = async () => {
      let url = BASE + path + '?afterSeq=' + lastEventId;
      if (replyId) url += '&replyId=' + encodeURIComponent(replyId);
      let resp;
      try {
        resp = await fetch(url, { signal: controller.signal, headers: userHeader(userId) });
      } catch (e) {
        if (controller.signal.aborted) return;
        if (onError) onError(e);
        return;
      }
      if (!resp.ok || !resp.body) {
        if (onError) onError(new Error('HTTP ' + resp.status));
        return;
      }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let terminated = false;
      let closedByServer = false;
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) { closedByServer = true; break; }
          if (controller.signal.aborted) break;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';
          for (const line of lines) {
            if (line.startsWith('id:')) {
              const idVal = parseInt(line.slice(3).trim(), 10);
              if (!isNaN(idVal) && idVal > lastEventId) lastEventId = idVal;
              continue;
            }
            if (!line.startsWith('data:')) continue;
            const dataStr = line.slice(5).trim();
            if (!dataStr) continue;
            let evt;
            try { evt = JSON.parse(dataStr); } catch (e) { continue; }
            if (evt.type === 'done') { terminated = true; if (onEnd) onEnd(evt); return; }
            if (evt.type === 'error') { terminated = true; if (onError) onError(new Error(evt.error)); return; }
            if (onEvent) onEvent(evt);
          }
        }
      } catch (e) {
        if (!controller.signal.aborted && onError) onError(e);
      } finally {
        if (closedByServer && !terminated && onEnd) onEnd();
      }
    };
    connect();
    return {
      close: () => controller.abort(),
      get lastEventId() { return lastEventId; }
    };
  },

  getStatus: (sessionId, userId) => get('/threads/' + encodeURIComponent(sessionId) + '/status', userHeader(userId)),

  confirmToolCall: (sessionId, results, userId) =>
    post('/threads/' + encodeURIComponent(sessionId) + '/confirm', { results }, userHeader(userId)),

  confirmStream: (sessionId, results, { onEvent, onError, userId } = {}) => {
    const path = '/threads/' + encodeURIComponent(sessionId) + '/confirm-stream';
    const controller = new AbortController();
    const connect = async () => {
      let resp;
      try {
        resp = await fetch(BASE + path, {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, userHeader(userId)),
          body: JSON.stringify({ results }),
          signal: controller.signal
        });
      } catch (e) {
        if (controller.signal.aborted) return;
        if (onError) onError(e);
        return;
      }
      if (!resp.ok || !resp.body) { if (onError) onError(new Error('HTTP ' + resp.status)); return; }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (controller.signal.aborted) break;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';
          for (const line of lines) {
            if (!line.startsWith('data:')) continue;
            const dataStr = line.slice(5).trim();
            if (!dataStr) continue;
            try {
              const parsed = JSON.parse(dataStr);
              if (onEvent) onEvent(parsed);
            } catch (e) {}
          }
        }
      } catch (e) {
        if (!controller.signal.aborted && onError) onError(e);
      }
    };
    connect();
    return {
      close() { controller.abort(); },
      ready: Promise.resolve()
    };
  },

  readMcpUiResource: (server, uri) =>
    get('/mcp/' + encodeURIComponent(server) + '/resources/ui?uri=' + encodeURIComponent(uri)),
  listMcpUiResources: (server) => get('/mcp/' + encodeURIComponent(server) + '/resources'),
  callMcpTool: (server, tool, args, confirmed) =>
    post('/mcp/' + encodeURIComponent(server) + '/tools/' + encodeURIComponent(tool),
      { arguments: args || {}, confirmed: !!confirmed }),
  updateUiContext: (sessionId, content, structuredContent) =>
    post('/mcp/ui-context', { sessionId, content, structuredContent }),

  uploadFile: async (file, userId, sessionId) => {
    const formData = new FormData();
    formData.append('file', file);
    if (userId) formData.append('userId', userId);
    if (sessionId) formData.append('sessionId', sessionId);
    const resp = await fetch(BASE + '/files/upload', { method: 'POST', body: formData });
    if (!resp.ok) throw await httpError(resp, '/files/upload');
    return resp.json();
  },

  sendA2A: async (text, { userId, sessionId, metadata } = {}) => {
    const msgMetadata = { ...(metadata || {}) };
    if (userId) msgMetadata.userId = userId;
    if (sessionId) msgMetadata.sessionId = sessionId;
    const resp = await fetch(BASE + '/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        method: 'message/send',
        id: 'debug-' + Date.now(),
        params: {
          message: { role: 'user', parts: [{ text }], metadata: msgMetadata },
          configuration: { blocking: true }
        }
      })
    });
    const data = await resp.json();
    const result = data.result;
    if (result && result.parts) {
      return result.parts.filter((p) => p.kind === 'text').map((p) => p.text).join('');
    }
    const msg = result && result.result && result.result.message;
    if (msg && msg.parts) {
      return msg.parts.filter((p) => p.kind === 'text').map((p) => p.text).join('');
    }
    throw new Error(data.error ? (data.error.message || 'A2A error') : 'Unexpected A2A response');
  }
};
