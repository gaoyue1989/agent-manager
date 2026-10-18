"use client";
// 用户侧 · 成员管理（M8 §6，demo c/members）：成员表 + 邀请弹窗（invitable-users）+ 角色变更/移除。
import { useCallback, useEffect, useState } from "react";
import { api, type MemberView, type UserView } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole, useMe } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";

export default function MembersPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const { me } = useMe();
  const writable = role === "owner" || role === "admin";
  const [members, setMembers] = useState<MemberView[]>([]);
  const [invitable, setInvitable] = useState<UserView[]>([]);
  const [msg, setMsg] = useState("");
  const [inviting, setInviting] = useState(false);
  const [form, setForm] = useState({ userId: 0, role: "member" });

  const load = useCallback(async () => {
    try {
      setMembers(await api.console.members.list(ws));
      if (writable) setInvitable(await api.console.members.invitable(ws));
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws, writable]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  const invite = async () => {
    try {
      await api.console.members.invite(ws, form.userId, form.role);
      setInviting(false);
      setMsg("成员已加入");
      await load();
    } catch (e: any) { setMsg(`邀请失败: ${e.message}`); }
  };
  const changeRole = async (m: MemberView, to: string) => {
    try { await api.console.members.patchRole(ws, m.userId, to); setMsg("角色已变更"); await load(); }
    catch (e: any) { setMsg(`变更失败: ${e.message}`); }
  };
  const remove = async (m: MemberView) => {
    if (!confirm(`确认移除成员 ${m.user?.displayName || m.user?.username}？`)) return;
    try { await api.console.members.remove(ws, m.userId); await load(); }
    catch (e: any) { setMsg(`移除失败: ${e.message}`); }
  };

  const nextRole = (r: string) => (r === "admin" ? "member" : "admin");

  return (
    <div data-testid="console-members">
      <PageHead title="成员管理" sub="用户由管理员在运维侧创建后可被邀请（用户自注册不开放 · 决议 6）"
        actions={writable && <Btn kind="pri" testid="member-invite-btn" onClick={() => setInviting(true)}>+ 邀请成员</Btn>} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="member-table">
        <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
          <th className="p-2.5">成员</th><th className="p-2.5">空间角色</th><th className="p-2.5">平台角色</th>
          <th className="p-2.5">加入时间</th><th className="p-2.5 text-right">操作</th>
        </tr></thead>
        <tbody>
          {members.map((m) => (
            <tr key={m.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 font-medium">
                {m.user?.displayName || m.user?.username || `#${m.userId}`}
                {me?.user.id === m.userId && <Tag>你</Tag>}
              </td>
              <td className="p-2.5"><Badge status={m.role === "member" ? "stopped" : "running"}>{m.role}</Badge></td>
              <td className="p-2.5 text-xs">{m.user?.role === "admin" ? "管理员" : "开发者"}</td>
              <td className="p-2.5 text-xs text-gray-500">{new Date(m.createdAt).toLocaleDateString()}</td>
              <td className="p-2.5 text-right">
                {writable && m.role !== "owner" ? (
                  <span className="inline-flex gap-1">
                    <Btn sm onClick={() => changeRole(m, nextRole(m.role))}>改为 {nextRole(m.role)}</Btn>
                    <Btn sm kind="danger" onClick={() => remove(m)}>移除</Btn>
                  </span>
                ) : <span className="text-xs text-gray-400">—</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Note>权限：owner/admin 可发布与改资产，member 只读 + 调试（设计 §5.1）。owner 行无操作（空间必须有 owner）。</Note>

      {inviting && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" data-testid="member-invite-dialog">
          <Card title="邀请成员" className="w-[380px]">
            <label className="mb-1 block text-xs font-medium">用户 *</label>
            <select value={form.userId} onChange={(e) => setForm({ ...form, userId: Number(e.target.value) })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value={0}>— 选择未加入的用户 —</option>
              {invitable.map((u) => <option key={u.id} value={u.id}>{u.displayName || u.username}（{u.username}）</option>)}
            </select>
            <label className="mb-1 block text-xs font-medium">空间角色</label>
            <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value="member">member（只读 + 调试）</option>
              <option value="admin">admin（可发布与改资产）</option>
            </select>
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setInviting(false)}>取消</Btn>
              <Btn kind="pri" disabled={!form.userId} onClick={invite}>邀请</Btn>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
