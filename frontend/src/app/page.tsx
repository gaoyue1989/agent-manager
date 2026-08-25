"use client";
// 服务列表页：状态/Endpoint/A2A 注册信息 + 行操作
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api } from "@/lib/api";

const STATUS_STYLE: Record<string, string> = {
  running: "bg-green-100 text-green-800",
  deploying: "bg-blue-100 text-blue-800",
  register_failed: "bg-yellow-100 text-yellow-800",
  deploy_failed: "bg-red-100 text-red-800",
  stopped: "bg-gray-200 text-gray-700",
  error: "bg-red-100 text-red-800",
};

export default function ServicesPage() {
  const [services, setServices] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try {
      setServices(await api.listServices());
      setMsg("");
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 5000); // 轮询实时状态
    return () => clearInterval(t);
  }, [load]);

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
        <Link href="/publish" data-testid="publish-entry"
          className="bg-blue-600 hover:bg-blue-700 text-white text-sm rounded px-4 py-2">
          发布新服务
        </Link>
      </div>
      {msg && <div className="mb-3 text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">{msg}</div>}
      {loading ? (
        <p className="text-gray-500">加载中…</p>
      ) : services.length === 0 ? (
        <p className="text-gray-500" data-testid="empty-hint">暂无服务，点击「发布新服务」开始。</p>
      ) : (
        <table className="w-full bg-white border border-gray-200 rounded text-sm" data-testid="service-table">
          <thead>
            <tr className="text-left text-gray-500 border-b">
              <th className="p-2">服务</th><th className="p-2">状态</th><th className="p-2">镜像</th>
              <th className="p-2">Endpoint</th><th className="p-2">A2A 注册</th><th className="p-2">操作</th>
            </tr>
          </thead>
          <tbody>
            {services.map((s) => (
              <tr key={s.id} className="border-b hover:bg-gray-50" data-testid={`svc-row-${s.id}`}>
                <td className="p-2 font-medium">
                  <Link href={`/services/${s.id}`} className="text-blue-600 hover:underline">{s.displayName}</Link>
                  <div className="text-xs text-gray-400">{s.k8sName}</div>
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
