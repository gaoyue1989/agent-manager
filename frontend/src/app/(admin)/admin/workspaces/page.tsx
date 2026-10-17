"use client";
// 运维侧 · 工作空间管理（demo a/workspaces 还原）：列表聚合 + 创建弹窗 + 停用 + 空间内 Agent/纳管。
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, type WorkspaceAdminView, type UserView, type ServiceRec } from "@/lib/api";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";

export default function WorkspacesPage() {
  const [list, setList] = useState<WorkspaceAdminView[]>([]);
  const [users, setUsers] = useState<UserView[]>([]);
  const [msg, setMsg] = useState("");
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: "", description: "", ownerUserId: 0, maxAgents: 20 });
  const [agents, setAgents] = useState<{ ws: WorkspaceAdminView; items: ServiceRec[] } | null>(null);
  const [bindTarget, setBindTarget] = useState<WorkspaceAdminView | null>(null);

  const load = useCallback(async () => {
    try {
      setList(await api.admin.listWorkspaces());
      setUsers(await api.admin.listUsers());
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const create = async () => {
    try {
      await api.admin.createWorkspace({
        name: form.name.trim(), description: form.description,
        ownerUserId: form.ownerUserId || undefined,
        quota: form.maxAgents > 0 ? { maxAgents: form.maxAgents } : undefined,
      });
      setCreating(false);
      setForm({ name: "", description: "", ownerUserId: 0, maxAgents: 20 });
      setMsg("工作空间已创建");
      await load();
    } catch (e: any) { setMsg(`创建失败: ${e.message}`); }
  };

  const toggleStatus = async (w: WorkspaceAdminView) => {
    try {
      await api.admin.patchWorkspace(w.id, { status: w.status === "active" ? "disabled" : "active" });
      await load();
    } catch (e: any) { setMsg(`操作失败: ${e.message}`); }
  };

  const remove = async (w: WorkspaceAdminView) => {
    if (!confirm(`确认删除工作空间 ${w.name}（仅空空间可删）？`)) return;
    try { await api.admin.deleteWorkspace(w.id); setMsg("已删除"); await load(); }
    catch (e: any) { setMsg(`删除失败: ${e.message}`); }
  };

  const showAgents = async (w: WorkspaceAdminView) => {
    try { setAgents({ ws: w, items: await api.admin.workspaceAgents(w.id) }); }
    catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  };

  const doBind = async (serviceId: number) => {
    if (!bindTarget) return;
    try {
      await api.admin.bind(bindTarget.id, serviceId);
      setMsg("已纳管");
      if (agents?.ws.id === bindTarget.id) await showAgents(bindTarget);
    } catch (e: any) { setMsg(`纳管失败: ${e.message}`); }
  };

  return (
    <div data-testid="admin-workspaces">
      <PageHead title="工作空间管理" sub="运维侧新增 · 空间是用户侧资源隔离与协作单元（不等于运行时隔离域，checkpoint 库/Redis 前缀仍需发布 env 显式配置）"
        actions={<Btn kind="pri" testid="ws-create-btn" onClick={() => setCreating(true)}>+ 创建工作空间</Btn>} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="overflow-x-auto">
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="ws-table">
        <thead>
          <tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
            <th className="p-2.5">空间</th><th className="p-2.5">Slug</th><th className="p-2.5">Owner</th>
            <th className="p-2.5">成员</th><th className="p-2.5">Agent</th><th className="p-2.5">配额</th>
            <th className="p-2.5">状态</th><th className="p-2.5 text-right">操作</th>
          </tr>
        </thead>
        <tbody>
          {list.map((w) => (
            <tr key={w.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 font-medium">{w.name}</td>
              <td className="p-2.5 font-mono text-xs text-gray-500">{w.slug}</td>
              <td className="p-2.5 text-xs">{w.owner?.displayName || w.owner?.username || "—"}</td>
              <td className="p-2.5">{w.memberCount}</td>
              <td className="p-2.5">{w.agentCount}</td>
              <td className="p-2.5 text-xs text-gray-500">{w.quota?.maxAgents ? `max_agents: ${w.quota.maxAgents}` : "—"}</td>
              <td className="p-2.5"><Badge status={w.status === "active" ? "running" : "stopped"}>{w.status}</Badge></td>
              <td className="p-2.5 text-right">
                <span className="inline-flex gap-1">
                  <Btn sm onClick={() => showAgents(w)}>查看 Agent</Btn>
                  <Btn sm onClick={() => toggleStatus(w)}>{w.status === "active" ? "停用" : "启用"}</Btn>
                  {w.slug !== "default" && <Btn sm kind="danger" onClick={() => remove(w)}>删除</Btn>}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      <Note tone="warn">工作空间是控制台层的资源归属，不等于运行时隔离域；每服务 checkpoint 库名与 Redis 前缀仍需在发布 env 中显式配置（已知缺口，见设计文档 §11）。</Note>

      {creating && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" data-testid="ws-create-dialog">
          <Card title="创建工作空间" className="w-[420px]">
            <label className="mb-1 block text-xs font-medium">名称 *</label>
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">描述</label>
            <input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">Owner（可选）</label>
            <select value={form.ownerUserId} onChange={(e) => setForm({ ...form, ownerUserId: Number(e.target.value) })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value={0}>— 暂不指派 —</option>
              {users.map((u) => <option key={u.id} value={u.id}>{u.displayName || u.username}（{u.username}）</option>)}
            </select>
            <label className="mb-1 block text-xs font-medium">配额 maxAgents</label>
            <input type="number" min={0} value={form.maxAgents} onChange={(e) => setForm({ ...form, maxAgents: Number(e.target.value) })}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setCreating(false)}>取消</Btn>
              <Btn kind="pri" disabled={!form.name.trim()} onClick={create}>创建</Btn>
            </div>
          </Card>
        </div>
      )}

      {agents && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" data-testid="ws-agents-dialog">
          <Card title={`空间内 Agent · ${agents.ws.name}`} className="max-h-[70vh] w-[760px] overflow-auto">
            {agents.items.length === 0 ? <p className="text-sm text-gray-400">空间内暂无 Agent</p> : (
              <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead><tr className="border-b text-left text-xs text-gray-500">
                  <th className="p-2">服务</th><th className="p-2">状态</th><th className="p-2">Endpoint</th><th className="p-2 text-right">纳管/移出</th>
                </tr></thead>
                <tbody>
                  {agents.items.map((s) => (
                    <tr key={s.id} className="border-b last:border-0">
                      <td className="p-2 font-medium"><Link href={`/admin/services/${s.id}`} className="text-blue-600 hover:underline">{s.displayName}</Link>
                        <div className="font-mono text-[11px] text-gray-400">{s.k8sName}</div></td>
                      <td className="p-2"><Badge status={s.status} /></td>
                      <td className="p-2 font-mono text-[11px] text-gray-500">{s.endpoint || "—"}</td>
                      <td className="p-2 text-right">
                        {agents.ws.slug === "default" && (
                          <span className="inline-flex gap-1">
                            <select onChange={(e) => { const w = list.find((x) => x.slug !== "default" && x.id === Number(e.target.value)); if (w) { setBindTarget(w); doBind(s.id); } }}
                              defaultValue="" className="rounded border border-gray-300 px-1.5 py-1 text-xs">
                              <option value="">纳管到…</option>
                              {list.filter((x) => x.slug !== "default" && x.status === "active").map((x) => (
                                <option key={x.id} value={x.id}>{x.name}</option>
                              ))}
                            </select>
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>
            )}
            <div className="mt-3 text-right"><Btn onClick={() => setAgents(null)}>关闭</Btn></div>
          </Card>
        </div>
      )}
      {bindTarget && <Tag>{bindTarget.name}</Tag>}
    </div>
  );
}
