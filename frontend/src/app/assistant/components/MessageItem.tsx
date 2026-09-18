"use client";
// 单条消息渲染（事件渲染核心）：
// - 工具调用事件 → deer-flow 风格状态步骤（执行中 spinner+流光 / 成功 ✓ / 非成功终态）
// - 助手流式文本 → 头像 + Markdown + 打字光标；空内容时打字点 + 流光"思考中…"
// - file_ready / 历史产出文件 → 文件卡片（图片内联预览）；系统消息 → 语义横幅
import { memo, useState } from "react";
import Markdown from "./Markdown";
import type { ChatMsg } from "./types";
import { toolStatus } from "@/lib/assistant-events";
import { IconAlert, IconCheck, IconChevronDown, IconDownload, IconFileText, IconImage, IconLoader, IconMinus, IconShieldCheck, IconSparkles, IconX } from "./icons";

const AGENT_BASE = "/agent/release-agent";

// 工具步骤按运行时真实状态渲染：执行中/等待确认/成功/失败/被拒/中断；
// 历史回放从 AgentState 取真实状态与结果输出（DB 权威来源），无状态时中性显示
const STEP_TONE: Record<string, string> = {
  running: "border-blue-200/80 bg-blue-50/70 text-gray-700 shadow-blue-100/60",
  awaiting: "border-amber-200/80 bg-amber-50/70 text-gray-700 shadow-amber-100/60",
  success: "border-gray-200/80 bg-white text-gray-600 shadow-gray-100/60",
  error: "border-red-200/80 bg-red-50/60 text-gray-700 shadow-red-100/50",
  denied: "border-amber-200/80 bg-amber-50/60 text-gray-700 shadow-amber-100/50",
  interrupted: "border-amber-200/80 bg-amber-50/60 text-gray-700 shadow-amber-100/50",
  unknown: "border-gray-200/70 bg-gray-50/70 text-gray-500 shadow-gray-100/50",
};

const STEP_LABEL: Record<string, string> = {
  running: "执行中…", awaiting: "待确认", error: "执行失败", denied: "已被拒绝",
  interrupted: "已中断", unknown: "无执行状态",
};

function ToolStep({ msg }: { msg: ChatMsg }) {
  const status = toolStatus(msg);
  const hasOutput = !!msg.output;
  const [open, setOpen] = useState(false);
  return (
    <div className="pl-10" data-testid="tool-step" data-status={status}>
      <button type="button" disabled={!hasOutput} onClick={() => hasOutput && setOpen((v) => !v)}
        aria-expanded={hasOutput ? open : undefined}
        className={`inline-flex max-w-full items-center gap-2 rounded-full border px-3 py-1.5 text-xs shadow-sm ${STEP_TONE[status]} ${hasOutput ? "cursor-pointer transition hover:shadow" : "cursor-default"}`}>
        {status === "running" ? (
          <IconLoader className="size-3.5 shrink-0 animate-spin text-blue-500" />
        ) : status === "awaiting" ? (
          <IconShieldCheck className="size-3.5 shrink-0 text-amber-500" />
        ) : status === "success" ? (
          <IconCheck className="size-3.5 shrink-0 text-emerald-500" />
        ) : status === "error" ? (
          <IconX className="size-3.5 shrink-0 text-red-500" />
        ) : status === "denied" || status === "interrupted" ? (
          <IconAlert className="size-3.5 shrink-0 text-amber-500" />
        ) : (
          <IconMinus className="size-3.5 shrink-0 text-gray-400" />
        )}
        <span className={`truncate font-mono ${status === "running" ? "shimmer-text" : ""}`}>{msg.content}</span>
        {STEP_LABEL[status] && (
          <span className={`shrink-0 text-[11px] ${
            status === "running" ? "text-blue-500/80"
              : status === "error" ? "text-red-600"
                : status === "unknown" ? "text-gray-400"
                  : "text-amber-600"}`}>{STEP_LABEL[status]}</span>
        )}
        {hasOutput && <IconChevronDown className={`size-3 shrink-0 text-gray-400 transition-transform ${open ? "rotate-180" : ""}`} />}
      </button>
      {hasOutput && open && (
        <div className="mt-1.5 max-w-2xl rounded-lg border border-gray-200 bg-gray-50/80 p-2.5">
          <pre className="chat-scroll max-h-64 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-gray-700">{msg.output}</pre>
          {msg.output_truncated && (
            <p className="mt-1.5 border-t border-gray-200 pt-1 text-[11px] text-gray-400">
              内容已截断（完整 {msg.output_full_length?.toLocaleString() ?? "?"} 字符，可通过 AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS 调整上限）
            </p>
          )}
        </div>
      )}
    </div>
  );
}

// 系统横幅：⚠️ 开头按警示渲染，其余为中性提示
function SystemBanner({ content }: { content: string }) {
  const warn = content.includes("⚠");
  return warn ? (
    <div className="mx-auto flex max-w-xl items-start gap-2 rounded-xl border border-amber-200/80 bg-amber-50/80 px-3 py-2 text-xs leading-relaxed text-amber-800">
      <IconAlert className="mt-0.5 size-3.5 shrink-0 text-amber-500" />
      <span>{content}</span>
    </div>
  ) : (
    <div className="mx-auto max-w-xl rounded-xl border border-gray-200/80 bg-gray-50/80 px-3 py-2 text-center text-xs text-gray-500">
      {content}
    </div>
  );
}

// 思考中指示：三个错峰弹跳圆点 + 流光文字（e2e 以"思考中"判定流未结束，文案保持前缀）
function Thinking() {
  return (
    <div className="flex items-center gap-2.5 py-1">
      <span className="flex items-center gap-1">
        <span className="typing-dot size-1.5 rounded-full bg-blue-400" />
        <span className="typing-dot size-1.5 rounded-full bg-blue-400" style={{ animationDelay: "0.2s" }} />
        <span className="typing-dot size-1.5 rounded-full bg-blue-400" style={{ animationDelay: "0.4s" }} />
      </span>
      <span className="shimmer-text text-xs">思考中…</span>
    </div>
  );
}

// 产出文件卡片：图片内联预览；普通文件给下载按钮
function FileCards({ files }: { files: NonNullable<ChatMsg["files"]> }) {
  return (
    <div className="space-y-2">
      {files.map((f, i) => {
        const isImage = f.mime_type?.startsWith("image/");
        const inline = f.download_url.startsWith("data:");
        return (
          <div key={i} data-testid="file-card" className="max-w-md">
            <div className="flex items-center gap-3 rounded-xl border border-blue-100/90 bg-gradient-to-r from-blue-50/90 to-indigo-50/60 p-2.5 shadow-sm">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-lg border border-blue-100 bg-white text-blue-600">
                {isImage ? <IconImage className="size-[18px]" /> : <IconFileText className="size-[18px]" />}
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-gray-800">{f.file_name}</p>
                <p className="text-[11px] text-gray-500">
                  {f.size > 0 ? `${(f.size / 1024).toFixed(1)} KB · ` : ""}{f.mime_type}
                </p>
              </div>
              {!inline && (
                <a href={f.download_url.startsWith("/files") ? `${AGENT_BASE}${f.download_url}` : f.download_url}
                  download={f.file_name} data-testid="file-download"
                  className="inline-flex shrink-0 items-center gap-1 rounded-lg bg-blue-600 px-2.5 py-1.5 text-xs text-white shadow-sm transition hover:bg-blue-700">
                  <IconDownload className="size-3.5" />下载
                </a>
              )}
            </div>
            {inline && isImage && (
              <img src={f.download_url} alt={f.file_name} className="mt-1.5 max-h-44 rounded-xl border border-gray-200 shadow-sm" />
            )}
          </div>
        );
      })}
    </div>
  );
}

function MessageItem({ msg }: { msg: ChatMsg }) {
  if (msg.role === "tool") return <ToolStep msg={msg} />;
  if (msg.role === "system") return <SystemBanner content={msg.content} />;
  if (msg.role === "user") {
    // 结构约定：.justify-end > div 为用户气泡（e2e file-support 依赖该选择器）
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-gradient-to-br from-blue-600 to-indigo-600 px-4 py-2.5 text-sm leading-relaxed text-white shadow-md shadow-blue-500/20">
          {msg.content}
        </div>
      </div>
    );
  }
  return (
    <div className="flex gap-3">
      <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-indigo-600 text-white shadow-sm shadow-blue-500/30">
        <IconSparkles className="size-4" />
      </span>
      <div className="min-w-0 flex-1 space-y-2">
        <div data-testid="assistant-msg" className="text-sm leading-relaxed text-gray-800">
          {msg.content ? (
            <>
              <Markdown text={msg.content} />
              {msg.pending && <span className="ml-0.5 inline-block h-4 w-[2px] animate-pulse rounded bg-blue-500 align-text-bottom" />}
            </>
          ) : msg.pending ? (
            <Thinking />
          ) : null}
        </div>
        {msg.files && msg.files.length > 0 && <FileCards files={msg.files} />}
      </div>
    </div>
  );
}

export default memo(MessageItem);
