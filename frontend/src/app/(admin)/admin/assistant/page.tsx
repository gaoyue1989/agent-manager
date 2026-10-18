"use client";
// 发布助手页 = ChatPane 共享组件的消费包装（M4 抽取，行为零差异回归）：
// AGENT_BASE=/agent/release-agent（proxy 反代）；oaf-assistant-sid 会话 id 双写
// localStorage + cookie（评测采集模式 proxy 注入 x-eval-session 的依据）。
import ChatPane from "@/components/chat/ChatPane";

const GREETING = "我是 OAF 平台的智能发布助手。可以让我发布配置包、查询服务状态、更新环境变量、重新发布或下线服务。";
const SUGGESTIONS = ["现在有哪些服务？", "把 packageId=3 发布一下", "更新服务环境变量", "下线一个服务"];

export default function AssistantPage() {
  return (
    <ChatPane
      agentBase="/agent/release-agent"
      persistKey="oaf-assistant-sid"
      persistCookie
      title="发布助手"
      subtitle="对话式驱动发布全流程"
      greeting={GREETING}
      suggestions={SUGGESTIONS}
      userId="webui"
      testId="assistant-page"
    />
  );
}
