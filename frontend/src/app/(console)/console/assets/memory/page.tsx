"use client";
// 用户侧 · 记忆空间（M8 §4，demo c/memory）：按 Agent×会话只读聚合（当前用户视角；API 身份占位说明）。
import { useEffect, useState } from "react";
import { api, type AgentListItem } from "@/lib/api";
import { useConsoleWs, useMe } from "@/lib/useMe";
import { Card, Note, PageHead } from "@/components/ui";

export default function MemoryPage() {
  const ws = useConsoleWs();
  const { me } = useMe();
  const [agents, setAgents] = useState<AgentListItem[]>([]);
  const [agentId, setAgentId] = useState(0);
  const [threads, setThreads] = useState<{ session_id: string; title?: string; message_count?: number; updated_at?: string; preview?: string }[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    api.console.agents(ws).then((rows) => {
      setAgents(rows);
      const first = rows.find((r) => r.status === "running");
      if (first) setAgentId(first.id);
    }).catch(() => {});
  }, [ws]);

  useEffect(() => {
    const short = agents.find((a) => a.id === agentId)?.shortName;
    if (!short) return;
    setLoading(true);
    fetch(`/agent/${short}/threads?userId=u-${me?.user.id ?? 0}`)
      .then((r) => (r.ok ? r.json() : []))
      .then((list) => setThreads(Array.isArray(list) ? list.slice(0, 20) : []))
      .catch(() => setThreads([]))
      .finally(() => setLoading(false));
  }, [agentId, agents, me]);

  return (
    <div data-testid="console-memory">
      <PageHead title="记忆空间" sub="会话记忆随 checkpoint 持久化，按「Agent × 用户」隔离；一期只读摘要"
        actions={
          <select value={agentId} onChange={(e) => setAgentId(Number(e.target.value))}
            className="rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]">
            <option value={0}>— 选择 Agent —</option>
            {agents.filter((a) => a.status === "running").map((a) => <option key={a.id} value={a.id}>{a.displayName}</option>)}
          </select>
        } />
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <Card title={`用户 u-${me?.user.id ?? "…"} · 会话记忆`}>
          <Note>以下为会话列表聚合（只读；每会话首条内容为近期互动的入口）；记忆编辑与清除策略在后续版本开放。</Note>
          <ul className="pl-1.5">
            {threads.map((t) => (
              <li key={t.session_id} className="relative border-l border-gray-200 pb-3.5 pl-5">
                <span className="absolute -left-[3.5px] top-1.5 size-1.5 rounded-full bg-emerald-500" />
                <b className="text-[13px] font-medium">{t.title || t.session_id}</b>
                <small className="block text-[11px] text-gray-400">
                  {t.message_count != null ? `${t.message_count} 条消息 · ` : ""}{(t.updated_at ?? "").replace("T", " ").slice(0, 19)}
                </small>
              </li>
            ))}
            {threads.length === 0 && !loading && <p className="text-sm text-gray-400">{agentId ? "该 Agent 上当前用户暂无会话" : "请先选择 Agent"}</p>}
          </ul>
        </Card>
        <Card title="API 调用身份">
          <p className="text-sm leading-relaxed text-gray-500">
            数据面 API 调用（API Key）的记忆按调用身份隔离；一期无身份映射（网关 introspect 在二期开放后，
            API Key → 调用身份的记忆视图才可用），此处不做虚构数据。
          </p>
          <div className="mt-3 text-xs text-gray-400">
            存储位置：服务 checkpoint 库（session_user / agent_state）· 每服务独立库名，跨空间不共享。
          </div>
        </Card>
      </div>
    </div>
  );
}
