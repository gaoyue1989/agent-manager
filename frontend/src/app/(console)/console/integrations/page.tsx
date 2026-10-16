"use client";
// 用户侧 · 集成 Agent（M7 §3.1，demo c/integrations 双栏）：已建立集成关系表 + 可注册候选端点。
import { useCallback, useEffect, useState } from "react";
import { api, type IntegrationCandidate, type IntegrationView } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";

export default function IntegrationsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin";
  const [relations, setRelations] = useState<IntegrationView[]>([]);
  const [candidates, setCandidates] = useState<IntegrationCandidate[]>([]);
  const [referencedNames, setReferencedNames] = useState<Record<number, string>>({});
  const [msg, setMsg] = useState("");
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ memberServiceId: 0, askPolicy: "PROPAGATE", authHeaderKey: "X-Agent-Protocol-Token" });

  const load = useCallback(async () => {
    try {
      const data = await api.console.integrations.list(ws);
      setRelations(data.relations);
      setCandidates(data.candidates);
      // 引用方名回显（console agents 列表查名）
      const agents = await api.console.agents(ws);
      const nameById: Record<number, string> = {};
      for (const a of agents) nameById[a.id] = a.displayName;
      setReferencedNames(nameById);
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  const create = async () => {
    try {
      await api.console.integrations.create(ws, form);
      setCreating(false);
      setMsg("集成关系已登记（创建 Agent 时可在资产配置区勾选）");
      await load();
    } catch (e: any) { setMsg(`登记失败: ${e.message}`); }
  };
  const remove = async (it: IntegrationView) => {
    if (!confirm(`确认解除与 ${it.name} 的集成？（被生成包引用时会被拒绝）`)) return;
    try { await api.console.integrations.remove(ws, it.id); await load(); }
    catch (e: any) { setMsg(`解除失败: ${e.message}`); }
  };
  const toggleEnabled = async (it: IntegrationView) => {
    try { await api.console.integrations.patch(ws, it.id, { enabled: !it.enabled }); await load(); }
    catch (e: any) { setMsg(`操作失败: ${e.message}`); }
  };

  return (
    <div data-testid="console-integrations">
      <PageHead title="集成 Agent" sub="将其它 Agent 集成为远程子 agent（Agent Protocol：对端暴露 POST /tasks，运行时 agent_spawn 调度、子事件回流父流）"
        actions={writable && <Btn kind="pri" onClick={() => setCreating(true)}>+ 注册 Agent 端点</Btn>} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1.6fr_1fr]">
        <div>
          <div className="mb-2 text-xs text-gray-400">已建立的集成关系（lead → member）</div>
          <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="integration-table">
            <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">member Agent</th><th className="p-2.5">Endpoint</th><th className="p-2.5">被引用（lead）</th>
              <th className="p-2.5">ask 策略</th><th className="p-2.5">状态</th><th className="p-2.5 text-right">操作</th>
            </tr></thead>
            <tbody>
              {relations.map((it) => (
                <tr key={it.id} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="p-2.5 font-medium">{it.name}</td>
                  <td className="p-2.5 font-mono text-[11px] text-gray-500">{it.endpointUrl}</td>
                  <td className="p-2.5 text-xs">{it.referencedBy.length > 0 ? it.referencedBy.map((id) => referencedNames[id] || `#${id}`).join("、") : <span className="text-gray-400">—</span>}</td>
                  <td className="p-2.5"><Tag>{it.askPolicy}</Tag></td>
                  <td className="p-2.5"><Badge status={it.enabled ? "running" : "stopped"}>{it.enabled ? "启用" : "停用"}</Badge></td>
                  <td className="p-2.5 text-right">
                    <span className="inline-flex gap-1">
                      {writable && <Btn sm onClick={() => toggleEnabled(it)}>{it.enabled ? "停用" : "启用"}</Btn>}
                      {writable && <Btn sm kind="danger" onClick={() => remove(it)}>解除集成</Btn>}
                    </span>
                  </td>
                </tr>
              ))}
              {relations.length === 0 && <tr><td colSpan={6} className="p-5 text-center text-sm text-gray-400">暂无集成关系（右侧候选表登记）</td></tr>}
            </tbody>
          </table>
          <div className="mt-2.5 text-xs text-gray-400">创建 Agent 时在「资产配置 → SubAgent」勾选已集成端点，写入生成包 subAgents[].endpoint。</div>
        </div>
        <div>
          <div className="mb-2 text-xs text-gray-400">可注册的 Agent 端点（本空间服务）</div>
          <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="candidate-table">
            <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">Agent</th><th className="p-2.5">状态</th><th className="p-2.5 text-right">操作</th>
            </tr></thead>
            <tbody>
              {candidates.map((c) => (
                <tr key={c.serviceId} className="border-b last:border-0 hover:bg-gray-50">
                  <td className="p-2.5 font-medium">{c.displayName}
                    <div className="font-mono text-[11px] text-gray-400">{c.protocolEnabled ? `${c.endpointUrl}/tasks` : "—"}</div>
                  </td>
                  <td className="p-2.5">
                    {!c.protocolEnabled ? <Badge status="stopped">未开启 Agent Protocol</Badge>
                      : c.probe === "online" ? <Badge status="running">POST /tasks 在线</Badge>
                        : <Badge status="error">不可达</Badge>}
                  </td>
                  <td className="p-2.5 text-right">
                    {writable && <Btn sm kind="pri" disabled={!c.protocolEnabled || c.probe !== "online"}
                      onClick={() => { setForm({ ...form, memberServiceId: c.serviceId }); setCreating(true); }}>集成</Btn>}
                  </td>
                </tr>
              ))}
              {candidates.length === 0 && <tr><td colSpan={3} className="p-5 text-center text-sm text-gray-400">空间内暂无服务</td></tr>}
            </tbody>
          </table>
          <Note>对端需开启 <code>agentscope.agent-protocol.enabled=true</code>；服务间认证经 lead 的 <code>AGENT_REMOTE_HEADERS_JSON</code> env 注入（X-Agent-Protocol-Token），值不落包。</Note>
        </div>
      </div>

      {creating && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
          <Card title="注册 Agent 端点" className="w-[420px]">
            <label className="mb-1 block text-xs font-medium">member Agent *</label>
            <select value={form.memberServiceId} onChange={(e) => setForm({ ...form, memberServiceId: Number(e.target.value) })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value={0}>— 选择在线候选 —</option>
              {candidates.filter((c) => c.protocolEnabled && c.probe === "online").map((c) => (
                <option key={c.serviceId} value={c.serviceId}>{c.displayName}（{c.endpointUrl}）</option>
              ))}
            </select>
            <label className="mb-1 block text-xs font-medium">ask 策略</label>
            <select value={form.askPolicy} onChange={(e) => setForm({ ...form, askPolicy: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value="PROPAGATE">PROPAGATE（远端确认回流父流）</option>
              <option value="DENY">DENY（远端 ask 自动拒绝）</option>
            </select>
            <label className="mb-1 block text-xs font-medium">认证头键名（值经创建页「集成凭证」提供，只进 Secret）</label>
            <input value={form.authHeaderKey} onChange={(e) => setForm({ ...form, authHeaderKey: e.target.value })}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setCreating(false)}>取消</Btn>
              <Btn kind="pri" disabled={!form.memberServiceId} onClick={create}>登记</Btn>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
