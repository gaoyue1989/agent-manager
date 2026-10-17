"use client";
// 用户侧 · 模型连接（M5 §3.1，demo c/models）：连接表 + Agent 使用情况表；
// 新建/编辑走右侧抽屉（slide-over，不复刻 demo 右栏常驻表单——评审反馈改为弹出）。
import { useCallback, useEffect, useState } from "react";
import { api, type ModelConnectionView, type ModelConnUsageRow } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead } from "@/components/ui";
import Drawer from "@/components/Drawer";

// 抽屉内新建/编辑共用表单（editing 非 null = 编辑模式，PATCH 局部更新）
function ConnForm({ ws, editing, onDone }: {
  ws: number; editing: ModelConnectionView | null; onDone: (msg: string) => void;
}) {
  const [form, setForm] = useState({ name: "", modelId: "", baseUrl: "", apiKey: "", temperature: 0.3, maxTokens: 16384 });
  const [keyInput, setKeyInput] = useState(""); // 编辑模式：空 = 不变；显式输入空格提交 = 清除
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const submit = async () => {
    setErr("");
    // URL 结构校验与后端 validateBaseURL 同口径（issue #156）：必须含主机的 http(s) URL
    try {
      const u = new URL(form.baseUrl);
      if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error("bad protocol");
      if (!u.host) throw new Error("no host");
    } catch {
      setErr("Base URL 必须是含主机的 http(s) 地址，如 https://api.deepseek.com/v1");
      return;
    }
    setBusy(true);
    try {
      if (editing) {
        const body: Record<string, unknown> = { modelId: form.modelId, baseUrl: form.baseUrl };
        if (keyInput.trim() !== "") body.apiKey = keyInput;
        else if (keyInput === " ") body.apiKey = ""; // 输入单个空格 = 显式清除
        await api.console.modelConnections.patch(ws, editing.id, body);
        onDone("连接已保存（下发行已重置为待同步）");
      } else {
        await api.console.modelConnections.create(ws, form);
        onDone("连接已保存（未验证），可在列表「测试」验证连通性");
      }
    } catch (e: any) {
      setErr(e.message || "保存失败");
      setBusy(false);
    }
  };

  return (
    <div data-testid="conn-form">
      {err && <div className="mb-3 rounded border border-red-200 bg-red-50 p-2 text-xs text-red-700">{err}</div>}
      <label className="mb-1 block text-xs font-medium">连接名称 *</label>
      <input value={form.name} disabled={!!editing} onChange={(e) => setForm({ ...form, name: e.target.value })}
        data-testid="mc-name" placeholder="如 mimo / deepseek-v3"
        className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm disabled:bg-gray-50 disabled:text-gray-500" />
      <label className="mb-1 block text-xs font-medium">模型 ID *</label>
      <input value={form.modelId} onChange={(e) => setForm({ ...form, modelId: e.target.value })} data-testid="mc-model"
        placeholder="发给推理端点的模型名" className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
      <label className="mb-1 block text-xs font-medium">Base URL *</label>
      <input value={form.baseUrl} onChange={(e) => setForm({ ...form, baseUrl: e.target.value })} data-testid="mc-url"
        placeholder="OpenAI 兼容端点，如 https://api.deepseek.com/v1"
        className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
      <label className="mb-1 block text-xs font-medium">API Key {editing ? "" : "*"}</label>
      <input type="password" value={editing ? keyInput : form.apiKey}
        onChange={(e) => editing ? setKeyInput(e.target.value) : setForm({ ...form, apiKey: e.target.value })}
        data-testid="mc-key" placeholder={editing ? `留空 = 保持不变（当前 ${editing.apiKeyMasked || "未设置"}；输入空格 = 清除）` : "sk-…"}
        className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="mb-1 block text-xs font-medium">Temperature</label>
          <input type="number" step="0.1" value={form.temperature} onChange={(e) => setForm({ ...form, temperature: Number(e.target.value) })}
            className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium">Max Tokens</label>
          <input type="number" value={form.maxTokens} onChange={(e) => setForm({ ...form, maxTokens: Number(e.target.value) })}
            className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
        </div>
      </div>
      <Note>API Key 落库内网明文、接口掩码回显；下发时经服务 Secret 注入，绝不落包/落 ConfigMap。</Note>
      <div className="flex justify-end gap-2 border-t border-gray-100 pt-3">
        <Btn onClick={() => onDone("")}>取消</Btn>
        <Btn kind="pri" testid="mc-save"
          disabled={busy || !form.modelId.trim() || !form.baseUrl.trim() || (!editing && (!form.name.trim() || !form.apiKey))}
          onClick={submit}>{busy ? "保存中…" : editing ? "保存" : "保存并关闭"}</Btn>
      </div>
    </div>
  );
}

export default function ModelsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin";
  const [conns, setConns] = useState<ModelConnectionView[]>([]);
  const [usage, setUsage] = useState<ModelConnUsageRow[]>([]);
  const [msg, setMsg] = useState("");
  const [drawer, setDrawer] = useState<{ open: boolean; editing: ModelConnectionView | null }>({ open: false, editing: null });

  const load = useCallback(async () => {
    try {
      setConns(await api.console.modelConnections.list(ws));
      setUsage(await api.console.modelConnections.usage(ws));
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  const test = async (id: number) => {
    try {
      const r = await api.console.modelConnections.test(ws, id);
      setMsg(r.ok ? `连通正常 · ${r.latencyMs}ms` : `测试失败: ${r.error ?? "unknown"}`);
      await load();
    } catch (e: any) { setMsg(`测试失败: ${e.message}`); }
  };
  const remove = async (cn: ModelConnectionView) => {
    if (!confirm(`确认删除连接 ${cn.name}？（仍为某 Agent 默认连接时会被拒绝）`)) return;
    try { await api.console.modelConnections.remove(ws, cn.id); setMsg("已删除"); await load(); }
    catch (e: any) { setMsg(`删除失败: ${e.message}`); }
  };
  const deliver = async (connId: number, serviceId: number) => {
    try {
      await api.console.modelConnections.deliver(ws, connId, serviceId);
      setMsg("下发完成");
      await load();
    } catch (e: any) { setMsg(`下发失败: ${e.message}`); }
  };

  const closeDrawer = (m: string) => {
    setDrawer({ open: false, editing: null });
    if (m) setMsg(m);
    load();
  };

  return (
    <div data-testid="console-models">
      <PageHead title="模型连接" sub="工作空间级配置（对标 AgentCore 模型管理）：先配置、测试，创建 Agent 时勾选并下发至其 /models 接口；调试对话可会话级切换"
        actions={writable && <Btn kind="pri" testid="mc-create-btn" onClick={() => setDrawer({ open: true, editing: null })}>+ 新建模型连接</Btn>} />
      {msg && <div data-testid="models-msg" className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}

      <div className="mb-2 mt-1 text-xs text-gray-400">已保存的模型连接（工作空间级）</div>
      <div className="overflow-x-auto">
      <table className="mb-5 w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="conn-list">
        <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
          <th className="p-2.5">连接名称</th><th className="p-2.5">model_id</th><th className="p-2.5">Base URL</th>
          <th className="p-2.5">状态</th><th className="p-2.5">已下发</th><th className="p-2.5 text-right">操作</th>
        </tr></thead>
        <tbody>
          {conns.map((cn) => (
            <tr key={cn.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 font-medium">{cn.name}</td>
              <td className="p-2.5 font-mono text-xs">{cn.modelId}</td>
              <td className="p-2.5 max-w-[220px] truncate font-mono text-[11px] text-gray-500" title={cn.baseUrl}>{cn.baseUrl}</td>
              <td className="p-2.5"><Badge status={cn.status}>{cn.status === "verified" ? "已验证" : cn.status === "failed" ? "测试失败" : "未验证"}</Badge></td>
              <td className="p-2.5 text-xs">{cn.deliveryCount > 0 ? `${cn.deliveryCount} 个` : "未下发"}</td>
              <td className="p-2.5 text-right">
                <span className="inline-flex gap-1">
                  <Btn sm onClick={() => test(cn.id)}>测试</Btn>
                  {writable && <Btn sm onClick={() => setDrawer({ open: true, editing: cn })}>编辑</Btn>}
                  {writable && <Btn sm kind="danger" onClick={() => remove(cn)}>删除</Btn>}
                </span>
              </td>
            </tr>
          ))}
          {conns.length === 0 && <tr><td colSpan={6} className="p-6 text-center text-sm text-gray-400">暂无连接，点右上角「新建模型连接」创建第一条</td></tr>}
        </tbody>
      </table>
      </div>

      <div className="mb-2 mt-1 flex items-center justify-between">
        <span className="text-xs text-gray-400">Agent 使用情况（含运行时模型清单）</span>
        <span className="flex items-center gap-3 text-[11px] text-gray-400">
          <span className="inline-flex items-center gap-1"><i className="size-2 rounded-full bg-blue-500" />本空间下发</span>
          <span className="inline-flex items-center gap-1"><i className="size-2 rounded-full bg-gray-300" />存量/系统</span>
          <span className="inline-flex items-center gap-1"><i className="size-2 rounded-full bg-emerald-500" />默认</span>
        </span>
      </div>
      {usage.length === 0 ? (
        <Card><div className="py-8 text-center text-sm text-gray-400">空间内暂无 Agent</div></Card>
      ) : (
        <div className="mb-5 grid grid-cols-1 gap-3 xl:grid-cols-2" data-testid="usage-table">
          {usage.map((u) => {
            const conns = u.connections ?? []; // nil 序列化兜底
            const rt = u.runtimeModels ?? null; // null = 业务面拉取失败
            const failed = conns.filter((c) => c.status === "failed");
            const pending = conns.filter((c) => c.status === "pending");
            const synced = conns.filter((c) => c.status === "synced");
            const deliverable = conns.filter((c) => c.status !== "synced");
            return (
              <div key={u.serviceId} data-testid={`usage-card-${u.serviceId}`}
                className="flex flex-col rounded-xl border border-gray-200 bg-white">
                {/* 头部：Agent + 默认模型 + 下发状态徽标 + 操作 */}
                <div className="flex items-center gap-2.5 border-b border-gray-100 px-4 py-3">
                  <span className="flex size-7 flex-none items-center justify-center rounded-lg bg-gradient-to-br from-violet-500 to-indigo-500 text-[12px] font-semibold text-white">
                    {(u.displayName || "?").slice(0, 1)}
                  </span>
                  <div className="min-w-0 flex-1">
                    <b className="block truncate text-[13px] font-semibold">{u.displayName}</b>
                    <small className="text-[11px] text-gray-400">
                      默认模型：{(() => {
                        const rtDef = (rt ?? []).find((m) => m.isDefault);
                        return rtDef ? rtDef.name : (u.defaultConnectionName || "系统默认");
                      })()}
                    </small>
                  </div>
                  {failed.length > 0 ? <Badge status="error">下发失败 {failed.length}</Badge>
                    : pending.length > 0 ? <Badge status="pending">待同步 {pending.length}</Badge>
                    : synced.length > 0 ? <Badge status="running">已同步 {synced.length}</Badge>
                    : <Badge status="stopped">未下发</Badge>}
                  {writable && deliverable.length > 0 && (
                    <Btn sm kind="pri" testid={`deliver-${u.serviceId}`}
                      onClick={() => Promise.all(deliverable.map((c) => deliver(c.connectionId, u.serviceId)))}>
                      {synced.length > 0 ? "重新下发" : "下发"}
                    </Btn>
                  )}
                </div>
                {/* 下发连接明细（本空间勾选的连接在该 Agent 上的状态） */}
                {conns.length > 0 && (
                  <div className="border-b border-gray-100 px-4 py-2.5">
                    <div className="mb-1.5 text-[11px] font-medium text-gray-400">本空间连接下发</div>
                    <div className="flex flex-wrap gap-1.5">
                      {conns.map((c) => (
                        <span key={c.connectionId} title={c.error || `${c.name} → ${c.remoteModelId || "-"}`}
                          className={`inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-[11px] ${
                            c.status === "synced" ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                            : c.status === "failed" ? "border-red-200 bg-red-50 text-red-600"
                            : "border-amber-200 bg-amber-50 text-amber-700"}`}>
                          <i className={`size-1.5 rounded-full ${c.status === "synced" ? "bg-emerald-500" : c.status === "failed" ? "bg-red-500" : "bg-amber-500"}`} />
                          {c.name}
                          <span className="text-gray-400">
                            {c.status === "synced" ? "已同步" : c.status === "failed" ? "失败" : "待同步"}
                          </span>
                        </span>
                      ))}
                    </div>
                  </div>
                )}
                {/* 运行时模型清单（Agent 侧 /models 真实状态） */}
                <div className="flex-1 px-4 py-2.5">
                  <div className="mb-1.5 flex items-center justify-between">
                    <span className="text-[11px] font-medium text-gray-400">运行时模型（Agent 侧实际加载）</span>
                    {rt !== null && <span className="text-[11px] text-gray-400">{rt.length} 个</span>}
                  </div>
                  {rt === null ? (
                    <div className="flex items-center gap-1.5 text-xs text-gray-400">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="size-3.5"><circle cx="12" cy="12" r="9" /><path d="M12 8v4m0 4h.01" /></svg>
                      拉取失败（业务面不可达或非 running）
                    </div>
                  ) : rt.length === 0 ? (
                    <div className="text-xs text-gray-400">暂无（Agent 侧 model_config 为空）</div>
                  ) : (
                    <div className="flex max-h-[104px] flex-wrap gap-1.5 overflow-y-auto">
                      {rt.map((m) => (
                        <span key={m.id}
                          title={`${m.name}\nmodel_id: ${m.modelId}\n来源: ${m.baseUrl || "系统内置"}${m.delivered ? "\n由本空间连接下发" : ""}`}
                          className={`inline-flex max-w-full items-center gap-1 rounded-md border px-2 py-0.5 text-[11px] ${
                            m.isDefault ? "border-emerald-300 bg-emerald-50 font-medium text-emerald-800"
                            : m.delivered ? "border-blue-200 bg-blue-50 text-blue-700"
                            : "border-gray-200 bg-gray-50 text-gray-600"}`}>
                          {m.isDefault && (
                            <svg viewBox="0 0 24 24" fill="currentColor" className="size-3 text-emerald-500"><path d="M12 2l2.4 4.9 5.4.8-3.9 3.8.9 5.4L12 14.4 7.2 16.9l.9-5.4L4.2 7.7l5.4-.8L12 2z" /></svg>
                          )}
                          <span className="truncate">{m.name}</span>
                          <span className="text-gray-400">
                            {m.source === "system" ? "系统" : m.delivered ? "已下发" : "存量"}
                          </span>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
      <Note>使用链路：创建/更新 Agent 勾选连接 → 默认连接经发布 env 注入（LLM_BASE_URL/LLM_MODEL/LLM_MODEL_ID/LLM_API_KEY → 服务 Secret）；Agent 就绪后全部勾选连接幂等下发至其 <code>/models</code> 接口，调试对话可会话级切换。</Note>

      <Drawer open={drawer.open} testid="conn-drawer" width={480}
        title={drawer.editing ? `编辑连接 · ${drawer.editing.name}` : "新建模型连接"}
        onClose={() => closeDrawer("")}>
        <ConnForm ws={ws} editing={drawer.editing} onDone={closeDrawer} />
      </Drawer>
    </div>
  );
}
