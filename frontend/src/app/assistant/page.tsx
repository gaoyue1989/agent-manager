"use client";
// 发布助手对话（CopilotKit + AG-UI 标准协议，agui-migration-plan Phase 2）
//
// 传输（R7 spike 定稿，useSingleEndpoint=false → REST 传输）：
//   runtimeUrl = /agent/release-agent/agui/run
//   客户端实际调用：GET  {runtimeUrl}/info（agents 以 id 为键的对象 map）
//                  GET  {runtimeUrl}/threads?agentId=&limit=（{threads,nextCursor}）
//                  POST {runtimeUrl}/agent/release-agent/run（RunAgentInput → SSE）
//                  POST {runtimeUrl}/agent/release-agent/stop/{threadId}
// HITL：useInterrupt 消费标准 RUN_FINISHED.outcome.interrupts，resolve({approved}) →
//       客户端 buildResumeArray 生成 resume[] 发起新 run（服务端 agui_interrupt 覆盖率/CAS 校验）
// 刷新恢复（R11 兜底）：GET {threads}/{id}/messages 的 pendingInterrupts 自渲染卡片 +
//       手工构造 resume[] 直接 POST /agui/run（绕过 useInterrupt）
// 文件：📎 /files/upload → fileIds 经 properties → forwardedProps.fileIds（服务端注入工作区）；
//       oaf.file_ready / oaf.tool_image 经 agent.subscribe(onCustomEvent) 渲染下载/图片卡片
// 线程：/agui/run/threads 列表（archived=非 uuid 旧会话，只读 D5）+ DELETE；切换/新建经
//       threadId prop + key 重挂载保证 CopilotKit 干净状态（服务端记忆按 threadId 完整恢复；
//       页面状态在 Provider 外层，重挂载不丢）
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CopilotKit, CopilotChat, useAgent, useInterrupt } from "@copilotkit/react-core/v2";

const AGENT_BASE = "/agent/release-agent";
const AGUI_BASE = `${AGENT_BASE}/agui/run`;
const AGENT_ID = "release-agent";

type FileCard = {
  file_id: string; file_name: string; mime_type: string; size: number;
  download_url?: string; media_type?: string; data?: string; url?: string; toolCallId?: string;
};
type AttachItem = { fileId: string; name: string; mime: string; size: number };
type ThreadItem = { id: string; updatedAt: string; userId: string; archived: boolean };
type PendingInterrupt = {
  id: string; message: string; toolCallId: string | null;
  metadata: Record<string, unknown> | null;
};

// HTTP 非安全上下文无 crypto.randomUUID，用时间戳+随机串兜底
const uid = () =>
  typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

export default function AssistantPage() {
  const [threadId, setThreadId] = useState(() => uid());
  const [attachments, setAttachments] = useState<AttachItem[]>([]);
  const [uploading, setUploading] = useState(false);
  const [fileCards, setFileCards] = useState<FileCard[]>([]);
  const [threads, setThreads] = useState<ThreadItem[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [notice, setNotice] = useState("");
  const [pendingManual, setPendingManual] = useState<PendingInterrupt[]>([]);
  const [manualBusy, setManualBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // ===== 历史会话列表 =====
  const loadThreads = useCallback(async () => {
    try {
      const resp = await fetch(`${AGENT_BASE}/agui/run/threads?agentId=${AGENT_ID}&limit=20`);
      if (!resp.ok) return;
      const data = await resp.json();
      const items = (data.threads ?? []).map((t: any) => ({
        id: String(t.id ?? ""),
        updatedAt: String(t.updatedAt ?? "").replace("T", " ").slice(0, 19),
        userId: String(t.metadata?.userId ?? ""),
        archived: Boolean(t.metadata?.archived),
      }));
      setThreads(items);
    } catch { /* 列表加载失败静默 */ }
  }, []);

  const openHistory = useCallback(async () => {
    setShowHistory((v) => !v);
    if (!showHistory) await loadThreads();
  }, [loadThreads, showHistory]);

  const newThread = useCallback(() => {
    setAttachments([]);
    setFileCards([]);
    setPendingManual([]);
    setNotice("");
    setThreadId(uid());
  }, []);

  const deleteThread = useCallback(async (id: string) => {
    try {
      await fetch(`${AGENT_BASE}/agui/run/threads/${encodeURIComponent(id)}`, { method: "DELETE" });
      setThreads((prev) => prev.filter((t) => t.id !== id));
    } catch { /* 删除失败静默 */ }
  }, []);

  const selectThread = useCallback((t: ThreadItem) => {
    if (t.archived) {
      setNotice("旧会话只读（非 uuid 线程为旧链路会话，不可在新界面续聊）。");
      return;
    }
    setAttachments([]);
    setFileCards([]);
    setShowHistory(false);
    setThreadId(t.id);
  }, []);

  // ===== 刷新恢复（R11 兜底）：挂起 interrupt 自渲染 + 手工 resume[] =====
  useEffect(() => {
    let cancelled = false;
    setNotice("");
    setPendingManual([]);
    (async () => {
      try {
        const resp = await fetch(
          `${AGENT_BASE}/agui/run/threads/${encodeURIComponent(threadId)}/messages`);
        if (!resp.ok) return;
        const data = await resp.json();
        if (cancelled) return;
        if (Array.isArray(data.pendingInterrupts) && data.pendingInterrupts.length > 0) {
          setPendingManual(data.pendingInterrupts as PendingInterrupt[]);
        }
      } catch { /* 恢复探测失败静默 */ }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // ===== 附件上传（fileIds 走 forwardedProps 注入工作区）=====
  const uploadFile = useCallback(async (file: File) => {
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append("file", file);
      fd.append("userId", "webui");
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
      setNotice(`⚠️ ${e.message}`);
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }, []);

  const properties = useMemo(() => ({
    userId: "webui",
    fileIds: attachments.map((a) => a.fileId),
  }), [attachments]);

  return (
    <div data-testid="assistant-page" className="flex flex-col h-[calc(100vh-8rem)]">
      {/* CopilotKit v2 预编译样式（Tailwind4 产物，经 public 静态加载绕过本工程 postcss/Tailwind3 管线） */}
      <link rel="stylesheet" href="/copilotkit.css" />
      <div className="flex items-center justify-between mb-2">
        <h1 className="text-xl font-semibold">发布助手</h1>
        <div className="flex gap-2">
          <button onClick={openHistory} data-testid="history-btn"
            className="text-xs px-2 py-1 border rounded hover:bg-gray-100">历史会话</button>
          <button onClick={newThread} data-testid="new-session"
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
                <li key={`${t.userId}:${t.id}`}>
                  <button onClick={() => selectThread(t)}
                    data-testid="history-item"
                    className={`w-full text-left text-xs px-2 py-1.5 rounded hover:bg-blue-50 ${threadId === t.id ? "bg-blue-100 font-medium" : ""}`}>
                    <span className="font-mono">{t.id.slice(0, 18)}{t.id.length > 18 ? "…" : ""}</span>
                    {t.archived && <span className="ml-1 text-[10px] text-gray-400">只读</span>}
                    <span className="float-right text-gray-400">{t.updatedAt}</span>
                    <span data-testid="history-delete"
                      className="float-right text-red-400 hover:text-red-600 px-1"
                      onClick={(e) => { e.stopPropagation(); deleteThread(t.id); }}>✕</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {notice && (
        <div className="mb-2 text-xs text-amber-700 bg-amber-50 border border-amber-100 rounded p-2">
          {notice}
        </div>
      )}

      {/* 挂起 HITL 恢复卡片（R11 刷新兜底，绕过 useInterrupt 手工 resume[]） */}
      {pendingManual.length > 0 && (
        <ManualConfirmCard interrupts={pendingManual} threadId={threadId} busy={manualBusy}
          onDone={(err) => {
            setManualBusy(false);
            setPendingManual([]);
            if (err) setNotice(`⚠️ ${err}`);
          }}
          onBusy={() => setManualBusy(true)} />
      )}

      {/* Agent 产出文件卡片（oaf.file_ready / oaf.tool_image） */}
      {fileCards.length > 0 && (
        <div className="mb-2 space-y-2">
          {fileCards.map((f, i) => <FileCardView key={`${f.file_id}-${i}`} card={f} />)}
        </div>
      )}

      {/* CopilotKit Provider：key=threadId 切换线程时整体重挂载（对话状态干净；
          服务端记忆按 threadId 完整恢复，页面状态在本组件不受影响） */}
      <CopilotKit
        key={threadId}
        runtimeUrl={AGUI_BASE}
        useSingleEndpoint={false}
        properties={properties}
      >
        <div className="flex-1 min-h-0 border rounded overflow-hidden bg-white">
          <CopilotChat
            agentId={AGENT_ID}
            threadId={threadId}
            labels={{
              chatInputPlaceholder: "例如：现在有哪些服务？/ 把 packageId=3 发布一下",
              welcomeMessageText: "我是 OAF 平台的智能发布助手。可以让我发布配置包、查询服务状态、更新环境变量、重新发布或下线服务。",
              modalHeaderTitle: "发布助手",
            }}
          />
        </div>
        <HitlCard agentId={AGENT_ID} />
        <OafEventListener agentId={AGENT_ID} onFileCard={(c) => setFileCards((prev) => [...prev, c])} />
      </CopilotKit>

      <div className="mt-3">
        {attachments.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-2">
            {attachments.map((a) => (
              <span key={a.fileId} data-testid="attach-chip"
                className="inline-flex items-center gap-1 text-xs bg-blue-50 border border-blue-200 rounded px-2 py-1">
                {a.name}
                <button onClick={() => setAttachments((prev) => prev.filter((x) => x.fileId !== a.fileId))}
                  className="text-red-500 hover:text-red-700">✕</button>
              </span>
            ))}
          </div>
        )}
        <div className="flex gap-2 items-center">
          <input ref={fileInputRef} type="file" className="hidden" data-testid="attach-input"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadFile(f); }} />
          <button onClick={() => fileInputRef.current?.click()} disabled={uploading}
            data-testid="attach-btn"
            className="border rounded px-3 py-2 text-sm hover:bg-gray-100 disabled:opacity-50">
            {uploading ? "上传中…" : "📎"}
          </button>
          <span className="text-xs text-gray-400">
            附件随下一条消息注入工作区；在 CopilotChat 输入框输入并发送
          </span>
        </div>
      </div>
    </div>
  );
}

/** HITL 确认卡片（标准 interrupts，useInterrupt 驱动 resolve({approved}) → resume[] 新 run） */
function HitlCard({ agentId }: { agentId: string }) {
  useInterrupt({
    agentId,
    render: ({ interrupt, resolve, cancel }: any) => (
      <div className="border border-yellow-300 bg-yellow-50 rounded p-3 text-sm space-y-2 m-2"
        data-testid="confirm-card">
        <p className="font-medium">⚠️ 需要你确认以下工具调用：</p>
        <pre className="text-xs bg-white border rounded p-2 overflow-auto">
          {String(interrupt?.metadata?.toolName ?? "工具")}{interrupt?.metadata?.toolInput != null
            ? `\n${JSON.stringify(interrupt.metadata.toolInput, null, 2)}` : ""}
        </pre>
        <div className="space-x-2">
          <button onClick={() => resolve({ approved: true })}
            className="px-3 py-1 bg-green-600 hover:bg-green-700 text-white text-xs rounded">批准</button>
          <button onClick={() => cancel()}
            className="px-3 py-1 border rounded text-red-600 text-xs hover:bg-red-50">拒绝</button>
        </div>
      </div>
    ),
  });
  return null;
}

/** 订阅 agent 的 oaf.* CUSTOM 事件（onCustomEvent MetaEvent）→ 文件/图片卡片回调 */
function OafEventListener({ agentId, onFileCard }: {
  agentId: string;
  onFileCard: (card: FileCard) => void;
}) {
  const { agent } = useAgent({ agentId });
  const cbRef = useRef(onFileCard);
  useEffect(() => {
    cbRef.current = onFileCard;
  }, [onFileCard]);
  useEffect(() => {
    if (!agent?.subscribe) return;
    const subscription = agent.subscribe({
      onCustomEvent: ({ event }: any) => {
        if (event?.name === "oaf.file_ready" && event.value?.file_id) {
          const v = event.value;
          cbRef.current({
            file_id: v.file_id, file_name: v.file_name, mime_type: v.mime_type,
            size: v.size, download_url: v.download_url,
          });
        } else if (event?.name === "oaf.tool_image" && event.value) {
          const v = event.value;
          cbRef.current({
            file_id: `img-${v.toolCallId ?? Date.now()}`, file_name: "tool-image",
            mime_type: v.media_type ?? "image/png", size: 0,
            media_type: v.media_type, data: v.data, url: v.url, toolCallId: v.toolCallId,
          });
        }
      },
    });
    return () => subscription.unsubscribe();
  }, [agent]);
  return null;
}

/** 刷新兜底确认卡片（pendingInterrupts 手工 resume[]，R11） */
function ManualConfirmCard({ interrupts, threadId, busy, onBusy, onDone }: {
  interrupts: PendingInterrupt[];
  threadId: string;
  busy: boolean;
  onBusy: () => void;
  onDone: (err?: string) => void;
}) {
  const decide = async (approved: boolean) => {
    onBusy();
    try {
      const resp = await fetch(AGUI_BASE, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify({
          threadId, runId: uid(), messages: [],
          resume: interrupts.map((i) => ({
            interruptId: i.id, status: approved ? "resolved" : "cancelled",
            payload: { approved },
          })),
        }),
      });
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({}));
        throw new Error(body.detail ?? `HTTP ${resp.status}`);
      }
      // 消费到流结束；RUN_ERROR 帧承载错误
      const reader = resp.body!.getReader();
      const decoder = new TextDecoder();
      let err = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value, { stream: true });
        for (const m of text.matchAll(/"type":"RUN_ERROR".*?"message":"((?:[^"\\]|\\.)*)"/g)) {
          err = m[1];
        }
      }
      onDone(err || undefined);
    } catch (e: any) {
      onDone(`恢复失败: ${e.message}`);
    }
  };
  const first = interrupts[0];
  return (
    <div className="border border-yellow-300 bg-yellow-50 rounded p-3 text-sm space-y-2"
      data-testid="confirm-card">
      <p className="font-medium">⚠️ 检测到未完成的工具确认（页面刷新前挂起）：</p>
      <pre className="text-xs bg-white border rounded p-2 overflow-auto">
        {String(first?.metadata?.toolName ?? "工具")}{first?.metadata?.toolInput != null
          ? `\n${JSON.stringify(first.metadata.toolInput, null, 2)}` : ""}
      </pre>
      <div className="space-x-2">
        <button disabled={busy} onClick={() => decide(true)}
          className="px-3 py-1 bg-green-600 hover:bg-green-700 disabled:opacity-50 text-white text-xs rounded">批准</button>
        <button disabled={busy} onClick={() => decide(false)}
          className="px-3 py-1 border rounded text-red-600 text-xs hover:bg-red-50 disabled:opacity-50">拒绝</button>
      </div>
    </div>
  );
}

/** oaf.file_ready / oaf.tool_image 渲染（下载卡片 / 内联图片） */
function FileCardView({ card }: { card: FileCard }) {
  const dataUrl = card.data ? `data:${card.media_type};base64,${card.data}` : null;
  const href = dataUrl ?? card.download_url ?? card.url;
  return (
    <div data-testid="file-card"
      className="flex items-center gap-3 border border-blue-200 bg-blue-50 rounded-lg p-2 text-sm max-w-[90%]">
      <span className="text-lg">{(card.mime_type ?? "").startsWith("image/") ? "🖼️" : "📄"}</span>
      <div className="flex-1 min-w-0">
        <p className="font-medium truncate">{card.file_name}</p>
        <p className="text-xs text-gray-500">
          {card.size > 0 ? `${(card.size / 1024).toFixed(1)} KB ` : ""}{card.mime_type}
        </p>
      </div>
      {dataUrl ? (
        <img src={dataUrl} alt={card.file_name} className="max-h-24 rounded border" />
      ) : href ? (
        <a href={href} download={card.file_name} data-testid="file-download"
          className="px-3 py-1 bg-blue-600 hover:bg-blue-700 text-white text-xs rounded">下载</a>
      ) : null}
    </div>
  );
}
