"use client";
// 用户侧 · Agent 模板（M8 §5，demo c/templates）：公共包卡片 +「去导入」派生链路。
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, type PackageRec } from "@/lib/api";
import { useConsoleWs } from "@/lib/useMe";
import { Badge, Card, PageHead, Tag } from "@/components/ui";

const GRADIENTS = ["from-blue-600 to-blue-400", "from-emerald-500 to-green-400", "from-violet-500 to-purple-400", "from-orange-500 to-amber-400"];

export default function TemplatesPage() {
  const ws = useConsoleWs();
  const [list, setList] = useState<PackageRec[]>([]);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try { setList(await api.console.templates(ws)); setMsg(""); }
    catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  return (
    <div data-testid="console-templates">
      <PageHead title="Agent 模板" sub="平台公共包（workspace_id=0）与运维精选 · 不作为创建入口，可经「导入 OAF 包」派生副本（copy-on-write）" />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      {list.length === 0 ? (
        <Card><div className="py-10 text-center text-sm text-gray-400">
          暂无模板包 —— 运维可在「运维侧 → 配置包」将公共包标记为官方模板。
        </div></Card>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3" data-testid="template-grid">
          {list.map((p, i) => (
            <div key={p.id} className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-3.5 transition hover:border-blue-200 hover:shadow-md">
              <div className="flex items-start gap-2.5">
                <span className={`flex size-[34px] flex-none items-center justify-center rounded-lg bg-gradient-to-br text-sm font-semibold text-white ${GRADIENTS[i % GRADIENTS.length]}`}>
                  {(p.name || "?").slice(0, 1)}
                </span>
                <b className="min-w-0 flex-1 truncate text-[13.5px] font-semibold">{p.name}</b>
                <Badge status="running">官方</Badge>
              </div>
              <p className="min-h-[32px] text-xs leading-relaxed text-gray-400">{p.description || "（无描述）"}</p>
              <div className="flex flex-wrap gap-1.5">
                <Tag>被引用 {p.refCount} 次</Tag>
                <Tag>v{p.version}</Tag>
              </div>
              <div className="mt-auto flex items-center gap-1.5 border-t border-gray-100 pt-2">
                <time className="mr-auto text-[11px] text-gray-400">官方模板</time>
                <Link href={`/console/agents/create?mode=import&packageId=${p.id}`} data-testid={`tpl-import-${p.id}`}
                  className="rounded border border-gray-300 px-2 py-0.5 text-xs text-gray-600 hover:border-blue-200 hover:text-blue-600">去导入</Link>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
