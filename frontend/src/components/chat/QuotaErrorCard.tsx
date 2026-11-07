"use client";
// 配额满专属错误卡（sandbox-pool-opensandbox-design §6.3，评审 P0 UX 补偿）：
// 「配额满且全忙」是基线不存在的新失败面——列出该用户全部占用会话（会话标识 / expires_at /
// state 两态），executing 行提供「停止该会话释放配额」（复用 POST /threads/{sid}/stop，
// 仅限本人会话）；confirm_holding 行不给停止（后端 WAITING_CONFIRM 必 409，d5），
// 引导等保留窗到期或在该会话拒绝确认。两入口复用本卡：chat 预检 409 响应体 + SSE error 帧
// code=user_quota_exhausted。
import { useState } from "react";
import { bindingAdvice, quotaHeadline, type QuotaPayload } from "@/lib/sandbox-quota";

export default function QuotaErrorCard(props: {
  payload: QuotaPayload;
  /** 会话标识解析（/threads 列表同源标题）：返回 undefined = 不可见会话（已被清理）→ 卡片只显示时间与状态 */
  resolveTitle?: (sessionId: string) => string | undefined;
  /** 停止会话（executing 行按钮触发；父级发 POST /threads/{sid}/stop 后刷新） */
  onStop?: (sessionId: string) => Promise<void> | void;
  testId?: string;
}) {
  const { payload, resolveTitle, onStop } = props;
  const [stopping, setStopping] = useState<string>("");
  const [msg, setMsg] = useState("");

  const stop = async (sid: string) => {
    if (!onStop || stopping) return;
    setStopping(sid);
    setMsg("");
    try {
      await onStop(sid);
    } catch (e: any) {
      setMsg(`停止失败: ${e?.message ?? e}`);
    } finally {
      setStopping("");
    }
  };

  return (
    <div data-testid={props.testId ?? "quota-error-card"}
      className="rounded-xl border border-amber-300 bg-amber-50 p-3.5 text-[13px] shadow-sm">
      <div className="flex items-center gap-1.5 font-medium text-amber-800">
        <span aria-hidden>🚦</span>{quotaHeadline(payload)}
      </div>
      <p className="mt-1 text-[12px] text-amber-700">
        以上会话占满了你的沙箱配额；等待其一结束，或按下表提示释放后重试。
      </p>
      {payload.bindings.length === 0 ? (
        <p className="mt-2 text-[12px] text-amber-700">（无占用明细，稍后重试）</p>
      ) : (
        <ul className="mt-2 space-y-1.5" data-testid="quota-bindings">
          {payload.bindings.map((b, i) => {
            const adv = bindingAdvice(b);
            // 会话标识：resolveTitle 返回 undefined = 不在 /threads 列表（已被清理）→ 只显示时间与状态
            const known = b.session_id && resolveTitle ? resolveTitle(b.session_id) : undefined;
            const visible = known !== undefined && known !== "";
            const expires = b.expires_at ? b.expires_at.replace("T", " ").slice(0, 16) : "";
            return (
              <li key={b.session_id ?? i}
                className="rounded-lg border border-amber-200 bg-white/80 px-2.5 py-1.5">
                <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                  <span className="font-medium text-gray-700" data-testid={`quota-binding-state-${i}`}>
                    {adv.label}
                  </span>
                  {visible && <span className="truncate text-gray-600" title={b.session_id}>{known}</span>}
                  {expires ? (
                    <span className="text-[11px] text-gray-400">到期 {expires}</span>
                  ) : !visible ? (
                    <span className="text-[11px] text-gray-400">（会话不可见）</span>
                  ) : null}
                  {adv.canStop && b.session_id && onStop && (
                    <button
                      onClick={() => stop(b.session_id!)}
                      disabled={stopping === b.session_id}
                      data-testid={`quota-stop-${i}`}
                      className="ml-auto rounded-md border border-red-200 bg-white px-2 py-0.5 text-[11px] text-red-600 transition hover:border-red-300 hover:bg-red-50 disabled:opacity-50">
                      {stopping === b.session_id ? "停止中…" : "停止该会话释放配额"}
                    </button>
                  )}
                </div>
                <p className="mt-0.5 text-[11px] text-gray-500">{adv.advice}</p>
              </li>
            );
          })}
        </ul>
      )}
      {msg && <p className="mt-1.5 text-[12px] text-red-600">{msg}</p>}
    </div>
  );
}
