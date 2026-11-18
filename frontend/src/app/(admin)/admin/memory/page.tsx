"use client";
// 运维侧 · 记忆管理（openviking-memory-integration-design §11.1，与沙箱管理平级）：
// 全平台 Agent×记忆供给状态总览 + 为任意平台用户代签发访问 Key。
import { useCallback, useEffect, useState } from "react";
import { api, type MemorySupplyItem } from "@/lib/api";
import { Badge, Btn, Card, CopyBtn, Note, PageHead } from "@/components/ui";

export default function AdminMemoryPage() {
  const [rows, setRows] = useState<MemorySupplyItem[]>([]);
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(false);
  const [target, setTarget] = useState<MemorySupplyItem | null>(null);
  const [userId, setUserId] = useState("");
  const [issuing, setIssuing] = useState(false);
  const [msg, setMsg] = useState("");
  const [issued, setIssued] = useState<{ key: string; label: string } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try { setRows(await api.admin.memorySupply(keyword)); } catch { setRows([]); }
    setLoading(false);
  }, [keyword]);
  useEffect(() => { load(); }, [load]);

  const issue = async () => {
    if (!target) return;
    setIssuing(true); setMsg("");
    try {
      const out = await api.admin.issueMemoryKey(target.serviceId, userId.trim());
      setIssued({ key: out.userKey, label: `${target.displayName} × ${userId.trim()}` });
      setTarget(null); setUserId("");
    } catch (e: any) { setMsg(e.message); }
    setIssuing(false);
  };

  const ovRows = rows.filter((r) => r.provider === "openviking");
  return (
    <div data-testid="admin-memory">
      <PageHead title="记忆管理" sub="全平台 Agent 的 OpenViking 记忆供给状态；为平台用户代签发记忆访问 Key（供 OpenViking Studio / 外部 MCP 使用）"
        actions={
          <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索 Agent"
            className="rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
        } />
      <Note tone="info">供给模式切换 = 对应服务环境变量 AGENT_MEMORY_PROVIDER（openviking/local）+ 滚动重启；
      轮换某服务的 user seed 可作废其全部已签发 Key（OpenViking Admin 操作）。明文 Key 仅签发时展示一次。</Note>

      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white" data-testid="admin-memory-table">
        <table className="w-full text-sm">
          <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
            <th className="p-2.5">Agent</th><th className="p-2.5">供给模式</th><th className="p-2.5">会话归档</th>
            <th className="p-2.5">知识导入</th><th className="p-2.5">共享检索</th><th className="p-2.5">供给状态</th>
            <th className="p-2.5">数据面地址</th><th className="p-2.5 text-right">操作</th>
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.serviceId} className="border-b last:border-0 hover:bg-gray-50">
                <td className="p-2.5 font-medium">{r.displayName}</td>
                <td className="p-2.5">{r.provider === "openviking"
                  ? <Badge status="running">OpenViking</Badge>
                  : <Badge status="stopped">本地文件记忆</Badge>}</td>
                <td className="p-2.5 text-xs">{r.provider === "openviking" ? (r.sessionCommit ? "会话归档" : "逐轮提取") : "—"}</td>
                <td className="p-2.5 text-xs">{r.provider === "openviking" ? (r.knowledgeImport ? "已导入" : "未导入") : "—"}</td>
                <td className="p-2.5 text-xs">{r.provider === "openviking" ? (r.mcpSharedCtx ? "已注入" : "未注入") : "—"}</td>
                <td className="p-2.5">{r.provider === "openviking"
                  ? <Badge status={r.provisioned ? "running" : "stopped"}>{r.provisioned ? "已供给" : "未供给"}</Badge>
                  : "—"}</td>
                <td className="p-2.5 font-mono text-[11px] text-gray-500">{r.openVikingUrl || "—"}</td>
                <td className="p-2.5 text-right">
                  {r.provider === "openviking" && (
                    <button onClick={() => { setTarget(r); setMsg(""); }}
                      className="text-xs px-2 py-1 border rounded hover:bg-gray-100">代签发 Key</button>
                  )}
                </td>
              </tr>
            ))}
            {rows.length === 0 && !loading && <tr><td colSpan={8} className="p-6 text-center text-sm text-gray-400">暂无 Agent</td></tr>}
          </tbody>
        </table>
      </div>
      {ovRows.length === 0 && rows.length > 0 && (
        <Note tone="warn">当前没有任何 Agent 启用 OpenViking 记忆供给——在各服务环境变量设置 AGENT_MEMORY_PROVIDER=openviking 并滚动重启。</Note>
      )}

      {target && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
          <Card title={`代签发记忆访问 Key · ${target.displayName}`} className="w-[380px]">
            <label className="mb-1 block text-xs font-medium">平台用户 ID *</label>
            <input value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="u-1001"
              data-testid="admin-memory-user-id"
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 font-mono text-sm" />
            {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setTarget(null)}>取消</Btn>
              <Btn kind="pri" disabled={!userId.trim()} onClick={issue}>{issuing ? "签发中…" : "签发"}</Btn>
            </div>
          </Card>
        </div>
      )}
      {issued && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" data-testid="admin-memory-key-dialog">
          <Card title={`Key 已签发 · ${issued.label}`} className="w-[520px]">
            <div className="mb-2 break-all rounded-md bg-gray-900 p-3 font-mono text-xs text-emerald-300">{issued.key}</div>
            <Note tone="warn">关闭后不再可见，请立即复制保存。</Note>
            <div className="flex justify-end gap-2">
              <CopyBtn text={issued.key} />
              <Btn kind="pri" onClick={() => setIssued(null)}>我已保存，关闭</Btn>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
