"use client";
// 运维侧 · 审计日志（demo a/audit）：service_events / platform_config_events 聚合视图分页表。
import { useCallback, useEffect, useState } from "react";
import { api, type AuditItem } from "@/lib/api";
import { Btn, PageHead, Tag } from "@/components/ui";

const PAGE_SIZE = 50;

export default function AuditPage() {
  const [items, setItems] = useState<AuditItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [msg, setMsg] = useState("");

  const load = useCallback(async (p: number) => {
    try {
      const data = await api.admin.auditLogs(p, PAGE_SIZE);
      setItems(data.items);
      setTotal(data.total);
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, []);
  useEffect(() => { load(page); }, [load, page]);

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div data-testid="admin-audit">
      <PageHead title="审计日志" sub="一期聚合 service_events / platform_config_events 视图；独立 audit_logs 表在二期" />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="audit-table">
        <thead>
          <tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
            <th className="p-2.5">时间</th><th className="p-2.5">操作者</th><th className="p-2.5">动作</th>
            <th className="p-2.5">对象</th><th className="p-2.5">详情</th>
          </tr>
        </thead>
        <tbody>
          {items.map((it, i) => (
            <tr key={i} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 whitespace-nowrap text-xs text-gray-500">{new Date(it.time).toLocaleString("zh-CN", { hour12: false })}</td>
              <td className="p-2.5">{it.actor}</td>
              <td className="p-2.5"><Tag>{it.action}</Tag></td>
              <td className="p-2.5 font-mono text-xs">{it.object || "—"}</td>
              <td className="p-2.5 max-w-[320px] truncate font-mono text-[11px] text-gray-400" title={it.detail}>{it.detail}</td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan={5} className="p-6 text-center text-sm text-gray-400">暂无审计记录</td></tr>}
        </tbody>
      </table>
      <div className="mt-3 flex items-center justify-end gap-2 text-xs text-gray-500">
        <span>共 {total} 条</span>
        <Btn sm disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</Btn>
        <span>{page} / {pages}</span>
        <Btn sm disabled={page >= pages} onClick={() => setPage(page + 1)}>下一页</Btn>
      </div>
    </div>
  );
}
