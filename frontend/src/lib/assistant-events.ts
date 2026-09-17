export type ToolMessage = {
  role: string;
  content: string;
  pending?: boolean;
  toolCallId?: string;
};

export function completeToolCall<T extends ToolMessage>(
  messages: T[],
  event: { toolCallId?: unknown; toolCallName?: unknown; state?: unknown },
): T[] {
  if (typeof event.toolCallId !== "string" || !event.toolCallId) return messages;
  return messages.map((message) =>
    message.role === "tool" && message.pending && message.toolCallId === event.toolCallId
      ? { ...message, content: `${message.content} ${event.state === "SUCCESS" ? "✓" : "（执行结束）"}`, pending: false }
      : message,
  );
}
