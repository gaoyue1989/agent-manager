/* API 客户端：调用 agent-framework（页面与后端经 proxy.py 同源，无需 CORS） */
const BASE = '';

async function get(path) {
  const resp = await fetch(BASE + path);
  if (!resp.ok) throw new Error('HTTP ' + resp.status + ' ' + path);
  return resp.json();
}

async function post(path, body) {
  const resp = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {})
  });
  if (!resp.ok) throw new Error('HTTP ' + resp.status + ' ' + path);
  return resp.json();
}

async function postRaw(path, body) {
  const resp = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {})
  });
  return resp;
}

export const api = {
  BASE,

  getInfo: () => get('/'),
  getAgentCard: () => get('/.well-known/agent-card.json'),
  getSystemPrompt: () => get('/system-prompt'),
  getTools: () => get('/tools?includeInternal=true'),
  getSkills: () => get('/skills'),
  getMcpServers: () => get('/mcp'),

  // 会话（O7：去掉 /debug 前缀）
  getThreads: () => get('/threads'),
  getThreadHistory: (sessionId) =>
    get('/threads/' + encodeURIComponent(sessionId) + '/history'),

  // POST /chat 单次流（SSE 直吐，替代旧 fire-and-forget + 长连接订阅）
  triggerSessionChat: (sessionId, message, userId, { onEvent, onError } = {}) => {
    const path = '/threads/' + encodeURIComponent(sessionId) + '/chat';
    const controller = new AbortController();
    const connect = async () => {
      let resp;
      try {
        resp = await fetch(BASE + path, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message, userId: userId || 'debug-user' }),
          signal: controller.signal
        });
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
            if (!dataStr || dataStr === '{}') continue;
            try { if (onEvent) onEvent(JSON.parse(dataStr)); }
            catch (e) { /* 忽略解析错误 */ }
          }
        }
      } catch (e) {
        if (!controller.signal.aborted && onError) onError(e);
      }
    };
    connect();
    return { close() { controller.abort(); }, ready: Promise.resolve() };
  },

  // HITL 确认（提交审批人工确认）
  confirmStream: (sessionId, results, { onEvent, onError } = {}) => {
    const path = '/threads/' + encodeURIComponent(sessionId) + '/confirm-stream';
    const controller = new AbortController();
    const connect = async () => {
      let resp;
      try {
        resp = await fetch(BASE + path, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
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
            if (!dataStr || dataStr === '{}') continue;
            try { if (onEvent) onEvent(JSON.parse(dataStr)); }
            catch (e) { /* 忽略解析错误 */ }
          }
        }
      } catch (e) {
        if (!controller.signal.aborted && onError) onError(e);
      }
    };
    connect();
    return { close() { controller.abort(); }, ready: Promise.resolve() };
  },

  // MCP Apps：资源拉取 / 卡片工具调用 / 静默更新模型上下文
  readMcpUiResource: (server, uri) =>
    get('/mcp/' + encodeURIComponent(server) + '/resources/ui?uri=' + encodeURIComponent(uri)),
  callMcpTool: (server, tool, args, confirmed) =>
    post('/mcp/' + encodeURIComponent(server) + '/tools/' + encodeURIComponent(tool),
      { arguments: args || {}, confirmed: !!confirmed }),
  updateUiContext: (sessionId, content, structuredContent) =>
    post('/mcp/ui-context', { sessionId, content, structuredContent })
};
