"use client";
// 运维侧 · 用户管理（demo a/users 还原）：列表 + 创建 + 改角色/重置密码/禁用。
import { useCallback, useEffect, useState } from "react";
import { api, type UserView } from "@/lib/api";
import { Badge, Btn, Card, PageHead } from "@/components/ui";

export default function UsersPage() {
  const [list, setList] = useState<UserView[]>([]);
  const [msg, setMsg] = useState("");
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ username: "", password: "", displayName: "", role: "user" });
  const [resetting, setResetting] = useState<UserView | null>(null);
  const [newPass, setNewPass] = useState("");

  const load = useCallback(async () => {
    try { setList(await api.admin.listUsers()); setMsg(""); }
    catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const create = async () => {
    try {
      await api.admin.createUser(form);
      setCreating(false);
      setForm({ username: "", password: "", displayName: "", role: "user" });
      setMsg("用户已创建");
      await load();
    } catch (e: any) { setMsg(`创建失败: ${e.message}`); }
  };

  const patch = async (id: number, body: object, okMsg: string) => {
    try { await api.admin.patchUser(id, body); setMsg(okMsg); await load(); }
    catch (e: any) { setMsg(`操作失败: ${e.message}`); }
  };

  const doReset = async () => {
    if (!resetting || !newPass) return;
    await patch(resetting.id, { password: newPass }, "密码已重置");
    setResetting(null);
    setNewPass("");
  };

  return (
    <div data-testid="admin-users">
      <PageHead title="用户管理" sub="运维侧新增 · 首个 admin 由 ADMIN_INIT_USER 环境变量种子创建 · 用户自注册不开放（决议 6）"
        actions={<Btn kind="pri" testid="user-create-btn" onClick={() => setCreating(true)}>+ 创建用户</Btn>} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="overflow-x-auto">
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="user-table">
        <thead>
          <tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
            <th className="p-2.5">用户</th><th className="p-2.5">平台角色</th><th className="p-2.5">所属空间</th>
            <th className="p-2.5">最近登录</th><th className="p-2.5">状态</th><th className="p-2.5 text-right">操作</th>
          </tr>
        </thead>
        <tbody>
          {list.map((u) => (
            <tr key={u.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 font-medium">{u.displayName || u.username}<div className="text-[11px] text-gray-400">{u.username}</div></td>
              <td className="p-2.5"><Badge status={u.role === "admin" ? "running" : "stopped"}>{u.role}</Badge></td>
              <td className="p-2.5 text-xs">
                {(u.workspaces ?? []).length === 0 ? <span className="text-gray-400">—</span> :
                  (u.workspaces ?? []).map((w) => <span key={w.id} className="mr-1.5">{w.name}（{w.role}）</span>)}
              </td>
              <td className="p-2.5 text-xs text-gray-500">{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString("zh-CN", { hour12: false }) : "—"}</td>
              <td className="p-2.5"><Badge status={u.status === "active" ? "running" : "stopped"}>{u.status === "active" ? "启用" : "禁用"}</Badge></td>
              <td className="p-2.5 text-right">
                <span className="inline-flex gap-1">
                  <Btn sm onClick={() => patch(u.id, { role: u.role === "admin" ? "user" : "admin" }, "角色已变更")}>
                    改为 {u.role === "admin" ? "user" : "admin"}
                  </Btn>
                  <Btn sm onClick={() => { setResetting(u); setNewPass(""); }}>重置密码</Btn>
                  <Btn sm kind="danger" onClick={() => patch(u.id, { status: u.status === "active" ? "disabled" : "active" },
                    u.status === "active" ? "已禁用" : "已启用")}>
                    {u.status === "active" ? "禁用" : "启用"}
                  </Btn>
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>

      {creating && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" data-testid="user-create-dialog">
          <Card title="创建用户" className="w-[400px]">
            <label className="mb-1 block text-xs font-medium">用户名 *</label>
            <input value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">初始密码 *</label>
            <input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">显示名</label>
            <input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">平台角色</label>
            <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value="user">user（仅用户侧）</option>
              <option value="admin">admin（双侧）</option>
            </select>
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setCreating(false)}>取消</Btn>
              <Btn kind="pri" disabled={!form.username.trim() || !form.password} onClick={create}>创建</Btn>
            </div>
          </Card>
        </div>
      )}

      {resetting && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
          <Card title={`重置密码 · ${resetting.username}`} className="w-[360px]">
            <input type="password" value={newPass} onChange={(e) => setNewPass(e.target.value)} placeholder="新密码"
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setResetting(null)}>取消</Btn>
              <Btn kind="pri" disabled={!newPass} onClick={doReset}>确认重置</Btn>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
