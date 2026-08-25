"use client";
// 发布助手对话：无状态单次流（POST /threads/{sessionId}/chat，SSE 增量渲染）
// HITL：permission_ask 渲染确认卡片 → /threads/{sessionId}/confirm-stream 恢复
import { useCallback, useEffect, useRef, useState } from "react";

const AGENT_BASE = "/agent/release-agent";
type ChatMsg = {
  role: "user" | "assistant" | "tool" | "system";
  content: string;
  pending?: boolean;   // 工具行执行中
  confirm?: ConfirmCard;
};
type ConfirmCard = { toolCalls: { tool_call_id: string; name: string; input: unknown }[] };

// HTTP 环境非安全上下文无 crypto.randomUUID，用时间戳+随机串兜底
const uid = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

function getSessionId(): string {
  if (typeof window === "undefined") return "";
  let sid = window.localStorage.getItem("oaf-assistant-sid");
  if (!sid) {
    sid = `webui-${uid()}`;
    window.localStorage.setItem("oaf-assistant-sid", sid);
  }
  return sid;
}

export default function AssistantPage() {
  const [messages, setMessages] = useState<ChatMsg[]>([
    { role: "system", content: "我是 OAF 平台的智能发布助手。可以让我发布配置包、查询服务状态、更新环境变量、重新发布或下线服务。" },
  ]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const sessionId = useRef<string>("");

  useEffect(() => {
    sessionId.current = getSessionId();
  }, []);
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const updateLast = useCallback((fn: (m: ChatMsg) => ChatMsg) => {
    setMessages((prev) => {
      const next = [...prev];
      next[next.length - 1] = fn(next[next.length - 1]);
      return next;
    });
  }, []);
  // 文本增量必须落到「最后一条 assistant 气泡」——工具状态行会插在其后
  const updateLastAssistant = useCallback((fn: (m: ChatMsg) => ChatMsg) => {
    setMessages((prev) => {
      const next = [...prev];
      for (let i = next.length - 1; i >= 0; i--) {
        if (next[i].role === "assistant") { next[i] = fn(next[i]); break; }
      }
      return next;
    });
  }, []);

  /** 消费一次 SSE 单次流；返回是否出现 permission_ask */
  const consumeStream = useCallback(async (url: string, body: object, onAsk: (card: ConfirmCard) => void) => {
    const resp = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify(body),
    });
    if (!resp.ok || !resp.body) {
      throw new Error(`HTTP ${resp.status}`);
    }
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    let asked = false;

    const handlePayload = (line: string) => {
      if (!line.trim()) return;
      let ev: any;
      try { ev = JSON.parse(line); } catch { return; }
      switch (ev.type) {
        case "TEXT_BLOCK_DELTA":
          updateLastAssistant((m) => ({ ...m, content: m.content + (ev.delta ?? "") }));
          break;
        case "THINKING_BLOCK_DELTA":
          break; // 思考过程不上屏
        case "TOOL_CALL_START":
          setMessages((prev) => [...prev, { role: "tool", content: `🔧 ${ev.toolName}`, pending: true }]);
          break;
        case "TOOL_RESULT_END": {
          setMessages((prev) => prev.map((m) =>
            m.role === "tool" && m.pending && m.content.includes(ev.toolName)
              ? { ...m, content: `${m.content} ✓`, pending: false } : m));
          break;
        }
        case "permission_ask":
          asked = true;
          onAsk({ toolCalls: ev.tool_calls ?? [] });
          break;
        case "error":
          setMessages((prev) => [...prev, { role: "system", content: `⚠️ ${ev.error}` }]);
          break;
        // AGENT_END 等其余事件忽略（流关闭即终态）
      }
    };

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const frames = buf.split("\n\n");
      buf = frames.pop() ?? "";
      for (const frame of frames) {
        for (const line of frame.split("\n")) {
          if (line.startsWith("data:")) handlePayload(line.slice(5));
        }
      }
    }
    return asked;
  }, [updateLast]);

  /** 确认/拒绝后恢复执行（新执行段续流） */
  const sendConfirm = useCallback(async (card: ConfirmCard, confirmed: boolean) => {
    setBusy(true);
    try {
      const results = card.toolCalls.map((tc) => ({ tool_call_id: tc.tool_call_id, confirmed }));
      setMessages((prev) => [...prev,
        { role: "user", content: confirmed ? "（已批准工具执行）" : "（已拒绝工具执行）" },
        { role: "assistant", content: "", pending: true }]);
      await consumeStream(`${AGENT_BASE}/threads/${sessionId.current}/confirm-stream`, { results }, () => {});
    } catch (e: any) {
      setMessages((prev) => [...prev, { role: "system", content: `⚠️ ${e.message}` }]);
    } finally {
      updateLast((m) => ({ ...m, pending: false }));
      setBusy(false);
    }
  }, [consumeStream, updateLast]);

  const send = useCallback(async () => {
    const text = input.trim();
    if (!text || busy) return;
    setInput("");
    setBusy(true);
    setMessages((prev) => [
      ...prev.filter((m) => !(m.role === "tool")),
      { role: "user", content: text },
      { role: "assistant", content: "", pending: true },
    ]);
    try {
      await consumeStream(`${AGENT_BASE}/threads/${sessionId.current}/chat`,
        { message: text, userId: "webui" }, (card) => {
          updateLast((m) => ({ ...m, confirm: card }));
        });
    } catch (e: any) {
      setMessages((prev) => [...prev, { role: "system", content: `⚠️ 连接中断: ${e.message}` }]);
    } finally {
      updateLast((m) => ({ ...m, pending: false }));
      setBusy(false);
    }
  }, [busy, consumeStream, input, updateLast]);

  const resetSession = () => {
    const sid = `webui-${uid()}`;
    window.localStorage.setItem("oaf-assistant-sid", sid);
    sessionId.current = sid;
    setMessages([{ role: "system", content: "已开启新会话。" }]);
  };

  return (
    <div data-testid="assistant-page" className="flex flex-col h-[calc(100vh-8rem)]">
      <div className="flex items-center justify-between mb-2">
        <h1 className="text-xl font-semibold">发布助手</h1>
        <button onClick={resetSession} data-testid="new-session"
          className="text-xs px-2 py-1 border rounded hover:bg-gray-100">新会话</button>
      </div>

      <div ref={listRef} className="flex-1 overflow-y-auto bg-white border rounded p-4 space-y-3">
        {messages.map((m, i) => <Bubble key={i} msg={m} onConfirm={(ok) => m.confirm && sendConfirm(m.confirm, ok)} />)}
      </div>

      <div className="mt-3 flex gap-2">
        <textarea value={input} onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
          rows={2} placeholder="例如：现在有哪些服务？/ 把 packageId=3 发布一下 / 下线 demo 服务"
          data-testid="chat-input" disabled={busy}
          className="flex-1 border rounded p-2 text-sm resize-none disabled:opacity-50" />
        <button onClick={send} disabled={busy || !input.trim()} data-testid="chat-send"
          className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white text-sm rounded px-5">
          {busy ? "…" : "发送"}
        </button>
      </div>
    </div>
  );
}

function Bubble({ msg, onConfirm }: { msg: ChatMsg; onConfirm: (ok: boolean) => void }) {
  if (msg.role === "tool") {
    return (
      <div className={`text-xs font-mono ${msg.pending ? "text-gray-400 animate-pulse" : "text-emerald-600"}`}>
        {msg.content}{msg.pending ? " …" : ""}
      </div>
    );
  }
  if (msg.role === "system") {
    return <div className="text-xs text-amber-700 bg-amber-50 border border-amber-100 rounded p-2">{msg.content}</div>;
  }
  if (msg.role === "user") {
    return <div className="flex justify-end"><div className="bg-blue-600 text-white text-sm rounded-lg px-3 py-2 max-w-[80%] whitespace-pre-wrap">{msg.content}</div></div>;
  }
  return (
    <div className="space-y-2">
      <div className={`text-sm bg-gray-100 rounded-lg px-3 py-2 max-w-[90%] whitespace-pre-wrap ${msg.pending && !msg.content ? "animate-pulse text-gray-400" : ""}`} data-testid="assistant-msg">
        {msg.content || (msg.pending ? "思考中…" : "")}
        {msg.pending && msg.content ? <span className="animate-pulse">▍</span> : null}
      </div>
      {msg.confirm && (
        <div className="border border-yellow-300 bg-yellow-50 rounded p-3 text-sm space-y-2" data-testid="confirm-card">
          <p className="font-medium">⚠️ 需要你确认以下工具调用：</p>
          {msg.confirm.toolCalls.map((tc) => (
            <pre key={tc.tool_call_id} className="text-xs bg-white border rounded p-2 overflow-auto">{tc.name}\n{JSON.stringify(tc.input, null, 2)}</pre>
          ))}
          <div className="space-x-2">
            <button onClick={() => onConfirm(true)} className="px-3 py-1 bg-green-600 hover:bg-green-700 text-white text-xs rounded">批准</button>
            <button onClick={() => onConfirm(false)} className="px-3 py-1 border rounded text-red-600 text-xs hover:bg-red-50">拒绝</button>
          </div>
        </div>
      )}
    </div>
  );
}
