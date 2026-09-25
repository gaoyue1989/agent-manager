// 发布助手共享类型：page.tsx（状态与 SSE 逻辑）与渲染组件共用
export type FileCard = { file_id: string; file_name: string; mime_type: string; size: number; download_url: string };
export type AttachItem = { fileId: string; name: string; mime: string; size: number };
export type ThreadItem = { peer: string; fullKey: string; updatedAt: string; title?: string; model?: string };
/** 会话可切换模型（GET /models）：id="system" 为系统模型（默认），is_default 标记当前默认项 */
export type ModelOption = { id: string; name: string; provider?: string; model_id?: string; is_default?: boolean; source?: string; enabled?: boolean };

export type ToolCallInfo = {
  id?: string;
  tool_call_id?: string;
  name: string;
  input?: unknown;
  /** 执行状态：工具结果的 success/error/denied/interrupted，或挂起态的 asking（等待人工确认） */
  state?: string;
  /** 工具结果文本（后端按 AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS 截断） */
  output?: string;
  output_truncated?: boolean;
  output_full_length?: number;
};

export type ChatMsg = {
  role: "user" | "assistant" | "tool" | "system";
  content: string;
  pending?: boolean;   // 工具行执行中 / 助手流式输出中
  toolCallId?: string;
  /** 工具步骤终态：SUCCESS/ERROR/DENIED/INTERRUPTED（运行时）或 AWAITING_CONFIRM（前端口径）；历史回放无此字段 */
  state?: string;
  /** 工具结果文本（历史回放来自 AgentState；实时流不产出） */
  output?: string;
  output_truncated?: boolean;
  output_full_length?: number;
  confirm?: import("@/lib/confirm-card").ConfirmCard;
  files?: FileCard[];  // file_ready 渲染的下载卡片
};
