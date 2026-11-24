import { useEffect, useRef, useState } from 'react';
import type { ChatEntry } from '../types';

const STATE_LABELS: Record<string, string> = {
  running: '执行中',
  SUCCESS: '成功',
  ERROR: '失败',
  DENIED: '被拒绝',
  INTERRUPTED: '已中断',
  awaiting: '待确认',
};

type Props = {
  entries: ChatEntry[];
  busy: boolean;
  permissionCalls: Array<{ id: string; name: string }> | null;
  onSend: (text: string) => Promise<void>;
  onConfirm: (approved: boolean) => Promise<void>;
};

export function ChatPane({ entries, busy, permissionCalls, onSend, onConfirm }: Props) {
  const [draft, setDraft] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const node = scrollRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [entries, busy, permissionCalls]);

  const submit = () => {
    const text = draft.trim();
    if (!text || busy) return;
    setDraft('');
    void onSend(text);
  };

  return (
    <section className="panel">
      <div className="panel-head">
        <span className="title">对话</span>
        <span className="control-note">Agent 的每一步动作实时显示在右侧画面里</span>
      </div>
      <div className="chat-scroll" ref={scrollRef}>
        {entries.length === 0 && (
          <div className="bubble system">
            发一条消息开始，例如：打开 http://127.0.0.1:8088/ 并登录，然后汇总订单
          </div>
        )}
        {entries.map((entry) => (
          <div key={entry.id} className={`bubble ${entry.role}`}>
            {entry.text}
            {entry.tools.length > 0 && (
              <div className="tools">
                {entry.tools.map((tool) => (
                  <div key={tool.id} className="tool">
                    <div className="tool-head">
                      <span>🔧</span>
                      <span>{tool.name}</span>
                      <span>{STATE_LABELS[tool.state ?? ''] ?? tool.state ?? ''}</span>
                    </div>
                    {tool.summary ? <div className="meta">{tool.summary}</div> : null}
                    {tool.preview ? <div className="meta">{tool.preview}</div> : null}
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
        {permissionCalls && permissionCalls.length > 0 && (
          <div className="tool">
            <div className="tool-head">
              <span>⚠️</span>
              <span>需要确认：{permissionCalls.map((call) => call.name).join('、')}</span>
            </div>
            <div className="meta" style={{ display: 'flex', gap: 8 }}>
              <button className="btn primary" onClick={() => void onConfirm(true)}>批准</button>
              <button className="btn" onClick={() => void onConfirm(false)}>拒绝</button>
            </div>
          </div>
        )}
        {busy && <div className="bubble system">Agent 正在执行…</div>}
      </div>
      <div className="input-row">
        <textarea
          value={draft}
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
        />
        <button className="btn primary" onClick={submit} disabled={busy || draft.trim().length === 0}>
          发送
        </button>
      </div>
    </section>
  );
}
