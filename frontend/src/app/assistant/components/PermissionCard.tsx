"use client";

import { useState } from "react";
import { hasEnv, maskInput, parseInput, type ConfirmCard, type ConfirmResult } from "@/lib/confirm-card";

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
        <p className="text-xs text-red-700 bg-red-50 border border-red-200 rounded p-2">
          env 为全量覆盖，不是增量合并：未包含的旧变量将被移除，空对象将清空全部用户环境变量。请展开并核对每一项值。
        </p>
      )}
      {parsed !== null && typeof parsed === "object" && !Array.isArray(parsed) && (
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
          {fields.map((field) => (
            <div key={field} className="contents">
              <dt className="font-mono text-gray-600">{field}</dt>
              <dd className="font-mono whitespace-pre-wrap break-all">{display((visible as Record<string, unknown>)[field])}</dd>
            </div>
          ))}
        </dl>
      )}
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="font-medium">完整输入（不修改提交参数）</span>
        <button type="button" onClick={() => setRevealed((value) => !value)} aria-pressed={revealed}
          className="px-2 py-1 border rounded hover:bg-gray-100">
          {revealed ? "遮掩敏感值" : "展开敏感值"}
        </button>
      </div>
      <pre className="text-xs bg-gray-50 border rounded p-2 whitespace-pre-wrap break-all">{display(visible)}</pre>
    </div>
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
  const labels = { pending: "待确认", submitting: "提交中", resolved: "已提交", unknown: "结果未知" };
  return (
    <div className="border border-yellow-300 bg-yellow-50 rounded p-3 text-sm space-y-3" data-testid="confirm-card" data-status={card.status}>
      <div className="flex justify-between gap-2 font-medium">
        <p>请核对工具变更参数</p><span role="status">{labels[card.status]}</span>
      </div>
      <p className="text-xs text-gray-600">每个工具须明确选择批准或拒绝，再一次性提交全部结果。未选择不会默认为批准。</p>
      {card.toolCalls.map((tool, index) => {
        const selected = card.results?.find((result) => result.tool_call_id === tool.tool_call_id)?.confirmed ?? choices[tool.tool_call_id];
        return (
          <div key={`${tool.tool_call_id}-${index}`} className="bg-white border rounded p-3 space-y-2" data-testid="confirm-tool">
            <p className="font-medium break-all">{index + 1}. {tool.name}</p>
            <p className="text-xs text-gray-500 font-mono break-all">tool_call_id: {tool.tool_call_id || "（缺失）"}</p>
            <ToolInput input={tool.input} />
            <fieldset disabled={locked} className="flex gap-4 text-xs disabled:opacity-60">
              <legend className="sr-only">{tool.name} 执行选择</legend>
              {[true, false].map((approved) => (
                <label key={String(approved)} className="inline-flex items-center gap-1">
                  <input type="radio" name={`${card.id}-${tool.tool_call_id}`} checked={selected === approved}
                    onChange={() => setChoices((prev) => ({ ...prev, [tool.tool_call_id]: approved }))} />
                  {approved ? "批准" : "拒绝"}
                </label>
              ))}
            </fieldset>
          </div>
        );
      })}
      {card.status === "pending" && (
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" disabled={locked}
            onClick={() => setChoices(Object.fromEntries(card.toolCalls.map((tool) => [tool.tool_call_id, false])))}
            className="px-3 py-1 border rounded text-red-600 text-xs hover:bg-red-50 disabled:opacity-50">全部选择拒绝（取消）</button>
          <button type="button" disabled={locked || !complete}
            onClick={() => { if (!locked && complete) onSubmit(card.toolCalls.map((tool) => ({ tool_call_id: tool.tool_call_id, confirmed: choices[tool.tool_call_id] }))); }}
            className="px-3 py-1 bg-blue-600 hover:bg-blue-700 text-white text-xs rounded disabled:opacity-50">提交全部选择</button>
          {!complete && <span className="text-xs text-gray-500">请先逐项选择</span>}
        </div>
      )}
      {card.status === "submitting" && <p className="text-xs text-gray-600">正在提交并恢复执行，请勿重复操作。</p>}
      {card.status === "resolved" && <p className="text-xs text-gray-600">本批选择已提交；具体工具执行结果请查看后续消息。</p>}
      {card.status === "unknown" && <p role="alert" className="text-xs text-red-700">无法确认本批处理结果，可能已执行。已禁止重试，请刷新页面加载历史并核对执行状态，不要重复发起相同变更。</p>}
    </div>
  );
}
