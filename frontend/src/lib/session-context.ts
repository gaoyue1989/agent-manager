import type { ChatMsg } from "../components/chat/types";

export interface HistoryContextEntry {
  key?: string;
  title?: string;
  content?: string;
  content_chars?: number;
  created_at?: string;
  updated_at?: string;
}

type NamedEntry = HistoryContextEntry & { key: string };

function isNamedEntry(entry: unknown): entry is NamedEntry {
  return !!entry && typeof entry === "object" && typeof (entry as HistoryContextEntry).key === "string";
}

export function contextMessagesFromHistory(data: { context?: unknown }): ChatMsg[] {
  if (!Array.isArray(data.context)) return [];
  return data.context.filter(isNamedEntry).map((entry) => ({
    role: "context" as const,
    content: String(entry.content ?? ""),
    context: {
      key: entry.key,
      title: entry.title || entry.key,
      createdAt: entry.created_at ?? "",
      updatedAt: entry.updated_at ?? "",
    },
  }));
}

export function isWelcomeState(messages: ChatMsg[]): boolean {
  return messages.every((m) => m.role === "system" || m.role === "context");
}
