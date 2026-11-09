"use client";
// 用户侧 · 沙箱实例（sandbox-pool-opensandbox-design §6.2）：本工作空间各服务在
// opensandbox 的沙箱实例只读视图（backend 按 metadata.service ∈ ws 服务集合过滤，§5.3）。
// 不提供任何操作（用户侧只读，治理动作走运维侧 /admin/sandboxes 与资源自身生命周期）。
import { useCallback, useEffect, useState } from "react";
import { api, type SandboxInstance } from "@/lib/api";
import { countdown } from "@/lib/sandbox-quota";
import { useConsoleWs } from "@/lib/useMe";
import { Badge, Card, Note, PageHead } from "@/components/ui";

export default function SandboxInstancesPage() {
  const ws = useConsoleWs();
  const [rows, setRows] = useState<SandboxInstance[]>([]);
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (silent = false) => {
    if (!ws) return;
    if (!silent) setErr("");
    setLoading(true);
    try {
      setRows(await api.console.sandboxes.list(ws));
      setErr("");
    } catch (e: any) {
      if (!silent) setErr(e?.message ?? String(e));
    } finally {
      setLoading(false);
    }
  }, [ws]);

  useEffect(() => { load(); }, [load]);
  // 5s 轮询（expiresAt 倒计时随刷新推进；失败静默保留上次数据）
  useEffect(() => {
    const t = setInterval(() => { load(true); }, 5000);
    return () => clearInterval(t);
  }, [load]);

  return (
    <div data-testid="console-sandbox-instances">
      <PageHead title="沙箱实例" sub="本工作空间各服务的 OpenSandbox 沙箱实例（只读）" />
      <Note>
        沙箱 TTL / 回收由 opensandbox 服务端管理（到期自动 GC），启用与资源规格在对应 Agent
        的沙箱配置中调整；本页不提供操作，治理动作由平台运维侧执行。
      </Note>
      {err && (
        <div data-testid="sandbox-instances-error" className="mb-3 rounded border border-red-200 bg-red-50 p-2.5 text-xs text-red-700">
          {err}
        </div>
      )}
      <Card>
        <table className="w-full text-sm" data-testid="sandbox-instances-table">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">状态</th><th className="p-2.5">所属服务</th>
              <th className="p-2.5">会话</th><th className="p-2.5">到期</th><th className="p-2.5">创建时间</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((it) => (
              <tr key={it.id} className="border-b border-gray-100 last:border-0" data-testid={`sandbox-instance-${it.id}`}>
                <td className="p-2.5"><Badge status={it.state === "Running" ? "running" : "stopped"}>{it.state}</Badge></td>
                <td className="p-2.5">{it.service ?? "—"}</td>
                <td className="p-2.5 font-mono text-[12px]">{it.sessionId ?? "—"}</td>
                <td className="p-2.5 text-[12px]" title={it.expiresAt ?? ""}>{countdown(it.expiresAt)}</td>
                <td className="p-2.5 text-[12px] text-gray-500">{it.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-sm text-gray-400">
                {loading ? "加载中…" : "暂无沙箱实例"}
              </td></tr>
            )}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
