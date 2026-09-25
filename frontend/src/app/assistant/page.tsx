"use client";
// 发布助手对话：无状态单次流（POST /threads/chat，sessionId 在 body，SSE 增量渲染）
// HITL：permission_ask 渲染确认卡片 → /threads/{sessionId}/confirm-stream 恢复
// 文件：附件上传（/files/upload）→ chat 携带 fileIds；file_ready 事件渲染下载卡片
// 历史：GET /threads 列表（侧边栏常驻展示，进入页面即加载 + 发送后刷新）+ GET /threads/{sid}/history 回放；点击切换恢复上下文继续对话
// 刷新恢复：GET /threads/{sid}/status 检测 turn 状态，仍在执行/中断时 GET /subscribe 从
//     latest_event_seq 游标增量续传（durable SSE：事件服务端持久化，agent 执行与连接解耦，刷新不丢）
// UI：deer-flow 风格——工具事件渲染为时间线状态步骤、历史回放同构展示、欢迎态 Hero + 错峰建议；
//     渲染层拆分至 ./components/*（MessageItem/Markdown/PermissionCard/icons/types），本文件只保留状态与流逻辑
import { useCallback, useEffect, useRef, useState } from "react";
import { completeToolCall, markAwaitingConfirm, markDeniedWithoutResult, settlePendingToolCalls } from "@/lib/assistant-events";
import { classifyConfirmFailure, createConfirmCard, type ConfirmCard, type ConfirmResult } from "@/lib/confirm-card";
import MessageItem from "./components/MessageItem";
import PermissionCard from "./components/PermissionCard";
import type { AttachItem, ChatMsg, FileCard, ModelOption, ThreadItem } from "./components/types";
import { IconArrowUp, IconHistory, IconLoader, IconPaperclip, IconPlus, IconSparkles, IconX } from "./components/icons";

const AGENT_BASE = "/agent/release-agent";
// 初始问候语：欢迎态 Hero 直接以此作副标题，不在消息流里重复渲染
const GREETING = "我是 OAF 平台的智能发布助手。可以让我发布配置包、查询服务状态、更新环境变量、重新发布或下线服务。";
// 欢迎态快捷建议：点击直接发送
const SUGGESTIONS = ["现在有哪些服务？", "把 packageId=3 发布一下", "更新服务环境变量", "下线一个服务"];

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

// agent_state 会话 key 有两种形态：
// - 新（POST /threads/chat 直存）：裸 sessionId，UI 侧固定 "webui-" 前缀；
// - 旧（ChatUiChannel）："{peer}:gw-{hash}"，chat 端点以 peer（冒号前部分）为 sessionId。
// A2A 来源会话（带其他 vendorKey 后缀或非 webui 前缀）不展示。
function parseChatThread(sessionId: string): ThreadItem | null {
  if (sessionId.startsWith("webui-")) {
    return { peer: sessionId, fullKey: sessionId, updatedAt: "" };
  }
  const idx = sessionId.indexOf(":gw-");
  if (idx <= 0) return null;
  return { peer: sessionId.slice(0, idx), fullKey: sessionId, updatedAt: "" };
}

export default function AssistantPage() {
  const [messages, setMessages] = useState<ChatMsg[]>([{ role: "system", content: GREETING }]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const confirmInFlight = useRef(false);
  // 刷新续传的订阅句柄（AbortController）：卸载时中断订阅
  const resumeRef = useRef<AbortController | null>(null);
  const [attachments, setAttachments] = useState<AttachItem[]>([]);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const sessionId = useRef<string>("");

  // 历史会话：GET /threads 列表（ChatUiChannel 来源，最近 20 条），侧边栏常驻展示；refreshing 控制刷新按钮转动
  const [threads, setThreads] = useState<ThreadItem[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(true);
  const sessionLoading = useRef(true);
  // 会话模型：GET /models 可选列表（404 → 隐藏 picker）；modelId 为空串或 "system" 表示默认模型
  const [models, setModels] = useState<ModelOption[]>([]);
  const [modelId, setModelId] = useState<string>("system");
  // 存在未处理（pending/unknown）确认卡时锁定发送，避免新请求覆盖运行时未消费的确认上下文
  const awaitingConfirm = messages.some((m) => m.confirm && (m.confirm.status === "pending" || m.confirm.status === "unknown"));
  const sessionLocked = busy || historyLoading || uploading;
  // 欢迎态：只有系统提示、没有实际对话内容时展示 Hero + 快捷建议
  const welcome = !historyLoading && messages.every((m) => m.role === "system");

  const loadThreads = useCallback(async () => {
    try {
      const resp = await fetch(`${AGENT_BASE}/threads`);
      if (!resp.ok) return;
      const list: any[] = await resp.json();
      const items = list
        .map((t) => {
          const p = parseChatThread(t.session_id ?? "");
          return p ? {
            ...p,
            updatedAt: (t.updated_at ?? "").replace("T", " ").slice(0, 19),
            title: (t.title ?? "").trim(),
            model: t.model ?? "",
          } : null;
        })
        .filter(Boolean) as ThreadItem[];
      setThreads(items.slice(0, 20));
    } catch { /* 列表加载失败静默（面板显示空态） */ }
  }, []);

  /** 可选模型列表（GET /models；老后端无此端点时 404，picker 不渲染，行为与升级前一致） */
  const loadModels = useCallback(async () => {
    try {
      const resp = await fetch(`${AGENT_BASE}/models`);
      if (!resp.ok) return;
      const data = await resp.json();
      setModels(Array.isArray(data?.models) ? data.models : []);
    } catch { /* 模型列表加载失败静默（picker 不渲染，走默认模型） */ }
  }, []);

  // 侧边栏会话列表 + 模型列表：进入页面即加载
  useEffect(() => { loadThreads(); }, [loadThreads]);
  useEffect(() => { loadModels(); }, [loadModels]);

  /** 历史消息回放：GET /threads/{fullKey}/history → ChatMsg[]（含未消费 HITL 卡片重建 + 产出文件卡片），渲染与实时流同构 */
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
            // 工具调用步骤渲染在 assistant 文本之前（与实时流一致：步骤在上、答案在下）。
            // state 与 output 来自 AgentState（DB 权威来源，官方 SDK 自动持久化）：
            // 含 HITL 批准后的恢复段；无 state 的调用（未批准）渲染中性态，不臆造成败
            for (const tc of (m.tool_calls ?? [])) {
              msgs.push({
                role: "tool", content: `${tc.name}`, pending: false,
                // 工具调用 id：刷新续传时实时 TOOL_RESULT_END 据此认领对应步骤写入终态
                ...(tc.id ? { toolCallId: String(tc.id) } : {}),
                ...(tc.state ? { state: String(tc.state) } : {}),
                ...(tc.output ? { output: String(tc.output) } : {}),
                ...(tc.output_truncated ? {
                  output_truncated: true,
                  output_full_length: Number(tc.output_full_length) || 0,
                } : {}),
              });
            }
            if (m.content) msgs.push({ role: "assistant", content: m.content });
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
            msgs.push({ role: "assistant", content: "", confirm: createConfirmCard(data.pendingConfirm.tools) });
          } catch { /* 工具格式异常忽略 */ }
        }
      }
    } catch { /* 回放失败按空会话处理（上下文仍在后端，不影响继续对话） */ }
    return msgs;
  }, []);

  // 文本增量必须落到「最后一条 assistant 气泡」——工具状态行固定插在其前（步骤在上、答案在下）
  const updateLastAssistant = useCallback((fn: (m: ChatMsg) => ChatMsg) => {
    setMessages((prev) => {
      const next = [...prev];
      for (let i = next.length - 1; i >= 0; i--) {
        if (next[i].role === "assistant") { next[i] = fn(next[i]); break; }
      }
      return next;
    });
  }, []);

  /** SSE 事件处理器：文本增量节流 + 事件分发 + 收尾（对话流与刷新续传统一语义） */
  const createStreamProcessor = useCallback((onAsk: (card: ConfirmCard) => void) => {
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
          // 工具步骤插到最后一条 assistant 气泡之前（步骤在上、答案在下），同一轮的多个步骤按时间顺序堆叠。
          // 幂等：同一 toolCallId 已存在（刷新续传/恢复流重放）时只更新 pending，不重复插入
          setMessages((prev) => {
            const dup = prev.findIndex((m) => m.role === "tool" && m.toolCallId === ev.toolCallId);
            if (dup >= 0) {
              // 已有终态的步骤不被重放事件复活（只有确无结果时才回到执行中）
              if (prev[dup].state) return prev;
              const next = [...prev];
              next[dup] = { ...next[dup], pending: true };
              return next;
            }
            const next = [...prev];
            let idx = next.length;
            for (let i = next.length - 1; i >= 0; i--) {
              if (next[i].role === "assistant") { idx = i; break; }
            }
            // 工具名兜底：摘要帧（tool_call_summary）通常晚一帧到达，用它把「write_file」换成
            // 「创建 output/create-ai-ppt.js 408行」这类具体在干嘛的描述
            next.splice(idx, 0, { role: "tool", content: `${ev.toolName}`, toolCallId: ev.toolCallId, pending: true });
            return next;
          });
          break;
        case "tool_call_summary": {
          // 后端已把 delta 参数拼好并解析出关键字段，前端直接展示即可（无需自己解析 JSON）
          const sid = ev.toolCallId;
          const label = typeof ev.summary === "string" && ev.summary ? ev.summary : null;
          if (!sid || !label) break;
          setMessages((prev) => prev.map((m) =>
            m.role === "tool" && m.toolCallId === sid ? { ...m, content: label } : m));
          break;
        }
        case "tool_result_preview": {
          // 「输出 …」：结果首行（失败时为终态文案），挂到对应工具步骤上可展开查看
          const pid = ev.toolCallId;
          const preview = typeof ev.preview === "string" && ev.preview ? ev.preview : null;
          if (!pid || !preview) break;
          setMessages((prev) => prev.map((m) =>
            m.role === "tool" && m.toolCallId === pid && !m.output ? { ...m, output: preview } : m));
          break;
        }
        case "TOOL_RESULT_END": {
          setMessages((prev) => completeToolCall(prev, ev));
          break;
        }
        case "permission_ask":
          if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
          flushDelta();
          updateLastAssistant((m) => ({ ...m, pending: false }));
          // 该批工具进入等待人工确认：不再是「执行中」（没在执行，在等人）
          setMessages((prev) => markAwaitingConfirm(prev,
            (Array.isArray(ev.tool_calls) ? ev.tool_calls : []).map((tc: any) => tc?.tool_call_id).filter(Boolean)));
          onAsk(createConfirmCard(ev.tool_calls));
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
          throw new Error(ev.error || "执行流返回错误");
        case "interrupted":
          throw new Error(ev.reason || "执行流已中断");
        // AGENT_END / done 等其余事件忽略（流关闭即终态）
      }
    };

    /** 流段收尾：刷出节流增量 + 仍在转圈的步骤兜底收尾（用户拒绝/流中断/异常均无 TOOL_RESULT_END） */
    const finish = () => {
      if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
      flushDelta();
      setMessages((prev) => settlePendingToolCalls(prev));
    };

    return { handlePayload, finish };
  }, [updateLastAssistant]);

  /** 读取 SSE 响应体并逐帧分发（POST /threads/chat 与 GET /subscribe 续传共用） */
  const readSseResponse = useCallback(async (resp: Response, handlePayload: (line: string) => void) => {
    if (!resp.body) throw new Error("空响应体");
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const frames = buf.split(/\r?\n\r?\n/);
        buf = frames.pop() ?? "";
        for (const frame of frames) {
          for (const line of frame.split(/\r?\n/)) {
            if (line.startsWith("data:")) handlePayload(line.slice(5));
          }
        }
      }
    } finally {
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
  }, []);

  /** 消费一次 SSE 单次流（对话主入口，POST） */
  const consumeStream = useCallback(async (url: string, body: object, onAsk: (card: ConfirmCard) => void) => {
    const resp = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify(body),
    });
    if (!resp.ok || !resp.body) {
      throw new Error(`HTTP ${resp.status}`);
    }
    const processor = createStreamProcessor(onAsk);
    try {
      await readSseResponse(resp, processor.handlePayload);
    } finally {
      processor.finish();
    }
  }, [createStreamProcessor, readSseResponse]);

  /**
   * 刷新/切换会话恢复：turn 仍在执行时订阅 GET /subscribe 续传（durable SSE）。
   * afterSeq 取 /status 的 latest_event_seq——seq 单调递增，之后产生的事件必然 > 该值，
   * 状态查询与订阅建立之间的空窗不会漏事件；replyId 过滤本轮 turn，避免误吞并发新 turn 的事件。
   */
  const resumeTurn = useCallback(async (sid: string) => {
    if (resumeRef.current) return;
    let status: any;
    try {
      const resp = await fetch(`${AGENT_BASE}/threads/${encodeURIComponent(sid)}/status`);
      if (!resp.ok) return; // 状态查询失败（如事件存储暂不可用 503）：跳过恢复，不重置任何状态
      status = await resp.json();
    } catch { return; }
    // interrupted 也订阅：服务端回放已落库事件后补发 interrupted 帧收流，由下方渲染为中断提示
    if (!status || (status.state !== "working" && status.state !== "interrupted")) return;

    setBusy(true);
    // 落一个流式气泡承接续传增量（历史回放不含未完成消息；也让欢迎态切回对话态）
    setMessages((prev) => [...prev, { role: "assistant", content: "", pending: true }]);
    const controller = new AbortController();
    resumeRef.current = controller;
    const processor = createStreamProcessor((card) => {
      setMessages((prev) => [...prev, { role: "assistant", content: "", confirm: card }]);
    });
    try {
      const afterSeq = Number(status.latest_event_seq) || 0;
      const reply = status.reply_id ? `&replyId=${encodeURIComponent(status.reply_id)}` : "";
      const resp = await fetch(
        `${AGENT_BASE}/threads/${encodeURIComponent(sid)}/subscribe?afterSeq=${afterSeq}${reply}`,
        { headers: { Accept: "text/event-stream" }, signal: controller.signal });
      if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`);
      await readSseResponse(resp, processor.handlePayload);
    } catch (e: any) {
      if (!controller.signal.aborted) { // 会话切换/页面卸载的中断不提示
        const msg = String(e?.message ?? e);
        setMessages((prev) => [...prev, { role: "system", content: /interrupt/i.test(msg)
          ? "⚠️ 上次执行已中断，可重新发送消息继续。"
          : `⚠️ 任务续传连接中断: ${msg}` }]);
      }
    } finally {
      processor.finish();
      resumeRef.current = null;
      updateLastAssistant((m) => ({ ...m, pending: false }));
      setBusy(false);
      loadThreads();
    }
  }, [createStreamProcessor, readSseResponse, updateLastAssistant, loadThreads]);

  /** 切换到历史会话：更新本地会话 id → 回放历史消息（上下文由后端 checkpoint 自动恢复） */
  const selectSession = useCallback(async (item: ThreadItem) => {
    if (sessionLocked || sessionLoading.current || confirmInFlight.current) return;
    sessionLoading.current = true;
    setAttachments([]);
    setInput("");
    window.localStorage.setItem("oaf-assistant-sid", item.peer);
    sessionId.current = item.peer;
    // 回显该会话绑定的模型（无绑定/已被删除 → 系统默认）
    setModelId(item.model && item.model.trim() ? item.model : "system");
    setHistoryLoading(true);
    const msgs = await loadHistory(item.fullKey);
    setMessages(msgs.length > 0
      ? msgs
      : [{ role: "system", content: "该会话暂无可展示的历史消息，可直接继续对话。" }]);
    sessionLoading.current = false;
    setHistoryLoading(false);
    loadThreads(); // 切换后刷新列表（时间戳排序变化 + 当前会话高亮）
    resumeTurn(item.peer); // 目标会话若仍在执行，与刷新恢复同一链路续传
  }, [sessionLocked, loadHistory, loadThreads, resumeTurn]);

  /** 回显当前会话已绑定的模型（GET /threads/{sid}.model；未绑定 → system 默认） */
  const syncSessionModel = useCallback(async (sid: string) => {
    try {
      const resp = await fetch(`${AGENT_BASE}/threads/${encodeURIComponent(sid)}`);
      if (!resp.ok) return;
      const data = await resp.json();
      setModelId(data?.model && String(data.model).trim() ? String(data.model) : "system");
    } catch { /* 回显失败保持当前选择 */ }
  }, []);

  useEffect(() => {
    const sid = getSessionId();
    sessionId.current = sid;
    let cancelled = false;
    setHistoryLoading(true);
    loadHistory(sid).then((msgs) => {
      if (!cancelled && msgs.length) setMessages(msgs);
    }).finally(() => {
      if (!cancelled) {
        sessionLoading.current = false;
        setHistoryLoading(false);
        // 历史先落地再续传：loadHistory 的返回会整体 setMessages，先续传会被覆盖
        resumeTurn(sid);
      }
    });
    syncSessionModel(sid);
    return () => { cancelled = true; resumeRef.current?.abort(); };
  }, [loadHistory, resumeTurn, syncSessionModel]);
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);
  // 输入框随内容自动增高（上限 160px）
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [input]);

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

  /** 确认/拒绝后恢复执行（新执行段续流） */
  const sendConfirm = useCallback(async (card: ConfirmCard, results: ConfirmResult[]) => {
    if (sessionLocked || sessionLoading.current || confirmInFlight.current || card.status !== "pending") return;
    confirmInFlight.current = true;
    setBusy(true);
    const mark = (status: ConfirmCard["status"]) => setMessages((prev) => prev.map((m) =>
      m.confirm?.id === card.id ? { ...m, confirm: { ...m.confirm, status, results } } : m));
    mark("submitting");
    // 被拒绝的工具不会有 TOOL_RESULT_END：先按用户选择收尾，避免步骤永久停在「待确认」
    const rejectedIds = results.filter((r) => !r.confirmed).map((r) => r.tool_call_id);
    if (rejectedIds.length > 0) {
      setMessages((prev) => markDeniedWithoutResult(prev, rejectedIds));
    }
    try {
      setMessages((prev) => [...prev, { role: "assistant", content: "", pending: true }]);
      await consumeStream(`${AGENT_BASE}/threads/${encodeURIComponent(sessionId.current)}/confirm-stream`, { results }, (nextCard) => {
        setMessages((prev) => [...prev, { role: "assistant", content: "", confirm: nextCard }]);
      });
      mark("resolved");
    } catch (e: any) {
      // 404 confirm_context_not_found = 上下文已过期/不存在，工具从未获批准 → 未执行，可安全重新发起；
      // 其余（409 已消费、网络中断）无法排除已执行 → unknown，禁重试，交由历史核实
      mark(classifyConfirmFailure(e.message));
      setMessages((prev) => [...prev, { role: "system", content: `⚠️ ${e.message}` }]);
    } finally {
      updateLastAssistant((m) => ({ ...m, pending: false }));
      confirmInFlight.current = false;
      setBusy(false);
    }
  }, [sessionLocked, consumeStream, updateLastAssistant]);

  const send = useCallback(async (overrideText?: string) => {
    const text = (overrideText ?? input).trim();
    const fileIds = attachments.map((a) => a.fileId);
    if ((!text && fileIds.length === 0) || sessionLocked || awaitingConfirm || sessionLoading.current || confirmInFlight.current) return;
    setInput("");
    setBusy(true);
    const attachNames = attachments.map((a) => a.name);
    // 保留历史轮次的工具步骤（含 state/output，历史权威来源）；
    // 仅对流式残留的 pending 行兜底收尾（正常路径由 consumeStream 的 finally settle 覆盖）
    setMessages((prev) => [
      ...settlePendingToolCalls(prev),
      { role: "user", content: text + (attachNames.length ? `\n[附件: ${attachNames.join(", ")}]` : "") },
      { role: "assistant", content: "", pending: true },
    ]);
    setAttachments([]);
    try {
      await consumeStream(`${AGENT_BASE}/threads/chat`,
        // model 随消息下发：字段缺省不改变会话绑定，传值即切换（本 turn 生效并持久化）；"system" = 回默认模型
        { message: text, userId: "webui", sessionId: sessionId.current, fileIds, model: modelId }, (card) => {
          setMessages((prev) => [...prev, { role: "assistant", content: "", confirm: card }]);
        });
    } catch (e: any) {
      setMessages((prev) => [...prev, { role: "system", content: `⚠️ 连接中断: ${e.message}` }]);
    } finally {
      updateLastAssistant((m) => ({ ...m, pending: false }));
      setBusy(false);
      loadThreads(); // 新会话首轮对话后进入历史列表
    }
  }, [sessionLocked, awaitingConfirm, consumeStream, input, attachments, updateLastAssistant, loadThreads, modelId]);

  const resetSession = () => {
    if (sessionLocked || sessionLoading.current || confirmInFlight.current) return;
    setAttachments([]);
    setInput("");
    const sid = `webui-${uid()}`;
    window.localStorage.setItem("oaf-assistant-sid", sid);
    sessionId.current = sid;
    setMessages([{ role: "system", content: "已开启新会话。" }]);
  };

  // 欢迎态下除问候语外的系统提示（新会话/上传失败等）仍需展示，避免信息丢失
  const welcomeNotices = messages.filter((m) => m.role === "system" && m.content !== GREETING);

  // 底部输入卡：毛玻璃圆角卡 + 左附件/右发送（发送键随状态变形：箭头 ⇄ 转圈）
  const inputCard = (
    <div className="rounded-2xl border border-gray-200 bg-white/90 shadow-sm shadow-gray-200/60 backdrop-blur transition-all focus-within:border-blue-300 focus-within:shadow-md focus-within:shadow-blue-100/80">
      {attachments.length > 0 && (
        <div className="flex flex-wrap gap-2 px-3 pt-3">
          {attachments.map((a) => (
            <span key={a.fileId} data-testid="attach-chip"
              className="inline-flex max-w-full items-center gap-1.5 rounded-lg border border-blue-200 bg-blue-50 px-2 py-1 text-xs text-blue-700">
              <span className="max-w-[160px] truncate">{a.name}</span>
              <button onClick={() => removeAttachment(a.fileId)} aria-label="移除附件" className="text-blue-400 transition hover:text-red-500">
                <IconX className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}
      <textarea ref={inputRef} value={input} onChange={(e) => setInput(e.target.value)} rows={1}
        onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
        placeholder={awaitingConfirm
          ? "请先在上方确认卡片完成批准/拒绝，再继续对话"
          : "例如：现在有哪些服务？/ 把 packageId=3 发布一下 / 附件点左下角 📎"}
        data-testid="chat-input" disabled={sessionLocked}
        className="max-h-40 w-full resize-none bg-transparent px-4 pb-1 pt-3.5 text-sm text-gray-800 outline-none placeholder:text-gray-400 disabled:opacity-50" />
      <div className="flex items-center gap-1 px-2 pb-2">
        <input ref={fileInputRef} type="file" className="hidden"
          data-testid="attach-input"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadFile(f); }} />
        <button onClick={() => fileInputRef.current?.click()} disabled={sessionLocked || awaitingConfirm}
          data-testid="attach-btn" title="上传附件"
          className="flex size-9 items-center justify-center rounded-full text-gray-400 transition hover:bg-gray-100 hover:text-blue-600 disabled:opacity-50">
          {uploading ? <IconLoader className="size-4 animate-spin" /> : <IconPaperclip className="size-4" />}
        </button>
        {models.length > 0 && (
          <select value={modelId} onChange={(e) => setModelId(e.target.value)}
            data-testid="model-select" disabled={sessionLocked}
            title="会话模型（下一条消息生效并绑定到本会话）"
            className="ml-1 max-w-[150px] truncate rounded-full border border-gray-200 bg-white/80 py-1 pl-2 pr-6 text-[11px] text-gray-500 outline-none transition hover:border-blue-300 focus:border-blue-300 disabled:opacity-50">
            {models.map((m) => (
              <option key={m.id} value={m.id}>{m.name || m.id}</option>
            ))}
          </select>
        )}
        <span className="ml-1 hidden select-none text-[11px] text-gray-300 sm:block">Enter 发送 · Shift+Enter 换行 · 附件点 📎</span>
        <button onClick={() => send()} disabled={sessionLocked || awaitingConfirm || (!input.trim() && attachments.length === 0)}
          data-testid="chat-send" aria-label="发送"
          className="ml-auto flex size-9 items-center justify-center rounded-full bg-gradient-to-b from-blue-500 to-blue-600 text-white shadow-md shadow-blue-500/30 transition hover:from-blue-400 hover:to-blue-500 disabled:from-gray-200 disabled:to-gray-300 disabled:shadow-none">
          {busy ? <IconLoader className="size-4 animate-spin" /> : <IconArrowUp className="size-4" />}
        </button>
      </div>
    </div>
  );

  return (
    <div data-testid="assistant-page" className="flex h-[calc(100vh-8rem)] overflow-hidden rounded-2xl border border-gray-200/70 bg-white/40 shadow-sm">
      {/* 左侧边栏：品牌 / 新会话 / 历史会话（deer-flow 工作区布局，会话列表常驻） */}
      <aside className="flex w-60 shrink-0 flex-col border-r border-gray-200/70 bg-white/70 backdrop-blur">
        <div className="flex items-center gap-2.5 px-4 pb-1 pt-4">
          <span className="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-600 to-indigo-600 text-white shadow-md shadow-blue-500/25">
            <IconSparkles className="size-[18px]" />
          </span>
          <div className="min-w-0">
            <h1 className="text-sm font-semibold tracking-tight text-gray-900">发布助手</h1>
            <p className="truncate text-[11px] text-gray-400">对话式驱动发布全流程</p>
          </div>
        </div>
        <div className="px-3 pt-3">
          <button onClick={resetSession} disabled={sessionLocked} data-testid="new-session"
            className="flex w-full items-center justify-center gap-2 rounded-xl border border-gray-200 bg-white px-3 py-2 text-xs font-medium text-gray-700 shadow-sm transition hover:border-blue-200 hover:text-blue-700 disabled:opacity-50">
            <IconPlus className="size-3.5" />新会话
          </button>
        </div>
        <div className="mt-5 flex items-center justify-between px-4">
          <span className="text-xs font-medium tracking-wide text-gray-400">历史会话</span>
          <button onClick={async () => { setRefreshing(true); await loadThreads(); setRefreshing(false); }}
            data-testid="history-btn" title="刷新历史会话"
            className="rounded-md p-1 text-gray-400 transition hover:bg-gray-100 hover:text-blue-600">
            <IconHistory className={`size-3.5 ${refreshing ? "animate-spin" : ""}`} />
          </button>
        </div>
        <div data-testid="history-panel" className="chat-scroll mt-1 flex-1 space-y-0.5 overflow-y-auto px-2 pb-3">
          {threads.length === 0 ? (
            <p className="px-2 py-6 text-center text-[11px] leading-relaxed text-gray-400">暂无历史会话（发送消息后自动记录，保留 7 天）</p>
          ) : (
            <ul className="space-y-0.5">
              {threads.map((t) => (
                <li key={t.fullKey}>
                  <button onClick={() => selectSession(t)} disabled={sessionLocked}
                    data-testid="history-item"
                    className={`w-full rounded-lg px-2.5 py-2 text-left transition disabled:opacity-50 ${
                      sessionId.current === t.peer ? "bg-blue-50 ring-1 ring-blue-100" : "hover:bg-gray-100/70"}`}>
                    <span className={`block truncate text-xs ${sessionId.current === t.peer ? "font-medium text-blue-700" : "text-gray-600"}`}>
                      {t.title || t.peer}
                    </span>
                    <span className="mt-0.5 block truncate text-[11px] text-gray-400">
                      {t.updatedAt || "—"}{t.model ? ` · ${t.model}` : ""}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>

      {/* 主区：细头部（当前会话）→ 消息流 / 欢迎态 → 输入区 */}
      <div className="relative flex min-w-0 flex-1 flex-col">
        {/* 背景光斑（纯装饰，置于内容层之下） */}
        <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0 h-80 overflow-hidden">
          <div className="absolute -top-20 left-[15%] h-56 w-56 rounded-full bg-blue-200/30 blur-3xl" />
          <div className="absolute -top-8 right-[10%] h-48 w-48 rounded-full bg-indigo-200/25 blur-3xl" />
        </div>

        {!historyLoading && !welcome && (
          <div className="relative flex items-center gap-2 border-b border-gray-100/80 px-5 py-2.5">
            <span className="size-1.5 shrink-0 rounded-full bg-emerald-400" />
            <span className="truncate font-mono text-xs text-gray-400">{sessionId.current}</span>
            {busy && <span className="ml-auto shrink-0 text-[11px] text-blue-500">处理中…</span>}
          </div>
        )}

        {historyLoading ? (
          <div className="flex flex-1 items-center justify-center">
            <IconLoader className="size-5 animate-spin text-gray-300" />
          </div>
        ) : welcome ? (
          /* 欢迎态：Hero + 错峰入场建议 + 输入卡（deer-flow 欢迎模式） */
          <div className="relative flex flex-1 flex-col items-center justify-center px-4 py-6">
            <span className="animate-fade-in-up flex size-14 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-600 to-indigo-600 text-white shadow-xl shadow-blue-500/30">
              <IconSparkles className="size-7" />
            </span>
            <h2 className="animate-fade-in-up mt-5 text-3xl font-bold tracking-tight" style={{ animationDelay: "60ms" }}>
              <span className="aurora-text">发布助手</span>
            </h2>
            <p className="animate-fade-in-up mt-3 max-w-md text-center text-sm leading-relaxed text-gray-500" style={{ animationDelay: "120ms" }}>
              {GREETING}
            </p>
            <div className="mt-8 flex max-w-2xl flex-wrap justify-center gap-2">
              {SUGGESTIONS.map((s, i) => (
                <button key={s} onClick={() => send(s)} disabled={sessionLocked || awaitingConfirm}
                  style={{ animationDelay: `${180 + i * 70}ms` }}
                  className="animate-fade-in-up rounded-full border border-gray-200 bg-white/80 px-4 py-2 text-xs text-gray-600 shadow-sm backdrop-blur transition hover:-translate-y-0.5 hover:border-blue-300 hover:text-blue-700 hover:shadow-md disabled:opacity-50 disabled:hover:translate-y-0">
                  {s}
                </button>
              ))}
            </div>
            {welcomeNotices.length > 0 && (
              <div className="mt-6 w-full max-w-md space-y-2">
                {welcomeNotices.map((m, i) => <MessageItem key={i} msg={m} />)}
              </div>
            )}
            <div className="mt-8 w-full max-w-2xl">{inputCard}</div>
          </div>
        ) : (
          /* 对话态：消息流 + 底部输入卡 */
          <>
            <div ref={listRef} className="chat-scroll relative mt-1 flex-1 overflow-y-auto">
              <div className="mx-auto max-w-3xl space-y-4 px-3 pb-4 pt-3">
                {messages.map((m, i) => (
                  <div key={i} className="animate-fade-in space-y-2">
                    <MessageItem msg={m} />
                    {m.confirm && (
                      <div className="pl-10">
                        <PermissionCard card={m.confirm} disabled={sessionLocked} onSubmit={(results) => sendConfirm(m.confirm!, results)} />
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
            <div className="relative shrink-0 px-3 pb-3">
              <div className="mx-auto max-w-3xl">{inputCard}</div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
