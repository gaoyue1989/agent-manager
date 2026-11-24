import { useEffect, useRef, useState } from 'react';

type Props = {
  url: string;
  viewOnly: boolean;
  ready: boolean;
  owner: 'AGENT' | 'HUMAN';
};

type LiveStatus = 'idle' | 'connecting' | 'connected' | 'closed' | 'error';

export function LiveView({ url, viewOnly, ready, owner }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const rfbRef = useRef<{
    viewOnly: boolean;
    disconnect: () => void;
  } | null>(null);
  const [status, setStatus] = useState<LiveStatus>('idle');

  useEffect(() => {
    if (!ready) {
      setStatus('idle');
      return;
    }
    let disposed = false;
    let retryTimer: number | undefined;

    const connect = async () => {
      if (disposed || !containerRef.current) return;
      setStatus('connecting');
      let RFB: typeof import('@novnc/novnc').default;
      try {
        ({ default: RFB } = await import('@novnc/novnc'));
      } catch {
        setStatus('error');
        if (!disposed) retryTimer = window.setTimeout(() => void connect(), 2000);
        return;
      }
      if (disposed || !containerRef.current) return;
      containerRef.current.innerHTML = '';
      const rfb = new RFB(containerRef.current, url, { wsProtocols: ['binary'] });
      rfb.scaleViewport = true;
      rfb.viewOnly = viewOnly;
      rfb.addEventListener('connect', () => setStatus('connected'));
      rfb.addEventListener('disconnect', (event) => {
        setStatus(event.detail?.clean ? 'closed' : 'error');
        rfbRef.current = null;
        if (!disposed) {
          retryTimer = window.setTimeout(() => void connect(), 2000);
        }
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
  }, [url, ready]);

  useEffect(() => {
    if (rfbRef.current) {
      rfbRef.current.viewOnly = viewOnly;
    }
  }, [viewOnly]);

  const overlay = !ready
    ? '沙箱尚未创建：先给 Agent 发一条消息（首轮会创建浏览器沙箱）'
    : status === 'connecting'
      ? '正在连接实时画面…'
      : status === 'error'
        ? '画面连接断开，正在重试…'
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
