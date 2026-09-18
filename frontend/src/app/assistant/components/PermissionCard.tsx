"use client";
// HITL 确认卡片渲染：纯逻辑在 lib/confirm-card.ts（createConfirmCard/maskInput 等，node:test 覆盖）
// 视觉：琥珀警示卡 + 状态徽标；批准/拒绝为胶囊选择；语义结构（fieldset/radio/data-testid）保持不变
import { useState } from "react";
import { hasEnv, maskInput, parseInput, type ConfirmCard, type ConfirmResult } from "@/lib/confirm-card";
import { IconAlert, IconCheck, IconLoader, IconShieldCheck, IconX } from "./icons";

export type { ConfirmCard, ConfirmResult } from "@/lib/confirm-card";

function display(value: unknown): string {
  return value === undefined ? "（未提供）" : JSON.stringify(value, null, 2);
}

function ToolInput({ input }: { input: unknown }) {
  const [revealed, setRevealed] = useState(false);
  const parsed = parseInput(input);
  const visible = revealed ? parsed : maskInput(parsed);
  const fields = ["serviceId", "k8sName", "packageId", "image", "replicas"];
  return (
    <div className="space-y-2">
      {hasEnv(parsed) && (
        <p className="flex items-start gap-1.5 rounded-lg border border-red-200 bg-red-50 p-2 text-xs leading-relaxed text-red-700">
          <IconAlert className="mt-0.5 size-3.5 shrink-0" />
          env 为全量覆盖，不是增量合并：未包含的旧变量将被移除，空对象将清空全部用户环境变量。请展开并核对每一项值。
        </p>
      )}
      {parsed !== null && typeof parsed === "object" && !Array.isArray(parsed) && (
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
          {fields.map((field) => (
            <div key={field} className="contents">
              <dt className="font-mono text-gray-500">{field}</dt>
              <dd className="whitespace-pre-wrap break-all font-mono text-gray-700">{display((visible as Record<string, unknown>)[field])}</dd>
            </div>
          ))}
        </dl>
      )}
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="font-medium text-gray-600">完整输入（不修改提交参数）</span>
        <button type="button" onClick={() => setRevealed((value) => !value)} aria-pressed={revealed}
          className="rounded-lg border border-gray-200 bg-white px-2.5 py-1 text-gray-600 shadow-sm transition hover:border-blue-200 hover:text-blue-700">
          {revealed ? "遮掩敏感值" : "展开敏感值"}
        </button>
      </div>
      <pre className="whitespace-pre-wrap break-all rounded-lg border border-gray-200 bg-slate-50 p-2.5 text-[11px] leading-relaxed text-gray-700">{display(visible)}</pre>
    </div>
  );
}

// 状态徽标：pending 脉冲琥珀 / submitting 蓝色转圈 / resolved 绿色 / expired 灰色 / unknown 红色
function StatusBadge({ status }: { status: ConfirmCard["status"] }) {
  const labels = { pending: "待确认", submitting: "提交中", resolved: "已提交", expired: "已失效", unknown: "结果未知" };
  const tone = {
    pending: "border-amber-200 bg-amber-50 text-amber-700",
    submitting: "border-blue-200 bg-blue-50 text-blue-700",
    resolved: "border-emerald-200 bg-emerald-50 text-emerald-700",
    expired: "border-gray-300 bg-gray-100 text-gray-600",
    unknown: "border-red-200 bg-red-50 text-red-700",
  }[status];
  return (
    <span role="status" className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium ${tone}`}>
      {status === "submitting" && <IconLoader className="size-3 animate-spin" />}
      {(status === "pending" || status === "unknown") && <span className={`size-1.5 rounded-full ${status === "pending" ? "animate-pulse bg-amber-500" : "bg-red-500"}`} />}
      {(status === "resolved" || status === "expired") && <IconCheck className="size-3" />}
      {labels[status]}
    </span>
  );
}

export default function PermissionCard({ card, disabled, onSubmit }: {
  card: ConfirmCard;
  disabled: boolean;
  onSubmit: (results: ConfirmResult[]) => void;
}) {
  const [choices, setChoices] = useState<Record<string, boolean>>({});
  const locked = disabled || card.status !== "pending";
  const complete = card.toolCalls.length > 0 && card.toolCalls.every((tool) => typeof choices[tool.tool_call_id] === "boolean");
  return (
    <div data-testid="confirm-card" data-status={card.status}
      className="space-y-3 rounded-2xl border border-amber-200/80 bg-gradient-to-b from-amber-50/80 to-white p-4 text-sm shadow-sm">
      <div className="flex items-center justify-between gap-2">
        <p className="flex items-center gap-2 font-medium text-gray-800">
          <IconShieldCheck className="size-4 text-amber-500" />请核对工具变更参数
        </p>
        <StatusBadge status={card.status} />
      </div>
      <p className="text-xs leading-relaxed text-gray-500">每个工具须明确选择批准或拒绝，再一次性提交全部结果。未选择不会默认为批准。</p>
      {card.toolCalls.map((tool, index) => {
        const selected = card.results?.find((result) => result.tool_call_id === tool.tool_call_id)?.confirmed ?? choices[tool.tool_call_id];
        return (
          <div key={`${tool.tool_call_id}-${index}`} data-testid="confirm-tool" className="space-y-2.5 rounded-xl border border-gray-200 bg-white p-3 shadow-sm">
            <div className="flex items-center gap-2">
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-amber-400 to-orange-500 text-[11px] font-semibold text-white">{index + 1}</span>
              <p className="min-w-0 break-all font-medium text-gray-800">{tool.name}</p>
            </div>
            <p className="break-all font-mono text-[11px] text-gray-400">tool_call_id: {tool.tool_call_id || "（缺失）"}</p>
            <ToolInput input={tool.input} />
            <fieldset disabled={locked} className="disabled:opacity-60">
              <legend className="sr-only">{tool.name} 执行选择</legend>
              <div className="flex gap-2">
                {[true, false].map((approved) => {
                  const active = selected === approved;
                  return (
                    <label key={String(approved)}
                      className={`inline-flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs transition
                        has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-blue-400 has-[:focus-visible]:ring-offset-1
                        ${active
                          ? approved ? "border-emerald-300 bg-emerald-50 font-medium text-emerald-700" : "border-red-300 bg-red-50 font-medium text-red-700"
                          : "border-gray-200 text-gray-500 hover:border-gray-300 hover:text-gray-700"}`}>
                      <input type="radio" name={`${card.id}-${tool.tool_call_id}`} checked={selected === approved} className="peer sr-only"
                        onChange={() => setChoices((prev) => ({ ...prev, [tool.tool_call_id]: approved }))} />
                      {approved ? <IconCheck className="size-3" /> : <IconX className="size-3" />}
                      {approved ? "批准" : "拒绝"}
                    </label>
                  );
                })}
              </div>
            </fieldset>
          </div>
        );
      })}
      {card.status === "pending" && (
        <div className="flex flex-wrap items-center gap-2 pt-0.5">
          <button type="button" disabled={locked}
            onClick={() => setChoices(Object.fromEntries(card.toolCalls.map((tool) => [tool.tool_call_id, false])))}
            className="rounded-lg border border-red-200 bg-white px-3 py-1.5 text-xs text-red-600 shadow-sm transition hover:bg-red-50 disabled:opacity-50">全部选择拒绝（取消）</button>
          <button type="button" disabled={locked || !complete}
            onClick={() => { if (!locked && complete) onSubmit(card.toolCalls.map((tool) => ({ tool_call_id: tool.tool_call_id, confirmed: choices[tool.tool_call_id] }))); }}
            className="rounded-lg bg-gradient-to-b from-blue-500 to-blue-600 px-3.5 py-1.5 text-xs text-white shadow-sm shadow-blue-500/30 transition hover:from-blue-400 hover:to-blue-500 disabled:from-gray-300 disabled:to-gray-300 disabled:shadow-none">提交全部选择</button>
          {!complete && <span className="text-xs text-gray-400">请先逐项选择</span>}
        </div>
      )}
      {card.status === "submitting" && <p className="text-xs text-gray-500">正在提交并恢复执行，请勿重复操作。</p>}
      {card.status === "resolved" && <p className="text-xs text-gray-500">本批选择已提交；具体工具执行结果请查看后续消息。</p>}
      {card.status === "expired" && (
        <p className="flex items-start gap-1.5 rounded-lg border border-gray-200 bg-gray-50 p-2 text-xs leading-relaxed text-gray-600">
          <IconAlert className="mt-0.5 size-3.5 shrink-0 text-gray-400" />
          该确认已失效：当前没有待确认的挂起操作（可能已被处理或会话已更新），本次未执行任何变更。如需继续，请重新向助手发起该操作。
        </p>
      )}
      {card.status === "unknown" && (
        <p role="alert" className="flex items-start gap-1.5 rounded-lg border border-red-200 bg-red-50 p-2 text-xs leading-relaxed text-red-700">
          <IconAlert className="mt-0.5 size-3.5 shrink-0" />
          无法确认本批处理结果，可能已执行。已禁止重试，请刷新页面加载历史并核对执行状态，不要重复发起相同变更。
        </p>
      )}
    </div>
  );
}
