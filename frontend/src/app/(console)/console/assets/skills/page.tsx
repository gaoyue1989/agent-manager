"use client";
// 用户侧 · Skill 管理（M8 §3）：L4 用户技能（所选 Agent 直连，per-user 隔离如实标注）+ 包级只读聚合。
import { useCallback, useEffect, useState } from "react";
import { api, type AgentListItem } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole, useMe } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead } from "@/components/ui";

export default function SkillsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const { me } = useMe();
  const writable = role === "owner" || role === "admin";
  const [agents, setAgents] = useState<AgentListItem[]>([]);
  const [agentId, setAgentId] = useState(0);
  const [l4, setL4] = useState<{ name: string; description?: string }[]>([]);
  const [pkgSkills, setPkgSkills] = useState<{ name: string; from: string }[]>([]);
  const [msg, setMsg] = useState("");

  useEffect(() => {
    api.console.agents(ws).then((rows) => {
      setAgents(rows);
      const first = rows.find((r) => r.status === "running");
      if (first) setAgentId(first.id);
    }).catch((e) => setMsg(e.message));
  }, [ws]);

  const loadL4 = useCallback(async () => {
    if (!agentId) return;
    const short = agents.find((a) => a.id === agentId)?.shortName;
    if (!short) return;
    try {
      const resp = await fetch(`/agent/${short}/skills/users`);
      const d = resp.ok ? await resp.json() : [];
      setL4(Array.isArray(d) ? d : d?.skills ?? []);
    } catch { setL4([]); }
  }, [agentId, agents]);
  useEffect(() => { loadL4(); }, [loadL4]);

  // 包级只读聚合：本空间各服务 skillsJson
  useEffect(() => {
    (async () => {
      try {
        const rows = await api.console.agents(ws);
        const out: { name: string; from: string }[] = [];
        for (const a of rows) {
          const d = await api.console.agentDetail(ws, a.id).catch(() => null);
          if (!d?.skillsJson) continue;
          try {
            const skills = JSON.parse(d.skillsJson);
            for (const s of skills) out.push({ name: s.name, from: `${a.displayName} v${a.version}` });
          } catch { /* 忽略坏 JSON */ }
        }
        setPkgSkills(out);
      } catch { /* 忽略 */ }
    })();
  }, [ws]);

  const upload = async (file: File) => {
    const short = agents.find((a) => a.id === agentId)?.shortName;
    if (!short || !file) return;
    const fd = new FormData();
    fd.append("file", file);
    fd.append("userId", `u-${me?.user.id ?? 0}`);
    try {
      const resp = await fetch(`/agent/${short}/skills/users/upload`, { method: "POST", body: fd });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      setMsg("技能已上传（对调试对话即时生效）");
      await loadL4();
    } catch (e: any) { setMsg(`上传失败: ${e.message}`); }
  };
  const removeL4 = async (name: string) => {
    const short = agents.find((a) => a.id === agentId)?.shortName;
    if (!short || !confirm(`确认删除技能 ${name}？`)) return;
    await fetch(`/agent/${short}/skills/users/${encodeURIComponent(name)}`, { method: "DELETE" }).catch(() => {});
    await loadL4();
  };

  return (
    <div data-testid="console-skills">
      <PageHead title="Skill 管理" sub="用户级技能（L4）对本人全部调试会话可用；包级技能随 OAF 包只读"
        actions={
          <select value={agentId} onChange={(e) => setAgentId(Number(e.target.value))} data-testid="skill-agent-select"
            className="rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]">
            <option value={0}>— 选择 Agent（取数目标）—</option>
            {agents.filter((a) => a.status === "running").map((a) => <option key={a.id} value={a.id}>{a.displayName}</option>)}
          </select>
        } />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <div>
          <div className="mb-2 text-xs text-gray-400">本空间用户技能（L4 · per-user：操作的是当前登录用户的技能集）</div>
          <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="l4-table">
            <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">技能</th><th className="p-2.5">描述</th><th className="p-2.5">状态</th><th className="p-2.5 text-right">操作</th>
            </tr></thead>
            <tbody>
              {l4.map((s) => (
                <tr key={s.name} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="p-2.5 font-medium">{s.name}</td>
                  <td className="p-2.5 max-w-[220px] truncate text-xs text-gray-500">{s.description || "—"}</td>
                  <td className="p-2.5"><Badge status="running">启用</Badge></td>
                  <td className="p-2.5 text-right">{writable && <Btn sm kind="danger" onClick={() => removeL4(s.name)}>删除</Btn>}</td>
                </tr>
              ))}
              {l4.length === 0 && <tr><td colSpan={4} className="p-5 text-center text-sm text-gray-400">{agentId ? "当前用户在该 Agent 上暂无 L4 技能" : "请先选择取数目标 Agent"}</td></tr>}
            </tbody>
          </table>
          {writable && agentId > 0 && (
            <label className="mt-3 inline-block cursor-pointer rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">
              上传技能（SKILL.md zip）
              <input type="file" accept=".zip" className="hidden" onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
            </label>
          )}
        </div>
        <div>
          <div className="mb-2 text-xs text-gray-400">包级技能（随 OAF 包 · 只读）</div>
          <Card>
            <div className="flex flex-wrap gap-1.5">
              {pkgSkills.map((s, i) => (
                <span key={i} className="inline-flex items-center rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-600">
                  {s.name}（{s.from}）
                </span>
              ))}
              {pkgSkills.length === 0 && <span className="text-xs text-gray-400">空间内各服务包暂未声明技能</span>}
            </div>
            <div className="mt-3 text-xs text-gray-400">编辑包级技能请进入 Agent 详情 → 版本 → 编辑（copy-on-write）</div>
          </Card>
          <Note>L4 技能存储在各 agent-framework 实例内（per-service KV），跨 Agent 不共享；调试对话即时生效（既有机制回归）。</Note>
        </div>
      </div>
    </div>
  );
}
