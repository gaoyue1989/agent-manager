"use client";
// 服务详情：状态轮询、Pod、Agent Card、env 编辑、操作与事件时间线
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ServiceDetail } from "@/lib/api";

const STATUS_STYLE: Record<string, string> = {
  running: "bg-green-100 text-green-800",
  deploying: "bg-blue-100 text-blue-800",
  register_failed: "bg-yellow-100 text-yellow-800",
  deploy_failed: "bg-red-100 text-red-800",
  stopped: "bg-gray-200 text-gray-700",
  error: "bg-red-100 text-red-800",
};

export default function ServiceDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [svc, setSvc] = useState<ServiceDetail | null>(null);
  const [msg, setMsg] = useState("");
  const [envRows, setEnvRows] = useState<{ key: string; value: string }[]>([]);
  const [envDirty, setEnvDirty] = useState(false);
  const [savingEnv, setSavingEnv] = useState(false);

  const load = useCallback(async () => {
    try {
      const d = await api.getService(id);
      setSvc(d);
      if (!envDirty) {
        let env: Record<string, string> = {};
        try { env = JSON.parse(d.envJson || "{}"); } catch {}
        setEnvRows(Object.entries(env).map(([key, value]) => ({ key, value })));
      }
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    }
  }, [id, envDirty]);

  useEffect(() => {
    load();
    const t = setInterval(load, 5000); // 状态轮询
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const act = async (fn: () => Promise<unknown>, okMsg: string) => {
    try {
      await fn();
      setMsg(okMsg);
      await load();
    } catch (e: any) {
      setMsg(`操作失败: ${e.message}`);
    }
  };

  const saveEnv = async () => {
    const env: Record<string, string> = {};
    for (const r of envRows) if (r.key.trim()) env[r.key.trim()] = r.value;
    setSavingEnv(true);
    await act(() => api.updateEnv(id, env), "env 已更新，滚动重启中");
    setEnvDirty(false);
    setSavingEnv(false);
  };

  if (!svc) return <p className="text-gray-500">{msg || "加载中…"}</p>;
  let card: any = null;
  try { card = JSON.parse(svc.agentCardJson || "null"); } catch {}

  return (
    <div data-testid="detail-page" className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">
          {svc.displayName}
          <span className={`ml-3 px-2 py-0.5 rounded-full text-xs align-middle ${STATUS_STYLE[svc.status] || ""}`} data-testid="detail-status">{svc.status}</span>
        </h1>
        <Link href="/" className="text-sm text-blue-600 hover:underline">← 返回列表</Link>
      </div>
      {msg && <div data-testid="detail-msg" className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">{msg}</div>}

      {/* 基本信息 */}
      <section className="bg-white border rounded p-4 grid grid-cols-2 gap-y-2 gap-x-8 text-sm">
        <div>K8s 名：<code>{svc.k8sName}</code></div>
        <div>镜像：<code className="text-xs">{svc.image}</code></div>
        <div>Endpoint：<a href={svc.endpoint} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline break-all" data-testid="endpoint-link">{svc.endpoint}</a></div>
        <div>副本数:{svc.replicas}</div>
        <div>A2A 注册：{svc.registeredName ? `${svc.registeredName}@${svc.registeredVersion}` : "未注册"}</div>
        <div>注册时间:{svc.registeredAt ? new Date(svc.registeredAt).toLocaleString() : "—"}</div>
      </section>

      {/* 操作区 */}
      <section className="space-x-2">
        {(svc.status === "running" || svc.status === "register_failed") && (
          <button onClick={() => act(() => api.unpublish(id), "下线中")} className="text-sm px-3 py-1.5 border rounded hover:bg-gray-100">下线</button>
        )}
        {["stopped", "error", "deploy_failed"].includes(svc.status) && (
          <button onClick={() => act(() => api.startAgain(id), "上线中")} className="text-sm px-3 py-1.5 border rounded hover:bg-gray-100">上线</button>
        )}
        <button onClick={() => act(() => api.republish(id), "重新发布中")} data-testid="republish-btn"
          className="text-sm px-3 py-1.5 border rounded hover:bg-gray-100">重新发布</button>
        {svc.status === "register_failed" && (
          <button onClick={() => act(() => api.reregister(id), "重新注册中")} className="text-sm px-3 py-1.5 border rounded hover:bg-gray-100">重新注册</button>
        )}
        <button onClick={() => { if (confirm("确认删除该服务？")) act(() => api.deleteService(id), "已删除"); }}
          className="text-sm px-3 py-1.5 border rounded text-red-600 hover:bg-red-50">删除</button>
      </section>

      {/* Pod 状态 */}
      <section className="bg-white border rounded p-4">
        <h2 className="font-medium mb-2">实时 Pod</h2>
        {!svc.pods?.length ? <p className="text-xs text-gray-400">无运行中的 Pod</p> : (
          <table className="w-full text-sm">
            <thead><tr className="text-left text-gray-500 border-b"><th className="p-1">名称</th><th className="p-1">Phase</th><th className="p-1">Ready</th><th className="p-1">重启</th></tr></thead>
            <tbody>{svc.pods.map((p) => (
              <tr key={p.name} className="border-b"><td className="p-1">{p.name}</td><td className="p-1">{p.phase}</td>
                <td className="p-1">{p.ready ? "✅" : "❌"}</td><td className="p-1">{p.restarts}</td></tr>
            ))}</tbody>
          </table>
        )}
      </section>

      {/* env 编辑 */}
      <section className="bg-white border rounded p-4">
        <div className="flex items-center justify-between mb-2">
          <h2 className="font-medium">环境变量（全量覆盖语义）</h2>
          <div className="space-x-2">
            <button onClick={() => { setEnvRows([...envRows, { key: "", value: "" }]); setEnvDirty(true); }}
              data-testid="detail-add-env" className="text-xs px-2 py-1 border rounded hover:bg-gray-100">+ 添加</button>
            <button onClick={saveEnv} disabled={savingEnv} data-testid="save-env-btn"
              className="text-xs px-3 py-1 bg-blue-600 disabled:opacity-50 text-white rounded hover:bg-blue-700">保存并滚动重启</button>
          </div>
        </div>
        <table className="w-full text-sm">
          <tbody>
            {envRows.map((r, i) => (
              <tr key={i}>
                <td className="pr-2 pb-2 w-2/5">
                  <input value={r.key} onChange={(e) => { setEnvRows(envRows.map((x, j) => j === i ? { ...x, key: e.target.value } : x)); setEnvDirty(true); }}
                    placeholder="KEY" data-testid={`denv-key-${i}`} className="border rounded p-1.5 w-full" />
                </td>
                <td className="pr-2 pb-2">
                  <input value={r.value} onChange={(e) => { setEnvRows(envRows.map((x, j) => j === i ? { ...x, value: e.target.value } : x)); setEnvDirty(true); }}
                    placeholder="VALUE" data-testid={`denv-value-${i}`} className="border rounded p-1.5 w-full" />
                </td>
                <td className="pb-2">
                  <button onClick={() => { setEnvRows(envRows.filter((_, j) => j !== i)); setEnvDirty(true); }} className="text-red-500 hover:text-red-700 px-2">×</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* Agent Card */}
      {card && (
        <section className="bg-white border rounded p-4">
          <h2 className="font-medium mb-2">Agent Card（A2A 注册信息）</h2>
          <pre className="text-xs bg-gray-900 text-green-200 p-3 rounded overflow-auto max-h-72" data-testid="agent-card">{JSON.stringify(card, null, 2)}</pre>
        </section>
      )}

      {/* 事件时间线 */}
      <section className="bg-white border rounded p-4">
        <h2 className="font-medium mb-2">事件时间线</h2>
        <ul className="text-sm space-y-1">
          {svc.events?.map((ev) => (
            <li key={ev.id} className="flex gap-3">
              <span className="text-gray-400 text-xs whitespace-nowrap">{new Date(ev.createdAt).toLocaleString()}</span>
              <span><span className="text-gray-500">{ev.fromStatus}</span> → <span className="font-medium">{ev.toStatus}</span>
                {ev.reason && <span className="text-gray-400"> · {ev.reason}</span>}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
