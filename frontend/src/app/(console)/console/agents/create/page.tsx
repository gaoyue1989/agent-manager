"use client";
// 用户侧 · 创建 Agent（M3）：单页分区表单（AgentCreateForm mode=create）。
// member 角色只读：不渲染表单，展示只读提示（写操作后端同样 403，issue #141）。
import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import AgentCreateForm from "@/components/create/AgentCreateForm";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Card } from "@/components/ui";

function CreateInner() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const params = useSearchParams();
  // 模板页「去导入」深链：?mode=import&packageId=N 预选派生源
  const deepPkg = Number(params.get("packageId")) || undefined;
  const deepMode = params.get("mode") === "import";
  if (!ws) return null;
  if (role !== "owner" && role !== "admin") {
    return <Card><div className="py-8 text-center text-sm text-gray-500">member 角色为只读，不可创建 Agent（owner/admin 可操作）。</div></Card>;
  }
  return (
    <AgentCreateForm
      key={`${ws}-${deepPkg ?? 0}`}
      ws={ws}
      mode="create"
      deepImportPackageId={deepMode ? deepPkg : undefined}
    />
  );
}

export default function CreateAgentPage() {
  return (
    <Suspense fallback={<p className="text-sm text-gray-400">加载中…</p>}>
      <CreateInner />
    </Suspense>
  );
}
