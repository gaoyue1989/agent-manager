"use client";
// 运维侧 · 工具调用监控（tool-call-observability-design §四）：全平台视角，
// 与用户侧观测大盘共用 ToolCallStatsPanel（差异仅在数据获取范围）。
import { api } from "@/lib/api";
import { PageHead } from "@/components/ui";
import { ToolCallStatsPanel } from "@/components/ToolCallStatsPanel";

export default function AdminObservabilityPage() {
  return (
    <div data-testid="admin-obs">
      <PageHead title="工具调用" sub="全平台 Agent 工具调用监控" />
      <ToolCallStatsPanel testid="obs" fetcher={(range) => api.admin.toolCalls(range)} />
    </div>
  );
}
