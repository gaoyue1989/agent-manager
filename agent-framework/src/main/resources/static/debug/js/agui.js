/* ===== AG-UI 协议传输（agui-native-parity-design §12.2 L2） =====
 *
 * 与 api.js 的 sendChat/subscribe/confirmStream **同签名**，内部改走 /agui/* 端点并把
 * AG-UI 事件翻译为 native 词表——chat.js 渲染器零改动，调用点仅按 ui.protocol 分流。
 *
 * 翻译表（AguiEvent → native handleEvent 等价事件）：
 *   RUN_STARTED            → AGENT_START
 *   TEXT_MESSAGE_*         → TEXT_BLOCK_START/DELTA/END
 *   REASONING_MESSAGE_*    → THINKING_BLOCK_*
 *   TOOL_CALL_START/ARGS/END → TOOL_CALL_START/DELTA/END
 *   TOOL_CALL_RESULT       → TOOL_RESULT_START + TEXT_DELTA + END
 *   RUN_FINISHED(interrupts) → permission_ask（tool_call_id = interruptId 尾段；
 *                              interruptId 映射保留供 resume[] 回传）
 *   RUN_FINISHED(正常)      → AGENT_END + done
 *   RUN_ERROR              → error
 *   CUSTOM(token_usage)    → MODEL_CALL_END
 *   CUSTOM(合成帧名)        → value 原样（native 形态帧）
 *   CUSTOM(platform.control:interrupted) → interrupted
 *   CUSTOM(platform.native_event) → value 原样（续传降级段，本就是 native 帧）
 *   RAW / 未知              → 忽略（console 可见）
 */

const BASE = window.location.pathname.replace(/\/(?:debug|monitor)\/?.*$/, '') || '';

/** 当前 run 的 toolCallId → interruptId 映射（HITL resume[] 回传用；run 开始时清空） */
let interruptIdMap = {};

async function sseRequest(path, { method, body, headers, onEvent, onError, onEnd, onWaiting }) {
  const controller = new AbortController();
  let lastEventId = 0;
  const connect = async () => {
    let resp;
    try {
      resp = await fetch(BASE + path, {
        method: method || 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal
      });
    } catch (e) {
      if (!controller.signal.aborted && onError) onError(e);
      return;
    }
    if (!resp.ok || !resp.body) {
      let msg = 'HTTP ' + resp.status;
      try { const j = await resp.json(); if (j && j.error) msg = j.error; } catch (e) {}
      if (onError) onError(new Error(msg));
      return;
    }
    if (onWaiting) onWaiting();
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
          if (line.startsWith('id:')) {
            const idVal = parseInt(line.slice(3).trim(), 10);
            if (!isNaN(idVal) && idVal > lastEventId) lastEventId = idVal;
            continue;
          }
          if (!line.startsWith('data:')) continue; // comment 帧（心跳/排队）忽略
          const dataStr = line.slice(5).trim();
          if (!dataStr) continue;
          let evt;
          try { evt = JSON.parse(dataStr); } catch (e) { continue; }
          console.log('[SSE/agui]', JSON.stringify(evt, null, 2));
          for (const native of translate(evt)) {
            if (native.type === '__end__') { if (onEnd) onEnd(); return; }
            if (native.type === '__error__') { if (onError) onError(new Error(native.error)); return; }
            if (onEvent) onEvent(native);
          }
        }
      }
      // 流关闭：RUN_FINISHED 已翻译出 done（__end__ 分支提前返回），此处兜底收流
      if (onEnd) onEnd();
    } catch (e) {
      if (!controller.signal.aborted && onError) onError(e);
    }
  };
  connect();
  return { close: () => controller.abort(), get lastEventId() { return lastEventId; } };
}

/** AguiEvent → native 词表事件数组 */
export function translate(evt) {
  const t = evt.type;
  switch (t) {
    case 'RUN_STARTED':
      return [{ type: 'AGENT_START' }];
    case 'TEXT_MESSAGE_START':
      return [{ type: 'TEXT_BLOCK_START', blockId: evt.messageId }];
    case 'TEXT_MESSAGE_CONTENT':
      return [{ type: 'TEXT_BLOCK_DELTA', delta: evt.delta, blockId: evt.messageId }];
    case 'TEXT_MESSAGE_END':
      return [{ type: 'TEXT_BLOCK_END', blockId: evt.messageId }];
    case 'REASONING_MESSAGE_START':
      return [{ type: 'THINKING_BLOCK_START', blockId: (evt.messageId || 'r') }];
    case 'REASONING_MESSAGE_CONTENT':
      return [{ type: 'THINKING_BLOCK_DELTA', delta: evt.delta, blockId: (evt.messageId || 'r') }];
    case 'REASONING_MESSAGE_END':
      return [{ type: 'THINKING_BLOCK_END', blockId: (evt.messageId || 'r') }];
    case 'TOOL_CALL_START':
      return [{ type: 'TOOL_CALL_START', toolCallId: evt.toolCallId, toolCallName: evt.toolCallName }];
    case 'TOOL_CALL_ARGS':
      // 与 native TOOL_CALL_DELTA 同形：toolCallName 恒为占位符 __fragment__
      return [{ type: 'TOOL_CALL_DELTA', delta: evt.delta, toolCallId: evt.toolCallId, toolCallName: '__fragment__' }];
    case 'TOOL_CALL_END':
      return [{ type: 'TOOL_CALL_END', toolCallId: evt.toolCallId, toolCallName: evt.toolCallName }];
    case 'TOOL_CALL_RESULT': {
      const frames = [{ type: 'TOOL_RESULT_START', toolCallId: evt.toolCallId }];
      if (evt.content != null) {
        frames.push({ type: 'TOOL_RESULT_TEXT_DELTA', delta: String(evt.content), toolCallId: evt.toolCallId });
      }
      frames.push({ type: 'TOOL_RESULT_END', toolCallId: evt.toolCallId, state: evt.state || 'SUCCESS' });
      return frames;
    }
    case 'RUN_FINISHED': {
      const interrupts = evt.outcome && evt.outcome.interrupts;
      if (interrupts && interrupts.length > 0) {
        interruptIdMap = {};
        return [{
          type: 'permission_ask',
          tool_calls: interrupts.map((i) => {
            // interruptId = runId:toolCallId；卡片与提交链路沿用 native 的 tool_call_id 口径
            const toolCallId = String(i.id || '').split(':').pop();
            const meta = i.metadata || {};
            interruptIdMap[toolCallId] = i.id;
            return { tool_call_id: toolCallId, name: meta.toolName || 'tool', input: meta.toolInput };
          }),
          reply_id: interruptIdPrefix(interrupts)
        }];
      }
      return [{ type: 'AGENT_END' }, { type: '__end__' }];
    }
    case 'RUN_ERROR':
      return [{ type: '__error__', error: evt.message || 'run error' }];
    case 'CUSTOM': {
      const name = evt.name;
      if (name === 'token_usage') {
        const d = (evt.value && evt.value.delta) || evt.value || {};
        return [{ type: 'MODEL_CALL_END', inputTokens: d.inputTokens, outputTokens: d.outputTokens, totalTokens: d.totalTokens }];
      }
      // 平台合成帧对等承载：value 本就是 native 形态（file_ready/tool_call_summary/...）
      if (name === 'platform.control') {
        const v = evt.value || {};
        return v.kind === 'interrupted' ? [{ type: 'interrupted', reason: v.reason || 'turn_interrupted' }] : [];
      }
      if (name === 'platform.native_event') {
        return evt.value ? [evt.value] : [];
      }
      if (evt.value && evt.value.type) {
        return [evt.value];
      }
      return [];
    }
    case 'RAW':
    default:
      return []; // 标准客户端忽略未知事件；console.log 已留痕
  }
}

function interruptIdPrefix(interrupts) {
  const first = String((interrupts[0] && interrupts[0].id) || '');
  const idx = first.lastIndexOf(':');
  return idx > 0 ? first.slice(0, idx) : null;
}

/** 消费 interruptIdMap：results（native confirm 形态）→ resume[]（AG-UI 恢复形态） */
function buildResume(results) {
  return (results || []).map((r) => ({
    interruptId: interruptIdMap[r.tool_call_id] || r.tool_call_id,
    status: r.confirmed ? 'resolved' : 'cancelled',
    payload: { approved: !!r.confirmed, acceptRule: !!r.accept_rule }
  }));
}

export const agui = {
  /**
   * 同 api.sendChat 签名。fileIds 在 AG-UI 模式暂不消费（上传注入是 native 面能力，
   * 见设计 §12.5），仅透传 message 文本。
   */
  sendChat: (sessionId, message, userId, fileIds, { onEvent, onWaiting, onError, onEnd } = {}) => {
    interruptIdMap = {};
    return sseRequest('/agui/runs', {
      body: {
        threadId: sessionId,
        runId: 'ui-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8),
        messages: [{ id: 'm-' + Date.now(), role: 'user', content: message }]
      },
      headers: userId ? { 'X-User-Id': userId } : {},
      onEvent, onWaiting, onError, onEnd
    });
  },

  /** 同 api.subscribe 签名：断线/刷新恢复经平台扩展续传端点 */
  subscribe: (sessionId, { afterSeq, replyId, userId, onEvent, onError, onEnd } = {}) => {
    interruptIdMap = {};
    let url = '/agui/threads/' + encodeURIComponent(sessionId) + '/events?afterSeq=' + (afterSeq || 0);
    if (replyId) url += '&runId=' + encodeURIComponent(replyId);
    return sseRequest(url, {
      method: 'GET',
      headers: userId ? { 'X-User-Id': userId } : {},
      onEvent, onError, onEnd
    });
  },

  /** 同 api.confirmStream 签名：results（native 形态）→ resume[] 恢复流 */
  confirmStream: (sessionId, results, { onEvent, onError, userId } = {}) => {
    return sseRequest('/agui/runs', {
      body: {
        threadId: sessionId,
        runId: 'ui-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8),
        messages: [],
        resume: buildResume(results)
      },
      headers: userId ? { 'X-User-Id': userId } : {},
      onEvent, onError
    });
  }
};
