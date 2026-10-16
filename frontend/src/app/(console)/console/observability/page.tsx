"use client";
// 用户侧 · 观测大盘占位（M8 §7，demo c/obs 空态；一期不做，eval-studio 外链保留）。
import { ComingSoon, PageHead } from "@/components/ui";

export default function ObservabilityPage() {
  return (
    <div data-testid="console-obs">
      <PageHead title="观测大盘" sub="一期不做 · 指标与 Trace 后续统一从可观测系统（OTel）接入"
        actions={<a href={process.env.NEXT_PUBLIC_EVAL_STUDIO_URL || "http://localhost:18400"} target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">打开 eval-studio ↗</a>} />
      <ComingSoon icon="📈" title="指标与 Trace 接入规划中"
        desc="观测大盘整体后移：会话量、工具调用等指标与 Trace 后续从可观测系统统一接入；评测飞轮（eval-studio）保持外链入口，不依赖大盘。" />
    </div>
  );
}
