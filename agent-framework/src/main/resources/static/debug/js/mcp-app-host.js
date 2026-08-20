/* ===== MCP Apps Host：iframe 生命周期 + JSON-RPC over postMessage =====
 *
 * 对齐 MCP Apps 规范（Stable 2026-01-26）与官方 ext-apps/examples/basic-host 参考实现，
 * 设计文档 mcp-apps-extension-plan.md §5.1.2。
 *
 * 协议要点：
 * - app → host: ui/initialize(request) → host 回 McpUiInitializeResult（hostCapabilities/hostContext）
 * - app → host: ui/notifications/initialized(notification) → handshake 完成，此后才下发 tool-input 等
 * - host → app: ui/notifications/tool-input / tool-result / tool-cancelled(notification)
 * - app → host: tools/call(request) → 转 POST /mcp/{server}/tools/{tool}；ask 工具走确认流重试
 * - app → host: ui/update-model-context(request) → 静默更新模型上下文（POST /mcp/ui-context，4.7）
 * - app → host: ui/open-link(request) → 安全默认拒绝
 * - host → app: ui/resource-teardown(request) → 卸载前通知（等待响应后销毁，防数据丢失）
 *
 * 安全：
 * - iframe sandbox="allow-scripts"（无 allow-same-origin，opaque origin）
 * - srcdoc 注入 HTML + <meta http-equiv="Content-Security-Policy">（srcdoc 下响应头 CSP 不生效）
 * - 消息过滤 e.source === iframe.contentWindow 且 JSON-RPC 格式校验
 */

const MSG_TIMEOUT_MS = 8000;   // tools/call 等 request 的响应等待超时
const HANDSHAKE_TIMEOUT_MS = 10000;

export class McpAppHost {
  /**
   * @param {Object} opts
   * @param {string} opts.tcId        工具调用 ID（单例 key）
   * @param {string} opts.server      MCP server 名
   * @param {string} opts.resourceUri ui:// 资源 URI
   * @param {string} opts.toolName    工具名（卡片标题）
   * @param {Object} opts.callbacks
   * @param {Function} [opts.callbacks.needsConfirm] async (call:{name,arguments}) => boolean
   * @param {Function} [opts.callbacks.log] (level, message) => void
   */
  constructor({ tcId, server, resourceUri, toolName, callbacks = {} }) {
    this.tcId = tcId;
    this.server = server;
    this.resourceUri = resourceUri;
    this.toolName = toolName;
    this.callbacks = callbacks;

    this.iframe = null;
    this.container = null;
    this.initialized = false;        // ui/notifications/initialized 到达
    this.disabled = false;           // teardown 后不再收发消息
    this.pendingMessages = [];       // handshake 前排队（tool-input）
    this.nextId = 1;
    this.pendingRequests = new Map(); // id -> {resolve, reject, timer}
    this.onMessage = (e) => this.handleMessage(e);
    this.toolArguments = null;
    this.toolResult = null;
  }

  /** 异步挂载：拉取资源 HTML → 创建沙箱 iframe → 监听 postMessage */
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
    // 沙箱：allow-scripts 但不 allow-same-origin（opaque origin，隔离卡片）
    this.iframe.setAttribute('sandbox', 'allow-scripts');
    this.iframe.srcdoc = html;
    container.innerHTML = '';   // 清除「加载中…」
    container.appendChild(this.iframe);
    window.addEventListener('message', this.onMessage);
    return this.iframe;
  }

  /** 卸载：发 ui/resource-teardown，等待响应后销毁（超时兜底）；iframe DOM 保留静态渲染 */
  teardown(reason) {
    if (this.disabled || !this.iframe) return;
    this.disabled = true;
    window.removeEventListener('message', this.onMessage);
    this.pendingRequests.forEach((p) => clearTimeout(p.timer));
    this.pendingRequests.clear();
    // 通知卡片（fire-and-forget：不阻塞销毁，超时由 App 侧自行清理）
    this.post({
      jsonrpc: '2.0',
      id: 'teardown-' + this.nextId++,
      method: 'ui/resource-teardown',
      params: { reason: reason || 'reply-completed' }
    });
  }

  /** 工具完整参数（initialize 完成后必发；未完成先排队） */
  sendToolInput(args) {
    this.toolArguments = args;
    this.sendAfterHandshake('ui/notifications/tool-input', { arguments: args });
  }

  /** 工具执行结果推送（CallToolResult: content + isError + structuredContent） */
  sendToolResult(result) {
    this.toolResult = result;
    this.sendAfterHandshake('ui/notifications/tool-result', result || {});
  }

  /** 工具取消通知（拒绝确认 / 流中断） */
  sendToolCancelled(reason) {
    this.sendAfterHandshake('ui/notifications/tool-cancelled', { reason: reason || 'cancelled' });
  }

  /** handshake 完成后下发；未完成入队 */
  sendAfterHandshake(method, params) {
    if (this.disabled || !this.iframe) return;
    if (!this.initialized) {
      this.pendingMessages.push({ method, params });
      return;
    }
    this.post({ jsonrpc: '2.0', method, params });
  }

  // ---------- 消息收发 ----------

  /** host → app：postMessage（iframe 为 opaque origin，targetOrigin 只能用 *） */
  post(msg) {
    if (this.iframe && this.iframe.contentWindow) {
      try {
        this.iframe.contentWindow.postMessage(msg, '*');
      } catch (e) { /* 忽略发送异常 */ }
    }
  }

  /** 发送 JSON-RPC request 并等待响应 */
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

  /** app → host 消息入口（source 过滤 + JSON-RPC 格式校验） */
  handleMessage(e) {
    if (!this.iframe || e.source !== this.iframe.contentWindow) return;
    const msg = e.data;
    if (!msg || typeof msg !== 'object' || msg.jsonrpc !== '2.0') return;

    // 响应帧：匹配待处理 request
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
        // 安全默认：一律拒绝（P2 支持配置化白名单）
        this.respondError(msg.id, { code: -32001, message: 'open-link rejected: 默认拒绝外部链接（P2 白名单）' });
        break;
      case 'ui/message':
        // P2：触发新回复（4.7 P2 项），当前仅记录
        this.respondError(msg.id, { code: -32001, message: 'ui/message 未启用（P2）' });
        break;
      case 'notifications/message': {
        const p = msg.params || {};
        if (this.callbacks.log) this.callbacks.log(p.level || 'info', p.message || '');
        break;
      }
      case 'ui/notifications/size-changed':
        // P2：iframe 高度自适应
        break;
      default:
        this.respondError(msg.id, { code: -32601, message: '未知方法: ' + msg.method });
    }
  }

  /** ui/initialize 响应：声明宿主能力（serverTools 已实现，openLinks/serverResources 未启用） */
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

  /** handshake 完成：下发排队消息（tool-input） */
  onInitialized() {
    this.initialized = true;
    const queue = this.pendingMessages;
    this.pendingMessages = [];
    for (const m of queue) {
      if (!this.disabled) this.post({ jsonrpc: '2.0', method: m.method, params: m.params });
    }
  }

  /** 卡片 tools/call：转代理端点；ask 工具走确认流（403 needsConfirm → 确认后重试） */
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
      // ask 工具：403 + {needsConfirm, toolCalls} → 确认卡片 → 重试
      if (e.data && e.data.needsConfirm && this.callbacks.needsConfirm) {
        try {
          const approved = await this.callbacks.needsConfirm({
            name: tool,
            arguments: args
          });
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

  /** 静默更新模型上下文（4.7）：持久化，下次 agent 调用时注入（不触发新回复） */
  async handleUpdateModelContext(id, params) {
    const sessionId = (window.App.state && window.App.state.getState('threads.current')) || '';
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

  /** JSON-RPC 响应 */
  respond(id, result) {
    if (id == null) return; // notification 无响应
    this.post({ jsonrpc: '2.0', id, result });
  }

  respondError(id, error) {
    if (id == null) return;
    this.post({ jsonrpc: '2.0', id, error });
  }
}

/**
 * 将后端下发的 CSP 构造为 <meta> 注入 HTML <head>（srcdoc 下响应头 CSP 不生效）。
 * @param {string} html      原始 HTML
 * @param {Object} csp       {default: "default-src 'none'; ..."}
 */
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

/** 工具结果转规范 CallToolResult（SSE 词表仅有文本 + state，content 简化为单 text 块） */
export function buildToolResult(rawText, state) {
  const text = rawText == null ? '' : String(rawText);
  return {
    content: text ? [{ type: 'text', text }] : [],
    isError: state === 'error' || state === 'denied' || state === 'interrupted',
    structuredContent: null
  };
}
