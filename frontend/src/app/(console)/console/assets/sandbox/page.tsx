"use client";
// 用户侧 · 沙箱配置（demo c/sandbox）：空间内 Agent 的 OpenSandbox 沙箱启用状态总览（只读）。
// 生效来源还原 SandboxRuntime 三层裁决（env 显式 > 包声明 > 默认关）；
// 修改入口在对应 Agent 编辑页的沙箱配置区块——本页不做任何写操作。
import { useEffect, useState } from "react";
import Link from "next/link";
import { api, type SandboxItem } from "@/lib/api";
import { useConsoleWs } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead } from "@/components/ui";

const SOURCE_TEXT: Record<SandboxItem["source"], string> = {
  env: "发布 env · SANDBOX_ENABLED",
  package: "包声明 · config.sandbox.enabled",
  default: "默认关闭（未声明）",
};

function ResSpec(it: SandboxItem) {
  if (!it.enabled && !it.image && !it.timeoutMinutes && !it.memoryMb && !it.cpuCount) return "—";
  const parts = [
    it.image ? it.image.replace(/^opensandbox\//, "").replace(/^code-interpreter/, "code-interpreter") : "默认镜像",
    it.memoryMb ? `${it.memoryMb}MB` : "",
    it.cpuCount ? `${it.cpuCount} CPU` : "",
  ].filter(Boolean);
  if (it.timeoutMinutes) parts.push(`超时 ${it.timeoutMinutes}min`);
  return parts.join(" · ");
}

export default function SandboxPage() {
  const ws = useConsoleWs();
  const [rows, setRows] = useState<SandboxItem[]>([]);
  const [keyword, setKeyword] = useState("");
  const [filter, setFilter] = useState<"all" | "on" | "off">("all");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!ws) return;
    setLoading(true);
    api.console.sandbox(ws, keyword)
      .then((list) => setRows(list))
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  }, [ws, keyword]);

  const shown = rows.filter((r) => (filter === "all" ? true : filter === "on" ? r.enabled : !r.enabled));
  const onCount = rows.filter((r) => r.enabled).length;

  return (
    <div data-testid="console-sandbox">
      <PageHead title="沙箱配置" sub="空间内 Agent 的 OpenSandbox 沙箱启用状态总览（只读）；启用 / 资源规格的修改请进入对应 Agent 的编辑页（写入包声明或发布 env）" />
      <Note>
        生效裁决（agent-framework SandboxRuntime 三层）：部署 env <b>SANDBOX_ENABLED</b> 显式设置 &gt; 包 frontmatter <b>config.sandbox.enabled</b>
        （随包版本走，reload 热生效）&gt; 默认关闭（主机工作区执行）。OpenSandbox Server / API Key 为平台级配置
        （运维侧「默认配置」页维护），API Key 经服务 Secret 注入，不落包不回显。
      </Note>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}
          className="rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]">
          <option value="all">全部沙箱状态</option>
          <option value="on">已启用</option>
          <option value="off">未启用</option>
        </select>
        <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索 Agent 名称"
          className="w-56 rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
        <span className="text-xs text-gray-400">{loading ? "加载中…" : `共 ${rows.length} 个 Agent · 已启用 ${onCount}`}</span>
      </div>
      <Card>
        <table className="w-full text-sm" data-testid="sandbox-table">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">Agent</th><th className="p-2.5">沙箱状态</th><th className="p-2.5">生效来源</th>
              <th className="p-2.5">资源规格</th><th className="p-2.5">状态</th><th className="p-2.5 text-right"></th>
            </tr>
          </thead>
          <tbody>
            {shown.map((it) => (
              <tr key={it.serviceId} className="border-b border-gray-100 last:border-0">
                <td className="p-2.5 font-medium">{it.displayName}<div className="font-mono text-[11px] text-gray-400">{it.k8sName}</div></td>
                <td className="p-2.5">
                  {it.enabled
                    ? <Badge status="running">已启用</Badge>
                    : <Badge status="stopped">未启用</Badge>}
                </td>
                <td className="p-2.5 text-xs text-gray-500">
                  {SOURCE_TEXT[it.source]}
                  {it.envExplicit && it.packageDeclared != null && (
                    <span className="ml-1 text-gray-400">（包声明 {it.packageDeclared ? "true" : "false"} 被 env 覆盖）</span>
                  )}
                </td>
                <td className="p-2.5 font-mono text-[11px] text-gray-600">{ResSpec(it)}</td>
                <td className="p-2.5">
                  <Badge status={it.status}>{it.status === "running" ? "运行中" : it.status === "stopped" ? "已下线" : it.status === "failed" ? "异常" : it.status}</Badge>
                </td>
                <td className="p-2.5 text-right">
                  <Link href={`/console/agents/${it.serviceId}/edit`}>
                    <Btn sm>编辑配置</Btn>
                  </Link>
                </td>
              </tr>
            ))}
            {shown.length === 0 && (
              <tr><td colSpan={6} className="p-6 text-center text-sm text-gray-400">
                {loading ? "加载中…" : "空间内暂无 Agent（或无匹配结果）——到「Agent」页创建后在此查看沙箱状态"}
              </td></tr>
            )}
          </tbody>
        </table>
      </Card>
      <Note>
        修改路径：<b>Agent 编辑页 → 沙箱配置区块</b>——开关写入生成包 frontmatter config.sandbox.enabled（copy-on-write 新版本，reload 热生效）；
        镜像 / 超时 / 内存 / CPU 作为发布 env（SANDBOX_IMAGE / SANDBOX_TIMEOUT_MINUTES / SANDBOX_MEMORY_MB / SANDBOX_CPU_COUNT）注入，
        变更随重发布滚动重启生效。
      </Note>
    </div>
  );
}
