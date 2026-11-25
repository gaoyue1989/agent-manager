import { useEffect, useRef, useState } from 'react';
import * as api from '../api';

type Props = {
  sessionId: string;
  userId: string;
  sandboxId: string | null;
  viewOnly: boolean;
  ready: boolean;
  owner: 'AGENT' | 'HUMAN';
};

type LiveStatus = 'idle' | 'connecting' | 'connected' | 'closed' | 'error';

export function LiveView({ sessionId, userId, sandboxId, viewOnly, ready, owner }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const rfbRef = useRef<{ viewOnly: boolean; disconnect: () => void } | null>(null);
  const failuresRef = useRef(0);
  const [status, setStatus] = useState<LiveStatus>('idle');

  useEffect(() => {
    failuresRef.current = 0;
  }, [sessionId, userId]);

  useEffect(() => {
    if (!ready) {
      setStatus('idle');
      return undefined;
    }
    let disposed = false;
    let retryTimer: number | undefined;

    const fail = () => {
      failuresRef.current += 1;
      setStatus('error');
      retryTimer = window.setTimeout(() => void connect(), 2000);
    };

    const connect = async () => {
      if (disposed || !containerRef.current) return;
      setStatus('connecting');
      let RFB: typeof import('@novnc/novnc').default;
      try {
        ({ default: RFB } = await import('@novnc/novnc'));
      } catch {
        if (!disposed) fail();
        return;
      }
      if (disposed || !containerRef.current) return;
      let url: string;
      try {
        url = await api.vncUrl(sessionId, userId, sandboxId);
      } catch {
        if (!disposed) fail();
        return;
      }
      if (disposed || !containerRef.current) return;
      containerRef.current.innerHTML = '';
      const rfb = new RFB(containerRef.current, url, { wsProtocols: ['binary'] });
      rfb.scaleViewport = true;
      rfb.viewOnly = viewOnly;
      rfb.addEventListener('connect', () => {
        failuresRef.current = 0;
        setStatus('connected');
      });
      rfb.addEventListener('disconnect', (event) => {
        rfbRef.current = null;
        if (disposed) return;
        setStatus(event.detail?.clean ? 'closed' : 'error');
        fail();
      });
      rfbRef.current = rfb;
    };

    void connect();
    return () => {
      disposed = true;
      if (retryTimer) window.clearTimeout(retryTimer);
      rfbRef.current?.disconnect();
      rfbRef.current = null;
    };
  }, [ready, sandboxId, sessionId, userId, viewOnly]);

  useEffect(() => {
    if (rfbRef.current) rfbRef.current.viewOnly = viewOnly;
  }, [viewOnly]);

  const overlay = !ready
    ? '沙箱尚未创建：先给 Agent 发一条消息（首轮会创建浏览器沙箱）'
    : status === 'connecting'
      ? '正在连接实时画面…'
      : status === 'error'
        ? '画面连接断开，正在重试…（连续失败将自动切换 HTTP 截图模式）'
        : status === 'closed'
          ? '画面连接已关闭，正在重连…'
          : null;

  return (
    <div className="live-wrap">
      <div className="live-canvas" ref={containerRef} />
      {overlay ? <div className="live-overlay">{overlay}</div> : null}
      {owner === 'HUMAN' && !overlay ? (
        <div className="live-overlay" style={{ alignItems: 'flex-end', paddingBottom: 12 }}>
          <span className="badge human">人工接管中：可直接在画面里操作浏览器</span>
        </div>
      ) : null}
    </div>
  );
}
