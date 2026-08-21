/* ===== API 客户端：统一封装后端端点 ===== */

const BASE = window.location.pathname.replace(/\/debug\/?.*$/, '') || '';

async function get(path) {
  const resp = await fetch(BASE + path);
  if (!resp.ok) throw new Error('HTTP ' + resp.status + ' ' + path);
  return resp.json();
}

async function post(path, body) {
  const resp = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (!resp.ok) throw new Error('HTTP ' + resp.status + ' ' + path);
  return resp.json();
}

export const api = {
  BASE,

  // 基础信息
  getInfo: () => get('/'),
  getHealth: () => get('/health'),
  getAgentCard: () => get('/.well-known/agent-card.json'),
  getSystemPrompt: () => get('/system-prompt'),

  // 工具 / 技能 / MCP
  getTools: (includeInternal = false) => get('/tools?includeInternal=' + includeInternal),
  getSkills: () => get('/skills'),
  getMcpServers: () => get('/mcp'),
  getMetadata: (includeDetails = false) => get('/metadata?includeDetails=' + includeDetails),

  // 调试数据
  getEnvConfig: () => get('/debug/config/env'),
  getOafConfig: () => get('/debug/config/oaf'),
  getDatabaseStatus: () => get('/debug/database/status'),
  getMemory: () => get('/debug/memory'),
  getWorkspace: () => get('/debug/workspace'),
  getSandbox: () => get('/debug/sandbox'),
  getLogs: (level = 'all', limit = 100) => get('/debug/logs?level=' + level + '&limit=' + limit),
  getThreads: () => get('/threads'),
  getThreadHistory: (sessionId) => get('/threads/' + encodeURIComponent(sessionId) + '/history'),
  getLlmCalls: (sessionId) => get('/threads/' + encodeURIComponent(sessionId) + '/llm-calls'),

  // 单次流对话（无状态架构：POST /threads/{sid}/chat 事件直吐，执行完即关闭）
  // 排队等待期间后端发 waiting 帧；结束发 done 帧；异常发 error 帧
  sendChat: (sessionId, message, userId, { onEvent, onWaiting, onError, onEnd } = {}) => {
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
        let msg = 'HTTP ' + resp.status;
        try { const j = await resp.json(); if (j && j.error) msg = j.error; } catch (e) {}
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
    return { close: () => controller.abort() };
  },

  // HITL 确认（独立端点，见 hitl-permission-plan.md 6.3）  // HITL 确认（独立端点，见 hitl-permission-plan.md 6.3）
  confirmToolCall: (sessionId, results) =>
    post('/threads/' + encodeURIComponent(sessionId) + '/confirm', { results }),

  // HITL 确认后事件流（SSE；恢复为新执行段，后端先 acquire turn 租约，排队/冲突以 error 帧返回）
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
            if (!dataStr) continue;
            try {
              if (onEvent) onEvent(JSON.parse(dataStr));
            } catch (e) { /* 忽略解析错误 */ }
          }
        }
      } catch (e) {
        if (!controller.signal.aborted && onError) onError(e);
      }
    };
    connect();
    return {
      close() { controller.abort(); },
      /** 等待 HTTP 响应头（连接建立） */
      ready: Promise.resolve()
    };
  },

  // MCP Apps（阶段一/二端点，见 mcp-apps-extension-plan.md 4.4）
  readMcpUiResource: (server, uri) =>
    get('/mcp/' + encodeURIComponent(server) + '/resources/ui?uri=' + encodeURIComponent(uri)),
  listMcpUiResources: (server) => get('/mcp/' + encodeURIComponent(server) + '/resources'),
  callMcpTool: (server, tool, args, confirmed) =>
    post('/mcp/' + encodeURIComponent(server) + '/tools/' + encodeURIComponent(tool),
      { arguments: args || {}, confirmed: !!confirmed }),
  updateUiContext: (sessionId, content, structuredContent) =>
    post('/mcp/ui-context', { sessionId, content, structuredContent }),

  // A2A 同步调用（转发 SDK，userId/sessionId 写入 message.metadata）
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
    // 兼容 SDK 返回: result 可能为 Task 或 Message
    const result = data.result;
    if (result && result.parts) {
      // 标准 Message: {kind:"message", role:"agent", parts:[{kind:"text",text}]}
      return result.parts.filter((p) => p.kind === 'text').map((p) => p.text).join('');
    }
    // 兼容旧格式简化 Task: {id, status, result:{message:{parts}}}
    const msg = result && result.result && result.result.message;
    if (msg && msg.parts) {
      return msg.parts.filter((p) => p.kind === 'text').map((p) => p.text).join('');
    }
    throw new Error(data.error ? (data.error.message || 'A2A error') : 'Unexpected A2A response');
  }
};