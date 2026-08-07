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
  getLogs: (level = 'all', limit = 100) => get('/debug/logs?level=' + level + '&limit=' + limit),
  getThreads: () => get('/debug/threads'),
  getThreadHistory: (sessionId) => get('/debug/threads/' + encodeURIComponent(sessionId) + '/history'),
  getLlmCalls: (sessionId) => get('/debug/threads/' + encodeURIComponent(sessionId) + '/llm-calls'),

  // MCP 资源（UI 渲染）
  readMcpResource: (server, uri) => post('/mcp/resources/read', { server, uri }),

  // A2A 同步调用（支持顶层参数 + 兼容旧格式 metadata）
  sendA2A: (text, { userId, sessionId, metadata } = {}) => {
    const params = {
      message: { role: 'user', parts: [{ text }] }
    };
    // 优先使用标准 A2A 顶层参数
    if (userId) params.userId = userId;
    if (sessionId) params.sessionId = sessionId;
    // 兼容旧格式 metadata
    if (metadata) params.metadata = metadata;

    return post('/', {
      jsonrpc: '2.0',
      method: 'message/send',
      id: 'debug-' + Date.now(),
      params
    });
  }
};
