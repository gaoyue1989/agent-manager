"use client";
// 服务详情：状态轮询、Pod、Agent Card、env 编辑、操作与事件时间线
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, PackageRec, ServiceDetail } from "@/lib/api";

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
  // 敏感变量：value 仅承载新输入（空且未标记删除 = 保持不变，不上送）；del = 显式删除（回落平台默认）
  const [secretRows, setSecretRows] = useState<{ key: string; value: string; del: boolean }[]>([]);
  const [secretDirty, setSecretDirty] = useState(false);
  const [savingEnv, setSavingEnv] = useState(false);
  const [pkg, setPkg] = useState<PackageRec | null>(null);
  const [versions, setVersions] = useState<PackageRec[]>([]);
  const [versionsLoaded, setVersionsLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const d = await api.getService(id);
      setSvc(d);
      if (!envDirty) {
        let env: Record<string, string> = {};
        try { env = JSON.parse(d.envJson || "{}"); } catch {}
        // 敏感键（含存量未迁移键）从普通区剔除，归入敏感区掩码显示
        const secretNames = new Set((d.envSecretKeys || []).map((k) => k.key));
        setEnvRows(Object.entries(env).filter(([key]) => !secretNames.has(key)).map(([key, value]) => ({ key, value })));
      }
      if (!secretDirty) {
        setSecretRows((d.envSecretKeys || []).map((k) => ({ key: k.key, value: "", del: false })));
      }
      // 配置包信息 + 同 slug 版本列表（升级用）；失败不阻塞主视图。
      // 版本列表只拉一次（versionsLoaded 守卫）：5s 轮询重拉会重置升级下拉选择
      if (!versionsLoaded) {
        api.getPackage(d.packageId).then((pd) => {
          setPkg(pd.package);
          api.listPackages("", pd.package.slug).then((vs) => { setVersions(vs); setVersionsLoaded(true); })
            .catch(() => setVersionsLoaded(true));
        }).catch(() => {});
      }
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    }
  }, [id, envDirty, secretDirty, versionsLoaded]);

  // 轮询依赖 load 本体（其 useCallback 闭包携带 envDirty/secretDirty 最新值）：
  // 若只依赖 [id]，setInterval 会永远持有挂载时的旧闭包（dirty 恒为 false），
  // 每 5s 用服务端值覆盖未保存的编辑/填入内容
  useEffect(() => {
    load();
    const t = setInterval(load, 5000); // 状态轮询
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

  // 填入平台默认（R3）：把默认配置中服务尚未配置的键追加为可编辑行，显式保存后才生效；
  // 不点保存则服务 env 保持原样（平台配置变更不影响存量服务）
  const fillDefaults = async () => {
    try {
      const [cfg, dflt] = await Promise.all([api.getPlatformConfig(), api.getPlatformDefaults()]);
      const sensitive = new Set(cfg.groups.flatMap((g) => g.fields.filter((f) => f.sensitive).map((f) => f.envKey)));
      const existing = new Set([...envRows.map((r) => r.key), ...secretRows.map((r) => r.key)].filter(Boolean));
      const entries = Object.entries(dflt.values).filter(([k]) => !existing.has(k));
      if (entries.length === 0) { setMsg("平台默认配置的键均已存在，无需填入"); return; }
      const newPlain = entries.filter(([k]) => !sensitive.has(k)).map(([key, value]) => ({ key, value }));
      const newSecret = entries.filter(([k]) => sensitive.has(k)).map(([key, value]) => ({ key, value, del: false }));
      if (newPlain.length) setEnvRows((rows) => [...rows, ...newPlain]);
      if (newSecret.length) setSecretRows((rows) => [...rows.filter((r) => r.key.trim()), ...newSecret]);
      setEnvDirty(true); setSecretDirty(true);
      setMsg(`已填入 ${entries.length} 项平台默认值，请检查后点「保存并滚动重启」生效`);
    } catch (e: any) {
      setMsg(`填入失败: ${e.message}`);
    }
  };

  const saveEnv = async () => {
    const env: Record<string, string> = {};
    const secretKeys: string[] = [];
    for (const r of envRows) if (r.key.trim()) env[r.key.trim()] = r.value;
    // 敏感键三态：输入新值=设置、标记删除=清空（回落平台默认）、两者皆无=保持不变（sticky 不上送）
    for (const r of secretRows) {
      if (!r.key.trim()) continue;
      if (r.del) { env[r.key.trim()] = ""; secretKeys.push(r.key.trim()); }
      else if (r.value.trim()) { env[r.key.trim()] = r.value.trim(); secretKeys.push(r.key.trim()); }
    }
    setSavingEnv(true);
    await act(() => api.updateEnv(id, env, secretKeys), "env 已更新，滚动重启中");
    setEnvDirty(false);
    setSecretDirty(false);
    setSavingEnv(false);
  };

  const upgradePackage = async (target: PackageRec) => {
    if (!svc) return;
    if (target.id === svc.packageId) return;
    if (!confirm(`将配置包从 ${pkg?.slug}@${pkg?.version} 升级到 ${target.slug}@${target.version}？将触发滚动重启并重新注册。`)) return;
    await act(() => api.republish(id, { packageId: target.id }), "配置包升级中，滚动完成后回到 running");
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
        <div>
          配置包：
          {pkg ? (
            <>
              <Link href={`/packages/${svc.packageId}`} data-testid="svc-package-link" className="text-blue-600 hover:underline">
                {pkg.slug}@{pkg.version}
              </Link>
              {versions.length > 1 && (
                <select data-testid="upgrade-pkg-select" defaultValue=""
                  onChange={(e) => { const v = versions.find((x) => x.id === Number(e.target.value)); if (v) upgradePackage(v); }}
                  className="ml-2 border rounded p-0.5 text-xs">
                  <option value="">升级到…</option>
                  {versions.filter((v) => v.id !== svc.packageId).map((v) => (
                    <option key={v.id} value={v.id}>v{v.version}</option>
                  ))}
                </select>
              )}
            </>
          ) : (
            <span className="text-gray-400">#{svc.packageId}</span>
          )}
        </div>
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

      {/* env 编辑：普通变量（ConfigMap 全量覆盖）+ 敏感变量（服务 Secret 掩码） */}
      <section className="bg-white border rounded p-4">
        <div className="flex items-center justify-between mb-2">
          <h2 className="font-medium">环境变量</h2>
          <div className="space-x-2">
            <button onClick={fillDefaults} data-testid="detail-fill-defaults"
              className="text-xs px-2 py-1 border rounded hover:bg-gray-100">填入平台默认</button>
            <button onClick={() => { setEnvRows([...envRows, { key: "", value: "" }]); setEnvDirty(true); }}
              data-testid="detail-add-env" className="text-xs px-2 py-1 border rounded hover:bg-gray-100">+ 添加变量</button>
            <button onClick={saveEnv} disabled={savingEnv} data-testid="save-env-btn"
              className="text-xs px-3 py-1 bg-blue-600 disabled:opacity-50 text-white rounded hover:bg-blue-700">保存并滚动重启</button>
          </div>
        </div>
        <p className="text-xs text-gray-400 mb-2">普通变量（全量覆盖语义）：</p>
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
        <div className="flex items-center justify-between mt-4 mb-2 pt-3 border-t">
          <h3 className="text-sm font-medium">敏感变量 <span className="text-[10px] px-1 rounded bg-gray-100 border text-gray-500">🔒 存于服务 Secret</span></h3>
          <button onClick={() => { setSecretRows([...secretRows, { key: "", value: "", del: false }]); setSecretDirty(true); }}
            data-testid="detail-add-secret" className="text-xs px-2 py-1 border rounded hover:bg-gray-100">+ 添加敏感变量</button>
        </div>
        <p className="text-xs text-gray-400 mb-2">
          值保存后不回显；输入新值=覆盖，留空=保持不变，点「删除」=移除该键（回落平台默认配置）。
        </p>
        {!secretRows.length ? <p className="text-xs text-gray-400" data-testid="dsecret-empty">无敏感变量（平台默认配置生效）</p> : (
          <table className="w-full text-sm">
            <tbody>
              {secretRows.map((r, i) => (
                <tr key={i} className={r.del ? "opacity-50" : ""}>
                  <td className="pr-2 pb-2 w-2/5">
                    <input value={r.key} onChange={(e) => { setSecretRows(secretRows.map((x, j) => j === i ? { ...x, key: e.target.value } : x)); setSecretDirty(true); }}
                      placeholder="KEY（如 LLM_API_KEY）" data-testid={`dsecret-key-${i}`} className="border rounded p-1.5 w-full" />
                  </td>
                  <td className="pr-2 pb-2">
                    <input type="password" autoComplete="new-password" value={r.value}
                      onChange={(e) => { setSecretRows(secretRows.map((x, j) => j === i ? { ...x, value: e.target.value } : x)); setSecretDirty(true); }}
                      placeholder="已配置，输入新值覆盖" data-testid={`dsecret-value-${i}`} className="border rounded p-1.5 w-full" />
                  </td>
                  <td className="pb-2 whitespace-nowrap">
                    {r.del ? (
                      <button onClick={() => { setSecretRows(secretRows.map((x, j) => j === i ? { ...x, del: false } : x)); setSecretDirty(true); }}
                        className="text-xs text-gray-500 hover:text-gray-700 px-2 underline">撤销删除</button>
                    ) : (
                      <button onClick={() => { setSecretRows(secretRows.map((x, j) => j === i ? { ...x, del: true } : x)); setSecretDirty(true); }}
                        data-testid={`dsecret-del-${i}`} className="text-xs text-red-500 hover:text-red-700 px-2">删除</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
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
