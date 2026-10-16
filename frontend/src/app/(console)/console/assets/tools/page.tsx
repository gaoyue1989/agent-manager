"use client";
// 用户侧 · 工具管理（M6 §3.1，demo c/tools 双栏）：MCP 连接器配置表 + 三态权限策略 + 新建表单。
import { useCallback, useEffect, useState } from "react";
import { api, type McpConnectorView } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";

export default function ToolsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin";
  const [list, setList] = useState<McpConnectorView[]>([]);
  const [selected, setSelected] = useState<McpConnectorView | null>(null);
  const [msg, setMsg] = useState("");
  const [form, setForm] = useState({ name: "", transport: "streamable_http", endpoint: "", authType: "none", authToken: "", timeoutSec: 120, scopeMode: "all" });

  const load = useCallback(async () => {
    try {
      const rows = await api.console.tools.list(ws);
      setList(rows);
      setSelected((cur) => (cur ? rows.find((r) => r.id === cur.id) ?? rows[0] ?? null : rows[0] ?? null));
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  const create = async () => {
    try {
      const view = await api.console.tools.create(ws, {
        name: form.name.trim(), transport: form.transport, endpoint: form.endpoint,
        authType: form.authType, authToken: form.authType === "bearer" ? form.authToken : "",
        timeoutSec: form.timeoutSec,
        toolScope: { mode: form.scopeMode },
      });
      setMsg("连接器已保存，正在验证…");
      setForm({ name: "", transport: "streamable_http", endpoint: "", authType: "none", authToken: "", timeoutSec: 120, scopeMode: "all" });
      await load();
      const res = await api.console.tools.verify(ws, view.id);
      setMsg(res.ok ? `已保存并验证通过（${res.toolCount} 个工具）` : `已保存，验证失败: ${res.error ?? "unknown"}`);
      await load();
    } catch (e: any) { setMsg(`保存失败: ${e.message}`); }
  };
  const verify = async (id: number) => {
    try {
      const res = await api.console.tools.verify(ws, id);
      setMsg(res.ok ? `验证通过（${res.toolCount} 个工具 · ${res.latencyMs}ms）` : `验证失败: ${res.error ?? "unknown"}`);
      await load();
    } catch (e: any) { setMsg(`验证失败: ${e.message}`); }
  };
  const remove = async (cn: McpConnectorView) => {
    if (!confirm(`确认删除连接器 ${cn.name}？（被 Agent 引用时会被拒绝）`)) return;
    try { await api.console.tools.remove(ws, cn.id); await load(); }
    catch (e: any) { setMsg(`删除失败: ${e.message}`); }
  };

  const perms: Record<string, string> = (() => {
    try { return JSON.parse(selected?.permissions || "{}").tools ?? {}; } catch { return {}; }
  })();

  return (
    <div data-testid="console-tools">
      <PageHead title="工具管理" sub="创建并维护 MCP 连接器配置（对标 AgentCore tools/create）；Agent 创建/更新时在资产配置步勾选，发布时注入生成包（mcpServers / mcp-configs/）" />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1.6fr_1fr]">
        <div>
          <div className="mb-2 text-xs text-gray-400">已保存的连接器配置（工作空间级）</div>
          <table className="mb-4 w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="conn-list">
            <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">连接器</th><th className="p-2.5">传输</th><th className="p-2.5">Endpoint</th>
              <th className="p-2.5">状态</th><th className="p-2.5">被引用</th><th className="p-2.5 text-right">操作</th>
            </tr></thead>
            <tbody>
              <tr className="border-b bg-gray-50/60">
                <td className="p-2.5 font-medium">platform-mcp <Tag>平台内置</Tag></td>
                <td className="p-2.5 text-xs">Streamable HTTP</td>
                <td className="p-2.5 font-mono text-[11px] text-gray-500">/mcp（backend 同进程）</td>
                <td className="p-2.5"><Badge status="pending">ask 全量（HITL）</Badge></td>
                <td className="p-2.5 text-xs">按需勾选</td>
                <td className="p-2.5 text-right text-xs text-gray-400">—</td>
              </tr>
              {list.map((cn) => (
                <tr key={cn.id} className={`cursor-pointer border-b last:border-0 hover:bg-gray-50 ${selected?.id === cn.id ? "bg-blue-50/50" : ""}`}
                  onClick={() => setSelected(cn)}>
                  <td className="p-2.5 font-medium">{cn.name}</td>
                  <td className="p-2.5 text-xs">{cn.transport === "sse" ? "SSE" : "Streamable HTTP"}</td>
                  <td className="p-2.5 max-w-[170px] truncate font-mono text-[11px] text-gray-500" title={cn.endpoint}>{cn.endpoint}</td>
                  <td className="p-2.5"><Badge status={cn.status}>{cn.status === "verified" ? "已验证" : cn.status === "failed" ? "失败" : "未验证"}</Badge></td>
                  <td className="p-2.5 text-xs">{cn.refCount > 0 ? `${cn.refCount} 个 Agent` : "未被引用"}</td>
                  <td className="p-2.5 text-right" onClick={(e) => e.stopPropagation()}>
                    <span className="inline-flex gap-1">
                      {writable && <Btn sm kind="pri" onClick={() => verify(cn.id)}>验证</Btn>}
                      {writable && <Btn sm kind="danger" onClick={() => remove(cn)}>删除</Btn>}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="mb-2 text-xs text-gray-400">工具权限默认策略（三态）{selected && ` · ${selected.name}`}</div>
          <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="perm-table">
            <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500"><th className="p-2.5">工具</th><th className="p-2.5">权限</th><th className="p-2.5">说明</th></tr></thead>
            <tbody>
              {Object.entries(perms).map(([tool, perm]) => (
                <tr key={tool} className="border-b last:border-0">
                  <td className="p-2.5 font-mono text-xs">{tool}</td>
                  <td className="p-2.5"><Badge status={perm === "allow" ? "running" : perm === "ask" ? "pending" : "error"}>{perm}</Badge></td>
                  <td className="p-2.5 text-xs text-gray-500">{perm === "ask" ? "执行前经 HITL 确认卡" : perm === "deny" ? "拒绝调用" : "直接执行"}</td>
                </tr>
              ))}
              {Object.keys(perms).length === 0 && <tr><td colSpan={3} className="p-4 text-xs text-gray-400">未声明工具策略（运行时按未声明自动放行 / 平台内置为 ask 全量）</td></tr>}
            </tbody>
          </table>
        </div>
        <div>
          <div className="mb-2 text-xs text-gray-400">新建连接器</div>
          <Card>
            <label className="mb-1 block text-xs font-medium">名称 *</label>
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} data-testid="tool-name"
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">传输 *</label>
            <select value={form.transport} onChange={(e) => setForm({ ...form, transport: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value="streamable_http">Streamable HTTP</option>
              <option value="sse">SSE</option>
            </select>
            <label className="mb-1 block text-xs font-medium">Endpoint *</label>
            <input value={form.endpoint} onChange={(e) => setForm({ ...form, endpoint: e.target.value })} data-testid="tool-endpoint"
              placeholder="http://ticket-mcp.internal:9090/mcp"
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">认证</label>
            <select value={form.authType} onChange={(e) => setForm({ ...form, authType: e.target.value })}
              className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
              <option value="none">无认证（内网）</option>
              <option value="bearer">Bearer Token（存服务 Secret）</option>
            </select>
            {form.authType === "bearer" && (
              <>
                <label className="mb-1 block text-xs font-medium">Bearer Token</label>
                <input type="password" value={form.authToken} onChange={(e) => setForm({ ...form, authToken: e.target.value })}
                  className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
              </>
            )}
            <label className="mb-1 block text-xs font-medium">超时（秒）</label>
            <input type="number" value={form.timeoutSec} onChange={(e) => setForm({ ...form, timeoutSec: Number(e.target.value) })}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <div className="flex justify-end">
              <Btn kind="pri" testid="tool-save" disabled={!form.name.trim() || !form.endpoint.trim()} onClick={create}>保存并验证</Btn>
            </div>
          </Card>
          <Note>注入说明：Agent 创建/更新勾选后写入生成包 <code>mcpServers</code> + <code>mcp-configs/</code>；认证凭据不落包，经服务 Secret（{"{name}-env-secret"}）注入运行时。验证成功后工具快照进入 toolScope（子集模式选项源）。</Note>
        </div>
      </div>
    </div>
  );
}
