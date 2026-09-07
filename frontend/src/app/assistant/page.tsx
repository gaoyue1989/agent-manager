"use client";
// 发布助手对话：无状态单次流（POST /threads/{sessionId}/chat，SSE 增量渲染）
// HITL：permission_ask 渲染确认卡片 → /threads/{sessionId}/confirm-stream 恢复
// 文件：附件上传（/files/upload）→ chat 携带 fileIds；file_ready 事件渲染下载卡片
// 历史：GET /threads 列表 + GET /threads/{sid}/history 回放；点击切换恢复上下文继续对话
// Markdown：react-markdown + remark-gfm（表格/任务列表/删除线）+ rehype-sanitize（净化）
//          代码块走 Prism oneLight 高亮；光标 span 作为 Markdown 外层兄弟节点，不进解析器
import { memo, useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeSanitize from "rehype-sanitize";
import SyntaxHighlighter from "react-syntax-highlighter/dist/esm/prism";
import { oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";

const AGENT_BASE = "/agent/release-agent";
type ChatMsg = {
  role: "user" | "assistant" | "tool" | "system";
  content: string;
  pending?: boolean;   // 工具行执行中
  confirm?: ConfirmCard;
  files?: FileCard[];  // file_ready 渲染的下载卡片
};
type FileCard = { file_id: string; file_name: string; mime_type: string; size: number; download_url: string };
type AttachItem = { fileId: string; name: string; mime: string; size: number };
type ConfirmCard = { toolCalls: { tool_call_id: string; name: string; input: unknown }[] };
type ThreadItem = { peer: string; fullKey: string; updatedAt: string };

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

// agent_state 会话 key 为 "{peer}:gw-{hash}"（ChatUiChannel 固定 gw-hash）；
// chat 端点以 peer（冒号前部分）为 sessionId，peer 相同即恢复同一会话上下文。
// A2A 来源会话（后缀为 vendorKey 非 gw-）不展示。
function parseChatThread(sessionId: string): ThreadItem | null {
  const idx = sessionId.indexOf(":gw-");
  if (idx <= 0) return null;
  return { peer: sessionId.slice(0, idx), fullKey: sessionId, updatedAt: "" };
}

export default function AssistantPage() {
  const [messages, setMessages] = useState<ChatMsg[]>([
    { role: "system", content: "我是 OAF 平台的智能发布助手。可以让我发布配置包、查询服务状态、更新环境变量、重新发布或下线服务。" },
  ]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [attachments, setAttachments] = useState<AttachItem[]>([]);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const sessionId = useRef<string>("");

  // 历史会话：GET /threads 列表（ChatUiChannel 来源，最近 20 条）；showHistory 控制侧栏显隐
  const [threads, setThreads] = useState<ThreadItem[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);

  const loadThreads = useCallback(async () => {
    try {
      const resp = await fetch(`${AGENT_BASE}/threads`);
      if (!resp.ok) return;
      const list: any[] = await resp.json();
      const items = list
        .map((t) => {
          const p = parseChatThread(t.session_id ?? "");
          return p ? { ...p, updatedAt: (t.updated_at ?? "").replace("T", " ").slice(0, 19) } : null;
        })
        .filter(Boolean) as ThreadItem[];
      setThreads(items.slice(0, 20));
    } catch { /* 列表加载失败静默（面板显示空态） */ }
  }, []);

  /** 历史消息回放：GET /threads/{fullKey}/history → ChatMsg[]（含未消费 HITL 卡片重建 + 产出文件卡片） */
  const loadHistory = useCallback(async (fullKey: string) => {
    const msgs: ChatMsg[] = [];
    try {
      const resp = await fetch(`${AGENT_BASE}/threads/${encodeURIComponent(fullKey)}/history`);
      if (resp.ok) {
        const data = await resp.json();
        for (const m of (data.messages ?? [])) {
          if (m.role === "user") {
            msgs.push({ role: "user", content: m.content ?? "" });
          } else if (m.role === "assistant") {
            if (m.content) msgs.push({ role: "assistant", content: m.content });
            // 工具调用行渲染在 assistant 文本之后（与 SSE 最终形态一致：🔧 name ✓）
            for (const tc of (m.tool_calls ?? [])) {
              msgs.push({ role: "tool", content: `🔧 ${tc.name} ✓`, pending: false });
            }
          }
        }
        // 产出文件卡片：挂到最后一条 assistant 气泡（与 SSE file_ready 渲染一致）
        const files: FileCard[] = (data.files ?? []).map((f: any) => ({
          file_id: f.file_id, file_name: f.file_name, mime_type: f.mime_type,
          size: f.size ?? 0, download_url: `${AGENT_BASE}/files/${f.file_id}`,
        }));
        if (files.length > 0) {
          const last = msgs.map((m) => m.role).lastIndexOf("assistant");
          if (last >= 0) {
            msgs[last] = { ...msgs[last], files: [...(msgs[last].files ?? []), ...files] };
          } else {
            msgs.push({ role: "assistant", content: "", files });
          }
        }
        // 未消费的确认卡片：重建 HITL 卡片供用户批准/拒绝（confirm-stream 恢复）
        if (data.pendingConfirm?.tools?.length) {
          try {
            msgs.push({ role: "assistant", content: "", confirm: { toolCalls: data.pendingConfirm.tools } });
          } catch { /* 工具格式异常忽略 */ }
        }
      }
    } catch { /* 回放失败按空会话处理（上下文仍在后端，不影响继续对话） */ }
    return msgs;
  }, []);

  /** 切换到历史会话：更新本地会话 id → 回放历史消息（上下文由后端 checkpoint 自动恢复） */
  const selectSession = useCallback(async (item: ThreadItem) => {
    if (busy) return;
    window.localStorage.setItem("oaf-assistant-sid", item.peer);
    sessionId.current = item.peer;
    setHistoryLoading(true);
    setShowHistory(false);
    const msgs = await loadHistory(item.fullKey);
    setMessages(msgs.length > 0
      ? msgs
      : [{ role: "system", content: "该会话暂无可展示的历史消息，可直接继续对话。" }]);
    setHistoryLoading(false);
  }, [busy, loadHistory]);

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

  /** 附件上传：POST /files/upload（multipart）→ 追加到附件列表 */
  const uploadFile = useCallback(async (file: File) => {
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append("file", file);
      fd.append("userId", "webui");
      fd.append("sessionId", sessionId.current);
      const resp = await fetch(`${AGENT_BASE}/files/upload`, { method: "POST", body: fd });
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({}));
        throw new Error(body.message ?? `上传失败 HTTP ${resp.status}`);
      }
      const data = await resp.json();
      setAttachments((prev) => [...prev, {
        fileId: data.file_id, name: data.file_name, mime: data.mime_type, size: data.size,
      }]);
    } catch (e: any) {
      setMessages((prev) => [...prev, { role: "system", content: `⚠️ ${e.message}` }]);
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }, []);

  const removeAttachment = (fileId: string) =>
    setAttachments((prev) => prev.filter((a) => a.fileId !== fileId));

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

    // 文本增量节流：60ms 窗口内累积 delta 后批量 setState，避免每 token 触发整段 markdown 解析
    let pendingDelta = "";
    let flushTimer: ReturnType<typeof setTimeout> | null = null;
    const flushDelta = () => {
      flushTimer = null;
      if (!pendingDelta) return;
      const d = pendingDelta;
      pendingDelta = "";
      updateLastAssistant((m) => ({ ...m, content: m.content + d }));
    };
    const scheduleFlush = () => {
      if (flushTimer) return;
      flushTimer = setTimeout(flushDelta, 60);
    };

    const handlePayload = (line: string) => {
      if (!line.trim()) return;
      let ev: any;
      try { ev = JSON.parse(line); } catch { return; }
      switch (ev.type) {
        case "TEXT_BLOCK_DELTA":
          pendingDelta += ev.delta ?? "";
          scheduleFlush();
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
        case "file_ready":
          // Agent 产出文件 → 渲染下载卡片（挂到最后一条 assistant 气泡）
          updateLastAssistant((m) => ({
            ...m,
            files: [...(m.files ?? []), {
              file_id: ev.file_id, file_name: ev.file_name, mime_type: ev.mime_type,
              size: ev.size, download_url: ev.download_url,
            }],
          }));
          break;
        case "TOOL_RESULT_DATA_DELTA":
          // 图片类二进制工具结果内联（base64 小图直接渲染）
          if (ev.media_type?.startsWith("image/") && ev.data) {
            updateLastAssistant((m) => ({
              ...m,
              files: [...(m.files ?? []), {
                file_id: `inline-${m.content.length}`, file_name: "inline-image",
                mime_type: ev.media_type, size: 0,
                download_url: `data:${ev.media_type};base64,${ev.data}`,
              }],
            }));
          }
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
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
    flushDelta();
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
    const fileIds = attachments.map((a) => a.fileId);
    if ((!text && fileIds.length === 0) || busy) return;
    setInput("");
    setBusy(true);
    const attachNames = attachments.map((a) => a.name);
    setMessages((prev) => [
      ...prev.filter((m) => !(m.role === "tool")),
      { role: "user", content: text + (attachNames.length ? `\n[附件: ${attachNames.join(", ")}]` : "") },
      { role: "assistant", content: "", pending: true },
    ]);
    setAttachments([]);
    try {
      await consumeStream(`${AGENT_BASE}/threads/${sessionId.current}/chat`,
        { message: text, userId: "webui", fileIds }, (card) => {
          updateLast((m) => ({ ...m, confirm: card }));
        });
    } catch (e: any) {
      setMessages((prev) => [...prev, { role: "system", content: `⚠️ 连接中断: ${e.message}` }]);
    } finally {
      updateLast((m) => ({ ...m, pending: false }));
      setBusy(false);
    }
  }, [busy, consumeStream, input, attachments, updateLast]);

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
        <div className="flex gap-2">
          <button onClick={async () => { setShowHistory((v) => !v); if (!showHistory) await loadThreads(); }}
            data-testid="history-btn"
            className="text-xs px-2 py-1 border rounded hover:bg-gray-100">历史会话</button>
          <button onClick={resetSession} data-testid="new-session"
            className="text-xs px-2 py-1 border rounded hover:bg-gray-100">新会话</button>
        </div>
      </div>

      {showHistory && (
        <div data-testid="history-panel" className="mb-2 border rounded bg-white p-2 max-h-64 overflow-y-auto">
          {threads.length === 0 ? (
            <p className="text-xs text-gray-400 p-2">暂无历史会话（发送消息后自动记录，保留 7 天）</p>
          ) : (
            <ul className="space-y-1">
              {threads.map((t) => (
                <li key={t.fullKey}>
                  <button onClick={() => selectSession(t)} disabled={busy}
                    data-testid="history-item"
                    className={`w-full text-left text-xs px-2 py-1.5 rounded hover:bg-blue-50 disabled:opacity-50 ${sessionId.current === t.peer ? "bg-blue-100 font-medium" : ""}`}>
                    <span className="font-mono">{t.peer}</span>
                    <span className="float-right text-gray-400">{t.updatedAt}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div ref={listRef} className="flex-1 overflow-y-auto bg-white border rounded p-4 space-y-3">
        {historyLoading ? (
          <p className="text-xs text-gray-400">加载历史会话…</p>
        ) : (
          messages.map((m, i) => <Bubble key={i} msg={m} onConfirm={(ok) => m.confirm && sendConfirm(m.confirm, ok)} />)
        )}
      </div>

      <div className="mt-3">
        {attachments.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-2">
            {attachments.map((a) => (
              <span key={a.fileId} data-testid="attach-chip"
                className="inline-flex items-center gap-1 text-xs bg-blue-50 border border-blue-200 rounded px-2 py-1">
                {a.name}
                <button onClick={() => removeAttachment(a.fileId)} className="text-red-500 hover:text-red-700">✕</button>
              </span>
            ))}
          </div>
        )}
        <div className="flex gap-2">
          <input ref={fileInputRef} type="file" className="hidden"
            data-testid="attach-input"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadFile(f); }} />
          <button onClick={() => fileInputRef.current?.click()} disabled={busy || uploading}
            data-testid="attach-btn"
            className="border rounded px-3 text-sm hover:bg-gray-100 disabled:opacity-50">
            {uploading ? "上传中…" : "📎"}
          </button>
          <textarea value={input} onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
            rows={2} placeholder="例如：现在有哪些服务？/ 把 packageId=3 发布一下 / 上传文件请先点 📎"
            data-testid="chat-input" disabled={busy}
            className="flex-1 border rounded p-2 text-sm resize-none disabled:opacity-50" />
          <button onClick={send} disabled={busy || (!input.trim() && attachments.length === 0)} data-testid="chat-send"
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white text-sm rounded px-5">
            {busy ? "…" : "发送"}
          </button>
        </div>
      </div>
    </div>
  );
}

// Assistant 文本按 GFM Markdown 渲染：表格/任务列表/删除线/链接自动识别；围栏代码块走 Prism oneLight
// rehype-sanitize 默认白名单已禁 <script>/event handler；ADD_ATTR 仅扩展 class/target/rel 以保留代码块主题与外链安全属性
const Markdown = memo(function Markdown({ text }: { text: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeSanitize]}
      components={{
        code({ className, children, ...rest }) {
          const match = /language-(\w+)/.exec(className || "");
          const code = String(children).replace(/\n$/, "");
          if (match) {
            return (
              <SyntaxHighlighter language={match[1]} style={oneLight} PreTag="div" customStyle={{ margin: "6px 0", borderRadius: 6, fontSize: 12 }}>
                {code}
              </SyntaxHighlighter>
            );
          }
          return <code className="bg-gray-200 px-1 rounded text-xs" {...rest}>{children}</code>;
        },
        a: (props) => <a {...props} target="_blank" rel="noreferrer" className="text-blue-600 underline" />,
        h1: (props) => <h1 {...props} className="text-base font-semibold mt-2 mb-1" />,
        h2: (props) => <h2 {...props} className="text-base font-semibold mt-2 mb-1" />,
        h3: (props) => <h3 {...props} className="text-sm font-semibold mt-2 mb-1" />,
        ul: (props) => <ul {...props} className="list-disc ml-5 my-1" />,
        ol: (props) => <ol {...props} className="list-decimal ml-5 my-1" />,
        li: (props) => <li {...props} className="my-0.5" />,
        blockquote: (props) => <blockquote {...props} className="border-l-2 border-gray-300 pl-2 text-gray-600 my-1" />,
        table: (props) => <table {...props} className="border-collapse text-xs my-1" />,
        th: (props) => <th {...props} className="border border-gray-300 px-2 py-0.5 bg-gray-50" />,
        td: (props) => <td {...props} className="border border-gray-300 px-2 py-0.5" />,
        hr: (props) => <hr {...props} className="my-2 border-gray-200" />,
      }}
    >
      {text}
    </ReactMarkdown>
  );
});

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
      <div className={`text-sm bg-gray-100 rounded-lg px-3 py-2 max-w-[90%] ${msg.pending && !msg.content ? "animate-pulse text-gray-400" : ""}`} data-testid="assistant-msg">
        {msg.content ? <Markdown text={msg.content} /> : (msg.pending ? "思考中…" : "")}
        {msg.pending && msg.content ? <span className="animate-pulse">▍</span> : null}
      </div>
      {msg.files && msg.files.length > 0 && (
        <div className="space-y-2">
          {msg.files.map((f, i) => (
            <div key={i} data-testid="file-card"
              className="flex items-center gap-3 border border-blue-200 bg-blue-50 rounded-lg p-2 text-sm max-w-[90%]">
              <span className="text-lg">{f.mime_type?.startsWith("image/") ? "🖼️" : "📄"}</span>
              <div className="flex-1 min-w-0">
                <p className="font-medium truncate">{f.file_name}</p>
                <p className="text-xs text-gray-500">{f.size > 0 ? `${(f.size / 1024).toFixed(1)} KB` : ""} {f.mime_type}</p>
              </div>
              {f.download_url.startsWith("data:") ? (
                <img src={f.download_url} alt={f.file_name} className="max-h-24 rounded border" />
              ) : (
                <a href={f.download_url} download={f.file_name}
                  className="px-3 py-1 bg-blue-600 hover:bg-blue-700 text-white text-xs rounded"
                  data-testid="file-download">
                  下载
                </a>
              )}
            </div>
          ))}
        </div>
      )}
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
