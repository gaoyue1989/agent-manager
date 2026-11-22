"use client";
// 用户侧 · 观测大盘（tool-call-observability-design §四）：本工作空间 Agent
// 工具调用监控，指标来自平台观测栈（backend PromQL 聚合）。
// 外链地址取 NEXT_PUBLIC_EVAL_STUDIO_URL；未配置时隐藏入口（issue #145）。
import { api } from "@/lib/api";
import { useConsoleWs } from "@/lib/useMe";
import { ComingSoon, PageHead } from "@/components/ui";
import { ToolCallStatsPanel } from "@/components/ToolCallStatsPanel";

export default function ObservabilityPage() {
  const ws = useConsoleWs();
  const evalStudioUrl = process.env.NEXT_PUBLIC_EVAL_STUDIO_URL;
  return (
    <div data-testid="console-obs">
      <PageHead title="观测大盘" sub="本工作空间 Agent 工具调用监控"
        actions={evalStudioUrl
          ? <a href={evalStudioUrl} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">打开 eval-studio ↗</a>
          : <span className="inline-flex items-center gap-1.5 rounded-md border border-dashed border-gray-200 px-3 py-1.5 text-[13px] text-gray-400">评测工作站地址未配置</span>} />
      {ws > 0
        ? <ToolCallStatsPanel testid="obs" fetcher={(range) => api.console.toolCalls(ws, range)} />
        : <ComingSoon icon="📈" title="暂无可用工作空间" desc="加入工作空间后即可查看 Agent 工具调用监控。" />}
    </div>
  );
}
