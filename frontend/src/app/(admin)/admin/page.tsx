"use client";
// 服务列表页：状态/Endpoint/A2A 注册信息 + 行操作
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, type WorkspaceAdminView } from "@/lib/api";
import { STATUS_STYLE } from "@/lib/status";

export default function ServicesPage() {
  const [services, setServices] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState("");
  // 工作空间列（M1 §3.1）：归属展示 + 筛选（0=全部）+ 未归属「纳管」
  const [workspaces, setWorkspaces] = useState<WorkspaceAdminView[]>([]);
  const [wsFilter, setWsFilter] = useState(0);
  const [bindTo, setBindTo] = useState(0);

  const load = useCallback(async () => {
    try {
      setServices(await api.listServices("", "", 0, wsFilter));
      api.admin.listWorkspaces().then(setWorkspaces).catch(() => {});
      setMsg("");
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    } finally {
      setLoading(false);
    }
  }, [wsFilter]);

  useEffect(() => {
    load();
    const t = setInterval(load, 5000); // 轮询实时状态
    return () => clearInterval(t);
  }, [load]);

  const wsName = (id?: number) => workspaces.find((w) => w.id === id)?.name || (id && id > 0 ? `空间 ${id}` : "");
  const unbound = (s: any) => s.workspaceId === undefined || s.workspaceId === 0 ||
    workspaces.find((w) => w.id === s.workspaceId)?.slug === "default";

  const act = async (fn: () => Promise<unknown>, okMsg: string) => {
    try {
      await fn();
      setMsg(okMsg);
      await load();
    } catch (e: any) {
      setMsg(`操作失败: ${e.message}`);
    }
  };

  return (
    <div data-testid="services-page">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-xl font-semibold">服务列表</h1>
        <div className="space-x-2">
          <Link href="/admin/packages" data-testid="packages-entry"
            className="border border-blue-600 text-blue-600 hover:bg-blue-50 text-sm rounded px-4 py-2 inline-block">
            配置包
          </Link>
          <Link href="/admin/publish" data-testid="publish-entry"
            className="bg-blue-600 hover:bg-blue-700 text-white text-sm rounded px-4 py-2 inline-block">
            发布新服务
          </Link>
        </div>
      </div>
      {msg && <div className="mb-3 text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">{msg}</div>}
      <div className="mb-3 flex items-center gap-2 text-sm">
        <select value={wsFilter} onChange={(e) => setWsFilter(Number(e.target.value))} data-testid="ws-filter"
          className="rounded border border-gray-300 px-2 py-1.5 text-xs">
          <option value={0}>全部工作空间</option>
          {workspaces.filter((w) => w.slug !== "default").map((w) => (
            <option key={w.id} value={w.id}>{w.name}</option>
          ))}
          <option value={-1}>未归属（default）</option>
        </select>
      </div>
      {loading ? (
        <p className="text-gray-500">加载中…</p>
      ) : services.length === 0 ? (
        <p className="text-gray-500" data-testid="empty-hint">暂无服务，点击「发布新服务」开始。</p>
      ) : (
        <table className="w-full bg-white border border-gray-200 rounded text-sm" data-testid="service-table">
          <thead>
            <tr className="text-left text-gray-500 border-b">
              <th className="p-2">服务</th><th className="p-2">工作空间</th><th className="p-2">状态</th><th className="p-2">镜像</th>
              <th className="p-2">Endpoint</th><th className="p-2">A2A 注册</th><th className="p-2">操作</th>
            </tr>
          </thead>
          <tbody>
            {services.map((s) => (
              <tr key={s.id} className="border-b hover:bg-gray-50" data-testid={`svc-row-${s.id}`}>
                <td className="p-2 font-medium">
                  <Link href={`/admin/services/${s.id}`} className="text-blue-600 hover:underline">{s.displayName}</Link>
                  <div className="text-xs text-gray-400">{s.k8sName}</div>
                </td>
                <td className="p-2 text-xs">
                  {wsName(s.workspaceId) || <span className="text-gray-400">未归属</span>}
                  {unbound(s) && (
                    <select value={bindTo} onChange={(e) => setBindTo(Number(e.target.value))}
                      className="ml-1 rounded border border-gray-300 px-1 py-0.5 text-[11px]" data-testid={`bind-select-${s.id}`}>
                      <option value={0}>纳管到…</option>
                      {workspaces.filter((w) => w.slug !== "default" && w.status === "active").map((w) => (
                        <option key={w.id} value={w.id}>{w.name}</option>
                      ))}
                    </select>
                  )}
                  {bindTo > 0 && unbound(s) && (
                    <button onClick={() => { act(() => api.admin.bind(bindTo, s.id), "已纳管"); setBindTo(0); }}
                      className="ml-1 text-[11px] text-blue-600 hover:underline" data-testid={`bind-go-${s.id}`}>确认</button>
                  )}
                </td>
                <td className="p-2">
                  <span className={`px-2 py-0.5 rounded-full text-xs ${STATUS_STYLE[s.status] || "bg-gray-100"}`}
                    data-testid={`status-${s.k8sName}`}>{s.status}</span>
                </td>
                <td className="p-2 text-xs">{s.image}</td>
                <td className="p-2 text-xs break-all"><a href={s.endpoint} target="_blank" rel="noreferrer" className="text-blue-500 hover:underline">{s.endpoint}</a></td>
                <td className="p-2 text-xs">
                  {s.registeredName ? `${s.registeredName}@${s.registeredVersion}` : <span className="text-gray-400">未注册</span>}
                </td>
                <td className="p-2 space-x-1 whitespace-nowrap">
                  {(s.status === "running" || s.status === "register_failed") && (
                    <button onClick={() => act(() => api.unpublish(s.id), "已下线")} className="text-xs px-2 py-1 border rounded hover:bg-gray-100">下线</button>
                  )}
                  {(s.status === "stopped" || s.status === "error" || s.status === "deploy_failed") && (
                    <button onClick={() => act(() => api.startAgain(s.id), "上线中")} className="text-xs px-2 py-1 border rounded hover:bg-gray-100">上线</button>
                  )}
                  <button onClick={() => act(() => api.republish(s.id), "重新发布中")} className="text-xs px-2 py-1 border rounded hover:bg-gray-100">重发布</button>
                  <button onClick={() => { if (confirm(`确认删除 ${s.displayName}?`)) act(() => api.deleteService(s.id), "已删除"); }}
                    className="text-xs px-2 py-1 border rounded text-red-600 hover:bg-red-50">删除</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
