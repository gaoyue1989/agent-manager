/* ===== MCP Apps Host：iframe 生命周期 + JSON-RPC over postMessage =====
 * 从 agent-framework 的 static/debug/js/mcp-app-host.js 复制适配。
 * 对齐 MCP Apps 规范（Stable 2026-01-26）。依赖 window.App.api / window.App.state。
 */

const MSG_TIMEOUT_MS = 8000;
const HANDSHAKE_TIMEOUT_MS = 10000;

export class McpAppHost {
  constructor({ tcId, server, resourceUri, toolName, callbacks = {} }) {
    this.tcId = tcId;
    this.server = server;
    this.resourceUri = resourceUri;
    this.toolName = toolName;
    this.callbacks = callbacks;

    this.iframe = null;
    this.container = null;
    this.initialized = false;
    this.disabled = false;
    this.pendingMessages = [];
    this.nextId = 1;
    this.pendingRequests = new Map();
    this.onMessage = (e) => this.handleMessage(e);
    this.toolArguments = null;
    this.toolResult = null;
  }

  async mount(container) {
    this.container = container;
    let data;
    try {
      data = await window.App.api.readMcpUiResource(this.server, this.resourceUri);
    } catch (e) {
      throw new Error('资源加载失败: ' + (e.message || e));
    }
    if (this.disabled || !container.isConnected) return;

    const html = injectCspMeta(data.html || '', data.csp || {});
    this.iframe = document.createElement('iframe');
    this.iframe.className = 'mcp-apps-iframe';
    this.iframe.setAttribute('sandbox', 'allow-scripts');
    this.iframe.srcdoc = html;
    container.innerHTML = '';
    container.appendChild(this.iframe);
    window.addEventListener('message', this.onMessage);
    return this.iframe;
  }

  teardown(reason) {
    if (this.disabled || !this.iframe) return;
    this.disabled = true;
    window.removeEventListener('message', this.onMessage);
    this.pendingRequests.forEach((p) => clearTimeout(p.timer));
    this.pendingRequests.clear();
    this.post({
      jsonrpc: '2.0',
      id: 'teardown-' + this.nextId++,
      method: 'ui/resource-teardown',
      params: { reason: reason || 'reply-completed' }
    });
  }

  sendToolInput(args) {
    this.toolArguments = args;
    this.sendAfterHandshake('ui/notifications/tool-input', { arguments: args });
  }

  sendToolResult(result) {
    this.toolResult = result;
    this.sendAfterHandshake('ui/notifications/tool-result', result || {});
  }

  sendToolCancelled(reason) {
    this.sendAfterHandshake('ui/notifications/tool-cancelled', { reason: reason || 'cancelled' });
  }

  sendAfterHandshake(method, params) {
    if (this.disabled || !this.iframe) return;
    if (!this.initialized) {
      this.pendingMessages.push({ method, params });
      return;
    }
    this.post({ jsonrpc: '2.0', method, params });
  }

  post(msg) {
    if (this.iframe && this.iframe.contentWindow) {
      try {
        this.iframe.contentWindow.postMessage(msg, '*');
      } catch (e) { /* 忽略发送异常 */ }
    }
  }

  sendRequest(method, params, timeoutMs) {
    return new Promise((resolve, reject) => {
      const id = 'mcp-app-' + this.tcId + '-' + this.nextId++;
      const timer = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error('MCP App 无响应: ' + method));
      }, timeoutMs || MSG_TIMEOUT_MS);
      this.pendingRequests.set(id, { resolve, reject, timer });
      this.post({ jsonrpc: '2.0', id, method, params });
    });
  }

  handleMessage(e) {
    if (!this.iframe || e.source !== this.iframe.contentWindow) return;
    const msg = e.data;
    if (!msg || typeof msg !== 'object' || msg.jsonrpc !== '2.0') return;

    if (msg.id != null && 'result' in msg) {
      const p = this.pendingRequests.get(String(msg.id));
      if (p) {
        clearTimeout(p.timer);
        this.pendingRequests.delete(String(msg.id));
        p.resolve(msg.result);
      }
      return;
    }
    if (msg.id != null && 'error' in msg) {
      const p = this.pendingRequests.get(String(msg.id));
      if (p) {
        clearTimeout(p.timer);
        this.pendingRequests.delete(String(msg.id));
        p.reject(new Error((msg.error && msg.error.message) || 'MCP App error'));
      }
      return;
    }

    switch (msg.method) {
      case 'ui/initialize':
        this.respond(msg.id, this.buildInitializeResult(msg.params || {}));
        break;
      case 'ui/notifications/initialized':
        this.onInitialized();
        break;
      case 'tools/call':
        this.handleToolsCall(msg.id, msg.params || {});
        break;
      case 'ui/update-model-context':
        this.handleUpdateModelContext(msg.id, msg.params || {});
        break;
      case 'ui/open-link':
        this.respondError(msg.id, { code: -32001, message: 'open-link rejected: 默认拒绝外部链接' });
        break;
      case 'ui/message':
        this.respondError(msg.id, { code: -32001, message: 'ui/message 未启用（P2）' });
        break;
      case 'notifications/message': {
        const p = msg.params || {};
        if (this.callbacks.log) this.callbacks.log(p.level || 'info', p.message || '');
        break;
      }
      case 'ui/notifications/size-changed':
        break;
      default:
        this.respondError(msg.id, { code: -32601, message: '未知方法: ' + msg.method });
    }
  }

  buildInitializeResult(params) {
    void params;
    return {
      hostCapabilities: {
        openLinks: false,
        serverTools: true,
        serverResources: false
      },
      hostContext: {
        theme: document.documentElement.classList.contains('dark') ? 'dark' : 'light',
        locale: 'zh-CN',
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
        containerDimensions: {
          width: this.iframe ? this.iframe.clientWidth || 640 : 640,
          height: this.iframe ? this.iframe.clientHeight || 400 : 400
        }
      }
    };
  }

  onInitialized() {
    this.initialized = true;
    const queue = this.pendingMessages;
    this.pendingMessages = [];
    for (const m of queue) {
      if (!this.disabled) this.post({ jsonrpc: '2.0', method: m.method, params: m.params });
    }
  }

  async handleToolsCall(id, params) {
    if (this.disabled) {
      this.respondError(id, { code: -32000, message: 'host 已卸载' });
      return;
    }
    const tool = (params && params.name) || '';
    const args = (params && params.arguments) || {};
    try {
      const result = await window.App.api.callMcpTool(this.server, tool, args, false);
      this.respond(id, result);
    } catch (e) {
      if (e.data && e.data.needsConfirm && this.callbacks.needsConfirm) {
        try {
          const approved = await this.callbacks.needsConfirm({ name: tool, arguments: args });
          if (!approved || this.disabled) {
            this.respondError(id, { code: -32000, message: '调用被用户拒绝' });
            return;
          }
          const result = await window.App.api.callMcpTool(this.server, tool, args, true);
          this.respond(id, result);
        } catch (err) {
          this.respondError(id, { code: -32000, message: err.message || '调用失败' });
        }
        return;
      }
      this.respondError(id, { code: -32000, message: e.message || '调用失败' });
    }
  }

  async handleUpdateModelContext(id, params) {
    const sessionId = (window.App.state && window.App.state.getSessionId()) || '';
    const content = (params && params.content) || '';
    const structured = (params && params.structuredContent) || null;
    try {
      if (!sessionId) throw new Error('无活跃会话，无法更新模型上下文');
      await window.App.api.updateUiContext(sessionId, content, structured);
      this.respond(id, {});
      if (this.callbacks.log) this.callbacks.log('info', 'ui/update-model-context 已静默更新');
    } catch (e) {
      this.respondError(id, { code: -32000, message: e.message || '上下文更新失败' });
    }
  }

  respond(id, result) {
    if (id == null) return;
    this.post({ jsonrpc: '2.0', id, result });
  }

  respondError(id, error) {
    if (id == null) return;
    this.post({ jsonrpc: '2.0', id, error });
  }
}

function injectCspMeta(html, csp) {
  const value = (csp && csp.default) || '';
  const meta = value
    ? '<meta http-equiv="Content-Security-Policy" content="' + escapeAttr(value) + '">'
    : '';
  const headMatch = html.match(/<head[^>]*>/i);
  if (headMatch) {
    const idx = headMatch.index + headMatch[0].length;
    return html.slice(0, idx) + meta + html.slice(idx);
  }
  return meta + html;
}

function escapeAttr(s) {
  return String(s).replace(/"/g, '&quot;');
}

export function buildToolResult(rawText, state) {
  const text = rawText == null ? '' : String(rawText);
  return {
    content: text ? [{ type: 'text', text }] : [],
    isError: state === 'error' || state === 'denied' || state === 'interrupted',
    structuredContent: null
  };
}