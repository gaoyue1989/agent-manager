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
