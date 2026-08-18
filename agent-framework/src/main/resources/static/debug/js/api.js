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
  getThreads: () => get('/debug/threads'),
  getThreadHistory: (sessionId) => get('/debug/threads/' + encodeURIComponent(sessionId) + '/history'),
  getLlmCalls: (sessionId) => get('/debug/threads/' + encodeURIComponent(sessionId) + '/llm-calls'),

  // 长连接 SSE（F）：订阅会话事件总线
  subscribeSession: (sessionId, { onEvent, onError } = {}) => {
    const path = '/debug/threads/' + encodeURIComponent(sessionId) + '/events';
    let aborted = false;
    const controller = new AbortController();
    let readyResolve;
    const readyPromise = new Promise(r => { readyResolve = r; });

    const connect = async () => {
      let backoff = 1000;
      const read = async () => {
        if (aborted || controller.signal.aborted) return;
        let resp;
        try {
          resp = await fetch(BASE + path, { signal: controller.signal });
          // fetch 返回 = HTTP 响应头已收到 = SSE 连接已建立
          readyResolve();
        } catch (e) {
          if (aborted || e.name === 'AbortError') return;
          readyResolve(); // 即使出错也 resolve，避免死等
          await retry();
          return;
        }
        if (!resp.ok || !resp.body) { readyResolve(); await retry(); return; }
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            if (aborted || controller.signal.aborted) break;
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
          if (!aborted && !controller.signal.aborted) await retry();
        } catch (e) {
          if (!aborted && e.name !== 'AbortError') await retry();
        }
      };

      const retry = async () => {
        if (aborted) return;
        if (onError) onError(new Error('订阅断开，准备重连'));
        await new Promise(r => setTimeout(r, backoff));
        backoff = Math.min(backoff * 2, 15000);
        read();
      };

      read();
    };

    connect();

    return {
      close() {
        aborted = true;
        controller.abort();
      },
      /** 等待 SSE 连接建立（fetch 返回 = 连接就绪） */
      ready: readyPromise
    };
  },

  // 长连接模型触发（fire-and-forget）
  triggerSessionChat: (sessionId, message, userId) =>
    post('/debug/threads/' + encodeURIComponent(sessionId) + '/chat', {
      message, userId: userId || 'debug-user'
    }),

  // HITL 确认（独立端点，见 hitl-permission-plan.md 6.3）
  confirmToolCall: (sessionId, results) =>
    post('/threads/' + encodeURIComponent(sessionId) + '/confirm', { results }),

  // HITL 确认后事件流（SSE；长连接场景事件同时经 SessionEventBus 扇出到原订阅）
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

  // MCP 资源（UI 渲染）
  readMcpResource: (server, uri) => post('/mcp/resources/read', { server, uri }),

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