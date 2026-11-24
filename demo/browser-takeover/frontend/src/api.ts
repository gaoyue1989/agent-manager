import type { BrowserState, ControlView, SseFrame } from './types';

export const SERVICE = 'browser-takeover';
export const AGENT_BASE = `/agent/${SERVICE}`;

function jsonHeaders(userId: string): Record<string, string> {
  return { 'Content-Type': 'application/json', 'X-User-Id': userId };
}

export async function readSse(
  response: Response,
  onFrame: (frame: SseFrame) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${await response.text().catch(() => '')}`);
  }
  if (!response.body) {
    throw new Error('no stream body');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  if (signal) {
    signal.addEventListener('abort', () => {
      void reader.cancel().catch(() => undefined);
    }, { once: true });
  }
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let separator = buffer.indexOf('\n\n');
    while (separator >= 0) {
      const chunk = buffer.slice(0, separator);
      buffer = buffer.slice(separator + 2);
      const data = chunk
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('\n');
      if (data) {
        try {
          onFrame(JSON.parse(data) as SseFrame);
        } catch {
        }
      }
      separator = buffer.indexOf('\n\n');
    }
  }
}

const threadsUrl = (sessionId: string) => `${AGENT_BASE}/threads/${encodeURIComponent(sessionId)}`;

export async function chat(
  sessionId: string,
  message: string,
  userId: string,
  onFrame: (frame: SseFrame) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`${AGENT_BASE}/threads/chat`, {
    method: 'POST',
    headers: jsonHeaders(userId),
    body: JSON.stringify({ sessionId, message, userId }),
    signal,
  });
  await readSse(response, onFrame, signal);
}

export async function subscribe(
  sessionId: string,
  afterSeq: number,
  replyId: string | undefined,
  userId: string,
  onFrame: (frame: SseFrame) => void,
  signal?: AbortSignal,
): Promise<void> {
  const params = new URLSearchParams({ afterSeq: String(afterSeq) });
  if (replyId) params.set('replyId', replyId);
  const response = await fetch(`${threadsUrl(sessionId)}/subscribe?${params.toString()}`, {
    headers: { 'X-User-Id': userId },
    signal,
  });
  await readSse(response, onFrame, signal);
}

export async function status(
  sessionId: string,
  userId: string,
): Promise<{ state: string; latest_event_seq?: number; reply_id?: string }> {
  const response = await fetch(`${threadsUrl(sessionId)}/status`, {
    headers: { 'X-User-Id': userId },
  });
  if (!response.ok) throw new Error(`status HTTP ${response.status}`);
  return response.json();
}

export async function history(
  sessionId: string,
  userId: string,
): Promise<{ messages: Array<{ role?: string; content?: string; tool_calls?: Array<{ name?: string; state?: string }> }> }> {
  const response = await fetch(`${threadsUrl(sessionId)}/history`, {
    headers: { 'X-User-Id': userId },
  });
  if (!response.ok) throw new Error(`history HTTP ${response.status}`);
  return response.json();
}

export async function browserState(sessionId: string, userId: string): Promise<BrowserState> {
  const response = await fetch(`${threadsUrl(sessionId)}/browser`, {
    headers: { 'X-User-Id': userId },
  });
  if (!response.ok) throw new Error(`browser state HTTP ${response.status}`);
  return response.json();
}

export async function takeover(
  sessionId: string,
  userId: string,
  reason: string,
): Promise<{ control: ControlView; stop_outcome?: string | null }> {
  const response = await fetch(`${threadsUrl(sessionId)}/browser/takeover`, {
    method: 'POST',
    headers: jsonHeaders(userId),
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(`takeover HTTP ${response.status}`);
  return response.json();
}

export async function release(
  sessionId: string,
  userId: string,
  onFrame: (frame: SseFrame) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`${threadsUrl(sessionId)}/browser/release`, {
    method: 'POST',
    headers: jsonHeaders(userId),
    signal,
  });
  await readSse(response, onFrame, signal);
}

export async function confirmStream(
  sessionId: string,
  userId: string,
  toolCallIds: string[],
  approved: boolean,
  onFrame: (frame: SseFrame) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`${threadsUrl(sessionId)}/confirm-stream`, {
    method: 'POST',
    headers: jsonHeaders(userId),
    body: JSON.stringify({ results: toolCallIds.map((id) => ({ tool_call_id: id, confirmed: approved })) }),
    signal,
  });
  await readSse(response, onFrame, signal);
}

export function vncUrl(sessionId: string, userId: string): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}${threadsUrl(sessionId)}/browser/vnc?userId=${encodeURIComponent(userId)}`;
}
