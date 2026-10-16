"use client";
// 用户侧 · 知识库占位（M8 §7，demo c/knowledge 空态）。
import { ComingSoon, PageHead } from "@/components/ui";

export default function KnowledgePage() {
  return (
    <div data-testid="console-knowledge">
      <PageHead title="知识库" sub="规划中 · 对标 AgentCore「知识库经 MCP 接入，在工具页签下同步展示」" />
      <ComingSoon icon="📚" title="知识库能力规划中"
        desc="一期可通过 MCP 连接器接入外部知识检索服务（工具管理页可见）；平台内置知识库实体、切片管理与检索评测在后续版本提供。"
        action={{ href: "/console/assets/tools", label: "先看工具管理" }} />
    </div>
  );
}
