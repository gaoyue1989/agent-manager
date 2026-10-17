"use client";
// 用户侧 · Agent 列表（demo c/agents）：统计卡 + 状态/关键字筛选 + 卡片网格。
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, type AgentListItem } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Card, PageHead, StatCard, Tag } from "@/components/ui";

const AVA_GRADIENTS = [
  "from-blue-600 to-blue-400", "from-violet-500 to-purple-400", "from-emerald-500 to-green-400",
  "from-orange-500 to-amber-400", "from-pink-500 to-rose-400", "from-gray-400 to-gray-300",
];
function avaCls(id: number) { return AVA_GRADIENTS[id % AVA_GRADIENTS.length]; }

export default function AgentsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin"; // member 只读：隐藏创建入口（issue #141）
  const [list, setList] = useState<AgentListItem[]>([]);
  const [status, setStatus] = useState("");
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try { setList(await api.console.agents(ws, status, keyword)); setMsg(""); }
    catch (e: any) { setMsg(`加载失败: ${e.message}`); }
    finally { setLoading(false); }
  }, [ws, status, keyword]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  const running = list.filter((a) => a.status === "running").length;
  const bad = list.filter((a) => ["error", "deploy_failed", "register_failed"].includes(a.status)).length;

  return (
    <div data-testid="console-agents">
      <PageHead title="Agent" sub="工作空间内可见的智能体实例（= services 按空间过滤，不新建实体）"
        actions={writable ? <Link href="/console/agents/create" data-testid="agents-create"
          className="inline-flex items-center gap-1.5 rounded-md border border-blue-600 bg-blue-600 px-3 py-1.5 text-[13px] text-white hover:bg-blue-700">+ 创建 Agent</Link> : undefined} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="mb-3.5 grid grid-cols-3 gap-3">
        <StatCard label="Agent 总数" value={list.length} />
        <StatCard label="正常" value={running} />
        <StatCard label="异常" value={bad} danger={bad > 0} />
      </div>
      <div className="mb-3.5 flex flex-wrap items-center gap-2">
        <select value={status} onChange={(e) => setStatus(e.target.value)} data-testid="agents-status-filter"
          className="w-[130px] rounded-md border border-gray-300 px-2 py-1.5 text-[13px]">
          <option value="">全部状态</option>
          <option value="running">运行中</option>
          <option value="deploying">部署中</option>
          <option value="stopped">已下线</option>
          <option value="register_failed">注册失败</option>
          <option value="error">异常</option>
        </select>
        <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索 Agent 名称 / 描述"
          data-testid="agents-keyword"
          className="w-[220px] rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
      </div>
      {loading ? <p className="text-sm text-gray-400">加载中…</p> : list.length === 0 ? (
        <Card><div className="py-10 text-center text-sm text-gray-400">
          {/* 区分「空间为空」与「筛选/搜索无匹配」——统一空态会误导用户以为空间为空（issue #146） */}
          {(keyword || status)
            ? <>未找到匹配的 Agent —— 试试其他关键词或清除筛选条件（当前：{status ? `状态=${status} ` : ""}{keyword ? `关键词="${keyword}"` : ""}）</>
            : <>工作空间内暂无 Agent —— 点击右上角「创建 Agent」开始，或让管理员把存量服务纳管进本空间。</>}
        </div></Card>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4" data-testid="agents-grid">
          {list.map((a) => (
            <div key={a.id} data-testid={`agent-card-${a.id}`}
              className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-3.5 transition hover:border-blue-200 hover:shadow-md">
              <div className="flex items-start gap-2.5">
                <span className={`flex size-[34px] flex-none items-center justify-center rounded-lg bg-gradient-to-br text-sm font-semibold text-white ${avaCls(a.id)}`}>
                  {(a.displayName || "?").slice(0, 1)}
                </span>
                <b className="min-w-0 flex-1 truncate text-[13.5px] font-semibold">{a.displayName}</b>
                <Badge status={a.status} />
              </div>
              <p className="min-h-[32px] text-xs leading-relaxed text-gray-400">
                {a.cardSummary.description || a.cardSummary.name || "（包内未声明描述）"}
              </p>
              <div className="flex flex-wrap gap-1.5">
                {a.model && <Tag>{a.model}</Tag>}
                <Tag>{a.replicas} 副本</Tag>
                {a.version && <Tag>v{a.version}</Tag>}
              </div>
              <div className="mt-auto flex items-center gap-1.5 border-t border-gray-100 pt-2">
                <time className="mr-auto text-[11px] text-gray-400">
                  {new Date(a.updatedAt).toLocaleDateString("zh-CN", { month: "short", day: "numeric" })}
                </time>
                {(a.status === "running" || a.status === "register_failed") && (
                  <Link href={`/console/agents/${a.id}?tab=debug`} data-testid={`agent-debug-${a.id}`}
                    className="rounded border border-gray-300 px-2 py-0.5 text-xs text-gray-600 hover:border-blue-200 hover:text-blue-600">调试</Link>
                )}
                <Link href={`/console/agents/${a.id}`} data-testid={`agent-detail-${a.id}`}
                  className="rounded border border-gray-300 px-2 py-0.5 text-xs text-gray-600 hover:border-blue-200 hover:text-blue-600">详情</Link>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
