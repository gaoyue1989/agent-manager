"use client";
// 用户侧 · 凭证管理（M8 §2.2，demo c/credentials）：API Key 表 + 签发弹窗（作用域全空间/单 Agent）+ 一次性明文。
import { useCallback, useEffect, useState } from "react";
import { api, type AgentListItem, type ApiKeyView } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead } from "@/components/ui";

export default function CredentialsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin";
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [agents, setAgents] = useState<AgentListItem[]>([]);
  const [msg, setMsg] = useState("");
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: "", agentId: 0, expiresInDays: 90 });
  const [plaintext, setPlaintext] = useState("");

  const load = useCallback(async () => {
    try { setKeys(await api.console.apiKeys.list(ws, 0)); setMsg(""); }
    catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws]);
  useEffect(() => {
    if (!ws) return;
    load();
    api.console.agents(ws).then(setAgents).catch(() => {});
  }, [ws, load]);

  const issue = async () => {
    try {
      const out = await api.console.apiKeys.create(ws, {
        name: form.name.trim(),
        agentId: form.agentId || undefined,
        expiresInDays: form.expiresInDays,
      });
      setPlaintext(out.plaintext);
      setCreating(false);
      setForm({ name: "", agentId: 0, expiresInDays: 90 });
      await load();
    } catch (e: any) { setMsg(`签发失败: ${e.message}`); }
  };
  const revoke = async (k: ApiKeyView) => {
    if (!confirm(`确认吊销 ${k.name}？（即时生效，行保留审计）`)) return;
    try { await api.console.apiKeys.revoke(ws, k.id); await load(); }
    catch (e: any) { setMsg(`吊销失败: ${e.message}`); }
  };

  return (
    <div data-testid="console-credentials">
      <PageHead title="凭证管理" sub="工作空间级 API Key，用于对外调用 Agent（A2A / HTTP）；明文仅签发时展示一次"
        actions={writable && <Btn kind="pri" testid="key-issue-btn" onClick={() => setCreating(true)}>+ 签发凭证</Btn>} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="key-table">
        <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
          <th className="p-2.5">名称</th><th className="p-2.5">Key 前缀</th><th className="p-2.5">作用范围</th>
          <th className="p-2.5">过期时间</th><th className="p-2.5">状态</th><th className="p-2.5 text-right">操作</th>
        </tr></thead>
        <tbody>
          {keys.map((k) => (
            <tr key={k.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 font-medium">{k.name}</td>
              <td className="p-2.5 font-mono text-xs">{k.keyPrefix}</td>
              <td className="p-2.5 text-xs">{k.agentId ? (k.agentName || `Agent #${k.agentId}`) : "空间内全部 Agent"}</td>
              <td className="p-2.5 text-xs">{k.expiresAt ? new Date(k.expiresAt).toLocaleDateString() : "永不过期"}</td>
              <td className="p-2.5"><Badge status={k.status === "active" ? "running" : "stopped"}>{k.status === "active" ? "启用" : k.status === "expired" ? "已过期" : "已吊销"}</Badge></td>
              <td className="p-2.5 text-right">
                {writable && k.status === "active" && <Btn sm kind="danger" onClick={() => revoke(k)}>吊销</Btn>}
              </td>
            </tr>
          ))}
          {keys.length === 0 && <tr><td colSpan={6} className="p-6 text-center text-sm text-gray-400">暂无凭证</td></tr>}
        </tbody>
      </table>
      <Note>一期凭证仅身份标识与控制台内记账，数据面校验在二期网关（auth_request + introspect）生效；服务级敏感 env（LLM_API_KEY / OPENSANDBOX_API_KEY 等）由平台自动路由进 K8s Secret（{"{name}-env-secret"}），不在此处管理。</Note>

      {creating && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" data-testid="key-issue-dialog">
          <Card title="签发凭证" className="w-[400px]">
            <label className="mb-1 block text-xs font-medium">名称 *</label>
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">作用范围</label>
            <select value={form.agentId} onChange={(e) => setForm({ ...form, agentId: Number(e.target.value) })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value={0}>空间内全部 Agent</option>
              {agents.map((a) => <option key={a.id} value={a.id}>{a.displayName}</option>)}
            </select>
            <label className="mb-1 block text-xs font-medium">有效期（天，0 = 永不过期）</label>
            <input type="number" min={0} value={form.expiresInDays} onChange={(e) => setForm({ ...form, expiresInDays: Number(e.target.value) })}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setCreating(false)}>取消</Btn>
              <Btn kind="pri" disabled={!form.name.trim()} onClick={issue}>签发</Btn>
            </div>
          </Card>
        </div>
      )}
      {plaintext && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" data-testid="key-plaintext-dialog">
          <Card title="凭证已签发 · 明文仅此一次展示" className="w-[460px]">
            <div className="mb-2 break-all rounded-md bg-gray-900 p-3 font-mono text-xs text-emerald-300">{plaintext}</div>
            <Note tone="warn">关闭后不再可见，请立即复制保存。</Note>
            <div className="flex justify-end gap-2">
              <Btn onClick={() => navigator.clipboard?.writeText(plaintext)}>复制</Btn>
              <Btn kind="pri" onClick={() => setPlaintext("")}>我已保存，关闭</Btn>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
