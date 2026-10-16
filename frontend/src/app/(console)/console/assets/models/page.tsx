"use client";
// 用户侧 · 模型连接（M5 §3.1，demo c/models 双栏）：连接表 + Agent 使用情况表 + 新建表单。
import { useCallback, useEffect, useState } from "react";
import { api, type ModelConnectionView, type ModelConnUsageRow } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead } from "@/components/ui";

export default function ModelsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin";
  const [conns, setConns] = useState<ModelConnectionView[]>([]);
  const [usage, setUsage] = useState<ModelConnUsageRow[]>([]);
  const [msg, setMsg] = useState("");
  const [form, setForm] = useState({ name: "", modelId: "", baseUrl: "", apiKey: "", temperature: 0.3, maxTokens: 16384 });
  const [editing, setEditing] = useState<ModelConnectionView | null>(null);

  const load = useCallback(async () => {
    try {
      setConns(await api.console.modelConnections.list(ws));
      setUsage(await api.console.modelConnections.usage(ws));
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  const create = async () => {
    try {
      await api.console.modelConnections.create(ws, form);
      setForm({ name: "", modelId: "", baseUrl: "", apiKey: "", temperature: 0.3, maxTokens: 16384 });
      setMsg("连接已保存（未验证）");
      await load();
    } catch (e: any) { setMsg(`保存失败: ${e.message}`); }
  };
  const test = async (id: number) => {
    try {
      const r = await api.console.modelConnections.test(ws, id);
      setMsg(r.ok ? `连通正常 · ${r.latencyMs}ms` : `测试失败: ${r.error ?? "unknown"}`);
      await load();
    } catch (e: any) { setMsg(`测试失败: ${e.message}`); }
  };
  const patchBase = async (cn: ModelConnectionView, body: object) => {
    try { await api.console.modelConnections.patch(ws, cn.id, body); setMsg("已保存（下发行已重置为待同步）"); await load(); }
    catch (e: any) { setMsg(`保存失败: ${e.message}`); }
  };
  const remove = async (cn: ModelConnectionView) => {
    if (!confirm(`确认删除连接 ${cn.name}？（仍为某 Agent 默认连接时会被拒绝）`)) return;
    try { await api.console.modelConnections.remove(ws, cn.id); await load(); }
    catch (e: any) { setMsg(`删除失败: ${e.message}`); }
  };
  const deliver = async (connId: number, serviceId: number) => {
    try {
      await api.console.modelConnections.deliver(ws, connId, serviceId);
      setMsg("下发完成");
      await load();
    } catch (e: any) { setMsg(`下发失败: ${e.message}`); }
  };

  return (
    <div data-testid="console-models">
      <PageHead title="模型连接" sub="工作空间级配置（对标 AgentCore 模型管理）：先配置、测试，创建 Agent 时勾选并下发至其 /models 接口；调试对话可会话级切换" />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1.6fr_1fr]">
        <div>
          <div className="mb-2 text-xs text-gray-400">已保存的模型连接（工作空间级）</div>
          <table className="mb-4 w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="conn-list">
            <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">连接名称</th><th className="p-2.5">model_id</th><th className="p-2.5">Base URL</th>
              <th className="p-2.5">状态</th><th className="p-2.5">已下发</th><th className="p-2.5 text-right">操作</th>
            </tr></thead>
            <tbody>
              {conns.map((cn) => (
                <tr key={cn.id} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="p-2.5 font-medium">{cn.name}</td>
                  <td className="p-2.5 font-mono text-xs">{cn.modelId}</td>
                  <td className="p-2.5 max-w-[180px] truncate font-mono text-[11px] text-gray-500" title={cn.baseUrl}>{cn.baseUrl}</td>
                  <td className="p-2.5"><Badge status={cn.status}>{cn.status === "verified" ? "已验证" : cn.status === "failed" ? "测试失败" : "未验证"}</Badge></td>
                  <td className="p-2.5 text-xs">{cn.deliveryCount > 0 ? `${cn.deliveryCount} 个` : "未下发"}</td>
                  <td className="p-2.5 text-right">
                    <span className="inline-flex gap-1">
                      {writable && <Btn sm onClick={() => setEditing(cn)}>编辑</Btn>}
                      <Btn sm onClick={() => test(cn.id)}>测试</Btn>
                      {writable && <Btn sm kind="danger" onClick={() => remove(cn)}>删除</Btn>}
                    </span>
                  </td>
                </tr>
              ))}
              {conns.length === 0 && <tr><td colSpan={6} className="p-5 text-center text-sm text-gray-400">暂无连接，右侧表单创建第一条</td></tr>}
            </tbody>
          </table>

          <div className="mb-2 text-xs text-gray-400">Agent 使用情况</div>
          <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="usage-table">
            <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">Agent</th><th className="p-2.5">默认模型连接</th><th className="p-2.5">已同步连接</th>
              <th className="p-2.5">下发状态</th><th className="p-2.5 text-right">操作</th>
            </tr></thead>
            <tbody>
              {usage.map((u) => {
                const worst = u.connections.some((c) => c.status === "failed") ? "failed"
                  : u.connections.some((c) => c.status === "pending") ? "pending"
                    : u.connections.length > 0 ? "synced" : "";
                return (
                  <tr key={u.serviceId} className="border-b last:border-0 hover:bg-gray-50">
                    <td className="p-2.5 font-medium">{u.displayName}</td>
                    <td className="p-2.5 text-xs">{u.defaultConnectionName || "系统默认"}</td>
                    <td className="p-2.5 text-xs">
                      {u.connections.map((c) => (
                        <span key={c.connectionId} title={c.error} className="mr-1 inline-block rounded bg-gray-100 px-1.5 py-0.5 text-[11px]">{c.name}</span>
                      ))}
                      {u.connections.length === 0 && <span className="text-gray-400">—</span>}
                    </td>
                    <td className="p-2.5">{worst ? <Badge status={worst === "synced" ? "running" : worst}>{worst === "synced" ? "已同步" : worst === "pending" ? "待同步" : "失败"}</Badge> : <span className="text-xs text-gray-400">—</span>}</td>
                    <td className="p-2.5 text-right">
                      {writable && u.connections.length > 0 && (
                        <Btn sm onClick={() => Promise.all(u.connections.filter((c) => c.status !== "synced").map((c) => deliver(c.connectionId, u.serviceId)))}>
                          {worst === "synced" ? "重新下发" : "下发"}
                        </Btn>
                      )}
                    </td>
                  </tr>
                );
              })}
              {usage.length === 0 && <tr><td colSpan={5} className="p-5 text-center text-sm text-gray-400">空间内暂无 Agent</td></tr>}
            </tbody>
          </table>
        </div>
        <div>
          <div className="mb-2 text-xs text-gray-400">新建模型连接</div>
          <Card>
            <label className="mb-1 block text-xs font-medium">连接名称 *</label>
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} data-testid="mc-name"
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">模型 ID *</label>
            <input value={form.modelId} onChange={(e) => setForm({ ...form, modelId: e.target.value })} data-testid="mc-model"
              placeholder="发给推理端点的模型名" className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">Base URL *</label>
            <input value={form.baseUrl} onChange={(e) => setForm({ ...form, baseUrl: e.target.value })} data-testid="mc-url"
              placeholder="OpenAI 兼容端点，如 https://api.deepseek.com/v1"
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">API Key *</label>
            <input type="password" value={form.apiKey} onChange={(e) => setForm({ ...form, apiKey: e.target.value })} data-testid="mc-key"
              placeholder="sk-…" className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
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
            <div className="flex justify-end">
              <Btn kind="pri" testid="mc-save" disabled={!form.name.trim() || !form.modelId.trim() || !form.baseUrl.trim()} onClick={create}>保存并测试</Btn>
            </div>
          </Card>
          <Note>使用链路：创建/更新 Agent 勾选连接 → 默认连接经发布 env 注入（LLM_BASE_URL/LLM_MODEL/LLM_API_KEY → 服务 Secret）；Agent 就绪后全部勾选连接幂等下发至其 <code>/models</code> 接口，调试对话可会话级切换。API Key 落库内网明文、接口掩码回显、下发时经服务 Secret 注入。</Note>
        </div>
      </div>

      {editing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
          <Card title={`编辑连接 · ${editing.name}`} className="w-[420px]">
            <label className="mb-1 block text-xs font-medium">模型 ID</label>
            <input defaultValue={editing.modelId} data-testid="mc-edit-model" onChange={(e) => setEditing({ ...editing, modelId: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">Base URL</label>
            <input defaultValue={editing.baseUrl} data-testid="mc-edit-url" onChange={(e) => setEditing({ ...editing, baseUrl: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">API Key（留空 = 不变；输入空格后清空 = 清除）</label>
            <input type="password" placeholder={editing.apiKeyMasked || "未设置"} data-testid="mc-edit-key"
              onChange={(e) => setEditing({ ...editing, apiKeyMasked: e.target.value })}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setEditing(null)}>取消</Btn>
              <Btn kind="pri" onClick={async () => {
                const key = (editing.apiKeyMasked ?? "").trim();
                await patchBase(editing, {
                  modelId: editing.modelId, baseUrl: editing.baseUrl,
                  ...(key === "" ? {} : { apiKey: key }),
                });
                setEditing(null);
              }}>保存</Btn>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
