export interface StageAwareMsg {
  role: string;
  content: string;
  pending?: boolean;
  stages?: string[];
  turnSummary?: string;
}

export interface GroupedRenderItem<T extends StageAwareMsg> {
  key: number;
  msg: T;
  tools: T[];
}

export function appendStageStep<T extends StageAwareMsg>(messages: T[], text: unknown): T[] {
  if (typeof text !== "string") return messages;
  const trimmed = text.trim();
  if (!trimmed) return messages;
  const next = [...messages];
  for (let i = next.length - 1; i >= 0; i--) {
    if (next[i].role === "assistant") {
      const prevStages = next[i].stages ?? [];
      if (prevStages.length > 0 && prevStages[prevStages.length - 1] === trimmed) {
        return messages;
      }
      next[i] = { ...next[i], stages: [...prevStages, trimmed] };
      return next;
    }
  }
  return messages;
}

export function setTurnSummary<T extends StageAwareMsg>(messages: T[], summary: unknown): T[] {
  if (typeof summary !== "string") return messages;
  const trimmed = summary.trim();
  if (!trimmed) return messages;
  const next = [...messages];
  for (let i = next.length - 1; i >= 0; i--) {
    if (next[i].role === "assistant") {
      next[i] = { ...next[i], turnSummary: trimmed };
      return next;
    }
  }
  return messages;
}

export function groupRenderItems<T extends StageAwareMsg>(messages: T[]): GroupedRenderItem<T>[] {
  const items: GroupedRenderItem<T>[] = [];
  let pendingTools: T[] = [];
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    if (m.role === "tool") {
      pendingTools.push(m);
      continue;
    }
    if (m.role === "assistant" && (m.stages?.length || m.turnSummary)) {
      items.push({ key: i, msg: m, tools: pendingTools });
      pendingTools = [];
      continue;
    }
    for (let j = 0; j < pendingTools.length; j++) {
      items.push({ key: i * 1000 + j, msg: pendingTools[j], tools: [] });
    }
    pendingTools = [];
    items.push({ key: i, msg: m, tools: [] });
  }
  for (let j = 0; j < pendingTools.length; j++) {
    items.push({ key: messages.length * 1000 + j, msg: pendingTools[j], tools: [] });
  }
  return items;
}

export function resolveTimelineHeadline(stages: string[], turnSummary?: string): string {
  if (turnSummary && turnSummary.trim()) return turnSummary.trim();
  if (stages.length > 0) return stages[stages.length - 1];
  return "执行过程";
}
