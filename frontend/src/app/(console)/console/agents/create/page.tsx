"use client";
// 用户侧 · 创建 Agent（M3）：单页分区表单（AgentCreateForm mode=create）。
import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import AgentCreateForm from "@/components/create/AgentCreateForm";
import { useConsoleWs } from "@/lib/useMe";

function CreateInner() {
  const ws = useConsoleWs();
  const params = useSearchParams();
  // 模板页「去导入」深链：?mode=import&packageId=N 预选派生源
  const deepPkg = Number(params.get("packageId")) || undefined;
  const deepMode = params.get("mode") === "import";
  if (!ws) return null;
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
