"use client";
// 用户侧 · 编辑 Agent（M3 §2.7）：与创建页同构表单（mode=edit），保存 = copy-on-write 新版本 + republish。
import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import AgentCreateForm from "@/components/create/AgentCreateForm";
import { api, type EditFormView } from "@/lib/api";
import { useConsoleWs } from "@/lib/useMe";
import { Card } from "@/components/ui";

export default function EditAgentPage() {
  const { id } = useParams<{ id: string }>();
  const ws = useConsoleWs();
  const [form, setForm] = useState<EditFormView | null>(null);
  const [msg, setMsg] = useState("");
  const [forbidden, setForbidden] = useState(false);

  useEffect(() => {
    if (!ws || !id) return;
    api.console.editForm(ws, Number(id))
      .then(setForm)
      .catch((e: any) => {
        if (/forbidden|403/i.test(e.message)) setForbidden(true);
        else setMsg(`加载失败: ${e.message}`);
      });
  }, [ws, id]);

  if (forbidden) {
    return <Card><div className="py-8 text-center text-sm text-gray-500">member 角色为只读，不可编辑（owner/admin 可操作）。</div></Card>;
  }
  if (msg) return <div className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>;
  if (!form) return <p className="text-sm text-gray-400">加载中…</p>;
  return <AgentCreateForm ws={ws} mode="edit" initial={form} serviceId={Number(id)} />;
}
