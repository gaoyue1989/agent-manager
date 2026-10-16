"use client";
// 用户侧 · 概览（demo c/overview）：统计卡 + 最近发布时间线 + 快捷入口（数据来自 console summary）。
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, type ConsoleSummary } from "@/lib/api";
import { useConsoleWs } from "@/lib/useMe";
import { Badge, Card, PageHead, StatCard } from "@/components/ui";

export default function ConsoleOverviewPage() {
  const ws = useConsoleWs();
  const [sum, setSum] = useState<ConsoleSummary | null>(null);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try { setSum(await api.console.summary(ws)); setMsg(""); }
    catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  return (
    <div data-testid="console-overview">
      <PageHead title="概览" sub={`工作空间 · 数据来自控制面聚合（观测大盘一期不做，指标/Trace 后续从可观测系统接入）`}
        actions={<Link href="/console/agents/create" data-testid="ov-create"
          className="inline-flex items-center gap-1.5 rounded-md border border-blue-600 bg-blue-600 px-3 py-1.5 text-[13px] text-white hover:bg-blue-700">+ 创建 Agent</Link>} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Agent 总数" value={sum?.agents.total ?? "—"} />
        <StatCard label="运行中" value={sum?.agents.running ?? "—"} sub="正常" />
        <StatCard label="异常" value={sum?.agents.error ?? "—"} danger={(sum?.agents.error ?? 0) > 0} />
        <StatCard label="已下线" value={sum?.agents.stopped ?? "—"} />
      </div>
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1.6fr_1fr]">
        <Card title="最近发布">
          <ul className="pl-1.5">
            {(sum?.recentEvents ?? []).map((e) => (
              <li key={e.id} className="relative border-l border-gray-200 pb-3.5 pl-5">
                <span className={`absolute -left-[3.5px] top-1.5 size-1.5 rounded-full ${["running", "deploying", "created"].includes(e.toStatus) ? "bg-emerald-500" : e.toStatus === "stopped" ? "bg-gray-300" : "bg-red-500"}`} />
                <Link href={`/console/agents/${e.serviceId}`} className="text-[13px] font-medium text-blue-600 hover:underline">{e.displayName}</Link>
                <span className="ml-2"><Badge status={e.toStatus} /></span>
                <small className="block text-[11px] text-gray-400">{e.reason} · {new Date(e.createdAt).toLocaleString("zh-CN", { hour12: false })}</small>
              </li>
            ))}
            {(sum?.recentEvents ?? []).length === 0 && <p className="text-sm text-gray-400">暂无发布记录</p>}
          </ul>
        </Card>
        <Card title="快捷入口">
          <div className="flex flex-col gap-2.5">
            <Link href="/console/agents/create" className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">创建 Agent</Link>
            <Link href="/console/agents" className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">打开调试对话</Link>
            <Link href="/console/assets/credentials" className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">签发调用凭证</Link>
            <a href={process.env.NEXT_PUBLIC_EVAL_STUDIO_URL || "http://localhost:18400"} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">评测飞轮 eval-studio ↗</a>
          </div>
        </Card>
      </div>
    </div>
  );
}
