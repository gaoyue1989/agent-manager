export type SseFrame = { type: string } & Record<string, unknown>;

export type ControlView = {
  owner: 'AGENT' | 'HUMAN';
  reason?: string | null;
  by?: string | null;
  trigger?: string | null;
  since?: string | null;
  expires_in_seconds?: number;
};

export type BrowserState = {
  control: ControlView;
  sandbox: { ready: boolean; sandbox_id?: string | null; reason?: string };
  vnc_path: string;
};

export type ToolRow = { id: string; name: string; summary?: string; preview?: string; state?: string };

export type ChatEntry = {
  id: string;
  role: 'user' | 'assistant' | 'system';
  text: string;
  tools: ToolRow[];
};
