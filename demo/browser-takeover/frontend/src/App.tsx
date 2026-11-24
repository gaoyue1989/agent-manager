import { useCallback, useEffect, useState } from 'react';
import * as api from './api';
import type { BrowserState, ChatEntry, SseFrame } from './types';
import { ChatPane } from './components/ChatPane';
import { ControlBar } from './components/ControlBar';
import { LiveView } from './components/LiveView';

const SID_STORAGE = 'browser-takeover-sid';
const UID_STORAGE = 'browser-takeover-uid';

let entrySeq = 0;
const nextId = () => `e-${++entrySeq}`;

function newSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `s-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

type PermissionCall = { id: string; name: string };

export default function App() {
  const [userId, setUserId] = useState(() => localStorage.getItem(UID_STORAGE) ?? 'demo-user');
  const [sessionId] = useState(() => localStorage.getItem(SID_STORAGE) ?? newSessionId());
  const [entries, setEntries] = useState<ChatEntry[]>([]);
  const [browser, setBrowser] = useState<BrowserState | null>(null);
  const [busy, setBusy] = useState(false);
  const [permissionCalls, setPermissionCalls] = useState<PermissionCall[] | null>(null);

  useEffect(() => {
    localStorage.setItem(SID_STORAGE, sessionId);
  }, [sessionId]);

  useEffect(() => {
    localStorage.setItem(UID_STORAGE, userId);
  }, [userId]);

  const pushSystem = useCallback((text: string) => {
    setEntries((prev) => [...prev, { id: nextId(), role: 'system', text, tools: [] }]);
  }, []);

  const appendAssistantText = useCallback((delta: string) => {
    setEntries((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.role === 'assistant') {
        return [...prev.slice(0, -1), { ...last, text: last.text + delta }];
      }
      return [...prev, { id: nextId(), role: 'assistant', text: delta, tools: [] }];
    });
  }, []);

  const updateTool = useCallback((toolCallId: string, patch: Partial<{ name: string; summary: string; preview: string; state: string }>) => {
    setEntries((prev) => {
      const copy = prev.slice();
      for (let i = copy.length - 1; i >= 0; i -= 1) {
        const entry = copy[i];
        const index = entry.tools.findIndex((tool) => tool.id === toolCallId);
        if (index >= 0) {
          const tools = entry.tools.slice();
          tools[index] = { ...tools[index], ...patch };
          copy[i] = { ...entry, tools };
          return copy;
        }
      }
      const last = copy[copy.length - 1];
      const name = patch.name ?? 'tool';
      if (last && last.role === 'assistant') {
        copy[copy.length - 1] = {
          ...last,
          tools: [...last.tools, { id: toolCallId, name, ...patch }],
        };
        return copy;
      }
      return [...copy, { id: nextId(), role: 'assistant', text: '', tools: [{ id: toolCallId, name, ...patch }] }];
    });
  }, []);

  const applyFrame = useCallback((frame: SseFrame) => {
    switch (frame.type) {
      case 'TEXT_BLOCK_DELTA':
        appendAssistantText(typeof frame.delta === 'string' ? frame.delta : '');
        break;
      case 'TOOL_CALL_START':
        updateTool(String(frame.toolCallId ?? ''), {
          name: String(frame.toolName ?? 'tool'),
          state: 'running',
        });
        break;
      case 'tool_call_summary':
        updateTool(String(frame.toolCallId ?? ''), { summary: String(frame.summary ?? '') });
        break;
      case 'tool_result_preview':
        updateTool(String(frame.toolCallId ?? ''), { preview: String(frame.preview ?? '') });
        break;
      case 'TOOL_RESULT_END':
        updateTool(String(frame.toolCallId ?? ''), { state: String(frame.state ?? '') });
        break;
      case 'permission_ask': {
        const calls = (frame.tool_calls as Array<{ tool_call_id?: string; name?: string }> | undefined) ?? [];
        setPermissionCalls(calls.map((call) => ({
          id: String(call.tool_call_id ?? ''),
          name: String(call.name ?? 'tool'),
        })));
        break;
      }
      case 'error':
        pushSystem(`错误：${String(frame.error ?? 'unknown')}`);
        setBusy(false);
        break;
      case 'cancelled':
        pushSystem('本轮已被停止（控制权已交给人工）');
        break;
      default:
        break;
    }
  }, [appendAssistantText, pushSystem, updateTool]);

  const refreshBrowser = useCallback(async () => {
    try {
      setBrowser(await api.browserState(sessionId, userId));
    } catch (error) {
      pushSystem(`读取浏览器状态失败：${(error as Error).message}`);
    }
  }, [pushSystem, sessionId, userId]);

  useEffect(() => {
    void refreshBrowser();
    const timer = window.setInterval(() => void refreshBrowser(), 5000);
    return () => window.clearInterval(timer);
  }, [refreshBrowser]);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    (async () => {
      try {
        const hist = await api.history(sessionId, userId);
        const restored: ChatEntry[] = [];
        for (const message of hist.messages ?? []) {
          const role = (message.role ?? '').toLowerCase();
          if (role !== 'user' && role !== 'assistant') continue;
          restored.push({
            id: nextId(),
            role,
            text: message.content ?? '',
            tools: (message.tool_calls ?? []).map((tool) => ({
              id: nextId(),
              name: tool.name ?? 'tool',
              state: tool.state,
            })),
          });
        }
        if (!cancelled && restored.length > 0) {
          setEntries(restored);
        }
        const state = await api.status(sessionId, userId);
        if (!cancelled && state.state === 'working') {
          setBusy(true);
          void api
            .subscribe(sessionId, state.latest_event_seq ?? 0, state.reply_id, userId, applyFrame, controller.signal)
            .catch(() => undefined)
            .finally(() => {
              if (!cancelled) setBusy(false);
            });
        }
      } catch {
      }
    })();
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [applyFrame, sessionId, userId]);

  const send = useCallback(async (text: string) => {
    if (!text.trim() || busy) return;
    setEntries((prev) => [...prev, { id: nextId(), role: 'user', text, tools: [] }]);
    setBusy(true);
    try {
      await api.chat(sessionId, text, userId, applyFrame);
    } catch (error) {
      const message = (error as Error).message;
      pushSystem(message.includes('user_quota_exhausted')
        ? '沙箱配额已满：先结束或复用已有会话的沙箱（配额见服务 env SANDBOX_USER_QUOTA）'
        : `请求失败：${message}`);
    } finally {
      setBusy(false);
      void refreshBrowser();
    }
  }, [applyFrame, busy, pushSystem, refreshBrowser, sessionId, userId]);

  const takeover = useCallback(async () => {
    try {
      const result = await api.takeover(sessionId, userId, '人工接管（演示前端）');
      pushSystem(`已接管浏览器控制权${result.stop_outcome ? `（当前轮：${result.stop_outcome}）` : ''}`);
      await refreshBrowser();
    } catch (error) {
      pushSystem(`接管失败：${(error as Error).message}`);
    }
  }, [pushSystem, refreshBrowser, sessionId, userId]);

  const release = useCallback(async () => {
    setBusy(true);
    pushSystem('已交还控制权，Agent 正在重新采样浏览器现场…');
    try {
      await api.release(sessionId, userId, applyFrame);
    } catch (error) {
      pushSystem(`交还失败：${(error as Error).message}`);
    } finally {
      setBusy(false);
      await refreshBrowser();
    }
  }, [applyFrame, pushSystem, refreshBrowser, sessionId, userId]);

  const confirm = useCallback(async (approved: boolean) => {
    const calls = permissionCalls;
    setPermissionCalls(null);
    if (!calls || calls.length === 0) return;
    setBusy(true);
    try {
      await api.confirmStream(sessionId, userId, calls.map((call) => call.id), approved, applyFrame);
    } catch (error) {
      pushSystem(`确认失败：${(error as Error).message}`);
    } finally {
      setBusy(false);
      await refreshBrowser();
    }
  }, [applyFrame, permissionCalls, pushSystem, refreshBrowser, sessionId, userId]);

  const owner = browser?.control.owner ?? 'AGENT';
  const sandboxReady = browser?.sandbox.ready ?? false;

  return (
    <div className="app">
      <header className="topbar">
        <h1>浏览器人工接管演示</h1>
        <span className="control-note">session {sessionId.slice(0, 8)}</span>
        <div style={{ flex: 1 }} />
        <label>
          用户
          <input value={userId} onChange={(event) => setUserId(event.target.value)} />
        </label>
      </header>
      <main className="main">
        <ChatPane
          entries={entries}
          busy={busy}
          permissionCalls={permissionCalls}
          onSend={send}
          onConfirm={confirm}
        />
        <section className="panel">
          <ControlBar
            owner={owner}
            reason={browser?.control.reason ?? null}
            sandboxReady={sandboxReady}
            sandboxId={browser?.sandbox.sandbox_id ?? null}
            busy={busy}
            onTakeover={takeover}
            onRelease={release}
          />
          <LiveView
            sessionId={sessionId}
            userId={userId}
            sandboxId={browser?.sandbox.sandbox_id ?? null}
            viewOnly={owner !== 'HUMAN'}
            ready={sandboxReady}
            owner={owner}
          />
        </section>
      </main>
    </div>
  );
}
